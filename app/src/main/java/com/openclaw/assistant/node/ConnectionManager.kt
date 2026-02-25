package com.openclaw.assistant.node

import android.os.Build
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.gateway.GatewayClientInfo
import com.openclaw.assistant.gateway.GatewayConnectOptions
import com.openclaw.assistant.gateway.GatewayEndpoint
import com.openclaw.assistant.gateway.GatewayTlsParams
import com.openclaw.assistant.protocol.OpenClawCanvasA2UICommand
import com.openclaw.assistant.protocol.OpenClawCanvasCommand
import com.openclaw.assistant.protocol.OpenClawCameraCommand
import com.openclaw.assistant.protocol.OpenClawLocationCommand
import com.openclaw.assistant.protocol.OpenClawScreenCommand
import com.openclaw.assistant.protocol.OpenClawSmsCommand
import com.openclaw.assistant.protocol.OpenClawCapability
import com.openclaw.assistant.LocationMode
import com.openclaw.assistant.VoiceWakeMode

class ConnectionManager(
  private val prefs: SecurePrefs,
  private val cameraEnabled: () -> Boolean,
  private val locationMode: () -> LocationMode,
  private val voiceWakeMode: () -> VoiceWakeMode,
  private val smsAvailable: () -> Boolean,
  private val hasRecordAudioPermission: () -> Boolean,
  private val manualTls: () -> Boolean,
  private val deviceId: () -> String?,
) {
  companion object {
    internal fun resolveTlsParamsForEndpoint(
      endpoint: GatewayEndpoint,
      storedFingerprint: String?,
      manualTlsEnabled: Boolean,
    ): GatewayTlsParams? {
      val stableId = endpoint.stableId
      val stored = storedFingerprint?.trim().takeIf { !it.isNullOrEmpty() }
      val isManual = stableId.startsWith("manual|")

      if (isManual) {
        if (!manualTlsEnabled) return null
        if (!stored.isNullOrBlank()) {
          return GatewayTlsParams(
            required = true,
            expectedFingerprint = stored,
            allowTOFU = false,
            stableId = stableId,
          )
        }
        return GatewayTlsParams(
          required = true,
          expectedFingerprint = null,
          allowTOFU = false,
          stableId = stableId,
        )
      }

      // Prefer stored pins. Never let discovery-provided TXT override a stored fingerprint.
      if (!stored.isNullOrBlank()) {
        return GatewayTlsParams(
          required = true,
          expectedFingerprint = stored,
          allowTOFU = false,
          stableId = stableId,
        )
      }

      val hinted = endpoint.tlsEnabled || !endpoint.tlsFingerprintSha256.isNullOrBlank()
      if (hinted) {
        // TXT is unauthenticated. Do not treat the advertised fingerprint as authoritative.
        return GatewayTlsParams(
          required = true,
          expectedFingerprint = null,
          allowTOFU = false,
          stableId = stableId,
        )
      }

      return null
    }
  }

  fun buildInvokeCommands(): List<String> =
    buildList {
      add(OpenClawCanvasCommand.Present.rawValue)
      add(OpenClawCanvasCommand.Hide.rawValue)
      add(OpenClawCanvasCommand.Navigate.rawValue)
      add(OpenClawCanvasCommand.Eval.rawValue)
      add(OpenClawCanvasCommand.Snapshot.rawValue)
      add(OpenClawCanvasA2UICommand.Push.rawValue)
      add(OpenClawCanvasA2UICommand.PushJSONL.rawValue)
      add(OpenClawCanvasA2UICommand.Reset.rawValue)
      add(OpenClawScreenCommand.Record.rawValue)
      if (cameraEnabled()) {
        add(OpenClawCameraCommand.Snap.rawValue)
        add(OpenClawCameraCommand.Clip.rawValue)
      }
      if (locationMode() != LocationMode.Off) {
        add(OpenClawLocationCommand.Get.rawValue)
      }
      if (smsAvailable()) {
        add(OpenClawSmsCommand.Send.rawValue)
      }
      if (BuildConfig.DEBUG) {
        add("debug.logs")
        add("debug.ed25519")
      }
      add("app.update")
    }

  fun buildCapabilities(): List<String> =
    buildList {
      add(OpenClawCapability.Canvas.rawValue)
      add(OpenClawCapability.Screen.rawValue)
      if (cameraEnabled()) add(OpenClawCapability.Camera.rawValue)
      if (smsAvailable()) add(OpenClawCapability.Sms.rawValue)
      if (voiceWakeMode() != VoiceWakeMode.Off && hasRecordAudioPermission()) {
        add(OpenClawCapability.VoiceWake.rawValue)
      }
      if (locationMode() != LocationMode.Off) {
        add(OpenClawCapability.Location.rawValue)
      }
    }

  fun resolvedVersionName(): String {
    val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
    return if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
      "$versionName-dev"
    } else {
      versionName
    }
  }

  fun resolveModelIdentifier(): String? {
    return listOfNotNull(Build.MANUFACTURER, Build.MODEL)
      .joinToString(" ")
      .trim()
      .ifEmpty { null }
  }

  fun buildUserAgent(): String {
    val version = resolvedVersionName()
    val release = Build.VERSION.RELEASE?.trim().orEmpty()
    val releaseLabel = if (release.isEmpty()) "unknown" else release
    return "OpenClawAndroid/$version (Android $releaseLabel; SDK ${Build.VERSION.SDK_INT})"
  }

  fun buildClientInfo(clientId: String, clientMode: String): GatewayClientInfo {
    return GatewayClientInfo(
      id = clientId,
      displayName = deviceId() ?: prefs.displayName.value,
      version = resolvedVersionName(),
      platform = "android",
      mode = clientMode,
      instanceId = prefs.instanceId.value,
      deviceFamily = "Android",
      modelIdentifier = resolveModelIdentifier(),
    )
  }

  fun buildNodeConnectOptions(): GatewayConnectOptions {
    val caps = buildCapabilities()
    val requestedScopes = caps.map { "node.$it" }

    return GatewayConnectOptions(
      role = "node",
      scopes = requestedScopes,
      caps = caps,
      commands = buildInvokeCommands(),
      permissions = emptyMap(),
      client = buildClientInfo(clientId = "openclaw-android", clientMode = "node"),
      userAgent = buildUserAgent(),
    )
  }

  fun buildOperatorConnectOptions(): GatewayConnectOptions {
    return GatewayConnectOptions(
      role = "operator",
      scopes = listOf("operator.read", "operator.write", "operator.talk.secrets"),
      caps = emptyList(),
      commands = emptyList(),
      permissions = emptyMap(),
      client = buildClientInfo(clientId = "openclaw-control-ui", clientMode = "ui"),
      userAgent = buildUserAgent(),
    )
  }

  fun resolveTlsParams(endpoint: GatewayEndpoint): GatewayTlsParams? {
    val stored = prefs.loadGatewayTlsFingerprint(endpoint.stableId)
    return resolveTlsParamsForEndpoint(endpoint, storedFingerprint = stored, manualTlsEnabled = manualTls())
  }
}
