package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.camera.NightSightProgressState
import com.example.core.camera.ShutterProgressMode
import com.example.core.camera.ShutterProgressPhase
import com.example.core.camera.ShutterProgressState
import com.example.core.camera.toShutterProgressState
import com.example.ui.theme.CameraMotionTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Shared circular progress ring overlay used across every camera mode:
 * - [ShutterProgressPhase.CAPTURING] (determinate): fills 0° -> 360° smoothly as frames, sweep, or duration advance.
 * - [ShutterProgressPhase.CAPTURING] (indeterminate, e.g. Video / Slow-Mo / unlimited Time Lapse): crisp active ring
 *   that subtly pulses each time [ShutterProgressState.framePulseCount] increments.
 * - [ShutterProgressPhase.PROCESSING]: distinct spinning dual-arc indicator (plus progress arc if percentage is known)
 *   so processing never looks frozen.
 * - [ShutterProgressPhase.COMPLETED]: full 360° completion ring.
 */
@Composable
fun ShutterProgressRing(
    progressState: ShutterProgressState,
    processingSpinAngle: Float,
    framePulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val phase = progressState.phase
    if (phase == ShutterProgressPhase.IDLE) return

    val animatedCaptureSweep by animateFloatAsState(
        targetValue = when {
            phase == ShutterProgressPhase.CAPTURING && !progressState.isIndeterminateCapture ->
                (progressState.progress.coerceIn(0.05f, 1f) * 360f)
            phase == ShutterProgressPhase.COMPLETED -> 360f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 220),
        label = "shared_shutter_capture_sweep"
    )

    val captureRingColor = if (progressState.mode.isVideoLikeRecording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val processingRingColor = MaterialTheme.colorScheme.tertiary
    val completedRingColor = MaterialTheme.colorScheme.tertiary
    val trackRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)

    val modeTagPrefix = when (progressState.mode) {
        ShutterProgressMode.NIGHT_SIGHT -> "night_sight"
        ShutterProgressMode.ASTRO -> "astro"
        ShutterProgressMode.TIME_LAPSE -> "timelapse"
        ShutterProgressMode.PORTRAIT -> "portrait"
        ShutterProgressMode.PHOTO -> "photo"
        ShutterProgressMode.HD -> "hd"
        ShutterProgressMode.PRO -> "pro"
        ShutterProgressMode.PANORAMA -> "panorama"
        ShutterProgressMode.VIDEO -> "video"
        ShutterProgressMode.SLOW_MOTION -> "slow_motion"
    }
    val ringTag = when (phase) {
        ShutterProgressPhase.CAPTURING -> "${modeTagPrefix}_shutter_capture_progress"
        ShutterProgressPhase.PROCESSING -> "${modeTagPrefix}_shutter_processing_ring"
        ShutterProgressPhase.COMPLETED -> "${modeTagPrefix}_shutter_completed"
        ShutterProgressPhase.IDLE -> "${modeTagPrefix}_shutter_idle"
    }

    key(ringTag) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .semantics { testTag = ringTag }
                .drawWithCache {
                val strokePx = 5.dp.toPx()
                val pulseExtraPx = 2.dp.toPx()
                val inset = strokePx / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)
                val baseStroke = Stroke(width = strokePx, cap = StrokeCap.Round)

                onDrawBehind {
                    // Background track
                    drawArc(
                        color = trackRingColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = baseStroke
                    )

                    when (phase) {
                        ShutterProgressPhase.CAPTURING -> {
                            if (progressState.isIndeterminateCapture) {
                                // Video / Slow-Mo / unlimited Time Lapse: steady active ring + pulse on each tick/frame
                                drawArc(
                                    color = captureRingColor.copy(alpha = 0.45f + 0.55f * framePulseAlpha),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(
                                        width = strokePx + (pulseExtraPx * framePulseAlpha),
                                        cap = StrokeCap.Round
                                    )
                                )
                                drawArc(
                                    color = captureRingColor,
                                    startAngle = processingSpinAngle,
                                    sweepAngle = 64f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = baseStroke
                                )
                            } else {
                                drawArc(
                                    color = captureRingColor,
                                    startAngle = -90f,
                                    sweepAngle = animatedCaptureSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = baseStroke
                                )
                            }
                        }
                        ShutterProgressPhase.PROCESSING -> {
                            // Clearly distinct spinning double-arc indicator so processing never looks frozen
                            drawArc(
                                color = processingRingColor,
                                startAngle = processingSpinAngle,
                                sweepAngle = 115f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = baseStroke
                            )
                            drawArc(
                                color = captureRingColor.copy(alpha = 0.65f),
                                startAngle = processingSpinAngle + 180f,
                                sweepAngle = 70f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = baseStroke
                            )
                        }
                        ShutterProgressPhase.COMPLETED -> {
                            drawArc(
                                color = completedRingColor,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = baseStroke
                            )
                        }
                        ShutterProgressPhase.IDLE -> Unit
                    }
                }
            }
        )
    }
}

/**
 * Shared Material 3 status banner rendered immediately above the shutter bar across all camera modes.
 *
 * Shows frame count, elapsed/remaining time, processing progress, "Saved" completion,
 * and during Astrophotography capture offers a clear "Stop & save" action so long sessions are never lost.
 */
@Composable
fun ShutterProgressStatusBanner(
    progressState: ShutterProgressState,
    onStopAndSaveClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val visible = progressState.phase != ShutterProgressPhase.IDLE &&
        (progressState.detailLabel.isNotBlank() || progressState.statusLabel.isNotBlank())

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(180)),
        modifier = modifier
    ) {
        val containerColor = when (progressState.phase) {
            ShutterProgressPhase.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
            ShutterProgressPhase.PROCESSING -> MaterialTheme.colorScheme.secondaryContainer
            ShutterProgressPhase.CAPTURING -> if (progressState.mode.isVideoLikeRecording) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
            ShutterProgressPhase.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val contentColor = when (progressState.phase) {
            ShutterProgressPhase.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
            ShutterProgressPhase.PROCESSING -> MaterialTheme.colorScheme.onSecondaryContainer
            ShutterProgressPhase.CAPTURING -> if (progressState.mode.isVideoLikeRecording) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            ShutterProgressPhase.IDLE -> MaterialTheme.colorScheme.onSurface
        }
        val caption = progressState.detailLabel.ifBlank { progressState.statusLabel }
        val showStopAndSaveChip = progressState.mode == ShutterProgressMode.ASTRO &&
            progressState.phase == ShutterProgressPhase.CAPTURING &&
            progressState.canStopAndSave

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("shutter_progress_banner")
        ) {
            Surface(
                color = containerColor.copy(alpha = 0.94f),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("shutter_progress_caption")
                )
            }

            if (showStopAndSaveChip) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onStopAndSaveClick)
                        .testTag("astro_stop_and_save_button")
                ) {
                    Text(
                        text = "Stop & save",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Reusable Google Pixel-inspired prominent double-ring shutter button with touch feedback,
 * smooth video-recording shape morphing, capture confirmation pulse, and one shared multi-phase
 * circular progress component used across all camera modes.
 */
@Composable
fun CameraShutterButton(
    onClick: () -> Unit,
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,
    isCountingDown: Boolean = false,
    countdownSeconds: Int = 0,
    captureConfirmationTrigger: Int = 0,
    shutterProgress: ShutterProgressState = ShutterProgressState(),
    onActiveProgressShutterClick: () -> Unit = {},
    nightSightProgress: NightSightProgressState = NightSightProgressState(),
    onCancelNightSightCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
    outerSize: Dp = 84.dp,
    innerSize: Dp = 68.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val effectiveProgress = remember(shutterProgress, nightSightProgress) {
        if (shutterProgress.phase != ShutterProgressPhase.IDLE) {
            shutterProgress
        } else {
            nightSightProgress.toShutterProgressState()
        }
    }

    val phase = effectiveProgress.phase
    val isProgressCapturing = phase == ShutterProgressPhase.CAPTURING
    val isProgressProcessing = phase == ShutterProgressPhase.PROCESSING
    val isProgressCompleted = phase == ShutterProgressPhase.COMPLETED
    val isProgressActive = effectiveProgress.isActive
    val isTimeLapseCapturing = isProgressCapturing && effectiveProgress.mode == ShutterProgressMode.TIME_LAPSE
    val isVideoLikeCapturing = isProgressCapturing && effectiveProgress.mode.isVideoLikeRecording
    val isAstroCapturing = isProgressCapturing && effectiveProgress.mode == ShutterProgressMode.ASTRO
    val isNightCapturing = isProgressCapturing && effectiveProgress.mode == ShutterProgressMode.NIGHT_SIGHT
    val isPanoramaCapturing = isProgressCapturing && effectiveProgress.mode == ShutterProgressMode.PANORAMA
    val isMultiFrameStillCapturing = isProgressCapturing &&
        effectiveProgress.mode.supportsConcurrentPhotoQueue &&
        effectiveProgress.totalFrames > 1

    var isCapturePulsing by remember { mutableStateOf(false) }
    LaunchedEffect(captureConfirmationTrigger) {
        if (captureConfirmationTrigger > 0) {
            isCapturePulsing = true
            delay(CameraMotionTokens.DURATION_CAPTURE_CONFIRMATION.toLong())
            isCapturePulsing = false
        }
    }

    // Subtle pulse whenever an indeterminate recording emits a frame or second tick
    var isFramePulseActive by remember { mutableStateOf(false) }
    LaunchedEffect(effectiveProgress.framePulseCount) {
        if (effectiveProgress.framePulseCount > 0 && isVideoLikeCapturing && effectiveProgress.isIndeterminateCapture) {
            isFramePulseActive = true
            delay(240L)
            isFramePulseActive = false
        }
    }
    val framePulseAlpha by animateFloatAsState(
        targetValue = if (isFramePulseActive) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "shutter_frame_pulse_alpha"
    )

    val needsSpinAnim = isProgressProcessing || (isProgressCapturing && effectiveProgress.isIndeterminateCapture)
    val processingSpinAngle = if (needsSpinAnim) {
        val processingTransition = rememberInfiniteTransition(label = "shutter_progress_processing_anim")
        val angle by processingTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1050, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shutter_progress_spin"
        )
        angle
    } else {
        0f
    }

    val processingPulseScale = if (isProgressProcessing) {
        val pulseTransition = rememberInfiniteTransition(label = "shutter_progress_pulse_anim")
        val pulse by pulseTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shutter_progress_pulse"
        )
        pulse
    } else {
        1.0f
    }

    val pressScale by animateFloatAsState(
        targetValue = when {
            isProgressProcessing -> processingPulseScale
            isFramePulseActive -> 1.04f
            isPressed -> 0.92f
            isCapturePulsing || isProgressCompleted -> 1.05f
            else -> 1.0f
        },
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "shutter_scale"
    )

    val targetBorderColor = when {
        isProgressCapturing || isProgressProcessing -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        isProgressCompleted -> MaterialTheme.colorScheme.tertiary
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
        isVideoLikeCapturing -> 32.dp
        isRecording -> 32.dp
        isProgressCapturing -> 56.dp
        isProgressProcessing -> 54.dp
        else -> innerSize
    }
    val animatedInnerSize by animateDpAsState(
        targetValue = targetInnerSize,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_inner_size"
    )

    val targetCornerRadius = when {
        isVideoLikeCapturing || isRecording -> 8.dp
        else -> (innerSize / 2)
    }
    val animatedCornerRadius by animateDpAsState(
        targetValue = targetCornerRadius,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "shutter_corner_radius"
    )

    val targetInnerColor = when {
        isVideoLikeCapturing -> MaterialTheme.colorScheme.error
        isProgressCapturing -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
        isProgressProcessing -> MaterialTheme.colorScheme.tertiaryContainer
        isProgressCompleted -> MaterialTheme.colorScheme.tertiary
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

    // Shutter clickability rules across all 10 modes:
    // - IDLE: always enabled.
    // - CAPTURING: enabled for stoppable/cancellable modes (Night Sight, Astro, Time Lapse, Video, Slow-Mo, Panorama),
    //   and also enabled during Portrait CAPTURING compatibility, otherwise disabled while exposing.
    // - PROCESSING: enabled ONLY when allowConcurrentCapture == true (Photo, HD, Portrait, Pro with free queue slot).
    // - COMPLETED: enabled for concurrent still modes so consecutive shots never stall on the "Saved" badge.
    val isClickable = when (phase) {
        ShutterProgressPhase.IDLE -> true
        ShutterProgressPhase.CAPTURING -> isVideoLikeCapturing || isAstroCapturing || isNightCapturing ||
            isPanoramaCapturing || effectiveProgress.canStopAndSave || effectiveProgress.allowConcurrentCapture
        ShutterProgressPhase.PROCESSING -> effectiveProgress.allowConcurrentCapture
        ShutterProgressPhase.COMPLETED -> effectiveProgress.mode.supportsConcurrentPhotoQueue
    }
    val effectiveOnClick: () -> Unit = {
        when {
            isNightCapturing && shutterProgress.phase == ShutterProgressPhase.IDLE -> onCancelNightSightCapture()
            isProgressCapturing && (isVideoLikeCapturing || isAstroCapturing || isNightCapturing || isPanoramaCapturing || effectiveProgress.canStopAndSave) ->
                onActiveProgressShutterClick()
            isProgressProcessing && effectiveProgress.allowConcurrentCapture -> onClick()
            isProgressCompleted && effectiveProgress.mode.supportsConcurrentPhotoQueue -> onClick()
            !isProgressActive -> onClick()
        }
    }

    val accessibilityLabel = when (phase) {
        ShutterProgressPhase.CAPTURING -> when (effectiveProgress.mode) {
            ShutterProgressMode.NIGHT_SIGHT ->
                "Capturing Night Sight frame ${effectiveProgress.currentFrame} of ${effectiveProgress.totalFrames}. Tap to cancel."
            ShutterProgressMode.ASTRO ->
                "Capturing Astrophotography frame ${effectiveProgress.currentFrame} of ${effectiveProgress.totalFrames}. Tap to stop and save what you have."
            ShutterProgressMode.TIME_LAPSE ->
                "Recording Time Lapse, ${effectiveProgress.currentFrame} frames captured. Tap to stop recording."
            ShutterProgressMode.VIDEO ->
                "Recording video. Tap to stop recording."
            ShutterProgressMode.SLOW_MOTION ->
                "Recording slow motion video. Tap to stop recording."
            ShutterProgressMode.PANORAMA ->
                "Capturing panorama. Tap to finish and stitch."
            ShutterProgressMode.PORTRAIT ->
                "Capturing Portrait photo"
            ShutterProgressMode.HD ->
                "Capturing HD photo"
            ShutterProgressMode.PRO ->
                "Capturing Pro photo"
            ShutterProgressMode.PHOTO ->
                "Capturing photo"
        }
        ShutterProgressPhase.PROCESSING -> {
            val modeName = when (effectiveProgress.mode) {
                ShutterProgressMode.NIGHT_SIGHT -> "Night Sight photo"
                ShutterProgressMode.ASTRO -> "Astrophotography photo"
                ShutterProgressMode.TIME_LAPSE -> "Time Lapse video"
                ShutterProgressMode.PORTRAIT -> "Portrait photo"
                ShutterProgressMode.PHOTO -> "Photo"
                ShutterProgressMode.HD -> "HD photo"
                ShutterProgressMode.PRO -> "Pro photo"
                ShutterProgressMode.PANORAMA -> "Panorama"
                ShutterProgressMode.VIDEO -> "Video"
                ShutterProgressMode.SLOW_MOTION -> "Slow Motion video"
            }
            val nextHint = if (effectiveProgress.allowConcurrentCapture) ". Tap to capture next photo." else ""
            "Processing $modeName ${(effectiveProgress.progress * 100f).roundToInt()} percent$nextHint"
        }
        ShutterProgressPhase.COMPLETED -> "Capture saved"
        ShutterProgressPhase.IDLE -> if (isVideoMode) {
            if (isRecording) "Stop recording" else "Start recording"
        } else {
            "Capture photo"
        }
    }

    Box(
        modifier = modifier
            .size(outerSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
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
        // Shared outer circular progress ring across all camera modes
        ShutterProgressRing(
            progressState = effectiveProgress,
            processingSpinAngle = processingSpinAngle,
            framePulseAlpha = framePulseAlpha
        )

        when {
            isVideoLikeCapturing -> {
                // Video / Slow-Mo / Time Lapse recording: unmistakable red Stop square inside the shared ring
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(RoundedCornerShape(animatedCornerRadius))
                        .background(animatedInnerColor)
                        .testTag(
                            if (isTimeLapseCapturing) "timelapse_stop_button" else "video_stop_button"
                        )
                )
            }
            isPanoramaCapturing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor)
                        .testTag("panorama_capture_stop_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Finish panorama capture",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "${(effectiveProgress.progress * 100f).roundToInt().coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            isAstroCapturing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor)
                        .testTag("astro_capture_stop_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop and save Astrophotography capture",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        if (effectiveProgress.totalFrames > 0) {
                            Text(
                                text = "${effectiveProgress.currentFrame}/${effectiveProgress.totalFrames}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("astro_frame_progress_text")
                            )
                        }
                    }
                }
            }
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
                        if (effectiveProgress.totalFrames > 0) {
                            Text(
                                text = "${effectiveProgress.currentFrame}/${effectiveProgress.totalFrames}",
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
            isMultiFrameStillCapturing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${effectiveProgress.currentFrame}/${effectiveProgress.totalFrames}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            isProgressProcessing -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor),
                    contentAlignment = Alignment.Center
                ) {
                    val pct = (effectiveProgress.progress * 100f).roundToInt().coerceIn(0, 99)
                    Text(
                        text = if (pct > 0) "$pct%" else "...",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.testTag(
                            if (effectiveProgress.mode == ShutterProgressMode.NIGHT_SIGHT) {
                                "night_sight_processing_pct_text"
                            } else {
                                "shutter_processing_pct_text"
                            }
                        )
                    )
                }
            }
            isProgressCompleted -> {
                Box(
                    modifier = Modifier
                        .size(animatedInnerSize)
                        .clip(CircleShape)
                        .background(animatedInnerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Saved",
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
