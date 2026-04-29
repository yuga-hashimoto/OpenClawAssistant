package com.openclaw.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.service.AssistantState
import com.openclaw.assistant.service.AssistantUI
import com.openclaw.assistant.service.SessionForegroundService
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.mutableStateOf

/**
 * Transparent overlay Activity used as a fallback when OpenClaw is not set as the default
 * voice assistant. VoiceInteractionSession.showSession() only works when the app is the active
 * VoiceInteractionService, which requires being set as the default assistant. This Activity
 * provides the same UX using a ComponentActivity that is foreground — critical because
 * SpeechRecognizer on Android 14+ requires a foreground Activity context to access the
 * microphone without being the default assistant.
 */
class VoiceOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VoiceOverlayActivity"
        private const val INITIAL_FILLER_DELAY_MS = 750L
        private const val INTERRUPT_LISTEN_DELAY_MS = 350L
    }

    private val settings by lazy { SettingsRepository.getInstance(this) }
    private val apiClient = OpenClawClient()
    private val chatRepository by lazy { ChatRepository.getInstance(this) }
    private var currentSessionId: String? = null

    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI state
    private val currentState = mutableStateOf(AssistantState.IDLE)
    private val displayText = mutableStateOf("")
    private val userQuery = mutableStateOf("")
    private val partialText = mutableStateOf("")
    private val errorMessage = mutableStateOf<String?>(null)
    private val audioLevel = mutableStateOf(0f)

    // Jobs
    private var listeningJob: Job? = null
    private var speakingJob: Job? = null
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    private var thinkingSoundJob: Job? = null
    private var waitPhraseJob: Job? = null

    private val ignoreNextTtsStop = AtomicBoolean(false)

    // Audio
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private val interruptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            if (currentState.value != AssistantState.SPEAKING &&
                currentState.value != AssistantState.PREPARING_SPEECH) return
            Log.d(TAG, "Barge-in interrupt received")
            interruptAndListen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechManager = SpeechRecognizerManager(this) // Activity context = foreground mic access
        ttsManager = TTSManager(this)
        ttsManager.initializeCurrentProvider()

        ContextCompat.registerReceiver(
            this,
            interruptReceiver,
            IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            OpenClawAssistantTheme {
                AssistantUI(
                    state = currentState.value,
                    displayText = displayText.value,
                    userQuery = userQuery.value,
                    partialText = partialText.value,
                    errorMessage = errorMessage.value,
                    audioLevel = audioLevel.value,
                    onClose = { finish() },
                    onRetry = { startListening() },
                    onInterrupt = { interruptAndListen() }
                )
            }
        }

        // Pause hotword so it doesn't conflict with SpeechRecognizer
        sendPauseBroadcast()
        SessionForegroundService.start(this)

        // Session management
        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            val nodeRuntime = (applicationContext as OpenClawApplication).nodeRuntime
            if (!settings.resumeLatestSession) {
                val newKey = java.util.UUID.randomUUID().toString()
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val label = String.format(getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(newKey)
                scope.launch { nodeRuntime.patchChatSession(newKey, label) }
            }
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                    } else {
                        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        currentSessionId = chatRepository.createSession(
                            title = String.format(getString(R.string.default_session_title_format), timeStr)
                        )
                    }
                    currentSessionId?.let { settings.sessionId = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                }
            }
        }

        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = getString(R.string.error_config_required)
            displayText.value = getString(R.string.config_required)
            return
        }

        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            val nodeRuntime = (applicationContext as OpenClawApplication).nodeRuntime
            if (!nodeRuntime.chatHealthOk.value) {
                currentState.value = AssistantState.ERROR
                errorMessage.value = getString(R.string.error_gateway_not_connected)
                displayText.value = getString(R.string.config_required)
                return
            }
        }

        startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        abandonAudioFocus()
        SessionForegroundService.stop(this)
        scope.cancel()
        if (::speechManager.isInitialized) speechManager.destroy()
        if (::ttsManager.isInitialized) {
            ttsManager.stop()
            ttsManager.shutdown()
        }
        releaseWakeLock()
        try { unregisterReceiver(interruptReceiver) } catch (_: Exception) {}
        toneGeneratorReleased.set(true)
        try { toneGenerator.release() } catch (_: Exception) {}
        sendResumeBroadcast()
    }

    private fun sendPauseBroadcast() {
        sendBroadcast(Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD").apply {
            setPackage(packageName)
        })
    }

    private fun sendResumeBroadcast() {
        sendBroadcast(Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD").apply {
            setPackage(packageName)
        })
    }

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (_: RuntimeException) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::OverlayWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun startListening(initialDelayMs: Long = 50L) {
        listeningJob?.cancel()
        acquireWakeLock()
        sendPauseBroadcast()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false

            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    am.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val listenResult = withTimeoutOrNull(30_000L) {
                    speechManager.startListening(
                        settings.speechLanguage.ifEmpty { null },
                        settings.speechSilenceTimeout
                    ).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                currentState.value = AssistantState.LISTENING
                                playTone(ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING)
                                    currentState.value = AssistantState.LISTENING
                            }
                            is SpeechResult.RmsChanged -> audioLevel.value = result.rmsdB
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                if (currentState.value != AssistantState.LISTENING)
                                    currentState.value = AssistantState.LISTENING
                            }
                            is SpeechResult.Result -> {
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                        result.code == SpeechRecognizer.ERROR_NO_MATCH
                                when {
                                    isTimeout && settings.continuousMode && elapsed < 10000 -> {
                                        // retry silently
                                    }
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT -> {
                                        speechManager.destroy()
                                        delay(500)
                                    }
                                    isTimeout -> {
                                        val state = currentState.value
                                        if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                            hasActuallySpoken = true
                                        } else {
                                            playTone(ToneGenerator.TONE_PROP_NACK, 100)
                                            finish()
                                        }
                                    }
                                    else -> {
                                        playTone(ToneGenerator.TONE_PROP_NACK, 100)
                                        currentState.value = AssistantState.ERROR
                                        errorMessage.value = result.message
                                        hasActuallySpoken = true
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        hasActuallySpoken = true
                    } else {
                        finish()
                        hasActuallySpoken = true
                    }
                }

                if (!hasActuallySpoken) delay(300)
            }
        }
    }

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private fun sendToOpenClaw(message: String) {
        currentState.value = AssistantState.THINKING
        playTone(ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        if (settings.fillerPhrasesEnabled) scheduleInitialFillerPhrase()

        scope.launch {
            if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                currentSessionId?.let { chatRepository.addMessage(it, message, isUser = true) }
            }
            if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                sendViaGateway(message)
            } else {
                sendViaHttp(message)
            }
        }
    }

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = scope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (currentState.value == AssistantState.THINKING && isActive) playFillerPhrase()
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = scope.launch {
            delay(5000)
            if (currentState.value == AssistantState.THINKING && isActive) playWaitPhrase()
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val phrase = getString(R.string.filler_ok)
        stopAuxiliarySpeech()
        var job: Job? = null
        job = scope.launch {
            try { ttsManager.speakWithProgress(phrase).collect {} }
            catch (_: CancellationException) {}
            catch (e: Exception) { Log.w(TAG, "Filler phrase failed", e) }
            finally { if (auxiliarySpeechJob === job) auxiliarySpeechJob = null }
        }
        auxiliarySpeechJob = job
    }

    private fun playWaitPhrase() {
        val phrases = listOf(
            getString(R.string.wait_phrase_let_me_think),
            getString(R.string.wait_phrase_one_moment),
            getString(R.string.wait_phrase_checking)
        )
        stopAuxiliarySpeech()
        var job: Job? = null
        job = scope.launch {
            try { ttsManager.speakWithProgress(phrases.random()).collect {} }
            catch (_: CancellationException) {}
            catch (e: Exception) { Log.w(TAG, "Wait phrase failed", e) }
            finally { if (auxiliarySpeechJob === job) auxiliarySpeechJob = null }
        }
        auxiliarySpeechJob = job
    }

    private fun stopAuxiliarySpeech() {
        val wasActive = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (wasActive) ttsManager.stop()
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            cancelInitialFillerPhrase()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = getString(R.string.error_gateway_not_connected)
            return
        }
        try {
            val countBefore = nodeRuntime.chatMessages.value.count { it.role == "assistant" }
            startWaitPhraseTimer()
            nodeRuntime.sendChat(message = message, thinking = "low", attachments = emptyList())
            val responseText = withTimeoutOrNull(60_000L) {
                nodeRuntime.chatMessages
                    .first { msgs -> msgs.count { it.role == "assistant" } > countBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }
            cancelWaitPhraseTimer()
            if (responseText != null) {
                displayText.value = responseText
                handleResponseReceived(responseText)
            } else {
                cancelInitialFillerPhrase(); stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = getString(R.string.error_no_response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway error", e)
            cancelInitialFillerPhrase(); cancelWaitPhraseTimer(); stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = e.message ?: getString(R.string.error_network)
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
        startWaitPhraseTimer()
        val result = apiClient.sendMessage(
            httpUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId
        )
        cancelWaitPhraseTimer()
        result.fold(
            onSuccess = { response ->
                val text = response.getResponseText()
                if (text != null) {
                    displayText.value = text
                    handleResponseReceived(text)
                } else {
                    cancelInitialFillerPhrase(); stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = response.error ?: getString(R.string.error_no_response)
                }
            },
            onFailure = { e ->
                Log.e(TAG, "HTTP error", e)
                cancelInitialFillerPhrase(); stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = e.message ?: getString(R.string.error_network)
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()

        if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            currentSessionId?.let { chatRepository.addMessage(it, responseText, isUser = false) }
        }

        if (settings.ttsEnabled) {
            speakResponse(responseText)
        } else if (settings.continuousMode) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            stopThinkingSound()
            currentState.value = AssistantState.IDLE
            releaseWakeLock()
            SessionForegroundService.stop(this)
        }
    }

    private fun interruptAndListen() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        sendPauseBroadcast()
        ignoreNextTtsStop.set(true)
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        speechManager.destroy()
        abandonAudioFocus()
        currentState.value = AssistantState.PROCESSING
        partialText.value = ""
        errorMessage.value = null
        scope.launch {
            delay(INTERRUPT_LISTEN_DELAY_MS)
            startListening()
        }
    }

    private fun speakResponse(text: String) {
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            ignoreNextTtsStop.set(false)
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Speaking -> {
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                                if (settings.ttsBargeInEnabled) sendResumeBroadcast()
                            }
                            is TTSState.Done -> chunkSuccess = true
                            is TTSState.Error -> {
                                if (!ignoreNextTtsStop.get()) chunkSuccess = false
                            }
                            else -> {}
                        }
                    }
                    if (!chunkSuccess) { success = false; break }
                }

                abandonAudioFocus()
                if (settings.ttsBargeInEnabled && !settings.continuousMode) sendPauseBroadcast()
                if (ignoreNextTtsStop.get()) return@launch

                if (success) {
                    if (settings.continuousMode) {
                        delay(500)
                        startListening()
                    } else {
                        currentState.value = AssistantState.IDLE
                        releaseWakeLock()
                        SessionForegroundService.stop(this@VoiceOverlayActivity)
                    }
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = getString(R.string.error_speech_general)
                }
            } catch (e: CancellationException) {
                if (!ignoreNextTtsStop.get()) throw e
            } catch (e: Exception) {
                if (ignoreNextTtsStop.get()) return@launch
                Log.e(TAG, "TTS error", e)
                abandonAudioFocus()
                releaseWakeLock()
                currentState.value = AssistantState.ERROR
                errorMessage.value = getString(R.string.error_speech_general)
            }
        }
    }
}
