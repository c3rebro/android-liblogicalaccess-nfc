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
8. A DESFire Format built-in workflow with read-only preflight, UID-bound confirmation and Fake-Backend tests. Native `FORMAT_PICC` execution remains disabled.
9. GitHub Actions JVM tests for the hardware-independent modules.

Still to verify on the Windows build machine / physical device:

1. Conan cross-build of liblogicalaccess `3.7.0` for `arm64-v8a`.
2. CMake link of the JNI bridge against the generated Conan package.
3. APK packaging/loading of all required `.so` dependencies.
4. Physical DESFire Quick Check through Android NFC.
5. Visual verification of generated Quick Check PDFs on Android.
6. Read-only DESFire Format preflight on a real card.

## Recommended next work

1. Let the JVM CI run on the feature PR and fix any Kotlin/Gradle issues it finds.
2. Run `build-and-deploy.bat -SkipDeploy` when the Windows/Android Studio machine is available.
3. Run a physical read-only Quick Check against a DESFire EV2/EV3 card and compare the output/PDF with RFIDGear.
4. Add redacted APDU TX/RX diagnostics around the native transport, disabled by default and never logging key bytes.
5. Decide how destructive authorization should identify cards that use randomized/privacy UIDs.
6. Only after the native read-only path is verified, add a separate destructive/encoder backend exposing `DesfireFormatCard` and wire the already modelled format workflow to it.
7. Map `DesfireReadData` as the next non-mutating `.rfPrj` execution primitive.
8. Keep create/write/delete/change-key operations disabled until their exact RFIDGear semantics and confirmation UX are defined.
