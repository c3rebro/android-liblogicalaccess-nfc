# Next steps

## Current boundary

Implemented in source:

1. RFIDGear `.rfPrj` ZIP/XML parsing, validation and task-order compilation.
2. A platform-neutral DESFire card command model.
3. A read-only Android DESFire Quick Check using `IsoDep.transceive()` through JNI/liblogicalaccess.
4. Session-only AID key entry for protected Quick Check metadata.
5. A separated safety/readiness policy for compiled RFIDGear actions, so the preview distinguishes read-only intent, current backend support, disabled writes and destructive operations.

Still to verify on the Windows build machine:

1. Conan cross-build of liblogicalaccess `3.7.0` for `arm64-v8a`.
2. CMake link of the JNI bridge against the generated Conan package.
3. APK packaging/loading of all required `.so` dependencies.
4. Physical DESFire card run through Android NFC.

## Recommended next work

1. Run `build-and-deploy.bat -SkipDeploy` on the Windows/Android Studio machine and capture the first native compiler/linker errors.
2. Run a physical read-only Quick Check against a DESFire EV2/EV3 card and compare the output with RFIDGear on a desktop reader.
3. Add redacted APDU TX/RX diagnostics around the native transport, disabled by default and never logging key bytes.
4. Map `DesfireReadData` in the native backend as the next non-mutating project-execution primitive.
5. Add an execution screen that can run only actions where `safeToRun && backendSupports(action)` is true.
6. Keep create/write/delete/format/change-key operations disabled until the exact RFIDGear payload mapping and confirmation UX are specified.
7. Add CI for JVM tests first; add native Android build CI after the Conan recipe is stable.
