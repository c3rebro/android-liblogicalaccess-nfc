package de.shansen.liblogicalaccessnfc

import de.shansen.rfcard.CardBackend
import de.shansen.rfcard.CardError
import de.shansen.rfcard.CardIdentity
import de.shansen.rfcard.CardResponse
import de.shansen.rfcard.CardResult
import de.shansen.rfcard.CardTechnology
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireCommunicationMode
import de.shansen.rfcard.DesfireFileType
import de.shansen.rfcard.DesfireGetFreeMemory
import de.shansen.rfcard.DesfireGetVersion
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfcard.DesfireListApplications
import de.shansen.rfcard.DesfireListFiles
import de.shansen.rfcard.DesfireReadApplicationSettings
import de.shansen.rfcard.DesfireReadFileSettings
import de.shansen.rfcard.DesfireAccessRights
import de.shansen.rfcard.CardCommand

/**
 * Read-only DESFire CardBackend backed by liblogicalaccess through JNI.
 *
 * One instance represents one uninterrupted IsoDep/native session. Destructive
 * CardCommand implementations are deliberately not mapped here.
 */
class NativeDesfireCardBackend(
    private val uid: ByteArray
) : CardBackend {
    companion object {
        fun supports(command: CardCommand): Boolean = when (command) {
            DesfireGetVersion,
            DesfireGetFreeMemory,
            is DesfireListApplications,
            is DesfireAuthenticate,
            is DesfireReadApplicationSettings,
            is DesfireListFiles,
            is DesfireReadFileSettings -> true

            else -> false
        }
    }

    private var sessionHandle: Long = 0L

    override fun connect(): CardResult<Unit> {
        if (sessionHandle != 0L) return CardResult.ok(Unit)
        return runCatching {
            sessionHandle = NativeBridge.beginDesfireSession(uid)
            check(sessionHandle != 0L) { "liblogicalaccess DESFire session could not be created." }
            CardResult.ok(Unit)
        }.getOrElse { error ->
            sessionHandle = 0L
            CardResult.fail(CardError.TRANSPORT_ERROR, error.message ?: "Unable to create native DESFire session.")
        }
    }

    override fun disconnect() {
        val handle = sessionHandle
        sessionHandle = 0L
        if (handle != 0L) {
            runCatching { NativeBridge.endDesfireSession(handle) }
        }
    }

    override fun identify(): CardResult<CardIdentity> {
        if (sessionHandle == 0L) return CardResult.fail(CardError.TRANSPORT_ERROR, "DESFire session is not connected.")
        return CardResult.ok(
            CardIdentity(
                uid = uid.copyOf(),
                technology = CardTechnology.MIFARE_DESFIRE,
                detail = "Android ISO-DEP / liblogicalaccess"
            )
        )
    }

    override fun execute(command: CardCommand): CardResult<CardResponse> {
        if (sessionHandle == 0L) return CardResult.fail(CardError.TRANSPORT_ERROR, "DESFire session is not connected.")

        return when (command) {
            DesfireGetVersion -> executeSimple(NativeBridge.OP_GET_VERSION).mapSuccess { payload ->
                require(payload.size >= 16) { "Invalid DESFire version payload (${payload.size} bytes)." }
                CardResponse.DesfireVersion(
                    hardwareVendor = payload.u8(0),
                    hardwareType = payload.u8(1),
                    hardwareSubType = payload.u8(2),
                    hardwareMajor = payload.u8(3),
                    hardwareMinor = payload.u8(4),
                    hardwareStorageSize = payload.u8(5),
                    hardwareProtocol = payload.u8(6),
                    softwareVendor = payload.u8(7),
                    softwareType = payload.u8(8),
                    softwareSubType = payload.u8(9),
                    softwareMajor = payload.u8(10),
                    softwareMinor = payload.u8(11),
                    softwareStorageSize = payload.u8(12),
                    softwareProtocol = payload.u8(13),
                    productionWeek = payload.u8(14),
                    productionYear = payload.u8(15)
                )
            }

            DesfireGetFreeMemory -> executeSimple(NativeBridge.OP_GET_FREE_MEMORY).mapSuccess { payload ->
                require(payload.size >= 4) { "Invalid free-memory payload." }
                CardResponse.DesfireFreeMemory(payload.i32le(0))
            }

            is DesfireListApplications -> executeNative(
                operation = NativeBridge.OP_LIST_APPLICATIONS,
                key = command.piccMasterKey,
                authenticate = command.piccMasterKey != null
            ).mapSuccess { payload ->
                require(payload.size >= 5) { "Invalid application-list payload." }
                val wasAuthenticated = payload.u8(0) != 0
                val count = payload.i32le(1)
                require(count >= 0 && payload.size >= 5 + count * 4) { "Invalid DESFire application count." }
                val aids = List(count) { index -> payload.i32le(5 + index * 4) }
                CardResponse.ApplicationIds(aids, wasAuthenticated)
            }

            is DesfireAuthenticate -> executeNative(
                operation = NativeBridge.OP_AUTHENTICATE,
                appId = command.appId,
                key = command.key,
                authenticate = true
            ).mapSuccess { CardResponse.Empty }

            is DesfireReadApplicationSettings -> executeNative(
                operation = NativeBridge.OP_READ_APPLICATION_SETTINGS,
                appId = command.appId,
                key = command.key,
                authenticate = command.authenticateBeforeRead
            ).mapSuccess { payload ->
                require(payload.size >= 4) { "Invalid application-settings payload." }
                CardResponse.DesfireApplicationSettings(
                    keySettings = payload.u8(0),
                    maxKeys = payload.u8(1),
                    keyType = nativeKeyType(payload.u8(2)),
                    wasAuthenticated = payload.u8(3) != 0
                )
            }

            is DesfireListFiles -> executeNative(
                operation = NativeBridge.OP_LIST_FILES,
                appId = command.appId,
                key = command.key,
                authenticate = command.authenticateBeforeRead
            ).mapSuccess { payload ->
                require(payload.size >= 2) { "Invalid file-list payload." }
                val wasAuthenticated = payload.u8(0) != 0
                val count = payload.u8(1)
                require(payload.size >= 2 + count) { "Invalid DESFire file count." }
                CardResponse.DesfireFileIds(
                    fileIds = List(count) { payload.u8(2 + it) },
                    wasAuthenticated = wasAuthenticated
                )
            }

            is DesfireReadFileSettings -> executeNative(
                operation = NativeBridge.OP_READ_FILE_SETTINGS,
                appId = command.appId,
                fileNo = command.fileNo,
                key = command.key,
                authenticate = command.authenticateBeforeRead
            ).mapSuccess { payload ->
                require(payload.size >= 12) { "Invalid file-settings payload." }
                val size = payload.i32le(8).takeIf { it >= 0 }
                CardResponse.DesfireFileSettings(
                    fileNo = payload.u8(0),
                    fileType = nativeFileType(payload.u8(1)),
                    communicationMode = nativeCommunicationMode(payload.u8(2)),
                    accessRights = DesfireAccessRights(
                        read = payload.u8(3),
                        write = payload.u8(4),
                        readWrite = payload.u8(5),
                        change = payload.u8(6)
                    ),
                    size = size,
                    wasAuthenticated = payload.u8(7) != 0
                )
            }

            else -> CardResult.fail(
                CardError.PROTOCOL_CONSTRAINT,
                "${command.javaClass.simpleName} is intentionally not available in the read-only native Quick Check backend."
            )
        }
    }

    private fun executeSimple(operation: Int): CardResult<ByteArray> =
        executeNative(operation = operation)

    private fun executeNative(
        operation: Int,
        appId: Int = 0,
        fileNo: Int = 0,
        key: DesfireKey? = null,
        authenticate: Boolean = false
    ): CardResult<ByteArray> {
        return runCatching {
            val packet = NativeBridge.desfireExecute(
                handle = sessionHandle,
                operation = operation,
                appId = appId,
                fileNo = fileNo,
                keyType = key?.type?.nativeCode ?: -1,
                keyNo = key?.number ?: -1,
                key = key?.bytes,
                authenticate = authenticate
            )
            decodePacket(packet)
        }.getOrElse { error ->
            CardResult.fail(CardError.TRANSPORT_ERROR, error.message ?: "Native DESFire call failed.")
        }
    }

    private fun decodePacket(packet: ByteArray): CardResult<ByteArray> {
        if (packet.isEmpty()) return CardResult.fail(CardError.UNKNOWN, "Native DESFire call returned an empty packet.")
        val status = packet.u8(0)
        if (status == 0) return CardResult.ok(packet.copyOfRange(1, packet.size))

        val message = if (packet.size > 1) packet.copyOfRange(1, packet.size).decodeToString() else null
        val error = when (status) {
            1 -> CardError.AUTH_FAILURE
            2 -> CardError.PERMISSION_DENIED
            3 -> CardError.PROTOCOL_CONSTRAINT
            4 -> CardError.TRANSPORT_ERROR
            else -> CardError.UNKNOWN
        }
        return CardResult.fail(error, message)
    }

    private fun <T : CardResponse> CardResult<ByteArray>.mapSuccess(transform: (ByteArray) -> T): CardResult<CardResponse> {
        if (!isSuccess || value == null) return CardResult.fail(error, message)
        return runCatching { CardResult.ok<CardResponse>(transform(value)) }
            .getOrElse { CardResult.fail(CardError.PROTOCOL_CONSTRAINT, it.message ?: "Invalid native DESFire response.") }
    }

    private val DesfireKeyType.nativeCode: Int
        get() = when (this) {
            DesfireKeyType.DES -> 0
            DesfireKeyType.TDES_3K -> 1
            DesfireKeyType.AES -> 2
        }

    private fun nativeKeyType(value: Int): DesfireKeyType = when (value) {
        0 -> DesfireKeyType.DES
        1 -> DesfireKeyType.TDES_3K
        2 -> DesfireKeyType.AES
        else -> throw IllegalArgumentException("Unknown native DESFire key type $value.")
    }

    private fun nativeCommunicationMode(value: Int): DesfireCommunicationMode = when (value) {
        0 -> DesfireCommunicationMode.PLAIN
        1 -> DesfireCommunicationMode.MACED
        2 -> DesfireCommunicationMode.ENCIPHERED
        else -> throw IllegalArgumentException("Unknown native DESFire communication mode $value.")
    }

    private fun nativeFileType(value: Int): DesfireFileType = when (value) {
        0 -> DesfireFileType.STANDARD_DATA
        1 -> DesfireFileType.BACKUP_DATA
        2 -> DesfireFileType.VALUE
        3 -> DesfireFileType.LINEAR_RECORD
        4 -> DesfireFileType.CYCLIC_RECORD
        else -> throw IllegalArgumentException("Unknown native DESFire file type $value.")
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.i32le(offset: Int): Int =
        u8(offset) or
            (u8(offset + 1) shl 8) or
            (u8(offset + 2) shl 16) or
            (u8(offset + 3) shl 24)
}
