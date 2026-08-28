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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

private const val TAG = "RigQwenTTSProvider"

/**
 * Rig Qwen TTS Provider
 *
 * Speaks to the Qwen3-TTS CustomVoice server on the Windows GPU box
 * (`rig_tts_server.py`, port 8799). The rig exposes a custom contract:
 *
 *   POST /tts  {"text": "...", "speaker": "ryan"}  ->  audio/wav (24 kHz mono PCM16)
 *   GET  /health -> {"ok": true}
 *
 * Available speakers on the rig: ryan (default), eric, serena, vivian,
 * dylan, aiden, uncle_fu, ono_anna, sohee.
 */
class RigQwenTTSProvider(private val context: Context) : TTSProvider {

    private val settings = SettingsRepository.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.e(TAG, "Not configured: ${getConfigurationError()}")
            return@withContext false
        }

        try {
            // Request audio from the rig's Qwen3-TTS server
            val audioData = synthesizeSpeech(text)
            if (audioData == null) {
                Log.e(TAG, "Failed to synthesize speech")
                return@withContext false
            }

            // Save to temp file and play (WAV — MediaPlayer handles it natively)
            val tempFile = File.createTempFile("rig_qwen_", ".wav", context.cacheDir)
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
        val baseUrl = settings.rigQwenUrl.trim().trimEnd('/')
        val speaker = settings.rigQwenSpeaker.ifBlank { "ryan" }
        val custom = customVoices().firstOrNull { it.name.equals(speaker, ignoreCase = true) }

        val request: Request
        if (custom != null) {
            // User-defined custom voice -> VoiceDesign endpoint (prompt-based).
            val requestBody = JSONObject().apply {
                put("text", text)
                put("instruct", custom.instruct)
                put("language", "English")
            }.toString()
            request = Request.Builder()
                .url("$baseUrl/voice_design")
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
        } else {
            // Built-in timbre -> CustomVoice endpoint.
            val requestBody = JSONObject().apply {
                put("text", text)
                put("speaker", speaker)
            }.toString()
            request = Request.Builder()
                .url("$baseUrl/tts")
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
        }

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    Log.e(TAG, "API error: ${response.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            null
        }
    }

    /** Parse the stored custom voices JSON into a list. */
    fun customVoices(): List<RigQwenCustomVoice> = parseCustomVoices(settings.rigQwenCustomVoices)

    /** Persist the custom voice list back to settings. */
    fun saveCustomVoices(voices: List<RigQwenCustomVoice>) {
        settings.rigQwenCustomVoices = serializeCustomVoices(voices)
    }

    data class RigQwenCustomVoice(
        val name: String,
        val instruct: String,
    )

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
                    continuation.resume(false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}", e)
            continuation.resume(false)
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

    override fun getType(): String = TTSProviderType.RIG_QWEN

    override fun getDisplayName(): String = "Rig Qwen TTS"

    override fun isConfigured(): Boolean {
        return settings.rigQwenUrl.isNotBlank()
    }

    override fun getConfigurationError(): String? {
        return if (settings.rigQwenUrl.isBlank()) {
            context.getString(R.string.tts_error_rig_qwen_no_url)
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
            File.createTempFile("rig_qwen_", ".wav", context.cacheDir).apply {
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

    companion object {
        /** Speakers available on the rig's Qwen3-TTS CustomVoice server. */
        val RIG_QWEN_SPEAKERS: List<String> = listOf(
            "ryan", "eric", "serena", "vivian", "dylan",
            "aiden", "uncle_fu", "ono_anna", "sohee",
        )

        /** Parse the stored custom voices JSON into a list (no context needed). */
        fun parseCustomVoices(raw: String): List<RigQwenCustomVoice> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    RigQwenCustomVoice(
                        name = o.optString("name", "voice${i + 1}"),
                        instruct = o.optString("instruct", ""),
                    )
                }.filter { it.name.isNotBlank() && it.instruct.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** Serialize a custom voice list to JSON. */
        fun serializeCustomVoices(voices: List<RigQwenCustomVoice>): String {
            val arr = org.json.JSONArray()
            voices.forEach { v ->
                arr.put(org.json.JSONObject().apply {
                    put("name", v.name)
                    put("instruct", v.instruct)
                })
            }
            return arr.toString()
        }
    }
}
