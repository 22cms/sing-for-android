#pragma once

#include <vector>
#include <cmath>
#include <complex>
#include <cstdint>
#include "kiss_fft.h"

namespace sing {

constexpr int N_FFT = 6144;
constexpr int DEFAULT_N_BINS = 2048;      // Default model frequency bins (dim_f = 2048)
constexpr int N_BINS = 2048;              // Legacy alias for 9703 models
constexpr int MAX_N_BINS = 3072;          // Max frequency bins (dim_f = 3072 for Voc_FT)
constexpr int N_BINS_TOTAL = N_FFT / 2 + 1; // 3073 bins
constexpr int HOP_LENGTH = 1024;  // Overlap hop
constexpr int N_FRAMES = 256;
constexpr int TOTAL_SAMPLES = (N_FRAMES - 1) * HOP_LENGTH; // 255 * 1024 = 261,120 samples (~5.92s @ 44.1kHz)
constexpr int PAD_SAMPLES = N_FFT / 2; // 3072 samples (PyTorch center=True reflect pad)
constexpr int PADDED_SAMPLES = TOTAL_SAMPLES + 2 * PAD_SAMPLES; // 267,264 samples
constexpr int TENSOR_CHANNELS = 4; // Re_L, Im_L, Re_R, Im_R
constexpr int TENSOR_SIZE = TENSOR_CHANNELS * N_BINS * N_FRAMES; // 2,097,152 floats (dim_f = 2048)
constexpr int MAX_TENSOR_SIZE = TENSOR_CHANNELS * MAX_N_BINS * N_FRAMES; // 3,145,728 floats (dim_f = 3072)
constexpr float COMPENSATION = 1.035f; // UVR MDX-Net 1 compensation factor

class StftEngine {
public:
    StftEngine();
    ~StftEngine();

    // Convert interleaved stereo float PCM [TOTAL_SAMPLES * 2] into
    // tensor [1, 4, num_bins, 256] in flat layout: ch * (num_bins * N_FRAMES) + bin * N_FRAMES + frame
    // Uses PyTorch-compatible center=True reflection padding. Zeroes out bins 0, 1, 2.
    void forward(const float* stereo_pcm, float* out_tensor, int num_bins = DEFAULT_N_BINS);

    // Convert estimated vocal tensor [1, 4, num_bins, 256] back into
    // interleaved stereo float PCM [TOTAL_SAMPLES * 2] via iSTFT and overlap-add.
    // Zero-pads bins num_bins..3072, applies Hermitian symmetry, exact window sum division and compensation.
    void inverse(const float* in_tensor, float* out_stereo_pcm, int num_bins = DEFAULT_N_BINS);

    // Apply vocal attenuation blending directly in PCM domain:
    // output = original_pcm - (1.0f - level) * vocal_pcm
    static void blend(const float* original_pcm, const float* vocal_pcm,
                      float* out_pcm, int sample_count, float level);

private:
    std::vector<float> window_;
    std::vector<float> ola_inv_norm_;

    std::vector<float> padded_input_;
    std::vector<float> padded_output_;

    kiss_fft_cfg cfg_forward_{nullptr};
    kiss_fft_cfg cfg_inverse_{nullptr};

    std::vector<kiss_fft_cpx> fft_in_;
    std::vector<kiss_fft_cpx> fft_out_;

    void init_tables();
};

} // namespace sing
