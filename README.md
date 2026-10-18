# Pixel Camera 📷

A universal Android camera application inspired by the Google Pixel Camera experience, built using modern Android development practices: **Kotlin**, **Jetpack Compose**, **Material 3**, **CameraX**, and **Camera2**.

[![Android CI](https://github.com/sam/pixelcamera/actions/workflows/android-ci.yml/badge.svg)](https://github.com/sam/pixelcamera/actions/workflows/android-ci.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-brightgreen.svg)](https://developer.android.com)
[![Target](https://img.shields.io/badge/Target%20SDK-36-blue.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-purple.svg)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-17-orange.svg)](https://adoptium.net)

---

## 📌 Project Status — Phase 1: UI and Theme System

This repository contains the completed **Phase 1: UI and Theme System**:
- **Adaptive Material 3 Theme**: Coordinated light and dark palettes with persistent user preferences (`System Default`, `Light`, `Dark`). Android 12+ Monet dynamic wallpaper colors with static palette fallback for Android 10–11 (API 29–30).
- **Polished Pixel Camera Viewfinder**: Large adaptive preview placeholder responding in real-time to selected Aspect Ratios (`4:3`, `16:9`, `Full`) and Framing Grid Overlays (`Off`, `3 × 3`, `Golden ratio`).
- **Centered Camera Mode Carousel**: Preserved smooth horizontal scrolling with mathematically exact viewport centering across all 9 planned modes (`Night Sight`, `Portrait`, `Camera`, `Video`, `Cinematic`, `Slow Motion`, `Time Lapse`, `Panorama`, `Modes`).
- **Quick Controls Bottom Sheet**: Pixel-inspired quick adjustment drawer for Flash (`Auto`, `On`, `Off`, `Torch`), Timer (`Off`, `3s`, `10s`), Ratio, and Grid with direct settings link.
- **Dedicated Settings Screen**: Categorized settings (Appearance, Photo, Video, Gestures, About) with `SharedPreferences` persistence.
- **Reusable UI Components**: `CircularIconButton`, `CameraShutterButton`, `SettingsRow`, `SettingsSwitchRow`, and `SingleChoiceDialog`.
- **Zero Permissions**: No camera, microphone, or storage permissions are requested in Phase 1; capture engine is scheduled for subsequent phases.

---

## 🛠 Tech Stack & Specifications

| Component | Specification |
|---|---|
| **App Name** | Pixel Camera |
| **Application ID** | `com.sam.pixelcamera` |
| **Namespace** | `com.example` |
| **Min SDK** | Android 10 (API 29) |
| **Target / Compile SDK** | Android 15 / 16 (API 36) |
| **Java / Toolchain** | JDK 17 |
| **UI Toolkit** | Jetpack Compose (BOM 2024.09.00) with Material 3 |
| **Architecture** | MVVM / Clean Modular Foundation |
| **Unit Testing** | JUnit 4, AndroidX Test, Robolectric |
| **CI / CD** | GitHub Actions (`android-ci.yml`) |

---

## 🎨 Theme Behavior

Pixel Camera implements an adaptive theme hierarchy in `app/src/main/java/com/example/ui/theme/Theme.kt`:

1. **Android 10 and Android 11 (API 29–30):**
   - Uses a predefined static Pixel obsidian dark and clean light color palette.
   - Strictly avoids invoking Android 12 Monet dynamic APIs (`dynamicLightColorScheme` / `dynamicDarkColorScheme`), ensuring complete backward compatibility without runtime crashes.
2. **Android 12 and later (API 31+):**
   - Automatically queries system Material You Monet dynamic color schemes.
   - Falls back to the static palette if dynamic color extraction is unavailable on third-party ROMs.
3. **System Dark/Light Preference:**
   - Adheres to the user's system dark mode preference out-of-the-box.

---

## 📂 Project Architecture

```text
com.example/
├── MainActivity.kt                      # Host Activity with edge-to-edge system bars
├── core/
│   ├── camera/
│   │   └── CameraContract.kt            # Lens facing, aspect ratios, engine state
│   ├── model/
│   │   ├── CameraMode.kt                # Photo, Video, Night Sight, Portrait, Modes
│   │   └── SystemDeviceInfo.kt          # Telemetry and Monet capability checks
│   ├── processing/
│   │   └── ImageProcessingContract.kt   # Multi-frame HDR & Night Sight pipelines
│   └── storage/
│       └── MediaStorageContract.kt      # Android 10+ MediaStore & Scoped Storage
├── feature/
│   └── preview/
│       ├── CameraFoundationScreen.kt    # Root Composable screen
│       └── components/
│           ├── CameraModeSelector.kt    # Mode switching carousel
│           ├── CameraPreviewPlaceholder.kt # Viewfinder reticles & grid overlay
│           ├── CameraShutterBar.kt      # Pixel double-ring shutter & lens switch
│           ├── CameraTopBar.kt          # Title and Phase 0 status badge
│           └── SystemStatusBar.kt       # Android version & theme telemetry
└── ui/
    └── theme/
        ├── Color.kt                     # Static palettes & camera accents
        ├── Theme.kt                     # Adaptive dynamic/static theme engine
        └── Type.kt                      # Material 3 typography
```

---

## 🧪 Testing & Verification

Run local unit and Robolectric tests:

```bash
# Using Gradle Wrapper
./gradlew testDebugUnitTest

# Or using system Gradle
gradle :app:testDebugUnitTest
```

Run Android Lint:

```bash
./gradlew lintDebug
```

Assemble Debug APK:

```bash
./gradlew assembleDebug
```

Output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🚀 How to Import into Android Studio

1. **Prerequisites:**
   - Android Studio Ladybug (2024.2.1+) or newer.
   - JDK 17 configured in Android Studio (`Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`).
   - Android SDK Platform 36 and Android SDK Platform 29 installed via SDK Manager.
2. **Import Steps:**
   - Launch Android Studio.
   - Click **Open** (or `File > Open...`).
   - Select the root directory containing `settings.gradle.kts` and `build.gradle.kts`.
   - Allow Gradle to sync dependencies automatically.
3. **Run App:**
   - Select an emulator or connected device running Android 10 (API 29) or higher.
   - Click the green **Run** button (or `Shift + F10`).

---

## 📤 How to Push to GitHub

To push this project to your GitHub repository:

```bash
# 1. Initialize git if not already initialized
git init

# 2. Stage all project files
git add .

# 3. Commit Phase 0 Foundation
git commit -m "feat: complete Phase 0 project foundation and CI infrastructure"

# 4. Rename current branch to main
git branch -M main

# 5. Add your remote repository URL (replace with your repo URL)
git remote add origin https://github.com/<your-username>/pixelcamera.git

# 6. Push to GitHub
git push -u origin main
```

Once pushed, GitHub Actions will automatically trigger `.github/workflows/android-ci.yml`, run tests, execute Android lint, and archive the debug APK artifact!
