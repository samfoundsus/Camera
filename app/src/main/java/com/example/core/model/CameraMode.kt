package com.example.core.model

/**
 * Representation of camera capture modes for the Pixel Camera app.
 *
 * Exact modes:
 * 1. Camera (PHOTO)
 * 2. Portrait
 * 3. Video
 * 4. Slow Motion
 * 5. Night Sight
 * 6. Cinematic
 * 7. Time Lapse
 * 8. Panorama
 * 9. HD (Highest supported native still-image output)
 * 10. Pro (Manual sensor controls: ISO, Shutter, Focus, WB, EV, RAW)
 */
enum class CameraMode(val displayName: String, val isVideo: Boolean = false) {
    NIGHT_SIGHT("Night Sight"),
    PORTRAIT("Portrait"),
    PHOTO("Camera"),
    VIDEO("Video", isVideo = true),
    ASTRO("Astrophotography"),
    SLOW_MOTION("Slow Motion", isVideo = true),
    TIME_LAPSE("Time Lapse", isVideo = true),
    PANORAMA("Panorama"),
    HD("HD"),
    PRO("Pro");

    /**
     * Color filters are supported exclusively in standard Camera/Photo and Video modes.
     */
    val supportsFilters: Boolean
        get() = this == PHOTO || this == VIDEO

    companion object {
        val defaultMode = PHOTO
    }
}
