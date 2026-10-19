package org.francasio.sing.util

/**
 * JNI bridge for process environment variables (Java has no setenv()).
 *
 * The FastRPC transport inside the QNN stack reads its library search paths
 * from the process environment (ADSP_LIBRARY_PATH, ";"-separated). Those paths
 * are how DSP-side libraries - QNN "Skel" binaries like libQnnHtpV75Skel.so -
 * are located on the host filesystem and streamed to the DSP.
 */
object NativeEnv {
    external fun setenv(name: String, value: String): Boolean
    external fun chdir(path: String): Boolean
}
