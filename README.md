# Android RFIDGear Encoder

Android card-encoding runtime driven by project files created with `c3rebro/RFiDGear`.

The application is intentionally split into a project parser/compiler, a deterministic execution engine and a replaceable card backend. RFIDGear remains the authoring tool while Android acts as the portable runtime/encoder.

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

The Android app can open a project and show a safe per-task preview:

- `SUPPORTED` — project fields can be compiled into a typed runtime action;
- `UNSUPPORTED` — the RFIDGear operation is known, but intentionally not enabled yet;
- `INVALID` — required project fields are missing or invalid.

No secret key values or write payloads are printed by the project preview.

Project-driven write execution is still disabled while the runtime/compiler contract is being completed.

## DESFire Quick Check

A real read-only DESFire Quick Check path is now implemented separately from project execution.

When a DESFire/ISO-DEP tag is presented, the app keeps one NFC session open and queries through liblogicalaccess:

- card version;
- free memory;
- application IDs;
- application key settings;
- file IDs;
- file type, size, communication mode and access rights.

Public metadata is always attempted first. If an application or file metadata requires authentication, configured AID-specific keys are tried before global defaults. AID-specific keys can be added from the Android UI; they are currently session-only and are never shown again as plaintext after entry.

The report distinguishes `PUBLIC`, `AUTHENTICATED`, `KEY_REQUIRED`, `DENIED` and `UNAVAILABLE` access states. See [`docs/DESFIRE_QUICK_CHECK.md`](docs/DESFIRE_QUICK_CHECK.md).

The native Quick Check backend deliberately maps no create/write/delete/format/change commands. Unsupported/destructive `CardCommand`s are rejected before a native operation is attempted.

## Android NFC / native state

Implemented:

- Kotlin Android app;
- NFC Reader Mode;
- ISO-DEP discovery;
- raw `IsoDep.transceive()` transport;
- JNI/native C++ `DataTransport`;
- same-thread DESFire native session;
- `ISO7816ReaderCardAdapter` and `DESFireISO7816ResultChecker`;
- `DESFireEV1Chip` / `DESFireEV1ISO7816Commands`;
- typed Kotlin `NativeDesfireCardBackend`;
- read-only Quick Check execution and report UI.

The code uses the public liblogicalaccess core rather than the older application-specific `liblogicalaccess-android` package bindings.

## Native dependency

The native build is pinned to liblogicalaccess `3.7.0`.

The Windows bootstrap builds it for Android `arm64-v8a` via Conan and the Android NDK, then stages the required shared libraries for APK packaging. PKCS and libusb support are disabled because the phone-NFC Quick Check does not use them.

Generated toolchains, sources, Conan packages and native libraries live under `.tools/` and are git-ignored.

## Windows: one-click build and physical-device deployment

On a Windows build machine, run:

```bat
build-and-deploy.bat
```

The script checks/installs the required toolchain, prepares liblogicalaccess, runs all JVM unit tests, builds `app-debug.apk`, installs it via ADB and launches it on one connected physical Android device.

Pinned/required build prerequisites include:

- JDK 17 (Android Studio bundled JBR preferred)
- Gradle 8.11.1
- Android platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1 / Ninja
- Android Platform Tools / ADB
- Git
- Python 3
- project-local Conan 2.x environment

Missing Android SDK packages are installed using `sdkmanager`. SDK license acceptance remains interactive.

Before deployment enable **Developer options** and **USB debugging**, connect/unlock the phone and accept the RSA authorization dialog. The current native build target is `arm64-v8a`.

Useful options:

```bat
build-and-deploy.bat -SkipDeploy
build-and-deploy.bat -SkipLaunch
```

## Current verification boundary

The project parser/runtime and Quick Check orchestration have unit-test coverage. The JNI/liblogicalaccess Android path is implemented in source, but the first Windows Conan/CMake build and physical DESFire-card run still need to be performed on the target build machine. Compiler/linker/runtime issues from that first run should be treated as the next integration feedback, not as proof that the native path is already production-ready.

## Security

This is an encoding tool, so project files and keys are security-sensitive inputs. The runtime treats project XML as untrusted, limits ZIP/XML sizes, disables external XML entities, avoids logging secret fields and keeps card keys out of report text.

Quick Check keys are currently session-only. Persistent key storage, if added later, must use Android Keystore-backed encryption; raw DESFire keys must not be stored as plaintext preferences or hard-coded into the APK.
