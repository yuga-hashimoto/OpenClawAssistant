package com.openclaw.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import ai.picovoice.porcupine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.io.FileOutputStream

/**
 * ホットワード検知サービス
 * バックグラウンドで常時聴取し、ウェイクワード「OpenClaw」を検知したら音声認識開始
 */
class HotwordService : Service() {

    companion object {
        private const val TAG = "HotwordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hotword_channel"
        
        // カスタムウェイクワードファイル名（assetsに配置）
        private const val WAKE_WORD_FILE = "openclaw_android.ppn"

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
    private var porcupineManager: PorcupineManager? = null
    
    private lateinit var settings: SettingsRepository
    private lateinit var apiClient: OpenClawClient
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager

    private var isListeningForCommand = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        apiClient = OpenClawClient()
        speechManager = SpeechRecognizerManager(this)
        ttsManager = TTSManager(this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        startForeground(NOTIFICATION_ID, createNotification())
        startHotwordDetection()
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        scope.cancel()
        stopHotwordDetection()
        speechManager.destroy()
        ttsManager.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Say \"Open Claw\" to activate")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * カスタムキーワードファイルをassetsから内部ストレージにコピー
     */
    private fun copyKeywordFile(): String? {
        return try {
            val outputFile = File(filesDir, WAKE_WORD_FILE)
            
            // 既にコピー済みならスキップ
            if (outputFile.exists()) {
                return outputFile.absolutePath
            }
            
            // assetsからコピー
            assets.open(WAKE_WORD_FILE).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "Keyword file copied to: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy keyword file", e)
            null
        }
    }

    private fun startHotwordDetection() {
        val accessKey = settings.picovoiceAccessKey
        if (accessKey.isBlank()) {
            Log.e(TAG, "Picovoice access key not configured")
            stopSelf()
            return
        }

        try {
            // カスタムキーワードファイルをコピー
            val keywordPath = copyKeywordFile()
            
            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(0.7f)
            
            if (keywordPath != null) {
                // カスタムキーワード「OpenClaw」を使用
                Log.d(TAG, "Using custom keyword: OpenClaw")
                builder.setKeywordPath(keywordPath)
            } else {
                // フォールバック: ビルトインキーワード「Porcupine」を使用
                Log.w(TAG, "Custom keyword not found, falling back to 'Porcupine'")
                builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            }

            porcupineManager = builder.build(this) { keywordIndex ->
                Log.d(TAG, "Hotword 'OpenClaw' detected!")
                onHotwordDetected()
            }

            porcupineManager?.start()
            Log.d(TAG, "Hotword detection started")

        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to start Porcupine", e)
            // カスタムキーワードで失敗した場合、ビルトインで再試行
            tryFallbackKeyword(accessKey)
        }
    }

    private fun tryFallbackKeyword(accessKey: String) {
        try {
            Log.d(TAG, "Trying fallback keyword 'Porcupine'")
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.7f)
                .build(this) { keywordIndex ->
                    Log.d(TAG, "Hotword detected (fallback)!")
                    onHotwordDetected()
                }

            porcupineManager?.start()
            Log.d(TAG, "Fallback hotword detection started")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Fallback also failed", e)
            stopSelf()
        }
    }

    private fun stopHotwordDetection() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine", e)
        }
    }

    private fun onHotwordDetected() {
        if (isListeningForCommand) return
        isListeningForCommand = true

        // ホットワード検知を一時停止
        porcupineManager?.stop()

        // 確認音を鳴らす（オプション）
        playConfirmationBeep()

        // 音声認識開始
        startCommandListening()
    }

    private fun playConfirmationBeep() {
        // 短いビープ音で応答（TTSで代用）
        scope.launch {
            // 「Yes」と短く応答
            ttsManager.speak("Yes")
        }
    }

    private fun startCommandListening() {
        scope.launch {
            var recognizedText: String? = null

            speechManager.startListening("ja-JP").collectLatest { result ->
                when (result) {
                    is SpeechResult.Result -> {
                        Log.d(TAG, "Command recognized: ${result.text}")
                        recognizedText = result.text
                    }
                    is SpeechResult.Error -> {
                        Log.e(TAG, "Speech error: ${result.message}")
                        resumeHotwordDetection()
                    }
                    else -> {}
                }
            }

            // 音声認識完了後、OpenClawに送信
            recognizedText?.let { text ->
                sendToOpenClaw(text)
            } ?: run {
                resumeHotwordDetection()
            }
        }
    }

    private fun sendToOpenClaw(message: String) {
        if (!settings.isConfigured()) {
            Log.e(TAG, "Webhook not configured")
            scope.launch {
                ttsManager.speak("Configuration required")
                resumeHotwordDetection()
            }
            return
        }

        scope.launch {
            val result = apiClient.sendMessage(
                webhookUrl = settings.webhookUrl,
                message = message,
                sessionId = settings.sessionId,
                userId = settings.userId.takeIf { it.isNotBlank() },
                authToken = settings.authToken.takeIf { it.isNotBlank() }
            )

            result.fold(
                onSuccess = { response ->
                    val responseText = response.getResponseText()
                    if (responseText != null) {
                        ttsManager.speak(responseText)
                    }
                    resumeHotwordDetection()
                },
                onFailure = { error ->
                    Log.e(TAG, "API error", error)
                    ttsManager.speak("An error occurred")
                    resumeHotwordDetection()
                }
            )
        }
    }

    private fun resumeHotwordDetection() {
        isListeningForCommand = false
        scope.launch {
            delay(500) // 少し待ってから再開
            try {
                porcupineManager?.start()
                Log.d(TAG, "Hotword detection resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming hotword detection", e)
            }
        }
    }
}
