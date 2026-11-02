package me.midra.dictionarysync.library

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.MainActivity
import kotlinx.coroutines.flow.first
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.Preferences
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.Downloader
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp

/** Real Activity/lifecycle, isolated application data, no network or Provider writes. */
@RunWith(AndroidJUnit4::class)
class MainActivityAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceName = "画面確認用の長いソース名・回転と文字サイズを変更しても内容と操作を保持"
    private lateinit var sourceId: String

    @Before fun seedIsolatedData(): Unit = runBlocking {
        check(context.packageName == "me.midra.dictionarysync.acceptance") {
            "Run only with -PisolatedAcceptance=true; never touch the user's application database"
        }
        val repo = testApp(context) { url, _, _ ->
            val bytes = "かいてん\t回転確認語\t名詞\nほか\t別の確認語\t名詞".toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
        val existing = repo.database.library().sources().find { it.url == "https://example.org/activity-acceptance" }
        sourceId = existing?.id ?: repo.editor.save(null, sourceName, "https://example.org/activity-acceptance", "AUTO", "AUTO", "AUTO")
        // A downloaded but disabled source exercises browsing without scheduling real HTTP/Provider work.
        repo.database.library().updateSource(repo.database.library().source(sourceId)!!.copy(enabled = false, syncState = SyncState.REMOVED))
        repo.preferences.setSetupDone(true)
    }

    @Test fun formSurvivesActivityRecreationAndLandscape(): Unit = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        compose.onNodeWithContentDescription("ソースを追加").performClick()
        compose.onNodeWithText("名前").performClick().performTextInput("回転中の入力")
        compose.onNodeWithText("辞書ファイルのURL").performScrollTo().performClick().performTextInput("https://example.org/draft?x=1&x=2")
        compose.onNode(hasText("6時間", substring = false) and isToggleable()).performScrollTo().performClick()
        scenario.recreate()
        assertDraft()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertDraft()
        compose.onNodeWithText("追加", substring = false).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("キャンセル").performClick()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun assertDraft() {
        compose.onNodeWithText("名前").performScrollTo().assertTextContains("回転中の入力")
        compose.onNodeWithText("辞書ファイルのURL").performScrollTo().assertTextContains("https://example.org/draft?x=1&x=2")
        compose.onNode(hasText("6時間", substring = false) and isToggleable()).performScrollTo().assertIsDisplayed().assertIsOn()
    }

    @Test fun searchSelectionAndAccessibleSwitchSurviveRecreation(): Unit = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        compose.onNodeWithTag("source-list").performScrollToNode(hasTestTag("source-enabled-$sourceId"))
        compose.onNodeWithTag("source-enabled-$sourceId")
            .assertContentDescriptionEquals("$sourceName、無効")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        compose.onNodeWithText(sourceName).performClick()
        compose.onNodeWithTag("entries-search").performScrollTo().performClick().performTextInput("回転")
        waitForSearch()
        scenario.recreate()
        compose.onNodeWithTag("entries-search").performScrollTo().assertTextContains("回転")
        waitForSearch()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("選択", substring = false).performClick()
        compose.onNodeWithTag("source-list").performScrollToNode(hasText(sourceName))
        compose.onNodeWithText(sourceName).performClick()
        scenario.recreate()
        compose.onNodeWithTag("source-list").performScrollToNode(hasContentDescription("$sourceName、選択済み"))
        compose.onNodeWithContentDescription("$sourceName、選択済み").assertIsSelected()
        compose.onNodeWithTag("source-list").performScrollToNode(hasTestTag("source-enabled-$sourceId"))
        compose.onNodeWithTag("source-enabled-$sourceId").assertIsNotEnabled()
        assertEquals(false, runBlocking { DictionaryDatabase.get(context).library().source(sourceId)!!.enabled })
    }

    private fun waitForSearch() {
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithText("回転確認語").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("別の確認語").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun wifiSettingExposesLabelAndSwitchAsOneAccessibleControl(): Unit = ActivityScenario.launch(MainActivity::class.java).use {
        compose.onNodeWithText("設定", substring = false).performClick()
        compose.onNodeWithText("Wi-Fiのみで取得")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsToggleable().assertIsDisplayed()
    }

    @Test fun firstLaunchShowsSetupUntilFinished() {
        runBlocking { Preferences(context).setSetupDone(false) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("バックグラウンドでの実行を許可する").assertIsDisplayed()
            compose.onNodeWithText("電池の最適化から除外").assertExists()
            compose.onNodeWithText("対応診断を開く").performScrollTo().performClick()
            compose.onNodeWithText("キーボードを有効にする").assertIsDisplayed()
            compose.onNodeWithContentDescription("戻る").performClick()
            // Setup has no navigation bar; the step survives recreation.
            assertEquals(0, compose.onAllNodesWithText("設定", substring = false).fetchSemanticsNodes().size)
            scenario.recreate()
            compose.onNodeWithText("はじめる").performClick()
            compose.onNodeWithTag("source-list").assertExists()
            compose.waitUntil(5000) { runBlocking { Preferences(context).setupDone.first() } }
        }
        ActivityScenario.launch(MainActivity::class.java).use { compose.onNodeWithTag("source-list").assertExists() }
    }

    @Test fun settingsOfferBatteryOptimizationExemption(): Unit = ActivityScenario.launch(MainActivity::class.java).use {
        compose.onNodeWithText("設定", substring = false).performClick()
        compose.onNodeWithText("電池の最適化から除外").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }
}
