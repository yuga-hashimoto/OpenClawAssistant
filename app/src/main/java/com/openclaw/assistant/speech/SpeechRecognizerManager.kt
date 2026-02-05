package com.openclaw.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * Speech recognition manager
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Check if speech recognition is available
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Start listening and return results as Flow
     * language が null の場合はシステムデフォルトを使用する
     */
    fun startListening(language: String? = null): Flow<SpeechResult> = callbackFlow {
        // デフォルト言語の決定
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()
        
        android.util.Log.e("SpeechRecognizerManager", "startListening called, language=$targetLanguage, isAvailable=${isAvailable()}")

        // Clean slate: Ensure any previous instance is safely destroyed
        recognizer?.let { rec ->
            try {
                rec.cancel()
                rec.destroy()
            } catch (e: Exception) {
                // Ignore
            }
            recognizer = null
        }

        // Wait for Android to release internal resources (critical for 2nd+ invocations)
        kotlinx.coroutines.delay(300)

        // Use application context to avoid activity/service lifecycle leaks
        val appContext = context.applicationContext
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = newRecognizer

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechResult.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                trySend(SpeechResult.Processing)
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "オーディオエラー"
                    SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "権限が不足しています"
                    SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                    SpeechRecognizer.ERROR_NO_MATCH -> "認識できませんでした"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識サービスがビジー - 再試行します"
                    SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力がありませんでした"
                    else -> "不明なエラー ($error)"
                }
                
                trySend(SpeechResult.Error(errorMessage, error))
                
                // If busy, we don't necessarily close immediately if we want to retry manually in session,
                // but for now, let the session handle the error result.
                close()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.Result(
                        text = matches[0],
                        confidence = confidence?.getOrNull(0) ?: 0f,
                        alternatives = matches.drop(1)
                    ))
                } else {
                    trySend(SpeechResult.Error("認識結果がありません", SpeechRecognizer.ERROR_NO_MATCH))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.PartialResult(matches[0]))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        // Run on Main thread
        kotlinx.coroutines.Dispatchers.Main.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
             try {
                 newRecognizer.startListening(intent)
             } catch (e: Exception) {
                 trySend(SpeechResult.Error("開始エラー: ${e.message}"))
                 close()
             }
        })

        awaitClose {
            kotlinx.coroutines.Dispatchers.Main.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
                try {
                    // Cancel before destroy is safer
                    newRecognizer.cancel()
                    newRecognizer.destroy()
                } catch (e: Exception) {
                    // Ignore
                }
                if (recognizer == newRecognizer) {
                    recognizer = null
                }
            })
        }
    }

    /**
     * Stop listening manually
     */
    fun stopListening() { 
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() { 
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        recognizer = null
    }
}

/**
 * 音声認識の結果
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Listening : SpeechResult()
    object Processing : SpeechResult()
    data class RmsChanged(val rmsdB: Float) : SpeechResult()
    data class PartialResult(val text: String) : SpeechResult()
    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    data class Error(val message: String, val code: Int? = null) : SpeechResult()
}
