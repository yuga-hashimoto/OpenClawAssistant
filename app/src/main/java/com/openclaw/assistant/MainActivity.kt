package com.openclaw.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "権限が必要です", Toast.LENGTH_SHORT).show()
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
            // アシスタントアプリ選択画面を開く
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            // フォールバック
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "設定画面を開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleHotwordService(enabled: Boolean) {
        settings.hotwordEnabled = enabled
        if (enabled) {
            if (settings.picovoiceAccessKey.isBlank()) {
                Toast.makeText(this, "Picovoice Access Keyを設定してください", Toast.LENGTH_SHORT).show()
                settings.hotwordEnabled = false
                return
            }
            HotwordService.start(this)
            Toast.makeText(this, "ホットワード検知を開始しました", Toast.LENGTH_SHORT).show()
        } else {
            HotwordService.stop(this)
            Toast.makeText(this, "ホットワード検知を停止しました", Toast.LENGTH_SHORT).show()
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
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    val isConfigured = settings.isConfigured()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw Assistant") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
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
            // ステータスカード
            StatusCard(isConfigured = isConfigured)
            
            Spacer(modifier = Modifier.height(24.dp))

            // クイックアクション
            Text(
                text = "起動方法",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            // ホームボタン長押し
            ActionCard(
                icon = Icons.Default.Home,
                title = "ホームボタン長押し",
                description = "システムアシスタントとして設定が必要です",
                actionText = "設定を開く",
                onClick = onOpenAssistantSettings
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ホットワード
            ActionCard(
                icon = Icons.Default.Mic,
                title = "ウェイクワード「OpenClaw」",
                description = if (hotwordEnabled) "「OpenClaw」と呼んでください" else "タップで有効化",
                showSwitch = true,
                switchValue = hotwordEnabled,
                onSwitchChange = { enabled ->
                    hotwordEnabled = enabled
                    onToggleHotword(enabled)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 使い方
            Text(
                text = "使い方",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            UsageCard()

            Spacer(modifier = Modifier.height(24.dp))

            // 設定未完了の警告
            if (!isConfigured) {
                WarningCard(
                    message = "Webhook URLを設定してください",
                    onClick = onOpenSettings
                )
            }
        }
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
                    text = if (isConfigured) "準備完了" else "設定が必要",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (isConfigured) "OpenClawと連携可能です" else "Webhook URLを設定してください",
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
    onSwitchChange: ((Boolean) -> Unit)? = null
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
                    color = Color.Gray
                )
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
            UsageStep(number = "1", text = "ホームボタン長押し または ウェイクワード")
            UsageStep(number = "2", text = "質問や依頼を話す")
            UsageStep(number = "3", text = "OpenClawが応答を読み上げ")
            UsageStep(number = "4", text = "連続会話可能（セッション維持）")
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
