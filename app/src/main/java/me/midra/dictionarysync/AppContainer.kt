package me.midra.dictionarysync

import android.app.Application
import android.content.Context
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.Preferences
import me.midra.dictionarysync.data.source.DictionaryDownload
import me.midra.dictionarysync.library.SnapshotStore
import me.midra.dictionarysync.library.SourceEditor
import me.midra.dictionarysync.library.SourceFetcher
import me.midra.dictionarysync.sync.AndroidSyncGateway
import me.midra.dictionarysync.sync.ApplyQueue
import me.midra.dictionarysync.sync.FetchDispatch
import me.midra.dictionarysync.sync.FetchRunner
import me.midra.dictionarysync.sync.FetchScheduler
import me.midra.dictionarysync.sync.FetchWork
import me.midra.dictionarysync.sync.PendingRequests
import me.midra.dictionarysync.sync.RoomSyncStore
import me.midra.dictionarysync.sync.SourceSync
import me.midra.dictionarysync.sync.SyncCoordinator
import me.midra.dictionarysync.sync.SyncEngine
import me.midra.dictionarysync.sync.SyncEvents
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.notification.SyncNotifier

/**
 * The app's object graph, wired by hand (no DI framework). One instance per process, from [DictionarySyncApplication];
 * tests build their own with an in-memory database, a fake download and a fake Provider.
 *
 * - [editor] adds and edits sources; [fetcher] fetches them; [snapshots] holds their generations.
 * - [sourceSync] owns each source's desired shared-dictionary state; [applies] brings it to the Provider.
 * - [scheduler] and [dispatch] turn durable requests into WorkManager work.
 */
class AppContainer(
    context: Context,
    val database: DictionaryDatabase = DictionaryDatabase.get(context),
    /** The clock of every component the container builds, including the sync engine and the periodic check. */
    val now: () -> Long = System::currentTimeMillis,
    download: DictionaryDownload = DictionaryDownload.android(context),
    /** A new engine per operation: the Android gateway reads the diagnosis once per instance. */
    engine: (() -> SyncEngine)? = null,
    /** Replaces the shared-dictionary side as a whole (tests share one with other containers or fixtures). */
    sourceSync: SourceSync? = null,
    dispatch: FetchDispatch? = null,
    /** Whether shared-dictionary writes can run now; read-only. */
    val readiness: suspend () -> SyncReadiness = { AndroidSyncGateway(context.applicationContext).readiness() },
    events: SyncEvents? = null,
) {
    private val context = context.applicationContext

    val preferences = Preferences(this.context)
    val requests = PendingRequests(database)
    val snapshots = SnapshotStore(database)
    val editor = SourceEditor(this.context, database, preferences, snapshots, download, now)
    val fetcher = SourceFetcher(this.context, database, preferences, requests, snapshots, download, now)
    val sourceSync = sourceSync ?: SourceSync(this.context, database, requests, engine ?: {
        SyncEngine(RoomSyncStore(database, now), AndroidSyncGateway(this.context), SyncCoordinator.installation(this.context), now)
    })
    val applies = ApplyQueue(database, this.sourceSync)

    // WorkManager and notification channels are touched only when first used, so tests that never schedule work do not.
    val dispatch: FetchDispatch by lazy { dispatch ?: FetchWork(this.context) }
    val events: SyncEvents by lazy { events ?: SyncNotifier(this.context) }
    val scheduler by lazy { FetchScheduler(this.context, database, requests, applies, this.dispatch) }
    val fetchRunner by lazy { FetchRunner(database, fetcher, requests, applies, now) }
}

class DictionarySyncApplication : Application() {
    val container by lazy { AppContainer(this) }
}

/** The process's [AppContainer]. */
val Context.appContainer get() = (applicationContext as DictionarySyncApplication).container
