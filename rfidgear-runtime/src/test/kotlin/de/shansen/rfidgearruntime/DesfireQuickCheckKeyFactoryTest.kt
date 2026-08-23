package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireKeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesfireQuickCheckKeyFactoryTest {

    @Test
    fun `aid accepts decimal and 0x hexadecimal`() {
        assertEquals(1212, DesfireQuickCheckKeyFactory.parseAid("1212"))
        assertEquals(0x4BC, DesfireQuickCheckKeyFactory.parseAid("0x4BC"))
    }

    @Test
    fun `aes key accepts common separators`() {
        val key = DesfireQuickCheckKeyFactory.fromHex(
            label = "app",
            keyHex = "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F",
            type = DesfireKeyType.AES,
            keyNumber = 0
        )

        assertEquals(16, key.key.bytes.size)
        assertEquals(DesfireKeyType.AES, key.key.type)
        assertEquals(0, key.key.number)
        assertEquals(0x0F.toByte(), key.key.bytes.last())
    }

    @Test
    fun `3k3des requires 24 bytes`() {
        val key = DesfireQuickCheckKeyFactory.fromHex(
            label = "3k",
            keyHex = "000102030405060708090A0B0C0D0E0F1011121314151617",
            type = DesfireKeyType.TDES_3K,
            keyNumber = 0
        )

        assertEquals(24, key.key.bytes.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `3k3des rejects 16 byte value`() {
        DesfireQuickCheckKeyFactory.fromHex(
            label = "bad",
            keyHex = "00000000000000000000000000000000",
            type = DesfireKeyType.TDES_3K,
            keyNumber = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `aid zero is rejected because it is PICC not an application`() {
        DesfireQuickCheckKeyFactory.parseAid("0")
    }

    @Test
    fun `key toString never contains key material`() {
        val key = DesfireQuickCheckKeyFactory.fromHex(
            label = "safe-label",
            keyHex = "00112233445566778899AABBCCDDEEFF",
            type = DesfireKeyType.AES,
            keyNumber = 2
        )

        val text = key.toString()
        assertTrue(text.contains("safe-label"))
        assertTrue(!text.contains("00112233"))
    }
}
