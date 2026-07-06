package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig

internal object MoonlightLaunchStartPolicy {
    fun shouldWaitForRtspBeforeStarting(
        protectNativeSurface: Boolean,
        hasEncoderSurface: Boolean,
        launchConfig: StreamConfig?,
    ): Boolean {
        if (!protectNativeSurface || hasEncoderSurface) {
            return false
        }

        val codecPreference = launchConfig?.codecPreference ?: CodecPreference.AUTO
        return codecPreference == CodecPreference.AUTO
    }
}
