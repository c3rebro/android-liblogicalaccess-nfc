# DESFire Quick Check

## Goal

Implement RFIDGear's DESFire Quick Check as a non-destructive Android runtime operation.
The Quick Check is intentionally implemented above the card backend: it is an orchestration of DESFire reads/authentication attempts, not a single native command.

## Current runtime flow

1. Connect to one card session.
2. Identify the card and require MIFARE DESFire.
3. Read DESFire version information where available.
4. Read free memory where available.
5. Try application-directory listing without authentication.
6. If PICC directory listing is protected, try configured PICC key candidates.
7. For every discovered AID:
   - try application settings without authentication;
   - try file listing without authentication;
   - only if listing is denied, try configured keys for that AID;
   - AID-specific keys are tried before global application defaults;
   - after successful authentication, read application settings, file IDs and file settings.
8. Return a structured report. No card write/change operation is part of Quick Check.

## Access states

The runtime distinguishes:

- `PUBLIC`: information was available without authentication.
- `AUTHENTICATED`: a configured key was required and accepted.
- `KEY_REQUIRED`: the application exists, but the available key set does not permit listing.
- `DENIED`: a key was accepted for the surrounding operation but a subordinate metadata read was still denied.
- `UNAVAILABLE`: metadata was not available for another reason.

This is intentionally more explicit than the original RFIDGear tree UI because a successful `GetKeySettings` call does not necessarily prove that an attempted key authenticated; DESFire may permit public metadata reads.

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
  -> KEY_REQUIRED
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

## Android key UI

The app currently supports adding an application-specific Quick Check key by AID.
The UI accepts decimal or `0x`-prefixed AIDs and common key separators.

Keys are currently **session-only**:

- they are kept in memory;
- they are not written to SharedPreferences or project files;
- key values are not displayed again after adding them;
- clearing session keys overwrites the current in-memory key byte arrays before dropping the configuration.

When native Quick Check execution is connected, the intended UI flow is:

```text
scan card
  -> report contains KEY_REQUIRED for AID 0x123456
  -> prompt "Define key for AID 0x123456"
  -> user enters key
  -> ask user to present the card again
  -> next Quick Check retries AID-specific key before global defaults
```

Persistent key storage, if added later, must be implemented separately with Android Keystore-backed encryption. Raw DESFire keys must not be persisted as plaintext preferences.

## CardBackend primitives added for Quick Check

- `DesfireGetVersion`
- `DesfireGetFreeMemory`
- `DesfireListApplications`
- `DesfireAuthenticate`
- `DesfireReadApplicationSettings`
- `DesfireListFiles`
- `DesfireReadFileSettings`

Responses carry typed DESFire metadata and, where relevant, whether the backend knows that authentication succeeded.

## Native work still required

The current JNI bridge still only holds an `AndroidIsoDepTransport`; liblogicalaccess is not linked yet.
The next implementation step is a native `CardBackend` implementation that maps the Quick Check primitives to liblogicalaccess 3.7.0:

```text
Android IsoDep.transceive()
        |
JNI DataTransport
        |
ISO7816ReaderCardAdapter
        |
DESFireISO7816Commands / DESFireEV1ISO7816Commands
        |
CardBackend responses
        |
DesfireQuickCheckService
```

All commands for one Quick Check must run inside one uninterrupted `IsoDep`/JNI/liblogicalaccess session and on the same JNI-owning thread.
