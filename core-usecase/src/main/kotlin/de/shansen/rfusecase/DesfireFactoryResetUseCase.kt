package de.shansen.rfusecase

import de.shansen.rfcard.CardBackend
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
import de.shansen.rfcard.DesfireListApplications

/**
 * Read-only information gathered before a destructive DESFire factory reset is authorized.
 */
class DesfireFactoryResetPreflight internal constructor(
    val identity: CardIdentity,
    val version: CardResponse.DesfireVersion,
    val visibleApplicationIds: List<Int>?,
    val warnings: List<String> = emptyList()
) {
    private val confirmedUid = identity.uid.copyOf()

    val confirmationPhrase: String
        get() = "FACTORY RESET ${confirmedUid.toHex()}"

    internal fun authorizationUidCopy(): ByteArray = confirmedUid.copyOf()
}

/** One-shot proof that the user confirmed the exact card selected during preflight. */
class DesfireFactoryResetAuthorization private constructor(
    private val expectedUid: ByteArray
) {
    private var consumed = false

    internal fun matches(uid: ByteArray): Boolean = expectedUid.contentEquals(uid)

    internal fun consume(): Boolean = synchronized(this) {
        if (consumed) false else {
            consumed = true
            true
        }
    }

    companion object {
        fun confirm(
            preflight: DesfireFactoryResetPreflight,
            typedPhrase: String
        ): DesfireFactoryResetAuthorization {
            require(typedPhrase == preflight.confirmationPhrase) {
                "Confirmation must exactly match '${preflight.confirmationPhrase}'."
            }
            return DesfireFactoryResetAuthorization(preflight.authorizationUidCopy())
        }
    }
}

enum class DesfireFactoryResetStatus {
    SUCCESS_VERIFIED,
    SUCCESS_UNVERIFIED,
    FORMAT_FAILED,
    FORMATTED_KEY_RESET_FAILED,
    VERIFICATION_FAILED,
    CARD_MISMATCH,
    AUTHORIZATION_CONSUMED
}

data class DesfireFactoryResetResult(
    val status: DesfireFactoryResetStatus,
    val identity: CardIdentity?,
    /** Core invoked the backend's destructive FORMAT operation. */
    val formatOperationInvoked: Boolean,
    /** Core invoked the backend's PICC-master-key change operation. */
    val keyResetOperationInvoked: Boolean,
    val error: CardError? = null,
    val message: String? = null,
    val remainingApplicationIds: List<Int>? = null
) {
    val verifiedSuccess: Boolean
        get() = status == DesfireFactoryResetStatus.SUCCESS_VERIFIED

    val formatReportedSuccess: Boolean
        get() = status in setOf(
            DesfireFactoryResetStatus.SUCCESS_VERIFIED,
            DesfireFactoryResetStatus.SUCCESS_UNVERIFIED,
            DesfireFactoryResetStatus.FORMATTED_KEY_RESET_FAILED,
            DesfireFactoryResetStatus.VERIFICATION_FAILED
        )

    val keyResetReportedSuccess: Boolean
        get() = status in setOf(
            DesfireFactoryResetStatus.SUCCESS_VERIFIED,
            DesfireFactoryResetStatus.SUCCESS_UNVERIFIED,
            DesfireFactoryResetStatus.VERIFICATION_FAILED
        )
}

/**
 * Built-in destructive DESFire factory-reset workflow.
 *
 * Target state defined by this project:
 * - all applications/files are removed through FORMAT_PICC;
 * - PICC master key #0 is DES / 2K3DES-compatible, 16 zero bytes (32 hex zeros), version 0.
 *
 * This workflow intentionally does not alter PICC key-settings bits; only card content and the
 * PICC master key are part of the factory-reset contract at this stage.
 *
 * Safety invariants:
 * - preflight is read-only and requires a positive DESFire GetVersion response;
 * - confirmation is exact and UID-bound;
 * - authorization is one-shot;
 * - the card and DESFire protocol are re-verified immediately before destructive work;
 * - FORMAT and PICC-key reset are each invoked at most once;
 * - no destructive operation is automatically retried;
 * - if FORMAT succeeds but the key reset fails, the partial state is reported explicitly;
 * - verified success requires authentication with the new factory key and an empty application
 *   directory afterwards.
 */
class DesfireFactoryResetUseCase {
    fun preflight(backend: CardBackend): CardResult<DesfireFactoryResetPreflight> {
        val connect = backend.connect()
        if (!connect.isSuccess) {
            return CardResult.fail(
                connect.error,
                connect.message ?: "Unable to connect for DESFire factory-reset preflight."
            )
        }

        return try {
            val identityResult = backend.identify()
            val identity = identityResult.value
            if (!identityResult.isSuccess || identity == null) {
                return CardResult.fail(
                    identityResult.error,
                    identityResult.message ?: "Unable to identify card for DESFire factory-reset preflight."
                )
            }
            if (identity.technology != CardTechnology.MIFARE_DESFIRE) {
                return CardResult.fail(
                    CardError.PROTOCOL_CONSTRAINT,
                    "Factory Reset requires a MIFARE DESFire card."
                )
            }

            val versionResult = backend.execute(DesfireGetVersion)
            val version = versionResult.value as? CardResponse.DesfireVersion
            if (!versionResult.isSuccess || version == null) {
                return CardResult.fail(
                    if (versionResult.isSuccess) CardError.PROTOCOL_CONSTRAINT else versionResult.error,
                    versionResult.message
                        ?: "DESFire GetVersion probe failed; factory reset cannot be authorized."
                )
            }

            val warnings = mutableListOf<String>()
            val directoryResult = backend.execute(DesfireListApplications())
            val applications = if (directoryResult.isSuccess) {
                (directoryResult.value as? CardResponse.ApplicationIds)?.aids
            } else {
                warnings += directoryResult.message
                    ?: "Application directory is protected or unavailable; preflight application count is unknown."
                null
            }

            CardResult.ok(
                DesfireFactoryResetPreflight(
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    version = version,
                    visibleApplicationIds = applications?.toList(),
                    warnings = warnings
                )
            )
        } finally {
            backend.disconnect()
        }
    }

    fun execute(
        backend: CardBackend,
        authorization: DesfireFactoryResetAuthorization,
        currentPiccMasterKey: DesfireKey
    ): DesfireFactoryResetResult {
        if (currentPiccMasterKey.number != 0) {
            return failure(
                DesfireFactoryResetStatus.FORMAT_FAILED,
                error = CardError.PROTOCOL_CONSTRAINT,
                message = "DESFire Factory Reset requires the current PICC master key (key number 0)."
            )
        }

        val connect = backend.connect()
        if (!connect.isSuccess) {
            return failure(
                DesfireFactoryResetStatus.FORMAT_FAILED,
                error = connect.error,
                message = connect.message ?: "Unable to connect for DESFire factory reset."
            )
        }

        return try {
            val identityResult = backend.identify()
            val identity = identityResult.value
            if (!identityResult.isSuccess || identity == null) {
                return failure(
                    DesfireFactoryResetStatus.FORMAT_FAILED,
                    identity = identity,
                    error = identityResult.error,
                    message = identityResult.message ?: "Unable to identify card before factory reset."
                )
            }

            if (identity.technology != CardTechnology.MIFARE_DESFIRE ||
                !authorization.matches(identity.uid)
            ) {
                return failure(
                    DesfireFactoryResetStatus.CARD_MISMATCH,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    error = CardError.PROTOCOL_CONSTRAINT,
                    message = "Presented card does not match the DESFire card confirmed during factory-reset preflight."
                )
            }

            val versionProbe = backend.execute(DesfireGetVersion)
            if (!versionProbe.isSuccess || versionProbe.value !is CardResponse.DesfireVersion) {
                return failure(
                    DesfireFactoryResetStatus.FORMAT_FAILED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    error = if (versionProbe.isSuccess) CardError.PROTOCOL_CONSTRAINT else versionProbe.error,
                    message = versionProbe.message
                        ?: "DESFire GetVersion probe failed immediately before factory reset; no destructive operation was invoked."
                )
            }

            if (!authorization.consume()) {
                return failure(
                    DesfireFactoryResetStatus.AUTHORIZATION_CONSUMED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    error = CardError.PROTOCOL_CONSTRAINT,
                    message = "This factory-reset authorization has already been used. Run a new preflight and confirm again."
                )
            }

            val format = backend.execute(DesfireFormatCard(currentPiccMasterKey))
            if (!format.isSuccess) {
                return DesfireFactoryResetResult(
                    status = DesfireFactoryResetStatus.FORMAT_FAILED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatOperationInvoked = true,
                    keyResetOperationInvoked = false,
                    error = format.error,
                    message = format.message
                        ?: "DESFire format failed. The PICC master key was not changed and no automatic retry was attempted."
                )
            }

            val factoryKey = DesfireFactoryDefaults.piccMasterKey()
            try {
                val keyReset = backend.execute(
                    DesfireChangePiccMasterKey(
                        currentPiccMasterKey = currentPiccMasterKey,
                        newPiccMasterKey = factoryKey,
                        newKeyVersion = 0
                    )
                )
                if (!keyReset.isSuccess) {
                    return DesfireFactoryResetResult(
                        status = DesfireFactoryResetStatus.FORMATTED_KEY_RESET_FAILED,
                        identity = identity.copy(uid = identity.uid.copyOf()),
                        formatOperationInvoked = true,
                        keyResetOperationInvoked = true,
                        error = keyReset.error,
                        message = keyReset.message
                            ?: "FORMAT succeeded, but resetting the PICC master key to the factory DES zero key failed. No destructive retry was attempted."
                    )
                }

                val authVerification = backend.execute(DesfireAuthenticate(appId = 0, key = factoryKey))
                if (!authVerification.isSuccess) {
                    return DesfireFactoryResetResult(
                        status = DesfireFactoryResetStatus.VERIFICATION_FAILED,
                        identity = identity.copy(uid = identity.uid.copyOf()),
                        formatOperationInvoked = true,
                        keyResetOperationInvoked = true,
                        error = authVerification.error,
                        message = "Format and PICC-key reset reported success, but authentication with the factory DES zero key failed."
                    )
                }

                val directoryVerification = backend.execute(DesfireListApplications(factoryKey))
                val applicationIds = if (directoryVerification.isSuccess) {
                    (directoryVerification.value as? CardResponse.ApplicationIds)?.aids
                } else {
                    null
                }

                when {
                    applicationIds != null && applicationIds.isEmpty() ->
                        DesfireFactoryResetResult(
                            status = DesfireFactoryResetStatus.SUCCESS_VERIFIED,
                            identity = identity.copy(uid = identity.uid.copyOf()),
                            formatOperationInvoked = true,
                            keyResetOperationInvoked = true,
                            remainingApplicationIds = emptyList(),
                            message = "Factory Reset verified: application directory is empty and PICC master key #0 accepts the factory DES zero key."
                        )

                    applicationIds != null ->
                        DesfireFactoryResetResult(
                            status = DesfireFactoryResetStatus.VERIFICATION_FAILED,
                            identity = identity.copy(uid = identity.uid.copyOf()),
                            formatOperationInvoked = true,
                            keyResetOperationInvoked = true,
                            remainingApplicationIds = applicationIds.toList(),
                            message = "Factory key authentication succeeded, but applications are still visible after reset. No destructive retry was attempted."
                        )

                    else ->
                        DesfireFactoryResetResult(
                            status = DesfireFactoryResetStatus.SUCCESS_UNVERIFIED,
                            identity = identity.copy(uid = identity.uid.copyOf()),
                            formatOperationInvoked = true,
                            keyResetOperationInvoked = true,
                            error = if (directoryVerification.isSuccess) null else directoryVerification.error,
                            message = "Factory key authentication succeeded, but the empty application directory could not be verified."
                        )
                }
            } finally {
                factoryKey.clear()
            }
        } finally {
            backend.disconnect()
        }
    }

    private fun failure(
        status: DesfireFactoryResetStatus,
        identity: CardIdentity? = null,
        error: CardError? = null,
        message: String
    ) = DesfireFactoryResetResult(
        status = status,
        identity = identity,
        formatOperationInvoked = false,
        keyResetOperationInvoked = false,
        error = error,
        message = message
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
