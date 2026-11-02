package me.midra.dictionarysync.ui.settings

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.sync.BackgroundAccess
import me.midra.dictionarysync.sync.SyncCoordinator
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.common.Operations

/**
 * App-wide settings and the device conditions shown with them. The conditions are read-only and can change in system
 * settings at any time, so screens refresh them on resume; each is null until first checked.
 */
class SettingsState(private val scope: CoroutineScope, private val context: Context, private val app: AppContainer,
    private val operations: Operations) {
    val wifiOnly: StateFlow<Boolean> = app.preferences.wifiOnly.stateIn(scope, SharingStarted.WhileSubscribed(5000), true)
    /** null until read, so the first frame can choose between the setup and the source list. */
    val setupDone: StateFlow<Boolean?> = app.preferences.setupDone.map<Boolean, Boolean?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    private val readinessValue = MutableStateFlow<SyncReadiness?>(null)
    private val batteryValue = MutableStateFlow<Boolean?>(null)
    private val notificationsValue = MutableStateFlow<Boolean?>(null)
    /** Whether shared-dictionary writes can currently run. */
    val readiness: StateFlow<SyncReadiness?> = readinessValue.asStateFlow()
    /** Whether battery optimization still applies to background work. */
    val batteryOptimized: StateFlow<Boolean?> = batteryValue.asStateFlow()
    /** Whether sync notifications can be shown. */
    val notificationsEnabled: StateFlow<Boolean?> = notificationsValue.asStateFlow()

    fun refreshReadiness() {
        scope.launch {
            readinessValue.value = try { withContext(Dispatchers.IO) { app.readiness() } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                SyncReadiness.UNSUPPORTED
            }
        }
    }
    fun refreshBatteryOptimization() { batteryValue.value = BackgroundAccess.batteryOptimized(context) }
    fun refreshNotifications() { notificationsValue.value = BackgroundAccess.notificationsEnabled(context) }
    fun refreshAll() { refreshReadiness(); refreshBatteryOptimization(); refreshNotifications() }

    /** Applies to every fetch; waiting requests are re-evaluated at once. */
    fun setWifiOnly(value: Boolean) = operations.run {
        SyncCoordinator.locked(context) { app.preferences.setWifiOnly(value) }
        app.scheduler.repair()
    }

    /** The first-launch setup was finished or skipped; it is not shown again. */
    fun finishSetup() {
        scope.launch {
            try { app.preferences.setSetupDone(true) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operations.report(failure)
            }
        }
    }
}
