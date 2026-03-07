package com.openclaw.assistant.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openclaw.assistant.R
import com.openclaw.assistant.chat.ChatMessageContent
import com.openclaw.assistant.node.CanvasController
import com.openclaw.assistant.node.NodeRuntime
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CanvasScreen(
    canvasController: CanvasController,
    nodeRuntime: NodeRuntime,
    modifier: Modifier = Modifier
) {
    val nodeRuntimeRef = remember { mutableStateOf(nodeRuntime) }
    SideEffect { nodeRuntimeRef.value = nodeRuntime }

    // Box layers the chat bar (Compose) directly on top of the WebView (AndroidView).
    // CanvasChatBar lives in the main Compose composition → state flows (pendingRunCount,
    // messages, etc.) properly trigger recomposition and isAiBusy stays accurate.
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // WebView returned directly — no FrameLayout wrapper.
                // setOnTouchListener ensures ACTION_DOWN tells Compose not to intercept scrolls.
                canvasController.getOrCreateWebView(context).also {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.webViewClient = CanvasWebViewClient(canvasController)
                    it.setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false
                    }
                    canvasController.attach(it)
                    it.requestFocus()
                }
            },
            onRelease = { _ -> canvasController.detach() }
        )
        CanvasChatBar(
            canvasController = canvasController,
            nodeRuntimeRef = nodeRuntimeRef,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun CanvasChatBar(
    canvasController: CanvasController,
    nodeRuntimeRef: androidx.compose.runtime.State<NodeRuntime>,
    modifier: Modifier = Modifier
) {
    val nodeRuntime = nodeRuntimeRef.value
    val isDefault by canvasController.isDefaultFlow.collectAsState()
    val isPageLoading by canvasController.isPageLoadingFlow.collectAsState()
    val healthOk by nodeRuntime.chatHealthOk.collectAsState()
    val streamingText by nodeRuntime.chatStreamingAssistantText.collectAsState()
    val pendingTools by nodeRuntime.chatPendingToolCalls.collectAsState()
    val pendingRunCount by nodeRuntime.pendingRunCount.collectAsState()
    val messages by nodeRuntime.chatMessages.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var lastAiText by remember { mutableStateOf<String?>(null) }
    // Count of assistant messages at send time — used to detect a NEW response
    var assistantCountAtSend by remember { mutableStateOf(0) }

    // pendingRunCount covers the full AI processing window (send → tools → complete)
    val isAiBusy = isSending || pendingRunCount > 0

    // Clear isSending only when a NEW assistant message arrives (not a pre-existing one)
    LaunchedEffect(messages) {
        val assistantCount = messages.count { it.role == "assistant" }
        if (isSending && assistantCount > assistantCountAtSend) {
            isSending = false
        }
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }
        val text = lastAssistant?.content
            ?.firstOrNull { it.type == "text" }?.text
        if (!text.isNullOrBlank()) lastAiText = text
    }

    // Auto-hide text response after 10 seconds
    LaunchedEffect(lastAiText) {
        if (lastAiText != null) {
            delay(10_000L)
            lastAiText = null
        }
    }

    // Clear isSending as soon as any AI activity is detected
    LaunchedEffect(streamingText, pendingTools.size, pendingRunCount) {
        if (streamingText != null || pendingTools.isNotEmpty() || pendingRunCount > 0) {
            isSending = false
        }
    }
    // Safety timeout — generous to cover slow AI gateway responses (observed ~73s latency)
    LaunchedEffect(isSending) {
        if (isSending) {
            delay(120_000L)
            isSending = false
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isBlank() || !healthOk || isAiBusy) return
        inputText = ""
        lastAiText = null
        assistantCountAtSend = messages.count { it.role == "assistant" }
        isSending = true
        val canvasInstruction = "[CANVAS MODE] You MUST use canvas tools to respond to this message. " +
            "Use canvas.eval() to display your response as HTML in the canvas, " +
            "or canvas.navigate() to load a page. " +
            "Do NOT reply with plain text only — the user cannot see plain text responses here.\n\n"
        nodeRuntimeRef.value.sendChat(canvasInstruction + text, "low", emptyList())
    }

    val statusLabel: String? = when {
        streamingText != null     -> stringResource(R.string.canvas_status_thinking)
        pendingTools.isNotEmpty() -> stringResource(R.string.canvas_status_tool)
        isPageLoading             -> stringResource(R.string.canvas_status_loading)
        isAiBusy                  -> stringResource(R.string.canvas_status_sending)
        !healthOk                 -> stringResource(R.string.canvas_chat_not_connected)
        else                      -> null
    }
    val showSpinner = isAiBusy || isPageLoading

    Surface(
        modifier = modifier,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            // ── Diagnostic card: AI responded with text but no Canvas ────────
            AnimatedVisibility(
                visible = isDefault && !isAiBusy && !isPageLoading && lastAiText != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                if (lastAiText != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.canvas_diag_no_canvas),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = lastAiText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }

            // ── Not-connected warning ────────────────────────────────────────
            AnimatedVisibility(
                visible = !healthOk,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = stringResource(R.string.canvas_diag_not_connected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // ── Status row (spinner + label) ─────────────────────────────────
            AnimatedVisibility(
                visible = statusLabel != null && healthOk,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                if (statusLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showSpinner) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // ── Streaming text preview ───────────────────────────────────────
            AnimatedVisibility(
                visible = streamingText != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val st = streamingText
                if (st != null) {
                    Text(
                        text = st,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // ── Input row ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(text = stringResource(R.string.canvas_chat_hint)) },
                    enabled = healthOk && !isAiBusy,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { sendMessage() },
                    enabled = healthOk && !isAiBusy && inputText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.canvas_chat_send)
                    )
                }
            }
        }
    }
}

private class CanvasWebViewClient(
    private val canvasController: CanvasController
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        canvasController.onPageFinished()
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val token = canvasController.gatewayToken?.takeIf { it.isNotBlank() } ?: return null
        val origin = canvasController.gatewayOrigin ?: return null
        if (!request.url.toString().startsWith(origin)) return null

        return try {
            val conn = URL(request.url.toString()).openConnection() as HttpURLConnection
            request.requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connect()
            WebResourceResponse(
                conn.contentType?.substringBefore(";") ?: "text/html",
                conn.contentEncoding ?: "utf-8",
                conn.inputStream
            )
        } catch (_: Exception) {
            null
        }
    }
}
