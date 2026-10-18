package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable Google Pixel-inspired prominent double-ring shutter button with touch feedback.
 */
@Composable
fun CameraShutterButton(
    onClick: () -> Unit,
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,
    modifier: Modifier = Modifier,
    outerSize: Dp = 84.dp,
    innerSize: Dp = 68.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label = "shutter_scale"
    )

    Box(
        modifier = modifier
            .size(outerSize)
            .scale(scale)
            .clip(CircleShape)
            .border(
                width = 4.dp,
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = outerSize / 2),
                onClick = onClick
            )
            .testTag("shutter_button"),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.error)
            )
        } else {
            val innerColor = if (isVideoMode) {
                MaterialTheme.colorScheme.error
            } else {
                Color.White
            }

            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(innerColor)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            )
        }
    }
}
