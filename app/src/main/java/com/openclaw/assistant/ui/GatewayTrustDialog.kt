package com.openclaw.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.node.NodeRuntime
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer

@Composable
fun GatewayTrustDialog(
  prompt: NodeRuntime.GatewayTrustPrompt,
  onAccept: () -> Unit,
  onDecline: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text(stringResource(R.string.gateway_trust_title)) },
    text = {
      androidx.compose.foundation.layout.Column {
        Text(stringResource(R.string.gateway_trust_message))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                prompt.fingerprintSha256,
                modifier = androidx.compose.ui.Modifier.padding(8.dp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onAccept) {
        Text(stringResource(R.string.gateway_trust_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDecline) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
