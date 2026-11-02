package me.midra.dictionarysync.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp

/**
 * Opt-in: seeds synthetic sources in representative states into the isolated acceptance app for manual screenshots.
 * No network or Provider access. Run with `adb shell am instrument -e seedScreenshots 1 ...`.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotFixture {
    @Test fun seed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedScreenshots") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "me.midra.dictionarysync.acceptance") { "Isolated acceptance app only" }
        val words = (1..1240).joinToString("\n") { "よみ$it\t合成語$it\t名詞" } + "\nとうきょう\t東京\t地名\tコメント付きの行"
        val gboard = "# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n" +
            "こうち\t高知\tja-JP\t\nとうきょう\t東京\tja-JP\t\nbrb\tbe right back\ten-US\t\nbrb\tbe right back\ten-GB\t\n" +
            "\tcomplimarde\tfr-CA\t\nにこにこ\tニコニコ\t\t\n"
        val repo = testApp(context) { url, _, _ ->
            val bytes = (if (url.endsWith("/gboard")) gboard else words).toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
        val dao = repo.database.library()
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        fun seed(name: String, path: String, format: String = "TSV", change: (me.midra.dictionarysync.data.local.SourceEntity) -> me.midra.dictionarysync.data.local.SourceEntity) = runBlocking {
            val id = dao.sources().find { it.url == "https://example.org/$path" }?.id ?: repo.editor.save(null, name, "https://example.org/$path", "AUTO", format, "AUTO")
            dao.updateSource(change(dao.source(id)!!))
        }
        seed("IME共通辞書（合成）", "synced") { it.copy(syncState = SyncState.SYNCED, activeSnapshot = it.latestSnapshot, fetchedAt = now - 3 * hour) }
        seed("地名辞書（合成）", "failed") { it.copy(state = FetchState.FETCH_FAILED, error = "HTTP_404", syncState = SyncState.SYNCED, activeSnapshot = it.latestSnapshot, fetchedAt = now - 30 * hour) }
        seed("人名辞書（合成）", "diagnosis") { it.copy(syncState = SyncState.SYNC_FAILED, syncError = "DIAGNOSIS_REQUIRED", syncIntervalHours = 168, fetchedAt = now - 20 * 60_000) }
        seed("Gboard辞書（合成）", "gboard", "AUTO") { it.copy(syncState = SyncState.SYNCED, activeSnapshot = it.latestSnapshot, fetchedAt = now - 2 * hour) }
        seed("無効にしたソース（合成）", "disabled") { it.copy(enabled = false, syncState = SyncState.REMOVED, activeSnapshot = null, syncIntervalHours = 6) }
    }
}
