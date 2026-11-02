package me.midra.dictionarysync.ui.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.sync.PlanPurpose
import me.midra.dictionarysync.sync.SyncPreview
import me.midra.dictionarysync.ui.common.Operations

/**
 * A shared-dictionary write that stopped and waits for the user: resuming, finishing or undoing it through a recovery,
 * or cancelling it. These run on screen because they rest on the user's consent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncReviewState(scope: CoroutineScope, private val app: AppContainer, private val operations: Operations) {
    /** The unfinished plan, if any. A failed evaluation keeps the last value and reports once; later changes still show. */
    val pending: StateFlow<SyncPreview?> = app.database.sync().observePendingPlans().distinctUntilChanged()
        .transformLatest {
            try { emit(app.sourceSync.pending()) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operations.report(failure)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    private val shown = MutableStateFlow<SyncPreview?>(null)
    /** The plan the review dialog shows. */
    val preview: StateFlow<SyncPreview?> = shown.asStateFlow()

    fun open() { shown.value = pending.value }
    fun dismiss() { shown.value = null }

    /** Replaces the shown plan with a recovery of it, for review before it is applied. */
    fun prepareRecovery(purpose: PlanPurpose) = operations.run {
        val chosen = shown.value ?: return@run
        shown.value = app.sourceSync.prepareRecovery(chosen.plan.id, purpose)
    }

    fun cancel() = operations.run {
        shown.value?.let { app.sourceSync.cancel(it.plan.id) }
        shown.value = null
    }

    fun apply() = operations.run {
        val chosen = shown.value ?: return@run
        shown.value = null
        try { app.sourceSync.apply(chosen.plan.id, chosen.plan.token) }
        finally { app.scheduler.repair() }
    }
}
