package com.openclaw.assistant.speech.embedded

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Manager for embedded TTS engine (Sherpa-ONNX implementation)
 */
class EmbeddedTTSManager(private val context: Context) {
    private const val TAG = "EmbeddedTTSManager"
    
    private var tts: OfflineTts? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    // Directory for storing voice models
    private val modelDir = File(context.filesDir, "tts_models")

    init {
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    /**
     * Check if a specific language model is installed
     */
    fun isModelInstalled(locale: Locale): Boolean {
        val name = getModelFolderName(locale)
        val dir = File(modelDir, name)
        return dir.exists() && File(dir, "model.onnx").exists()
    }

    /**
     * Initialize the TTS engine
     */
    fun initialize(locale: Locale): Boolean {
        if (!isModelInstalled(locale)) return false
        
        try {
            val dir = File(modelDir, getModelFolderName(locale))
            val config = OfflineTtsVitsModelConfig(
                model = File(dir, "model.onnx").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = dir.absolutePath,
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            
            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(vits = config, numThreads = 1, debug = true),
                ruleFsts = "",
                maxNumSentences = 1
            )
            
            tts = OfflineTts(context.assets, ttsConfig)
            Log.e(TAG, "Sherpa-ONNX TTS initialized for $locale")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX: ${e.message}")
            return false
        }
    }

    /**
     * Synthesize and play audio
     */
    fun speak(text: String, locale: Locale) {
        if (tts == null && !initialize(locale)) {
            Log.e(TAG, "TTS not initialized and could not be initialized")
            return
        }

        scope.launch {
            try {
                val audio = tts?.generate(text, 0)
                if (audio != null) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating or playing audio: ${e.message}")
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        audioTrack.play()
        
        // Wait for playback to finish
        val durationMs = (samples.size.toFloat() / sampleRate * 1000).toLong()
        Thread.sleep(durationMs + 100)
        audioTrack.stop()
        audioTrack.release()
    }

    fun stop() {
        // Implementation for stopping current playback
    }

    /**
     * Download voice models
     */
    fun downloadModel(locale: Locale, onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit) {
        val folderName = getModelFolderName(locale)
        val targetDir = File(modelDir, folderName)
        if (!targetDir.exists()) targetDir.mkdirs()

        val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-tts-ja-jp-vits-piper-nanami/resolve/main/" // Placeholder
        val files = listOf("model.onnx", "tokens.txt")

        scope.launch {
            var success = true
            files.forEach { fileName ->
                val ok = downloadFile("$baseUrl$fileName", File(targetDir, fileName))
                if (!ok) success = false
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    private suspend fun downloadFile(url: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            false
        }
    }

    private fun getModelFolderName(locale: Locale): String {
        return when (locale.language) {
            "ja" -> "vits-piper-ja-nanami"
            else -> "vits-piper-en-amy"
        }
    }
}
