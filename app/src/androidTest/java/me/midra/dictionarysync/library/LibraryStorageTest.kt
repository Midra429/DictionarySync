package me.midra.dictionarysync.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.seed
import me.midra.dictionarysync.testApp

/** In-memory Room + synthetic HTTP bodies. Never calls UserDictionary or changes device settings. */
@RunWith(AndroidJUnit4::class)
class LibraryStorageTest {
    @Test fun failedFetchAndEmptyCandidatePreservePreviousGeneration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            var body = "よみ\t合成語\t名詞\nよみ\t別の合成語\t名詞"
            val repository = testApp(context, db) { url, _, _ ->
                val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val id = repository.editor.seed(null, "合成試験", "https://example.org/a?x=1&x=2", "ja", "AUTO", "AUTO")
            repository.fetcher.fetch(id)
            val first = db.library().source(id)!!
            assertEquals(FetchState.FETCHED, first.state)
            val snapshot = first.latestSnapshot!!
            assertEquals(2, db.library().entries(snapshot).size)
            body = "よみ\t語\t名詞\n不正な2列\tデータ"
            repository.fetcher.fetch(id)
            val failed = db.library().source(id)!!
            assertEquals(FetchState.FETCH_FAILED, failed.state)
            assertEquals(snapshot, failed.latestSnapshot)
            assertEquals(first.fetchedAt, failed.fetchedAt)
            assertEquals(2, db.library().entries(snapshot).size)
            body = "<html>error</html>"
            repository.fetcher.fetch(id)
            assertEquals(snapshot, db.library().source(id)!!.latestSnapshot)
            body = "# 構造が確定した空のTSV\n"
            repository.fetcher.fetch(id)
            val empty = db.library().source(id)!!
            assertEquals(FetchState.FETCHED, empty.state)
            assertEquals(snapshot, empty.previousSnapshot)
            assertTrue(db.library().entries(empty.latestSnapshot!!).isEmpty())
            assertEquals(2, db.library().entries(snapshot).size)
            assertNull(empty.activeSnapshot)
        } finally { db.close() }
    }

    @Test fun failedRoomTransactionRollsBackAndUrlIsNeverNormalized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val repository = testApp(context, db)
            val url = "https://example.org/a?x=1&x=2&b=%2f+%20"
            val id = repository.editor.seed(null, "合成試験", url, "ja-JP", "TSV", "UTF-8")
            val before = db.library().source(id)!!
            try {
                db.withTransaction { db.library().updateSource(before.copy(name = "中断")); error("synthetic crash") }
                fail("Expected rollback")
            } catch (_: IllegalStateException) { /* expected */ }
            assertEquals(before, db.library().source(id))
            assertEquals(url, db.library().source(id)!!.url)
            repository.editor.seed(id, "名前だけ変更", url, "ja-JP", "TSV", "UTF-8")
            assertEquals(before.revision, db.library().source(id)!!.revision)
            repository.editor.seed(id, "言語変更", url, "ja", "TSV", "UTF-8")
            assertEquals(before.revision + 1, db.library().source(id)!!.revision)
            assertTrue(repository.sourceSync.delete(id)) // Never applied: deleted without the Provider.
            assertTrue(db.library().sources().isEmpty())
        } finally { db.close() }
    }
}
