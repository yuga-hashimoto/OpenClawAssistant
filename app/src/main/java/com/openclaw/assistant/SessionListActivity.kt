package com.openclaw.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.openclaw.assistant.data.SettingsRepository
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.ui.backend.ChatBackendTarget
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

class SessionListActivity : ComponentActivity() {

    private val viewModel: SessionListViewModel by viewModels()

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
        setContent {
            OpenClawAssistantTheme {
                val sessions by viewModel.allSessions.collectAsState()
                val agentListResult by viewModel.agentList.collectAsState()
                val agents = agentListResult?.agents ?: emptyList()
                val defaultAgentId = agentListResult?.defaultId ?: "main"
                SessionListScreen(
                    sessions = sessions,
                    isGatewayConfigured = viewModel.isGatewayConfigured,
                    isHttpConfigured = viewModel.isHttpConfigured,
                    agents = agents,
                    defaultAgentId = defaultAgentId,
                    onBack = { finish() },
                    onSessionClick = { session ->
                        viewModel.setUseNodeChat(session.isGateway)
                        startActivity(Intent(this, ChatActivity::class.java).apply {
                            putExtra(ChatActivity.EXTRA_SESSION_ID, session.id)
                        })
                    },
                    onCreateSession = { name, isGateway, agentId, targetBackendId ->
                        ChatBackendTarget.set(targetBackendId)
                        viewModel.setUseNodeChat(isGateway)
                        viewModel.createSession(name, isGateway, agentId, targetBackendId) { sessionId, createdAsGateway ->
                            startActivity(Intent(this, ChatActivity::class.java).apply {
                                putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId)
                                putExtra(ChatActivity.EXTRA_SESSION_TITLE, name)
                            })
                        }
                    },
                    onDeleteSession = { sessionId, isGateway ->
                        viewModel.deleteSession(sessionId, isGateway)
                    },
                    onRenameSession = { sessionId, newName, isGateway ->
                        viewModel.renameSession(sessionId, newName, isGateway)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSessions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<SessionUiModel>,
    isGatewayConfigured: Boolean,
    isHttpConfigured: Boolean,
    agents: List<AgentInfo> = emptyList(),
    defaultAgentId: String = "main",
    onBack: () -> Unit,
    onSessionClick: (SessionUiModel) -> Unit,
    onCreateSession: (String, Boolean, String?, String?) -> Unit,
    onDeleteSession: (String, Boolean) -> Unit,
    onRenameSession: (String, String, Boolean) -> Unit = { _, _, _ -> }
) {
    var sessionToDelete by remember { mutableStateOf<SessionUiModel?>(null) }
    var sessionToRename by remember { mutableStateOf<SessionUiModel?>(null) }
    var sessionActionTarget by remember { mutableStateOf<SessionUiModel?>(null) }
    var showTypeSelectionDialog by remember { mutableStateOf(false) }
    var showNameInputDialog by remember { mutableStateOf(false) }
    var showGatewayCreateDialog by remember { mutableStateOf(false) }
    var pendingTargetBackendId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val backendRepo = remember { BackendRepository.getInstance(context) }
    val backends by backendRepo.backends.collectAsState()
    val enabledBackends = backends.filter { it.enabled }
    val openClawBackends = enabledBackends.filter {
        it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP
    }
    val hermesBackends = enabledBackends.filter { it.type == BackendType.HERMES_API_SERVER }
    val preferredOpenClawBackend = openClawBackends.preferredOpenClawBackend()
    val preferredHermesBackend = hermesBackends.firstOrNull { it.isPrimary } ?: hermesBackends.firstOrNull()
    val hasOpenClaw = preferredOpenClawBackend != null || isGatewayConfigured || isHttpConfigured
    val hasHermes = preferredHermesBackend != null

    fun beginOpenClawChat() {
        val backend = preferredOpenClawBackend
        pendingTargetBackendId = backend?.id
        val useGateway = backend?.type == BackendType.OPENCLAW_GATEWAY ||
            (backend == null && isGatewayConfigured && !isHttpConfigured)
        if (useGateway) {
            showGatewayCreateDialog = true
        } else {
            showNameInputDialog = true
        }
    }

    fun beginHermesChat() {
        pendingTargetBackendId = preferredHermesBackend?.id
        showNameInputDialog = true
    }

    val listState = rememberLazyListState()
    var scrollTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scrollTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(scrollTrigger) {
        if (sessions.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversations_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            val newSessionName = stringResource(R.string.new_chat)
            FloatingActionButton(onClick = { 
                if (hasOpenClaw && hasHermes) {
                    showTypeSelectionDialog = true
                } else if (hasHermes) {
                    beginHermesChat()
                } else if (hasOpenClaw) {
                    beginOpenClawChat()
                } else if (isHttpConfigured) {
                    pendingTargetBackendId = null
                    showNameInputDialog = true
                } else if (isGatewayConfigured) {
                    pendingTargetBackendId = null
                    showGatewayCreateDialog = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = newSessionName)
            }
        }
    ) { paddingValues ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.no_sessions),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionListItem(
                        session = session,
                        onClick = { onSessionClick(session) },
                        onLongClick = { sessionActionTarget = session }
                    )
                }
            }
        }
    }

    // Product selection dialog (OpenClaw vs Hermes)
    if (showTypeSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showTypeSelectionDialog = false },
            title = { Text(stringResource(R.string.select_chat_type)) },
            text = { Text(stringResource(R.string.select_chat_backend_desc)) },
            dismissButton = {
                TextButton(onClick = {
                    showTypeSelectionDialog = false
                    beginOpenClawChat()
                }) {
                    Text(stringResource(R.string.chat_type_openclaw))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTypeSelectionDialog = false
                    beginHermesChat()
                }) {
                    Text(stringResource(R.string.chat_type_hermes))
                }
            }
        )
    }

    // Gateway creation dialog with name + agent selection
    if (showGatewayCreateDialog) {
        var inputName by remember { mutableStateOf("") }
        var selectedAgentId by remember { mutableStateOf<String?>(null) }
        var agentDropdownExpanded by remember { mutableStateOf(false) }

        val effectiveAgentId = selectedAgentId ?: defaultAgentId
        val selectedAgentName = agents.find { it.id == effectiveAgentId }?.name
            ?: if (effectiveAgentId == "main" || effectiveAgentId.isBlank()) "Default" else effectiveAgentId

        AlertDialog(
            onDismissRequest = { showGatewayCreateDialog = false },
            title = { Text(stringResource(R.string.new_chat)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text(stringResource(R.string.session_name_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (agents.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Agent",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box {
                            Surface(
                                onClick = { agentDropdownExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedAgentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = agentDropdownExpanded,
                                onDismissRequest = { agentDropdownExpanded = false }
                            ) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = { Text(agent.name) },
                                        onClick = {
                                            selectedAgentId = agent.id
                                            agentDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showGatewayCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGatewayCreateDialog = false
                    val ts = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    val finalName = if (inputName.isNotBlank()) inputName else context.getString(R.string.chat_session_title_format, ts)
                    val agentId = selectedAgentId ?: if (defaultAgentId != "main" && defaultAgentId.isNotBlank()) defaultAgentId else null
                    onCreateSession(finalName, true, agentId, pendingTargetBackendId)
                }) {
                    Text(stringResource(R.string.create))
                }
            }
        )
    }

    // HTTP name input dialog
    if (showNameInputDialog) {
        var inputName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNameInputDialog = false },
            title = { Text(stringResource(R.string.new_chat)) },
            text = { 
                Column {
                    Text(stringResource(R.string.enter_session_name))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text(stringResource(R.string.session_name_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameInputDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameInputDialog = false
                    val finalName = if (inputName.isNotBlank()) inputName else "New Conversation"
                    onCreateSession(finalName, false, null, pendingTargetBackendId)
                }) {
                    Text(stringResource(R.string.create))
                }
            }
        )
    }



    // Long-press action menu: Rename / Delete
    sessionActionTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionActionTarget = null },
            title = { Text(session.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = null,
            confirmButton = {
                TextButton(onClick = {
                    sessionActionTarget = null
                    sessionToRename = session
                }) {
                    Text(stringResource(R.string.rename_session))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    sessionActionTarget = null
                    sessionToDelete = session
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // Rename dialog
    sessionToRename?.let { session ->
        var renameInput by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text(stringResource(R.string.rename_session_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.rename_session_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameInput.trim()
                        if (name.isNotBlank()) {
                            onRenameSession(session.id, name, session.isGateway)
                        }
                        sessionToRename = null
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = {
                Text(stringResource(R.string.delete_session_message, session.title))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.id, session.isGateway)
                    sessionToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun List<AgentBackendConfig>.preferredOpenClawBackend(): AgentBackendConfig? {
    return firstOrNull { it.isPrimary }
        ?: firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        ?: firstOrNull()
}

@Composable
private fun SessionListItem(
    session: SessionUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dateText = remember(session.createdAt) {
                        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(session.createdAt))
                    }
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (session.product == ChatProduct.OPENCLAW) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = when (session.product) {
                                ChatProduct.OPENCLAW -> stringResource(R.string.chat_type_openclaw)
                                ChatProduct.HERMES -> stringResource(R.string.chat_type_hermes)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            color = if (session.product == ChatProduct.OPENCLAW) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            IconButton(onClick = onLongClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.menu)
                )
            }
        }
    }
}
