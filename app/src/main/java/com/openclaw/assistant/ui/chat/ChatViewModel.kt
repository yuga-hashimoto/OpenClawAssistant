package com.openclaw.assistant.ui.chat

import android.app.Application
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val partialText: String = "" // For real-time speech transcription
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val speechManager = SpeechRecognizerManager(application)
    
    // Session Management
    val allSessions = chatRepository.allSessions.stateIn(
        viewModelScope, 
        SharingStarted.WhileSubscribed(5000), 
        emptyList()
    )
    
    // Current Session
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    
    // Sync current session with Settings if needed, or just let UI drive it?
    // Let's load the last one if available, or create new.
    
    init {
        // Load messages for current session if set
        viewModelScope.launch {
             // If we have a sessionId in settings, try to use it?
             // Or better, let's start fresh or let user select.
             // For now, let's observe whatever session we have.
             
             // Actually, we want to watch the session ID and update the message flow
        }
    }

    // Messages Flow - mapped from current Session ID
    private val _messagesFlow = _currentSessionId.flatMapLatest { sessionId ->
         if (sessionId != null) {
             chatRepository.getMessages(sessionId).map { entities ->
                 entities.map { entity ->
                     ChatMessage(
                         id = entity.id,
                         text = entity.content,
                         isUser = entity.isUser,
                         timestamp = entity.timestamp
                     )
                 }
             }
         } else {
             flowOf(emptyList())
         }
    }
    
    // We combine _messagesFlow into uiState
    init {
        viewModelScope.launch {
            _messagesFlow.collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        
        // Initial session setup
        viewModelScope.launch {
            // Check if there are sessions. If yes, pick latest.
            val latest = chatRepository.getLatestSession()
            if (latest != null) {
                _currentSessionId.value = latest.id
                settings.sessionId = latest.id
            } else {
                createNewSession()
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val simpleDateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            val newId = chatRepository.createSession("Chat ${simpleDateFormat.format(java.util.Date())}")
            _currentSessionId.value = newId
            settings.sessionId = newId // Sync for API use
        }
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
        settings.sessionId = sessionId
    }

    fun deleteSession(sessionId: String) {
        // Immediate UI update if deleting current session
        val isCurrent = _currentSessionId.value == sessionId
        if (isCurrent) {
            _currentSessionId.value = null
        }

        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            if (isCurrent) {
                // Determine if we should switch to another or create new
                val nextSession = chatRepository.getLatestSession()
                if (nextSession != null) {
                    _currentSessionId.value = nextSession.id
                    settings.sessionId = nextSession.id
                } else {
                    createNewSession()
                }
            }
        }
    }

    // TTS will be set from Activity
    private var tts: TextToSpeech? = null
    private var isTTSReady = false

    /**
     * ActivityからTTSを設定する
     */
    fun setTTS(textToSpeech: TextToSpeech) {
        Log.e(TAG, "setTTS called")
        tts = textToSpeech
        isTTSReady = true
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Ensure we have a session
        val sessionId = _currentSessionId.value ?: return

        _uiState.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            try {
                // Save User Message
                chatRepository.addMessage(sessionId, text, isUser = true)

                val result = apiClient.sendMessage(
                    webhookUrl = settings.webhookUrl,
                    message = text,
                    sessionId = sessionId,
                    authToken = settings.authToken.takeIf { it.isNotBlank() }
                )

                result.fold(
                    onSuccess = { response ->
                        val responseText = response.getResponseText() ?: "No response"
                        // Save AI Message
                        chatRepository.addMessage(sessionId, responseText, isUser = false)
                        
                        _uiState.update { it.copy(isThinking = false) }
                        if (settings.ttsEnabled) {
                            speak(responseText)
                        } else if (lastInputWasVoice && settings.continuousMode) {
                            // If TTS is disabled but we're in continuous mode, restart listening directly
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(500)
                                startListening()
                            }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isThinking = false, error = error.message) }
                        // Ideally log error as system message? 
                        // For now just UI state.
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    private var lastInputWasVoice = false
    private var listeningJob: kotlinx.coroutines.Job? = null

    fun startListening() {
        Log.e(TAG, "startListening() called, isListening=${_uiState.value.isListening}")
        if (_uiState.value.isListening) return

        // Pause Hotword Service to prevent microphone conflict
        sendPauseBroadcast()

        lastInputWasVoice = true // Mark as voice input
        listeningJob?.cancel()

        // Stop TTS if speaking
        tts?.stop()

        listeningJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for TTS resource release before starting mic
            delay(500)

            try {
                while (isActive && !hasActuallySpoken) {
                    Log.e(TAG, "Starting speechManager.startListening(), isListening=true")
                    _uiState.update { it.copy(isListening = true, partialText = "") }

                    speechManager.startListening("ja-JP").collect { result ->
                        Log.e(TAG, "SpeechResult: $result")
                        when (result) {
                            is SpeechResult.PartialResult -> {
                                _uiState.update { it.copy(partialText = result.text) }
                            }
                            is SpeechResult.Result -> {
                                hasActuallySpoken = true
                                _uiState.update { it.copy(isListening = false, partialText = "") }
                                sendMessage(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH
                                
                                if (isTimeout && settings.continuousMode && elapsed < 5000) {
                                    Log.d(TAG, "Speech timeout within 5s window ($elapsed ms), retrying loop...")
                                    // Just fall through to next while iteration
                                    _uiState.update { it.copy(isListening = false) }
                                } else {
                                    // Permanent error or out of time
                                    _uiState.update { it.copy(isListening = false, error = result.message) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                }
                            }
                            else -> {}
                        }
                    }
                    
                    if (!hasActuallySpoken) {
                        delay(300) // Small gap between retries
                    }
                }
            } finally {
                // If the loop finishes (e.g. error or spoken), and we are NOT continuing to speak/think immediately,
                // we might want to resume hotword...
                // HOWEVER: if we successfully spoke, we are now "Thinking" or "Speaking", so we shouldn't resume yet.
                // We only resume if we are truly done (e.g. stopped listening without input).
                
                // But actually, sendMessage() triggers Thinking -> Speaking -> (maybe) startListening again.
                // So we should only resume hotword if we are definitely NOT going to loop back.
                
                if (!lastInputWasVoice) {
                    sendResumeBroadcast()
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
        sendResumeBroadcast()
    }

    private fun speak(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            
            val success = if (isTTSReady && tts != null) {
                speakWithTTS(text)
            } else {
                Log.e(TAG, "TTS not ready, skipping speech")
                false
            }
            
            _uiState.update { it.copy(isSpeaking = false) }

            // If it was a voice conversation and continuous mode is on, continue listening
            if (success && lastInputWasVoice && settings.continuousMode) {
                // Explicit cleanup and wait for TTS to fully release audio focus
                speechManager.destroy()
                kotlinx.coroutines.delay(1000) // Increased from 800ms for more reliable cleanup

                // Restart listening (which will pause hotword again if needed, though it should still be paused)
                startListening()
            } else {
                // Conversation ended
                sendResumeBroadcast()
            }
        }
    }

    private suspend fun speakWithTTS(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
        
        tts?.setOnUtteranceProgressListener(listener)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.e(TAG, "TTS speak result: $result")
        
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed immediately")
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
    
    fun stopSpeaking() {
        lastInputWasVoice = false // Stop loop if manually stopped
        tts?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
        sendResumeBroadcast()
    }

    // REMOVED private fun addMessage because we now flow from DB

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        sendResumeBroadcast()
        // Don't shutdown TTS here - Activity owns it
    }

    private fun sendPauseBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
}
