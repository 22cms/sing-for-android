package org.francasio.sing

import org.francasio.sing.util.NativeLibDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkeletonMappingTest {
    @Test
    fun testKnownSkeletons() {
        assertEquals("V68", NativeLibDownloader.mapSocToSkeleton("SM8350"))
        assertEquals("V69", NativeLibDownloader.mapSocToSkeleton("SM8450"))
        assertEquals("V73", NativeLibDownloader.mapSocToSkeleton("SM8550"))
        assertEquals("V73", NativeLibDownloader.mapSocToSkeleton("SM8635"))
        assertEquals("V73", NativeLibDownloader.mapSocToSkeleton("SM7675"))
        assertEquals("V75", NativeLibDownloader.mapSocToSkeleton("SM8650"))
        assertEquals("V75", NativeLibDownloader.mapSocToSkeleton("SM8735"))
        assertEquals("V79", NativeLibDownloader.mapSocToSkeleton("SM8750"))
        assertEquals("V81", NativeLibDownloader.mapSocToSkeleton("SM8850"))
    }

    @Test
    fun testUnmappedSocs() {
        assertNull(NativeLibDownloader.mapSocToSkeleton("SM9999"))
        assertNull(NativeLibDownloader.mapSocToSkeleton("UNKNOWN"))
        assertNull(NativeLibDownloader.mapSocToSkeleton(""))
    }
}
