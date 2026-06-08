# Khord Protocol Specification

**Version:** 0.1.0-draft
**License:** CC-BY-SA-4.0
**Status:** Draft — PoC implementation target

## 1. Overview

Khord is a split-trust encrypted messaging protocol. Two independent servers each hold partial metadata, neither able to reconstruct the full picture. All message content is end-to-end encrypted using the Signal Protocol (X3DH + Double Ratchet) implemented on libsodium primitives.

This document specifies the protocol in sufficient detail for independent implementation. It is the authoritative reference — all implementations must conform to this spec.

## 2. Actors and Components

### 2.1 Key Server

A REST API server that stores and serves cryptographic key material.

**Stores:**
- Public identity key fingerprints
- Signed pre-key bundles
- One-time pre-keys

**Does not know:**
- Mailbox IDs, bearer tokens, message content, communication patterns

**Authentication:** Cryptographic challenge-response (client proves possession of private identity key)

### 2.2 Relay Server

A REST + WebSocket server that routes opaque encrypted blobs to mailboxes.

**Stores:**
- Mailbox registrations (mailbox ID → bearer token hash)
- Undelivered message blobs (mailbox ID, sequence number, blob, TTL)

**Does not know:**
- Identity keys, user identities, who owns which mailbox, message content

**Authentication:** Opaque bearer tokens per mailbox

### 2.3 Client

An application that manages all cryptographic state and communicates with both servers.

**Manages:**
- Identity key pair (Ed25519 + X25519)
- Pre-key pairs and ratchet state
- Per-contact mailbox IDs and bearer tokens
- Contact list (identity keys, display names, mailbox addresses)
- Message history (local only)

## 3. Cryptographic Primitives

All primitives from libsodium:

| Operation | Primitive | libsodium function |
|-----------|-----------|-------------------|
| Identity key pair | Ed25519 | crypto_sign_keypair / crypto_sign_seed_keypair |
| Key agreement key pair | X25519 | crypto_box_keypair (or convert from Ed25519) |
| Diffie-Hellman | X25519 | crypto_scalarmult |
| Signatures | Ed25519 | crypto_sign_detached / crypto_sign_verify_detached |
| AEAD (Double Ratchet) | XChaCha20-Poly1305-IETF | crypto_aead_xchacha20poly1305_ietf_encrypt / _decrypt |
| Key derivation | HKDF-SHA-256 (RFC 5869) | manual HMAC-SHA-256 over crypto_hash_sha256 |
| HMAC | HMAC-SHA-256 | manual implementation per RFC 2104 (libsodium's `crypto_auth_hmacsha256` is hardcoded to a 32-byte key and unsuitable for arbitrary-length salts) |
| Password-based KDF | Argon2id | crypto_pwhash |
| Random bytes | OS CSPRNG | randombytes_buf |
| Hashing | SHA-256 | crypto_hash_sha256 |

### 3.1 Identity Key Derivation from Seed Phrase

```
seed_phrase → UTF-8 bytes
salt        = "khord-identity01"   (literal 16 ASCII bytes, no padding, no hashing)
raw_seed    = Argon2id(seed_phrase_bytes, salt,
                       opslimit  = crypto_pwhash_OPSLIMIT_MODERATE  = 3,
                       memlimit  = crypto_pwhash_MEMLIMIT_MODERATE  = 268_435_456 (256 MiB),
                       algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13 = 2,
                       outlen    = 32)
identity_signing_key   = Ed25519 keypair from raw_seed (crypto_sign_seed_keypair)
identity_agreement_key = X25519 keypair converted from Ed25519 keypair
                         (crypto_sign_ed25519_pk_to_curve25519 / _sk_to_curve25519)
fingerprint = hex(SHA-256(identity_public_signing_key))
```

The fingerprint is the full 32-byte SHA-256 digest, hex-encoded as a
64-character lowercase string. **No truncation.**

The salt is fixed at exactly 16 ASCII bytes (libsodium requires
`crypto_pwhash_SALTBYTES = 16`). The literal string `"khord-identity01"`
is chosen so any independent implementation can reproduce the salt
byte-for-byte without padding rules or hashing.

**Note:** The Argon2id parameters above are LOAD-BEARING. Any deviation
produces a different identity key from the same seed phrase, breaking
recovery for every existing user. Independent client implementations must
match these exact values.

## 4. Key Server API

Base URL: `https://<keyserver>/v1/`

### 4.1 Upload Pre-Key Bundle

```
POST /v1/keys/{fingerprint}/bundle
Authorization: Challenge-Response (see 4.4)

Body:
{
  "identity_key": "<base64 Ed25519 public key>",
  "signed_pre_key": {
    "key_id": <uint32>,
    "public_key": "<base64 X25519 public key>",
    "signature": "<base64 Ed25519 signature over public_key>"
  },
  "one_time_pre_keys": [
    {
      "key_id": <uint32>,
      "public_key": "<base64 X25519 public key>"
    },
    ...
  ]
}

Response: 201 Created
```

### 4.2 Fetch Pre-Key Bundle

```
GET /v1/keys/{fingerprint}/bundle
No authentication required

Response: 200 OK
{
  "identity_key": "<base64>",
  "signed_pre_key": {
    "key_id": <uint32>,
    "public_key": "<base64>",
    "signature": "<base64>"
  },
  "one_time_pre_key": {      // Single key, consumed on fetch
    "key_id": <uint32>,
    "public_key": "<base64>"
  }
}
```

**Note:** One-time pre-keys are consumed on fetch (returned once, then deleted). If none remain, the response omits the field and X3DH proceeds without it (reduced properties but still functional per spec).

**Rate limiting (abuse protection).** This endpoint is public and consumes a
one-time pre-key on every call, so an unthrottled attacker could drain a
victim's OPK pool and force every new conversation with them into the
reduced-property (OPK-less) handshake. Two per-minute ceilings apply (see
§12): a **per-fingerprint** limit (default 5/min) so one victim's pool can't
be drained, and a **per-IP** limit (default 30/min) so one caller can't sweep
many victims. Over either, the server returns `429 Too Many Requests` with a
`Retry-After` header. Legitimate clients fetch a given bundle once per new
conversation and never approach these limits.

### 4.3 Replenish One-Time Pre-Keys

```
POST /v1/keys/{fingerprint}/prekeys
Authorization: Challenge-Response

Body:
{
  "one_time_pre_keys": [
    {
      "key_id": <uint32>,
      "public_key": "<base64>"
    },
    ...
  ]
}

Response: 201 Created
```

### 4.4 Challenge-Response Authentication

```
Step 1: Client requests challenge
GET /v1/auth/challenge/{fingerprint}
Response: { "challenge": "<base64 random bytes>", "expires": <unix_timestamp> }

Step 2: Client signs challenge with identity key
POST /v1/auth/verify
{
  "fingerprint": "<fingerprint>",
  "challenge": "<base64 challenge>",
  "signature": "<base64 Ed25519 signature over challenge>",
  "identity_key": "<base64 Ed25519 public key>"   // optional, see below
}
Response: { "token": "<short-lived session token>" }

Step 3: Client uses session token for subsequent requests
Authorization: Bearer <session_token>
```

**`identity_key` field (Step 2).** This field exists to resolve the
chicken-and-egg of first-time registration (ADR 020 step 9): the very first
bundle upload requires an authenticated session, but the server has no
identity key stored yet for that fingerprint.

- **First-time auth** (no `identity_keys` row exists for `fingerprint`): the
  field is **REQUIRED**. Server validates `hex(SHA-256(identity_key)) ==
  fingerprint`, then verifies the signature against the provided key.
- **Subsequent auth** (row exists): the field is **IGNORED** — the server
  uses the stored key. As defense-in-depth, if the field is provided and
  does *not* match the stored key, the verify call is rejected.

All authentication failures return `401` with no detail beyond `"auth
failed"` — the server does not distinguish missing-row, expired-challenge,
reused-challenge, or bad-signature to the client.

### 4.5 Check Pre-Key Count

```
GET /v1/keys/{fingerprint}/prekey-count
Authorization: Challenge-Response

Response: { "count": <number of remaining one-time pre-keys> }
```

Client should replenish when count drops below a threshold (e.g., 10).

## 5. Relay Server API

Base URL: `https://<relayserver>/v1/`

### 5.1 Create Mailbox

```
POST /v1/mailboxes
Body:
{
  "mailbox_id": "<client-generated random ID, ≥22 base64url chars>",
  "proof_of_work": "<decimal-ASCII nonce solving the §5.6 puzzle>"
}

Response: 201 Created
{
  "mailbox_id": "<mailbox_id>",
  "bearer_token": "<base64url-encoded 32-random-bytes, no padding>"
}
```

**`mailbox_id` constraints.** The string must be at least 22 base64url
characters long (the minimum is also published by `GET /v1/pow-params`).
Clients SHOULD generate it as `base64url(random_16_bytes)` with padding
stripped — this gives 22 characters and ~128 bits of entropy, which is
both unguessable and large enough that the proof-of-work cannot be cheaply
grinded against short IDs. The server stores `mailbox_id` exactly as
received; it is opaque to the server.

**`bearer_token` encoding.** The server returns 32 random bytes, encoded as
URL-safe base64 with the trailing `=` padding stripped (43 characters). The
client uses the string verbatim in `Authorization: Bearer <token>` for
subsequent requests. The server stores only `sha256(token_bytes)` — the
plaintext is returned exactly once. If the token is lost, the client must
create a new mailbox.

**Duplicate `mailbox_id`.** Returns `409 Conflict`.

### 5.2 Send Message to Mailbox

```
POST /v1/mailboxes/{mailbox_id}/messages
No authentication required (anyone can send to a mailbox if they know the ID)

Body:
{
  "blob": "<base64 encrypted message blob>"
}

Response: 202 Accepted
{
  "sequence": <assigned sequence number>
}
```

**Note:** Sending is unauthenticated — knowing the mailbox ID is sufficient. This is intentional: the sender should not need to prove identity to the Relay Server.

**Size and backlog caps (abuse protection).** A single message blob is capped
at **256 KiB** of decoded bytes (default); larger payloads are rejected with
`413 Request Entity Too Large`. Images are not sent inline — they go through
the media endpoint, which has its own (larger) cap — so a text message never
needs to be large. Each mailbox also holds at most **1000** live (unexpired)
messages (default); once full, further sends are rejected with `429 Too Many
Requests` until the recipient acknowledges (and the server deletes) some
backlog or messages expire. Together these stop one sender filling the
relay's storage. See §12.

### 5.3 Fetch Messages (REST polling)

```
GET /v1/mailboxes/{mailbox_id}/messages?after_sequence={n}
Authorization: Bearer <mailbox_bearer_token>

Response: 200 OK
{
  "messages": [
    {
      "sequence": <uint64>,
      "blob": "<base64>",
      "expires": <unix_timestamp>
    },
    ...
  ]
}
```

### 5.4 Acknowledge Delivery

```
POST /v1/mailboxes/{mailbox_id}/ack
Authorization: Bearer <mailbox_bearer_token>

Body:
{
  "through_sequence": <uint64>  // All messages up to and including this sequence
}

Response: 200 OK
```

Server deletes the acknowledged messages. **The per-mailbox sequence
counter is monotonic forever and is NOT reset by acknowledgement** — if a
mailbox has been assigned sequences 1-100 and the client acks all of them,
the next message receives sequence 101 (not 1). This is a stronger guarantee
than a typical message-queue ack and is required so out-of-order or
late-arriving messages cannot be confused with fresh ones (ADR 007).

### 5.5 WebSocket Connection

```
WebSocket: wss://<relayserver>/v1/mailboxes/{mailbox_id}/ws
Authentication: Bearer token sent as first message after connection

Server pushes new messages as they arrive:
{
  "type": "message",
  "sequence": <uint64>,
  "blob": "<base64>"
}

Client sends acknowledgments:
{
  "type": "ack",
  "through_sequence": <uint64>
}
```

**Connection caps (abuse protection).** To bound memory under connection-flood
abuse, the server limits concurrent WebSocket connections to **10 per client
IP** (across all mailboxes) and **5 subscribers per mailbox** (defaults). A
connection beyond either cap is accepted and then immediately closed with code
`1008` (policy violation). A normal client holds a single connection per open
conversation and never approaches these limits. See §12.

### 5.6 Proof of Work (Mailbox Creation)

```
Algorithm: Hashcash-style
Input:     utf8(mailbox_id) || utf8(decimal_ascii_nonce)
Digest:    SHA-256(input)            -- 32 bytes
Solved:    leading_zero_bits(digest) >= N
N:         server-configured difficulty (advertised at /v1/pow-params)
Client iterates nonce = 0, 1, 2, ... encoded as decimal ASCII, until solved.
```

**Input encoding (normative).** `mailbox_id` is its UTF-8 byte
representation; `nonce` is the unsigned-integer nonce rendered as decimal
ASCII (e.g., `"42"`). The two byte strings are concatenated with no
separator. `leading_zero_bits` counts zero bits MSB-first across the digest.

### 5.7 Proof-of-Work Parameters

```
GET /v1/pow-params
No authentication

Response: 200 OK
{
  "difficulty_bits": <int>,
  "algorithm": "sha256-leading-zero-bits",
  "input": "utf8(mailbox_id) || utf8(decimal_nonce)",
  "mailbox_id_min_length": <int>
}
```

Public endpoint. Returns the current PoW parameters so clients can mine a
solution before calling §5.1. The server may change `difficulty_bits` at
any time; clients should refresh the parameters on each mailbox creation
attempt rather than caching.

## 6. X3DH Key Agreement

Implementation follows the X3DH specification: https://signal.org/docs/specifications/x3dh/

### 6.1 Pre-Key Bundle (stored on Key Server)

- Identity key IK (Ed25519 public key, converted to X25519 for DH)
- Signed pre-key SPK (X25519 public key, signed by IK)
- One-time pre-key OPK (X25519 public key, optional)

### 6.2 Initial Message (Alice → Bob)

Alice fetches Bob's pre-key bundle from Key Server, then:

1. Verify SPK signature using Bob's IK
2. Generate ephemeral key pair EK
3. Compute DH values:
   - DH1 = DH(Alice_IK, Bob_SPK)
   - DH2 = DH(Alice_EK, Bob_IK)
   - DH3 = DH(Alice_EK, Bob_SPK)
   - DH4 = DH(Alice_EK, Bob_OPK) [if OPK available]
4. Derive SK via HKDF-SHA-256 (X3DH §2.2):

   ```
   F    = 32 bytes of 0xFF                       (X25519 curve discriminator)
   KM   = F || DH1 || DH2 || DH3 [|| DH4]
   salt = 32 zero bytes                          (HashLen-zero salt per X3DH §2.2)
   info = b"khord-x3dh-v1"                       (UTF-8 bytes)
   SK   = HKDF-Extract-then-Expand(salt, KM, info, 32)
   ```

5. Build associated data: `AD = Encode(IK_A) || Encode(IK_B)` where
   `Encode(IK)` is the **raw 32-byte Ed25519 public key bytes** (Khord-
   specific resolution of X3DH §3.3's encoding choice — both endpoints
   are Ed25519/X25519-only, so no curve-type prefix is required).
6. Initialize the Double Ratchet with SK.
7. Encrypt the initial message with the Double Ratchet (the AEAD's
   associated data is `AD || header_bytes`, see §7.2).
8. Send to Bob's relay mailbox:
   - Alice's identity key (Ed25519 public, 32 B)
   - Alice's ephemeral public key (X25519 public, 32 B)
   - Bob's SPK key_id used
   - Bob's OPK key_id used (if any)
   - Ratchet header + ciphertext

### 6.3 Receiving Initial Message (Bob)

Bob receives the blob from his relay mailbox, then:

1. Look up own SPK and OPK by key_id
2. Compute the same DH values using own private keys
3. Derive SK
4. Initialize Double Ratchet with SK
5. Decrypt message

## 7. Double Ratchet

Implementation follows the Double Ratchet specification: https://signal.org/docs/specifications/doubleratchet/

### 7.1 Ratchet State

Each session (per contact) maintains:
- Root key (RK)
- Sending chain key (CKs) and message number (Ns)
- Receiving chain key (CKr) and message number (Nr)
- Sending ratchet key pair (DHs)
- Receiving ratchet public key (DHr)
- Skipped message keys (for out-of-order delivery)

**Skipped-key bounds (recommended for interoperability).**
Implementations should cap the size of the skipped-message-key store to
prevent a malicious sender from forcing unbounded memory use:

| Bound | Value | Meaning |
|---|---|---|
| `MAX_SKIP_PER_CHAIN` | 1000 | Max messages skipped in a single decrypt call |
| `MAX_SKIP_TOTAL` | 2000 | Max retained skipped-message-key entries across all chains |

Receivers must abort the decrypt with a recoverable error on overflow.
Khord clients enforce these exact values; independent implementations are
encouraged to match for cross-implementation compatibility.

### 7.2 Message Header (inside encrypted blob)

The header is encoded as canonical JSON with **locked field order** and
no whitespace, no trailing comma, no optional fields:

```
{"dh_public_key":"<base64 current ratchet public key>","previous_chain_length":<uint32>,"message_number":<uint32>}
```

Field order must be exactly `dh_public_key`, `previous_chain_length`,
`message_number`. The byte representation of the header is part of the
AEAD's associated data, so any whitespace or field-order variance
between sender and receiver causes the decrypt to fail even when keys
match.

### 7.3 KDFs

```
KDF_RK(rk, dh_out)  → (rk', ck)         (32 + 32 bytes)
                     = HKDF-SHA-256(salt = rk, IKM = dh_out,
                                     info = b"khord-rr-v1", L = 64)
                     split into rk' || ck (32 || 32)

KDF_CK(ck)          → (ck', mk)         (32 + 32 bytes)
                     mk  = HMAC-SHA-256(ck, b"\x01")
                     ck' = HMAC-SHA-256(ck, b"\x02")
```

### 7.4 Encryption

```
1. If CKs is null, this side cannot send yet — the next received message
   will trigger a DH ratchet step that establishes CKs.
2. (CKs, mk) = KDF_CK(CKs)
3. Build header(DHs.public, PN, Ns) and serialise to canonical JSON bytes.
4. Increment Ns.
5. Encrypt plaintext with crypto_aead_xchacha20poly1305_ietf_encrypt:
     nonce = random(24)
     associated_data = AD || header_bytes
     payload = nonce || aead_ciphertext
6. Wipe mk from memory after use.
```

### 7.5 Decryption

Decryption MUST be implemented copy-on-write: state mutations are committed
only when AEAD verification succeeds. A decrypt failure (tampered
ciphertext, wrong key, AD mismatch) must leave the receiver's state
unchanged so that a subsequent legitimate frame still decrypts.

```
1. Snapshot the ratchet state.
2. If (header.dh_public_key, header.message_number) is in the skipped
   message-key store: pop the cached MK and decrypt with it. Return.
3. If header.dh_public_key differs from DHr (or DHr is null):
     a. SkipMessageKeys(state, header.previous_chain_length)
        — caches every MK in the still-unfinished old receiving chain,
          subject to the §7.1 caps.
     b. Perform a DH ratchet step (advance RK, derive new CKr, then
        rotate DHs and derive new CKs).
4. SkipMessageKeys(state, header.message_number) for any gap in the
   current chain.
5. (CKr, mk) = KDF_CK(CKr); increment Nr.
6. associated_data = AD || header_bytes
7. plaintext = crypto_aead_xchacha20poly1305_ietf_decrypt(...)
   — if this raises, restore from the snapshot and propagate the error.
8. Wipe mk.
```

## 8. Encrypted Payload Format

Inside the Double Ratchet encrypted envelope, the plaintext message uses this format:

```json
{
  "version": 1,
  "type": "text",
  "timestamp": "<ISO 8601, client-generated>",
  "body": "<message text>",
  "reply_info": {
    "mailbox":      "<sender's inbound mailbox ID for replies>",
    "relay_server": "<sender's relay-server base URL>",
    "key_server":   "<sender's key-server base URL>",
    "fingerprint":  "<sender's identity fingerprint, hex sha256(ik)>",
    "display_name": "<sender's chosen display name (or 'Anonymous')>"
  }
}
```

**Type field is mandatory** for forward compatibility. Unknown types must be handled gracefully by the client (display "unsupported message type" rather than crash).

**`reply_info` is REQUIRED on the X3DH initial** (`x3dh_initial` envelope, §10.2). Without it the recipient cannot auto-create the sender's contact entry and falls back to the legacy "received from unknown fingerprint" error — which forces a bidirectional QR scan that we explicitly want to avoid. See §10.2.

`reply_info` SHOULD also be included on subsequent `ratchet` messages so display-name renames and relay-server migrations propagate without an out-of-band channel. Khord's reference implementation includes it on every outbound message; the cost (~150 bytes) is dwarfed by the AEAD framing overhead, and recipients no-op when nothing changed.

**Privacy boundary.** `reply_info` lives **inside the AEAD ciphertext**, not in the outer wire envelope (§10). The relay server therefore never sees mailbox addresses, server URLs, fingerprints, or display names — they are end-to-end encrypted alongside the message body. Only the intended recipient learns them, after decryption.

Future types (not in PoC):
- `media_reference` — symmetric key + download URL for encrypted media
- `key_change` — notification of identity key change
- `disappear_timer` — timer configuration for disappearing messages

## 9. QR Code Payload

The QR code exchanged between contacts contains:

```json
{
  "version": 1,
  "identity_key": "<base64 Ed25519 public key>",
  "fingerprint": "<hex-encoded SHA-256 fingerprint>",
  "key_server": "<Key Server base URL>",
  "relay_server": "<Relay Server base URL>",
  "relay_mailbox": "<mailbox ID on Relay Server for receiving messages from this contact>"
}
```

The `relay_server` URL is required. Without it, the protocol would be
hardcoded to a single relay-server operator, contradicting the split-trust
model (ADR 002) — different users may run their clients against different
relay-server instances. The contact's own preferred relay server is the
authoritative location for `relay_mailbox`.

**Security property:** Scanning the QR code provides the identity key
directly. When the client later fetches the pre-key bundle from the Key
Server, it verifies the identity key in the bundle matches the one from
the QR code. This prevents Key Server key substitution attacks.

## 10. Khord Wire Envelope

The bytes a Khord client places in the relay server's `blob` field are not
opaque ciphertext alone — they are a structured envelope that carries the
X3DH metadata (on the very first message in a session) plus the Double
Ratchet header and AEAD ciphertext. The envelope itself is JSON; the
encrypted payload sits inside the AEAD ciphertext.

### 10.1 Encoding

```
envelope_bytes = canonical_json(envelope)
relay_blob     = base64(envelope_bytes)
```

The receiver does the inverse: base64-decode the relay blob to get the
canonical JSON bytes; parse those as the envelope; then process by `type`.

JSON encoding rules for cross-implementation compatibility:
- field names use `snake_case`
- the `type` field is the discriminator (matching kotlinx.serialization's
  `classDiscriminator` default)
- absent / `null` optional fields MAY be omitted on the wire
- unknown fields MUST be tolerated by receivers

### 10.2 `x3dh_initial` — first message in a new session

```json
{
  "version": 1,
  "type": "x3dh_initial",
  "ik_a": "<base64 Alice's Ed25519 identity public, 32 B>",
  "ek_a": "<base64 Alice's ephemeral X25519 public, 32 B>",
  "spk_id": <int>,
  "opk_id": <int|null>,
  "header": "<base64 of canonical Double Ratchet header bytes (§7.2)>",
  "ciphertext": "<base64 of (nonce || aead_ct) — what RatchetAead.encrypt returns>"
}
```

This shape is sent ONCE per contact, when Alice initiates the X3DH
agreement. The receiver runs `X3dh.respond` with their stored SPK + OPK
secrets to derive SK, initialises the ratchet, and decrypts the inner
payload (§8). The OPK private key MUST be deleted on successful X3DH
(X3DH §3.4 — forward-secrecy hard requirement).

**Unidirectional contact flow.** The decrypted inner payload of an
`x3dh_initial` MUST carry `reply_info` (§8). The receiver uses it to
auto-create a contact entry for the sender — Alice's mailbox + relay
server + key server + fingerprint + display name all come from there.
This means **one QR scan is enough to start a conversation**: only
Alice scans Bob's QR; Bob receives Alice's first message and learns
everything he needs to reply, without scanning Alice's QR. An `x3dh_initial`
without `reply_info` is treated as a legacy / older-client message and
the receiver returns a wire-format error to force the caller to fall back
to a bidirectional QR exchange.

### 10.3 `ratchet` — every subsequent message

```json
{
  "version": 1,
  "type": "ratchet",
  "header": "<base64>",
  "ciphertext": "<base64>"
}
```

After the first X3DH bootstrap, every additional message uses this
shape — there is no need to re-send the X3DH metadata. The receiver
identifies the contact via the **mailbox ID** the blob arrived on
(per-contact directional mailboxes, ADR 005); the wire envelope carries
no sender identity.

### 10.4 Forward compatibility

Receivers MUST accept envelopes with an unknown `type` and surface a
"unsupported message type" condition rather than failing the entire
session. The `version` field allows breaking-change negotiation in a
future revision.

## 11. Security Considerations

### 10.1 Known Limitations (PoC)

- No traffic analysis resistance — timing, volume, and frequency of messages are visible to network observers and the Relay Server
- Key storage depends on Android Keystore — if the device is rooted or compromised, keys may be extractable
- The crypto implementation has not been professionally audited
- No reproducible builds — users cannot verify deployed binaries match source code

### 10.2 Trust Model

- Neither server is trusted with message content (E2E encryption)
- Neither server alone can determine who communicates with whom (split-trust)
- The Key Server is trusted to serve correct pre-key bundles — but QR code verification detects substitution for known contacts
- The Relay Server is trusted to deliver messages — but cannot read or modify them (encryption + authentication)
- The client device is trusted (secure enclave protects keys, but device compromise is out of scope)

### 10.3 Future Hardening

- Professional security audit of crypto implementation (mandatory before production)
- Reproducible builds (mandatory before production)
- Traffic analysis mitigations (Level 3)
- Tor integration for connection-level privacy (Level 4b)
- Hardware security key support for identity key storage

## 12. Abuse Protections (Rate Limits & Storage Caps)

Both servers apply spam/DoS protections in addition to the proof-of-work in
§5.6. All thresholds below are **server-configured defaults** and may change;
they are deliberately generous so a normal client never encounters them.
Clients SHOULD treat `429 Too Many Requests` and `413 Request Entity Too
Large` as ordinary, retryable conditions and honour any `Retry-After` header
on a 429.

**Per-IP rate limits.** Limiting is per client IP, fixed-window per minute:

| Endpoint                                   | Default limit | On exceed |
|--------------------------------------------|---------------|-----------|
| All endpoints (blanket ceiling)            | 100 / min     | `429`     |
| Key Server `POST /v1/auth/challenge`, `/verify` | 10 / min | `429`     |
| Key Server `GET /v1/keys/{fp}/bundle` (per IP)  | 30 / min | `429`     |
| Key Server `GET /v1/keys/{fp}/bundle` (per fingerprint) | 5 / min | `429` |
| Relay Server `POST /v1/mailboxes`          | 10 / min      | `429`     |

The bundle-fetch endpoint carries two independent limits (per-IP and
per-fingerprint); whichever is reached first applies. Rationale for the
per-fingerprint limit is in §4.2.

**Relay storage caps.**

| Cap                                 | Default  | On exceed |
|-------------------------------------|----------|-----------|
| Single message blob size (decoded)  | 256 KiB  | `413`     |
| Live messages per mailbox           | 1000     | `429`     |
| Single media blob size              | 10 MiB   | `413`     |

Messages and media also carry a 7-day TTL and are deleted on acknowledgement
/ first read; the caps above bound storage between those events.

**WebSocket connection caps.** Concurrent WS connections are limited to **10
per client IP** and **5 subscribers per mailbox** (defaults). A connection
beyond either cap is closed with code `1008` immediately after the upgrade.

**Request body size.** Every request body is capped at **12 MiB** at the
transport layer (covering the largest media upload plus framing); a larger
body is rejected with `413` before any handler runs.

**PoW difficulty.** The mailbox-creation / media-upload proof-of-work
difficulty (§5.6) is server-configured and advertised at `GET /v1/pow-params`
(default 16 leading zero bits). Clients MUST read the advertised difficulty
per attempt rather than assuming a fixed value.

**Deployment note.** Per-IP limits and WS per-IP caps key on the client
address as seen by the application. Behind a reverse proxy, the proxy must
forward the real client address (e.g. uvicorn `--proxy-headers` with a
trusted `--forwarded-allow-ips`), otherwise all traffic is attributed to the
proxy IP. Limiter and WS-cap state is in-memory and per-process, consistent
with the single-worker deployment the in-process notifier already requires.
