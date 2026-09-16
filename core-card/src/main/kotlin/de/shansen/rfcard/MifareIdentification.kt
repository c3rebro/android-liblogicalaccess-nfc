package de.shansen.rfcard

/** Read-only GetVersion identification, following NXP AN10833.
 * ISO-DEP is a transport, not a card family. Never infer DESFire from ISO-DEP alone.
 */
object MifareIdentification {
    enum class Family(val label: String) {
        DESFIRE("DESFire"), PLUS("MIFARE Plus"), ULTRALIGHT("MIFARE Ultralight"),
        NTAG("NTAG"), DESFIRE_LIGHT("DESFire Light"), UNKNOWN("ISO-DEP card")
    }

    fun identify(transceive: (ByteArray) -> ByteArray): Family {
        val wrapped = runCatching { transceive(wrappedCommand(0x60)) }.getOrNull()
        if (wrapped != null && wrapped.size == 9 && wrapped[7].u8() == 0x91 &&
            wrapped[8].u8() in listOf(0x00, 0xAF)) {
            val family = fromVersion(wrapped.copyOfRange(0, 7))
            // Finish the read-only chained command before the next operation.
            var response: ByteArray = wrapped
            repeat(3) {
                if (response.last().u8() == 0xAF) {
                    response = runCatching { transceive(wrappedCommand(0xAF)) }.getOrNull()
                        ?: return Family.UNKNOWN
                    if (response.size < 2 || response[response.size - 2].u8() != 0x91)
                        return Family.UNKNOWN
                }
            }
            return if (response.last().u8() == 0) family else Family.UNKNOWN
        }
        // Plus generations can use native framing rather than SW1/SW2 APDUs.
        val native = runCatching { transceive(byteArrayOf(0x60)) }.getOrNull()
            ?: return Family.UNKNOWN
        if (native.size >= 8 && native[0].u8() in listOf(0x90, 0xAF)) {
            val family = fromVersion(native.copyOfRange(1, 8))
            var response: ByteArray = native
            repeat(3) {
                if (response.first().u8() == 0xAF) {
                    response = runCatching { transceive(byteArrayOf(0xAF.toByte())) }.getOrNull()
                        ?: return Family.UNKNOWN
                    if (response.isEmpty()) return Family.UNKNOWN
                }
            }
            // Do not interpret a native Plus response as DESFire.
            return if (family == Family.PLUS && response.first().u8() == 0x90) family else Family.UNKNOWN
        }
        return Family.UNKNOWN
    }

    fun fromVersion(hardware: ByteArray): Family {
        if (hardware.size < 7 || hardware[0].u8() != 0x04) return Family.UNKNOWN
        return when (hardware[1].u8() and 0x0F) {
            1 -> if (hardware[3].u8() == 0xA0) Family.UNKNOWN else Family.DESFIRE
            2 -> Family.PLUS
            3 -> Family.ULTRALIGHT
            4, 7 -> Family.NTAG
            8 -> Family.DESFIRE_LIGHT
            else -> Family.UNKNOWN
        }
    }

    /** Legacy Plus S/X/SE historical-byte signatures from AN10833. */
    fun legacyPlus(historicalBytes: ByteArray?): Boolean = historicalBytes?.joinToString("") {
        "%02X".format(it)
    } in setOf("C1052F2F0035C7", "C1052F2F01BCD6", "C105213000F6D1", "C105213010F6D1")

    private fun wrappedCommand(command: Int) = byteArrayOf(0x90.toByte(), command.toByte(), 0, 0, 0)
    private fun Byte.u8() = toInt() and 0xFF
}
