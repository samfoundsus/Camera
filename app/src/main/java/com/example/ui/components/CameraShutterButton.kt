package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.camera.NightSightCapturePhase
import com.example.core.camera.NightSightProgressState
import com.example.ui.theme.CameraMotionTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Reusable Google Pixel-inspired prominent double-ring shutter button with touch feedback,
 * smooth video-recording shape morphing, capture confirmation pulse, and Night Sight
 * multi-phase circular progress ring (Capture fill + Cancel -> Processing spinner -> Done).
 */
@Composable
fun CameraShutterButton(
    onClick: () -> Unit,
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,
    isCountingDown: Boolean = false,
    countdownSeconds: Int = 0,
    captureConfirmationTrigger: Int = 0,
    nightSightProgress: NightSightProgressState = NightSightProgressState(),
    onCancelNightSightCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
    outerSize: Dp = 84.dp,
    innerSize: Dp = 68.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val nightPhase = nightSightProgress.phase
    val isNightCapturing = nightPhase == NightSightCapturePhase.CAPTURING
    val isNightProcessing = nightPhase == NightSightCapturePhase.PROCESSING
    val isNightCompleted = nightPhase == NightSightCapturePhase.COMPLETED
    val isNightActive = nightSightProgress.isActive

    var isCapturePulsing by remember { mutableStateOf(false) }
    LaunchedEffect(captureConfirmationTrigger) {
        if (captureConfirmationTrigger > 0) {
            isCapturePulsing = true
            delay(CameraMotionTokens.DURATION_CAPTURE_CONFIRMATION.toLong())
            isCapturePulsing = false
        }
    }

    val animatedCaptureSweep by animateFloatAsState(
        targetValue = when (nightPhase) {
            NightSightCapturePhase.CAPTURING -> (nightSightProgress.progress.coerceIn(0.05f, 1f) * 360f)
            NightSightCapturePhase.COMPLETED -> 360f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 220),
        label = "night_sight_capture_sweep"
    )

    val processingTransition = rememberInfiniteTransition(label = "night_sight_processing_anim")
    val processingSpinAngle by processingTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "night_sight_processing_spin"
    )
    val processingPulseScale by processingTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "night_sight_processing_pulse"
    )

    val pressScale by animateFloatAsState(
        targetValue = when {
            isNightProcessing -> processingPulseScale
            isPressed -> 0.92f
            isCapturePulsing || isNightCompleted -> 1.05f
            else -> 1.0f
        },
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "shutter_scale"
    )

    val targetBorderColor = when {
        isNightCapturing || isNightProcessing -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        isNightCompleted -> MaterialTheme.colorScheme.tertiary
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

    val targetInnerSize = when {
        isRecording -> 32.dp
        isNightCapturing -> 56.dp
        isNightProcessing -> 54.dp
        else -> innerSize
    }
    val animatedInnerSize by animateDpAsState(
        targetValue = targetInnerSize,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_inner_size"
    )

    val targetCornerRadius = when {
        isRecording -> 8.dp
        else -> (innerSize / 2)
    }
    val animatedCornerRadius by animateDpAsState(
        targetValue = targetCornerRadius,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_corner_radius"
    )

    val targetInnerColor = when {
        isNightCapturing -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
        isNightProcessing -> MaterialTheme.colorScheme.tertiaryContainer
        isNightCompleted -> MaterialTheme.colorScheme.tertiary
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

    val captureRingColor = MaterialTheme.colorScheme.primary
    val processingRingColor = MaterialTheme.colorScheme.tertiary
    val completedRingColor = MaterialTheme.colorScheme.tertiary
    val trackRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    // While capturing Night Sight, tapping the shutter cancels capture cleanly.
    // While processing or completed, shutter clicks are disabled so duplicate captures cannot start.
    val isClickable = when (nightPhase) {
        NightSightCapturePhase.IDLE -> true
        NightSightCapturePhase.CAPTURING -> true
        NightSightCapturePhase.PROCESSING, NightSightCapturePhase.COMPLETED -> false
    }
    val effectiveOnClick: () -> Unit = {
        if (isNightCapturing) {
            onCancelNightSightCapture()
        } else if (!isNightActive) {
            onClick()
        }
    }
    val accessibilityLabel = when (nightPhase) {
        NightSightCapturePhase.CAPTURING ->
            "Capturing Night Sight frame ${nightSightProgress.currentFrame} of ${nightSightProgress.totalFrames}. Tap to cancel."
        NightSightCapturePhase.PROCESSING ->
            "Processing Night Sight photo ${(nightSightProgress.progress * 100f).roundToInt()} percent"
        NightSightCapturePhase.COMPLETED -> "Night Sight photo saved"
        NightSightCapturePhase.IDLE -> if (isVideoMode) {
            if (isRecording) "Stop recording" else "Start recording"
        } else {
            "Capture photo"
        }
    }

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
            .semantics { contentDescription = accessibilityLabel }
            .clickable(
                enabled = isClickable,
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = outerSize / 2),
                onClick = effectiveOnClick
            )
            .testTag("shutter_button"),
        contentAlignment = Alignment.Center
    ) {
        // Outer circular progress ring for Night Sight phases
        if (nightPhase != NightSightCapturePhase.IDLE) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(
                        when (nightPhase) {
                            NightSightCapturePhase.CAPTURING -> "night_sight_shutter_capture_progress"
                            NightSightCapturePhase.PROCESSING -> "night_sight_shutter_processing_ring"
                            NightSightCapturePhase.COMPLETED -> "night_sight_shutter_completed"
                            NightSightCapturePhase.IDLE -> "night_sight_shutter_idle"
                        }
                    )
            ) {
                val strokePx = 5.dp.toPx()
                val inset = strokePx / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)

                // Background track
                drawArc(
                    color = trackRingColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                when (nightPhase) {
                    NightSightCapturePhase.CAPTURING -> {
                        drawArc(
                            color = captureRingColor,
                            startAngle = -90f,
                            sweepAngle = animatedCaptureSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    NightSightCapturePhase.PROCESSING -> {
                        // Clearly distinct spinning double-arc indicator so processing never looks frozen
                        drawArc(
                            color = processingRingColor,
                            startAngle = processingSpinAngle,
                            sweepAngle = 115f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = captureRingColor.copy(alpha = 0.65f),
                            startAngle = processingSpinAngle + 180f,
                            sweepAngle = 70f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    NightSightCapturePhase.COMPLETED -> {
                        drawArc(
                            color = completedRingColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    NightSightCapturePhase.IDLE -> Unit
                }
            }
        }

        when {
            isNightCapturing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor)
                        .testTag("night_sight_cancel_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel Night Sight capture",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        if (nightSightProgress.totalFrames > 0) {
                            Text(
                                text = "${nightSightProgress.currentFrame}/${nightSightProgress.totalFrames}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("night_sight_frame_progress_text")
                            )
                        }
                    }
                }
            }
            isNightProcessing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor),
                    contentAlignment = Alignment.Center
                ) {
                    val pct = (nightSightProgress.progress * 100f).roundToInt().coerceIn(0, 99)
                    Text(
                        text = if (pct > 0) "$pct%" else "...",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.testTag("night_sight_processing_pct_text")
                    )
                }
            }
            isNightCompleted -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Night Sight complete",
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            isCountingDown -> {
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
            }
            else -> {
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
}
