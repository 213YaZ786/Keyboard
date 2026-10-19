package helium314.keyboard.latin.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import helium314.keyboard.latin.R

/**
 * Right after an update the app installed itself. Android stops the app to
 * replace it and does not let it start itself again, so a notification
 * opens it in one tap. Updates installed by hand pass by silently.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(Updates.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Updates.INSTALLING, false)) return
        prefs.edit().remove(Updates.INSTALLING).apply()
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH))
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_keyboard)
            .setContentTitle("Keyboard $version")
            .setContentText("Updated. Tap to open.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, note) }
    }

    private companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7201
    }
}
