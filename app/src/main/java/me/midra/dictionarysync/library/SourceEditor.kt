package me.midra.dictionarysync.library

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.Preferences
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.DictionaryDownload
import me.midra.dictionarysync.sync.SyncCoordinator
import me.midra.dictionarysync.sync.SyncStopped

/**
 * Adds and edits sources. A new source, or new fetch settings, are stored only together with a generation fetched
 * with them, in one transaction: no provisional source, request or schedule exists before that.
 */
class SourceEditor(
    private val context: Context,
    private val db: DictionaryDatabase,
    private val preferences: Preferences,
    private val snapshots: SnapshotStore,
    private val download: DictionaryDownload,
    private val now: () -> Long,
) {
    private val dao = db.library()

    /** Adds a source ([id] null) or edits one. Returns its ID; the shared dictionary is updated later by the apply chain. */
    suspend fun save(id: String?, draft: SourceDraft): String = withContext(Dispatchers.IO) {
        SyncCoordinator.locked(context) {
            val settings = draft.validated()
            val old = checkSavable(id, settings)
            val candidate = if (old == null || settings.changesFetch(old)) fetch(old, settings) else null
            db.withTransaction {
                val source = write(old, settings)
                if (candidate == null) return@withTransaction source.id
                val fetchedAt = now()
                val snapshot = snapshots.add(source, candidate, fetchedAt)
                dao.updateSource(source.copy(latestSnapshot = snapshot.id, previousSnapshot = source.latestSnapshot,
                    fetchedAt = fetchedAt, changedAt = fetchedAt, state = FetchState.FETCHED, error = null,
                    syncState = if (source.enabled) SyncState.UPDATE_PENDING else source.syncState))
                source.id
            }
        }
    }

    /** Stores settings without fetching, for storage fixtures that need an unfetched source. */
    internal suspend fun seed(id: String?, draft: SourceDraft): String = SyncCoordinator.locked(context) {
        val settings = draft.validated()
        write(checkSavable(id, settings), settings).id
    }

    /** The source being edited (null when adding), after checking that [settings] can be saved now. */
    private suspend fun checkSavable(id: String?, settings: SourceSettings): SourceEntity? {
        if (db.sync().plans().any { !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        val listed = dao.sources()
        if (listed.any { it.url == settings.url && it.id != id }) throw DictionaryProblem("DUPLICATE_URL")
        if (id == null) return null
        val old = dao.source(id) ?: throw DictionaryProblem("SOURCE_MISSING")
        if (!old.listed) throw SyncStopped("SOURCE_DELETE_PENDING")
        return old
    }

    /** Downloads and parses with [settings]. Writes nothing; cancelling stops a stalled download too. */
    private suspend fun fetch(old: SourceEntity?, settings: SourceSettings): SnapshotStore.Candidate {
        if (old != null && old.serverNotBefore > now()) throw DictionaryProblem("SERVER_WAIT")
        val wifiOnly = preferences.wifiOnly.first()
        val result = runInterruptible { download.get(settings.url, wifiOnly, null) }
        currentCoroutineContext().ensureActive()
        if (result.notModified) throw DictionaryProblem("UNEXPECTED_304")
        val parsed = DictionaryParser.parse(result.bytes, settings.encoding, settings.format, result.contentType)
        if (preferences.wifiOnly.first() != wifiOnly) throw DictionaryProblem("POLICY_CHANGED")
        return SnapshotStore.Candidate.of(result, parsed, settings.locale)
    }

    /**
     * Writes [settings] to [old], or to a new source. A new source with the URL of a deleted one starts afresh in the
     * deleted one's row, under a newer revision, so the ownership history of its words stays with it. Changed fetch
     * settings start a new revision that has not been fetched yet.
     */
    private suspend fun write(old: SourceEntity?, settings: SourceSettings): SourceEntity {
        if (old != null) {
            val changed = settings.changesFetch(old)
            val source = old.copy(name = settings.name, url = settings.url, locale = settings.locale, format = settings.format,
                encoding = settings.encoding, syncIntervalHours = settings.syncIntervalHours,
                revision = old.revision + if (changed) 1 else 0,
                state = if (changed) FetchState.SETTINGS_CHANGED else old.state, error = if (changed) null else old.error)
            dao.updateSource(source)
            return source
        }
        val archived = dao.archivedUrl(settings.url)
        val source = SourceEntity(archived?.id ?: UUID.randomUUID().toString(), settings.name, settings.url,
            locale = settings.locale, format = settings.format, encoding = settings.encoding,
            syncIntervalHours = settings.syncIntervalHours, revision = (archived?.revision ?: 0) + 1)
        if (archived == null) dao.addSource(source) else dao.updateSource(source)
        return source
    }
}
