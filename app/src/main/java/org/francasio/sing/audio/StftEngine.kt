package org.francasio.sing.audio

class StftEngine : AutoCloseable {

    companion object {
        const val N_FFT = 6144
        const val N_BINS = 2048
        const val MAX_N_BINS = 3072
        const val HOP_LENGTH = 1024
        const val N_FRAMES = 256
        const val TOTAL_SAMPLES = (N_FRAMES - 1) * HOP_LENGTH // 255 * 1024 = 261,120 samples (~5.92s @ 44.1kHz)
        const val TENSOR_SIZE = 4 * N_BINS * N_FRAMES     // 2,097,152 floats (dim_f = 2048)
        const val MAX_TENSOR_SIZE = 4 * MAX_N_BINS * N_FRAMES // 3,145,728 floats (dim_f = 3072)

        init {
            try {
                System.loadLibrary("sing_dsp")
            } catch (e: UnsatisfiedLinkError) {
                var loaded = false
                val apkPath = org.francasio.sing.provider.SingSettings.resolvedModulePath
                if (!apkPath.isNullOrEmpty()) {
                    try {
                        val apkFile = java.io.File(apkPath)
                        if (apkFile.exists()) {
                            java.util.zip.ZipFile(apkFile).use { zip ->
                                val abis = android.os.Build.SUPPORTED_ABIS
                                var matchEntry: java.util.zip.ZipEntry? = null
                                for (abi in abis) {
                                    val entry = zip.getEntry("lib/$abi/libsing_dsp.so")
                                    if (entry != null) {
                                        matchEntry = entry
                                        break
                                    }
                                }
                                if (matchEntry != null) {
                                    val tempLib = java.io.File.createTempFile("libsing_dsp", ".so")
                                    tempLib.deleteOnExit()
                                    zip.getInputStream(matchEntry).use { input ->
                                        java.io.FileOutputStream(tempLib).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    System.load(tempLib.absolutePath)
                                    loaded = true
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (!loaded) throw e
            }
        }

        @JvmStatic
        external fun nativeResamplerCreate(fromRate: Int, toRate: Int): Long

        @JvmStatic
        external fun nativeResamplerProcess(handle: Long, inPcm: FloatArray, inFrames: Int, outPcm: FloatArray): Int

        @JvmStatic
        external fun nativeResamplerReset(handle: Long)

        @JvmStatic
        external fun nativeResamplerDestroy(handle: Long)
    }

    class NativeSincResampler(fromRate: Int, toRate: Int) : AutoCloseable {
        private var handle: Long = nativeResamplerCreate(fromRate, toRate)

        fun resample(inFloats: FloatArray, inFrames: Int, outFloats: FloatArray): Int {
            check(handle != 0L) { "NativeSincResampler is closed" }
            return nativeResamplerProcess(handle, inFloats, inFrames, outFloats)
        }

        fun reset() {
            if (handle != 0L) {
                nativeResamplerReset(handle)
            }
        }

        override fun close() {
            if (handle != 0L) {
                nativeResamplerDestroy(handle)
                handle = 0L
            }
        }
    }

    private var nativeHandle: Long = nativeInit()

    fun forward(stereoPcm: FloatArray, outTensor: FloatArray, numBins: Int = N_BINS) {
        check(nativeHandle != 0L) { "StftEngine is closed" }
        require(stereoPcm.size >= TOTAL_SAMPLES * 2)
        require(outTensor.size >= 4 * numBins * N_FRAMES)
        nativeForward(nativeHandle, stereoPcm, outTensor, numBins)
    }

    fun inverse(inTensor: FloatArray, outStereoPcm: FloatArray, numBins: Int = N_BINS) {
        check(nativeHandle != 0L) { "StftEngine is closed" }
        require(inTensor.size >= 4 * numBins * N_FRAMES)
        require(outStereoPcm.size >= TOTAL_SAMPLES * 2)
        nativeInverse(nativeHandle, inTensor, outStereoPcm, numBins)
    }

    fun centerCutTensor(tensor: FloatArray, numBins: Int) {
        check(nativeHandle != 0L) { "StftEngine is closed" }
        require(tensor.size >= 4 * numBins * N_FRAMES)
        nativeCenterCutTensor(nativeHandle, tensor, numBins)
    }

    fun centerCutPcm(pcm: FloatArray, out: FloatArray, frames: Int) {
        check(nativeHandle != 0L) { "StftEngine is closed" }
        require(frames > 1 && pcm.size >= frames * 2 && out.size >= frames * 2)
        nativeCenterCutPcm(nativeHandle, pcm, out, frames)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeForward(handle: Long, stereoPcm: FloatArray, outTensor: FloatArray, numBins: Int)
    private external fun nativeInverse(handle: Long, inTensor: FloatArray, outStereoPcm: FloatArray, numBins: Int)
    private external fun nativeCenterCutTensor(handle: Long, tensor: FloatArray, numBins: Int)
    private external fun nativeCenterCutPcm(handle: Long, pcm: FloatArray, out: FloatArray, frames: Int)
}
