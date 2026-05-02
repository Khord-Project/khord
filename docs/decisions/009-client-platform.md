# ADR 009: Client Platform — Kotlin Multiplatform, Android First

**Status:** Accepted
**Date:** 2026-05-02

## Context

The client must handle all cryptographic operations (key generation, X3DH, Double Ratchet), manage private keys securely, and provide a chat UI. The platform choice affects key storage security, code sharing across future platforms, and distribution channel options.

## Decision

**Kotlin Multiplatform (KMP)** for shared protocol/crypto layer. Android-first via F-Droid, iOS later.

### Architecture

- **Shared KMP module:** X3DH implementation, Double Ratchet state machine, key management, server communication, message serialization. Written once, tested once, audited once.
- **Android-specific:** UI (Jetpack Compose), Android Keystore integration for private key storage, UnifiedPush integration.
- **iOS-specific (future):** UI (SwiftUI), iOS Keychain integration. Shared KMP module compiles to iOS via Kotlin/Native.

### Why not PWA

A PWA was initially considered for PoC speed. However, PWA key storage options (Web Crypto API non-extractable keys, IndexedDB) are vulnerable to XSS in ways that native secure storage (Android Keystore, iOS Keychain) is not. For a project whose core value proposition is "absolute privacy," the key storage weakness was unacceptable even for a PoC.

### Why Android first

- F-Droid distribution aligns with privacy-first, open-source positioning
- Target audience (privacy-conscious users) skews toward F-Droid / sideloading
- No Google Play Services dependency required
- AGPL licensing is fully compatible with F-Droid

### libsodium bindings

- Android: lazysodium-android (Java/Kotlin bindings to libsodium)
- iOS (future): Kotlin/Native can call C libsodium directly, or use a Swift wrapper

## Consequences

- PoC timeline is longer than a PWA would have been (weeks vs. days). Accepted trade-off for proper key storage.
- The crypto/protocol code is written once in Kotlin and shared across platforms. Only UI and platform-specific integrations (keystore, push) are per-platform.
- F-Droid requires fully open source with no proprietary dependencies. KMP (Apache 2.0), libsodium (ISC), and all other dependencies must be FOSS.
- Reproducible builds (production requirement) are achievable with Kotlin/Gradle toolchain.
