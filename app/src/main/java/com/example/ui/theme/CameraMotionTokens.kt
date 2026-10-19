package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState

/**
 * Centralized Camera-inspired motion tokens and animation specifications.
 * Defines standard durations, easing curves, spring physics, and interaction modifiers
 * aligned with Material 3 Expressive Motion guidelines.
 */
object CameraMotionTokens {
    // Standard Duration Tokens (ms)
    const val DURATION_TOUCH_FEEDBACK = 100
    const val DURATION_CONTROL_TRANSITION = 160
    const val DURATION_PANEL_TRANSITION = 200
    const val DURATION_CAMERA_SWITCH = 240
    const val DURATION_SCREEN_ENTER = 260
    const val DURATION_SCREEN_EXIT = 240
    const val DURATION_CAPTURE_CONFIRMATION = 140

    // Standard Easing Curves
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val StandardFastOutSlowIn: Easing = FastOutSlowInEasing
    val StandardLinearOutSlowIn: Easing = LinearOutSlowInEasing

    // Standard Animation Specs
    fun <T> touchSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> stiffSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )

    fun <T> controlTween(durationMillis: Int = DURATION_CONTROL_TRANSITION) = tween<T>(
        durationMillis = durationMillis,
        easing = StandardFastOutSlowIn
    )

    fun <T> panelTween(durationMillis: Int = DURATION_PANEL_TRANSITION) = tween<T>(
        durationMillis = durationMillis,
        easing = EmphasizedEasing
    )

    fun <T> screenEnterTween() = tween<T>(
        durationMillis = DURATION_SCREEN_ENTER,
        easing = StandardFastOutSlowIn
    )

    fun <T> screenExitTween() = tween<T>(
        durationMillis = DURATION_SCREEN_EXIT,
        easing = StandardFastOutSlowIn
    )
}

/**
 * Reusable modifier adding subtle touch-scale feedback (approx 0.95–1.0)
 * without making controls feel overly bouncy, adhering to camera micro-interactions.
 */
fun Modifier.pressFeedback(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit = {}
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1.0f,
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "press_feedback_scale"
    )

    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            onClick = onClick
        )
}
