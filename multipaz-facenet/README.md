# Multipaz FaceNet Module

The `multipaz-facenet` module provides cross-platform biometric face matching and active liveness verification for the Multipaz SDK. It uses Google MediaPipe BlazeFace for landmark alignment and MobileFaceNet for feature extraction and similarity scoring.

In addition to implementing the client-side `FaceMatcher` interface for wallet applications on mobile devices (Android and iOS), this module can also be used on the server-side (JVM on Linux and macOS) for face detection, aligned face cropping, and portrait-to-portrait matching—for example, in identity issuance and credential-provisioning flows where an applicant's portrait photo must be verified against existing records or government registry portraits.

## Table of Contents

- [Overview & Architecture](#overview--architecture)
- [Supported Platforms](#supported-platforms)
- [Prebuilts Directory (`prebuilts/`)](#prebuilts-directory-prebuilts)
- [Scripts Directory (`scripts/`)](#scripts-directory-scripts)
- [Test Data & Model Provenance](#test-data--model-provenance)
- [Performance & Security Notice](#performance--security-notice)

---

## Overview & Architecture

The module enables two primary biometric workflows:

1. **Face Matching against Reference Portrait**: Compares live camera frames against an issued credential portrait (in PNG, JPEG, or JPEG 2000 format).
2. **Active Liveness Verification**: Guides the user through randomized head-pose challenges (left, right, tilt up, tilt down, straight ahead) followed by high-resolution still portrait capture.

### Key Components

- **`FaceNetFaceMatcher`**: Main entry point implementing `org.multipaz.facematch.FaceMatcher`. Accepts a MobileFaceNet model provider, configuration (`FaceNetModelConfig`), and optional debug logging.
- **`FaceNetSessionBase` / `FaceNetLivenessSessionBase`**: Platform-agnostic state machines managing detection frames, landmark verification, consecutive matching thresholds, and challenge transitions.
- **`BlazeFaceDecoder` & `BlazeFaceModelData`**: MediaPipe BlazeFace short-range face detector. Extracts bounding boxes and 6 2D facial landmarks (eyes, nose, mouth center, ear tragi) for eye-level affine rotation and face cropping.
- **Platform Interpreters**:
  - **Android**: `AndroidFaceNetInterpreter` & `AndroidFaceDetector` using Google LiteRT (`org.tensorflow.lite.Interpreter`).
  - **iOS**: `IosFaceNetInterpreter` & `IosFaceDetector` using TensorFlowLiteC via Kotlin/Native cinterop.
  - **JVM** (Server & Desktop): `JvmFaceNetInterpreter` & `JvmFaceDetector` using dynamic TensorFlow Lite C runtime libraries on Linux and macOS.

### Debug Mode & Cheat Mode

When `FaceNetFaceMatcher` is instantiated with `debug = true` (or registered with the identifier `"facenet_debug"`):
- **On-Screen Biometric Telemetry**: The camera preview overlay displays real-time similarity scores, match threshold, and facial landmark bounding boxes.
- **Cheat Mode**: Tapping or pressing anywhere on the camera video preview area instantly satisfies the biometric match or liveness check (setting similarity to `1.0f` and marking verification as successful). This enables developers to easily test presentation and verification flows with non-matching test credentials or in simulator environments without needing the actual credential subject present.

---

## Supported Platforms

| Platform | Face Detection Engine | Embedding / Matching Engine | Native Acceleration |
| :--- | :--- | :--- | :--- |
| **Android** | MediaPipe BlazeFace (LiteRT) | MobileFaceNet (LiteRT) | CPU / XNNPACK (4 threads) |
| **iOS** (Device & Simulator) | MediaPipe BlazeFace (TFLite C) | MobileFaceNet (TFLite C) | CPU / XNNPACK |
| **JVM** (Linux & macOS: Server/Desktop) | MediaPipe BlazeFace (TFLite C) | MobileFaceNet (TFLite C) | CPU / XNNPACK |
| **Web** (JS / Wasm) | Stubs / Prompts | Stubs / Prompts | Browser Camera Stream |

---

## Prebuilts Directory (`prebuilts/`)

The `prebuilts/` directory houses native binaries and model files required for compilation and offline execution:

### 1. `prebuilts/TensorFlowLiteC-ios-2.17.0.tar.gz`
- **Purpose**: Provides Google's official TensorFlow Lite C library headers and compiled static libraries for iOS compilation.
- **Contents**:
  - C headers in `include/` (`c_api.h`, etc.) for Kotlin/Native `cinterop`.
  - Static library slices (`libTensorFlowLiteC.a`) for `iosArm64` (device), `iosSimulatorArm64` (Apple Silicon simulator), and `iosX64` (Intel simulator).
- **Gradle Integration**: Unpacked automatically during build execution by the `unpackTensorFlowLiteC` Gradle task into `build/TensorFlowLiteC/`.
- **Regeneration**: Built via `scripts/create-tflite-archive.sh`.

### 2. `prebuilts/TensorFlowLiteC-desktop-2.17.1.tar.gz`
- **Purpose**: Provides prebuilt TensorFlow Lite C dynamic libraries for JVM execution on Linux and macOS (covering both server and desktop deployments).
- **Contents**:
  - `darwin-aarch64/libtensorflowlite_c.dylib` (macOS Apple Silicon)
  - `darwin-x86-64/libtensorflowlite_c.dylib` (macOS Intel)
  - `linux-aarch64/libtensorflowlite_c.so` (Linux ARM64 / AArch64)
  - `linux-x86-64/libtensorflowlite_c.so` (Linux x86_64 / AMD64)
- **Gradle Integration**: Unpacked by the `unpackTensorFlowLiteCDesktop` Gradle task into `build/generated/resources/tfliteDesktop/` and bundled into the JVM JAR resources.
- **Regeneration**: Built via `scripts/create-tflite-desktop-archive.sh`.

### 3. `prebuilts/face_detection_short_range.tflite`
- **Purpose**: Google MediaPipe BlazeFace short-range face detection model (Apache License 2.0).
- **Usage**: Used directly as an asset/resource on Android and iOS when bundled. A Base64-encoded fallback is also embedded in `BlazeFaceModelData.kt` to guarantee out-of-the-box operation without requiring external asset packaging.

---

## Scripts Directory (`scripts/`)

The `scripts/` directory contains maintenance scripts for building prebuilt archives and managing assets:

### 1. `scripts/create-tflite-archive.sh`
Downloads the official Google `TensorFlowLiteC` iOS framework release (v2.17.0), verifies its SHA-256 checksum, extracts headers, thins architecture slices (`iosArm64`, `iosSimulatorArm64`, `iosX64`), strips obsolete embedded LLVM bitcode (`__LLVM,__bundle`) via `llvm-objcopy` (reducing archive size from ~33MB to ~7MB), packages them with `ar`, and outputs `prebuilts/TensorFlowLiteC-ios-2.17.0.tar.gz`.

### 2. `scripts/create-tflite-desktop-archive.sh`
Downloads prebuilt TensorFlow Lite C dynamic libraries (v2.17.1) for macOS (`darwin_arm64`, `darwin_amd64`) and Linux (`linux_arm64`, `linux_amd64`) across desktop and server JVM environments, verifies their SHA-256 checksums, and bundles them into `prebuilts/TensorFlowLiteC-desktop-2.17.1.tar.gz`.

---

## Test Data & Model Provenance

Test portraits, reference assets, and the default MobileFaceNet model are maintained in the sibling module [`multipaz-facenet-test-data`](../multipaz-facenet-test-data):

- The model file `mobile_facenet.tflite` is Qualcomm's official pre-exported MobileFaceNet model (Apache-2.0).
- Test faces and model bytes are compiled into `FaceTestData.kt` as Base64 chunks (`FaceTestData.testModel`, `FaceTestData.allPortraits`).
- Unit tests in `multipaz-facenet` and sample applications (`testapp`, `SwiftTestApp`) consume `FaceTestData` directly for zero-configuration, reproducible test execution across all targets.

---

## Performance & Security Notice

This library is provided as an open-source reference implementation and development component. It **does not come with any guarantees of performance, accuracy, or biometric security**, including:

- **Matching Accuracy**: No guarantees are provided regarding False Match Rate (FMR), False Non-Match Rate (FNMR), or biometric error rates under varying environmental conditions (lighting, angles, facial occlusions, aging, or resolution differences).
- **Presentation Attack Detection (PAD)**: No guarantees are provided regarding resistance to spoofing, printed photographs, video replay attacks, 3D masks, or synthetic/deepfake media. While the active liveness flow implements basic head-pose challenges, it has not undergone formal biometric presentation attack detection certification (e.g., ISO/IEC 30107-3 or FIDO).

Deployments with strict assurance requirements should conduct independent biometric evaluations, calibrate match thresholds to their operational threat model, or integrate certified biometric and liveness verification solutions as appropriate for their risk profile.
