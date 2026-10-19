package org.francasio.sing.audio.backend

import org.francasio.sing.util.ModelLoader

interface InferenceBackend : AutoCloseable {
    val providerName: String
    val shortProviderName: String
    val modelInfo: ModelLoader.ModelInfo

    val numBins: Int
        get() = modelInfo.numBins

    fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
        throw UnsupportedOperationException("STFT inference not supported by this backend")
    }

    fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
        throw UnsupportedOperationException("Time-domain inference not supported by this backend")
    }

    fun resetStates() {}
}

