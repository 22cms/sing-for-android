#include "vr_dsp_engine.h"
#include <cstring>
#include <algorithm>
#include <cmath>

namespace sing {

constexpr float TWO_PI = 6.28318530717958647692f;

VrDspEngine::VrDspEngine(int window_frames)
    : window_frames_(window_frames), roi_frames_(window_frames - 2 * OFFSET_FRAMES) {
    fft_cfg_ = kiss_fftr_alloc(VR_N_FFT, 0, nullptr, nullptr);
    ifft_cfg_ = kiss_fftr_alloc(VR_N_FFT, 1, nullptr, nullptr);
    init_tables();
}

VrDspEngine::~VrDspEngine() {
    if (fft_cfg_) {
        kiss_fftr_free(fft_cfg_);
        fft_cfg_ = nullptr;
    }
    if (ifft_cfg_) {
        kiss_fftr_free(ifft_cfg_);
        ifft_cfg_ = nullptr;
    }
}

void VrDspEngine::setWindowFrames(int frames) {
    if (frames == window_frames_) return;
    window_frames_ = frames;
    roi_frames_ = window_frames_ - 2 * OFFSET_FRAMES;
    init_tables();
}

void VrDspEngine::reset() {
    std::fill(cached_spec_.begin(), cached_spec_.end(), kiss_fft_cpx{0.0f, 0.0f});
    std::fill(pcm_22k_.begin(), pcm_22k_.end(), 0.0f);
    std::fill(pcm_11k_.begin(), pcm_11k_.end(), 0.0f);
}

static void compute_istft_norm(std::vector<float>& norm_table, int n_fft, int hop, int frames, const std::vector<float>& win) {
    int total_samples = (frames - 1) * hop;
    int padded_samples = total_samples + n_fft;
    std::vector<float> sum_sq(padded_samples, 0.0f);

    for (int t = 0; t < frames; ++t) {
        int start = t * hop;
        for (int n = 0; n < n_fft; ++n) {
            sum_sq[start + n] += win[n] * win[n];
        }
    }

    norm_table.resize(total_samples);
    int pad = n_fft / 2;
    for (int s = 0; s < total_samples; ++s) {
        float denom = sum_sq[pad + s] * static_cast<float>(n_fft);
        norm_table[s] = (denom > 1e-8f) ? (1.0f / denom) : 0.0f;
    }
}

void VrDspEngine::init_tables() {
    hann_window_.resize(VR_N_FFT);
    for (int n = 0; n < VR_N_FFT; ++n) {
        hann_window_[n] = 0.5f * (1.0f - std::cos(TWO_PI * n / static_cast<float>(VR_N_FFT)));
    }

    // iSTFT normalization tables for ROI frames
    compute_istft_norm(istft_window_norm_b1_, VR_N_FFT, 128, roi_frames_, hann_window_);
    compute_istft_norm(istft_window_norm_b2_, VR_N_FFT, 256, roi_frames_, hann_window_);
    compute_istft_norm(istft_window_norm_b3_, VR_N_FFT, 512, roi_frames_, hann_window_);

    // Buffer allocations
    int chunk_samples_44k = (window_frames_ - 1) * 512;
    pcm_22k_.resize((chunk_samples_44k / 2) * 2);
    pcm_11k_.resize((chunk_samples_44k / 4) * 2);

    cached_spec_.resize(2 * VR_TOTAL_BINS * window_frames_);
}

static void apply_reflect_pad(const float* in, int frames, int ch, int pad, std::vector<float>& padded) {
    padded.resize(frames + 2 * pad);
    for (int k = 0; k < pad; ++k) {
        padded[pad - 1 - k] = in[(1 + k) * 2 + ch];
    }
    for (int i = 0; i < frames; ++i) {
        padded[pad + i] = in[i * 2 + ch];
    }
    for (int k = 0; k < pad; ++k) {
        padded[pad + frames + k] = in[(frames - 2 - k) * 2 + ch];
    }
}

void VrDspEngine::forward(const float* stereo_pcm_44k, float* out_mag_tensor) {
    const int chunk_samples_44k = (window_frames_ - 1) * 512;
    const int chunk_samples_22k = chunk_samples_44k / 2;
    const int chunk_samples_11k = chunk_samples_44k / 4;

    // 1. Resample to 22.05 kHz and 11.025 kHz via Polyphase Half-Band filters
    HalfBand2x::downsample2x(stereo_pcm_44k, chunk_samples_44k, pcm_22k_.data());
    HalfBand2x::downsample2x(pcm_22k_.data(), chunk_samples_22k, pcm_11k_.data());

    constexpr int PAD = VR_N_FFT / 2; // 384
    std::vector<float> padded;
    std::vector<float> fin(VR_N_FFT);
    std::vector<kiss_fft_cpx> fout(VR_BINS_HALF);

    const int plane = VR_TOTAL_BINS * window_frames_;

    for (int ch = 0; ch < 2; ++ch) {
        // --- Band 1: 11025 Hz, hl=128, crop [0..278) (278 bins) ---
        apply_reflect_pad(pcm_11k_.data(), chunk_samples_11k, ch, PAD, padded);
        for (int t = 0; t < window_frames_; ++t) {
            int start = t * 128;
            for (int n = 0; n < VR_N_FFT; ++n) {
                fin[n] = padded[start + n] * hann_window_[n];
            }
            kiss_fftr(fft_cfg_, fin.data(), fout.data());

            for (int bin = 0; bin < 278; ++bin) {
                cached_spec_[ch * plane + bin * window_frames_ + t] = fout[bin];
            }
        }

        // --- Band 2: 22050 Hz, hl=256, crop [14..322) (308 bins) ---
        apply_reflect_pad(pcm_22k_.data(), chunk_samples_22k, ch, PAD, padded);
        for (int t = 0; t < window_frames_; ++t) {
            int start = t * 256;
            for (int n = 0; n < VR_N_FFT; ++n) {
                fin[n] = padded[start + n] * hann_window_[n];
            }
            kiss_fftr(fft_cfg_, fin.data(), fout.data());

            for (int bin = 14; bin < 322; ++bin) {
                int out_bin = 278 + (bin - 14);
                cached_spec_[ch * plane + out_bin * window_frames_ + t] = fout[bin];
            }
        }

        // --- Band 3: 44100 Hz, hl=512, crop [131..313) (182 bins) ---
        apply_reflect_pad(stereo_pcm_44k, chunk_samples_44k, ch, PAD, padded);
        for (int t = 0; t < window_frames_; ++t) {
            int start = t * 512;
            for (int n = 0; n < VR_N_FFT; ++n) {
                fin[n] = padded[start + n] * hann_window_[n];
            }
            kiss_fftr(fft_cfg_, fin.data(), fout.data());

            for (int bin = 131; bin < 313; ++bin) {
                int out_bin = 278 + 308 + (bin - 131);
                cached_spec_[ch * plane + out_bin * window_frames_ + t] = fout[bin];
            }

            // Zero Nyquist bin 768
            cached_spec_[ch * plane + 768 * window_frames_ + t] = {0.0f, 0.0f};
        }

        // --- Pre-filter bins [757..768) ---
        for (int t = 0; t < window_frames_; ++t) {
            float gp = 1.0f;
            for (int b = 758; b < 768; ++b) {
                float g = std::pow(10.0f, -(b - 757) * (3.5f - gp) / 20.0f);
                gp = g;
                cached_spec_[ch * plane + b * window_frames_ + t].r *= g;
                cached_spec_[ch * plane + b * window_frames_ + t].i *= g;
            }
        }
    }

    // Compute peak magnitude across full spectrogram for normalization
    float max_mag = 1e-8f;
    for (int ch = 0; ch < 2; ++ch) {
        for (int bin = 0; bin < VR_TOTAL_BINS; ++bin) {
            for (int t = 0; t < window_frames_; ++t) {
                int idx = ch * plane + bin * window_frames_ + t;
                float r = cached_spec_[idx].r;
                float i = cached_spec_[idx].i;
                float mag = std::sqrt(r * r + i * i);
                if (mag > max_mag) max_mag = mag;
            }
        }
    }

    // Normalization into out_mag_tensor
    float inv_max = 1.0f / max_mag;
    for (int ch = 0; ch < 2; ++ch) {
        for (int bin = 0; bin < VR_TOTAL_BINS; ++bin) {
            for (int t = 0; t < window_frames_; ++t) {
                int idx = ch * plane + bin * window_frames_ + t;
                float r = cached_spec_[idx].r;
                float i = cached_spec_[idx].i;
                out_mag_tensor[idx] = std::sqrt(r * r + i * i) * inv_max;
            }
        }
    }
}

static void fft_lp_filter_internal(kiss_fft_cpx* spec, int num_bins, int bin_start, int bin_stop) {
    float g = 1.0f;
    float step = 1.0f / (bin_stop - bin_start);
    for (int b = bin_start; b < bin_stop && b < num_bins; ++b) {
        g -= step;
        spec[b].r *= g;
        spec[b].i *= g;
    }
    for (int b = bin_stop; b < num_bins; ++b) {
        spec[b].r = 0.0f;
        spec[b].i = 0.0f;
    }
}

static void fft_hp_filter_internal(kiss_fft_cpx* spec, int num_bins, int bin_start, int bin_stop) {
    float g = 1.0f;
    float step = 1.0f / (bin_start - bin_stop);
    for (int b = bin_start; b > bin_stop && b >= 0; --b) {
        g -= step;
        spec[b].r *= g;
        spec[b].i *= g;
    }
    for (int b = 0; b <= bin_stop && b < num_bins; ++b) {
        spec[b].r = 0.0f;
        spec[b].i = 0.0f;
    }
}

void VrDspEngine::inverse(const float* in_mask_tensor, float* out_stereo_pcm_44k, bool extract_instrumental) {
    const int plane_in = VR_TOTAL_BINS * window_frames_;
    const int plane_mask = VR_TOTAL_BINS * roi_frames_;

    const int b1_samples = (roi_frames_ - 1) * 128;
    const int b2_samples = (roi_frames_ - 1) * 256;
    const int b3_samples = (roi_frames_ - 1) * 512;
    const int total_out_samples = roi_frames_ * 512;

    std::vector<float> b1_wave(b1_samples * 2, 0.0f);
    std::vector<float> b1_up_wave(b2_samples * 2, 0.0f);
    std::vector<float> b2_wave(b2_samples * 2, 0.0f);
    std::vector<float> b12_wave(b2_samples * 2, 0.0f);
    std::vector<float> b12_up_wave(b3_samples * 2, 0.0f);
    std::vector<float> b3_wave(b3_samples * 2, 0.0f);

    constexpr int PAD = VR_N_FFT / 2; // 384
    std::vector<kiss_fft_cpx> spec_band(VR_BINS_HALF);
    std::vector<float> time_out(VR_N_FFT);

    for (int ch = 0; ch < 2; ++ch) {
        // --- Synthesize Band 1 (11.025 kHz, hl=128) ---
        std::vector<float> accum_b1(b1_samples + VR_N_FFT, 0.0f);
        for (int t = 0; t < roi_frames_; ++t) {
            int src_t = OFFSET_FRAMES + t;
            std::fill(spec_band.begin(), spec_band.end(), kiss_fft_cpx{0.0f, 0.0f});

            for (int bin = 0; bin < 278; ++bin) {
                float mask = in_mask_tensor[ch * plane_mask + bin * roi_frames_ + t];
                if (!extract_instrumental) mask = 1.0f - mask;
                auto orig = cached_spec_[ch * plane_in + bin * window_frames_ + src_t];
                spec_band[bin] = {orig.r * mask, orig.i * mask};
            }

            fft_lp_filter_internal(spec_band.data(), VR_BINS_HALF, 28, 140);
            kiss_fftri(ifft_cfg_, spec_band.data(), time_out.data());

            int start = t * 128;
            for (int n = 0; n < VR_N_FFT; ++n) {
                accum_b1[start + n] += time_out[n] * hann_window_[n];
            }
        }
        for (int s = 0; s < b1_samples; ++s) {
            b1_wave[s * 2 + ch] = accum_b1[PAD + s] * istft_window_norm_b1_[s];
        }

        // --- Synthesize Band 2 (22.05 kHz, hl=256) ---
        std::vector<float> accum_b2(b2_samples + VR_N_FFT, 0.0f);
        for (int t = 0; t < roi_frames_; ++t) {
            int src_t = OFFSET_FRAMES + t;
            std::fill(spec_band.begin(), spec_band.end(), kiss_fft_cpx{0.0f, 0.0f});

            for (int bin = 14; bin < 322; ++bin) {
                int in_bin = 278 + (bin - 14);
                float mask = in_mask_tensor[ch * plane_mask + in_bin * roi_frames_ + t];
                if (!extract_instrumental) mask = 1.0f - mask;
                auto orig = cached_spec_[ch * plane_in + in_bin * window_frames_ + src_t];
                spec_band[bin] = {orig.r * mask, orig.i * mask};
            }

            fft_hp_filter_internal(spec_band.data(), VR_BINS_HALF, 70, 14);
            fft_lp_filter_internal(spec_band.data(), VR_BINS_HALF, 283, 314);
            kiss_fftri(ifft_cfg_, spec_band.data(), time_out.data());

            int start = t * 256;
            for (int n = 0; n < VR_N_FFT; ++n) {
                accum_b2[start + n] += time_out[n] * hann_window_[n];
            }
        }
        for (int s = 0; s < b2_samples; ++s) {
            b2_wave[s * 2 + ch] = accum_b2[PAD + s] * istft_window_norm_b2_[s];
        }

        // --- Synthesize Band 3 (44.1 kHz, hl=512) ---
        std::vector<float> accum_b3(b3_samples + VR_N_FFT, 0.0f);
        for (int t = 0; t < roi_frames_; ++t) {
            int src_t = OFFSET_FRAMES + t;
            std::fill(spec_band.begin(), spec_band.end(), kiss_fft_cpx{0.0f, 0.0f});

            for (int bin = 131; bin < 313; ++bin) {
                int in_bin = 278 + 308 + (bin - 131);
                float mask = in_mask_tensor[ch * plane_mask + in_bin * roi_frames_ + t];
                if (!extract_instrumental) mask = 1.0f - mask;
                auto orig = cached_spec_[ch * plane_in + in_bin * window_frames_ + src_t];
                spec_band[bin] = {orig.r * mask, orig.i * mask};
            }

            fft_hp_filter_internal(spec_band.data(), VR_BINS_HALF, 154, 141);
            kiss_fftri(ifft_cfg_, spec_band.data(), time_out.data());

            int start = t * 512;
            for (int n = 0; n < VR_N_FFT; ++n) {
                accum_b3[start + n] += time_out[n] * hann_window_[n];
            }
        }
        for (int s = 0; s < b3_samples; ++s) {
            b3_wave[s * 2 + ch] = accum_b3[PAD + s] * istft_window_norm_b3_[s];
        }
    }

    // Multi-rate reconstruction:
    // 1. Upsample Band 1 (11.025k -> 22.05k)
    HalfBand2x::upsample2x(b1_wave.data(), b1_samples, b1_up_wave.data());

    // 2. Add Band 1 + Band 2
    for (int i = 0; i < b2_samples * 2; ++i) {
        b12_wave[i] = b1_up_wave[i] + b2_wave[i];
    }

    // 3. Upsample Band 1+2 (22.05k -> 44.1k)
    HalfBand2x::upsample2x(b12_wave.data(), b2_samples, b12_up_wave.data());

    // 4. Add Band 1+2 + Band 3 into final output
    for (int i = 0; i < b3_samples * 2; ++i) {
        out_stereo_pcm_44k[i] = b12_up_wave[i] + b3_wave[i];
    }

    // Zero-pad any remainder up to total_out_samples
    if (total_out_samples > b3_samples) {
        std::fill(out_stereo_pcm_44k + b3_samples * 2, out_stereo_pcm_44k + total_out_samples * 2, 0.0f);
    }
}

} // namespace sing
