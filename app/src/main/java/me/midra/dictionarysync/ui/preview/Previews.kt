package me.midra.dictionarysync.ui.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Light/dark, large font, narrow phone and expanded width variants for key components. */
@Preview(name = "ライト", locale = "ja", showBackground = true)
@Preview(name = "ダーク", locale = "ja", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "文字200%", locale = "ja", showBackground = true, fontScale = 2f)
@Preview(name = "狭い画面", locale = "ja", showBackground = true, widthDp = 320)
@Preview(name = "広い画面", locale = "ja", showBackground = true, widthDp = 840, heightDp = 600)
annotation class DictionaryPreviews
