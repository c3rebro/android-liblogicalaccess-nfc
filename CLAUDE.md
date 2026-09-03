# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

Android DESFire/RFID runtime that executes RFIDGear project workflows (`.rfPrj` files) and fixed built-in card use cases via NFC. Currently supports read-only Quick Check and destructive-operation preflight validation. Pairs with the RFIDGear desktop authoring tool.

## Build Commands

**Windows one-click (build + test + install + launch):**
```bat
build-and-deploy.bat
build-and-deploy.bat -SkipDeploy   # build and test only
build-and-deploy.bat -SkipLaunch   # build but don't auto-launch
```

**JVM unit tests only (no device required, runs in CI):**
```bash
gradle --no-daemon :core-project:test :core-execution:test :core-usecase:test :rfidgear-runtime:test
```

**Run a single test class:**
```bash
gradle --no-daemon :rfidgear-runtime:test --tests "de.shansen.rfidgearruntime.DesfireQuickCheckServiceTest"
```

**Android Kotlin compilation check (no device required):**
```bash
gradle --no-daemon :app:compileDebugKotlin
```

**Full build (all modules including native):**
```bash
gradle --no-daemon build
```

The native build (liblogicalaccess via Conan + NDK) runs as part of `:app` and requires Android NDK 27.0.12077973 and Conan 2.31.1 installed. The `build-and-deploy.bat` script installs all prerequisites automatically on Windows.

## Module Structure

Six Gradle modules with strict layering — lower modules must not depend on higher ones:

```
app
├── rfidgear-runtime  → core-project, core-card, core-execution
├── core-usecase      → core-card
└── core-project      (no internal deps)
    core-execution    → core-project
    core-card         (no internal deps)
```

- **core-card** — `CardBackend` interface, `CardCommand`/`CardResponse` sealed types for all DESFire operations, `DesfireKey`, `CardError`
- **core-project** — ZIP/XML parsing of `.rfPrj` files (`RfProjectReader`), validation (`RfProjectValidator`), domain model
- **core-execution** — `RfExecutionEngine`: task sequencing and conditional logic
- **core-usecase** — Standalone built-in workflows: `DesfireFormatUseCase`, `DesfireFactoryResetUseCase`, `BuiltInUseCaseCatalog`
- **rfidgear-runtime** — RFIDGear field-to-action compiler (`RfidGearTaskCompiler`), `DesfireQuickCheckService`, `RfidGearActionSafetyPolicy`, `DesfireQuickCheckReportDocument`
- **app** — Android entry point: `MainActivity` (NFC discovery, UI), `NativeBridge` (JNI), `AndroidIsoDepTransport` (IsoDep wrapper), `NativeDesfireCardBackend`, `DesfireQuickCheckPdfRenderer`

## Architecture: JNI Boundary

The native liblogicalaccess C++ library owns the DESFire session state. Android NFC (`IsoDep`) is injected into native code via a global transport reference managed by `NativeBridge`/`native_bridge.cpp`. All APDU bytes flow: Android NFC → `AndroidIsoDepTransport` → JNI → liblogicalaccess → DESFire card.

`NativeDesfireCardBackend` implements `CardBackend` by calling JNI methods. This is the only class that crosses the JNI boundary from Kotlin.

## CardBackend Interface Pattern

`CardBackend` (core-card) is the platform-neutral abstraction. All workflows accept a `CardBackend`, not an Android type. Tests use fake `CardBackend` implementations. Adding new DESFire operations means:
1. Adding a `CardCommand` subtype and matching `CardResponse` subtype in core-card
2. Implementing dispatch in `NativeDesfireCardBackend` (JNI call) and the fake test backend
3. Using it in a use case or the rfidgear-runtime compiler

## Safety Policy

Destructive operations (Format, Factory Reset) are **intentionally disabled** in native execution. `DesfireFormatUseCase` and `DesfireFactoryResetUseCase` perform read-only preflight checks only — they validate whether the operation would succeed but do not execute it. `RfidGearActionSafetyPolicy` enforces which RFIDGear task types are authorized to run.

Do not enable destructive execution without explicit design review.

## Key Constraints

- **No persistent key storage** — `DesfireKey` is session-only; `clear()` zeros key bytes on disconnect. No Android Keystore usage yet.
- **Report redaction** — `DesfireQuickCheckReportDocument` must never include raw key bytes or write payloads. PDF reports are safe to share.
- **Untrusted `.rfPrj` input** — XML parsing disables external entities and enforces size limits.
- **arm64-v8a only** — Native library cross-compiles for arm64-v8a exclusively (NDK ABI filter).
- **JVM tests only in CI** — GitHub Actions runs JVM tests + Android compile check. Hardware tests (physical DESFire card + Windows) run manually via `build-and-deploy.bat`.

## Native Build Prerequisites (Windows)

JDK 17, Android SDK (platform-tools, Android 35, Build-tools 35.0.0, NDK 27.0.12077973, CMake 3.22.1), Conan 2.31.1. The `build-and-deploy.bat` script auto-installs missing tools via winget and handles SDK license acceptance.

liblogicalaccess 3.7.0 is pinned in `native/conanfile.txt` and must not be upgraded without testing on a physical DESFire card with full session/crypto validation.
