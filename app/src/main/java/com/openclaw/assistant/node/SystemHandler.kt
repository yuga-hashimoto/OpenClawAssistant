package com.openclaw.assistant.node

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.PermissionRequester
import com.openclaw.assistant.R
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class SystemHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    suspend fun handleNotify(paramsJson: String?): GatewaySession.InvokeResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionRequester?.requestIfMissing(listOf(Manifest.permission.POST_NOTIFICATIONS))
                return GatewaySession.InvokeResult.error("NOT_AUTHORIZED", "NOT_AUTHORIZED: POST_NOTIFICATIONS permission required")
            }
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val title = (params["title"] as? JsonPrimitive)?.content ?: "OpenClaw Assistant"
        // Support both "body" (original spec) and "message" (legacy fallback)
        val body = (params["body"] as? JsonPrimitive)?.content
            ?: (params["message"] as? JsonPrimitive)?.content
            ?: ""

        if (body.isEmpty()) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "body is required")
        }

        val priority = (params["priority"] as? JsonPrimitive)?.content?.lowercase() ?: "active"
        val sound = (params["sound"] as? JsonPrimitive)?.content?.lowercase()
        val silent = sound == "none" || sound == "silent" || sound == "off" || sound == "false" || sound == "0"

        // Channel config based on priority
        val (channelId, channelImportance, notifPriority) = when (priority) {
            "passive" -> Triple(
                "openclaw.system.notify.passive",
                NotificationManager.IMPORTANCE_LOW,
                NotificationCompat.PRIORITY_LOW,
            )
            "timesensitive" -> Triple(
                "openclaw.system.notify.timesensitive",
                NotificationManager.IMPORTANCE_HIGH,
                NotificationCompat.PRIORITY_HIGH,
            )
            else -> Triple(
                "openclaw.system.notify.active",
                NotificationManager.IMPORTANCE_DEFAULT,
                NotificationCompat.PRIORITY_DEFAULT,
            )
        }

        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = when (priority) {
                "passive" -> "OpenClaw Passive Notifications"
                "timesensitive" -> "OpenClaw Time-Sensitive Notifications"
                else -> "OpenClaw Notifications"
            }
            val channel = NotificationChannel(channelId, channelName, channelImportance)
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(notifPriority)
            .setContentIntent(tapPendingIntent)

        if (silent) {
            builder.setSilent(true)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())

        return GatewaySession.InvokeResult.ok("""{"ok":true}""")
    }

    fun handleVolume(paramsJson: String?): GatewaySession.InvokeResult {
        return try {
            val params = paramsJson?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
            } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

            val level = (params["level"] as? JsonPrimitive)?.intOrNull

            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            if (level != null) {
                // Set volume
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val safeLevel = level.coerceIn(0, 100)
                val newVolume = (safeLevel / 100f * maxVolume).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                GatewaySession.InvokeResult.ok("""{"level":$safeLevel}""")
            } else {
                // Get volume
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val percentage = if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0
                GatewaySession.InvokeResult.ok("""{"level":$percentage}""")
            }
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("INTERNAL_ERROR", e.message ?: "Unknown error")
        }
    }

    fun handleBrightness(paramsJson: String?): GatewaySession.InvokeResult {
        return try {
            val params = paramsJson?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
            } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

            val level = (params["level"] as? JsonPrimitive)?.intOrNull

            if (level != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(appContext)) {
                    return GatewaySession.InvokeResult.error("PERMISSION_DENIED", "WRITE_SETTINGS permission is not granted")
                }
                // Set brightness
                val safeLevel = level.coerceIn(0, 100)
                val newBrightness = (safeLevel / 100f * 255).toInt()

                // Disable auto-brightness if setting manually
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newBrightness)

                GatewaySession.InvokeResult.ok("""{"level":$safeLevel}""")
            } else {
                // Get brightness
                val currentBrightness = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                val percentage = (currentBrightness * 100 / 255f).toInt()
                GatewaySession.InvokeResult.ok("""{"level":$percentage}""")
            }
        } catch (e: Settings.SettingNotFoundException) {
             GatewaySession.InvokeResult.error("INTERNAL_ERROR", "Could not read brightness setting")
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("INTERNAL_ERROR", e.message ?: "Unknown error")
        }
    }
}
