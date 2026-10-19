# Camera 📷

A high-performance, feature-rich Android camera application inspired by the Google Pixel Camera user experience. Built from the ground up using modern Android development practices, **Kotlin**, **Jetpack Compose**, **Material Design 3**, **CameraX**, and **Camera2** interop APIs.

> **Disclaimer:** This is an independent, open-source Android camera implementation demonstrating modern Android architecture and computational photography UI flows. It is not an official Google Camera or Pixel Camera application.

---

## 🌟 Key Features & Camera Modes

### 📸 Capture Modes
The application includes a complete 10-mode camera capture suite (`com.example.core.model.CameraMode`):

1. **Camera (Photo Mode):** Standard zero-shutter-lag (ZSL) photo capture with instant tap-to-focus, zoom shortcuts, flash popup, grid overlays, aspect ratio selection, and live color filters.
2. **Video:** High-definition video recording powered by CameraX `VideoCapture` and `Recorder`. Features live recording duration overlay, audio toggle, video stabilization modes, and real-time live preview filters.
3. **Portrait:** Subject depth segmentation with background bokeh blur control (aperture values $f/1.4$ to $f/16$), facial smoothing, and portrait retouching intensity settings.
4. **Night Sight:** Multi-frame low-light burst capture with selectable exposure durations (Auto, 2s, 3s, 6s) and multi-frame noise reduction pipeline.
5. **HD Capture:** High-resolution photo capture maximizing native hardware sensor megapixel output.
6. **Pro Mode:** Professional manual sensor control bar with live parameters:
   - **ISO Sensitivity:** Manual slider from ISO 50 up to ISO 6400 (hardware capable) with Auto toggle.
   - **Shutter Speed:** Manual exposure timing from 1/8000s up to 1/2s.
   - **Focus Distance:** Fine manual focus control slider with Focus Lock.
   - **White Balance:** Color temperature adjustment (2000K to 10000K) and presets.
   - **EV Compensation:** Exposure Value adjustments from -3.0 EV to +3.0 EV.
   - **RAW Format:** Toggle for uncompressed RAW / DNG format capture.
   - **Live Telemetry:** Real-time luminance histogram calculation and focus peaking edge detection (`ProLiveAnalysis`).
7. **Panorama:** Step-by-step panoramic photo capture with interactive direction arrows, preview strip, and frame stitching pipeline (`PanoramaProcessor`).
8. **Astrophotography:** Star and sky long-exposure simulation with tripod stability detection notice and multi-frame stacking (`AstroProcessor`).
9. **Slow Motion:** High-speed video recording (120 FPS / 240 FPS) with selectable slow-motion playback speed multipliers.
10. **Time Lapse:** Interval-based recording with customizable interval speeds (5x, 10x, 30x, 120x).

---

## 🎨 Pixel-Style Color Filters

The app features a unified color filter system (`CameraFilterProcessor`) with 18 selectable presets categorized into two groups:

* **Natural / Pixel-Inspired:** *Original*, *Natural*, *Warm*, *Cool*, *Rich*, *Soft*, *Portrait Natural*, *Night Natural*.
* **Creative:** *Vivid*, *Cinematic*, *Monochrome*, *Film*, *Retro*, *Dramatic*, *Amber*, *Noir*, *Sepia*, *Emerald*.

### Filter Pipeline Integrity & Visibility
* **Mode Restriction:** To ensure consistent image capture quality, the Filter Tray is strictly visible and enabled in **Camera/Photo** and **Video** modes only. It is automatically hidden in specialized processing modes (Portrait, Night Sight, HD, Pro, Panorama, Astro, Slow Motion, Time Lapse).
* **Unified Output:** Live camera preview, saved photo JPEGs, and exported recorded videos share the exact same filter color matrix transformation. Preview-only filters that disappear from final output are strictly avoided.

---

## ⚙️ Camera Controls & Settings

* **Aspect Ratios:** Native `4:3`, `16:9`, and `1:1` square cropping (`AspectCropUtils`).
* **Framing Grids:** `Off`, `3 × 3`, `4 × 4`, and `Golden Ratio` canvas overlays.
* **Flash Modes:** `Auto`, `On`, `Off`, and continuous `Torch` with quick-access popup menu.
* **Timer:** `Off`, `3s`, and `10s` countdown timer with animated viewfinder indicator.
* **Camera Switching:** Instant toggle between rear main lens and front selfie camera with optional selfie mirroring.
* **Settings Persistence:** Preferences stored securely via `CameraPreferencesRepository` using `SharedPreferences`.
* **Adaptive Material You:** Dynamic Monet wallpaper color extraction on Android 12+ (API 31+) with fallback to dark/light static obsidian palette on Android 10–11 (API 29–30).

---

## 🏗 Technical Architecture

```text
com.example/
├── MainActivity.kt                      # Host Activity with edge-to-edge system bars
├── core/
│   ├── camera/
│   │   ├── CameraCapabilities.kt        # Lens capabilities & hardware discovery
│   │   ├── CameraContract.kt            # Lens facing & camera engine states
│   │   ├── NightSightDeviceSupport.kt    # HAL level checks for low-light burst
│   │   └── PortraitBlurDeviceSupport.kt  # Hardware depth/segmentation support
│   ├── model/
│   │   ├── CameraMode.kt                # 10 capture modes with filter support flags
│   │   └── SystemDeviceInfo.kt          # Telemetry & API level capability checks
│   ├── processing/
│   │   ├── AspectCropUtils.kt           # Aspect ratio crop rect calculations
│   │   ├── AstroProcessor.kt            # Multi-frame astrophotography stacking
│   │   ├── CameraFilterProcessor.kt     # GPU/ColorMatrix filter engine
│   │   ├── HdrProcessor.kt              # Exposure bracketing & tone mapping
│   │   ├── NightSightProcessor.kt       # Multi-frame low-light noise reduction
│   │   ├── PanoramaProcessor.kt         # Live panoramic stitching pipeline
│   │   ├── PortraitProcessor.kt         # Segmentation & depth bokeh blur
│   │   └── ProLiveAnalysis.kt           # Live histogram & focus peaking analyzer
│   └── storage/
│       └── MediaStorageContract.kt      # Scoped Storage & EXIF handling
├── feature/
│   ├── preview/
│   │   ├── CameraFoundationScreen.kt    # Root viewfinder Composable screen
│   │   └── components/
│   │       ├── CameraFilterTray.kt      # Horizontal filter selector tray
│   │       ├── CameraModeSelector.kt    # Centered mode carousel
│   │       ├── CameraPreviewPlaceholder.kt # Reticles, zoom slider & badge overlays
│   │       ├── CameraProControlBar.kt   # Manual ISO/Shutter/Focus control bar
│   │       ├── CameraShutterBar.kt      # Pixel double-ring shutter & controls
│   │       ├── CameraTopBar.kt          # Minimal top control bar & flash popup
│   │       └── CameraXViewfinder.kt     # CameraX PreviewView & capture engine
│   └── settings/
│       └── SettingsScreen.kt            # Categorized camera preferences
└── ui/
    ├── components/                      # Reusable Material 3 dialogs & buttons
    └── theme/
        ├── CameraMotionTokens.kt        # Material 3 Expressive motion physics
        ├── Color.kt                     # Dark & Light obsidian camera palettes
        ├── Theme.kt                     # Adaptive dynamic/static theme engine
        └── Type.kt                      # Material 3 typography
```

### Stack Summary
* **Language:** Kotlin 2.2
* **UI Framework:** Jetpack Compose (BOM 2024.09.00) with Material Design 3
* **Camera Architecture:** CameraX 1.4/1.5 (`camera-core`, `camera-camera2`, `camera-video`, `camera-lifecycle`, `camera-view`) supplemented with direct Camera2 `CaptureRequest` overrides for manual ISO, shutter speed, focus distance, EV compensation, white balance, and RAW sensor capture.
* **Asynchronous Processing:** Kotlin Coroutines (`StateFlow`, `SharedFlow`) and background thread dispatchers.
* **Storage:** Android `MediaStore` Scoped Storage API with `ExifInterface` EXIF metadata preservation.

---

## 📱 Hardware HAL Levels & Compatibility

Camera performance and feature availability depend on the host device's Camera2 Hardware Support Level (`INFO_SUPPORTED_HARDWARE_LEVEL`):

| Hardware Level | Capabilities & Fallbacks |
|---|---|
| **LEGACY** | Basic single-frame software capture. Manual exposure bracketing, RAW streams, and per-frame control are disabled. |
| **LIMITED** | Standard CameraX preview and photo/video recording. High-speed video (120/240 FPS) and manual RAW may be unavailable. |
| **FULL / LEVEL_3** | Full sensor hardware control, `RAW_SENSOR` streams, per-frame manual exposure/gain override, and fast burst buffering for custom multi-frame HDR+ and Astrophotography. |

### Android API Requirements
* **Min SDK:** API 29 (Android 10) — Scoped Storage enforced.
* **Target / Compile SDK:** API 36 (Android 15/16).
* **JDK:** Java 17 toolchain.

---

## ⚙️ Environment Configuration & Secrets

The project uses the **Secrets Gradle Plugin** (`com.google.android.libraries.mapsplatform.secrets-gradle-plugin`) configured in `app/build.gradle.kts`:

```kotlin
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}
```

* `.env.example` provides default variable templates (such as `GEMINI_API_KEY`) for local development and CI environments.
* Local secrets can be defined in a `.env` file (git-ignored) or injected via AI Studio user secrets.

---

## 🛠 Build & Run Instructions

### Prerequisites
1. **Android Studio:** Ladybug (2024.2.1+) or newer.
2. **JDK:** JDK 17 configured in Android Studio (`Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`).
3. **Android SDK:** Platforms 36 and 29 installed via Android SDK Manager.

### Building & Running
1. Clone the repository and open the project root in Android Studio.
2. Allow Gradle to sync dependencies automatically.
3. Connect an Android device (API 29+) or start an Android Emulator.
4. Execute the debug build:
   ```bash
   ./gradlew assembleDebug
   ```
   The compiled APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🧪 Testing & CI Pipeline

The project includes an extensive unit and Robolectric test suite located in `app/src/test/java/com/example/`:

* `CameraFiltersTest.kt`: Validates all 18 filter presets, color matrix transforms, non-destructive processing, and thumbnail caching.
* `ExampleRobolectricTest.kt`: CUJ UI tests, preferences persistence, aspect ratio crops, mode selector centering, and top bar controls.
* `NightSightPhase33Test.kt`: Tests low-light exposure selections and hardware level validations.
* `PanoramaModeTest.kt`: Validates panorama live state transitions, step counters, and frame stitching logic.
* `PhotoPipelinePhase31Test.kt`: Validates JPEG capture, EXIF metadata, and MediaStore saving.
* `PortraitModePhase32Test.kt`: Validates aperture blur values and portrait retouching levels.
* `ProLiveAnalysisTest.kt`: Validates live histogram calculation and focus peaking analysis algorithms.
* `SlowMotionAndTimeLapseTest.kt`: Validates frame rate and interval speed configurations.
* `ThemeApiCompatibilityRobolectricTest.kt`: Ensures API 29–30 static palette fallback safety without invoking API 31+ Monet methods.

### Test Commands
```bash
# Run all unit and Robolectric tests
./gradlew testDebugUnitTest

# Run Android Lint static analysis
./gradlew lintDebug
```

### Continuous Integration
The repository is configured with GitHub Actions (`.github/workflows/android-ci.yml`) triggering on pushes and pull requests to `main` and `develop`. It validates Gradle wrapper integrity, executes unit tests, runs Android Lint, and archives the debug APK artifact.

---

## 🔒 Permissions & Privacy

* `android.permission.CAMERA`: Required for live viewfinder preview and photo/video capture.
* `android.permission.RECORD_AUDIO`: Required for audio recording in Video mode.
* `android.permission.ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: Optional, used solely for EXIF location tagging when enabled in settings.
* **Privacy:** All photo and video processing occurs entirely on-device. No images, videos, or camera telemetry are transmitted to external servers.

---

## ⚠️ Known Limitations & Physical Device Dependencies

* **Hardware Dependencies:** Specialized sensor controls (such as manual ISO 50, manual focus distance, RAW/DNG export, and 240 FPS high-speed video) require hardware HAL level `FULL` or `LEVEL_3`. On `LIMITED` or `LEGACY` devices, unsupported options gracefully display a fallback notification or hide unavailable manual sliders.
* **Physical Device Validation:** While all UI flows, state logic, algorithms, and rendering passes are verified via Robolectric JVM test suites, physical camera sensor behaviors (such as optical image stabilization hardware or specific OEM camera vendor extensions) depend on physical device testing.

---

## 📄 License

No formal open-source license file is currently present in this repository. All rights are reserved by the project author.
