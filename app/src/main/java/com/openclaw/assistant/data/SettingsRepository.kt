package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Secure settings storage
 */
class SettingsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Webhook URL (required)
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) {
            if (value != webhookUrl) {
                prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()
                isVerified = false
            }
        }

    // Auth Token (optional)
    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // Session ID (auto-generated)
    var sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    // Hotword enabled
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()

    // Wake word selection (preset or custom)
    var wakeWordPreset: String
        get() = prefs.getString(KEY_WAKE_WORD_PRESET, WAKE_WORD_OPEN_CLAW) ?: WAKE_WORD_OPEN_CLAW
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_PRESET, value).apply()

    // Custom wake word (when preset is "custom")
    var customWakeWord: String
        get() = prefs.getString(KEY_CUSTOM_WAKE_WORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_WAKE_WORD, value).apply()

    // Get the actual wake words list for Vosk
    fun getWakeWords(): List<String> {
        return when (wakeWordPreset) {
            WAKE_WORD_OPEN_CLAW -> listOf("open claw")
            WAKE_WORD_HEY_ASSISTANT -> listOf("hey assistant")
            WAKE_WORD_JARVIS -> listOf("jarvis")
            WAKE_WORD_COMPUTER -> listOf("computer")
            WAKE_WORD_CUSTOM -> {
                val custom = customWakeWord.trim().lowercase()
                if (custom.isNotEmpty()) listOf(custom) else listOf("open claw")
            }
            else -> listOf("open claw")
        }
    }

    // Get display name for current wake word
    fun getWakeWordDisplayName(): String {
        return when (wakeWordPreset) {
            WAKE_WORD_OPEN_CLAW -> "Open Claw"
            WAKE_WORD_HEY_ASSISTANT -> "Hey Assistant"
            WAKE_WORD_JARVIS -> "Jarvis"
            WAKE_WORD_COMPUTER -> "Computer"
            WAKE_WORD_CUSTOM -> customWakeWord.ifEmpty { "Custom" }
            else -> "Open Claw"
        }
    }

    // TTS enabled
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    // Continuous mode
    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    // Connection Verified
    var isVerified: Boolean
        get() = prefs.getBoolean(KEY_IS_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_VERIFIED, value).apply()

    // Check if configured
    fun isConfigured(): Boolean {
        return webhookUrl.isNotBlank() && isVerified
    }

    // Generate new session ID
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // Reset session
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_IS_VERIFIED = "is_verified"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_CONTINUOUS_MODE = "continuous_mode"

        // Wake word presets
        const val WAKE_WORD_OPEN_CLAW = "open_claw"
        const val WAKE_WORD_HEY_ASSISTANT = "hey_assistant"
        const val WAKE_WORD_JARVIS = "jarvis"
        const val WAKE_WORD_COMPUTER = "computer"
        const val WAKE_WORD_CUSTOM = "custom"
        
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
