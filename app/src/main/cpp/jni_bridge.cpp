#include <jni.h>
#include <string>
#include <vector>
#include "stft_engine.h"
#include "sinc_resampler.h"
#include "vr_dsp_engine.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* engine = new sing::StftEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<sing::StftEngine*>(handle);
    delete engine;
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeForward(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray stereo_pcm, jfloatArray out_tensor, jint num_bins) {
    auto* engine = reinterpret_cast<sing::StftEngine*>(handle);
    if (!engine) return;

    jfloat* pcm_ptr = env->GetFloatArrayElements(stereo_pcm, nullptr);
    jfloat* tensor_ptr = env->GetFloatArrayElements(out_tensor, nullptr);

    engine->forward(pcm_ptr, tensor_ptr, num_bins);

    env->ReleaseFloatArrayElements(stereo_pcm, pcm_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(out_tensor, tensor_ptr, 0);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeInverse(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray in_tensor, jfloatArray out_stereo_pcm, jint num_bins) {
    auto* engine = reinterpret_cast<sing::StftEngine*>(handle);
    if (!engine) return;

    jfloat* tensor_ptr = env->GetFloatArrayElements(in_tensor, nullptr);
    jfloat* pcm_ptr = env->GetFloatArrayElements(out_stereo_pcm, nullptr);

    engine->inverse(tensor_ptr, pcm_ptr, num_bins);

    env->ReleaseFloatArrayElements(in_tensor, tensor_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(out_stereo_pcm, pcm_ptr, 0);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeCenterCutTensor(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray tensor, jint num_bins) {
    auto* engine = reinterpret_cast<sing::StftEngine*>(handle);
    if (!engine || num_bins <= 0 || num_bins > sing::MAX_N_BINS ||
        env->GetArrayLength(tensor) < 4 * num_bins * sing::N_FRAMES) return;
    jfloat* data = env->GetFloatArrayElements(tensor, nullptr);
    engine->centerCutTensor(data, num_bins);
    env->ReleaseFloatArrayElements(tensor, data, 0);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeCenterCutPcm(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray pcm,
        jfloatArray out, jint frames) {
    auto* engine = reinterpret_cast<sing::StftEngine*>(handle);
    if (!engine || frames <= 1 || env->GetArrayLength(pcm) < frames * 2 ||
        env->GetArrayLength(out) < frames * 2) return;
    jfloat* input = env->GetFloatArrayElements(pcm, nullptr);
    jfloat* output = env->GetFloatArrayElements(out, nullptr);
    engine->centerCutPcm(input, output, frames);
    env->ReleaseFloatArrayElements(pcm, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(out, output, 0);
}

JNIEXPORT jlong JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeResamplerCreate(
        JNIEnv* /*env*/, jclass /*clazz*/, jint fromRate, jint toRate) {
    auto* resampler = new sing::SincResampler(fromRate, toRate);
    return reinterpret_cast<jlong>(resampler);
}

JNIEXPORT jint JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeResamplerProcess(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray inPcm, jint inFrames, jfloatArray outPcm) {
    auto* resampler = reinterpret_cast<sing::SincResampler*>(handle);
    if (!resampler || inFrames <= 0) return 0;

    jfloat* in_ptr = env->GetFloatArrayElements(inPcm, nullptr);
    jfloat* out_ptr = env->GetFloatArrayElements(outPcm, nullptr);

    int out_frames = resampler->resample(in_ptr, inFrames, out_ptr);

    env->ReleaseFloatArrayElements(inPcm, in_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outPcm, out_ptr, 0);

    return out_frames;
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeResamplerReset(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* resampler = reinterpret_cast<sing::SincResampler*>(handle);
    if (resampler) {
        resampler->reset();
    }
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_StftEngine_nativeResamplerDestroy(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* resampler = reinterpret_cast<sing::SincResampler*>(handle);
    delete resampler;
}

// VrDspEngine JNI Bindings

JNIEXPORT jlong JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/, jint windowFrames) {
    auto* engine = new sing::VrDspEngine(windowFrames);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<sing::VrDspEngine*>(handle);
    delete engine;
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeReset(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<sing::VrDspEngine*>(handle);
    if (engine) engine->reset();
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeSetWindowFrames(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint windowFrames) {
    auto* engine = reinterpret_cast<sing::VrDspEngine*>(handle);
    if (engine) engine->setWindowFrames(windowFrames);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeForward(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray stereoPcm, jfloatArray outTensor) {
    auto* engine = reinterpret_cast<sing::VrDspEngine*>(handle);
    if (!engine) return;

    jfloat* pcm_ptr = env->GetFloatArrayElements(stereoPcm, nullptr);
    jfloat* tensor_ptr = env->GetFloatArrayElements(outTensor, nullptr);

    engine->forward(pcm_ptr, tensor_ptr);

    env->ReleaseFloatArrayElements(stereoPcm, pcm_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outTensor, tensor_ptr, 0);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_VrDspEngine_nativeInverse(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray inMaskTensor, jfloatArray outStereoPcm, jboolean isInstrumental) {
    auto* engine = reinterpret_cast<sing::VrDspEngine*>(handle);
    if (!engine) return;

    jfloat* tensor_ptr = env->GetFloatArrayElements(inMaskTensor, nullptr);
    jfloat* pcm_ptr = env->GetFloatArrayElements(outStereoPcm, nullptr);

    engine->inverse(tensor_ptr, pcm_ptr, isInstrumental);

    env->ReleaseFloatArrayElements(inMaskTensor, tensor_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outStereoPcm, pcm_ptr, 0);
}

} // extern "C"
