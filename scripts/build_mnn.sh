#!/usr/bin/env bash
set -euo pipefail

# Find Android NDK
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    SDK_DIR="${ANDROID_HOME:-${HOME}/Android/Sdk}"
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

# Apply MNN patches (crash-free NNAPI, layout-aware QNN transpose)
if [ -f "$SCRIPT_DIR/mnn_patches.patch" ]; then
    if git -C "$MNN_REPO_DIR" apply --check "$SCRIPT_DIR/mnn_patches.patch" 2>/dev/null; then
        echo "Applying patches to MNN..."
        git -C "$MNN_REPO_DIR" apply "$SCRIPT_DIR/mnn_patches.patch"
    else
        echo "MNN patches already applied."
    fi
fi

# Fetch QNN SDK dependencies for Qualcomm Hexagon HTP acceleration
if [ ! -d "${MNN_REPO_DIR}/source/backend/qnn/3rdParty/lib/arm64-v8a" ]; then
    echo "Preparing QNN SDK dependencies for MNN..."
    if [ -f "${MNN_REPO_DIR}/prepare_qnn_deps.sh" ]; then
        bash "${MNN_REPO_DIR}/prepare_qnn_deps.sh"
    fi
fi

build_abi() {
    local RAW_ABI="$1"
    local ABI
    case "$RAW_ABI" in
        arm32|armv7|armeabi) ABI="armeabi-v7a" ;;
        arm64|aarch64) ABI="arm64-v8a" ;;
        x86-64|x86_64|amd64|x64) ABI="x86_64" ;;
        x86|i686) ABI="x86" ;;
        *) ABI="$RAW_ABI" ;;
    esac

    local BUILD_DIR="${WORK_DIR}/build_${ABI}"
    echo "=== Building MNN for ${ABI} ==="
    mkdir -p "$BUILD_DIR"
    pushd "$BUILD_DIR" >/dev/null

    local QNN_FLAG="OFF"
    local ARM82_FLAG="OFF"
    local AVX2_FLAG="OFF"

    if [ "$ABI" = "arm64-v8a" ]; then
        QNN_FLAG="ON"
        ARM82_FLAG="ON"
    elif [ "$ABI" = "x86_64" ] || [ "$ABI" = "x86" ]; then
        AVX2_FLAG="ON"
    fi

    cmake "$MNN_REPO_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-27 \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DMNN_BUILD_SHARED_LIBS=ON \
        -DMNN_SEP_BUILD=OFF \
        -DMNN_QNN="$QNN_FLAG" \
        -DQNN_SDK_ROOT="${MNN_REPO_DIR}/source/backend/qnn/3rdParty" \
        -DMNN_NNAPI=ON \
        -DMNN_OPENCL=ON \
        -DMNN_VULKAN=ON \
        -DMNN_ARM82="$ARM82_FLAG" \
        -DMNN_AVX2="$AVX2_FLAG" \
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

# Target ABIs: arm64-v8a (primary 64-bit ARM), armeabi-v7a (32-bit ARM), and x86_64 (64-bit x86)
# Pass specific ABI(s) as script arguments (e.g. ./build_mnn.sh arm32 x86-64) or build all by default
DEFAULT_ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
if [ $# -gt 0 ]; then
    for target in "$@"; do
        build_abi "$target"
    done
else
    for abi in "${DEFAULT_ABIS[@]}"; do
        build_abi "$abi"
    done
fi

# Update include headers
echo "Updating MNN headers..."
mkdir -p "${ROOT_DIR}/app/src/main/cpp/mnn/include"
cp -r "$MNN_REPO_DIR/include/"* "${ROOT_DIR}/app/src/main/cpp/mnn/include/"

echo "MNN build and installation completed successfully!"

