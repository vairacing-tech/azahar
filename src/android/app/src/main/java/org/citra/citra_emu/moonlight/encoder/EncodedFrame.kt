package org.citra.citra_emu.moonlight.encoder

data class EncodedFrame(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return presentationTimeUs == other.presentationTimeUs &&
            flags == other.flags &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + flags
        return result
    }
}
