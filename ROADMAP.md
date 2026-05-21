# Khord Roadmap

This is the public roadmap for Khord. It reflects what's shipped, what's in progress, and where we're headed. For detailed technical tracking, see the [project board](https://github.com/orgs/Khord-Project/projects/1).

Khord is a proof of concept under active development. Priorities shift based on tester feedback and real-world usage.

---

## ✅ Shipped

### Core Messaging
- [x] End-to-end encrypted 1:1 messaging (Signal Protocol: X3DH + Double Ratchet)
- [x] Group messaging — fully client-side, servers have no concept of groups
- [x] Per-contact unlinkable mailboxes
- [x] Real-time WebSocket push notifications
- [x] Message timestamps and date separators
- [x] Delete conversation (local, per-contact)

### Identity & Security
- [x] Key-as-identity (Ed25519) — no accounts, no phone numbers, no email
- [x] BIP39 seed phrase generation
- [x] Seed phrase recovery — same words restore the same identity
- [x] Session reset protocol — contacts reconnect automatically after recovery
- [x] Panic button — instant, total device wipe
- [x] SQLCipher encrypted local storage with Android Keystore
- [x] FLAG_SECURE on sensitive screens

### Contact Exchange
- [x] One-directional QR code scanning
- [x] Manual contact link paste (no camera required)
- [x] Contact link sharing via system share sheet

### Infrastructure
- [x] Split-trust two-server architecture (Key Server + Relay Server)
- [x] Production servers at keys.khord.org / relay.khord.org
- [x] Split Coolify deployment (independent server lifecycles)
- [x] CI/CD pipeline — automated tests + signed APK on every release
- [x] In-app update checker
- [x] In-app bug reporting → GitHub Issues
- [x] Diagnostic ring buffer for field debugging
- [x] Runtime server selection (community or self-hosted)

### UX
- [x] 3 color themes (Teal / Forest / Minimal) × light + dark
- [x] Display names above message bubbles
- [x] Recent chats home screen
- [x] Version display in settings
- [x] Khord launcher icon

---

## 🔜 Next

These are the priorities for the next development cycle, driven by tester feedback.

- [ ] **Contact acceptance gate** — recipients must approve new contacts before messages are delivered
- [ ] **F-Droid listing** — metadata, reproducible builds, submission
- [ ] **One-time secret link sharing** — share contact info through a self-destructing encrypted link
- [ ] **Dead contact detection** — graceful handling when a contact's identity no longer exists
- [ ] **Xiaomi / MIUI reliability** — SharedPreferences passphrase backup for devices with unreliable Keystore

---

## 📋 Planned

These are confirmed goals, not yet scheduled.

### Features
- [ ] Media attachments (images, files)
- [ ] Contact blocking and muting
- [ ] Message reactions
- [ ] Group admin transfer
- [ ] Group rename UI
- [ ] Contact rename from chat

### Platform
- [ ] iOS client (KMP shared module is ready, needs SwiftUI)
- [ ] Google Play Store listing
- [ ] UnifiedPush / FCM push backends (battery optimization)
- [ ] Multi-device sync

### Protocol
- [ ] Sender Keys optimization for large groups
- [ ] Key rotation protocol
- [ ] Traffic analysis resistance (message padding, timing obfuscation)
- [ ] Cross-introduction protocol (group members who haven't scanned each other)

### Security & Operations
- [ ] Professional security audit
- [ ] Reproducible builds
- [ ] Data processing commitment for community servers
- [ ] CONTRIBUTING.md with development methodology

---

## 🔭 Exploring

Ideas we're interested in but haven't committed to.

- [ ] Voice and video calls
- [ ] Desktop client
- [ ] Disappearing messages
- [ ] One-time secret sharing service (self-hosted at khord.org)

---

## How We Work

Khord is built by a human architect and AI collaborators. The architecture, design decisions, and user experience are driven by the architect. Implementation, testing, and iteration happen through AI-assisted development. Every architectural decision is documented in [ADRs](docs/decisions/). The full development story is told in our articles:

1. [Privacy by Architecture](docs/articles/privacy-by-architecture.md) — what Khord is and why
2. [The Architect and the Translator](docs/articles/architect-and-translator.md) — how we build
3. [The Bug That Wasn't There](docs/articles/the-bug-that-wasnt-there.md) — what happens when the collaboration gets it wrong

---

*Last updated: May 2026*
