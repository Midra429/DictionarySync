package me.midra.dictionarysync.data.parser

import java.util.Locale


class DictionaryProblem(val code: String, val line: Int? = null, val retryAfter: String? = null) : Exception(code)
/** [locale] is the language tag the file declares for this entry ("ALL" for every language), or null if the format has none. */
data class ParsedEntry(val line: Int, val reading: String, val word: String, val partOfSpeech: String, val comment: String, val locale: String? = null)
/** [skipped] lists lines without enough columns that a format-confirmed file was read past (see [TsvParser.parseText]). */
data class ParsedDictionary(val entries: List<ParsedEntry>, val physicalLines: Int, val parser: String, val encoding: String,
    val skipped: List<Int> = emptyList())

/** Strict whole-document validation. Never returns a partially parsed dictionary. */
object TsvParser {
    // A 100,000-word dictionary in UTF-16 or Apple's indented plist is well over 10 MiB.
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    private const val MS_HEADER = "!Microsoft IME Dictionary Tool"
    private const val GBOARD_HEADER = "# Gboard Dictionary version:"
    private const val GBOARD_FORMAT = "# Gboard Dictionary format:"
    private const val GBOARD_V2_COLUMNS = "shortcut\tword\tlanguage_tag\tpos_tag"

    /** The header, never the file name, decides what each column means. Unknown headers fall through to plain TSV and its checks. */
    private enum class Dialect(val parser: String) {
        PLAIN("ime-tsv-v1"), MS("ms-wordlist-v1"), ATOK("atok-tango-v1"), GBOARD_V1("gboard-v1"), GBOARD_V2("gboard-v2")
    }
    val parsers = Dialect.entries.map { it.parser }.toSet()

    fun parse(bytes: ByteArray, encoding: String = "AUTO", format: String = "AUTO", contentType: String? = null): ParsedDictionary {
        return DictionaryParser.parse(bytes, encoding, format, contentType)
    }

    private fun dialect(first: String, line: Int): Dialect {
        val lower = first.lowercase(Locale.ROOT)
        return when {
            first.startsWith(MS_HEADER) -> Dialect.MS
            // ATOK accepts either case after "!!". DICUT10 and older use a different layout (Mozc refuses them too).
            lower.startsWith("!!atok_tango_text_header") -> Dialect.ATOK
            lower.startsWith("!!dicut") -> if ((lower.removePrefix("!!dicut").trim().toIntOrNull() ?: 0) >= 11) Dialect.ATOK else fail("ATOK_DICUT_UNSUPPORTED", line)
            first.startsWith(GBOARD_HEADER) -> when (first.removePrefix(GBOARD_HEADER).trim()) {
                "1" -> Dialect.GBOARD_V1
                "2" -> Dialect.GBOARD_V2
                else -> fail("GBOARD_VERSION_UNSUPPORTED", line)
            }
            else -> Dialect.PLAIN
        }
    }

    internal fun parseText(text: String, usedEncoding: String, format: String): ParsedDictionary {
        val lines = text.split(Regex("\r\n|\n|\r"))
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        val dialect = if (firstIndex < 0) Dialect.PLAIN else dialect(lines[firstIndex], firstIndex + 1)
        val ms = dialect == Dialect.MS
        val gboard = dialect == Dialect.GBOARD_V1 || dialect == Dialect.GBOARD_V2
        if (format !in setOf("AUTO", "TSV", "MS_WORDLIST")) fail("UNSUPPORTED_FORMAT")
        if (format == "MS_WORDLIST" && !ms) fail("MS_HEADER_REQUIRED")
        if (ms && lines.none { it == "!Format:WORDLIST" }) fail("MS_FORMAT_REQUIRED")
        val entries = ArrayList<ParsedEntry>()
        val languages = HashMap<String, String>()
        var gboardFormat = false
        var structure = dialect != Dialect.PLAIN || format != "AUTO"
        // Once a header or the user has confirmed the format, a line too short to be a word is skipped (as Mozc does) and
        // reported, rather than failing the file. A header-less automatic guess stays strict: the columns are its only evidence.
        val confirmed = structure
        val skipped = ArrayList<Int>()
        lines.forEachIndexed { index, line ->
            val number = index + 1
            if (line.toByteArray(Charsets.UTF_8).size > 64 * 1024) fail("RECORD_LIMIT", number)
            if (line.isEmpty()) return@forEachIndexed
            // ATOK defines every "!!" line as an annotation, wherever it appears and whatever it contains.
            if (dialect == Dialect.ATOK && line.startsWith("!!")) return@forEachIndexed
            // Gboard's v2 column declaration contains tabs; it must never become an entry.
            if (gboard && line.startsWith(GBOARD_FORMAT)) {
                if (dialect != Dialect.GBOARD_V2 || line.removePrefix(GBOARD_FORMAT) != GBOARD_V2_COLUMNS) fail("GBOARD_FORMAT_UNSUPPORTED", number)
                gboardFormat = true
                return@forEachIndexed
            }
            if (!line.contains('\t') && (line.startsWith('#') || (ms && line.startsWith('!')))) {
                structure = true
                return@forEachIndexed
            }
            if (line.isBlank() && !line.contains('\t')) return@forEachIndexed
            if (dialect == Dialect.GBOARD_V2 && !gboardFormat) fail("GBOARD_FORMAT_REQUIRED", number)
            val columns = when (dialect) {
                Dialect.PLAIN -> 3..4
                // Like Mozc's importer, the MS-IME header admits stray trailing columns (an extra tab before the comment).
                Dialect.MS -> 3..Int.MAX_VALUE
                Dialect.ATOK -> 3..10 // comment, auto-replace and up to five replacement candidates may follow.
                Dialect.GBOARD_V1 -> 3..3
                Dialect.GBOARD_V2 -> 4..4
            }
            // Empty columns past the widest row (a stray tab at the end) carry nothing.
            val fields = line.split('\t').toMutableList()
            while (fields.size > columns.last && fields.last().isEmpty()) fields.removeAt(fields.lastIndex)
            if (confirmed && (fields.size < 3 || fields.all { it.isBlank() })) { skipped += number; return@forEachIndexed }
            if (fields.size !in columns) fail("TSV_COLUMNS_${fields.size}", number)
            fields.forEachIndexed { column, value -> validateValue(value, if (column == 2) 256 else if (column == 3) 4096 else 1024, number) }
            if (fields[1].isBlank()) fail("EMPTY_WORD", number)
            entries += if (gboard) {
                // The third column is a language tag, not a part of speech. Empty means every language.
                ParsedEntry(number, fields[0], fields[1], fields.getOrElse(3) { "" }, "", language(fields[2], number, languages))
            } else {
                // ATOK's auto-replacement has no UserDictionary counterpart; refuse it rather than drop or misfile it.
                if (dialect == Dialect.ATOK && (fields.getOrElse(4) { "" } !in setOf("", "しない") || fields.drop(5).any { it.isNotEmpty() }))
                    fail("ATOK_AUTO_REPLACE_UNSUPPORTED", number)
                // Extra MS-IME columns are kept with the comment rather than dropped, so the shown text is not lost.
                val comment = if (ms && fields.size > 4) fields.drop(3).filter { it.isNotEmpty() }.joinToString(" ") else fields.getOrElse(3) { "" }
                ParsedEntry(number, fields[0], fields[1], fields[2], comment)
            }
            structure = true
            if (entries.size > MAX_ENTRIES) fail("ENTRY_LIMIT", number)
        }
        if (dialect == Dialect.GBOARD_V2 && !gboardFormat) fail("GBOARD_FORMAT_REQUIRED")
        if (!structure) fail("AMBIGUOUS_EMPTY")
        // Nothing but short lines means the wrong format, not a dictionary with a few slips.
        if (entries.isEmpty() && skipped.isNotEmpty()) fail("TSV_COLUMNS_SHORT", skipped.first())
        return ParsedDictionary(entries, lines.size - if (lines.last().isEmpty()) 1 else 0, dialect.parser, usedEncoding, skipped)
    }

    // Gboard repeats a few tags on every line; each distinct tag is validated once per file.
    private fun language(tag: String, line: Int, cache: MutableMap<String, String>): String {
        if (tag.isEmpty()) return "ALL"
        cache[tag]?.let { return it }
        val locale = try { Locale.Builder().setLanguageTag(tag).build() } catch (_: Exception) { fail("GBOARD_LANGUAGE_TAG", line) }
        if (locale.language.isEmpty() || locale.language == "und") fail("GBOARD_LANGUAGE_TAG", line)
        return locale.toLanguageTag().also { cache[tag] = it }
    }

    internal fun validateValue(value: String, max: Int, line: Int) {
        if (value.codePointCount(0, value.length) > max) fail("FIELD_LIMIT", line)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) fail("INVALID_UNICODE", line)
                i += 2
            } else {
                if (Character.isLowSurrogate(c) || Character.isISOControl(c)) fail("CONTROL_CHARACTER", line)
                i++
            }
        }
    }
    internal fun fail(code: String, line: Int? = null): Nothing = throw DictionaryProblem(code, line)
}
