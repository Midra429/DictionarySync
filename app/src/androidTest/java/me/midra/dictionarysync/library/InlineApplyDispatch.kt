package me.midra.dictionarysync.library

import java.util.concurrent.atomic.AtomicInteger
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.sync.ApplyRunner
import me.midra.dictionarysync.sync.FetchDispatch

/** Runs the apply chain inline, as ApplyWorker would, so screen tests can observe Provider outcomes. Fetch scheduling is ignored. */
class InlineApplyDispatch(private val library: () -> LibraryRepository) : FetchDispatch {
    val applies = AtomicInteger()
    override suspend fun enqueue() {}
    override suspend fun applyNow() { applies.incrementAndGet(); while (ApplyRunner(library()).run()) Unit }
    override suspend fun wakeAfter(delayMillis: Long) {}
    override suspend fun cancelWake() {}
    override suspend fun sources(sources: List<SourceEntity>) {}
}
