package com.openclaw.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
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
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/**
 * Voice Interaction Session
 * 実際の音声対話を処理
 */
class OpenClawSession(context: Context) : VoiceInteractionSession(context), 
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
    }

    private val settings = SettingsRepository.getInstance(context)
    private val apiClient = OpenClawClient()
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization

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
        Log.e(TAG, "Session onCreate completed")
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

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
                    onRetry = { startListening() }
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        
        Log.d(TAG, "Session shown with flags: $showFlags")
        
        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()
        
        // 設定チェック
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = "Please configure Webhook URL"
            displayText.value = "Configuration required"
            return
        }

        // 音声認識開始
        startListening()
    }
    
    override fun onHide() {
        super.onHide()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        abandonAudioFocus()
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()

        // Resume Hotword
        sendResumeBroadcast()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        
        // Resume Hotword (safety)
        sendResumeBroadcast()
        
        ttsManager.shutdown()
        toneGenerator.release()
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

    private fun startListening() {
        listeningJob?.cancel()
        
        currentState.value = AssistantState.PROCESSING
        // displayText.value = "" // Don't clear immediately to keep context if needed, or clear? Let's clear for fresh start.
        displayText.value = ""
        userQuery.value = "" // Clear previous user query on new listening start
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for resources
            delay(300)

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
                        android.media.AudioManager.STREAM_VOICE_CALL,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                speechManager.startListening("ja-JP").collectLatest { result ->
                    when (result) {
                        is SpeechResult.Ready -> {
                            currentState.value = AssistantState.LISTENING
                            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP)
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
                            hasActuallySpoken = true
                            // displayText.value = result.text // Don't set displayText here, set userQuery
                            userQuery.value = result.text
                            sendToOpenClaw(result.text)
                        }
                        is SpeechResult.Error -> {
                            val elapsed = System.currentTimeMillis() - startTime
                            val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                                          result.code == SpeechRecognizer.ERROR_NO_MATCH
                            
                            if (isTimeout && settings.continuousMode && elapsed < 5000) {
                                Log.d(TAG, "Speech timeout within 5s window ($elapsed ms), retrying...")
                                // Continue to next loop iteration
                            } else if (result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                speechManager.destroy()
                                delay(1000)
                                // Retrying busy doesn't count against 5s window
                            } else {
                                currentState.value = AssistantState.ERROR
                                errorMessage.value = result.message
                                hasActuallySpoken = true
                            }
                        }
                        else -> {}
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private fun sendToOpenClaw(message: String) {
        currentState.value = AssistantState.THINKING
        displayText.value = ""

        scope.launch {
            val result = apiClient.sendMessage(
                webhookUrl = settings.webhookUrl,
                message = message,
                sessionId = settings.sessionId,
                authToken = settings.authToken.takeIf { it.isNotBlank() }
            )

            result.fold(
                onSuccess = { response ->
                    val responseText = response.getResponseText()
                    if (responseText != null) {
                        displayText.value = responseText
                        if (settings.ttsEnabled) {
                            speakResponse(responseText)
                        } else if (settings.continuousMode) {
                            scope.launch {
                                delay(500)
                                startListening()
                            }
                        }
                    } else if (response.error != null) {
                        currentState.value = AssistantState.ERROR
                        errorMessage.value = response.error
                    } else {
                        currentState.value = AssistantState.ERROR
                        errorMessage.value = "No response"
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "API error", error)
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = error.message ?: "Network error"
                }
            )
        }
    }

    private fun speakResponse(text: String) {
        currentState.value = AssistantState.SPEAKING

        scope.launch {
            val success = ttsManager.speak(text)

            // Abandon audio focus after TTS completes
            abandonAudioFocus()

            if (success) {
                // 読み上げ完了後、連続会話モードが有効なら再度リスニング開始
                if (settings.continuousMode) {
                    delay(500)
                    startListening()
                }
            } else {
                currentState.value = AssistantState.ERROR
                errorMessage.value = "Speech error"
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
}

/**
 * アシスタントの状態
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    SPEAKING,
    ERROR
}

/**
 * アシスタントUI (Compose)
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
    onRetry: () -> Unit
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
            // Closeボタン
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

            // マイクアイコン
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
                            AssistantState.SPEAKING -> Color(0xFF2196F3)
                            AssistantState.THINKING, AssistantState.PROCESSING -> Color(0xFFFFC107)
                            AssistantState.ERROR -> Color(0xFFF44336)
                            else -> Color(0xFF9E9E9E)
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

            // 状態テキスト
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> "Listening..."
                    AssistantState.PROCESSING -> "Processing..."
                    AssistantState.THINKING -> "Thinking..."
                    AssistantState.SPEAKING -> "Speaking..."
                    AssistantState.ERROR -> "Error"
                    else -> "Ready"
                },
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 認識中のテキスト（部分結果）
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

            // メインテキスト
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Text(
                    text = displayText,
                    fontSize = 18.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Errorメッセージ
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
                    Text("Try again")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
