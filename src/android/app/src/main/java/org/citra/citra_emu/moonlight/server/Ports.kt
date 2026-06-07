package org.citra.citra_emu.moonlight.server

object Ports {
    const val BASE = 47_989
    const val HTTP = BASE
    const val HTTPS = BASE - 5
    const val VIDEO = BASE + 9
    const val CONTROL = BASE + 10
    const val AUDIO = BASE + 11
    const val RTSP = BASE + 21
    const val LEGACY_CONTROL = 47_995
    const val LEGACY_INPUT = 35_043
}
