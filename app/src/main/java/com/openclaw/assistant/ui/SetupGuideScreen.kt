package com.openclaw.assistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.openclaw.assistant.utils.GatewayConfigUtils
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.setup_guide_first_run),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
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
                .verticalScroll(rememberScrollState()),
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
                            if (decoded != null) {
                                val parsed = GatewayConfigUtils.parseGatewayEndpoint(decoded.url)
                                if (parsed != null) {
                                    runtime.setManualHost(parsed.host)
                                    runtime.setManualPort(parsed.port)
                                    runtime.setManualTls(parsed.tls)
                                    // Save to gateway-specific storage (prefs), not HTTP settings
                                    if (decoded.token != null) {
                                        runtime.prefs.saveGatewayToken(decoded.token)
                                    }
                                    if (decoded.password != null) {
                                        runtime.setGatewayPassword(decoded.password)
                                    }
                                    // Auto-generate HTTP URL and token from gateway endpoint
                                    GatewayConfigUtils.composeGatewayManualUrl(parsed.host, parsed.port.toString(), parsed.tls)
                                        ?.let { settings.httpUrl = it }
                                    decoded.token?.let { settings.authToken = it }
                                        ?: decoded.password?.let { settings.authToken = it }
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
                                ?.let { settings.httpUrl = it }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Launch,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.setup_guide_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 40.sp
        )
        Spacer(modifier = Modifier.height(32.dp))

        BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_1))
        BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_2))
        BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_3))
        BulletPoint(stringResource(R.string.setup_guide_welcome_bullet_4))

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

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
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
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
    Column {
        Text(
            text = stringResource(R.string.setup_guide_connection_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Mode Selector
        TabRow(
            selectedTabIndex = if (mode == ConnectionMode.SetupCode) 0 else 1,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            Tab(
                selected = mode == ConnectionMode.SetupCode,
                onClick = { onModeChange(ConnectionMode.SetupCode) },
                text = { Text(stringResource(R.string.setup_guide_mode_setup_code)) }
            )
            Tab(
                selected = mode == ConnectionMode.Manual,
                onClick = { onModeChange(ConnectionMode.Manual) },
                text = { Text(stringResource(R.string.setup_guide_mode_manual)) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (mode == ConnectionMode.SetupCode) {
            Text(
                text = stringResource(R.string.setup_guide_connection_guide_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.setup_guide_connection_guide_cmd_1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "openclaw qr --setup-code-only",
                    color = Color.Green,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            Text(
                text = stringResource(R.string.setup_guide_connection_guide_json_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = setupCode,
                onValueChange = onSetupCodeChange,
                label = { Text(stringResource(R.string.setup_guide_setup_code_label)) },
                placeholder = { Text(stringResource(R.string.setup_guide_setup_code_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            val isCodeValid = GatewayConfigUtils.decodeGatewaySetupCode(setupCode) != null
            if (setupCode.isNotBlank() && !isCodeValid) {
                Text(
                    text = stringResource(R.string.setup_guide_invalid_code),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
        } else {
            // Manual Mode fields
            OutlinedTextField(
                value = manualHost,
                onValueChange = onManualHostChange,
                label = { Text(stringResource(R.string.setup_guide_manual_host)) },
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = manualPort,
                onValueChange = onManualPortChange,
                label = { Text(stringResource(R.string.setup_guide_manual_port)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_guide_manual_tls), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.setup_guide_manual_tls_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = manualTls, onCheckedChange = onManualTlsChange)
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = authToken,
                onValueChange = onAuthTokenChange,
                label = { Text(stringResource(R.string.setup_guide_manual_token)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = manualPassword,
                onValueChange = onManualPasswordChange,
                label = { Text(stringResource(R.string.setup_guide_manual_password)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        val canContinue = if (mode == ConnectionMode.SetupCode) {
            GatewayConfigUtils.decodeGatewaySetupCode(setupCode) != null
        } else {
            manualHost.isNotBlank() && manualPort.toIntOrNull() != null
        }

        Button(
            onClick = onNext,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.setup_guide_next), fontSize = 18.sp)
        }
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val permissions = remember {
        mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissionsStatus by remember {
        mutableStateOf(permissions.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsStatus = results.mapValues { it.value }
    }

    Column {
        Text(
            text = stringResource(R.string.setup_guide_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_guide_permissions_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        PermissionItem(
            icon = Icons.Default.Mic,
            name = stringResource(R.string.permission_record_audio),
            desc = stringResource(R.string.permission_record_audio_desc),
            isGranted = permissionsStatus[Manifest.permission.RECORD_AUDIO] == true
        )
        PermissionItem(
            icon = Icons.Default.PhotoCamera,
            name = stringResource(R.string.capability_camera),
            desc = stringResource(R.string.permission_camera_desc),
            isGranted = permissionsStatus[Manifest.permission.CAMERA] == true
        )
        PermissionItem(
            icon = Icons.Default.LocationOn,
            name = stringResource(R.string.capability_location),
            desc = stringResource(R.string.permission_location_desc),
            isGranted = permissionsStatus[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissionsStatus[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionItem(
                icon = Icons.Default.Notifications,
                name = stringResource(R.string.permission_notifications),
                desc = stringResource(R.string.permission_post_notifications_desc),
                isGranted = permissionsStatus[Manifest.permission.POST_NOTIFICATIONS] == true
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { launcher.launch(permissions.toTypedArray()) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(stringResource(R.string.grant_permission), fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(stringResource(R.string.setup_guide_next), fontSize = 18.sp)
        }
    }
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    name: String,
    desc: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (isGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontWeight = FontWeight.Bold)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.setup_guide_final_check_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_guide_final_check_desc),
            style = MaterialTheme.typography.bodyLarge,
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

        Spacer(modifier = Modifier.height(24.dp))

        if (!attemptedConnect) {
            Text(
                text = stringResource(R.string.setup_guide_test_connection_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            StatusIndicator(
                state = if (isConnected) ConnectionState.Connected else ConnectionState.Disconnected,
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

        Spacer(modifier = Modifier.height(24.dp))

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
    }
}

@Composable
private fun PairingGuideBlock(deviceId: String?) {
    val approveCmd = if (deviceId != null) {
        "openclaw devices approve $deviceId"
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandBlock(command: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(command))
                    android.widget.Toast.makeText(context, context.getString(R.string.setup_guide_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            .padding(10.dp)
    ) {
        Text(
            text = command,
            color = Color.Green,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
