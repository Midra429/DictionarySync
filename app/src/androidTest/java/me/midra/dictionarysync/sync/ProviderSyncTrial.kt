package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.probe.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import me.midra.dictionarysync.seed
import me.midra.dictionarysync.testApp

/** Opt-in real Provider trial. The persistent test ledger is retained, including after a failure. */
@RunWith(AndroidJUnit4::class)
class ProviderSyncTrial {
    @Test fun syntheticRoundTrip() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowSyntheticProviderTrial") == "20261029")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "me.midra.dictionarysync.acceptance") { "Use the isolated acceptance application" }
        val marker = requireNotNull(args.getString("manualMarker"))
        require(ReadProbe.MARKER_PATTERN.matches(marker))
        val (summary, verdict) = ReadProbe(AndroidDictionaryReader(context.contentResolver)).run(marker)
        assertEquals(ReadVerdict.READ_CONFIRMED, verdict)
        val witness = summary.markerRows.single()
        val locale = SyncKey.from(witness)!!.locale
        assertNull(witness.shortcut)
        val file = File(context.noBackupFilesDir, "stage2-synthetic-trial.db")
        // Never erase/recreate a prior ledger with possible live rows.
        check(!file.exists()) { "Prior synthetic trial ledger exists; inspect/recover it first" }
        val db = Room.databaseBuilder(context, DictionaryDatabase::class.java, file.absolutePath).build()
        val probe = AndroidProbeGateway(context.contentResolver)
        val initial = probe.snapshot()
        val baseline = WriteProbe.fingerprint(initial)
        val keyA = SyncKey.of(locale, "dsstage2_20261029_A", "でぃーえすにためし")
        val keyB = SyncKey.of(locale, "dsstage2_20261029_B", "でぃーえすにためし")
        assertFalse(initial.any { it.word == keyA.word || it.word == keyB.word })
        try {
            SyncCoordinator.locked(context) {
                WriteProbe(probe, AndroidProbeJournal(context)).basic(marker)
                AndroidSyncGateway(context).certify()
            }
            val gateway = AndroidSyncGateway(context)
            gateway.checkAccess() // Fresh witness, enabled IME and environment certificate, not past docs.
            var body = "${keyA.shortcut}\t${keyA.word}\t名詞\n${keyB.shortcut}\t${keyB.word}\t名詞\n\t$marker\t名詞\n"
            val library = testApp(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain") }
            val sourceId = library.editor.seed(null, "人工語試験", "https://example.org/synthetic-trial", locale, "TSV", "UTF-8")
            library.fetcher.fetch(sourceId)
            suspend fun prepare(): SyncPreview = SyncCoordinator.locked(context) {
                val source = db.library().source(sourceId)!!
                val snapshot = db.library().snapshot(source.latestSnapshot!!)!!
                SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context)).prepare(sourceId, source.revision, source.activeSnapshot,
                    snapshot.id, snapshot.bodyHash, db.library().entries(snapshot.id).map { SyncKey.of(it.locale, it.word, it.reading) })
            }
            suspend fun apply(p: SyncPreview) = SyncCoordinator.locked(context) {
                SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context)).apply(p.plan.id, p.plan.token)
            }
            val first = prepare(); apply(first)
            assertEquals(2, db.sync().bindings().count { it.ownership == Ownership.OWNED })
            assertEquals(1, db.sync().bindings().count { it.ownership == Ownership.BORROWED })
            val ownedIds = db.sync().bindings().filter { it.ownership == Ownership.OWNED }.map { SyncCodec.row(it.observed!!).id }.toSet()
            repeat(2) { library.fetcher.fetch(sourceId); apply(prepare()) }
            assertEquals(ownedIds, db.sync().bindings().filter { it.ownership == Ownership.OWNED }.map { SyncCodec.row(it.observed!!).id }.toSet())
            assertEquals(1, gateway.find(keyA).size); assertEquals(1, gateway.find(keyB).size)
            assertEquals(witness, gateway.byId(witness.id))
            // Replace A with B and the borrowed witness. The 1/3 removal needs a candidate-bound approval.
            body = "${keyB.shortcut}\t${keyB.word}\t名詞\n\t$marker\t名詞\n"
            library.fetcher.fetch(sourceId); val next = prepare(); apply(next)
            assertTrue(gateway.find(keyA).isEmpty()); assertEquals(1, gateway.find(keyB).size)
            // Clean up via the same engine; retain both Journal and source tombstone for inspection.
            val deletion = SyncCoordinator.locked(context) {
                val source = db.library().source(sourceId)!!
                SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context)).prepare(sourceId, source.revision,
                    source.activeSnapshot, null, "DELETE", emptyList())
            }
            apply(deletion)
            assertTrue(gateway.find(keyB).isEmpty()); assertEquals(witness, gateway.byId(witness.id))
            assertTrue(db.sync().references().isEmpty()); assertTrue(db.sync().plans().all { it.state == PlanState.COMMITTED })
            assertEquals(baseline, WriteProbe.fingerprint(probe.snapshot()))
        } finally {
            // Do not guess cleanup after a failure. Live/ambiguous rows keep their persistent ledger.
            db.close(); probe.close()
        }
    }
    @Test fun readOnlyGateAfterCleanup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("expectReadGateClosed") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "me.midra.dictionarysync.acceptance") { "Use the isolated acceptance application" }
        try { AndroidSyncGateway(context).checkAccess(); fail("The deleted witness must not prove access") }
        catch (error: SyncStopped) { assertEquals("READ_WITNESS_LOST", error.code) }
    }

}
