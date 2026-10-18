package com.example.feature.preview.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.model.CameraMode
import com.example.ui.components.CameraShutterButton
import com.example.ui.components.CircularIconButton

@Composable
fun CameraShutterBar(
    activeMode: CameraMode,
    isRecording: Boolean = false,
    lastCapturedPhotoUri: Uri? = null,
    onShutterClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    onFlipCameraClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_shutter_bar")
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail button
        if (lastCapturedPhotoUri != null) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onGalleryClick)
                    .testTag("gallery_button"),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = lastCapturedPhotoUri,
                    contentDescription = "Last captured photo thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            CircularIconButton(
                icon = Icons.Outlined.PhotoLibrary,
                contentDescription = "Gallery preview",
                onClick = onGalleryClick,
                size = 52.dp,
                iconSize = 24.dp,
                testTag = "gallery_button"
            )
        }

        // Pixel prominent double-ring shutter button
        CameraShutterButton(
            onClick = onShutterClick,
            isVideoMode = activeMode.isVideo,
            isRecording = isRecording
        )

        // Camera lens switch button
        CircularIconButton(
            icon = Icons.Outlined.Cameraswitch,
            contentDescription = "Switch front or rear camera",
            onClick = onFlipCameraClick,
            size = 52.dp,
            iconSize = 24.dp,
            testTag = "lens_switch_button"
        )
    }
}
