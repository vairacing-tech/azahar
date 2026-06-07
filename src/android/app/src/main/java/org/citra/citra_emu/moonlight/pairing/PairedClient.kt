package org.citra.citra_emu.moonlight.pairing

data class PairedClient(
    val uuid: String,
    val name: String,
    val certificatePem: String,
    val pairedAtEpochMillis: Long,
)
