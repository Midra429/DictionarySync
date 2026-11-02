package me.midra.dictionarysync.library

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.sync.*
import me.midra.dictionarysync.ui.DictionarySyncApp
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.ui.AppViewModel
import me.midra.dictionarysync.ui.sources.BatchOperation

/** Tests the actual screen/VM with a private database, synthetic downloads and fake Provider. */
@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Dispatch : FetchDispatch {
        override suspend fun enqueue() {}
        override suspend fun wakeAfter(delayMillis: Long) {}
        override suspend fun cancelWake() {}
        override suspend fun sources(sources: List<SourceEntity>) {}
    }
    private fun field(label: String) = compose.onNode(hasText(label, substring = false) and hasClickAction())
    private fun interval(label: String) = compose.onNode(hasText(label, substring = false) and isToggleable())
    @Test fun successfulAddClosesRestoredFormAfterInFlightDownload(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), RequirementsStorageTest.Gateway(), "ui-rotation") }
            val repo = testApp(context, db, sync = sync, dispatch = Dispatch()) { url, _, _ ->
                started.countDown()
                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                val bytes = "よみ\t回転中の取得語\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { !model.operations.busy.value }
            compose.onNodeWithContentDescription("ソースを追加").performClick()
            compose.onNodeWithText("名前").performClick().performTextInput("取得中回転")
            compose.onNodeWithText("辞書ファイルのURL").performScrollTo().performClick().performTextInput("https://example.org/rotation")
            compose.onNodeWithText("追加", substring = false).performClick()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("名前").performScrollTo().assertTextContains("取得中回転")
            release.countDown()
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.sources.value.size == 1 }
            compose.onNodeWithText("名前").assertDoesNotExist()
            compose.onNodeWithText("取得中回転").assertExists()
        } finally {
            release.countDown()
            compose.runOnIdle { store.clear() }
            db.close()
        }
    }
    @Test fun failedAddKeepsAllInputsAndRetryOpensReadOnlyOfflineSearch(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            var body = "<html>failed</html>"
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), RequirementsStorageTest.Gateway(), "ui-install") }
            val repo = testApp(context, db, sync = sync, dispatch = Dispatch()) { url, _, _ ->
                val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.onNodeWithContentDescription("ソースを追加").performClick()
            compose.onNodeWithText("名前").performClick().performTextInput("UI合成試験")
            compose.onNodeWithText("辞書ファイルのURL").performClick().performTextInput("https://example.org/ui?x=1&x=2")
            compose.onNodeWithText("形式: 自動 · 言語: 自動 · 文字コード: 自動").assertExists()
            compose.onNodeWithText("詳細設定").performScrollTo().performClick()
            field("形式").performScrollTo().assertTextContains("自動")
            field("言語").performScrollTo().assertTextContains("自動")
            field("文字コード").performScrollTo().assertTextContains("自動")
            interval("24時間").performScrollTo().assertIsOn()
            interval("6時間").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("名前").performScrollTo().assertTextContains("UI合成試験")
            interval("6時間").performScrollTo().assertIsOn()
            compose.onNodeWithText("追加", substring = false).performClick()
            compose.waitUntil(10000) { model.operations.error.value != null && !model.operations.busy.value }
            assertTrue(db.library().sources().isEmpty())
            compose.onNodeWithText("閉じる").performClick()
            compose.onNodeWithText("名前").performScrollTo().assertTextContains("UI合成試験")
            compose.onNodeWithText("辞書ファイルのURL").performScrollTo().assertTextContains("https://example.org/ui?x=1&x=2")
            interval("6時間").performScrollTo().assertIsOn()
            body = "よみ\t合成語\t名詞\nひゃく\t100%合成\t記号"
            compose.onNodeWithText("追加", substring = false).performClick()
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.sources.value.size == 1 }
            compose.onNodeWithText("UI合成試験").assertExists()
            compose.onNodeWithText("2語 · 6時間ごと · 1時間以内に更新").assertExists()
            compose.onNodeWithText("UI合成試験").performClick()
            compose.onNodeWithText("2語 · 日本語 · 閲覧のみ").assertExists()
            compose.onNodeWithTag("entries-search").performClick().performTextInput("%")
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeBy(300)
                compose.onAllNodesWithText("100%合成").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("合成語", substring = false).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("合成語", substring = false).assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("entries-search").assertTextContains("%")
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithText("設定", substring = false).performClick()
            compose.onNodeWithText("Wi-Fiのみで取得", substring = true).assertExists()
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }
    @Test fun mixedLanguageEntriesNameTheirLanguageAndWordsWithoutReadingHaveNoEmptyLine(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val body = "# Gboard Dictionary version:1\nbrb\tbe right back\ten-US\nbrb\tbe right back\ten-GB\n\tcomplimarde\t\n"
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), RequirementsStorageTest.Gateway(), "ui-languages") }
            val repo = testApp(context, db, sync = sync, dispatch = Dispatch()) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain") }
            repo.editor.save(null, "混在言語", "https://example.org/gboard", "AUTO", "AUTO", "AUTO")
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { model.sources.sources.value.size == 1 }
            compose.onNodeWithText("混在言語").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("英語（アメリカ合衆国）").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("3語 · 3言語 · 閲覧のみ").assertExists()
            compose.onNodeWithText("英語（イギリス）").assertExists()
            compose.onNodeWithText("すべての言語").assertExists()
            assertEquals(2, compose.onAllNodesWithText("be right back").fetchSemanticsNodes().size)
            compose.onNodeWithText("complimarde").assertExists()
            compose.onAllNodes(hasText("", substring = false) and !hasSetTextAction()).assertCountEquals(0) // No blank reading line.
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }
    @Test fun multiSelectionSuppressesNavigationAndToggleAndUsesSingleDeleteConfirmation(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "ui-selection") }
            val repo = testApp(context, db, sync = sync, dispatch = Dispatch()) { url, _, _ ->
                val bytes = "よみ\t${url.last()}合成\t名詞".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val a = repo.editor.save(null, "選択A", "https://example.org/a", "AUTO", "AUTO", "AUTO")
            val b = repo.editor.save(null, "選択B", "https://example.org/b", "AUTO", "AUTO", "AUTO")
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { model.sources.sources.value.size == 2 && !model.operations.busy.value }
            compose.onNodeWithText("選択").performClick()
            compose.onNodeWithText("選択A").performClick()
            compose.onNodeWithTag("source-enabled-$a").assertIsNotEnabled()
            compose.onNodeWithText("閲覧のみ", substring = true).assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("1件選択中").assertExists()
            compose.onNodeWithText("すべて選択").performClick()
            compose.onNodeWithText("無効化", substring = false).performClick()
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.notice.value != null }
            assertFalse(db.library().source(a)!!.enabled); assertFalse(db.library().source(b)!!.enabled)
            compose.onNodeWithText("2件を受け付けました", substring = true).assertExists()
            compose.onNodeWithText("すべて選択").performClick()
            compose.onNodeWithText("削除", substring = false).performClick()
            compose.onNodeWithText("2件のソースを削除しますか？", substring = true).assertExists()
            compose.onAllNodesWithText("削除", substring = false, useUnmergedTree = true).onLast().performClick()
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.sources.value.isEmpty() }
            assertTrue(db.library().sources().isEmpty())
            assertTrue(gateway.rows.isEmpty())
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }
    @Test fun batchIsAcceptedAndAProviderFailureIsReportedOnTheRowNotTheBatch(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "ui-partial") }
            lateinit var repo: AppContainer
            val dispatch = InlineApplyDispatch { repo.applies }
            repo = testApp(context, db, sync = sync, dispatch = dispatch) { url, _, _ ->
                val bytes = "よみ\t${url.last()}語\t名詞".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val a = repo.editor.save(null, "未反映A", "https://example.org/a", "AUTO", "TSV", "AUTO")
            val b = repo.editor.save(null, "外部編集B", "https://example.org/b", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(b))
            val row = gateway.rows.values.single()
            gateway.rows[row.id] = row.copy(word = "外部編集")
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.sources.value.size == 2 }
            compose.runOnIdle { model.sources.batch(listOf(a, b), BatchOperation.DISABLE) }
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.notice.value != null }
            assertTrue(model.sources.selection.value.isEmpty())
            assertTrue(dispatch.applies.get() > 0)
            // Both desired states are stored. B's removal stopped at the external edit in the apply chain; while that
            // plan awaits review, A's write may be held back too (RECOVER_PLAN_FIRST), depending on processing order.
            assertFalse(repo.database.library().source(a)!!.enabled); assertFalse(repo.database.library().source(b)!!.enabled)
            assertNotNull(repo.database.library().source(b)!!.syncError)
            assertTrue(repo.database.library().source(a)!!.syncState in setOf(SyncState.REMOVED, SyncState.SYNC_FAILED))
            compose.onNodeWithText("2件を受け付けました", substring = true).assertExists()
            assertEquals("外部編集", gateway.rows[row.id]!!.word)
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }
    @Test fun initialRetryAfterStaysInUnsavedFormStateAndBlocksEarlyRetry(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            var calls = 0
            val repo = testApp(context, db, dispatch = Dispatch()) { _, _, _ ->
                calls++; throw me.midra.dictionarysync.data.parser.DictionaryProblem("HTTP_503", retryAfter = "120")
            }
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { !model.operations.busy.value }
            compose.runOnIdle { model.form.save(SourceDraft("待機", "https://example.org/a", "AUTO", "AUTO", "AUTO", 24)) }
            compose.waitUntil(10000) { !model.operations.busy.value && model.operations.error.value != null }
            assertEquals(1, calls); assertTrue(model.form.retryAt.value > System.currentTimeMillis())
            compose.runOnIdle { model.operations.dismissError(); model.form.save(SourceDraft("待機", "https://example.org/a", "AUTO", "AUTO", "AUTO", 24)) }
            compose.waitUntil(10000) { !model.operations.busy.value && model.operations.error.value != null }
            assertEquals("SERVER_WAIT", model.operations.error.value); assertEquals(1, calls)
            assertTrue(repo.database.library().sources().isEmpty()); assertTrue(db.requests().pending().isEmpty())
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }

    @Test fun failedAddErrorDoesNotSurviveLaterSuccessfulAdd(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repo = testApp(context, db, dispatch = Dispatch()) { url, _, _ ->
                if (url.toString().endsWith("/missing")) throw me.midra.dictionarysync.data.parser.DictionaryProblem("HTTP_404")
                val bytes = "よみ\t再試行語\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { !model.operations.busy.value }
            compose.runOnIdle { model.form.edit(null); model.form.save(SourceDraft("再試行", "https://example.org/missing")) }
            compose.waitUntil(10000) { !model.operations.busy.value && model.operations.error.value != null }
            assertTrue(model.form.open.value)
            compose.runOnIdle { model.form.save(SourceDraft("再試行", "https://example.org/found")) }
            compose.waitUntil(10000) { !model.operations.busy.value && !model.form.open.value }
            assertNull(model.operations.error.value)
            assertEquals(1, repo.database.library().sources().size)
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }
    @Test fun backgroundFailureAndRecoveryUpdateOpenScreenWithoutRestart(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "ui-background") }
            val repo = testApp(context, db, sync = sync, dispatch = Dispatch()) { url, _, _ ->
                val bytes = "よみ\t同期語\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val id = repo.editor.save(null, "バックグラウンド同期", "https://example.org/background", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(id))
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model) {} } }
            compose.waitUntil(10000) { !model.operations.busy.value && model.sources.sources.value.size == 1 }
            val row = gateway.rows.values.single()
            gateway.rows[row.id] = row.copy(word = "外部編集語")
            // A Worker/repository operation does not go through the ViewModel action callbacks.
            assertFalse(sync.synchronize(id))
            compose.waitUntil(10000) { model.review.pending.value?.plan?.state == PlanState.RECOVERY_REQUIRED }
            compose.onNodeWithText("確認する").performClick()
            compose.onNodeWithText("関連ソースを再反映").assertDoesNotExist()
            compose.onNodeWithTag("sync-all-differences").performScrollToNode(hasText("関連ソースをまとめて再反映する"))
            compose.onNodeWithText("関連ソースをまとめて再反映する").performClick()
            compose.waitUntil(10000) { !model.operations.busy.value && model.review.preview.value?.plan?.purpose == PlanPurpose.RESYNC }
            compose.onNodeWithText("関連ソースを再反映").assertExists()
            compose.onNodeWithText("再反映を実行").performClick()
            compose.waitUntil(10000) { !model.operations.busy.value && model.review.pending.value == null }
            compose.onNodeWithText("確認する").assertDoesNotExist()
            assertEquals(SyncState.SYNCED, repo.database.library().source(id)!!.syncState)
            assertEquals(setOf("同期語", "外部編集語"), gateway.rows.values.map { it.word }.toSet())
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }


    @Test fun setupRereadsReadinessAfterReturningFromDiagnostics(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        var readiness = SyncReadiness.DIAGNOSIS_REQUIRED
        try {
            val repo = testApp(context, db, sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), RequirementsStorageTest.Gateway(), "ui-setup") },
                dispatch = Dispatch(), readiness = { readiness })
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("test", model)
            // Diagnostics completes without leaving the activity, so no ON_RESUME follows.
            compose.setContent { DictionarySyncTheme { DictionarySyncApp(model, setup = true) { onBack ->
                androidx.compose.material3.Button(onClick = { readiness = SyncReadiness.READY; onBack() }) { androidx.compose.material3.Text("診断を完了して戻る") }
            } } }
            compose.onNodeWithText("ユーザー辞書への反映に必要です").assertExists()
            compose.onNodeWithText("対応診断を開く").performScrollTo().performClick()
            compose.onNodeWithText("診断を完了して戻る").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("完了しています").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ユーザー辞書への反映に必要です").assertDoesNotExist()
        } finally { store.clear(); db.close() }
    }
}
