package de.shansen.liblogicalaccessnfc

import android.nfc.tech.IsoDep

class AndroidIsoDepTransport(private val isoDep: IsoDep) {
    fun transceive(command: ByteArray): ByteArray = isoDep.transceive(command)
    fun isConnected(): Boolean = isoDep.isConnected
}
