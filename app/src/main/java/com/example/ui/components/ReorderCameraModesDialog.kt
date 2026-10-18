package com.example.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.core.model.CameraMode
import com.example.core.preferences.CameraPreferences
import kotlinx.coroutines.delay

@Composable
fun ReorderCameraModesDialog(
    visibleModeNames: List<String>,
    hiddenModeNames: List<String>,
    onSaveLayout: (visible: List<String>, hidden: List<String>) -> Unit,
    onResetDefault: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var visibleList by remember(visibleModeNames) { mutableStateOf(visibleModeNames) }
    var hiddenList by remember(hiddenModeNames) { mutableStateOf(hiddenModeNames) }

    var draggingSection by remember { mutableStateOf<String?>(null) } // "VISIBLE" or "HIDDEN"
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }
    var touchContainerY by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val currentVisibleList by rememberUpdatedState(visibleList)
    val currentHiddenList by rememberUpdatedState(hiddenList)

    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeightDp = 48.dp
    val spacingDp = 8.dp
    val slotHeightPx = with(density) { (rowHeightDp + spacingDp).toPx() }

    // Smooth auto-scroll when dragging near top or bottom edges of LazyColumn
    LaunchedEffect(draggingIndex, draggingSection) {
        if (draggingIndex == null || draggingSection == null) return@LaunchedEffect

        val scrollEdgeThreshold = with(density) { 60.dp.toPx() }
        val maxScrollSpeed = 16f

        while (true) {
            val isDragging = draggingIndex != null
            if (!isDragging) break

            val topEdgeDistance = touchContainerY
            val bottomEdgeDistance = containerHeightPx - touchContainerY

            var scrollAmount = 0f
            if (topEdgeDistance < scrollEdgeThreshold && lazyListState.canScrollBackward) {
                val factor = ((scrollEdgeThreshold - topEdgeDistance) / scrollEdgeThreshold).coerceIn(0f, 1f)
                scrollAmount = -maxScrollSpeed * factor - 4f
            } else if (bottomEdgeDistance < scrollEdgeThreshold && lazyListState.canScrollForward) {
                val factor = ((scrollEdgeThreshold - bottomEdgeDistance) / scrollEdgeThreshold).coerceIn(0f, 1f)
                scrollAmount = maxScrollSpeed * factor + 4f
            }

            if (scrollAmount != 0f) {
                lazyListState.scrollBy(scrollAmount)
                draggingOffset += scrollAmount
                touchContainerY += scrollAmount

                val currentIndex = draggingIndex ?: break
                val section = draggingSection
                val list = if (section == "VISIBLE") currentVisibleList else currentHiddenList

                if (draggingOffset > slotHeightPx * 0.5f && currentIndex < list.size - 1) {
                    val mutable = list.toMutableList()
                    val item = mutable.removeAt(currentIndex)
                    mutable.add(currentIndex + 1, item)
                    if (section == "VISIBLE") visibleList = mutable else hiddenList = mutable
                    draggingIndex = currentIndex + 1
                    draggingOffset -= slotHeightPx
                } else if (draggingOffset < -slotHeightPx * 0.5f && currentIndex > 0) {
                    val mutable = list.toMutableList()
                    val item = mutable.removeAt(currentIndex)
                    mutable.add(currentIndex - 1, item)
                    if (section == "VISIBLE") visibleList = mutable else hiddenList = mutable
                    draggingIndex = currentIndex - 1
                    draggingOffset += slotHeightPx
                }
            } else {
                delay(16)
            }
            delay(16)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reorder_modes_dialog"),
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Reorder Camera Modes",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Long-press or drag the handle (≡) on the left to reorder modes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .onGloballyPositioned { coordinates ->
                        containerHeightPx = coordinates.size.height.toFloat()
                    },
                verticalArrangement = Arrangement.spacedBy(spacingDp)
            ) {
                // VISIBLE MODES HEADER
                item(key = "HEADER_VISIBLE") {
                    Text(
                        text = "MAIN CAMERA SCREEN (${visibleList.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                // VISIBLE MODE ROWS
                itemsIndexed(
                    items = visibleList,
                    key = { _, modeName -> "VISIBLE_$modeName" }
                ) { index, modeName ->
                    val mode = runCatching { CameraMode.valueOf(modeName) }.getOrNull()
                    val displayName = mode?.displayName ?: modeName
                    val isDragging = draggingSection == "VISIBLE" && draggingIndex == index

                    Surface(
                        color = if (isDragging) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeightDp)
                            .zIndex(if (isDragging) 10f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = draggingOffset
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Left Drag Handle (≡) - Supports long press and smooth vertical dragging
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .pointerInput(Unit) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    draggingSection = "VISIBLE"
                                                    draggingIndex = index
                                                    draggingOffset = 0f
                                                    touchContainerY = offset.y
                                                },
                                                onDragEnd = {
                                                    draggingSection = null
                                                    draggingIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingSection = null
                                                    draggingIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    if (draggingSection != "VISIBLE") return@detectDragGesturesAfterLongPress
                                                    val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                    draggingOffset += dragAmount.y
                                                    touchContainerY += dragAmount.y
                                                    val list = currentVisibleList

                                                    if (draggingOffset > slotHeightPx * 0.5f && currentIndex < list.size - 1) {
                                                        val mutable = list.toMutableList()
                                                        val item = mutable.removeAt(currentIndex)
                                                        mutable.add(currentIndex + 1, item)
                                                        visibleList = mutable
                                                        draggingIndex = currentIndex + 1
                                                        draggingOffset -= slotHeightPx
                                                    } else if (draggingOffset < -slotHeightPx * 0.5f && currentIndex > 0) {
                                                        val mutable = list.toMutableList()
                                                        val item = mutable.removeAt(currentIndex)
                                                        mutable.add(currentIndex - 1, item)
                                                        visibleList = mutable
                                                        draggingIndex = currentIndex - 1
                                                        draggingOffset += slotHeightPx
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DragHandle,
                                        contentDescription = "Reorder $displayName",
                                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Hide (−) button
                            IconButton(
                                onClick = {
                                    if (visibleList.size > 1) {
                                        val newVisible = visibleList.toMutableList()
                                        val removed = newVisible.removeAt(index)
                                        visibleList = newVisible
                                        hiddenList = hiddenList + removed
                                    }
                                },
                                enabled = visibleList.size > 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.RemoveCircleOutline,
                                    contentDescription = "Hide Mode",
                                    tint = if (visibleList.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // HIDDEN MODES HEADER
                item(key = "HEADER_HIDDEN") {
                    Text(
                        text = "HIDDEN UNDER 'MORE' (${hiddenList.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }

                if (hiddenList.isEmpty()) {
                    item(key = "HIDDEN_EMPTY_NOTICE") {
                        Text(
                            text = "All camera modes are visible on the main screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    // HIDDEN MODE ROWS
                    itemsIndexed(
                        items = hiddenList,
                        key = { _, modeName -> "HIDDEN_$modeName" }
                    ) { index, modeName ->
                        val mode = runCatching { CameraMode.valueOf(modeName) }.getOrNull()
                        val displayName = mode?.displayName ?: modeName
                        val isDragging = draggingSection == "HIDDEN" && draggingIndex == index

                        Surface(
                            color = if (isDragging) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = if (isDragging) 8.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeightDp)
                                .zIndex(if (isDragging) 10f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = draggingOffset
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Left Drag Handle (≡) - Supports long press and smooth vertical dragging
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .pointerInput(Unit) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { offset ->
                                                        draggingSection = "HIDDEN"
                                                        draggingIndex = index
                                                        draggingOffset = 0f
                                                        touchContainerY = offset.y
                                                    },
                                                    onDragEnd = {
                                                        draggingSection = null
                                                        draggingIndex = null
                                                        draggingOffset = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggingSection = null
                                                        draggingIndex = null
                                                        draggingOffset = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (draggingSection != "HIDDEN") return@detectDragGesturesAfterLongPress
                                                        val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                        draggingOffset += dragAmount.y
                                                        touchContainerY += dragAmount.y
                                                        val list = currentHiddenList

                                                        if (draggingOffset > slotHeightPx * 0.5f && currentIndex < list.size - 1) {
                                                            val mutable = list.toMutableList()
                                                            val item = mutable.removeAt(currentIndex)
                                                            mutable.add(currentIndex + 1, item)
                                                            hiddenList = mutable
                                                            draggingIndex = currentIndex + 1
                                                            draggingOffset -= slotHeightPx
                                                        } else if (draggingOffset < -slotHeightPx * 0.5f && currentIndex > 0) {
                                                            val mutable = list.toMutableList()
                                                            val item = mutable.removeAt(currentIndex)
                                                            mutable.add(currentIndex - 1, item)
                                                            hiddenList = mutable
                                                            draggingIndex = currentIndex - 1
                                                            draggingOffset += slotHeightPx
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DragHandle,
                                            contentDescription = "Reorder hidden $displayName",
                                            tint = if (isDragging) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDragging) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Show (+) button
                                IconButton(
                                    onClick = {
                                        val newHidden = hiddenList.toMutableList()
                                        val removed = newHidden.removeAt(index)
                                        hiddenList = newHidden
                                        visibleList = visibleList + removed
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AddCircleOutline,
                                        contentDescription = "Show Mode",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveLayout(visibleList, hiddenList)
                    onDismissRequest()
                },
                modifier = Modifier.testTag("save_reorder_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        visibleList = CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES
                        hiddenList = CameraPreferences.DEFAULT_HIDDEN_MODE_NAMES
                        onResetDefault()
                    },
                    modifier = Modifier.testTag("reset_reorder_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset")
                }

                Spacer(modifier = Modifier.width(4.dp))

                TextButton(
                    onClick = onDismissRequest
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}

