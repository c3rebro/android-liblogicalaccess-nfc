# Built-in card use cases

The Android app can execute fixed workflows without an RFIDGear `.rfPrj` file. These workflows use the same platform-neutral `CardCommand` model as project execution but are defined and tested independently from RFIDGear persistence.

## Catalog

`core-usecase` currently defines:

| Use case | Risk | Current Android state |
| --- | --- | --- |
| DESFire Quick Check | READ_ONLY | Runtime/native path implemented; physical verification pending |
| DESFire Format | DESTRUCTIVE | Read-only preflight implemented; native FORMAT_PICC execution intentionally disabled |
| DESFire Factory Reset | DESTRUCTIVE | Read-only preflight + full core state machine implemented; destructive native execution intentionally disabled |

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
5. a fresh DESFire `GetVersion` probe must succeed immediately before the destructive backend operation;
6. the supplied credential must be PICC master key number `0`;
7. the core invokes `DesfireFormatCard` at most once;
8. the core never performs an automatic destructive retry, and a destructive backend must not retry `FORMAT_PICC` internally;
9. once the destructive backend operation is invoked, the authorization is consumed even if authentication/transport fails;
10. verification is read-only and attempts to confirm that the application directory is empty.

`DesfireFormatResult.destructiveOperationInvoked` means that the core handed control to the backend's destructive format operation. It deliberately does **not** claim that the `FORMAT_PICC` APDU reached the card: a backend may still fail during PICC selection/authentication before sending the destructive APDU.

Likewise, a backend/card acknowledgement alone is not treated as verified success. `SUCCESS_VERIFIED` requires a successful post-format application-directory check showing no remaining applications. If the format operation reports success but verification is unavailable, the result remains explicitly unverified.

The current Android `NativeDesfireCardBackend` remains read-only and therefore cannot execute `DesfireFormatCard` yet. The UI exposes only the preflight and clearly states that no format command is sent.

### Desktop/native reference sequence

RFIDGear's current liblogicalaccess provider performs DESFire format as:

```text
selectApplication(0)
authenticate(0, PICC master key)
erase()
```

In liblogicalaccess 3.7.0, `DESFireISO7816Commands::erase()` sends `DF_INS_FORMAT_PICC` and requires status `91 00`. A later Android native-format implementation should mirror this sequence rather than inventing a separate APDU path.

## DESFire Factory Reset

Factory Reset is intentionally a separate built-in workflow from Format.

The target state is:

- all applications and files are removed through `FORMAT_PICC`;
- PICC master key number `0` is changed to DES / 2K3DES-compatible format;
- the key value is 16 zero bytes, i.e. `00000000000000000000000000000000` (32 hexadecimal zero characters);
- the key version is `0`.

This definition matches RFIDGear's documented DESFire factory key convention: DES uses a 16-byte DES/2K3DES representation. The Factory Reset use case currently **does not change PICC key-settings bits**; that would be a separate explicit requirement if needed later.

Factory Reset uses its own confirmation phrase:

```text
FACTORY RESET <UID>
```

The destructive core sequence is:

```text
read-only preflight
  -> exact UID-bound confirmation
  -> fresh GetVersion on the same card
  -> FORMAT_PICC once
  -> change PICC master key #0 once to DES / 16x00 / version 0
  -> authenticate with the new factory key
  -> verify that the application directory is empty
```

No destructive step is automatically retried. In particular, if `FORMAT_PICC` succeeds but the PICC key change fails, the result is `FORMATTED_KEY_RESET_FAILED`: the card is known to have been formatted, but the factory key target was not reached. The workflow must not automatically format the card a second time.

`DesfireChangePiccMasterKey` is a dedicated core command so a future native encoder backend can implement the exact PICC operation without manufacturing application-key settings. Its expected native sequence is:

```text
selectApplication(0)
authenticate(0, current PICC master key)
changeKey(0, factory DES zero key)
```

The current Android native backend remains read-only, so the Android UI exposes only the Factory Reset preflight. No `FORMAT_PICC` or `changeKey` command is reachable from the current UI/backend combination.

## UID binding limitation

The first implementation binds destructive authorization to Android's presented NFC UID. DESFire configurations using randomized/privacy UIDs may present a different identifier on a later scan. This is intentionally a safe failure (the destructive operation is blocked), but a later implementation may need a stable card fingerprint derived from authenticated/version data before format/factory-reset execution can be enabled for such cards.

## Architectural rule

Built-in workflows and `.rfPrj` workflows must converge on the same `core-card` command types. Do not duplicate DESFire protocol/APDU logic in use-case or UI code.
