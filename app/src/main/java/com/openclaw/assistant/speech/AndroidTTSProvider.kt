package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "AndroidTTSProvider"

private val SENTENCE_ENDERS = arrayOf("。", "．", ". ", "! ", "? ", "！", "？")
private val COMMA_ENDERS = arrayOf("。", "，", ", ")

/**
 * Android native TTS provider (wrapper around TextToSpeech)
 */
class AndroidTTSProvider(private val context: Context) : TTSProvider {
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = SettingsRepository.getInstance(context)
    
    init {
        initialize()
    }
    
    private fun initialize() {
        val preferredEngine = settings.ttsEngine
        
        if (preferredEngine.isNotEmpty()) {
            Log.d(TAG, "Initializing with preferred engine: $preferredEngine")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Initialized with preferred engine")
                    onInitSuccess()
                } else {
                    Log.w(TAG, "Preferred engine failed, falling back to default")
                    tryDefaultEngine()
                }
            }, preferredEngine)
        } else {
            tryDefaultEngine()
        }
    }
    
    private fun tryDefaultEngine() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Initialized with default engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Failed to initialize TTS")
            }
        }
    }
    
    private fun onInitSuccess() {
        isInitialized = true
        setupVoice()
        pendingSpeak?.invoke()
        pendingSpeak = null
    }
    
    private fun setupVoice() {
        val tts = this.tts ?: return
        
        val languageTag = settings.speechLanguage
        val locale = if (languageTag.isNotEmpty()) {
            Locale.forLanguageTag(languageTag)
        } else {
            Locale.getDefault()
        }
        
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        
        // Set speed
        tts.setSpeechRate(settings.ttsSpeed)
        tts.setPitch(1.0f)
        
        // Try to select high-quality voice
        try {
            val targetLang = tts.language?.language
            val voices = tts.voices
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }
            
            bestVoice?.let { tts.voice = it }
        } catch (e: Exception) {
            Log.w(TAG, "Error selecting voice: ${e.message}")
        }
    }
    
    override suspend fun speak(text: String): Boolean {
        val maxLen = try {
            TextToSpeech.getMaxSpeechInputLength()
        } catch (e: Exception) {
            3900
        }
        
        val chunks = splitText(text, maxLen * 9 / 10)
        
        for ((index, chunk) in chunks.withIndex()) {
            val success = speakChunk(chunk, index == 0)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed")
                return false
            }
        }
        return true
    }
    
    private suspend fun speakChunk(text: String, isFirst: Boolean): Boolean {
        val timeoutMs = (30_000L + (text.length * 15L)).coerceAtMost(120_000L)
        
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                
                if (isInitialized) {
                    setupVoice()
                    tts?.setOnUtteranceProgressListener(listener)
                    val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val speakResult = tts?.speak(text, queueMode, null, utteranceId)
                    
                    if (speakResult != TextToSpeech.SUCCESS) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                } else {
                    pendingSpeak = {
                        setupVoice()
                        tts?.setOnUtteranceProgressListener(listener)
                        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        tts?.speak(text, queueMode, null, utteranceId)
                    }
                }
                
                continuation.invokeOnCancellation { tts?.stop() }
            }
        }
        
        return result ?: false
    }
    
    override fun stop() {
        tts?.stop()
    }
    
    override fun shutdown() {
        tts?.shutdown()
        isInitialized = false
    }
    
    override fun isAvailable(): Boolean = isInitialized
    
    override fun getType(): String = TTSProviderType.LOCAL
    
    override fun getDisplayName(): String = context.getString(R.string.tts_provider_local_name)
    
    override fun isConfigured(): Boolean = true // Local TTS is always configured
    
    override fun getConfigurationError(): String? = null
    
    override fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()
        
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }
            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_stopped)))
                close()
            }
            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_generic)))
                close()
            }
        }

        if (isInitialized) {
            setupVoice()
            trySend(TTSState.Preparing)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            trySend(TTSState.Error(context.getString(R.string.tts_error_not_initialized)))
            close()
        }
        
        awaitClose { stop() }
    }
    
    // ⚡ Bolt Optimization: Using substring() inside a chunking loop creates implicit string allocations
    // for every chunk. Furthermore, lastIndexOf() runs unbounded backwards across the *entire* text
    // (O(N) operation inside the loop, leading to O(N^2) complexity).
    // Replaced with an index-based (offset/limit) approach using regionMatches to avoid allocations
    // and guarantee O(1) or small bounded search time per chunk, preventing massive latency spikes
    // on long inputs.
    private fun splitText(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var offset = 0

        while (offset < text.length) {
            val remainingLength = text.length - offset
            if (remainingLength <= maxLength) {
                chunks.add(text.substring(offset).trim())
                break
            }

            val limit = offset + maxLength
            val splitPoint = findBestSplitPointBounded(text, offset, limit)

            if (splitPoint > offset) {
                chunks.add(text.substring(offset, splitPoint).trim())
                offset = splitPoint
            } else {
                // No boundary found, force split at maxLength
                chunks.add(text.substring(offset, limit).trim())
                offset = limit
            }

            // Skip leading whitespace for the next chunk
            while (offset < text.length && text[offset].isWhitespace()) {
                offset++
            }
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun findBestSplitPointBounded(text: String, offset: Int, limit: Int): Int {
        val chunkLength = limit - offset

        val paragraphBreak = lastIndexOfBounded(text, "\n\n", offset, limit)
        if (paragraphBreak > offset + chunkLength / 2) return paragraphBreak + 2

        var bestPos = -1
        // Using indices explicitly helps avoid List iterator allocations in this hot path
        for (i in SENTENCE_ENDERS.indices) {
            val ender = SENTENCE_ENDERS[i]
            val pos = lastIndexOfBounded(text, ender, offset, limit)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > offset + chunkLength / 3) return bestPos

        val lineBreak = lastIndexOfBounded(text, "\n", offset, limit)
        if (lineBreak > offset + chunkLength / 3) return lineBreak + 1

        for (i in COMMA_ENDERS.indices) {
            val ender = COMMA_ENDERS[i]
            val pos = lastIndexOfBounded(text, ender, offset, limit)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > offset + chunkLength / 3) return bestPos

        val space = lastIndexOfBounded(text, " ", offset, limit)
        if (space > offset + chunkLength / 3) return space + 1

        return -1
    }

    private fun lastIndexOfBounded(text: String, delimiter: String, offset: Int, limit: Int): Int {
        if (delimiter.isEmpty()) return -1
        val startSearch = limit - delimiter.length
        if (startSearch < offset) return -1
        for (i in startSearch downTo offset) {
            if (text.regionMatches(i, delimiter, 0, delimiter.length)) {
                return i
            }
        }
        return -1
    }
}
