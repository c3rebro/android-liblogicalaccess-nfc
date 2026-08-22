# Android RFIDGear Encoder

Android card-encoding runtime driven by project files created with `c3rebro/RFiDGear`.

The application is intentionally split into a project parser/compiler, a deterministic execution engine and a replaceable card backend. The goal is that RFIDGear remains the authoring tool while Android acts as the portable runtime/encoder.

## Target architecture

```text
.rfPrj (RFIDGear)
        |
        v
+-------------------+
| core-project      |  ZIP/XML parsing, validation, task IDs/conditions
+-------------------+
        |
        v
+-------------------+
| rfidgear-runtime  |  persisted RFIDGear fields -> typed card actions
+-------------------+
        |
        +--------------------+
        |                    |
        v                    v
+-------------------+  +-------------------+
| core-execution    |  | core-card         |
| task sequencing   |  | card backend API  |
| error conditions  |  | DESFire commands  |
+-------------------+  +-------------------+
                              |
                              v
                      Android NFC / IsoDep
                              |
                              v
                         JNI / C++
                              |
                              v
                       liblogicalaccess
```

## RFIDGear project support

RFIDGear `.rfPrj` files are ZIP archives containing `taskdatabase.xml`. Plain XML project files are also accepted for diagnostics. See [`docs/RFPRJ_FORMAT.md`](docs/RFPRJ_FORMAT.md) for the compatibility contract reconstructed from the current RFIDGear source.

The Android app can already open a project and show a safe per-task preview:

- `SUPPORTED` — project fields can be compiled into a typed runtime action;
- `UNSUPPORTED` — the RFIDGear operation is known, but intentionally not enabled yet;
- `INVALID` — required project fields are missing or invalid.

No secret key values or write payloads are printed by the project preview.

### DESFire compiler status

Currently compiled to typed actions:

- `AuthenticateApplication`
- `AppExistCheck`
- `ReadAppSettings`
- `CheckAppKeyCount`
- `FormatDesfireCard`
- `CreateApplication`
- `DeleteApplication`
- `CreateFile`
- `ReadData`

Explicitly blocked until their desktop semantics are completely mapped:

- `WriteData` — payload/offset/length live in RFIDGear's data-explorer hierarchy;
- `DeleteFile` — the current desktop fallback path uses an inconsistent application field;
- application/PICC key change operations;
- application/PICC key-setting changes;
- file-setting changes.

The app currently remains **dry-preview only for project tasks**. Loading a project does not execute encoding commands on a card.

## Android NFC state

- Kotlin Android app
- NFC Reader Mode
- ISO-DEP discovery
- UID and supported technologies display
- raw `IsoDep.transceive()` transport
- JNI/native C++ bridge
- CMake integration point

The official `liblogicalaccess/liblogicalaccess-android` project confirms the same architectural pattern: an Android-side NFC transport forwards byte commands to the native liblogicalaccess reader implementation. Its source is useful as a reference, but it contains application-specific hard-coded Java package names, so this project uses its own clean transport boundary rather than copying those dependencies wholesale.

## Windows: one-click build and physical-device deployment

On a Windows build machine, run:

```bat
build-and-deploy.bat
```

The script checks/installs the required toolchain, runs all JVM unit tests, builds `app-debug.apk`, installs it via ADB and launches it on one connected physical Android device.

Pinned build prerequisites:

- JDK 17 (Android Studio bundled JBR preferred)
- Gradle 8.11.1
- Android platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1
- Android Platform Tools / ADB

Missing Android SDK packages are installed using `sdkmanager`. SDK license acceptance remains interactive.

Before deployment enable **Developer options** and **USB debugging**, connect/unlock the phone and accept the RSA authorization dialog. The current native build target is `arm64-v8a`.

Useful options:

```bat
build-and-deploy.bat -SkipDeploy
build-and-deploy.bat -SkipLaunch
```

## Next implementation boundary

The next hardware milestone is not to execute arbitrary `.rfPrj` files immediately. It is to connect the typed `core-card` DESFire commands to liblogicalaccess through Android `IsoDep`, beginning with non-destructive identification/authentication/read operations and a dry-run/audit path. Destructive operations will only be enabled after their project mapping and result semantics are covered by fixtures/tests.

## Security

This is an encoding tool, so project files and keys are security-sensitive inputs. The runtime therefore treats project XML as untrusted, limits ZIP/XML sizes, disables external XML entities, avoids logging secret fields, and keeps card keys out of UI-layer logic. Production key storage/provisioning is still an open design item and must not be implemented by hard-coding secrets into the APK.
