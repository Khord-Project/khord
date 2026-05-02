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
| Symmetric encryption | XSalsa20-Poly1305 | crypto_secretbox / crypto_secretbox_open |
| AEAD (if needed) | XChaCha20-Poly1305 | crypto_aead_xchacha20poly1305_ietf_encrypt |
| Key derivation | HKDF-SHA-256 | crypto_kdf_* or manual HMAC-based HKDF |
| Password-based KDF | Argon2id | crypto_pwhash |
| Random bytes | OS CSPRNG | randombytes_buf |
| Hashing | SHA-256 | crypto_hash_sha256 |

### 3.1 Identity Key Derivation from Seed Phrase

```
seed_phrase → UTF-8 bytes
salt = "khord-identity-v1" (fixed, documented)
raw_seed = Argon2id(seed_phrase_bytes, salt,
                     opslimit=MODERATE, memlimit=MODERATE)
identity_signing_key = Ed25519 keypair from raw_seed (crypto_sign_seed_keypair)
identity_agreement_key = X25519 keypair converted from Ed25519 keypair
fingerprint = SHA-256(identity_public_signing_key)[0:32] (hex-encoded)
```

**Note:** The exact Argon2id parameters (opslimit, memlimit) must be documented and fixed across all client implementations. Any change produces a different key from the same seed.

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
  "signature": "<base64 Ed25519 signature over challenge>"
}
Response: { "token": "<short-lived session token>" }

Step 3: Client uses session token for subsequent requests
Authorization: Bearer <session_token>
```

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
  "mailbox_id": "<client-generated random ID>",
  "proof_of_work": "<proof of work solution>"
}

Response: 201 Created
{
  "mailbox_id": "<mailbox_id>",
  "bearer_token": "<random access token>"
}
```

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

Server deletes acknowledged messages and resets sequence tracking.

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

### 5.6 Proof of Work (Mailbox Creation)

```
Algorithm: Hashcash-style
Input: mailbox_id + nonce
Requirement: SHA-256(mailbox_id || nonce) has N leading zero bits
N is configurable server-side (difficulty parameter)
Client iterates nonce values until the requirement is met
```

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
4. SK = HKDF(DH1 || DH2 || DH3 [|| DH4], info="khord-x3dh-v1")
5. Initialize Double Ratchet with SK
6. Encrypt initial message with Double Ratchet
7. Send to Bob's relay mailbox:
   - Alice's identity key
   - Alice's ephemeral public key
   - Bob's SPK key_id used
   - Bob's OPK key_id used (if any)
   - Encrypted message

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

### 7.2 Message Header (inside encrypted blob)

```
{
  "dh_public_key": "<base64 current ratchet public key>",
  "previous_chain_length": <uint32>,
  "message_number": <uint32>
}
```

### 7.3 Encryption

```
1. If DHr has changed, perform DH ratchet step (advance root key, create new chain)
2. Derive message key from sending chain: MK = KDF(CKs)
3. Advance sending chain: CKs = KDF(CKs)
4. Encrypt plaintext with MK using crypto_secretbox
5. Attach header (current DH public key, chain length, message number)
```

### 7.4 Decryption

```
1. Check if message key is in skipped keys store
2. If header DH public key differs from stored DHr, perform DH ratchet
3. Derive message key from receiving chain
4. Decrypt and verify
```

## 8. Encrypted Payload Format

Inside the Double Ratchet encrypted envelope, the plaintext message uses this format:

```json
{
  "version": 1,
  "type": "text",
  "timestamp": "<ISO 8601, client-generated>",
  "body": "<message text>"
}
```

**Type field is mandatory** for forward compatibility. Unknown types must be handled gracefully by the client (display "unsupported message type" rather than crash).

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
  "relay_mailbox": "<mailbox ID on Relay Server for receiving messages from this contact>"
}
```

**Security property:** Scanning the QR code provides the identity key directly. When the client later fetches the pre-key bundle from the Key Server, it verifies the identity key in the bundle matches the one from the QR code. This prevents Key Server key substitution attacks.

## 10. Security Considerations

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
