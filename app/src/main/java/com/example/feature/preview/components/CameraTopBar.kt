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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.example.core.preferences.FlashMode

/**
 * Minimal camera top bar inspired by Google Pixel Camera.
 * Contains only active camera controls (Flash, Quick Controls / Settings drawer, Settings).
 * Completely free of debug or build diagnostics.
 */
@Composable
fun CameraTopBar(
    flashMode: FlashMode,
    onFlashModeSelected: (FlashMode) -> Unit = {},
    onCycleFlash: () -> Unit = {},
    onQuickControlsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFlashSupported: Boolean = true,
    isVideoMode: Boolean = false,
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

            Box(
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        showFlashMenu = true
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top_bar_flash_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (effectiveFlashMode == FlashMode.OFF) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = flashIcon,
                            contentDescription = flashDesc,
                            tint = if (effectiveFlashMode == FlashMode.OFF) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
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

                            DropdownMenuItem(
                                enabled = isOptionEnabled,
                                text = {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            !isOptionEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
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
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                            )
                        }
                    }
                }
            }

            // Quick Controls Drawer Toggle
            IconButton(
                onClick = onQuickControlsClick,
                modifier = Modifier
                    .size(48.dp)
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

            // Full Settings Button
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(48.dp)
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
