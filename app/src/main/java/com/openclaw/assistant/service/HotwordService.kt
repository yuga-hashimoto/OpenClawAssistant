package com.openclaw.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import org.json.JSONObject

/**
 * ホットワード検知サービス (Vosk)
 */
class HotwordService : Service(), VoskRecognitionListener {

    companion object {
        private const val TAG = "HotwordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hotword_channel"
        private const val SAMPLE_RATE = 16000.0f
        const val ACTION_RESUME_HOTWORD = "com.openclaw.assistant.ACTION_RESUME_HOTWORD"
        const val ACTION_PAUSE_HOTWORD = "com.openclaw.assistant.ACTION_PAUSE_HOTWORD"
        
        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private lateinit var settings: SettingsRepository

    private var isListeningForCommand = false
    private var isSessionActive = false

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_HOTWORD -> {
                    Log.d(TAG, "Pause signal received")
                    isSessionActive = true
                    speechService?.stop()
                    speechService?.shutdown()
                    speechService = null
                    isListeningForCommand = false
                }
                ACTION_RESUME_HOTWORD -> {
                    Log.d(TAG, "Resume signal received")
                    // Reset both flags to ensure clean state
                    isSessionActive = false
                    isListeningForCommand = false

                    // Ensure speechService is cleaned up
                    speechService?.let {
                        try {
                            it.stop()
                            it.shutdown()
                        } catch (e: Exception) { /* ignore */ }
                    }
                    speechService = null

                    resumeHotwordDetection()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        
        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction(ACTION_RESUME_HOTWORD)
            addAction(ACTION_PAUSE_HOTWORD)
        }
        // Internal broadcast receiver: Do not export to other apps
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        initVosk()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {}
        scope.cancel()
        speechService?.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun initVosk() {
        scope.launch(Dispatchers.IO) {
            try {
                val modelPath = copyAssets()
                if (modelPath != null) {
                    model = Model(modelPath)
                    withContext(Dispatchers.Main) {
                        if (!isSessionActive) startHotwordListening()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
            }
        }
    }

    private fun copyAssets(): String? {
        val targetDir = java.io.File(filesDir, "model")
        try {
            copyAssetFolder(assets, "model", targetDir.absolutePath)
            return targetDir.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            val files = assetManager.list(fromAssetPath) ?: return false
            java.io.File(toPath).mkdirs()
            var res = true
            for (file in files) {
                if (file.contains(".")) {
                    res = res and copyAsset(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                } else {
                    res = res and copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
            }
            return res
        } catch (e: Exception) {
            return false
        }
    }

    private fun copyAsset(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        var inStream: java.io.`InputStream`? = null
        var outStream: java.io.OutputStream? = null
        try {
            inStream = assetManager.open(fromAssetPath)
            java.io.File(toPath).createNewFile()
            outStream = java.io.FileOutputStream(toPath)
            inStream.copyTo(outStream)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            inStream?.close()
            outStream?.close()
        }
    }

    private fun startHotwordListening() {
        if (model == null || isSessionActive) return
        try {
            // Get wake words from settings
            val wakeWords = settings.getWakeWords()
            val wakeWordsJson = wakeWords.joinToString("\", \"", "[\"", "\"]")
            Log.d(TAG, "Starting hotword detection with words: $wakeWordsJson")
            
            val rec = Recognizer(model, SAMPLE_RATE, wakeWordsJson)
            speechService = SpeechService(rec, SAMPLE_RATE)
            speechService?.startListening(this)
            Log.d(TAG, "Hotword listening started")
        } catch (e: IOException) {
            Log.e(TAG, "Start error", e)
        }
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (isListeningForCommand || isSessionActive) return
        hypothesis?.let {
            val json = JSONObject(it)
            val text = json.optString("text", "")
            
            // Check against configured wake words
            val wakeWords = settings.getWakeWords()
            val detected = wakeWords.any { word -> text.contains(word) }
            
            if (detected) {
                Log.e(TAG, "Hotword detected! Text: $text")
                onHotwordDetected()
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk Error: " + exception?.message)
        if (isSessionActive) return
        scope.launch {
            delay(3000)
            if (!isSessionActive) resumeHotwordDetection()
        }
    }

    override fun onTimeout() {
        if (!isListeningForCommand && !isSessionActive) {
            speechService?.startListening(this)
        }
    }

    private fun onHotwordDetected() {
        if (isListeningForCommand || isSessionActive) return
        isListeningForCommand = true
        isSessionActive = true

        Log.d(TAG, "Hotword Detected! Triggering Assistant Overlay...")

        // Stop service on Main thread to avoid race conditions
        scope.launch {
            // Ensure speechService is safely stopped
            speechService?.let {
                try {
                    it.stop()
                    it.shutdown()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop speech service", e)
                }
            }
            speechService = null

            delay(300) // Wait for resource release

            val intent = Intent(this@HotwordService, OpenClawAssistantService::class.java).apply {
                action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT
            }
            startService(intent)
            Log.e(TAG, "startService ACTION_SHOW_ASSISTANT called")
        }
    }

    private fun resumeHotwordDetection() {
        if (isSessionActive) return
        isListeningForCommand = false
        scope.launch {
            delay(500)
            if (!isSessionActive && speechService == null) {
                startHotwordListening()
            }
        }
    }
}
