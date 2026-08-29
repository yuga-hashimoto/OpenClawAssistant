package com.openclaw.assistant.speech

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for all TTS providers (Local, ElevenLabs, OpenAI, VOICEVOX)
 */
interface TTSProvider {
    /**
     * Speak the given text.
     * @param text The text to speak
     * @return true if successful, false otherwise
     */
    suspend fun speak(text: String): Boolean
    
    /**
     * Stop current speech.
     */
    fun stop()
    
    /**
     * Release resources.
     */
    fun shutdown()
    
    /**
     * Check if this provider is available/ready.
     */
    fun isAvailable(): Boolean
    
    /**
     * Get provider type identifier.
     */
    fun getType(): String
    
    /**
     * Get provider display name.
     */
    fun getDisplayName(): String
    
    /**
     * Check if provider is properly configured (API keys set, etc.)
     */
    fun isConfigured(): Boolean
    
    /**
     * Get configuration error message if not configured.
     */
    fun getConfigurationError(): String?
    
    /**
     * Speak with progress updates.
     */
    fun speakWithProgress(text: String): Flow<TTSState> {
        throw NotImplementedError("Progress tracking not implemented for this provider")
    }
}

/**
 * TTS State for progress tracking
 */
sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}

/**
 * Provider type constants
 */
object TTSProviderType {
    const val LOCAL = "local"
    const val ELEVENLABS = "elevenlabs"
    const val OPENAI = "openai"
    const val VOICEVOX = "voicevox"
    const val RIG_QWEN = "rig_qwen"
}
