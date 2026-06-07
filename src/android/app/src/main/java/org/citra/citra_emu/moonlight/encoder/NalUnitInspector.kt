package org.citra.citra_emu.moonlight.encoder

object NalUnitInspector {
    fun containsIdrNal(bytes: ByteArray, mime: String): Boolean {
        var startCodeOffset = bytes.findStartCodeOffset(0)
        while (startCodeOffset >= 0) {
            val nalOffset = startCodeOffset + bytes.startCodeLengthAt(startCodeOffset)
            if (nalOffset >= bytes.size) return false
            if (isIdrNalHeader(bytes[nalOffset], mime)) return true
            startCodeOffset = bytes.findStartCodeOffset(nalOffset + 1)
        }
        return false
    }

    private fun isIdrNalHeader(header: Byte, mime: String): Boolean {
        val value = header.toInt() and 0xFF
        return when (mime) {
            "video/hevc" -> ((value and 0x7E) shr 1) in 19..21
            else -> (value and 0x1F) == 5
        }
    }

    private fun ByteArray.findStartCodeOffset(fromIndex: Int): Int {
        if (size < 3) return -1
        var index = fromIndex.coerceAtLeast(0)
        while (index <= size - 3) {
            if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) {
                if (this[index + 2] == 1.toByte()) return index
                if (index <= size - 4 && this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte()) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    private fun ByteArray.startCodeLengthAt(offset: Int): Int =
        if (offset <= size - 4 && this[offset + 2] == 0.toByte() && this[offset + 3] == 1.toByte()) 4 else 3
}
