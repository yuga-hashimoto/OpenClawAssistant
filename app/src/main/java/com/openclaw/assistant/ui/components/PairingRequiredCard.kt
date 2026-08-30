package com.openclaw.assistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.ui.terminal.HermesTerminalClient
import com.openclaw.assistant.ui.terminal.TerminalCommandClient
import kotlinx.coroutines.launch

internal const val PAIRING_AUTO_RETRY_MS = 5 * 60_000L

@Composable
fun PairingRequiredCard(deviceId: String, displayName: String = "") {
    val context = LocalContext.current
    val nodeRuntime = remember { (context.applicationContext as OpenClawApplication).nodeRuntime }
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()

    val approveCommand = stringResource(R.string.approve_command_format, deviceId)
    val rejectCommand = stringResource(R.string.reject_command_format, deviceId)

    var expanded by remember { mutableStateOf(false) }
    var runningCommand by remember { mutableStateOf(false) }
    var commandStatus by remember { mutableStateOf<String?>(null) }
    var autoApproveAttempted by remember(deviceId) { mutableStateOf(false) }

    suspend fun approveWithBestRoute(isAuto: Boolean) {
        runningCommand = true

        suspend fun approveThroughGateway(): Boolean {
            commandStatus = context.getString(
                if (isAuto) R.string.pairing_gateway_auto_running else R.string.pairing_gateway_running,
            )
            val gatewayResult = nodeRuntime.approvePendingPairingForDevice(deviceId)
            if (gatewayResult.approved) {
                commandStatus = context.getString(R.string.pairing_gateway_approve_sent)
                nodeRuntime.refreshGatewayConnection()
                return true
            }
            return false
        }

        if (TerminalCommandClient.isConfigured(context) || HermesTerminalClient.resolveEndpoint(context) != null) {
            commandStatus = context.getString(
                if (isAuto) R.string.pairing_terminal_auto_running else R.string.pairing_terminal_running,
            )
            val terminalResult =
                if (TerminalCommandClient.isConfigured(context)) {
                    TerminalCommandClient.run(context, approveCommand, timeoutSeconds = 45).map { Unit }
                } else {
                    HermesTerminalClient.requestCommandRun(context, approveCommand)
                }
            runningCommand = false
            if (terminalResult.isSuccess) {
                commandStatus = context.getString(R.string.pairing_terminal_approve_sent)
                nodeRuntime.refreshGatewayConnection()
            } else {
                runningCommand = true
                if (approveThroughGateway()) {
                    runningCommand = false
                    return
                }
                runningCommand = false
                commandStatus = context.getString(
                    if (isAuto) R.string.pairing_terminal_auto_failed else R.string.pairing_terminal_failed_copied,
                )
                if (!isAuto) {
                    val clip = ClipData.newPlainText("OpenClaw Approve", approveCommand)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, R.string.pairing_command_copied, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        if (approveThroughGateway()) {
            runningCommand = false
            return
        }

        runningCommand = false
        commandStatus = context.getString(R.string.pairing_host_command_required, approveCommand)
        if (!isAuto) {
            val clip = ClipData.newPlainText("OpenClaw Approve", approveCommand)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(context, R.string.pairing_command_copied, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PAIRING_AUTO_RETRY_MS)
            nodeRuntime.refreshGatewayConnection()
        }
    }

    LaunchedEffect(deviceId) {
        if (!autoApproveAttempted) {
            autoApproveAttempted = true
            approveWithBestRoute(isAuto = true)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.pairing_required_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pairing_device_id, deviceId),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pairing_required_desc),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pairing_requested_scopes),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            SelectionContainer {
                Text(
                    text = approveCommand,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            approveWithBestRoute(isAuto = false)
                        }
                    },
                    enabled = !runningCommand,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pairing_approve_terminal), fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        val clip = ClipData.newPlainText("OpenClaw Reject", rejectCommand)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(context, R.string.pairing_command_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pairing_reject_command), fontSize = 12.sp)
                }
            }

            commandStatus?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { nodeRuntime.refreshGatewayConnection() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.pairing_refresh_status))
            }

            // Instructions expander
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (expanded) stringResource(R.string.hide_instructions) else stringResource(R.string.show_instructions))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pairing_guide_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.pairing_guide_step_1), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.pairing_guide_step_2), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.pairing_guide_step_3), style = MaterialTheme.typography.bodySmall)

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.pairing_troubleshooting_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.pairing_troubleshooting_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
