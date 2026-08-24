# Next steps

## Current boundary

Implemented in source:

1. RFIDGear `.rfPrj` ZIP/XML parsing, validation and task-order compilation.
2. A platform-neutral DESFire card command model.
3. A read-only Android DESFire Quick Check using `IsoDep.transceive()` through JNI/liblogicalaccess.
4. Session-only AID key entry for protected Quick Check metadata.
5. A separated safety/readiness policy for compiled RFIDGear actions.
6. A secret-free Quick Check report document with text and Android PDF renderers.
7. Built-in use-case infrastructure independent of `.rfPrj` files.
8. A DESFire Format built-in workflow with read-only preflight, positive DESFire protocol probes, immutable UID-bound confirmation, one-shot authorization, PICC master-key-#0 enforcement and Fake-Backend tests. Native `FORMAT_PICC` execution remains disabled.
9. GitHub Actions coverage for the hardware-independent JVM tests and Android Kotlin/UI compilation.
10. The desktop/native reference sequence for DESFire format is confirmed: RFIDGear uses `selectApplication(0) -> authenticate(0, masterKey) -> erase()`, and liblogicalaccess 3.7.0 implements `erase()` with `DF_INS_FORMAT_PICC`.

Still to verify on the Windows build machine / physical device:

1. Conan cross-build of liblogicalaccess `3.7.0` for `arm64-v8a`.
2. CMake link of the JNI bridge against the generated Conan package.
3. APK packaging/loading of all required `.so` dependencies.
4. Physical DESFire Quick Check through Android NFC.
5. Visual verification of generated Quick Check PDFs on Android.
6. Read-only DESFire Format preflight on a real card.

## Recommended next work

1. Keep PR #2 as the bounded reporting/built-in-use-case change and merge it after review when desired.
2. Run `build-and-deploy.bat -SkipDeploy` when the Windows/Android Studio machine is available.
3. Run a physical read-only Quick Check against a DESFire EV2/EV3 card and compare the output/PDF with RFIDGear.
4. Run the read-only Format preflight on real DESFire cards and confirm `GetVersion`, UID binding and visible application reporting.
5. Add redacted APDU TX/RX diagnostics around the native transport, disabled by default and never logging key bytes.
6. Decide how destructive authorization should identify cards that use randomized/privacy UIDs.
7. Only after the native read-only path is verified, add a separate destructive/encoder backend exposing `DesfireFormatCard`; it must mirror the confirmed `selectApplication(0) -> authenticate(0, key #0) -> erase()` sequence and must not retry the destructive operation internally.
8. Map `DesfireReadData` as the next non-mutating `.rfPrj` execution primitive.
9. Keep create/write/delete/change-key operations disabled until their exact RFIDGear semantics and confirmation UX are defined.
