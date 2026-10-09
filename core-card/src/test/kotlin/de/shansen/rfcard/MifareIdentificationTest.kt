package de.shansen.rfcard

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MifareIdentificationTest {
    @Test fun productFamilyUsesVersionNotTransport() {
        fun version(type: Int) = byteArrayOf(4, type.toByte(), 1, 3, 0, 0x1A, 5)
        assertEquals(MifareIdentification.Family.DESFIRE, MifareIdentification.fromVersion(version(1)))
        assertEquals(MifareIdentification.Family.DESFIRE, MifareIdentification.fromVersion(version(0x81)))
        assertEquals(MifareIdentification.Family.DESFIRE, MifareIdentification.fromVersion(version(0x91)))
        assertEquals(MifareIdentification.Family.PLUS, MifareIdentification.fromVersion(version(2)))
        assertEquals(MifareIdentification.Family.PLUS, MifareIdentification.fromVersion(version(0x82)))
        assertEquals(MifareIdentification.Family.UNKNOWN, MifareIdentification.fromVersion(byteArrayOf(4, 1)))
        assertEquals(MifareIdentification.Family.DESFIRE_LIGHT, MifareIdentification.fromVersion(version(8)))
    }
    @Test fun wrappedDesfireFinishesChaining() {
        val commands = mutableListOf<Int>()
        val family = MifareIdentification.identify { command ->
            commands.add(command[1].toInt() and 255)
            when (commands.size) {
                1 -> byteArrayOf(4, 1, 1, 3, 0, 0x1A, 5, 0x91.toByte(), 0xAF.toByte())
                2 -> ByteArray(7) + byteArrayOf(0x91.toByte(), 0xAF.toByte())
                else -> ByteArray(14) + byteArrayOf(0x91.toByte(), 0)
            }
        }
        assertEquals(MifareIdentification.Family.DESFIRE, family)
        assertEquals(listOf(0x60, 0xAF, 0xAF), commands)
    }
    @Test fun nativePlusDoesNotNeedSw1Sw2() {
        assertEquals(MifareIdentification.Family.PLUS, MifareIdentification.identify { command ->
            if (command.size == 5) byteArrayOf(0x0B)
            else byteArrayOf(0x90.toByte(), 4, 2, 1, 0x22, 0, 0x18, 4)
        })
    }
    @Test fun unsupportedAndBrokenFramesAreNotDesfire() {
        assertEquals(MifareIdentification.Family.UNKNOWN, MifareIdentification.identify { byteArrayOf(0x6A, 0x81.toByte()) })
        assertEquals(MifareIdentification.Family.UNKNOWN, MifareIdentification.identify { throw java.io.IOException() })
        assertFalse(MifareIdentification.legacyPlus(null))
        assertTrue(MifareIdentification.legacyPlus(byteArrayOf(0xC1.toByte(),5,0x2F,0x2F,0,0x35,0xC7.toByte())))
    }
    @Test fun chainedNativePlusFinishesBeforeReturning() {
        var frames = 0
        assertEquals(MifareIdentification.Family.PLUS, MifareIdentification.identify { command ->
            if (command.size == 5) byteArrayOf(0x0B)
            else when (++frames) {
                1 -> byteArrayOf(0xAF.toByte(), 4, 2, 1, 0x22, 0, 0x18, 4)
                2 -> byteArrayOf(0xAF.toByte()) + ByteArray(7)
                else -> byteArrayOf(0x90.toByte()) + ByteArray(14)
            }
        })
        assertEquals(3, frames)
    }
    @Test fun transportFailureDoesNotRetryAnotherFraming() {
        var calls = 0
        assertEquals(MifareIdentification.Family.UNKNOWN, MifareIdentification.identify {
            calls++
            throw java.io.IOException("Timed out")
        })
        assertEquals(1, calls)
    }
}
