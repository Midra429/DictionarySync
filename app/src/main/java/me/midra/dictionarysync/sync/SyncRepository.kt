package me.midra.dictionarysync.sync

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity

class SyncRepository(private val context: Context, private val db: DictionaryDatabase = DictionaryDatabase.get(context),
    private val engineFactory: () -> SyncEngine = { SyncEngine(RoomSyncStore(db), AndroidSyncGateway(context), SyncCoordinator.installation(context)) }) {
    private fun engine() = engineFactory()

    private suspend fun prepareLocked(source: SourceEntity, deleting: Boolean = false): SyncPreview = engine().preview(planLocked(source, deleting))
    /** The plan alone; a sync that applies it right away never builds the preview a review screen shows. */
    private suspend fun planLocked(source: SourceEntity, deleting: Boolean = false): SyncPlan {
        val removing = deleting || !source.enabled
        val snapshot = if (removing) null else source.latestSnapshot?.let { db.library().snapshot(it) } ?: throw SyncStopped("FETCH_REQUIRED")
        if (snapshot != null && snapshot.revision != source.revision) throw SyncStopped("REFETCH_SETTINGS_CHANGED")
        val desired = if (snapshot == null) emptyList() else db.library().entries(snapshot.id).map {
            SyncKey.of(it.locale, it.word, it.reading)
        }
        return engine().plan(source.id, source.revision, source.activeSnapshot, snapshot?.id, snapshot?.bodyHash ?: "REMOVE",
            desired, retainSource = removing && !deleting)
    }
    suspend fun prepare(sourceId: String, deleting: Boolean = false): SyncPreview = SyncCoordinator.locked(context) {
        prepareLocked(db.library().source(sourceId) ?: throw SyncStopped("SOURCE_MISSING"), deleting)
    }

    /** A normal sync uses the existing journal/ownership engine without a per-update approval UI. */
    suspend fun synchronize(id: String, deleting: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        synchronizeLocked(id, deleting)
    }
    private suspend fun synchronizeLocked(id: String, deleting: Boolean = false): Boolean {
        try {
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            val removingSource = deleting || source.lifecycle == "DELETE_PENDING"
            val pending = db.sync().plans().filter { it.state !in SyncEngine.TERMINAL }
            // Resume only this source's ordinary plan. Recovery decisions remain explicit.
            val resumable = pending.singleOrNull()?.takeIf {
                it.sourceId == id && it.purpose == "APPLY" && it.state != "RECOVERY_REQUIRED" &&
                    (source.enabled || it.newSnapshot == null) && (!removingSource || (it.newSnapshot == null && !it.retainSource))
            }
            if (pending.isNotEmpty() && resumable == null) throw SyncStopped("RECOVER_PLAN_FIRST")
            if (resumable != null) {
                engine().apply(resumable.id, resumable.token)
            } else if ((!source.enabled || removingSource) && source.activeSnapshot == null && db.sync().references(id).isEmpty()) {
                // Nothing was ever applied: no Provider access is necessary to disable/delete.
                db.library().updateSource(source.copy(syncState = "REMOVED", syncError = null,
                    lifecycle = if (removingSource) "DELETED" else source.lifecycle))
            } else {
                val plan = planLocked(source, removingSource)
                engine().apply(plan.id, plan.token)
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            db.library().source(id)?.let { current ->
                db.library().updateSource(current.copy(syncState = if (current.syncState == "RECOVERY_REQUIRED") current.syncState else "SYNC_FAILED",
                    syncError = (error as? SyncStopped)?.code ?: error.javaClass.simpleName))
            }
            return false
        }
    }

    /** Desired state is durable even when releasing Provider references cannot finish yet. */
    /** Whether anything of this source may be in the Provider, so releasing it needs Provider access. */
    private suspend fun applied(id: String) = db.library().source(id)?.activeSnapshot != null || db.sync().references(id).isNotEmpty()

    /**
     * Stores the desired state. With [background], the Provider write is left to the apply Worker (the caller enqueues
     * it); a disable that has nothing in the Provider still completes immediately because it needs no Provider access.
     */
    suspend fun setEnabled(id: String, enabled: Boolean, background: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        if (db.sync().plans().any { it.state !in SyncEngine.TERMINAL }) throw SyncStopped("RECOVER_PLAN_FIRST")
        db.withTransaction {
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            if (source.lifecycle != "ACTIVE") throw SyncStopped("SOURCE_DELETE_PENDING")
            db.library().updateSource(source.copy(enabled = enabled,
                syncState = if (enabled) "UPDATE_PENDING" else "REMOVE_PENDING", syncError = null))
            if (!enabled) db.requests().request(id)?.let { PendingRequests(db).finish(it, "SOURCE_DISABLED") }
        }
        if (!background || (!enabled && !applied(id))) synchronizeLocked(id) else true
    }

    /**
     * Deletes a source. A pending plan of this source is resolved here (cancelled, or removed with the user's
     * confirmation of the delete). Otherwise, with [background], only the desired deletion is stored unless nothing
     * is in the Provider.
     */
    suspend fun delete(id: String, background: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        val pending = db.sync().plans().filter { it.state !in SyncEngine.TERMINAL }
        val current = pending.singleOrNull()?.takeIf { it.sourceId == id && it.purpose == "APPLY" }
        if (current != null && (current.newSnapshot != null || current.retainSource || current.state == "RECOVERY_REQUIRED")) {
            if (!current.approved) {
                engine().cancel(current.id)
            } else {
                val recovery = engine().prepareRecovery(current.id, "REMOVE_SOURCE")
                engine().apply(recovery.plan.id, recovery.plan.token)
                db.requests().request(id)?.let { PendingRequests(db).finish(it, "SOURCE_REMOVED") }
                return@locked true
            }
        }
        val removal = pending.singleOrNull { it.sourceId == id && it.purpose == "REMOVE_SOURCE" }
        if (removal != null && pending.all { it.id == removal.id || it.id == removal.parentId }) {
            engine().apply(removal.id, removal.token)
            db.requests().request(id)?.let { PendingRequests(db).finish(it, "SOURCE_REMOVED") }
            return@locked true
        }
        if (db.sync().plans().any { it.state !in SyncEngine.TERMINAL && (it.sourceId != id || it.newSnapshot != null || it.retainSource) }) throw SyncStopped("RECOVER_PLAN_FIRST")
        db.withTransaction {
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            db.library().updateSource(source.copy(enabled = false, lifecycle = "DELETE_PENDING", syncState = "REMOVE_PENDING"))
            db.requests().request(id)?.let { PendingRequests(db).finish(it, "SOURCE_REMOVED") }
        }
        if (!background || !applied(id)) synchronizeLocked(id, deleting = true) else true
    }

    /** Puts a source whose shared-dictionary write failed back in line for the apply Worker. */
    suspend fun retry(id: String) = SyncCoordinator.locked(context) {
        if (db.sync().plans().any { it.state !in SyncEngine.TERMINAL }) throw SyncStopped("RECOVER_PLAN_FIRST")
        db.withTransaction {
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            val removing = !source.enabled || source.lifecycle == "DELETE_PENDING"
            db.library().updateSource(source.copy(syncState = if (removing) "REMOVE_PENDING" else "UPDATE_PENDING", syncError = null))
        }
    }
    suspend fun apply(id: String, token: String?) = SyncCoordinator.locked(context) { engine().apply(id, token) }
    suspend fun cancel(id: String) = SyncCoordinator.locked(context) { engine().cancel(id) }
    suspend fun prepareRecovery(id: String, purpose: String) = SyncCoordinator.locked(context) { engine().prepareRecovery(id, purpose) }
    suspend fun pending(): SyncPreview? = SyncCoordinator.locked(context) {
        val plans = db.sync().plans().filter { it.state !in SyncEngine.TERMINAL }
        plans.firstOrNull { plan -> plans.none { it.parentId == plan.id } }?.let { engine().preview(it) }
    }
}
