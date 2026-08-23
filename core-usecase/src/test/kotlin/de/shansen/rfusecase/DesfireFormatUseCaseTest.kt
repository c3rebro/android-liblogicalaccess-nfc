package de.shansen.rfusecase

import de.shansen.rfcard.CardBackend
import de.shansen.rfcard.CardCommand
import de.shansen.rfcard.CardError
import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardResult
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireFormatCard
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfcard.DesfireListApplications
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesfireFormatUseCaseTest {
    private val key = DesfireKey(ByteArray(16), DesfireKeyType.AES, 0)
    private val uid = byteArrayOf(0x04, 0x01, 0x02, 0x03)

    @Test
    fun preflightIsReadOnlyAndBuildsUidBoundConfirmation() {
        val backend = FakeFormatBackend(uid = uid, initialApps = listOf(0x123456))

        val result = DesfireFormatUseCase().preflight(backend)
        val preflight = requireNotNull(result.value)

        assertTrue(result.isSuccess)
        assertEquals("FORMAT 04010203", preflight.confirmationPhrase)
        assertEquals(listOf(0x123456), preflight.visibleApplicationIds)
        assertEquals(0, backend.formatCalls)
    }

    @Test
    fun mutatingPublicIdentityUidCannotChangeAuthorizationTarget() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(uid = uid, initialApps = listOf(0x123456))
        val preflight = requireNotNull(service.preflight(backend).value)
        val originalPhrase = preflight.confirmationPhrase

        preflight.identity.uid.fill(0x7F)

        assertEquals("FORMAT 04010203", originalPhrase)
        assertEquals(originalPhrase, preflight.confirmationPhrase)

        val authorization = DesfireFormatAuthorization.confirm(preflight, originalPhrase)
        val result = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.SUCCESS_VERIFIED, result.status)
        assertEquals(1, backend.formatCalls)
    }

    @Test
    fun preflightRequiresPositiveDesfireVersionProbe() {
        val backend = FakeFormatBackend(uid = uid, failVersion = true)

        val result = DesfireFormatUseCase().preflight(backend)

        assertFalse(result.isSuccess)
        assertEquals(CardError.PROTOCOL_CONSTRAINT, result.error)
        assertEquals(0, backend.formatCalls)
    }

    @Test
    fun authorizationRequiresExactConfirmationPhrase() {
        val backend = FakeFormatBackend(uid = uid)
        val preflight = requireNotNull(DesfireFormatUseCase().preflight(backend).value)

        assertFailsWith<IllegalArgumentException> {
            DesfireFormatAuthorization.confirm(preflight, "FORMAT OTHER")
        }
        assertFailsWith<IllegalArgumentException> {
            DesfireFormatAuthorization.confirm(preflight, " ${preflight.confirmationPhrase} ")
        }
    }

    @Test
    fun differentCardIsRejectedBeforeFormatCommand() {
        val service = DesfireFormatUseCase()
        val firstBackend = FakeFormatBackend(uid = uid)
        val preflight = requireNotNull(service.preflight(firstBackend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val secondBackend = FakeFormatBackend(uid = byteArrayOf(0x04, 0x55, 0x66, 0x77))
        val result = service.execute(secondBackend, authorization, key)

        assertEquals(DesfireFormatStatus.CARD_MISMATCH, result.status)
        assertFalse(result.formatCommandSent)
        assertFalse(result.commandReportedSuccess)
        assertFalse(result.verifiedSuccess)
        assertEquals(0, secondBackend.formatCalls)
    }

    @Test
    fun successfulFormatIsSentOnceAndVerifiedWithEmptyDirectory() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(uid = uid, initialApps = listOf(0x123456, 0x654321))
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.SUCCESS_VERIFIED, result.status)
        assertTrue(result.commandReportedSuccess)
        assertTrue(result.verifiedSuccess)
        assertTrue(result.formatCommandSent)
        assertEquals(1, backend.formatCalls)
        assertEquals(emptyList(), result.remainingApplicationIds)
    }

    @Test
    fun formatAuthorizationCanBeUsedForOnlyOneAttempt() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(uid = uid, failFormat = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val first = service.execute(backend, authorization, key)
        val second = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.FORMAT_FAILED, first.status)
        assertEquals(DesfireFormatStatus.AUTHORIZATION_CONSUMED, second.status)
        assertFalse(second.formatCommandSent)
        assertEquals(1, backend.formatCalls)
    }

    @Test
    fun formatSuccessWithoutDirectoryVerificationIsNotReportedAsVerified() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(
            uid = uid,
            initialApps = listOf(0x123456),
            failDirectoryAfterFormat = true
        )
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.SUCCESS_UNVERIFIED, result.status)
        assertTrue(result.commandReportedSuccess)
        assertFalse(result.verifiedSuccess)
        assertEquals(1, backend.formatCalls)
    }

    @Test
    fun failedFormatIsNeverRetriedAutomatically() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(uid = uid, failFormat = true)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)

        val result = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.FORMAT_FAILED, result.status)
        assertFalse(result.commandReportedSuccess)
        assertFalse(result.verifiedSuccess)
        assertEquals(CardError.AUTH_FAILURE, result.formatError)
        assertEquals(1, backend.formatCalls)
    }

    @Test
    fun failedDesfireProbeImmediatelyBeforeExecutionBlocksFormat() {
        val service = DesfireFormatUseCase()
        val backend = FakeFormatBackend(uid = uid)
        val preflight = requireNotNull(service.preflight(backend).value)
        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)
        backend.failVersion = true

        val result = service.execute(backend, authorization, key)

        assertEquals(DesfireFormatStatus.FORMAT_FAILED, result.status)
        assertFalse(result.formatCommandSent)
        assertEquals(0, backend.formatCalls)
    }

    @Test
    fun nonDesfireCardCannotReachAuthorizationStage() {
        val backend = FakeFormatBackend(uid = uid, technology = CardTechnology.MIFARE_CLASSIC)

        val result = DesfireFormatUseCase().preflight(backend)

        assertFalse(result.isSuccess)
        assertEquals(CardError.PROTOCOL_CONSTRAINT, result.error)
        assertEquals(0, backend.formatCalls)
    }

    private class FakeFormatBackend(
        private val uid: ByteArray,
        private val technology: CardTechnology = CardTechnology.MIFARE_DESFIRE,
        initialApps: List<Int> = emptyList(),
        private val failFormat: Boolean = false,
        private val failDirectoryAfterFormat: Boolean = false,
        failVersion: Boolean = false
    ) : CardBackend {
        private var apps = initialApps.toMutableList()
        private var formatCompleted = false
        var failVersion: Boolean = failVersion
        var formatCalls: Int = 0
            private set

        override fun connect(): CardResult<Unit> = CardResult.ok(Unit)
        override fun disconnect() = Unit

        override fun identify(): CardResult<CardIdentity> = CardResult.ok(
            CardIdentity(uid.copyOf(), technology, "fake")
        )

        override fun execute(command: CardCommand): CardResult<CardResponse> = when (command) {
            DesfireGetVersion -> {
                if (failVersion) {
                    CardResult.fail(CardError.PROTOCOL_CONSTRAINT, "DESFire GetVersion not supported")
                } else {
                    CardResult.ok(
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
                }
            }

            is DesfireListApplications -> {
                if (formatCompleted && failDirectoryAfterFormat) {
                    CardResult.fail(CardError.PERMISSION_DENIED, "Directory verification unavailable")
                } else {
                    CardResult.ok(
                        CardResponse.ApplicationIds(apps.toList(), wasAuthenticated = command.piccMasterKey != null)
                    )
                }
            }

            is DesfireFormatCard -> {
                formatCalls++
                if (failFormat) {
                    CardResult.fail(CardError.AUTH_FAILURE, "PICC authentication failed")
                } else {
                    apps.clear()
                    formatCompleted = true
                    CardResult.ok(CardResponse.Empty)
                }
            }

            else -> CardResult.fail(CardError.PROTOCOL_CONSTRAINT, "Unsupported fake command")
        }
    }
}
