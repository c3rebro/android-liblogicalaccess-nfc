# Next steps

1. Add liblogicalaccess v3.7.0 as a pinned dependency/submodule.
2. Build it for `arm64-v8a` with the Android NDK.
3. Determine the exact target card technology (for example MIFARE DESFire EV2/EV3).
4. Implement a liblogicalaccess reader/transport adapter backed by `AndroidIsoDepTransport.transceive()`.
5. Add a safe test command such as card identification or application enumeration where permitted.
6. Add structured logging around APDU TX/RX without logging secret material.
7. Add tests for JNI lifecycle and NFC reconnect behavior.
8. Add CI for Gradle + native build.
