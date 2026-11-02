package me.midra.dictionarysync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.DictionaryDownload
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.library.SourceDraft
import me.midra.dictionarysync.library.SourceEditor
import me.midra.dictionarysync.sync.AndroidSyncGateway
import me.midra.dictionarysync.sync.FetchDispatch
import me.midra.dictionarysync.sync.FetchReason
import me.midra.dictionarysync.sync.SourceSync
import me.midra.dictionarysync.sync.SyncEvents
import me.midra.dictionarysync.sync.SyncReadiness

/** Schedules nothing: storage tests drive fetches and applies themselves. */
object NoDispatch : FetchDispatch {
    override suspend fun enqueue() {}
    override suspend fun wakeAfter(delayMillis: Long) {}
    override suspend fun cancelWake() {}
    override suspend fun sources(sources: List<SourceEntity>) {}
}

/**
 * An [AppContainer] for tests: [db] (usually in memory), the given [download], no WorkManager and no notifications.
 * Without [sync], shared-dictionary writes go to the real Provider gateway, which refuses them unless diagnosed.
 */
fun testApp(context: Context, db: DictionaryDatabase = DictionaryDatabase.get(context), now: () -> Long = System::currentTimeMillis,
    sync: SourceSync? = null, dispatch: FetchDispatch = NoDispatch, readiness: (suspend () -> SyncReadiness)? = null,
    download: DictionaryDownload = DictionaryDownload.android(context)) =
    AppContainer(context, db, now, download, sourceSync = sync, dispatch = dispatch, events = SyncEvents.None,
        readiness = readiness ?: { AndroidSyncGateway(context.applicationContext).readiness() })

/** [SourceEditor.save] with the form's fields in order. */
suspend fun SourceEditor.save(id: String?, name: String, url: String, locale: String = "AUTO", format: String = "AUTO",
    encoding: String = "AUTO", syncIntervalHours: Int = 24) = save(id, SourceDraft(name, url, locale, format, encoding, syncIntervalHours))

/** [SourceEditor.seed] with the form's fields in order. */
suspend fun SourceEditor.seed(id: String?, name: String, url: String, locale: String = "AUTO", format: String = "AUTO",
    encoding: String = "AUTO") = seed(id, SourceDraft(name, url, locale, format, encoding))

/** Requests and immediately executes a manual fetch of one source, without the fetch chain. */
suspend fun AppContainer.fetch(id: String) = withContext(Dispatchers.IO) {
    fetcher.execute(requests.submit(listOf(id), FetchReason.MANUAL).single())
}

/** Requests every syncable source and fetches each in turn, without the fetch chain. */
suspend fun AppContainer.fetchAll(): List<SourceEntity> = withContext(Dispatchers.IO) {
    val batch = scheduler.submitAll()
    for (request in batch) {
        currentCoroutineContext().ensureActive()
        fetcher.execute(request)
    }
    batch.mapNotNull { database.library().source(it.sourceId) }
}

/** Executes every due request after a restart, as the fetch chain would one by one. */
suspend fun AppContainer.resumePendingFetches() {
    fetcher.markInterrupted()
    for (request in requests.ready(now())) {
        currentCoroutineContext().ensureActive()
        fetcher.execute(request)
    }
}
