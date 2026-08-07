package com.openclaw.assistant.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.R
import com.openclaw.assistant.node.NodeRuntime

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
      val fullText = stringResource(R.string.gateway_trust_message, prompt.fingerprintSha256)
      val startIndex = fullText.indexOf(prompt.fingerprintSha256)

      val annotatedText = buildAnnotatedString {
        append(fullText)
        if (startIndex >= 0) {
          addStyle(
            style = SpanStyle(
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            ),
            start = startIndex,
            end = startIndex + prompt.fingerprintSha256.length
          )
        }
      }

      SelectionContainer {
        Text(annotatedText)
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
