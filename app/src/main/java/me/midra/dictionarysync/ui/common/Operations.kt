package me.midra.dictionarysync.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.sync.SyncStopped

/**
 * The user's operations, one at a time across every screen: while one runs, [busy] disables the others. A failure
 * becomes [error], an internal code the UI explains.
 *
 * [onAbandoned] runs when an operation is cancelled because the screen went away. Desired states and journalled plans
 * are durable, so it hands the remaining shared-dictionary work to the background instead of waiting for a relaunch.
 */
class Operations(private val scope: CoroutineScope, private val onAbandoned: suspend () -> Unit) {
    private val running = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    val busy: StateFlow<Boolean> = running.asStateFlow()
    val error: StateFlow<String?> = failure.asStateFlow()

    fun dismissError() { failure.value = null }
    fun report(error: Exception) { failure.value = code(error) }

    /** Starts [block] unless another operation is running; returns its job, or null if it was not started. */
    fun run(block: suspend () -> Unit): Job? {
        if (running.value) return null
        running.value = true
        return scope.launch {
            try { block() }
            catch (exception: Exception) {
                if (exception is CancellationException) {
                    withContext(NonCancellable) {
                        // Best effort: if scheduling fails here, the startup and Worker repair passes enqueue it later.
                        try { onAbandoned() } catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                    throw exception
                }
                report(exception)
            } finally { running.value = false }
        }
    }

    companion object {
        /** The internal code of a failure: a sync or dictionary problem's own, otherwise the exception's class. */
        fun code(failure: Throwable) = (failure as? DictionaryProblem)?.code ?: SyncStopped.code(failure)
    }
}
