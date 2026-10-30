package me.midra.dictionarysync.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.probe.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent in-memory databases and synthetic HTTP + Provider. No UserDictionary writes. */
@RunWith(AndroidJUnit4::class)
class FormatBatchStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val xml get() = "<plist version='1.0'><array><dict><key>phrase</key><string>共有語</string><key>shortcut</key><string>よみ</string></dict></array></plist>"
    private class Gateway : SyncGateway {
        val rows = linkedMapOf<Long, ProbeRow>()
        var inserts = 0
        var deletes = 0
        override fun fingerprint() = "synthetic-formats"
        override fun checkAccess() = fingerprint()
        override fun find(key: SyncKey) = rows.values.filter { key.matches(it) }
        override fun byId(id: Long) = rows[id]
        override fun insert(value: ProbeValue): Long {
            inserts++
            val id = (rows.keys.maxOrNull() ?: 0) + 1
            rows[id] = value.row(id)
            return id
        }
        override fun delete(row: ProbeRow): Int = if (rows[row.id] == row) { rows.remove(row.id); deletes++; 1 } else 0
    }
    private suspend fun prepare(db: DictionaryDatabase, engine: SyncEngine, id: String): SyncPreview {
        val s = db.library().source(id)!!
        val snapshot = db.library().snapshot(s.latestSnapshot!!)!!
        return engine.prepare(id, s.revision, s.activeSnapshot, snapshot.id, snapshot.bodyHash,
            db.library().entries(snapshot.id).map { SyncKey.of(it.locale, it.word, it.reading) })
    }

    @Test fun mixedFormatsShareOneKeyAndFailedSourceRetainsItsReferenceDuringBatch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val bodies = mutableMapOf("https://example.org/a" to xml, "https://example.org/b" to "よみ,共有語,名詞,,注釈", "https://example.org/c" to "つぎ\t第三語\t名詞")
            val calls = mutableListOf<String>()
            val library = LibraryRepository(context, db) { url, _, _ ->
                calls.add(url)
                val bytes = bodies.getValue(url).toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val a = library.seedSource(null, "XML", "https://example.org/a", "ja", "AUTO", "AUTO")
            val b = library.seedSource(null, "CSV", "https://example.org/b", "ja", "APPLE_CSV", "AUTO")
            val c = library.seedSource(null, "TSV", "https://example.org/c", "ja", "TSV", "AUTO")
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "synthetic-install")
            assertEquals(3, library.fetchAll().count { it.state == "FETCHED" })
            for (id in listOf(a, b, c)) { val p = prepare(db, engine, id); engine.apply(p.plan.id, p.plan.token) }
            assertEquals(2, gateway.inserts); assertEquals(3, db.sync().references().size)
            val beforeA = db.library().source(a)!!
            val references = db.sync().references()
            val oldRows = gateway.rows.toMap()
            bodies["https://example.org/a"] = "<html>synthetic failure</html>"
            bodies["https://example.org/b"] = "べつ,新語,名詞,,新注釈"
            bodies["https://example.org/c"] = "# 正当な空候補"
            calls.clear()
            val results = library.fetchAll()
            assertEquals(3, calls.size); assertEquals(3, calls.toSet().size)
            assertEquals(setOf("FETCH_FAILED", "FETCHED"), results.map { it.state }.toSet())
            val failed = db.library().source(a)!!
            assertEquals(beforeA.latestSnapshot, failed.latestSnapshot); assertEquals(beforeA.activeSnapshot, failed.activeSnapshot)
            assertEquals(beforeA.fetchedAt, failed.fetchedAt); assertEquals(beforeA.syncedAt, failed.syncedAt)
            assertEquals(references, db.sync().references()); assertEquals(oldRows, gateway.rows)
            assertEquals(3, db.sync().plans().size) // Fetching never prepares or approves a Plan.
            val p = prepare(db, engine, b)
            assertTrue(p.ownedDeletes.isEmpty()) // XML still needs the shared key.
            engine.apply(p.plan.id, p.plan.token)
            assertEquals(0, gateway.deletes)
            assertTrue(gateway.rows.values.any { it.word == "共有語" })
            assertEquals(beforeA.activeSnapshot, db.sync().references().single { it.sourceId == a }.snapshotId)
            assertEquals(beforeA.activeSnapshot, db.library().source(a)!!.activeSnapshot)
        } finally { db.close() }
    }

    @Test fun batchPreservesUnapprovedPlanJournalAndPinnedCandidateAcrossRepeatedFetches() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            var body = xml
            val library = LibraryRepository(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "application/xml") }
            val id = library.seedSource(null, "XML", "https://example.org/a", "ja", "PLIST_XML", "AUTO")
            library.fetchAll()
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "synthetic-install")
            val preview = prepare(db, engine, id)
            val journal = db.sync().operations(preview.plan.id)
            repeat(4) { body = xml.replace("共有語", "新語$it"); library.fetchAll() }
            assertEquals(preview.plan, db.sync().plans().single { it.id == preview.plan.id })
            assertEquals(journal, db.sync().operations(preview.plan.id))
            assertNotNull(db.library().snapshot(preview.plan.newSnapshot!!))
            assertFalse(db.sync().plans().single { it.id == preview.plan.id }.approved); assertEquals(0, gateway.inserts)
            assertTrue(db.sync().references().isEmpty()); assertNull(db.library().source(id)!!.activeSnapshot)
            try { engine.apply(preview.plan.id, preview.plan.token); fail() } catch (e: SyncStopped) { assertEquals("PLAN_STALE", e.code) }
            assertEquals(0, gateway.inserts)
        } finally { db.close() }
    }

    @Test fun malformedXmlCsvAndMimeFailuresNeverPublishPartialSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            var body = xml
            var mime = "text/plain"
            val library = LibraryRepository(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, mime) }
            val id = library.seedSource(null, "自動形式", "https://example.org/a", "ja", "AUTO", "AUTO")
            library.fetch(id)
            val before = db.library().source(id)!!
            val snapshot = db.library().snapshot(before.latestSnapshot!!)!!
            for ((bad, type) in listOf(xml.dropLast(3) to "application/xml", xml.replace("<key>shortcut", "<key>phrase") to "text/plain", "r,w,p,,\nr,w,p,,extra,column" to "text/csv", xml to "text/html", "r\tw\tp" to "application/xml")) {
                body = bad; mime = type; library.fetch(id)
                val s = db.library().source(id)!!
                assertEquals("FETCH_FAILED", s.state); assertEquals(before.latestSnapshot, s.latestSnapshot)
                assertEquals(before.fetchedAt, s.fetchedAt); assertEquals(listOf(snapshot.id), db.library().snapshotIds(id))
                assertEquals(1, db.library().entries(snapshot.id).size)
            }
            body = "よみ,共有語,名詞,,注釈"; mime = "text/csv"; library.fetch(id)
            assertEquals("CSV_MANUAL_REQUIRED", db.library().source(id)!!.error)
            library.seedSource(id, before.name, before.url, "ja", "APPLE_CSV", "AUTO"); library.fetch(id)
            val csv = db.library().snapshot(db.library().source(id)!!.latestSnapshot!!)!!
            assertEquals("apple-csv-v1", csv.parser)
            assertEquals("注釈", db.library().entries(csv.id).single().comment)
        } finally { db.close() }
    }

    @Test fun gboardLanguagesBecomePerEntryKeysUnlessTheSourceNamesOne() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val body = "# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n" +
                "よみ\t共有語\tfr-CA\t\nよみ\t共有語\ten-CA\t\nよみ\t共有語\t\t\n"
            val library = LibraryRepository(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain") }
            val gateway = Gateway()
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "synthetic-install") }
            val id = library.seedSource(null, "Gboard", "https://example.org/g", "AUTO", "AUTO", "AUTO")
            library.fetch(id)
            val snapshot = db.library().snapshot(db.library().source(id)!!.latestSnapshot!!)!!
            assertEquals("gboard-v2", snapshot.parser); assertEquals("ja", snapshot.resolvedLocale)
            assertEquals(listOf("fr-CA", "en-CA", "ALL"), db.library().entries(snapshot.id).map { it.locale })
            assertEquals(3, snapshot.uniqueCount)
            assertTrue(sync.synchronize(id))
            assertEquals(setOf("fr_CA", "en_CA", null), gateway.rows.values.map { it.locale }.toSet())
            // A manual language overrides what the file declares, so the three rows collapse into one key.
            library.seedSource(id, "Gboard", "https://example.org/g", "ja", "AUTO", "AUTO"); library.fetch(id)
            val manual = db.library().snapshot(db.library().source(id)!!.latestSnapshot!!)!!
            assertEquals(listOf("ja", "ja", "ja"), db.library().entries(manual.id).map { it.locale }); assertEquals(1, manual.uniqueCount)
            assertTrue(sync.synchronize(id))
            assertEquals(listOf("ja"), gateway.rows.values.map { it.locale })
        } finally { db.close() }
    }

    @Test fun sameLineXmlDuplicateRowsStoreAndEmptyCandidateRemainsUnapproved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            var body = xml.replace("</array>", xml.substringAfter("<array>").substringBefore("</array>") + "</array>")
            val library = LibraryRepository(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "application/xml") }
            val id = library.seedSource(null, "同一行XML", "https://example.org/a", "ja", "AUTO", "AUTO")
            library.fetchAll()
            val first = db.library().snapshot(db.library().source(id)!!.latestSnapshot!!)!!
            assertEquals(2, first.count); assertEquals(1, first.uniqueCount); assertEquals(1, first.lines)
            assertEquals(listOf(1, 2), db.library().entries(first.id).map { it.line })
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "synthetic-install")
            val initial = prepare(db, engine, id); engine.apply(initial.plan.id, initial.plan.token)
            body = "<plist version='1.0'><array/></plist>"; library.fetchAll()
            assertEquals("FETCHED", db.library().source(id)!!.state)
            val empty = prepare(db, engine, id)
            assertEquals(1, empty.ownedDeletes.size)
            try { engine.apply(empty.plan.id, "wrong-candidate"); fail() } catch (e: SyncStopped) { assertEquals("APPROVAL_REQUIRED", e.code) }
            assertEquals(0, gateway.deletes); assertEquals(first.id, db.library().source(id)!!.activeSnapshot)
            assertEquals(1, db.sync().references().size); assertFalse(db.sync().plans().single { it.id == empty.plan.id }.approved)
        } finally { db.close() }
    }

    @Test fun networkWaitDoesNotStopOtherSourcesAndDeletionPendingIsNotFetched() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val calls = mutableListOf<Pair<String, Boolean>>()
            val library = LibraryRepository(context, db) { url, wifi, _ ->
                calls += url to wifi
                if (url.endsWith("wait")) throw DictionaryProblem("WIFI_WAIT")
                val bytes = "r,w,p,,".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/csv")
            }
            val wait = library.seedSource(null, "待機", "https://example.org/wait", "ja", "APPLE_CSV", "AUTO")
            val ok = library.seedSource(null, "成功", "https://example.org/ok", "ja", "APPLE_CSV", "AUTO")
            val deleting = library.seedSource(null, "削除処理中", "https://example.org/deleting", "ja", "APPLE_CSV", "AUTO")
            db.library().updateSource(db.library().source(deleting)!!.copy(lifecycle = "DELETE_PENDING"))
            val result = library.fetchAll()
            assertEquals(setOf(wait, ok), result.map { it.id }.toSet())
            assertEquals(2, calls.size); assertEquals(1, calls.map { it.second }.toSet().size)
            assertEquals("NETWORK_WAIT", db.library().source(wait)!!.state)
            assertEquals("FETCHED", db.library().source(ok)!!.state)
            assertNull(db.library().source(deleting)!!.latestSnapshot)
            assertTrue(db.sync().plans().isEmpty()); assertTrue(db.sync().references().isEmpty())
        } finally { db.close() }
    }
}
