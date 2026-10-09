package de.shansen.liblogicalaccessnfc

import android.nfc.tech.IsoDep

class AndroidIsoDepTransport(private val isoDep: IsoDep, private val onEvent: (String) -> Unit = {}) {
    private var exchange = 0
    fun transceive(command: ByteArray): ByteArray {
        val number = ++exchange
        val instruction = command.getOrNull(if (command.size > 1 && command[0] == 0x90.toByte()) 1 else 0)
            ?.let { "0x%02X".format(it.toInt() and 0xFF) } ?: "unknown"
        val start = android.os.SystemClock.elapsedRealtime()
        onEvent("Exchange $number: command $instruction, ${command.size} bytes; waiting for card")
        try {
            val response = isoDep.transceive(command)
            onEvent("Exchange $number: ${response.size} response bytes, ${android.os.SystemClock.elapsedRealtime() - start} ms")
            return response
        } catch (e: Exception) {
            onEvent("Exchange $number: ${e.javaClass.simpleName}, ${android.os.SystemClock.elapsedRealtime() - start} ms; timeout ${isoDep.timeout} ms")
            throw e
        }
    }
    fun isConnected(): Boolean = isoDep.isConnected
}
