# Android liblogicalaccess NFC

Minimal Android proof-of-concept for hardware-near NFC access using Android's NFC stack with a native C++ bridge prepared for liblogicalaccess.

## Architecture

```text
Kotlin UI
  -> Android NfcAdapter / IsoDep
  -> NfcTransport
  -> JNI bridge
  -> C++ adapter
  -> liblogicalaccess
```

The first milestone deliberately keeps Android tag discovery/APDU transport separate from liblogicalaccess. This makes it possible to validate the phone's NFC hardware first and then integrate the required liblogicalaccess card/chip layer without coupling UI code to native details.

## Current state

- Kotlin Android app
- NFC Reader Mode
- ISO-DEP discovery
- UID and supported technologies display
- raw APDU transceive helper
- JNI/native C++ bridge
- CMake integration point for liblogicalaccess
- no bundled liblogicalaccess source or binaries yet

## liblogicalaccess integration

The next step is to add liblogicalaccess v3.7.0 as a pinned dependency or submodule, build it for Android/arm64-v8a, and wire its required CMake targets into `app/src/main/cpp/CMakeLists.txt`.

The exact reader/chip configuration depends on the target card technology and whether the phone's integrated NFC controller or an external reader is intended.

## Build

Open the project in Android Studio, install the requested Android SDK/NDK/CMake components, and run it on a physical NFC-capable Android device.

## Important

This is a technical PoC scaffold, not production-ready NFC/security code. Keys, secure messaging, DESFire authentication, key diversification, and secret storage must not be implemented in the UI layer or hard-coded into the app.
