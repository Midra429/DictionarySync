package me.midra.dictionarysync.data.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import me.midra.dictionarysync.data.source.Downloader
import me.midra.dictionarysync.library.SnapshotStore
import org.junit.Assert.*
import org.junit.Test

/** The memory-saving rewrites must give exactly what the code they replaced gave. */
class LowMemoryEquivalenceTest {
    private val texts = listOf("", "a", "\n", "\r", "\r\n", "\n\r", "a\nb", "a\r\nb\r\n", "a\rb\r", "a\n\n", "\r\r\n\n", "x\r\n\r\ny", "末尾なし\t語", "a b\u0085c")

    @Test fun linesSplitLikeTheRegex() {
        for (text in texts) {
            val expected = text.split(Regex("\r\n|\n|\r"))
            assertEquals(text, expected, TsvParser.lines(text).toList())
            assertEquals(text, expected.size - if (expected.last().isEmpty()) 1 else 0, TsvParser.physicalLines(text))
        }
    }

    @Test fun skkMarkersAreFoundAtTheSameLineStartsAsTheRegex() {
        val regex = Regex("(?m)^;; okuri-(ari|nasi) entries\\.")
        val bodies = listOf(";; okuri-ari entries.", "x\n;; okuri-nasi entries.\n", "x\r;; okuri-ari entries.", "x\u0085;; okuri-ari entries.",
            " ;; okuri-ari entries.", "x;; okuri-ari entries.", ";; okuri-ari entries", ";; okuri-nasi entries!", "é\n;; okuri-nasi entries.")
        for (body in bodies) {
            val bytes = body.toByteArray(Charsets.ISO_8859_1)
            val expected = regex.containsMatchIn(String(bytes, Charsets.ISO_8859_1))
            val actual = try { DictionaryParser.parse(bytes); false } catch (e: DictionaryProblem) { e.code == "SKK_UNSUPPORTED" }
            assertEquals(body, expected, actual)
        }
    }

    @Test fun contentHashIsUnchanged() {
        fun old(entries: List<ParsedEntry>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                entries.map { listOf(it.reading, it.word, it.partOfSpeech, it.comment, it.locale.orEmpty()) }
                    .map { it to it.joinToString("\u0000") }.sortedBy { it.second }.forEach { (row) ->
                        row.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
                    }
            }
            return Downloader.sha256(bytes.toByteArray())
        }
        val random = java.util.Random(7)
        val alphabet = listOf("", "a", "ab", "b", "あ", "😀", "ａ", " ", "A")
        fun value() = (0 until random.nextInt(3)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
        repeat(50) {
            val entries = (1..random.nextInt(40)).map { ParsedEntry(it, value(), value().ifEmpty { "w" }, value(), value(), listOf(null, "ja", "ALL")[random.nextInt(3)]) }
            assertEquals(old(entries), SnapshotStore.contentHash(entries))
            assertEquals(SnapshotStore.contentHash(entries), SnapshotStore.contentHash(entries.shuffled(random)))
        }
    }
}
