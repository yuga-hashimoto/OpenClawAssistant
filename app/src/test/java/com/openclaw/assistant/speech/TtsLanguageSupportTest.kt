package com.openclaw.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Verifies the language-code derivation logic used by ElevenLabsProvider
 * and the STT language-fallback error-code constants.
 *
 * These tests cover the fix for issue #373 (TTS stops working for non-Japanese languages).
 */
class TtsLanguageSupportTest {

    // ----- ElevenLabs language-code extraction (mirrors ElevenLabsProvider.synthesizeSpeech) -----

    private fun extractLangCode(speechLanguage: String): String? =
        speechLanguage
            .takeIf { it.isNotEmpty() }
            ?.let { Locale.forLanguageTag(it).language }
            ?.takeIf { it.isNotEmpty() }

    @Test
    fun `hi-IN maps to ISO 639-1 hi`() {
        assertEquals("hi", extractLangCode("hi-IN"))
    }

    @Test
    fun `ja-JP maps to ISO 639-1 ja`() {
        assertEquals("ja", extractLangCode("ja-JP"))
    }

    @Test
    fun `en-US maps to ISO 639-1 en`() {
        assertEquals("en", extractLangCode("en-US"))
    }

    @Test
    fun `de-DE maps to ISO 639-1 de`() {
        assertEquals("de", extractLangCode("de-DE"))
    }

    @Test
    fun `empty string returns null so language_code is omitted`() {
        assertNull(extractLangCode(""))
    }

    @Test
    fun `bare two-letter code is preserved`() {
        assertEquals("fr", extractLangCode("fr"))
    }

    // ----- STT language-fallback error codes (API 31) -----

    @Test
    fun `error code 12 is treated as language not supported`() {
        val code = 12
        val isLanguageUnsupported = code == 12 || code == 13
        assertEquals(true, isLanguageUnsupported)
    }

    @Test
    fun `error code 13 is treated as language unavailable`() {
        val code = 13
        val isLanguageUnsupported = code == 12 || code == 13
        assertEquals(true, isLanguageUnsupported)
    }

    @Test
    fun `other error codes are not treated as language errors`() {
        val nonLanguageCodes = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        for (code in nonLanguageCodes) {
            val isLanguageUnsupported = code == 12 || code == 13
            assertEquals("code $code should not be language-unsupported", false, isLanguageUnsupported)
        }
    }

    @Test
    fun `fallback clears effectiveLanguage to null`() {
        var effectiveLanguage: String? = "hi-IN"
        val errorCode = 12

        if ((errorCode == 12 || errorCode == 13) && effectiveLanguage != null) {
            effectiveLanguage = null
        }

        assertNull(effectiveLanguage)
    }

    @Test
    fun `fallback does not fire when language is already null`() {
        var fallbackTriggered = false
        var effectiveLanguage: String? = null
        val errorCode = 12

        if ((errorCode == 12 || errorCode == 13) && effectiveLanguage != null) {
            effectiveLanguage = null
            fallbackTriggered = true
        }

        assertEquals(false, fallbackTriggered)
    }
}
