#pragma once

#include <vector>
#include <cmath>
#include <complex>
#include <cstdint>
#include <mutex>
#include "kiss_fft.h"
#include "kiss_fftr.h"

namespace sing {

constexpr int VR_N_FFT = 768;
constexpr int VR_BINS_HALF = VR_N_FFT / 2 + 1; // 385
constexpr int VR_TOTAL_BINS = 769;

// Polyphase Half-Band filter for 2x decimation/interpolation
struct HalfBand2x {
    static constexpr float kOddTaps[8] = {
        -0.010455f, 0.010954f, -0.016681f, 0.024990f,
        -0.037320f, 0.058382f, -0.104400f, 0.324530f
    };
    static constexpr float kCenterTap = 0.5f;

    static int downsample2x(const float* in, int in_frames, float* out) {
        int out_frames = in_frames / 2;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < out_frames; ++i) {
                int center_idx = 2 * i;
                float sum = kCenterTap * in[center_idx * 2 + ch];
                for (int tap = 0; tap < 8; ++tap) {
                    int dist = 1 + 2 * (7 - tap);
                    int left_idx = center_idx - dist;
                    int right_idx = center_idx + dist;
                    float s_left = (left_idx >= 0) ? in[left_idx * 2 + ch] : in[ch];
                    float s_right = (right_idx < in_frames) ? in[right_idx * 2 + ch] : in[(in_frames - 1) * 2 + ch];
                    sum += kOddTaps[tap] * (s_left + s_right);
                }
                out[i * 2 + ch] = sum;
            }
        }
        return out_frames;
    }

    static int upsample2x(const float* in, int in_frames, float* out) {
        int out_frames = in_frames * 2;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < in_frames; ++i) {
                out[(2 * i) * 2 + ch] = in[i * 2 + ch];
                float sum = 0.0f;
                for (int tap = 0; tap < 8; ++tap) {
                    int left_idx = i - (7 - tap);
                    int right_idx = i + 1 + (7 - tap);
                    float s_left = (left_idx >= 0) ? in[left_idx * 2 + ch] : in[ch];
                    float s_right = (right_idx < in_frames) ? in[right_idx * 2 + ch] : in[(in_frames - 1) * 2 + ch];
                    sum += (2.0f * kOddTaps[tap]) * (s_left + s_right);
                }
                out[(2 * i + 1) * 2 + ch] = sum;
            }
        }
        return out_frames;
    }
};

class VrDspEngine {
public:
    explicit VrDspEngine(int window_frames = 512);
    ~VrDspEngine();

    void setWindowFrames(int frames);
    int getWindowFrames() const { return window_frames_; }
    int getRoiFrames() const { return roi_frames_; }
    int getChunkSamples() const { return (window_frames_ - 1) * 512; }
    int getRoiSamples() const { return roi_frames_ * 512; }
    int getValidRoiSamples() const { return (roi_frames_ - 1) * 512; }

    void reset();

    // Forward 3-band analysis:
    // Takes interleaved stereo PCM at 44.1 kHz [chunkSamples * 2],
    // computes 3-band STFT, fills out_mag_tensor [1, 2, 769, window_frames] (normalized),
    // and caches the complex spectrogram internally for inverse synthesis.
    void forward(const float* stereo_pcm_44k, float* out_mag_tensor);

    // Inverse 3-band synthesis:
    // Takes model mask tensor [1, 2, 769, roi_frames],
    // applies mask to cached complex spectrogram,
    // performs multi-rate iSTFT and band reconstruction,
    // writes out_stereo_pcm_44k [roiSamples * 2].
    // If extract_instrumental is true, applies mask directly.
    // If extract_instrumental is false, applies (1.0f - mask) to isolate vocal stem.
    void inverse(const float* in_mask_tensor, float* out_stereo_pcm_44k, bool extract_instrumental = false);

private:
    int window_frames_{512};
    int roi_frames_{256};
    static constexpr int OFFSET_FRAMES = 128;

    kiss_fftr_cfg fft_cfg_{nullptr};
    kiss_fftr_cfg ifft_cfg_{nullptr};

    std::vector<float> hann_window_;
    std::vector<float> istft_window_norm_b1_;
    std::vector<float> istft_window_norm_b2_;
    std::vector<float> istft_window_norm_b3_;

    // Resampled audio buffers
    std::vector<float> pcm_22k_;
    std::vector<float> pcm_11k_;

    // Cached complex spectrogram: [ch 0..1][bin 0..768][t 0..window_frames-1]
    // Flat layout: ch * (VR_TOTAL_BINS * window_frames) + bin * window_frames + t
    std::vector<kiss_fft_cpx> cached_spec_;

    void init_tables();
};

} // namespace sing
