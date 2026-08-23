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
import de.shansen.rfcard.DesfireListApplications
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
    fun readOnlyApplicationSettingsAreSafeAndCanBeEnabledByBackend() {
        val evaluation = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireReadApplicationSettings(
                    appId = 0x123456,
                    key = aesKey,
                    authenticateBeforeRead = true
                )
            ),
            backendSupportsAction = { true }
        )

        assertEquals(RfidGearActionSafetyKind.READ_ONLY, evaluation.safety.kind)
        assertEquals(RfidGearBackendReadiness.SUPPORTED, evaluation.backendReadiness)
        assertTrue(evaluation.canRunOnCurrentAndroidBackend)
        assertEquals("ENABLED", evaluation.previewStatus)
    }

    @Test
    fun sessionAuthenticationIsSafeButDocumentedAsSessionOnly() {
        val evaluation = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireAuthenticate(0x123456, aesKey)),
            backendSupportsAction = { true }
        )

        assertEquals(RfidGearActionSafetyKind.READ_ONLY, evaluation.safety.kind)
        assertTrue(evaluation.safety.reason.contains("session authentication"))
        assertTrue(evaluation.canRunOnCurrentAndroidBackend)
    }

    @Test
    fun readDataIsSafeButCanRemainNotMapped() {
        val evaluation = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireReadData(
                    appId = 0x123456,
                    fileNo = 1,
                    length = 16,
                    readKey = aesKey,
                    communicationMode = DesfireCommunicationMode.PLAIN
                )
            ),
            backendSupportsAction = { false }
        )

        assertEquals(RfidGearActionSafetyKind.READ_ONLY, evaluation.safety.kind)
        assertEquals(RfidGearBackendReadiness.NOT_SUPPORTED, evaluation.backendReadiness)
        assertFalse(evaluation.canRunOnCurrentAndroidBackend)
        assertEquals("NOT_MAPPED", evaluation.previewStatus)
    }

    @Test
    fun compositeChecksAreReadOnlyButNotMappedWithoutSemanticExecutor() {
        val exists = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.CheckApplicationExists(
                command = DesfireListApplications(piccMasterKey = aesKey),
                targetAppId = 0x123456
            ),
            backendSupportsAction = { false }
        )
        val keyCount = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.CheckApplicationKeyCount(
                command = DesfireReadApplicationSettings(
                    appId = 0x123456,
                    key = aesKey,
                    authenticateBeforeRead = true
                ),
                expectedCount = 5
            ),
            backendSupportsAction = { false }
        )

        assertEquals(RfidGearActionSafetyKind.READ_ONLY, exists.safety.kind)
        assertEquals(RfidGearActionSafetyKind.READ_ONLY, keyCount.safety.kind)
        assertEquals(RfidGearBackendReadiness.NOT_SUPPORTED, exists.backendReadiness)
        assertEquals(RfidGearBackendReadiness.NOT_SUPPORTED, keyCount.backendReadiness)
        assertFalse(exists.canRunOnCurrentAndroidBackend)
        assertFalse(keyCount.canRunOnCurrentAndroidBackend)
        assertEquals("NOT_MAPPED", exists.previewStatus)
        assertEquals("NOT_MAPPED", keyCount.previewStatus)
    }

    @Test
    fun cardWritesAreDisabledEvenIfABackendWouldSupportTheCommand() {
        val createApplication = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(
                DesfireCreateApplication(
                    appId = 0x123456,
                    piccMasterKey = aesKey,
                    targetKeyType = DesfireKeyType.AES,
                    maxKeys = 5,
                    keySettings = 0x0F
                )
            ),
            backendSupportsAction = { true }
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
            ),
            backendSupportsAction = { true }
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
            ),
            backendSupportsAction = { true }
        )

        assertEquals(RfidGearActionSafetyKind.MUTATING, createApplication.safety.kind)
        assertEquals(RfidGearActionSafetyKind.MUTATING, writeData.safety.kind)
        assertEquals(RfidGearActionSafetyKind.MUTATING, createFile.safety.kind)
        assertEquals("WRITE_DISABLED", createApplication.previewStatus)
        assertFalse(createApplication.canRunOnCurrentAndroidBackend)
        assertFalse(writeData.canRunOnCurrentAndroidBackend)
        assertFalse(createFile.canRunOnCurrentAndroidBackend)
    }

    @Test
    fun destructiveOperationsAreDisabledSeparately() {
        val deleteApplication = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireDeleteApplication(0x123456, aesKey)),
            backendSupportsAction = { true }
        )
        val formatCard = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Execute(DesfireFormatCard(aesKey)),
            backendSupportsAction = { true }
        )

        assertEquals(RfidGearActionSafetyKind.DESTRUCTIVE, deleteApplication.safety.kind)
        assertEquals(RfidGearActionSafetyKind.DESTRUCTIVE, formatCard.safety.kind)
        assertEquals("DESTRUCTIVE_DISABLED", deleteApplication.previewStatus)
    }

    @Test
    fun unsupportedCompilerActionsStayUnsupported() {
        val evaluation = RfidGearActionSafetyPolicy.evaluate(
            RfidGearAction.Unsupported("payload tree is not mapped"),
            backendSupportsAction = { true }
        )

        assertEquals(RfidGearActionSafetyKind.UNSUPPORTED, evaluation.safety.kind)
        assertEquals("payload tree is not mapped", evaluation.safety.reason)
        assertFalse(evaluation.canRunOnCurrentAndroidBackend)
    }
}
