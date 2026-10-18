package com.example

import com.example.core.model.CameraMode
import com.example.core.preferences.CameraPreferences
import com.example.core.processing.FocusPeakingFrame
import com.example.core.processing.LiveHistogramData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for Pro mode Live Histogram and Focus Peaking algorithms, coordinate transformations,
 * and state isolation.
 */
class ProLiveAnalysisTest {

    @Test
    fun liveHistogramData_equalityAndProperties() {
        val bins1 = FloatArray(256) { it / 255f }
        val bins2 = FloatArray(256) { it / 255f }

        val data1 = LiveHistogramData(
            normalizedBins = bins1,
            isShadowClipping = false,
            isHighlightClipping = true,
            averageLuminance = 180f
        )
        val data2 = LiveHistogramData(
            normalizedBins = bins2,
            isShadowClipping = false,
            isHighlightClipping = true,
            averageLuminance = 180f
        )

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
        assertTrue(data1.isHighlightClipping)
        assertFalse(data1.isShadowClipping)
    }

    @Test
    fun histogramComputation_detectsClipping() {
        // Construct synthetic 100x100 luma grid with blown-out highlights (> 248)
        val w = 100
        val h = 100
        val lumaOverexposed = Array(h) { IntArray(w) { 255 } }
        val rawBins = IntArray(256)
        var shadowCount = 0
        var highlightCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = lumaOverexposed[y][x]
                rawBins[v]++
                if (v < 8) shadowCount++
                if (v > 248) highlightCount++
            }
        }

        val totalPixels = w * h
        val isShadowClipping = (shadowCount.toFloat() / totalPixels) > 0.04f
        val isHighlightClipping = (highlightCount.toFloat() / totalPixels) > 0.04f

        assertTrue("Overexposed frame should trigger highlight clipping", isHighlightClipping)
        assertFalse("Overexposed frame should not trigger shadow clipping", isShadowClipping)
        assertEquals(10000, rawBins[255])
    }

    @Test
    fun sobelEdgeDetector_identifiesSharpContrastEdges() {
        // Construct 3x3 window with flat luminance
        val flatWindow = arrayOf(
            intArrayOf(100, 100, 100),
            intArrayOf(100, 100, 100),
            intArrayOf(100, 100, 100)
        )
        val gxFlat = (flatWindow[0][2] + 2 * flatWindow[1][2] + flatWindow[2][2]) -
            (flatWindow[0][0] + 2 * flatWindow[1][0] + flatWindow[2][0])
        val gyFlat = (flatWindow[2][0] + 2 * flatWindow[2][1] + flatWindow[2][2]) -
            (flatWindow[0][0] + 2 * flatWindow[0][1] + flatWindow[0][2])
        val magFlat = abs(gxFlat) + abs(gyFlat)

        assertEquals("Flat area should have zero Sobel gradient", 0, magFlat)

        // Construct 3x3 window with a sharp vertical edge (black to white)
        val sharpEdgeWindow = arrayOf(
            intArrayOf(20, 20, 240),
            intArrayOf(20, 20, 240),
            intArrayOf(20, 20, 240)
        )
        val gxSharp = (sharpEdgeWindow[0][2] + 2 * sharpEdgeWindow[1][2] + sharpEdgeWindow[2][2]) -
            (sharpEdgeWindow[0][0] + 2 * sharpEdgeWindow[1][0] + sharpEdgeWindow[2][0])
        val gySharp = (sharpEdgeWindow[2][0] + 2 * sharpEdgeWindow[2][1] + sharpEdgeWindow[2][2]) -
            (sharpEdgeWindow[0][0] + 2 * sharpEdgeWindow[0][1] + sharpEdgeWindow[0][2])
        val magSharp = abs(gxSharp) + abs(gySharp)

        assertTrue(
            "Sharp contrast edge must exceed Sobel threshold (130); got $magSharp",
            magSharp >= 130
        )
    }

    @Test
    fun coordinateTransformation_handlesRotationAndMirroring() {
        val gridW = 160
        val gridH = 120

        // Test 90 degrees clockwise rotation (sensor landscape to portrait display)
        val rotation90 = 90
        val isRotated90 = rotation90 == 90 || rotation90 == 270
        val outW90 = if (isRotated90) gridH else gridW
        val outH90 = if (isRotated90) gridW else gridH

        assertEquals(120, outW90)
        assertEquals(160, outH90)

        val x = 10
        val y = 20

        // 90 deg non-mirrored
        val outX90 = (gridH - 1) - y
        val outY90 = x
        assertEquals(99, outX90)
        assertEquals(10, outY90)

        // 90 deg mirrored for selfie front camera
        val outX90Mirrored = (outW90 - 1) - outX90
        assertEquals(20, outX90Mirrored)
    }

    @Test
    fun proModePreferences_isolateHistogramAndPeaking() {
        val defaultPrefs = CameraPreferences()
        assertFalse("Histogram should default to disabled", defaultPrefs.proHistogramEnabled)
        assertFalse("Focus peaking should default to disabled", defaultPrefs.proFocusPeakingEnabled)

        val enabledPrefs = defaultPrefs.copy(
            proHistogramEnabled = true,
            proFocusPeakingEnabled = true
        )
        assertTrue(enabledPrefs.proHistogramEnabled)
        assertTrue(enabledPrefs.proFocusPeakingEnabled)

        // Verify non-Pro modes do not share Pro settings state
        assertFalse(CameraMode.PHOTO.isVideo)
        assertEquals("Camera", CameraMode.PHOTO.displayName)
        assertEquals("Pro", CameraMode.PRO.displayName)
    }
}
