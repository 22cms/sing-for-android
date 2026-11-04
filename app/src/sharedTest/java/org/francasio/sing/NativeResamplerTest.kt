package org.francasio.sing

import org.francasio.sing.audio.StftEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class NativeResamplerTest {
    @Before fun requireNativeDsp() {
        assumeTrue(java.lang.Boolean.getBoolean("sing.native.tests"))
    }

    private fun convert(from: Int, to: Int, input: FloatArray, block: Int): FloatArray {
        val frames = input.size / 2
        val expected = ((frames.toLong() * to + from - 1) / from).toInt()
        val output = FloatArray((expected + 4096) * 2)
        val scratch = FloatArray(output.size)
        val chunk = FloatArray(input.size)
        var written = 0
        StftEngine.NativeSpeexResampler(from, to).use { resampler ->
            var start = 0
            while (start < frames) {
                val count = minOf(block, frames - start)
                System.arraycopy(input, start * 2, chunk, 0, count * 2)
                val produced = resampler.resample(chunk, count, scratch)
                System.arraycopy(scratch, 0, output, written * 2, produced * 2)
                written += produced
                start += count
            }
            val tail = resampler.finish(scratch)
            System.arraycopy(scratch, 0, output, written * 2, tail * 2)
            written += tail
            assertEquals(0, resampler.finish(scratch))
            resampler.reset()
            assertTrue(resampler.resample(input, frames, scratch) >= 0)
        }
        assertEquals(expected, written)
        return output.copyOf(written * 2)
    }

    @Test fun streamingMatchesWholeBufferAndDrainsEverySourceFrame() {
        for (rate in listOf(8000, 44100, 48000, 96000, 192000, 384000)) {
            val input = FloatArray(5000 * 2) { i ->
                (0.5 * sin(2.0 * Math.PI * 1700 * (i / 2) / rate)).toFloat()
            }
            val whole = convert(rate, 44100, input, 5000)
            val split = convert(rate, 44100, input, 127)
            assertArrayEquals("$rate Hz", whole, split, 0.00001f)
            val restored = convert(44100, rate, split, 113)
            assertTrue(restored.size >= input.size)
            assertTrue(restored.size - input.size <= ((rate + 44099) / 44100) * 2)
        }
    }

    @Test fun jniRejectsUndersizedOutputBuffers() {
        StftEngine.NativeSpeexResampler(44100, 192000).use { resampler ->
            try {
                resampler.resample(FloatArray(2048), 1024, FloatArray(2))
                fail("Expected invalid output capacity to be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
