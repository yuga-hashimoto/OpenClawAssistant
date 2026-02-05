package com.openclaw.assistant.speech.diagnostics

import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Data structure for voice diagnostics results
 */
data class VoiceDiagnostic(
    val sttStatus: DiagnosticStatus,
    val ttsStatus: DiagnosticStatus,
    val sttEngine: String? = null,
    val ttsEngine: String? = null,
    val missingLanguages: List<String> = emptyList(),
    val suggestions: List<DiagnosticSuggestion> = emptyList()
)

enum class DiagnosticStatus {
    READY,      // Ready to use
    WARNING,    // Action required (settings)
    ERROR       // Inoperable
}

data class DiagnosticSuggestion(
    val message: String,
    val actionLabel: String? = null,
    val intent: Intent? = null,
    val isSystemSetting: Boolean = false
)

/**
 * Class for performing voice functionality diagnostics
 */
class VoiceDiagnostics(private val context: Context) {

    fun performFullCheck(tts: TextToSpeech?): VoiceDiagnostic {
        val sttResult = checkSTT()
        val ttsResult = checkTTS(tts)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        suggestions.addAll(sttResult.suggestions)
        suggestions.addAll(ttsResult.suggestions)

        return VoiceDiagnostic(
            sttStatus = sttResult.status,
            ttsStatus = ttsResult.status,
            sttEngine = sttResult.engine,
            ttsEngine = ttsResult.engine,
            missingLanguages = ttsResult.missingLangs,
            suggestions = suggestions
        )
    }

    private data class ComponentCheckResult(
        val status: DiagnosticStatus,
        val engine: String? = null,
        val suggestions: List<DiagnosticSuggestion> = emptyList(),
        val missingLangs: List<String> = emptyList()
    )

    private fun checkSTT(): ComponentCheckResult {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                suggestions = listOf(
                    DiagnosticSuggestion(
                        "Speech recognition service not found. Make sure Google app is installed.",
                        "Open Store",
                        null
                    )
                )
            )
        }
        return ComponentCheckResult(status = DiagnosticStatus.READY, engine = "System Default")
    }

    private fun checkTTS(tts: TextToSpeech?): ComponentCheckResult {
        val engine = tts?.defaultEngine
        val currentLocale = Locale.getDefault()
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        
        if (tts == null || engine == null) {
            // Check if Google TTS is actually installed via Package Manager
            val isGoogleInstalled = try {
                context.packageManager.getPackageInfo("com.google.android.tts", 0)
                true
            } catch (e: Exception) {
                false
            }

            val msg = if (isGoogleInstalled) {
                "Google TTS is installed but HIDDEN by the system. This is a common MIUI issue."
            } else {
                "TTS engine could not be initialized. Engine is null."
            }

            suggestions.add(
                DiagnosticSuggestion(
                    msg,
                    "Fix in Settings",
                    Intent("com.android.settings.TTS_SETTINGS"),
                    true
                )
            )
            
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                engine = "null/hidden",
                suggestions = suggestions
            )
        }

        val langResult = tts.isLanguageAvailable(currentLocale)
        val jaResult = tts.isLanguageAvailable(Locale.JAPANESE)
        
        var status = DiagnosticStatus.READY

        if (langResult < TextToSpeech.LANG_AVAILABLE && jaResult < TextToSpeech.LANG_AVAILABLE) {
            status = DiagnosticStatus.WARNING
            suggestions.add(
                DiagnosticSuggestion(
                    "Voice data for ${currentLocale.displayName} is missing in $engine.",
                    "Manage Data",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        if (engine != "com.google.android.tts") {
            suggestions.add(
                DiagnosticSuggestion(
                    "Currently using $engine. Switch to Google for better support.",
                    "Select Google",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        return ComponentCheckResult(
            status = status,
            engine = engine,
            suggestions = suggestions
        )
    }
}
