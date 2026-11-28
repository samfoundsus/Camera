# Preserve source file names and line numbers for readable crash stack traces in release/minified builds.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# CameraX / Camera2Interop / Extensions reflection safety (including QHD custom resolution reflection)
-keep class androidx.camera.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keepclassmembers class androidx.camera.video.VideoCapture$Builder {
    public * setCustomOrderedResolutions(...);
}

# ML Kit Subject Segmentation safety
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Moshi / Kotlinx Serialization & reflective model adapters
-keep class com.squareup.moshi.** { *; }
-keep class kotlinx.serialization.** { *; }

# Keep GPU filter / crash diagnostics classes intact for debugging and stack trace attribution
-keep class com.example.core.camera.CrashLogManager { *; }
-keep class com.example.core.processing.GpuCameraFilterEngine { *; }
-keep class com.example.core.processing.GlFilterSurfaceProcessor { *; }
