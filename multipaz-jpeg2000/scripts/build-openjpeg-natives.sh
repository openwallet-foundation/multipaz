#!/usr/bin/env bash
set -euo pipefail

# Builds libmultipaz_openjpeg_jni for Android (arm64-v8a, armeabi-v7a, x86_64)
# using the Android NDK, and for Desktop JVM (linux-x86_64, linux-aarch64,
# macos-arm64, macos-x86_64) using Zig's hermetic cross-compiling C toolchain.
#
# All downloaded archives are verified against pinned SHA-256 checksums.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${MODULE_DIR}/native"

OPENJPEG_VERSION="2.5.4"
OPENJPEG_URL="https://github.com/uclouvain/openjpeg/archive/refs/tags/v${OPENJPEG_VERSION}.tar.gz"
OPENJPEG_SHA256="a695fbe19c0165f295a8531b1e4e855cd94d0875d2f88ec4b61080677e27188a"

ZIG_VERSION="0.14.0"

sha256_check() {
    local expected="$1"
    local file="$2"
    if command -v sha256sum >/dev/null 2>&1; then
        echo "${expected}  ${file}" | sha256sum -c -
    else
        echo "${expected}  ${file}" | shasum -a 256 -c -
    fi
}

# Locate Android SDK / NDK
ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${ANDROID_SDK_ROOT}" ]]; then
    if [[ -d "${HOME}/Android/Sdk" ]]; then
        ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
    elif [[ -d "${HOME}/Library/Android/sdk" ]]; then
        ANDROID_SDK_ROOT="${HOME}/Library/Android/sdk"
    else
        echo "Error: ANDROID_HOME is not set and Android SDK was not found." >&2
        exit 1
    fi
fi

if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    NDK_DIR="${ANDROID_NDK_HOME}"
else
    NDK_DIR="$(find "${ANDROID_SDK_ROOT}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi

if [[ -z "${NDK_DIR}" || ! -d "${NDK_DIR}" ]]; then
    echo "Error: Android NDK not found under ${ANDROID_SDK_ROOT}/ndk" >&2
    exit 1
fi
echo "Using Android NDK: ${NDK_DIR}"

NDK_STRIP="$(find "${NDK_DIR}/toolchains/llvm/prebuilt" -name "llvm-strip" | head -n 1)"
NDK_SYSROOT_INCLUDE="$(find "${NDK_DIR}/toolchains/llvm/prebuilt" -path "*/sysroot/usr/include" -type d | head -n 1)"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

echo "Downloading OpenJPEG v${OPENJPEG_VERSION}..."
curl -fsSL "${OPENJPEG_URL}" -o "${TMP_DIR}/openjpeg.tar.gz"
sha256_check "${OPENJPEG_SHA256}" "${TMP_DIR}/openjpeg.tar.gz"
tar -xzf "${TMP_DIR}/openjpeg.tar.gz" -C "${TMP_DIR}"
OPENJPEG_SRC_DIR="${TMP_DIR}/openjpeg-${OPENJPEG_VERSION}"

# ---------------------------------------------------------------------------
# 1. Build Android JNI libraries via CMake + Android NDK toolchain
# ---------------------------------------------------------------------------
ANDROID_ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
for ABI in "${ANDROID_ABIS[@]}"; do
    echo "Building Android (${ABI})..."
    BUILD_DIR="${TMP_DIR}/build-android-${ABI}"
    cmake -S "${NATIVE_DIR}" -B "${BUILD_DIR}" \
        -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPENJPEG_SRC_DIR="${OPENJPEG_SRC_DIR}"
    cmake --build "${BUILD_DIR}" --config Release --parallel

    OUT_DIR="${MODULE_DIR}/src/androidMain/jniLibs/${ABI}"
    mkdir -p "${OUT_DIR}"
    cp "${BUILD_DIR}/libmultipaz_openjpeg_jni.so" "${OUT_DIR}/libmultipaz_openjpeg_jni.so"
    "${NDK_STRIP}" --strip-unneeded "${OUT_DIR}/libmultipaz_openjpeg_jni.so"
done

# ---------------------------------------------------------------------------
# 2. Download Zig for hermetic desktop cross-compilation (Linux + macOS)
# ---------------------------------------------------------------------------
HOST_OS="$(uname -s)"
HOST_ARCH="$(uname -m)"
if [[ "${HOST_OS}" == "Linux" && "${HOST_ARCH}" == "x86_64" ]]; then
    ZIG_TARBALL="zig-linux-x86_64-${ZIG_VERSION}.tar.xz"
    ZIG_SHA256="473ec26806133cf4d1918caf1a410f8403a13d979726a9045b421b685031a982"
elif [[ "${HOST_OS}" == "Linux" && ( "${HOST_ARCH}" == "aarch64" || "${HOST_ARCH}" == "arm64" ) ]]; then
    ZIG_TARBALL="zig-linux-aarch64-${ZIG_VERSION}.tar.xz"
    ZIG_SHA256="ab64e3ea277f6fc5f3d723dcd95d9ce1ab282c8ed0f431b4de880d30df891e4f"
elif [[ "${HOST_OS}" == "Darwin" && "${HOST_ARCH}" == "arm64" ]]; then
    ZIG_TARBALL="zig-macos-aarch64-${ZIG_VERSION}.tar.xz"
    ZIG_SHA256="b71e4b7c4b4be9953657877f7f9e6f7ee89114c716da7c070f4a238220e95d7e"
elif [[ "${HOST_OS}" == "Darwin" && "${HOST_ARCH}" == "x86_64" ]]; then
    ZIG_TARBALL="zig-macos-x86_64-${ZIG_VERSION}.tar.xz"
    ZIG_SHA256="685816166f21f0b8d6fc7aa6a36e91396dcd82ca6556dfbe3e329deffc01fec3"
else
    echo "Unsupported host platform: ${HOST_OS} ${HOST_ARCH}" >&2
    exit 1
fi

echo "Downloading Zig ${ZIG_VERSION} (${ZIG_TARBALL})..."
curl -fsSL "https://ziglang.org/download/${ZIG_VERSION}/${ZIG_TARBALL}" -o "${TMP_DIR}/zig.tar.xz"
sha256_check "${ZIG_SHA256}" "${TMP_DIR}/zig.tar.xz"
mkdir -p "${TMP_DIR}/zig"
tar -xJf "${TMP_DIR}/zig.tar.xz" -C "${TMP_DIR}/zig" --strip-components=1
ZIG_BIN="${TMP_DIR}/zig/zig"

# Prepare generated OpenJPEG config headers & JNI header for desktop builds
OPJ_GEN_DIR="${TMP_DIR}/opj_gen"
mkdir -p "${OPJ_GEN_DIR}"
cat > "${OPJ_GEN_DIR}/opj_config.h" << 'EOF'
#ifndef OPJ_CONFIG_H_INCLUDED
#define OPJ_CONFIG_H_INCLUDED
#define OPJ_VERSION_MAJOR 2
#define OPJ_VERSION_MINOR 5
#define OPJ_VERSION_BUILD 4
#endif
EOF

cat > "${OPJ_GEN_DIR}/opj_config_private.h" << 'EOF'
#ifndef OPJ_CONFIG_PRIVATE_H_INCLUDED
#define OPJ_CONFIG_PRIVATE_H_INCLUDED
#define OPJ_PACKAGE_VERSION "2.5.4"
#define _LARGEFILE_SOURCE 1
#define _FILE_OFFSET_BITS 64
#define OPJ_HAVE_FSEEKO 1
#define OPJ_HAVE_POSIX_MEMALIGN 1
#if !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200112L
#endif
#if defined(__BIG_ENDIAN__) || (defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__))
#define OPJ_BIG_ENDIAN 1
#endif
#endif
EOF

# Copy standalone jni.h from NDK sysroot into OPJ_GEN_DIR so cross-compilation
# does not mix host sysroot headers with target sysroot headers.
cp "${NDK_SYSROOT_INCLUDE}/jni.h" "${OPJ_GEN_DIR}/jni.h"

OPJ_LIB_DIR="${OPENJPEG_SRC_DIR}/src/lib/openjp2"
OPJ_SRCS=(
    "${NATIVE_DIR}/openjpeg_jni.c"
    "${OPJ_LIB_DIR}/thread.c"
    "${OPJ_LIB_DIR}/bio.c"
    "${OPJ_LIB_DIR}/cio.c"
    "${OPJ_LIB_DIR}/dwt.c"
    "${OPJ_LIB_DIR}/event.c"
    "${OPJ_LIB_DIR}/ht_dec.c"
    "${OPJ_LIB_DIR}/image.c"
    "${OPJ_LIB_DIR}/invert.c"
    "${OPJ_LIB_DIR}/j2k.c"
    "${OPJ_LIB_DIR}/jp2.c"
    "${OPJ_LIB_DIR}/mct.c"
    "${OPJ_LIB_DIR}/mqc.c"
    "${OPJ_LIB_DIR}/openjpeg.c"
    "${OPJ_LIB_DIR}/opj_clock.c"
    "${OPJ_LIB_DIR}/pi.c"
    "${OPJ_LIB_DIR}/t1.c"
    "${OPJ_LIB_DIR}/t2.c"
    "${OPJ_LIB_DIR}/tcd.c"
    "${OPJ_LIB_DIR}/tgt.c"
    "${OPJ_LIB_DIR}/function_list.c"
    "${OPJ_LIB_DIR}/opj_malloc.c"
    "${OPJ_LIB_DIR}/sparse_array.c"
)

build_desktop_target() {
    local zig_target="$1"
    local platform_dir="$2"
    local lib_name="$3"

    echo "Building Desktop (${platform_dir} -> ${zig_target})..."
    local out_dir="${MODULE_DIR}/src/jvmMain/resources/nativeLibs/${platform_dir}"
    mkdir -p "${out_dir}"

    "${ZIG_BIN}" cc -target "${zig_target}" \
        -shared -fPIC -O3 -std=c11 \
        -fvisibility=hidden -ffunction-sections -fdata-sections \
        -Wl,-s \
        -DOPJ_STATIC -DMUTEX_pthread \
        -I"${OPJ_GEN_DIR}" -I"${OPJ_LIB_DIR}" \
        "${OPJ_SRCS[@]}" \
        -lm -lpthread \
        -o "${out_dir}/${lib_name}"
}

build_desktop_target "x86_64-linux-gnu.2.17" "linux-x86_64" "libmultipaz_openjpeg_jni.so"
build_desktop_target "aarch64-linux-gnu.2.17" "linux-aarch64" "libmultipaz_openjpeg_jni.so"
build_desktop_target "aarch64-macos" "macos-arm64" "libmultipaz_openjpeg_jni.dylib"
build_desktop_target "x86_64-macos" "macos-x86_64" "libmultipaz_openjpeg_jni.dylib"

echo "Built all native libraries successfully:"
find "${MODULE_DIR}/src/androidMain/jniLibs" "${MODULE_DIR}/src/jvmMain/resources/nativeLibs" -type f -exec ls -lh {} +
