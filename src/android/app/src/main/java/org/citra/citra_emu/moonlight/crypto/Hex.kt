package org.citra.citra_emu.moonlight.crypto

object Hex {
    private val digits = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            out[index * 2] = digits[value ushr 4]
            out[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    fun decode(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(clean.length / 2) { index ->
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hex character" }
            ((high shl 4) or low).toByte()
        }
    }
}
