package org.francasio.sing

import org.francasio.sing.audio.PcmOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

class PcmOutputTest {
    @Test
    fun bypassPreservesBytesAndConsumesOnlyRemainingInput() {
        val input = ByteBuffer.wrap(byteArrayOf(99, 0, -1, -128, 127, 42))
        input.position(1)
        input.limit(5)
        val output = ByteBuffer.allocate(4)
        PcmOutput.copy(input, output)
        assertArrayEquals(byteArrayOf(0, -1, -128, 127), output.array())
        org.junit.Assert.assertEquals(input.limit(), input.position())
    }

    @Test
    fun integerEndpointsDoNotOverflow() {
        val samples = floatArrayOf(-1f, 0f, 1f)
        for ((encoding, expected) in listOf(
            2 to byteArrayOf(0, -128, 0, 0, -1, 127),
            0x20000000 to byteArrayOf(0, 0, -128, 0, 0, 0, -1, -1, 127),
            0x30000000 to byteArrayOf(0, 0, 0, -128, 0, 0, 0, 0, -1, -1, -1, 127)
        )) {
            val buffer = ByteBuffer.allocate(expected.size)
            PcmOutput.write(buffer, samples, samples.size, encoding)
            assertArrayEquals(expected, buffer.array())
        }
    }
}
