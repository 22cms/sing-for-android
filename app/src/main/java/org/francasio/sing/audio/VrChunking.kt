package org.francasio.sing.audio

/** VR's model context is unchanged; this overlap only blends independently synthesized PCM. */
internal object VrChunking {
    const val WINDOW_FRAMES = 512
    const val CONTEXT_FRAMES = 128
    const val FRAME_HOP = 512
    // Keep room for the 2048-point Preserve Side analysis at either edge,
    // without repeating the old 16384-sample (~371 ms) overlap on every chunk.
    const val CROSSFADE_SAMPLES = 4096

    fun validSamples(windowFrames: Int = WINDOW_FRAMES): Int {
        require(windowFrames > CONTEXT_FRAMES * 2 + 1)
        // The native inverse produces (ROI - 1) hops; its last hop is zero padding.
        return (windowFrames - CONTEXT_FRAMES * 2 - 1) * FRAME_HOP
    }

    fun hopSamples(windowFrames: Int = WINDOW_FRAMES): Int =
        (validSamples(windowFrames) - CROSSFADE_SAMPLES).also { require(it > 0) }

    fun crossfadeWeight(position: Int, length: Int): Float {
        if (length <= 0 || position >= length) return 1f
        if (position <= 0) return 0f
        val x = position.toDouble() / length
        // Quintic smootherstep: zero first/second derivatives at both ends.
        // Suppresses chunk-edge synthesis/filter artifacts without a long fade.
        return (x * x * x * (x * (x * 6 - 15) + 10)).toFloat()
    }
}
