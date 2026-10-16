package com.datanet.share.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.datanet.share.MainActivity

object ServiceNotifications {
    fun build(context: Context, title: String, text: String, stopIntent: Intent): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Constants.CHANNEL_ID, "DataNet Share", NotificationManager.IMPORTANCE_LOW))
        val content = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, stopIntent.component.hashCode(), stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, Constants.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text).setContentIntent(content)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop).build()
    }
}
