#!/usr/bin/env bash
set -euo pipefail

# Find Android NDK
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    SDK_DIR="${ANDROID_HOME:-/home/fran/Android/Sdk}"
    if [ -d "$SDK_DIR/ndk" ]; then
        # Pick the latest installed NDK version
        NDK_DIR=$(find "$SDK_DIR/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
    fi
fi

if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    echo "Error: Android NDK not found. Please set ANDROID_NDK_HOME." >&2
    exit 1
fi
echo "Using Android NDK: $NDK_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${ROOT_DIR}/build-mnn"
MNN_REPO_DIR="${WORK_DIR}/MNN"

mkdir -p "$WORK_DIR"

# Clone MNN repo if not already present
if [ ! -d "$MNN_REPO_DIR/.git" ]; then
    echo "Cloning Alibaba MNN (latest 3.6.x branch)..."
    git clone --depth 1 https://github.com/alibaba/MNN.git "$MNN_REPO_DIR"
else
    echo "MNN repository already cloned at $MNN_REPO_DIR"
fi

build_abi() {
    local ABI="$1"
    local BUILD_DIR="${WORK_DIR}/build_${ABI}"
    echo "=== Building MNN for ${ABI} ==="
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    pushd "$BUILD_DIR" >/dev/null

    cmake "$MNN_REPO_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-27 \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DMNN_BUILD_SHARED_LIBS=ON \
        -DMNN_SEP_BUILD=OFF \
        -DMNN_QNN=OFF \
        -DMNN_NNAPI=ON \
        -DMNN_OPENCL=ON \
        -DMNN_VULKAN=ON \
        -DMNN_ARM82=ON \
        -DMNN_BUILD_CONVERTER=OFF \
        -DMNN_BUILD_TOOLS=OFF \
        -DMNN_BUILD_TEST=OFF \
        -DMNN_BUILD_BENCHMARK=OFF \
        -DMNN_BUILD_DEMO=OFF \
        -DNATIVE_LIBRARY_OUTPUT="${ROOT_DIR}/app/src/main/jniLibs"

    cmake --build . --target MNN -- -j"$(nproc)"
    popd >/dev/null

    local OUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/${ABI}"
    if [ ! -f "$OUT_DIR/libMNN.so" ]; then
        local FOUND_SO
        FOUND_SO=$(find "$BUILD_DIR" -name "libMNN.so" | head -n 1)
        if [ -n "$FOUND_SO" ]; then
            mkdir -p "$OUT_DIR"
            cp "$FOUND_SO" "$OUT_DIR/libMNN.so"
        fi
    fi

    local STRIP_BIN
    STRIP_BIN=$(find "$NDK_DIR" -name "llvm-strip" 2>/dev/null | head -n 1 || true)
    if [ -n "$STRIP_BIN" ] && [ -x "$STRIP_BIN" ]; then
        "$STRIP_BIN" --strip-unneeded "$OUT_DIR/libMNN.so"
        echo "Stripped debug symbols from $OUT_DIR/libMNN.so"
    fi

    echo "Installed libMNN.so -> $OUT_DIR/libMNN.so ($(ls -lh "$OUT_DIR/libMNN.so" | awk '{print $5}'))"
}

# Build for arm64-v8a (primary) and armeabi-v7a
build_abi "arm64-v8a"
build_abi "armeabi-v7a"

# Update include headers
echo "Updating MNN headers..."
mkdir -p "${ROOT_DIR}/app/src/main/cpp/mnn/include"
cp -r "$MNN_REPO_DIR/include/"* "${ROOT_DIR}/app/src/main/cpp/mnn/include/"

echo "MNN build and installation completed successfully!"

