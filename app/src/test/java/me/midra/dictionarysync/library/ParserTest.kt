package me.midra.dictionarysync.library

import java.nio.charset.Charset
import me.midra.dictionarysync.data.parser.*
import me.midra.dictionarysync.data.source.SourceRules
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    private fun parse(text: String) = TsvParser.parse(text.toByteArray())
    @Test fun threeAndFourColumnsPreserveExactUnicodeAndDuplicates() {
        val result = parse("#合成\r\nよみ\t 単語🌸 \t名詞\rよみ\t 単語🌸 \t名詞\t注釈")
        assertEquals(2, result.entries.size)
        assertEquals(" 単語🌸 ", result.entries[0].word)
        assertEquals("注釈", result.entries[1].comment)
        assertEquals(3, result.physicalLines)
    }
    @Test fun malformedLaterRowRejectsWholeDocumentWithLine() {
        val error = assertThrows(DictionaryProblem::class.java) { parse("a\tb\tc\na\tb\tc\td\te\tf\tg") }
        assertEquals(2, error.line); assertEquals("TSV_COLUMNS_7", error.code)
    }
    @Test fun msImeExtraColumnsJoinTheCommentButPlainTsvStaysStrict() {
        val ms = "!Microsoft IME Dictionary Tool\r\n!Format:WORDLIST\r\nよみ\t語\t人名\t\t余分なタブの後のコメント\r\nよみ\t別語\t人名\tコメント\t続き\t\r\n"
        val entries = TsvParser.parse(ms.toByteArray(Charsets.UTF_16LE), contentType = "text/plain; charset=utf-16le").entries
        assertEquals(listOf("余分なタブの後のコメント", "コメント 続き"), entries.map { it.comment })
        assertEquals("TSV_COLUMNS_5", assertThrows(DictionaryProblem::class.java) { parse("よみ\t語\t人名\t\tコメント") }.code)
    }
    @Test fun trailingEmptyColumnsAreDroppedButDataBeyondTheLastColumnIsNot() {
        assertEquals("注釈", parse("よみ\t語\t名詞\t注釈\t\t").entries.single().comment)
        assertEquals("ALL", TsvParser.parse("# Gboard Dictionary version:1\nよみ\t語\t\t\n".toByteArray()).entries.single().locale)
        assertEquals("TSV_COLUMNS_6", assertThrows(DictionaryProblem::class.java) { parse("よみ\t語\t名詞\t\t\tデータ") }.code)
    }
    @Test fun confirmedFormatsReadPastShortLinesButAutomaticPlainTsvDoesNot() {
        val ms = "!Microsoft IME Dictionary Tool\r\n!Format:WORDLIST\r\nよみ\t語\t名詞\r\nよみだけ\r\n! 注\tタブ入りの注記\r\n\t\t\r\n"
        val result = TsvParser.parse(ms.toByteArray(Charsets.UTF_16LE), contentType = "text/plain; charset=utf-16le")
        assertEquals(1, result.entries.size); assertEquals(listOf(4, 5, 6), result.skipped)
        assertEquals(listOf(2), TsvParser.parse("よみ\t語\t名詞\nよみ\t語だけ".toByteArray(), format = "TSV").skipped)
        assertEquals("TSV_COLUMNS_2", assertThrows(DictionaryProblem::class.java) { parse("よみ\t語\t名詞\nよみ\t語だけ") }.code)
        // Only short lines: the wrong format was chosen.
        assertEquals("TSV_COLUMNS_SHORT", assertThrows(DictionaryProblem::class.java) {
            TsvParser.parse("a,b,c\nd,e,f".toByteArray(), format = "TSV") }.code)
    }
    @Test fun hashAndBangReadingsAreNotCommentsWhenTabsExist() {
        assertEquals(2, parse("#よみ\t語\t名詞\n!よみ\t別語\t名詞").entries.size)
    }
    @Test fun structuralEmptyIsAValidDictionaryButRawEmptyIsRejected() {
        assertTrue(parse("# 正当に空の合成TSV\n").entries.isEmpty())
        assertThrows(DictionaryProblem::class.java) { parse("") }
        assertThrows(DictionaryProblem::class.java) { parse(" \r\n ") }
        assertTrue(TsvParser.parse(" \n".toByteArray(), format = "TSV").entries.isEmpty())
    }
    @Test fun utf16BomAndKnownBomlessMsHeaderAreRecognized() {
        val text = "よみ\t語\t名詞"
        assertEquals("語", TsvParser.parse(byteArrayOf(-1, -2) + text.toByteArray(Charsets.UTF_16LE)).entries.single().word)
        assertEquals("語", TsvParser.parse(byteArrayOf(-2, -1) + text.toByteArray(Charsets.UTF_16BE)).entries.single().word)
        val ms = "!Microsoft IME Dictionary Tool\r\n!Format:WORDLIST\r\n$text"
        assertEquals("ms-wordlist-v1", TsvParser.parse(ms.toByteArray(Charsets.UTF_16LE), contentType = "text/plain; charset=utf-16le").parser)
    }
    @Test fun malformedEncodingAndBomConflictAreRejected() {
        assertThrows(DictionaryProblem::class.java) { TsvParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(DictionaryProblem::class.java) { TsvParser.parse(byteArrayOf(-1, -2) + "a\tb\tc".toByteArray(Charsets.UTF_16LE), contentType = "text/plain; charset=utf-8") }
        assertThrows(DictionaryProblem::class.java) { TsvParser.parse(byteArrayOf(-1, -2, 0, 0)) }
    }
    @Test fun cp932RequiresExplicitChoice() {
        val bytes = "よみ\t語\t名詞".toByteArray(Charset.forName("windows-31j"))
        assertThrows(DictionaryProblem::class.java) { TsvParser.parse(bytes) }
        assertEquals("語", TsvParser.parse(bytes, "windows-31j").entries.single().word)
    }
    @Test fun htmlJsonAndWrongColumnCountCannotBecomeEmpty() {
        listOf("<!DOCTYPE html><html>oops", "{\"error\":true}", "a\tb", "a,b,c,,d").forEach { text ->
            assertThrows(DictionaryProblem::class.java) { parse(text) }
        }
        assertThrows(DictionaryProblem::class.java) { TsvParser.parse("a\tb\tc".toByteArray(), contentType = "text/html") }
    }
    @Test fun controlsEmptyWordsAndOversizedFieldsAreRejected() {
        listOf("r\t \tp", "r\tword\u0000\tp", "r\t" + "語".repeat(1025) + "\tp").forEach { text ->
            assertThrows(DictionaryProblem::class.java) { parse(text) }
        }
    }
    @Test fun reorderedEntriesHaveSameContentDigestButMetadataChangesDoNot() {
        val a = parse("a\tb\tc\nq\tw\te").entries
        assertEquals(LibraryRepository.contentHash(a), LibraryRepository.contentHash(a.reversed()))
        assertNotEquals(LibraryRepository.contentHash(a), LibraryRepository.contentHash(a.map { it.copy(comment = "changed") }))
    }
    @Test fun urlsPreserveQueryOrderingDuplicatesAndEscapes() {
        val url = "https://example.org/dictionary?a=1&a=2&b=%2f+%20"
        assertEquals(url, SourceRules.url(url).toString())
        assertEquals(url, SourceRules.url("  $url#ignored  ").toString())
        listOf("http://example.org/d", "https://user:pass@example.org/d", "file:///tmp/d").forEach {
            assertThrows(DictionaryProblem::class.java) { SourceRules.url(it) }
        }
    }
    @Test fun declaredLanguagesApplyPerEntryOnlyUnderAuto() {
        val gboard = parse("# Gboard Dictionary version:1\nよみ\t語\tfr-CA\nよみ\t語\ten-CA\nよみ\t語\t").entries
        assertEquals(listOf("fr-CA", "en-CA", "ALL"), LibraryRepository.resolved(gboard, "AUTO").map { it.locale })
        assertEquals(listOf("ja", "ja", "ja"), LibraryRepository.resolved(gboard, "ja").map { it.locale })
        assertEquals(listOf("ja"), LibraryRepository.resolved(parse("よみ\t語\t名詞").entries, "AUTO").map { it.locale })
        // Same word and reading in different languages are different shared-dictionary keys.
        assertEquals(3, LibraryRepository.uniqueCount(LibraryRepository.resolved(gboard, "AUTO")))
        assertEquals(1, LibraryRepository.uniqueCount(LibraryRepository.resolved(gboard, "ja")))
        assertNotEquals(LibraryRepository.contentHash(LibraryRepository.resolved(gboard, "AUTO")), LibraryRepository.contentHash(LibraryRepository.resolved(gboard, "ja")))
    }
    @Test fun localeAndLiteralSearchAreExplicit() {
        assertEquals("ja-JP", SourceRules.locale("ja-jp")); assertEquals("ALL", SourceRules.locale("ALL"))
        assertThrows(DictionaryProblem::class.java) { SourceRules.locale("ja_JP") }
        assertEquals("%a\\%\\_\\\\%", SourceRules.like("a%_\\"))
    }
}
