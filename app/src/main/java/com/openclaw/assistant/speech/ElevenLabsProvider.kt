package com.openclaw.assistant.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

private const val TAG = "ElevenLabsProvider"
private const val API_BASE_URL = "https://api.elevenlabs.io/v1"

/**
 * ElevenLabs TTS Provider
 */
class ElevenLabsProvider(private val context: Context) : TTSProvider {
    
    private val settings = SettingsRepository.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private var mediaPlayer: MediaPlayer? = null
    
    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.e(TAG, "Not configured: ${getConfigurationError()}")
            return@withContext false
        }
        
        try {
            // Request audio from ElevenLabs API
            val audioData = synthesizeSpeech(text)
            if (audioData == null) {
                Log.e(TAG, "Failed to synthesize speech")
                return@withContext false
            }
            
            // Save to temp file and play
            val tempFile = File.createTempFile("elevenlabs_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioData) }

            try {
                playAudioFile(tempFile)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking: ${e.message}", e)
            false
        }
    }
    
    private suspend fun synthesizeSpeech(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = settings.elevenLabsApiKey
        val voiceId = settings.elevenLabsVoiceId
        val modelId = settings.elevenLabsModel
        
        val url = "$API_BASE_URL/text-to-speech/$voiceId"
        
        // ElevenLabs API requires speed to be between 0.7 and 1.2
        // Use String.format to avoid floating point precision issues
        val speed = String.format(Locale.US, "%.2f", settings.elevenLabsSpeed.coerceIn(0.7f, 1.2f)).toDouble()
        
        val requestBody = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            val langCode = settings.speechLanguage
                .takeIf { it.isNotEmpty() }
                ?.let { Locale.forLanguageTag(it).language }
                ?.takeIf { it.isNotEmpty() }
            if (langCode != null) {
                put("language_code", langCode)
            }
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.3)
                put("speed", speed)
            })
        }.toString()
        
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "API error: ${response.code}, $errorBody")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            null
        }
    }
    
    private suspend fun playAudioFile(file: File, onStarted: (() -> Unit)? = null): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    start()
                    onStarted?.invoke()
                }
                setOnCompletionListener {
                    continuation.resume(true)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}", e)
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        
        continuation.invokeOnCancellation {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }
    
    override fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping: ${e.message}")
        }
    }
    
    override fun shutdown() {
        stop()
    }
    
    override fun isAvailable(): Boolean = true // Always available if configured
    
    override fun getType(): String = TTSProviderType.ELEVENLABS
    
    override fun getDisplayName(): String = "ElevenLabs"
    
    override fun isConfigured(): Boolean {
        return settings.elevenLabsApiKey.isNotBlank()
    }
    
    override fun getConfigurationError(): String? {
        return if (settings.elevenLabsApiKey.isBlank()) {
            context.getString(R.string.tts_error_elevenlabs_no_apikey)
        } else null
    }
    
    override fun speakWithProgress(text: String): Flow<TTSState> = channelFlow {
        send(TTSState.Preparing)
        
        if (!isConfigured()) {
            send(TTSState.Error(getConfigurationError() ?: context.getString(R.string.tts_error_not_initialized)))
            return@channelFlow
        }
        
        // Synthesize speech (API call)
        val audioData = try {
            synthesizeSpeech(text)
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            null
        }
        
        if (audioData == null) {
            send(TTSState.Error("Failed to synthesize speech"))
            return@channelFlow
        }
        
        // Save to temp file
        val tempFile = try {
            File.createTempFile("elevenlabs_", ".mp3", context.cacheDir).apply {
                FileOutputStream(this).use { it.write(audioData) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio", e)
            send(TTSState.Error("Failed to save audio"))
            return@channelFlow
        }
        
        // Play audio - Speaking state emitted only when playback actually starts
        val success = playAudioFile(tempFile) {
            trySend(TTSState.Speaking)
        }
        
        // Cleanup
        try {
            tempFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp file", e)
        }
        
        if (success) {
            send(TTSState.Done)
        } else {
            send(TTSState.Error("Failed to play audio"))
        }
    }
    
    /**
     * Get available voices from ElevenLabs API
     */
    suspend fun getVoices(): List<ElevenLabsVoice> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        
        val apiKey = settings.elevenLabsApiKey
        val request = Request.Builder()
            .url("$API_BASE_URL/voices")
            .header("xi-api-key", apiKey)
            .get()
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    parseVoices(body)
                } else {
                    Log.e(TAG, "Failed to get voices: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting voices: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun parseVoices(json: String?): List<ElevenLabsVoice> {
        if (json == null) return emptyList()
        
        return try {
            val root = JSONObject(json)
            val voicesArray = root.getJSONArray("voices")
            
            List(voicesArray.length()) { i ->
                val voice = voicesArray.getJSONObject(i)
                ElevenLabsVoice(
                    voiceId = voice.getString("voice_id"),
                    name = voice.getString("name"),
                    category = voice.optString("category", "premade"),
                    previewUrl = voice.optString("preview_url", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing voices: ${e.message}")
            emptyList()
        }
    }
    
    data class ElevenLabsVoice(
        val voiceId: String,
        val name: String,
        val category: String,
        val previewUrl: String
    )
}
