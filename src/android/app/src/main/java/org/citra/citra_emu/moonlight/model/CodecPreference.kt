package org.citra.citra_emu.moonlight.model

enum class CodecPreference(val mime: String?) {
    AUTO(null),
    H264("video/avc"),
    HEVC("video/hevc");

    companion object {
        fun fromWire(value: String?): CodecPreference =
            entries.firstOrNull { it.name == value } ?: H264
    }
}
