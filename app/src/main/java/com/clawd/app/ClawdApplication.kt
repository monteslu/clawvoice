package com.clawd.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ClawdApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Connection service channel
            val connectionChannel = NotificationChannel(
                CHANNEL_CONNECTION,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(connectionChannel)

            // Push notification channel
            val ntfyChannel = NotificationChannel(
                CHANNEL_NTFY,
                getString(R.string.ntfy_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.ntfy_channel_description)
            }
            notificationManager.createNotificationChannel(ntfyChannel)
        }
    }

    companion object {
        const val CHANNEL_CONNECTION = "clawd_connection"
        const val CHANNEL_NTFY = "clawd_ntfy"
    }
}
