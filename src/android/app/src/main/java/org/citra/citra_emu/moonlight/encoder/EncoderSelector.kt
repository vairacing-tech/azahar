package org.citra.citra_emu.moonlight.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import org.citra.citra_emu.moonlight.model.CodecPreference

object EncoderSelector {
    private const val MIME_H264 = "video/avc"
    private const val MIME_HEVC = "video/hevc"

    fun select(
        codecPreference: CodecPreference,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): HardwareEncoderInfo {
        val requestedMimes = when (codecPreference) {
            CodecPreference.AUTO -> listOf(MIME_HEVC, MIME_H264)
            CodecPreference.H264 -> listOf(MIME_H264)
            CodecPreference.HEVC -> listOf(MIME_HEVC)
        }

        val candidates = requestedMimes
            .flatMap { mime -> hardwareEncodersFor(mime, width, height, fps, bitrate) }
            .sortedWith(compareByDescending<HardwareEncoderInfo> { it.score }.thenBy { it.codecName })

        return candidates.firstOrNull()
            ?: throw IllegalStateException(
                "No hardware encoder supports ${codecPreference.name} at ${width}x$height ${fps}fps ${bitrate}bps",
            )
    }

    fun listHardwareEncoders(
        codecPreference: CodecPreference,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): List<HardwareEncoderInfo> {
        val requestedMimes = when (codecPreference) {
            CodecPreference.AUTO -> listOf(MIME_HEVC, MIME_H264)
            CodecPreference.H264 -> listOf(MIME_H264)
            CodecPreference.HEVC -> listOf(MIME_HEVC)
        }
        return requestedMimes
            .flatMap { hardwareEncodersFor(it, width, height, fps, bitrate) }
            .sortedWith(compareByDescending<HardwareEncoderInfo> { it.score }.thenBy { it.codecName })
    }

    private fun hardwareEncodersFor(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): List<HardwareEncoderInfo> {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.mapNotNull { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                return@mapNotNull null
            }

            val looksSoftware = info.name.contains("software", ignoreCase = true) ||
                info.name.startsWith("c2.android.", ignoreCase = true) ||
                info.name.startsWith("omx.google.", ignoreCase = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!info.isHardwareAccelerated || info.isSoftwareOnly || looksSoftware) {
                    return@mapNotNull null
                }
            } else if (looksSoftware) {
                return@mapNotNull null
            }

            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                ?: return@mapNotNull null
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                return@mapNotNull null
            }
            if (!supportsRequestedFormat(caps, width, height, fps, bitrate)) {
                return@mapNotNull null
            }

            val encoderCaps = caps.encoderCapabilities
            val cbr = encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
            val lowLatency = runCatching {
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
            val videoCaps = caps.videoCapabilities ?: return@mapNotNull null
            val vendor = vendorFor(info.name)
            val score = score(info.name, vendor, mime, cbr, lowLatency)

            HardwareEncoderInfo(
                codecName = info.name,
                mime = mime,
                vendor = vendor,
                cbrSupported = cbr,
                lowLatencyFeature = lowLatency,
                widthAlignment = videoCaps.widthAlignment,
                heightAlignment = videoCaps.heightAlignment,
                score = score,
            )
        }
    }

    private fun supportsRequestedFormat(
        caps: MediaCodecInfo.CodecCapabilities,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): Boolean {
        val videoCaps = caps.videoCapabilities ?: return false
        val alignedWidth = align(width, videoCaps.widthAlignment)
        val alignedHeight = align(height, videoCaps.heightAlignment)

        val bitrateOk = videoCaps.bitrateRange.containsSafely(bitrate)
        val sizeRateOk = runCatching {
            videoCaps.areSizeAndRateSupported(alignedWidth, alignedHeight, fps.toDouble())
        }.getOrElse {
            runCatching { videoCaps.isSizeSupported(alignedWidth, alignedHeight) }.getOrDefault(false)
        }
        val frameRateOk = runCatching {
            videoCaps.getSupportedFrameRatesFor(alignedWidth, alignedHeight).containsSafely(fps.toDouble())
        }.getOrDefault(true)
        val performanceOk = runCatching {
            val points = videoCaps.supportedPerformancePoints
            points == null || points.isEmpty() || points.any { point ->
                point.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(alignedWidth, alignedHeight, fps))
            }
        }.getOrDefault(true)

        return bitrateOk && sizeRateOk && frameRateOk && performanceOk
    }

    private fun score(name: String, vendor: String, mime: String, cbr: Boolean, lowLatency: Boolean): Int {
        var score = 0
        val lower = name.lowercase()
        if (lower.startsWith("c2.qti.") || lower.startsWith("omx.qcom.")) score += 1_000
        if (vendor == "Qualcomm") score += 800
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) score += 100
        if (cbr) score += 50
        if (lowLatency) score += 50
        if (mime == MIME_HEVC) score += 20
        return score
    }

    private fun vendorFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("qti") || lower.contains("qcom") || lower.contains("qualcomm") -> "Qualcomm"
            lower.contains("exynos") || lower.contains("samsung") -> "Samsung"
            lower.contains("mtk") || lower.contains("mediatek") -> "MediaTek"
            lower.contains("google") -> "Google"
            else -> "Vendor"
        }
    }

    private fun align(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return ((value + alignment - 1) / alignment) * alignment
    }

    private fun Range<Int>.containsSafely(value: Int): Boolean =
        runCatching { contains(value) }.getOrDefault(false)

    private fun Range<Double>.containsSafely(value: Double): Boolean =
        runCatching { contains(value) }.getOrDefault(false)

    fun buildProbeFormat(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): MediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
}
