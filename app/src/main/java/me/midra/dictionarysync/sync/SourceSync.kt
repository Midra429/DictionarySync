package me.midra.dictionarysync.sync

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.data.source.MemoryBudget

/**
 * A source's place in the shared dictionary: its desired state (enabled, disabled, deleted) and the plans that bring
 * the Provider there. Every operation holds the sync lock; Provider writes go through [SyncEngine] only.
 */
class SourceSync(private val context: Context, private val db: DictionaryDatabase,
    private val requests: PendingRequests = PendingRequests(db), private val engine: () -> SyncEngine) {
    private val library = db.library()

    private suspend fun unfinishedPlans() = db.sync().plans().filter { !it.state.terminal }
    private suspend fun requireNoPlan() { if (unfinishedPlans().isNotEmpty()) throw SyncStopped("RECOVER_PLAN_FIRST") }
    private suspend fun source(id: String) = library.source(id) ?: throw SyncStopped("SOURCE_MISSING")
    /** Whether anything of this source may be in the Provider, so releasing it needs Provider access. */
    private suspend fun applied(id: String) = library.source(id)?.activeSnapshot != null || db.sync().references(id).isNotEmpty()

    /** A preview of syncing [sourceId] (or of deleting it), for review before applying. */
    suspend fun prepare(sourceId: String, deleting: Boolean = false): SyncPreview = SyncCoordinator.locked(context) {
        engine().preview(plan(source(sourceId), deleting))
    }

    /** The plan that brings [source]'s desired state to the Provider; a sync that applies it at once needs no preview. */
    private suspend fun plan(source: SourceEntity, deleting: Boolean): SyncPlan {
        val removing = deleting || !source.enabled
        val snapshot = if (removing) null else source.latestSnapshot?.let { library.snapshot(it) } ?: throw SyncStopped("FETCH_REQUIRED")
        if (snapshot != null && snapshot.revision != source.revision) throw SyncStopped("REFETCH_SETTINGS_CHANGED")
        // Checked before the entries are loaded: the engine checks the whole managed set once the keys exist.
        if (snapshot != null && snapshot.count > MemoryBudget().maxKeys) throw SyncStopped("KEY_LIMIT")
        val desired = if (snapshot == null) emptyList() else library.entries(snapshot.id).map { SyncKey.of(it.locale, it.word, it.reading) }
        return engine().plan(source.id, source.revision, source.activeSnapshot, snapshot?.id, snapshot?.bodyHash ?: "REMOVE",
            desired, retainSource = removing && !deleting)
    }

    /**
     * Brings one source's desired state to the Provider without a review: resumes its interrupted plan, or plans and
     * applies anew. Returns false, with the reason on the source, when it could not.
     */
    suspend fun synchronize(id: String, deleting: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        synchronizeLocked(id, deleting)
    }

    private suspend fun synchronizeLocked(id: String, deleting: Boolean = false): Boolean {
        try {
            val source = source(id)
            val removingSource = deleting || source.lifecycle == SourceLifecycle.DELETE_PENDING
            val pending = unfinishedPlans()
            // Resume only this source's ordinary plan. Recovery decisions remain explicit.
            val resumable = pending.singleOrNull()?.takeIf {
                it.sourceId == id && it.purpose == PlanPurpose.APPLY && it.state != PlanState.RECOVERY_REQUIRED &&
                    (source.enabled || it.newSnapshot == null) && (!removingSource || (it.newSnapshot == null && !it.retainSource))
            }
            if (pending.isNotEmpty() && resumable == null) throw SyncStopped("RECOVER_PLAN_FIRST")
            when {
                resumable != null -> engine().apply(resumable.id, resumable.token)
                (!source.enabled || removingSource) && !applied(id) ->
                    // Nothing was ever applied: no Provider access is necessary to disable or delete.
                    library.updateSource(source.copy(syncState = SyncState.REMOVED, syncError = null,
                        lifecycle = if (removingSource) SourceLifecycle.DELETED else source.lifecycle))
                else -> plan(source, removingSource).let { engine().apply(it.id, it.token) }
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            library.source(id)?.let { current ->
                library.updateSource(current.copy(syncError = SyncStopped.code(error),
                    syncState = if (current.syncState == SyncState.RECOVERY_REQUIRED) current.syncState else SyncState.SYNC_FAILED))
            }
            return false
        }
    }

    /**
     * Stores the desired state. With [background], the Provider write is left to the apply chain (the caller enqueues
     * it); a disable that has nothing in the Provider still completes at once, since it needs no Provider access.
     */
    suspend fun setEnabled(id: String, enabled: Boolean, background: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        requireNoPlan()
        db.withTransaction {
            val source = source(id)
            if (!source.listed) throw SyncStopped("SOURCE_DELETE_PENDING")
            library.updateSource(source.copy(enabled = enabled, syncError = null,
                syncState = if (enabled) SyncState.UPDATE_PENDING else SyncState.REMOVE_PENDING))
            if (!enabled) requests.request(id)?.let { requests.finish(it, FetchOutcome.SOURCE_DISABLED) }
        }
        if (!background || (!enabled && !applied(id))) synchronizeLocked(id) else true
    }

    /**
     * Deletes a source. Its own unfinished plan is resolved here: cancelled if never approved, otherwise removed through
     * a recovery, which the delete confirmation consents to. Otherwise, with [background], only the desired deletion is
     * stored unless nothing is in the Provider.
     */
    suspend fun delete(id: String, background: Boolean = false): Boolean = SyncCoordinator.locked(context) {
        val pending = unfinishedPlans()
        val current = pending.singleOrNull()?.takeIf { it.sourceId == id && it.purpose == PlanPurpose.APPLY }
        if (current != null && (current.newSnapshot != null || current.retainSource || current.state == PlanState.RECOVERY_REQUIRED)) {
            if (!current.approved) engine().cancel(current.id)
            else {
                val recovery = engine().prepareRecovery(current.id, PlanPurpose.REMOVE_SOURCE)
                engine().apply(recovery.plan.id, recovery.plan.token)
                finishRequest(id)
                return@locked true
            }
        }
        val removal = pending.singleOrNull { it.sourceId == id && it.purpose == PlanPurpose.REMOVE_SOURCE }
        if (removal != null && pending.all { it.id == removal.id || it.id == removal.parentId }) {
            engine().apply(removal.id, removal.token)
            finishRequest(id)
            return@locked true
        }
        if (unfinishedPlans().any { it.sourceId != id || it.newSnapshot != null || it.retainSource }) throw SyncStopped("RECOVER_PLAN_FIRST")
        db.withTransaction {
            library.updateSource(source(id).copy(enabled = false, lifecycle = SourceLifecycle.DELETE_PENDING, syncState = SyncState.REMOVE_PENDING))
            finishRequest(id)
        }
        if (!background || !applied(id)) synchronizeLocked(id, deleting = true) else true
    }

    private suspend fun finishRequest(id: String) {
        requests.request(id)?.let { requests.finish(it, FetchOutcome.SOURCE_REMOVED) }
    }

    /** Puts a source whose shared-dictionary write failed back in line for the apply chain. */
    suspend fun retry(id: String) = SyncCoordinator.locked(context) {
        requireNoPlan()
        db.withTransaction {
            val source = source(id)
            val removing = !source.enabled || source.lifecycle == SourceLifecycle.DELETE_PENDING
            library.updateSource(source.copy(syncState = if (removing) SyncState.REMOVE_PENDING else SyncState.UPDATE_PENDING, syncError = null))
        }
    }

    /** Applies a reviewed plan; [token] binds the approval to the previewed candidate. */
    suspend fun apply(id: String, token: String?) = SyncCoordinator.locked(context) { engine().apply(id, token) }
    suspend fun cancel(id: String) = SyncCoordinator.locked(context) { engine().cancel(id) }
    suspend fun prepareRecovery(id: String, purpose: PlanPurpose) = SyncCoordinator.locked(context) { engine().prepareRecovery(id, purpose) }

    /** The unfinished plan the user decides on: the innermost one, as a recovery stands in for its parent. */
    suspend fun pending(): SyncPreview? = SyncCoordinator.locked(context) {
        val plans = unfinishedPlans()
        plans.firstOrNull { plan -> plans.none { it.parentId == plan.id } }?.let { engine().preview(it) }
    }
}
