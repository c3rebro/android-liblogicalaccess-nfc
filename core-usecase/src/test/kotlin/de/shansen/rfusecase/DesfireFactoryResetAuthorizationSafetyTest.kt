package de.shansen.rfusecase

import de.shansen.rfcard.CardBackend
import de.shansen.rfcard.CardCommand
import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardResult
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireListApplications
import kotlin.test.Test
import kotlin.test.assertEquals

class DesfireFactoryResetAuthorizationSafetyTest {
    @Test
    fun publicIdentityMutationCannotChangeFactoryResetConfirmationTarget() {
        val backend = PreflightBackend(byteArrayOf(0x04, 0x11, 0x22, 0x33))
        val preflight = requireNotNull(DesfireFactoryResetUseCase().preflight(backend).value)
        val originalPhrase = preflight.confirmationPhrase

        preflight.identity.uid.fill(0x7F)

        assertEquals("FACTORY RESET 04112233", originalPhrase)
        assertEquals(originalPhrase, preflight.confirmationPhrase)
        DesfireFactoryResetAuthorization.confirm(preflight, originalPhrase)
    }

    private class PreflightBackend(private val uid: ByteArray) : CardBackend {
        override fun connect(): CardResult<Unit> = CardResult.ok(Unit)
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
            is DesfireListApplications -> CardResult.ok(CardResponse.ApplicationIds(emptyList()))
            else -> error("Unexpected command during read-only preflight: ${command.javaClass.simpleName}")
        }
    }
}
