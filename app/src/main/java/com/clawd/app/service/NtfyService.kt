package com.clawd.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clawd.app.ClawdApplication
import com.clawd.app.MainActivity
import com.clawd.app.R
import com.clawd.app.data.SecureStorage
import com.clawd.app.network.NtfyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NtfyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ntfyClient: NtfyClient? = null
    private var notificationId = 1000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createServiceNotification())

        val topic = SecureStorage.getNtfyTopic(this)
        if (topic.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        ntfyClient = NtfyClient(topic)
        ntfyClient?.connect()

        scope.launch {
            ntfyClient?.messages?.collect { message ->
                showNotification(message.title ?: "Clawd", message.message ?: "New message")
            }
        }

        return START_STICKY
    }

    private fun createServiceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ClawdApplication.CHANNEL_CONNECTION)
            .setContentTitle("Clawd")
            .setContentText("Listening for notifications")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showNotification(title: String, message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ClawdApplication.CHANNEL_NTFY)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId++, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        ntfyClient?.disconnect()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 2
    }
}
