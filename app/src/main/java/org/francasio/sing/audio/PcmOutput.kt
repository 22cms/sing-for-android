package org.francasio.sing.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes processed samples in the format negotiated with the audio sink. */
internal object PcmOutput {
    fun copy(input: ByteBuffer, output: ByteBuffer) {
        output.put(input)
    }

    fun write(buffer: ByteBuffer, samples: FloatArray, count: Int, encoding: Int) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = samples[i].let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
            when (encoding) {
                4 -> buffer.putFloat(value)
                2 -> buffer.putShort((value.toDouble() * 32768.0).toLong().coerceIn(-32768L, 32767L).toShort())
                0x20000000 -> {
                    val pcm = (value.toDouble() * 8388608.0).toLong().coerceIn(-8388608L, 8388607L).toInt()
                    buffer.put(pcm.toByte())
                    buffer.put((pcm shr 8).toByte())
                    buffer.put((pcm shr 16).toByte())
                }
                0x30000000 -> buffer.putInt((value.toDouble() * 2147483648.0).toLong().coerceIn(-2147483648L, 2147483647L).toInt())
                else -> error("Unsupported PCM encoding: $encoding")
            }
        }
    }
}
