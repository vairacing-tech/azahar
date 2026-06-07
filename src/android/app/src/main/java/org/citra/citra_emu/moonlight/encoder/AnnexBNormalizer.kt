package org.citra.citra_emu.moonlight.encoder

import java.nio.ByteBuffer

internal object AnnexBNormalizer {
    fun from(buffer: ByteBuffer): ByteArray {
        if (!buffer.hasRemaining()) return ByteArray(0)
        if (hasAnnexBStartCode(buffer)) {
            return buffer.copyRemainingBytes()
        }
        return convertLengthPrefixedNalUnits(buffer) ?: buffer.copyRemainingBytes()
    }

    fun from(bytes: ByteArray): ByteArray =
        from(ByteBuffer.wrap(bytes))

    private fun hasAnnexBStartCode(buffer: ByteBuffer): Boolean {
        val start = buffer.position()
        val end = buffer.limit()
        var index = start
        while (index <= end - 3) {
            if (buffer.get(index) == 0.toByte() && buffer.get(index + 1) == 0.toByte()) {
                if (buffer.get(index + 2) == 1.toByte()) return true
                if (index <= end - 4 && buffer.get(index + 2) == 0.toByte() && buffer.get(index + 3) == 1.toByte()) {
                    return true
                }
            }
            index++
        }
        return false
    }

    private fun convertLengthPrefixedNalUnits(buffer: ByteBuffer): ByteArray? {
        val start = buffer.position()
        val end = buffer.limit()
        val output = ByteArray(end - start)
        val source = buffer.duplicate()
        var readOffset = start
        var writeOffset = 0

        while (readOffset + LENGTH_PREFIX_SIZE <= end) {
            val nalLength = readIntBE(buffer, readOffset)
            if (nalLength <= 0 || readOffset + LENGTH_PREFIX_SIZE + nalLength > end) {
                return null
            }
            output[writeOffset] = 0
            output[writeOffset + 1] = 0
            output[writeOffset + 2] = 0
            output[writeOffset + 3] = 1
            writeOffset += LENGTH_PREFIX_SIZE

            source.limit(end)
            source.position(readOffset + LENGTH_PREFIX_SIZE)
            source.limit(readOffset + LENGTH_PREFIX_SIZE + nalLength)
            source.get(output, writeOffset, nalLength)
            writeOffset += nalLength
            readOffset += LENGTH_PREFIX_SIZE + nalLength
        }

        return if (readOffset == end && writeOffset > 0) output else null
    }

    private fun readIntBE(buffer: ByteBuffer, offset: Int): Int =
        ((buffer.get(offset).toInt() and 0xFF) shl 24) or
            ((buffer.get(offset + 1).toInt() and 0xFF) shl 16) or
            ((buffer.get(offset + 2).toInt() and 0xFF) shl 8) or
            (buffer.get(offset + 3).toInt() and 0xFF)

    private fun ByteBuffer.copyRemainingBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private const val LENGTH_PREFIX_SIZE = 4
}
