package com.taskmesh.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskmesh.app.R

/**
 * Posts [AppEvent]s as system notifications on the "taskmesh_jobs" channel.
 * Created in Application#onCreate (minSdk 26, so channels need no version check).
 */
class NotificationHelper(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Job events",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "TaskMesh job completion, failure, timeout and worker health events"
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return true
    }

    fun post(event: AppEvent) {
        if (!canPostNotifications()) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(event.id.toInt(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post: ignore.
        }
    }

    companion object {
        const val CHANNEL_ID = "taskmesh_jobs"
    }
}
