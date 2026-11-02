package me.midra.dictionarysync.library

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.data.local.SnapshotEntity
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.parser.ParsedDictionary
import me.midra.dictionarysync.data.parser.ParsedEntry
import me.midra.dictionarysync.data.parser.TsvParser
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import me.midra.dictionarysync.data.source.SourceRules

/**
 * Fetched generations (snapshots) of a source and their entries. The caller holds the sync lock and, for writes, a
 * Room transaction; nothing here talks to the network or the Provider.
 */
class SnapshotStore(private val db: DictionaryDatabase) {
    private val dao = db.library()

    /** A parsed download of [source]'s current settings, with every entry's language resolved. */
    class Candidate(val download: Download, val parsed: ParsedDictionary, sourceLocale: String) {
        val entries = resolved(parsed.entries, sourceLocale)
        val contentHash = contentHash(entries)
    }

    /** Stores [candidate] as a new generation of [source]'s current settings and revision. */
    suspend fun add(source: SourceEntity, candidate: Candidate, fetchedAt: Long): SnapshotEntity {
        val download = candidate.download
        val parsed = candidate.parsed
        val entries = candidate.entries
        val snapshot = SnapshotEntity(
            id = UUID.randomUUID().toString(), sourceId = source.id, revision = source.revision,
            bodyHash = download.sha256, contentHash = candidate.contentHash, fetchedAt = fetchedAt, finalUrl = download.finalUrl,
            parser = parsed.parser, encoding = parsed.encoding, bytes = download.bytes.size, lines = parsed.physicalLines,
            count = entries.size, uniqueCount = uniqueCount(entries),
            sourceLocale = source.locale, sourceUrl = source.url, sourceFormat = source.format, sourceEncoding = source.encoding,
            etag = download.etag, lastModified = download.lastModified,
            validationVersion = DictionaryParser.VALIDATION_VERSION, resolvedLocale = SourceRules.resolvedLocale(source.locale),
            skippedLines = parsed.skipped.size, firstSkippedLine = parsed.skipped.firstOrNull(),
        )
        dao.addSnapshot(snapshot)
        entries.chunked(ENTRY_BATCH).forEach { chunk ->
            dao.addEntries(chunk.map { EntryEntity(snapshot.id, it.line, it.reading, it.word, it.partOfSpeech, it.comment, it.locale!!) })
        }
        return snapshot
    }

    /**
     * Keeps the latest, previous and applied generations and every one an unfinished plan references. Key sets of
     * finished plans are never read again, so they go too; the plans and their journal stay.
     */
    suspend fun prune(source: SourceEntity, keep: Set<String?>) {
        val planned = db.sync().plans().filter { !it.state.terminal }.flatMap { listOfNotNull(it.oldSnapshot, it.newSnapshot) }.toSet()
        db.sync().deleteCommittedPlanKeys(source.id)
        dao.snapshotIds(source.id).filter { it !in keep && it !in planned }.forEach { delete(it) }
    }

    suspend fun deleteAll(sourceId: String) = dao.snapshotIds(sourceId).forEach { delete(it) }

    private suspend fun delete(id: String) { dao.deleteEntries(id); dao.deleteSnapshot(id) }

    /**
     * The latest generation, if its stored entries still match their hashes and it was fetched with [source]'s current
     * settings and parser version: only then may a 304 Not Modified reuse it.
     */
    suspend fun validatedLatest(source: SourceEntity): SnapshotEntity? {
        val snapshot = source.latestSnapshot?.let { dao.snapshot(it) } ?: return null
        if (snapshot.sourceId != source.id || snapshot.revision != source.revision || snapshot.sourceUrl != source.url ||
            snapshot.sourceLocale != source.locale || snapshot.sourceFormat != source.format || snapshot.sourceEncoding != source.encoding ||
            snapshot.validationVersion != DictionaryParser.VALIDATION_VERSION) return null
        val entries = dao.entries(snapshot.id).map { ParsedEntry(it.line, it.reading, it.word, it.partOfSpeech, it.comment, it.locale) }
        if (entries.size != snapshot.count || uniqueCount(entries) != snapshot.uniqueCount || contentHash(entries) != snapshot.contentHash) return null
        val parsers = when (source.format) {
            // A header-identified TSV dialect is still a TSV; the header decides the parser, not the selection.
            "TSV" -> TsvParser.parsers
            "MS_WORDLIST" -> setOf("ms-wordlist-v1")
            "PLIST_XML" -> setOf("apple-plist-xml-v1")
            "APPLE_CSV" -> setOf("apple-csv-v1")
            else -> TsvParser.parsers + "apple-plist-xml-v1"
        }
        return snapshot.takeIf {
            it.parser in parsers && it.encoding in DictionaryParser.ENCODINGS && (source.encoding == "AUTO" || source.encoding == it.encoding)
        }
    }

    companion object {
        private const val ENTRY_BATCH = 500

        /** Every entry gets the language its shared-dictionary key uses; stored, hashed and counted with it. */
        fun resolved(entries: List<ParsedEntry>, sourceLocale: String) = entries.map { it.copy(locale = SourceRules.entryLocale(sourceLocale, it.locale)) }

        fun uniqueCount(entries: List<ParsedEntry>) = entries.map { Triple(it.locale, it.word, it.reading) }.toSet().size

        /** Order-independent hash of the entries' content, used to tell a changed distribution from a re-download. */
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
