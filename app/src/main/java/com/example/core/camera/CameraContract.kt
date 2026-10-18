package com.example.core.camera

import com.example.core.model.CameraMode

/**
 * Architectural contract for future camera engine implementations (Phase 1+).
 * This establishes the abstraction layer before CameraX and Camera2 bindings are introduced.
 */
enum class LensFacing {
    BACK,
    FRONT
}

enum class AspectRatioPreference {
    RATIO_4_3,
    RATIO_1_1,
    RATIO_9_16
}

data class CameraEngineState(
    val isInitialized: Boolean = false,
    val activeMode: CameraMode = CameraMode.PHOTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val aspectRatio: AspectRatioPreference = AspectRatioPreference.RATIO_4_3,
    val isFlashAvailable: Boolean = false,
    val isHdrSupported: Boolean = false
)

interface CameraEngineContract {
    fun initializeEngine()
    fun switchLens()
    fun setMode(mode: CameraMode)
    fun setAspectRatio(ratio: AspectRatioPreference)
}
