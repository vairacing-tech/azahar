package org.citra.citra_emu.moonlight.model

data class StreamConfig(
    val codecPreference: CodecPreference = CodecPreference.H264,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrate: Int = 16_000_000,
    val audioEnabled: Boolean = true,
    val lowLatency: Boolean = true,
    val iFrameIntervalSeconds: Int = 1,
) {
    init {
        require(width > 0 && height > 0) { "Resolution must be positive" }
        require(fps > 0) { "FPS must be positive" }
        require(bitrate > 0) { "Bitrate must be positive" }
        require(iFrameIntervalSeconds >= 0) { "I-frame interval cannot be negative" }
    }

    val resolutionLabel: String get() = "${width}x$height"
}
