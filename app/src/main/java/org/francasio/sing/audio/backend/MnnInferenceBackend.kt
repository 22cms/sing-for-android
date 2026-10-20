package org.francasio.sing.audio.backend

import android.util.Log
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ModelLoader
import java.io.File

class MnnInferenceBackend(
    modelFile: File,
    threads: Int,
    val target: String,
    override val modelInfo: ModelLoader.ModelInfo,
    backendPreference: Int = targetToPreference(target)
) : InferenceBackend {

    companion object {
        private const val TAG = "AppleSing_MNN"
        const val BACKEND_AUTO = 0
        const val BACKEND_NPU = 1
        const val BACKEND_GPU = 2
        const val BACKEND_NNAPI = 3
        const val BACKEND_CPU = 4

        @Volatile
        var currentBackendPreference: Int = BACKEND_AUTO

        fun targetToPreference(target: String): Int = when (target) {
            SingSettings.TARGET_NPU -> BACKEND_NPU
            SingSettings.TARGET_GPU -> BACKEND_GPU
            SingSettings.TARGET_NNAPI -> BACKEND_NNAPI
            SingSettings.TARGET_CPU -> BACKEND_CPU
            else -> BACKEND_AUTO
        }

        init {
            try { System.loadLibrary("MNN"); Log.i(TAG, "Loaded libMNN.so") }
            catch (t: Throwable) { Log.e(TAG, "Failed to load libMNN.so: ${t.message}") }
        }
    }

    override val providerName: String
    override val shortProviderName: String
    private var handle: Long = 0L

    init {
        val pref = if (backendPreference != BACKEND_AUTO) backendPreference else currentBackendPreference
        val isFp32 = modelInfo.precision.equals("FP32", ignoreCase = true) || modelFile.name.contains("fp32", ignoreCase = true)
        Log.i(TAG, "Initializing MNN backend (${modelFile.name}, threads=$threads, target=$target, pref=$pref, isFp32=$isFp32)...")
        handle = nativeCreate(modelFile.absolutePath, threads, pref, isFp32)
        if (handle == 0L) throw IllegalStateException("MNN failed to load ${modelFile.absolutePath}")
        val backend = nativeBackendName(handle)
        val isHardware = backend != "CPU" && !backend.startsWith("CPU")
        providerName = if (isHardware) {
            "${modelInfo.precision} via MNN $backend"
        } else {
            "${modelInfo.precision} via MNN $backend ($threads threads)"
        }
        shortProviderName = if (isHardware) {
            "MNN $backend"
        } else {
            "MNN $backend, $threads threads"
        }
        Log.i(TAG, "MNN backend ready: $shortProviderName")
    }

    override fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
        if (handle == 0L) throw IllegalStateException("MNN engine closed")
        if (!nativeRun(handle, stftInput, vocalOutput))
            throw IllegalStateException("MNN run failed")
    }

    override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
        throw UnsupportedOperationException("MNN time-domain not implemented")
    }

    override fun close() {
        val h = handle; handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    private external fun nativeCreate(path: String, threads: Int, backendPref: Int, isFp32: Boolean): Long
    private external fun nativeBackendName(handle: Long): String
    private external fun nativeRun(handle: Long, input: FloatArray, output: FloatArray): Boolean
    private external fun nativeDestroy(handle: Long)
}

