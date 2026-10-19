package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.CameraPreferencesRepository
import com.example.feature.preview.CameraFoundationScreen
import com.example.feature.settings.SettingsScreen
import com.example.ui.theme.PixelCameraTheme

import android.view.KeyEvent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.example.core.preferences.VolumeButtonAction

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import com.example.ui.theme.CameraMotionTokens
import kotlinx.coroutines.delay

enum class CameraScreenDestination {
    CAMERA,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var preferencesRepository: CameraPreferencesRepository
    private var currentDestination: CameraScreenDestination = CameraScreenDestination.CAMERA
    private var currentVolumeAction: VolumeButtonAction = VolumeButtonAction.VOLUME
    private var hardwareShutterTriggerState = mutableIntStateOf(0)
    private var hardwareZoomDeltaState = mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferencesRepository = CameraPreferencesRepository(applicationContext)
        currentVolumeAction = preferencesRepository.preferences.value.volumeButtonAction
        val capabilitiesRepository = com.example.core.camera.DefaultCameraCapabilitiesRepository()

        setContent {
            val preferences by preferencesRepository.preferences.collectAsStateWithLifecycle()
            var currentScreen by rememberSaveable { mutableStateOf(CameraScreenDestination.CAMERA) }
            var isNavigating by remember { mutableStateOf(false) }

            currentDestination = currentScreen
            currentVolumeAction = preferences.volumeButtonAction

            val navigateToSettings: () -> Unit = {
                if (!isNavigating && currentScreen == CameraScreenDestination.CAMERA) {
                    isNavigating = true
                    currentScreen = CameraScreenDestination.SETTINGS
                }
            }
            val navigateToCamera: () -> Unit = {
                if (!isNavigating && currentScreen == CameraScreenDestination.SETTINGS) {
                    isNavigating = true
                    currentScreen = CameraScreenDestination.CAMERA
                }
            }

            LaunchedEffect(currentScreen) {
                delay(CameraMotionTokens.DURATION_SCREEN_ENTER.toLong())
                isNavigating = false
            }

            PixelCameraTheme(
                themePreference = preferences.themePreference,
                dynamicColor = preferences.dynamicColor
            ) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState == CameraScreenDestination.SETTINGS) {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> (fullWidth * 0.16f).toInt() },
                                animationSpec = CameraMotionTokens.screenEnterTween()
                            ) + fadeIn(animationSpec = CameraMotionTokens.screenEnterTween()))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -(fullWidth * 0.10f).toInt() },
                                        animationSpec = CameraMotionTokens.screenExitTween()
                                    ) + fadeOut(animationSpec = CameraMotionTokens.screenExitTween())
                                )
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> -(fullWidth * 0.10f).toInt() },
                                animationSpec = CameraMotionTokens.screenEnterTween()
                            ) + fadeIn(animationSpec = CameraMotionTokens.screenEnterTween()))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> (fullWidth * 0.16f).toInt() },
                                        animationSpec = CameraMotionTokens.screenExitTween()
                                    ) + fadeOut(animationSpec = CameraMotionTokens.screenExitTween())
                                )
                        }
                    },
                    label = "main_screen_transition"
                ) { screen ->
                    when (screen) {
                        CameraScreenDestination.CAMERA -> {
                            CameraFoundationScreen(
                                preferences = preferences,
                                preferencesRepository = preferencesRepository,
                                capabilitiesRepository = capabilitiesRepository,
                                hardwareShutterTrigger = hardwareShutterTriggerState.intValue,
                                hardwareZoomDelta = hardwareZoomDeltaState.floatValue,
                                onNavigateToSettings = navigateToSettings
                            )
                        }
                        CameraScreenDestination.SETTINGS -> {
                            SettingsScreen(
                                preferences = preferences,
                                capabilitiesRepository = capabilitiesRepository,
                                onThemeChanged = { preferencesRepository.setThemePreference(it) },
                                onDynamicColorChanged = { preferencesRepository.setDynamicColor(it) },
                                onAspectRatioChanged = { preferencesRepository.setAspectRatioMode(it) },
                                onGridChanged = { preferencesRepository.setGridMode(it) },
                                onTimerChanged = { preferencesRepository.setTimerMode(it) },
                                onFlashChanged = { preferencesRepository.setFlashMode(it) },
                                onSoundsChanged = { preferencesRepository.setCameraSounds(it) },
                                onLocationChanged = { preferencesRepository.setSaveLocation(it) },
                                onMirrorChanged = { preferencesRepository.setSelfieMirror(it) },
                                onVolumeActionChanged = { preferencesRepository.setVolumeButtonAction(it) },
                                onVideoResolutionChanged = { preferencesRepository.setVideoResolution(it) },
                                onVideoFpsChanged = { preferencesRepository.setVideoFrameRate(it) },
                                onVideoStabilizationChanged = { preferencesRepository.setVideoStabilization(it) },
                                onHdOptionChanged = { preferencesRepository.setHdOutputOption(it) },
                                onPortraitRetouchChanged = { preferencesRepository.setPortraitRetouch(it) },
                                onPortraitBlurLevelChanged = { preferencesRepository.setPortraitBlurLevel(it) },
                                onNightSightExposureChanged = { preferencesRepository.setNightSightExposure(it) },
                                onTimeLapseSpeedChanged = { preferencesRepository.setTimeLapseSpeed(it) },
                                onSlowMoSpeedChanged = { preferencesRepository.setSlowMoSpeed(it) },
                                onProIsoChanged = { preferencesRepository.setProIso(it) },
                                onProShutterChanged = { preferencesRepository.setProShutterSpeed(it) },
                                onProFocusModeChanged = { preferencesRepository.setProFocusMode(it) },
                                onProFocusDistanceChanged = { preferencesRepository.setProFocusDistance(it) },
                                onProWbChanged = { preferencesRepository.setProWhiteBalance(it) },
                                onProEvChanged = { preferencesRepository.setProEv(it) },
                                onProRawFormatChanged = { preferencesRepository.setProRawFormat(it) },
                                onProHistogramToggled = { preferencesRepository.setProHistogramEnabled(it) },
                                onProFocusPeakingToggled = { preferencesRepository.setProFocusPeakingEnabled(it) },
                                onCustomModeLayoutChanged = { visible, hidden -> preferencesRepository.setCustomModeLayout(visible, hidden) },
                                onResetModeLayout = { preferencesRepository.resetModeLayoutToDefaults() },
                                onNavigateBack = navigateToCamera
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::preferencesRepository.isInitialized) {
            currentVolumeAction = preferencesRepository.preferences.value.volumeButtonAction
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentDestination == CameraScreenDestination.CAMERA &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            when (currentVolumeAction) {
                VolumeButtonAction.SHUTTER -> {
                    if (event.repeatCount == 0) {
                        hardwareShutterTriggerState.intValue++
                    }
                    return true
                }
                VolumeButtonAction.ZOOM -> {
                    val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.1f else -0.1f
                    hardwareZoomDeltaState.floatValue += delta
                    return true
                }
                VolumeButtonAction.VOLUME -> {
                    return super.onKeyDown(keyCode, event)
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (currentDestination == CameraScreenDestination.CAMERA &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            currentVolumeAction != VolumeButtonAction.VOLUME
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Preview(showBackground = true)
@Composable
fun CameraFoundationScreenPreview() {
    val context = LocalContext.current
    val repo = remember { CameraPreferencesRepository(context) }
    val prefs = remember { CameraPreferences() }

    PixelCameraTheme {
        CameraFoundationScreen(
            preferences = prefs,
            preferencesRepository = repo,
            onNavigateToSettings = {}
        )
    }
}
