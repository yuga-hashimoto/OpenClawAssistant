package com.openclaw.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.OpenClawAssistantService
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)
        
        checkPermissions()

        setContent {
            OpenClawAssistantTheme {
                MainScreen(
                    settings = settings,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAssistantSettings = { openAssistantSettings() },
                    onToggleHotword = { enabled -> toggleHotwordService(enabled) }
                )
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleHotwordService(enabled: Boolean) {
        settings.hotwordEnabled = enabled
        if (enabled) {
            HotwordService.start(this)
            Toast.makeText(this, "Hotword detection started", Toast.LENGTH_SHORT).show()
        } else {
            HotwordService.stop(this)
            Toast.makeText(this, "Hotword detection stopped", Toast.LENGTH_SHORT).show()
        }
    }

    fun isAssistantActive(): Boolean {
        return try {
            val setting = Settings.Secure.getString(contentResolver, "assistant")
            setting?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: SettingsRepository,
    onOpenSettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onToggleHotword: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var isConfigured by remember { mutableStateOf(settings.isConfigured()) }
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    var isAssistantSet by remember { mutableStateOf((context as? MainActivity)?.isAssistantActive() ?: false) }
    var showTroubleshooting by remember { mutableStateOf(false) }
    var showHowToUse by remember { mutableStateOf(false) }
    
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isConfigured = settings.isConfigured()
                hotwordEnabled = settings.hotwordEnabled
                isAssistantSet = (context as? MainActivity)?.isAssistantActive() ?: false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw Assistant") },
                actions = {
                    IconButton(onClick = { showHowToUse = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status card
            StatusCard(isConfigured = isConfigured)
            
            Spacer(modifier = Modifier.height(24.dp))

            // Quick actions - 2 cards side by side
            Text(
                text = "Activation Methods",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Home button long press
                CompactActionCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    icon = Icons.Default.Home,
                    title = "Home Button",
                    description = if (isAssistantSet) "Active" else "Not Set",
                    isActive = isAssistantSet,
                    onClick = onOpenAssistantSettings,
                    showInfoIcon = true,
                    onInfoClick = { showTroubleshooting = true }
                )

                // Hotword
                CompactActionCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    icon = Icons.Default.Mic,
                    title = settings.getWakeWordDisplayName(),
                    description = if (hotwordEnabled) "Active" else "Disabled",
                    isActive = hotwordEnabled,
                    showSwitch = true,
                    switchValue = hotwordEnabled,
                    onSwitchChange = { enabled ->
                        if (enabled && !isConfigured) {
                            return@CompactActionCard
                        }
                        hotwordEnabled = enabled
                        onToggleHotword(enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Open Chat - Large button
            val chatContext = LocalContext.current
            Button(
                onClick = {
                    val intent = Intent(chatContext, ChatActivity::class.java)
                    chatContext.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Open Chat", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Configuration warning
            if (!isConfigured) {
                WarningCard(
                    message = "Please configure Webhook URL",
                    onClick = onOpenSettings
                )
            }
        }
    }

    if (showTroubleshooting) {
        TroubleshootingDialog(onDismiss = { showTroubleshooting = false })
    }

    if (showHowToUse) {
        HowToUseDialog(onDismiss = { showHowToUse = false })
    }
}

@Composable
fun StatusCard(isConfigured: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) Color(0xFF4CAF50) else Color(0xFFFFC107)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = if (isConfigured) "Ready" else "Setup Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (isConfigured) "Connected to OpenClaw" else "Please configure Webhook URL",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionText: String? = null,
    onClick: (() -> Unit)? = null,
    showSwitch: Boolean = false,
    switchValue: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null,
    isHighlight: Boolean = false,
    showInfo: Boolean = false,
    onInfoClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onClick?.invoke() },
        enabled = onClick != null && !showSwitch
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = if (isHighlight && !showSwitch) MaterialTheme.colorScheme.error else Color.Gray
                )
            }

            if (showInfo) {
                IconButton(onClick = onInfoClick ?: {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "Help",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (showSwitch) {
                Switch(
                    checked = switchValue,
                    onCheckedChange = onSwitchChange
                )
            } else if (actionText != null) {
                TextButton(onClick = { onClick?.invoke() }) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
fun UsageCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            UsageStep(number = "1", text = "Long press Home or say Wake Word")
            UsageStep(number = "2", text = "Ask your question or request")
            UsageStep(number = "3", text = "OpenClaw reads the response aloud")
            UsageStep(number = "4", text = "Continue conversation (session maintained)")
        }
    }
}

@Composable
fun UsageStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun WarningCard(message: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                color = Color(0xFFE65100),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFFF9800)
            )
        }
    }
}

@Composable
fun CompactActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    showSwitch: Boolean = false,
    switchValue: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null,
    showInfoIcon: Boolean = false,
    onInfoClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        onClick = { onClick?.invoke() },
        enabled = onClick != null && !showSwitch,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                    if (showInfoIcon) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "Help",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onInfoClick?.invoke() }
                        )
                    }
                    if (showSwitch) {
                        Switch(
                            checked = switchValue,
                            onCheckedChange = onSwitchChange,
                            modifier = Modifier
                                .scale(0.8f)
                                .offset(y = (-8).dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            Text(
                text = description,
                fontSize = 12.sp,
                color = if (isActive) Color(0xFF4CAF50) else Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun HowToUseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to Use") },
        text = {
            Column {
                UsageStep(number = "1", text = "Long press Home or say Wake Word")
                UsageStep(number = "2", text = "Ask your question or request")
                UsageStep(number = "3", text = "OpenClaw reads the response aloud")
                UsageStep(number = "4", text = "Continue conversation (session maintained)")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
fun TroubleshootingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assist Gesture Not Working?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Even if set as default, some system features might override the long-press gesture.",
                    fontSize = 14.sp
                )
                
                BulletPoint(
                    title = "Circle to Search (Overrides Long-Press)", 
                    desc = "If holding the home button triggers a search screen:\n" +
                           "• Pixel: Settings > System > Navigation mode > Tap Gear icon > Turn off Circle to Search.\n" +
                           "• Samsung: Settings > Display > Navigation bar > Turn off Circle to Search."
                )
                
                BulletPoint(
                    title = "Gesture Navigation (Corner Swipe)", 
                    desc = "If you don't have a home button (swipe navigation), swipe up diagonally from either the bottom-left or bottom-right corner to launch the assistant."
                )
                
                BulletPoint(
                    title = "Google App Setting", 
                    desc = "Open Google App > Tap Profile > Settings > Google Assistant > General > Turn off 'Google Assistant' if it still interferes."
                )

                BulletPoint(
                    title = "Refresh Binding", 
                    desc = "If status is 'Active' but it still won't work, try changing 'Digital assistant app' to 'None' and then back to 'OpenClaw Assistant'."
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                Button(
                    onClick = {
                        val intent = Intent(context, OpenClawAssistantService::class.java).apply {
                            action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT
                        }
                        context.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Debug: Force Start Session")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
fun BulletPoint(title: String, desc: String) {
    Column {
        Text("• $title", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(desc, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp))
    }
}
