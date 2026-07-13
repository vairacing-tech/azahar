package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.encoder.EncoderFrameRateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderFrameRateConfigTest {
    @Test
    fun preservesThirtyFpsRequest() {
        val values = EncoderFrameRateConfig.forFps(30)

        assertEquals(30, values.frameRate)
        assertEquals(30, values.operatingRate)
        assertEquals(30f, values.maxFpsToEncoder)
    }

    @Test
    fun preservesArbitrarySupportedFpsRequest() {
        val values = EncoderFrameRateConfig.forFps(45)

        assertEquals(45, values.frameRate)
        assertEquals(45, values.operatingRate)
        assertEquals(45f, values.maxFpsToEncoder)
    }

    @Test
    fun doesNotRepeatFramesBeyondRequestedLimit() {
        val values = EncoderFrameRateConfig.forFps(60)

        assertNull(values.repeatPreviousFrameAfterUs)
    }
}
