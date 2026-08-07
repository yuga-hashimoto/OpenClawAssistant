package com.openclaw.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
 * Hotword detection service (Vosk)
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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Background execution limits prevented starting HotwordService: ${e.message}", e)
                try {
                    context.stopService(intent)
                } catch (stopEx: Exception) {
                    Log.e(TAG, "Failed to stopService as well", stopEx)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security limits prevented starting HotwordService: ${e.message}", e)
                try {
                    context.stopService(intent)
                } catch (stopEx: Exception) {
                    Log.e(TAG, "Failed to stopService as well", stopEx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HotwordService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }

        fun shouldCopyModel(currentVersion: Int, savedVersion: Int, targetDirExists: Boolean, targetDirNotEmpty: Boolean): Boolean {
            return !(savedVersion == currentVersion && targetDirExists && targetDirNotEmpty)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private lateinit var settings: SettingsRepository

    @Volatile private var isListeningForCommand = false
    @Volatile private var isSessionActive = false
    @Volatile private var pendingInterruptLaunch = false
    private var audioRetryCount = 0
    private val MAX_AUDIO_RETRIES = 5
    private var watchdogJob: Job? = null
    private var errorRecoveryJob: Job? = null
    private var retryJob: Job? = null
    private val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    private fun debugLog(message: String) {
        if (settings.wakeWordDebugEnabled) HotwordDebugLogger.log(message)
    }

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_HOTWORD -> {
                    Log.d(TAG, "Pause signal received")
                    debugLog("Session started — hotword paused")
                    pendingInterruptLaunch = false
                    isSessionActive = true
                    speechService?.stop()
                    speechService?.shutdown()
                    speechService = null
                    isListeningForCommand = false
                    startWatchdog()
                }
                ACTION_RESUME_HOTWORD -> {
                    Log.d(TAG, "Resume signal received")
                    debugLog("Session ended — resuming hotword")
                    cancelWatchdog()
                    pendingInterruptLaunch = false
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

        // Safety net: catch uncaught Vosk thread crashes to prevent app-wide crash
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isVoskCrash = throwable.stackTrace.any {
                it.className.startsWith("org.vosk")
            }
            if (isVoskCrash) {
                Log.e(TAG, "Caught uncaught Vosk exception on thread ${thread.name}", throwable)
                if (throwable is UnsatisfiedLinkError || throwable.cause is UnsatisfiedLinkError) {
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(throwable)
                    }
                    getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("vosk_unsupported", true).apply()
                    // Don't resume - device doesn't support Vosk
                } else if (throwable is RuntimeException && throwable.message == "error reading audio buffer") {
                    // Mic unavailability during Vosk read — use exponential backoff to avoid
                    // tight restart loops when the mic is persistently unavailable.
                    speechService = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!isSessionActive) {
                            scheduleAudioRetry()
                        }
                    }
                } else {
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(throwable)
                    }
                    speechService = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!isSessionActive) {
                            resumeHotwordDetection()
                        }
                    }
                }
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        settings = SettingsRepository.getInstance(this)

        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction(ACTION_RESUME_HOTWORD)
            addAction(ACTION_PAUSE_HOTWORD)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ requires RECORD_AUDIO runtime permission for foregroundServiceType="microphone"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Cannot start foreground service with microphone type.")
            debugLog("RECORD_AUDIO permission denied — service stopped")
            showPermissionNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            // This can happen on Android 14+ if the app is not in an eligible state
            // (e.g. started from background without a visible activity). Handled gracefully.
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        initVosk()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed. Scheduling restart.")
        val restartIntent = Intent(applicationContext, HotwordService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelWatchdog()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {}
        scope.cancel()
        speechService?.shutdown()
    }

    private fun showPermissionNotification() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_permission_title))
            .setContentText(getString(R.string.notification_mic_permission_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showMicUnavailableNotification() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_unavailable_title))
            .setContentText(getString(R.string.notification_mic_unavailable_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 2, notification)
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
        val wakeWordName = settings.getWakeWordDisplayName()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content, wakeWordName))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    private fun initVosk() {
        if (model != null) {
            if (!isSessionActive) startHotwordListening()
            return
        }
        debugLog("Vosk: initializing model...")
        val prefs = getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)

        // Clear vosk_unsupported flag when the app is updated, so the new Vosk
        // native libraries get a chance to load on devices that previously failed.
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 1 }
        val unsupportedSinceVersion = prefs.getInt("vosk_unsupported_version", 0)
        if (prefs.getBoolean("vosk_unsupported", false)) {
            if (unsupportedSinceVersion < currentVersion) {
                Log.d(TAG, "App updated ($unsupportedSinceVersion -> $currentVersion). Retrying Vosk init.")
                prefs.edit().remove("vosk_unsupported").remove("vosk_unsupported_version").apply()
            } else {
                Log.w(TAG, "Vosk is unsupported on this device. Skipping init.")
                debugLog("Vosk: NOT supported on this device")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val modelPath = copyAssets()
                if (modelPath != null) {
                    debugLog("Vosk: model loaded — starting listener")
                    model = Model(modelPath)
                    withContext(Dispatchers.Main) {
                        if (!isSessionActive) startHotwordListening()
                    }
                } else {
                    debugLog("Vosk: model copy failed")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Vosk native library not supported on this device", e)
                debugLog("Vosk: UnsatisfiedLinkError — native lib not supported")
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("audio_retry_count", audioRetryCount)
                        setCustomKey("is_session_active", isSessionActive)
                        recordException(e)
                    }
                }
                prefs.edit()
                    .putBoolean("vosk_unsupported", true)
                    .putInt("vosk_unsupported_version", currentVersion)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
                debugLog("Vosk: init error — ${e.message}")
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("audio_retry_count", audioRetryCount)
                        setCustomKey("is_session_active", isSessionActive)
                        recordException(e)
                    }
                }
            }
        }
    }

    private fun copyAssets(): String? {
        val targetDir = java.io.File(filesDir, "model")

        // Check version to avoid redundant copy
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) {
            1
        }

        val prefs = getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt("model_version", 0)

        if (!shouldCopyModel(currentVersion, savedVersion, targetDir.exists(), targetDir.list()?.isNotEmpty() == true)) {
            Log.d(TAG, "Model version $savedVersion matches current $currentVersion. Skipping copy.")
            return targetDir.absolutePath
        }

        try {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }

            val success = copyAssetFolder(assets, "model", targetDir.absolutePath)
            if (success) {
                prefs.edit().putInt("model_version", currentVersion).apply()
                return targetDir.absolutePath
            }
            return null
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

        // Clean up any existing speechService to prevent resource leak
        speechService?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up existing speechService", e)
            }
            speechService = null
        }

        // Verify RECORD_AUDIO permission before touching AudioRecord
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Cannot start hotword listening.")
            debugLog("RECORD_AUDIO permission denied — cannot listen")
            return
        }

        // Pre-validate that AudioRecord can actually be created
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $bufferSize")
            debugLog("AudioRecord.getMinBufferSize failed: $bufferSize")
            scheduleAudioRetry()
            return
        }
        val testRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            null
        }
        if (testRecord == null || testRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize. Mic may be in use or unavailable.")
            debugLog("AudioRecord init failed — mic may be in use or unavailable")
            testRecord?.release()
            scheduleAudioRetry()
            return
        }
        testRecord.release()
        audioRetryCount = 0

        try {
            // Get wake words from settings; add [unk] so Vosk can absorb non-matching speech
            val wakeWords = settings.getWakeWordTargets().map { it.phrase }
            val wakeWordsJson = (wakeWords + "[unk]").joinToString("\", \"", "[\"", "\"]")
            Log.d(TAG, "Starting hotword detection with words: $wakeWordsJson")
            debugLog("Listening for: ${wakeWords.joinToString()} (sensitivity=${settings.wakeWordSensitivity})")

            val rec = Recognizer(model, SAMPLE_RATE, wakeWordsJson)
            speechService = SpeechService(rec, SAMPLE_RATE)
            speechService?.startListening(this)
            Log.d(TAG, "Hotword listening started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotword listening", e)
            debugLog("Failed to start listener: ${e.message}")
            speechService = null
            scheduleAudioRetry()
        }
    }

    private fun scheduleAudioRetry() {
        if (audioRetryCount >= MAX_AUDIO_RETRIES) {
            Log.e(TAG, "Max audio retries ($MAX_AUDIO_RETRIES) exceeded. Giving up.")
            debugLog("Mic unavailable after $MAX_AUDIO_RETRIES retries — giving up")
            audioRetryCount = 0
            showMicUnavailableNotification()
            if (BuildConfig.FIREBASE_ENABLED) {
                FirebaseCrashlytics.getInstance().recordException(
                    RuntimeException("Microphone unavailable after $MAX_AUDIO_RETRIES retries")
                )
            }
            return
        }
        audioRetryCount++
        val delayMs = (2000L * audioRetryCount).coerceAtMost(10000L)
        Log.w(TAG, "Scheduling audio retry #$audioRetryCount in ${delayMs}ms")
        debugLog("Mic unavailable — retry #$audioRetryCount in ${delayMs}ms")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (!isSessionActive) {
                startHotwordListening()
            }
        }
    }

    // Cache for compiled Regex objects to avoid redundant instantiations on every audio frame
    private val wakeWordRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * Parses Vosk keyword spotting confidence for [word] from result text.
     * Vosk grammar mode returns results like "[open claw](0.92)" or plain "open claw".
     * Returns the score (0.0–1.0), or 1.0 if plain text match, or 0.0 if not found.
     */
    private fun parseWakeWordConfidence(text: String, word: String): Float {
        val pattern = wakeWordRegexCache.computeIfAbsent(word) {
            Regex("\\[${Regex.escape(word)}\\]\\(([0-9.]+)\\)", RegexOption.IGNORE_CASE)
        }
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()
            ?: if (text.contains(word, ignoreCase = true)) 1.0f else 0.0f
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (isListeningForCommand || isSessionActive) return
        hypothesis?.let {
            try {
                val json = JSONObject(it)
                val text = json.optString("text", "")

                // Check against configured wake words with confidence threshold
                val wakeWordTargets = settings.getWakeWordTargets()
                var maxConfidence = 0f
                var detectedTarget: SettingsRepository.WakeWordTarget? = null
                wakeWordTargets.forEach { target ->
                    val conf = parseWakeWordConfidence(text, target.phrase)
                    if (conf > maxConfidence) maxConfidence = conf
                    if (conf >= settings.wakeWordSensitivity &&
                        (detectedTarget == null || conf >= parseWakeWordConfidence(text, detectedTarget!!.phrase))
                    ) {
                        detectedTarget = target
                    }
                }

                if (text.isNotEmpty()) {
                    debugLog("heard: \"$text\" conf=${"%.2f".format(maxConfidence)}")
                }

                detectedTarget?.let { target ->
                    Log.e(TAG, "Hotword detected! Text: $text")
                    debugLog("DETECTED: \"$text\" -> ${target.target} conf=${"%.2f".format(maxConfidence)}")
                    onHotwordDetected(target)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Vosk result: $it", e)
            }
            Unit
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk Error: " + exception?.message)
        debugLog("Vosk error: ${exception?.message} — recovering in 3s")
        if (isSessionActive) return
        errorRecoveryJob?.cancel()
        errorRecoveryJob = scope.launch {
            delay(3000)
            if (!isSessionActive) resumeHotwordDetection()
        }
    }

    override fun onTimeout() {
        if (!isListeningForCommand && !isSessionActive) {
            speechService?.startListening(this)
        }
    }

    private fun onHotwordDetected(target: SettingsRepository.WakeWordTarget) {
        if (isListeningForCommand || (isSessionActive && !settings.ttsBargeInEnabled)) return
        isListeningForCommand = true
        startWatchdog()

        Log.d(TAG, "Hotword Detected! Triggering ${target.target} Assistant Overlay or Barge-in...")
        playWakeSound(target.wakeSound)

        // Broadcast to interrupt ongoing TTS (Barge-in)
        val interruptIntent = Intent("com.openclaw.assistant.ACTION_INTERRUPT_TTS")
        interruptIntent.setPackage(packageName)
        sendBroadcast(interruptIntent)
        pendingInterruptLaunch = true

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

            delay(350) // Give an existing session a moment to claim the interrupt

            if (!pendingInterruptLaunch) {
                Log.d(TAG, "Barge-in handled by existing session")
                return@launch
            }
            pendingInterruptLaunch = false
            isSessionActive = true
            val intent = Intent(this@HotwordService, OpenClawAssistantService::class.java).apply {
                action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT
                putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
            }
            try {
                startService(intent)
                Log.e(TAG, "startService ACTION_SHOW_ASSISTANT called")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            } catch (e: SecurityException) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            }
        }
    }

    private fun playWakeSound(sound: String) {
        val tone = when (sound) {
            SettingsRepository.WAKE_SOUND_NONE -> return
            SettingsRepository.WAKE_SOUND_HIGH -> ToneGenerator.TONE_PROP_ACK
            SettingsRepository.WAKE_SOUND_LOW -> ToneGenerator.TONE_PROP_NACK
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            generator.startTone(tone, 140)
            scope.launch {
                delay(180)
                generator.release()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to play wake sound", error)
        }
    }

    private fun resumeHotwordDetection() {
        if (isSessionActive) return
        isListeningForCommand = false
        audioRetryCount = 0
        updateNotification()
        scope.launch {
            delay(500)
            if (!isSessionActive && speechService == null) {
                startHotwordListening()
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            Log.w(TAG, "Watchdog timeout! Auto-resuming hotword detection.")
            isSessionActive = false
            isListeningForCommand = false
            resumeHotwordDetection()
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
