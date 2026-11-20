package com.example.feature.preview.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.core.preferences.CameraFilterPreset
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdrMode
import com.example.ui.theme.CameraMotionTokens
import kotlinx.coroutines.delay

/**
 * Minimal camera top bar inspired by Pixel design.
 * Contains active camera controls (Flash with live state indicator, Quick Controls drawer,
 * Filters with tap/long-press label hint, and Settings).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraTopBar(
    flashMode: FlashMode,
    onFlashModeSelected: (FlashMode) -> Unit = {},
    onCycleFlash: () -> Unit = {},
    hdrMode: HdrMode = HdrMode.AUTO,
    onCycleHdr: () -> Unit = {},
    onQuickControlsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFlashSupported: Boolean = true,
    isVideoMode: Boolean = false,
    selectedFilter: CameraFilterPreset = CameraFilterPreset.ORIGINAL,
    isFilterTrayOpen: Boolean = false,
    isFiltersSupported: Boolean = true,
    onFiltersClick: () -> Unit = {},
    onInfoClick: (() -> Unit)? = null
) {
    var showFlashMenu by remember { mutableStateOf(false) }
    var filtersHintTrigger by remember { mutableIntStateOf(0) }
    var showFiltersHint by remember { mutableStateOf(false) }

    LaunchedEffect(filtersHintTrigger) {
        if (filtersHintTrigger > 0) {
            showFiltersHint = true
            delay(1500L)
            showFiltersHint = false
        }
    }

    val effectiveFlashMode = when {
        !isFlashSupported -> FlashMode.OFF
        isVideoMode && flashMode != FlashMode.TORCH -> FlashMode.OFF
        else -> flashMode
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
        shape = CircleShape,
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_top_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Flash Mode Anchor & Pill showing real state (Auto, On, Off, Torch)
            val flashIcon = when (effectiveFlashMode) {
                FlashMode.AUTO -> Icons.Outlined.FlashAuto
                FlashMode.ON -> Icons.Outlined.FlashOn
                FlashMode.OFF -> Icons.Outlined.FlashOff
                FlashMode.TORCH -> Icons.Outlined.FlashlightOn
            }
            val flashStateLabel = effectiveFlashMode.displayName
            val flashDesc = "Flash: $flashStateLabel"

            val flashInteractionSource = remember { MutableInteractionSource() }
            val isFlashPressed by flashInteractionSource.collectIsPressedAsState()
            val flashScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isFlashPressed) 0.92f else 1.0f,
                animationSpec = CameraMotionTokens.touchSpring(),
                label = "flash_btn_scale"
            )
            val animatedFlashBg by androidx.compose.animation.animateColorAsState(
                targetValue = when (effectiveFlashMode) {
                    FlashMode.OFF -> MaterialTheme.colorScheme.surfaceContainerHigh
                    FlashMode.AUTO -> MaterialTheme.colorScheme.secondaryContainer
                    FlashMode.ON -> MaterialTheme.colorScheme.primaryContainer
                    FlashMode.TORCH -> MaterialTheme.colorScheme.tertiaryContainer
                },
                animationSpec = CameraMotionTokens.controlTween(),
                label = "flash_bg"
            )
            val animatedFlashContentColor by androidx.compose.animation.animateColorAsState(
                targetValue = when (effectiveFlashMode) {
                    FlashMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                    FlashMode.AUTO -> MaterialTheme.colorScheme.onSecondaryContainer
                    FlashMode.ON -> MaterialTheme.colorScheme.onPrimaryContainer
                    FlashMode.TORCH -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                animationSpec = CameraMotionTokens.controlTween(),
                label = "flash_icon_tint"
            )

            Box(
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .scale(flashScale)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = flashInteractionSource,
                            indication = ripple(bounded = true),
                            role = Role.Button,
                            onClick = { showFlashMenu = true }
                        )
                        .padding(horizontal = 4.dp)
                        .testTag("top_bar_flash_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(animatedFlashBg)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = flashIcon,
                            contentDescription = flashDesc,
                            tint = animatedFlashContentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = flashStateLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = animatedFlashContentColor,
                            maxLines = 1,
                            modifier = Modifier.testTag("top_bar_flash_state_label")
                        )
                    }
                }

                // Compact Flash Popup anchored directly to the flash button
                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
                ) {
                    DropdownMenu(
                        expanded = showFlashMenu,
                        onDismissRequest = { showFlashMenu = false },
                        offset = DpOffset(x = 0.dp, y = 4.dp),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .testTag("flash_popup_menu")
                    ) {
                        val menuOptions = if (isVideoMode) {
                            listOf(FlashMode.OFF, FlashMode.TORCH)
                        } else {
                            FlashMode.entries
                        }

                        menuOptions.forEach { mode ->
                            val isOptionEnabled = mode == FlashMode.OFF || isFlashSupported
                            val isSelected = mode == effectiveFlashMode && isOptionEnabled
                            val itemIcon = when (mode) {
                                FlashMode.AUTO -> Icons.Outlined.FlashAuto
                                FlashMode.ON -> Icons.Outlined.FlashOn
                                FlashMode.OFF -> Icons.Outlined.FlashOff
                                FlashMode.TORCH -> Icons.Outlined.FlashlightOn
                            }

                            val animatedItemBg by androidx.compose.animation.animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    Color.Transparent
                                },
                                animationSpec = CameraMotionTokens.controlTween(),
                                label = "flash_item_bg_${mode.name}"
                            )
                            val animatedItemColor by androidx.compose.animation.animateColorAsState(
                                targetValue = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    !isOptionEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                animationSpec = CameraMotionTokens.controlTween(),
                                label = "flash_item_text_${mode.name}"
                            )

                            DropdownMenuItem(
                                enabled = isOptionEnabled,
                                text = {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = animatedItemColor
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = itemIcon,
                                        contentDescription = "Flash ${mode.displayName}",
                                        tint = when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            !isOptionEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    if (isOptionEnabled) {
                                        onFlashModeSelected(mode)
                                        showFlashMenu = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("flash_option_${mode.name.lowercase()}")
                                    .background(animatedItemBg)
                            )
                        }
                    }
                }
            }

            // Quick Controls Drawer Toggle
            val qcInteractionSource = remember { MutableInteractionSource() }
            val isQcPressed by qcInteractionSource.collectIsPressedAsState()
            val qcScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isQcPressed) 0.90f else 1.0f,
                animationSpec = CameraMotionTokens.touchSpring(),
                label = "qc_btn_scale"
            )
            IconButton(
                onClick = onQuickControlsClick,
                interactionSource = qcInteractionSource,
                modifier = Modifier
                    .size(48.dp)
                    .scale(qcScale)
                    .testTag("top_bar_quick_controls_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Quick camera controls",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Filters Control Toggle (Natural & Creative Presets) - Enabled strictly in PHOTO and VIDEO modes
            if (isFiltersSupported) {
                val isFilterActive = !selectedFilter.isOriginal || isFilterTrayOpen
                val filterInteractionSource = remember { MutableInteractionSource() }
                val isFilterPressed by filterInteractionSource.collectIsPressedAsState()
                val filterScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isFilterPressed) 0.90f else 1.0f,
                    animationSpec = CameraMotionTokens.touchSpring(),
                    label = "filter_btn_scale"
                )
                val animatedFilterBg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isFilterActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    animationSpec = CameraMotionTokens.controlTween(),
                    label = "filter_btn_bg"
                )
                val animatedFilterTint by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isFilterActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    animationSpec = CameraMotionTokens.controlTween(),
                    label = "filter_btn_tint"
                )

                val hintOffsetYPx = with(LocalDensity.current) { 52.dp.roundToPx() }

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .scale(filterScale)
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = filterInteractionSource,
                                indication = ripple(bounded = true, radius = 24.dp),
                                role = Role.Button,
                                onClick = {
                                    filtersHintTrigger++
                                    onFiltersClick()
                                },
                                onLongClick = {
                                    filtersHintTrigger++
                                }
                            )
                            .testTag("top_bar_filters_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(animatedFilterBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FilterVintage,
                                contentDescription = if (selectedFilter.isOriginal) {
                                    "Filters"
                                } else {
                                    "Filters: ${selectedFilter.displayName}"
                                },
                                tint = animatedFilterTint,
                                modifier = Modifier.size(20.dp)
                            )
                            if (!selectedFilter.isOriginal) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .testTag("top_bar_filter_active_dot")
                                )
                            }
                        }
                    }

                    if (showFiltersHint) {
                        Popup(
                            alignment = Alignment.TopCenter,
                            offset = IntOffset(x = 0, y = hintOffsetYPx),
                            properties = PopupProperties(focusable = false)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                shape = RoundedCornerShape(10.dp),
                                shadowElevation = 3.dp,
                                modifier = Modifier.testTag("top_bar_filters_hint_label")
                            ) {
                                Text(
                                    text = "Filters",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Full Settings Button
            val settingsInteractionSource = remember { MutableInteractionSource() }
            val isSettingsPressed by settingsInteractionSource.collectIsPressedAsState()
            val settingsScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isSettingsPressed) 0.90f else 1.0f,
                animationSpec = CameraMotionTokens.touchSpring(),
                label = "settings_btn_scale"
            )
            IconButton(
                onClick = onSettingsClick,
                interactionSource = settingsInteractionSource,
                modifier = Modifier
                    .size(48.dp)
                    .scale(settingsScale)
                    .testTag("top_bar_settings_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Camera settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
