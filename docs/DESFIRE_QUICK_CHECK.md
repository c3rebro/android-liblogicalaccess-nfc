# DESFire Quick Check

## Goal

Implement RFIDGear's DESFire Quick Check as a non-destructive Android runtime operation.
The Quick Check is intentionally implemented above the card backend: it is an orchestration of DESFire reads/authentication attempts, not a single native command.

## Current runtime flow

1. Android detects an ISO-DEP tag and opens one `IsoDep` session.
2. The JNI bridge creates one liblogicalaccess DESFire session on the same callback thread.
3. Read DESFire version information where available.
4. Read free memory where available.
5. Try application-directory listing without authentication.
6. If PICC directory listing is protected, try configured PICC key candidates.
7. For every discovered AID:
   - try application settings without authentication;
   - try file listing without authentication;
   - when listing is protected, try configured keys for that AID;
   - AID-specific keys are tried before global application defaults;
   - if file IDs are public but individual file settings are protected, retry those file settings with configured AID keys;
   - report which metadata was public and which required authentication.
8. Destroy the native session and close `IsoDep`.

No write, create, delete, format, change-key or change-settings operation is exposed by the native Quick Check backend.

## Access states

The runtime distinguishes:

- `PUBLIC`: information was available without authentication.
- `AUTHENTICATED`: a configured key was required and accepted.
- `KEY_REQUIRED`: the application/file exists, but no key is configured for the protected metadata.
- `DENIED`: configured keys were tried but did not permit the metadata read; the AID remains eligible for adding another key.
- `UNAVAILABLE`: metadata was not available for another reason.

This is intentionally more explicit than the original RFIDGear tree UI because a successful metadata read does not necessarily prove that an attempted key authenticated; DESFire may permit public metadata reads.

## Key configuration

`DesfireQuickCheckConfig` contains three independent key scopes:

- `piccKeys`: candidates for protected application-directory listing;
- `defaultApplicationKeys`: fallback candidates for all applications;
- `applicationKeys`: keys associated with a specific AID.

For application inspection the order is:

```text
public access
  -> AID-specific keys
  -> global application defaults
  -> KEY_REQUIRED / DENIED
```

A key contains:

- human-readable label;
- DESFire key type (`AES`, `TDES_3K`, `DES`/2K3DES representation);
- key number;
- key bytes.

The runtime validates exact key sizes:

- AES: 16 bytes;
- DES / 2K3DES representation used by RFIDGear: 16 bytes;
- 3K3DES: 24 bytes.

The key object's `toString()` never emits the secret bytes.

## Card-family identification

ISO-DEP support alone does not identify DESFire. RFIDGear checks the read-only GetVersion hardware product family before starting any DESFire action (NXP AN10833), supports wrapped and native Plus response framing, and recognizes legacy Plus S/X/SE historical-byte signatures. An unidentified ISO-DEP card is recorded as unconfirmed rather than DESFire. DESFire Light is identified separately; the current DESFire actions do not support it.

Non-ISO-DEP cards are recorded with UID, activation metadata, Android technology list and an explicit capability message. Android's Classic/Ultralight APIs, when exposed for that card, indicate phone support; RFIDGear currently implements neither family's memory read/write flow. Classic-compatible identification can also mean Plus in SL1, so it is not presented as an exact chip-family identification.

References: [NXP AN10833](https://www.nxp.com/docs/en/application-note/AN10833.pdf), [Android NFC technologies](https://developer.android.com/develop/connectivity/nfc/advanced-nfc).

## Android key UI

The app supports adding an application-specific Quick Check key by AID.
The UI accepts decimal or `0x`-prefixed AIDs and common key separators.

Keys are **session-only by default**:

- they are kept in memory;
- they survive orientation changes but are discarded when the app session ends;
- key values are not displayed again after adding them;
- clearing session keys overwrites the current in-memory key byte arrays before dropping the configuration.

The active flow is:

```text
scan card
  -> Quick Check reports KEY_REQUIRED or DENIED for AID 0x123456
  -> app opens "Define key for AID 0x123456"
  -> user enters key type, number and value
  -> present card again
  -> next Quick Check retries the AID-specific key before global defaults
```

Both key dialogs have an unchecked permanent-storage option and warn that permanent storage is not recommended. Opted-in keys are encrypted using AES-GCM with an Android Keystore wrapping key. Only ciphertext and the IV reach private preferences; project files and reports never contain raw keys. Clearing application keys also deletes saved application keys. Clearing the PICC key deletes its saved copy.

## CardBackend primitives used by Quick Check

- `DesfireGetVersion`
- `DesfireGetFreeMemory`
- `DesfireListApplications`
- `DesfireAuthenticate`
- `DesfireReadApplicationSettings`
- `DesfireListFiles`
- `DesfireReadFileSettings`

The generic `core-card` model also contains destructive commands for the future encoder, but `NativeDesfireCardBackend` deliberately does not map them. Passing one of those commands to the Quick Check backend returns `PROTOCOL_CONSTRAINT` before a native card operation is attempted.

## Native implementation

The native read-only path is implemented as:

```text
Android IsoDep.transceive()
        |
AndroidIsoDepDataTransport (C++ / JNI)
        |
ISO7816ReaderCardAdapter
        |
DESFireISO7816ResultChecker
        |
DESFireEV1Chip + DESFireEV1ISO7816Commands
        |
NativeDesfireCardBackend
        |
DesfireQuickCheckService
```

`AndroidIsoDepDataTransport` is pinned to the JNI thread on which it was created. A complete Quick Check therefore runs synchronously inside one `onTagDiscovered()` callback and one uninterrupted `IsoDep` session.

The native bridge exposes only seven operation IDs for Quick Check: version, free memory, application listing, authenticate, application settings, file listing and file settings.

## Native dependency build

The Windows bootstrap prepares liblogicalaccess `3.7.0` for `arm64-v8a` with Conan and the Android NDK before Gradle/CMake builds the APK. Generated native dependencies are staged under `.tools/`, which is git-ignored.

`build-and-deploy.bat` remains the intended entry point for the complete Windows build/test/install flow.

## Verification status

Implemented and covered by JVM/Fake-Backend tests:

- public application/file listing;
- AID-specific key fallback;
- AID keys before global defaults;
- protected file metadata even when file IDs are public;
- PICC directory key fallback;
- key-length validation;
- `needsKeys` reporting for missing or unsuccessful AID keys.

Implemented but still requiring the first real Windows/native build and physical-card verification:

- Conan cross-build of liblogicalaccess and dependencies with the pinned NDK;
- CMake link of the JNI bridge against the generated Conan package;
- packaging/loading all required Android `.so` dependencies;
- actual DESFire APDU exchange through `IsoDep.transceive()`;
- exception/status mapping against real cards and Android `TagLostException` behavior.
