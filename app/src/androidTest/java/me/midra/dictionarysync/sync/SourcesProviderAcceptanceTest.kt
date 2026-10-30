package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import me.midra.dictionarysync.library.LibraryRepository
import me.midra.dictionarysync.library.RequirementsStorageTest
import me.midra.dictionarysync.probe.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real execution requires separate, manual diagnosis in the acceptance app. Never erase its ledger. */
@RunWith(AndroidJUnit4::class)
class SourcesProviderAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fakeProviderRehearsal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val gateway = RequirementsStorageTest.Gateway()
        val witness = SyncKey.of("ja", "dictionarysync_check_a11ce001", null).value().row(7)
        gateway.rows[7] = witness
        try { scenario(db, gateway, witness, "rehearsal") }
        finally { db.close() }
        assertEquals(listOf(witness), gateway.rows.values.toList())
        assertEquals(5, gateway.inserts)
    }

    @Test fun realProviderRoundTrip() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowSourcesProviderTrial") == "isolated-sources-v1")
        check(context.packageName == "me.midra.dictionarysync.acceptance") { "Use the isolated acceptance application" }
        val gateway = AndroidSyncGateway(context)
        gateway.checkAccess() // Never certify here or reuse the original application's proof.
        val witness = requireNotNull(AndroidProbeJournal(context).lastBasic()?.witness)
        check(witness.locale == "ja" && witness.shortcut.isNullOrEmpty())
        val file = File(context.noBackupFilesDir, "sources-provider-acceptance-v1.db")
        check(listOf(file, File("${file.path}-wal"), File("${file.path}-shm")).none { it.exists() }) {
            "Prior ledger exists; inspect it instead of recreating it"
        }
        AndroidProbeGateway(context.contentResolver).use { probe ->
            val initial = probe.snapshot()
            val prefix = "dssources_${witness.word.removePrefix(ReadProbe.MARKER_PREFIX)}_"
            check(initial.none { it.word.startsWith(prefix) })
            val baseline = WriteProbe.fingerprint(initial)
            val db = Room.databaseBuilder(context, DictionaryDatabase::class.java, file.absolutePath).build()
            try {
                scenario(db, gateway, witness, prefix)
                assertEquals(baseline, WriteProbe.fingerprint(probe.snapshot()))
            } finally {
                // On failure, retain the source records, ownership and journal; do not guess cleanup.
                db.close()
            }
        }
    }

    private suspend fun scenario(db: DictionaryDatabase, gateway: SyncGateway, witness: ProbeRow, prefix: String) {
        val sync = SyncRepository(context, db) {
            SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context))
        }
        val shared = SyncKey.of("ja", "${prefix}shared", "でぃーえすきょうゆう")
        val aWord = SyncKey.of("ja", "${prefix}A", "でぃーえすえー")
        val bWord = SyncKey.of("ja", "${prefix}B", "でぃーえすびー")
        val replacement = SyncKey.of("ja", "${prefix}C", "でぃーえすしー")
        val borrowed = SyncKey.from(witness)!!
        fun body(vararg keys: SyncKey) = keys.joinToString("\n") { "${it.shortcut.orEmpty()}\t${it.word}\t名詞" }
        var aBody = body(aWord, shared, borrowed)
        val library = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ ->
            val bytes = (if (url.endsWith("/a")) aBody else body(bWord, shared)).toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
        val a = library.save(null, "人工ソースA", "https://example.org/sources-trial/a", "AUTO", "AUTO", "AUTO")
        val b = library.save(null, "人工ソースB", "https://example.org/sources-trial/b", "AUTO", "AUTO", "AUTO", 6)
        val runner = FetchRunner(library)
        runner.runOne(); runner.runOne()
        for (key in listOf(aWord, bWord, shared)) assertEquals(1, gateway.find(key).size)
        val sharedRow = gateway.find(shared).single()
        assertEquals(witness, gateway.byId(witness.id))
        assertEquals(1, db.sync().bindings().count { it.ownership == "BORROWED" })

        // Exercise the current fetch -> pending apply path, without prepare/apply approval calls.
        aBody = body(replacement, shared, borrowed)
        library.requestAllFetches()
        runner.runOne(); runner.runOne()
        assertTrue(gateway.find(aWord).isEmpty())
        assertEquals(1, gateway.find(replacement).size)
        assertEquals(sharedRow, gateway.find(shared).single())
        library.requestAllFetches()
        runner.runOne(); runner.runOne()
        assertEquals(1, gateway.find(replacement).size)
        assertEquals(sharedRow, gateway.find(shared).single())

        val snapshot = library.dao.source(a)!!.latestSnapshot!!
        assertTrue(sync.setEnabled(a, false))
        assertTrue(gateway.find(replacement).isEmpty())
        assertEquals(sharedRow, gateway.find(shared).single())
        assertEquals(snapshot, library.dao.source(a)!!.latestSnapshot)
        assertEquals(3, library.dao.entries(snapshot).size)
        assertTrue(sync.setEnabled(a, true))
        assertEquals(1, gateway.find(replacement).size)

        // Same sequential per-source operations used by LibraryViewModel.batch; UI is tested separately.
        assertTrue(sync.delete(a))
        assertTrue(gateway.find(replacement).isEmpty())
        assertEquals(sharedRow, gateway.find(shared).single())
        assertTrue(sync.delete(b))
        for (key in listOf(aWord, bWord, shared, replacement)) assertTrue(gateway.find(key).isEmpty())
        assertEquals(witness, gateway.byId(witness.id))
        assertTrue(db.sync().references().isEmpty())
        assertTrue(db.sync().plans().all { it.state == "COMMITTED" })
        assertEquals("DELETED", library.dao.source(a)!!.lifecycle)
        assertEquals("DELETED", library.dao.source(b)!!.lifecycle)
    }
}
