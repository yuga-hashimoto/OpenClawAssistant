package com.openclaw.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.service.NodeForegroundService
import com.openclaw.assistant.service.OpenClawAssistantService
import com.openclaw.assistant.ui.GatewayTrustDialog
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.speech.diagnostics.DiagnosticStatus
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostic
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostics
import com.openclaw.assistant.ui.components.CollapsibleSection
import com.openclaw.assistant.ui.components.ConnectionState
import com.openclaw.assistant.ui.components.PairingRequiredCard
import com.openclaw.assistant.ui.components.StatusIndicator
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import com.openclaw.assistant.ui.SetupGuideScreen

data class PermissionStatusInfo(
    val permissionName: String,
    val isGranted: Boolean
)

data class PermissionInfo(
    val permission: String,
    val nameResId: Int,
    val descResId: Int
)

sealed class AppTab(val route: String, val labelResId: Int, val icon: ImageVector) {
    object Home     : AppTab("home",     R.string.tab_nav_home,     Icons.Default.Home)
    object Chat     : AppTab("chat",     R.string.tab_nav_chat,     Icons.AutoMirrored.Filled.Chat)
    object Canvas   : AppTab("canvas",   R.string.tab_nav_canvas,   Icons.Default.Brush)
    object Settings : AppTab("settings", R.string.tab_nav_settings, Icons.Default.Settings)
    companion object { val ALL by lazy { listOf(Home, Chat, Canvas, Settings) } }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var settings: SettingsRepository
    private var tts: TextToSpeech? = null
    private var voiceDiagnostic by mutableStateOf<VoiceDiagnostic?>(null)
    private var missingPermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
    private var allPermissionsStatus by mutableStateOf<List<PermissionStatusInfo>>(emptyList())
    private var pendingHotwordStart = false
    private var chatRefreshTrigger by mutableStateOf(0)

    private lateinit var screenCaptureRequester: ScreenCaptureRequester
    private lateinit var permissionRequester: PermissionRequester

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false

        // Auto-enable capabilities when permission is newly granted
        val runtime = (applicationContext as OpenClawApplication).nodeRuntime
        if (cameraGranted) {
            runtime.setCameraEnabled(true)
        }
        if (smsGranted) {
            runtime.setSmsEnabled(true)
        }
        if (coarseGranted) {
            runtime.setLocationMode(com.openclaw.assistant.LocationMode.WhileUsing)
            runtime.setLocationPreciseEnabled(fineGranted)
        } else if (fineGranted) {
            runtime.setLocationPreciseEnabled(true)
        }

        if (pendingHotwordStart) {
            pendingHotwordStart = false
            if (recordAudioGranted) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    showPermissionSettingsDialog()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        } else {
            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                // Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_SHORT).show()
            }
        }
        refreshMissingPermissions()
        refreshAllPermissionsStatus()
    }

    override fun attachBaseContext(newBase: Context) {
        // ComponentActivity does not participate in AppCompat's locale delegation,
        // so we must manually apply the saved locale here. This guarantees every
        // new instance (whether created by recreate() or a system config change)
        // immediately has the correct locale without relying on timing.
        val tag = try {
            SettingsRepository.getInstance(newBase).appLanguage.trim()
        } catch (e: Exception) { "" }
        if (tag.isNotBlank()) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)
        
        screenCaptureRequester = ScreenCaptureRequester(this)
        permissionRequester = PermissionRequester(this)
        
        val runtime = (applicationContext as OpenClawApplication).nodeRuntime
        runtime.screenRecorder.attachScreenCaptureRequester(screenCaptureRequester)
        runtime.screenRecorder.attachPermissionRequester(permissionRequester)
        runtime.attachPermissionRequester(permissionRequester)
        
        initializeTTS()
        // Removed checkPermissions() from onCreate to allow SetupGuideScreen to handle it
        refreshMissingPermissions()
        refreshAllPermissionsStatus()

        setContent {
            OpenClawAssistantTheme {
                val hasCompletedSetup by remember { mutableStateOf(settings.hasCompletedSetup) }
                var showSetupGuide by remember { mutableStateOf(!hasCompletedSetup) }

                if (showSetupGuide) {
                    SetupGuideScreen(
                        settings = settings,
                        onComplete = {
                            showSetupGuide = false
                            // After setup, we might want to trigger permission refresh or other once
                            refreshMissingPermissions()
                            refreshAllPermissionsStatus()
                        }
                    )
                } else {
                    MainNavHost(
                        settings = settings,
                        diagnostic = voiceDiagnostic,
                        missingPermissions = missingPermissions,
                        allPermissionsStatus = allPermissionsStatus,
                        chatRefreshTrigger = chatRefreshTrigger,
                        onOpenAssistantSettings = { openAssistantSettings() },
                        onRefreshDiagnostics = {
                            initializeTTS() // Re-init on manual refresh
                            refreshAllPermissionsStatus()
                        },
                        onRequestPermissions = { permissions ->
                            val ungranted = permissions.filter {
                                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (ungranted.isNotEmpty()) {
                                permissionLauncher.launch(ungranted.toTypedArray())
                            }
                        },
                        onOpenAppSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    private fun initializeTTS() {
        tts?.shutdown()
        Log.e("MainActivity", "Initializing TTS with Google Engine priority...")
        tts = TextToSpeech(this, this, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    override fun onInit(status: Int) {
        Log.e("MainActivity", "TTS onInit status=$status")
        if (status == TextToSpeech.SUCCESS) {
            runDiagnostics()
        } else {
            Log.e("MainActivity", "Google TTS failed, trying default...")
            tts = TextToSpeech(this) { 
                runDiagnostics()
            }
        }
    }

    private fun runDiagnostics() {
        voiceDiagnostic = VoiceDiagnostics(this).performFullCheck(tts)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshMissingPermissions() {
        val missing = mutableListOf<PermissionInfo>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.RECORD_AUDIO, R.string.permission_record_audio, R.string.permission_record_audio_desc))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.POST_NOTIFICATIONS, R.string.permission_post_notifications, R.string.permission_post_notifications_desc))
        }
        missingPermissions = missing
    }

    private fun refreshAllPermissionsStatus() {
        val list = mutableListOf<PermissionStatusInfo>()
        list.add(PermissionStatusInfo(getString(R.string.permission_record_audio), ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
        list.add(PermissionStatusInfo(getString(R.string.permission_camera), ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
        list.add(PermissionStatusInfo(getString(R.string.permission_location_fine), ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED))
        list.add(PermissionStatusInfo(getString(R.string.permission_location_coarse), ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(PermissionStatusInfo(getString(R.string.permission_notifications), ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        }
        allPermissionsStatus = list
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.could_not_open_settings), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleHotwordService(enabled: Boolean) {
        if (enabled) {
            // Check that a backend connection is configured before enabling wakeword.
            // The voice interaction session requires gateway or HTTP to communicate with the AI.
            val runtime = (applicationContext as OpenClawApplication).nodeRuntime
            val isConnectionConfigured = if (settings.connectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                runtime.manualHost.value.isNotBlank()
            } else {
                settings.httpUrl.isNotBlank()
            }
            if (!isConnectionConfigured) {
                Toast.makeText(this, getString(R.string.wakeword_requires_connection_error), Toast.LENGTH_LONG).show()
                return
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                // Show rationale dialog before requesting permission
                showPermissionRationaleDialog()
            } else {
                // First-time request: launch directly
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            settings.hotwordEnabled = false
            HotwordService.stop(this)
            Toast.makeText(this, getString(R.string.hotword_stopped), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionRationaleDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_rationale_title))
            .setMessage(getString(R.string.mic_permission_rationale_message))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_permanently))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    fun isAssistantActive(): Boolean {
        return try {
            Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        chatRefreshTrigger++
        refreshMissingPermissions()
        refreshAllPermissionsStatus()

        // Detect app language change made in SettingsActivity and recreate to apply it.
        // attachBaseContext() ensures the new instance always gets the correct locale,
        // so a single recreate() is always sufficient (no guard needed).
        // When savedTag is blank (System Default), compare against Locale.getDefault()
        // so that reverting from e.g. zh-CN back to system locale also triggers recreate.
        val savedTag = SettingsRepository.getInstance(this).appLanguage.trim()
        val displayedLanguage = resources.configuration.locales[0].language
        val expectedLanguage = if (savedTag.isNotBlank()) {
            java.util.Locale.forLanguageTag(savedTag).language
        } else {
            java.util.Locale.getDefault().language
        }
        if (displayedLanguage != expectedLanguage) {
            recreate()
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}

@Composable
fun MainNavHost(
    settings: SettingsRepository,
    diagnostic: VoiceDiagnostic?,
    missingPermissions: List<PermissionInfo>,
    allPermissionsStatus: List<PermissionStatusInfo>,
    chatRefreshTrigger: Int,
    onOpenAssistantSettings: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    var selectedTab by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save    = { it.route },
            restore = { route ->
                when (route) {
                    AppTab.Chat.route     -> AppTab.Chat
                    AppTab.Canvas.route   -> AppTab.Canvas
                    AppTab.Settings.route -> AppTab.Settings
                    else                  -> AppTab.Home
                }
            }
        )
    ) { mutableStateOf<AppTab>(AppTab.Home) }
    var showSettingsCredits by rememberSaveable { mutableStateOf(false) }

    val sessionListViewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.Chat) sessionListViewModel.refreshSessions()
    }
    LaunchedEffect(chatRefreshTrigger) {
        sessionListViewModel.refreshSessions()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.ALL.forEach { tab ->
                    NavigationBarItem(
                        selected  = selectedTab == tab,
                        onClick   = { selectedTab = tab },
                        icon      = { Icon(tab.icon, contentDescription = null) },
                        label     = { Text(stringResource(tab.labelResId)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                AppTab.Home -> MainScreen(
                    settings             = settings,
                    diagnostic           = diagnostic,
                    missingPermissions   = missingPermissions,
                    allPermissionsStatus = allPermissionsStatus,
                    onOpenSettings       = { selectedTab = AppTab.Settings },
                    onOpenAssistantSettings = onOpenAssistantSettings,
                    onRefreshDiagnostics = onRefreshDiagnostics,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings    = onOpenAppSettings
                )
                AppTab.Chat -> {
                    val sessions        by sessionListViewModel.allSessions.collectAsState()
                    val agentListResult by sessionListViewModel.agentList.collectAsState()
                    val context = LocalContext.current
                    SessionListScreen(
                        sessions            = sessions,
                        isGatewayConfigured = sessionListViewModel.isGatewayConfigured,
                        isHttpConfigured    = sessionListViewModel.isHttpConfigured,
                        agents              = agentListResult?.agents ?: emptyList(),
                        defaultAgentId      = agentListResult?.defaultId ?: "main",
                        onBack              = { selectedTab = AppTab.Home },
                        onSessionClick      = { session ->
                            sessionListViewModel.setUseNodeChat(session.isGateway)
                            context.startActivity(
                                Intent(context, ChatActivity::class.java).apply {
                                    putExtra(ChatActivity.EXTRA_SESSION_ID, session.id)
                                }
                            )
                        },
                        onCreateSession     = { name, isGateway, agentId ->
                            sessionListViewModel.setUseNodeChat(isGateway)
                            sessionListViewModel.createSession(name, isGateway, agentId) { sessionId, _ ->
                                context.startActivity(
                                    Intent(context, ChatActivity::class.java).apply {
                                        putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId)
                                        putExtra(ChatActivity.EXTRA_SESSION_TITLE, name)
                                    }
                                )
                            }
                        },
                        onDeleteSession     = { id, gw -> sessionListViewModel.deleteSession(id, gw) },
                        onRenameSession     = { id, nm, gw -> sessionListViewModel.renameSession(id, nm, gw) }
                    )
                }
                AppTab.Canvas -> {
                    val context = LocalContext.current
                    val (canvasController, nodeRuntime) = remember(context.applicationContext) {
                        val nr = (context.applicationContext as OpenClawApplication).nodeRuntime
                        nr.canvas to nr
                    }
                    com.openclaw.assistant.ui.CanvasScreen(
                        canvasController = canvasController,
                        nodeRuntime = nodeRuntime,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AppTab.Settings -> {
                    if (showSettingsCredits) {
                        com.openclaw.assistant.ui.settings.CreditsScreen(
                            settings = settings,
                            onBack   = { showSettingsCredits = false }
                        )
                    } else {
                        val context = LocalContext.current
                        SettingsScreen(
                            settings  = settings,
                            onSave    = {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.saved),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            onBack    = { selectedTab = AppTab.Home },
                            onCredits = { showSettingsCredits = true }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: SettingsRepository,
    diagnostic: VoiceDiagnostic?,
    missingPermissions: List<PermissionInfo> = emptyList(),
    allPermissionsStatus: List<PermissionStatusInfo> = emptyList(),
    onOpenSettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit = {},
    onOpenAppSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }
    var isConfigured by remember { mutableStateOf(settings.isConfigured()) }
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    val displayWakeWord = settings.getWakeWordDisplayName()
    var isAssistantSet by remember { mutableStateOf((context as? MainActivity)?.isAssistantActive() ?: false) }
    val nodeConnected by runtime.isConnected.collectAsState()
    val nodeStatusText by runtime.statusText.collectAsState()
    var showTroubleshooting by rememberSaveable { mutableStateOf(false) }
    var showHowToUse by rememberSaveable { mutableStateOf(false) }
    var showLocationInfo by rememberSaveable { mutableStateOf(false) }
    
    var showScreenCaptureDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val smsEnabled by runtime.smsEnabled.collectAsState()
    val screenRecordActive by runtime.screenRecordActive.collectAsState()
    val lastCapabilityError by runtime.lastCapabilityError.collectAsState()
    LaunchedEffect(lastCapabilityError) {
        val err = lastCapabilityError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        runtime.clearCapabilityError()
    }
    // Permission error observation
    val missingScopeError by runtime.missingScopeError.collectAsState()
    val isPairingRequired by runtime.isPairingRequired.collectAsState()
    val isOperatorOffline by runtime.isOperatorOffline.collectAsState()
    val deviceId = runtime.deviceId
    val displayName by runtime.displayName.collectAsState()
    val pendingGatewayTrust by runtime.pendingGatewayTrust.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isConfigured = settings.isConfigured()
                hotwordEnabled = settings.hotwordEnabled
                isAssistantSet = (context as? MainActivity)?.isAssistantActive() ?: false
                onRefreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check for updates on startup
    LaunchedEffect(Unit) {
        try {
            val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val info = com.openclaw.assistant.utils.UpdateChecker.checkUpdate(versionName ?: "")
            if (info != null && info.hasUpdate) {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.update_available, info.latestVersion),
                    actionLabel = context.getString(R.string.update_action),
                    duration = SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            // Ignore startup update check errors
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/R5R51S97C4"))
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            // No browser available; ignore
                        }
                    }) {
                        Icon(Icons.Default.VolunteerActivism, contentDescription = stringResource(R.string.credits_support_kofi_button))
                    }
                    IconButton(onClick = { showHowToUse = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.how_to_use))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (pendingGatewayTrust != null) {
                GatewayTrustDialog(
                    prompt = pendingGatewayTrust!!,
                    onAccept = { runtime.acceptGatewayTrustPrompt() },
                    onDecline = { runtime.declineGatewayTrustPrompt() }
                )
            }
            // Show alert if missing scope error is present
            if (missingScopeError != null) {
                MissingScopeCard(error = missingScopeError!!, onClick = onOpenSettings)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Show pairing required banner
            if (isPairingRequired && deviceId != null) {
                PairingRequiredCard(deviceId = deviceId, displayName = displayName)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Show operator offline warning (node connected but operator not authorized)
            if (isOperatorOffline && deviceId != null) {
                OperatorOfflineCard(deviceId = deviceId, displayName = displayName)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // === SYSTEM STATUS DASHBOARD ===
            val displayStatusText = when (nodeStatusText) {
                "Operator Online (Node Offline)" -> stringResource(R.string.status_operator_online_node_offline)
                "Node Online (Operator Offline)" -> stringResource(R.string.status_node_online_operator_offline)
                "Offline" -> stringResource(R.string.status_offline)
                else -> nodeStatusText
            }
            
            SystemStatusCard(
                connected = nodeConnected,
                statusText = displayStatusText,
                onConnect = { runtime.connectManual() },
                onDisconnect = { runtime.disconnect() },
                onOpenSettings = onOpenSettings
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // === CAPABILITIES CONTROLS ===
            Text(
                text = stringResource(R.string.permissions_for_openclaw_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            val cameraEnabled by runtime.cameraEnabled.collectAsState()
            val locationMode by runtime.locationMode.collectAsState()
            val locationPrecise by runtime.locationPreciseEnabled.collectAsState()
            
            // === CAPABILITIES: all 4 in one row ===
            val hasTelephony = remember { runtime.sms.hasTelephonyFeature() }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Camera Toggle
                CapabilityCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.capability_camera),
                    isActive = cameraEnabled,
                    onClick = {
                        if (!cameraEnabled) {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                runtime.setCameraEnabled(true)
                            } else {
                                onRequestPermissions(listOf(Manifest.permission.CAMERA))
                            }
                        } else {
                            runtime.setCameraEnabled(false)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                // Location Toggle (ON/OFF)
                val locationStatusText = when {
                    locationMode == LocationMode.Off -> stringResource(R.string.location_off)
                    locationPrecise -> stringResource(R.string.location_precise)
                    else -> stringResource(R.string.location_coarse)
                }
                CapabilityCard(
                    icon = Icons.Default.LocationOn,
                    label = stringResource(R.string.capability_location),
                    isActive = locationMode != LocationMode.Off,
                    statusText = locationStatusText,
                    onClick = {
                        if (locationMode == LocationMode.Off) {
                            val coarseGranted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            val fineGranted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            
                            if (coarseGranted || fineGranted) {
                                runtime.setLocationMode(LocationMode.WhileUsing)
                                runtime.setLocationPreciseEnabled(fineGranted)
                            } else {
                                onRequestPermissions(listOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ))
                            }
                        } else {
                            runtime.setLocationMode(LocationMode.Off)
                        }
                    },
                    onLongClick = { showLocationInfo = true },
                    modifier = Modifier.weight(1f)
                )

                // SMS Toggle
                if (hasTelephony) {
                    CapabilityCard(
                        icon = Icons.Default.Sms,
                        label = stringResource(R.string.capability_sms),
                        isActive = smsEnabled,
                        onClick = {
                            if (smsEnabled) {
                                runtime.setSmsEnabled(false)
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.SEND_SMS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    runtime.setSmsEnabled(true)
                                } else {
                                    onRequestPermissions(listOf(Manifest.permission.SEND_SMS))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Screen Capture Toggle
                CapabilityCard(
                    icon = if (screenRecordActive) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                    label = stringResource(R.string.capability_screen),
                    isActive = screenRecordActive,
                    onClick = { showScreenCaptureDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (missingPermissions.isNotEmpty()) {
                PermissionStatusCard(
                    missingPermissions = missingPermissions,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (diagnostic != null || allPermissionsStatus.isNotEmpty()) {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_title),
                    initiallyExpanded = diagnostic?.let { it.sttStatus != DiagnosticStatus.READY || it.ttsStatus != DiagnosticStatus.READY } ?: false
                ) {
                    if (diagnostic != null) {
                        DiagnosticPanel(diagnostic, onRefreshDiagnostics)
                    }
                    if (diagnostic != null && allPermissionsStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (allPermissionsStatus.isNotEmpty()) {
                        PermissionDiagnosticsPanel(allPermissionsStatus, onRefreshDiagnostics)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Quick Actions
            Text(text = stringResource(R.string.activation_methods), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))

            val displayWakeWord = settings.getWakeWordDisplayName()

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactActionCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.home_button),
                    description = if (isAssistantSet) "ON" else "OFF",
                    isActive = isAssistantSet,
                    onClick = onOpenAssistantSettings,
                    showInfoIcon = true,
                    onInfoClick = { showTroubleshooting = true }
                )
                CompactActionCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = if (hotwordEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    title = stringResource(R.string.wake_word),
                    description = "$displayWakeWord (${if (hotwordEnabled) "ON" else "OFF"})",
                    showSwitch = true,
                    switchValue = hotwordEnabled,
                    onSwitchChange = { enabled ->
                        (context as? MainActivity)?.toggleHotwordService(enabled)
                        hotwordEnabled = enabled
                    },
                    isActive = hotwordEnabled,
                    showInfoIcon = false
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            val chatContext = LocalContext.current
            Button(
                onClick = { chatContext.startActivity(Intent(chatContext, SessionListActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.open_chat), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            // Legacy warning if ONLY legacy is configured and fails
            if (!isConfigured && !nodeConnected) {
                Spacer(modifier = Modifier.height(24.dp))
                WarningCard(message = stringResource(R.string.setup_required_hint), onClick = onOpenSettings)
            }
        }
    }

    if (showScreenCaptureDialog) {
        AlertDialog(
            onDismissRequest = { showScreenCaptureDialog = false },
            title = { Text(stringResource(R.string.screen_capture_title)) },
            text = { Text(stringResource(R.string.screen_capture_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showScreenCaptureDialog = false
                    if (screenRecordActive) {
                        runtime.setScreenRecordActive(false)
                    } else {
                        runtime.setScreenRecordActive(true)
                    }
                }) {
                    Text(stringResource(if (screenRecordActive) R.string.stop else R.string.start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenCaptureDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showTroubleshooting) TroubleshootingDialog(onDismiss = { showTroubleshooting = false })
    if (showHowToUse) HowToUseDialog(displayWakeWord = displayWakeWord, onDismiss = { showHowToUse = false })
    if (showLocationInfo) {
        AlertDialog(
            onDismissRequest = { showLocationInfo = false },
            title = { Text(stringResource(R.string.location_info_title)) },
            text = { Text(stringResource(R.string.location_info_message)) },
            confirmButton = {
                TextButton(onClick = { showLocationInfo = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }
}

@Composable
fun OperatorOfflineCard(deviceId: String, displayName: String = "") {
    val context = LocalContext.current
    val command = context.getString(R.string.operator_offline_command, deviceId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.operator_offline_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.operator_offline_message),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Command", command)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.operator_offline_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.operator_offline_copy), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SystemStatusCard(
    connected: Boolean,
    statusText: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val backgroundColor = if (connected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    val contentColor = if (connected) Color(0xFF1B5E20) else Color(0xFFB71C1C)
    val statusLabelColor = if (connected) Color(0xFF2E7D32) else Color(0xFFC62828)
    val statusDotColor = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = contentColor.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = contentColor,
                            containerColor = Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, contentColor.copy(alpha = 0.5f))
                    ) {
                        Text(stringResource(R.string.disconnect))
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = Color.White
                        )
                    ) {
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CapabilityCard(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .height(72.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 9.sp,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
fun DiagnosticPanel(diagnostic: VoiceDiagnostic, onRefresh: () -> Unit) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostic_engines), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DiagnosticItem(label = "In: ${diagnostic.sttEngine?.take(10) ?: "Def"}", status = diagnostic.sttStatus, modifier = Modifier.weight(1f))
                DiagnosticItem(label = "Out: ${diagnostic.ttsEngine?.split('.')?.lastOrNull() ?: "null"}", status = diagnostic.ttsStatus, modifier = Modifier.weight(1f))
            }
            if (diagnostic.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                diagnostic.suggestions.forEach { SuggestionItem(it) }
            }
        }
    }
}

@Composable
fun PermissionDiagnosticsPanel(allPermissionsStatus: List<PermissionStatusInfo>, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostic_app_permissions), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            allPermissionsStatus.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = if (perm.isGranted) Color(0xFF4CAF50) else Color(0xFFF44336)
                    val icon = if (perm.isGranted) Icons.Default.Check else Icons.Default.Close
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(perm.permissionName, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (perm.isGranted) stringResource(R.string.permission_status_granted)
                        else stringResource(R.string.permission_status_denied),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticItem(label: String, status: DiagnosticStatus, modifier: Modifier = Modifier) {
    val color = when (status) { DiagnosticStatus.READY -> Color(0xFF4CAF50); DiagnosticStatus.WARNING -> Color(0xFFFFC107); DiagnosticStatus.ERROR -> Color(0xFFF44336) }
    val icon = when (status) { DiagnosticStatus.READY -> Icons.Default.Check; DiagnosticStatus.WARNING -> Icons.Default.Info; DiagnosticStatus.ERROR -> Icons.Default.Error }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SuggestionItem(suggestion: com.openclaw.assistant.speech.diagnostics.DiagnosticSuggestion) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = suggestion.message, modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp)
            if (suggestion.actionLabel != null && suggestion.intent != null) {
                TextButton(onClick = { try { context.startActivity(suggestion.intent) } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.state_error), Toast.LENGTH_SHORT).show() } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(suggestion.actionLabel, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun CompactActionCard(modifier: Modifier = Modifier, icon: ImageVector, title: String, description: String, isActive: Boolean = false, onClick: (() -> Unit)? = null, showSwitch: Boolean = false, switchValue: Boolean = false, onSwitchChange: ((Boolean) -> Unit)? = null, showInfoIcon: Boolean = false, onInfoClick: (() -> Unit)? = null) {
    Card(modifier = modifier, onClick = { onClick?.invoke() }, enabled = onClick != null && !showSwitch, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    if (showInfoIcon) Icon(imageVector = Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).clickable { onInfoClick?.invoke() })
                    if (showSwitch) Switch(checked = switchValue, onCheckedChange = onSwitchChange, modifier = Modifier.scale(0.8f).offset(y = (-8).dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(text = description, fontSize = 12.sp, color = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun MissingScopeCard(error: String, onClick: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), 
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error, 
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_error_title), 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.permission_error_desc), 
                        fontSize = 13.sp, 
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                    )
                    if (!expanded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.permission_error_action), 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                // Fix Request Section
                Text(
                    text = stringResource(R.string.fix_request_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                val fixMessage = stringResource(R.string.fix_request_message)
                Text(
                    text = fixMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(context, ChatActivity::class.java).apply {
                            putExtra("EXTRA_PREFILL_TEXT", fixMessage)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_ask_ai))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.technical_details),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Error", error)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.error_copied, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_copy_error))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onClick, // This now just toggles via the card click, wait... user might want to open settings. 
                        // The `onClick` passed to MissingScopeCard was originally to open settings?
                        // Let's check where it's called.
                        // In MainScreen: MissingScopeCard(error = it) { settingsIntent... }
                        // So onClick DOES open settings.
                        // My previous edit in step 284 changed the Card's onClick to expansion toggle.
                        // So I need to make sure the "Open Settings" button calls the `onClick` param.
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    missingPermissions: List<PermissionInfo>,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.permissions_missing_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            missingPermissions.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(perm.nameResId),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = stringResource(perm.descResId),
                            fontSize = 12.sp,
                            color = Color(0xFF795548)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.open_settings), color = Color(0xFFE65100))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onRequestPermissions(missingPermissions.map { it.permission }) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
fun WarningCard(message: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = message, color = Color(0xFFE65100), modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFFF9800))
        }
    }
}



@Composable
fun UsageStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text(text = number, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun HowToUseDialog(displayWakeWord: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, 
        title = { Text(stringResource(R.string.how_to_use)) }, 
        text = { 
            Column { 
                (1..4).forEach { i ->
                    val resId = context.resources.getIdentifier("step_$i", "string", context.packageName)
                    val text = if (i == 1) {
                        stringResource(resId, displayWakeWord)
                    } else {
                        stringResource(resId)
                    }
                    UsageStep(i.toString(), text) 
                } 
            } 
        }, 
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } }
    )
}

@Composable
fun TroubleshootingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.assist_gesture_not_working)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.troubleshooting_intro), fontSize = 14.sp)
            listOf("circle_to_search", "gesture_navigation", "google_app_setting", "refresh_binding").forEach { key -> 
                val titleId = context.resources.getIdentifier("${key}_title", "string", context.packageName)
                val descId = context.resources.getIdentifier("${key}_desc", "string", context.packageName)
                BulletPoint(stringResource(titleId), stringResource(descId)) 
            }
            Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { context.startService(Intent(context, OpenClawAssistantService::class.java).apply { action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT }) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text(stringResource(R.string.debug_force_start)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } })
}

@Composable
fun BulletPoint(title: String, desc: String) {
    Column { Text("• $title", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(desc, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp)) }
}
