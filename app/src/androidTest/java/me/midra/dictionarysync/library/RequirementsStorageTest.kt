package me.midra.dictionarysync.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.probe.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent Room databases and a fake Provider. No personal dictionary access. */
@RunWith(AndroidJUnit4::class)
class RequirementsStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private fun response(url: String, body: String): Download {
        val bytes = body.toByteArray()
        return Download(bytes, Downloader.sha256(bytes), url, "text/plain")
    }
    class Gateway : SyncGateway {
        val rows = linkedMapOf<Long, ProbeRow>()
        var next = 100L
        var inserts = 0
        var unknownInsertAt: Int? = null
        override fun fingerprint() = "requirements-fake"
        override fun checkAccess() = fingerprint()
        override fun find(key: SyncKey) = rows.values.filter(key::matches)
        override fun byId(id: Long) = rows[id]
        override fun insert(value: ProbeValue): Long? {
            val id = ++next; inserts++; rows[id] = value.row(id)
            return id.takeUnless { inserts == unknownInsertAt }
        }
        override fun delete(row: ProbeRow): Int = if (rows[row.id] == row) { rows.remove(row.id); 1 } else 0
    }
    private fun sync(db: DictionaryDatabase, gateway: Gateway) = SyncRepository(context, db) {
        SyncEngine(RoomSyncStore(db), gateway, "requirements-install")
    }
    @Test fun deleteAfterPartialImportRemovesKnownRowsAndUnblocksOtherSources() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url,
                if (url.endsWith("a")) "よみ\t一語目\t名詞\nよみ\t二語目\t名詞\nよみ\t三語目\t名詞" else "よみ\t別ソース\t名詞") }
            val id = repo.save(null, "部分登録", "https://example.org/a", "AUTO", "TSV", "AUTO")
            gateway.unknownInsertAt = 2
            assertFalse(sync.synchronize(id))
            assertEquals(2, gateway.rows.size)
            assertNull(repo.dao.source(id)!!.activeSnapshot)
            val parent = db.sync().plans().single()
            val journal = db.sync().operations(parent.id)
            val unknown = gateway.rows[gateway.next]
            assertTrue(sync.delete(id))
            assertEquals("DELETED", repo.dao.source(id)!!.lifecycle)
            assertEquals(listOf(unknown), gateway.rows.values.toList())
            assertEquals(journal, db.sync().operations(parent.id))
            assertTrue(db.sync().plans().all { it.state in SyncEngine.TERMINAL })
            val other = repo.save(null, "次のソース", "https://example.org/b", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(other))
        } finally { db.close() }
    }
    @Test fun deleteCancelsUnstartedImportWithoutProviderWrites() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url, "よみ\t未登録\t名詞") }
            val id = repo.save(null, "未開始", "https://example.org/a", "AUTO", "TSV", "AUTO")
            sync.prepare(id)
            assertTrue(sync.delete(id))
            assertEquals("DELETED", repo.dao.source(id)!!.lifecycle)
            assertEquals(0, gateway.inserts)
            assertEquals("CANCELLED", db.sync().plans().single().state)
        } finally { db.close() }
    }
    @Test fun deleteRecoveryCanRetryAfterAtomicFinalizationFails() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url, "よみ\t一語目\t名詞\nよみ\t二語目\t名詞") }
            val id = repo.save(null, "削除再開", "https://example.org/a", "AUTO", "TSV", "AUTO")
            gateway.unknownInsertAt = 2
            assertFalse(sync.synchronize(id))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_source_delete BEFORE UPDATE ON sources WHEN NEW.lifecycle='DELETED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            try { sync.delete(id); fail() } catch (_: android.database.SQLException) { }
            assertNotEquals("DELETED", repo.dao.source(id)!!.lifecycle)
            assertEquals(1, gateway.rows.size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_source_delete")
            assertTrue(sync.delete(id))
            assertEquals("DELETED", repo.dao.source(id)!!.lifecycle)
            assertEquals(1, gateway.rows.size); assertEquals(2, gateway.inserts)
            assertTrue(db.sync().plans().all { it.state in SyncEngine.TERMINAL })
        } finally { db.close() }
    }
    @Test fun initialFailuresAndStorageRollbackNeverRegisterCandidateOrRequest() = runBlocking {
        val db = memory()
        try {
            var body = "<html>error</html>"
            var code: String? = "WIFI_WAIT"
            val repo = LibraryRepository(context, db) { url, _, _ ->
                code?.let { throw DictionaryProblem(it) }; response(url, body)
            }
            for (problem in listOf("WIFI_WAIT", "NETWORK_WAIT", "HTTP_404", null)) {
                code = problem
                try { repo.save(null, "入力を保持", "https://example.org/a", "AUTO", "AUTO", "AUTO", 6); fail() }
                catch (_: DictionaryProblem) { }
                assertTrue(db.library().sources().isEmpty()); assertTrue(db.requests().pending().isEmpty())
            }
            body = "よみ\t合成語\t名詞"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_initial BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'synthetic save failure'); END")
            try { repo.save(null, "保存失敗", "https://example.org/a", "AUTO", "AUTO", "AUTO"); fail() }
            catch (_: android.database.SQLException) { }
            assertTrue(db.library().sources().isEmpty()); assertTrue(db.requests().pending().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_initial")
            val id = repo.save(null, "成功", "https://example.org/a", "AUTO", "AUTO", "AUTO")
            assertEquals(1, db.library().entries(db.library().source(id)!!.latestSnapshot!!).size)
        } finally { db.close() }
    }
    @Test fun addAndEditCommitOnlyValidatedSettingsKeepRawCountAndExplicitLanguage() = runBlocking {
        val db = memory()
        try {
            var body = "よみ\t合成語\t名詞\nよみ\t合成語\t名詞"
            var calls = 0
            val repo = LibraryRepository(context, db) { url, _, _ -> calls++; response(url, body) }
            val url = "https://example.org/a?x=1&x=2&q=%2f+%20"
            val id = repo.save(null, " 新規 ", " $url#ignored ", "AUTO", "AUTO", "AUTO")
            val first = db.library().source(id)!!
            assertEquals("新規", first.name); assertEquals(url, first.url); assertEquals("AUTO", first.locale)
            assertTrue(first.enabled); assertEquals(24, first.syncIntervalHours)
            val snapshot = db.library().snapshot(first.latestSnapshot!!)!!
            assertEquals("AUTO", snapshot.sourceLocale); assertEquals("ja", snapshot.resolvedLocale)
            assertEquals(2, snapshot.count); assertEquals(2, repo.dao.observeCounts().first().single().count)
            repo.save(id, "名前と間隔", url, "AUTO", "AUTO", "AUTO", 6)
            assertEquals(1, calls); assertEquals(first.latestSnapshot, repo.dao.source(id)!!.latestSnapshot)
            val before = repo.dao.source(id)!!
            body = "壊れた\t2列"
            try { repo.save(id, "失敗編集", "https://example.org/new", "en", "TSV", "UTF-8", 1); fail() }
            catch (_: DictionaryProblem) { }
            assertEquals(before, repo.dao.source(id))
            body = "read\tWord\tnoun"
            repo.dao.updateSource(before.copy(enabled = false))
            repo.save(id, "成功編集", "https://example.org/new", "en", "TSV", "UTF-8", 12)
            val updated = repo.dao.source(id)!!
            assertFalse(updated.enabled); assertEquals(12, updated.syncIntervalHours)
            assertEquals(before.revision + 1, updated.revision)
            assertEquals("en", repo.dao.snapshot(updated.latestSnapshot!!)!!.resolvedLocale)
            try { repo.save(null, "同URL", updated.url, "AUTO", "AUTO", "AUTO"); fail() }
            catch (e: DictionaryProblem) { assertEquals("DUPLICATE_URL", e.code) }
        } finally { db.close() }
    }
    @Test fun cancellationDoesNotPersistProvisionalSource() = runBlocking {
        val db = memory()
        try {
            val repo = LibraryRepository(context, db) { _, _, _ -> throw CancellationException("synthetic cancel") }
            try { repo.save(null, "中断", "https://example.org/a", "AUTO", "AUTO", "AUTO"); fail() }
            catch (_: CancellationException) { }
            assertTrue(repo.dao.sources().isEmpty())
        } finally { db.close() }
    }
    @Test fun disableDeleteAndReenablePreserveSharedAndBorrowedWordsAndOfflineEntries() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val manual = SyncKey.of("ja", "手動語", "しゅどう").value().row(7)
            gateway.rows[7] = manual
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url, if (url.endsWith("a")) "きょうゆう\t共有語\t名詞\nしゅどう\t手動語\t名詞\nえー\tA語\t名詞" else "きょうゆう\t共有語\t名詞\nびー\tB語\t名詞") }
            val a = repo.save(null, "A", "https://example.org/a", "AUTO", "TSV", "AUTO")
            val b = repo.save(null, "B", "https://example.org/b", "AUTO", "TSV", "AUTO", 6)
            assertTrue(sync.synchronize(a)); assertTrue(sync.synchronize(b)); assertTrue(sync.synchronize(a))
            assertEquals(3, gateway.inserts)
            repo.requests.submit(listOf(a), FetchReason.PERIODIC)
            val snapshot = repo.dao.source(a)!!.latestSnapshot
            assertTrue(sync.setEnabled(a, false))
            assertTrue(db.requests().pending().isEmpty()); assertEquals(snapshot, repo.dao.source(a)!!.latestSnapshot)
            assertEquals(3, repo.dao.entries(snapshot!!).size)
            assertEquals(setOf("共有語", "B語", "手動語"), gateway.rows.values.map { it.word }.toSet())
            assertTrue(sync.delete(b))
            assertEquals(listOf(manual), gateway.rows.values.toList())
            assertTrue(sync.setEnabled(a, true))
            assertEquals(setOf("共有語", "A語", "手動語"), gateway.rows.values.map { it.word }.toSet())
            assertEquals(24, repo.dao.source(a)!!.syncIntervalHours)
        } finally { db.close() }
    }
    @Test fun externalEditRemainsAndDesiredOffIsReportedAsUnfinished() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url, "よみ\t元の語\t名詞") }
            val id = repo.save(null, "外部編集", "https://example.org/a", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(id))
            val row = gateway.rows.values.single().copy(word = "外部編集語")
            gateway.rows[row.id] = row
            assertFalse(sync.setEnabled(id, false))
            assertFalse(repo.dao.source(id)!!.enabled); assertNotNull(repo.dao.source(id)!!.syncError)
            assertEquals(row, gateway.rows[row.id]); assertNotNull(repo.dao.source(id)!!.activeSnapshot)
        } finally { db.close() }
    }
    @Test fun workerFetchAutomaticallyAppliesValidEmptyAndOldDataSurvivesAnotherFailure() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            var aBody = "よみ\tA語\t名詞"; var bBody = "べつ\tB語\t名詞"
            val repo = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ -> response(url, if (url.endsWith("a")) aBody else bBody) }
            val a = repo.save(null, "A", "https://example.org/a", "AUTO", "TSV", "AUTO", 1)
            val b = repo.save(null, "B", "https://example.org/b", "AUTO", "TSV", "AUTO", 6)
            repo.syncPending(); assertEquals(2, gateway.rows.size)
            aBody = "<html>failed</html>"; bBody = "# valid empty TSV"
            val oldA = repo.dao.source(a)!!
            repo.requestAllFetches()
            val runner = FetchRunner(repo)
            runner.runOne(); runner.runOne()
            assertEquals(setOf("A語"), gateway.rows.values.map { it.word }.toSet())
            assertEquals(oldA.latestSnapshot, repo.dao.source(a)!!.latestSnapshot)
            assertEquals(oldA.fetchedAt, repo.dao.source(a)!!.fetchedAt)
            assertEquals("SYNCED", repo.dao.source(b)!!.syncState)
        } finally { db.close() }
    }
    @Test fun perSourceIntervalsControlDueRequestsAndDisabledSourcesAreIgnored() = runBlocking {
        val db = memory()
        try {
            val now = System.currentTimeMillis()
            val repo = LibraryRepository(context, db)
            repo.dao.addSource(SourceEntity("A", "1h", "https://example.org/a", fetchedAt = now - 3_600_000, syncIntervalHours = 1))
            repo.dao.addSource(SourceEntity("B", "6h", "https://example.org/b", fetchedAt = now - 3_600_000, syncIntervalHours = 6))
            repo.dao.addSource(SourceEntity("C", "disabled", "https://example.org/c", enabled = false))
            repo.requestPeriodicFetches(now, "B"); assertTrue(db.requests().pending().isEmpty())
            repo.requestPeriodicFetches(now); assertEquals(listOf("A"), db.requests().pending().map { it.sourceId })
            val request = db.requests().pending().single()
            repo.dao.updateSource(repo.dao.source("A")!!.copy(enabled = false))
            repo.executeFetch(request)
            assertEquals("SOURCE_DISABLED", db.requests().request("A")!!.outcome)
            assertEquals(3_600_000L, FetchWork.periodicRequest("A", 1).workSpec.intervalDuration)
            assertEquals(21_600_000L, FetchWork.periodicRequest("B", 6).workSpec.intervalDuration)
            assertEquals("B", FetchWork.periodicRequest("B", 6).workSpec.input.getString("sourceId"))
        } finally { db.close() }
    }
    @Test fun workerResumesDesiredDeletionAsDeleteAndKeepsItsJournal() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            val repo = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ -> response(url, "よみ\t削除試験語\t名詞") }
            val id = repo.save(null, "削除再開", "https://example.org/a", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(id))
            val source = repo.dao.source(id)!!
            repo.dao.updateSource(source.copy(enabled = false, lifecycle = "DELETE_PENDING", syncState = "REMOVE_PENDING"))
            FetchRunner(repo).runOne()
            assertEquals("DELETED", repo.dao.source(id)!!.lifecycle)
            assertTrue(gateway.rows.isEmpty()); assertTrue(db.sync().references().isEmpty())
            assertEquals(2, db.sync().plans().size)
            assertTrue(db.sync().plans().all { it.state == "COMMITTED" })
        } finally { db.close() }
    }

    @Test fun approvedOlderPlanLeavesNewerFetchPendingUntilItIsApplied() = runBlocking {
        val db = memory()
        try {
            val gateway = Gateway(); val sync = sync(db, gateway)
            var body = "よみ\t旧版の語\t名詞"
            val repo = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ -> response(url, body) }
            val id = repo.save(null, "再開", "https://example.org/resume", "AUTO", "TSV", "AUTO")
            val plan = sync.prepare(id).plan
            // A process may stop after approval and before its first Provider operation.
            db.sync().plan(plan.copy(approved = true))
            body = "よみ\t新版の語\t名詞"
            repo.fetch(id)
            val latest = repo.dao.source(id)!!.latestSnapshot
            assertTrue(sync.synchronize(id))
            assertEquals(plan.newSnapshot, repo.dao.source(id)!!.activeSnapshot)
            assertEquals("UPDATE_PENDING", repo.dao.source(id)!!.syncState)
            repo.syncPending()
            assertEquals(latest, repo.dao.source(id)!!.activeSnapshot)
            assertEquals("SYNCED", repo.dao.source(id)!!.syncState)
            assertEquals(listOf("新版の語"), gateway.rows.values.map { it.word })
        } finally { db.close() }
    }

    @Test fun editingToDeletedSourcesUrlPreservesHistoryAndRejectsActiveDuplicate() = runBlocking {
        val db = memory()
        try {
            val repo = LibraryRepository(context, db) { url, _, _ -> response(url, "よみ\t保存語\t名詞") }
            val sync = sync(db, Gateway())
            val archivedUrl = "https://example.org/archived"
            val archived = repo.save(null, "削除済み", archivedUrl, "AUTO", "TSV", "AUTO")
            val archivedSnapshot = repo.dao.source(archived)!!.latestSnapshot
            assertTrue(sync.delete(archived))
            val id = repo.save(null, "編集対象", "https://example.org/current", "AUTO", "TSV", "AUTO")
            repo.save(id, "変更済み", archivedUrl, "AUTO", "TSV", "AUTO")
            assertEquals(archivedUrl, repo.dao.source(id)!!.url)
            assertEquals("DELETED", repo.dao.source(archived)!!.lifecycle)
            assertEquals(1, repo.dao.entries(archivedSnapshot!!).size)
            try { repo.save(null, "重複", archivedUrl, "AUTO", "TSV", "AUTO"); fail() }
            catch (error: DictionaryProblem) { assertEquals("DUPLICATE_URL", error.code) }
        } finally { db.close() }
    }

}
