# Pixel Camera — Feature Matrix & Technical Roadmap

This document outlines the planned feature matrix, hardware requirements, and realistic Android version and hardware-level constraints for the **Pixel Camera** project.

> **Status Notice:** This project is currently in **Phase 0 (Project Foundation & Infrastructure)**. All features listed below are **Planned** and scheduled across upcoming phases. No camera capture or processing pipelines are active in Phase 0.

---

## 1. Feature Availability Matrix

| Feature | Target Phase | Status | Minimum Android Version | Hardware / HAL Requirements |
|---|---|---|---|---|
| **Photo Mode (Standard Capture)** | Phase 1 | 🟡 Planned | Android 10 (API 29) | Camera2 `INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED` or higher |
| **Video Mode (1080p / 4K)** | Phase 1 | 🟡 Planned | Android 10 (API 29) | MediaCodec H.264/HEVC encoder, 30/60 FPS |
| **Portrait Mode (Depth / Bokeh)** | Phase 2 | 🟡 Planned | Android 10 (API 29) | Dual camera or depth sensor, or ML monocular depth map |
| **Night Sight (Multi-frame Low Light)** | Phase 2 | 🟡 Planned | Android 10 (API 29) | RAW10 or YUV multi-frame burst capture, GPU compute |
| **Astrophotography** | Phase 3 | 🟡 Planned | Android 11 (API 30) | Tripod stability detection, long exposure (>4s frames), star alignment |
| **Panorama & Photo Sphere** | Phase 3 | 🟡 Planned | Android 10 (API 29) | Gyroscope + Accelerometer, real-time image stitching |
| **Time Lapse** | Phase 2 | 🟡 Planned | Android 10 (API 29) | Frame rate decimator, wake lock, thermal mitigation |
| **Slow Motion (120 / 240 FPS)** | Phase 2 | 🟡 Planned | Android 10 (API 29) | High-speed video capability (`REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO`) |
| **Custom HDR (Burst Exposure Merging)**| Phase 3 | 🟡 Planned | Android 10 (API 29) | Zero Shutter Lag (ZSL), AE bracket control, Renderscript/Vulkan compute |
| **Multi-Frame Night Processing** | Phase 3 | 🟡 Planned | Android 10 (API 29) | Camera2 manual exposure + gain override, fast alignment |
| **Super-Resolution (Demosaic Merging)**| Phase 4 | 🟡 Planned | Android 11 (API 30) | Sub-pixel micro-jitter alignment, NPU or Vulkan compute |
| **Top Shot & Motion Photos** | Phase 3 | 🟡 Planned | Android 10 (API 29) | Micro-video container embedding (JPEG + MP4 trailer), burst evaluation |
| **Face Retouching & Real Tone** | Phase 3 | 🟡 Planned | Android 10 (API 29) | ML Kit Face Detection, skin tone luminance preservation |
| **Dual Exposure Controls (Shadows & Highlights)**| Phase 2 | 🟡 Planned | Android 11 (API 30) | Tone curve manipulation or dual synthetic EV bracket pipeline |
| **Flash & Night Flash Modes** | Phase 1 | 🟡 Planned | Android 10 (API 29) | Camera flash unit (`FLASH_INFO_AVAILABLE`) |
| **Timer (3s / 10s countdown)** | Phase 1 | 🟡 Planned | Android 10 (API 29) | Audio/visual countdown indicator |
| **Viewfinder Framing Grids (3x3, Golden Ratio)**| Phase 1 | 🟡 Planned | Android 10 (API 29) | Overlay canvas rendering |
| **Aspect Ratio Selection (4:3 / 16:9)**| Phase 1 | 🟡 Planned | Android 10 (API 29) | Preview and capture crop configuration |
| **Output Quality & RAW Support (DNG)**| Phase 2 | 🟡 Planned | Android 10 (API 29) | `REQUEST_AVAILABLE_CAPABILITIES_RAW` for DNG capture |
| **Location Tagging** | Phase 2 | 🟡 Planned | Android 10 (API 29) | Runtime `ACCESS_FINE_LOCATION` & EXIF GPS tags |
| **Selfie Mirroring** | Phase 1 | 🟡 Planned | Android 10 (API 29) | Front-facing camera mirror transform |
| **Video Stabilization (EIS / OIS)** | Phase 2 | 🟡 Planned | Android 10 (API 29) | `CONTROL_VIDEO_STABILIZATION_MODE_ON` or OIS optical hardware |
| **QR & Barcode Scanning** | Phase 2 | 🟡 Planned | Android 10 (API 29) | CameraX Analysis Analyzer + ML Kit Barcode Scanning |
| **Google Lens Handoff** | Phase 2 | 🟡 Planned | Android 10 (API 29) | Lens Intent resolver / App handoff |
| **Palm Gesture Shutter** | Phase 3 | 🟡 Planned | Android 10 (API 29) | On-device hand landmark detection |
| **Google Photos Integration** | Phase 2 | 🟡 Planned | Android 10 (API 29) | Scoped storage MediaStore integration, direct gallery launcher |

---

## 2. Realistic Device & Hardware Constraints

### Camera2 Hardware Support Levels
- **LEGACY Devices:** Devices operating under the LEGACY hardware level do not support manual exposure bracketing, RAW sensor streams, or per-frame control. Multi-frame HDR and Night Sight will be disabled or fall back to single-frame software enhancement.
- **LIMITED Devices:** Basic burst and CameraX preview support available. High-speed video (120/240 fps) and manual RAW capture may not be supported.
- **FULL / LEVEL_3 Devices:** Full sensor control, RAW capture (`RAW_SENSOR`), per-frame metadata, and manual focus/exposure control available. Required for custom multi-frame HDR+ and Astrophotography.

### Android Version Specifics
- **Android 10 & 11 (API 29–30):**
  - Requires static Material 3 theme fallback (Monet dynamic coloring API `dynamicLightColorScheme` is unavailable).
  - Scoped Storage enforced (`MediaStore` API).
- **Android 12+ (API 31+):**
  - System Material You (Monet) dynamic theming supported and enabled by default.
  - Camera privacy indicator (green dot) handled by system.
- **Android 13+ (API 33+):**
  - Granular media permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`) instead of legacy storage permissions.
- **Android 14+ (API 34+):**
  - Ultra HDR image capture support (`JPEG_R`).

### Processing & Memory Constraints
- Custom multi-frame alignment requires significant RAM during burst buffering (approx. 150MB–300MB for 9 uncompressed YUV frames at 12MP).
- Background coroutine dispatchers with strict memory limits and downscaled pyramid alignment will be implemented to prevent OutOfMemory (OOM) errors on devices with less than 4GB RAM.
