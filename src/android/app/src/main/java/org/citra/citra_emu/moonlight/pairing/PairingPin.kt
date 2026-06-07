package org.citra.citra_emu.moonlight.pairing

object PairingPin {
    private val pinPattern = Regex("\\d{4}")

    fun normalize(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return trimmed.takeIf { pinPattern.matches(it) }
    }

    fun fromQuery(query: Map<String, String>): String? =
        listOf("pin", "PIN", "Pin", "value", "code", "passcode")
            .firstNotNullOfOrNull { key -> normalize(query[key]) }
}
