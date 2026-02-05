package com.openclaw.assistant.speech.embedded

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manager for embedded TTS engine (Sherpa-ONNX implementation)
 * 
 * Supports: English, Chinese, and Japanese.
 */
class EmbeddedTTSManager(private val context: Context) {
    companion object {
        private const val TAG = "EmbeddedTTSManager"
        
        // Model download URLs
        private const val GITHUB_MODEL_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        private const val HF_MODEL_BASE_URL = "https://huggingface.co/csukuangfj/"
        
        // Available models
        private val MODELS = mapOf(
            "ja" to ModelInfo(
                baseUrl = "${HF_MODEL_BASE_URL}sherpa-onnx-tts-ja-jp-vits-piper-nanami/resolve/main/",
                folderName = "vits-piper-ja-jp-nanami",
                files = listOf("ja_JP-nanami-medium.onnx", "tokens.txt"),
                isArchive = false
            ),
            "zh" to ModelInfo(
                baseUrl = GITHUB_MODEL_BASE_URL,
                folderName = "vits-melo-tts-zh_en",
                files = listOf("model.onnx", "tokens.txt", "lexicon.txt"),
                archiveName = "vits-melo-tts-zh_en.tar.bz2",
                isArchive = true
            ),
            "en" to ModelInfo(
                baseUrl = GITHUB_MODEL_BASE_URL,
                folderName = "vits-piper-en_US-glados",
                files = listOf("en_US-glados.onnx", "tokens.txt"),
                archiveName = "vits-piper-en_US-glados.tar.bz2",
                isArchive = true
            )
        )
    }
    
    data class ModelInfo(
        val baseUrl: String,
        val folderName: String,
        val files: List<String>,
        val archiveName: String? = null,
        val isArchive: Boolean = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Directory for storing voice models
    private val modelDir = File(context.filesDir, "tts_models")

    init {
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    /**
     * Check if a specific language model is installed
     */
    fun isModelInstalled(locale: Locale): Boolean {
        val modelInfo = getModelInfo(locale) ?: return false
        val dir = File(modelDir, modelInfo.folderName)
        if (!dir.exists()) return false
        
        // Check if all required files exist
        return modelInfo.files.all { File(dir, it).exists() }
    }

    /**
     * Get model info for a locale
     */
    private fun getModelInfo(locale: Locale): ModelInfo? {
        return when (locale.language) {
            "ja" -> MODELS["ja"]
            "zh" -> MODELS["zh"]
            "en" -> MODELS["en"]
            else -> null
        }
    }
    
    /**
     * Check if embedded TTS is available for this locale
     */
    fun isLocaleSupported(locale: Locale): Boolean {
        return getModelInfo(locale) != null
    }
    
    /**
     * Get list of supported language display names
     */
    fun getSupportedLanguages(): List<String> {
        return listOf("日本語 (Japanese)", "English", "中文 (Chinese)")
    }

    /**
     * Download voice models
     */
    fun downloadModel(locale: Locale, onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit) {
        val modelInfo = getModelInfo(locale)
        
        if (modelInfo == null) {
            Log.e(TAG, "No model available for locale: $locale")
            scope.launch(Dispatchers.Main) { onComplete(false) }
            return
        }
        
        val targetDir = File(modelDir, modelInfo.folderName)
        if (!targetDir.exists()) targetDir.mkdirs()

        scope.launch {
            try {
                if (modelInfo.isArchive && modelInfo.archiveName != null) {
                    // Download and extract archive
                    val archiveFile = File(modelDir, modelInfo.archiveName)
                    val downloadSuccess = downloadFile(modelInfo.baseUrl + modelInfo.archiveName, archiveFile) { progress ->
                        scope.launch(Dispatchers.Main) { onProgress(progress) }
                    }
                    
                    if (!downloadSuccess) {
                        withContext(Dispatchers.Main) { onComplete(false) }
                        return@launch
                    }
                    
                    val extractSuccess = extractTarBz2(archiveFile, modelDir)
                    archiveFile.delete()
                    withContext(Dispatchers.Main) { onComplete(extractSuccess && isModelInstalled(locale)) }
                } else {
                    // Download individual files
                    var overallSuccess = true
                    modelInfo.files.forEach { fileName ->
                        val outputFile = File(targetDir, fileName)
                        val success = downloadFile(modelInfo.baseUrl + fileName, outputFile) { _ -> }
                        if (!success) overallSuccess = false
                    }
                    withContext(Dispatchers.Main) { onComplete(overallSuccess && isModelInstalled(locale)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    private suspend fun downloadFile(
        url: String, 
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code} for $url")
                    return@withContext false
                }
                
                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()
                var bytesRead = 0L
                
                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                onProgress(bytesRead.toFloat() / contentLength)
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }
    
    private fun extractTarBz2(archiveFile: File, outputDir: File): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("tar", "-xjf", archiveFile.absolutePath, "-C", outputDir.absolutePath)
            )
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }
    
    fun speak(text: String, locale: Locale) {
        // Playback logic handled by Sherpa-ONNX in next update
    }
}
