package me.midra.dictionarysync.sync

import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.midra.dictionarysync.data.local.DictionaryDatabase

@Dao
interface SyncDao {
    @Query("SELECT * FROM entry_keys") suspend fun keys(): List<SyncKey>
    @Query("SELECT * FROM entry_keys WHERE id IN (:ids)") suspend fun keysById(ids: List<String>): List<SyncKey>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun keys(keys: List<SyncKey>)
    @Query("SELECT * FROM provider_bindings") suspend fun bindings(): List<ProviderBinding>
    @Query("SELECT * FROM provider_bindings WHERE keyId IN (:keyIds)") suspend fun bindingsById(keyIds: List<String>): List<ProviderBinding>
    @Query("SELECT COUNT(*) FROM provider_bindings WHERE ownership='OWNED'") suspend fun ownedCount(): Int
    @Query("SELECT COUNT(*) FROM provider_bindings WHERE ownership='OWNED' AND keyId IN (:keyIds)") suspend fun ownedCount(keyIds: List<String>): Int
    @Query("SELECT * FROM provider_bindings WHERE keyId=:keyId") suspend fun binding(keyId: String): ProviderBinding?
    // Whole-row replacement. @Upsert tries an INSERT and handles the constraint exception for every existing row,
    // which dominates the cost of journalling 100,000 words. Neither table is referenced by a foreign key.
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun binding(binding: ProviderBinding)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bindings(bindings: List<ProviderBinding>)
    @Query("SELECT * FROM source_references") suspend fun references(): List<SourceReference>
    @Query("SELECT * FROM source_references WHERE sourceId=:sourceId") suspend fun references(sourceId: String): List<SourceReference>
    @Query("SELECT DISTINCT keyId FROM source_references WHERE keyId IN (:keyIds)") suspend fun referenced(keyIds: List<String>): List<String>
    @Query("SELECT DISTINCT keyId FROM source_references WHERE sourceId != :sourceId AND keyId IN (:keyIds)")
    suspend fun referencedElsewhere(sourceId: String, keyIds: List<String>): List<String>
    @Insert suspend fun references(refs: List<SourceReference>)
    @Query("DELETE FROM source_references WHERE sourceId=:id") suspend fun clearReferences(id: String)
    /** Borrowed rows no source references any more are released; never deleted, since the app did not add them. */
    @Query("UPDATE provider_bindings SET ownership='RELEASED', observedAt=:now WHERE ownership='BORROWED' AND conflict IS NULL AND keyId NOT IN (SELECT keyId FROM source_references)")
    suspend fun releaseUnreferenced(now: Long)
    @Query("SELECT * FROM sync_plans ORDER BY createdAt, id") suspend fun plans(): List<SyncPlan>
    @Query("SELECT * FROM sync_plans WHERE state NOT IN ('COMMITTED', 'CANCELLED', 'FAILED_PRESERVED') ORDER BY createdAt, id")
    fun observePendingPlans(): Flow<List<SyncPlan>>
    @Upsert suspend fun plan(plan: SyncPlan)
    @Query("SELECT * FROM plan_keys WHERE planId=:id") suspend fun planKeys(id: String): List<PlanKey>
    @Insert suspend fun planKeys(keys: List<PlanKey>)
    // Key sets of committed plans are never read again; plans, journal, bindings and references are kept.
    @Query("DELETE FROM plan_keys WHERE planId IN (SELECT id FROM sync_plans WHERE sourceId=:sourceId AND state='COMMITTED')")
    suspend fun deleteCommittedPlanKeys(sourceId: String)
    @Query("SELECT * FROM operation_journal WHERE planId=:id ORDER BY id") suspend fun operations(id: String): List<SyncOperation>
    @Query("SELECT COUNT(*) FROM operation_journal WHERE planId=:id AND kind=:kind") suspend fun operationCount(id: String, kind: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun operation(op: SyncOperation)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun operations(ops: List<SyncOperation>)
    @Query("SELECT id FROM sync_installation WHERE slot=1") suspend fun installation(): String?
    @Query("SELECT value FROM reference_generation WHERE slot=1") suspend fun referenceGeneration(): Long?
    @Insert suspend fun installation(value: SyncInstallation)
}

class RoomSyncStore(private val db: DictionaryDatabase) : SyncStore {
    private val dao = db.sync()
    init { ensureReferenceTriggers(db) }
    override suspend fun referenceVersion() = "generation:" + (dao.referenceGeneration() ?: 0)
    override suspend fun installation() = dao.installation()
    override suspend fun installation(id: String) = dao.installation(SyncInstallation(id = id))
    override suspend fun keys() = dao.keys()
    // Chunked below SQLite's 999 bound-variable limit on older Android versions.
    override suspend fun keys(ids: Collection<String>) = ids.distinct().chunked(900).flatMap { dao.keysById(it) }
    override suspend fun bindings() = dao.bindings()
    override suspend fun bindings(keyIds: Collection<String>) = keyIds.distinct().chunked(900).flatMap { dao.bindingsById(it) }
    override suspend fun ownedCount() = dao.ownedCount()
    override suspend fun ownedCount(keyIds: Collection<String>) = keyIds.distinct().chunked(900).sumOf { dao.ownedCount(it) }
    override suspend fun binding(keyId: String) = dao.binding(keyId)
    override suspend fun references() = dao.references()
    override suspend fun references(sourceId: String) = dao.references(sourceId)
    override suspend fun sharedKeys(sourceId: String, keyIds: Collection<String>) =
        keyIds.distinct().chunked(900).flatMap { dao.referencedElsewhere(sourceId, it) }.toSet()
    override suspend fun plans() = dao.plans()
    override suspend fun planKeys(id: String) = dao.planKeys(id)
    override suspend fun operations(id: String) = dao.operations(id)
    override suspend fun prepare(plan: SyncPlan, keys: List<SyncKey>, planKeys: List<PlanKey>) = db.withTransaction {
        validate(plan); dao.keys(keys); dao.plan(plan); dao.planKeys(planKeys)
        val source = db.library().source(plan.sourceId)!!
        db.library().updateSource(source.copy(syncState = "PREPARED", lifecycle = if (plan.newSnapshot == null && !plan.retainSource) "DELETE_PENDING" else source.lifecycle))
    }
    override suspend fun plan(plan: SyncPlan) = db.withTransaction {
        dao.plan(plan)
        if (plan.purpose != "APPLY" && plan.state == "CANCELLED") {
            val parent = dao.plans().single { it.id == plan.parentId }
            dao.plan(parent.copy(state = "RECOVERY_REQUIRED"))
            db.library().source(plan.sourceId)?.let { db.library().updateSource(it.copy(syncState = "RECOVERY_REQUIRED")) }
            return@withTransaction
        }
        db.library().source(plan.sourceId)?.let { db.library().updateSource(it.copy(syncState = plan.state, lifecycle = if (plan.state == "CANCELLED") "ACTIVE" else it.lifecycle)) }
        Unit
    }
    override suspend fun binding(binding: ProviderBinding) = dao.binding(binding)
    override suspend fun operation(op: SyncOperation) = dao.operation(op)
    override suspend fun verified(binding: ProviderBinding, op: SyncOperation) = db.withTransaction { dao.binding(binding); dao.operation(op) }
    override suspend fun record(bindings: List<ProviderBinding>, ops: List<SyncOperation>) {
        if (bindings.isEmpty() && ops.isEmpty()) return
        db.withTransaction { dao.bindings(bindings); dao.operations(ops) }
    }
    override suspend fun validate(plan: SyncPlan) {
        val source = db.library().source(plan.sourceId) ?: throw SyncStopped("SOURCE_MISSING")
        if (source.revision != plan.revision || source.activeSnapshot != plan.oldSnapshot ||
            (plan.purpose == "APPLY" && plan.newSnapshot != null && !plan.approved && source.latestSnapshot != plan.newSnapshot)) throw SyncStopped("PLAN_STALE")
        if (plan.purpose != "APPLY") {
            val context = RecoveryContext.decode(requireNotNull(plan.recoveryContext))
            // O(1) per boundary: a trigger-maintained generation instead of hashing every reference.
            if (referenceVersion() != context.references ||
                context.sources.any { pin -> sources().find { it.id == pin.id } != pin }) throw SyncStopped("RECOVERY_STALE")
            val parent = dao.plans().single { it.id == plan.parentId }
            if (parent.state in SyncEngine.TERMINAL || parent.purpose != "APPLY") throw SyncStopped("PLAN_CLOSED")
        }
    }
    override suspend fun sources() = db.library().sources().map { SyncSourceState(it.id, it.revision, it.activeSnapshot, it.lifecycle) }
    override suspend fun prepareRecovery(plan: SyncPlan, keys: List<PlanKey>) = db.withTransaction {
        validate(plan)
        val parent = dao.plans().single { it.id == plan.parentId }
        dao.plan(parent.copy(state = "RECOVERING")); dao.plan(plan); dao.planKeys(keys)
        val source = db.library().source(plan.sourceId)!!
        db.library().updateSource(source.copy(syncState = "RECOVERING"))
    }
    override suspend fun activateRecovery(plan: SyncPlan, bindings: List<ProviderBinding>) = db.withTransaction {
        validate(plan)
        bindings.forEach { dao.binding(it) }; dao.plan(plan)
        val source = db.library().source(plan.sourceId)!!
        db.library().updateSource(source.copy(syncState = "RECOVERING"))
    }
    override suspend fun finishRecovery(plan: SyncPlan) = db.withTransaction {
        validate(plan)
        val parent = dao.plans().single { it.id == plan.parentId }
        if (plan.purpose == "RESYNC") {
            commit(parent, dao.planKeys(parent.id).filter { it.desired }.map { it.keyId })
        } else if (plan.purpose == "REMOVE_SOURCE") {
            val source = db.library().source(parent.sourceId)!!
            dao.clearReferences(parent.sourceId)
            db.library().updateSource(source.copy(enabled = false, activeSnapshot = null,
                lifecycle = "DELETED", syncState = "REMOVED", syncError = null))
            dao.plan(parent.copy(state = "FAILED_PRESERVED", finishedAt = System.currentTimeMillis()))
            dao.releaseUnreferenced(System.currentTimeMillis())
        } else {
            val source = db.library().source(parent.sourceId)!!
            // No successful sync timestamp or snapshot is published for a failed attempt.
            db.library().updateSource(source.copy(syncState = "FAILED_PRESERVED", lifecycle = "ACTIVE"))
            dao.plan(parent.copy(state = "FAILED_PRESERVED", finishedAt = System.currentTimeMillis()))
            dao.releaseUnreferenced(System.currentTimeMillis())
        }
        dao.plan(plan.copy(state = "COMMITTED", error = null, finishedAt = System.currentTimeMillis()))
    }
    companion object {
        private val prepared = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DictionaryDatabase, Boolean>())
        /** Triggers persist in the database file; IF NOT EXISTS makes this a no-op after the first open. */
        fun ensureReferenceTriggers(db: DictionaryDatabase) = synchronized(prepared) {
            if (!prepared.add(db)) return@synchronized
            val sql = db.openHelper.writableDatabase
            for (event in listOf("INSERT", "UPDATE", "DELETE")) sql.execSQL(
                "CREATE TRIGGER IF NOT EXISTS reference_generation_${event.lowercase()} AFTER $event ON source_references BEGIN " +
                    // Not INSERT OR IGNORE: inside a trigger the outer statement's conflict policy (Room's ABORT) would apply.
                    "INSERT INTO reference_generation(slot, value) SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM reference_generation WHERE slot = 1); " +
                    "UPDATE reference_generation SET value = value + 1 WHERE slot = 1; END")
        }
    }
    override suspend fun commit(plan: SyncPlan, keys: List<String>) = db.withTransaction {
        validate(plan)
        val source = db.library().source(plan.sourceId)!!
        val oldKeys = dao.references(plan.sourceId).map { it.keyId }
        dao.clearReferences(plan.sourceId)
        if (plan.newSnapshot != null) dao.references(keys.map { SourceReference(plan.sourceId, plan.newSnapshot, it) })
        // Only this source's previous keys can lose their last reference here.
        oldKeys.chunked(900).forEach { chunk ->
            val remaining = dao.referenced(chunk).toSet()
            dao.bindings(dao.bindingsById(chunk.filter { it !in remaining }).filter { it.ownership == "BORROWED" && it.conflict == null }
                .map { it.copy(ownership = "RELEASED", observedAt = System.currentTimeMillis()) })
        }
        db.library().updateSource(source.copy(activeSnapshot = plan.newSnapshot, syncedAt = System.currentTimeMillis(),
            syncState = when {
                plan.newSnapshot == null -> "REMOVED"
                source.latestSnapshot != plan.newSnapshot -> "UPDATE_PENDING"
                else -> "SYNCED"
            }, syncError = null,
            lifecycle = if (plan.newSnapshot == null && !plan.retainSource) "DELETED" else "ACTIVE"))
        dao.plan(plan.copy(state = "COMMITTED", error = null, finishedAt = System.currentTimeMillis()))
    }
}
