#pragma once

#include <vector>
#include <cmath>

namespace sing {

class SincResampler {
public:
    SincResampler(int from_rate, int to_rate);
    ~SincResampler() = default;

    // Resamples interleaved stereo PCM from in_pcm to out_pcm.
    // Returns the number of output stereo frames written.
    int resample(const float* in_pcm, int in_frames, float* out_pcm);

    // Resets filter history and fractional phase.
    void reset();

private:
    int from_rate_;
    int to_rate_;
    double ratio_;
    double cutoff_;
    int filter_span_;
    double frac_pos_;
    int history_frames_;
    std::vector<float> history_;

    static constexpr double PI = 3.14159265358979323846;
};

} // namespace sing
