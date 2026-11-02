package me.midra.dictionarysync.sync

import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.library.RequirementsStorageTest
import me.midra.dictionarysync.probe.AndroidProbeGateway
import me.midra.dictionarysync.probe.WriteProbe
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp

/** One retained artificial word for a separately authorized human IME check. Never reset the ledger. */
@RunWith(AndroidJUnit4::class)
class SourceImeCandidateAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val key = SyncKey.of("ja", "候補確認八七三一", "どうきてすと")
    private val sourceUrl = "https://example.org/ime-candidate-one-word-v1"
    private val ledger get() = File(context.noBackupFilesDir, "source-ime-candidate-v1.db")
    private val baseline get() = File(context.noBackupFilesDir, "source-ime-candidate-v1.baseline")

    @Test fun fakeProviderRehearsal() = runBlocking {
        val gateway = RequirementsStorageTest.Gateway()
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            stage(db, gateway)
            assertEquals(1, gateway.inserts)
            cleanup(db, gateway)
            assertTrue(gateway.rows.isEmpty())
        } finally { db.close() }
    }

    @Test fun stageOneWordForHumanCandidateCheck() = runBlocking {
        authorize()
        val gateway = AndroidSyncGateway(context)
        gateway.checkAccess()
        check(listOf(ledger, File("${ledger.path}-wal"), File("${ledger.path}-shm"), baseline).none { it.exists() }) {
            "Prior candidate ledger exists; preserve it and inspect/cleanup instead of staging again"
        }
        AndroidProbeGateway(context.contentResolver).use { probe ->
            val initial = probe.snapshot()
            check(initial.none { it.word == key.word }) { "Candidate word already exists; do not borrow it for this trial" }
            // Hash only, persisted before any change. Keep this file and the ledger on every failure.
            baseline.writeText(WriteProbe.fingerprint(initial))
            val db = openLedger()
            try {
                stage(db, gateway)
                val row = gateway.find(key).single()
                assertEquals(WriteProbe.fingerprint(initial + row), WriteProbe.fingerprint(probe.snapshot()))
                report("STAGED: word=${key.word}; shortcut=${key.shortcut}; locale=ja; rowId=${row.id}; one source, one owned row; retain for human IME check then run cleanupOneWord")
            } finally { db.close() }
        }
    }

    @Test fun cleanupOneWord() = runBlocking {
        authorize()
        val gateway = AndroidSyncGateway(context)
        gateway.checkAccess()
        check(ledger.isFile && baseline.isFile) { "Candidate ledger/baseline missing; never infer ownership" }
        val db = openLedger()
        try {
            cleanup(db, gateway)
            AndroidProbeGateway(context.contentResolver).use { probe ->
                assertEquals(baseline.readText(), WriteProbe.fingerprint(probe.snapshot()))
            }
            report("CLEANED: candidate absent; Provider baseline restored; source DELETED; ledger/history retained")
        } finally { db.close() }
    }

    private suspend fun stage(db: DictionaryDatabase, gateway: SyncGateway) {
        val sync = sync(db, gateway)
        val repo = testApp(context, db, sync = sync) { url, _, _ ->
            val bytes = "${key.shortcut}\t${key.word}\t名詞".toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain; charset=utf-8")
        }
        val id = repo.editor.save(null, "IME候補確認用（人工語1件）", sourceUrl, "AUTO", "AUTO", "AUTO")
        repo.fetchRunner.runOne()
        assertEquals(SyncState.SYNCED, repo.database.library().source(id)!!.syncState)
        assertEquals(1, gateway.find(key).size)
        assertEquals(1, db.sync().references().size)
        assertEquals(Ownership.OWNED, db.sync().bindings().single().ownership)
        assertTrue(db.sync().plans().all { it.state == PlanState.COMMITTED })
    }

    private suspend fun cleanup(db: DictionaryDatabase, gateway: SyncGateway) {
        val source = db.library().allSources().single()
        check(source.url == sourceUrl)
        val entries = db.library().entries(requireNotNull(source.latestSnapshot))
        check(entries.size == 1 && entries.single().word == key.word && entries.single().reading == key.shortcut)
        assertTrue(sync(db, gateway).delete(source.id))
        assertTrue(gateway.find(key).isEmpty())
        assertTrue(db.sync().references().isEmpty())
        assertEquals(SourceLifecycle.DELETED, db.library().source(source.id)!!.lifecycle)
        assertTrue(db.sync().plans().all { it.state == PlanState.COMMITTED })
    }

    private fun sync(db: DictionaryDatabase, gateway: SyncGateway) = SourceSync(context, db) {
        SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context))
    }
    private fun openLedger() = Room.databaseBuilder(context, DictionaryDatabase::class.java, ledger.absolutePath).build()
    private fun authorize() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSourceImeCandidate") == "isolated-one-word-v1")
        check(context.packageName == "me.midra.dictionarysync.acceptance")
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
