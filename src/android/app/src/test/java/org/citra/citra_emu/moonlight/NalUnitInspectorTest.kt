package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.encoder.NalUnitInspector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NalUnitInspectorTest {
    @Test
    fun detectsH264IdrNal() {
        val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val pFrame = byteArrayOf(0, 0, 1, 0x41, 0x11, 0x22)

        assertTrue(NalUnitInspector.containsIdrNal(idrFrame, "video/avc"))
        assertFalse(NalUnitInspector.containsIdrNal(pFrame, "video/avc"))
    }

    @Test
    fun detectsHevcIdrNal() {
        val idrFrame = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x11, 0x22)
        val pFrame = byteArrayOf(0, 0, 1, 0x02, 0x01, 0x11, 0x22)

        assertTrue(NalUnitInspector.containsIdrNal(idrFrame, "video/hevc"))
        assertFalse(NalUnitInspector.containsIdrNal(pFrame, "video/hevc"))
    }
}
