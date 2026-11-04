package org.francasio.sing.audio

import org.francasio.sing.audio.backend.InferenceBackend
import kotlin.math.sin

/** Benchmarks the same model-specific DSP and overlap-adjusted step used by playback. */
internal object SpectralBenchmark {
    data class Result(
        val totalMs: Long, val forwardMs: Long, val inferenceMs: Long, val inverseMs: Long,
        val rtf: Double, val audioFrames: Int
    )

    fun run(backend: InferenceBackend): Result {
        val model = backend.modelInfo
        val stft = StftEngine()
        var vr: VrDspEngine? = null
        try {
            if (model.isVr3Band) vr = VrDspEngine()
            else stft.configure(model.fftSize, model.compensation, model.isInstrumental)
            val frames = vr?.chunkSamples ?: StftEngine.TOTAL_SAMPLES
            val input = FloatArray(frames * 2) { i ->
                (0.5 * sin(2.0 * Math.PI * 440.0 * (i / 2) / 44100.0)).toFloat()
            }
            val bins = backend.numBins
            val tensor = FloatArray(vr?.inputTensorSize ?: (4 * bins * StftEngine.N_FRAMES))
            val prediction = FloatArray(vr?.outputTensorSize ?: tensor.size)
            val output = FloatArray((vr?.roiSamples ?: frames) * 2)
            fun forward() {
                if (vr != null) vr.forward(input, tensor) else stft.forward(input, tensor, bins)
            }
            fun inverse() {
                if (vr != null) vr.inverse(prediction, output, isInstrumental = false)
                else stft.inverse(prediction, output, bins)
            }
            forward()
            backend.run(tensor, prediction)
            inverse()
            var forwardNs = 0L
            var inferenceNs = 0L
            var inverseNs = 0L
            val iterations = 3
            repeat(iterations) {
                val start = System.nanoTime()
                forward()
                val afterForward = System.nanoTime()
                backend.run(tensor, prediction)
                val afterInference = System.nanoTime()
                inverse()
                val end = System.nanoTime()
                forwardNs += afterForward - start
                inferenceNs += afterInference - afterForward
                inverseNs += end - afterInference
            }
            val averageNs = (forwardNs + inferenceNs + inverseNs).toDouble() / iterations
            val hop = vr?.let { VrChunking.hopSamples(it.windowFrames) }
                ?: AiStemSeparationEngine.HOP_SAMPLES
            return Result(
                (averageNs / 1_000_000).toLong(), forwardNs / iterations / 1_000_000,
                inferenceNs / iterations / 1_000_000, inverseNs / iterations / 1_000_000,
                (hop / 44100.0) / (averageNs.coerceAtLeast(1.0) / 1_000_000_000.0), hop
            )
        } finally {
            vr?.close()
            stft.close()
        }
    }
}
