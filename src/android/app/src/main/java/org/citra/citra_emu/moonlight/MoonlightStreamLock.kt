package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig

internal object MoonlightStreamLock {
    fun canReuseLockedEncoder(
        lockedConfig: StreamConfig,
        requestedConfig: StreamConfig,
        activeMime: String,
    ): Boolean =
        lockedConfig.width == requestedConfig.width &&
            lockedConfig.height == requestedConfig.height &&
            lockedConfig.fps == requestedConfig.fps &&
            mimeMatchesPreference(activeMime, requestedConfig.codecPreference)

    private fun mimeMatchesPreference(mime: String, preference: CodecPreference): Boolean =
        when (preference) {
            CodecPreference.AUTO -> true
            CodecPreference.H264 -> mime == MIME_H264
            CodecPreference.HEVC -> mime == MIME_HEVC
        }

    private const val MIME_H264 = "video/avc"
    private const val MIME_HEVC = "video/hevc"
}
