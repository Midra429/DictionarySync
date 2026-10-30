package me.midra.dictionarysync

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.midra.dictionarysync.library.LibraryViewModel
import me.midra.dictionarysync.probe.ProbeViewModel
import me.midra.dictionarysync.ui.DictionarySyncApp
import me.midra.dictionarysync.ui.diagnostics.DiagnosticsScreen
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme

class MainActivity : ComponentActivity() {
    private val probe: ProbeViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        probe.refreshImeState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DictionarySyncTheme {
                // Nothing is drawn for the one frame before the setting is read, so neither screen flashes.
                val setupDone by library.setupDone.collectAsStateWithLifecycle()
                setupDone?.let { done ->
                    DictionarySyncApp(library, setup = !done) { onBack ->
                        DiagnosticsScreen(probe, onBack,
                            onOpenSettings = { openSettings(Settings.ACTION_USER_DICTIONARY_SETTINGS) },
                            onOpenImeSettings = { openSettings(Settings.ACTION_INPUT_METHOD_SETTINGS) })
                    }
                }
            }
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.probe_settings_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.probe_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
