# Architecture

## Goal

Provide a small Android test application that can use a phone's integrated NFC controller while keeping the card logic in native C++ so liblogicalaccess can be integrated without leaking native concerns into the Android UI.

## Layers

### Android discovery

`MainActivity` owns `NfcAdapter.ReaderCallback` and receives `Tag` objects.

### Android transport

`AndroidIsoDepTransport` exposes the raw ISO-DEP transceive path required for APDU-level communication.

### JNI

`NativeBridge` establishes the native boundary. The current implementation stores a global reference to the active transport. A subsequent liblogicalaccess reader adapter can invoke `transceive(byte[])` through JNI.

### liblogicalaccess

Not vendored yet. Pin a version first, then implement the reader/provider adapter required by the desired card technology.

## Why discovery stays in Kotlin

Android NFC discovery and the `Tag`/`IsoDep` handles live in the Android framework. Keeping discovery in Kotlin and exposing only the APDU transport to C++ avoids reimplementing Android lifecycle and NFC framework handling in JNI.
