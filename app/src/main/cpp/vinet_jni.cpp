// JNI bridge for the Vi-NNet native engine (libsing_dsp).
// Exposes create/process/reset/destroy to NativeInferenceBackend.kt.
//
// The engine itself lives in vinet.cpp (compiled plan interpreter); we include
// it directly with VINET_NO_MAIN so the CLI entry point is skipped.
#define VINET_NO_MAIN
#include "vinet.cpp"

#include <jni.h>
#include <android/log.h>
#include <new>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <unistd.h>

namespace {

struct VinetHandle {
  Vinet v;
  // 64-byte aligned planar buffers for NEON/AVX-friendly loads and no false
  // sharing between the conversion and inference kernels.
  alignas(64) float planar_in[8192];   // [2, blockSize] planar, L then R
  alignas(64) float planar_out[8192];
};

}  // namespace

#define VLOG(...) __android_log_print(ANDROID_LOG_INFO,  "AppleSing_Vinet", __VA_ARGS__)
#define VERR(...) __android_log_print(ANDROID_LOG_ERROR, "AppleSing_Vinet", __VA_ARGS__)


namespace {
std::vector<int> detect_performance_cores(int want) {
  std::vector<std::pair<int,int>> found;
  for (int i=0;i<128;i++) {
    std::ostringstream p;
    p << "/sys/devices/system/cpu/cpu" << i << "/cpufreq/cpuinfo_max_freq";
    std::ifstream f(p.str());
    int k=0;
    if (f >> k) found.emplace_back(k,i);
  }
  if (found.empty()) return {};
  std::sort(found.begin(), found.end(), std::greater<std::pair<int,int>>());
  std::vector<int> res;
  int top = found.front().first;
  for (auto &pr : found) {
    if ((int)res.size() >= want) break;
    // keep performance-ish cores, dropping obvious efficiency tails when recognizable
    if (res.empty() || pr.first >= (int)(top*0.75)) res.push_back(pr.second);
  }
  if ((int)res.size() < want && !found.empty()) {
    while ((int)res.size()<want) {
      int cand = found[res.size()%found.size()].second;
      if (std::find(res.begin(),res.end(),cand)==res.end()) res.push_back(cand);
      else break;
    }
  }
  return res;
}
}
extern "C" {

JNIEXPORT jlong JNICALL
Java_org_francasio_sing_audio_backend_NativeInferenceBackend_nativeCreate(
    JNIEnv* env, jobject thiz, jstring jpath, jint threads) {
  (void)thiz;
  if (jpath == nullptr) return 0;
  const char* path = env->GetStringUTFChars(jpath, nullptr);
  if (path == nullptr) return 0;

  auto* h = new (std::nothrow) VinetHandle();
  if (h == nullptr) {
    env->ReleaseStringUTFChars(jpath, path);
    return 0;
  }

  const bool ok = h->v.load(path);
  env->ReleaseStringUTFChars(jpath, path);
  if (!ok) {
    VERR("failed to load plan: %s", path);
    delete h;
    return 0;
  }

  int n = (int)threads;
  if (n < 1) n = 1;
  auto perf = detect_performance_cores(8);
  if (perf.empty()) {
    for (int i=0;i<n && i<8;i++) perf.push_back(i);
  }
  if ((int)perf.size() < n) n = (int)perf.size();
  if (n < 1) n = 1;
  if (n > 8) n = 8;
  if ((int)perf.size() > n) perf.resize(n);
  h->v.init_threads(n - 1, perf);
  memset(h->planar_in, 0, sizeof(h->planar_in));
  memset(h->planar_out, 0, sizeof(h->planar_out));
  h->v.reset_states();
  VLOG("engine created: %d threads, %d slots, %d ops", n, (int)h->v.slot.size(),
       (int)h->v.ops.size());
  return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_backend_NativeInferenceBackend_nativeProcess(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray jin, jfloatArray jout,
    jint frameCount, jint blockSize) {
  (void)thiz;
  auto* h = (VinetHandle*)(intptr_t)handle;
  if (h == nullptr) return;

  int bs = blockSize > 0 ? blockSize : 4096;
  if (bs > 4096) bs = 4096; // VinetHandle planar buffers are sized for 4096 frames
  int n = (int)frameCount;
  if (n > bs) n = bs;
  if (n < 0) n = 0;

  // interleaved stereo input -> planar [L(bs), R(bs)]
  jfloat* src = env->GetFloatArrayElements(jin, nullptr);
  if (src == nullptr) return;
  float* pl = h->planar_in;
  float* pr = pl + bs;
  for (int i = 0; i < n; i++) {
    pl[i] = src[2 * i];
    pr[i] = src[2 * i + 1];
  }
  for (int i = n; i < bs; i++) {
    pl[i] = 0.f;
    pr[i] = 0.f;
  }
  env->ReleaseFloatArrayElements(jin, src, JNI_ABORT);

  h->v.run_block(h->planar_in, h->planar_out);

  // planar output -> interleaved stereo
  jfloat* dst = env->GetFloatArrayElements(jout, nullptr);
  if (dst == nullptr) return;
  const float* ol = h->planar_out;
  const float* orr = ol + bs;
  for (int i = 0; i < n; i++) {
    dst[2 * i] = ol[i];
    dst[2 * i + 1] = orr[i];
  }
  env->ReleaseFloatArrayElements(jout, dst, 0);
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_backend_NativeInferenceBackend_nativeReset(
    JNIEnv* env, jobject thiz, jlong handle) {
  (void)env;
  (void)thiz;
  auto* h = (VinetHandle*)(intptr_t)handle;
  if (h != nullptr) h->v.reset_states();
}

JNIEXPORT void JNICALL
Java_org_francasio_sing_audio_backend_NativeInferenceBackend_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle) {
  (void)env;
  (void)thiz;
  auto* h = (VinetHandle*)(intptr_t)handle;
  if (h != nullptr) {
    VLOG("engine destroyed");
    delete h;   // Vinet destructor joins the worker pool
  }
}

// Java has no setenv(); the QNN/FastRPC stack reads its search paths from the
// process environment (ADSP_LIBRARY_PATH etc.), so expose it here.
JNIEXPORT jboolean JNICALL
Java_org_francasio_sing_util_NativeEnv_setenv(
    JNIEnv* env, jclass, jstring name, jstring value) {
  if (name == nullptr || value == nullptr) return JNI_FALSE;
  const char* n = env->GetStringUTFChars(name, nullptr);
  const char* v = env->GetStringUTFChars(value, nullptr);
  jboolean ok = JNI_FALSE;
  if (n != nullptr && v != nullptr && setenv(n, v, 1) == 0) ok = JNI_TRUE;
  if (n != nullptr) env->ReleaseStringUTFChars(name, n);
  if (v != nullptr) env->ReleaseStringUTFChars(value, v);
  return ok;
}

// Java has no chdir(); used only in debug mode so that libraries which write
// diagnostics relative to the process working directory (e.g. the QNN profiler)
// land inside the app cache dir.
JNIEXPORT jboolean JNICALL
Java_org_francasio_sing_util_NativeEnv_chdir(
    JNIEnv* env, jclass, jstring path) {
  if (path == nullptr) return JNI_FALSE;
  const char* p = env->GetStringUTFChars(path, nullptr);
  jboolean ok = JNI_FALSE;
  if (p != nullptr && chdir(p) == 0) ok = JNI_TRUE;
  if (p != nullptr) env->ReleaseStringUTFChars(path, p);
  return ok;
}

}  // extern "C"
