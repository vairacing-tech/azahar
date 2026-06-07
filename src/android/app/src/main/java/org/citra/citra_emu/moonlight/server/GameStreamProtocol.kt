package org.citra.citra_emu.moonlight.server

import org.citra.citra_emu.moonlight.pairing.PairingHash

object GameStreamProtocol {
    const val APP_VERSION = "4.1.1.0"
    const val GFE_VERSION = "2.11.4.0"
    const val RTSP_CLIENT_VERSION = 11

    val pairingHash = PairingHash.SHA1

    const val INCLUDE_VIDEO_FRAME_HEADER = false
    const val LEGACY_TCP_CONTROL = true
}
