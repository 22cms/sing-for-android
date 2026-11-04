package org.francasio.sing

import org.francasio.sing.audio.AiStemSeparationEngine
import org.francasio.sing.audio.SpectralBenchmark
import org.francasio.sing.audio.StftEngine
import org.francasio.sing.audio.VrChunking
import org.francasio.sing.audio.VrDspEngine
import org.francasio.sing.audio.backend.InferenceBackend
import org.francasio.sing.util.ModelLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Isolates synthesis/blending errors with an identical mask across all model windows. */
class VrStreamingBoundaryTest {
    @Before fun requireNativeDsp() {
        assumeTrue(java.lang.Boolean.getBoolean("sing.native.tests"))
    }

    private class ConstantMaskBackend : InferenceBackend {
        override val providerName = "constant mask"
        override val shortProviderName = "test"
        override val modelInfo = ModelLoader.ModelInfo(
            id = "vr_test", modelId = "vr_test", modelDisplayName = "test", displayName = "test",
            targetStem = "vocal", networkType = "vr_3band", numBins = 769, numFrames = 512,
            fftSize = 768, fileName = "test.onnx", fileSizeDesc = ""
        )
        val calls = AtomicInteger()
        override fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
            calls.incrementAndGet()
            vocalOutput.fill(0.2f)
        }
        override fun close() {}
    }

    @Test fun multipleChunksAndPartialEosMatchContinuousSynthesisAtJoins() {
        val hop = VrChunking.hopSamples()
        val frames = hop * 2 + 16384
        val input = FloatArray(frames * 2) { index ->
            val t = (index / 2) / 44100.0
            val phase = if (index % 2 == 0) 0.0 else 0.4
            (0.17 * sin(2 * Math.PI * 430 * t + phase) + 0.09 * sin(2 * Math.PI * 1700 * t)).toFloat()
        }
        val window = frames / VrChunking.FRAME_HOP + 1 + VrChunking.CONTEXT_FRAMES * 2
        val reference = VrDspEngine(window).use { dsp ->
            val contextual = FloatArray(dsp.chunkSamples * 2) { index ->
                val frame = abs(index / 2 - VrChunking.CONTEXT_FRAMES * VrChunking.FRAME_HOP)
                if (frame < frames) input[frame * 2 + index % 2] else 0f
            }
            dsp.forward(contextual, FloatArray(dsp.inputTensorSize))
            FloatArray(dsp.roiSamples * 2).also {
                dsp.inverse(FloatArray(dsp.outputTensorSize) { 0.2f }, it)
            }.copyOf(frames * 2)
        }
        val referenceCenter = FloatArray(reference.size)
        StftEngine().use { it.centerCutPcm(reference, referenceCenter, frames) }

        for (preserveSide in listOf(false, true)) {
            val backend = ConstantMaskBackend()
            val engine = AiStemSeparationEngine(backend)
            try {
                engine.preserveSide = preserveSide
                engine.ecoMode = false
                engine.activate()
                engine.pushInput(input, frames)
                engine.onEndOfStream()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (engine.availableVocalFrames < frames && System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals(frames.toLong(), engine.availableVocalFrames)
                val output = FloatArray(input.size)
                assertEquals(frames, engine.popVocalAndBlend(output, frames, 0f))
                assertTrue(engine.isEnded())
                assertEquals("Expected two full steps and one partial tail", 3, backend.calls.get())
                val expectedVocal = if (preserveSide) referenceCenter else reference
                var sumSquaredError = 0.0
                var count = 0
                var peak = 0f
                for (seam in listOf(hop, hop * 2)) {
                    for (index in (seam - 1024) * 2 until (seam + 1024) * 2) {
                        val error = abs((input[index] - expectedVocal[index]) - output[index])
                        peak = maxOf(peak, error)
                        sumSquaredError += error * error
                        count++
                    }
                }
                assertTrue("Join peak error $peak (preserveSide=$preserveSide)", peak < 0.01f)
                assertTrue("Join RMS error (preserveSide=$preserveSide)", sqrt(sumSquaredError / count) < 0.001)
            } finally {
                engine.release()
            }
        }
    }

    @Test fun benchmarkReportsPlaybackHopRatherThanInputWindowDuration() {
        val result = SpectralBenchmark.run(ConstantMaskBackend())
        assertEquals(VrChunking.hopSamples(), result.audioFrames)
        assertTrue(result.rtf > 0.0)
    }
}
