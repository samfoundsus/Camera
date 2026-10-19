package com.example.feature.preview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.example.core.preferences.CameraFilterPreset
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdrMode
import com.example.ui.theme.CameraMotionTokens

/**
 * Minimal camera top bar inspired by Pixel design.
 * Contains active camera controls (Flash, Quick Controls drawer, Filters, Settings).
 */
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
            // Flash Mode Anchor & Button
            val (flashIcon, flashDesc) = when (effectiveFlashMode) {
                FlashMode.AUTO -> Pair(Icons.Outlined.FlashAuto, "Flash Auto")
                FlashMode.ON -> Pair(Icons.Outlined.FlashOn, "Flash On")
                FlashMode.OFF -> Pair(Icons.Outlined.FlashOff, "Flash Off")
                FlashMode.TORCH -> Pair(Icons.Outlined.Highlight, "Flash Torch")
            }

            val flashInteractionSource = remember { MutableInteractionSource() }
            val isFlashPressed by flashInteractionSource.collectIsPressedAsState()
            val flashScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isFlashPressed) 0.90f else 1.0f,
                animationSpec = CameraMotionTokens.touchSpring(),
                label = "flash_btn_scale"
            )
            val animatedFlashBg by androidx.compose.animation.animateColorAsState(
                targetValue = if (effectiveFlashMode == FlashMode.OFF) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                animationSpec = CameraMotionTokens.controlTween(),
                label = "flash_bg"
            )
            val animatedFlashIconTint by androidx.compose.animation.animateColorAsState(
                targetValue = if (effectiveFlashMode == FlashMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                animationSpec = CameraMotionTokens.controlTween(),
                label = "flash_icon_tint"
            )

            Box(
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        showFlashMenu = true
                    },
                    interactionSource = flashInteractionSource,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(flashScale)
                        .testTag("top_bar_flash_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(animatedFlashBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = flashIcon,
                            contentDescription = flashDesc,
                            tint = animatedFlashIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Compact Flash Popup anchored directly to the flash icon
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
                                FlashMode.TORCH -> Icons.Outlined.Highlight
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
                                        contentDescription = null,
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

                IconButton(
                    onClick = onFiltersClick,
                    interactionSource = filterInteractionSource,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(filterScale)
                        .testTag("top_bar_filters_button")
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
                                "Color filters"
                            } else {
                                "Color filter: ${selectedFilter.displayName}"
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
                        contentDescription = "Open Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
