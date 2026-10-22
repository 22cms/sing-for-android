// Run on Linux with:
// g++ -std=c++17 -O2 -DKISSFFT_DATATYPE=float -Iapp/src/main/cpp -Iextern/kissfft \
//   tests/center_cut_test.cpp app/src/main/cpp/stft_engine.cpp \
//   extern/kissfft/kiss_fft.c extern/kissfft/kiss_fftr.c -pthread -o /tmp/center_cut_test
// /tmp/center_cut_test
#include "stft_engine.h"
#include <cmath>
#include <cstdio>
#include <vector>

int main() {
    sing::StftEngine engine;
    constexpr int frames = 4096;
    std::vector<float> input(frames * 2), removed(frames * 2);
    for (int mode = 0; mode < 2; ++mode) {
        for (int i = 0; i < frames; ++i) {
            const float sample = 0.4f * std::sin(2.0 * 3.141592653589793 * 430 * i / 44100);
            input[2 * i] = sample;
            input[2 * i + 1] = mode == 0 ? sample : -sample;
        }
        engine.centerCutPcm(input.data(), removed.data(), frames);
        double mono_power = 0.0;
        for (int i = 1024; i < frames - 1024; ++i) {
            const double mono = (input[2 * i] + input[2 * i + 1]) * 0.5 - removed[2 * i];
            mono_power += mono * mono;
        }
        const double mono_rms = std::sqrt(mono_power / (frames - 2048));
        std::printf("%s: mono residual RMS %.4f\n", mode == 0 ? "center" : "side", mono_rms);
        if ((mode == 0 && mono_rms > 0.01) || (mode == 1 && mono_rms < 0.08)) return 1;
    }

    constexpr int bins = 2048;
    const int plane = bins * sing::N_FRAMES;
    std::vector<float> tensor(4 * plane);
    const int i = 100 * sing::N_FRAMES + 17;
    tensor[i] = 10.0f;
    tensor[2 * plane + i] = -10.0f;
    engine.centerCutTensor(tensor.data(), bins);
    if (std::abs(tensor[i]) > 0.001f || std::abs(tensor[plane + i] + 8.5f) > 0.001f) return 2;

    // The time-domain path uses one block of context on either side. Check
    // that the quadrature copy does not reset at a block boundary.
    constexpr int pad = 1024;
    std::vector<float> waveform(3 * frames * 2), context((frames + 2 * pad) * 2);
    std::vector<float> processed(context.size());
    for (int frame = 0; frame < 3 * frames; ++frame) {
        const float s = 0.4f * std::sin(2.0 * 3.141592653589793 * 430 * frame / 44100);
        waveform[2 * frame] = s;
        waveform[2 * frame + 1] = -s;
    }
    auto sample = [&](int block, int offset) {
        for (int frame = 0; frame < frames + 2 * pad; ++frame) {
            const int src = block + frame - pad;
            context[2 * frame] = waveform[2 * src];
            context[2 * frame + 1] = waveform[2 * src + 1];
        }
        engine.centerCutPcm(context.data(), processed.data(), frames + 2 * pad);
        return processed[2 * (pad + offset)];
    };
    const float previous = sample(frames, frames - 1);
    const float next = sample(2 * frames, 0);
    const float previousSlope = sample(frames, frames - 1) - sample(frames, frames - 2);
    if (std::abs((next - previous) - previousSlope) > 0.03f) return 3;
    return 0;
}
