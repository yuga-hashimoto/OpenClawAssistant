package com.openclaw.assistant.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R

enum class ConnectionState {
    Connected,
    Connecting,
    Disconnected
}

/**
 * Animated dot + label showing connection state.
 *
 * - Connected: solid green dot
 * - Connecting: pulsing amber dot
 * - Disconnected: static grey dot
 */
@Composable
fun StatusIndicator(
    state: ConnectionState,
    label: String? = null,
    dotSize: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val dotColor = when (state) {
        ConnectionState.Connected -> Color(0xFF4CAF50)
        ConnectionState.Connecting -> Color(0xFFFFA726)
        ConnectionState.Disconnected -> Color(0xFF757575)
    }

    val stateDesc = when (state) {
        ConnectionState.Connected -> stringResource(R.string.connected)
        ConnectionState.Connecting -> stringResource(R.string.gateway_connecting)
        ConnectionState.Disconnected -> stringResource(R.string.disconnected)
    }

    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.Connecting) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .alpha(alpha)
                .background(dotColor, CircleShape)
                .then(
                    if (label == null) {
                        Modifier.semantics {
                            contentDescription = stateDesc
                        }
                    } else {
                        Modifier
                    }
                )
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = dotColor
            )
        }
    }
}
