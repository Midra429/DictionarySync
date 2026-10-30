package me.midra.dictionarysync.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.common.formatCount
import me.midra.dictionarysync.ui.common.reasonText

/** What background fetch and apply passes report as they go. Workers notify the user; every other caller ignores it. */
interface SyncEvents {
    fun fetching(source: SourceEntity) {}
    fun applying(source: SourceEntity) {}
    /** The pass ended without another one queued right behind it. */
    fun idle() {}
    /** A successful apply and the words it inserted into and deleted from the shared dictionary. */
    fun applied(source: SourceEntity, added: Int, removed: Int) {}
    /** A fetch that ended for good, after its automatic retries. */
    fun fetchFailed(source: SourceEntity, code: String) {}
    fun applyFailed(source: SourceEntity, code: String) {}

    companion object { val None = object : SyncEvents {} }
}

/**
 * Progress, change and failure notifications, one channel each so they can be turned off separately.
 * Notifying never fails a Worker: without permission, or on a system error, the notification is dropped.
 */
class SyncNotifier(context: Context) : SyncEvents {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    init {
        attempt {
            manager.createNotificationChannelsCompat(listOf(
                channel(PROGRESS_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW, R.string.notify_channel_progress, R.string.notify_channel_progress_detail),
                channel(CHANGES_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.notify_channel_changes, R.string.notify_channel_changes_detail),
                channel(FAILURES_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.notify_channel_failures, R.string.notify_channel_failures_detail),
            ))
        }
    }

    override fun fetching(source: SourceEntity) = progress(R.string.notify_fetching, source.name)
    override fun applying(source: SourceEntity) = progress(R.string.notify_applying, source.name)
    override fun idle() = attempt { manager.cancel(PROGRESS) }

    override fun applied(source: SourceEntity, added: Int, removed: Int) {
        attempt { manager.cancel(source.id, FAILURE) }
        if (added == 0 && removed == 0) return
        val text = listOfNotNull(
            added.takeIf { it > 0 }?.let { context.getString(R.string.notify_added, formatCount(it)) },
            removed.takeIf { it > 0 }?.let { context.getString(R.string.notify_removed, formatCount(it)) },
        ).joinToString(context.getString(R.string.notify_separator))
        result(source, CHANGE, CHANGES_CHANNEL, context.getString(R.string.notify_changed, source.name), text)
    }

    override fun fetchFailed(source: SourceEntity, code: String) =
        result(source, FAILURE, FAILURES_CHANNEL, context.getString(R.string.notify_fetch_failed, source.name), reasonText(context, code))

    override fun applyFailed(source: SourceEntity, code: String) =
        result(source, FAILURE, FAILURES_CHANNEL, context.getString(R.string.notify_apply_failed, source.name), reasonText(context, code))

    private fun progress(title: Int, name: String) = post(null, PROGRESS, builder(PROGRESS_CHANNEL)
        .setContentTitle(context.getString(title)).setContentText(name)
        .setProgress(0, 0, true).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        // A Worker killed with its process cannot clear it; no single pass outlives WorkManager's ten minutes.
        .setTimeoutAfter(PROGRESS_TIMEOUT_MILLIS))

    /** One change and one failure notification per source; a newer one replaces it. */
    private fun result(source: SourceEntity, id: Int, channel: String, title: String, text: String) = post(source.id, id, builder(channel)
        .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true).setCategory(if (id == FAILURE) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS))

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification_sync)
        .setContentIntent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        })

    private fun channel(id: String, importance: Int, name: Int, description: Int) = NotificationChannelCompat.Builder(id, importance)
        .setName(context.getString(name)).setDescription(context.getString(description)).build()

    @SuppressLint("MissingPermission") // Checked right before notifying.
    private fun post(tag: String?, id: Int, builder: NotificationCompat.Builder) = attempt {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@attempt
        manager.notify(tag, id, builder.build())
    }

    private inline fun attempt(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) {}
    }

    companion object {
        const val PROGRESS_CHANNEL = "sync_progress"
        const val CHANGES_CHANNEL = "sync_changes"
        const val FAILURES_CHANNEL = "sync_failures"
        private const val PROGRESS = 1
        private const val CHANGE = 2
        private const val FAILURE = 3
        private const val PROGRESS_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
