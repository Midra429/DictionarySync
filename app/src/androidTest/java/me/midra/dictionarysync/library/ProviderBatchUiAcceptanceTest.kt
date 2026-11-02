package me.midra.dictionarysync.library

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import me.midra.dictionarysync.probe.*
import me.midra.dictionarysync.sync.*
import me.midra.dictionarysync.ui.DictionarySyncApp
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.ui.AppViewModel

/** Production screen/VM in the test Activity, dedicated ledger, no OS work or real downloads. */
@RunWith(AndroidJUnit4::class)
class ProviderBatchUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val key = SyncKey.of("ja", "一括確認九二六四", "いっかつてすと")
    private val names = listOf("実Provider一括確認A", "実Provider一括確認B")
    private val urls = listOf("https://example.org/provider-batch-ui-v1/a", "https://example.org/provider-batch-ui-v1/b")
    private val ledger get() = File(context.noBackupFilesDir, "provider-batch-ui-v1.db")
    private val baseline get() = File(context.noBackupFilesDir, "provider-batch-ui-v1.baseline")

    private class NoWork : FetchDispatch {
        override suspend fun enqueue() {}
        override suspend fun wakeAfter(delayMillis: Long) {}
        override suspend fun cancelWake() {}
        override suspend fun sources(sources: List<SourceEntity>) {}
    }

    /** Fail before any unexpected write. Keep the production gateway's access and exact predicates. */
    private inner class LimitedGateway(private val delegate: SyncGateway) : SyncGateway by delegate {
        var inserts = 0
        var deletes = 0
        override fun insert(value: ProbeValue): Long? {
            check(value.word == key.word && value.locale == key.locale && value.shortcut == key.shortcut)
            return delegate.insert(value).also { inserts++ }
        }
        override fun delete(row: ProbeRow): Int {
            check(key.matches(row)) { "Trial may delete only its artificial shared word" }
            return delegate.delete(row).also { if (it == 1) deletes++ }
        }
    }

    @Test fun fakeProviderScreenRehearsal() {
        check(context.packageName == "me.midra.dictionarysync.acceptance")
        val fake = RequirementsStorageTest.Gateway()
        val witness = SyncKey.of("ja", "dictionarysync_check_ba7c0001", null).value().row(7)
        fake.rows[witness.id] = witness
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            scenario(db, LimitedGateway(fake), witness)
            assertEquals(listOf(witness), fake.rows.values.toList())
        } finally { db.close() }
    }

    @Test fun realProviderScreenRoundTrip() {
        authorize()
        val gateway = LimitedGateway(AndroidSyncGateway(context))
        gateway.checkAccess() // Diagnosis is performed separately, only after user approval.
        check(listOf(ledger, File("${ledger.path}-wal"), File("${ledger.path}-shm"), baseline).none { it.exists() }) {
            "Prior trial exists; preserve the ledger and inspect/cleanup instead of recreating it"
        }
        val witness = requireNotNull(AndroidProbeJournal(context).lastBasic()?.witness)
        check(witness.locale == "ja" && witness.shortcut.isNullOrEmpty())
        AndroidProbeGateway(context.contentResolver).use { probe ->
            val initial = probe.snapshot()
            check(initial.none { it.word == key.word }) { "Artificial word already exists" }
            baseline.writeText(WriteProbe.fingerprint(initial))
            val db = openLedger()
            try {
                scenario(db, gateway, witness) {
                    val row = gateway.find(key).singleOrNull()
                    assertEquals(WriteProbe.fingerprint(initial + listOfNotNull(row)), WriteProbe.fingerprint(probe.snapshot()))
                }
                assertEquals(baseline.readText(), WriteProbe.fingerprint(probe.snapshot()))
                report("REAL COMPLETE: Provider baseline restored; dedicated ledger retained")
            } finally { db.close() }
        }
    }

    /** Fallback after a stopped UI assertion. Never erase/recreate the ledger or infer ownership. */
    @Test fun cleanupRetainedTrial() = runBlocking {
        authorize()
        check(ledger.isFile && baseline.isFile) { "Trial ledger/baseline missing" }
        val gateway = LimitedGateway(AndroidSyncGateway(context))
        gateway.checkAccess()
        val witness = requireNotNull(AndroidProbeJournal(context).lastBasic()?.witness)
        val db = openLedger()
        try {
            val sources = db.library().allSources()
            check(sources.size in 1..2 && sources.all { it.url in urls && it.name == names[urls.indexOf(it.url)] })
            val expected = setOf(key, SyncKey.from(witness)!!)
            for (source in sources) {
                val entries = db.library().entries(requireNotNull(source.latestSnapshot))
                check(entries.size == 2 && entries.map { SyncKey.of("ja", it.word, it.reading) }.toSet() == expected)
                if (source.lifecycle != SourceLifecycle.DELETED) assertTrue(sync(db, gateway).delete(source.id))
            }
            assertTrue(gateway.find(key).isEmpty())
            assertTrue(db.sync().references().isEmpty())
            assertTrue(db.library().allSources().all { it.lifecycle == SourceLifecycle.DELETED })
            AndroidProbeGateway(context.contentResolver).use { probe ->
                assertEquals(baseline.readText(), WriteProbe.fingerprint(probe.snapshot()))
            }
            report("CLEANED: known trial sources deleted through SourceSync; baseline restored; history retained")
        } finally { db.close() }
    }

    private fun scenario(db: DictionaryDatabase, gateway: LimitedGateway, witness: ProbeRow, checkWholeProvider: () -> Unit = {}) {
        val sync = sync(db, gateway)
        val borrowed = SyncKey.from(witness)!!
        lateinit var repo: AppContainer
        val dispatch = InlineApplyDispatch { repo.applies }
        repo = testApp(context, db, sync = sync, dispatch = dispatch) { url, _, _ ->
            check(url in urls)
            val bytes = listOf(key, borrowed).joinToString("\n") { "${it.shortcut.orEmpty()}\t${it.word}\t名詞" }.toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain; charset=utf-8")
        }
        val ids = runBlocking {
            urls.mapIndexed { index, url -> repo.editor.save(null, names[index], url, "AUTO", "AUTO", "AUTO") }.also {
                val runner = repo.fetchRunner
                runner.runOne(); runner.runOne()
            }
        }
        val snapshots = runBlocking { ids.map { db.library().source(it)!!.latestSnapshot } }
        assertApplied(db, gateway, witness, ids)
        assertEquals(1, gateway.inserts)
        checkWholeProvider()
        val store = ViewModelStore()
        lateinit var model: AppViewModel
        try {
            compose.runOnUiThread {
                model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
                store.put("provider-batch-ui", model)
            }
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10_000) { !model.operations.busy.value && model.sources.sources.value.size == 2 }
            select(ids, enter = true)
            compose.onNodeWithText("無効化", substring = false).performClick()
            waitFor(model) { ids.all { db.library().source(it)!!.let { source -> !source.enabled && source.syncState == SyncState.REMOVED } } }
            assertTrue(gateway.find(key).isEmpty())
            assertEquals(witness, gateway.byId(witness.id))
            runBlocking {
                assertTrue(db.sync().references().isEmpty())
                assertEquals(snapshots, ids.map { db.library().source(it)!!.latestSnapshot })
                snapshots.forEach { assertEquals(2, db.library().entries(it!!).size) }
            }
            assertEquals(1, gateway.deletes)
            checkWholeProvider()
            select(ids, enter = false)
            compose.onNodeWithText("有効化", substring = false).performClick()
            waitFor(model) { ids.all { db.library().source(it)!!.syncState == SyncState.SYNCED } }
            assertApplied(db, gateway, witness, ids)
            assertEquals(2, gateway.inserts)
            checkWholeProvider()
            select(ids, enter = false)
            compose.onNodeWithText("削除", substring = false).performClick()
            compose.onNodeWithText("2件のソースを削除しますか？").assertIsDisplayed()
            compose.onNode(hasText("削除", substring = false) and hasAnyAncestor(isDialog())).performClick()
            waitFor(model) { ids.all { db.library().source(it)!!.lifecycle == SourceLifecycle.DELETED } }
            compose.onNodeWithText("2件のソースを削除しますか？", substring = true).assertDoesNotExist()
            assertTrue(gateway.find(key).isEmpty())
            assertEquals(witness, gateway.byId(witness.id))
            assertEquals(2, gateway.deletes)
            runBlocking {
                assertTrue(db.sync().references().isEmpty())
                assertTrue(ids.all { db.library().source(it)!!.activeSnapshot == null })
                assertEquals(snapshots, ids.map { db.library().source(it)!!.latestSnapshot })
                assertEquals(8, db.sync().plans().size)
                assertTrue(db.sync().plans().all { it.state == PlanState.COMMITTED })
            }
            checkWholeProvider()
            report("SCREEN COMPLETE: 2 selected sources; disable/enable/delete each success2 failure0; one shared OWNED + borrowed witness; INSERT2 DELETE2; COMMITTED8; references0")
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun assertApplied(db: DictionaryDatabase, gateway: SyncGateway, witness: ProbeRow, ids: List<String>) {
        assertEquals(1, gateway.find(key).size)
        assertEquals(witness, gateway.byId(witness.id))
        runBlocking {
            assertTrue(ids.all { db.library().source(it)!!.let { source -> source.enabled && source.syncState == SyncState.SYNCED && source.activeSnapshot == source.latestSnapshot } })
            assertEquals(4, db.sync().references().size)
            assertEquals(1, db.sync().bindings().count { it.ownership == Ownership.OWNED })
            assertEquals(1, db.sync().bindings().count { it.ownership == Ownership.BORROWED })
        }
    }

    private fun select(ids: List<String>, enter: Boolean) {
        if (enter) compose.onNodeWithText("選択", substring = false).performClick()
        else compose.onAllNodes(hasText("選択を解除") and isEnabled()).fetchSemanticsNodes().firstOrNull()?.let { compose.onNodeWithText("選択を解除").performClick() }
        ids.forEachIndexed { index, id ->
            compose.onNodeWithTag("source-list").performScrollToNode(hasText(names[index]))
            compose.onNodeWithContentDescription("${names[index]}、未選択").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithContentDescription("${names[index]}、選択済み").assertIsSelected()
            compose.onNodeWithTag("source-enabled-$id").assertIsNotEnabled()
        }
        compose.onNodeWithText("2件選択中").assertExists()
    }

    private fun waitFor(model: AppViewModel, predicate: suspend () -> Boolean) {
        compose.waitUntil(20_000) { !model.operations.busy.value && runBlocking { predicate() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2件を受け付けました", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertNull(model.operations.error.value)
        assertTrue(model.sources.selection.value.isEmpty())
    }
    private fun sync(db: DictionaryDatabase, gateway: SyncGateway) = SourceSync(context, db) {
        SyncEngine(RoomSyncStore(db), gateway, SyncCoordinator.installation(context))
    }
    private fun openLedger() = Room.databaseBuilder(context, DictionaryDatabase::class.java, ledger.absolutePath).build()
    private fun authorize() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowProviderBatchUi") == "isolated-shared-word-v1")
        check(context.packageName == "me.midra.dictionarysync.acceptance")
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
