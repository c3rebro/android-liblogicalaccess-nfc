package de.shansen.rfidgearruntime

import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireAccessRights
import de.shansen.rfcard.DesfireCommunicationMode
import de.shansen.rfcard.DesfireFileType
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesfireQuickCheckReportDocumentTest {
    @Test
    fun reportDocumentContainsMetadataWithoutRawKeyMaterial() {
        val rawKey = ByteArray(16) { index -> (index + 1).toByte() }
        val rawKeyHex = rawKey.joinToString("") { "%02X".format(it) }
        val key = DesfireQuickCheckKey(
            label = "test-app-key",
            key = DesfireKey(rawKey, DesfireKeyType.AES, 2)
        )
        val keyRef = key.ref()

        val report = DesfireQuickCheckReport(
            identity = CardIdentity(
                uid = byteArrayOf(0x04, 0x11, 0x22, 0x33),
                technology = CardTechnology.MIFARE_DESFIRE,
                detail = "test backend"
            ),
            version = CardResponse.DesfireVersion(
                hardwareVendor = 4,
                hardwareType = 1,
                hardwareSubType = 1,
                hardwareMajor = 1,
                hardwareMinor = 0,
                hardwareStorageSize = 0x1A,
                hardwareProtocol = 5,
                softwareVendor = 4,
                softwareType = 1,
                softwareSubType = 1,
                softwareMajor = 3,
                softwareMinor = 0,
                softwareStorageSize = 0x1A,
                softwareProtocol = 5,
                productionWeek = 12,
                productionYear = 26
            ),
            freeMemoryBytes = 2048,
            directoryAccess = DesfireQuickCheckAccess.PUBLIC,
            directoryAuthenticatedWith = null,
            applications = listOf(
                DesfireApplicationQuickCheck(
                    aid = 0x123456,
                    settings = DesfireApplicationSettingsSnapshot(0x0B, 5, DesfireKeyType.AES),
                    settingsAccess = DesfireQuickCheckAccess.AUTHENTICATED,
                    filesAccess = DesfireQuickCheckAccess.AUTHENTICATED,
                    authenticatedWith = keyRef,
                    files = listOf(
                        DesfireFileQuickCheck(
                            fileNo = 1,
                            settings = CardResponse.DesfireFileSettings(
                                fileNo = 1,
                                fileType = DesfireFileType.STANDARD_DATA,
                                communicationMode = DesfireCommunicationMode.ENCIPHERED,
                                accessRights = DesfireAccessRights(1, 2, 0x0F, 0),
                                size = 128,
                                wasAuthenticated = true
                            ),
                            access = DesfireQuickCheckAccess.AUTHENTICATED,
                            authenticatedWith = keyRef
                        )
                    ),
                    attemptedKeys = listOf(keyRef)
                )
            )
        )

        val document = DesfireQuickCheckReportDocumentFactory.from(
            report = report,
            generatedAt = "2026-08-23T23:30:00+02:00",
            environment = DesfireQuickCheckReportEnvironment(
                nfcTechnologies = listOf("IsoDep", "NfcA"),
                maxTransceiveLength = 261,
                backendVersion = "test-native"
            )
        )
        val text = DesfireQuickCheckTextRenderer.render(document)

        assertEquals(DesfireQuickCheckReportStatus.COMPLETE, document.result.status)
        assertTrue(text.contains("04112233"))
        assertTrue(text.contains("AID 0x123456"))
        assertTrue(text.contains("test-app-key [AES #2]"))
        assertTrue(text.contains("R=KEY1"))
        assertTrue(text.contains("W=KEY2"))
        assertTrue(text.contains("RW=NEVER"))
        assertFalse(text.contains(rawKeyHex))
        assertFalse(text.contains(rawKeyHex.lowercase()))
    }

    @Test
    fun missingApplicationKeyMarksReportPartial() {
        val report = DesfireQuickCheckReport(
            identity = CardIdentity(byteArrayOf(1, 2, 3, 4), CardTechnology.MIFARE_DESFIRE),
            version = null,
            freeMemoryBytes = null,
            directoryAccess = DesfireQuickCheckAccess.PUBLIC,
            directoryAuthenticatedWith = null,
            applications = listOf(
                DesfireApplicationQuickCheck(
                    aid = 0x010203,
                    settings = null,
                    settingsAccess = DesfireQuickCheckAccess.KEY_REQUIRED,
                    filesAccess = DesfireQuickCheckAccess.KEY_REQUIRED,
                    authenticatedWith = null,
                    files = emptyList(),
                    message = "Define a key for this AID."
                )
            )
        )

        val document = DesfireQuickCheckReportDocumentFactory.from(report)

        assertEquals(DesfireQuickCheckReportStatus.PARTIAL, document.result.status)
        assertEquals(listOf(0x010203), document.result.keyRequiredAids)
        assertTrue(DesfireQuickCheckTextRenderer.render(document).contains("Keys required for: 0x010203"))
    }
}
