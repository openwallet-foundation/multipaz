# Multipaz Longfellow Module

The `multipaz-longfellow` module provides Zero-Knowledge Proof (ZKP) capabilities for the Multipaz SDK based on [Longfellow](https://github.com/longfellow-zk/longfellow-zk). It implements mdoc selective disclosure and verifiable predicate proofs (such as `age_over_18`, `age_over_21`, etc.) according to the emerging ISO/IEC 18013-5 Second Edition standard.

## Table of Contents

- [Overview & Architecture](#overview--architecture)
- [Prebuilt Binaries](#prebuilt-binaries)
- [Supported Platforms](#supported-platforms)
- [How to Update and Compile Longfellow](#how-to-update-and-compile-longfellow)
  - [Prerequisites](#prerequisites)
  - [Step-by-Step Update Procedure](#step-by-step-update-procedure)
  - [Build Script Details (`compile_rust.sh`)](#build-script-details-compile_rustsh)
- [Verification & Testing](#verification--testing)

---

## Overview & Architecture

Longfellow uses zero-knowledge succinct non-interactive arguments of knowledge (SNARKs) to prove statements about attributes in an ISO/IEC 18013-5 mdoc credential without revealing the attribute value itself or the issuer signature key directly.

The `multipaz-longfellow` module integrates Longfellow through:

1. **Rust FFI Layer (`rust/`)**: An in-tree Rust crate (`multipaz-longfellow-ffi`) wrapping the upstream `longfellow-zk` Rust implementation and exporting standard C ABI functions matching `mdoc_zk.h`:
   - `run_mdoc_prover`: Generates a zero-knowledge proof for requested attributes from an mdoc.
   - `run_mdoc_verifier`: Verifies a zero-knowledge proof against the issuer certificate and reader transcript.
   - `generate_circuit`: Compiles/generates the arithmetic circuit matching a `ZkSpec`.
   - `find_zk_spec_by_attributes`: Looks up the preconfigured ZK circuit specification for a set of attributes.
   - `free_mdoc_proof` & `free_circuit_bytes`: Cleanly frees native memory allocated by the prover and circuit generator.

2. **JVM & Android (JNA)**: Rather than maintaining JNI C++ glue code, the Java/Android targets use [JNA (Java Native Access)](https://github.com/java-native-access/jna) via `LongfellowCLibrary.kt` and `LongfellowNatives.javaShared.kt`.

3. **Apple iOS (Kotlin/Native cinterop)**: The iOS target links static archives (`libmdoc_static.a`) using Kotlin/Native `cinterop` and `src/iosMain/MdocZk.def`.

---

## Prebuilt Binaries

To ensure fast builds and allow developers to consume the SDK without requiring Rust, Android NDK, or cross-compilation toolchains installed, precompiled binaries for all supported platforms are checked into the repository:

- **Android** (`.so` shared libraries in `src/androidMain/jniLibs/`):
  - `arm64-v8a/libzkp.so` (64-bit ARM)
  - `x86_64/libzkp.so` (64-bit x86 Android emulator)
- **JVM Desktop & Server** (`.dylib` / `.so` in `src/jvmMain/resources/nativeLibs/`):
  - `macos-arm64/libzkp.dylib` (macOS Apple Silicon)
  - `macos-x86_64/libzkp.dylib` (macOS Intel)
  - `linux-x86_64/libzkp.so` (Linux x86_64, built against glibc 2.17 for broad compatibility)
- **iOS** (`.a` static archives in `src/iosMain/nativeLibs/`):
  - `arm64-iphoneos/lib/libmdoc_static.a` (iOS physical devices)
  - `arm64-iphonesimulator/lib/libmdoc_static.a` (iOS Simulator on Apple Silicon)
  - `x86_64-iphonesimulator/lib/libmdoc_static.a` (iOS Simulator on Intel)

---

## Supported Platforms

| Platform | ABI / Target | Integration Mechanism | Binary Format |
| :--- | :--- | :--- | :--- |
| **Android** | `arm64-v8a`, `x86_64` | JNA (`net.java.dev.jna:jna@aar`) | Shared Library (`libzkp.so`) |
| **JVM (macOS)** | Apple Silicon (`aarch64`), Intel (`x86_64`) | JNA (`com.sun.jna:jna`) | Dynamic Library (`libzkp.dylib`) |
| **JVM (Linux)** | `x86_64` (glibc ≥ 2.17) | JNA (`com.sun.jna:jna`) | Shared Library (`libzkp.so`) |
| **iOS** | Device (`arm64`), Simulator (`arm64`, `x86_64`) | Kotlin/Native `cinterop` | Static Archive (`libmdoc_static.a`) |

---

## How to Update and Compile Longfellow

### Prerequisites

To cross-compile the Rust binaries for all platforms from a macOS host, you need:

1. **Rust & Cargo** (installed via [rustup](https://rustup.rs/)):
   ```shell
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```
2. **Android NDK**:
   Set `ANDROID_NDK_ROOT` to your installed NDK path (e.g. `$HOME/Library/Android/sdk/ndk/27.0.12077973`).
3. **`cargo-ndk`** (for Android cross-compilation):
   ```shell
   cargo install cargo-ndk
   ```
4. **`zig` & `cargo-zigbuild`** (for Linux glibc cross-compilation):
   ```shell
   brew install zig cargo-zigbuild
   ```
5. **Xcode Command Line Tools** (for iOS SDKs and Apple Darwin toolchains).

### Step-by-Step Update Procedure

1. **Update the Submodule**:
   The upstream Longfellow Rust implementation is tracked as a Git submodule under `multipaz-longfellow/rust/longfellow-zk`:
   ```shell
   cd multipaz-longfellow/rust/longfellow-zk
   git fetch origin
   git checkout origin/main # or checkout a specific release tag/commit
   cd ../..
   ```

2. **Run the Master Compilation Script**:
   Execute [`compile_rust.sh`](compile_rust.sh):
   ```shell
   ./multipaz-longfellow/compile_rust.sh
   ```
   This script will:
   - Verify all prerequisite tools and install missing Rust targets via `rustup`.
   - Build macOS (`aarch64-apple-darwin`, `x86_64-apple-darwin`).
   - Build Linux (`x86_64-unknown-linux-gnu.2.17`) via `cargo zigbuild`.
   - Build Android (`arm64-v8a`, `x86_64`) via `cargo-ndk`.
   - Build iOS (`aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios`).
   - Copy the compiled binaries directly into the corresponding `src/` directories.

3. **Verify Tests**:
   Run the test suites across host JVM, iOS Simulator, and Android:
   ```shell
   ./gradlew :multipaz-longfellow:jvmTest
   ./gradlew :multipaz-longfellow:iosSimulatorArm64Test
   ./gradlew :samples:testapp:assembleDebug
   ```

4. **Commit the Changes**:
   Stage both the submodule pointer update and the regenerated prebuilt binaries:
   ```shell
   git add multipaz-longfellow/rust/longfellow-zk
   git add multipaz-longfellow/src/
   ```

### Build Script Details (`compile_rust.sh`)

- **AArch64 Crypto Extensions**: Non-Apple AArch64 targets require ARMv8 cryptography extensions (`+aes,+sha2`) enabled for field arithmetic, configured in `rust/.cargo/config.toml`.
- **iOS Minimum Deployment Target**: The script sets `IPHONEOS_DEPLOYMENT_TARGET=15.0` to ensure modern Darwin symbol compatibility (`___chkstk_darwin`).
- **Linux Glibc Target**: `cargo zigbuild` targets `x86_64-unknown-linux-gnu.2.17` so the generated `.so` runs on older enterprise Linux server distributions without requiring a cutting-edge glibc.

---

## Verification & Testing

Unit tests for proof generation and verification are located in `src/commonTest/` and `src/jvmTest/`:
- `SystemTest`: Verifies end-to-end ZK proof generation and verification for standard mdoc claims.
- `NonCanonicalIssuerOrderingTest`: Verifies parser resilience against non-canonical CBOR issuer authentication maps.
