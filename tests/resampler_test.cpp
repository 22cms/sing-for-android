#include "stereo_resampler.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <vector>

static std::vector<float> convert(int from, int to, const std::vector<float>& input, int block) {
    sing::StereoResampler resampler(from, to);
    const int frames = input.size() / 2;
    const int capacity = resampler.maxOutputFrames(frames) + 1024;
    std::vector<float> result(capacity * 2);
    int written = 0;
    for (int i = 0; i < frames; i += block) {
        int count = std::min(block, frames - i);
        written += resampler.resample(input.data() + i * 2, count,
                                      result.data() + written * 2, capacity - written);
    }
    // Exercise partial tail drains, including tiny output capacities.
    while (!resampler.isFinished()) written += resampler.finish(result.data() + written * 2, 7);
    assert(written == (int64_t(frames) * to + from - 1) / from);
    assert(resampler.finish(result.data(), capacity) == 0);
    result.resize(written * 2);
    resampler.reset();
    std::vector<float> repeat(capacity * 2);
    int n = resampler.resample(input.data(), frames, repeat.data(), capacity);
    n += resampler.finish(repeat.data() + n * 2, capacity - n);
    assert(n == written);
    for (int i = 0; i < n * 2; ++i) assert(std::abs(result[i] - repeat[i]) < 1e-5f);
    return result;
}

int main() {
    for (int rate : {8000, 44100, 48000, 96000, 192000, 384000}) {
        const int frames = rate / 3 + 17;
        std::vector<float> input(frames * 2);
        for (int i = 0; i < frames; ++i) {
            input[i * 2] = 0.5f * std::sin(2.0 * 3.141592653589793 * 1700 * i / rate);
            input[i * 2 + 1] = -input[i * 2];
        }
        auto whole = convert(rate, 44100, input, frames);
        auto split = convert(rate, 44100, input, 127);
        assert(whole.size() == split.size());
        float error = 0;
        for (size_t i = 0; i < whole.size(); ++i) error = std::max(error, std::abs(whole[i] - split[i]));
        assert(error < 1e-5f);
        auto roundTrip = convert(44100, rate, split, 113);
        assert(roundTrip.size() >= input.size());
        assert(roundTrip.size() - input.size() <= size_t((rate + 44099) / 44100 * 2));
        std::printf("%d Hz: streaming error %.8f, output %zu frames\n", rate, error, split.size() / 2);
    }
    sing::StereoResampler checked(48000, 44100);
    float input[4] = {}, output[4] = {};
    bool rejected = false;
    try { checked.resample(input, 2, output, 1); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
    auto empty = convert(48000, 44100, {}, 1);
    assert(empty.empty());
}
