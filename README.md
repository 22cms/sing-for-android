# Sing for Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://android.com)
[![Engine](https://img.shields.io/badge/Engine-MNN%20%7C%20ONNX%20QNN-orange.svg)](#hardware-acceleration)

**Sing for Android** brings real-time neural stem separation and Apple Music Sing mode to Android devices via an Xposed module. It hooks into the Apple Music audio pipeline to isolate, attenuate, or boost vocals on the fly with low latency and hardware acceleration.

---

## Features

- **Real-Time On-Device Neural Separation**: Separates vocals from backing tracks in real time during audio playback without needing internet connectivity or cloud processing.
- **Hardware Acceleration**:
  - **Qualcomm Hexagon HTP / NPU**: Powered by Microsoft ONNX Runtime with Qualcomm QNN Execution Provider for near-zero battery drain and sub-realtime latencies.
  - **OpenCL / Vulkan / NNAPI Mobile GPU**: Powered by Alibaba MNN for cross-platform GPU acceleration on Adreno, ARM Mali, and Samsung Xclipse chipsets.
  - **Multi-threaded CPU Fallback**: High-performance portable fallback with configurable thread count and NEON SIMD optimizations.
- **Multiple Model Architectures**:
  - **MDX-Net** (Kim Inst, UVR 1-9703) in FP16 / FP32 precision.
  - **Apple Sing (Vi-NNet)** native reference engine.
- **Advanced Playback & Slider Controls**:
  - **Two-Way Slider**: 0%–50% attenuates vocals for karaoke singing; 50%–100% attenuates the instrumental track for acapella vocal isolation.
  - **Remove Volume Limits**: Allows full muting of vocals or instrumental tracks without triggering Sing deactivation.
  - **Enable for All Songs**: Overrides server-side restrictions and missing time-synced lyrics to enable Sing across the entire music catalog.
  - **Preserve Side Channel**: Keeps backing vocals, stereo reverbs, and spatial effects natural.
  - **Thermal Pacing (Eco Mode)**: Paces neural worker execution when audio buffering is comfortably ahead to reduce thermals.
- **Modern Expressive UI**:
  - Material 3 Expressive and dynamic **Material You** theming adapting to your wallpaper palette.
  - Morphing animated back buttons and scroll focus animations.
  - Built-in **Inference Benchmark** with real-time audio ratio (RTF) and latency breakdown.

---

## Requirements

- **Android 10** or higher (arm64-v8a recommended).
- **Apple Music for Android** (v4.x+).
- **Xposed Environment**:
  - [LSPosed](https://github.com/JingMatrix/LSPosed) (Rooted with Magisk / KernelSU / APatch)
  - Or [LSPatch](https://github.com/LSPosed/LSPatch) (Non-root, embedded module mode).

---

## Installation & Setup

1. **Install Module**:
   - Download the latest APK release from [Releases](https://github.com/22cms/sing-for-android/releases).
   - Install the APK on your device.
2. **Enable in Xposed Manager**:
   - If using LSPosed: Open LSPosed, enable the **Sing for Android** module, and check **Apple Music** in its scope.
   - If using LSPatch: Patch the Apple Music APK with the Sing for Android module embedded (signature bypass mode 2).
3. **Configure Settings**:
   - Launch **Sing for Android** from your launcher (or long-press the Sing icon in Apple Music).
   - Select your preferred neural model and compute target in the app.
   - Run the built-in benchmark to verify that your hardware achieves realtime speeds (>1.0x Real-Time).

---

## Building from Source

### Prerequisites

- Android Studio Koala / Ladybug or command-line SDK tools.
- Android NDK (r25c+ or r27 recommended).
- CMake 3.22.1+.
- Java 17+.

### Build Commands

```bash
# Clone the repository
git clone https://github.com/22cms/sing-for-android.git
cd sing-for-android

# Build Debug APK (arm64-v8a)
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

The resulting APK will be located under `app/build/outputs/apk/`.

---

## Technical Architecture

```
Apple Music Audio Stream (PCM 44.1kHz / Stereo)
                       │
                       ▼
            [ SingAudioProcessor ]
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
[ KissFFT STFT (6144) ]       [ Direct PCM Buffer ]
         │                           │
         ▼                           │
[ Neural Inference Engine ]          │
 ├─ ONNX Runtime (QNN HTP NPU)       │
 ├─ Alibaba MNN (OpenCL/Vulkan GPU)  │
 └─ Multi-threaded CPU Fallback      │
         │                           │
         ▼                           │
[ iSTFT Synthesis & Windowing ]      │
         │                           │
         └─────────────┬─────────────┘
                       ▼
            [ Two-Way Slider Mixer ]
                       │
                       ▼
                 Audio Output
```

---

## Third-Party Projects & Acknowledgements

- **[Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime)** & **[Qualcomm QNN](https://www.qualcomm.com/products/technology/processors/cloud-ai-100/neural-processing-sdk)**: Neural network runtime with Hexagon HTP execution provider.
- **[Alibaba MNN](https://github.com/alibaba/MNN)**: Lightweight cross-platform deep learning inference engine for mobile GPUs and NPUs.
- **[MDX-Net / Ultimate Vocal Remover](https://github.com/Anjok07/ultimatevocalremovergui)**: State-of-the-art vocal isolation architectures by Roman Solovyev et al. (Kuielab).
- **[KissFFT](https://github.com/mborgerding/kissfft)**: Fast Fourier Transform library by Mark Borgerding.
- **[LSPosed](https://github.com/LSPosed/LSPosed) / [LSPatch](https://github.com/LSPosed/LSPatch)**: Modern ART hooking framework and patcher.
- **[Apple SF Symbols](https://developer.apple.com/sf-symbols/)**: Iconography reference.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
Apple Music is a trademark of Apple Inc. This project is independent and not affiliated with or endorsed by Apple Inc.
