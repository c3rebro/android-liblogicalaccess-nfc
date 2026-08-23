# Built-in card use cases

The Android app can execute fixed workflows without an RFIDGear `.rfPrj` file. These workflows use the same platform-neutral `CardCommand` model as project execution but are defined and tested independently from RFIDGear persistence.

## Catalog

`core-usecase` currently defines:

| Use case | Risk | Current Android state |
| --- | --- | --- |
| DESFire Quick Check | READ_ONLY | Runtime/native path implemented; physical verification pending |
| DESFire Format | DESTRUCTIVE | Read-only preflight implemented; native FORMAT_PICC execution intentionally disabled |

## DESFire format safety model

Formatting is deliberately split into two phases.

### 1. Read-only preflight

The app:

1. identifies the card and requires MIFARE DESFire;
2. requires a successful DESFire `GetVersion` response as a positive protocol probe;
3. tries to list applications without authentication;
4. records an internal copy of the presented NFC UID;
5. derives a confirmation phrase: `FORMAT <UID>`.

No mutating command is sent during preflight. `DesfireFormatPreflight` cannot be constructed by application/UI code; it is created inside `core-usecase` only after the positive DESFire probe. The confirmation target is based on a private UID snapshot, so mutating the public `CardIdentity.uid` byte array cannot change which card is authorized.

### 2. Destructive execution

The core workflow already models the future execution contract:

1. the user must explicitly confirm the exact preflight phrase;
2. the resulting authorization is one-shot;
3. the card is presented again;
4. its UID must match the preflight authorization;
5. a fresh DESFire `GetVersion` probe must succeed immediately before the destructive command;
6. the PICC master key is supplied;
7. `DesfireFormatCard` / DESFire `FORMAT_PICC` is sent at most once;
8. failures never trigger an automatic destructive retry;
9. any format attempt consumes the authorization, including authentication/transport failure;
10. verification is read-only and attempts to confirm that the application directory is empty.

A backend/card acknowledgement alone is not treated as verified success. `SUCCESS_VERIFIED` requires a successful post-format application-directory check showing no remaining applications. If `FORMAT_PICC` reports success but verification is unavailable, the result remains explicitly unverified.

The current Android `NativeDesfireCardBackend` remains read-only and therefore cannot execute `DesfireFormatCard` yet. The UI exposes only the preflight and clearly states that no format command is sent.

## UID binding limitation

The first implementation binds destructive authorization to Android's presented NFC UID. DESFire configurations using randomized/privacy UIDs may present a different identifier on a later scan. This is intentionally a safe failure (the format command is blocked), but a later implementation may need a stable card fingerprint derived from authenticated/version data before format execution can be enabled for such cards.

## Architectural rule

Built-in workflows and `.rfPrj` workflows must converge on the same `core-card` command types. Do not duplicate DESFire protocol/APDU logic in use-case or UI code.
