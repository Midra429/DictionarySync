package me.midra.dictionarysync.library

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.MainActivity
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.Preferences
import me.midra.dictionarysync.data.local.SourceEntity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real HTTPS + production Activity journey. The target IME must stay disabled. */
@RunWith(AndroidJUnit4::class)
class PublicSourceUiAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = DictionaryDatabase.get(context)
    private val names = listOf("公開HTTPS確認・TSV", "公開HTTPS確認・plist")

    @Test fun addBrowseAndBatchWithoutProviderAccess() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowPublicSourceUi") == "isolated-no-provider-v1")
        check(context.packageName == "me.midra.dictionarysync.acceptance")
        assertImeDisabled()
        val urls = listOf("publicTsvUrlBase64", "publicPlistUrlBase64").map { key ->
            requireNotNull(args.getString(key)) { key }.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
        }
        val existing = runBlocking { db.library().allSources() }
        check(existing.none { (it.url in urls || it.name in names) && it.lifecycle != "ACTIVE" }) { "Completed/prior ledger: do not recreate" }
        val before = existing.filter { it.lifecycle != "DELETED" && it.url !in urls }
        val ids = mutableListOf<String>()
        runBlocking { Preferences(context).setSetupDone(true) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("source-list").assertExists()
            urls.forEachIndexed { index, url ->
                assertImeDisabled()
                val prior = existing.singleOrNull { row -> row.url == url }
                if (prior == null) {
                    compose.onNodeWithContentDescription("ソースを追加").performClick()
                    compose.onNodeWithText("名前").performClick().performTextInput(names[index])
                    compose.onNodeWithText("辞書ファイルのURL").performScrollTo().performClick().performTextInput(url)
                    compose.onNode(hasText("24時間", substring = false) and isToggleable()).performScrollTo().assertIsOn()
                    compose.onNodeWithText("追加", substring = false).performClick()
                } else {
                    // Resume a failed UI assertion without refetching or erasing saved data/history.
                    check(prior.name == names[index] && prior.activeSnapshot == null)
                    check(runBlocking { db.sync().references().none { it.sourceId == prior.id } })
                    if (!prior.enabled) {
                        compose.onNodeWithTag("source-list").performScrollToNode(hasTestTag("source-enabled-${prior.id}"))
                        compose.onNodeWithTag("source-enabled-${prior.id}").performClick()
                    }
                    report("Resume saved source ${prior.id}; no new fetch")
                }
                compose.waitUntil(60_000) {
                    runBlocking { db.library().sources().any { row -> row.name == names[index] && row.syncError == "IME_DISABLED" } }
                }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("キャンセル").fetchSemanticsNodes().isEmpty() }
                val source = runBlocking { db.library().sources().single { row -> row.name == names[index] } }
                ids.add(source.id)
                assertEquals("AUTO", source.locale)
                assertEquals("AUTO", source.format)
                assertEquals("AUTO", source.encoding)
                assertEquals(24, source.syncIntervalHours)
                assertTrue(source.enabled)
                val snapshot = runBlocking { db.library().snapshot(source.latestSnapshot!!)!! }
                assertTrue(snapshot.count > 0)
                assertEquals(if (index == 0) "ime-tsv-v1" else "apple-plist-xml-v1", snapshot.parser)
                assertEquals("ja", snapshot.resolvedLocale)
                report("${names[index]}: bytes=${snapshot.bytes}; entries=${snapshot.count}; parser=${snapshot.parser}; encoding=${snapshot.encoding}; sha256=${snapshot.bodyHash}; finalUrl=${snapshot.finalUrl}")
                compose.onNodeWithTag("source-list").performScrollToNode(hasText(names[index]))
                compose.onNodeWithText(names[index], useUnmergedTree = true).performScrollTo().performClick()
                compose.onNodeWithText("${java.text.NumberFormat.getIntegerInstance().format(snapshot.count)}語 · 閲覧のみ").assertIsDisplayed()
                val entry = runBlocking { db.library().entries(snapshot.id).first() }
                compose.onNodeWithTag("entries-search").performScrollTo().performClick().performTextInput(entry.word)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText(entry.word, substring = false) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("戻る").performClick()
            }
            select(ids)
            compose.onNodeWithText("無効化", substring = false).performClick()
            waitFor(ids) { !it.enabled && it.syncState == "REMOVED" }
            compose.onNodeWithText("2件を受け付けました", substring = true).assertExists()
            val snapshots = runBlocking { ids.map { db.library().source(it)!!.latestSnapshot } }
            select(ids, enter = false)
            compose.onNodeWithText("有効化", substring = false).performClick()
            waitFor(ids) { it.enabled && it.syncError == "IME_DISABLED" }
            // Accepted on screen; the apply Worker then reports the missing keyboard on each row.
            compose.onNodeWithText("2件を受け付けました", substring = true).assertExists()
            // Disabling again needs no Provider access for unapplied sources.
            select(ids)
            compose.onNodeWithText("無効化", substring = false).performClick()
            waitFor(ids) { !it.enabled && it.syncState == "REMOVED" }
            assertEquals(snapshots, runBlocking { ids.map { db.library().source(it)!!.latestSnapshot } })
            select(ids, enter = false)
            compose.onNodeWithText("削除", substring = false).performClick()
            compose.onNodeWithText("2件のソースを削除しますか？").assertIsDisplayed()
            compose.onNode(hasText("削除", substring = false) and hasAnyAncestor(isDialog())).performClick()
            waitFor(ids) { it.lifecycle == "DELETED" }
            compose.onNodeWithText("2件を受け付けました", substring = true).assertExists()
        }
        assertImeDisabled()
        runBlocking {
            assertEquals(before, db.library().sources())
            assertTrue(db.sync().references().none { it.sourceId in ids })
            assertTrue(db.sync().plans().none { it.sourceId in ids })
            assertTrue(ids.all { db.library().source(it)!!.activeSnapshot == null })
        }
        report("Completed: real HTTPS/form/browse; batch disable/delete 2 successes; batch enable 2 IME_DISABLED failures retained; existing sources unchanged; no Provider plans/references")
    }

    private fun select(ids: List<String>, enter: Boolean = true) {
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

    private fun waitFor(ids: List<String>, predicate: (SourceEntity) -> Boolean) {
        compose.waitUntil(20_000) { runBlocking { ids.all { predicate(db.library().source(it)!!) } } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("すべて選択", substring = false) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertImeDisabled() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        check(manager.enabledInputMethodList.none { it.packageName == context.packageName }) { "Disable acceptance IME before this no-Provider journey" }
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
