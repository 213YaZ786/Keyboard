package helium314.keyboard.latin.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import helium314.keyboard.latin.R

/**
 * The download of an update in a notification: its progress, then gone
 * when Android's installer takes over, or a failure that opens the
 * release page. Silent, on a channel of its own.
 */
class UpdateNotice(private val context: Context, private val version: String) {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Update download", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun progress(fraction: Float) {
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        post(
            base()
                .setContentText("Downloading")
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    fun failed(page: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            base()
                .setContentText("The update could not be installed. Tap to download it.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancel() = notifications.cancel(ID)

    private fun base() = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_keyboard)
        .setContentTitle("Keyboard $version")
        .setSilent(true)

    private fun post(note: android.app.Notification) {
        if (!notifications.areNotificationsEnabled()) return
        runCatching { notifications.notify(ID, note) }
    }

    private companion object {
        const val CHANNEL = "update_download"
        const val ID = 7202
    }
}
