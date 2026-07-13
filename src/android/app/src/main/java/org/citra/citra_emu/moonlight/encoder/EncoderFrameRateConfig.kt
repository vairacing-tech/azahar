package org.citra.citra_emu.moonlight.encoder

import android.media.MediaFormat

internal data class EncoderFrameRateValues(
    val frameRate: Int,
    val operatingRate: Int,
    val maxFpsToEncoder: Float,
    val repeatPreviousFrameAfterUs: Long,
)

internal object EncoderFrameRateConfig {
    fun forFps(fps: Int): EncoderFrameRateValues {
        require(fps > 0) { "FPS must be positive" }
        return EncoderFrameRateValues(
            frameRate = fps,
            operatingRate = fps,
            maxFpsToEncoder = fps.toFloat(),
            repeatPreviousFrameAfterUs = (1_000_000L / fps).coerceAtLeast(1L),
        )
    }

    fun applyTo(format: MediaFormat, fps: Int) {
        val values = forFps(fps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, values.frameRate)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, values.operatingRate)
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, values.maxFpsToEncoder)
        format.setLong(
            MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
            values.repeatPreviousFrameAfterUs,
        )
    }
}
