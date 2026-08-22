package de.shansen.rfidgearruntime

import de.shansen.rfcard.*
import de.shansen.rfproject.RfProjectTask

sealed interface RfidGearAction {
    data class Execute(val command: CardCommand) : RfidGearAction

    /** Execute list-applications, then return NoError when targetAppId exists, IsNotTrue otherwise. */
    data class CheckApplicationExists(
        val command: DesfireListApplications,
        val targetAppId: Int
    ) : RfidGearAction

    /** Read app settings, then compare MaxKeys with expectedCount. */
    data class CheckApplicationKeyCount(
        val command: DesfireReadApplicationSettings,
        val expectedCount: Int
    ) : RfidGearAction

    data class Unsupported(val reason: String) : RfidGearAction
}

data class CompiledRfidGearTask(
    val taskId: String,
    val modelType: String,
    val operation: String?,
    val action: RfidGearAction
)

class RfidGearCompileException(
    val field: String,
    message: String
) : IllegalArgumentException("$field: $message")

/**
 * Converts persisted RFIDGear task fields into platform-neutral card actions.
 *
 * This compiler is intentionally strict. It does not invent defaults for missing
 * security-sensitive fields and does not print key values in exception messages.
 */
object RfidGearTaskCompiler {
    fun compile(task: RfProjectTask): CompiledRfidGearTask {
        val control = task.control()
        val operation = control.taskType

        val action = when (task.typeName) {
            "MifareDesfireSetupViewModel" -> compileDesfire(task, operation)
            else -> RfidGearAction.Unsupported(
                "Task model '${task.typeName}' is not mapped to a card backend yet."
            )
        }

        return CompiledRfidGearTask(
            taskId = control.id,
            modelType = task.typeName,
            operation = operation,
            action = action
        )
    }

    private fun compileDesfire(task: RfProjectTask, operation: String?): RfidGearAction = when (operation) {
        "AuthenticateApplication" -> RfidGearAction.Execute(
            DesfireAuthenticate(
                appId = task.appId("AppNumberCurrent"),
                key = task.desfireKey(
                    valueField = "DesfireAppKeyCurrent",
                    typeField = "SelectedDesfireAppKeyEncryptionTypeCurrent",
                    numberField = "SelectedDesfireAppKeyNumberCurrent"
                )
            )
        )

        "AppExistCheck" -> RfidGearAction.CheckApplicationExists(
            command = DesfireListApplications(
                piccMasterKey = task.desfireKey(
                    valueField = "DesfireAppKeyCurrent",
                    typeField = "SelectedDesfireAppKeyEncryptionTypeCurrent",
                    fixedNumber = 0
                )
            ),
            targetAppId = task.appId("AppNumberCurrent")
        )

        "ReadAppSettings" -> RfidGearAction.Execute(
            DesfireReadApplicationSettings(
                appId = task.appId("AppNumberCurrent"),
                key = task.desfireKey(
                    valueField = "DesfireAppKeyCurrent",
                    typeField = "SelectedDesfireAppKeyEncryptionTypeCurrent",
                    numberField = "SelectedDesfireAppKeyNumberCurrent"
                ),
                authenticateBeforeRead = true
            )
        )

        "CheckAppKeyCount" -> RfidGearAction.CheckApplicationKeyCount(
            command = DesfireReadApplicationSettings(
                appId = task.appId("AppNumberCurrent"),
                key = task.desfireKey(
                    valueField = "DesfireAppKeyCurrent",
                    typeField = "SelectedDesfireAppKeyEncryptionTypeCurrent",
                    numberField = "SelectedDesfireAppKeyNumberCurrent"
                ),
                authenticateBeforeRead = true
            ),
            expectedCount = task.decimalInt("SelectedDesfireAppMaxNumberOfKeys", 1, 16)
        )

        "FormatDesfireCard" -> RfidGearAction.Execute(
            DesfireFormatCard(
                piccMasterKey = task.desfireKey(
                    valueField = "DesfireMasterKeyCurrent",
                    typeField = "SelectedDesfireMasterKeyEncryptionTypeCurrent",
                    fixedNumber = 0
                )
            )
        )

        "CreateApplication" -> RfidGearAction.Execute(
            DesfireCreateApplication(
                appId = task.appId("AppNumberNew"),
                piccMasterKey = task.desfireKey(
                    valueField = "DesfireMasterKeyCurrent",
                    typeField = "SelectedDesfireMasterKeyEncryptionTypeCurrent",
                    fixedNumber = 0
                ),
                targetKeyType = task.keyType("SelectedDesfireAppKeyEncryptionTypeCreateNewApp"),
                maxKeys = task.decimalInt("SelectedDesfireAppMaxNumberOfKeys", 1, 16),
                keySettings = task.createApplicationKeySettings(),
                authenticateToPiccFirst = true
            )
        )

        "DeleteApplication" -> RfidGearAction.Execute(
            // RFIDGear's current implementation uses AppNumberNew for the application to delete.
            DesfireDeleteApplication(
                appId = task.appId("AppNumberNew"),
                piccMasterKey = task.desfireKey(
                    valueField = "DesfireMasterKeyCurrent",
                    typeField = "SelectedDesfireMasterKeyEncryptionTypeCurrent",
                    fixedNumber = 0
                )
            )
        )

        "CreateFile" -> RfidGearAction.Execute(
            DesfireCreateFile(
                appId = task.appId("AppNumberCurrent"),
                appMasterKey = task.desfireKey(
                    valueField = "DesfireAppKeyCurrent",
                    typeField = "SelectedDesfireAppKeyEncryptionTypeCurrent",
                    fixedNumber = 0
                ),
                fileNo = task.byteValue("FileNumberCurrent"),
                fileType = task.fileType("SelectedDesfireFileType"),
                size = task.decimalInt("FileSizeCurrent", 0, Int.MAX_VALUE),
                communicationMode = task.communicationMode("SelectedDesfireFileCryptoMode"),
                accessRights = DesfireAccessRights(
                    read = task.accessRight("SelectedDesfireFileAccessRightRead"),
                    write = task.accessRight("SelectedDesfireFileAccessRightWrite"),
                    readWrite = task.accessRight("SelectedDesfireFileAccessRightReadWrite"),
                    change = task.accessRight("SelectedDesfireFileAccessRightChange")
                )
            )
        )

        "ReadData" -> RfidGearAction.Execute(
            DesfireReadData(
                appId = task.appId("AppNumberCurrent"),
                fileNo = task.byteValue("FileNumberCurrent"),
                length = task.decimalInt("FileSizeCurrent", 1, Int.MAX_VALUE),
                readKey = task.desfireKey(
                    valueField = "DesfireReadKeyCurrent",
                    typeField = "SelectedDesfireReadKeyEncryptionType",
                    numberField = "SelectedDesfireReadKeyNumber"
                ),
                communicationMode = task.communicationMode("SelectedDesfireFileCryptoMode")
            )
        )

        "WriteData" -> RfidGearAction.Unsupported(
            "WriteData payload is stored in RFIDGear's serialized data-explorer hierarchy; offset/length semantics must be mapped before card writes are enabled."
        )

        "DeleteFile" -> RfidGearAction.Unsupported(
            "RFIDGear currently uses different application fields in the authenticated and fallback DeleteFile paths; this ambiguity must be resolved before Android enables deletion."
        )

        "ApplicationKeyChangeover",
        "ApplicationKeySettingsChangeover",
        "PICCMasterKeyChangeover",
        "PICCMasterKeySettingsChangeover",
        "ChangeFileSettings" -> RfidGearAction.Unsupported(
            "$operation requires key-policy/file-setting mapping that is not enabled yet."
        )

        "None", null, "" -> RfidGearAction.Unsupported("No DESFire operation is configured.")
        else -> RfidGearAction.Unsupported("DESFire operation '$operation' is unknown to this runtime version.")
    }

    private fun RfProjectTask.requireText(field: String): String =
        text(field) ?: throw RfidGearCompileException(field, "required project field is missing or empty")

    private fun RfProjectTask.appId(field: String): Int {
        val raw = requireText(field).trim()
        val result = if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toIntOrNull(16)
        } else {
            raw.toIntOrNull(10)
        } ?: throw RfidGearCompileException(field, "must be a decimal value or 0x-prefixed hexadecimal value")

        if (result !in 0..0xFFFFFF) {
            throw RfidGearCompileException(field, "DESFire application ID must be between 0 and 0xFFFFFF")
        }
        return result
    }

    private fun RfProjectTask.byteValue(field: String): Int {
        val raw = requireText(field).trim()
        val result = if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toIntOrNull(16)
        } else {
            raw.toIntOrNull(10)
        } ?: throw RfidGearCompileException(field, "must be a decimal value or 0x-prefixed hexadecimal byte")

        if (result !in 0..255) throw RfidGearCompileException(field, "must be between 0 and 255")
        return result
    }

    private fun RfProjectTask.decimalInt(field: String, min: Int, max: Int): Int {
        val value = requireText(field).trim().toIntOrNull()
            ?: throw RfidGearCompileException(field, "must be a decimal integer")
        if (value !in min..max) throw RfidGearCompileException(field, "must be between $min and $max")
        return value
    }

    private fun RfProjectTask.keyType(field: String): DesfireKeyType = when (requireText(field).trim()) {
        "DF_KEY_DES" -> DesfireKeyType.DES
        "DF_KEY_3K3DES" -> DesfireKeyType.TDES_3K
        "DF_KEY_AES" -> DesfireKeyType.AES
        else -> throw RfidGearCompileException(field, "unsupported RFIDGear DESFire key type")
    }

    private fun RfProjectTask.desfireKey(
        valueField: String,
        typeField: String,
        numberField: String? = null,
        fixedNumber: Int? = null
    ): DesfireKey {
        val type = keyType(typeField)
        val compact = requireText(valueField).replace(" ", "")
        val expectedHexChars = if (type == DesfireKeyType.TDES_3K) 48 else 32
        if (compact.length != expectedHexChars || compact.any { it.digitToIntOrNull(16) == null }) {
            throw RfidGearCompileException(
                valueField,
                "invalid key format for $type; expected ${expectedHexChars / 2} bytes of hexadecimal key material"
            )
        }

        val keyBytes = ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val number = fixedNumber ?: byteValue(requireNotNull(numberField))
        if (number !in 0..15) throw RfidGearCompileException(numberField ?: "keyNumber", "DESFire key number must be between 0 and 15")
        return DesfireKey(keyBytes, type, number)
    }

    private fun RfProjectTask.communicationMode(field: String): DesfireCommunicationMode = when (requireText(field).trim()) {
        "CM_PLAIN" -> DesfireCommunicationMode.PLAIN
        "CM_MAC" -> DesfireCommunicationMode.MACED
        "CM_ENCRYPT" -> DesfireCommunicationMode.ENCIPHERED
        else -> throw RfidGearCompileException(field, "unsupported RFIDGear DESFire communication mode")
    }

    private fun RfProjectTask.fileType(field: String): DesfireFileType = when (requireText(field).trim()) {
        "StdDataFile" -> DesfireFileType.STANDARD_DATA
        "BackupFile" -> DesfireFileType.BACKUP_DATA
        "ValueFile" -> DesfireFileType.VALUE
        "LinearRecordFile" -> DesfireFileType.LINEAR_RECORD
        "CyclicRecordFile" -> DesfireFileType.CYCLIC_RECORD
        else -> throw RfidGearCompileException(field, "unsupported RFIDGear DESFire file type")
    }

    private fun RfProjectTask.accessRight(field: String): Int {
        val raw = requireText(field).trim()
        return when (raw) {
            "AR_FREE" -> 14
            "AR_NEVER" -> 15
            else -> {
                val keyNo = raw.removePrefix("AR_KEY").takeIf { raw.startsWith("AR_KEY") }?.toIntOrNull()
                    ?: raw.toIntOrNull()
                    ?: throw RfidGearCompileException(field, "unsupported RFIDGear DESFire access right")
                if (keyNo !in 0..13) throw RfidGearCompileException(field, "access key must be 0..13, AR_FREE or AR_NEVER")
                keyNo
            }
        }
    }

    private fun RfProjectTask.bool(field: String): Boolean = when (requireText(field).trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw RfidGearCompileException(field, "must be true or false")
    }

    private fun RfProjectTask.createApplicationKeySettings(): Int {
        val changeMode = when (requireText("SelectedDesfireAppKeySettingsCreateNewApp").trim()) {
            "ChangeKeyUsingMK", "ChangeKeyWithMasterKey" -> 0x00
            "ChangeKeyUsingKeyNo", "ChangeKeyWithTargetedKeyNumber" -> 0xE0
            "ChangeKeyFrozen" -> 0xF0
            else -> throw RfidGearCompileException(
                "SelectedDesfireAppKeySettingsCreateNewApp",
                "unsupported DESFire change-key policy"
            )
        }

        var settings = changeMode
        if (bool("IsAllowChangeMKChecked")) settings = settings or 0x01
        if (bool("IsAllowListingWithoutMKChecked")) settings = settings or 0x02
        if (bool("IsAllowCreateDelWithoutMKChecked")) settings = settings or 0x04
        if (bool("IsAllowConfigChangableChecked")) settings = settings or 0x08
        return settings and 0xFF
    }
}
