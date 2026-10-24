package org.francasio.sing.audio.backend

import android.util.Log
import org.francasio.sing.util.ModelLoader
import java.io.File

/**
 * Native Vi-NNet inference backend.
 *
 * Runs the purpose-built C++ engine (compiled plan + persistent state buffers,
 * NEON FMA kernels, multithreaded split over big cores). The compiled plan
 * ("*.vinet") is extracted from the module APK assets by [ModelLoader].
 *
 * Audio format: interleaved stereo float PCM per call, 4096 frames per block.
 */
class NativeInferenceBackend(
    modelFile: File,
    threads: Int,
    override val modelInfo: ModelLoader.ModelInfo
) : InferenceBackend {

    companion object {
        private const val TAG = "AppleSing_Native"
        private const val CHANNELS = 2

        init {
            try {
                System.loadLibrary("sing_dsp")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libsing_dsp.so: ${t.message}")
            }
        }
    }

    override val providerName: String
    override val shortProviderName: String
    override val numBins: Int = modelInfo.numBins

    private var handle: Long = 0L
    private val blockSize: Int = modelInfo.blockSize

    init {
        Log.i(TAG, "Initializing native Vi-NNet engine (${modelFile.name}, " +
                "${modelFile.length() / 1024 / 1024} MB, threads=$threads)...")
        if (modelInfo.blockSize !in 1..4096) {
            throw IllegalStateException("Native Vi-NNet requires blockSize in 1..4096, got ${modelInfo.blockSize}")
        }
        handle = nativeCreate(modelFile.absolutePath, threads)
        if (handle == 0L) {
            throw IllegalStateException(
                "Native Vi-NNet engine failed to load ${modelFile.absolutePath}"
            )
        }
        providerName = "Native Vi-NNet FP32 ($threads threads, NEON)"
        shortProviderName = "Native NEON, $threads threads"
        Log.i(TAG, "Native Vi-NNet engine ready: $providerName (block=$blockSize)")
    }

    @Synchronized
    override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
        val h = handle
        if (h == 0L) throw IllegalStateException("Native Vi-NNet engine is closed")
        nativeProcess(h, inPcm, outPcm, frameCount, blockSize)
    }

    @Synchronized
    override fun resetStates() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                nativeDestroy(h)
            } catch (t: Throwable) {
                Log.w(TAG, "Error destroying native engine: ${t.message}")
            }
        }
    }

    private external fun nativeCreate(path: String, threads: Int): Long
    private external fun nativeProcess(
        handle: Long,
        inPcm: FloatArray,
        outPcm: FloatArray,
        frameCount: Int,
        blockSize: Int
    )
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
