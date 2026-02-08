package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTS common utilities
 */
object TTSUtils {
    private const val TAG = "TTSUtils"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /**
     * Set up locale and high quality voice
     */
    fun setupVoice(tts: TextToSpeech?) {
        val currentLocale = Locale.getDefault()
        Log.e(TAG, "Current system locale: $currentLocale")

        // Log engine info
        try {
            val engine = tts?.defaultEngine
            Log.e(TAG, "Using TTS Engine: $engine")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get default engine: ${e.message}")
        }

        // Try setting system locale
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage($currentLocale) result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to English (US) if default fails
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // Select high quality voice (prefer local)
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

        // Standard adjustments
        tts?.setSpeechRate(1.2f)
        tts?.setPitch(1.0f)
    }

    /**
     * Dynamically switch TTS language/speed based on text content
     */
    fun applyLanguageForText(tts: TextToSpeech?, text: String) {
        if (text.isEmpty() || tts == null) return

        // Sample first 100 chars for efficiency
        val sample = text.take(100)

        // Check for Kanji, Hiragana, Katakana
        val hasJapanese = sample.any {
            it in '\u3040'..'\u309F' || // Hiragana
            it in '\u30A0'..'\u30FF' || // Katakana
            it in '\u4E00'..'\u9FAF'    // Kanji
        }

        if (hasJapanese) {
            tts.language = Locale.JAPANESE
            tts.setSpeechRate(1.5f)
        } else {
            val hasLatin = sample.any { it in 'a'..'z' || it in 'A'..'Z' }
            if (hasLatin) {
                tts.language = Locale.US
                tts.setSpeechRate(1.2f)
            } else {
                // Fallback to system default
                val defaultLocale = Locale.getDefault()
                tts.language = defaultLocale
                tts.setSpeechRate(1.0f)
            }
        }
    }
}
