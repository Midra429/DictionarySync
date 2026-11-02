package me.midra.dictionarysync.library

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
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
import me.midra.dictionarysync.data.source.SourceRules

/**
 * Fetched generations (snapshots) of a source and their entries. The caller holds the sync lock and, for writes, a
 * Room transaction; nothing here talks to the network or the Provider.
 */
class SnapshotStore(private val db: DictionaryDatabase) {
    private val dao = db.library()

    /**
     * A parsed download, with every entry's language resolved. Keeps only what a generation stores, so the downloaded
     * body and the unresolved entries can be collected before the entries are inserted.
     */
    class Candidate private constructor(
        val bodyHash: String, val finalUrl: String, val bytes: Int, val etag: String?, val lastModified: String?,
        val parser: String, val encoding: String, val lines: Int, val skippedLines: Int, val firstSkippedLine: Int?,
        val entries: List<ParsedEntry>,
    ) {
        val contentHash = contentHash(entries)

        companion object {
            fun of(download: Download, parsed: ParsedDictionary, sourceLocale: String) = Candidate(
                download.sha256, download.finalUrl, download.bytes.size, download.etag, download.lastModified,
                parsed.parser, parsed.encoding, parsed.physicalLines, parsed.skipped.size, parsed.skipped.firstOrNull(),
                resolved(parsed.entries, sourceLocale))
        }
    }

    /** Stores [candidate] as a new generation of [source]'s current settings and revision. */
    suspend fun add(source: SourceEntity, candidate: Candidate, fetchedAt: Long): SnapshotEntity {
        val entries = candidate.entries
        val snapshot = SnapshotEntity(
            id = UUID.randomUUID().toString(), sourceId = source.id, revision = source.revision,
            bodyHash = candidate.bodyHash, contentHash = candidate.contentHash, fetchedAt = fetchedAt, finalUrl = candidate.finalUrl,
            parser = candidate.parser, encoding = candidate.encoding, bytes = candidate.bytes, lines = candidate.lines,
            count = entries.size, uniqueCount = uniqueCount(entries),
            sourceLocale = source.locale, sourceUrl = source.url, sourceFormat = source.format, sourceEncoding = source.encoding,
            etag = candidate.etag, lastModified = candidate.lastModified,
            validationVersion = DictionaryParser.VALIDATION_VERSION, resolvedLocale = SourceRules.resolvedLocale(source.locale),
            skippedLines = candidate.skippedLines, firstSkippedLine = candidate.firstSkippedLine,
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

        /**
         * Order-independent hash of the entries' content, used to tell a changed distribution from a re-download. Rows
         * are ordered field by field, which is the order of their NUL-joined text since no field contains NUL (the
         * parsers reject control characters), and streamed into the digest rather than serialized in memory first.
         */
        fun contentHash(entries: List<ParsedEntry>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(Discard, digest).buffered()).use { out ->
                entries.sortedWith(FIELD_ORDER).forEach { entry ->
                    for (value in listOf(entry.reading, entry.word, entry.partOfSpeech, entry.comment, entry.locale.orEmpty())) {
                        val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** OutputStream.nullOutputStream() needs API 33. */
        private object Discard : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        private val FIELD_ORDER = compareBy<ParsedEntry>({ it.reading }, { it.word }, { it.partOfSpeech }, { it.comment }, { it.locale.orEmpty() })
    }
}
