package de.shansen.rfidgearruntime

import de.shansen.rfcard.CardCommand
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireChangeFileSettings
import de.shansen.rfcard.DesfireChangeKey
import de.shansen.rfcard.DesfireChangeKeySettings
import de.shansen.rfcard.DesfireCreateApplication
import de.shansen.rfcard.DesfireCreateFile
import de.shansen.rfcard.DesfireDeleteApplication
import de.shansen.rfcard.DesfireDeleteFile
import de.shansen.rfcard.DesfireFormatCard
import de.shansen.rfcard.DesfireGetFreeMemory
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireListApplications
import de.shansen.rfcard.DesfireListFiles
import de.shansen.rfcard.DesfireReadApplicationSettings
import de.shansen.rfcard.DesfireReadData
import de.shansen.rfcard.DesfireReadFileSettings
import de.shansen.rfcard.DesfireWriteData

enum class RfidGearActionSafetyKind {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE,
    UNSUPPORTED
}

enum class RfidGearBackendReadiness {
    SUPPORTED,
    NOT_SUPPORTED
}

data class RfidGearActionSafety(
    val kind: RfidGearActionSafetyKind,
    val operationLabel: String,
    val reason: String
) {
    val safeToRun: Boolean
        get() = kind == RfidGearActionSafetyKind.READ_ONLY
}

data class RfidGearActionEvaluation(
    val safety: RfidGearActionSafety,
    val backendReadiness: RfidGearBackendReadiness
) {
    val canRunOnCurrentAndroidBackend: Boolean
        get() = safety.safeToRun && backendReadiness == RfidGearBackendReadiness.SUPPORTED

    val previewStatus: String
        get() = when (safety.kind) {
            RfidGearActionSafetyKind.READ_ONLY -> when (backendReadiness) {
                RfidGearBackendReadiness.SUPPORTED -> "ENABLED"
                RfidGearBackendReadiness.NOT_SUPPORTED -> "NOT_MAPPED"
            }
            RfidGearActionSafetyKind.MUTATING -> "WRITE_DISABLED"
            RfidGearActionSafetyKind.DESTRUCTIVE -> "DESTRUCTIVE_DISABLED"
            RfidGearActionSafetyKind.UNSUPPORTED -> "UNSUPPORTED"
        }

    fun previewLine(): String = buildString {
        append(previewStatus)
        append(' ')
        append(safety.operationLabel)
        append(" - ")
        append(safety.reason)
        if (safety.safeToRun && backendReadiness == RfidGearBackendReadiness.NOT_SUPPORTED) {
            append("; not mapped by the current Android backend")
        }
    }
}

/**
 * Safety classifier for RFIDGear project actions.
 *
 * Safety and backend readiness are deliberately separate: a command may be read-only but not
 * supported by the current Android backend yet, and a future backend capability must not make
 * mutating commands executable without an explicit safety decision.
 */
object RfidGearActionSafetyPolicy {
    fun classify(action: RfidGearAction): RfidGearActionSafety = when (action) {
        is RfidGearAction.CheckApplicationExists -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.READ_ONLY,
            operationLabel = "AppExistCheck",
            reason = "read-only composite check; requires RFIDGear result evaluation"
        )

        is RfidGearAction.CheckApplicationKeyCount -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.READ_ONLY,
            operationLabel = "CheckAppKeyCount",
            reason = "read-only composite check; requires RFIDGear result evaluation"
        )

        is RfidGearAction.Execute -> classifyCommand(action.command)

        is RfidGearAction.Unsupported -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.UNSUPPORTED,
            operationLabel = "Unsupported",
            reason = action.reason
        )
    }

    fun evaluate(
        action: RfidGearAction,
        backendSupportsAction: (RfidGearAction) -> Boolean
    ): RfidGearActionEvaluation {
        val safety = classify(action)
        val readiness = if (safety.safeToRun && backendSupportsAction(action)) {
            RfidGearBackendReadiness.SUPPORTED
        } else {
            RfidGearBackendReadiness.NOT_SUPPORTED
        }
        return RfidGearActionEvaluation(safety, readiness)
    }

    private fun classifyCommand(command: CardCommand): RfidGearActionSafety = when (command) {
        DesfireGetVersion,
        DesfireGetFreeMemory,
        is DesfireListApplications,
        is DesfireReadApplicationSettings,
        is DesfireListFiles,
        is DesfireReadFileSettings -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.READ_ONLY,
            operationLabel = command.javaClass.simpleName,
            reason = "read-only card metadata command"
        )

        is DesfireAuthenticate -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.READ_ONLY,
            operationLabel = command.javaClass.simpleName,
            reason = "session authentication only; card data is not changed"
        )

        is DesfireReadData -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.READ_ONLY,
            operationLabel = command.javaClass.simpleName,
            reason = "read-only file-data command"
        )

        is DesfireCreateApplication,
        is DesfireCreateFile,
        is DesfireChangeFileSettings,
        is DesfireChangeKey,
        is DesfireChangeKeySettings,
        is DesfireWriteData -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.MUTATING,
            operationLabel = command.javaClass.simpleName,
            reason = "would modify card data; encoder execution is not enabled yet"
        )

        is DesfireDeleteApplication,
        is DesfireDeleteFile,
        is DesfireFormatCard -> RfidGearActionSafety(
            kind = RfidGearActionSafetyKind.DESTRUCTIVE,
            operationLabel = command.javaClass.simpleName,
            reason = "would delete or format card data; blocked on Android"
        )
    }
}
