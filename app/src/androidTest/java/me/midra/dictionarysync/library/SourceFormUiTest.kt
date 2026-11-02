package me.midra.dictionarysync.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.editor.SourceForm
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The add/edit form in isolation, with synthetic callbacks. Scheduling is an interval; there is no second automatic-fetch switch. */
@RunWith(AndroidJUnit4::class)
class SourceFormUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun newFormatsCanBeExplicitlySelectedAndSaved() {
        var saved: String? = null
        compose.setContent { DictionarySyncTheme { SourceForm(SourceEntity("A", "合成", "https://example.org/a"), false, {}) { saved = it.format } } }
        val format = compose.onNode(hasText("形式", substring = false) and hasClickAction())
        format.performScrollTo().assertTextContains("自動").performClick()
        compose.onNode(hasText("Apple XML plist") and hasAnyAncestor(isPopup())).performClick()
        format.performScrollTo().assertTextContains("Apple XML plist").performClick()
        compose.onNode(hasText("Apple追加辞書CSV") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("保存", substring = false).performClick()
        assertEquals("APPLE_CSV", saved)
    }
    @Test fun allOptionsAreEditableAndSurviveSavedStateRestoration() {
        var result: List<Any>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DictionarySyncTheme {
            SourceForm(SourceEntity("A", "合成", "https://example.org/a", locale = "ja"), false, {}) { draft ->
                result = with(draft) { listOf(name, url, locale, format, encoding, syncIntervalHours) }
            }
        } }
        fun field(label: String) = compose.onNode(hasText(label, substring = false) and hasClickAction())
        fun choose(label: String) = compose.onNode(hasText(label, substring = false) and hasAnyAncestor(isPopup())).performClick()
        field("言語").performScrollTo().assertTextContains("日本語").performClick(); choose("自動")
        compose.onNode(hasText("7日", substring = false) and isToggleable()).performScrollTo().performClick()
        field("文字コード").performScrollTo().performClick(); choose("UTF-8")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("7日", substring = false) and isToggleable()).performScrollTo().assertIsOn()
        field("文字コード").performScrollTo().assertTextContains("UTF-8")
        compose.onNodeWithText("保存", substring = false).performClick()
        assertEquals(listOf("合成", "https://example.org/a", "AUTO", "AUTO", "UTF-8", 168), result)
    }
}
