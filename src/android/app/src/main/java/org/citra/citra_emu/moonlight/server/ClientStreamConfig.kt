package org.citra.citra_emu.moonlight.server

import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig

object ClientStreamConfig {
    private val MODE_PATTERN = Regex("""(?i)(\d{2,5})x(\d{2,5})(?:[x@](\d{1,3}))?""")

    fun fromLaunchQuery(query: Map<String, String>, fallback: StreamConfig): StreamConfig? {
        val mode = query.firstString("mode", "resolution", "res")
        val modeMatch = mode?.let { MODE_PATTERN.find(it) }
        val width = query.firstInt("width", "w")
            ?: modeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val height = query.firstInt("height", "h")
            ?: modeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val fps = query.firstInt("fps", "refreshRate", "refresh_rate")
            ?: modeMatch?.groupValues?.getOrNull(3)?.toIntOrNull()
        val bitrate = query.firstInt("bitrate", "bitrateKbps", "bitrate_kbps", "bps")
            ?.let(::normalizeFlexibleBitrate)
        val codec = codecFromQuery(query)

        if (width == null && height == null && fps == null && bitrate == null && codec == null) {
            return null
        }

        return StreamConfig(
            codecPreference = codec ?: fallback.codecPreference,
            width = sanitizeDimension(width, fallback.width),
            height = sanitizeDimension(height, fallback.height),
            fps = sanitizeFps(fps, fallback.fps),
            bitrate = bitrate ?: fallback.bitrate,
            audioEnabled = fallback.audioEnabled,
            lowLatency = fallback.lowLatency,
            iFrameIntervalSeconds = fallback.iFrameIntervalSeconds,
        )
    }

    fun fromRtspAnnounce(body: String, fallback: StreamConfig): StreamConfig {
        val bitStreamFormat = intAttribute(body, "x-nv-vqos[0].bitStreamFormat")
        val codec = when (bitStreamFormat) {
            0 -> CodecPreference.H264
            1 -> CodecPreference.HEVC
            else -> fallback.codecPreference
        }
        val bitrateKbps = firstAttributeInt(
            body,
            "x-ml-video.configuredBitrateKbps",
            "x-nv-video[0].initialBitrateKbps",
            "x-nv-video[0].initialPeakBitrateKbps",
            "x-nv-vqos[0].bw.maximumBitrateKbps",
            "x-nv-vqos[0].bw.maximumBitrate",
            "x-nv-video[0].peakBitrate",
            "x-nv-video[0].averageBitrate",
        )

        return StreamConfig(
            codecPreference = codec,
            width = sanitizeDimension(intAttribute(body, "x-nv-video[0].clientViewportWd"), fallback.width),
            height = sanitizeDimension(intAttribute(body, "x-nv-video[0].clientViewportHt"), fallback.height),
            fps = sanitizeFps(intAttribute(body, "x-nv-video[0].maxFPS"), fallback.fps),
            bitrate = normalizeKbpsBitrate(bitrateKbps) ?: fallback.bitrate,
            audioEnabled = fallback.audioEnabled,
            lowLatency = fallback.lowLatency,
            iFrameIntervalSeconds = fallback.iFrameIntervalSeconds,
        )
    }

    private fun codecFromQuery(query: Map<String, String>): CodecPreference? =
        when (query.firstString("codec", "videoCodec", "videocodec", "format")?.lowercase()) {
            "h264", "avc", "video/avc" -> CodecPreference.H264
            "h265", "hevc", "video/hevc" -> CodecPreference.HEVC
            "auto" -> CodecPreference.AUTO
            else -> null
        }

    private fun firstAttributeInt(body: String, vararg names: String): Int? {
        names.forEach { name ->
            intAttribute(body, name)?.let { return it }
        }
        return null
    }

    private fun intAttribute(body: String, name: String): Int? {
        val pattern = Regex("""(?im)(?:^|\r?\n)\s*(?:a=)?${Regex.escape(name)}:(\d+)""")
        return pattern.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun sanitizeDimension(value: Int?, fallback: Int): Int {
        val positive = value?.takeIf { it > 1 } ?: fallback
        return if (positive % 2 == 0) positive else (positive - 1).coerceAtLeast(2)
    }

    private fun sanitizeFps(value: Int?, fallback: Int): Int =
        (value ?: fallback).coerceIn(1, 240)

    private fun normalizeFlexibleBitrate(value: Int): Int =
        when {
            value < 1_000 -> value * 1_000_000
            value < 1_000_000 -> value * 1_000
            else -> value
        }.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)

    private fun normalizeKbpsBitrate(value: Int?): Int? {
        val kbps = value?.takeIf { it >= 1_000 } ?: return null
        return (kbps.coerceIn(1_000, 100_000) * 1_000).coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
    }

    private fun Map<String, String>.firstString(vararg names: String): String? {
        names.forEach { name ->
            get(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        entries.forEach { (key, value) ->
            if (names.any { key.equals(it, ignoreCase = true) } && value.isNotBlank()) {
                return value
            }
        }
        return null
    }

    private fun Map<String, String>.firstInt(vararg names: String): Int? =
        firstString(*names)?.toIntOrNull()

    private const val MIN_BITRATE_BPS = 1_000_000
    private const val MAX_BITRATE_BPS = 100_000_000
}
