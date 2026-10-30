package me.midra.dictionarysync.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.midra.dictionarysync.R

/**
 * Notification permission and battery optimization. Battery optimization (Doze and App Standby) delays the periodic
 * fetch and apply Workers to maintenance windows and standby-bucket quotas. It is the one such setting every supported Android version offers; OEM and version-specific
 * restrictions are left to the system.
 */
object BackgroundAccess {
    fun batteryOptimized(context: Context) =
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Asks for the exemption while optimized; otherwise opens the list where it can be undone. Falls back to app details. */
    fun openBatterySettings(context: Context, optimized: Boolean) {
        val app = Uri.fromParts("package", context.packageName, null)
        val specific = if (optimized) requestExemption(app) else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(specific, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))) {
            try { context.startActivity(intent); return }
            catch (_: ActivityNotFoundException) {}
            catch (_: SecurityException) {}
        }
        Toast.makeText(context, R.string.background_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    /** Whether the app may post notifications at all; channels turned off one by one still count as enabled. */
    fun notificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** The app's notification settings, where each channel can also be turned off. Falls back to app details. */
    fun openNotificationSettings(context: Context) {
        val app = Uri.fromParts("package", context.packageName, null)
        val specific = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        for (intent in listOf(specific, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))) {
            try { context.startActivity(intent); return }
            catch (_: ActivityNotFoundException) {}
            catch (_: SecurityException) {}
        }
        Toast.makeText(context, R.string.background_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    // The system dialog asks the user directly; periodic dictionary updates are the app's core function.
    @SuppressLint("BatteryLife")
    private fun requestExemption(app: Uri) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)
}
