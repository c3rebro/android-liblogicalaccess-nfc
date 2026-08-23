package de.shansen.rfidgearruntime

import de.shansen.rfcard.*
import org.junit.Assert.*
import org.junit.Test

class DesfireQuickCheckServiceTest {

    private val zeroAes = DesfireQuickCheckKey(
        "default-aes",
        DesfireKey(ByteArray(16), DesfireKeyType.AES, 0)
    )

    private val appSpecific = DesfireQuickCheckKey(
        "door-app",
        DesfireKey(ByteArray(16) { 0x11 }, DesfireKeyType.AES, 0)
    )

    @Test
    fun `public application is inspected without authentication`() {
        val backend = FakeQuickCheckBackend()

        val report = DesfireQuickCheckService().run(
            backend,
            DesfireQuickCheckConfig(defaultApplicationKeys = listOf(zeroAes))
        )

        assertNull(report.error)
        assertEquals(DesfireQuickCheckAccess.PUBLIC, report.directoryAccess)
        assertEquals(1, report.applications.size)
        assertEquals(DesfireQuickCheckAccess.PUBLIC, report.applications.single().filesAccess)
        assertNull(report.applications.single().authenticatedWith)
        assertEquals(0, backend.authenticationAttempts)
    }

    @Test
    fun `protected application uses aid specific key when public listing fails`() {
        val backend = FakeQuickCheckBackend(
            protectedApps = mapOf(0x123456 to appSpecific.key)
        )

        val report = DesfireQuickCheckService().run(
            backend,
            DesfireQuickCheckConfig(
                defaultApplicationKeys = listOf(zeroAes),
                applicationKeys = mapOf(0x123456 to listOf(appSpecific))
            )
        )

        val app = report.applications.single()
        assertEquals(0x123456, app.aid)
        assertEquals(DesfireQuickCheckAccess.AUTHENTICATED, app.filesAccess)
        assertEquals("door-app", app.authenticatedWith?.label)
        assertTrue(backend.authenticationAttempts >= 1)
        assertTrue(backend.authenticatedKeys.first().contentEquals(appSpecific.key.bytes))
        assertTrue(report.needsKeys.isEmpty())
    }

    @Test
    fun `protected application without configured key is reported as key required`() {
        val backend = FakeQuickCheckBackend(
            protectedApps = mapOf(0x123456 to appSpecific.key)
        )

        val report = DesfireQuickCheckService().run(backend, DesfireQuickCheckConfig())

        val app = report.applications.single()
        assertEquals(DesfireQuickCheckAccess.KEY_REQUIRED, app.filesAccess)
        assertEquals(listOf(0x123456), report.needsKeys)
        assertTrue(app.message!!.contains("Define a key"))
        assertEquals(0, backend.authenticationAttempts)
    }

    @Test
    fun `aid specific key is tried before global defaults`() {
        val backend = FakeQuickCheckBackend(
            protectedApps = mapOf(0x123456 to appSpecific.key)
        )

        val report = DesfireQuickCheckService().run(
            backend,
            DesfireQuickCheckConfig(
                defaultApplicationKeys = listOf(zeroAes),
                applicationKeys = mapOf(0x123456 to listOf(appSpecific))
            )
        )

        assertEquals(DesfireQuickCheckAccess.AUTHENTICATED, report.applications.single().filesAccess)
        assertTrue(backend.authenticationAttempts >= 1)
        assertTrue(backend.authenticatedKeys.first().contentEquals(appSpecific.key.bytes))
    }

    @Test
    fun `public file list can use aid key for protected file settings`() {
        val backend = FakeQuickCheckBackend(
            protectedFileSettings = mapOf(0x123456 to appSpecific.key)
        )

        val withoutKey = DesfireQuickCheckService().run(backend, DesfireQuickCheckConfig())
        val appWithoutKey = withoutKey.applications.single()
        assertEquals(DesfireQuickCheckAccess.PUBLIC, appWithoutKey.filesAccess)
        assertEquals(DesfireQuickCheckAccess.KEY_REQUIRED, appWithoutKey.files.single().access)
        assertEquals(listOf(0x123456), withoutKey.needsKeys)

        val withKeyBackend = FakeQuickCheckBackend(
            protectedFileSettings = mapOf(0x123456 to appSpecific.key)
        )
        val withKey = DesfireQuickCheckService().run(
            withKeyBackend,
            DesfireQuickCheckConfig(applicationKeys = mapOf(0x123456 to listOf(appSpecific)))
        )
        val protectedFile = withKey.applications.single().files.single()
        assertEquals(DesfireQuickCheckAccess.AUTHENTICATED, protectedFile.access)
        assertEquals("door-app", protectedFile.authenticatedWith?.label)
        assertTrue(withKey.needsKeys.isEmpty())
    }

    @Test
    fun `directory listing can fall back to configured picc key`() {
        val piccKey = DesfireQuickCheckKey(
            "picc",
            DesfireKey(ByteArray(16) { 0x22 }, DesfireKeyType.AES, 0)
        )
        val backend = FakeQuickCheckBackend(
            directoryKey = piccKey.key
        )

        val report = DesfireQuickCheckService().run(
            backend,
            DesfireQuickCheckConfig(piccKeys = listOf(piccKey))
        )

        assertNull(report.error)
        assertEquals(DesfireQuickCheckAccess.AUTHENTICATED, report.directoryAccess)
        assertEquals("picc", report.directoryAuthenticatedWith?.label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `3k3des key must contain 24 bytes`() {
        DesfireKey(ByteArray(16), DesfireKeyType.TDES_3K, 0)
    }

    private class FakeQuickCheckBackend(
        private val protectedApps: Map<Int, DesfireKey> = emptyMap(),
        private val protectedFileSettings: Map<Int, DesfireKey> = emptyMap(),
        private val directoryKey: DesfireKey? = null
    ) : CardBackend {

        var authenticationAttempts = 0
        val authenticatedKeys = mutableListOf<ByteArray>()
        private val aids = (protectedApps.keys + protectedFileSettings.keys).ifEmpty { setOf(0x123456) }.toList()

        override fun connect(): CardResult<Unit> = CardResult.ok(Unit)
        override fun disconnect() = Unit

        override fun identify(): CardResult<CardIdentity> = CardResult.ok(
            CardIdentity(byteArrayOf(1, 2, 3, 4), CardTechnology.MIFARE_DESFIRE, "test")
        )

        override fun execute(command: CardCommand): CardResult<CardResponse> = when (command) {
            DesfireGetVersion -> CardResult.ok(
                CardResponse.DesfireVersion(
                    hardwareVendor = 0x04,
                    hardwareType = 0x01,
                    hardwareSubType = 0,
                    hardwareMajor = 1,
                    hardwareMinor = 0,
                    hardwareStorageSize = 0x1A,
                    hardwareProtocol = 0x05,
                    softwareVendor = 0x04,
                    softwareType = 0x01,
                    softwareSubType = 0,
                    softwareMajor = 1,
                    softwareMinor = 0,
                    softwareStorageSize = 0x1A,
                    softwareProtocol = 0x05
                )
            )

            DesfireGetFreeMemory -> CardResult.ok(CardResponse.DesfireFreeMemory(4096))

            is DesfireListApplications -> {
                if (directoryKey == null) {
                    CardResult.ok(CardResponse.ApplicationIds(aids, wasAuthenticated = false))
                } else if (command.piccMasterKey != null && sameKey(command.piccMasterKey, directoryKey)) {
                    recordAuthentication(command.piccMasterKey)
                    CardResult.ok(CardResponse.ApplicationIds(aids, wasAuthenticated = true))
                } else {
                    command.piccMasterKey?.let(::recordAuthentication)
                    CardResult.fail(CardError.PERMISSION_DENIED, "PICC directory listing denied")
                }
            }

            is DesfireReadApplicationSettings -> {
                command.key?.takeIf { command.authenticateBeforeRead }?.let(::recordAuthentication)
                val expected = protectedApps[command.appId]
                when {
                    expected == null -> CardResult.ok(publicSettings().copy(wasAuthenticated = command.key != null))
                    command.key != null && sameKey(command.key, expected) -> CardResult.ok(
                        publicSettings().copy(wasAuthenticated = true)
                    )
                    else -> CardResult.fail(CardError.PERMISSION_DENIED, "Application settings denied")
                }
            }

            is DesfireAuthenticate -> {
                recordAuthentication(command.key)
                val expected = protectedApps[command.appId]
                if (expected != null && sameKey(command.key, expected)) {
                    CardResult.ok(CardResponse.Empty)
                } else {
                    CardResult.fail(CardError.AUTH_FAILURE, "Wrong key")
                }
            }

            is DesfireListFiles -> {
                command.key?.takeIf { command.authenticateBeforeRead }?.let(::recordAuthentication)
                val expected = protectedApps[command.appId]
                when {
                    expected == null -> CardResult.ok(
                        CardResponse.DesfireFileIds(listOf(1), wasAuthenticated = command.key != null)
                    )
                    command.key != null && sameKey(command.key, expected) -> CardResult.ok(
                        CardResponse.DesfireFileIds(listOf(1), wasAuthenticated = true)
                    )
                    else -> CardResult.fail(CardError.PERMISSION_DENIED, "File listing denied")
                }
            }

            is DesfireReadFileSettings -> {
                command.key?.takeIf { command.authenticateBeforeRead }?.let(::recordAuthentication)
                val expected = protectedFileSettings[command.appId] ?: protectedApps[command.appId]
                when {
                    expected == null || (command.key != null && sameKey(command.key, expected)) -> CardResult.ok(
                        CardResponse.DesfireFileSettings(
                            fileNo = command.fileNo,
                            fileType = DesfireFileType.STANDARD_DATA,
                            communicationMode = DesfireCommunicationMode.PLAIN,
                            accessRights = DesfireAccessRights(0, 0, 0, 0),
                            size = 32,
                            wasAuthenticated = expected != null
                        )
                    )
                    else -> CardResult.fail(CardError.PERMISSION_DENIED, "File settings denied")
                }
            }

            else -> CardResult.fail(CardError.UNKNOWN, "Unsupported fake command ${command.javaClass.simpleName}")
        }

        private fun recordAuthentication(key: DesfireKey) {
            authenticationAttempts++
            authenticatedKeys += key.bytes.copyOf()
        }

        private fun publicSettings() = CardResponse.DesfireApplicationSettings(
            keySettings = 0x0B,
            maxKeys = 5,
            keyType = DesfireKeyType.AES,
            wasAuthenticated = false
        )

        private fun sameKey(left: DesfireKey, right: DesfireKey): Boolean =
            left.type == right.type &&
                left.number == right.number &&
                left.bytes.contentEquals(right.bytes)
    }
}
