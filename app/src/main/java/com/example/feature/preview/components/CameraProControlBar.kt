package com.example.feature.preview.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Iso
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import java.util.Locale

import androidx.compose.runtime.saveable.rememberSaveable

enum class ProTab(val key: String, val title: String, val icon: ImageVector) {
    ZOOM("ZOOM", "Lens", Icons.Outlined.ZoomIn),
    ISO("ISO", "ISO", Icons.Outlined.Iso),
    SHUTTER("SHUTTER", "Shutter", Icons.Outlined.ShutterSpeed),
    FOCUS("FOCUS", "Focus", Icons.Outlined.CenterFocusStrong),
    WB("WB", "WB", Icons.Outlined.WbSunny),
    EV("EV", "EV", Icons.Outlined.Contrast),
    RAW("RAW", "RAW", Icons.Outlined.RawOn),
    ASSIST("ASSIST", "Assist", Icons.Outlined.AutoAwesome);

    companion object {
        fun fromKey(key: String?): ProTab? = entries.find { it.key.equals(key, ignoreCase = true) }
    }
}

/**
 * Pixel Camera professional manual sensor controls bar.
 * Provides fine-grained ISO, Shutter Speed, Focus, White Balance, EV, Lens/Zoom, and RAW toggles.
 * Distinguishes user intent from verified hardware capabilities.
 */
@Composable
fun CameraProControlBar(
    preferences: CameraPreferences,
    capabilitiesState: CameraCapabilitiesState,
    onIsoChanged: (String) -> Unit,
    onShutterChanged: (String) -> Unit,
    onFocusModeChanged: (ProFocusMode) -> Unit,
    onFocusDistanceChanged: (Float) -> Unit,
    onWbChanged: (String) -> Unit,
    onEvChanged: (Float) -> Unit,
    onRawFormatChanged: (ProRawFormat) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    onHistogramToggled: (Boolean) -> Unit,
    onFocusPeakingToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectedProControl: String? = "ISO",
    onSelectProControl: (String?) -> Unit = {}
) {
    val activeTab = ProTab.fromKey(selectedProControl)

    val updateControlKey: (String?) -> Unit = { newKey ->
        onSelectProControl(newKey)
    }

    val isPendingDiscovery = capabilitiesState is CameraCapabilitiesState.PendingDiscovery

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_pro_control_bar")
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Expandable Parameter Value Selector
        activeTab?.let { tab ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pro_value_selector_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(com.example.ui.theme.CameraMotionTokens.panelTween())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Header with capability status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tab.title.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (isPendingDiscovery) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Camera2 engine query pending (Phase 2)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    val currentLensCaps = if (capabilitiesState is CameraCapabilitiesState.Verified) {
                        capabilitiesState.currentLensCapabilities
                    } else null

                    when (tab) {
                        ProTab.ZOOM -> {
                            val minZoom = currentLensCaps?.minZoomRatio ?: 1.0f
                            val maxZoom = (currentLensCaps?.maxZoomRatio ?: 4.0f).coerceAtLeast(minZoom)
                            val availableRatios = currentLensCaps?.zoomShortcuts
                                ?: com.example.core.camera.generateZoomShortcuts(minZoom, maxZoom)
                            val clampedProZoom = com.example.core.camera.clampZoomRatio(
                                preferences.proZoomRatio,
                                minZoom,
                                maxZoom
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val headerTitle = if (currentLensCaps != null && !currentLensCaps.isTarget20xZoomSupported) {
                                        "Zoom Ratio (Hardware Max ${com.example.core.camera.formatZoomRatio(maxZoom)})"
                                    } else {
                                        "Zoom Ratio & Lens Switch"
                                    }
                                    Text(
                                        text = headerTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = com.example.core.camera.formatZoomRatio(clampedProZoom, asCompactShortcut = false),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("pro_zoom_value_label")
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    availableRatios.forEach { zoomPoint ->
                                        val isSelected = Math.abs(clampedProZoom - zoomPoint) < 0.08f
                                        ProValuePill(
                                            text = com.example.core.camera.formatZoomRatio(zoomPoint, asCompactShortcut = true),
                                            isSelected = isSelected,
                                            onClick = {
                                                onZoomRatioChanged(
                                                    com.example.core.camera.clampZoomRatio(zoomPoint, minZoom, maxZoom)
                                                )
                                            },
                                            modifier = Modifier.testTag(
                                                "pro_zoom_shortcut_${String.format(Locale.US, "%.1f", zoomPoint).replace('.', '_')}x"
                                            )
                                        )
                                    }
                                }

                                if (maxZoom > minZoom) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = com.example.core.camera.formatZoomRatio(minZoom, asCompactShortcut = true),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = clampedProZoom.coerceIn(minZoom, maxZoom),
                                            onValueChange = { rawVal ->
                                                val rounded = Math.round(rawVal * 10f) / 10f
                                                onZoomRatioChanged(
                                                    com.example.core.camera.clampZoomRatio(rounded, minZoom, maxZoom)
                                                )
                                            },
                                            valueRange = minZoom..maxZoom.coerceAtLeast(minZoom + 0.1f),
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp)
                                                .testTag("pro_zoom_slider")
                                        )
                                        Text(
                                            text = com.example.core.camera.formatZoomRatio(maxZoom, asCompactShortcut = true),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        ProTab.ISO -> {
                            val hardwareIsoRange = currentLensCaps?.manualCapabilities?.isoRange
                            val isHardwareVerified = currentLensCaps?.manualCapabilities?.isManualIsoSupported == true && hardwareIsoRange != null

                            // Discover actual minimum and maximum supported ISO values from hardware
                            val actualMinIso = hardwareIsoRange?.start?.toFloat() ?: 100f
                            val actualMaxIso = hardwareIsoRange?.endInclusive?.toFloat() ?: 6400f

                            // Check if the device hardware supports ISO 50
                            val supportsIso50 = isHardwareVerified && (hardwareIsoRange.start <= 50 && 50 <= hardwareIsoRange.endInclusive)

                            // If the device supports ISO 50, make it the slider's minimum and initial value.
                            // If ISO 50 is unsupported, use the device's actual minimum supported ISO instead.
                            val minIso = if (supportsIso50) 50f else actualMinIso.coerceAtLeast(10f)
                            val maxIso = actualMaxIso.coerceAtLeast(minIso + 50f)

                            val isAutoIso = preferences.proIso.equals("AUTO", ignoreCase = true)
                            // Initial value for manual ISO is minIso (ISO 50 if supported, or actual minimum supported ISO if unsupported)
                            val currentIsoVal = preferences.proIso.toIntOrNull()?.toFloat() ?: minIso
                            val currentIsoT = if (maxIso > minIso) {
                                kotlin.math.ln(currentIsoVal.coerceIn(minIso, maxIso) / minIso) / kotlin.math.ln(maxIso / minIso)
                            } else 0f

                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ISO Sensitivity",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (isAutoIso) "ISO AUTO" else "ISO ${currentIsoVal.toInt()}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isAutoIso) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                        )
                                        ProValuePill(
                                            text = "AUTO",
                                            isSelected = isAutoIso,
                                            onClick = {
                                                if (isAutoIso) {
                                                    onIsoChanged(minIso.toInt().toString())
                                                } else {
                                                    onIsoChanged("AUTO")
                                                }
                                            },
                                            modifier = Modifier.testTag("pro_iso_auto_pill")
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = minIso.toInt().toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = if (isAutoIso) 0.0f else currentIsoT.coerceIn(0f, 1f),
                                        onValueChange = { t ->
                                            val roundedIso = if (t <= 0.001f) {
                                                minIso.toInt()
                                            } else if (t >= 0.999f) {
                                                maxIso.toInt()
                                            } else {
                                                val calcIso = minIso * Math.pow((maxIso / minIso).toDouble(), t.toDouble()).toFloat()
                                                (Math.round(calcIso / 10f) * 10).coerceIn(minIso.toInt(), maxIso.toInt())
                                            }
                                            onIsoChanged(roundedIso.toString())
                                        },
                                        valueRange = 0f..1f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                            .testTag("pro_iso_slider")
                                    )
                                    Text(
                                        text = maxIso.toInt().toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        ProTab.SHUTTER -> {
                            val shutterNanosRange = currentLensCaps?.manualCapabilities?.shutterSpeedRangeNanos ?: (250_000L..2_000_000_000L)
                            val minSec = (shutterNanosRange.start / 1_000_000_000.0).toFloat().coerceAtLeast(0.0001f)
                            val maxSec = (shutterNanosRange.endInclusive / 1_000_000_000.0).toFloat().coerceAtLeast(minSec * 2f)

                            val isAutoShutter = preferences.proShutterSpeed.equals("AUTO", ignoreCase = true)
                            val currentShutterSec = parseShutterSpeedToSeconds(preferences.proShutterSpeed)
                            val currentShutterNanos = com.example.core.camera.parseShutterSpeedToNanos(preferences.proShutterSpeed)
                            val isCurrentShutterUnsupported = !isAutoShutter && currentShutterNanos != null &&
                                (currentShutterNanos < shutterNanosRange.start || currentShutterNanos > shutterNanosRange.endInclusive)

                            val currentShutterT = kotlin.math.ln(currentShutterSec.coerceIn(minSec, maxSec) / minSec) / kotlin.math.ln(maxSec / minSec)

                            val shutterPresets = listOf(
                                "1/1000s", "1/500s", "1/250s", "1/125s", "1/60s", "1/30s", "1/15s", "1/8s", "1/4s",
                                "1/3s", "1/2s", "1s", "2s", "3s"
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Shutter Speed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (isAutoShutter) "S AUTO" else preferences.proShutterSpeed,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isAutoShutter) {
                                                MaterialTheme.colorScheme.secondary
                                            } else if (isCurrentShutterUnsupported) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                        ProValuePill(
                                            text = "AUTO",
                                            isSelected = isAutoShutter,
                                            onClick = { onShutterChanged("AUTO") },
                                            modifier = Modifier.testTag("pro_shutter_auto_pill")
                                        )
                                    }
                                }

                                // Quick presets including exact long-exposure options: 1/3s, 1/2s, 1s, 2s, 3s
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    shutterPresets.forEach { preset ->
                                        val nanos = com.example.core.camera.parseShutterSpeedToNanos(preset)
                                        val isSupported = nanos != null && (nanos in shutterNanosRange)
                                        val isSelected = !isAutoShutter && preferences.proShutterSpeed.equals(preset, ignoreCase = true)
                                        ProValuePill(
                                            text = preset,
                                            isSelected = isSelected,
                                            enabled = isSupported,
                                            onClick = {
                                                if (isSupported) {
                                                    onShutterChanged(preset)
                                                }
                                            },
                                            modifier = Modifier.testTag(
                                                "pro_shutter_preset_${preset.replace('/', '_').replace('.', '_')}"
                                            )
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = formatShutterSpeedLabel(minSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = if (isAutoShutter) 0.3f else currentShutterT.coerceIn(0f, 1f),
                                        onValueChange = { t ->
                                            val calcSec = minSec * Math.pow((maxSec / minSec).toDouble(), t.toDouble()).toFloat()
                                            val formatted = formatShutterSpeedLabel(calcSec)
                                            onShutterChanged(formatted)
                                        },
                                        valueRange = 0f..1f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                            .testTag("pro_shutter_slider")
                                    )
                                    Text(
                                        text = formatShutterSpeedLabel(maxSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isCurrentShutterUnsupported) {
                                    Text(
                                        text = "Unsupported: hardware max is ${formatShutterSpeedLabel(maxSec)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                } else if (!isAutoShutter && currentShutterSec >= 0.33f) {
                                    Text(
                                        text = "Long exposure: preview may differ from capture. Hold steady.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }

                        ProTab.FOCUS -> {
                            val isHardwareFocusSupported = currentLensCaps?.manualCapabilities?.isManualFocusSupported == true
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!isHardwareFocusSupported && currentLensCaps != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Fixed Focus Lens (Manual focus unavailable on ${currentLensCaps.lens.displayName})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ProValuePill(
                                        text = "Auto Focus (AF)",
                                        isSelected = preferences.proFocusMode == ProFocusMode.AUTO,
                                        onClick = { onFocusModeChanged(ProFocusMode.AUTO) }
                                    )
                                    if (isHardwareFocusSupported) {
                                        ProValuePill(
                                            text = "Manual Focus (MF)",
                                            isSelected = preferences.proFocusMode == ProFocusMode.MANUAL,
                                            onClick = { onFocusModeChanged(ProFocusMode.MANUAL) }
                                        )
                                    }
                                }

                                if (preferences.proFocusMode == ProFocusMode.MANUAL && isHardwareFocusSupported) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Macro",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = preferences.proFocusDistance,
                                            onValueChange = onFocusDistanceChanged,
                                            valueRange = 0.0f..1.0f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp)
                                                .testTag("pro_focus_slider")
                                        )
                                        Text(
                                            text = "Infinity",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        ProTab.WB -> {
                            val wbOptions = listOf("AUTO", "3000K", "4000K", "5500K", "6500K", "7500K")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                wbOptions.forEach { wb ->
                                    val isSelected = preferences.proWhiteBalance == wb
                                    ProValuePill(
                                        text = wb,
                                        isSelected = isSelected,
                                        onClick = { onWbChanged(wb) }
                                    )
                                }
                            }
                        }

                        ProTab.EV -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Exposure Value",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val evFormatted = when {
                                        preferences.proEv > 0f -> "+${String.format(Locale.US, "%.1f", preferences.proEv)} EV"
                                        else -> "${String.format(Locale.US, "%.1f", preferences.proEv)} EV"
                                    }
                                    Text(
                                        text = evFormatted,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "-2.0",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = preferences.proEv,
                                        onValueChange = { rawVal ->
                                            val rounded = Math.round(rawVal * 10f) / 10f
                                            onEvChanged(rounded)
                                        },
                                        valueRange = -2.0f..2.0f,
                                        steps = 39,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                            .testTag("pro_ev_slider")
                                    )
                                    Text(
                                        text = "+2.0",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        ProTab.RAW -> {
                            val isRawSupported = currentLensCaps?.manualCapabilities?.isRawDngSupported == true
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!isRawSupported && currentLensCaps != null) {
                                    Text(
                                        text = "RAW (DNG) unsupported on ${currentLensCaps.lens.displayName}; captured in high-detail JPEG",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ProRawFormat.entries.forEach { format ->
                                        val isSelected = preferences.proRawFormat == format
                                        ProValuePill(
                                            text = format.displayName,
                                            isSelected = isSelected,
                                            onClick = { onRawFormatChanged(format) }
                                        )
                                    }
                                }
                            }
                        }

                        ProTab.ASSIST -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ProValuePill(
                                    text = if (preferences.proHistogramEnabled) "Histogram: ON" else "Histogram: OFF",
                                    isSelected = preferences.proHistogramEnabled,
                                    onClick = { onHistogramToggled(!preferences.proHistogramEnabled) }
                                )
                                ProValuePill(
                                    text = if (preferences.proFocusPeakingEnabled) "Focus Peaking: ON" else "Focus Peaking: OFF",
                                    isSelected = preferences.proFocusPeakingEnabled,
                                    onClick = { onFocusPeakingToggled(!preferences.proFocusPeakingEnabled) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Horizontal Pro Mode Tabs Strip (Pixel-styled compact pill bar)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    val label = when (tab) {
                        ProTab.ZOOM -> com.example.core.camera.formatZoomRatio(preferences.proZoomRatio, asCompactShortcut = false)
                        ProTab.ISO -> if (preferences.proIso == "AUTO") "ISO A" else "ISO ${preferences.proIso}"
                        ProTab.SHUTTER -> if (preferences.proShutterSpeed == "AUTO") "S Auto" else preferences.proShutterSpeed
                        ProTab.FOCUS -> if (preferences.proFocusMode == ProFocusMode.AUTO) "AF" else "MF"
                        ProTab.WB -> if (preferences.proWhiteBalance == "AUTO") "WB A" else preferences.proWhiteBalance
                        ProTab.EV -> if (preferences.proEv == 0.0f) "EV 0" else (if (preferences.proEv > 0) "+${String.format(Locale.US, "%.1f", preferences.proEv)}" else String.format(Locale.US, "%.1f", preferences.proEv))
                        ProTab.RAW -> if (preferences.proRawFormat == ProRawFormat.JPEG_ONLY) "JPG" else "RAW"
                        ProTab.ASSIST -> "Assist"
                    }

                    val animatedTabBg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
                        label = "pro_tab_bg"
                    )
                    val animatedTabContentColor by androidx.compose.animation.animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
                        label = "pro_tab_content_color"
                    )
                    val tabInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isTabPressed by tabInteractionSource.collectIsPressedAsState()
                    val tabScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isTabPressed) 0.94f else 1.0f,
                        animationSpec = com.example.ui.theme.CameraMotionTokens.touchSpring(),
                        label = "pro_tab_scale"
                    )

                    Surface(
                        color = animatedTabBg,
                        shape = CircleShape,
                        modifier = Modifier
                            .scale(tabScale)
                            .clickable(
                                interactionSource = tabInteractionSource,
                                indication = ripple(bounded = true, radius = 22.dp)
                            ) {
                                updateControlKey(tab.key)
                            }
                            .testTag("pro_tab_${tab.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = animatedTabContentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                color = animatedTabContentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProValuePill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1.0f else 0.4f
    val animatedPillBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
        label = "pro_pill_bg"
    )
    val animatedPillText by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
        label = "pro_pill_text"
    )
    val pillInteractionSource = remember { MutableInteractionSource() }
    val isPillPressed by pillInteractionSource.collectIsPressedAsState()
    val pillScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPillPressed && enabled) 0.94f else 1.0f,
        animationSpec = com.example.ui.theme.CameraMotionTokens.touchSpring(),
        label = "pro_pill_scale"
    )

    Surface(
        color = animatedPillBg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .alpha(alpha)
            .scale(pillScale)
            .clickable(
                interactionSource = pillInteractionSource,
                indication = ripple(bounded = true, radius = 24.dp),
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            color = animatedPillText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

private fun formatShutterSpeedLabel(sec: Float): String {
    return if (sec >= 1.0f) {
        if (Math.abs(sec - Math.round(sec)) < 0.05f) {
            "${Math.round(sec)}s"
        } else {
            String.format(Locale.US, "%.1fs", sec)
        }
    } else {
        val denominator = Math.round(1.0f / sec).coerceAtLeast(1)
        "1/${denominator}s"
    }
}

private fun parseShutterSpeedToSeconds(str: String): Float {
    if (str.equals("AUTO", ignoreCase = true)) return 0.01667f
    if (str.endsWith("s")) {
        val s = str.dropLast(1)
        if (s.contains("/")) {
            val parts = s.split("/")
            val num = parts.getOrNull(0)?.toFloatOrNull() ?: 1f
            val den = parts.getOrNull(1)?.toFloatOrNull() ?: 60f
            return num / den
        }
        return s.toFloatOrNull() ?: 0.01667f
    }
    return 0.01667f
}

