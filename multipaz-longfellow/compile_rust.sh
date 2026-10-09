#!/usr/bin/env bash
#
# Copyright 2026 The Multipaz Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="${SCRIPT_DIR}/rust"
TARGET_DIR="${RUST_DIR}/target"

export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$PATH"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-15.0}"

echo "================================================================="
echo "=> Checking prerequisites..."
echo "================================================================="

command -v cargo >/dev/null 2>&1 || { echo >&2 "Error: cargo is required. Please install Rust via https://rustup.rs"; exit 1; }
command -v rustup >/dev/null 2>&1 || { echo >&2 "Error: rustup is required."; exit 1; }
command -v cargo-ndk >/dev/null 2>&1 || { echo >&2 "Error: cargo-ndk is required. Run 'cargo install cargo-ndk'"; exit 1; }
command -v cargo-zigbuild >/dev/null 2>&1 || { echo >&2 "Error: cargo-zigbuild is required. Run 'brew install zig cargo-zigbuild'"; exit 1; }

if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo "Warning: ANDROID_NDK_ROOT ($ANDROID_NDK_ROOT) does not exist."
    echo "Please set ANDROID_NDK_ROOT to your Android NDK path."
    exit 1
fi

echo "==> Ensuring Rust cross-compilation targets..."
rustup target add \
  aarch64-apple-darwin x86_64-apple-darwin \
  aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
  aarch64-linux-android x86_64-linux-android \
  x86_64-unknown-linux-gnu

pushd "${RUST_DIR}" >/dev/null

echo "================================================================="
echo "=> Building macOS targets (arm64 and x86_64)..."
echo "================================================================="
cargo build --release --target aarch64-apple-darwin
cargo build --release --target x86_64-apple-darwin

mkdir -p "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/macos-arm64"
mkdir -p "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/macos-x86_64"

cp "${TARGET_DIR}/aarch64-apple-darwin/release/libzkp.dylib" \
   "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/macos-arm64/libzkp.dylib"

cp "${TARGET_DIR}/x86_64-apple-darwin/release/libzkp.dylib" \
   "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/macos-x86_64/libzkp.dylib"

echo "================================================================="
echo "=> Building Linux target (x86_64)..."
echo "================================================================="
cargo zigbuild --release --target x86_64-unknown-linux-gnu.2.17

mkdir -p "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/linux-x86_64"

cp "${TARGET_DIR}/x86_64-unknown-linux-gnu/release/libzkp.so" \
   "${SCRIPT_DIR}/src/jvmMain/resources/nativeLibs/linux-x86_64/libzkp.so"

echo "================================================================="
echo "=> Building Android targets (arm64-v8a and x86_64)..."
echo "================================================================="
cargo ndk -t arm64-v8a -t x86_64 build --release

mkdir -p "${SCRIPT_DIR}/src/androidMain/jniLibs/arm64-v8a"
mkdir -p "${SCRIPT_DIR}/src/androidMain/jniLibs/x86_64"

cp "${TARGET_DIR}/aarch64-linux-android/release/libzkp.so" \
   "${SCRIPT_DIR}/src/androidMain/jniLibs/arm64-v8a/libzkp.so"
cp "${TARGET_DIR}/x86_64-linux-android/release/libzkp.so" \
   "${SCRIPT_DIR}/src/androidMain/jniLibs/x86_64/libzkp.so"

echo "================================================================="
echo "=> Building iOS targets (device and simulators)..."
echo "================================================================="
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim
cargo build --release --target x86_64-apple-ios

mkdir -p "${SCRIPT_DIR}/src/iosMain/nativeLibs/arm64-iphoneos/lib"
mkdir -p "${SCRIPT_DIR}/src/iosMain/nativeLibs/arm64-iphonesimulator/lib"
mkdir -p "${SCRIPT_DIR}/src/iosMain/nativeLibs/x86_64-iphonesimulator/lib"

cp "${TARGET_DIR}/aarch64-apple-ios/release/libzkp.a" \
   "${SCRIPT_DIR}/src/iosMain/nativeLibs/arm64-iphoneos/lib/libmdoc_static.a"
cp "${TARGET_DIR}/aarch64-apple-ios-sim/release/libzkp.a" \
   "${SCRIPT_DIR}/src/iosMain/nativeLibs/arm64-iphonesimulator/lib/libmdoc_static.a"
cp "${TARGET_DIR}/x86_64-apple-ios/release/libzkp.a" \
   "${SCRIPT_DIR}/src/iosMain/nativeLibs/x86_64-iphonesimulator/lib/libmdoc_static.a"

popd >/dev/null

echo "================================================================="
echo "=> Longfellow binaries successfully built and installed!"
echo "================================================================="
