#pragma once

#include <cstdint>
#include <vector>
#include <speex_resampler.h>

namespace sing {

// Streaming adapter; filtering and fractional phase belong to SpeexDSP.
class StereoResampler {
public:
    StereoResampler(int fromRate, int toRate);
    ~StereoResampler();
    StereoResampler(const StereoResampler&) = delete;
    StereoResampler& operator=(const StereoResampler&) = delete;

    int maxOutputFrames(int inputFrames) const;
    int resample(const float* input, int frames, float* output, int capacity);
    int finish(float* output, int capacity);
    bool isFinished() const { return finished_; }
    void reset();

private:
    SpeexResamplerState* state_ = nullptr;
    int fromRate_, toRate_;
    int64_t inputFrames_ = 0, outputFrames_ = 0;
    bool draining_ = false, finished_ = false;
    std::vector<float> zeros_;
};

} // namespace sing
