package de.shansen.liblogicalaccessnfc

object NativeBridge {
    init {
        System.loadLibrary("lla_android_bridge")
    }

    const val OP_GET_VERSION = 1
    const val OP_GET_FREE_MEMORY = 2
    const val OP_LIST_APPLICATIONS = 3
    const val OP_AUTHENTICATE = 4
    const val OP_READ_APPLICATION_SETTINGS = 5
    const val OP_LIST_FILES = 6
    const val OP_READ_FILE_SETTINGS = 7

    external fun version(): String
    external fun attachTransport(transport: AndroidIsoDepTransport)
    external fun detachTransport()

    external fun beginDesfireSession(uid: ByteArray): Long
    external fun endDesfireSession(handle: Long)

    /**
     * Executes one read-only DESFire primitive in the active native session.
     *
     * The returned packet starts with one status byte. Status 0 means success;
     * non-zero packets contain a UTF-8 error message after the status byte.
     */
    external fun desfireExecute(
        handle: Long,
        operation: Int,
        appId: Int = 0,
        fileNo: Int = 0,
        keyType: Int = -1,
        keyNo: Int = -1,
        key: ByteArray? = null,
        authenticate: Boolean = false
    ): ByteArray
}
