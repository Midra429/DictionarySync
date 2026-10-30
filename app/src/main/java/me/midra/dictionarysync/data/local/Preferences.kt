package me.midra.dictionarysync.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dictionaryPreferences by preferencesDataStore("dictionary-preferences")
class Preferences(context: Context) {
    private val store = context.applicationContext.dictionaryPreferences
    private val wifi = booleanPreferencesKey("wifi_only")
    private val setup = booleanPreferencesKey("setup_done")
    val wifiOnly = store.data.map { it[wifi] ?: true }
    /** Whether the first-launch setup was finished or skipped. */
    val setupDone = store.data.map { it[setup] ?: false }
    suspend fun setWifiOnly(value: Boolean) { store.edit { it[wifi] = value } }
    suspend fun setSetupDone(value: Boolean) { store.edit { it[setup] = value } }
}
