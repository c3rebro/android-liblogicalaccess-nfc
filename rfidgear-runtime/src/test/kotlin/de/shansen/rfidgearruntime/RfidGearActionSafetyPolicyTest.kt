package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireAccessRights
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireCommunicationMode
import de.shansen.rfcard.DesfireCreateApplication
import de.shansen.rfcard.DesfireCreateFile
import de.shansen.rfcard.DesfireDeleteApplication
import de.shansen.rfcard.DesfireFileType
import de.shansen.rfcard.DesfireFormatCard
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfcard.DesfireReadApplicationSettings
import de.shansen.rfcard.DesfireReadData
import de.shansen.rfcard.DesfireWriteData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RfidGearActionSafetyPolicyTest {
    private val aesKey = DesfireKey(ByteArray(16), DesfireKeyType.AES, 0)

    @Test
    fun readOnlyApplicationSettingsAreEnabledForCurrentBackend() {
        val safety = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireReadApplicationSettings(
                    appId = 0x123456,
                    key = aesKey,
                    authenticateBeforeRead = true
                )
            )
        )

        assertEquals(RfidGearActionReadiness.QUICK_CHECK_ENABLED, safety.readiness)
        assertTrue(safety.canRunOnCurrentAndroidBackend)
    }

    @Test
    fun sessionAuthenticationIsEnabledButDocumentedAsNonPersistent() {
        val safety = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireAuthenticate(0x123456, aesKey))
        )

        assertEquals(RfidGearActionReadiness.QUICK_CHECK_ENABLED, safety.readiness)
        assertTrue(safety.reason.contains("session authentication"))
    }

    @Test
    fun readDataIsSafeButNotMappedYet() {
        val safety = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireReadData(
                    appId = 0x123456,
                    fileNo = 1,
                    length = 16,
                    readKey = aesKey,
                    communicationMode = DesfireCommunicationMode.PLAIN
                )
            )
        )

        assertEquals(RfidGearActionReadiness.QUICK_CHECK_NOT_MAPPED, safety.readiness)
        assertFalse(safety.canRunOnCurrentAndroidBackend)
    }

    @Test
    fun cardWritesAreDisabled() {
        val createApplication = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireCreateApplication(
                    appId = 0x123456,
                    piccMasterKey = aesKey,
                    targetKeyType = DesfireKeyType.AES,
                    maxKeys = 5,
                    keySettings = 0x0F
                )
            )
        )
        val writeData = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireWriteData(
                    appId = 0x123456,
                    fileNo = 1,
                    payload = byteArrayOf(1, 2, 3),
                    writeKey = aesKey,
                    communicationMode = DesfireCommunicationMode.PLAIN
                )
            )
        )
        val createFile = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireCreateFile(
                    appId = 0x123456,
                    appMasterKey = aesKey,
                    fileNo = 1,
                    fileType = DesfireFileType.STANDARD_DATA,
                    size = 16,
                    communicationMode = DesfireCommunicationMode.PLAIN,
                    accessRights = DesfireAccessRights(0, 0, 0, 0)
                )
            )
        )

        assertEquals(RfidGearActionReadiness.MUTATING_DISABLED, createApplication.readiness)
        assertEquals(RfidGearActionReadiness.MUTATING_DISABLED, writeData.readiness)
        assertEquals(RfidGearActionReadiness.MUTATING_DISABLED, createFile.readiness)
        assertFalse(createApplication.canRunOnCurrentAndroidBackend)
        assertFalse(writeData.canRunOnCurrentAndroidBackend)
        assertFalse(createFile.canRunOnCurrentAndroidBackend)
    }

    @Test
    fun destructiveOperationsAreDisabledSeparately() {
        val deleteApplication = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireDeleteApplication(0x123456, aesKey))
        )
        val formatCard = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireFormatCard(aesKey))
        )

        assertEquals(RfidGearActionReadiness.DESTRUCTIVE_DISABLED, deleteApplication.readiness)
        assertEquals(RfidGearActionReadiness.DESTRUCTIVE_DISABLED, formatCard.readiness)
    }

    @Test
    fun unsupportedCompilerActionsStayUnsupported() {
        val safety = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Unsupported("payload tree is not mapped")
        )

        assertEquals(RfidGearActionReadiness.UNSUPPORTED, safety.readiness)
        assertEquals("payload tree is not mapped", safety.reason)
    }
}
