// Minimal stand-in for libdl_android.so, used only when the device's real
// library cannot be loaded. The real one declares a DT_NEEDED on ld-android.so
// (the dynamic linker itself), which bionic special-cases; if that ever fails
// from an app namespace, this stub still satisfies the single import made by
// libvndksupport, android_get_exported_namespace@LIBDL_ANDROID. Returning NULL
// means "no such namespace", which makes android_load_sphal_library() fail
// cleanly - those calls only load sphal HAL libraries and are never reached by
// the QNN FastRPC path.
#include <stddef.h>

__attribute__((visibility("default")))
void* android_get_exported_namespace(const char* name) {
    (void)name;
    return NULL;
}
