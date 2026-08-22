package de.shansen.rfcard

/**
 * Platform-neutral card access boundary.
 *
 * Implementations may use liblogicalaccess/Android NFC, a desktop reader,
 * or a deterministic test backend. No UI or RFIDGear XML types belong here.
 */
interface CardBackend : AutoCloseable {
    fun connect(): CardResult<Unit>
    fun disconnect()
    fun identify(): CardResult<CardIdentity>
    fun execute(command: CardCommand): CardResult<CardResponse>

    override fun close() = disconnect()
}

data class CardIdentity(
    val uid: ByteArray,
    val technology: CardTechnology,
    val detail: String? = null
)

enum class CardTechnology {
    UNKNOWN,
    MIFARE_CLASSIC,
    MIFARE_ULTRALIGHT,
    MIFARE_DESFIRE
}

data class CardResult<T>(
    val error: CardError,
    val value: T? = null,
    val message: String? = null
) {
    val isSuccess: Boolean get() = error == CardError.NO_ERROR

    companion object {
        fun <T> ok(value: T? = null): CardResult<T> = CardResult(CardError.NO_ERROR, value)
        fun <T> fail(error: CardError, message: String? = null): CardResult<T> = CardResult(error, null, message)
    }
}

/**
 * Runtime error vocabulary intentionally mirrors the stable semantic categories
 * needed by RFIDGear control flow. Backend-specific details belong in message/metadata,
 * not in branching logic.
 */
enum class CardError(val rfidGearName: String) {
    NO_ERROR("NoError"),
    EMPTY("Empty"),
    AUTH_FAILURE("AuthFailure"),
    TRANSPORT_ERROR("TransportError"),
    WRONG_CARD("WrongCard"),
    INVALID_ARGUMENT("InvalidArgument"),
    OPERATION_NOT_SUPPORTED("OperationNotSupported"),
    CARD_ERROR("CardError"),
    UNKNOWN("Unknown");

    companion object {
        fun fromRfidGearName(value: String?): CardError =
            entries.firstOrNull { it.rfidGearName.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

sealed interface CardResponse {
    data object Empty : CardResponse
    data class Bytes(val data: ByteArray) : CardResponse
    data class ApplicationIds(val aids: List<Int>) : CardResponse
    data class DesfireApplicationSettings(
        val keySettings: Int,
        val maxKeys: Int? = null,
        val keyType: DesfireKeyType? = null
    ) : CardResponse
}

sealed interface CardCommand

enum class DesfireKeyType {
    DES,
    TDES_2K,
    TDES_3K,
    AES
}

enum class DesfireCommunicationMode {
    PLAIN,
    MACED,
    ENCIPHERED
}

enum class DesfireFileType {
    STANDARD_DATA,
    BACKUP_DATA,
    VALUE,
    LINEAR_RECORD,
    CYCLIC_RECORD
}

data class DesfireAccessRights(
    val read: Int,
    val write: Int,
    val readWrite: Int,
    val change: Int
)

data class DesfireKey(
    val bytes: ByteArray,
    val type: DesfireKeyType,
    val number: Int,
    val version: Int? = null
) {
    init {
        require(number in 0..15) { "DESFire key number must be between 0 and 15." }
    }

    /** Best-effort secret erasure for temporary runtime key material. */
    fun clear() = bytes.fill(0)
}

data class DesfireAuthenticate(
    val appId: Int,
    val key: DesfireKey
) : CardCommand

data class DesfireListApplications(
    val piccMasterKey: DesfireKey? = null
) : CardCommand

data class DesfireReadApplicationSettings(
    val appId: Int,
    val key: DesfireKey?,
    val authenticateBeforeRead: Boolean
) : CardCommand

data class DesfireCreateApplication(
    val appId: Int,
    val piccMasterKey: DesfireKey,
    val targetKeyType: DesfireKeyType,
    val maxKeys: Int,
    val keySettings: Int,
    val authenticateToPiccFirst: Boolean = true
) : CardCommand

data class DesfireDeleteApplication(
    val appId: Int,
    val piccMasterKey: DesfireKey
) : CardCommand

data class DesfireCreateFile(
    val appId: Int,
    val appMasterKey: DesfireKey,
    val fileNo: Int,
    val fileType: DesfireFileType,
    val size: Int,
    val communicationMode: DesfireCommunicationMode,
    val accessRights: DesfireAccessRights,
    val minValue: Int = 0,
    val maxValue: Int = 1000,
    val initialValue: Int = 0,
    val limitedCreditEnabled: Boolean = false,
    val maxRecords: Int = 100
) : CardCommand

data class DesfireDeleteFile(
    val appId: Int,
    val appMasterKey: DesfireKey,
    val fileNo: Int
) : CardCommand

data class DesfireChangeFileSettings(
    val appId: Int,
    val fileNo: Int,
    val changeKey: DesfireKey,
    val communicationMode: DesfireCommunicationMode,
    val accessRights: DesfireAccessRights
) : CardCommand

data class DesfireReadData(
    val appId: Int,
    val fileNo: Int,
    val length: Int,
    val readKey: DesfireKey,
    val communicationMode: DesfireCommunicationMode
) : CardCommand

data class DesfireWriteData(
    val appId: Int,
    val fileNo: Int,
    val payload: ByteArray,
    val writeKey: DesfireKey,
    val communicationMode: DesfireCommunicationMode
) : CardCommand

data class DesfireChangeKey(
    val appId: Int,
    val targetKeyNumber: Int,
    val targetKeyType: DesfireKeyType,
    val currentTargetKey: ByteArray,
    val newTargetKey: ByteArray,
    val newTargetKeyVersion: Int,
    val masterKey: DesfireKey,
    val keySettings: Int
) : CardCommand

data class DesfireChangeKeySettings(
    val appId: Int,
    val authenticationKey: DesfireKey,
    val keySettings: Int
) : CardCommand

data class DesfireFormatCard(
    val piccMasterKey: DesfireKey
) : CardCommand
