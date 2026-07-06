package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightLaunchStartPolicyTest {
    @Test
    fun waitsForRtspWhenVulkanLaunchHasAutoCodecAndNoEncoderSurface() {
        assertTrue(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = true,
                hasEncoderSurface = false,
                launchConfig = StreamConfig(codecPreference = CodecPreference.AUTO),
            ),
        )
    }

    @Test
    fun waitsForRtspWhenVulkanLaunchDoesNotDeclareCodec() {
        assertTrue(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = true,
                hasEncoderSurface = false,
                launchConfig = null,
            ),
        )
    }

    @Test
    fun startsImmediatelyWhenCodecIsExplicitOrSurfaceIsAlreadyAvailable() {
        assertFalse(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = true,
                hasEncoderSurface = false,
                launchConfig = StreamConfig(codecPreference = CodecPreference.HEVC),
            ),
        )
        assertFalse(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = true,
                hasEncoderSurface = false,
                launchConfig = StreamConfig(codecPreference = CodecPreference.H264),
            ),
        )
        assertFalse(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = true,
                hasEncoderSurface = true,
                launchConfig = StreamConfig(codecPreference = CodecPreference.AUTO),
            ),
        )
    }

    @Test
    fun doesNotWaitForRtspWhenNativeSurfaceIsNotProtected() {
        assertFalse(
            MoonlightLaunchStartPolicy.shouldWaitForRtspBeforeStarting(
                protectNativeSurface = false,
                hasEncoderSurface = false,
                launchConfig = StreamConfig(codecPreference = CodecPreference.AUTO),
            ),
        )
    }
}
