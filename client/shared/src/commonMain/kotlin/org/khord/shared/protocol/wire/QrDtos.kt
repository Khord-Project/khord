package org.khord.shared.protocol.wire

import kotlinx.serialization.Serializable

/**
 * QR-code payload exchanged between contacts — PROTOCOL.md §9.
 *
 * Carries everything Alice needs to start a Khord session with Bob
 * (or vice versa) without server-mediated discovery:
 *
 *   - `identityKey` — Bob's Ed25519 public (32 B, base64). Trust anchor.
 *   - `fingerprint` — full 64-char hex SHA-256 of `identityKey`.
 *     Redundant with `identityKey` but included for verification convenience.
 *   - `keyServer` — Bob's preferred Key Server base URL. Alice fetches
 *     Bob's pre-key bundle here; she trusts the BUNDLE only after
 *     verifying it ties to `identityKey` (X3DH §4.5 / ADR 003).
 *   - `relayServer` — Bob's preferred Relay Server base URL. Alice POSTs
 *     her first encrypted message to `relay_mailbox` on this server.
 *     (PROTOCOL.md §9 amendment — without this, the protocol would be
 *     hardcoded to a single relay operator, contradicting ADR 002 / 008.)
 *   - `relayMailbox` — Bob's freshly-minted inbound mailbox ID for THIS
 *     contact (per-contact directional, ADR 005).
 */
@Serializable
data class QrPayload(
    val version: Int = 1,
    val identityKey: String,
    val fingerprint: String,
    val keyServer: String,
    val relayServer: String,
    val relayMailbox: String,
)
