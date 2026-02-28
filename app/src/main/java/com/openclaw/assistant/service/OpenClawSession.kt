package com.openclaw.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/**
 * Voice Interaction Session
 * Handles actual voice interaction
 */
class OpenClawSession(context: Context) : VoiceInteractionSession(context), 
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
    }

    private val settings = SettingsRepository.getInstance(context)
    private val apiClient = OpenClawClient(ignoreSslErrors = settings.httpIgnoreSslErrors)
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    
    // Repository
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(context)
    private var currentSessionId: String? = null
    
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()
        
        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
             Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        speechManager = SpeechRecognizerManager(context)
        ttsManager = TTSManager(context)
        val initialized = ttsManager.initializeCurrentProvider()
        Log.e(TAG, "Session TTS: initialized=$initialized ready=${ttsManager.isReady()} error=${ttsManager.getErrorMessage()}")
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator already released", e)
        }
    }

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Session foreground service keeps process alive during conversation (prevents MIUI/HyperOS freeze)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            Log.e(TAG, "Initializing ComposeView with owners")
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }
            
            setContent {
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
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // Ensure any existing SpeechRecognizerManager is cleaned up before creating a new one
        if (this::speechManager.isInitialized) {
            try {
                speechManager.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy existing SpeechRecognizerManager before recreation", e)
            }
        }
        speechManager = SpeechRecognizerManager(context)

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()
        
        // SESSION MANAGEMENT
        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            // Gateway mode: manage session on the gateway side, not in local DB
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!settings.resumeLatestSession) {
                // Start a fresh gateway session with a human-readable label
                val newKey = java.util.UUID.randomUUID().toString()
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val label = String.format(context.getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(newKey)
                scope.launch { nodeRuntime.patchChatSession(newKey, label) }
            }
            // resumeLatestSession ON → keep the current active gateway session as-is
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                        Log.d(TAG, "Resuming latest session: $currentSessionId")
                    } else {
                        currentSessionId = chatRepository.createSession(title = String.format(context.getString(R.string.default_session_title_format), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                        Log.d(TAG, "Created new session: $currentSessionId")
                    }

                    // Store this ID in settings so ChatActivity and API calls use it
                    currentSessionId?.let { settings.sessionId = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }
        
        // Check settings
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_config_required)
            displayText.value = context.getString(R.string.config_required)
            return
        }

        // For Gateway mode, fail fast if the gateway is not healthy
        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!nodeRuntime.chatHealthOk.value) {
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_gateway_not_connected)
                displayText.value = context.getString(R.string.config_required)
                return
            }
        }

        // Start speech recognition
        startListening()
    }
    
    override fun onHide() {
        super.onHide()

        // If a voice session is active (listening, thinking, or speaking),
        // keep resources alive so conversation continues with screen off.
        val state = currentState.value
        val isVoiceActive = state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING ||
                state == AssistantState.PREPARING_SPEECH ||
                state == AssistantState.PROCESSING
        
        if (isVoiceActive) {
            Log.d(TAG, "onHide: voice session active ($state), keeping resources alive")
            // Keep scope, speechManager, ttsManager alive
            // SessionForegroundService is already running
            return
        }

        cleanupSession()
    }

    private fun cleanupSession() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        abandonAudioFocus()
        SessionForegroundService.stop(context)
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
        releaseWakeLock()

        // Resume Hotword
        sendResumeBroadcast()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        
        // Resume Hotword (safety)
        sendResumeBroadcast()

        SessionForegroundService.stop(context)
        ttsManager.shutdown()
        toneGeneratorReleased.set(true)
        toneGenerator.release()
        releaseWakeLock()
    }

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var speakingJob: Job? = null

    private fun startListening() {
        Log.d(TAG, "startListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        listeningJob?.cancel()
        acquireWakeLock()
        
        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for resources - Reduced from 500ms as we now reuse SpeechRecognizer
            delay(50)

            while (isActive && !hasActuallySpoken) {
                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val listenResult = withTimeoutOrNull(30_000L) {
                    speechManager.startListening(settings.speechLanguage.ifEmpty { null }, settings.speechSilenceTimeout).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Processing -> {
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> {
                                audioLevel.value = result.rmsdB
                            }
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                // Ensure state is listening if we get partial results
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received: text='${result.text}'")
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (isTimeout && settings.continuousMode && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                    speechManager.destroy()
                                    delay(1000)
                                } else if (isTimeout) {
                                    // Timeout - close session without error screen
                                    // But only if AI is not currently thinking or speaking
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true // break the loop but don't finish
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        finish() // Close the session
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    currentState.value = AssistantState.ERROR
                                    errorMessage.value = result.message
                                    hasActuallySpoken = true
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (30s). Device may be locked.")
                    // Manual timeout - only close session if not thinking/speaking
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true // break the loop but don't finish
                    } else {
                        finish() // Close the session
                        hasActuallySpoken = true
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private var thinkingSoundJob: Job? = null

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private fun sendToOpenClaw(message: String) {
        Log.d(TAG, "sendToOpenClaw() called, transitioning to THINKING")
        currentState.value = AssistantState.THINKING
        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        scope.launch {
            // Save user message to local DB only for HTTP mode
            if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                currentSessionId?.let { sessionId ->
                    chatRepository.addMessage(sessionId, message, isUser = true)
                }
            }

            if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                sendViaGateway(message)
            } else {
                sendViaHttp(message)
            }
        }
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_gateway_not_connected)
            return
        }

        try {
            val assistantCountBefore = nodeRuntime.chatMessages.value.count { it.role == "assistant" }

            nodeRuntime.sendChat(
                message = message,
                thinking = "low",
                attachments = emptyList()
            )

            // Wait for a new complete assistant response (timeout 60s).
            // chatMessages only adds a message when the full response is committed,
            // so watching it avoids both the pendingRunCount==0 early-fire issue
            // and streaming partial-text races.
            val responseText = withTimeoutOrNull(60_000L) {
                nodeRuntime.chatMessages
                    .first { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }

            if (responseText != null) {
                displayText.value = responseText
                handleResponseReceived(responseText)
            } else {
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_no_response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway error", e)
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = e.message ?: context.getString(R.string.error_network)
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
        val result = apiClient.sendMessage(
            httpUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId
        )

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText()
                if (responseText != null) {
                    displayText.value = responseText
                    handleResponseReceived(responseText)
                } else if (response.error != null) {
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = response.error
                } else {
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "API error", error)
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = error.message ?: context.getString(R.string.error_network)
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {

        // Save AI response to local DB only for HTTP mode
        if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
        }

        if (settings.ttsEnabled) {
            // Thinking sound continues until actual audio playback starts
            speakResponse(responseText)
        } else if (settings.continuousMode) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            stopThinkingSound()
            // TTS disabled & continuous conversation OFF: Return to IDLE
            currentState.value = AssistantState.IDLE
            SessionForegroundService.stop(context)
        }
    }

    private fun interruptAndListen() {
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        currentState.value = AssistantState.IDLE
        startListening()
    }

    private fun speakResponse(text: String) {
        Log.d(TAG, "speakResponse() called, text length=${text.length}")
        // Thinking sound continues until TTSState.Speaking is received
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Preparing -> {
                                Log.d(TAG, "TTS Preparing")
                                // Keep PREPARING_SPEECH state
                            }
                            is TTSState.Speaking -> {
                                Log.d(TAG, "TTS Speaking")
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                            }
                            is TTSState.Done -> {
                                Log.d(TAG, "TTS Done")
                                chunkSuccess = true
                            }
                            is TTSState.Error -> {
                                Log.e(TAG, "TTS Error: ${state.message}")
                                chunkSuccess = false
                            }
                        }
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }

                // Abandon audio focus after TTS completes
                abandonAudioFocus()

                if (success) {
                    // After speech completion, if continuous conversation mode is enabled, start listening again
                    if (settings.continuousMode) {
                        Log.d(TAG, "TTS complete, continuous mode ON. Starting 2nd rally startListening() in 500ms")
                        delay(500)
                        startListening()
                    } else {
                        // If continuous conversation is OFF, end the session
                        currentState.value = AssistantState.IDLE
                        releaseWakeLock()
                        SessionForegroundService.stop(context)
                    }
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_speech_general)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak error", e)
                abandonAudioFocus()
                releaseWakeLock()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_speech_general)
            }
        }
    }



    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

/**
 * Assistant state
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

/**
 * Assistant UI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone icon
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            // Audio level animation
            // RMS dB usually ranges from roughly -2 (silence) to 10+ (loud speech).
            // We map this to a scale factor.
            // Shift -2 to 0: (level + 2)
            // Divide by expected max roughly 12: ((level + 2) / 12)
            // Clamp to 0..1 range just in case.
            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            
            // Scale increases up to 1.5x for loud sounds
            val targetLevelScale = 1f + (normalizedLevel * 0.5f) 
            
            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow), // Slightly faster response
                label = "audio_level_scale"
            )

            // Combine breathing (baseScale) with voice reaction. 
            // When speaking loudly, the voice reaction should dominate.
            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = finalScale
                        scaleY = finalScale
                    }
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            AssistantState.LISTENING -> Color(0xFF4CAF50)
                            AssistantState.SPEAKING, AssistantState.PREPARING_SPEECH -> Color(0xFF2196F3)
                            AssistantState.THINKING, AssistantState.PROCESSING -> Color(0xFFFFC107)
                            AssistantState.ERROR -> Color(0xFFF44336)
                            else -> Color(0xFF9E9E9E)
                        }
                    )
                    .then(
                        if (state == AssistantState.SPEAKING || state == AssistantState.PREPARING_SPEECH) {
                            Modifier.clickable { onInterrupt() }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status text
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.PREPARING_SPEECH -> stringResource(R.string.preparing_speech)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                },
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Recognized text (partial results)
            if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                Text(
                    text = partialText,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }

            // User Query (Final Result)
            if (userQuery.isNotBlank()) {
                Text(
                    text = "$userQuery",
                    fontSize = 16.sp,
                    color = Color.DarkGray, // Slightly different color
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Main text
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Text(
                    text = displayText,
                    fontSize = 18.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
