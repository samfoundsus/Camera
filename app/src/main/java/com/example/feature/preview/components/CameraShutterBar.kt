package com.example.feature.preview.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.model.CameraMode
import com.example.ui.components.CameraShutterButton
import com.example.ui.components.CircularIconButton
import com.example.ui.theme.CameraMotionTokens

@Composable
fun CameraShutterBar(
    activeMode: CameraMode,
    isRecording: Boolean = false,
    isCountingDown: Boolean = false,
    countdownSeconds: Int = 0,
    lastCapturedPhotoUri: Uri? = null,
    captureConfirmationTrigger: Int = 0,
    onShutterClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    onFlipCameraClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var lensFlipRotationTarget by remember { mutableFloatStateOf(0f) }
    var lastFlipTimestamp by remember { mutableLongStateOf(0L) }

    val animatedRotation by animateFloatAsState(
        targetValue = lensFlipRotationTarget,
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "lens_switch_rotation"
    )

    val flipInteractionSource = remember { MutableInteractionSource() }
    val isFlipPressed by flipInteractionSource.collectIsPressedAsState()
    val flipScale by animateFloatAsState(
        targetValue = if (isFlipPressed) 0.88f else 1.0f,
        animationSpec = CameraMotionTokens.touchSpring(),
        label = "lens_switch_scale"
    )

    val handleFlipClick = {
        val now = System.currentTimeMillis()
        if (now - lastFlipTimestamp >= 320L) {
            lastFlipTimestamp = now
            lensFlipRotationTarget += 180f
            onFlipCameraClick()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("camera_shutter_bar")
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail button with touch feedback
        val galleryInteractionSource = remember { MutableInteractionSource() }
        val isGalleryPressed by galleryInteractionSource.collectIsPressedAsState()
        val galleryScale by animateFloatAsState(
            targetValue = if (isGalleryPressed) 0.92f else 1.0f,
            animationSpec = CameraMotionTokens.touchSpring(),
            label = "gallery_scale"
        )

        if (lastCapturedPhotoUri != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val videoThumbnail by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = lastCapturedPhotoUri
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val uri = lastCapturedPhotoUri
                    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    val isVideo = type?.startsWith("video/", ignoreCase = true) == true ||
                        uri.toString().contains("video", ignoreCase = true) ||
                        uri.lastPathSegment?.endsWith(".mp4", ignoreCase = true) == true
                    if (!isVideo) return@withContext null

                    val fromLoadThumbnail = runCatching {
                        context.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                    }.getOrNull()
                    if (fromLoadThumbnail != null) return@withContext fromLoadThumbnail

                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.getFrameAtTime(0L)
                    } catch (_: Exception) {
                        null
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .scale(galleryScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = galleryInteractionSource,
                        indication = ripple(bounded = true, radius = 26.dp),
                        onClick = onGalleryClick
                    )
                    .testTag("gallery_button"),
                contentAlignment = Alignment.Center
            ) {
                val bmp = videoThumbnail
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Last captured photo thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = lastCapturedPhotoUri,
                        contentDescription = "Last captured photo thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .size(52.dp)
                    .scale(galleryScale)
                    .testTag("gallery_button"),
                tonalElevation = 2.dp
            ) {
                IconButton(
                    onClick = onGalleryClick,
                    interactionSource = galleryInteractionSource,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = "Gallery preview",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Pixel prominent double-ring shutter button with touch feedback and recording morph
        CameraShutterButton(
            onClick = onShutterClick,
            isVideoMode = activeMode.isVideo,
            isRecording = isRecording,
            isCountingDown = isCountingDown,
            countdownSeconds = countdownSeconds,
            captureConfirmationTrigger = captureConfirmationTrigger
        )

        // Camera lens switch button with 180° rotation and touch scale feedback
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(52.dp)
                .scale(flipScale)
                .testTag("lens_switch_button"),
            tonalElevation = 2.dp
        ) {
            IconButton(
                onClick = handleFlipClick,
                interactionSource = flipInteractionSource,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Switch front or rear camera",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(animatedRotation)
                )
            }
        }
    }
}
