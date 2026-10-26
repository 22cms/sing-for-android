package org.francasio.sing.audio

class VrDspEngine(windowFrames: Int = 512) : AutoCloseable {

    companion object {
        const val VR_N_FFT = 768
        const val VR_TOTAL_BINS = 769
        const val OFFSET_FRAMES = 128

        init {
            try {
                System.loadLibrary("sing_dsp")
            } catch (e: UnsatisfiedLinkError) {
                // sing_dsp already loaded or will be loaded via StftEngine fallback
            }
        }

        @JvmStatic
        private external fun nativeInit(windowFrames: Int): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeSetWindowFrames(handle: Long, windowFrames: Int)

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeForward(handle: Long, stereoPcm: FloatArray, outTensor: FloatArray)

        @JvmStatic
        private external fun nativeInverse(handle: Long, inMaskTensor: FloatArray, outStereoPcm: FloatArray, isInstrumental: Boolean)
    }

    private var nativeHandle: Long = nativeInit(windowFrames)
    var windowFrames: Int = windowFrames
        private set

    val roiFrames: Int
        get() = windowFrames - 2 * OFFSET_FRAMES

    val chunkSamples: Int
        get() = (windowFrames - 1) * 512

    val roiSamples: Int
        get() = roiFrames * 512

    val validRoiSamples: Int
        get() = (roiFrames - 1) * 512

    val inputTensorSize: Int
        get() = 2 * VR_TOTAL_BINS * windowFrames

    val outputTensorSize: Int
        get() = 2 * VR_TOTAL_BINS * roiFrames

    fun setWindowSize(frames: Int) {
        check(nativeHandle != 0L) { "VrDspEngine is closed" }
        windowFrames = frames
        nativeSetWindowFrames(nativeHandle, frames)
    }

    fun forward(stereoPcm: FloatArray, outTensor: FloatArray) {
        check(nativeHandle != 0L) { "VrDspEngine is closed" }
        require(stereoPcm.size >= chunkSamples * 2)
        require(outTensor.size >= inputTensorSize)
        nativeForward(nativeHandle, stereoPcm, outTensor)
    }

    fun inverse(inMaskTensor: FloatArray, outStereoPcm: FloatArray, isInstrumental: Boolean = false) {
        check(nativeHandle != 0L) { "VrDspEngine is closed" }
        require(inMaskTensor.size >= outputTensorSize)
        require(outStereoPcm.size >= roiSamples * 2)
        nativeInverse(nativeHandle, inMaskTensor, outStereoPcm, isInstrumental)
    }

    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
}
