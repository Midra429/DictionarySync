package me.midra.dictionarysync.data.parser

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import me.midra.dictionarysync.data.parser.TsvParser.fail
import me.midra.dictionarysync.data.parser.TsvParser.validateValue

internal object PlistParser {
    const val PARSER = "apple-plist-xml-v1"
    private val appleDoctype = Regex("""<!DOCTYPE\s+plist\s+PUBLIC\s+(["'])-//Apple//DTD PLIST 1\.0//EN\1\s+(["'])http://www\.apple\.com/DTDs/PropertyList-1\.0\.dtd\2\s*>""")
    private val dictOpen = Regex("<dict\\s*/?>")
    private val dictClose = Regex("</dict\\s*>")
    private val dictEmpty = Regex("<dict\\s*/>")

    /** Remove only the recognized declaration, before XML parsing. No DTD reaches the XML reader. */
    private fun withoutDtd(text: String): String {
        var position = 0
        var rootSeen = false
        var doctypeSeen = false
        var dictStart: Int? = null
        var result = text
        while (position < text.length) {
            val start = text.indexOf('<', position)
            if (start < 0) break
            position = when {
                text.startsWith("<!--", start) -> {
                    val end = text.indexOf("-->", start + 4)
                    if (end < 0) fail("XML_INVALID")
                    end + 3
                }
                text.startsWith("<![CDATA[", start) -> {
                    val end = text.indexOf("]]>", start + 9)
                    if (end < 0) fail("XML_INVALID")
                    end + 3
                }
                text.startsWith("<!DOCTYPE", start) -> {
                    val match = appleDoctype.find(text, start)
                    if (rootSeen || doctypeSeen || match == null || match.range.first != start) fail("XML_DTD_FORBIDDEN")
                    doctypeSeen = true
                    // Keep line numbers stable for diagnostics. Strings/comments containing markup are untouched.
                    result = result.replaceRange(match.range, match.value.map { if (it == '\r' || it == '\n') it else ' ' }.joinToString(""))
                    match.range.last + 1
                }
                text.startsWith("<!", start) -> fail("XML_DTD_FORBIDDEN")
                else -> {
                    if (!text.startsWith("<?", start)) rootSeen = true
                    // Skip quoted attributes and processing instructions as one token.
                    var end = start + 1
                    var quote: Char? = null
                    while (end < text.length) {
                        val c = text[end]
                        if (quote != null) { if (c == quote) quote = null }
                        else if (c == '\'' || c == '"') quote = c
                        else if (c == '>') break
                        end++
                    }
                    // Only dict tags need the patterns; skipping the rest keeps large files linear and cheap.
                    val dict = text.startsWith("<dict", start) || text.startsWith("</dict", start)
                    val tag = if (dict) text.substring(start, minOf(end + 1, text.length)) else ""
                    if (dict && dictOpen.matches(tag)) dictStart = start
                    if (dict && (dictClose.matches(tag) || dictEmpty.matches(tag))) {
                        dictStart?.let { if (text.substring(it, minOf(end + 1, text.length)).toByteArray(Charsets.UTF_8).size > 64 * 1024) fail("RECORD_LIMIT") }
                        dictStart = null
                    }
                    end + 1
                }
            }
            if (dictStart?.let { position - it > 64 * 1024 } == true) fail("RECORD_LIMIT")
        }
        return result
    }

    fun parse(text: String, encoding: String): ParsedDictionary {
        val safe = withoutDtd(text)
        val handler = Handler()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        // Defense in depth: even an unexpected resolution attempt fails without opening any URI.
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> fail("XML_ENTITY_FORBIDDEN") }
        try { reader.parse(InputSource(StringReader(safe))) }
        catch (error: SAXException) {
            // Some SAX implementations wrap exceptions raised by callbacks; preserve our precise diagnostic.
            val problem = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<DictionaryProblem>().firstOrNull()
            if (problem != null) throw problem
            fail("XML_INVALID", (error as? SAXParseException)?.lineNumber)
        }
        if (!handler.complete) fail("XML_STRUCTURE")
        val lines = text.split(Regex("\r\n|\n|\r"))
        return ParsedDictionary(handler.entries, lines.size - if (lines.last().isEmpty()) 1 else 0, PARSER, encoding)
    }

    private class Handler : DefaultHandler() {
        val entries = ArrayList<ParsedEntry>()
        var complete = false
        private var locator: Locator? = null
        private val stack = ArrayList<String>()
        private val value = StringBuilder()
        private var arraySeen = false
        private var key: String? = null
        private val fields = LinkedHashMap<String, String>()
        private var entryLine = 0
        private var recordBytes = 0
        private fun stop(code: String): Nothing = fail(code, locator?.lineNumber)
        override fun setDocumentLocator(locator: Locator) { this.locator = locator }
        override fun startPrefixMapping(prefix: String?, uri: String?) { stop("XML_NAMESPACE") }
        override fun processingInstruction(target: String?, data: String?) { stop("XML_PROCESSING_INSTRUCTION") }
        override fun skippedEntity(name: String?) { stop("XML_ENTITY_FORBIDDEN") }
        override fun error(e: SAXParseException): Nothing = throw e
        override fun fatalError(e: SAXParseException): Nothing = throw e

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (stack.size >= 16) stop("XML_DEPTH_LIMIT")
            if (uri.isNotEmpty() || qName != localName) stop("XML_NAMESPACE")
            val valid = when (stack.lastOrNull()) {
                null -> qName == "plist" && !complete
                "plist" -> qName == "array" && !arraySeen
                "array" -> qName == "dict"
                "dict" -> if (key == null) qName == "key" else qName == "string"
                else -> false
            }
            if (!valid) stop("XML_STRUCTURE")
            if (qName == "plist") {
                if (attributes.length != 1 || attributes.getQName(0) != "version" || attributes.getValue(0) != "1.0") stop("XML_PLIST_VERSION")
            } else if (attributes.length != 0) stop("XML_ATTRIBUTES")
            when (qName) {
                "array" -> arraySeen = true
                "dict" -> { fields.clear(); key = null; entryLine = locator?.lineNumber ?: 1; recordBytes = 0 }
                "key", "string" -> value.clear()
            }
            stack.add(qName)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if ("dict" in stack) {
                recordBytes += String(ch, start, length).toByteArray(Charsets.UTF_8).size
                if (recordBytes > 64 * 1024) stop("RECORD_LIMIT")
            }
            if (stack.lastOrNull().let { it == "key" || it == "string" }) {
                value.append(ch, start, length)
                if (value.length > 2048) stop("FIELD_LIMIT") // At most 1,024 supplementary code points.
            } else if ((start until start + length).any { ch[it] !in " \t\r\n" }) stop("XML_STRUCTURE")
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (qName) {
                "key" -> {
                    val name = value.toString()
                    if (fields.size >= 8) stop("XML_KEY_LIMIT")
                    if (fields.containsKey(name)) stop("XML_DUPLICATE_KEY")
                    if (name !in setOf("phrase", "shortcut")) stop("XML_UNKNOWN_KEY")
                    key = name
                }
                "string" -> {
                    val field = value.toString()
                    validateValue(field, 1024, locator?.lineNumber ?: entryLine)
                    fields[key ?: stop("XML_STRUCTURE")] = field
                    key = null
                }
                "dict" -> {
                    if (key != null || fields.keys != setOf("phrase", "shortcut")) stop("XML_REQUIRED_KEYS")
                    val word = fields.getValue("phrase")
                    if (word.isBlank()) stop("EMPTY_WORD")
                    // XML can contain multiple dicts on one physical line. Room's row identity must stay unique.
                    entries += ParsedEntry(entries.size + 1, fields.getValue("shortcut"), word, "", "")
                    if (entries.size > TsvParser.MAX_ENTRIES) stop("ENTRY_LIMIT")
                }
                "plist" -> { if (!arraySeen) stop("XML_STRUCTURE"); complete = true }
            }
            stack.removeAt(stack.lastIndex)
        }
    }
}
