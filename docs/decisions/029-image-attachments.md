# ADR 029: Image Attachments via a Separate Media Endpoint

**Status:** Accepted
**Date:** 2026-05-29

## Context

Users want to send photos, not just text. Khord's existing message
path is the Double Ratchet over the relay's mailbox endpoints, which
is tuned for small payloads: every message is base64-framed inside a
JSON envelope, buffered, and fanned out per-recipient. A multi-megabyte
image does not belong on that channel — it would bloat the mailbox,
inflate 33% under base64, and (for groups) be re-encrypted and
re-uploaded once per member.

Constraints unique to Khord:

1. **The relay must never see plaintext.** It stores opaque ciphertext
   for messages; media must be no different — no plaintext image, no
   filename, no content type, no EXIF, no sender/recipient binding.
2. **The key must never reach the server.** Decryption material travels
   only through the E2E channel.
3. **No durable server storage.** The relay discards messages on ack;
   media should be equally transient (one-time read + TTL).
4. **Pairwise channels only.** Groups have no server-side concept; a
   group image must fan its *key* out per-member, even though the blob
   is uploaded once.
5. **Mobile bandwidth.** Don't auto-download full images; show a small
   inline preview and fetch the full image on demand.

## Decision

Add a **separate media endpoint** on the relay (`POST /v1/media`,
`GET /v1/media/{id}`) that stores opaque encrypted blobs, and send the
decryption material + a tiny inline thumbnail inside the normal E2E
message payload.

### Separate endpoint, not inline

The image ciphertext is uploaded out-of-band to the media endpoint and
referenced from the message by a random `media_id`. This decouples
image size from the message channel, lets a group image be stored once
and fetched N times, and keeps the hot message path small. The
alternative — inlining the image in the ratchet payload — was rejected:
it couples blob size to the ratchet, multiplies storage by group size,
and stresses the mailbox.

### One-time symmetric key per image (not the ratchet)

Each image gets a fresh 32-byte XChaCha20-Poly1305 key + 24-byte nonce
(`MediaCrypto`). The full image and the thumbnail are encrypted under
that one key with two distinct nonces — the thumbnail nonce is *derived*
from the image nonce (low bit of the last byte flipped) so it's
reproducible from `media_nonce` alone, never reused, and needs no extra
wire field. We deliberately do NOT run the image through the Double
Ratchet: the ratchet protects the ordered message channel, and pushing
a large blob through it would advance ratchet state for a payload that
isn't really a "message". The one-time key rides inside the ratchet-
protected payload instead, so it still gets full E2E protection without
polluting the message channel.

### Thumbnail inline in the payload

A ≤64px JPEG (quality 60, ~2–5 KB encrypted) is encrypted with the same
key and base64'd into the payload, so the recipient renders a preview
instantly without a network round-trip. The full image is fetched on
tap. The thumbnail is encrypted too — the relay never sees even a
preview (it isn't on the relay at all; it's in the E2E payload).

### Payload fields

Optional, nullable additions to `InnerPayload` (snake_case on wire):
`media_id`, `media_key` (base64 32 bytes), `media_nonce` (base64 24
bytes), `media_relay` (the relay base URL the blob lives on, for cross-
relay/federation fetch), `thumbnail` (base64 encrypted thumbnail).
`body` doubles as the optional caption (may be empty for image-only).
Old clients ignore unknown fields (`ignoreUnknownKeys`); a payload
without these decodes as a plain text message.

### EXIF stripping (privacy requirement)

Images are decoded to a `Bitmap` and re-encoded as JPEG before
encryption (`ImageProcessor`). A Bitmap carries pixels only, so the
re-encoded JPEG has none of the original's GPS / camera-model /
timestamp / embedded-thumbnail EXIF. The original's *orientation* tag
is read first and baked into the pixels so the stripped image still
displays upright — orientation is the one tag whose loss is visible.
Full images are downscaled to ≤2048px on the longest side (quality 85)
for bandwidth and as a second guard against high-res metadata leakage.

### One-time fetch + TTL

`GET /v1/media/{id}` deletes the blob on first successful read (like a
consumed message) and the relay reaps un-fetched blobs after a TTL
(default = the 7-day message TTL). Because the read is one-time, the
client caches the decrypted full image on the **filesystem**
(app-private storage, `MediaCache`), referenced by path
(`media_cached_path`) — never in the SQLite DB. The sender caches its
own plaintext at send time, since the one-time read won't let it
re-fetch its own upload.

### Proof of work

Upload reuses the relay's Hashcash PoW, but the puzzle subject is
`sha256_hex(encrypted_blob)` rather than a mailbox id, so a mined nonce
authorizes exactly one blob and can't be replayed for other uploads.

### Groups

The blob is uploaded once; `media_id` + key + thumbnail are fanned out
to each member through their pairwise channel. The relay sees one upload
and N downloads it can't correlate (different mailboxes, different
times). Each member fetches and decrypts independently and caches their
own decrypted copy.

## Privacy properties

- The relay stores only `random-id → encrypted-bytes` + timestamps: no
  filename, content type, EXIF, sender, recipient, or key.
- The key travels only through the E2E channel; the relay can't decrypt
  the blob or the thumbnail.
- EXIF (GPS, camera model, timestamps) is stripped before encryption.
- One-time fetch + TTL: blobs don't linger server-side.

## Consequences

- **New relay table + endpoints** (server side, shipped separately).
- **Schema migration 11 → 12**: six nullable media columns on both
  `message` and `group_message`. Invisible to the FTS5 indexes (those
  cover `body` only).
- **No video** in this iteration — images only; video is a future
  extension (larger blobs, transcoding, streaming concerns).
- **Full images live on the filesystem, not the DB** — keeps the
  encrypted DB small; the cache is the only persistent copy after the
  relay's one-time read.
- The shared module gains `MediaCrypto`; the Android module gains
  `ImageProcessor` (the only place with an image codec) + `MediaCache`
  + the attachment UI.
