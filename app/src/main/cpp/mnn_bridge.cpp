#define MNN_NO_MAIN
#include "MNN/Interpreter.hpp"
#include "MNN/Tensor.hpp"
#include "MNN/MNNQnnBackend.h"
#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <dlfcn.h>

#define MLOG(...) __android_log_print(ANDROID_LOG_INFO, "AppleSing_MNN", __VA_ARGS__)

static bool testSession(MNN::Interpreter* interp, MNN::Session* sess) {
    if (!interp || !sess) return false;
    try {
        auto* in = interp->getSessionInput(sess, nullptr);
        if (in) {
            auto dims = in->shape();
            bool hasDynamic = false;
            for (int d : dims) if (d <= 0) hasDynamic = true;
            if (hasDynamic && dims.size() == 4) {
                int b = dims[0] > 0 ? dims[0] : 1;
                int c = dims[1] > 0 ? dims[1] : 2;
                int f = dims[2] > 0 ? dims[2] : 769;
                dims = {b, c, f, 512};
                interp->resizeTensor(in, dims);
                interp->resizeSession(sess);
            }
        }
        int rc = interp->runSession(sess);
        if (rc != 0) {
            MLOG("MNN test run failed with rc=%d", rc);
            return false;
        }
        return true;
    } catch (...) {
        MLOG("MNN test run threw exception");
        return false;
    }
}

struct MnnHandle {
    MNN::Interpreter* interp = nullptr;
    MNN::Session* sess = nullptr;
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
    std::vector<int> inDims;
    std::vector<int> outDims;
    int threads = 4;
    std::string backendTag = "CPU";
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_francasio_sing_audio_backend_MnnInferenceBackend_nativeCreate(
    JNIEnv* env, jobject thiz, jstring jpath, jint threads, jint backendPref, jboolean isFp32) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    auto* h = new (std::nothrow) MnnHandle();
    if (!h) { env->ReleaseStringUTFChars(jpath, path); return 0; }
    h->interp = MNN::Interpreter::createFromFile(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!h->interp) { delete h; return 0; }
    h->threads = threads > 0 ? threads : 4;
    MNN::BackendConfig::PrecisionMode targetPrecision = isFp32 ?
        MNN::BackendConfig::Precision_Normal : MNN::BackendConfig::Precision_Low;

    // Ensure ADSP_LIBRARY_PATH is populated for Qualcomm DSP / Hexagon HTP
    const char* activeQnnDir = nullptr;
    const char* adsp = getenv("ADSP_LIBRARY_PATH");
    const char* candidatePaths[] = {
        "/data/data/com.apple.android.music/cache/ort_libs_arm64-v8a",
        "/data/user/0/com.apple.android.music/cache/ort_libs_arm64-v8a",
        "/data/data/com.apple.android.music/cache/ort_libs_arm64",
        "/data/user/0/com.apple.android.music/cache/ort_libs_arm64",
        "/data/data/com.apple.android.music/cache/qnn_libs_arm64",
        "/data/user/0/com.apple.android.music/cache/qnn_libs_arm64",
        nullptr
    };
    for (int i = 0; candidatePaths[i]; i++) {
        if (access(candidatePaths[i], R_OK) == 0) {
            activeQnnDir = candidatePaths[i];
            if (!adsp || strlen(adsp) == 0) {
                setenv("ADSP_LIBRARY_PATH", candidatePaths[i], 1);
                setenv("DSP_LIBRARY_PATH", candidatePaths[i], 1);
                MLOG("Set ADSP_LIBRARY_PATH to %s", candidatePaths[i]);
            }
            break;
        }
    }

    // Preload libQnnHtp.so if available
    void* qnnHtpHandle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
    if (!qnnHtpHandle) {
        const char* qnnHtpPaths[] = {
            "/data/data/com.apple.android.music/cache/ort_libs_arm64-v8a/libQnnHtp.so",
            "/data/user/0/com.apple.android.music/cache/ort_libs_arm64-v8a/libQnnHtp.so",
            nullptr
        };
        for (int i = 0; qnnHtpPaths[i]; i++) {
            if (access(qnnHtpPaths[i], R_OK) == 0) {
                qnnHtpHandle = dlopen(qnnHtpPaths[i], RTLD_NOW | RTLD_GLOBAL);
                if (qnnHtpHandle) {
                    MLOG("Preloaded %s for MNN QNN backend", qnnHtpPaths[i]);
                    break;
                }
            }
        }
    }

    // Preload OpenCL if available
    const char* openclPaths[] = {
        "libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        nullptr
    };
    for (int i = 0; openclPaths[i]; i++) {
        if (access(openclPaths[i], R_OK) == 0 || i == 0) {
            void* clHandle = dlopen(openclPaths[i], RTLD_NOW | RTLD_GLOBAL);
            if (clHandle) {
                MLOG("Preloaded OpenCL from %s", openclPaths[i]);
                break;
            }
        }
    }

    bool allowQnn = (backendPref == 0 || backendPref == 1);
    bool allowOpenCL = (backendPref == 0 || backendPref == 2);
    bool allowVulkan = (backendPref == 0 || backendPref == 2);
    bool allowNnapi = (backendPref == 0 || backendPref == 1 || backendPref == 3);

    if (allowQnn && !h->sess) {
        // 1. Try Qualcomm QNN (Hexagon HTP NPU)
        try {
            MNN::ScheduleConfig qcfg;
            qcfg.type = MNN_FORWARD_QNN;
            qcfg.numThread = h->threads;
            qcfg.backupType = MNN_FORWARD_CPU;
            MNN::BackendConfig bcfg;
            bcfg.precision = MNN::BackendConfig::Precision_Low;
            bcfg.power = MNN::BackendConfig::Power_High;

            MNNQnnBackendConfigV1 qnnOpt;
            memset(&qnnOpt, 0, sizeof(qnnOpt));
            qnnOpt.structSize = sizeof(MNNQnnBackendConfigV1);
            qnnOpt.version = MNN_QNN_CONFIG_VERSION;
            qnnOpt.runtime = MNN_QNN_RUNTIME_HTP;
            if (activeQnnDir) {
                qnnOpt.runtimeLibraryDirectory = activeQnnDir;
            }
            bcfg.sharedContext = &qnnOpt;
            qcfg.backendConfig = &bcfg;

            h->sess = h->interp->createSession(qcfg);
            if (h->sess) {
                int backends[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
                if (h->interp->getSessionInfo(h->sess, MNN::Interpreter::BACKENDS, backends) && backends[0] == MNN_FORWARD_QNN) {
                    if (testSession(h->interp, h->sess)) {
                        h->backendTag = "QNN (Hexagon HTP NPU)";
                        MLOG("MNN session verified with Qualcomm QNN backend (HTP runtime)");
                    } else {
                        MLOG("MNN QNN session test run failed; rejecting");
                        h->interp->releaseSession(h->sess);
                        h->sess = nullptr;
                    }
                } else {
                    MLOG("MNN QNN session creation did not resolve to QNN (got backend=%d); rejecting", backends[0]);
                    h->interp->releaseSession(h->sess);
                    h->sess = nullptr;
                }
            }
        } catch (...) {
            h->sess = nullptr;
        }
    }

    // 2. Try OpenCL GPU (Qualcomm Adreno / ARM Mali)
    if (allowOpenCL && !h->sess) {
        try {
            MNN::ScheduleConfig clcfg;
            clcfg.type = MNN_FORWARD_OPENCL;
            clcfg.mode = MNN_GPU_MEMORY_IMAGE | MNN_GPU_TUNING_FAST;
            clcfg.backupType = MNN_FORWARD_CPU;
            MNN::BackendConfig bcfg;
            bcfg.precision = targetPrecision;
            bcfg.power = MNN::BackendConfig::Power_High;
            clcfg.backendConfig = &bcfg;
            h->sess = h->interp->createSession(clcfg);
            if (h->sess) {
                int backends[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
                if (h->interp->getSessionInfo(h->sess, MNN::Interpreter::BACKENDS, backends) && backends[0] == MNN_FORWARD_OPENCL) {
                    if (testSession(h->interp, h->sess)) {
                        h->backendTag = "OpenCL GPU";
                        MLOG("MNN session verified with OpenCL GPU backend");
                    } else {
                        MLOG("MNN OpenCL session test run failed; rejecting");
                        h->interp->releaseSession(h->sess);
                        h->sess = nullptr;
                    }
                } else {
                    MLOG("MNN OpenCL session creation did not resolve to OpenCL (got backend=%d); rejecting", backends[0]);
                    h->interp->releaseSession(h->sess);
                    h->sess = nullptr;
                }
            }
        } catch (...) {
            h->sess = nullptr;
        }
    }

    // 3. Try Vulkan GPU
    if (allowVulkan && !h->sess) {
        try {
            MNN::ScheduleConfig vkcfg;
            vkcfg.type = MNN_FORWARD_VULKAN;
            vkcfg.mode = MNN_GPU_TUNING_WIDE | MNN_GPU_RECORD_BATCH;
            vkcfg.backupType = MNN_FORWARD_CPU;
            MNN::BackendConfig bcfg;
            bcfg.precision = targetPrecision;
            bcfg.power = MNN::BackendConfig::Power_High;
            vkcfg.backendConfig = &bcfg;
            h->sess = h->interp->createSession(vkcfg);
            if (h->sess) {
                int backends[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
                if (h->interp->getSessionInfo(h->sess, MNN::Interpreter::BACKENDS, backends) && backends[0] == MNN_FORWARD_VULKAN) {
                    if (testSession(h->interp, h->sess)) {
                        h->backendTag = "Vulkan GPU";
                        MLOG("MNN session verified with Vulkan GPU backend");
                    } else {
                        MLOG("MNN Vulkan session test run failed; rejecting");
                        h->interp->releaseSession(h->sess);
                        h->sess = nullptr;
                    }
                } else {
                    MLOG("MNN Vulkan session creation did not resolve to Vulkan (got backend=%d); rejecting", backends[0]);
                    h->interp->releaseSession(h->sess);
                    h->sess = nullptr;
                }
            }
        } catch (...) {
            h->sess = nullptr;
        }
    }

    // 4. Try Android NNAPI
    if (allowNnapi && !h->sess) {
        try {
            MNN::ScheduleConfig nncfg;
            nncfg.type = MNN_FORWARD_NN;
            nncfg.numThread = h->threads;
            nncfg.backupType = MNN_FORWARD_CPU;
            MNN::BackendConfig bcfg;
            bcfg.precision = targetPrecision;
            bcfg.power = MNN::BackendConfig::Power_High;
            nncfg.backendConfig = &bcfg;
            h->sess = h->interp->createSession(nncfg);
            if (h->sess) {
                int backends[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
                if (h->interp->getSessionInfo(h->sess, MNN::Interpreter::BACKENDS, backends) && backends[0] == MNN_FORWARD_NN) {
                    if (testSession(h->interp, h->sess)) {
                        h->backendTag = "NNAPI";
                        MLOG("MNN session verified with NNAPI backend");
                    } else {
                        MLOG("MNN NNAPI session test run failed; rejecting and falling back");
                        h->interp->releaseSession(h->sess);
                        h->sess = nullptr;
                    }
                } else {
                    MLOG("MNN NNAPI session creation did not resolve to NNAPI (got backend=%d); rejecting", backends[0]);
                    h->interp->releaseSession(h->sess);
                    h->sess = nullptr;
                }
            }
        } catch (...) {
            h->sess = nullptr;
        }
    }

    // 5. Fallback to CPU
    if (!h->sess) {
        try {
            MNN::ScheduleConfig ccfg;
            ccfg.type = MNN_FORWARD_CPU;
            ccfg.numThread = h->threads;
            MNN::BackendConfig bcfg;
            bcfg.precision = targetPrecision;
            bcfg.power = MNN::BackendConfig::Power_High;
            ccfg.backendConfig = &bcfg;
            h->sess = h->interp->createSession(ccfg);
            if (h->sess) {
                if (backendPref == 1) {
                    h->backendTag = "CPU (NPU unavailable)";
                } else if (backendPref == 2) {
                    h->backendTag = "CPU (GPU unavailable)";
                } else if (backendPref == 3) {
                    h->backendTag = "CPU (NNAPI unavailable)";
                } else {
                    h->backendTag = "CPU";
                }
                MLOG("MNN session created with %s backend (%d threads)", h->backendTag.c_str(), h->threads);
            }
        } catch (...) {
            h->sess = nullptr;
        }
    }

    if (!h->sess) { delete h->interp; delete h; return 0; }

    // Check actual backend runtime info if available
    int backends[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
    if (h->interp->getSessionInfo(h->sess, MNN::Interpreter::BACKENDS, backends)) {
        int actualBackend = backends[0];
        MLOG("MNN getSessionInfo BACKENDS: primary=%d secondary=%d", backends[0], backends[1]);
        switch (actualBackend) {
            case MNN_FORWARD_QNN:
                h->backendTag = "QNN (Hexagon HTP NPU)";
                break;
            case MNN_FORWARD_OPENCL:
                h->backendTag = "OpenCL GPU";
                break;
            case MNN_FORWARD_VULKAN:
                h->backendTag = "Vulkan GPU";
                break;
            case MNN_FORWARD_NN:
                h->backendTag = "NNAPI";
                break;
            case MNN_FORWARD_CPU:
                if (backendPref == 1) {
                    h->backendTag = "CPU (NPU unavailable)";
                } else if (backendPref == 2) {
                    h->backendTag = "CPU (GPU unavailable)";
                } else if (backendPref == 3) {
                    h->backendTag = "CPU (NNAPI unavailable)";
                } else {
                    h->backendTag = "CPU";
                }
                break;
            default:
                break;
        }
    }

    h->input = h->interp->getSessionInput(h->sess, nullptr);
    h->output = h->interp->getSessionOutput(h->sess, nullptr);
    h->inDims = h->input->shape();

    bool hasDynamic = false;
    for (int d : h->inDims) {
        if (d <= 0) { hasDynamic = true; break; }
    }
    if (hasDynamic && h->inDims.size() == 4) {
        int b = h->inDims[0] > 0 ? h->inDims[0] : 1;
        int c = h->inDims[1] > 0 ? h->inDims[1] : 2;
        int f = h->inDims[2] > 0 ? h->inDims[2] : 769;
        std::vector<int> targetDims = {b, c, f, 512};
        h->inDims = targetDims;
        h->interp->resizeTensor(h->input, targetDims);
        h->interp->resizeSession(h->sess);
        MLOG("MNN resized dynamic input to {%d, %d, %d, 512}", b, c, f);
    }
    h->outDims = h->output->shape();

    MLOG("MNN engine created: backend=%s threads=%d in=%zu out=%zu", h->backendTag.c_str(), h->threads, h->inDims.size(), h->outDims.size());
    return (jlong)(intptr_t)h;
}

JNIEXPORT jboolean JNICALL
Java_org_francasio_sing_audio_backend_MnnInferenceBackend_nativeRun(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray jin, jfloatArray jout) {
    auto* h = (MnnHandle*)(intptr_t)handle;
    if (!h || !h->input || !h->output) return JNI_FALSE;

    jsize inLen = env->GetArrayLength(jin);
    bool hasDynamic = false;
    for (int d : h->inDims) {
        if (d <= 0) { hasDynamic = true; break; }
    }
    if (hasDynamic && h->inDims.size() == 4) {
        int b = h->inDims[0] > 0 ? h->inDims[0] : 1;
        int c = h->inDims[1] > 0 ? h->inDims[1] : 2;
        int f = h->inDims[2] > 0 ? h->inDims[2] : 769;
        int t = inLen / (b * c * f);
        if (t <= 0) t = 512;
        std::vector<int> targetDims = {b, c, f, t};
        if (h->inDims != targetDims) {
            h->inDims = targetDims;
            h->interp->resizeTensor(h->input, targetDims);
            h->interp->resizeSession(h->sess);
            h->outDims = h->output->shape();
        }
    }

    size_t inN = 1; for (int d : h->inDims) inN *= d;
    size_t outN = 1; for (int d : h->outDims) outN *= d;
    jfloat* src = env->GetFloatArrayElements(jin, nullptr);
    if (!src) return JNI_FALSE;
    halide_type_t ht; ht.code = halide_type_float; ht.bits = 32; ht.lanes = 1;
    MNN::Tensor* hostIn = MNN::Tensor::create(h->inDims, ht, src, MNN::Tensor::CAFFE);
    h->input->copyFromHostTensor(hostIn);
    delete hostIn;
    env->ReleaseFloatArrayElements(jin, src, JNI_ABORT);
    int rc = h->interp->runSession(h->sess);
    if (rc != 0) return JNI_FALSE;
    jfloat* dst = env->GetFloatArrayElements(jout, nullptr);
    if (!dst) return JNI_FALSE;
    MNN::Tensor* hostOut = MNN::Tensor::create(h->outDims, ht, dst, MNN::Tensor::CAFFE);
    h->output->copyToHostTensor(hostOut);
    delete hostOut;
    env->ReleaseFloatArrayElements(jout, dst, 0);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_org_francasio_sing_audio_backend_MnnInferenceBackend_nativeBackendName(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* h = (MnnHandle*)(intptr_t)handle;
    if (!h) return env->NewStringUTF("CPU");
    return env->NewStringUTF(h->backendTag.c_str());
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_backend_MnnInferenceBackend_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* h = (MnnHandle*)(intptr_t)handle;
    if (!h) return;
    if (h->interp) {
        if (h->sess) h->interp->releaseSession(h->sess);
        delete h->interp;
    }
    delete h;
}

} // extern C

