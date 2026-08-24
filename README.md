# Android RFIDGear Encoder

Android DESFire/RFID runtime that can execute both RFIDGear project workflows and fixed built-in card use cases.

RFIDGear remains the authoring tool for `.rfPrj` workflows, while Android acts as the portable runtime/encoder. Built-in tools such as DESFire Quick Check, DESFire Format and DESFire Factory Reset do not require a project file.

## Target architecture

```text
.rfPrj (RFIDGear)                  Built-in use cases
        |                                  |
        v                                  v
+-------------------+              +-------------------+
| core-project      |              | core-usecase      |
| ZIP/XML parsing   |              | fixed workflows   |
+-------------------+              +-------------------+
        |                                  |
        v                                  |
+-------------------+                      |
| rfidgear-runtime  |                      |
| fields -> actions |                      |
+-------------------+                      |
        |                                  |
        +------------------+---------------+
                           |
                           v
                   +-------------------+
                   | core-card         |
                   | card command API  |
                   +-------------------+
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

`core-execution` provides RFIDGear task sequencing/condition handling independently from the card backend.

## RFIDGear project support

RFIDGear `.rfPrj` files are ZIP archives containing `taskdatabase.xml`. Plain XML project files are also accepted for diagnostics. See [`docs/RFPRJ_FORMAT.md`](docs/RFPRJ_FORMAT.md) for the compatibility contract reconstructed from the current RFIDGear source.

The Android app can open a project and show a safe per-task preview. The preview separates:

- read-only actions that are mapped by the current Android backend;
- read-only actions that are understood but not mapped yet;
- mutating operations that remain disabled;
- destructive operations that remain blocked;
- unsupported/invalid project operations.

No secret key values or write payloads are printed by the project preview. Project-driven write execution is still disabled while the runtime/compiler contract is being completed.

## Built-in use cases

Built-in workflows are independent of `.rfPrj` files and reuse the same platform-neutral `CardCommand` model.

Currently modelled:

- **DESFire Quick Check** - read-only inspection of applications, files, communication modes and access rights.
- **DESFire Format** - destructive workflow with a read-only preflight, UID-bound confirmation and explicit no-auto-retry semantics. Native `FORMAT_PICC` execution is intentionally not enabled yet.
- **DESFire Factory Reset** - destructive workflow that formats the PICC and then restores PICC master key #0 to DES with 16 zero bytes (`32` hexadecimal zeros), key version `0`. The full core state machine is modelled, while Android currently exposes only the read-only preflight.

See [`docs/BUILT_IN_USE_CASES.md`](docs/BUILT_IN_USE_CASES.md).

## DESFire Quick Check

A real read-only DESFire Quick Check path is implemented separately from project execution.

When a DESFire/ISO-DEP tag is presented, the app keeps one NFC session open and queries through liblogicalaccess:

- card version;
- free memory;
- application IDs;
- application key settings;
- file IDs;
- file type, size, communication mode and access rights.

Public metadata is always attempted first. If application/file metadata requires authentication, configured AID-specific keys are tried before global defaults. AID-specific keys can be added from the Android UI; they are currently session-only and are never shown again as plaintext after entry.

The report distinguishes `PUBLIC`, `AUTHENTICATED`, `KEY_REQUIRED`, `DENIED` and `UNAVAILABLE` access states. The latest result can be exported as a PDF using Android's system document picker. The export model contains no raw DESFire key bytes. See [`docs/DESFIRE_QUICK_CHECK.md`](docs/DESFIRE_QUICK_CHECK.md) and [`docs/REPORTING.md`](docs/REPORTING.md).

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
- read-only Quick Check execution and report UI;
- Quick Check PDF export;
- read-only DESFire Format preflight UI;
- read-only DESFire Factory Reset preflight UI.

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

## CI and verification boundary

GitHub Actions runs the hardware-independent JVM tests for project parsing, execution semantics, built-in use cases and Quick Check reporting, plus Android Kotlin/UI compilation.

The JNI/liblogicalaccess Android path is implemented in source, but the first Windows Conan/CMake build and physical DESFire-card run still need to be performed on the target build machine. Compiler/linker/runtime issues from that first run should be treated as the next integration feedback, not as proof that the native path is already production-ready.

## Security

This is an encoding tool, so project files and keys are security-sensitive inputs. The runtime treats project XML as untrusted, limits ZIP/XML sizes, disables external XML entities, avoids logging secret fields and keeps card keys out of report text/PDF models.

Quick Check keys are currently session-only. Persistent key storage, if added later, must use Android Keystore-backed encryption; raw DESFire keys must not be stored as plaintext preferences or hard-coded into the APK.

The DESFire factory default zero key is a public protocol default rather than a secret and is generated as fresh in-memory key material when needed; caller-provided current PICC keys remain secret and must never be logged or persisted in plaintext.
