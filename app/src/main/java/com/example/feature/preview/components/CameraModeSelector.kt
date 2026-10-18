package com.example.feature.preview.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.CameraMode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Pixel Camera horizontal mode selector with customizable modes and More menu support.
 *
 * Ensures the currently selected camera mode is always horizontally centered in the screen viewport.
 * Uses LazyRow with content padding equal to half of the available viewport width so that
 * any item can be centered precisely without edge clipping.
 */
@Composable
fun CameraModeSelector(
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier,
    visibleModes: List<CameraMode> = CameraMode.entries,
    hiddenModes: List<CameraMode> = emptyList(),
    onMoreClick: () -> Unit = {}
) {
    // If selectedMode is in hiddenModes, temporarily append it so it stays accessible & centered
    val displayedModes = remember(visibleModes, hiddenModes, selectedMode) {
        if (selectedMode !in visibleModes && selectedMode in hiddenModes) {
            visibleModes + selectedMode
        } else {
            visibleModes
        }
    }

    val lazyListState = rememberLazyListState()
    var isInitialLaunch by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_mode_selector"),
        contentAlignment = Alignment.Center
    ) {
        val halfViewportWidth = maxWidth / 2

        LaunchedEffect(selectedMode, displayedModes) {
            val targetIndex = displayedModes.indexOf(selectedMode)
            if (targetIndex < 0) return@LaunchedEffect

            // Wait until layout pass has determined the viewport bounds
            if (lazyListState.layoutInfo.viewportEndOffset <= lazyListState.layoutInfo.viewportStartOffset) {
                snapshotFlow { lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset }
                    .filter { it > 0 }
                    .first()
            }

            if (isInitialLaunch) {
                // On initial launch: bring target item into view and center immediately without animation
                var targetItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                if (targetItem == null) {
                    lazyListState.scrollToItem(targetIndex)
                    targetItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                }

                if (targetItem != null) {
                    val layoutInfo = lazyListState.layoutInfo
                    val itemCenter = targetItem.offset + targetItem.size / 2
                    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                    val correction = itemCenter - viewportCenter
                    if (correction != 0) {
                        lazyListState.scrollBy(correction.toFloat())
                    }

                    // Recalculate after layout settles to guarantee exact horizontal centering
                    val postLayout = lazyListState.layoutInfo
                    val postItem = postLayout.visibleItemsInfo.find { it.index == targetIndex }
                    if (postItem != null) {
                        val postItemCenter = postItem.offset + postItem.size / 2
                        val postViewportCenter = (postLayout.viewportStartOffset + postLayout.viewportEndOffset) / 2
                        val remaining = postItemCenter - postViewportCenter
                        if (remaining != 0) {
                            lazyListState.scrollBy(remaining.toFloat())
                        }
                    }
                }
                isInitialLaunch = false
            } else {
                // Switching modes: animate smoothly to the exact horizontal center
                var targetItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                if (targetItem == null) {
                    lazyListState.animateScrollToItem(targetIndex)
                    targetItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                }

                if (targetItem != null) {
                    val layoutInfo = lazyListState.layoutInfo
                    val itemCenter = targetItem.offset + targetItem.size / 2
                    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                    val correction = itemCenter - viewportCenter

                    if (correction != 0) {
                        lazyListState.animateScrollBy(
                            value = correction.toFloat(),
                            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                        )
                    }

                    // Recalculate after layout settles to eliminate any fractional remainder
                    val postLayout = lazyListState.layoutInfo
                    val postItem = postLayout.visibleItemsInfo.find { it.index == targetIndex }
                    if (postItem != null) {
                        val postItemCenter = postItem.offset + postItem.size / 2
                        val postViewportCenter = (postLayout.viewportStartOffset + postLayout.viewportEndOffset) / 2
                        val remaining = postItemCenter - postViewportCenter
                        if (remaining != 0) {
                            lazyListState.scrollBy(remaining.toFloat())
                        }
                    }
                }
            }
        }

        LazyRow(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = halfViewportWidth, end = halfViewportWidth),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = displayedModes,
                key = { _, mode -> mode.name }
            ) { _, mode ->
                val isSelected = mode == selectedMode

                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { onModeSelected(mode) }
                        .testTag("mode_item_${mode.name.lowercase()}")
                ) {
                    Text(
                        text = mode.displayName,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Render 'More' option button at the end if there are hidden modes
            if (hiddenModes.isNotEmpty()) {
                item(key = "MORE_OPTION_KEY") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clickable { onMoreClick() }
                            .testTag("mode_item_more")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "More Modes",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "More",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
