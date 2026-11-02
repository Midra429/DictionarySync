package me.midra.dictionarysync

import java.io.File
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.MemoryBudget
import me.midra.dictionarysync.library.SnapshotStore
import me.midra.dictionarysync.sync.PlanKey
import me.midra.dictionarysync.sync.SourceReference
import me.midra.dictionarysync.sync.SyncKey
import org.junit.Assume
import org.junit.Test

/** One stage for n entries, run in a child JVM whose heap is searched for (see [MemoryMeasurement]). */
object MemoryMeasurementStage {
    fun body(format: String, n: Int, pad: Int): ByteArray {
        val c = "注".repeat(pad)
        return when (format) {
            "tsv" -> buildString { for (i in 1..n) append("じんこうのたんご").append(i).append("\t人工の単語表記").append(i).append("\t名詞\t").append(c).append('\n') }.toByteArray()
            "ascii" -> buildString { for (i in 1..n) append("r").append(i).append("\tw").append(i).append("\tn\t").append("c".repeat(pad)).append('\n') }.toByteArray()
            "ms" -> byteArrayOf(-1, -2) + buildString {
                append("!Microsoft IME Dictionary Tool\r\n!Format:WORDLIST\r\n")
                for (i in 1..n) append("じんこうのたんご").append(i).append("\t人工の単語表記").append(i).append("\t名詞\t").append(c).append("\r\n")
            }.toByteArray(Charsets.UTF_16LE)
            "plist" -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<array>\n")
                for (i in 1..n) append("\t<dict>\n\t\t<key>phrase</key>\n\t\t<string>人工の単語表記").append(i).append(c)
                    .append("</string>\n\t\t<key>shortcut</key>\n\t\t<string>じんこうのたんご").append(i).append("</string>\n\t</dict>\n")
                append("</array>\n</plist>\n")
            }.toByteArray()
            else -> error(format)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val (stage, format, count, pad) = args
        val n = count.toInt()
        when (stage) {
            "size" -> { println(body(format, n, pad.toInt()).size); return }
            "fetch" -> {
                // The download is held while parsing, and the candidate until it is stored.
                val download = Download(body(format, n, pad.toInt()), "0".repeat(64), "https://example.org/d", null)
                val parsed = DictionaryParser.parse(download.bytes, budget = MemoryBudget(Long.MAX_VALUE / 4))
                check(SnapshotStore.Candidate.of(download, parsed, "AUTO").entries.size == n)
            }
            "sync" -> {
                // A superset of what planning, applying and recovery hold at once for n keys.
                val entries = (1..n).map { EntryEntity("s", it, "じんこうのたんご$it", "人工の単語表記$it", "名詞", "", "ja") }
                val keys = entries.map { SyncKey.of(it.locale, it.word, it.reading) }
                val planKeys = keys.map { PlanKey("plan-00000000-0000-0000-0000-000000000000", it.id, true) }
                val byId = keys.associateBy { it.id }
                val involved = planKeys.filter { it.desired }.map { byId.getValue(it.keyId) }.distinctBy { it.id }
                val refs = keys.map { SourceReference("source-00000000-0000-0000-0000-000000000000", "snap-00000000-0000-0000-0000-000000000000", it.id) }
                check(entries.size + involved.size + refs.map { it.keyId }.toHashSet().size == 3 * n)
            }
        }
        println("OK")
    }
}

/**
 * Measures the smallest -Xmx (MiB) that runs each stage, the basis of [MemoryBudget]'s costs. Not part of the suite:
 * MEASURE_MEMORY=1 MEASURE_OUT=/path/out.txt ./gradlew :app:testDebugUnitTest --tests '*MemoryMeasurement*'
 */
class MemoryMeasurement {
    private val java = ProcessHandle.current().info().command().get()
    private fun run(xmx: Int, vararg args: String): String {
        val process = ProcessBuilder(java, "-Xmx${xmx}m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            MemoryMeasurementStage::class.java.name, *args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) out.trim() else ""
    }
    private fun minimum(vararg args: String): Int {
        var lo = 4; var hi = 2048
        while (lo < hi) { val mid = (lo + hi) / 2; if (run(mid, *args).endsWith("OK")) hi = mid else lo = mid + 1 }
        return lo
    }

    @Test fun measure() {
        Assume.assumeTrue(System.getenv("MEASURE_MEMORY") == "1")
        val out = File(System.getenv("MEASURE_OUT")).apply { writeText("") }
        for ((format, pad) in listOf("tsv" to 0, "tsv" to 40, "ascii" to 0, "ascii" to 200, "ms" to 0, "plist" to 0)) {
            for (n in listOf(50_000, 150_000)) {
                val bytes = run(4096, "size", format, "$n", "$pad")
                out.appendText("fetch $format pad=$pad n=$n bytes=$bytes minXmxMiB=${minimum("fetch", format, "$n", "$pad")}\n")
            }
        }
        for (n in listOf(50_000, 150_000, 300_000)) out.appendText("sync n=$n minXmxMiB=${minimum("sync", "-", "$n", "0")}\n")
    }
}
