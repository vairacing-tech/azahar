package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightStreamLockTest {
    @Test
    fun acceptsRtspCodecConfirmationWhenLockedEncoderMimeAlreadyMatches() {
        val launchConfig = StreamConfig(codecPreference = CodecPreference.AUTO)
        val rtspConfig = launchConfig.copy(codecPreference = CodecPreference.HEVC)

        assertTrue(
            MoonlightStreamLock.canReuseLockedEncoder(
                lockedConfig = launchConfig,
                requestedConfig = rtspConfig,
                activeMime = "video/hevc",
            ),
        )
    }

    @Test
    fun rejectsLockedEncoderReuseWhenResolutionOrCodecChanges() {
        val lockedConfig = StreamConfig(codecPreference = CodecPreference.AUTO)

        assertFalse(
            MoonlightStreamLock.canReuseLockedEncoder(
                lockedConfig = lockedConfig,
                requestedConfig = lockedConfig.copy(width = lockedConfig.width + 2),
                activeMime = "video/hevc",
            ),
        )
        assertFalse(
            MoonlightStreamLock.canReuseLockedEncoder(
                lockedConfig = lockedConfig,
                requestedConfig = lockedConfig.copy(codecPreference = CodecPreference.H264),
                activeMime = "video/hevc",
            ),
        )
    }

    @Test
    fun acceptsLockedEncoderReuseWhenOnlyBitrateChanges() {
        val lockedConfig = StreamConfig(codecPreference = CodecPreference.HEVC, bitrate = 5_000_000)

        assertTrue(
            MoonlightStreamLock.canReuseLockedEncoder(
                lockedConfig = lockedConfig,
                requestedConfig = lockedConfig.copy(bitrate = 12_000_000),
                activeMime = "video/hevc",
            ),
        )
    }
}
