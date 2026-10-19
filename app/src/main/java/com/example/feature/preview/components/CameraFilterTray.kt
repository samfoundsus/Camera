package com.example.feature.preview.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.preferences.CameraFilterCategory
import com.example.core.preferences.CameraFilterPreset
import com.example.core.processing.CameraFilterProcessor
import com.example.ui.theme.CameraMotionTokens

/**
 * Compact, horizontally browsable Filter Selector Tray.
 *
 * Groups presets into Natural (Pixel-inspired) and Creative categories, displays
 * cached thumbnails processed through [CameraFilterProcessor], highlights the active
 * filter using Material You dynamic colors, and keeps the shutter and primary camera
 * controls accessible.
 */
@Composable
fun CameraFilterTray(
    selectedFilter: CameraFilterPreset,
    onFilterSelected: (CameraFilterPreset) -> Unit,
    onResetFilter: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCategoryName by rememberSaveable(selectedFilter) {
        mutableStateOf(selectedFilter.category.name)
    }
    val activeCategory = remember(activeCategoryName) {
        runCatching { CameraFilterCategory.valueOf(activeCategoryName) }
            .getOrDefault(CameraFilterCategory.NATURAL)
    }

    val categoryPresets = remember(activeCategory) {
        CameraFilterProcessor.getPresetsByCategory(activeCategory)
    }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(activeCategory, selectedFilter) {
        val targetIndex = categoryPresets.indexOf(selectedFilter)
        if (targetIndex >= 0) {
            runCatching {
                lazyListState.animateScrollToItem(index = (targetIndex - 1).coerceAtLeast(0))
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("camera_filter_tray")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Header: Category Switcher (Natural / Creative), Active Preset Subtitle, Reset & Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category selector pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CameraFilterCategory.entries.forEach { category ->
                        val isCategorySelected = category == activeCategory
                        val categoryHasActiveFilter = !selectedFilter.isOriginal && selectedFilter.category == category
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val tabScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.94f else 1.0f,
                            animationSpec = CameraMotionTokens.touchSpring(),
                            label = "filter_cat_scale"
                        )
                        val bgColor by animateColorAsState(
                            targetValue = if (isCategorySelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            animationSpec = CameraMotionTokens.controlTween(),
                            label = "filter_cat_bg"
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (isCategorySelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = CameraMotionTokens.controlTween(),
                            label = "filter_cat_content"
                        )

                        Surface(
                            color = bgColor,
                            shape = CircleShape,
                            modifier = Modifier
                                .scale(tabScale)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = ripple(bounded = true),
                                    onClick = { activeCategoryName = category.name }
                                )
                                .testTag("filter_category_${category.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = category.displayName,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isCategorySelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = contentColor
                                )
                                if (categoryHasActiveFilter) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                }

                // Right actions: Reset to Original & Close tray
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedVisibility(
                        visible = !selectedFilter.isOriginal,
                        enter = fadeIn(CameraMotionTokens.controlTween()) + scaleIn(
                            initialScale = 0.9f,
                            animationSpec = CameraMotionTokens.controlTween()
                        ),
                        exit = fadeOut(CameraMotionTokens.controlTween()) + scaleOut(
                            targetScale = 0.9f,
                            animationSpec = CameraMotionTokens.controlTween()
                        )
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClick = onResetFilter)
                                .testTag("filter_reset_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Reset filter to Original",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Original",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("filter_tray_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close filters tray",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Active Filter Name & Subtle Description Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedFilter.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("filter_active_name_label")
                )
                Text(
                    text = selectedFilter.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Horizontally Browsable Filter Presets with Representative Filtered Thumbnails
            LazyRow(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filter_presets_lazy_row"),
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(
                    items = categoryPresets,
                    key = { _, preset -> preset.id }
                ) { _, preset ->
                    FilterPresetThumbnailCard(
                        preset = preset,
                        isSelected = preset == selectedFilter,
                        onClick = { onFilterSelected(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPresetThumbnailCard(
    preset: CameraFilterPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "filter_card_scale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.5.dp else 1.dp,
        animationSpec = CameraMotionTokens.controlTween(),
        label = "filter_border_width"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        animationSpec = CameraMotionTokens.controlTween(),
        label = "filter_border_color"
    )
    val labelBgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = CameraMotionTokens.controlTween(),
        label = "filter_label_bg"
    )
    val labelTextColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = CameraMotionTokens.controlTween(),
        label = "filter_label_text"
    )

    val thumbnailBitmap = remember(preset) {
        CameraFilterProcessor.getOrCreateThumbnail(preset, sizePx = 88)
    }

    Column(
        modifier = Modifier
            .width(68.dp)
            .scale(cardScale)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(vertical = 2.dp)
            .testTag("filter_item_${preset.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp)
                )
                .testTag("filter_thumbnail_${preset.id}"),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = thumbnailBitmap.asImageBitmap(),
                contentDescription = "${preset.displayName} filter preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Selected filter",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        Surface(
            color = labelBgColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 10.sp
                ),
                color = labelTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}
