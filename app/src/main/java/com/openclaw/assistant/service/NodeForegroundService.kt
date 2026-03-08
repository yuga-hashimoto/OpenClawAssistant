package com.openclaw.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.VoiceWakeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class NodeForegroundService : Service() {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var notificationJob: Job? = null
  private var lastRequiresMic = false
  private var didStartForeground = false
  private var lastNotification: Notification? = null

  private val pauseResumeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val runtime = (application as OpenClawApplication).nodeRuntime
      when (intent.action) {
        ACTION_PAUSE_HOTWORD -> runtime.pauseVoiceWake()
        ACTION_RESUME_HOTWORD -> runtime.resumeVoiceWake()
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    val filter = IntentFilter().apply {
      addAction(ACTION_PAUSE_HOTWORD)
      addAction(ACTION_RESUME_HOTWORD)
    }
    ContextCompat.registerReceiver(this, pauseResumeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    val initial = buildNotification(title = "OpenClaw Node", text = "Starting…")
    startForegroundWithTypes(notification = initial, requiresMic = false)

    val runtime = (application as OpenClawApplication).nodeRuntime
    notificationJob =
      scope.launch {
        combine(
          runtime.statusText,
          runtime.serverName,
          runtime.isConnected,
          runtime.voiceWakeMode,
          runtime.voiceWakeIsListening,
        ) { args ->
          val status = args[0] as String
          val server = args[1] as? String
          val connected = args[2] as Boolean
          val voiceMode = args[3] as VoiceWakeMode
          val voiceListening = args[4] as Boolean
          Quint(status, server, connected, voiceMode, voiceListening)
        }.collect { (status, server, connected, voiceMode, voiceListening) ->
          val title = if (connected) "OpenClaw Node · Connected" else "OpenClaw Node"
          val voiceSuffix =
            if (voiceMode == VoiceWakeMode.Always) {
              if (voiceListening) " · Voice Wake: Listening" else " · Voice Wake: Paused"
            } else {
              ""
            }
          val text = (server?.let { "$status · $it" } ?: status) + voiceSuffix

          val requiresMic =
            voiceMode == VoiceWakeMode.Always && hasRecordAudioPermission()

          startForegroundWithTypes(
            notification = buildNotification(title = title, text = text),
            requiresMic = requiresMic,
          )
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        (application as OpenClawApplication).nodeRuntime.disconnect()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_PREPARE_MEDIA_PROJECTION -> {
        // Android 14+: startForeground() with MEDIA_PROJECTION type must be called in response
        // to a startForegroundService() that was itself called inside the activity result callback.
        // This satisfies the OS requirement that the FGS be started from the permission grant callback.
        val notification = lastNotification ?: buildNotification("OpenClaw Node", "Starting…")
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (lastRequiresMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, notification, types)
        lastNotification = notification
        mediaProjectionReady.getAndSet(null)?.complete(Unit)
        return START_STICKY
      }
    }
    // Keep running; connection is managed by NodeRuntime (auto-reconnect + manual).
    return START_STICKY
  }

  override fun onDestroy() {
    unregisterReceiver(pauseResumeReceiver)
    notificationJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  private fun ensureChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Connection",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "OpenClaw node connection status"
        setShowBadge(false)
      }
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(title: String, text: String): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchPending =
      PendingIntent.getActivity(
        this,
        1,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, NodeForegroundService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        2,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Disconnect", stopPending)
      .build()
  }

  private fun updateNotification(notification: Notification) {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, notification)
  }

  private fun startForegroundWithTypes(notification: Notification, requiresMic: Boolean) {
    if (didStartForeground && requiresMic == lastRequiresMic) {
      updateNotification(notification)
      lastNotification = notification
      return
    }

    lastRequiresMic = requiresMic

    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    if (requiresMic) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    startForeground(NOTIFICATION_ID, notification, types)
    lastNotification = notification
    didStartForeground = true
  }


  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  companion object {
    private const val CHANNEL_ID = "connection"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_STOP = "com.openclaw.assistant.action.STOP"
    private const val ACTION_PAUSE_HOTWORD = "com.openclaw.assistant.ACTION_PAUSE_HOTWORD"
    private const val ACTION_RESUME_HOTWORD = "com.openclaw.assistant.ACTION_RESUME_HOTWORD"
    private const val ACTION_PREPARE_MEDIA_PROJECTION = "com.openclaw.assistant.action.PREPARE_MEDIA_PROJECTION"

    // Completed when startForeground(MEDIA_PROJECTION) is called from ACTION_PREPARE_MEDIA_PROJECTION.
    // ScreenRecordManager awaits this before calling getMediaProjection() on Android 14+.
    val mediaProjectionReady = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun start(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }

    /**
     * Must be called from within an ActivityResult callback (after user grants media projection
     * permission). Sends ACTION_PREPARE_MEDIA_PROJECTION to the service so it can call
     * startForeground() with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION while still within the
     * OS-granted background-start exemption window.
     */
    fun prepareForMediaProjection(context: Context) {
      val deferred = CompletableDeferred<Unit>()
      mediaProjectionReady.set(deferred)
      val intent = Intent(context, NodeForegroundService::class.java)
        .setAction(ACTION_PREPARE_MEDIA_PROJECTION)
      context.startForegroundService(intent)
    }
  }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
