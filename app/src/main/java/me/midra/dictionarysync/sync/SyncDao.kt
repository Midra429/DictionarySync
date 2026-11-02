package me.midra.dictionarysync.sync

import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState

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
    // The terminal PlanStates.
    @Query("SELECT * FROM sync_plans WHERE state NOT IN ('COMMITTED', 'CANCELLED', 'FAILED_PRESERVED') ORDER BY createdAt, id")
    fun observePendingPlans(): Flow<List<SyncPlan>>
    @Upsert suspend fun plan(plan: SyncPlan)
    @Query("SELECT * FROM plan_keys WHERE planId=:id") suspend fun planKeys(id: String): List<PlanKey>
    @Insert suspend fun planKeys(keys: List<PlanKey>)
    // Key sets of committed plans are never read again; plans, journal, bindings and references are kept.
    @Query("DELETE FROM plan_keys WHERE planId IN (SELECT id FROM sync_plans WHERE sourceId=:sourceId AND state='COMMITTED')")
    suspend fun deleteCommittedPlanKeys(sourceId: String)
    @Query("SELECT * FROM operation_journal WHERE planId=:id ORDER BY id") suspend fun operations(id: String): List<SyncOperation>
    @Query("SELECT COUNT(*) FROM operation_journal WHERE planId=:id AND kind=:kind") suspend fun operationCount(id: String, kind: OperationKind): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun operation(op: SyncOperation)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun operations(ops: List<SyncOperation>)
    @Query("SELECT id FROM sync_installation WHERE slot=1") suspend fun installation(): String?
    @Query("SELECT value FROM reference_generation WHERE slot=1") suspend fun referenceGeneration(): Long?
    @Insert suspend fun installation(value: SyncInstallation)
}

/**
 * The Room side of [SyncStore]. Plan transitions also update the source's [SourceEntity.syncState] and lifecycle in
 * the same transaction, so the list never shows a state the plans contradict.
 */
class RoomSyncStore(private val db: DictionaryDatabase, private val now: () -> Long = System::currentTimeMillis) : SyncStore {
    private val dao = db.sync()
    private val library = db.library()
    init { ensureReferenceTriggers(db) }

    override suspend fun referenceVersion() = "generation:" + (dao.referenceGeneration() ?: 0)
    override suspend fun installation() = dao.installation()
    override suspend fun installation(id: String) = dao.installation(SyncInstallation(id = id))
    override suspend fun keys() = dao.keys()
    // Chunked below SQLite's 999 bound-variable limit on older Android versions.
    override suspend fun keys(ids: Collection<String>) = ids.distinct().chunked(SyncEngine.BATCH).flatMap { dao.keysById(it) }
    override suspend fun bindings() = dao.bindings()
    override suspend fun bindings(keyIds: Collection<String>) = keyIds.distinct().chunked(SyncEngine.BATCH).flatMap { dao.bindingsById(it) }
    override suspend fun ownedCount() = dao.ownedCount()
    override suspend fun ownedCount(keyIds: Collection<String>) = keyIds.distinct().chunked(SyncEngine.BATCH).sumOf { dao.ownedCount(it) }
    override suspend fun binding(keyId: String) = dao.binding(keyId)
    override suspend fun references() = dao.references()
    override suspend fun references(sourceId: String) = dao.references(sourceId)
    override suspend fun sharedKeys(sourceId: String, keyIds: Collection<String>) =
        keyIds.distinct().chunked(SyncEngine.BATCH).flatMap { dao.referencedElsewhere(sourceId, it) }.toSet()
    override suspend fun plans() = dao.plans()
    override suspend fun planKeys(id: String) = dao.planKeys(id)
    override suspend fun operations(id: String) = dao.operations(id)
    override suspend fun binding(binding: ProviderBinding) = dao.binding(binding)
    override suspend fun operation(op: SyncOperation) = dao.operation(op)
    override suspend fun verified(binding: ProviderBinding, op: SyncOperation) = db.withTransaction { dao.binding(binding); dao.operation(op) }
    override suspend fun record(bindings: List<ProviderBinding>, ops: List<SyncOperation>) {
        if (bindings.isEmpty() && ops.isEmpty()) return
        db.withTransaction { dao.bindings(bindings); dao.operations(ops) }
    }
    override suspend fun sources() = library.sources().map { SyncSourceState(it.id, it.revision, it.activeSnapshot, it.lifecycle) }

    override suspend fun prepare(plan: SyncPlan, keys: List<SyncKey>, planKeys: List<PlanKey>) = db.withTransaction {
        validate(plan); dao.keys(keys); dao.plan(plan); dao.planKeys(planKeys)
        val deleting = plan.newSnapshot == null && !plan.retainSource
        updateSource(plan.sourceId) {
            it.copy(syncState = SyncState.PREPARED, lifecycle = if (deleting) SourceLifecycle.DELETE_PENDING else it.lifecycle)
        }
    }

    override suspend fun plan(plan: SyncPlan) = db.withTransaction {
        dao.plan(plan)
        if (plan.purpose.recovery && plan.state == PlanState.CANCELLED) {
            // A cancelled recovery returns its parent to the user's decision.
            dao.plan(parent(plan).copy(state = PlanState.RECOVERY_REQUIRED))
            updateSource(plan.sourceId, required = false) { it.copy(syncState = SyncState.RECOVERY_REQUIRED) }
        } else updateSource(plan.sourceId, required = false) {
            it.copy(syncState = plan.state.sourceState,
                lifecycle = if (plan.state == PlanState.CANCELLED) SourceLifecycle.ACTIVE else it.lifecycle)
        }
    }

    override suspend fun validate(plan: SyncPlan) {
        val source = library.source(plan.sourceId) ?: throw SyncStopped("SOURCE_MISSING")
        val candidateReplaced = plan.purpose == PlanPurpose.APPLY && plan.newSnapshot != null && !plan.approved &&
            source.latestSnapshot != plan.newSnapshot
        if (source.revision != plan.revision || source.activeSnapshot != plan.oldSnapshot || candidateReplaced) throw SyncStopped("PLAN_STALE")
        if (plan.purpose.recovery) {
            val context = RecoveryContext.decode(requireNotNull(plan.recoveryContext))
            // O(1) per boundary: a trigger-maintained generation instead of hashing every reference.
            val sources = sources()
            if (referenceVersion() != context.references || context.sources.any { pin -> sources.find { it.id == pin.id } != pin })
                throw SyncStopped("RECOVERY_STALE")
            val parent = parent(plan)
            if (parent.state.terminal || parent.purpose.recovery) throw SyncStopped("PLAN_CLOSED")
        }
    }

    override suspend fun prepareRecovery(plan: SyncPlan, keys: List<PlanKey>) = db.withTransaction {
        validate(plan)
        dao.plan(parent(plan).copy(state = PlanState.RECOVERING)); dao.plan(plan); dao.planKeys(keys)
        updateSource(plan.sourceId) { it.copy(syncState = SyncState.RECOVERING) }
    }

    override suspend fun activateRecovery(plan: SyncPlan, bindings: List<ProviderBinding>) = db.withTransaction {
        validate(plan)
        bindings.forEach { dao.binding(it) }; dao.plan(plan)
        updateSource(plan.sourceId) { it.copy(syncState = SyncState.RECOVERING) }
    }

    override suspend fun finishRecovery(plan: SyncPlan) = db.withTransaction {
        validate(plan)
        val parent = parent(plan)
        when (plan.purpose) {
            PlanPurpose.RESYNC -> commit(parent, dao.planKeys(parent.id).filter { it.desired }.map { it.keyId })
            PlanPurpose.REMOVE_SOURCE -> {
                dao.clearReferences(parent.sourceId)
                updateSource(parent.sourceId) {
                    it.copy(enabled = false, activeSnapshot = null, lifecycle = SourceLifecycle.DELETED, syncState = SyncState.REMOVED, syncError = null)
                }
                dao.plan(parent.copy(state = PlanState.FAILED_PRESERVED, finishedAt = now()))
                dao.releaseUnreferenced(now())
            }
            else -> {
                // No successful sync timestamp or snapshot is published for a failed attempt.
                updateSource(parent.sourceId) { it.copy(syncState = SyncState.FAILED_PRESERVED, lifecycle = SourceLifecycle.ACTIVE) }
                dao.plan(parent.copy(state = PlanState.FAILED_PRESERVED, finishedAt = now()))
                dao.releaseUnreferenced(now())
            }
        }
        dao.plan(plan.copy(state = PlanState.COMMITTED, error = null, finishedAt = now()))
    }

    override suspend fun commit(plan: SyncPlan, keys: List<String>) = db.withTransaction {
        validate(plan)
        val source = library.source(plan.sourceId)!!
        val oldKeys = dao.references(plan.sourceId).map { it.keyId }
        dao.clearReferences(plan.sourceId)
        if (plan.newSnapshot != null) dao.references(keys.map { SourceReference(plan.sourceId, plan.newSnapshot, it) })
        // Only this source's previous keys can lose their last reference here.
        oldKeys.chunked(SyncEngine.BATCH).forEach { chunk ->
            val remaining = dao.referenced(chunk).toSet()
            dao.bindings(dao.bindingsById(chunk.filter { it !in remaining })
                .filter { it.ownership == Ownership.BORROWED && it.conflict == null }
                .map { it.copy(ownership = Ownership.RELEASED, observedAt = now()) })
        }
        val removed = plan.newSnapshot == null
        library.updateSource(source.copy(activeSnapshot = plan.newSnapshot, syncedAt = now(), syncError = null,
            syncState = when {
                removed -> SyncState.REMOVED
                source.latestSnapshot != plan.newSnapshot -> SyncState.UPDATE_PENDING // A newer generation arrived meanwhile.
                else -> SyncState.SYNCED
            },
            lifecycle = if (removed && !plan.retainSource) SourceLifecycle.DELETED else SourceLifecycle.ACTIVE))
        dao.plan(plan.copy(state = PlanState.COMMITTED, error = null, finishedAt = now()))
    }

    private suspend fun parent(plan: SyncPlan) = dao.plans().single { it.id == plan.parentId }

    /** Updates the plan's source; with [required], a missing source is a programming error (validate checked it). */
    private suspend fun updateSource(id: String, required: Boolean = true, change: (SourceEntity) -> SourceEntity) {
        val source = library.source(id)
        if (source == null) { check(!required) { "SOURCE_MISSING" }; return }
        library.updateSource(change(source))
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
}
