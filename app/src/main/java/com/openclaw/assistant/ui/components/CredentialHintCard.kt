package com.openclaw.assistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R

@Composable
fun CredentialHintCard() {
    val context = LocalContext.current
    // openclaw config get redacts credentials, so read the JSON file directly.
    // Uses bash $() to get the path from `openclaw config file` at runtime.
    val getCredentialCmd = """node -e "const a=JSON.parse(require('fs').readFileSync('$(openclaw config file)'.replace(/^~/,process.env.HOME))).gateway?.auth;if(a?.password)console.log('password:',a.password);else if(a?.token)console.log('token:',a.token);else console.log('not found')""""

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("credential cmd", text))
        Toast.makeText(context, context.getString(R.string.pairing_command_copied), Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_code_credential_hint_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.setup_code_credential_hint_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = { copyToClipboard(getCredentialCmd) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setup_code_credential_copy_button))
            }
        }
    }
}
