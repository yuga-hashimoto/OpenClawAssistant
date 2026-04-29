package com.openclaw.assistant.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Voice Interaction Service
 * Launched as a system assistant with a long press of the home button
 */
class OpenClawAssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OpenClawAssistantSvc"
        private const val PENDING_SESSION_TIMEOUT_MS = 30_000L
        const val ACTION_SHOW_ASSISTANT = "com.openclaw.assistant.ACTION_SHOW_ASSISTANT"
    }

    private var isServiceReady = false
    private var pendingShowSession = false
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSessionTimeoutRunnable = Runnable {
        if (pendingShowSession) {
            Log.w(TAG, "Pending showSession timed out. Clearing.")
            pendingShowSession = false
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e(TAG, "Assistant trigger receiver triggered: ${intent?.action}")
            if (intent?.action == ACTION_SHOW_ASSISTANT) {
                triggerShowSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "VoiceInteractionService onCreate")
        val filter = IntentFilter(ACTION_SHOW_ASSISTANT)
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "onStartCommand received: null (system restart)")
            return START_STICKY
        }
        
        val action = intent.action
        Log.e(TAG, "onStartCommand received: $action")
        if (action == ACTION_SHOW_ASSISTANT) {
            triggerShowSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.e(TAG, "onBind received: ${intent?.action}")
        return super.onBind(intent)
    }

    private fun triggerShowSession() {
        val compName = ComponentName(this, OpenClawAssistantService::class.java)
        val isActive = isActiveService(this, compName)
        Log.e(TAG, "triggerShowSession: isServiceReady=$isServiceReady, isActiveService=$isActive")

        if (!isActive) {
            // Not the default assistant: onReady() will never fire and showSession() won't work.
            // Fall back to VoiceOverlayActivity which uses an Activity context so SpeechRecognizer
            // gets foreground mic access on Android 14+.
            Log.e(TAG, "Not active assistant — launching VoiceOverlayActivity")
            try {
                val intent = Intent(this, com.openclaw.assistant.VoiceOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VoiceOverlayActivity, resuming hotword", e)
                sendBroadcast(Intent(HotwordService.ACTION_RESUME_HOTWORD).apply {
                    setPackage(packageName)
                })
            }
            return
        }

        if (isServiceReady) {
            try {
                val args = Bundle()
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called immediately")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call showSession immediately", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        } else {
            Log.e(TAG, "Service not ready. Queuing showSession request.")
            pendingShowSession = true
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            handler.postDelayed(pendingSessionTimeoutRunnable, PENDING_SESSION_TIMEOUT_MS)
        }
    }

    override fun onReady() {
        super.onReady()
        Log.e(TAG, "VoiceInteractionService onReady")
        isServiceReady = true
        if (pendingShowSession) {
            pendingShowSession = false
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            try {
                val args = Bundle()
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called from onReady (pending)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call pending showSession", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.e(TAG, "VoiceInteractionService onShutdown")
        isServiceReady = false
        pendingShowSession = false
        handler.removeCallbacks(pendingSessionTimeoutRunnable)
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "debugReceiver already unregistered", e)
        }
    }
}
