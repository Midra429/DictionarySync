package me.midra.dictionarysync.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import me.midra.dictionarysync.R

/** Dictionary access IME. Never reads, commits, records, or transmits editor contents. */
class DictionaryProbeIme : InputMethodService() {
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(context).apply {
            setText(R.string.ime_panel_explanation)
            textSize = 16f
        })
        addView(Button(context).apply {
            setText(R.string.ime_switch_keyboard)
            setOnClickListener {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        })
    }
}
