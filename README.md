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
- Windows prerequisite/build/deploy automation
- no bundled liblogicalaccess source or binaries yet

## Windows: one-click build and physical-device deployment

On a Windows build machine, run:

```bat
build-and-deploy.bat
```

The script checks or installs the required toolchain, then builds `app-debug.apk`, installs it via ADB and launches the app on one connected physical Android device.

Pinned build prerequisites:

- JDK 17 (Android Studio bundled JBR is preferred)
- Gradle 8.11.1
- Android platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1
- Android Platform Tools / ADB

Missing Android SDK packages are installed using `sdkmanager`. Missing Android Command-Line Tools can be downloaded after an explicit confirmation and are checksum-verified. SDK licenses are never silently accepted; `sdkmanager --licenses` remains interactive.

For deployment, enable **Developer options** and **USB debugging** on the Android phone, connect and unlock it, and accept the RSA authorization dialog. The current PoC builds only `arm64-v8a`.

Useful options:

```bat
build-and-deploy.bat -SkipDeploy
build-and-deploy.bat -SkipLaunch
```

`-SkipDeploy` only builds the APK. `-SkipLaunch` builds and installs it but does not start the app.

## liblogicalaccess integration

The next step is to add liblogicalaccess v3.7.0 as a pinned dependency or submodule, build it for Android/arm64-v8a, and wire its required CMake targets into `app/src/main/cpp/CMakeLists.txt`.

The exact reader/chip configuration depends on the target card technology and whether the phone's integrated NFC controller or an external reader is intended.

## Important

This is a technical PoC scaffold, not production-ready NFC/security code. Keys, secure messaging, DESFire authentication, key diversification, and secret storage must not be implemented in the UI layer or hard-coded into the app.
