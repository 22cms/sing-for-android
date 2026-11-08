#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdio>

namespace {

struct FastRpcDspCapability {
  uint32_t domain;
  uint32_t attribute_ID;
  uint32_t capability;
};

typedef int (*fn_remote_handle_control)(uint32_t req, void* data, uint32_t datalen);

}  // namespace

extern "C" {

// Direct FastRPC query to CDSP for HEXAGON_CPU_ARCH.
// Uses the vendor FastRPC client library (libcdsprpc.so) present on all Qualcomm devices.
JNIEXPORT jstring JNICALL
Java_org_francasio_sing_util_NativeEnv_detectHexagonArch(JNIEnv* env, jclass) {
  void* handle = dlopen("libcdsprpc.so", RTLD_NOW);
  if (!handle) {
    handle = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW);
  }
  if (!handle) {
    return nullptr;
  }

  auto ctrl = (fn_remote_handle_control)dlsym(handle, "remote_handle_control");
  if (!ctrl) {
    ctrl = (fn_remote_handle_control)dlsym(handle, "remote_handle64_control");
  }
  if (!ctrl) {
    dlclose(handle);
    return nullptr;
  }

  // domain = 3 (CDSP_DOMAIN_ID), attribute_ID = 6 (ARCH_VER)
  FastRpcDspCapability cap = { 3, 6, 0 };
  // req = 2 (DSPRPC_GET_DSP_INFO)
  int ret = ctrl(2, &cap, sizeof(cap));
  dlclose(handle);

  if (ret != 0 || cap.capability == 0) {
    return nullptr;
  }

  uint32_t arch = cap.capability & 0xFF;
  if (arch == 0) return nullptr;

  char buf[16];
  snprintf(buf, sizeof(buf), "V%X", arch);
  return env->NewStringUTF(buf);
}

}  // extern "C"
