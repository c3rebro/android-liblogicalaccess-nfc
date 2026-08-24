package de.shansen.rfusecase

import de.shansen.rfcard.CardBackend
import de.shansen.rfcard.CardCommand
import de.shansen.rfcard.CardError
import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardResult
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireChangePiccMasterKey
import de.shansen.rfcard.DesfireFactoryDefaults
import de.shansen.rfcard.DesfireFormatCard
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfcard.DesfireListApplications
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesfireFactoryResetUseCaseTest {
    private val uid = byteArrayOf(0x04, 0x11, 0x22, 0x33)
    private val currentKey = DesfireKey(ByteArray(16) { 0x5A }, DesfireKeyType.AES, 0)

    @Test
    fun factoryDefaultPiccKeyIsDesKeyZeroWithSixteenZeroBytes() {
        val key = DesfireFactoryDefaults.piccMasterKey()

        assertEquals(DesfireKeyType.DES, key.type)
        assertEquals(0, key.number)
        assertEquals(0, key.version)
        assertEquals(16, key.bytes.size)
        assertEquals(32, DesfireFactoryDefaults.PICC_MASTER_KEY_HEX_LENGTH)
        assertContentEquals(ByteArray(16), key.bytes)
    }

    @Test
    fun preflightIsReadOnlyAndUsesDistinctFactoryResetConfirmation() {
        val backend = FakeFactoryResetBackend(uid, initialApps = listOf(0x123456))

        val result = DesfireFactoryResetUseCase().preflight(backend)
        val preflight = requireNotNull(result.value)

        assertTrue(result.isSuccess)
        assertEquals("FACTORY RESET 04112233", preflight.confirmationPhrase)
        assertEquals(listOf(0x123456), preflight.visibleApplicationIds)
        assertEquals(0, backend.formatCalls)
        assertEquals(0, backend.keyResetCalls)
    }

    @Test
    fun authorizationRequiresExactPhrase() {
        val service = DesfireFactoryResetUseCase()
        val preflight = requireNotNull(service.preflight(FakeFactoryResetBackend(uid)).value)

        assertFailsWith<IllegalArgumentException> {
            DesfireFactoryResetAuthorization.confirm(preflight, "FORMAT 04112233")
        }
        assertFailsWith<IllegalArgumentException> {
            DesfireFactoryResetAuthorization.confirm(preflight, " ${preflight.confirmationPhrase} ")
        }
    }

    @Test
    fun differentCardIsRejectedBeforeAnyDestructiveOperation() {
        val service = DesfireFactoryResetUseCase()
        val preflight = requireNotNull(service.preflight(FakeFactoryResetBackend(uid)).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val other = FakeFactoryResetBackend(byteArrayOf(0x04, 0x55, 0x66, 0x77))

        val result = service.execute(other, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.CARD_MISMATCH, result.status)
        assertFalse(result.formatOperationInvoked)
        assertFalse(result.keyResetOperationInvoked)
        assertEquals(0, other.formatCalls)
        assertEquals(0, other.keyResetCalls)
    }

    @Test
    fun formatFailureDoesNotAttemptKeyResetOrRetry() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid, failFormat = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.FORMAT_FAILED, result.status)
        assertTrue(result.formatOperationInvoked)
        assertFalse(result.keyResetOperationInvoked)
        assertFalse(result.formatReportedSuccess)
        assertEquals(1, backend.formatCalls)
        assertEquals(0, backend.keyResetCalls)
    }

    @Test
    fun keyResetFailureReportsPartialStateWithoutReformatting() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid, initialApps = listOf(0x123456), failKeyReset = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.FORMATTED_KEY_RESET_FAILED, result.status)
        assertTrue(result.formatReportedSuccess)
        assertFalse(result.keyResetReportedSuccess)
        assertEquals(1, backend.formatCalls)
        assertEquals(1, backend.keyResetCalls)
        assertTrue(backend.apps.isEmpty())
    }

    @Test
    fun successfulFactoryResetIsVerifiedWithFactoryKeyAndEmptyDirectory() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid, initialApps = listOf(0x123456, 0x654321))
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.SUCCESS_VERIFIED, result.status)
        assertTrue(result.verifiedSuccess)
        assertTrue(result.formatReportedSuccess)
        assertTrue(result.keyResetReportedSuccess)
        assertEquals(1, backend.formatCalls)
        assertEquals(1, backend.keyResetCalls)
        assertEquals(1, backend.factoryAuthCalls)
        assertEquals(emptyList(), result.remainingApplicationIds)
        assertEquals(DesfireKeyType.DES, backend.currentPiccKeyType)
        assertContentEquals(ByteArray(16), backend.currentPiccKeyBytes)
    }

    @Test
    fun factoryKeyAuthenticationFailurePreventsVerifiedSuccess() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid, failFactoryAuth = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.VERIFICATION_FAILED, result.status)
        assertFalse(result.verifiedSuccess)
        assertEquals(1, backend.formatCalls)
        assertEquals(1, backend.keyResetCalls)
        assertEquals(1, backend.factoryAuthCalls)
    }

    @Test
    fun authorizationIsOneShotEvenAfterPartialFailure() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid, failKeyReset = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val first = service.execute(backend, authorization, currentKey)
        val second = service.execute(backend, authorization, currentKey)

        assertEquals(DesfireFactoryResetStatus.FORMATTED_KEY_RESET_FAILED, first.status)
        assertEquals(DesfireFactoryResetStatus.AUTHORIZATION_CONSUMED, second.status)
        assertEquals(1, backend.formatCalls)
        assertEquals(1, backend.keyResetCalls)
    }

    @Test
    fun currentPiccKeyMustBeKeyZero() {
        val service = DesfireFactoryResetUseCase()
        val backend = FakeFactoryResetBackend(uid)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val wrongKey = DesfireKey(ByteArray(16), DesfireKeyType.AES, 1)
        val connectsBefore = backend.connectCalls

        val result = service.execute(backend, authorization, wrongKey)

        assertEquals(DesfireFactoryResetStatus.FORMAT_FAILED, result.status)
        assertEquals(CardError.PROTOCOL_CONSTRAINT, result.error)
        assertEquals(connectsBefore, backend.connectCalls)
        assertEquals(0, backend.formatCalls)
    }

    private class FakeFactoryResetBackend(
        private val uid: ByteArray,
        initialApps: List<Int> = emptyList(),
        private val failFormat: Boolean = false,
        private val failKeyReset: Boolean = false,
        private val failFactoryAuth: Boolean = false
    ) : CardBackend {
        val apps = initialApps.toMutableList()
        var currentPiccKeyType: DesfireKeyType = DesfireKeyType.AES
            private set
        var currentPiccKeyBytes: ByteArray = ByteArray(16) { 0x5A }
            private set
        var connectCalls = 0
            private set
        var formatCalls = 0
            private set
        var keyResetCalls = 0
            private set
        var factoryAuthCalls = 0
            private set

        override fun connect(): CardResult<Unit> {
            connectCalls++
            return CardResult.ok(Unit)
        }

        override fun disconnect() = Unit

        override fun identify(): CardResult<CardIdentity> = CardResult.ok(
            CardIdentity(uid.copyOf(), CardTechnology.MIFARE_DESFIRE, "fake")
        )

        override fun execute(command: CardCommand): CardResult<CardResponse> = when (command) {
            DesfireGetVersion -> CardResult.ok(
                CardResponse.DesfireVersion(
                    hardwareVendor = 4,
                    hardwareType = 1,
                    hardwareSubType = 0,
                    hardwareMajor = 1,
                    hardwareMinor = 0,
                    hardwareStorageSize = 0x1A,
                    hardwareProtocol = 5,
                    softwareVendor = 4,
                    softwareType = 1,
                    softwareSubType = 0,
                    softwareMajor = 3,
                    softwareMinor = 0,
                    softwareStorageSize = 0x1A,
                    softwareProtocol = 5
                )
            )

            is DesfireListApplications -> CardResult.ok(
                CardResponse.ApplicationIds(apps.toList(), wasAuthenticated = command.piccMasterKey != null)
            )

            is DesfireFormatCard -> {
                formatCalls++
                if (failFormat) {
                    CardResult.fail(CardError.AUTH_FAILURE, "format auth failed")
                } else {
                    apps.clear()
                    CardResult.ok(CardResponse.Empty)
                }
            }

            is DesfireChangePiccMasterKey -> {
                keyResetCalls++
                if (failKeyReset) {
                    CardResult.fail(CardError.AUTH_FAILURE, "key reset failed")
                } else {
                    currentPiccKeyType = command.newPiccMasterKey.type
                    currentPiccKeyBytes = command.newPiccMasterKey.bytes.copyOf()
                    CardResult.ok(CardResponse.Empty)
                }
            }

            is DesfireAuthenticate -> {
                if (command.appId == 0 &&
                    command.key.type == DesfireKeyType.DES &&
                    command.key.number == 0 &&
                    command.key.bytes.contentEquals(ByteArray(16))
                ) {
                    factoryAuthCalls++
                    if (failFactoryAuth) {
                        CardResult.fail(CardError.AUTH_FAILURE, "factory auth failed")
                    } else {
                        CardResult.ok(CardResponse.Empty)
                    }
                } else {
                    CardResult.fail(CardError.AUTH_FAILURE, "unexpected authentication key")
                }
            }

            else -> CardResult.fail(CardError.PROTOCOL_CONSTRAINT, "Unsupported fake command")
        }
    }
}
