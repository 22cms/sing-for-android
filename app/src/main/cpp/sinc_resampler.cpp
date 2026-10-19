#include "sinc_resampler.h"
#include <cstring>
#include <algorithm>

namespace sing {

SincResampler::SincResampler(int from_rate, int to_rate)
    : from_rate_(from_rate), to_rate_(to_rate), frac_pos_(0.0) {
    ratio_ = static_cast<double>(from_rate_) / static_cast<double>(to_rate_);

    if (from_rate_ > to_rate_) {
        // Downsampling: filter cutoff is 0.95 * (to_rate / from_rate) to prevent aliasing
        cutoff_ = (1.0 / ratio_) * 0.95;
        filter_span_ = static_cast<int>(std::ceil(16.0 / cutoff_));
    } else {
        // Upsampling: filter cutoff is 0.95 * source Nyquist
        cutoff_ = 0.95;
        filter_span_ = 16;
    }

    history_frames_ = filter_span_ * 2 + 32;
    history_.assign(history_frames_ * 2, 0.0f);
}

void SincResampler::reset() {
    frac_pos_ = 0.0;
    std::fill(history_.begin(), history_.end(), 0.0f);
}

int SincResampler::resample(const float* in_pcm, int in_frames, float* out_pcm) {
    if (in_frames <= 0) return 0;

    int out_idx = 0;
    double pos = frac_pos_;

    while (pos < in_frames) {
        int center_idx = static_cast<int>(std::floor(pos));
        double out_l = 0.0;
        double out_r = 0.0;
        double weight_sum = 0.0;

        int k_min = center_idx - filter_span_;
        int k_max = center_idx + filter_span_;

        for (int k = k_min; k <= k_max; ++k) {
            double x = k - pos; // continuous distance
            // Windowed sinc
            double sinc_val = (std::abs(x) < 1e-9) ? 1.0 : (std::sin(PI * cutoff_ * x) / (PI * cutoff_ * x));
            double u = x / filter_span_; // in [-1, 1]
            double w = (std::abs(u) <= 1.0) ? (0.42 + 0.5 * std::cos(PI * u) + 0.08 * std::cos(2.0 * PI * u)) : 0.0;
            double h = cutoff_ * sinc_val * w;

            // Fetch sample with history and edge extension
            float s_l, s_r;
            if (k < 0) {
                int hist_idx = history_frames_ + k;
                if (hist_idx >= 0 && hist_idx < history_frames_) {
                    s_l = history_[hist_idx * 2];
                    s_r = history_[hist_idx * 2 + 1];
                } else {
                    s_l = history_[0];
                    s_r = history_[1];
                }
            } else if (k < in_frames) {
                s_l = in_pcm[k * 2];
                s_r = in_pcm[k * 2 + 1];
            } else {
                s_l = in_pcm[(in_frames - 1) * 2];
                s_r = in_pcm[(in_frames - 1) * 2 + 1];
            }

            out_l += h * s_l;
            out_r += h * s_r;
            weight_sum += h;
        }

        double inv_w = (std::abs(weight_sum) > 1e-9) ? (1.0 / weight_sum) : 1.0;
        out_pcm[out_idx * 2] = static_cast<float>(out_l * inv_w);
        out_pcm[out_idx * 2 + 1] = static_cast<float>(out_r * inv_w);
        out_idx++;
        pos += ratio_;
    }

    frac_pos_ = pos - in_frames;

    // Update history with the end of in_pcm
    int copy_frames = std::min(in_frames, history_frames_);
    int hist_offset = history_frames_ - copy_frames;
    if (hist_offset > 0) {
        std::memmove(history_.data(), history_.data() + copy_frames * 2, hist_offset * 2 * sizeof(float));
    }
    std::memcpy(history_.data() + hist_offset * 2, in_pcm + (in_frames - copy_frames) * 2, copy_frames * 2 * sizeof(float));

    return out_idx;
}

} // namespace sing
