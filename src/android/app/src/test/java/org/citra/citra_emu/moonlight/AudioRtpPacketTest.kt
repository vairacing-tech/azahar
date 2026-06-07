package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.server.AudioRtpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRtpPacketTest {
    @Test
    fun buildsGameStreamOpusRtpHeader() {
        val packet = AudioRtpPacket.build(
            payload = byteArrayOf(0x11, 0x22, 0x33),
            sequence = 0x1234,
            timestamp = 25,
            ssrc = 0x41505355,
        )

        assertEquals(15, packet.size)
        assertEquals(0x80.toByte(), packet[0])
        assertEquals(97.toByte(), packet[1])
        assertEquals(0x12.toByte(), packet[2])
        assertEquals(0x34.toByte(), packet[3])
        assertArrayEquals(byteArrayOf(0, 0, 0, 25), packet.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x41, 0x50, 0x53, 0x55), packet.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), packet.copyOfRange(12, 15))
    }
}
