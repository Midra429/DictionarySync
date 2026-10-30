package me.midra.dictionarysync.library

import android.content.Context
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.sync.*
import me.midra.dictionarysync.data.parser.*
import me.midra.dictionarysync.data.source.*

class LibraryRepository(private val context: Context, val database: DictionaryDatabase = DictionaryDatabase.get(context),
    private val now: () -> Long = System::currentTimeMillis,
    private val synchronizeSource: (suspend (String) -> Boolean)? = null,
    private val download: (String, Boolean, HttpValidators?) -> Download = { url, wifi, validators -> AndroidDownloadRoute(context, wifi).use { Downloader().get(url, it, validators) } }) {
    val dao = database.library()
    val requests = PendingRequests(database)
    val preferences = Preferences(context)
    // Storage fixtures can seed an unfetched row without network access.
    internal suspend fun seedSource(id: String?, name: String, url: String, locale: String, format: String, encoding: String): String =
        SyncCoordinator.locked(context) { saveConfiguration(id, name, SourceRules.url(url).toString(), locale, format, encoding) }

    private suspend fun saveConfiguration(id: String?, name: String, url: String, locale: String, format: String, encoding: String): String {
        ensureNoPlan()
        SourceRules.name(name); SourceRules.url(url)
        val normalizedLocale = SourceRules.locale(locale)
        if (format !in DictionaryParser.formats || encoding !in setOf("AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "windows-31j")) throw DictionaryProblem("UNSUPPORTED_FORMAT")
        val sources = dao.sources()
        if (sources.any { it.url == url && it.id != id }) throw DictionaryProblem("DUPLICATE_URL")
        if (id == null && sources.size >= SourceRules.MAX_SOURCES) throw DictionaryProblem("SOURCE_LIMIT")
        val archived = if (id == null) dao.archivedUrl(url) else null
        val old = id?.let { dao.source(it) ?: throw DictionaryProblem("SOURCE_MISSING") }
        val changed = old != null && (old.url != url || old.locale != normalizedLocale || old.format != format || old.encoding != encoding)
        val source = (old ?: SourceEntity(archived?.id ?: UUID.randomUUID().toString(), name, url, revision = (archived?.revision ?: 0) + 1)).copy(name = name, url = url,
            locale = normalizedLocale, format = format, encoding = encoding,
            revision = (old?.revision ?: ((archived?.revision ?: 0) + 1)) + if (changed) 1 else 0,
            state = if (changed) "SETTINGS_CHANGED" else old?.state ?: "NOT_FETCHED", error = if (changed) null else old?.error)
        if (old == null && archived == null) dao.addSource(source) else dao.updateSource(source)
        return source.id
    }

    /** No provisional Source, request or schedule exists until the complete candidate commits. */
    suspend fun save(id: String?, name: String, url: String, locale: String, format: String, encoding: String,
        syncIntervalHours: Int = 24): String = withContext(Dispatchers.IO) {
        SyncCoordinator.locked(context) {
            ensureNoPlan()
            SourceRules.name(name)
            val normalizedUrl = SourceRules.url(url).toString()
            val language = SourceRules.locale(locale)
            if (syncIntervalHours !in SourceRules.intervals) throw DictionaryProblem("INVALID_INTERVAL")
            if (format !in DictionaryParser.formats || encoding !in setOf("AUTO", "UTF-8", "UTF-16LE", "UTF-16BE", "windows-31j")) throw DictionaryProblem("UNSUPPORTED_FORMAT")
            if (dao.sources().any { it.url == normalizedUrl && it.id != id }) throw DictionaryProblem("DUPLICATE_URL")
            val old = id?.let { dao.source(it) ?: throw DictionaryProblem("SOURCE_MISSING") }
            if (old != null && old.lifecycle != "ACTIVE") throw SyncStopped("SOURCE_DELETE_PENDING")
            val changed = old == null || old.url != normalizedUrl || old.locale != language || old.format != format || old.encoding != encoding
            val candidate = if (changed) {
                if (old != null && old.serverNotBefore > now()) throw DictionaryProblem("SERVER_WAIT")
                val wifi = preferences.wifiOnly.first()
                // Interruptible, so cancelling an add/edit stops a stalled download; nothing has been written yet.
                val result = runInterruptible { download(normalizedUrl, wifi, null) }
                currentCoroutineContext().ensureActive()
                if (result.notModified) throw DictionaryProblem("UNEXPECTED_304")
                val parsed = DictionaryParser.parse(result.bytes, encoding, format, result.contentType)
                if (preferences.wifiOnly.first() != wifi) throw DictionaryProblem("POLICY_CHANGED")
                result to parsed
            } else null
            database.withTransaction {
                val savedId = saveConfiguration(id, name.trim(), normalizedUrl, language, format, encoding)
                val source = dao.source(savedId)!!
                if (candidate == null) dao.updateSource(source.copy(syncIntervalHours = syncIntervalHours))
                else {
                    val (result, parsed) = candidate
                    val entries = resolved(parsed.entries, language)
                    val timestamp = now()
                    val snapshot = SnapshotEntity(UUID.randomUUID().toString(), savedId, source.revision, result.sha256,
                        contentHash(entries), timestamp, result.finalUrl, parsed.parser, parsed.encoding, result.bytes.size,
                        parsed.physicalLines, entries.size, uniqueCount(entries),
                        language, normalizedUrl, format, encoding, result.etag, result.lastModified,
                        DictionaryParser.VALIDATION_VERSION, SourceRules.resolvedLocale(language), parsed.skipped.size, parsed.skipped.firstOrNull())
                    dao.addSnapshot(snapshot)
                    addEntries(snapshot.id, entries)
                    dao.updateSource(source.copy(syncIntervalHours = syncIntervalHours, latestSnapshot = snapshot.id,
                        previousSnapshot = source.latestSnapshot, fetchedAt = timestamp, changedAt = timestamp,
                        state = "FETCHED", error = null, syncState = if (source.enabled) "UPDATE_PENDING" else source.syncState))
                }
                savedId
            }
        }
    }

    suspend fun fetch(id: String) = withContext(Dispatchers.IO) {
        val request = requests.submit(listOf(id), FetchReason.MANUAL).single()
        executeFetch(request)
    }

    /** The request is already durable. Only this execution path owns the long-running lock. */
    internal suspend fun executeFetch(request: PendingRequest): Unit = withContext(Dispatchers.IO) {
        SyncCoordinator.locked(context) {
            if (!requests.isCurrent(request, now())) return@locked
            val id = request.sourceId
            val old = dao.source(id)
            if (old == null || old.lifecycle != "ACTIVE" || !old.enabled || old.revision != request.configRevision) {
                requests.finish(request, if (old?.lifecycle != "ACTIVE") "SOURCE_REMOVED" else if (!old.enabled) "SOURCE_DISABLED" else "SETTINGS_CHANGED")
                return@locked
            }
            if (old.serverNotBefore > now()) {
                database.withTransaction {
                    requests.defer(request, "SERVER_WAIT", old.serverNotBefore)
                    dao.updateSource(old.copy(state = "FETCH_RETRY", error = "SERVER_WAIT"))
                }
                return@locked
            }
            dao.updateSource(old.copy(state = "FETCHING", error = null))
            val cached = validatedCache(old)
            val validators = cached?.let { HttpValidators(it.finalUrl, ValidatorHeaders.etag(it.etag), ValidatorHeaders.lastModified(it.lastModified)) }?.takeIf { it.usable }
            val wifi = preferences.wifiOnly.first() // Preference/storage errors must remain unacked.
            val (result, parsed) = try {
                val result = try { download(old.url, wifi, validators) } catch (e: DictionaryProblem) {
                    if (e.code != "INVALID_304") throw e
                    download(old.url, wifi, null) // One bounded unconditional recovery, never parse a 304 body.
                }
                currentCoroutineContext().ensureActive()
                if (result.notModified) {
                    if (cached == null || validators == null || result.finalUrl != cached.finalUrl) throw DictionaryProblem("UNEXPECTED_304")
                    result to null
                } else result to DictionaryParser.parse(result.bytes, old.encoding, old.format, result.contentType)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error // Unacked request is resumed after restart.
                val reason = (error as? DictionaryProblem)?.let { it.code + (it.line?.let { n -> " (行 $n)" } ?: "") } ?: error.javaClass.simpleName
                val waiting = (error as? DictionaryProblem)?.code in setOf("WIFI_WAIT", "NETWORK_WAIT", "POLICY_CHANGED")
                database.withTransaction {
                    if (waiting) {
                        dao.updateSource(old.copy(state = "NETWORK_WAIT", error = reason))
                        requests.defer(request, reason)
                    } else {
                        val retry = requests.failed(request, reason, HttpRetry.retryable(error), HttpRetry.retryAfter((error as? DictionaryProblem)?.retryAfter, now()), now())
                        val current = dao.source(id) ?: throw DictionaryProblem("SOURCE_MISSING")
                        dao.updateSource(current.copy(state = if (retry) "FETCH_RETRY" else "FETCH_FAILED", error = reason))
                    }
                }
                return@locked
            }
            if (preferences.wifiOnly.first() != wifi) {
                database.withTransaction {
                    dao.updateSource(old.copy(state = "NETWORK_WAIT", error = "POLICY_CHANGED"))
                    requests.defer(request, "POLICY_CHANGED")
                }
                return@locked
            }
            if (parsed == null) {
                database.withTransaction {
                    check(cached != null && validatedCache(old)?.id == cached.id) { "CACHE_LOST" }
                    dao.updateSource(old.copy(state = "FETCHED", error = null, fetchedAt = now(), syncState = "UPDATE_PENDING"))
                    requests.finish(request, "NOT_MODIFIED")
                }
                return@locked
            }
            // Storage errors propagate without ack. A rolled-back candidate must remain retryable.
            val entries = resolved(parsed.entries, old.locale)
            val hash = contentHash(entries)
            val previous = (old.activeSnapshot ?: old.latestSnapshot)?.let { dao.snapshot(it) }
            val now = now()
            val snapshot = SnapshotEntity(UUID.randomUUID().toString(), id, old.revision, result.sha256, hash, now, result.finalUrl,
                parsed.parser, parsed.encoding, result.bytes.size, parsed.physicalLines, entries.size, uniqueCount(entries), old.locale, old.url, old.format, old.encoding,
                result.etag, result.lastModified, DictionaryParser.VALIDATION_VERSION, SourceRules.resolvedLocale(old.locale),
                parsed.skipped.size, parsed.skipped.firstOrNull())
            database.withTransaction {
                val current = dao.source(id)
                if (current?.revision != request.configRevision || current.lifecycle != "ACTIVE") throw DictionaryProblem("SETTINGS_CHANGED")
                dao.addSnapshot(snapshot)
                addEntries(snapshot.id, entries)
                dao.updateSource(old.copy(latestSnapshot = snapshot.id, previousSnapshot = old.latestSnapshot,
                    state = "FETCHED", error = null, fetchedAt = now, syncState = "UPDATE_PENDING",
                    changedAt = if (previous?.contentHash != hash) now else old.changedAt))
                // Keep latest, previous and the applied generation. Never evict a generation an unfinished plan references;
                // finished plans keep their row and journal but no longer pin generations or their key sets.
                val planned = database.sync().plans().filter { it.state !in SyncEngine.TERMINAL }
                    .flatMap { listOfNotNull(it.oldSnapshot, it.newSnapshot) }.toSet()
                database.sync().deleteCommittedPlanKeys(id)
                dao.snapshotIds(id).filter { it !in setOf(snapshot.id, old.latestSnapshot, old.activeSnapshot) && it !in planned }.forEach {
                    dao.deleteEntries(it); dao.deleteSnapshot(it)
                }
                // Ack this captured generation only, atomically with the validated candidate.
                requests.finish(request, "FETCHED")
            }
            Unit
        }
    }

    /** Parsed entries are our cache. Verify their complete content/counts and the captured configuration. */
    private suspend fun validatedCache(source: SourceEntity): SnapshotEntity? {
        val snapshot = source.latestSnapshot?.let { dao.snapshot(it) } ?: return null
        if (snapshot.sourceId != source.id || snapshot.revision != source.revision || snapshot.sourceUrl != source.url ||
            snapshot.sourceLocale != source.locale || snapshot.sourceFormat != source.format || snapshot.sourceEncoding != source.encoding ||
            snapshot.validationVersion != DictionaryParser.VALIDATION_VERSION) return null
        val entries = dao.entries(snapshot.id)
        val parsed = entries.map { ParsedEntry(it.line, it.reading, it.word, it.partOfSpeech, it.comment, it.locale) }
        if (entries.size != snapshot.count || uniqueCount(parsed) != snapshot.uniqueCount || contentHash(parsed) != snapshot.contentHash) return null
        val parsers = when (source.format) {
            // A header-identified TSV dialect is still a TSV; the header decides the parser, not the selection.
            "TSV" -> TsvParser.parsers
            "MS_WORDLIST" -> setOf("ms-wordlist-v1")
            "PLIST_XML" -> setOf("apple-plist-xml-v1")
            "APPLE_CSV" -> setOf("apple-csv-v1")
            else -> TsvParser.parsers + "apple-plist-xml-v1"
        }
        return snapshot.takeIf { it.parser in parsers && it.encoding in setOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-31j") &&
            (source.encoding == "AUTO" || source.encoding == it.encoding) }
    }

    /** Fetch only. Each source commits independently; no Plan is prepared, approved, or resumed. */
    suspend fun fetchAll(): List<SourceEntity> = withContext(Dispatchers.IO) {
        val batch = requestAllFetches()
        for (request in batch) {
            currentCoroutineContext().ensureActive()
            executeFetch(request)
        }
        batch.mapNotNull { dao.source(it.sourceId) }
    }

    suspend fun requestAllFetches(): List<PendingRequest> = SyncCoordinator.locked(context) {
        requests.submit(dao.sources().filter { it.lifecycle == "ACTIVE" && it.enabled }.map { it.id }, FetchReason.ALL_SOURCES)
    }

    suspend fun requestPeriodicFetches(now: Long, sourceId: String? = null): Unit = SyncCoordinator.locked(context) {
        requests.submit(dao.sources().filter {
            it.lifecycle == "ACTIVE" && it.enabled && (sourceId == null || it.id == sourceId) && (it.fetchedAt == null || now - it.fetchedAt >= it.syncIntervalHours * 3_600_000L)
        }.map { it.id }, FetchReason.PERIODIC, now = now)
        Unit
    }





    suspend fun schedulingSources(): List<SourceEntity> = SyncCoordinator.locked(context) {
        dao.allSources()
    }

    suspend fun synchronize(id: String): Boolean = synchronizeSource?.invoke(id) ?: SyncRepository(context, database).synchronize(id)

    suspend fun syncPending(limit: Int = Int.MAX_VALUE, events: SyncEvents = SyncEvents.None) {
        // Finish an interrupted plan first; while it is pending, every other source would stop at RECOVER_PLAN_FIRST.
        val due = dao.sources().filter { it.syncState in BACKGROUND_SYNC_STATES }.sortedBy { it.syncState in setOf("UPDATE_PENDING", "REMOVE_PENDING") }
        for (source in due.take(limit)) {
            currentCoroutineContext().ensureActive()
            events.applying(source)
            val committed = committedPlans(source.id)
            val synced = synchronize(source.id)
            val current = dao.source(source.id) ?: source
            if (!synced) { events.applyFailed(current, current.syncError ?: "SYNC_FAILED"); continue }
            // The journal holds exactly the Provider writes of a plan; borrowed words were never inserted or deleted.
            val plans = committedPlans(source.id) - committed
            events.applied(current, plans.sumOf { database.sync().operationCount(it, "INSERT") },
                plans.sumOf { database.sync().operationCount(it, "DELETE") })
        }
    }
    private suspend fun committedPlans(id: String) =
        database.sync().plans().filter { it.sourceId == id && it.state == "COMMITTED" }.map { it.id }.toSet()

    suspend fun setWifiOnly(enabled: Boolean) = SyncCoordinator.locked(context) { preferences.setWifiOnly(enabled) }

    /** Bounded pass. Waiting requests are kept; no busy loop or implicit periodic scheduling. */
    suspend fun resumePendingFetches() {
        reconcileInterruptedFetch()
        for (request in requests.ready()) {
            currentCoroutineContext().ensureActive()
            executeFetch(request)
        }
    }

    suspend fun reconcileInterruptedFetch() = SyncCoordinator.locked(context) {
        dao.sources().filter { it.state == "FETCHING" }.forEach { dao.updateSource(it.copy(state = "INTERRUPTED", error = null)) }
    }
    suspend fun deleteLocal(id: String) = SyncCoordinator.locked(context) {
        ensureNoPlan()
        database.withTransaction {
            if (database.sync().plans().any { it.sourceId == id }) throw SyncStopped("USE_SYNC_DELETE")
            val source = dao.source(id) ?: return@withTransaction
            check(source.activeSnapshot == null) // Applied sources must use SyncRepository deletion plans.
            database.requests().request(id)?.let { requests.finish(it, "SOURCE_REMOVED") }
            dao.snapshotIds(id).forEach { dao.deleteEntries(it); dao.deleteSnapshot(it) }
            dao.deleteSource(id)
        }
    }
    private suspend fun addEntries(snapshotId: String, entries: List<ParsedEntry>) = entries.chunked(500).forEach { chunk ->
        dao.addEntries(chunk.map { EntryEntity(snapshotId, it.line, it.reading, it.word, it.partOfSpeech, it.comment, it.locale!!) })
    }

    private suspend fun ensureNoPlan() {
        if (database.sync().plans().any { it.state !in SyncEngine.TERMINAL }) throw SyncStopped("RECOVER_PLAN_FIRST")
    }
    companion object {
        /** Desired changes plus an ordinary plan interrupted mid-apply (cancelled Worker, closed screen, process death).
         * SyncRepository resumes only this source's APPLY plan; RECOVERY_REQUIRED/RECOVERING stay explicit. */
        val BACKGROUND_SYNC_STATES = setOf("UPDATE_PENDING", "REMOVE_PENDING", "PREPARED", "ADDING", "REMOVING")
        /** Every entry gets the language its shared-dictionary key uses; stored, hashed and counted with it. */
        fun resolved(entries: List<ParsedEntry>, sourceLocale: String) = entries.map { it.copy(locale = SourceRules.entryLocale(sourceLocale, it.locale)) }
        fun uniqueCount(entries: List<ParsedEntry>) = entries.map { Triple(it.locale, it.word, it.reading) }.toSet().size
        fun contentHash(entries: List<ParsedEntry>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                // The sort key is built once per entry, not once per comparison.
                entries.map { listOf(it.reading, it.word, it.partOfSpeech, it.comment, it.locale.orEmpty()) }
                    .map { it to it.joinToString("\u0000") }.sortedBy { it.second }.forEach { (row) ->
                    row.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
                }
            }
            return Downloader.sha256(bytes.toByteArray())
        }
    }
}
