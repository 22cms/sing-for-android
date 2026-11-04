#include "stereo_resampler.h"
#include <algorithm>
#include <limits>
#include <stdexcept>

namespace sing {

StereoResampler::StereoResampler(int fromRate, int toRate)
    : fromRate_(fromRate), toRate_(toRate) {
    if (fromRate < 8000 || fromRate > 384000 || toRate < 8000 || toRate > 384000)
        throw std::invalid_argument("Unsupported sample rate");
    int error = 0;
    state_ = speex_resampler_init(2, fromRate, toRate, 8, &error);
    if (!state_ || error != RESAMPLER_ERR_SUCCESS)
        throw std::runtime_error("SpeexDSP resampler initialization failed");
    zeros_.resize((speex_resampler_get_input_latency(state_) + 32) * 2, 0.0f);
    reset();
}

StereoResampler::~StereoResampler() { speex_resampler_destroy(state_); }

void StereoResampler::reset() {
    speex_resampler_reset_mem(state_);
    // Remove leading filter delay; finish() supplies the corresponding tail.
    speex_resampler_skip_zeros(state_);
    inputFrames_ = outputFrames_ = 0;
    draining_ = finished_ = false;
}

int StereoResampler::maxOutputFrames(int frames) const {
    if (frames < 0) throw std::invalid_argument("Negative input frame count");
    const int64_t count = (int64_t(frames) * toRate_ + fromRate_ - 1) / fromRate_ + 1;
    if (count > std::numeric_limits<int>::max()) throw std::overflow_error("Output too large");
    return int(count);
}

int StereoResampler::resample(const float* input, int frames, float* output, int capacity) {
    if (draining_) throw std::logic_error("Resampler is draining; reset before reuse");
    if (capacity < maxOutputFrames(frames)) throw std::invalid_argument("Output buffer too small");
    if (frames == 0) return 0;
    spx_uint32_t consumed = frames, produced = capacity;
    int error = speex_resampler_process_interleaved_float(state_, input, &consumed, output, &produced);
    if (error != RESAMPLER_ERR_SUCCESS || consumed != unsigned(frames))
        throw std::runtime_error("SpeexDSP failed to consume input");
    inputFrames_ += consumed;
    outputFrames_ += produced;
    return int(produced);
}

int StereoResampler::finish(float* output, int capacity) {
    draining_ = true;
    const int64_t target = (inputFrames_ * toRate_ + fromRate_ - 1) / fromRate_;
    const int wanted = int(std::min<int64_t>(capacity, target - outputFrames_));
    int written = 0;
    while (written < wanted) {
        spx_uint32_t consumed = zeros_.size() / 2, produced = wanted - written;
        int error = speex_resampler_process_interleaved_float(
            state_, zeros_.data(), &consumed, output + written * 2, &produced);
        if (error != RESAMPLER_ERR_SUCCESS || (consumed == 0 && produced == 0))
            throw std::runtime_error("SpeexDSP tail drain failed");
        written += produced;
    }
    outputFrames_ += written;
    finished_ = outputFrames_ >= target;
    return written;
}

} // namespace sing
