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
    data class WakeWordTarget(
        val phrase: String,
        val target: String,
        val wakeSound: String
    )

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

    // HTTP Server URL (required)
    var httpUrl: String
        get() = prefs.getString(KEY_HTTP_URL, "") ?: ""
        set(value) {
            if (value != httpUrl) {
                prefs.edit().putString(KEY_HTTP_URL, value).apply()
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

    var openClawWakeWord: String
        get() {
            ensureDualWakeWordsMigrated()
            return normalizeWakeWord(
                prefs.getString(KEY_OPENCLAW_WAKE_WORD, DEFAULT_OPENCLAW_WAKE_WORD) ?: DEFAULT_OPENCLAW_WAKE_WORD,
                DEFAULT_OPENCLAW_WAKE_WORD
            )
        }
        set(value) {
            ensureDualWakeWordsMigrated()
            prefs.edit().putString(KEY_OPENCLAW_WAKE_WORD, normalizeWakeWord(value, DEFAULT_OPENCLAW_WAKE_WORD)).apply()
        }

    var hermesWakeWord: String
        get() {
            ensureDualWakeWordsMigrated()
            return normalizeWakeWord(
                prefs.getString(KEY_HERMES_WAKE_WORD, DEFAULT_HERMES_WAKE_WORD) ?: DEFAULT_HERMES_WAKE_WORD,
                DEFAULT_HERMES_WAKE_WORD
            )
        }
        set(value) {
            ensureDualWakeWordsMigrated()
            prefs.edit().putString(KEY_HERMES_WAKE_WORD, normalizeWakeWord(value, DEFAULT_HERMES_WAKE_WORD)).apply()
        }

    var openClawWakeSound: String
        get() {
            ensureDualWakeWordsMigrated()
            return prefs.getString(KEY_OPENCLAW_WAKE_SOUND, WAKE_SOUND_STANDARD) ?: WAKE_SOUND_STANDARD
        }
        set(value) = prefs.edit().putString(KEY_OPENCLAW_WAKE_SOUND, normalizeWakeSound(value, WAKE_SOUND_STANDARD)).apply()

    var hermesWakeSound: String
        get() {
            ensureDualWakeWordsMigrated()
            return prefs.getString(KEY_HERMES_WAKE_SOUND, WAKE_SOUND_HIGH) ?: WAKE_SOUND_HIGH
        }
        set(value) = prefs.edit().putString(KEY_HERMES_WAKE_SOUND, normalizeWakeSound(value, WAKE_SOUND_HIGH)).apply()

    // Wake word detection sensitivity threshold (0.0 = easiest to trigger, 1.0 = hardest)
    var wakeWordSensitivity: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value.coerceIn(0.0f, 1.0f)).apply()

    // Get the actual wake words list for Vosk
    fun getWakeWords(): List<String> {
        return getWakeWordTargets().map { it.phrase }.distinct()
    }

    fun getWakeWordTargets(): List<WakeWordTarget> {
        ensureDualWakeWordsMigrated()
        return listOf(
            WakeWordTarget(openClawWakeWord, VOICE_TARGET_OPENCLAW, openClawWakeSound),
            WakeWordTarget(hermesWakeWord, VOICE_TARGET_HERMES, hermesWakeSound)
        ).filter { it.phrase.isNotBlank() }
    }

    private fun getLegacyWakeWords(): List<String> {
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
        return getWakeWords().joinToString(" / ")
    }

    private fun normalizeWakeWord(value: String, fallback: String): String {
        return value.trim().lowercase().replace(Regex("\\s+"), " ").ifBlank { fallback }
    }

    private fun normalizeWakeSound(value: String, fallback: String): String {
        return when (value) {
            WAKE_SOUND_NONE, WAKE_SOUND_STANDARD, WAKE_SOUND_HIGH, WAKE_SOUND_LOW -> value
            else -> fallback
        }
    }

    private fun ensureDualWakeWordsMigrated() {
        if (prefs.getBoolean(KEY_DUAL_WAKE_WORDS_MIGRATED, false)) return
        val editor = prefs.edit()
        if (!prefs.contains(KEY_OPENCLAW_WAKE_WORD)) {
            editor.putString(KEY_OPENCLAW_WAKE_WORD, getLegacyWakeWords().firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENCLAW_WAKE_WORD)
        }
        if (!prefs.contains(KEY_HERMES_WAKE_WORD)) {
            editor.putString(KEY_HERMES_WAKE_WORD, DEFAULT_HERMES_WAKE_WORD)
        }
        if (!prefs.contains(KEY_OPENCLAW_WAKE_SOUND)) {
            editor.putString(KEY_OPENCLAW_WAKE_SOUND, WAKE_SOUND_STANDARD)
        }
        if (!prefs.contains(KEY_HERMES_WAKE_SOUND)) {
            editor.putString(KEY_HERMES_WAKE_SOUND, WAKE_SOUND_HIGH)
        }
        editor.putBoolean(KEY_DUAL_WAKE_WORDS_MIGRATED, true).apply()
    }

    // TTS enabled
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true) // Default true as per user request
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    // Continuous mode
    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    // Resume Latest Session
    var resumeLatestSession: Boolean
        get() = prefs.getBoolean(KEY_RESUME_LATEST_SESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_LATEST_SESSION, value).apply()

    // TTS Speed
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.2f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    // TTS Engine
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()
    
    // TTS Type (local, elevenlabs, openai, voicevox)
    var ttsType: String
        get() = prefs.getString(KEY_TTS_TYPE, TTS_TYPE_LOCAL) ?: TTS_TYPE_LOCAL
        set(value) = prefs.edit().putString(KEY_TTS_TYPE, value).apply()
    
    // ElevenLabs settings
    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()
    
    var elevenLabsVoiceId: String
        get() = prefs.getString(KEY_ELEVENLABS_VOICE_ID, DEFAULT_ELEVENLABS_VOICE_ID) ?: DEFAULT_ELEVENLABS_VOICE_ID
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_VOICE_ID, value).apply()
    
    var elevenLabsModel: String
        get() = prefs.getString(KEY_ELEVENLABS_MODEL, DEFAULT_ELEVENLABS_MODEL) ?: DEFAULT_ELEVENLABS_MODEL
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_MODEL, value).apply()
    
    var elevenLabsSpeed: Float
        get() = prefs.getFloat(KEY_ELEVENLABS_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ELEVENLABS_SPEED, value.coerceIn(0.7f, 1.2f)).apply()
    
    // OpenAI settings
    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()
    
    var openAiVoice: String
        get() = prefs.getString(KEY_OPENAI_VOICE, DEFAULT_OPENAI_VOICE) ?: DEFAULT_OPENAI_VOICE
        set(value) = prefs.edit().putString(KEY_OPENAI_VOICE, value).apply()
    
    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    // Rig Qwen TTS settings
    var rigQwenUrl: String
        get() = prefs.getString(KEY_RIG_QWEN_URL, DEFAULT_RIG_QWEN_URL) ?: DEFAULT_RIG_QWEN_URL
        set(value) = prefs.edit().putString(KEY_RIG_QWEN_URL, value).apply()

    var rigQwenSpeaker: String
        get() = prefs.getString(KEY_RIG_QWEN_SPEAKER, DEFAULT_RIG_QWEN_SPEAKER) ?: DEFAULT_RIG_QWEN_SPEAKER
        set(value) = prefs.edit().putString(KEY_RIG_QWEN_SPEAKER, value).apply()

    /**
     * User-defined custom voices for the rig's VoiceDesign endpoint.
     * Stored as a JSON array of {"name": "...", "instruct": "..."}.
     * Empty string = no custom voices defined.
     */
    var rigQwenCustomVoices: String
        get() = prefs.getString(KEY_RIG_QWEN_CUSTOM_VOICES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RIG_QWEN_CUSTOM_VOICES, value).apply()
    
    // VOICEVOX settings
    var voiceVoxSpeakerId: Int
        get() = prefs.getInt(KEY_VOICEVOX_SPEAKER_ID, DEFAULT_VOICEVOX_SPEAKER_ID)
        set(value) = prefs.edit().putInt(KEY_VOICEVOX_SPEAKER_ID, value).apply()
    
    var voiceVoxStyleId: Int
        get() = prefs.getInt(KEY_VOICEVOX_STYLE_ID, DEFAULT_VOICEVOX_STYLE_ID)
        set(value) = prefs.edit().putInt(KEY_VOICEVOX_STYLE_ID, value).apply()
    
    var voiceVoxTermsAccepted: Boolean
        get() = prefs.getBoolean(KEY_VOICEVOX_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICEVOX_TERMS_ACCEPTED, value).apply()

    // Gateway Port for WebSocket agent list connection (default 18789)

    var gatewayPort: Int
        get() = prefs.getInt(KEY_GATEWAY_PORT, 18789)
        set(value) = prefs.edit().putInt(KEY_GATEWAY_PORT, value).apply()

    // Speech recognition silence timeout in ms (default 5000ms)
    var speechSilenceTimeout: Long
        get() = prefs.getLong(KEY_SPEECH_SILENCE_TIMEOUT, 5000L)
        set(value) = prefs.edit().putLong(KEY_SPEECH_SILENCE_TIMEOUT, value).apply()

    // Speech recognition language (BCP-47 tag, empty = system default)
    var speechLanguage: String
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEECH_LANGUAGE, value).apply()

    // App UI language (BCP-47 tag, empty = system default)
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    // Thinking sound enabled
    var thinkingSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_THINKING_SOUND_ENABLED, value).apply()

    // Filler phrases enabled (相槌 & 待ちフレーズ)
    var fillerPhrasesEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILLER_PHRASES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FILLER_PHRASES_ENABLED, value).apply()

    // Barge-in during TTS (WakeWord interruption while speaking)
    var ttsBargeInEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_BARGE_IN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_BARGE_IN_ENABLED, value).apply()

    // Wake word debug logging (shows verbose status on home screen)
    var wakeWordDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_DEBUG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_DEBUG_ENABLED, value).apply()

    // Trigger voice chat from media button (Bluetooth headset button, etc.)
    var mediaButtonEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_BUTTON_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MEDIA_BUTTON_ENABLED, value).apply()

    // Latest app update version dismissed from the startup notice.
    var dismissedUpdateVersion: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, value).apply()

    // Connection Verified
    var isVerified: Boolean
        get() = prefs.getBoolean(KEY_IS_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_VERIFIED, value).apply()

    // Has completed initial setup guide
    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_SETUP, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_COMPLETED_SETUP, value).apply()

    // Default Agent ID
    var defaultAgentId: String
        get() = prefs.getString(KEY_DEFAULT_AGENT_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_DEFAULT_AGENT_ID, value).apply()

    // Use NodeRuntime-backed chat pipeline (chat.send / chat history from gateway)
    var useNodeChat: Boolean
        get() = prefs.getBoolean(KEY_USE_NODE_CHAT, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_NODE_CHAT, value).apply()

    // Connection Type (Gateway vs Legacy)
    var connectionType: String
        get() = prefs.getString(KEY_CONNECTION_TYPE, CONNECTION_TYPE_GATEWAY) ?: CONNECTION_TYPE_GATEWAY
        set(value) = prefs.edit().putString(KEY_CONNECTION_TYPE, value).apply()

    // Connection type used by the voice session (wakeword / long-press home)
    // Defaults to the same as connectionType so existing setups behave identically
    var wakewordConnectionType: String
        get() = prefs.getString(KEY_WAKEWORD_CONNECTION_TYPE, connectionType) ?: connectionType
        set(value) = prefs.edit().putString(KEY_WAKEWORD_CONNECTION_TYPE, value).apply()

    /**
     * Get the chat completions URL.
     * Supports both base URL (http://server) and full path (http://server/v1/chat/completions).
     */
    fun getChatCompletionsUrl(): String {
        val url = httpUrl.trim().trimEnd('/')
        if (url.isBlank()) return ""
        return if (url.contains("/v1/")) url
        else "$url/v1/chat/completions"
    }

    /**
     * Get the base URL (without path) for WebSocket connections.
     * Extracts base from full path URLs, or returns as-is for base URLs.
     */
    fun getBaseUrl(): String {
        val url = httpUrl.trimEnd('/')
        val idx = url.indexOf("/v1/")
        return if (idx > 0) url.substring(0, idx) else url
    }

    // Check if configured
    fun isConfigured(): Boolean {
        return when (connectionType) {
            CONNECTION_TYPE_GATEWAY -> true
            else -> httpUrl.isNotBlank() && isVerified
        }
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
        private const val KEY_HTTP_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_OPENCLAW_WAKE_WORD = "openclaw_wake_word"
        private const val KEY_HERMES_WAKE_WORD = "hermes_wake_word"
        private const val KEY_OPENCLAW_WAKE_SOUND = "openclaw_wake_sound"
        private const val KEY_HERMES_WAKE_SOUND = "hermes_wake_sound"
        private const val KEY_DUAL_WAKE_WORDS_MIGRATED = "dual_wake_words_migrated"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_IS_VERIFIED = "is_verified"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_CONTINUOUS_MODE = "continuous_mode"
        private const val KEY_RESUME_LATEST_SESSION = "resume_latest_session"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_GATEWAY_PORT = "gateway_port"
        private const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
        private const val KEY_USE_NODE_CHAT = "use_node_chat"
        private const val KEY_CONNECTION_TYPE = "connection_type"
        private const val KEY_WAKEWORD_CONNECTION_TYPE = "wakeword_connection_type"
        private const val KEY_SPEECH_SILENCE_TIMEOUT = "speech_silence_timeout"
        private const val KEY_THINKING_SOUND_ENABLED = "thinking_sound_enabled"
        private const val KEY_FILLER_PHRASES_ENABLED = "filler_phrases_enabled"
        private const val KEY_TTS_BARGE_IN_ENABLED = "tts_barge_in_enabled"
        private const val KEY_WAKE_WORD_DEBUG_ENABLED = "wake_word_debug_enabled"
        private const val KEY_MEDIA_BUTTON_ENABLED = "media_button_enabled"
        private const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_HAS_COMPLETED_SETUP = "has_completed_setup"
        
        // TTS settings keys
        private const val KEY_TTS_TYPE = "tts_type"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
        private const val KEY_ELEVENLABS_MODEL = "elevenlabs_model"
        private const val KEY_ELEVENLABS_SPEED = "elevenlabs_speed"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_VOICE = "openai_voice"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_RIG_QWEN_URL = "rig_qwen_url"
        private const val KEY_RIG_QWEN_SPEAKER = "rig_qwen_speaker"
        private const val KEY_RIG_QWEN_CUSTOM_VOICES = "rig_qwen_custom_voices"
        private const val KEY_VOICEVOX_SPEAKER_ID = "voicevox_speaker_id"
        private const val KEY_VOICEVOX_STYLE_ID = "voicevox_style_id"
        private const val KEY_VOICEVOX_TERMS_ACCEPTED = "voicevox_terms_accepted"

        // Wake word presets
        const val WAKE_WORD_OPEN_CLAW = "open_claw"
        const val WAKE_WORD_HEY_ASSISTANT = "hey_assistant"
        const val WAKE_WORD_JARVIS = "jarvis"
        const val WAKE_WORD_COMPUTER = "computer"
        const val WAKE_WORD_CUSTOM = "custom"

        const val DEFAULT_OPENCLAW_WAKE_WORD = "hey claw"
        const val DEFAULT_HERMES_WAKE_WORD = "hey hermes"
        const val VOICE_TARGET_OPENCLAW = "openclaw"
        const val VOICE_TARGET_HERMES = "hermes"
        const val WAKE_SOUND_NONE = "none"
        const val WAKE_SOUND_STANDARD = "standard"
        const val WAKE_SOUND_HIGH = "high"
        const val WAKE_SOUND_LOW = "low"
        
        const val CONNECTION_TYPE_GATEWAY = "gateway"
        const val CONNECTION_TYPE_HTTP = "http"
        
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        
        // TTS Type constants
        const val TTS_TYPE_LOCAL = "local"
        const val TTS_TYPE_ELEVENLABS = "elevenlabs"
        const val TTS_TYPE_OPENAI = "openai"
        const val TTS_TYPE_VOICEVOX = "voicevox"
        const val TTS_TYPE_RIG_QWEN = "rig_qwen"
        
        // Default ElevenLabs voice ID (empty - user must set)
        const val DEFAULT_ELEVENLABS_VOICE_ID = ""
        const val DEFAULT_ELEVENLABS_MODEL = "eleven_multilingual_v2"
        
        // Default OpenAI voice
        const val DEFAULT_OPENAI_VOICE = "coral"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-tts"

        // Default Rig Qwen TTS (Windows GPU box, Qwen3-TTS CustomVoice)
        const val DEFAULT_RIG_QWEN_URL = "http://100.95.34.108:8799"
        const val DEFAULT_RIG_QWEN_SPEAKER = "ryan"
        
        // Default VOICEVOX speaker (none - user must select and download)
        const val DEFAULT_VOICEVOX_SPEAKER_ID = 0
        const val DEFAULT_VOICEVOX_STYLE_ID = 0  // 四国めたん あまあま ( Style ID 0 )

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
