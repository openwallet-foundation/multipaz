#!/usr/bin/env bash
set -euo pipefail

# This script downloads TensorFlowLite C prebuilts for macOS and Linux desktop,
# verifies their checksums, extracts the dynamic libraries, packages them with
# the default MobileFaceNet model into a bundled tarball for offline use by multipaz-facenet on JVM.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACENET_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PREBUILTS_DIR="${FACENET_DIR}/prebuilts"

VERSION="2.17.1"
OUTPUT_TARBALL="${PREBUILTS_DIR}/TensorFlowLiteC-desktop-${VERSION}.tar.gz"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

BUNDLE_DIR="${TMP_DIR}/bundle"
mkdir -p "${BUNDLE_DIR}/darwin-aarch64"
mkdir -p "${BUNDLE_DIR}/darwin-x86-64"
mkdir -p "${BUNDLE_DIR}/linux-x86-64"
mkdir -p "${BUNDLE_DIR}/linux-aarch64"

# 1. macOS arm64
URL_DARWIN_ARM64="https://github.com/tphakala/tflite_c/releases/download/v${VERSION}/tflite_c_v${VERSION}_darwin_arm64.tar.gz"
SHA_DARWIN_ARM64="c183be044a6ad0c6976a6ddd2fafb9d723b03c46c9f06870ab9a55201d71c2d0"
echo "Downloading darwin_arm64 from ${URL_DARWIN_ARM64}..."
curl -fsSL "${URL_DARWIN_ARM64}" -o "${TMP_DIR}/darwin_arm64.tar.gz"
echo "${SHA_DARWIN_ARM64}  ${TMP_DIR}/darwin_arm64.tar.gz" | shasum -a 256 -c -
tar -xzf "${TMP_DIR}/darwin_arm64.tar.gz" -C "${BUNDLE_DIR}/darwin-aarch64"
if [[ -f "${BUNDLE_DIR}/darwin-aarch64/libtensorflowlite_c.${VERSION}.dylib" ]]; then
    mv "${BUNDLE_DIR}/darwin-aarch64/libtensorflowlite_c.${VERSION}.dylib" "${BUNDLE_DIR}/darwin-aarch64/libtensorflowlite_c.dylib"
fi

# 2. macOS x86_64
URL_DARWIN_AMD64="https://github.com/tphakala/tflite_c/releases/download/v2.17.0/tflite_c_v2.17.0_darwin_amd64.tar.gz"
SHA_DARWIN_AMD64="081c17c67b4b2eb220c14344ef1ce5dab50fa623a1e7d42c84a8ea2446e25f1b"
echo "Downloading darwin_amd64 from ${URL_DARWIN_AMD64}..."
curl -fsSL "${URL_DARWIN_AMD64}" -o "${TMP_DIR}/darwin_amd64.tar.gz"
echo "${SHA_DARWIN_AMD64}  ${TMP_DIR}/darwin_amd64.tar.gz" | shasum -a 256 -c -
tar -xzf "${TMP_DIR}/darwin_amd64.tar.gz" -C "${BUNDLE_DIR}/darwin-x86-64"

# 3. Linux x86_64
URL_LINUX_AMD64="https://github.com/tphakala/tflite_c/releases/download/v${VERSION}/tflite_c_v${VERSION}_linux_amd64.tar.gz"
SHA_LINUX_AMD64="8fe21ceb2999128c797f05c8660d5b1d25850b15e1e8c531e8d8e9e050b3fe6f"
echo "Downloading linux_amd64 from ${URL_LINUX_AMD64}..."
curl -fsSL "${URL_LINUX_AMD64}" -o "${TMP_DIR}/linux_amd64.tar.gz"
echo "${SHA_LINUX_AMD64}  ${TMP_DIR}/linux_amd64.tar.gz" | shasum -a 256 -c -
tar -xzf "${TMP_DIR}/linux_amd64.tar.gz" -C "${BUNDLE_DIR}/linux-x86-64"
if [[ -f "${BUNDLE_DIR}/linux-x86-64/libtensorflowlite_c.so.${VERSION}" ]]; then
    mv "${BUNDLE_DIR}/linux-x86-64/libtensorflowlite_c.so.${VERSION}" "${BUNDLE_DIR}/linux-x86-64/libtensorflowlite_c.so"
fi

# 4. Linux arm64
URL_LINUX_ARM64="https://github.com/tphakala/tflite_c/releases/download/v${VERSION}/tflite_c_v${VERSION}_linux_arm64.tar.gz"
SHA_LINUX_ARM64="665fdfc5372be7eedd8439beef5b5572c4b502c8382087ebf481351599163152"
echo "Downloading linux_arm64 from ${URL_LINUX_ARM64}..."
curl -fsSL "${URL_LINUX_ARM64}" -o "${TMP_DIR}/linux_arm64.tar.gz"
echo "${SHA_LINUX_ARM64}  ${TMP_DIR}/linux_arm64.tar.gz" | shasum -a 256 -c -
tar -xzf "${TMP_DIR}/linux_arm64.tar.gz" -C "${BUNDLE_DIR}/linux-aarch64"
if [[ -f "${BUNDLE_DIR}/linux-aarch64/libtensorflowlite_c.so.${VERSION}" ]]; then
    mv "${BUNDLE_DIR}/linux-aarch64/libtensorflowlite_c.so.${VERSION}" "${BUNDLE_DIR}/linux-aarch64/libtensorflowlite_c.so"
fi

# 5. Default MobileFaceNet model
MODEL_SRC="${FACENET_DIR}/../samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite"
if [[ -f "${MODEL_SRC}" ]]; then
    cp "${MODEL_SRC}" "${BUNDLE_DIR}/mobile_facenet.tflite"
fi

mkdir -p "${PREBUILTS_DIR}"
echo "Packaging into ${OUTPUT_TARBALL}..."
tar -czf "${OUTPUT_TARBALL}" -C "${BUNDLE_DIR}" .

echo "Successfully created $(basename "${OUTPUT_TARBALL}") ($(du -h "${OUTPUT_TARBALL}" | cut -f1))"
