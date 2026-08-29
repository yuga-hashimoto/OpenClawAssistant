package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "TTSManager"

/**
 * Text-to-Speech Manager with support for multiple providers
 * (Local TTS, ElevenLabs, OpenAI, VOICEVOX)
 */
class TTSManager(private val context: Context) {
    
    private val settings = SettingsRepository.getInstance(context)
    
    // Provider instances
    private val providers = mutableMapOf<String, TTSProvider>()
    
    init {
        // Initialize all providers
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
        providers[TTSProviderType.ELEVENLABS] = ElevenLabsProvider(context)
        providers[TTSProviderType.OPENAI] = OpenAIProvider(context)
        providers[TTSProviderType.RIG_QWEN] = RigQwenTTSProvider(context)
        if (BuildConfig.FLAVOR == "full") {
            providers[TTSProviderType.VOICEVOX] = VoiceVoxProvider(context)
        }
    }
    
    /**
     * Get the currently configured provider
     */
    private fun getCurrentProvider(): TTSProvider? {
        val type = settings.ttsType
        return providers[type]
    }
    
    /**
     * Check if current provider is configured and available
     */
    fun isReady(): Boolean {
        val provider = getCurrentProvider()
        if (provider == null) {
            Log.e(TAG, "isReady: no provider for type '${settings.ttsType}'")
            return false
        }
        val available = provider.isAvailable()
        val configured = provider.isConfigured()
        if (!available || !configured) {
            Log.e(TAG, "isReady: ${provider.getDisplayName()} available=$available configured=$configured error=${provider.getConfigurationError()}")
        }
        return available && configured
    }
    
    /**
     * Get error message if not ready
     */
    fun getErrorMessage(): String? {
        val provider = getCurrentProvider()
        return when {
            provider == null -> context.getString(R.string.tts_error_unknown_type, settings.ttsType)
            !provider.isConfigured() -> provider.getConfigurationError()
            !provider.isAvailable() -> context.getString(R.string.tts_error_provider_unavailable, provider.getDisplayName())
            else -> null
        }
    }
    
    /**
     * Speak the given text using the configured provider
     */
    suspend fun speak(text: String): Boolean {
        val provider = getCurrentProvider()
        if (provider == null) {
            Log.e(TAG, "No provider found for type: ${settings.ttsType}")
            return false
        }
        
        if (!provider.isConfigured()) {
            Log.e(TAG, "Provider not configured: ${provider.getConfigurationError()}")
            return false
        }
        
        if (!provider.isAvailable()) {
            Log.e(TAG, "Provider not available: ${provider.getDisplayName()}")
            return false
        }
        
        // Preprocess text (strip markdown, etc.)
        val processedText = TTSUtils.stripMarkdownForSpeech(text)
        
        return provider.speak(processedText)
    }
    
    /**
     * Speak with progress updates
     */
    fun speakWithProgress(text: String): Flow<TTSState> {
        val provider = getCurrentProvider()
        if (provider == null) {
            return callbackFlow {
                trySend(TTSState.Error("No provider found"))
                close()
            }
        }
        
        if (!provider.isConfigured()) {
            return callbackFlow {
                trySend(TTSState.Error(provider.getConfigurationError() ?: "Not configured"))
                close()
            }
        }
        
        val processedText = TTSUtils.stripMarkdownForSpeech(text)
        return provider.speakWithProgress(processedText)
    }
    
    /**
     * Stop current speech
     */
    fun stop() {
        getCurrentProvider()?.stop()
    }
    
    /**
     * Stop all providers
     */
    fun stopAll() {
        providers.values.forEach { it.stop() }
    }
    
    /**
     * Release all resources
     */
    fun shutdown() {
        providers.values.forEach { it.shutdown() }
        providers.clear()
    }
    
    /**
     * Reinitialize after settings change
     */
    fun reinitialize() {
        shutdown()
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
        providers[TTSProviderType.ELEVENLABS] = ElevenLabsProvider(context)
        providers[TTSProviderType.OPENAI] = OpenAIProvider(context)
        providers[TTSProviderType.RIG_QWEN] = RigQwenTTSProvider(context)
        if (BuildConfig.FLAVOR == "full") {
            providers[TTSProviderType.VOICEVOX] = VoiceVoxProvider(context)
        }
    }
    
    /**
     * Initialize the current provider (needed for VOICEVOX)
     * Call this before using TTS
     */
    fun initializeCurrentProvider(): Boolean {
        val provider = getCurrentProvider()
        return if (BuildConfig.FLAVOR == "full" && provider is VoiceVoxProvider) {
            provider.initialize()
        } else {
            true // Other providers don't need explicit initialization
        }
    }
    
    /**
     * Get all available providers
     */
    fun getAvailableProviders(): List<TTSProviderInfo> {
        return listOf(
            TTSProviderInfo(
                type = TTSProviderType.LOCAL,
                displayName = context.getString(R.string.tts_provider_local_name),
                description = context.getString(R.string.tts_provider_local_description),
                isAvailable = true,
                isConfigured = true
            ),
            TTSProviderInfo(
                type = TTSProviderType.ELEVENLABS,
                displayName = "ElevenLabs",
                description = context.getString(R.string.tts_provider_elevenlabs_description),
                isAvailable = true,
                isConfigured = settings.elevenLabsApiKey.isNotBlank()
            ),
            TTSProviderInfo(
                type = TTSProviderType.OPENAI,
                displayName = "OpenAI",
                description = "OpenAI TTS API",
                isAvailable = true,
                isConfigured = settings.openAiApiKey.isNotBlank()
            ),
            TTSProviderInfo(
                type = TTSProviderType.RIG_QWEN,
                displayName = "Rig Qwen TTS",
                description = context.getString(R.string.tts_provider_rig_qwen_description),
                isAvailable = true,
                isConfigured = settings.rigQwenUrl.isNotBlank()
            ),
            TTSProviderInfo(
                type = TTSProviderType.VOICEVOX,
                displayName = "VOICEVOX",
                description = context.getString(R.string.tts_provider_voicevox_description),
                isAvailable = providers[TTSProviderType.VOICEVOX]?.isAvailable() == true,
                isConfigured = settings.voiceVoxTermsAccepted &&
                              providers[TTSProviderType.VOICEVOX]?.isAvailable() == true
            )
        )
    }
    
    /**
     * Get provider instance by type
     */
    fun getProvider(type: String): TTSProvider? = providers[type]
    
    data class TTSProviderInfo(
        val type: String,
        val displayName: String,
        val description: String,
        val isAvailable: Boolean,
        val isConfigured: Boolean
    )
}
