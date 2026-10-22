#include "stft_engine.h"
#include <cstring>
#include <algorithm>
#include <cmath>

namespace sing {

constexpr float TWO_PI = 6.28318530717958647692f;

StftEngine::StftEngine() : stop_(false), active_tasks_(0) {
    init_tables();
    center_forward_ = kiss_fftr_alloc(2048, 0, nullptr, nullptr);
    center_inverse_ = kiss_fftr_alloc(2048, 1, nullptr, nullptr);

    for (int w = 0; w < NUM_STFT_WORKERS; ++w) {
        cfgs_forward_[w] = kiss_fftr_alloc(N_FFT, 0, nullptr, nullptr);
        cfgs_inverse_[w] = kiss_fftr_alloc(N_FFT, 1, nullptr, nullptr);
        worker_fin_r_[w].resize(N_FFT);
        worker_fout_c_[w].resize(N_FFT / 2 + 1);
        worker_fin_c_[w].resize(N_FFT / 2 + 1);
        worker_fout_r_[w].resize(N_FFT);
        worker_padded_out_[w].resize(PADDED_SAMPLES);
    }

    for (int i = 0; i < NUM_STFT_WORKERS; ++i) {
        workers_.emplace_back([this]() {
            while (true) {
                std::function<void()> task;
                {
                    std::unique_lock<std::mutex> lock(queue_mutex_);
                    cv_task_.wait(lock, [this]() { return stop_ || !tasks_.empty(); });
                    if (stop_ && tasks_.empty()) return;
                    task = std::move(tasks_.front());
                    tasks_.pop();
                }
                task();
                if (--active_tasks_ == 0) {
                    std::unique_lock<std::mutex> lock(queue_mutex_);
                    cv_done_.notify_all();
                }
            }
        });
    }
}

StftEngine::~StftEngine() {
    {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        stop_ = true;
    }
    cv_task_.notify_all();
    for (auto& w : workers_) {
        if (w.joinable()) w.join();
    }
    for (int w = 0; w < NUM_STFT_WORKERS; ++w) {
        if (cfgs_forward_[w]) {
            kiss_fftr_free(cfgs_forward_[w]);
            cfgs_forward_[w] = nullptr;
        }
        if (cfgs_inverse_[w]) {
            kiss_fftr_free(cfgs_inverse_[w]);
            cfgs_inverse_[w] = nullptr;
        }
    }
    kiss_fftr_free(center_forward_);
    kiss_fftr_free(center_inverse_);
}

// JamesDSP-style center estimate, with a quadrature side copy mixed into the
// residual. A plain L-R residual has zero mono sum; rotating the side by 90
// degrees gives it a mono component without favoring the left or right channel.
// DC/Nyquist cannot be rotated (they must remain real).
static void center_cut_bin(float lr, float li, float rr, float ri,
                           float mono_gain, float& out_r, float& out_i) {
    const float sum_r = lr + rr, sum_i = li + ri;
    const float diff_r = lr - rr, diff_i = li - ri;
    const float sum_sq = sum_r * sum_r + sum_i * sum_i;
    const float diff_sq = diff_r * diff_r + diff_i * diff_i;
    const float ratio = std::sqrt(diff_sq / std::max(sum_sq, 1e-12f));
    const float alpha = 0.5f * (1.0f - std::min(1.0f, ratio));
    // Side = (L-R)/2; i * Side = (-ImSide, ReSide).
    out_r = alpha * sum_r + mono_gain * 0.5f * diff_i;
    out_i = alpha * sum_i - mono_gain * 0.5f * diff_r;
}

void StftEngine::centerCutTensor(float* tensor, int num_bins) {
    if (num_bins <= 0 || num_bins > MAX_N_BINS) return;
    const int plane = num_bins * N_FRAMES;
    for (int bin = 0; bin < num_bins; ++bin) {
        const float gain = (bin == 0 || bin == N_FFT / 2) ? 0.0f : 0.85f;
        for (int frame = 0; frame < N_FRAMES; ++frame) {
            const int i = bin * N_FRAMES + frame;
            float r, im;
            center_cut_bin(tensor[i], tensor[plane + i],
                           tensor[2 * plane + i], tensor[3 * plane + i], gain, r, im);
            tensor[i] = tensor[2 * plane + i] = r;
            tensor[plane + i] = tensor[3 * plane + i] = im;
        }
    }
}

void StftEngine::centerCutPcm(const float* pcm, float* out, int frames) {
    constexpr int fft = 2048, hop = 512, pad = fft / 2;
    if (frames <= 1) return;
    const int padded = frames + 2 * pad;
    std::vector<float> input[2] = {std::vector<float>(padded), std::vector<float>(padded)};
    std::vector<float> accum(padded, 0.0f), norm(padded, 0.0f);
    std::vector<float> fin(fft), fout(fft);
    std::vector<float> window(fft);
    for (int n = 0; n < fft; ++n) {
        window[n] = 0.5f * (1.0f - std::cos(TWO_PI * n / fft));
    }
    std::vector<kiss_fft_cpx> left(pad + 1), right(pad + 1), center(pad + 1);
    for (int ch = 0; ch < 2; ++ch) {
        for (int i = 0; i < padded; ++i) {
            int pos = i - pad;
            // Reflect at both ends (same boundary convention as model STFT).
            while (pos < 0 || pos >= frames) {
                if (pos < 0) pos = -pos;
                if (pos >= frames) pos = 2 * frames - 2 - pos;
            }
            input[ch][i] = pcm[2 * pos + ch];
        }
    }
    for (int start = 0; start + fft <= padded; start += hop) {
        for (int n = 0; n < fft; ++n) {
            fin[n] = input[0][start + n] * window[n];
        }
        kiss_fftr(center_forward_, fin.data(), left.data());
        for (int n = 0; n < fft; ++n) {
            fin[n] = input[1][start + n] * window[n];
        }
        kiss_fftr(center_forward_, fin.data(), right.data());
        for (int bin = 0; bin <= pad; ++bin) {
            const float gain = (bin == 0 || bin == pad) ? 0.0f : 0.85f;
            center_cut_bin(left[bin].r, left[bin].i, right[bin].r, right[bin].i,
                           gain, center[bin].r, center[bin].i);
        }
        kiss_fftri(center_inverse_, center.data(), fout.data());
        for (int n = 0; n < fft; ++n) {
            accum[start + n] += fout[n] * window[n];
            norm[start + n] += window[n] * window[n];
        }
    }
    for (int i = 0; i < frames; ++i) {
        const int s = pad + i;
        // kiss_fftri is unnormalized; identical mono center in both channels.
        const float v = norm[s] > 1e-8f ? accum[s] / (norm[s] * fft) : 0.0f;
        out[2 * i] = out[2 * i + 1] = std::isfinite(v) ? v : 0.0f;
    }
}

void StftEngine::submit(std::function<void()> task) {
    {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        tasks_.push(std::move(task));
        ++active_tasks_;
    }
    cv_task_.notify_one();
}

void StftEngine::wait_all() {
    std::unique_lock<std::mutex> lock(queue_mutex_);
    cv_done_.wait(lock, [this]() { return active_tasks_ == 0 && tasks_.empty(); });
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
            ola_inv_norm_[s] = 1.0f / (ola_window_sum[s] * static_cast<float>(N_FFT));
        } else {
            ola_inv_norm_[s] = 0.0f;
        }
    }

    // 3. Preallocate reflection padding buffers
    padded_input_[0].resize(PADDED_SAMPLES);
    padded_input_[1].resize(PADDED_SAMPLES);
}

void StftEngine::forward(const float* stereo_pcm, float* out_tensor, int num_bins) {
    if (num_bins <= 0 || num_bins > MAX_N_BINS) num_bins = DEFAULT_N_BINS;

    // Apply PyTorch center=True reflect padding for both stereo channels
    for (int ch = 0; ch < 2; ++ch) {
        float* pin = padded_input_[ch].data();
        for (int k = 0; k < PAD_SAMPLES; ++k) {
            pin[PAD_SAMPLES - 1 - k] = stereo_pcm[(1 + k) * 2 + ch];
        }
        for (int i = 0; i < TOTAL_SAMPLES; ++i) {
            pin[PAD_SAMPLES + i] = stereo_pcm[i * 2 + ch];
        }
        for (int k = 0; k < PAD_SAMPLES; ++k) {
            pin[PAD_SAMPLES + TOTAL_SAMPLES + k] = stereo_pcm[(TOTAL_SAMPLES - 2 - k) * 2 + ch];
        }
    }

    // Dispatch STFT frames across worker threads (512 total frames / NUM_STFT_WORKERS)
    const int total_tasks = 2 * N_FRAMES; // 512
    const int frames_per_worker = total_tasks / NUM_STFT_WORKERS; // 128

    for (int w = 0; w < NUM_STFT_WORKERS; ++w) {
        submit([this, w, frames_per_worker, num_bins, out_tensor]() {
            const int start_task = w * frames_per_worker;
            const int end_task = start_task + frames_per_worker;
            float* fin_r = worker_fin_r_[w].data();
            kiss_fft_cpx* fout_c = worker_fout_c_[w].data();
            const float* win = window_.data();

            for (int task = start_task; task < end_task; ++task) {
                const int ch = task / N_FRAMES;
                const int t = task % N_FRAMES;
                const int re_ch = ch * 2;
                const int im_ch = ch * 2 + 1;
                const int start_sample = t * HOP_LENGTH;
                const float* pin = padded_input_[ch].data() + start_sample;

                for (int n = 0; n < N_FFT; ++n) {
                    fin_r[n] = pin[n] * win[n];
                }

                kiss_fftr(cfgs_forward_[w], fin_r, fout_c);

                float* out_re = &out_tensor[re_ch * (num_bins * N_FRAMES) + t];
                float* out_im = &out_tensor[im_ch * (num_bins * N_FRAMES) + t];

                // UVR MDX-NET zeroes bins 0, 1, 2
                for (int bin = 0; bin < 3; ++bin) {
                    out_re[bin * N_FRAMES] = 0.0f;
                    out_im[bin * N_FRAMES] = 0.0f;
                }

                // Store bins 3..num_bins-1 into tensor
                for (int bin = 3; bin < num_bins; ++bin) {
                    out_re[bin * N_FRAMES] = fout_c[bin].r;
                    out_im[bin * N_FRAMES] = fout_c[bin].i;
                }
            }
        });
    }

    wait_all();
}

void StftEngine::inverse(const float* in_tensor, float* out_stereo_pcm, int num_bins) {
    if (num_bins <= 0 || num_bins > MAX_N_BINS) num_bins = DEFAULT_N_BINS;

    const int total_tasks = 2 * N_FRAMES; // 512
    const int frames_per_worker = total_tasks / NUM_STFT_WORKERS; // 128

    for (int w = 0; w < NUM_STFT_WORKERS; ++w) {
        submit([this, w, frames_per_worker, num_bins, in_tensor]() {
            const int start_task = w * frames_per_worker;
            const int end_task = start_task + frames_per_worker;
            float* pout = worker_padded_out_[w].data();
            std::fill(pout, pout + PADDED_SAMPLES, 0.0f);

            kiss_fft_cpx* fin_c = worker_fin_c_[w].data();
            float* fout_r = worker_fout_r_[w].data();
            const float* win = window_.data();

            for (int task = start_task; task < end_task; ++task) {
                const int ch = task / N_FRAMES;
                const int t = task % N_FRAMES;
                const int re_ch = ch * 2;
                const int im_ch = ch * 2 + 1;
                const int start_sample = t * HOP_LENGTH;
                const float* in_re = &in_tensor[re_ch * (num_bins * N_FRAMES) + t];
                const float* in_im = &in_tensor[im_ch * (num_bins * N_FRAMES) + t];

                // Bin 0 (DC)
                fin_c[0].r = in_re[0];
                fin_c[0].i = 0.0f;

                // Bins 1..num_bins-1 (model predictions)
                for (int bin = 1; bin < num_bins; ++bin) {
                    fin_c[bin].r = in_re[bin * N_FRAMES];
                    fin_c[bin].i = in_im[bin * N_FRAMES];
                }

                // Zero-pad cutoff frequencies: bins num_bins .. N_FFT / 2 (Nyquist)
                for (int bin = num_bins; bin <= N_FFT / 2; ++bin) {
                    fin_c[bin].r = 0.0f;
                    fin_c[bin].i = 0.0f;
                }

                kiss_fftri(cfgs_inverse_[w], fin_c, fout_r);

                // Apply synthesis window and accumulate into worker's padded buffer
                for (int n = 0; n < N_FFT; ++n) {
                    pout[start_sample + n] += fout_r[n] * win[n];
                }
            }
        });
    }

    wait_all();

    // Exact Overlap-Add Normalization and extract central TOTAL_SAMPLES:
    // Worker 0 & 1 -> Left Channel, Worker 2 & 3 -> Right Channel
    const float* norm = ola_inv_norm_.data();
    for (int ch = 0; ch < 2; ++ch) {
        const int w0 = ch * 2;
        const int w1 = ch * 2 + 1;
        const float* p0 = worker_padded_out_[w0].data();
        const float* p1 = worker_padded_out_[w1].data();

        for (int i = 0; i < TOTAL_SAMPLES; ++i) {
            const int s = PAD_SAMPLES + i;
            out_stereo_pcm[i * 2 + ch] = (p0[s] + p1[s]) * norm[s] * COMPENSATION;
        }
    }
}

} // namespace sing
