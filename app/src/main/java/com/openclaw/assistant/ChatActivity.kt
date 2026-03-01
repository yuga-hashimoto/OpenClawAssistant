package com.openclaw.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

import android.util.Log
import android.widget.Toast
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.ui.components.MarkdownText
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.activity.compose.rememberLauncherForActivityResult
import com.openclaw.assistant.ui.components.PairingRequiredCard
import com.openclaw.assistant.ui.chat.ChatUiState
import com.openclaw.assistant.ui.chat.ChatViewModel
import com.openclaw.assistant.ui.chat.PendingFileAttachment
import com.openclaw.assistant.ui.chat.ChatMessage
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.content.ContentResolver

import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.GatewayTrustDialog

private const val TAG = "ChatActivity"

class ChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "EXTRA_SESSION_TITLE"
    }

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var settings: SettingsRepository
    private var hasResumedOnce = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                showPermissionSettingsDialog()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
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

        // Select specific session if provided via Intent (must be before setContent)
        val extraTitle = intent.getStringExtra(EXTRA_SESSION_TITLE)
        intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
            viewModel.selectSessionOnStart(sessionId, extraTitle)
        }

        // Initialize TTS with Activity context (important for MIUI!)
        // Try Google TTS first for better compatibility on Chinese ROMs
        initializeTTS()

        // Request Microphone permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            OpenClawAssistantTheme {
                val uiState by viewModel.uiState.collectAsState()
                val allSessions by viewModel.allSessions.collectAsState()
                val currentSessionId by viewModel.currentSessionId.collectAsState()
                val initialSessionTitle by viewModel.initialSessionTitle.collectAsState()
                val prefillText = intent.getStringExtra("EXTRA_PREFILL_TEXT") ?: ""

                ChatScreen(
                    initialText = prefillText,
                    uiState = uiState,
                    allSessions = allSessions,
                    currentSessionId = currentSessionId,
                    initialSessionTitle = initialSessionTitle,
                    onSendMessage = { viewModel.sendMessage(it) },
                    onStartListening = {
                        Log.e(TAG, "onStartListening called, permission=${checkPermission()}")
                        if (checkPermission()) {
                            viewModel.startListening()
                        } else {
                            requestMicPermissionForListening()
                        }
                    },
                    onStopListening = { viewModel.stopListening() },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    onInterruptAndListen = {
                        if (checkPermission()) {
                            viewModel.interruptAndListen()
                        }
                    },
                    onBack = { finish() },
                    onAgentSelected = { viewModel.setAgent(it) },
                    onAcceptGatewayTrust = { viewModel.acceptGatewayTrust() },
                    onDeclineGatewayTrust = { viewModel.declineGatewayTrust() },
                    onAttachFiles = { viewModel.addAttachments(it) },
                    onRemoveAttachment = { viewModel.removeAttachment(it) }
                )
            }
        }
    }

    private fun initializeTTS() {
        Log.d(TAG, "Initializing TTSManager...")
        // Use new TTSManager through ViewModel
        viewModel.initializeTTS()
    }

    override fun onResume() {
        super.onResume()
        // Pause hotword detection while this activity holds the mic.
        // setPackage is required to reach NodeForegroundService (RECEIVER_NOT_EXPORTED).
        sendBroadcast(Intent(HotwordService.ACTION_PAUSE_HOTWORD).apply { setPackage(packageName) })
        // Refresh chat history in NodeChat mode to show any responses that arrived
        // while the screen was away (e.g., after returning from the session list).
        // Skip on first resume (right after onCreate) since loadChat bootstrap is already running.
        if (hasResumedOnce) {
            viewModel.refreshChatIfNeeded()
        }
        hasResumedOnce = true
    }

    override fun onPause() {
        super.onPause()
        // Only stop listening if we are NOT in an active voice conversation.
        // During voice interaction (listening/speaking/thinking), keep the session alive
        // even when the screen turns off. The WakeLock in ChatViewModel keeps the CPU alive.
        if (!viewModel.isVoiceSessionActive()) {
            viewModel.stopListening()
            // Resume hotword detection now that the mic will be released.
            // setPackage is required to reach NodeForegroundService (RECEIVER_NOT_EXPORTED).
            sendBroadcast(Intent(HotwordService.ACTION_RESUME_HOTWORD).apply { setPackage(packageName) })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // TTSManager is managed by ViewModel
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicPermissionForListening() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (!checkPermission()) {
            // First-time request or permanently denied
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showPermissionSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_required))
            .setMessage(getString(R.string.mic_permission_denied_permanently))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}

sealed interface ChatListItem {
    data class DateSeparator(val dateText: String) : ChatListItem
    data class MessageItem(val message: ChatMessage) : ChatListItem
}

private fun getFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return it.getString(nameIndex)
                }
            }
        }
    }
    return uri.path?.let { path ->
        val cut = path.lastIndexOf('/')
        if (cut != -1) path.substring(cut + 1) else path
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    initialText: String = "",
    uiState: ChatUiState,
    allSessions: List<com.openclaw.assistant.data.local.entity.SessionEntity>,
    currentSessionId: String?,
    initialSessionTitle: String? = null,
    onSendMessage: (String) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    onInterruptAndListen: () -> Unit,
    onBack: () -> Unit,
    onAgentSelected: (String?) -> Unit = {},
    onAcceptGatewayTrust: () -> Unit = {},
    onDeclineGatewayTrust: () -> Unit = {},
    onAttachFiles: (List<PendingFileAttachment>) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {}
) {
    var inputText by rememberSaveable { mutableStateOf(initialText) }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val context = androidx.compose.ui.platform.LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val next = uris.take(10).mapNotNull { uri ->
                try {
                    val mimeType = resolver.getType(uri) ?: "image/jpeg"
                    val fileName = getFileName(context, uri) ?: "image.jpg"

                    // Decode, resize and compress image to avoid server stack overflow
                    // on large Base64 payloads (server regex limit ~3.5MB decoded).
                    val MAX_DIMENSION = 1024
                    val JPEG_QUALITY = 70

                    val rawBytes = resolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        input.copyTo(out)
                        out.toByteArray()
                    } ?: ByteArray(0)
                    if (rawBytes.isEmpty()) return@mapNotNull null

                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        ?: return@mapNotNull null
                    val scaledBitmap = if (bitmap.width > MAX_DIMENSION || bitmap.height > MAX_DIMENSION) {
                        val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
                        val newW = (bitmap.width * scale).toInt()
                        val newH = (bitmap.height * scale).toInt()
                        android.graphics.Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                            if (it !== bitmap) bitmap.recycle()
                        }
                    } else bitmap

                    val compressedOut = ByteArrayOutputStream()
                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, compressedOut)
                    scaledBitmap.recycle()
                    val compressedBytes = compressedOut.toByteArray()

                    val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
                    PendingFileAttachment(
                        id = uri.toString() + "#" + System.currentTimeMillis().toString(),
                        fileName = fileName,
                        mimeType = "image/jpeg",
                        base64 = base64
                    )
                } catch (e: Exception) {
                    Log.w("ChatDbg", "pickFiles: failed to process image", e)
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (next.isNotEmpty()) onAttachFiles(next)
            }
        }
    }

    // Group messages by date
    val groupedItems = remember(uiState.messages) {
        val items = mutableListOf<ChatListItem>()
        val locale = Locale.getDefault()
        val skeleton = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
        val dateFormat = java.text.SimpleDateFormat(skeleton, locale)
        var lastDate = ""
        uiState.messages.forEach { message ->
            val date = dateFormat.format(java.util.Date(message.timestamp))
            if (date != lastDate) {
                items.add(ChatListItem.DateSeparator(date))
                lastDate = date
            }
            items.add(ChatListItem.MessageItem(message))
        }
        items.reversed()
    }

    // Scroll to bottom effect (animate when size changes)
    var previousItemCount by remember { mutableIntStateOf(groupedItems.size) }
    LaunchedEffect(groupedItems.size, uiState.isThinking, uiState.isSpeaking, uiState.isPreparingSpeech, uiState.pendingToolCalls.size) {
        if (groupedItems.size > previousItemCount + 1 || previousItemCount == 0) {
            listState.scrollToItem(0)
        } else {
            listState.animateScrollToItem(0)
        }
        previousItemCount = groupedItems.size
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        val sessionTitle = allSessions.find { it.id == currentSessionId }?.title
                            ?: initialSessionTitle
                            ?: stringResource(R.string.new_chat)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    sessionTitle,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            AgentSelector(
                                agents = uiState.availableAgents,
                                selectedAgentId = uiState.selectedAgentId,
                                defaultAgentId = uiState.defaultAgentId,
                                onAgentSelected = onAgentSelected,
                                isReadOnly = uiState.isNodeChatMode
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        actionIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            bottomBar = {
                Column {
                    if (uiState.partialText.isNotBlank()) {
                        Text(
                            text = uiState.partialText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (uiState.attachments.isNotEmpty()) {
                        AttachmentsStrip(
                            attachments = uiState.attachments,
                            onRemoveAttachment = onRemoveAttachment
                        )
                    }

                    ChatInputArea(
                        value = inputText,
                        onValueChange = { inputText = it },
                        onSend = {
                            onSendMessage(inputText)
                            inputText = ""
                            keyboardController?.hide()
                        },
                        isListening = uiState.isListening,
                        isSpeaking = uiState.isSpeaking,
                        hasAttachments = uiState.attachments.isNotEmpty(),
                        onMicClick = {
                            if (uiState.isSpeaking) {
                                onInterruptAndListen()
                            } else if (uiState.isListening) {
                                onStopListening()
                            } else {
                                onStartListening()
                            }
                        },
                        onAttachClick = { pickFiles.launch("image/*") }
                    )
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Gateway TLS Trust prompt
                if (uiState.pendingGatewayTrust != null) {
                    GatewayTrustDialog(
                        prompt = uiState.pendingGatewayTrust,
                        onAccept = onAcceptGatewayTrust,
                        onDecline = onDeclineGatewayTrust
                    )
                }

                // Pairing Guidance
                if (uiState.isPairingRequired && uiState.deviceId != null) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        PairingRequiredCard(deviceId = uiState.deviceId, displayName = uiState.displayName)
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
                    ) {
                        if (uiState.pendingToolCalls.isNotEmpty()) {
                            item { PendingToolsIndicator(uiState.pendingToolCalls) }
                        }
                        if (uiState.isPreparingSpeech) {
                            item { PreparingSpeechIndicator() }
                        }
                        if (uiState.isSpeaking) {
                            item { SpeakingIndicator(onStop = onStopSpeaking) }
                        }
                        if (uiState.isThinking) {
                            item { ThinkingIndicator() }
                        }

                        items(groupedItems) { item ->
                            when (item) {
                                is ChatListItem.DateSeparator -> DateHeader(item.dateText)
                                is ChatListItem.MessageItem -> MessageBubble(message = item.message)
                            }
                        }
                    }

                    // Scroll to bottom button
                    val showScrollToBottom by remember {
                        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    ) {
                        val coroutineScope = rememberCoroutineScope()
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.scroll_to_bottom))
                        }
                    }
                }
            }
        }
}

@Composable
fun DateHeader(dateText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) Color(0xFFFFBCA3) else Color(0xFFE8F5E9) // Light green for AI
    val contentColor = Color(0xFF1E1E1E) // Dark grey for both

    // Friendly rounded shapes with tail on sender's side
    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    val timestamp = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(message.timestamp))
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = shape,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                    if (isUser) {
                        Text(
                            text = message.text,
                            color = contentColor,
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            lineHeight = 24.sp
                        )
                    } else {
                        MarkdownText(
                            markdown = message.text,
                            color = contentColor
                        )
                    }
                    if (message.attachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        for (att in message.attachments) {
                            ChatAttachmentPreview(att, contentColor)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.5f)
                        )
                        if (!isUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = contentColor.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", message.text))
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatAttachmentPreview(attachment: com.openclaw.assistant.chat.ChatMessageContent, contentColor: Color) {
    if (attachment.type == "image" && attachment.base64 != null) {
        var image by remember(attachment.base64) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        var failed by remember(attachment.base64) { mutableStateOf(false) }

        LaunchedEffect(attachment.base64) {
            failed = false
            image = withContext(Dispatchers.Default) {
                try {
                    val bytes = android.util.Base64.decode(attachment.base64, android.util.Base64.NO_WRAP)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                    bitmap.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
            if (image == null) failed = true
        }

        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = attachment.fileName ?: "Image attachment",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            )
        } else if (failed) {
            Text("Failed to load image", style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.7f))
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(contentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = "File",
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = attachment.fileName ?: "File",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.thinking),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}

@Composable
fun SpeakingIndicator(onStop: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
         Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.speaking),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onStop, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_description), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
fun PreparingSpeechIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.preparing_speech),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}

@Composable
fun PendingToolsIndicator(toolCalls: List<String>) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.running_tools),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolCalls.take(5).forEach { call ->
                    val toolName = call.split(" ").firstOrNull() ?: call
                    SuggestionChip(
                        onClick = {},
                        label = { Text(toolName, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSelector(
    agents: List<AgentInfo>,
    selectedAgentId: String?,
    defaultAgentId: String = "main",
    onAgentSelected: (String?) -> Unit,
    isReadOnly: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val effectiveId = selectedAgentId ?: defaultAgentId
    val selectedAgent = agents.find { it.id == effectiveId }

    // Determine display name
    val displayName = if (selectedAgent != null) {
        selectedAgent.name
    } else {
        if (effectiveId == "main" || effectiveId.isBlank()) stringResource(R.string.agent_default)
        else effectiveId
    }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (!isReadOnly && agents.isNotEmpty()) {
                        Modifier.clickable { expanded = true }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (!isReadOnly) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isReadOnly && agents.isNotEmpty()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                agents.forEach { agent ->
                    DropdownMenuItem(
                        text = { Text(agent.name) },
                        onClick = {
                            onAgentSelected(agent.id)
                            expanded = false
                        },
                        leadingIcon = {
                            if (agent.id == effectiveId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatInputArea(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isListening: Boolean,
    isSpeaking: Boolean = false,
    hasAttachments: Boolean = false,
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onAttachClick,
            modifier = Modifier.padding(end = 4.dp).size(40.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.AttachFile,
                contentDescription = "Attach file",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text(stringResource(R.string.ask_hint)) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            // keyboardActions removed to allow newline on Enter
        )

        val canSend = value.isNotBlank() || hasAttachments

        val fabColor = when {
            canSend -> MaterialTheme.colorScheme.primary
            isListening -> MaterialTheme.colorScheme.error
            isSpeaking -> Color(0xFF2196F3) // Blue to indicate interrupt
            else -> MaterialTheme.colorScheme.primary
        }

        FloatingActionButton(
            onClick = {
                if (!canSend) onMicClick() else onSend()
            },
            containerColor = fabColor,
            shape = CircleShape
        ) {
            Icon(
                imageVector = if (!canSend) {
                     when {
                         isListening -> Icons.Default.Stop
                         isSpeaking -> Icons.Default.Mic  // Interrupt TTS and listen
                         else -> Icons.Default.Mic
                     }
                } else {
                     Icons.AutoMirrored.Filled.Send
                },
                contentDescription = stringResource(R.string.send_description),
                tint = Color.White
            )
        }
    }
}

@Composable
fun AttachmentsStrip(
    attachments: List<PendingFileAttachment>,
    onRemoveAttachment: (id: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (att in attachments) {
            AttachmentChip(
                fileName = att.fileName,
                onRemove = { onRemoveAttachment(att.id) },
            )
        }
    }
}

@Composable
fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            androidx.compose.material3.FilledTonalIconButton(
                onClick = onRemove,
                modifier = Modifier.size(30.dp),
            ) {
                Text("×")
            }
        }
    }
}
