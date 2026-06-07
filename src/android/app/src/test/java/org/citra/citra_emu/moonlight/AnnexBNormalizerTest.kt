package org.citra.citra_emu.moonlight

import java.nio.ByteBuffer
import org.citra.citra_emu.moonlight.encoder.AnnexBNormalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AnnexBNormalizerTest {
    @Test
    fun keepsAnnexBPayloadsUnchanged() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)

        assertArrayEquals(payload, AnnexBNormalizer.from(payload))
    }

    @Test
    fun convertsLengthPrefixedNalUnitsToAnnexB() {
        val payload = byteArrayOf(
            0, 0, 0, 3,
            0x65, 0x11, 0x22,
            0, 0, 0, 2,
            0x41, 0x33,
        )

        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1,
                0x65, 0x11, 0x22,
                0, 0, 0, 1,
                0x41, 0x33,
            ),
            AnnexBNormalizer.from(payload),
        )
    }

    @Test
    fun respectsByteBufferPositionAndLimit() {
        val backing = byteArrayOf(
            0x55,
            0, 0, 0, 2,
            0x26, 0x01,
            0x66,
        )
        val buffer = ByteBuffer.wrap(backing).apply {
            position(1)
            limit(backing.size - 1)
        }

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x26, 0x01),
            AnnexBNormalizer.from(buffer),
        )
    }
}
