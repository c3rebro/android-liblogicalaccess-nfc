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

enum class RfidGearActionReadiness(val previewStatus: String) {
    QUICK_CHECK_ENABLED("ENABLED"),
    QUICK_CHECK_NOT_MAPPED("NOT_MAPPED"),
    MUTATING_DISABLED("WRITE_DISABLED"),
    DESTRUCTIVE_DISABLED("DESTRUCTIVE_DISABLED"),
    UNSUPPORTED("UNSUPPORTED")
}

data class RfidGearActionSafety(
    val readiness: RfidGearActionReadiness,
    val operationLabel: String,
    val reason: String
) {
    val canRunOnCurrentAndroidBackend: Boolean
        get() = readiness == RfidGearActionReadiness.QUICK_CHECK_ENABLED

    fun previewLine(): String =
        "${readiness.previewStatus} $operationLabel - $reason"
}

/**
 * Single policy boundary between RFIDGear project compilation and Android card execution.
 *
 * The compiler may understand future encoder operations before the Android backend is allowed
 * to execute them. This policy keeps the preview and future execution gate aligned.
 */
object RfidGearActionSafetyPolicy {
    fun evaluate(action: RfidGearAction): RfidGearActionSafety = when (action) {
        is RfidGearAction.CheckApplicationExists -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.QUICK_CHECK_ENABLED,
            operationLabel = "AppExistCheck",
            reason = "read-only application directory check"
        )

        is RfidGearAction.CheckApplicationKeyCount -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.QUICK_CHECK_ENABLED,
            operationLabel = "CheckAppKeyCount",
            reason = "read-only application settings check"
        )

        is RfidGearAction.Execute -> evaluateCommand(action.command)

        is RfidGearAction.Unsupported -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.UNSUPPORTED,
            operationLabel = "Unsupported",
            reason = action.reason
        )
    }

    private fun evaluateCommand(command: CardCommand): RfidGearActionSafety = when (command) {
        DesfireGetVersion,
        DesfireGetFreeMemory,
        is DesfireListApplications,
        is DesfireReadApplicationSettings,
        is DesfireListFiles,
        is DesfireReadFileSettings -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.QUICK_CHECK_ENABLED,
            operationLabel = command.javaClass.simpleName,
            reason = "mapped by the current native Quick Check backend"
        )

        is DesfireAuthenticate -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.QUICK_CHECK_ENABLED,
            operationLabel = command.javaClass.simpleName,
            reason = "session authentication only; card data is not changed"
        )

        is DesfireReadData -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.QUICK_CHECK_NOT_MAPPED,
            operationLabel = command.javaClass.simpleName,
            reason = "read-only, but native file-data reading is not implemented yet"
        )

        is DesfireCreateApplication,
        is DesfireCreateFile,
        is DesfireChangeFileSettings,
        is DesfireChangeKey,
        is DesfireChangeKeySettings,
        is DesfireWriteData -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.MUTATING_DISABLED,
            operationLabel = command.javaClass.simpleName,
            reason = "would modify card data; encoder execution is not enabled yet"
        )

        is DesfireDeleteApplication,
        is DesfireDeleteFile,
        is DesfireFormatCard -> RfidGearActionSafety(
            readiness = RfidGearActionReadiness.DESTRUCTIVE_DISABLED,
            operationLabel = command.javaClass.simpleName,
            reason = "would delete or format card data; blocked on Android"
        )
    }
}
