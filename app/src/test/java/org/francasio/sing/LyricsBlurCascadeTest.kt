package org.francasio.sing

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBlurCascadeTest {
    @Test fun blurTracksActualLyricFontSize() {
        assertEquals(0.25f, LyricsBlurCascade.scaleForTextSize(32f))
        assertEquals(0.75f, LyricsBlurCascade.scaleForTextSize(96f))
        assertEquals(0.1875f, LyricsBlurCascade.scaleForTextSize(24f))
        assertEquals(0.25f, LyricsBlurCascade.scaleForTextSize(Float.NaN))
    }
    @Test fun introAndViewportTolerance() {
        assertEquals(mapOf(0 to 5f, 1 to 10f, 2 to 12f, 3 to 12f),
            LyricsBlurCascade.radii((0..5).toList(), setOf(0, 1, 2), emptySet(), -1))
    }

    @Test fun instrumentalIntroUsesDotsWithoutAnyFocusedLine() {
        assertEquals(mapOf(1 to 5f, 2 to 10f, 3 to 12f, 4 to 12f),
            LyricsBlurCascade.radii((1..5).toList(), setOf(1, 2, 3), emptySet(),
                reference = 0, dots = 0))
    }

    @Test fun multipleFocusedAndInterleavedFocusedPredecessors() {
        assertEquals(mapOf(0 to 5f, 2 to 5f, 5 to 5f, 6 to 10f, 7 to 12f, 8 to 12f),
            LyricsBlurCascade.radii((0..8).toList(), (0..8).toSet(), setOf(1, 3, 4), 4))
    }

    @Test fun creditsAndDotsDoNotConsumeLyricSlots() {
        assertEquals(mapOf(2 to 5f, 4 to 5f, 6 to 5f, 8 to 5f, 10 to 10f, 12 to 12f),
            LyricsBlurCascade.radii(listOf(2, 4, 6, 8, 10, 12), setOf(2, 4, 6, 8, 10, 12),
                emptySet(), 6, dots = 7, blurPreviousReference = true))
    }

    @Test fun tappedLineIsNeverBlurred() {
        assertEquals(mapOf(2 to 5f, 3 to 5f, 5 to 5f, 6 to 10f),
            LyricsBlurCascade.radii((0..6).toList(), (2..6).toSet(), emptySet(), 4))
    }

    @Test fun firstLineFocusedImmediatelyAtStartOfSongWithDots() {
        assertEquals(mapOf(2 to 5f, 3 to 10f, 4 to 12f),
            LyricsBlurCascade.radii((1..5).toList(), setOf(1, 2, 3), setOf(1), reference = 1, dots = null))
    }

    @Test fun firstLineFocusedImmediatelyWithoutDots() {
        assertEquals(mapOf(1 to 5f, 2 to 10f, 3 to 12f),
            LyricsBlurCascade.radii((0..5).toList(), setOf(0, 1, 2), setOf(0), reference = 0, dots = null))
    }

    @Test fun initialStateWithoutDotsKeepsFirstLineSharp() {
        assertEquals(mapOf(1 to 5f, 2 to 10f, 3 to 12f),
            LyricsBlurCascade.radii((0..5).toList(), setOf(0, 1, 2), emptySet(), reference = 0, dots = null, blurPreviousReference = false))
    }
}
