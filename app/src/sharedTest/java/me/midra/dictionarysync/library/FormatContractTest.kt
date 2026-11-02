package me.midra.dictionarysync.library

import java.nio.charset.Charset
import me.midra.dictionarysync.data.parser.*
import me.midra.dictionarysync.data.source.MemoryBudget
import org.junit.Assert.*
import org.junit.Test

/** Identical synthetic contracts run on JVM and Android's XML/charset implementation. */
class FormatContractTest {
    private val doctype = "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
    private fun dict(word: String = "合成語", reading: String = "よみ") = "<dict><key>phrase</key><string>$word</string><key>shortcut</key><string>$reading</string></dict>"
    private fun plist(body: String = dict(), prefix: String = "") = "$prefix<plist version=\"1.0\"><array>$body</array></plist>"
    private fun parse(text: String, format: String = "AUTO") = DictionaryParser.parse(text.toByteArray(), format = format)
    private fun rejects(text: String, format: String = "AUTO", code: String? = null): DictionaryProblem {
        val error = assertThrows(DictionaryProblem::class.java) { parse(text, format) }
        if (code != null) assertEquals(code, error.code)
        return error
    }

    @Test fun appleDoctypeAndBothKeyOrdersDecodeEscapesWithoutDtd() {
        val body = "<dict><key>shortcut</key><string>よみ&amp;</string><key>phrase</key><string>語&lt;&gt;&quot;&apos;&#x1f338;</string></dict>"
        val result = parse(plist(body, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$doctype\n"))
        assertEquals("apple-plist-xml-v1", result.parser)
        assertEquals("語<>\"'🌸", result.entries.single().word)
        assertEquals("よみ&", result.entries.single().reading)
        assertEquals("", result.entries.single().comment)
    }
    @Test fun sameLineXmlRecordsHaveDistinctStorageIdentityAndPreserveDuplicates() {
        val result = parse(plist(dict() + dict() + dict("別語", "")))
        assertEquals(listOf(1, 2, 3), result.entries.map { it.line })
        assertEquals(1, result.physicalLines)
        assertEquals("", result.entries.last().reading)
        assertEquals(result.entries[0].word, result.entries[1].word)
    }
    @Test fun structuralEmptyPlistIsValidButEmptyOrHtmlBodyIsNot() {
        assertTrue(parse("<plist version='1.0'><array/></plist>").entries.isEmpty())
        listOf("", " ", "<html>error</html>", "<!DOCTYPE html><html/>", "{\"error\":1}").forEach { rejects(it) }
    }
    @Test fun internalSubsetsEntitiesAndForeignDoctypesAreRejectedBeforeXml() {
        listOf(
            "<!DOCTYPE plist [<!ENTITY e SYSTEM 'file:///never-read'>]>",
            "<!DOCTYPE plist [<!ENTITY a 'expansion'>]>",
            "<!DOCTYPE plist SYSTEM 'https://example.invalid/never-fetch'>",
            doctype.dropLast(1) + " [<!ENTITY e 'value'>]>",
            doctype + doctype,
        ).forEach { rejects(plist(prefix = it), code = "XML_DTD_FORBIDDEN") }
        rejects(plist(dict("&unknown;")))
        rejects(plist(dict("%external;"), "<!ENTITY % external SYSTEM 'https://example.invalid/never-fetch'>"), code = "XML_DTD_FORBIDDEN")
    }
    @Test fun commentsAndCdataCannotSmuggleDtdAndLiteralEscapesArePreserved() {
        assertEquals("<!DOCTYPE text>", parse(plist(dict("<![CDATA[<!DOCTYPE text>]]>"), "<!-- <!DOCTYPE harmless> -->")).entries.single().word)
        rejects(plist(dict(), "<!-- closed --> <!DOCTYPE plist [<!ENTITY e 'value'>]>"))
        rejects(plist().replace("</array>", "$doctype</array>"), code = "XML_DTD_FORBIDDEN")
        rejects(plist(prefix = "<!-- unclosed"))
    }
    @Test fun duplicateMissingUnknownKeysAndUnknownTypesAreRejected() {
        listOf(
            "<dict><key>phrase</key><string>a</string><key>phrase</key><string>b</string></dict>",
            "<dict><key>phrase</key><string>a</string></dict>",
            "<dict><key>other</key><string>a</string></dict>",
            "<dict><key>phrase</key><integer>1</integer></dict>",
            "<dict><key>phrase</key><array/></dict>",
            "<dict/>", "<string>unexpected</string>",
        ).forEach { rejects(plist(it)) }
    }
    @Test fun wrongVersionRootAttributesNamespacesAndExtraTreesAreRejected() {
        listOf(
            "<plist><array/></plist>", "<plist version='2.0'><array/></plist>",
            "<plist version='1.0' extra='x'><array/></plist>", "<array/>",
            "<plist version='1.0'><array/><array/></plist>",
            "<plist xmlns='urn:unknown' version='1.0'><array/></plist>",
            "<plist xmlns:x='urn:unknown' version='1.0'><array/></plist>",
            plist() + plist(), plist().replace("<dict>", "<dict extra='x'>"),
            "<?untrusted action?>" + plist(),
        ).forEach { rejects(it) }
    }
    @Test fun nestedMalformedAndTrailingGarbageXmlAreRejected() {
        rejects("<plist version='1.0'>" + "<array>".repeat(20) + "</array>".repeat(20) + "</plist>")
        rejects(plist().dropLast(1)); rejects(plist() + "garbage")
        rejects(plist(dict("<string>nested</string>")))
        rejects(plist().replace("<array>", "<array>unexpected"))
    }
    @Test fun xmlControlsEmptyWordsAndRecordLimitsUseWholeDocumentValidation() {
        listOf("", " ", "&#x9;", "&#xA;", "&#x0;", "&#xD800;", "語".repeat(1025)).forEach { rejects(plist(dict(it))) }
        rejects(plist(dict().replace("<key>phrase", " ".repeat(65_536) + "<key>phrase")), code = "RECORD_LIMIT")
        rejects(plist(dict() + dict("&#x1;")))
        assertEquals("🌸".repeat(1024), parse(plist(dict("🌸".repeat(1024)))).entries.single().word)
    }
    @Test fun utf16XmlBomSignaturesAndDeclarationsAgree() {
        for ((name, bom) in listOf("UTF-16LE" to byteArrayOf(-1, -2), "UTF-16BE" to byteArrayOf(-2, -1))) {
            val xml = "<?xml version='1.0' encoding='UTF-16'?>" + plist()
            val bytes = xml.toByteArray(Charset.forName(name))
            for (body in listOf(bytes, bom + bytes)) {
                val result = DictionaryParser.parse(body, contentType = "application/xml; charset=$name")
                assertEquals(name, result.encoding); assertEquals("合成語", result.entries.single().word)
            }
        }
    }
    @Test fun declarationsBomManualAndHttpContradictionsAreRejected() {
        val xml = "<?xml version='1.0' encoding='UTF-16'?>" + plist()
        rejects(xml, code = "ENCODING_CONFLICT")
        rejects("<?xml version='1.0' encoding='windows-31j'?>" + plist(), code = "ENCODING_CONFLICT")
        val bytes = byteArrayOf(-17, -69, -65) + plist().toByteArray()
        listOf("application/xml; charset=UTF-16", "application/xml; charset=UTF-8; charset=UTF-16LE").forEach { mime ->
            assertEquals("ENCODING_CONFLICT", assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes, contentType = mime) }.code)
        }
        assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes, encoding = "UTF-16LE") }
    }
    @Test fun csvQuotedCommasEscapedQuotesAndMetadataArePreserved() {
        val parsed = parse("\" よ,み \",\"語\"\"句🌸\",未知品詞,,\"注,釈\"\r\n,別語,,,", "APPLE_CSV")
        assertEquals("apple-csv-v1", parsed.parser)
        assertEquals(" よ,み ", parsed.entries[0].reading); assertEquals("語\"句🌸", parsed.entries[0].word)
        assertEquals("未知品詞", parsed.entries[0].partOfSpeech); assertEquals("注,釈", parsed.entries[0].comment)
        assertEquals("", parsed.entries[1].reading); assertEquals(2, parsed.physicalLines)
    }
    @Test fun csvNeedsManualSelectionAndDoesNotInferHeaders() {
        rejects("よみ,合成語,名詞,,", code = "CSV_MANUAL_REQUIRED")
        rejects("読み,単語,品詞,空欄,コメント", "APPLE_CSV", "CSV_FOURTH_COLUMN")
        rejects("r,w,p,,c,extra", code = "CSV_COLUMNS")
        assertEquals("合成語", parse("よみ,合成語,名詞,,", "APPLE_CSV").entries.single().word)
    }
    @Test fun appleThreeColumnRowsAndMemoRowsMix() {
        val parsed = parse("こうち,高知,地名\r\nとうきょう,\"東京,都\",地名,,メモ", "APPLE_CSV")
        assertEquals(listOf("高知", "東京,都"), parsed.entries.map { it.word })
        assertEquals(listOf("", "メモ"), parsed.entries.map { it.comment })
        assertEquals("地名", parsed.entries[0].partOfSpeech)
        rejects("こうち,高知,地名", code = "CSV_MANUAL_REQUIRED")
    }
    @Test fun gboardV2FormatLineIsNotAnEntryAndLanguageIsNotAPartOfSpeech() {
        val text = "# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n" +
            "\tcomplimarde\tfr-CA\t\n# From OS\nこうち\t高知\tja-jp\t品詞なし\n#tag\t語\tfr-CA\t\n"
        val parsed = parse(text)
        assertEquals("gboard-v2", parsed.parser)
        assertEquals(listOf("complimarde", "高知", "語"), parsed.entries.map { it.word })
        assertEquals(listOf("", "こうち", "#tag"), parsed.entries.map { it.reading })
        assertEquals(listOf("", "品詞なし", ""), parsed.entries.map { it.partOfSpeech })
        assertEquals(listOf("fr-CA", "ja-JP", "fr-CA"), parsed.entries.map { it.locale })
    }
    @Test fun gboardV1HasThreeColumnsAndEmptyLanguageMeansAll() {
        val parsed = parse("# Gboard Dictionary version:1\nこうち\t高知\t\nとうきょう\t東京\t")
        assertEquals("gboard-v1", parsed.parser)
        assertEquals(listOf("ALL", "ALL"), parsed.entries.map { it.locale })
        assertEquals("", parsed.entries[0].partOfSpeech)
        assertEquals(listOf(null), parse("よみ\t語\tja-JP").entries.map { it.locale }) // Without the header, column 3 stays a part of speech.
    }
    @Test fun gboardHeadersAndColumnsAreChecked() {
        val v2 = "# Gboard Dictionary version:2\n"
        rejects("${v2}こうち\t高知\tja-JP\t", code = "GBOARD_FORMAT_REQUIRED")
        rejects("${v2}# Gboard Dictionary format:word\tshortcut\tlanguage_tag\tpos_tag\n", code = "GBOARD_FORMAT_UNSUPPORTED")
        rejects("# Gboard Dictionary version:3\nこうち\t高知\tja-JP", code = "GBOARD_VERSION_UNSUPPORTED")
        rejects("# Gboard Dictionary version:1\nこうち\t高知\tja-JP\t名詞", code = "TSV_COLUMNS_4")
        rejects("# Gboard Dictionary version:1\nこうち\t高知\tja_JP", code = "GBOARD_LANGUAGE_TAG")
    }
    @Test fun atokHeaderAnnotationsCommentsAndBlankExtraColumns() {
        val parsed = parse("!!ATOK_TANGO_TEXT_HEADER_1\n!!address\nこうち\t高知\t固有地名\nにこにこ\tニコニコ\t名詞\t注釈\tしない\t\t\t\t\t\n!!途中の注釈\tタブ入り")
        assertEquals("atok-tango-v1", parsed.parser)
        assertEquals(listOf("高知", "ニコニコ"), parsed.entries.map { it.word })
        assertEquals("注釈", parsed.entries[1].comment)
        assertEquals("atok-tango-v1", parse("!!dicut11\nこうち\t高知\t固有地名").parser)
        rejects("!!DICUT10\nこうち\t高知\t固有地名", code = "ATOK_DICUT_UNSUPPORTED")
        rejects("!!ATOK_TANGO_TEXT_HEADER_1\nこうち\t高知\t名詞\t\tする\t高知県", code = "ATOK_AUTO_REPLACE_UNSUPPORTED")
        rejects("!!ATOK_TANGO_TEXT_HEADER_1\nこうち\t高知\t名詞\t\t\t候補", code = "ATOK_AUTO_REPLACE_UNSUPPORTED")
        rejects("!!ATOK_TANGO_TEXT_HEADER_1\n" + "a\t".repeat(10) + "a", code = "TSV_COLUMNS_11")
        // Without the ATOK header, "!!" is an ordinary reading.
        assertEquals("!!よみ", parse("!!よみ\t語\t名詞").entries.single().reading)
    }
    @Test fun bomlessUtf16AtokAndGboardHeadersAreRecognized() {
        for (name in listOf("UTF-16LE", "UTF-16BE")) {
            for (text in listOf("!!ATOK_TANGO_TEXT_HEADER_1\r\nよみ\t語\t名詞", "# Gboard Dictionary version:1\nよみ\t語\tja")) {
                val result = DictionaryParser.parse(text.toByteArray(Charset.forName(name)))
                assertEquals(name, result.encoding); assertEquals("語", result.entries.single().word)
            }
        }
    }
    @Test fun skkRimeYamlAndArchivesAreNamedNotMisreadAsColumns() {
        val skk = ";; -*- coding: euc-jp -*-\n;; okuri-ari entries.\nわらw /笑/\n;; okuri-nasi entries.\nとうきょう /東京/東経/\n"
        for (format in listOf("AUTO", "TSV", "APPLE_CSV")) {
            assertEquals("SKK_UNSUPPORTED", assertThrows(DictionaryProblem::class.java) {
                DictionaryParser.parse(skk.toByteArray(Charset.forName("EUC-JP")), format = format)
            }.code)
        }
        rejects("# Rime dictionary\n---\nname: example\ncolumns:\n  - text\n  - code\n...\n東京\ttou kyou\t100\n", code = "RIME_UNSUPPORTED")
        rejects("---\nentries: []\n", code = "YAML_UNSUPPORTED")
        val tar = ByteArray(600).also { "ustar".toByteArray().copyInto(it, 257) }
        for (bytes in listOf(byteArrayOf(-3, 0x37, 0x7a, 0x58, 0x5a, 0), "BZh9".toByteArray(), byteArrayOf(0x37, 0x7a, -68, -81, 0x27, 0x1c), byteArrayOf(0x28, -75, 0x2f, -3), tar)) {
            assertEquals("UNSUPPORTED_BINARY", assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes) }.code)
        }
    }
    @Test fun csvWrongColumnsFourthFieldAndMixedTsvRejectEntireSource() {
        listOf("r,w,p,,c,extra", "r,w,p, ,c", "r,w,p,x,c", "r\tw\tp", "r,w,p,,c\nr,w,p,,c,extra").forEach { rejects(it, "APPLE_CSV") }
        assertEquals(2, rejects("r,w,p,,\nr,w,p,nonempty,", "APPLE_CSV").line)
    }
    @Test fun csvFourColumnMemoAndShortLinesAreReadPast() {
        val result = parse("r,w,p,メモ\nメモだけの行\nr2,w2,p\n", "APPLE_CSV")
        assertEquals(listOf("メモ", ""), result.entries.map { it.comment })
        assertEquals(listOf(2), result.skipped)
        // The automatic candidate check stays strict, so a stray short line is not silently suggested as CSV.
        rejects("r,w,p\nshort,line", code = "CSV_COLUMNS")
    }
    @Test fun csvMalformedQuotesAndEmbeddedNewlinesAreNeverRepaired() {
        listOf("r,\"word,p,,", "r,wo\"rd,p,,", "r,\"word\" ,p,,", "r,\"word\"x,p,,", "r,\"wo\nrd\",p,,").forEach { rejects(it, "APPLE_CSV", "CSV_QUOTE") }
    }
    @Test fun csvControlsEmptyWordsAndFieldLimitsMatchTsv() {
        listOf("r, ,p,,", "r,word\u0000,p,,", "r,word,p,,tab\there", "r,${"語".repeat(1025)},p,,", "r,word,${"p".repeat(257)},,", "r,word,p,,${"c".repeat(4097)}").forEach { rejects(it, "APPLE_CSV") }
        assertTrue(parse("\r\n ", "APPLE_CSV").entries.isEmpty())
        assertEquals("r", parse("\n\"r\",w,p,\"\",\"\"", "APPLE_CSV").entries.single().reading)
    }
    @Test fun explicitEncodingsAreSharedAcrossFormats() {
        for ((format, text) in listOf("APPLE_CSV" to "よみ,合成語,名詞,,注釈", "PLIST_XML" to plist())) {
            val bytes = text.toByteArray(Charset.forName("windows-31j"))
            assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes, format = format) }
            assertEquals("合成語", DictionaryParser.parse(bytes, "windows-31j", format).entries.single().word)
            for (name in listOf("UTF-16LE", "UTF-16BE")) {
                assertEquals("合成語", DictionaryParser.parse(text.toByteArray(Charset.forName(name)), name, format).entries.single().word)
            }
        }
    }
    @Test fun windows31jExtensionsAreDecodedWithoutReplacementInEveryFormat() {
        val word = "合成髙﨑①㈱"
        for ((format, text) in listOf("TSV" to "よみ\t$word\t名詞", "APPLE_CSV" to "よみ,$word,名詞,,", "PLIST_XML" to plist(dict(word)))) {
            val result = DictionaryParser.parse(text.toByteArray(Charset.forName("windows-31j")), "windows-31j", format)
            assertEquals("windows-31j", result.encoding)
            assertEquals(word, result.entries.single().word)
        }
    }
    @Test fun mimeAndManualFormatCannotOverrideInvalidBodyOrBinaryMagic() {
        for (format in listOf("AUTO", "APPLE_CSV", "PLIST_XML", "TSV")) {
            for (mime in listOf("text/html", "application/json", "application/problem+json", "application/xhtml+xml")) {
                assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse("r,w,p,,".toByteArray(), format = format, contentType = mime) }
            }
            for (body in listOf("bplist00", "PK\u0003\u0004", "PK\u0005\u0006")) rejects(body, format, "UNSUPPORTED_BINARY")
        }
        rejects(plist(), "APPLE_CSV"); rejects("r,w,p,,", "PLIST_XML")
        assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse("r\tw\tp".toByteArray(), contentType = "application/xml") }
        assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(plist().toByteArray(), contentType = "text/csv") }
    }
    @Test fun invalidUtf8UnsupportedBomAndByteLimitAreRejectedBeforeParsing() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(-1, -2, 0, 0), byteArrayOf(0, 0, -2, -1), byteArrayOf(0x1f, 0x8b.toByte()))) {
            assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes) }
        }
        // A heap that leaves room for 1 KiB of body: the limit follows memory, not a fixed size.
        val budget = MemoryBudget(MemoryBudget.heapFor(1024 * MemoryBudget.PER_BYTE))
        assertEquals(1024L, budget.maxBytes)
        assertEquals("SIZE_LIMIT", assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(ByteArray(1025) { 'a'.code.toByte() }, budget = budget) }.code)
    }
    @Test fun entryLimitAppliesToEveryFormatWithoutReturningPartialResults() {
        // Room for the body and exactly ten entries on top of it.
        fun budget(bytes: ByteArray) = MemoryBudget(MemoryBudget.heapFor(bytes.size * MemoryBudget.PER_BYTE + 10 * MemoryBudget.PER_ENTRY))
        fun limited(text: String, format: String = "AUTO") = text.toByteArray().let { bytes ->
            assertEquals("ENTRY_LIMIT", assertThrows(DictionaryProblem::class.java) { DictionaryParser.parse(bytes, format = format, budget = budget(bytes)) }.code)
        }
        fun fits(text: String, format: String = "AUTO") = text.toByteArray().let { bytes ->
            assertEquals(10, DictionaryParser.parse(bytes, format = format, budget = budget(bytes)).entries.size)
        }
        limited(plist(dict().repeat(11))); fits(plist(dict().repeat(10)))
        limited("r,w,p,,\n".repeat(11), "APPLE_CSV"); fits("r,w,p,,\n".repeat(10), "APPLE_CSV")
        limited("r\tw\tp\n".repeat(11)); fits("r\tw\tp\n".repeat(10))
    }
    @Test fun formerFixedLimitsNoLongerApply() {
        // Twice the former 100,000-entry limit, in the formats with the largest bodies per entry.
        val n = 200_000
        // Apple's own export indents every element; Microsoft IME exports UTF-16LE.
        val apple = (1..n).joinToString("", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<array>\n", "</array>\n</plist>\n") {
            "\t<dict>\n\t\t<key>phrase</key>\n\t\t<string>人工の単語表記$it</string>\n\t\t<key>shortcut</key>\n\t\t<string>じんこうのたんご$it</string>\n\t</dict>\n"
        }
        val ms = (1..n).joinToString("\r\n", "!Microsoft IME Dictionary Tool\r\n!Format:WORDLIST\r\n") { "じんこうのたんご$it\t人工の単語表記$it\t名詞\t人工的なコメント" }
        for (bytes in listOf(apple.toByteArray(), byteArrayOf(-1, -2) + ms.toByteArray(Charsets.UTF_16LE))) {
            // Only memory limits a dictionary now; a device whose heap cannot hold this one skips the check.
            org.junit.Assume.assumeTrue(MemoryBudget().maxEntries(bytes.size.toLong()) >= n)
            assertEquals(n, DictionaryParser.parse(bytes).entries.size)
        }
    }
}
