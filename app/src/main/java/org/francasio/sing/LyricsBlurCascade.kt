package org.francasio.sing

/** Positions are adapter positions of lyric rows, not distances between arbitrary adapter items. */
internal object LyricsBlurCascade {
    // Waydroid/tablet lyrics are about 32 physical pixels tall. RenderEffect
    // takes physical pixels, so scale the nominal cascade with the actual lyric
    // font. The 0.25 strength applies equally to every level on every device.
    fun scaleForTextSize(textSizePx: Float): Float =
        if (textSizePx.isFinite() && textSizePx > 0f) (textSizePx / 32f).coerceIn(0.5f, 4f) * 0.25f else 0.25f

    fun radii(
        lyrics: List<Int>,
        visible: Set<Int>,
        focused: Set<Int>,
        reference: Int,
        dots: Int? = null,
        blurPreviousReference: Boolean = false
    ): Map<Int, Float> {
        val rows = lyrics.distinct().sorted()
        val result = mutableMapOf<Int, Float>()

        // The two preceding *unfocused lyric rows* are blurred, even when a
        // focused row is interleaved. A dot or a credit never consumes a slot.
        rows.asReversed().asSequence().filter { it < reference && it !in focused }
            .take(2).forEach { result[it] = 5f }

        val after = if (dots != null && dots >= reference) dots else reference
        var count = 0
        val lastVisible = visible.filter { it > after }.maxOrNull()
        val tolerance = rows.firstOrNull { lastVisible != null && it > lastVisible && it !in focused }
        for (pos in rows) {
            if (pos <= after || pos in focused) continue
            count++
            val radius = when (count) {
                1 -> 5f
                2 -> 10f
                else -> 12f
            }
            // At most one attached row past the viewport gets the 12px blur.
            if (pos in visible || pos == tolerance) {
                result[pos] = radius
            }
        }
        if (blurPreviousReference && reference in rows && reference !in focused) {
            result[reference] = 5f
        }
        // A tapped reference stays sharp even before the player focuses it.
        if (!blurPreviousReference) result.remove(reference)
        focused.forEach { result.remove(it) }
        return result
    }
}
