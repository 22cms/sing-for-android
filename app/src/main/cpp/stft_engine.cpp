#include "stft_engine.h"
#include <cstring>
#include <algorithm>
#include <cmath>

namespace sing {

constexpr float TWO_PI = 6.28318530717958647692f;

StftEngine::StftEngine() {
    cfg_forward_ = kiss_fft_alloc(N_FFT, 0, nullptr, nullptr);
    cfg_inverse_ = kiss_fft_alloc(N_FFT, 1, nullptr, nullptr);
    fft_in_.resize(N_FFT);
    fft_out_.resize(N_FFT);
    init_tables();
}

StftEngine::~StftEngine() {
    if (cfg_forward_) {
        kiss_fft_free(cfg_forward_);
        cfg_forward_ = nullptr;
    }
    if (cfg_inverse_) {
        kiss_fft_free(cfg_inverse_);
        cfg_inverse_ = nullptr;
    }
}

void StftEngine::init_tables() {
    // 1. Periodic Hanning window matching torch.hann_window(6144, periodic=True)
    window_.resize(N_FFT);
    for (int n = 0; n < N_FFT; ++n) {
        window_[n] = 0.5f * (1.0f - std::cos(TWO_PI * n / static_cast<float>(N_FFT)));
    }

    // 2. Precompute exact overlap-add window sum and inverse normalization table
    std::vector<float> ola_window_sum(PADDED_SAMPLES, 0.0f);
    for (int t = 0; t < N_FRAMES; ++t) {
        int start_sample = t * HOP_LENGTH;
        for (int n = 0; n < N_FFT; ++n) {
            ola_window_sum[start_sample + n] += window_[n] * window_[n];
        }
    }

    ola_inv_norm_.resize(PADDED_SAMPLES);
    for (int s = 0; s < PADDED_SAMPLES; ++s) {
        if (ola_window_sum[s] > 1e-8f) {
            // kiss_fft inverse is unnormalized (sum = N * x), so dividing by (sum * N_FFT)
            // simultaneously normalizes the iFFT and normalizes the synthesis window sum!
            ola_inv_norm_[s] = 1.0f / (ola_window_sum[s] * static_cast<float>(N_FFT));
        } else {
            ola_inv_norm_[s] = 0.0f;
        }
    }

    // 3. Preallocate working buffers
    padded_input_.resize(PADDED_SAMPLES);
    padded_output_.resize(PADDED_SAMPLES);
}

void StftEngine::forward(const float* stereo_pcm, float* out_tensor, int num_bins) {
    if (num_bins <= 0 || num_bins > MAX_N_BINS) num_bins = DEFAULT_N_BINS;

    // Out tensor layout: [channel][bin][frame]
    // channel 0: Left Real,  channel 1: Left Imag
    // channel 2: Right Real, channel 3: Right Imag
    for (int ch = 0; ch < 2; ++ch) {
        int re_ch = ch * 2;
        int im_ch = ch * 2 + 1;

        // Apply PyTorch center=True reflect padding
        // Left reflection: reflect across sample 0 (sample -1 -> sample 1)
        for (int k = 0; k < PAD_SAMPLES; ++k) {
            padded_input_[PAD_SAMPLES - 1 - k] = stereo_pcm[(1 + k) * 2 + ch];
        }

        // Center original audio
        for (int i = 0; i < TOTAL_SAMPLES; ++i) {
            padded_input_[PAD_SAMPLES + i] = stereo_pcm[i * 2 + ch];
        }

        // Right reflection: reflect across sample TOTAL_SAMPLES - 1
        for (int k = 0; k < PAD_SAMPLES; ++k) {
            padded_input_[PAD_SAMPLES + TOTAL_SAMPLES + k] = stereo_pcm[(TOTAL_SAMPLES - 2 - k) * 2 + ch];
        }

        // Compute 256 STFT frames
        for (int t = 0; t < N_FRAMES; ++t) {
            int start_sample = t * HOP_LENGTH;

            for (int n = 0; n < N_FFT; ++n) {
                fft_in_[n].r = padded_input_[start_sample + n] * window_[n];
                fft_in_[n].i = 0.0f;
            }

            kiss_fft(cfg_forward_, fft_in_.data(), fft_out_.data());

            // UVR MDX-NET zeroes bins 0, 1, 2
            for (int bin = 0; bin < 3; ++bin) {
                int idx = bin * N_FRAMES + t;
                out_tensor[re_ch * (num_bins * N_FRAMES) + idx] = 0.0f;
                out_tensor[im_ch * (num_bins * N_FRAMES) + idx] = 0.0f;
            }

            // Store bins 3..num_bins-1 into tensor
            for (int bin = 3; bin < num_bins; ++bin) {
                int idx = bin * N_FRAMES + t;
                out_tensor[re_ch * (num_bins * N_FRAMES) + idx] = fft_out_[bin].r;
                out_tensor[im_ch * (num_bins * N_FRAMES) + idx] = fft_out_[bin].i;
            }
        }
    }
}

void StftEngine::inverse(const float* in_tensor, float* out_stereo_pcm, int num_bins) {
    if (num_bins <= 0 || num_bins > MAX_N_BINS) num_bins = DEFAULT_N_BINS;

    for (int ch = 0; ch < 2; ++ch) {
        int re_ch = ch * 2;
        int im_ch = ch * 2 + 1;

        std::fill(padded_output_.begin(), padded_output_.end(), 0.0f);

        for (int t = 0; t < N_FRAMES; ++t) {
            int start_sample = t * HOP_LENGTH;

            // Bin 0 (DC)
            float re0 = in_tensor[re_ch * (num_bins * N_FRAMES) + (0 * N_FRAMES + t)];
            fft_in_[0].r = re0;
            fft_in_[0].i = 0.0f;

            // Bins 1..num_bins-1 (model predictions)
            for (int bin = 1; bin < num_bins; ++bin) {
                int idx = bin * N_FRAMES + t;
                float re = in_tensor[re_ch * (num_bins * N_FRAMES) + idx];
                float im = in_tensor[im_ch * (num_bins * N_FRAMES) + idx];
                fft_in_[bin].r = re;
                fft_in_[bin].i = im;
                // Hermitian conjugate for negative frequencies
                fft_in_[N_FFT - bin].r = re;
                fft_in_[N_FFT - bin].i = -im;
            }

            // Zero-pad cutoff frequencies: bins num_bins .. 3072 (Nyquist)
            for (int bin = num_bins; bin <= N_FFT / 2; ++bin) {
                fft_in_[bin].r = 0.0f;
                fft_in_[bin].i = 0.0f;
                fft_in_[N_FFT - bin].r = 0.0f;
                fft_in_[N_FFT - bin].i = 0.0f;
            }

            kiss_fft(cfg_inverse_, fft_in_.data(), fft_out_.data());

            // Apply synthesis window and accumulate into padded buffer
            for (int n = 0; n < N_FFT; ++n) {
                padded_output_[start_sample + n] += fft_out_[n].r * window_[n];
            }
        }

        // Exact Overlap-Add Normalization and extract central TOTAL_SAMPLES
        for (int i = 0; i < TOTAL_SAMPLES; ++i) {
            int s = PAD_SAMPLES + i;
            out_stereo_pcm[i * 2 + ch] = padded_output_[s] * ola_inv_norm_[s] * COMPENSATION;
        }
    }
}

void StftEngine::blend(const float* original_pcm, const float* vocal_pcm,
                       float* out_pcm, int sample_count, float level) {
    float attenuation_factor = 1.0f - level;
    for (int i = 0; i < sample_count * 2; ++i) {
        out_pcm[i] = original_pcm[i] - (attenuation_factor * vocal_pcm[i]);
    }
}

} // namespace sing
