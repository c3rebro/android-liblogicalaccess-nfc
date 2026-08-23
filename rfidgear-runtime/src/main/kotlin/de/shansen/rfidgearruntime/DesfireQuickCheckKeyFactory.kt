package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType

object DesfireQuickCheckKeyFactory {

    fun parseAid(value: String): Int {
        val raw = value.trim()
        require(raw.isNotEmpty()) { "Application ID is required." }

        val aid = if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toIntOrNull(16)
        } else {
            raw.toIntOrNull(10)
        } ?: throw IllegalArgumentException(
            "Application ID must be decimal or 0x-prefixed hexadecimal."
        )

        require(aid in 1..0xFFFFFF) {
            "DESFire application ID must be between 1 and 0xFFFFFF."
        }
        return aid
    }

    fun fromHex(
        label: String,
        keyHex: String,
        type: DesfireKeyType,
        keyNumber: Int
    ): DesfireQuickCheckKey {
        require(label.isNotBlank()) { "Key label is required." }
        require(keyNumber in 0..15) { "DESFire key number must be between 0 and 15." }

        val compact = keyHex
            .trim()
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")

        val expectedBytes = when (type) {
            DesfireKeyType.TDES_3K -> 24
            DesfireKeyType.DES, DesfireKeyType.AES -> 16
        }

        require(compact.length == expectedBytes * 2) {
            "$type key must contain exactly $expectedBytes bytes (${expectedBytes * 2} hexadecimal characters)."
        }
        require(compact.all { it.digitToIntOrNull(16) != null }) {
            "Key contains non-hexadecimal characters."
        }

        val bytes = ByteArray(expectedBytes) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

        return DesfireQuickCheckKey(
            label = label.trim(),
            key = DesfireKey(bytes, type, keyNumber)
        )
    }
}
