package com.openclaw.assistant.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
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
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Voice Interaction Session
 * 実際の音声対話を処理
 */
class OpenClawSession(context: Context) : VoiceInteractionSession(context) {

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
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)

    override fun onCreate() {
        super.onCreate()
        speechManager = SpeechRecognizerManager(context)
        ttsManager = TTSManager(context)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Session shown")
        
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

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            setContent {
                AssistantUI(
                    state = currentState.value,
                    displayText = displayText.value,
                    partialText = partialText.value,
                    errorMessage = errorMessage.value,
                    onClose = { finish() },
                    onRetry = { startListening() }
                )
            }
        }
        return composeView
    }

    private fun startListening() {
        currentState.value = AssistantState.LISTENING
        displayText.value = "Listening..."
        partialText.value = ""
        errorMessage.value = null

        scope.launch {
            speechManager.startListening("ja-JP").collectLatest { result ->
                when (result) {
                    is SpeechResult.Ready -> {
                        Log.d(TAG, "Speech ready")
                    }
                    is SpeechResult.Listening -> {
                        currentState.value = AssistantState.LISTENING
                    }
                    is SpeechResult.RmsChanged -> {
                        // 音量レベル（アニメーション用）
                    }
                    is SpeechResult.PartialResult -> {
                        partialText.value = result.text
                    }
                    is SpeechResult.Processing -> {
                        currentState.value = AssistantState.PROCESSING
                        displayText.value = "Processing..."
                    }
                    is SpeechResult.Result -> {
                        Log.d(TAG, "Speech result: ${result.text}")
                        displayText.value = result.text
                        sendToOpenClaw(result.text)
                    }
                    is SpeechResult.Error -> {
                        Log.e(TAG, "Speech error: ${result.message}")
                        currentState.value = AssistantState.ERROR
                        errorMessage.value = result.message
                    }
                }
            }
        }
    }

    private fun sendToOpenClaw(message: String) {
        currentState.value = AssistantState.THINKING
        displayText.value = "Thinking..."

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
                        displayText.value = responseText
                        speakResponse(responseText)
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
            if (success) {
                // 読み上げ完了後、連続会話のため再度リスニング開始
                delay(500)
                startListening()
            } else {
                currentState.value = AssistantState.ERROR
                errorMessage.value = "Speech error"
            }
        }
    }

    override fun onHide() {
        super.onHide()
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
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
    partialText: String,
    errorMessage: String?,
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
            Box(
                modifier = Modifier
                    .size(80.dp)
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
