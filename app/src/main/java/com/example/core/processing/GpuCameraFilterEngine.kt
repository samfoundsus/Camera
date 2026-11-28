package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.view.PreviewView
import androidx.core.util.Consumer
import com.example.core.preferences.CameraFilterPreset
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * GPU-accelerated filter engine providing unified, shader-identical color grading across:
 * 1. Real-time CameraX Preview & VideoCapture via OpenGL ES 2.0 [GlFilterSurfaceProcessor] + [CameraEffect]
 * 2. Live [TextureView] fallback via AGSL [RuntimeShader] (API 33+) / [RenderEffect] (API 31+)
 * 3. Still photo [Bitmap] filtering via GPU AGSL [HardwareRenderer] (API 33+) or EGL14 GLSL FBO
 * 4. Video file GPU surface-to-surface transcoding (MediaCodec decoder -> SurfaceTexture -> GLSL -> MediaCodec encoder)
 */
object GpuCameraFilterEngine {
    private const val TAG = "GpuCameraFilterEngine"
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val MAX_GPU_TILE_DIM = 4096
    private const val MIN_GPU_BITMAP_DIM_PX = 257

    private val warmedUp = AtomicBoolean(false)
    private val glSurfaceProcessorFailed = AtomicBoolean(false)

    /**
     * Detects Android Emulator / Robolectric virtual environments where basic virtual camera HALs
     * and software SwiftShader/Goldfish GL drivers often reject CameraX `CameraEffect` SurfaceProcessor
     * or offscreen `HardwareRenderer`/EGL pipelines.
     */
    fun isEmulatorEnvironment(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()

        return fingerprint == "robolectric" ||
            hardware == "robolectric" ||
            fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("sdk_gphone") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            model.contains("sdk_gphone") ||
            manufacturer.contains("genymotion") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("vbox86") ||
            product.contains("sdk_gphone") ||
            product.contains("sdk_x86") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            product.contains("vbox86p") ||
            (brand.startsWith("generic") && device.startsWith("generic"))
    }

    /**
     * Returns `true` only when real-time CameraX [CameraEffect] OpenGL ES [GlFilterSurfaceProcessor]
     * is safe to attach. Disabled on emulators/Robolectric and automatically disabled if a runtime
     * GL error ever occurs so the camera falls back cleanly to standard CameraX + TextureView/CPU.
     */
    fun isGpuCameraEffectSupported(): Boolean {
        if (isJvmOrRobolectricEnvironment() || isEmulatorEnvironment()) return false
        return !glSurfaceProcessorFailed.get()
    }

    internal fun markGlSurfaceProcessorFailed() {
        glSurfaceProcessorFailed.set(true)
    }

    internal fun resetGlSurfaceProcessorFailureForTesting() {
        glSurfaceProcessorFailed.set(false)
    }

    private const val VERTEX_SHADER_SOURCE = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    /**
     * Builds the GLSL ES 2.0 fragment shader matching [CameraFilterProcessor] pixel math 1:1.
     * Supports both `samplerExternalOES` (CameraX / MediaCodec SurfaceTexture) and `sampler2D` (Bitmaps).
     */
    fun buildFilterFragmentShader(isExternalOes: Boolean): String {
        val extensionLine = if (isExternalOes) "#extension GL_OES_EGL_image_external : require\n" else ""
        val samplerType = if (isExternalOes) "samplerExternalOES" else "sampler2D"
        return """
            ${extensionLine}precision highp float;
            varying vec2 vTexCoord;
            uniform $samplerType uTexture;
            uniform float uIsIdentity;
            uniform float uIntensity;
            uniform float uIsMono;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uWarmth;
            uniform float uTint;
            uniform float uExposure;
            uniform float uShadowLift;
            uniform float uHighlightRolloff;
            uniform float uSCurve;
            uniform float uSkinProtect;
            uniform vec3 uLumaWeights;
            uniform vec3 uShadowTint;
            uniform vec3 uHighlightTint;

            vec3 evalToneCurve(vec3 x) {
                vec3 v = clamp(x, 0.0, 1.0);
                if (abs(uExposure) > 0.0005) {
                    v = clamp(v + uExposure * (0.4 + 0.6 * (1.0 - v)), 0.0, 1.0);
                }
                if (abs(uContrast - 1.0) > 0.0005) {
                    v = clamp((v - 0.5) * uContrast + 0.5, 0.0, 1.0);
                }
                if (uSCurve > 0.0005) {
                    float s = clamp(uSCurve, 0.0, 0.5);
                    vec3 delta = sin(6.2831853 * v) / 6.2831853;
                    v = clamp(v - s * delta, 0.0, 1.0);
                }
                if (uShadowLift > 0.0005) {
                    float lift = clamp(uShadowLift, 0.0, 0.35);
                    vec3 sw = (1.0 - v) * (1.0 - v);
                    v = clamp(v + lift * sw, 0.0, 1.0);
                }
                if (uHighlightRolloff > 0.0005) {
                    float rolloff = clamp(uHighlightRolloff, 0.0, 0.35);
                    vec3 hw = v * v;
                    v = clamp(v - rolloff * 0.65 * hw, 0.0, 1.0);
                }
                vec3 sw2 = (1.0 - v) * (1.0 - v);
                vec3 hw2 = v * v;
                v = clamp(v + uShadowTint * sw2 * 0.65 + uHighlightTint * hw2 * 0.65, 0.0, 1.0);
                return v;
            }

            void main() {
                vec4 src = texture2D(uTexture, vTexCoord);
                if (uIsIdentity > 0.5 || uIntensity <= 0.001) {
                    gl_FragColor = src;
                    return;
                }
                vec3 orig = clamp(src.rgb, 0.0, 1.0);
                vec3 graded;
                if (uIsMono > 0.5) {
                    float luma = clamp(dot(orig, uLumaWeights), 0.0, 1.0);
                    vec3 toned = evalToneCurve(vec3(luma));
                    graded = vec3(toned.r);
                } else {
                    float effSat = uSaturation;
                    float effWarmthMix = 1.0;
                    if (uSkinProtect > 0.01 && orig.r > orig.g && orig.g > orig.b) {
                        float rgDiff = orig.r - orig.g;
                        float gbDiff = orig.g - orig.b;
                        if (rgDiff >= 0.031 && rgDiff <= 0.373 &&
                            gbDiff >= 0.019 && gbDiff <= 0.334 &&
                            orig.r >= 0.255 && orig.r <= 0.961) {
                            float skinFactor = clamp(uSkinProtect * 0.55, 0.0, 0.65);
                            if (effSat > 1.0) {
                                effSat = 1.0 + (effSat - 1.0) * (1.0 - skinFactor);
                            }
                            effWarmthMix = 1.0 - skinFactor * 0.45;
                        }
                    }
                    vec3 warmthMul = clamp(
                        vec3(
                            1.0 + uWarmth * 0.18 + uTint * 0.05,
                            1.0 + uWarmth * 0.03 - uTint * 0.06,
                            1.0 - uWarmth * 0.20 + uTint * 0.05
                        ),
                        vec3(0.65),
                        vec3(1.35)
                    );
                    vec3 wMul = mix(vec3(1.0), warmthMul, effWarmthMix);
                    vec3 warmed = clamp(orig * wMul, 0.0, 1.0);
                    vec3 toned = evalToneCurve(warmed);
                    float luma = dot(toned, uLumaWeights);
                    graded = clamp(mix(vec3(luma), toned, effSat), 0.0, 1.0);
                }
                vec3 outRgb = mix(orig, graded, clamp(uIntensity, 0.0, 1.0));
                gl_FragColor = vec4(outRgb, src.a);
            }
        """.trimIndent()
    }

    /**
     * Builds the AGSL [RuntimeShader] source (Android 13+ / API 33+) matching the exact same
     * tone curve, split-toning, monochrome luma, and skin-tone protection model.
     */
    fun buildAgslFilterShaderSource(): String = """
        uniform shader inputShader;
        uniform float uIsIdentity;
        uniform float uIntensity;
        uniform float uIsMono;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uWarmth;
        uniform float uTint;
        uniform float uExposure;
        uniform float uShadowLift;
        uniform float uHighlightRolloff;
        uniform float uSCurve;
        uniform float uSkinProtect;
        uniform float3 uLumaWeights;
        uniform float3 uShadowTint;
        uniform float3 uHighlightTint;

        float3 evalToneCurve(float3 x) {
            float3 v = clamp(x, 0.0, 1.0);
            if (abs(uExposure) > 0.0005) {
                v = clamp(v + uExposure * (0.4 + 0.6 * (1.0 - v)), 0.0, 1.0);
            }
            if (abs(uContrast - 1.0) > 0.0005) {
                v = clamp((v - 0.5) * uContrast + 0.5, 0.0, 1.0);
            }
            if (uSCurve > 0.0005) {
                float s = clamp(uSCurve, 0.0, 0.5);
                float3 delta = sin(6.2831853 * v) / 6.2831853;
                v = clamp(v - s * delta, 0.0, 1.0);
            }
            if (uShadowLift > 0.0005) {
                float lift = clamp(uShadowLift, 0.0, 0.35);
                float3 sw = (1.0 - v) * (1.0 - v);
                v = clamp(v + lift * sw, 0.0, 1.0);
            }
            if (uHighlightRolloff > 0.0005) {
                float rolloff = clamp(uHighlightRolloff, 0.0, 0.35);
                float3 hw = v * v;
                v = clamp(v - rolloff * 0.65 * hw, 0.0, 1.0);
            }
            float3 sw2 = (1.0 - v) * (1.0 - v);
            float3 hw2 = v * v;
            v = clamp(v + uShadowTint * sw2 * 0.65 + uHighlightTint * hw2 * 0.65, 0.0, 1.0);
            return v;
        }

        half4 main(float2 coord) {
            half4 src = inputShader.eval(coord);
            if (uIsIdentity > 0.5 || uIntensity <= 0.001) {
                return src;
            }
            float a = max(float(src.a), 0.0001);
            float3 orig = clamp(float3(src.rgb) / a, 0.0, 1.0);
            float3 graded;
            if (uIsMono > 0.5) {
                float luma = clamp(dot(orig, uLumaWeights), 0.0, 1.0);
                float3 toned = evalToneCurve(float3(luma));
                graded = float3(toned.r);
            } else {
                float effSat = uSaturation;
                float effWarmthMix = 1.0;
                if (uSkinProtect > 0.01 && orig.r > orig.g && orig.g > orig.b) {
                    float rgDiff = orig.r - orig.g;
                    float gbDiff = orig.g - orig.b;
                    if (rgDiff >= 0.031 && rgDiff <= 0.373 &&
                        gbDiff >= 0.019 && gbDiff <= 0.334 &&
                        orig.r >= 0.255 && orig.r <= 0.961) {
                        float skinFactor = clamp(uSkinProtect * 0.55, 0.0, 0.65);
                        if (effSat > 1.0) {
                            effSat = 1.0 + (effSat - 1.0) * (1.0 - skinFactor);
                        }
                        effWarmthMix = 1.0 - skinFactor * 0.45;
                    }
                }
                float3 warmthMul = clamp(
                    float3(
                        1.0 + uWarmth * 0.18 + uTint * 0.05,
                        1.0 + uWarmth * 0.03 - uTint * 0.06,
                        1.0 - uWarmth * 0.20 + uTint * 0.05
                    ),
                    float3(0.65),
                    float3(1.35)
                );
                float3 wMul = mix(float3(1.0), warmthMul, effWarmthMix);
                float3 warmed = clamp(orig * wMul, 0.0, 1.0);
                float3 toned = evalToneCurve(warmed);
                float luma = dot(toned, uLumaWeights);
                graded = clamp(mix(float3(luma), toned, effSat), 0.0, 1.0);
            }
            float3 outRgb = mix(orig, graded, clamp(uIntensity, 0.0, 1.0)) * float(src.a);
            return half4(half3(outRgb), src.a);
        }
    """.trimIndent()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33AgslHelper {
        fun createConfiguredAgslShader(
            preset: CameraFilterPreset,
            intensity: Float
        ): RuntimeShader {
            val params = CameraFilterProcessor.getParameters(preset)
            return RuntimeShader(buildAgslFilterShaderSource()).apply {
                setFloatUniform("uIsIdentity", if (params.isIdentity) 1f else 0f)
                setFloatUniform("uIntensity", intensity.coerceIn(0f, 1f))
                setFloatUniform("uIsMono", if (params.isMonochrome) 1f else 0f)
                setFloatUniform("uContrast", params.contrast)
                setFloatUniform("uSaturation", params.saturation)
                setFloatUniform("uWarmth", params.warmth)
                setFloatUniform("uTint", params.tint)
                setFloatUniform("uExposure", params.exposureOffset)
                setFloatUniform("uShadowLift", params.shadowLift)
                setFloatUniform("uHighlightRolloff", params.highlightRolloff)
                setFloatUniform("uSCurve", params.sCurveStrength)
                setFloatUniform("uSkinProtect", params.skinToneProtection)
                setFloatUniform("uLumaWeights", params.lumaR, params.lumaG, params.lumaB)
                setFloatUniform("uShadowTint", params.shadowTintR, params.shadowTintG, params.shadowTintB)
                setFloatUniform("uHighlightTint", params.highlightTintR, params.highlightTintG, params.highlightTintB)
            }
        }

        fun applyAgslEffectToTextureView(
            textureView: TextureView,
            preset: CameraFilterPreset
        ): Boolean {
            return runCatching {
                val shader = createConfiguredAgslShader(preset, 1.0f)
                val effect = RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
                textureView.setLayerType(View.LAYER_TYPE_NONE, null)
                textureView.setRenderEffect(effect)
                true
            }.onFailure { err ->
                com.example.core.camera.CrashLogManager.recordNonFatalError("TextureView-AGSL", err)
            }.getOrDefault(false)
        }

        fun renderAgslTile(
            tile: Bitmap,
            preset: CameraFilterPreset,
            intensity: Float
        ): Bitmap? {
            val width = tile.width
            val height = tile.height
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val renderer = HardwareRenderer()
            val renderNode = RenderNode("CameraFilterNode")
            try {
                renderNode.setPosition(0, 0, width, height)
                val shader = createConfiguredAgslShader(preset, intensity)
                shader.setInputShader(
                    "inputShader",
                    BitmapShader(tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                )
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                }
                val recCanvas = renderNode.beginRecording()
                try {
                    recCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                } finally {
                    renderNode.endRecording()
                }

                renderer.setSurface(imageReader.surface)
                renderer.setContentRoot(renderNode)
                renderer.createRenderRequest()
                    .setWaitForPresent(true)
                    .syncAndDraw()

                val image = imageReader.acquireLatestImage() ?: return null
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val paddedWidth = width + rowPadding / max(pixelStride, 1)
                    val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    buffer.rewind()
                    paddedBitmap.copyPixelsFromBuffer(buffer)
                    return if (paddedWidth == width) {
                        paddedBitmap
                    } else {
                        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                        paddedBitmap.recycle()
                        cropped
                    }
                } finally {
                    image.close()
                }
            } finally {
                runCatching { renderer.destroy() }
                runCatching { imageReader.close() }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31RenderEffectHelper {
        fun clearRenderEffect(view: View) {
            runCatching { view.setRenderEffect(null) }
        }

        fun applyColorFilterEffectToTextureView(
            textureView: TextureView,
            preset: CameraFilterPreset
        ): Boolean {
            return runCatching {
                val cf = CameraFilterProcessor.createColorMatrixColorFilter(preset)
                textureView.setLayerType(View.LAYER_TYPE_NONE, null)
                textureView.setRenderEffect(cf?.let { RenderEffect.createColorFilterEffect(it) })
                cf != null
            }.onFailure { err ->
                com.example.core.camera.CrashLogManager.recordNonFatalError("TextureView-RenderEffect", err)
            }.getOrDefault(false)
        }
    }

    /**
     * Pre-warms filter LUTs and filter tray thumbnails on a low-priority background thread so
     * opening the filter panel or capturing the first filtered photo has zero main-thread stutter.
     * Strictly uses CPU LUTs for tiny thumbnails — never touches EGL or HardwareRenderer during launch.
     */
    fun warmUpAsync() {
        if (!warmedUp.compareAndSet(false, true)) return
        runCatching {
            val thread = Thread(
                {
                    runCatching {
                        CameraFilterProcessor.warmUpThumbnailsBlocking()
                    }.onFailure { err ->
                        com.example.core.camera.CrashLogManager.recordNonFatalError("FilterWarmUp", err)
                    }
                },
                "CameraFilterWarmUp"
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            thread.start()
        }.onFailure { err ->
            warmedUp.set(false)
            com.example.core.camera.CrashLogManager.recordNonFatalError("FilterWarmUpStart", err)
        }
    }

    /**
     * Creates a [CameraEffect] backed by [processor] targeting the requested CameraX use cases
     * (e.g. [CameraEffect.PREVIEW] or `CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE`).
     */
    fun createGlCameraEffect(
        targets: Int,
        processor: GlFilterSurfaceProcessor,
        onError: ((Throwable) -> Unit)? = null
    ): CameraEffect {
        return GlFilterCameraEffect(
            targets = targets,
            executor = processor.glExecutor,
            surfaceProcessor = processor,
            errorListener = Consumer { err ->
                Log.w(TAG, "CameraEffect error: ${err.message}", err)
                onError?.invoke(err)
            }
        )
    }

    private class GlFilterCameraEffect(
        targets: Int,
        executor: Executor,
        surfaceProcessor: SurfaceProcessor,
        errorListener: Consumer<Throwable>
    ) : CameraEffect(targets, executor, surfaceProcessor, errorListener)

    /**
     * Locates the child [TextureView] inside a [PreviewView] (when running in `COMPATIBLE` mode).
     */
    fun findChildTextureView(root: View?): TextureView? {
        if (root == null) return null
        if (root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findChildTextureView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Applies or clears a GPU live-preview fallback effect directly on the active [TextureView]
     * (used only when CameraX [GlFilterSurfaceProcessor] is not active, such as Camera2 direct video
     * mode or an OEM Extension that declines [CameraEffect]).
     *
     * Never sets conflicting layer/effect flags on the outer [PreviewView] `FrameLayout`.
     * Returns the backend descriptor string for debug verification.
     */
    fun applyFilterToPreviewViewFallback(
        previewView: PreviewView?,
        camera2TextureView: TextureView?,
        preset: CameraFilterPreset,
        glProcessorActiveOnPreview: Boolean
    ): String {
        // Always clear outer FrameLayout so it never conflicts with child TextureView rendering.
        if (previewView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31RenderEffectHelper.clearRenderEffect(previewView)
            }
            if (previewView.layerType != View.LAYER_TYPE_NONE) {
                runCatching { previewView.setLayerType(View.LAYER_TYPE_NONE, null) }
            }
        }

        val previewChildTexture = findChildTextureView(previewView)
        val targets = listOfNotNull(previewChildTexture, camera2TextureView).distinct()

        // If CameraEffect GL shader is actively processing Preview frames, ensure TextureViews
        // do NOT double-apply a second effect (except camera2TextureView if it is visible).
        val effectiveTargets = if (glProcessorActiveOnPreview) {
            previewChildTexture?.let { clearTextureViewEffect(it) }
            listOfNotNull(camera2TextureView?.takeIf { it.visibility == View.VISIBLE })
        } else {
            targets
        }

        if (preset.isOriginal || effectiveTargets.isEmpty()) {
            effectiveTargets.forEach { clearTextureViewEffect(it) }
            return if (glProcessorActiveOnPreview) {
                if (preset.isOriginal) "GPU-CameraEffect-Bypass" else "GPU-CameraEffect-GLES"
            } else {
                "Preview-Bypass"
            }
        }

        var appliedBackend = "Preview-Fallback"
        effectiveTargets.forEach { tv ->
            appliedBackend = applyEffectToTextureView(tv, preset)
        }
        return appliedBackend
    }

    private fun clearTextureViewEffect(textureView: TextureView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31RenderEffectHelper.clearRenderEffect(textureView)
        }
        runCatching { textureView.setLayerType(View.LAYER_TYPE_NONE, null) }
    }

    private fun applyEffectToTextureView(
        textureView: TextureView,
        preset: CameraFilterPreset
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isEmulatorEnvironment()) {
            if (Api33AgslHelper.applyAgslEffectToTextureView(textureView, preset)) {
                return "GPU-TextureView-AGSL"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Api31RenderEffectHelper.applyColorFilterEffectToTextureView(textureView, preset)) {
                return "GPU-TextureView-RenderEffect"
            }
        }

        val cf = CameraFilterProcessor.createColorMatrixColorFilter(preset)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = cf }
        runCatching { textureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint) }
        return "GPU-TextureView-LayerPaint"
    }

    // =========================================================================
    // GPU Still-Photo Bitmap Filtering (AGSL HardwareRenderer / EGL14 GLSL FBO)
    // =========================================================================

    /**
     * Attempts to filter [source] entirely on the GPU using AGSL [RuntimeShader] + [HardwareRenderer]
     * (API 33+) or offscreen EGL14 OpenGL ES 2.0 (API 26–32).
     *
     * Returns `(filteredBitmap, backendName)` on success, or `null` if GPU rendering is unavailable
     * (e.g., headless JVM / Robolectric) so [CameraFilterProcessor] can fall back to multi-threaded CPU LUTs.
     */
    fun applyFilterToBitmapOnGpu(
        source: Bitmap,
        preset: CameraFilterPreset,
        intensity: Float = 1.0f
    ): Pair<Bitmap, String>? {
        val params = CameraFilterProcessor.getParameters(preset)
        if (params.isIdentity || intensity <= 0.001f) {
            return source to "GPU-Bypass"
        }
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        // Skip GPU attempt for tiny bitmaps (such as 88x88 filter tray thumbnails warmed up at startup)
        // or on Robolectric / basic emulator software GPU environments — CPU LUTs handle them safely in <1ms.
        if ((width < MIN_GPU_BITMAP_DIM_PX && height < MIN_GPU_BITMAP_DIM_PX) ||
            isJvmOrRobolectricEnvironment() ||
            isEmulatorEnvironment()
        ) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val agslResult = runCatching {
                applyFilterWithAgslHardwareRenderer(source, preset, intensity)
            }.onFailure { err ->
                com.example.core.camera.CrashLogManager.recordNonFatalError("BitmapFilter-AGSL", err)
            }.getOrNull()
            if (agslResult != null) {
                return agslResult to "GPU-AGSL"
            }
        }

        val glesResult = runCatching {
            applyFilterWithEglOffscreen(source, preset, intensity)
        }.onFailure { err ->
            com.example.core.camera.CrashLogManager.recordNonFatalError("BitmapFilter-EGL", err)
        }.getOrNull()
        if (glesResult != null) {
            return glesResult to "GPU-GLES"
        }

        return null
    }

    private fun isJvmOrRobolectricEnvironment(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: return true
        return fingerprint.equals("robolectric", ignoreCase = true) ||
            Build.HARDWARE.equals("robolectric", ignoreCase = true)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyFilterWithAgslHardwareRenderer(
        source: Bitmap,
        preset: CameraFilterPreset,
        intensity: Float
    ): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= MAX_GPU_TILE_DIM && height <= MAX_GPU_TILE_DIM) {
            return Api33AgslHelper.renderAgslTile(source, preset, intensity)
        }

        // Tile very large bitmaps (e.g., 12–108 MP) so we never exceed GPU texture limits
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBitmap)
        var y = 0
        while (y < height) {
            val tileH = min(MAX_GPU_TILE_DIM, height - y)
            var x = 0
            while (x < width) {
                val tileW = min(MAX_GPU_TILE_DIM, width - x)
                val sub = Bitmap.createBitmap(source, x, y, tileW, tileH)
                try {
                    val rendered = Api33AgslHelper.renderAgslTile(sub, preset, intensity) ?: run {
                        outBitmap.recycle()
                        return null
                    }
                    try {
                        outCanvas.drawBitmap(rendered, x.toFloat(), y.toFloat(), null)
                    } finally {
                        if (!rendered.isRecycled) rendered.recycle()
                    }
                } finally {
                    if (sub !== source && !sub.isRecycled) sub.recycle()
                }
                x += tileW
            }
            y += tileH
        }
        return outBitmap
    }

    private fun applyFilterWithEglOffscreen(
        source: Bitmap,
        preset: CameraFilterPreset,
        intensity: Float
    ): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= MAX_GPU_TILE_DIM && height <= MAX_GPU_TILE_DIM) {
            return renderEglTile(source, preset, intensity)
        }
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBitmap)
        var y = 0
        while (y < height) {
            val tileH = min(MAX_GPU_TILE_DIM, height - y)
            var x = 0
            while (x < width) {
                val tileW = min(MAX_GPU_TILE_DIM, width - x)
                val sub = Bitmap.createBitmap(source, x, y, tileW, tileH)
                try {
                    val rendered = renderEglTile(sub, preset, intensity) ?: run {
                        outBitmap.recycle()
                        return null
                    }
                    try {
                        outCanvas.drawBitmap(rendered, x.toFloat(), y.toFloat(), null)
                    } finally {
                        if (!rendered.isRecycled) rendered.recycle()
                    }
                } finally {
                    if (sub !== source && !sub.isRecycled) sub.recycle()
                }
                x += tileW
            }
            y += tileH
        }
        return outBitmap
    }

    private fun renderEglTile(
        tile: Bitmap,
        preset: CameraFilterPreset,
        intensity: Float
    ): Bitmap? {
        val width = tile.width
        val height = tile.height
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return null
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) return null

        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var shaderProgram: GlFilterProgram? = null
        val textures = IntArray(1)

        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0 || configs[0] == null
            ) {
                return null
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return null

            shaderProgram = GlFilterProgram(isExternalOes = false)
            if (!shaderProgram.isValid) return null

            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tile, 0)

            val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
            GLES20.glViewport(0, 0, width, height)
            shaderProgram.draw(
                textureId = textures[0],
                texMatrix = identityMatrix,
                params = CameraFilterProcessor.getParameters(preset),
                intensity = intensity,
                flipVertical = false
            )
            GLES20.glFinish()

            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()

            val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBitmap.copyPixelsFromBuffer(pixelBuffer)
            return outBitmap
        } finally {
            if (textures[0] != 0) {
                runCatching { GLES20.glDeleteTextures(1, textures, 0) }
            }
            shaderProgram?.release()
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            // NEVER call EGL14.eglTerminate(EGL_DEFAULT_DISPLAY): EGL_DEFAULT_DISPLAY is shared
            // process-wide with HWUI/RenderThread (Compose). Terminating it kills the UI RenderThread!
            runCatching { EGL14.eglReleaseThread() }
        }
    }

    // =========================================================================
    // GPU Surface-to-Surface Video Transcoder (for Camera2 Direct / Fallback Video)
    // =========================================================================

    /**
     * Transcodes [inputFile] to [outputFile] entirely on the GPU via
     * `MediaCodec Decoder -> SurfaceTexture (OES) -> GLSL Filter Shader -> MediaCodec Encoder Input Surface`,
     * preserving the original audio track, frame timestamps, and rotation metadata.
     */
    fun transcodeVideoOnGpu(
        inputFile: File,
        outputFile: File,
        preset: CameraFilterPreset,
        intensity: Float = 1.0f,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (isJvmOrRobolectricEnvironment() || isEmulatorEnvironment()) return false
        if (!inputFile.exists() || inputFile.length() == 0L) return false

        val params = CameraFilterProcessor.getParameters(preset)
        if (params.isIdentity || intensity <= 0.001f) {
            return false
        }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var encoderInputSurface: Surface? = null
        var decoderSurface: Surface? = null
        var decoderSurfaceTexture: SurfaceTexture? = null
        var shaderProgram: GlFilterProgram? = null
        val oesTextures = IntArray(1)

        try {
            val retriever = MediaMetadataRetriever()
            val rotationDegrees: Int
            val durationUs: Long
            try {
                retriever.setDataSource(inputFile.absolutePath)
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            } finally {
                runCatching { retriever.release() }
            }

            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioFormat = fmt
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return false

            val width = (videoFormat.getInteger(MediaFormat.KEY_WIDTH) and 0xFFFE).coerceAtLeast(16)
            val height = (videoFormat.getInteger(MediaFormat.KEY_HEIGHT) and 0xFFFE).coerceAtLeast(16)
            val fps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30).coerceIn(15, 60)
            } else {
                30
            }
            val bitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                runCatching { videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(width * height * 6)
            } else {
                width * height * 6
            }.coerceAtLeast(3_000_000)

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return false

            val encodeFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // Setup EGL context bound to encoder's input Surface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) return false

            val eglConfig = chooseEglConfig(eglDisplay, recordable = true) ?: return false
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                encoderInputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            shaderProgram = GlFilterProgram(isExternalOes = true)
            if (!shaderProgram.isValid) return false

            GLES20.glGenTextures(1, oesTextures, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextures[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val frameSyncLock = Object()
            var frameAvailable = false
            val callbackThread = HandlerThread("GpuVideoTranscodeFrame").apply { start() }
            try {
                decoderSurfaceTexture = SurfaceTexture(oesTextures[0]).apply {
                    setOnFrameAvailableListener({
                        synchronized(frameSyncLock) {
                            frameAvailable = true
                            frameSyncLock.notifyAll()
                        }
                    }, Handler(callbackThread.looper))
                }
                decoderSurface = Surface(decoderSurfaceTexture)

                decoder = MediaCodec.createDecoderByType(videoMime)
                decoder.configure(videoFormat, decoderSurface, null, 0)
                decoder.start()

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
                    muxer.setOrientationHint(rotationDegrees)
                }

                extractor.selectTrack(videoTrackIndex)
                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                val texMatrix = FloatArray(16)

                var muxerVideoTrack = -1
                var muxerAudioTrack = -1
                var inputDone = false
                var decoderDone = false
                var encoderDone = false
                var framesRendered = 0

                fun drainEncoder(waitUs: Long) {
                    while (true) {
                        val encStatus = encoder.dequeueOutputBuffer(encInfo, waitUs)
                        if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                                if (audioFormat != null) {
                                    muxerAudioTrack = runCatching { muxer.addTrack(audioFormat) }.getOrDefault(-1)
                                }
                                muxer.start()
                                muxerStarted = true
                            }
                        } else if (encStatus >= 0) {
                            val outBuf = encoder.getOutputBuffer(encStatus)
                            if (outBuf != null && encInfo.size > 0 &&
                                (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                muxerStarted && muxerVideoTrack >= 0
                            ) {
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, outBuf, encInfo)
                            }
                            val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encStatus, false)
                            if (isEos) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }

                while (!encoderDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(5_000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    if (!decoderDone) {
                        val outIdx = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                        if (outIdx >= 0) {
                            val doRender = decInfo.size > 0
                            val ptsUs = decInfo.presentationTimeUs
                            val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outIdx, doRender)

                            if (doRender) {
                                synchronized(frameSyncLock) {
                                    val deadline = System.currentTimeMillis() + 1500L
                                    while (!frameAvailable && System.currentTimeMillis() < deadline) {
                                        frameSyncLock.wait(100L)
                                    }
                                    frameAvailable = false
                                }
                                decoderSurfaceTexture.updateTexImage()
                                decoderSurfaceTexture.getTransformMatrix(texMatrix)
                                GLES20.glViewport(0, 0, width, height)
                                shaderProgram.draw(
                                    textureId = oesTextures[0],
                                    texMatrix = texMatrix,
                                    params = params,
                                    intensity = intensity,
                                    flipVertical = false
                                )
                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                                framesRendered++
                                if (durationUs > 0L) {
                                    onProgress?.invoke((ptsUs.toFloat() / durationUs.toFloat()).coerceIn(0.05f, 0.92f))
                                }
                            }

                            if (isEos) {
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }

                    drainEncoder(if (decoderDone) 10_000L else 0L)
                }

                // Copy original audio track verbatim
                if (audioTrackIndex >= 0 && muxerStarted && muxerAudioTrack >= 0) {
                    val audioExtractor = MediaExtractor()
                    try {
                        audioExtractor.setDataSource(inputFile.absolutePath)
                        audioExtractor.selectTrack(audioTrackIndex)
                        val audioBuffer = ByteBuffer.allocate(256 * 1024)
                        val audioInfo = MediaCodec.BufferInfo()
                        while (true) {
                            audioBuffer.clear()
                            val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                            if (sampleSize < 0) break
                            audioInfo.offset = 0
                            audioInfo.size = sampleSize
                            audioInfo.presentationTimeUs = audioExtractor.sampleTime
                            val sampleFlags = audioExtractor.sampleFlags
                            var bufferFlags = 0
                            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                bufferFlags = bufferFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                bufferFlags = bufferFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            }
                            audioInfo.flags = bufferFlags
                            muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                            if (!audioExtractor.advance()) break
                        }
                    } finally {
                        runCatching { audioExtractor.release() }
                    }
                }
                onProgress?.invoke(1.0f)
                return framesRendered > 0 && muxerStarted && outputFile.exists() && outputFile.length() > 0L
            } finally {
                runCatching { callbackThread.quitSafely() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU video transcode failed for ${preset.id}: ${t.message}", t)
            return false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { decoderSurfaceTexture?.release() }
            runCatching { encoderInputSurface?.release() }
            if (oesTextures[0] != 0) {
                runCatching { GLES20.glDeleteTextures(1, oesTextures, 0) }
            }
            shaderProgram?.release()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                }
                // Never call eglTerminate on EGL_DEFAULT_DISPLAY (shared with HWUI RenderThread)
                runCatching { EGL14.eglReleaseThread() }
            }
        }
    }

    internal fun chooseEglConfig(eglDisplay: EGLDisplay, recordable: Boolean): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (recordable) {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
            ) {
                return configs[0]
            }
        }
        val fallbackAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        return if (EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0) &&
            numConfigs[0] > 0
        ) {
            configs[0]
        } else {
            null
        }
    }

    // =========================================================================
    // OpenGL ES 2.0 Shader Program Wrapper
    // =========================================================================

    internal class GlFilterProgram(private val isExternalOes: Boolean) {
        private val programId: Int
        private val positionLoc: Int
        private val texCoordLoc: Int
        private val texMatrixLoc: Int
        private val textureLoc: Int
        private val isIdentityLoc: Int
        private val intensityLoc: Int
        private val isMonoLoc: Int
        private val contrastLoc: Int
        private val saturationLoc: Int
        private val warmthLoc: Int
        private val tintLoc: Int
        private val exposureLoc: Int
        private val shadowLiftLoc: Int
        private val highlightRolloffLoc: Int
        private val sCurveLoc: Int
        private val skinProtectLoc: Int
        private val lumaWeightsLoc: Int
        private val shadowTintLoc: Int
        private val highlightTintLoc: Int

        private val quadPositions: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        private val quadTexCoordsNormal: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            )
        )
        private val quadTexCoordsFlipped: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

        val isValid: Boolean
            get() = programId != 0

        init {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE)
            val fragmentShader = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                buildFilterFragmentShader(isExternalOes)
            )
            programId = if (vertexShader != 0 && fragmentShader != 0) {
                val prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, vertexShader)
                GLES20.glAttachShader(prog, fragmentShader)
                GLES20.glLinkProgram(prog)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                if (linkStatus[0] == GLES20.GL_TRUE) {
                    prog
                } else {
                    Log.e(TAG, "GL program link error: ${GLES20.glGetProgramInfoLog(prog)}")
                    GLES20.glDeleteProgram(prog)
                    0
                }
            } else {
                if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
                if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
                0
            }

            if (programId != 0) {
                positionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
                texCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
                texMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
                textureLoc = GLES20.glGetUniformLocation(programId, "uTexture")
                isIdentityLoc = GLES20.glGetUniformLocation(programId, "uIsIdentity")
                intensityLoc = GLES20.glGetUniformLocation(programId, "uIntensity")
                isMonoLoc = GLES20.glGetUniformLocation(programId, "uIsMono")
                contrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
                saturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
                warmthLoc = GLES20.glGetUniformLocation(programId, "uWarmth")
                tintLoc = GLES20.glGetUniformLocation(programId, "uTint")
                exposureLoc = GLES20.glGetUniformLocation(programId, "uExposure")
                shadowLiftLoc = GLES20.glGetUniformLocation(programId, "uShadowLift")
                highlightRolloffLoc = GLES20.glGetUniformLocation(programId, "uHighlightRolloff")
                sCurveLoc = GLES20.glGetUniformLocation(programId, "uSCurve")
                skinProtectLoc = GLES20.glGetUniformLocation(programId, "uSkinProtect")
                lumaWeightsLoc = GLES20.glGetUniformLocation(programId, "uLumaWeights")
                shadowTintLoc = GLES20.glGetUniformLocation(programId, "uShadowTint")
                highlightTintLoc = GLES20.glGetUniformLocation(programId, "uHighlightTint")
            } else {
                positionLoc = -1; texCoordLoc = -1; texMatrixLoc = -1; textureLoc = -1
                isIdentityLoc = -1; intensityLoc = -1; isMonoLoc = -1; contrastLoc = -1
                saturationLoc = -1; warmthLoc = -1; tintLoc = -1; exposureLoc = -1
                shadowLiftLoc = -1; highlightRolloffLoc = -1; sCurveLoc = -1; skinProtectLoc = -1
                lumaWeightsLoc = -1; shadowTintLoc = -1; highlightTintLoc = -1
            }
        }

        fun draw(
            textureId: Int,
            texMatrix: FloatArray,
            params: CameraFilterParameters,
            intensity: Float,
            flipVertical: Boolean = false
        ) {
            if (programId == 0) return
            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            val texTarget = if (isExternalOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(texTarget, textureId)
            GLES20.glUniform1i(textureLoc, 0)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)

            GLES20.glUniform1f(isIdentityLoc, if (params.isIdentity) 1f else 0f)
            GLES20.glUniform1f(intensityLoc, intensity.coerceIn(0f, 1f))
            GLES20.glUniform1f(isMonoLoc, if (params.isMonochrome) 1f else 0f)
            GLES20.glUniform1f(contrastLoc, params.contrast)
            GLES20.glUniform1f(saturationLoc, params.saturation)
            GLES20.glUniform1f(warmthLoc, params.warmth)
            GLES20.glUniform1f(tintLoc, params.tint)
            GLES20.glUniform1f(exposureLoc, params.exposureOffset)
            GLES20.glUniform1f(shadowLiftLoc, params.shadowLift)
            GLES20.glUniform1f(highlightRolloffLoc, params.highlightRolloff)
            GLES20.glUniform1f(sCurveLoc, params.sCurveStrength)
            GLES20.glUniform1f(skinProtectLoc, params.skinToneProtection)
            GLES20.glUniform3f(lumaWeightsLoc, params.lumaR, params.lumaG, params.lumaB)
            GLES20.glUniform3f(shadowTintLoc, params.shadowTintR, params.shadowTintG, params.shadowTintB)
            GLES20.glUniform3f(highlightTintLoc, params.highlightTintR, params.highlightTintG, params.highlightTintB)

            if (positionLoc < 0 || texCoordLoc < 0) return

            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, quadPositions)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glVertexAttribPointer(
                texCoordLoc,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                if (flipVertical) quadTexCoordsFlipped else quadTexCoordsNormal
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)
            GLES20.glBindTexture(texTarget, 0)
        }

        fun release() {
            if (programId != 0) {
                runCatching { GLES20.glDeleteProgram(programId) }
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "GL shader compile error ($type): ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun createFloatBuffer(data: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
        }
    }
}

/**
 * Real-time OpenGL ES 2.0 [SurfaceProcessor] for CameraX [CameraEffect].
 *
 * Processes every camera frame on a dedicated GL thread and renders simultaneously to both
 * `Preview` and `VideoCapture` output surfaces with zero main-thread blocking and instant,
 * rebind-free filter switching whenever [activePreset] changes.
 */
class GlFilterSurfaceProcessor(
    private val onGlRuntimeError: ((Throwable) -> Unit)? = null
) : SurfaceProcessor {
    private val glThread = HandlerThread("GlCameraFilterThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    val glExecutor: Executor = Executor { command ->
        if (!released.get()) {
            glHandler.post {
                try {
                    command.run()
                } catch (t: Throwable) {
                    handleGlFailure("glExecutor", t)
                }
            }
        }
    }

    @Volatile
    var activePreset: CameraFilterPreset = CameraFilterPreset.ORIGINAL

    @Volatile
    var activeIntensity: Float = 1.0f

    private val released = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val activeOutputCount = AtomicInteger(0)
    private val renderedFrameCounter = AtomicLong(0L)

    val isActivelyRendering: Boolean
        get() = !released.get() && activeOutputCount.get() > 0 && renderedFrameCounter.get() > 0L

    val hasAttachedOutputs: Boolean
        get() = !released.get() && activeOutputCount.get() > 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var tempPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var filterProgram: GpuCameraFilterEngine.GlFilterProgram? = null

    private var inputTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private data class OutputTarget(
        val surfaceOutput: SurfaceOutput,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val size: Size
    )

    private val outputTargets = LinkedHashMap<SurfaceOutput, OutputTarget>()
    private val inputTexMatrix = FloatArray(16)
    private val outputTexMatrix = FloatArray(16)

    private fun handleGlFailure(stage: String, throwable: Throwable) {
        com.example.core.camera.CrashLogManager.recordNonFatalError("GlFilterSurfaceProcessor.$stage", throwable)
        GpuCameraFilterEngine.markGlSurfaceProcessorFailed()
        if (errorReported.compareAndSet(false, true)) {
            runCatching { onGlRuntimeError?.invoke(throwable) }
        }
    }

    private fun ensureGlInitialized(): Boolean {
        if (eglContext != EGL14.EGL_NO_CONTEXT && filterProgram?.isValid == true) {
            return true
        }
        return try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) return false

            val config = GpuCameraFilterEngine.chooseEglConfig(eglDisplay, recordable = true) ?: return false
            eglConfig = config
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            tempPbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
            if (tempPbufferSurface == EGL14.EGL_NO_SURFACE) return false

            if (!EGL14.eglMakeCurrent(eglDisplay, tempPbufferSurface, tempPbufferSurface, eglContext)) {
                return false
            }

            filterProgram = GpuCameraFilterEngine.GlFilterProgram(isExternalOes = true)
            filterProgram?.isValid == true
        } catch (t: Throwable) {
            handleGlFailure("ensureGlInitialized", t)
            false
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            runCatching { request.willNotProvideSurface() }
            return
        }
        glExecutor.execute {
            if (released.get() || !ensureGlInitialized()) {
                runCatching { request.willNotProvideSurface() }
                if (!released.get()) {
                    handleGlFailure("onInputSurface.init", IllegalStateException("EGL/GLSL initialization failed"))
                }
                return@execute
            }

            try {
                // Release any stale input surface from a previous pipeline bind
                releaseInputSurfaceInternal()

                if (!EGL14.eglMakeCurrent(eglDisplay, tempPbufferSurface, tempPbufferSurface, eglContext)) {
                    throw IllegalStateException("eglMakeCurrent failed in onInputSurface")
                }
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                inputTextureId = textures[0]
                if (inputTextureId == 0) {
                    throw IllegalStateException("glGenTextures returned 0")
                }
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                val st = SurfaceTexture(inputTextureId).apply {
                    setDefaultBufferSize(request.resolution.width, request.resolution.height)
                    setOnFrameAvailableListener({ renderAvailableFrame() }, glHandler)
                }
                val surface = Surface(st)
                inputSurfaceTexture = st
                inputSurface = surface

                request.provideSurface(surface, glExecutor) {
                    // Only clean up if this callback belongs to the current surface
                    if (inputSurface === surface) {
                        releaseInputSurfaceInternal()
                    } else {
                        runCatching { surface.release() }
                        runCatching { st.release() }
                    }
                }
            } catch (t: Throwable) {
                releaseInputSurfaceInternal()
                runCatching { request.willNotProvideSurface() }
                handleGlFailure("onInputSurface", t)
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released.get()) {
            runCatching { surfaceOutput.close() }
            return
        }
        glExecutor.execute {
            if (released.get() || !ensureGlInitialized()) {
                runCatching { surfaceOutput.close() }
                if (!released.get()) {
                    handleGlFailure("onOutputSurface.init", IllegalStateException("EGL/GLSL initialization failed"))
                }
                return@execute
            }

            try {
                val surface = surfaceOutput.getSurface(glExecutor) { event ->
                    if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                        removeOutputSurfaceInternal(surfaceOutput)
                        runCatching { surfaceOutput.close() }
                    }
                }

                val config = eglConfig ?: run {
                    runCatching { surfaceOutput.close() }
                    return@execute
                }

                val eglWindowSurface = runCatching {
                    EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        config,
                        surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                }.getOrDefault(EGL14.EGL_NO_SURFACE)

                if (eglWindowSurface == EGL14.EGL_NO_SURFACE) {
                    runCatching { surfaceOutput.close() }
                    handleGlFailure("onOutputSurface.window", IllegalStateException("eglCreateWindowSurface returned EGL_NO_SURFACE"))
                    return@execute
                }

                outputTargets[surfaceOutput] = OutputTarget(
                    surfaceOutput = surfaceOutput,
                    surface = surface,
                    eglSurface = eglWindowSurface,
                    size = surfaceOutput.size
                )
                activeOutputCount.set(outputTargets.size)
            } catch (t: Throwable) {
                runCatching { surfaceOutput.close() }
                handleGlFailure("onOutputSurface", t)
            }
        }
    }

    private fun renderAvailableFrame() {
        if (released.get()) return
        val st = inputSurfaceTexture ?: return
        val program = filterProgram ?: return
        if (!program.isValid || inputTextureId == 0) return

        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && tempPbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, tempPbufferSurface, tempPbufferSurface, eglContext)
            }
            st.updateTexImage()
            st.getTransformMatrix(inputTexMatrix)

            if (outputTargets.isEmpty()) return

            val timestampNs = st.timestamp
            val preset = activePreset
            val intensity = activeIntensity
            val params = CameraFilterProcessor.getParameters(preset)

            val iterator = outputTargets.values.iterator()
            while (iterator.hasNext()) {
                val target = iterator.next()
                if (!target.surface.isValid) continue
                runCatching {
                    target.surfaceOutput.updateTransformMatrix(outputTexMatrix, inputTexMatrix)
                    if (EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)) {
                        GLES20.glViewport(0, 0, target.size.width, target.size.height)
                        program.draw(
                            textureId = inputTextureId,
                            texMatrix = outputTexMatrix,
                            params = params,
                            intensity = intensity,
                            flipVertical = false
                        )
                        if (timestampNs > 0L) {
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, target.eglSurface, timestampNs)
                        }
                        EGL14.eglSwapBuffers(eglDisplay, target.eglSurface)
                    }
                }
            }
            renderedFrameCounter.incrementAndGet()
        } catch (t: Throwable) {
            handleGlFailure("renderAvailableFrame", t)
        }
    }

    private fun removeOutputSurfaceInternal(surfaceOutput: SurfaceOutput) {
        val removed = outputTargets.remove(surfaceOutput) ?: return
        activeOutputCount.set(outputTargets.size)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && removed.eglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, tempPbufferSurface, tempPbufferSurface, eglContext)
                EGL14.eglDestroySurface(eglDisplay, removed.eglSurface)
            }
        }
    }

    private fun releaseInputSurfaceInternal() {
        runCatching { inputSurfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { inputSurface?.release() }
        runCatching { inputSurfaceTexture?.release() }
        inputSurface = null
        inputSurfaceTexture = null
        if (inputTextureId != 0 && eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, tempPbufferSurface, tempPbufferSurface, eglContext)
                GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            }
            inputTextureId = 0
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching {
            glHandler.post {
                runCatching {
                    releaseInputSurfaceInternal()
                    val outputs = outputTargets.keys.toList()
                    outputs.forEach { out ->
                        removeOutputSurfaceInternal(out)
                        runCatching { out.close() }
                    }
                    outputTargets.clear()
                    activeOutputCount.set(0)
                    filterProgram?.release()
                    filterProgram = null
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        runCatching {
                            EGL14.eglMakeCurrent(
                                eglDisplay,
                                EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_CONTEXT
                            )
                        }
                        if (tempPbufferSurface != EGL14.EGL_NO_SURFACE) {
                            runCatching { EGL14.eglDestroySurface(eglDisplay, tempPbufferSurface) }
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                        }
                        // NEVER call EGL14.eglTerminate(EGL_DEFAULT_DISPLAY): it is shared process-wide
                        // with HWUI RenderThread! Only release this thread's EGL state.
                        runCatching { EGL14.eglReleaseThread() }
                    }
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                    eglContext = EGL14.EGL_NO_CONTEXT
                    tempPbufferSurface = EGL14.EGL_NO_SURFACE
                    eglConfig = null
                }
                runCatching { glThread.quitSafely() }
            }
        }
    }
}
