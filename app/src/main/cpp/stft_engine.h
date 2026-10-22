#pragma once

#include <vector>
#include <cmath>
#include <complex>
#include <cstdint>
#include <queue>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <functional>
#include "kiss_fft.h"
#include "kiss_fftr.h"

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
constexpr int NUM_STFT_WORKERS = 4;

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

    // Replace a stereo vocal spectrum with the mono component to subtract.
    // The side is retained in stereo and a quadrature copy survives mono downmix.
    void centerCutTensor(float* vocal_tensor, int num_bins);

    // Equivalent operation for time-domain model output (one 4096-frame block).
    void centerCutPcm(const float* vocal_pcm, float* center_pcm, int frames);

private:
    std::vector<float> window_;
    std::vector<float> ola_inv_norm_;

    std::vector<float> padded_input_[2];

    kiss_fftr_cfg cfgs_forward_[NUM_STFT_WORKERS]{};
    kiss_fftr_cfg cfgs_inverse_[NUM_STFT_WORKERS]{};
    kiss_fftr_cfg center_forward_{};
    kiss_fftr_cfg center_inverse_{};

    std::vector<float> worker_fin_r_[NUM_STFT_WORKERS];
    std::vector<kiss_fft_cpx> worker_fout_c_[NUM_STFT_WORKERS];
    std::vector<kiss_fft_cpx> worker_fin_c_[NUM_STFT_WORKERS];
    std::vector<float> worker_fout_r_[NUM_STFT_WORKERS];
    std::vector<float> worker_padded_out_[NUM_STFT_WORKERS];

    std::vector<std::thread> workers_;
    std::queue<std::function<void()>> tasks_;
    std::mutex queue_mutex_;
    std::condition_variable cv_task_;
    std::condition_variable cv_done_;
    std::atomic<int> active_tasks_{0};
    bool stop_{false};

    void init_tables();
    void submit(std::function<void()> task);
    void wait_all();
};

} // namespace sing
