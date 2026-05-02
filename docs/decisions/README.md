# Architecture Decision Records

This directory contains the Architecture Decision Records (ADRs) for Khord. Each ADR documents a significant architectural choice, the context and options considered, the decision made, and its consequences.

ADRs are numbered sequentially. Once accepted, an ADR is not modified — if a decision is reversed, a new ADR is created that supersedes the original.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [001](001-threat-model.md) | Threat Model Tiers | Accepted |
| [002](002-split-trust-architecture.md) | Split-Trust Two-Server Architecture | Accepted |
| [003](003-identity-model.md) | Identity Model — Key as Identity | Accepted |
| [004](004-introduction-model.md) | QR-Only Contact Introduction | Accepted |
| [005](005-mailbox-model.md) | Per-Contact Directional Mailboxes | Accepted |
| [006](006-crypto-protocol.md) | Cryptographic Protocol — libsodium + X3DH + Double Ratchet | Accepted |
| [007](007-message-format.md) | Message Envelope Format and Ordering | Accepted |
| [008](008-transport-protocol.md) | Hybrid Transport — Poll + Time-Boxed WebSocket | Accepted |
| [009](009-client-platform.md) | Client Platform — Kotlin Multiplatform, Android First | Accepted |
| [010](010-backend-stack.md) | Backend Stack — FastAPI + PostgreSQL | Accepted |
| [011](011-server-authentication.md) | Server Authentication — Challenge-Response and Bearer Tokens | Accepted |
| [012](012-abuse-prevention.md) | Rate Limiting and Abuse Prevention | Accepted |
| [013](013-push-notifications.md) | Push Notifications — UnifiedPush | Accepted |
| [014](014-key-backup.md) | Key Backup — Seed Phrase + Optional Shamir | Accepted |
| [015](015-message-persistence.md) | Message Persistence — Client-Local + Encrypted Backup | Accepted |
| [016](016-logging-policy.md) | Logging Policy — Allow-List Only | Accepted |
| [017](017-licensing.md) | Licensing — AGPL Code, CC-BY-SA Protocol Spec | Accepted |
| [018](018-api-versioning.md) | API Versioning — /v1/ Prefix From Day One | Accepted |
| [019](019-deployment.md) | Deployment — Docker Compose for PoC | Accepted |
| [020](020-registration-flow.md) | Registration and Onboarding Flow | Accepted |
| [021](021-testing-strategy.md) | Testing Strategy — Mandatory Protocol and Adversarial Tests | Accepted |
