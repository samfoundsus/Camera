package com.example.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.core.model.CameraMode
import com.example.core.preferences.CameraPreferences
import kotlinx.coroutines.launch

private data class ReorderModeEntry(
    val mode: CameraMode,
    val isVisible: Boolean
)

/**
 * Dedicated full-screen editor for reordering camera modes via drag-and-drop
 * and showing/hiding individual modes with Material 3 switches.
 * Enforces that at least one camera mode always stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderCameraModesScreen(
    visibleModeNames: List<String>,
    hiddenModeNames: List<String>,
    onSaveLayout: (visible: List<String>, hidden: List<String>) -> Unit,
    onResetDefault: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun buildEntries(visNames: List<String>, hidNames: List<String>): List<ReorderModeEntry> {
        val visibleModes = visNames.mapNotNull { runCatching { CameraMode.valueOf(it) }.getOrNull() }
            .distinct()
            .ifEmpty { listOf(CameraMode.PHOTO) }
        val hiddenModes = hidNames.mapNotNull { runCatching { CameraMode.valueOf(it) }.getOrNull() }
            .distinct()
            .filter { it !in visibleModes }
        val remaining = CameraMode.entries.filter { it !in visibleModes && it !in hiddenModes }
        return buildList {
            visibleModes.forEach { add(ReorderModeEntry(it, isVisible = true)) }
            (hiddenModes + remaining).forEach { add(ReorderModeEntry(it, isVisible = false)) }
        }
    }

    val entries = remember {
        mutableStateListOf<ReorderModeEntry>().apply {
            addAll(buildEntries(visibleModeNames, hiddenModeNames))
        }
    }

    var draggingMode by remember { mutableStateOf<CameraMode?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    // Keep local list synchronized if external state resets (when not actively dragging)
    LaunchedEffect(visibleModeNames, hiddenModeNames) {
        if (draggingMode == null) {
            val currentVisible = entries.filter { it.isVisible }.map { it.mode.name }
            val currentHidden = entries.filter { !it.isVisible }.map { it.mode.name }
            if (currentVisible != visibleModeNames || currentHidden != hiddenModeNames) {
                entries.clear()
                entries.addAll(buildEntries(visibleModeNames, hiddenModeNames))
            }
        }
    }

    fun persistCurrentEntries() {
        val visible = entries.filter { it.isVisible }.map { it.mode.name }
        val hidden = entries.filter { !it.isVisible }.map { it.mode.name }
        if (visible.isNotEmpty()) {
            onSaveLayout(visible, hidden)
        }
    }

    fun handleVerticalDrag(dragAmountY: Float) {
        val activeMode = draggingMode ?: return
        val currentIndex = entries.indexOfFirst { it.mode == activeMode }
        if (currentIndex < 0) return

        dragDeltaY += dragAmountY

        val currentItemInfo = listState.layoutInfo.visibleItemsInfo.find { it.key == activeMode.name }
        val itemHeightPx = (currentItemInfo?.size ?: 168).toFloat().coerceAtLeast(80f)
        val swapThreshold = itemHeightPx * 0.55f

        if (dragDeltaY > swapThreshold && currentIndex < entries.lastIndex) {
            val moved = entries.removeAt(currentIndex)
            entries.add(currentIndex + 1, moved)
            dragDeltaY -= itemHeightPx
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (dragDeltaY < -swapThreshold && currentIndex > 0) {
            val moved = entries.removeAt(currentIndex)
            entries.add(currentIndex - 1, moved)
            dragDeltaY += itemHeightPx
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        // Gentle auto-scroll near viewport top/bottom edges
        val layoutInfo = listState.layoutInfo
        if (currentItemInfo != null) {
            val itemTop = currentItemInfo.offset + dragDeltaY
            val itemBottom = itemTop + currentItemInfo.size
            val edgeMargin = 100f
            when {
                itemBottom > layoutInfo.viewportEndOffset - edgeMargin -> {
                    coroutineScope.launch { listState.scrollBy(16f) }
                }
                itemTop < layoutInfo.viewportStartOffset + edgeMargin -> {
                    coroutineScope.launch { listState.scrollBy(-16f) }
                }
            }
        }
    }

    val visibleCount = entries.count { it.isVisible }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("reorder_modes_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Reorder Camera Modes",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("reorder_modes_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Navigate back to Camera Settings"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            entries.clear()
                            entries.addAll(
                                buildEntries(
                                    CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES,
                                    CameraPreferences.DEFAULT_HIDDEN_MODE_NAMES
                                )
                            )
                            onResetDefault()
                        },
                        modifier = Modifier.testTag("reset_reorder_button")
                    ) {
                        Text("Reset")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
            ) {
                Text(
                    text = "Drag the handle to change order. Toggle modes to show them in the bar or move them to More.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )

                if (visibleCount <= 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp)
                            .testTag("reorder_min_visible_notice")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "At least one mode must stay visible",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("reorder_modes_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = entries,
                        key = { _, item -> item.mode.name }
                    ) { _, entry ->
                        val isDragging = draggingMode == entry.mode
                        val elevation by animateDpAsState(
                            targetValue = if (isDragging) 6.dp else 0.dp,
                            label = "reorder_card_elevation"
                        )
                        val isLastVisibleMode = entry.isVisible && visibleCount <= 1

                        Surface(
                            color = when {
                                isDragging -> MaterialTheme.colorScheme.primaryContainer
                                entry.isVisible -> MaterialTheme.colorScheme.surfaceContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = if (isDragging) 4.dp else 1.dp,
                            shadowElevation = elevation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragDeltaY else 0f
                                    scaleX = if (isDragging) 1.015f else 1f
                                    scaleY = if (isDragging) 1.015f else 1f
                                }
                                .pointerInput(entry.mode) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingMode = entry.mode
                                            dragDeltaY = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            handleVerticalDrag(dragAmount.y)
                                        },
                                        onDragEnd = {
                                            draggingMode = null
                                            dragDeltaY = 0f
                                            persistCurrentEntries()
                                        },
                                        onDragCancel = {
                                            draggingMode = null
                                            dragDeltaY = 0f
                                            persistCurrentEntries()
                                        }
                                    )
                                }
                                .testTag("reorder_mode_item_${entry.mode.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 64.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Direct vertical drag handle (48dp touch target)
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .pointerInput(entry.mode) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingMode = entry.mode
                                                    dragDeltaY = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    handleVerticalDrag(dragAmount.y)
                                                },
                                                onDragEnd = {
                                                    draggingMode = null
                                                    dragDeltaY = 0f
                                                    persistCurrentEntries()
                                                },
                                                onDragCancel = {
                                                    draggingMode = null
                                                    dragDeltaY = 0f
                                                    persistCurrentEntries()
                                                }
                                            )
                                        }
                                        .testTag("reorder_drag_handle_${entry.mode.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DragHandle,
                                        contentDescription = "Drag to reorder ${entry.mode.displayName}",
                                        tint = if (isDragging) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = entry.mode.displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (entry.isVisible) FontWeight.Medium else FontWeight.Normal
                                        ),
                                        color = when {
                                            isDragging -> MaterialTheme.colorScheme.onPrimaryContainer
                                            entry.isVisible -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (entry.isVisible) "Shown in mode bar" else "Hidden in More",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDragging) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Switch(
                                    checked = entry.isVisible,
                                    enabled = !isLastVisibleMode,
                                    onCheckedChange = { shouldShow ->
                                        val idx = entries.indexOfFirst { it.mode == entry.mode }
                                        if (idx < 0) return@Switch
                                        if (!shouldShow && visibleCount <= 1) return@Switch
                                        entries[idx] = entries[idx].copy(isVisible = shouldShow)
                                        persistCurrentEntries()
                                    },
                                    modifier = Modifier
                                        .semantics {
                                            contentDescription = if (entry.isVisible) {
                                                "Hide ${entry.mode.displayName} mode"
                                            } else {
                                                "Show ${entry.mode.displayName} mode"
                                            }
                                        }
                                        .testTag("reorder_mode_toggle_${entry.mode.name.lowercase()}")
                                )
                            }
                        }
                    }

                    item(key = "REORDER_BOTTOM_SPACER") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
