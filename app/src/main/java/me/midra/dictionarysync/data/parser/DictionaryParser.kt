package me.midra.dictionarysync.data.parser

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import me.midra.dictionarysync.data.parser.TsvParser.fail
import me.midra.dictionarysync.data.source.MemoryBudget

/** One strict decoding and validation boundary for every supported dictionary format. */
object DictionaryParser {
    // Bump whenever decoding, validation, or any parser semantics change.
    const val VALIDATION_VERSION = "dictionary-validation-v3"
    val formats = setOf("AUTO", "TSV", "MS_WORDLIST", "PLIST_XML", "APPLE_CSV")
    /** Encodings a source may select (besides AUTO) and a generation may be decoded with. */
    val ENCODINGS = setOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-31j")
    // ASCII headers that only these formats begin with, so a BOM-less UTF-16 file can be recognized by its bytes.
    private val utf16Signatures = listOf("!microsoft ime dictionary tool", "!!atok_tango_text_header", "!!dicut", "# gboard dictionary version:", "<?xml")
    // SKK markers are ASCII, so they are found before decoding even in EUC-JP, SKK's usual encoding.
    private val skkMarkers = listOf(";; okuri-ari entries.", ";; okuri-nasi entries.").map { it.toByteArray(Charsets.US_ASCII) }

    /** [budget] sizes the body and entry limits to the heap; see [MemoryBudget]. */
    fun parse(bytes: ByteArray, encoding: String = "AUTO", format: String = "AUTO", contentType: String? = null,
        budget: MemoryBudget = MemoryBudget()): ParsedDictionary {
        if (format !in formats) fail("UNSUPPORTED_FORMAT")
        if (bytes.isEmpty()) fail("EMPTY_BODY")
        if (bytes.size > budget.maxBytes) fail("SIZE_LIMIT")
        val maxEntries = budget.maxEntries(bytes.size.toLong())
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (mime in setOf("text/html", "application/xhtml+xml", "application/json") || mime?.endsWith("+json") == true) fail("NOT_DICTIONARY")
        // ZIP, gzip, xz, bzip2, 7z, zstd, tar and binary plist. Archives are never unpacked.
        if (bytes.starts(0x50, 0x4b, 3, 4) || bytes.starts(0x50, 0x4b, 5, 6) || bytes.starts(0x1f, 0x8b) ||
            bytes.starts(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) || bytes.starts(0x42, 0x5a, 0x68) || bytes.starts(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) ||
            bytes.starts(0x28, 0xb5, 0x2f, 0xfd) || bytes.size > 262 && String(bytes, 257, 5, Charsets.ISO_8859_1) == "ustar" ||
            bytes.take(6).toByteArray().contentEquals("bplist".toByteArray())) fail("UNSUPPORTED_BINARY")
        if (skk(bytes)) fail("SKK_UNSUPPORTED")
        val (text, usedEncoding) = decode(bytes, encoding, contentType)
        val head = text.trimStart().take(256).lowercase(java.util.Locale.ROOT)
        if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("{") || head.startsWith("[")) fail("NOT_DICTIONARY")
        val xml = text.trimStart().startsWith('<')
        if (!xml) yaml(text)
        val result = when {
            format == "PLIST_XML" || (format == "AUTO" && xml) -> PlistParser.parse(text, usedEncoding, maxEntries)
            format == "APPLE_CSV" -> AppleCsvParser.parse(text, usedEncoding, maxEntries)
            xml -> fail("FORMAT_CONFLICT")
            format == "AUTO" && !text.contains('\t') && text.contains(',') -> {
                AppleCsvParser.parse(text, usedEncoding, maxEntries, skipShort = false) // Validate the entire candidate before suggesting it.
                fail("CSV_MANUAL_REQUIRED")
            }
            else -> TsvParser.parseText(text, usedEncoding, format, maxEntries)
        }
        if ((mime in setOf("application/xml", "text/xml", "application/x-plist") || mime?.endsWith("+xml") == true) && result.parser != PlistParser.PARSER) fail("MIME_FORMAT_CONFLICT")
        if (mime == "text/csv" && result.parser != AppleCsvParser.PARSER) fail("MIME_FORMAT_CONFLICT")
        return result
    }

    /**
     * A YAML document start, after optional comments, is Rime's header or a structured (JISYO-style) dictionary.
     * Rime's code column is an input code, not a Japanese reading, so neither is read as TSV.
     */
    private fun yaml(text: String) {
        val lines = text.lineSequence().map { it.trimEnd() }
        if (lines.firstOrNull { it.isNotEmpty() && !it.startsWith('#') } != "---") return
        fail(if (lines.any { it == "..." }) "RIME_UNSUPPORTED" else "YAML_UNSUPPORTED")
    }

    /**
     * Whether a line starts with an SKK marker, read from the bytes as ISO-8859-1 the way `(?m)^` reads lines: after
     * LF, CR or NEL (0x85). Scanning the bytes avoids a second, decoded copy of the whole body.
     */
    private fun skk(bytes: ByteArray): Boolean {
        for (i in bytes.indices) {
            if (i > 0) { val before = bytes[i - 1].toInt() and 255; if (before != 0x0a && before != 0x0d && before != 0x85) continue }
            if (skkMarkers.any { marker -> i + marker.size <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] } }) return true
        }
        return false
    }

    private fun ByteArray.starts(vararg values: Int) = size >= values.size && values.indices.all { this[it].toInt() and 255 == values[it] }

    private fun decode(bytes: ByteArray, selected: String, contentType: String?): Pair<String, String> {
        if (bytes.starts(0, 0, 254, 255) || bytes.starts(255, 254, 0, 0)) fail("UTF32_UNSUPPORTED")
        val bom = when {
            bytes.starts(239, 187, 191) -> "UTF-8" to 3
            bytes.starts(255, 254) -> "UTF-16LE" to 2
            bytes.starts(254, 255) -> "UTF-16BE" to 2
            else -> null
        }
        fun canonical(name: String): String = try {
            val charset = Charset.forName(name)
            // Android and the desktop JDK expose different canonical names for CP932.
            if (charset == Charset.forName("windows-31j")) "windows-31j" else charset.name()
        } catch (_: Exception) { fail("UNSUPPORTED_ENCODING") }
        fun agrees(declared: String, actual: String) = declared == actual || (declared == "UTF-16" && actual in setOf("UTF-16LE", "UTF-16BE"))
        val http = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
            .findAll(contentType.orEmpty()).map { canonical(it.groupValues[1]) }.toList()
        val manual = if (selected == "AUTO") null else canonical(selected)
        if (manual != null && manual !in ENCODINGS) fail("UNSUPPORTED_ENCODING")
        val signature = listOf("UTF-16LE", "UTF-16BE").firstOrNull { name ->
            val head = String(bytes, 0, minOf(bytes.size, 64), Charset.forName(name)).lowercase(java.util.Locale.ROOT)
            utf16Signatures.any { head.startsWith(it) }
        }
        val chosen = manual ?: bom?.first ?: signature ?: "UTF-8"
        if (bom != null && bom.first != chosen || signature != null && signature != chosen || http.any { !agrees(it, chosen) }) fail("ENCODING_CONFLICT")
        val offset = bom?.second ?: 0
        val charset = Charset.forName(chosen)
        // Strictly validated through a small buffer, then decoded once into the String: decoding into a CharBuffer and
        // copying it to a String would hold the text twice. Valid input decodes identically either way.
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val output = CharBuffer.allocate(8192)
        while (true) {
            val result = decoder.decode(input, output, true)
            if (result.isError) fail("DECODE_FAILED")
            output.clear()
            if (result.isUnderflow) break
        }
        while (true) {
            val result = decoder.flush(output)
            if (result.isError) fail("DECODE_FAILED")
            output.clear()
            if (result.isUnderflow) break
        }
        val text = String(bytes, offset, bytes.size - offset, charset)
        // A Reader is used for XML, so declarations must be checked here, before SAX can ignore them.
        if (text.startsWith("<?xml")) {
            val declaration = text.substringBefore("?>")
            Regex("encoding\\s*=\\s*(['\"])([^'\"]+)\\1").find(declaration)?.groupValues?.get(2)?.let {
                if (!agrees(canonical(it), chosen)) fail("ENCODING_CONFLICT")
            }
        }
        return text to chosen
    }
}
