package com.openclaw.assistant.ui

import android.Manifest
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.gms.common.moduleinstall.ModuleInstall
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.ui.components.ConnectionState
import com.openclaw.assistant.ui.components.StatusIndicator
import com.openclaw.assistant.ui.GatewayTrustDialog
import com.openclaw.assistant.ui.theme.*
import com.openclaw.assistant.utils.GatewayConfigUtils
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch

private enum class SetupStep(val index: Int) {
    Welcome(1),
    Connection(2),
    Permissions(3),
    FinalCheck(4)
}

private enum class ConnectionMode {
    SetupCode,
    Manual
}

private enum class PermissionToggle(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val permissions: List<String>
) {
    Discovery(
        R.string.permission_discovery,
        R.string.permission_discovery_desc,
        Icons.Default.Wifi,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    ),
    Location(
        R.string.capability_location,
        R.string.permission_location_desc,
        Icons.Default.LocationOn,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    ),
    Notifications(
        R.string.permission_notifications,
        R.string.permission_post_notifications_desc,
        Icons.Default.Notifications,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    ),
    Microphone(
        R.string.permission_record_audio,
        R.string.permission_record_audio_desc,
        Icons.Default.Mic,
        listOf(Manifest.permission.RECORD_AUDIO)
    ),
    Camera(
        R.string.capability_camera,
        R.string.permission_camera_desc,
        Icons.Default.PhotoCamera,
        listOf(Manifest.permission.CAMERA)
    ),
    Photos(
        R.string.capability_screen,
        R.string.permission_camera_desc, // Placeholder desc: Screen Capture
        Icons.Default.Photo,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    ),
    Contacts(
        R.string.permission_contacts,
        R.string.permission_contacts_desc,
        Icons.Default.Contacts,
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    ),
    Calendar(
        R.string.permission_calendar,
        R.string.permission_calendar_desc,
        Icons.Default.CalendarMonth,
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    ),
    Motion(
        R.string.permission_motion,
        R.string.permission_motion_desc,
        Icons.Default.DirectionsRun,
        listOf(Manifest.permission.ACTIVITY_RECOGNITION)
    ),
    SMS(
        R.string.capability_sms,
        R.string.permission_send_sms_desc,
        Icons.Default.Sms,
        listOf(Manifest.permission.SEND_SMS)
    )
}

private enum class SpecialAccessToggle(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector
) {
    NotificationListener(
        R.string.permission_notification_listener,
        R.string.permission_notification_listener_desc,
        Icons.Default.NotificationsActive
    ),
    AppUpdates(
        R.string.permission_install_unknown_apps,
        R.string.permission_install_unknown_apps_desc,
        Icons.Default.SystemUpdate
    )
}

private fun hasMotionCapabilities(context: Context): Boolean {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    return sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    val componentName = "${context.packageName}/com.openclaw.assistant.service.OpenClawNotificationListenerService"
    return enabledListeners?.contains(componentName) == true
}

private fun canInstallUnknownApps(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideScreen(
    settings: SettingsRepository,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }

    var currentStep by rememberSaveable { mutableStateOf(SetupStep.Welcome) }

    // UI State for Connection step
    var connectionMode by rememberSaveable { mutableStateOf(ConnectionMode.SetupCode) }
    var setupCode by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf(runtime.manualHost.value) }
    var manualPort by rememberSaveable { mutableStateOf(runtime.manualPort.value.toString()) }
    var manualTls by rememberSaveable { mutableStateOf(runtime.manualTls.value) }
    var authToken by rememberSaveable { mutableStateOf(settings.authToken) }
    var manualPassword by rememberSaveable { mutableStateOf(runtime.getGatewayPassword() ?: "") }

    val totalSteps = SetupStep.entries.size

    val onboardingGradient = Brush.verticalGradient(
        colors = listOf(OnboardingGradientStart, OnboardingGradientMid, OnboardingGradientEnd)
    )

    Scaffold(
        modifier = Modifier.background(onboardingGradient),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = OnboardingTextPrimary,
                    navigationIconContentColor = OnboardingTextPrimary
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.setup_guide_first_run),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnboardingGradientMid
                        )
                        Text(
                            text = stringResource(R.string.setup_guide_step_format, currentStep.index, totalSteps),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                navigationIcon = {
                    if (currentStep.index > 1) {
                        IconButton(onClick = {
                            val prevIndex = currentStep.index - 1
                            currentStep = SetupStep.entries.first { it.index == prevIndex }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        val pendingGatewayTrust by runtime.pendingGatewayTrust.collectAsState()
        if (pendingGatewayTrust != null) {
            GatewayTrustDialog(
                prompt = pendingGatewayTrust!!,
                onAccept = { runtime.acceptGatewayTrustPrompt() },
                onDecline = { runtime.declineGatewayTrustPrompt() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .background(OnboardingSurface, RoundedCornerShape(24.dp))
                .border(1.dp, OnboardingBorder, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            when (currentStep) {
                SetupStep.Welcome -> WelcomeStep(
                    onNext = { currentStep = SetupStep.Connection }
                )
                SetupStep.Connection -> ConnectionStep(
                    mode = connectionMode,
                    setupCode = setupCode,
                    manualHost = manualHost,
                    manualPort = manualPort,
                    manualTls = manualTls,
                    authToken = authToken,
                    manualPassword = manualPassword,
                    onModeChange = { connectionMode = it },
                    onSetupCodeChange = { setupCode = it },
                    onManualHostChange = { manualHost = it },
                    onManualPortChange = { manualPort = it },
                    onManualTlsChange = { manualTls = it },
                    onAuthTokenChange = { authToken = it },
                    onManualPasswordChange = { manualPassword = it },
                    onNext = {
                        // Apply settings
                        if (connectionMode == ConnectionMode.SetupCode) {
                            val decoded = GatewayConfigUtils.decodeGatewaySetupCode(setupCode)
                            Log.d("SetupGuide", "Setup code decoded: decoded=${decoded != null}, hasToken=${decoded?.token != null}, hasPassword=${decoded?.password != null}")
                            if (decoded != null) {
                                val parsed = GatewayConfigUtils.parseGatewayEndpoint(decoded.url)
                                Log.d("SetupGuide", "Endpoint parsed: parsed=${parsed != null}")
                                if (parsed != null) {
                                    runtime.setManualHost(parsed.host)
                                    runtime.setManualPort(parsed.port)
                                    runtime.setManualTls(parsed.tls)
                                    // Save credentials and clear stale fields from previous setup codes.
                                    // Only one auth type is valid at a time.
                                    when {
                                        decoded.bootstrapToken != null -> {
                                            runtime.setGatewayBootstrapToken(decoded.bootstrapToken)
                                            // Save user-entered credential (password takes priority over token)
                                            when {
                                                manualPassword.isNotBlank() -> {
                                                    runtime.setGatewayPassword(manualPassword.trim())
                                                    runtime.prefs.setGatewayToken("")
                                                    runtime.prefs.saveGatewayToken("")
                                                }
                                                authToken.isNotBlank() -> {
                                                    runtime.prefs.saveGatewayToken(authToken.trim())
                                                    runtime.setGatewayPassword("")
                                                }
                                                else -> {
                                                    runtime.prefs.setGatewayToken("")
                                                    runtime.prefs.saveGatewayToken("")
                                                    runtime.setGatewayPassword("")
                                                }
                                            }
                                        }
                                        decoded.token != null -> {
                                            runtime.prefs.saveGatewayToken(decoded.token)
                                            runtime.setGatewayBootstrapToken("")
                                            runtime.setGatewayPassword("")
                                        }
                                        decoded.password != null -> {
                                            runtime.setGatewayPassword(decoded.password)
                                            runtime.prefs.setGatewayToken("")
                                            runtime.prefs.saveGatewayToken("")
                                            runtime.setGatewayBootstrapToken("")
                                        }
                                    }
                                    // Auto-generate HTTP URL and token from gateway endpoint
                                    GatewayConfigUtils.composeGatewayManualUrl(parsed.host, parsed.port.toString(), parsed.tls)
                                        ?.let {
                                            if (com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(it)) {
                                                settings.httpUrl = it
                                            }
                                        }
                                    decoded.token?.let { settings.authToken = it }
                                }
                            }
                        } else {
                            runtime.setManualHost(manualHost)
                            runtime.setManualPort(manualPort.toIntOrNull() ?: 18789)
                            runtime.setManualTls(manualTls)
                            // Save to gateway-specific storage (prefs), not HTTP settings
                            runtime.prefs.saveGatewayToken(authToken.trim())
                            runtime.setGatewayPassword(manualPassword.trim())
                            // Auto-generate HTTP URL from gateway endpoint
                            GatewayConfigUtils.composeGatewayManualUrl(manualHost, manualPort, manualTls)
                                ?.let {
                                    if (com.openclaw.assistant.shared.utils.NetworkUtils.isUrlSecure(it)) {
                                        settings.httpUrl = it
                                    }
                                }
                            // Set HTTP auth token from manual input
                            if (authToken.isNotBlank()) settings.authToken = authToken.trim()
                            else if (manualPassword.isNotBlank()) settings.authToken = manualPassword.trim()
                        }
                        runtime.setManualEnabled(true)
                        settings.connectionType = SettingsRepository.CONNECTION_TYPE_GATEWAY
                        currentStep = SetupStep.Permissions
                    }
                )
                SetupStep.Permissions -> PermissionsStep(
                    onNext = { currentStep = SetupStep.FinalCheck }
                )
                SetupStep.FinalCheck -> FinalCheckStep(
                    settings = settings,
                    onFinish = {
                        settings.hasCompletedSetup = true
                        onComplete()
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Launch,
                contentDescription = stringResource(R.string.setup_guide_title),
                modifier = Modifier.size(80.dp),
                tint = OnboardingGradientMid
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.setup_guide_title),
                style = MaterialTheme.typography.headlineMedium,
                color = OnboardingTextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 40.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_1))
            BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_2))
            BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_3))
            BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_4))

            Spacer(modifier = Modifier.height(32.dp))
        }

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.setup_guide_next), fontSize = 18.sp)
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(24.dp).padding(top = 2.dp),
            tint = OnboardingGradientMid
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = OnboardingTextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionStep(
    mode: ConnectionMode,
    setupCode: String,
    manualHost: String,
    manualPort: String,
    manualTls: Boolean,
    authToken: String,
    manualPassword: String,
    onModeChange: (ConnectionMode) -> Unit,
    onSetupCodeChange: (String) -> Unit,
    onManualHostChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onManualTlsChange: (Boolean) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onManualPasswordChange: (String) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // スクロール可能なコンテンツ部分
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        Text(
            text = stringResource(R.string.setup_guide_connection_title),
            style = MaterialTheme.typography.headlineSmall,
            color = OnboardingTextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Mode Selector
        TabRow(
            selectedTabIndex = if (mode == ConnectionMode.SetupCode) 0 else 1,
            containerColor = Color.Transparent,
            contentColor = OnboardingGradientMid,
            divider = {}
        ) {
            Tab(
                selected = mode == ConnectionMode.SetupCode,
                onClick = { onModeChange(ConnectionMode.SetupCode) },
                text = { Text(stringResource(R.string.setup_guide_mode_setup_code), color = if (mode == ConnectionMode.SetupCode) OnboardingGradientMid else OnboardingTextSecondary) }
            )
            Tab(
                selected = mode == ConnectionMode.Manual,
                onClick = { onModeChange(ConnectionMode.Manual) },
                text = { Text(stringResource(R.string.setup_guide_mode_manual), color = if (mode == ConnectionMode.Manual) OnboardingGradientMid else OnboardingTextSecondary) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (mode == ConnectionMode.SetupCode) {
            val decodedSetupCode = GatewayConfigUtils.decodeGatewaySetupCode(setupCode)
            val isCodeValid = decodedSetupCode != null
            val hasBootstrapOnly = isCodeValid &&
                decodedSetupCode?.bootstrapToken != null &&
                decodedSetupCode.password == null &&
                decodedSetupCode.token == null

            Text(
                text = stringResource(R.string.setup_guide_qr_scan_title),
                style = MaterialTheme.typography.titleMedium,
                color = OnboardingTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.setup_guide_qr_scan_cmd_label),
                style = MaterialTheme.typography.bodySmall,
                color = OnboardingTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            CommandBlock("openclaw qr")
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = GmsBarcodeScanning.getClient(context, options)
                    ModuleInstall.getClient(context)
                        .areModulesAvailable(scanner)
                        .addOnSuccessListener { response ->
                            if (response.areModulesAvailable()) {
                                scanner.startScan()
                                    .addOnSuccessListener { barcode ->
                                        barcode.rawValue?.let { rawValue ->
                                            // openclaw qr generates {"setupCode":"base64..."} JSON; extract the inner code
                                            val code = try {
                                                org.json.JSONObject(rawValue.trim())
                                                    .optString("setupCode")
                                                    .takeIf { it.isNotBlank() } ?: rawValue
                                            } catch (_: Exception) {
                                                rawValue
                                            }
                                            onSetupCodeChange(code)
                                        }
                                    }
                                    .addOnFailureListener { /* scan cancelled or failed — no action needed */ }
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.qr_scan_unavailable),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .addOnFailureListener {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.qr_scan_unavailable),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = OnboardingGradientMid
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, OnboardingGradientMid)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.qr_scan_prompt))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.qr_scan_prompt), fontSize = 16.sp)
            }

            // Show scan result
            if (setupCode.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                if (isCodeValid) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = decodedSetupCode!!.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color(0xFF4CAF50)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.setup_guide_invalid_code),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // When QR contains only bootstrapToken, prompt for password/token
            if (hasBootstrapOnly) {
                val credFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnboardingTextPrimary,
                    unfocusedTextColor = OnboardingTextPrimary,
                    focusedLabelColor = OnboardingGradientMid,
                    unfocusedLabelColor = OnboardingTextSecondary,
                    focusedBorderColor = OnboardingGradientMid,
                    unfocusedBorderColor = OnboardingBorder,
                    cursorColor = OnboardingGradientMid,
                    focusedPlaceholderColor = OnboardingTextSecondary,
                    unfocusedPlaceholderColor = OnboardingTextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.openclaw.assistant.ui.components.CredentialHintCard()
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = manualPassword,
                    onValueChange = onManualPasswordChange,
                    label = { Text(stringResource(R.string.setup_guide_manual_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = credFieldColors,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = authToken,
                    onValueChange = onAuthTokenChange,
                    label = { Text(stringResource(R.string.setup_guide_manual_token)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = credFieldColors,
                )
            }
        } else {
            // Manual Mode fields
            val manualFieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnboardingTextPrimary,
                unfocusedTextColor = OnboardingTextPrimary,
                focusedLabelColor = OnboardingGradientMid,
                unfocusedLabelColor = OnboardingTextSecondary,
                focusedBorderColor = OnboardingGradientMid,
                unfocusedBorderColor = OnboardingBorder,
                cursorColor = OnboardingGradientMid,
                focusedPlaceholderColor = OnboardingTextSecondary,
                unfocusedPlaceholderColor = OnboardingTextSecondary,
            )
            OutlinedTextField(
                value = manualHost,
                onValueChange = onManualHostChange,
                label = { Text(stringResource(R.string.setup_guide_manual_host)) },
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = manualFieldColors
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = manualPort,
                onValueChange = onManualPortChange,
                label = { Text(stringResource(R.string.setup_guide_manual_port)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = manualFieldColors
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_guide_manual_tls), fontWeight = FontWeight.Bold, color = OnboardingTextPrimary)
                    Text(stringResource(R.string.setup_guide_manual_tls_desc), style = MaterialTheme.typography.bodySmall, color = OnboardingTextSecondary)
                }
                Switch(
                    checked = manualTls,
                    onCheckedChange = onManualTlsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnboardingGradientMid,
                        checkedTrackColor = OnboardingGradientMid.copy(alpha = 0.4f),
                        uncheckedThumbColor = OnboardingTextSecondary,
                        uncheckedTrackColor = OnboardingBorder,
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = authToken,
                onValueChange = onAuthTokenChange,
                label = { Text(stringResource(R.string.setup_guide_manual_token)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = manualFieldColors
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = manualPassword,
                onValueChange = onManualPasswordChange,
                label = { Text(stringResource(R.string.setup_guide_manual_password)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = manualFieldColors
            )
        } // end of if/else (SetupCode / Manual)
        } // end of scrollable Column

        // --- 次へボタン・画面下部に固定 ---
        val canContinue = if (mode == ConnectionMode.SetupCode) {
            GatewayConfigUtils.decodeGatewaySetupCode(setupCode) != null
        } else {
            manualHost.isNotBlank() && manualPort.toIntOrNull() != null
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNext,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.setup_guide_next), fontSize = 18.sp)
        }
    } // end of Column(fillMaxSize)
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current

    val smsAvailable = remember(context) {
        context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) == true
    }
    val motionAvailable = remember(context) {
        hasMotionCapabilities(context)
    }

    var permissionsStatus by remember {
        mutableStateOf(PermissionToggle.entries.associateWith { toggle ->
            toggle.permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        })
    }

    var specialAccessStatus by remember {
        mutableStateOf(SpecialAccessToggle.entries.associateWith { toggle ->
            when (toggle) {
                SpecialAccessToggle.NotificationListener -> isNotificationListenerEnabled(context)
                SpecialAccessToggle.AppUpdates -> canInstallUnknownApps(context)
            }
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsStatus = PermissionToggle.entries.associateWith { toggle ->
            toggle.permissions.all { perm ->
                results[perm] ?: (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
            }
        }
    }

    // We can use a LifecycleEventObserver to refresh special access when returning from settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                specialAccessStatus = SpecialAccessToggle.entries.associateWith { toggle ->
                    when (toggle) {
                        SpecialAccessToggle.NotificationListener -> isNotificationListenerEnabled(context)
                        SpecialAccessToggle.AppUpdates -> canInstallUnknownApps(context)
                    }
                }
                // Also refresh normal permissions in case they were changed in settings
                permissionsStatus = PermissionToggle.entries.associateWith { toggle ->
                    toggle.permissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.setup_guide_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            color = OnboardingTextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_guide_permissions_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = OnboardingTextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        PermissionToggle.entries.forEach { toggle ->
            if (toggle == PermissionToggle.SMS && !smsAvailable) return@forEach
            if (toggle == PermissionToggle.Motion && !motionAvailable) return@forEach
            if (toggle == PermissionToggle.Notifications && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@forEach

            PermissionItem(
                icon = toggle.icon,
                name = stringResource(toggle.titleRes),
                desc = stringResource(toggle.descRes),
                isGranted = permissionsStatus[toggle] == true,
                onClick = {
                    launcher.launch(toggle.permissions.toTypedArray())
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.permission_special_access),
            style = MaterialTheme.typography.titleMedium,
            color = OnboardingTextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        SpecialAccessToggle.entries.forEach { toggle ->
            PermissionItem(
                icon = toggle.icon,
                name = stringResource(toggle.titleRes),
                desc = stringResource(toggle.descRes),
                isGranted = specialAccessStatus[toggle] == true,
                onClick = {
                    when (toggle) {
                        SpecialAccessToggle.NotificationListener -> {
                            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                            context.startActivity(intent)
                        }
                        SpecialAccessToggle.AppUpdates -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        } // end scrollable Column

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.setup_guide_next), fontSize = 18.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionItem(
    icon: ImageVector,
    name: String,
    desc: String,
    isGranted: Boolean,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onClickLabel = stringResource(R.string.permission_toggle_accessibility_label, name)
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (isGranted) OnboardingGradientMid.copy(alpha = 0.2f) else OnboardingBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = name,
                tint = if (isGranted) OnboardingGradientMid else OnboardingTextSecondary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontWeight = FontWeight.Bold, color = OnboardingTextPrimary)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = OnboardingTextSecondary)
        }
        if (isGranted) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green)
        }
    }
}

@Composable
private fun FinalCheckStep(
    settings: SettingsRepository,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }
    val isConnected by runtime.isConnected.collectAsState()
    val statusText by runtime.statusText.collectAsState()
    val serverName by runtime.serverName.collectAsState()
    val remoteAddress by runtime.remoteAddress.collectAsState()
    val manualHost by runtime.manualHost.collectAsState()
    val manualPort by runtime.manualPort.collectAsState()
    val manualTls by runtime.manualTls.collectAsState()
    val isPairingRequired by runtime.isPairingRequired.collectAsState()

    val scope = rememberCoroutineScope()
    val apiClient = remember { OpenClawClient() }
    var attemptedConnect by remember { mutableStateOf(false) }
    var pairingDetected by remember { mutableStateOf(false) }
    var isFinishing by remember { mutableStateOf(false) }

    val finishWithHttpTest: () -> Unit = {
        scope.launch {
            isFinishing = true
            if (settings.httpUrl.isNotBlank()) {
                val testUrl = settings.getChatCompletionsUrl()
                val result = apiClient.testConnection(testUrl, settings.authToken)
                if (result.isSuccess) {
                    settings.isVerified = true
                } else {
                    // HTTP test failed: skip HTTP config
                    settings.httpUrl = ""
                    settings.authToken = ""
                    settings.isVerified = false
                }
            }
            isFinishing = false
            onFinish()
        }
    }

    LaunchedEffect(isPairingRequired) {
        if (isPairingRequired) pairingDetected = true
    }

    val gatewayUrl = remember(manualHost, manualPort, manualTls) {
        "${if (manualTls) "https" else "http"}://$manualHost:$manualPort"
    }
    val hasToken = remember { runtime.prefs.loadGatewayToken()?.isNotBlank() == true }
    val hasPassword = remember { runtime.getGatewayPassword()?.isNotBlank() == true }
    val authLabel = when {
        hasToken && hasPassword -> stringResource(R.string.setup_guide_auth_token_password)
        hasToken -> stringResource(R.string.setup_guide_auth_token)
        hasPassword -> stringResource(R.string.setup_guide_auth_password)
        else -> stringResource(R.string.setup_guide_auth_none)
    }

    val grantedCount = remember(context) {
        PermissionToggle.entries.count { toggle ->
            toggle.permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
    val specialCount = remember(context) {
        SpecialAccessToggle.entries.count { toggle ->
            when (toggle) {
                SpecialAccessToggle.NotificationListener -> isNotificationListenerEnabled(context)
                SpecialAccessToggle.AppUpdates -> canInstallUnknownApps(context)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Text(
            text = stringResource(R.string.setup_guide_final_check_title),
            style = MaterialTheme.typography.headlineSmall,
            color = OnboardingTextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_guide_final_check_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = OnboardingTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Gateway URL + auth summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.setup_guide_gateway_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp)
                )
                Text(
                    text = gatewayUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.setup_guide_auth_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp)
                )
                Text(
                    text = authLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Permissions Summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OnboardingGradientMid.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .border(1.dp, OnboardingGradientMid.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, contentDescription = stringResource(R.string.permission_summary_title), tint = OnboardingGradientMid)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.permission_summary_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnboardingTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.permission_summary_format, grantedCount, specialCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnboardingTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!attemptedConnect) {
            Text(
                text = stringResource(R.string.setup_guide_test_connection_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            val isConnecting = statusText.contains("Connecting", ignoreCase = true)
            val state = when {
                isConnected -> ConnectionState.Connected
                isConnecting -> ConnectionState.Connecting
                else -> ConnectionState.Disconnected
            }

            StatusIndicator(
                state = state,
                label = statusText,
                modifier = Modifier.padding(16.dp)
            )

            if (isConnected && (serverName != null || remoteAddress != null)) {
                Text(
                    text = stringResource(R.string.setup_guide_connected_to, serverName ?: remoteAddress ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            if (!isConnected && pairingDetected) {
                Spacer(modifier = Modifier.height(8.dp))
                PairingGuideBlock(deviceId = runtime.deviceId)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        } // end of scrollable Column

        Spacer(modifier = Modifier.height(16.dp))

        if (isConnected) {
            Button(
                onClick = finishWithHttpTest,
                enabled = !isFinishing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isFinishing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.setup_guide_finish), fontSize = 18.sp)
                }
            }
        } else {
            Button(
                onClick = {
                    attemptedConnect = true
                    pairingDetected = false
                    runtime.connectManual()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.test_connection_button), fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = finishWithHttpTest,
                enabled = !isFinishing,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (isFinishing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.setup_guide_finish), fontSize = 16.sp)
                }
            }
        }
    } // end of Column(fillMaxSize)
}

@Composable
private fun PairingGuideBlock(deviceId: String?) {
    val context = LocalContext.current
    val approveCmd = if (deviceId != null) {
        context.getString(R.string.approve_command_format, deviceId)
    } else {
        "openclaw devices approve <RequestId>"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setup_guide_pairing_required),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.setup_guide_pairing_run_on_host),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CommandBlock("openclaw devices list")
        CommandBlock(approveCmd)
        Text(
            text = stringResource(R.string.setup_guide_pairing_retest_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommandBlock(command: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
            .clickable(
                onClickLabel = stringResource(R.string.pairing_copy_command),
                role = Role.Button
            ) {
                clipboardManager.setText(AnnotatedString(command))
                android.widget.Toast.makeText(context, context.getString(R.string.setup_guide_copied), android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = command,
            color = Color(0xFF58A6FF),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = stringResource(R.string.pairing_copy_command),
            tint = Color(0xFF58A6FF),
            modifier = Modifier.size(16.dp)
        )
    }
}
