package de.shansen.liblogicalaccessnfc

object NativeBridge {
    init {
        System.loadLibrary("lla_android_bridge")
    }

    external fun version(): String
    external fun attachTransport(transport: AndroidIsoDepTransport)
    external fun detachTransport()
}
