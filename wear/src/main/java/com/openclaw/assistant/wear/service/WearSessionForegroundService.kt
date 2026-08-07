package com.openclaw.assistant.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openclaw.assistant.wear.R

/**
 * Foreground service that keeps the process alive and holds a partial wake lock
 * during a voice session. Prevents the OS from freezing the process mid-conversation.
 */
class WearSessionForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(getString(R.string.notification_session_content))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("WearSessionFGS", "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_session_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "wear_session"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WearSessionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("WearSessionFGS", "Background execution limits prevented starting WearSessionForegroundService: ${e.message}", e)
                try { context.stopService(intent) } catch (ex: Exception) { android.util.Log.e("WearSessionFGS", "stopService failed", ex) }
            } catch (e: SecurityException) {
                android.util.Log.e("WearSessionFGS", "Security limits prevented starting WearSessionForegroundService: ${e.message}", e)
                try { context.stopService(intent) } catch (ex: Exception) { android.util.Log.e("WearSessionFGS", "stopService failed", ex) }
            } catch (e: Exception) {
                android.util.Log.e("WearSessionFGS", "Failed to start WearSessionForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearSessionForegroundService::class.java))
        }
    }
}
