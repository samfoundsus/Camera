# CI/CD Infrastructure & Automation Guide

This document describes the continuous integration and delivery architecture for the **Pixel Camera** project.

---

## 1. Overview

The continuous integration pipeline is powered by **GitHub Actions** (`.github/workflows/android-ci.yml`) to ensure every contribution builds cleanly, passes all unit tests, satisfies lint guidelines, and produces verified debug binaries.

## 2. Workflow Specifications

### Triggers
- **Push events**: Branches `main` and `develop`.
- **Pull Request events**: Pull requests targeting `main`.
- **Manual Trigger**: `workflow_dispatch` allows on-demand manual execution from the GitHub Actions console.

### Execution Environment
- **Runner**: `ubuntu-latest`
- **Java Development Kit**: JDK 17 (Temurin distribution)
- **Gradle**: Uses the committed Gradle Wrapper (`./gradlew`), guaranteeing reproducible builds across developer machines and cloud runners.
- **Android SDK**: Pre-configured by `android-actions/setup-android` or default GitHub Actions runner SDKs with API level 36 platforms and build tools.

---

## 3. Pipeline Stages

```text
┌───────────────────────┐
│     Code Checkout     │
└──────────┬────────────┘
           │
┌──────────▼────────────┐
│      Setup JDK 17     │
└──────────┬────────────┘
           │
┌──────────▼────────────┐
│ Validate Wrapper      │
└──────────┬────────────┘
           │
┌──────────▼────────────┐
│   Run Unit Tests      │ ───► Always upload Test Reports
└──────────┬────────────┘
           │
┌──────────▼────────────┐
│   Run Android Lint    │ ───► Always upload Lint Reports
└──────────┬────────────┘
           │
┌──────────▼────────────┐
│  Assemble Debug APK   │ ───► Upload app-debug.apk artifact
└───────────────────────┘
```

1. **Gradle Wrapper Validation**: Verifies checksum of the committed wrapper JAR to prevent supply-chain tampering.
2. **Execute Unit Tests**: Runs local JVM and Robolectric tests (`./gradlew testDebugUnitTest`).
3. **Static Analysis & Lint**: Executes Android Lint (`./gradlew lintDebug`).
4. **Compile & Assemble**: Compiles the debug variant (`./gradlew assembleDebug`).
5. **Artifact Retention**:
   - `pixel-camera-debug-apk`: Generated APK uploaded for review and testing.
   - `unit-test-reports`: HTML test reports archived even if tests fail (`if: always()`).
   - `lint-reports`: Lint analysis reports archived.

---

## 4. Security & Zero-Secret Model

- **Minimal Permissions**: The workflow requests only `contents: read` permissions.
- **Zero Secrets for Pull Requests**: Pull request builds do not depend on external secrets, API tokens, or signing keys.
- **Credential Hygiene**: No keystores (`.jks`, `.keystore`) or `.env` files are committed to version control. The debug build utilizes Gradle's default reproducible debug certificate.
