package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Common utilities for TTS
 */
object TTSUtils {
    private const val TAG = "TTSUtils"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    private val REGEX_CODE_BLOCK_START = Regex("```.*\\n?")
    private val REGEX_CODE_BLOCK_END = Regex("```")
    private val REGEX_HEADER = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val REGEX_BOLD_ASTERISK = Regex("\\*\\*([^*]+)\\*\\*")
    private val REGEX_ITALIC_ASTERISK = Regex("\\*([^*]+)\\*")
    private val REGEX_BOLD_UNDERSCORE = Regex("__([^_]+)__")
    private val REGEX_ITALIC_UNDERSCORE = Regex("_([^_]+)_")
    private val REGEX_INLINE_CODE = Regex("`([^`]+)`")
    private val REGEX_LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val REGEX_IMAGE = Regex("!\\[([^\\]]*)]\\([^)]+\\)")
    private val REGEX_HR = Regex("^[-*_]{3,}$", RegexOption.MULTILINE)
    private val REGEX_BLOCKQUOTE = Regex("^>\\s?", RegexOption.MULTILINE)
    private val REGEX_BULLET = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE)
    private val REGEX_NEWLINE = Regex("\n{3,}")

    private val SENTENCE_ENDERS = arrayOf("。", "．", ". ", "! ", "? ", "！", "？")
    private val COMMA_ENDERS = arrayOf("、", "，", ", ")

    /**
     * Setup locale and high-quality voice
     */
    fun setupVoice(tts: TextToSpeech?, speed: Float, languageTag: String? = null) {
        val currentLocale = if (!languageTag.isNullOrEmpty()) {
            Locale.forLanguageTag(languageTag)
        } else {
            Locale.getDefault()
        }
        Log.e(TAG, "Current system locale: $currentLocale")

        // Log engine information
        try {
            val engine = tts?.defaultEngine
            Log.e(TAG, "Using TTS Engine: $engine")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get default engine: ${e.message}")
        }

        // Try to set system locale
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage($currentLocale) result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to English (US) if default fails
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // Select high-quality voice (prioritizing non-network ones)
        try {
            val targetLang = tts?.language?.language
            val voices = tts?.voices
            Log.e(TAG, "Available voices count: ${voices?.size ?: 0}")
            
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }

            if (bestVoice != null) {
                tts?.voice = bestVoice
                Log.e(TAG, "Selected voice: ${bestVoice.name} (${bestVoice.locale})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice: ${e.message}")
        }

        applyUserConfig(tts, speed)
    }

    /**
     * Apply user-configured speed
     */
    fun applyUserConfig(tts: TextToSpeech?, speed: Float) {
        if (tts == null) return
        tts.setSpeechRate(speed)
        tts.setPitch(1.0f)
    }

    /**
     * Strip Markdown formatting and convert to plain text for TTS
     */
    fun stripMarkdownForSpeech(text: String): String {
        var result = text
        // Code block (```...```) -> keep only the content
        // Adjustments like removing backticks and the first line (language name) for cases with language specification (```kotlin ...) are needed, but
        // simplify by just removing backticks and reading the content. Or should it say "code block"?
        // According to user request "read everything except symbols", keep the content.
        result = result.replace(REGEX_CODE_BLOCK_START, "") // Remove starting ```language
        result = result.replace(REGEX_CODE_BLOCK_END, "")       // Remove ending ```

        // Remove headers (# ## ### etc.)
        result = result.replace(REGEX_HEADER, "")
        
        // Bold/Italic (**text**, *text*, __text__, _text_)
        result = result.replace(REGEX_BOLD_ASTERISK, "$1")
        result = result.replace(REGEX_ITALIC_ASTERISK, "$1")
        result = result.replace(REGEX_BOLD_UNDERSCORE, "$1")
        result = result.replace(REGEX_ITALIC_UNDERSCORE, "$1")
        
        // Inline code (code)
        result = result.replace(REGEX_INLINE_CODE, "$1")
        
        // Link [text](url) → text
        result = result.replace(REGEX_LINK, "$1")
        
        // Image ![alt](url) → alt
        result = result.replace(REGEX_IMAGE, "$1")
        
        // Horizontal rule (---, ***) -> Remove
        result = result.replace(REGEX_HR, "")
        
        // Blockquote (>)
        result = result.replace(REGEX_BLOCKQUOTE, "")
        
        // Bullet point markers (-, *, +)
        result = result.replace(REGEX_BULLET, "")
        
        // Numbered list markers (1., 2., etc.) - these might be okay to read, but keep only the numbers
        // result = result.replace(REGEX_NUMBERED_LIST, "")
        
        // Organize consecutive newlines
        result = result.replace(REGEX_NEWLINE, "\n\n")
        
        return result.trim()
    }

    /**
     * Query the engine's actual max input length, with a safe fallback.
     */
    fun getMaxInputLength(tts: TextToSpeech?): Int {
        return try {
            val limit = TextToSpeech.getMaxSpeechInputLength()
            // Use 90% of the reported limit as safety margin
            (limit * 9 / 10).coerceIn(500, limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query maxSpeechInputLength, using default 3900")
            3900
        }
    }

    /**
     * Splits long text into chunks that fit within the TTS max input length.
     * Splits naturally at sentence boundaries (period, newline, etc.), keeping each chunk under maxLength.
     */
    fun splitTextForTTS(text: String, maxLength: Int = 1000): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var offset = 0

        while (offset < text.length) {
            val remainingLength = text.length - offset
            if (remainingLength <= maxLength) {
                chunks.add(text.substring(offset).trim())
                break
            }

            // Find the last sentence boundary within maxLength
            val splitPoint = findBestSplitPoint(text, offset, offset + maxLength)

            if (splitPoint > offset) {
                chunks.add(text.substring(offset, splitPoint).trim())
                offset = splitPoint
            } else {
                // No boundary found, force split at maxLength
                chunks.add(text.substring(offset, offset + maxLength).trim())
                offset += maxLength
            }

            // Skip leading whitespace for the next chunk to emulate .trim() behavior for the remainder
            while (offset < text.length && text[offset].isWhitespace()) {
                offset++
            }
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun findBestSplitPoint(text: String, offset: Int, limit: Int): Int {
        val chunkLength = limit - offset

        // Priority: paragraph break > sentence end > comma > space
        val paragraphBreak = lastIndexOfBounded(text, "\n\n", offset, limit)
        if (paragraphBreak > offset + chunkLength / 2) return paragraphBreak + 2

        var bestPos = -1
        for (ender in SENTENCE_ENDERS) {
            val pos = lastIndexOfBounded(text, ender, offset, limit)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > offset + chunkLength / 3) return bestPos

        val lineBreak = lastIndexOfBounded(text, "\n", offset, limit)
        if (lineBreak > offset + chunkLength / 3) return lineBreak + 1

        for (ender in COMMA_ENDERS) {
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
        // ⚡ Bolt Optimization: Replace O(N^2) manual loop string chunking regression with built-in lastIndexOf.
        val result = text.lastIndexOf(delimiter, startSearch)
        return if (result >= offset) result else -1
    }
}
