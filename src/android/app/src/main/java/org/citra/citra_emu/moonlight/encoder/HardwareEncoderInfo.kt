package org.citra.citra_emu.moonlight.encoder

data class HardwareEncoderInfo(
    val codecName: String,
    val mime: String,
    val vendor: String,
    val cbrSupported: Boolean,
    val lowLatencyFeature: Boolean,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val score: Int,
) {
    val isSnapdragonPreferred: Boolean
        get() = vendor == "Qualcomm"
}
