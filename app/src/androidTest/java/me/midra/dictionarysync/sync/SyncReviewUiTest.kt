package me.midra.dictionarysync.sync

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.review.SyncReview
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A dialog rendered in isolation. No app databases, diagnostics or Provider mutations. */
@RunWith(AndroidJUnit4::class)
class SyncReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun preview(purpose: String, blockers: List<String> = emptyList()): SyncPreview {
        val plan = SyncPlan("child", "A", 1, "old", "candidate", "install", "fake", "token",
            approved = purpose == "APPLY", state = if (purpose == "APPLY") "RECOVERY_REQUIRED" else "PREPARED",
            createdAt = 1, purpose = purpose)
        return SyncPreview(plan, emptyList(), emptyList(), emptyList(), 1, 0, 1, listOf("A", "B"), blockers)
    }
    @Test fun resyncDisplaysCombinedSourcesAndApprovalAction() {
        var applied = false
        compose.setContent { DictionarySyncTheme { SyncReview(preview("RESYNC"), false,
            listOf(SourceEntity("A", "ソース甲", "https://example.org/a"), SourceEntity("B", "ソース乙", "https://example.org/b")),
            {}, {}, { applied = true }, {}) } }
        compose.onNodeWithText("関連ソースを再反映").assertIsDisplayed()
        compose.onNodeWithTag("sync-all-differences").performScrollToNode(hasText("まとめて処理するソース: ソース甲、ソース乙"))
        compose.onNodeWithText("まとめて処理するソース: ソース甲、ソース乙").assertIsDisplayed()
        compose.onNodeWithText("端末側で編集された単語は残します。", substring = true).assertExists()
        compose.onNodeWithText("再反映を実行").performClick()
        assertTrue(applied)
    }
    @Test fun unsafeResyncIsDisabledAndPartialCompensationWarnsIncomplete() {
        compose.setContent { DictionarySyncTheme { SyncReview(preview("RESYNC", listOf("INSERT_UNKNOWN")), false,
            emptyList(), {}, {}, {}, {}) } }
        compose.onNodeWithText("再反映を実行").assertIsNotEnabled()
        compose.onNodeWithTag("sync-all-differences").performScrollToNode(hasText("未解決: INSERT_UNKNOWN"))
        compose.onNodeWithText("未解決: INSERT_UNKNOWN").assertExists()
        compose.onNodeWithText("確認できる範囲だけを処理します。").assertExists()
    }
    @Test fun originalFailureOpensReviewBeforeAnyRecoveryIsApplied() {
        var chosen: String? = null
        var applied = false
        compose.setContent { DictionarySyncTheme { SyncReview(preview("APPLY"), false,
            emptyList(), {}, {}, { applied = true }, { chosen = it }) } }
        compose.onNodeWithTag("sync-all-differences").performScrollToNode(hasText("今回の反映を取り消して元に戻す"))
        compose.onNodeWithText("今回の反映を取り消して元に戻す").performClick()
        assertEquals("COMPENSATE", chosen); assertFalse(applied)
    }
    @Test fun fullDifferencesIncludeBeyondTwentyAndDistinguishRetainedRows() {
        val adds = (1..25).map { SyncKey.of("ja", "追加語$it", "よみ$it") }
        val deleted = SyncKey.of("ja", "削除語", "さくじょ")
        val shared = SyncKey.of("ja", "共有保持語", "きょうゆう")
        val p = SyncPlan("plan", "A", 1, "old", "candidate", "install", "fake", "token", createdAt = 1)
        val preview = SyncPreview(p, adds, listOf(deleted, shared), listOf(deleted), 1, 1, 25)
        var applied = false
        compose.setContent { DictionarySyncTheme { SyncReview(preview, false, listOf(SourceEntity("A", "合成ソース", "https://example.org/a")), {}, {}, { applied = true }, {}) } }
        val list = compose.onNodeWithTag("sync-all-differences")
        list.performScrollToNode(hasText("よみ25 → 追加語25 [ja]"))
        compose.onNode(hasText("追加", substring = false) and hasText("よみ25 → 追加語25 [ja]")).assertIsDisplayed()
        list.performScrollToNode(hasText("きょうゆう → 共有保持語 [ja]"))
        compose.onNode(hasText("対象外（単語は残す）") and hasText("きょうゆう → 共有保持語 [ja]")).assertExists()
        compose.onNode(hasText("削除", substring = false) and hasText("さくじょ → 削除語 [ja]")).assertExists()
        list.performScrollToNode(hasTestTag("differences-search"))
        compose.onNodeWithTag("differences-search").performClick().performTextInput("保持")
        compose.onNodeWithText("きょうゆう → 共有保持語 [ja]").assertExists()
        compose.onNodeWithText("よみ25 → 追加語25 [ja]").assertDoesNotExist()
        compose.onNodeWithText("追加 0・対象外 1").assertExists()
        assertFalse(applied)
    }
}
