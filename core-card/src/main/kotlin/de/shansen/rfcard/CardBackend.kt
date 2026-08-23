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

data class CardResult<out T>(
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
 * Exact runtime vocabulary from RFIDGear's ERROR enum.
 * Keeping these names stable is required because .rfPrj task conditions compare them.
 */
enum class CardError(val rfidGearName: String) {
    EMPTY("Empty"),
    NO_ERROR("NoError"),
    AUTH_FAILURE("AuthFailure"),
    PERMISSION_DENIED("PermissionDenied"),
    PROTOCOL_CONSTRAINT("ProtocolConstraint"),
    TRANSPORT_ERROR("TransportError"),
    UNKNOWN("Unknown"),
    IS_NOT_TRUE("IsNotTrue"),
    IS_NOT_FALSE("IsNotFalse");

    companion object {
        fun fromRfidGearName(value: String?): CardError =
            entries.firstOrNull { it.rfidGearName.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

sealed interface CardResponse {
    data object Empty : CardResponse
    data class Bytes(val data: ByteArray) : CardResponse

    data class ApplicationIds(
        val aids: List<Int>,
        /** true = listing was authenticated, false = public listing, null = backend cannot tell. */
        val wasAuthenticated: Boolean? = null
    ) : CardResponse

    data class DesfireApplicationSettings(
        val keySettings: Int,
        val maxKeys: Int? = null,
        val keyType: DesfireKeyType? = null,
        /** true = supplied key authenticated, false = settings were obtained without authentication. */
        val wasAuthenticated: Boolean? = null
    ) : CardResponse

    data class DesfireVersion(
        val hardwareVendor: Int,
        val hardwareType: Int,
        val hardwareSubType: Int,
        val hardwareMajor: Int,
        val hardwareMinor: Int,
        val hardwareStorageSize: Int,
        val hardwareProtocol: Int,
        val softwareVendor: Int,
        val softwareType: Int,
        val softwareSubType: Int,
        val softwareMajor: Int,
        val softwareMinor: Int,
        val softwareStorageSize: Int,
        val softwareProtocol: Int,
        val productionWeek: Int? = null,
        val productionYear: Int? = null
    ) : CardResponse

    data class DesfireFreeMemory(val bytes: Int) : CardResponse

    data class DesfireFileIds(
        val fileIds: List<Int>,
        val wasAuthenticated: Boolean? = null
    ) : CardResponse

    data class DesfireFileSettings(
        val fileNo: Int,
        val fileType: DesfireFileType,
        val communicationMode: DesfireCommunicationMode,
        val accessRights: DesfireAccessRights,
        val size: Int? = null,
        val wasAuthenticated: Boolean? = null
    ) : CardResponse
}

sealed interface CardCommand

/**
 * Cryptographic types persisted by RFIDGear. DF_KEY_DES covers RFIDGear's
 * 16-byte DES/2K3DES-compatible representation; DF_KEY_3K3DES uses 24 bytes.
 */
enum class DesfireKeyType {
    DES,
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
        val expected = when (type) {
            DesfireKeyType.TDES_3K -> 24
            DesfireKeyType.DES, DesfireKeyType.AES -> 16
        }
        require(bytes.size == expected) {
            "DESFire $type key must contain exactly $expected bytes."
        }
    }

    /** Best-effort secret erasure for temporary runtime key material. */
    fun clear() = bytes.fill(0)
}

data object DesfireGetVersion : CardCommand

data object DesfireGetFreeMemory : CardCommand

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

data class DesfireListFiles(
    val appId: Int,
    val key: DesfireKey? = null,
    val authenticateBeforeRead: Boolean = key != null
) : CardCommand

data class DesfireReadFileSettings(
    val appId: Int,
    val fileNo: Int,
    val key: DesfireKey? = null,
    val authenticateBeforeRead: Boolean = key != null
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
