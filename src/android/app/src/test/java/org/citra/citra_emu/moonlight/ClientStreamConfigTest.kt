package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.citra.citra_emu.moonlight.server.ClientStreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientStreamConfigTest {
    @Test
    fun launchQueryWithoutStreamFieldsDoesNotStartCapture() {
        val config = ClientStreamConfig.fromLaunchQuery(mapOf("corever" to "1"), StreamConfig())

        assertNull(config)
    }

    @Test
    fun launchQueryParsesModeAndFlexibleBitrate() {
        val config = ClientStreamConfig.fromLaunchQuery(
            mapOf(
                "mode" to "2560x1440x120",
                "bitrate" to "50",
                "codec" to "hevc",
            ),
            StreamConfig(),
        )!!

        assertEquals(CodecPreference.HEVC, config.codecPreference)
        assertEquals(2560, config.width)
        assertEquals(1440, config.height)
        assertEquals(120, config.fps)
        assertEquals(50_000_000, config.bitrate)
    }

    @Test
    fun rtspAnnounceParsesClientResolutionFpsBitrateAndCodec() {
        val announce = """
            v=0
            a=x-nv-video[0].clientViewportWd:1280
            a=x-nv-video[0].clientViewportHt:720
            a=x-nv-video[0].maxFPS:45
            a=x-nv-video[0].packetSize:1392
            a=x-nv-video[0].initialBitrateKbps:12000
            a=x-nv-vqos[0].bitStreamFormat:1
        """.trimIndent()

        val config = ClientStreamConfig.fromRtspAnnounce(announce, StreamConfig())

        assertEquals(CodecPreference.HEVC, config.codecPreference)
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(45, config.fps)
        assertEquals(12_000_000, config.bitrate)
    }

    @Test
    fun rtspAnnounceIgnoresLegacyRemoteFourKbpsBitrate() {
        val fallback = StreamConfig(bitrate = 16_000_000)
        val announce = """
            a=x-nv-video[0].clientViewportWd:1920
            a=x-nv-video[0].clientViewportHt:1080
            a=x-nv-video[0].maxFPS:60
            a=x-nv-video[0].averageBitrate:4
        """.trimIndent()

        val config = ClientStreamConfig.fromRtspAnnounce(announce, fallback)

        assertEquals(16_000_000, config.bitrate)
    }
}
