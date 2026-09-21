#!/usr/bin/env bash
set -euo pipefail

# This script downloads Qualcomm's official pre-exported MobileFaceNet TFLite model,
# verifies cryptographic checksums (SHA-256), extracts the model, and places it into
# the sample applications (testapp and SwiftTestApp).
#
# Provenance & Licensing:
# - Architecture: MobileFaceNet (Chen et al., 2018, "MobileFaceNets: Efficient CNNs for
#   Accurate Real-Time Face Verification on Mobile Devices")
# - Provider: Qualcomm Technologies, Inc. (Qualcomm AI Hub)
# - Model Card: https://huggingface.co/qualcomm/MobileFaceNet
# - License: Apache License 2.0 (Apache-2.0)
# - Release: v0.62.2 (float precision)
# - Input shape: 2 inputs ("img1", "img2"), each [1, 3, 112, 112] float32 in range [0.0, 1.0]
# - Output shape: 1 output ("embeddings"), shape [2, 128] float32

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TESTAPP_FILES_DIR="${ROOT_DIR}/samples/testapp/src/commonMain/composeResources/files"
SWIFT_TESTAPP_DIR="${ROOT_DIR}/samples/SwiftTestApp/SwiftTestApp"

VERSION="v0.62.2"
URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/${VERSION}/mobile_facenet-tflite-float.zip"
EXPECTED_ZIP_SHA256="2e5581f733225bd5329d99f717969725189046b3a8d5117ae5be0d011070a3da"
EXPECTED_MODEL_SHA256="2254b01065c1f69ff501e9501042441b85323710e3d5a7b6be04a5084b647651"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

DOWNLOAD_ZIP="${TMP_DIR}/mobile_facenet.zip"
echo "Downloading Qualcomm MobileFaceNet (${VERSION}) from ${URL}..."
curl -fsSL "${URL}" -o "${DOWNLOAD_ZIP}"

echo "Verifying archive SHA-256 checksum..."
ACTUAL_ZIP_SHA256="$(shasum -a 256 "${DOWNLOAD_ZIP}" | awk '{print $1}')"
if [[ "${ACTUAL_ZIP_SHA256}" != "${EXPECTED_ZIP_SHA256}" ]]; then
    echo "Archive checksum verification failed!" >&2
    echo "Expected: ${EXPECTED_ZIP_SHA256}" >&2
    echo "Actual:   ${ACTUAL_ZIP_SHA256}" >&2
    exit 1
fi
echo "Archive checksum OK."

EXTRACT_DIR="${TMP_DIR}/extracted"
mkdir -p "${EXTRACT_DIR}"
unzip -q "${DOWNLOAD_ZIP}" -d "${EXTRACT_DIR}"

MODEL_FILE="$(find "${EXTRACT_DIR}" -name "mobile_facenet.tflite" | head -n 1)"
if [[ -z "${MODEL_FILE}" || ! -f "${MODEL_FILE}" ]]; then
    echo "Error: mobile_facenet.tflite not found in extracted archive!" >&2
    exit 1
fi

echo "Verifying model file SHA-256 checksum..."
ACTUAL_MODEL_SHA256="$(shasum -a 256 "${MODEL_FILE}" | awk '{print $1}')"
if [[ "${ACTUAL_MODEL_SHA256}" != "${EXPECTED_MODEL_SHA256}" ]]; then
    echo "Model checksum verification failed!" >&2
    echo "Expected: ${EXPECTED_MODEL_SHA256}" >&2
    echo "Actual:   ${ACTUAL_MODEL_SHA256}" >&2
    exit 1
fi
echo "Model checksum OK."

mkdir -p "${TESTAPP_FILES_DIR}"
cp "${MODEL_FILE}" "${TESTAPP_FILES_DIR}/mobile_facenet.tflite"
echo "Installed model to ${TESTAPP_FILES_DIR}/mobile_facenet.tflite ($(du -h "${TESTAPP_FILES_DIR}/mobile_facenet.tflite" | cut -f1))"

# Update symlink in SwiftTestApp
if [[ -d "${SWIFT_TESTAPP_DIR}" ]]; then
    ln -sf "../../testapp/src/commonMain/composeResources/files/mobile_facenet.tflite" \
           "${SWIFT_TESTAPP_DIR}/mobile_facenet.tflite"
    echo "Updated symlink at ${SWIFT_TESTAPP_DIR}/mobile_facenet.tflite"
fi

echo "Done."
