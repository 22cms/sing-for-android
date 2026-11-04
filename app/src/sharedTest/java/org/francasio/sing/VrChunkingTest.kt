package org.francasio.sing

import org.francasio.sing.audio.VrChunking
import org.junit.Assert.*
import org.junit.Test

class VrChunkingTest {
    @Test fun retains512FrameWindowAndFullModelContext() {
        assertEquals(512, VrChunking.WINDOW_FRAMES)
        assertEquals(128, VrChunking.CONTEXT_FRAMES)
        assertEquals(130560, VrChunking.validSamples())
        assertEquals(126464, VrChunking.hopSamples())
        assertEquals(VrChunking.validSamples(), VrChunking.hopSamples() + VrChunking.CROSSFADE_SAMPLES)
    }

    @Test fun fadeIsBoundedMonotonicAndSmoothAtBothEnds() {
        val length = VrChunking.CROSSFADE_SAMPLES
        assertEquals(0f, VrChunking.crossfadeWeight(0, length), 0f)
        assertEquals(0.5f, VrChunking.crossfadeWeight(length / 2, length), 0f)
        assertEquals(1f, VrChunking.crossfadeWeight(length, length), 0f)
        var previous = 0f
        for (i in 0..length) {
            val weight = VrChunking.crossfadeWeight(i, length)
            assertTrue(weight in previous..1f)
            assertEquals(1f, weight + VrChunking.crossfadeWeight(length - i, length), 0.000001f)
            previous = weight
        }
        assertTrue(VrChunking.crossfadeWeight(1, length) < 0.000001f)
        assertTrue(1f - VrChunking.crossfadeWeight(length - 1, length) < 0.000001f)
    }
}
