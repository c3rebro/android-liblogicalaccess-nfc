package de.shansen.rfusecase

import de.shansen.rfcard.CardBackend
import de.shansen.rfcard.CardError
import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardResult
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireFormatCard
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireListApplications

/**
 * Read-only information gathered before the destructive FORMAT_PICC command is authorized.
 */
data class DesfireFormatPreflight(
    val identity: CardIdentity,
    val version: CardResponse.DesfireVersion?,
    val visibleApplicationIds: List<Int>?,
    val warnings: List<String> = emptyList()
) {
    val confirmationPhrase: String
        get() = "FORMAT ${identity.uid.toHex()}"
}

/**
 * Opaque proof that the user confirmed the exact card UID from a preflight scan.
 * Callers cannot construct this directly; use [confirm].
 */
class DesfireFormatAuthorization private constructor(
    internal val expectedUid: ByteArray
) {
    companion object {
        fun confirm(preflight: DesfireFormatPreflight, typedPhrase: String): DesfireFormatAuthorization {
            require(typedPhrase == preflight.confirmationPhrase) {
                "Confirmation must exactly match '${preflight.confirmationPhrase}'."
            }
            return DesfireFormatAuthorization(preflight.identity.uid.copyOf())
        }
    }
}

enum class DesfireFormatStatus {
    SUCCESS_VERIFIED,
    SUCCESS_UNVERIFIED,
    FORMAT_FAILED,
    VERIFICATION_FAILED,
    CARD_MISMATCH
}

data class DesfireFormatResult(
    val status: DesfireFormatStatus,
    val identity: CardIdentity?,
    val formatCommandSent: Boolean,
    val formatError: CardError? = null,
    val message: String? = null,
    val remainingApplicationIds: List<Int>? = null
) {
    /** True only when the destructive operation was positively verified afterwards. */
    val verifiedSuccess: Boolean
        get() = status == DesfireFormatStatus.SUCCESS_VERIFIED

    /** The card/backend reported FORMAT_PICC success, even if verification was unavailable/failed. */
    val commandReportedSuccess: Boolean
        get() = status == DesfireFormatStatus.SUCCESS_VERIFIED ||
            status == DesfireFormatStatus.SUCCESS_UNVERIFIED ||
            status == DesfireFormatStatus.VERIFICATION_FAILED
}

/**
 * Built-in destructive DESFire format workflow.
 *
 * Safety invariants:
 * - preflight is read-only;
 * - execution requires an authorization created from the preflight confirmation phrase;
 * - the card UID is checked again before FORMAT_PICC is sent;
 * - FORMAT_PICC is sent at most once per execute() call;
 * - transport/auth failures never trigger an automatic destructive retry.
 */
class DesfireFormatUseCase {
    fun preflight(backend: CardBackend): CardResult<DesfireFormatPreflight> {
        val connect = backend.connect()
        if (!connect.isSuccess) {
            return CardResult.fail(connect.error, connect.message ?: "Unable to connect for DESFire format preflight.")
        }

        return try {
            val identityResult = backend.identify()
            val identity = identityResult.value
            if (!identityResult.isSuccess || identity == null) {
                return CardResult.fail(
                    identityResult.error,
                    identityResult.message ?: "Unable to identify card for DESFire format preflight."
                )
            }
            if (identity.technology != CardTechnology.MIFARE_DESFIRE) {
                return CardResult.fail(
                    CardError.PROTOCOL_CONSTRAINT,
                    "Format use case requires a MIFARE DESFire card."
                )
            }

            val warnings = mutableListOf<String>()
            val versionResult = backend.execute(DesfireGetVersion)
            val version = if (versionResult.isSuccess) {
                versionResult.value as? CardResponse.DesfireVersion
            } else {
                warnings += versionResult.message ?: "DESFire version could not be read during preflight."
                null
            }

            val directoryResult = backend.execute(DesfireListApplications())
            val applications = if (directoryResult.isSuccess) {
                (directoryResult.value as? CardResponse.ApplicationIds)?.aids
            } else {
                warnings += directoryResult.message
                    ?: "Application directory is protected or unavailable; preflight application count is unknown."
                null
            }

            CardResult.ok(
                DesfireFormatPreflight(
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
        authorization: DesfireFormatAuthorization,
        piccMasterKey: DesfireKey
    ): DesfireFormatResult {
        val connect = backend.connect()
        if (!connect.isSuccess) {
            return DesfireFormatResult(
                status = DesfireFormatStatus.FORMAT_FAILED,
                identity = null,
                formatCommandSent = false,
                formatError = connect.error,
                message = connect.message ?: "Unable to connect for DESFire format."
            )
        }

        return try {
            val identityResult = backend.identify()
            val identity = identityResult.value
            if (!identityResult.isSuccess || identity == null) {
                return DesfireFormatResult(
                    status = DesfireFormatStatus.FORMAT_FAILED,
                    identity = identity,
                    formatCommandSent = false,
                    formatError = identityResult.error,
                    message = identityResult.message ?: "Unable to identify card before format."
                )
            }

            if (identity.technology != CardTechnology.MIFARE_DESFIRE ||
                !identity.uid.contentEquals(authorization.expectedUid)
            ) {
                return DesfireFormatResult(
                    status = DesfireFormatStatus.CARD_MISMATCH,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = false,
                    formatError = CardError.PROTOCOL_CONSTRAINT,
                    message = "Presented card does not match the DESFire card confirmed during preflight."
                )
            }

            val format = backend.execute(DesfireFormatCard(piccMasterKey))
            if (!format.isSuccess) {
                return DesfireFormatResult(
                    status = DesfireFormatStatus.FORMAT_FAILED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = true,
                    formatError = format.error,
                    message = format.message ?: "DESFire FORMAT_PICC failed. No automatic retry was attempted."
                )
            }

            // Verification is read-only. Use the same PICC key if directory listing requires auth.
            val versionVerification = backend.execute(DesfireGetVersion)
            val directoryVerification = backend.execute(DesfireListApplications(piccMasterKey))
            val applicationIds = if (directoryVerification.isSuccess) {
                (directoryVerification.value as? CardResponse.ApplicationIds)?.aids
            } else {
                null
            }

            when {
                applicationIds != null && applicationIds.isEmpty() -> DesfireFormatResult(
                    status = DesfireFormatStatus.SUCCESS_VERIFIED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = true,
                    remainingApplicationIds = emptyList(),
                    message = "FORMAT_PICC succeeded and the application directory is empty."
                )

                applicationIds != null -> DesfireFormatResult(
                    status = DesfireFormatStatus.VERIFICATION_FAILED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = true,
                    remainingApplicationIds = applicationIds.toList(),
                    message = "FORMAT_PICC returned success, but applications are still visible. The command was not retried."
                )

                versionVerification.isSuccess -> DesfireFormatResult(
                    status = DesfireFormatStatus.SUCCESS_UNVERIFIED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = true,
                    message = "FORMAT_PICC returned success. Card communication recovered, but the application directory could not be verified."
                )

                else -> DesfireFormatResult(
                    status = DesfireFormatStatus.SUCCESS_UNVERIFIED,
                    identity = identity.copy(uid = identity.uid.copyOf()),
                    formatCommandSent = true,
                    message = "FORMAT_PICC returned success, but post-format verification was unavailable. Rescan the card before taking further action."
                )
            }
        } finally {
            backend.disconnect()
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
