package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.PanoramaHorizontal
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.CameraMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreCameraModesSheet(
    hiddenModes: List<CameraMode>,
    onModeSelected: (CameraMode) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.testTag("more_camera_modes_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "More Camera Modes",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select any mode below to activate its viewfinder and controls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hiddenModes.forEach { mode ->
                    val icon = getModeIcon(mode)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onModeSelected(mode)
                                onDismissRequest()
                            }
                            .testTag("more_mode_item_${mode.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(24.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = getModeDescription(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun getModeIcon(mode: CameraMode): ImageVector {
    return when (mode) {
        CameraMode.NIGHT_SIGHT -> Icons.Outlined.NightsStay
        CameraMode.PORTRAIT -> Icons.Outlined.Portrait
        CameraMode.PHOTO -> Icons.Outlined.Camera
        CameraMode.HD -> Icons.Outlined.HighQuality
        CameraMode.PRO -> Icons.Outlined.CenterFocusStrong
        CameraMode.VIDEO -> Icons.Outlined.Videocam
        CameraMode.CINEMATIC -> Icons.Outlined.AutoAwesome
        CameraMode.SLOW_MOTION -> Icons.Outlined.SlowMotionVideo
        CameraMode.TIME_LAPSE -> Icons.Outlined.Speed
        CameraMode.PANORAMA -> Icons.Outlined.PanoramaHorizontal
    }
}

private fun getModeDescription(mode: CameraMode): String {
    return when (mode) {
        CameraMode.NIGHT_SIGHT -> "Enhanced low-light and astrophotography capture"
        CameraMode.PORTRAIT -> "Depth effects with adjustable background blur"
        CameraMode.PHOTO -> "Standard high-definition photo capture"
        CameraMode.HD -> "Full unbinned native sensor resolution photo"
        CameraMode.PRO -> "Manual ISO, shutter speed, focus, and RAW output"
        CameraMode.VIDEO -> "High-definition video recording with stabilization"
        CameraMode.CINEMATIC -> "Cinematic depth video recording"
        CameraMode.SLOW_MOTION -> "High-frame-rate slow motion capture"
        CameraMode.TIME_LAPSE -> "Interval speed time-lapse recording"
        CameraMode.PANORAMA -> "Panoramic wide-angle sweep capture"
    }
}
