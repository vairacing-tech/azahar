package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.server.NvVideoPacketHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class NvVideoPacketHeaderTest {
    @Test
    fun streamPacketIndexUsesTwentyFourBitsNotRtpSequenceWidth() {
        assertEquals(0, decode(NvVideoPacketHeader.encodeStreamPacketIndex(0)))
        assertEquals(65_535, decode(NvVideoPacketHeader.encodeStreamPacketIndex(65_535)))
        assertEquals(65_536, decode(NvVideoPacketHeader.encodeStreamPacketIndex(65_536)))
        assertEquals(0x00FF_FFFF, decode(NvVideoPacketHeader.encodeStreamPacketIndex(0x00FF_FFFF)))
        assertEquals(0, decode(NvVideoPacketHeader.encodeStreamPacketIndex(0x0100_0000)))
    }

    private fun decode(encoded: Int): Int =
        (encoded ushr 8) and 0x00FF_FFFF
}
