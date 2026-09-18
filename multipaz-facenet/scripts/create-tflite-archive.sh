#!/usr/bin/env bash
set -euo pipefail

# This script downloads the official Google TensorFlowLiteC release for iOS,
# verifies its checksum, extracts the required C headers and architecture slices,
# creates static library archives (.a), and packages them into a bundled tarball
# for offline use by multipaz-facenet.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACENET_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PREBUILTS_DIR="${FACENET_DIR}/prebuilts"

VERSION="2.17.0"
URL="https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
EXPECTED_SHA256="9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
OUTPUT_TARBALL="${PREBUILTS_DIR}/TensorFlowLiteC-ios-${VERSION}.tar.gz"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

DOWNLOAD_FILE="${TMP_DIR}/upstream.tar.gz"
echo "Downloading TensorFlowLiteC ${VERSION} from ${URL}..."
curl -fsSL "${URL}" -o "${DOWNLOAD_FILE}"

echo "Verifying SHA-256 checksum..."
ACTUAL_SHA256="$(shasum -a 256 "${DOWNLOAD_FILE}" | awk '{print $1}')"
if [[ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]]; then
    echo "Checksum verification failed!" >&2
    echo "Expected: ${EXPECTED_SHA256}" >&2
    echo "Actual:   ${ACTUAL_SHA256}" >&2
    exit 1
fi
echo "Checksum OK."

EXTRACT_DIR="${TMP_DIR}/extracted"
mkdir -p "${EXTRACT_DIR}"
tar -xzf "${DOWNLOAD_FILE}" -C "${EXTRACT_DIR}"

XCFRAMEWORK="${EXTRACT_DIR}/TensorFlowLiteC-${VERSION}/Frameworks/TensorFlowLiteC.xcframework"
if [[ ! -d "${XCFRAMEWORK}" ]]; then
    echo "Error: TensorFlowLiteC.xcframework not found in archive!" >&2
    exit 1
fi

BUNDLE_DIR="${TMP_DIR}/bundle"
mkdir -p "${BUNDLE_DIR}/include"
mkdir -p "${BUNDLE_DIR}/libs/iosArm64"
mkdir -p "${BUNDLE_DIR}/libs/iosSimulatorArm64"
mkdir -p "${BUNDLE_DIR}/libs/iosX64"

echo "Extracting headers..."
cp -R "${XCFRAMEWORK}/ios-arm64/TensorFlowLiteC.framework/Headers/"* "${BUNDLE_DIR}/include/"

echo "Processing device slice (iosArm64)..."
ar rcs "${BUNDLE_DIR}/libs/iosArm64/libTensorFlowLiteC.a" \
    "${XCFRAMEWORK}/ios-arm64/TensorFlowLiteC.framework/TensorFlowLiteC"

SIM_BINARY="${XCFRAMEWORK}/ios-arm64_x86_64-simulator/TensorFlowLiteC.framework/TensorFlowLiteC"

echo "Processing simulator arm64 slice (iosSimulatorArm64)..."
lipo -thin arm64 "${SIM_BINARY}" -output "${TMP_DIR}/sim-arm64.o"
ar rcs "${BUNDLE_DIR}/libs/iosSimulatorArm64/libTensorFlowLiteC.a" "${TMP_DIR}/sim-arm64.o"
rm -f "${TMP_DIR}/sim-arm64.o"

echo "Processing simulator x86_64 slice (iosX64)..."
lipo -thin x86_64 "${SIM_BINARY}" -output "${TMP_DIR}/sim-x86_64.o"
ar rcs "${BUNDLE_DIR}/libs/iosX64/libTensorFlowLiteC.a" "${TMP_DIR}/sim-x86_64.o"
rm -f "${TMP_DIR}/sim-x86_64.o"

mkdir -p "${PREBUILTS_DIR}"
echo "Packaging into ${OUTPUT_TARBALL}..."
tar -czf "${OUTPUT_TARBALL}" -C "${BUNDLE_DIR}" .

echo "Successfully created $(basename "${OUTPUT_TARBALL}") ($(du -h "${OUTPUT_TARBALL}" | cut -f1))"
