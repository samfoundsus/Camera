package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CameraMotionTokens
import kotlinx.coroutines.delay

/**
 * Reusable Google Pixel-inspired prominent double-ring shutter button with touch feedback,
 * smooth video-recording shape morphing, and capture confirmation pulse.
 */
@Composable
fun CameraShutterButton(
    onClick: () -> Unit,
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,
    isCountingDown: Boolean = false,
    countdownSeconds: Int = 0,
    captureConfirmationTrigger: Int = 0,
    modifier: Modifier = Modifier,
    outerSize: Dp = 84.dp,
    innerSize: Dp = 68.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    var isCapturePulsing by remember { mutableStateOf(false) }
    LaunchedEffect(captureConfirmationTrigger) {
        if (captureConfirmationTrigger > 0) {
            isCapturePulsing = true
            delay(CameraMotionTokens.DURATION_CAPTURE_CONFIRMATION.toLong())
            isCapturePulsing = false
        }
    }

    val pressScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isCapturePulsing -> 1.05f
            else -> 1.0f
        },
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "shutter_scale"
    )

    val targetBorderColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isCountingDown -> MaterialTheme.colorScheme.primary
        isCapturePulsing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_border_color"
    )

    val targetInnerSize = if (isRecording) 32.dp else innerSize
    val animatedInnerSize by animateDpAsState(
        targetValue = targetInnerSize,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_inner_size"
    )

    val targetCornerRadius = if (isRecording) 8.dp else (innerSize / 2)
    val animatedCornerRadius by animateDpAsState(
        targetValue = targetCornerRadius,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_corner_radius"
    )

    val targetInnerColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isCountingDown -> MaterialTheme.colorScheme.primaryContainer
        isVideoMode -> MaterialTheme.colorScheme.error
        else -> Color.White
    }
    val animatedInnerColor by animateColorAsState(
        targetValue = targetInnerColor,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_inner_color"
    )

    Box(
        modifier = modifier
            .size(outerSize)
            .scale(pressScale)
            .clip(CircleShape)
            .border(
                width = 4.dp,
                color = animatedBorderColor,
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
        if (isCountingDown) {
            Box(
                modifier = Modifier
                    .size(animatedInnerSize)
                    .clip(RoundedCornerShape(animatedCornerRadius))
                    .background(animatedInnerColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$countdownSeconds",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("shutter_countdown_text")
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(animatedInnerSize)
                    .clip(RoundedCornerShape(animatedCornerRadius))
                    .background(animatedInnerColor)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(animatedCornerRadius)
                    )
            )
        }
    }
}
