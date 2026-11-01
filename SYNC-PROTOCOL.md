# Edendale Sync Protocol, version 1

The wire specification for 27.1 cross-device sync: the relay's HTTP API,
device authentication, the encrypted envelopes, pairing, key epochs, the
recovery key, limits, and test vectors. Every platform branch implements it
natively and copies the vectors in Appendix A into its own tests; the `sync`
branch implements the relay side. No code is shared between branches
(AGENTS.md constraints 1, 2, and 7).

- **Location:** `main`, documentation only.
- **Status:** Draft for review, 2026-11-01. Nothing here is implemented, and
  the decisions in §15 are open. The text uses the recommended option for each
  open decision and marks it with its number, for example **[D3]**.
- **Basis:** [FEATURES-27.1.md](FEATURES-27.1.md) §5 (relay sync and merge) and
  §6 (pairing, keys, and the security review R1–R17 and S1–S9). Where this
  document and FEATURES-27.1.md differ, §15 lists the difference as a decision.
- **Relay today:** the `SyncSpace` Durable Object on `sync` (an append-only
  log, 64 KiB batches, a 10 MiB space, 12-month retention). §13 says what stays
  and what changes.

> The relay is untrusted for confidentiality and integrity (FEATURES §6). It
> verifies signatures, enforces limits, and stores what devices give it. It
> never holds a key that decrypts anything. Space keys go only to devices in
> the encrypted member list, in envelopes authenticated by the previous
> epoch's key, so the relay can neither add a device of its own (S3) nor
> substitute a key.

---

## 1. Conventions

### 1.1 Terms

| Term | Meaning |
|---|---|
| Space | A sync space: the set of devices that share watch state (FEATURES §6.1) |
| Device | One installation of Edendale that holds its own keys |
| Member | A device listed as active in the space's encrypted member list (§6.5) |
| Epoch | A numbered generation of the space key, starting at 1 (FEATURES §6.3) |
| Writer | The device-local ID that stamps watch-state writes for the merge (§6.2) |
| Envelope | A binary encrypted object: a batch or one part of a snapshot (§5) |
| Key envelope | An HPKE ciphertext that carries one epoch's space key to one recipient (§8) |
| Mailbox | A short-lived relay object that carries one pairing exchange (§9) |
| Origin | The relay's origin string, for example `https://sync.example` (§3.1) |

### 1.2 Notation

- `‖` is concatenation. `u8`, `u16`, `u32`, and `u64` are unsigned big-endian
  integers of 1, 2, 4, and 8 bytes.
- `label(x)` is the ASCII string `edendale-sync/v1/` followed by `x`. For
  example `label("batch")` is `edendale-sync/v1/batch`.
- `lv_cat` and `prepend_len` are CPace's length-value encoding with LEB128
  lengths (draft-irtf-cfrg-cpace-21 §6.3); they are used only inside CPace.
- `HKDF(ikm, salt, info, n)` is HKDF-SHA256 (RFC 5869), extract then expand,
  with output length `n`.
- `random(n)` is `n` bytes from the platform's cryptographically secure
  generator.
- Binary values in JSON and in URL paths are base64url without padding
  (RFC 4648 §5) **[D1]**. Decoders reject padding, characters outside the
  alphabet, and wrong lengths.

### 1.3 Primitives

| Use | Primitive | Reference |
|---|---|---|
| Device and recovery signing | Ed25519 | RFC 8032 |
| Device and recovery encryption keys | X25519 | RFC 7748 |
| Batches, snapshots, pairing channel | XChaCha20-Poly1305, random 192-bit nonce (R7) | draft-irtf-cfrg-xchacha-03 |
| Key envelopes, QR welcome | HPKE mode_psk: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305 **[D3]** | RFC 9180 |
| Subkeys and derivations | HKDF-SHA256 | RFC 5869 |
| Pairing by code | CPace, suite CPACE-RISTR255-SHA512, explicit key confirmation with HMAC-SHA-512 **[D9]** | draft-irtf-cfrg-cpace-21, RFC 9496 |
| Recovery key text | Crockford base32 with a 12-bit check **[D5]** | crockford.com/base32.html |

Implementations use audited libraries (R16): libsodium on every platform, or
CryptoKit where it has the primitive.

| Primitive | libsodium | CryptoKit |
|---|---|---|
| Ed25519 | `crypto_sign_seed_keypair`, `crypto_sign_detached`, `crypto_sign_verify_detached` | `Curve25519.Signing` |
| X25519 | `crypto_scalarmult`, `crypto_scalarmult_base` | `Curve25519.KeyAgreement` |
| HKDF-SHA256 | `crypto_kdf_hkdf_sha256_extract`, `_expand` (libsodium 1.0.19 or later) | `HKDF<SHA256>` |
| HMAC-SHA-256, HMAC-SHA-512 | `crypto_auth_hmacsha256_*`, `crypto_auth_hmacsha512_*` | `HMAC` |
| XChaCha20-Poly1305 | `crypto_aead_xchacha20poly1305_ietf_*` | Not available; use libsodium |
| HPKE | Built from the rows above, following RFC 9180 §4–§5 | `HPKE` (iOS, tvOS 17; macOS 14; visionOS 1) |
| ristretto255 | `crypto_core_ristretto255_from_hash`, `crypto_scalarmult_ristretto255`, `crypto_core_ristretto255_scalar_random` | Not available; use libsodium |

`crypto_scalarmult_ristretto255` clears the scalar's top bit and fails when
the result is the identity. Scalars from `crypto_core_ristretto255_scalar_random`
are already reduced, and the failure is CPace's abort (§9.2).

The relay needs only Ed25519 verification and SHA-256, both in the Workers
runtime's Web Crypto API. It implements no encryption.

### 1.4 Identifiers and keys

| Name | Size | Made by | Where it goes |
|---|---|---|---|
| `space_id` | 16 random bytes | Creating device | Relay (path), HKDF salt, AAD |
| `device_id` | 16 random bytes, new for every membership **[D15]** | Each device | Relay, AAD, member list |
| `writer_id` | 16 random bytes, once per installation | Each device | Inside ciphertext only (§6.2) |
| `batch_id`, `snapshot_id` | 16 random bytes each | Uploading device | Envelope header |
| `mailbox_id` | 16 random bytes | Relay | Mailbox, QR code |
| Device signing key | Ed25519 | Each device | Public half to the relay and member list |
| Device encryption key | X25519 | Each device | Public half in the member list and pairing messages; the relay never stores it as a device key |
| Space key `K_e` | 32 random bytes per epoch `e` | The device that starts the epoch | Key envelopes and the pairing welcome |
| Recovery secret `r` | 16 random bytes | Creating or replacing device | Shown once to the user (§10) |

Private keys, space keys, the recovery secret, and session tokens never leave
the device except as this document says, and never enter backups or device
transfer (FEATURES §5.5, §6.2). `device_id`, the device keys, and `writer_id`
are never restored onto another installation.

---

## 2. Overview

```
 new device ──pairing mailbox (CPace or QR)──▶ member device
     │                                          │
     │   challenge → Ed25519 signature → 15-minute session token
     ▼                                          ▼
 ┌──────────────────────── relay: one Durable Object per space ──────────────┐
 │ devices (id, signing key)   current epoch   key envelopes (per device)    │
 │ log: seq → envelope (batch) snapshot: parts of one envelope set (≤ 8 MiB) │
 └───────────────────────────────────────────────────────────────────────────┘
```

1. A device creates a space (§7.2) or joins one through a member (§9).
2. It signs a relay challenge to get a short session token (§4).
3. It appends encrypted batches of writes and reads everyone's batches after
   its cursor (§7.4), merging them with FEATURES §5.3's rules (§6.3).
4. From time to time a device writes an encrypted snapshot, and the relay drops
   the batches it covers (§7.5).
5. Removing a device starts a new epoch: a new space key, sent to every
   remaining member and to the recovery key in key envelopes (§8).

---

## 3. HTTP conventions

### 3.1 Base and versions

- All routes are under `https://<relay host>/v1/`. The relay URL is public
  and embedded in the apps; its domain is part of the hosting decision
  (FEATURES §10 item 4) **[D17]**. The vectors use `https://sync.example`.
- The **origin** string is the scheme, host, and port if not the default,
  lowercase and without a trailing slash, for example `https://sync.example`.
  Devices sign it (§4) so that a session signature made for one relay cannot be
  used on another.
- Three version markers move independently: the `/v1/` path, the envelope
  `version` byte (§5.2), and the plaintext `v` field (§6.1). Version 1 is
  described here.

### 3.2 Requests and responses

- Bodies are UTF-8 JSON with `content-type: application/json; charset=utf-8`
  **[D1]**. A body with another type gets `415 unsupported_media_type`.
- Every response has `cache-control: no-store` and
  `x-content-type-options: nosniff`, as the relay already sends.
- Integers are JSON numbers within ±(2⁵³ − 1). Times are UTC milliseconds.
- Both sides ignore JSON fields they do not know.
- Authenticated routes take `authorization: Bearer <token>` (§4.3).
- The relay sends no CORS headers and sets no cookies, so a web page can
  neither send it JSON nor read its responses. No Edendale web client syncs
  (constraint 8).
- The relay logs no request bodies and stores no network addresses
  (FEATURES §5.6). It uses the address only in flight as a rate-limit key
  (§12.2).

### 3.3 Errors

An error response has a JSON body `{"error": "<code>"}`, sometimes with extra
fields named below. `429` and `503` also carry `retry-after` in seconds.

| Status | `error` | When | Client action |
|---|---|---|---|
| 400 | `bad_request` | Malformed JSON, a missing field, a bad encoding or length | Bug: do not retry |
| 400 | `bad_cursor` | `after` is not an integer ≥ 0 (the relay's existing code) | Bug |
| 400 | `bad_envelope` | Envelope header invalid for this route (§5.3) | Bug |
| 400 | `bad_size` | Envelope length is not an allowed bucket (§5.4) | Bug |
| 401 | `unauthenticated` | Token missing, unknown, or expired | New session (§4), then retry once |
| 401 | `bad_signature` | Challenge unknown, used, or expired, or the signature does not verify | New challenge |
| 403 | `forbidden` | The session's principal cannot use this route (§4.4) | Bug |
| 403 | `device_removed` | This device was removed from the space | Stop syncing; delete the space keys; offer to join again |
| 403 | `device_mismatch` | The envelope's `device_id` is not the session's device | Bug |
| 404 | `not_found` | Unknown route | Bug |
| 404 | `space_not_found` | No such space: deleted, expired, or a wrong recovery lookup | Stop syncing; offer a new space from the local copy |
| 404 | `device_not_found` | No such device in the space | Join again |
| 404 | `snapshot_not_found` | The snapshot was replaced or its upload expired | Read again from the cursor |
| 404 | `mailbox_not_found` | No open mailbox with that number or ID | "This code has expired or is wrong" |
| 405 | `method_not_allowed` | Wrong method; `allow` header lists the right ones | Bug |
| 409 | `space_exists` | `space_id` already in use | New `space_id` |
| 409 | `recovery_in_use` | `lookup_id` already bound to another space | New recovery secret |
| 409 | `stale_epoch` | Envelope epoch is not the current epoch; body has `epoch` | Fetch key envelopes (§7.7), re-encrypt, retry |
| 409 | `epoch_conflict` | A new epoch must be current + 1; body has `epoch` | Fetch envelopes, read, retry only if still needed |
| 409 | `log_moved` | `after_seq` is not the latest sequence number; body has `latest_seq` | Read, rebuild the snapshot, retry |
| 409 | `devices_changed` | Key envelopes do not match the active devices | Read the log and device list, then retry or warn (§11.4) |
| 409 | `stale_snapshot` | `covers_seq` is not newer than the current snapshot's | Read the newer snapshot instead |
| 409 | `snapshot_incomplete` | Commit before every part was uploaded | Upload the missing parts |
| 409 | `snapshot_busy` | Another device's snapshot upload is in progress | Wait `retry-after` |
| 409 | `space_full` | The batch would take stored batches past 10 MiB | Write a snapshot (§7.5), retry |
| 409 | `device_exists` | `device_id` was already used in this space | New device ID; pairing restarts |
| 409 | `device_limit` | The space has 20 active devices | Remove one first |
| 409 | `batch_id_conflict` | Another device used this `batch_id` | New `batch_id` |
| 409 | `mailbox_claimed` | Another device has already answered this mailbox | "This code has been used"; new code |
| 409 | `mailbox_turn` | Mailbox message out of turn, or the sixth message was already sent | Bug, or wait for the other side |
| 410 | `mailbox_expired` | The mailbox's 2 minutes are over | New code |
| 413 | `too_large` | Request body over the route's limit (§12.1) | Bug |
| 415 | `unsupported_media_type` | Body is not JSON | Bug |
| 429 | `rate_limited` | A limit in §12.2 | Wait `retry-after` |
| 500 | `internal` | Relay fault | Back off (§11.5) |
| 503 | `unavailable` | Relay overloaded, or no free mailbox number | Back off, honouring `retry-after` |

---

## 4. Device authentication

There are no passwords and no long-lived tokens (FEATURES §5.2). A device
proves possession of its Ed25519 key by signing a fresh relay challenge and
gets a short session token in return.

### 4.1 Challenge

`POST /v1/spaces/{space_id}/challenges` with `{"device_id": "<16 bytes>"}`
returns `201` with:

```json
{ "challenge": "<32 bytes>", "expires_at": 1793563260000 }
```

The challenge is 32 random bytes, valid for 60 seconds and once. The relay
keeps at most 4 outstanding challenges per device and drops the oldest.

### 4.2 Signature and session

The device signs:

```
message = label("session") ‖ 0x00 ‖ u16(len(origin)) ‖ origin ‖ space_id ‖ device_id ‖ challenge
signature = Ed25519-Sign(device signing key, message)
```

`POST /v1/spaces/{space_id}/sessions` with
`{"device_id", "challenge", "signature"}` returns `201` with:

```json
{ "token": "<32 bytes>", "expires_at": 1793564100000, "epoch": 2, "latest_seq": 812, "rotation_pending": false }
```

The relay verifies the signature against the device's registered key and the
origin it is configured with, deletes the challenge, and creates the token.

### 4.3 Session tokens **[D2]**

- A token is 32 random bytes, sent as base64url in `authorization: Bearer`.
- The space's Durable Object stores only SHA-256(token), the principal, and the
  expiry. A token is valid in that one space only.
- A token lasts 15 minutes and is not refreshed: the device signs a new
  challenge. A device has at most 4 live tokens; a new one evicts the oldest.
- Removing a device deletes its tokens and challenges at once (FEATURES §6.3).
- Deleting a space deletes every token.

Because tokens are random and stored hashed per space, the relay holds no
signing secret whose theft would let anyone mint sessions (S6).

### 4.4 Principals

| Principal | Gets a session by | May use |
|---|---|---|
| Device | §4.1–§4.2 | Every `/v1/spaces/{space_id}` route |
| Recovery | §10.3, signing with the recovery key | Read its key envelope, list devices, add one device, delete the space |

A recovery session can add only one device; after that it can still read its
envelope and delete the space.

---

## 5. Encrypted envelopes

Batches and snapshot parts share one binary envelope.

### 5.1 Subkeys

For epoch `e` with space key `K_e` (FEATURES §6.2):

```
k_batch(e)     = HKDF(K_e, salt = space_id, info = label("batch")     ‖ u32(e), 32)
k_snapshot(e)  = HKDF(K_e, salt = space_id, info = label("snapshot")  ‖ u32(e), 32)
k_epoch_psk(e) = HKDF(K_e, salt = space_id, info = label("epoch-psk") ‖ u32(e), 32)
```

`k_epoch_psk(e)` authenticates the key envelopes of epoch `e + 1` (§8.2).
FEATURES §6.2 names a member-list subkey here; the member list travels inside
batches and snapshots instead (§6.5), and the third subkey serves the key
envelopes **[D13]**.

### 5.2 Layout

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `version` = `0x01` |
| 1 | 1 | `kind`: `0x01` batch, `0x02` snapshot part |
| 2 | 4 | `epoch` (u32) |
| 6 | 16 | `device_id`: the uploading device |
| 22 | 16 | `object_id`: the `batch_id`, or the `snapshot_id` |
| 38 | 8 | `covers_seq` (u64): 0 for a batch |
| 46 | 2 | `part_index` (u16): 0 for a batch |
| 48 | 2 | `part_count` (u16): 1 for a batch, 1–128 for a snapshot |
| 50 | 24 | `nonce`: `random(24)` for every envelope |
| 74 | n + 16 | XChaCha20-Poly1305 ciphertext of the padded plaintext, then the tag |

Bytes 0–49 are the **header**. The relay reads the header and never the
ciphertext.

```
aad        = space_id ‖ header                        (66 bytes)
ciphertext = XChaCha20-Poly1305-Encrypt(key, nonce, padded, aad)
envelope   = header ‖ nonce ‖ ciphertext
key        = k_batch(epoch) for a batch, k_snapshot(epoch) for a snapshot part
```

The AAD binds the space ID, epoch, uploading device ID, and batch ID that
FEATURES §6.2 and R8 require, and also the version, kind, `covers_seq`, and
part numbers, so the relay cannot pass a batch off as a snapshot part, move a
part, or misstate which batches a snapshot covers **[D14]**.

### 5.3 Checks on receipt

The relay checks, per route: `version` is 1; `kind` fits the route; the length
is an allowed bucket; `epoch` is the current epoch; `device_id` is the
session's device; and the snapshot fields match the staged snapshot (§7.5).

A device decrypts with the key for the header's epoch and drops the envelope,
with a sync warning, when:

- the AEAD check fails (tampered header, ciphertext, or tag, or another space);
- the padding is malformed (§5.4);
- `kind` or the snapshot fields do not match what it asked for; or
- the plaintext's `v` is greater than 1 (§6.1). In that case it stops syncing
  and asks the user to update, without advancing its cursor or writing
  snapshots, so it cannot drop data it does not understand.

### 5.4 Padding **[D7]**

```
padded = u32(len(plaintext)) ‖ plaintext ‖ zero bytes up to (bucket − 90)
```

The envelope's total length is exactly one bucket. 90 bytes are the header,
nonce, and tag; the length prefix takes 4 more, so a bucket holds
`bucket − 94` bytes of plaintext.

| Bucket (envelope bytes) | Largest plaintext |
|---|---|
| 1,024 | 930 |
| 2,048 | 1,954 |
| 4,096 | 4,002 |
| 8,192 | 8,098 |
| 16,384 | 16,290 |
| 32,768 | 32,674 |
| 65,536 (the relay's 64 KiB batch limit) | 65,442 |

- A batch uses the smallest bucket that fits.
- Every snapshot part is 65,536 bytes, the last one included, so the relay
  learns a snapshot's size only to the nearest 64 KiB.
- A receiver checks that the length prefix fits and that every padding byte is
  zero.
- There is no compression in version 1 **[D10]**.

### 5.5 Snapshots in parts

A snapshot's plaintext (§6.1) is cut into chunks of 65,442 bytes; the last
chunk may be shorter. Chunk `i` becomes part `i` with
`part_count = ceil(len / 65442)` (at least 1, at most 128, so at most 8 MiB of
plaintext **[D11]**). Every part has its own nonce and the same `snapshot_id`,
`epoch`, `covers_seq`, and `part_count`. The reader decrypts every part,
checks that it has parts `0 … part_count − 1` with matching headers, and
joins the chunks in order.

---

## 6. Plaintext

### 6.1 Batches and snapshots

Batches and snapshots have the same plaintext: UTF-8 JSON **[D1]**.

```json
{
  "v": 1,
  "writes": [ /* §6.2 */ ],
  "members": [ /* §6.5 */ ],
  "space": { /* §6.6 */ }
}
```

- `v` is required and is 1. `writes`, `members`, and `space` are optional in a
  batch.
- A **snapshot** is the merged state: for each `(key, reg)` the winning write
  and every held write that beats it (§6.3), every member record, and the
  latest space record.
- A device keeps every key, register, and field it does not understand, as
  received, and includes them in the snapshots it writes. A version 1
  extension adds new keys, registers, or fields, never a new meaning for an
  old one. This lets an older app write a snapshot without losing, for
  example, reviews it cannot show.

### 6.2 Writes

```json
{ "key": "movie:603", "reg": "rating", "hlc": [1793563200000, 1], "writer": "<16 bytes>", "val": 17 }
```

| Field | Type | Meaning |
|---|---|---|
| `key` | string | `movie:<tmdb id>`, `tv:<tmdb id>`, or `episode:<tmdb episode id>`, as every platform's store already keys records (FEATURES §5.1) |
| `reg` | string | The register (below) |
| `hlc` | `[ms, counter]` | Hybrid logical clock stamp: UTC milliseconds and a counter 0–65,535 |
| `writer` | 16 bytes | The writer ID of the device that made the write, never re-stamped (FEATURES §5.3) |
| `val` | JSON or `null` | The value; `null` clears the register and is kept |

Registers per key (FEATURES §5.3: progress is one register; the rest are one
each):

| `reg` | Keys | `val` |
|---|---|---|
| `progress` | `movie`, `episode` | `{"pos": 0–1, "watched_s": ≥ 0, "done": bool, "last": ms}`, and for an episode also `"show"`, `"season"`, `"episode"` (integers) |
| `favourite` | `movie`, `tv` | `true`, or `null` |
| `watchlist` | `movie`, `tv` | `true`, or `null` |
| `rating` | `movie`, `tv` | An integer 1–20: TMDB's 0.5–10 scale doubled |
| `review` | `movie`, `tv` | `{"text": string of at most 8,192 UTF-8 bytes}`, once a platform has reviews |

`pos` is the position as a fraction of the runtime, as the Android, Apple,
and Windows stores keep it today. A write whose known fields break these
rules is ignored by every device in the same way.

### 6.3 Merge

FEATURES §5.3 is the rule; this is its exact form.

- **Valid stamps.** `ms` is an integer from 946,684,800,000 (2000-01-01T00:00Z)
  to 2⁵³ − 1; `counter` is an integer from 0 to 65,535; `writer` is 16 bytes.
  A write with an invalid stamp is ignored.
- **Order.** Compare `ms`, then `counter`, then `writer` as 16 unsigned bytes
  in lexicographic order. The higher stamp wins. Compare the decoded bytes,
  never the base64url text, which sorts differently (vector `merge`).
- **Hold.** A write with `ms` more than 300,000 ms ahead of the device's clock
  is held and re-checked on every sync. Held writes do not move the device's
  clock and travel in snapshots.
- **Clock.** For a local write, with `now` the UTC wall clock in ms: if
  `now > hlc.ms`, the stamp is `[now, 0]`; otherwise `[hlc.ms, hlc.counter + 1]`,
  moving to `[hlc.ms + 1, 0]` past 65,535. On applying a received write with a
  higher stamp than `hlc`, the device sets `hlc` to that write's `[ms, counter]`.
- **Warning.** If the device's clock is more than 300,000 ms behind `hlc.ms`,
  the app shows a sync warning (FEATURES §6.7).
- A writer never issues the same stamp twice, so two different values with one
  stamp can come only from a faulty or malicious member (R10); the device keeps
  the value it has.

### 6.4 Batching

- A batch carries at most one write per `(key, reg)`: the latest pending one.
  Progress during playback is coalesced this way (FEATURES §5.2).
- A batch fits in 65,442 bytes of plaintext. More pending writes go in more
  batches, or, for a first upload of a large library, in a snapshot (§7.5).
- A device uploads every write it applies that did not come from the relay,
  with its original stamp, for example writes from iCloud or a OneDrive folder
  (FEATURES §5.5). Duplicates are harmless.
- Existing local records, which have no HLC yet, are stamped once with their
  stored update time in ms (counter 0) and the device's writer ID when sync is
  turned on. A record with no stored time is stamped with the current time.

### 6.5 Member records

```json
{ "device": "<16 bytes>", "sign_pk": "<32 bytes>", "enc_pk": "<32 bytes>", "name": "Living room TV",
  "platform": "android-tv", "added_by": "<16 bytes or null>", "hlc": [1793563203000, 0],
  "writer": "<16 bytes>", "removed": false }
```

- The member list is the set of these records, merged per `device`: a record
  with `removed: true` wins over any record without it, whatever its stamp;
  otherwise the higher stamp wins, which lets a device rename itself.
- Keys never change for a device ID. A record that names known keys
  differently is ignored, with a sync warning.
- `added_by` is the member that paired the device, or `null` for the device
  that created the space or joined with the recovery key.
- `name` is at most 64 UTF-8 bytes. `platform` is one of `ios`, `ipados`,
  `macos`, `tvos`, `visionos`, `android`, `android-tv`, `horizon`, `windows`,
  or `xbox`; apps show others as "Other device".
- Envelopes for a new epoch go only to active devices in this list, never to
  devices the relay lists (§8.1, S3).

### 6.6 Space record

```json
{ "recovery_enc_pk": "<32 bytes>", "recovery_psk": "<32 bytes>", "hlc": [1793563200000, 0], "writer": "<16 bytes>" }
```

The latest record by stamp holds the recovery public key and recovery PSK that
members use for recovery key envelopes (§10.2). `recovery_psk` is a secret,
kept only inside encrypted state. The relay never sees either value.

---

## 7. Relay API

### 7.1 Routes

| Method | Path | Principal | Purpose | Section |
|---|---|---|---|---|
| `GET`, `HEAD` | `/health` | — | Health check (exists today) | — |
| `POST` | `/v1/spaces` | — | Create a space | 7.2 |
| `DELETE` | `/v1/spaces/{space_id}` | Device, recovery | Delete the space and everything in it | 7.2 |
| `POST` | `/v1/spaces/{space_id}/challenges` | — | Device challenge | 4.1 |
| `POST` | `/v1/spaces/{space_id}/sessions` | — | Device session | 4.2 |
| `POST` | `/v1/recovery/{lookup_id}/challenges` | — | Recovery challenge | 10.3 |
| `POST` | `/v1/recovery/{lookup_id}/sessions` | — | Recovery session | 10.3 |
| `POST` | `/v1/spaces/{space_id}/batches` | Device | Append a batch | 7.3 |
| `GET` | `/v1/spaces/{space_id}/batches?after=&limit=` | Device | Read after a cursor | 7.4 |
| `POST` | `/v1/spaces/{space_id}/snapshots` | Device | Start a snapshot upload | 7.5 |
| `PUT` | `/v1/spaces/{space_id}/snapshots/{snapshot_id}/parts/{index}` | Device | Upload a part | 7.5 |
| `POST` | `/v1/spaces/{space_id}/snapshots/{snapshot_id}/commit` | Device | Make it the current snapshot | 7.5 |
| `GET` | `/v1/spaces/{space_id}/snapshots/{snapshot_id}/parts/{index}` | Device | Download a part | 7.5 |
| `GET` | `/v1/spaces/{space_id}/devices` | Device, recovery | List active devices | 7.6 |
| `POST` | `/v1/spaces/{space_id}/devices` | Device, recovery | Add a device | 7.6 |
| `DELETE` | `/v1/spaces/{space_id}/devices/{device_id}` | Device | Remove a device | 7.6 |
| `POST` | `/v1/spaces/{space_id}/epochs` | Device | Start the next epoch | 7.7 |
| `GET` | `/v1/spaces/{space_id}/envelopes?after_epoch=` | Device, recovery | Fetch key envelopes | 7.7 |
| `POST` | `/v1/mailboxes` | — | Open a pairing mailbox | 7.8 |
| `GET` | `/v1/mailboxes/{number}` | — | Read a mailbox's first message | 7.8 |
| `POST` | `/v1/mailboxes/{number}/claim` | — | Answer it: the one attempt | 7.8 |
| `POST` | `/v1/mailboxes/{number}/messages` | Mailbox token | Send the next message | 7.8 |
| `GET` | `/v1/mailboxes/{number}/messages?after=` | Mailbox token | Poll for messages | 7.8 |
| `DELETE` | `/v1/mailboxes/{number}` | Mailbox token | Close the mailbox | 7.8 |

### 7.2 Spaces

**Create.** `POST /v1/spaces`, rate-limited per address (§12.2):

```json
{
  "space_id": "<16 bytes>",
  "device": { "device_id": "<16 bytes>", "sign_pk": "<32 bytes>" },
  "recovery": { "lookup_id": "<16 bytes>", "sign_pk": "<32 bytes>" },
  "recovery_envelope": { "enc": "<32 bytes>", "ct": "<48 bytes>" }
}
```

`201 {"epoch": 1}`. Errors: `space_exists`, `recovery_in_use`, `bad_request`,
`rate_limited`. The device then gets a session and uploads its first batch or
snapshot with its own member record and the space record (§6.5, §6.6).

**Delete.** `DELETE /v1/spaces/{space_id}` → `204`. The relay deletes the space
and everything stored for it, and the recovery lookup, at once
(FEATURES §5.6). Every later request for the space gets `space_not_found`.

### 7.3 Append

`POST /v1/spaces/{space_id}/batches` with `{"envelope": "<bytes>"}`.

- `201 {"seq": 813}`: stored with the next sequence number. Sequence numbers
  rise and are never reused, even after batches are dropped.
- `200 {"seq": 813, "duplicate": true}`: this device already appended this
  `batch_id`; nothing was stored again. Retrying an append is therefore safe.
- Errors: `bad_envelope`, `bad_size`, `stale_epoch` (with `epoch`),
  `device_mismatch`, `batch_id_conflict`, `space_full`, `rate_limited`.

The relay requires `kind = 0x01`, `covers_seq = 0`, `part_index = 0`, and
`part_count = 1`.

### 7.4 Read

`GET /v1/spaces/{space_id}/batches?after={seq}&limit={n}`; `limit` defaults to
and is capped at 100.

```json
{
  "epoch": 2,
  "latest_seq": 820,
  "stored_bytes": 241664,
  "rotation_pending": false,
  "snapshot": { "snapshot_id": "<16 bytes>", "epoch": 2, "covers_seq": 812, "parts": 1 },
  "batches": [ { "seq": 813, "envelope": "<bytes>" } ],
  "more": false
}
```

- If a snapshot exists and `after` is less than its `covers_seq`, the response
  includes `snapshot`, and `batches` start after `covers_seq`
  (FEATURES §5.2). Otherwise `snapshot` is `null` and `batches` start after
  `after`.
- A page holds at most `limit` batches and at most 1 MiB of envelopes, but
  always at least one batch if any follows. `more` says whether more follow.
- `latest_seq` is the last sequence number ever assigned (0 for a new space);
  `stored_bytes` is the size of the stored batches.
- Batches are in the current epoch; §8.3 explains why no older epoch remains
  in the log.

### 7.5 Snapshots

**Start.** `POST /v1/spaces/{space_id}/snapshots` with
`{"snapshot_id", "epoch", "covers_seq", "parts"}` → `201 {"expires_at"}`.
`epoch` is the current epoch, or the next one when the snapshot is for a new
epoch (§7.7). `covers_seq` is at most `latest_seq`. `parts` is 1–128. A space
has one snapshot upload at a time, which expires 10 minutes after it starts. A
new upload replaces the same device's earlier one; while another device's
upload is open, the relay answers `snapshot_busy`.

**Upload a part.** `PUT …/snapshots/{snapshot_id}/parts/{index}` with
`{"envelope"}` → `204`. The header must have `kind = 0x02`, the session's
device, the upload's `epoch`, `snapshot_id`, `covers_seq`, and `part_count`,
`part_index = index`, and a length of 65,536. Uploading a part again replaces
it.

**Commit.** `POST …/snapshots/{snapshot_id}/commit` with `{}` →
`200 {"covers_seq": 812, "dropped": 412}`. The relay requires every part
(`snapshot_incomplete`), the current epoch (`stale_epoch`), and a `covers_seq`
greater than the current snapshot's, or at least 0 if there is none
(`stale_snapshot`). In one transaction it makes this the current snapshot and
deletes every batch with `seq ≤ covers_seq` (FEATURES §5.4 step 4).

**Download a part.** `GET …/snapshots/{snapshot_id}/parts/{index}` →
`200 {"envelope"}`, or `snapshot_not_found` once another snapshot has
replaced it; the device then reads again from its cursor.

A device writes a snapshot when `latest_seq − covers_seq` reaches 500, when
`stored_bytes` passes 5 MiB, on `space_full`, and for a first upload of a large
library. It waits a random 0–30 seconds first, so devices rarely race; the
loser gets `stale_snapshot` and reads the winner's.

### 7.6 Devices

**List.** `GET …/devices` → `200 {"devices": [{"device_id", "sign_pk"}]}`:
active devices only.

**Add.** `POST …/devices` with `{"device_id", "sign_pk"}` →
`201 {"epoch": 2}`. A member does this during pairing (§9), and a recovery
session for itself (§10.3). Errors: `device_exists` (the ID is active or was
ever removed), `device_limit`, `forbidden`.

**Remove.** `DELETE …/devices/{device_id}` → `204`. The device is refused at
once: its tokens, challenges, and key envelopes are deleted, and its ID is kept
as a tombstone so it cannot be added again. The relay sets `rotation_pending`
until the next epoch is committed. A device may remove itself to leave.

### 7.7 Epochs and key envelopes

**Start the next epoch.** `POST …/epochs`:

```json
{
  "epoch": 3,
  "after_seq": 820,
  "snapshot_id": "<16 bytes>",
  "envelopes": [ { "device_id": "<16 bytes>", "enc": "<32 bytes>", "ct": "<48 bytes>" } ],
  "recovery_envelope": { "enc": "<32 bytes>", "ct": "<48 bytes>" },
  "recovery": { "lookup_id": "<16 bytes>", "sign_pk": "<32 bytes>" }
}
```

→ `200 {"epoch": 3}`. `recovery` is present only when the recovery key is
replaced (§10.4). The relay requires, in this order:

1. `epoch` is the current epoch + 1 (`epoch_conflict`). This is FEATURES
   §6.3's "one new epoch at a time".
2. `after_seq` is `latest_seq` (`log_moved`), so no batch can fall between
   the old epoch and the new snapshot.
3. The upload `snapshot_id` is complete, with this `epoch` and
   `covers_seq = after_seq` (`snapshot_incomplete`, `bad_request`).
4. `envelopes` name every active device exactly once and no other
   (`devices_changed`).
5. `recovery_envelope` is present.

Then, in one transaction, it sets the epoch, stores the envelopes, makes the
snapshot current, deletes every batch with `seq ≤ after_seq`, clears
`rotation_pending`, and binds the new recovery lookup if one was given.

**Fetch.** `GET …/envelopes?after_epoch={e}`:

- for a device: `200 {"epoch": 3, "envelopes": [{"epoch": 3, "enc", "ct"}]}`,
  its own envelopes for every epoch after `e`, oldest first;
- for the recovery principal: the recovery envelope for the current epoch only.

The relay keeps each device's envelopes from the epoch after it joined until it
is removed, so a device that was offline through several epochs can follow the
chain (§8.2).

### 7.8 Pairing mailboxes

A mailbox carries at most 6 messages of at most 4 KiB each, alternating
between its owner (the new device) and one peer (the member). It accepts one
claim, within 120 seconds of being opened; after the claim, the exchange has
another 120 seconds to finish, which leaves time for the user to confirm on
the member. Its number is not given to a new mailbox for 10 minutes after it
ends.

**Open.** `POST /v1/mailboxes` with `{"message": "<the owner's first message>"}`
→ `201`:

```json
{ "number": "0427", "mailbox_id": "<16 bytes>", "token": "<32 bytes>", "expires_at": 1793563320000 }
```

`number` is four decimal digits **[D8]**, picked at random among the free
ones; `unavailable` with `retry-after` if none is free after a few tries.
Mailbox tokens are 32 random bytes, stored only as SHA-256, like session
tokens (§4.3).

**Read the first message.** `GET /v1/mailboxes/{number}` →
`200 {"mailbox_id", "message", "expires_at"}`. Errors: `mailbox_not_found`,
`mailbox_claimed`, `mailbox_expired`.

**Claim.** `POST /v1/mailboxes/{number}/claim` with
`{"mailbox_id", "message"}` → `201 {"token", "expires_at"}`. This is the one
attempt (FEATURES §6.1). `mailbox_id` must match, so a code typed late cannot
reach a newer mailbox that reused the number.

**Send.** `POST /v1/mailboxes/{number}/messages` with `{"message"}` →
`201 {"index": 2}`; `mailbox_turn` if it is the other side's turn.

**Poll.** `GET /v1/mailboxes/{number}/messages?after={index}` →
`200 {"messages": [{"index", "from": "owner" | "peer", "message"}], "state": "open" | "claimed" | "closed", "expires_at"}`.
Clients poll every 1–2 seconds.

**Close.** `DELETE /v1/mailboxes/{number}` → `204`. Either side closes the
mailbox when the exchange ends, and to signal a failed code. The relay deletes
everything in it when it closes or expires.

---

## 8. Key epochs

### 8.1 Starting an epoch

A member starts epoch `e + 1` when it removes a device, when `rotation_pending`
is set, when a batch of the current epoch contains a removal, when it replaces
the recovery key (§10.4) **[D12]**, and when the user asks to reset sync keys
(FEATURES §6.3).

1. To remove another device: `DELETE` it (§7.6), then append a batch with its
   member record marked `removed`. A device that leaves does the opposite:
   it appends its own removal first, then deletes itself, and another member
   starts the epoch.
2. Read the log to `latest_seq` and merge (§11.2).
3. List the relay's devices (§7.6). The recipients are the active devices in
   the member list that the relay also lists; a member the relay no longer
   lists is recorded as removed. A device the relay lists but the member list
   does not name is unknown: read the log again after 10 seconds, and if it is
   still unknown, `DELETE` it at the relay and show the warning in §11.4. It
   never gets an envelope.
4. Generate `K_{e+1} = random(32)`.
5. Write the snapshot of the merged state under epoch `e + 1` with
   `covers_seq = latest_seq`, uploading every part (§7.5).
6. Seal one key envelope per recipient, itself included, and one recovery
   envelope (§8.2, §10.2).
7. `POST …/epochs`. On `log_moved` or `devices_changed`, go back to step 2. On
   `epoch_conflict`, another device won: fetch its envelope, and start over only
   if the reason still holds, for example a removed device the winner did not
   know about.

When `rotation_pending` is set, each device waits a random 0–30 seconds before
starting, so one device usually does it.

**Uploads pause while a removal waits for its epoch.** While a batch of the
current epoch contains a removal, devices upload only member records and the
new epoch, keep their watch-state writes pending, and show "Sync is paused
while keys are reset" if this lasts more than a few minutes. An honest relay
has already refused the removed device, but a malicious one could keep serving
it and refuse the new epoch; pausing turns that into a delay, not a leak.

When a device is removed because it was lost or stolen, the app also offers to
replace the recovery key (§10.4, §14.2).

### 8.2 Key envelope **[D3]** **[D4]**

For recipient device `d` in epoch `e ≥ 2`, with HPKE (RFC 9180) mode_psk and
suite `0x0020, 0x0001, 0x0003`:

```
pkR    = d's enc_pk from the member list
info   = label("epoch-envelope") ‖ 0x00 ‖ space_id ‖ u32(e) ‖ d.device_id
psk    = k_epoch_psk(e − 1)
psk_id = label("epoch-psk") ‖ u32(e − 1)
aad    = empty
pt     = K_e
(enc, ct) = SealPSK(pkR, info, aad, pt, psk, psk_id)       enc: 32 bytes, ct: 48 bytes
```

Anyone who knows a device's public key could seal something to it. The PSK
proves that the sender also held the previous epoch's key, which the relay
never has, so the relay cannot substitute a key of its own. A device that
missed several epochs opens them in order, each with the key before it. If an
envelope does not open, the device stops uploading, shows a sync warning, and
asks the user to pair again; it never encrypts under a key it could not verify.

### 8.3 Why the log holds one epoch

The relay accepts appends only in the current epoch (`stale_epoch`), and an
epoch starts only at `latest_seq`, together with a snapshot in the new epoch
that covers every older batch. The log therefore never mixes epochs, a device
needs only the current key to read it, and a device that joins later needs only
the current key (FEATURES §6.3).

---

## 9. Pairing

The member device always decides who joins (FEATURES §6.1). Both flows end
with the same welcome, and both use one mailbox.

### 9.1 Order of a member's steps

After the user confirms "Add Living room TV?", the member:

1. reads the log to the current epoch, fetching its own envelopes if needed, so
   that it holds the current key;
2. appends a batch with the new device's member record (`added_by` = itself);
3. adds the device at the relay (§7.6); if the `epoch` in the response is newer
   than the key it holds, it fetches its envelopes first;
4. sends the welcome with the key of that epoch.

The member record goes first, so a member that starts an epoch while the new
device is being added always finds the device in the member list (§8.1 step 3).

The **welcome**, as UTF-8 JSON:

```json
{ "v": 1, "space": "<16 bytes>", "epoch": 2, "key": "<K_epoch, 32 bytes>",
  "member": { "device": "<16 bytes>", "sign_pk": "<32 bytes>", "enc_pk": "<32 bytes>" } }
```

The new device stores the key, signs in (§4), reads the space, and checks that
the member list contains `member` with the same keys. If the relay's epoch is
newer than `epoch`, it follows the envelope chain (§8.2). If the check fails, it
leaves the space and reports the failure.

### 9.2 By code (CPace)

The new device **N** shows the code `NNNN-SSSS-SSSS`: the four-digit mailbox
number, then an 8-digit secret drawn uniformly from 00000000–99999999 with
`random` and rejection sampling (no modulo bias). The relay never sees the
secret (R1, R2).

CPace inputs (draft-irtf-cfrg-cpace-21, initiator-responder, N is A):

```
PRS = the 8 secret digits in ASCII
CI  = lv_cat(label("pairing"), origin)
sid = random(16), chosen by N
ADa = N.device_id ‖ N.sign_pk ‖ N.enc_pk          (80 bytes)
ADb = M.device_id ‖ M.sign_pk ‖ M.enc_pk          (80 bytes)
```

CPace binds the session to `sid`, which is unique per mailbox, and to both
devices' public keys through ADa and ADb (R3). The mailbox number is not part of
CI, because N opens the mailbox with its first message, before it knows the
number **[D9]**.

| # | From | Message bytes |
|---|---|---|
| 1 | N, with `POST /v1/mailboxes` | `0x11 ‖ sid ‖ Ya ‖ ADa` |
| 2 | M, with the claim | `0x12 ‖ Yb ‖ ADb ‖ Tb` |
| 3 | N | `0x13 ‖ Ta ‖ seal(k_n2m, 0x13, join request)` |
| 4 | M | `0x14 ‖ seal(k_m2n, 0x14, welcome)` |
| 5 | N | `0x15 ‖ seal(k_n2m, 0x15, {"v":1})`, then N closes the mailbox |

```
g        = calculate_generator(PRS, CI, sid)            (ristretto255, SHA-512)
Ya = ya·g, Yb = yb·g                                     (fresh random scalars)
K        = scalar_mult_vfy(own scalar, other share)      abort if the identity or invalid
ISK      = SHA-512(lv_cat("CPaceRistretto255_ISK", sid, K) ‖ lv_cat(Ya, ADa) ‖ lv_cat(Yb, ADb))
mac_key  = SHA-512("CPaceMac" ‖ sid ‖ ISK)
Ta       = HMAC-SHA-512(mac_key, lv_cat(Ya, ADa))
Tb       = HMAC-SHA-512(mac_key, lv_cat(Yb, ADb))
k_n2m    = HKDF(ISK, salt = sid, info = label("pairing/n2m"), 32)
k_m2n    = HKDF(ISK, salt = sid, info = label("pairing/m2n"), 32)
seal(k, t, pt) = nonce ‖ XChaCha20-Poly1305-Encrypt(k, nonce, pt, aad = sid ‖ u8(t)),  nonce = random(24)
```

- M computes `K`, `ISK`, and `Tb` as soon as it reads message 1, and sends
  message 2 as its claim.
- N checks `Tb` in constant time. If it does not match, N closes the mailbox
  and both devices show that the code did not match. A wrong code produces a
  mismatch, not a leak: an attacker gets one guess per code.
- M checks `Ta` before it shows the join request
  `{"v": 1, "name": "Living room TV", "platform": "android-tv"}` and asks the
  user to confirm. If the user declines, M closes the mailbox.
- If M has sent the welcome but sees no message 5 before the mailbox expires,
  it removes the device again (§7.6), which starts a new epoch (§8.1), and says
  pairing did not finish.
- After 5 failed codes in a row, N waits 30 seconds before it offers another,
  then twice as long after each further failure, up to 15 minutes
  (FEATURES §6.1).
- The secret, the scalars, `K`, `ISK`, and the derived keys are erased when the
  exchange ends.

### 9.3 By QR code

N opens a mailbox with the one-byte message `0x21` and shows a QR code that
holds **[D18]**:

```
payload = 0x01 ‖ u16(number) ‖ mailbox_id ‖ N.device_id ‖ N.sign_pk ‖ N.enc_pk ‖ qr_secret ‖ u8(len(name)) ‖ name
text    = "edendale-sync:1:" ‖ base64url(payload)
```

`qr_secret = random(32)` exists only on N's screen. The relay never sees it.

| # | From | Message bytes |
|---|---|---|
| 1 | N, with `POST /v1/mailboxes` | `0x21` |
| 2 | M, with the claim | `0x22 ‖ enc ‖ ct`: the welcome, sealed to N |
| 3 | N, once message 2 opens | `0x23 ‖ HMAC-SHA-256(ack_key, SHA-256(message 2))`, then N closes the mailbox |

```
psk     = HKDF(qr_secret, salt = mailbox_id, info = label("qr/psk"), 32)
info    = label("qr-welcome") ‖ 0x00 ‖ mailbox_id ‖ N.device_id
(enc, ct) = HPKE SealPSK(pkR = N.enc_pk, info, aad = empty, pt = welcome, psk, psk_id = label("qr/psk"))
ack_key = HKDF(qr_secret, salt = mailbox_id, info = label("qr/ack"), 32)
```

The welcome is sealed to the public key in the QR code, so the relay cannot
swap in its own (R3, S3). The PSK proves the sender scanned the code, so no one
who merely knows N's public key can pull N into another space. If M sees no
valid acknowledgement before the mailbox expires, it removes the device again
(§7.6), which starts a new epoch (§8.1), and says pairing did not finish.

---

## 10. Recovery key

### 10.1 Format **[D5]**

The recovery secret `r` is 16 random bytes (128 bits, FEATURES §6.4). The app
shows it once as 28 Crockford base32 characters in 7 groups of 4:

```
check = the first 12 bits of SHA-256(label("recovery/check") ‖ r)
text  = base32(r ‖ check)                 140 bits → 28 characters, e.g. ZPEA-Q073-FQFT-9B5M-KZ22-H7GY-DZPZ
```

Input is case-insensitive; spaces and hyphens are ignored; `O` reads as `0`,
and `I` and `L` read as `1`. A wrong check says "This recovery key has a typo"
before anything reaches the relay. The alphabet is
`0123456789ABCDEFGHJKMNPQRSTVWXYZ`.

### 10.2 Derived values **[D6]**

```
salt      = label("recovery")
lookup_id = HKDF(r, salt, label("recovery/lookup"), 16)    the relay finds the space by it
sign seed = HKDF(r, salt, label("recovery/sign"),   32)    Ed25519 key pair for recovery sessions
enc sk    = HKDF(r, salt, label("recovery/enc"),    32)    X25519 key pair for recovery envelopes
psk       = HKDF(r, salt, label("recovery/psk"),    32)    authenticates recovery envelopes
```

The relay receives `lookup_id` and the recovery signing public key. Members
receive the recovery encryption public key and `psk` in the space record
(§6.6). Nothing stores `r`.

**Recovery envelope** for epoch `e`, HPKE mode_psk as in §8.2:

```
pkR    = recovery_enc_pk
info   = label("recovery-envelope") ‖ 0x00 ‖ space_id ‖ u32(e)
psk    = recovery_psk,  psk_id = label("recovery/psk")
pt     = K_e
```

### 10.3 Recovering

1. The user types the recovery key. The app checks it and derives §10.2.
2. `POST /v1/recovery/{lookup_id}/challenges` with `{}` →
   `201 {"space_id", "challenge", "expires_at"}`.
3. The app signs
   `label("recovery-session") ‖ 0x00 ‖ u16(len(origin)) ‖ origin ‖ lookup_id ‖ challenge`
   with the recovery signing key and sends
   `POST /v1/recovery/{lookup_id}/sessions` with `{"challenge", "signature"}` →
   `201 {"space_id", "token", "expires_at", "epoch"}`.
4. It fetches its envelope (§7.7) and opens it, getting the current `K_e`.
5. It creates its own device keys, adds itself (§7.6), signs in as that device
   (§4), reads the space, and appends its own member record with
   `added_by: null`.
6. The app shows the member list and offers to remove the devices the user no
   longer has, and to replace the recovery key (§10.4).

A wrong or replaced recovery key gets `space_not_found`.

### 10.4 Replacing

Any member can replace the recovery key: it creates a new `r`, starts an epoch
(§8.1) whose recovery envelope uses the new keys and whose request carries the
new `recovery` binding, and writes a new space record in that epoch's snapshot.
The old lookup stops working when the epoch commits, and because the epoch
changes, the old recovery key cannot open anything new **[D12]**. The app shows
the new key once.

---

## 11. Client procedure

### 11.1 When to sync

On launch, on return to the foreground, about 10 seconds after the last local
change, when playback stops, when the app goes to the background, and in the
background where the OS allows (FEATURES §5.2, §8). Sync never blocks playback,
import, or classification.

### 11.2 One sync

1. Get a session if there is no valid token.
2. **Upload.** Unless uploads are paused (§8.1), send pending writes as
   batches (§6.4). Record each
   `(batch_id, seq, SHA-256(envelope))` as unconfirmed. On `stale_epoch`, fetch
   envelopes and re-encrypt; on `space_full`, write a snapshot and retry.
3. **Read.** Page through `GET …/batches?after=cursor`. If a snapshot comes
   back, download and merge it and set the cursor to its `covers_seq`. Decrypt
   and merge each batch and set the cursor to its `seq`. If a batch or snapshot
   is in an epoch the device lacks, fetch envelopes (§7.7) first.
4. **Check** (§11.4).
5. Write a snapshot if §7.5 says so, and start an epoch if `rotation_pending`
   is set (§8.1).

The cursor and the merged state are saved together, so a crash cannot skip a
batch. A batch read twice changes nothing.

### 11.3 Device state

| Kept | Notes |
|---|---|
| `space_id`, `device_id`, device keys, `writer_id` | This device only; never in backups (FEATURES §5.5) |
| The current epoch's key | Older keys can be deleted: the log holds one epoch (§8.3), and the next envelope needs only the current key |
| `cursor`, `highest_seq_seen`, `hlc` | — |
| Pending writes and unconfirmed batches | Survive restarts |
| The merged state, member list, and space record | In protected storage (R14) |

### 11.4 Checks against a misbehaving relay (S2, S3)

- **Own batches.** An unconfirmed batch is confirmed when a read returns, at
  its `seq`, an envelope with the same SHA-256, or when a snapshot that covers
  its `seq` contains each of its writes or a newer one. If the cursor passes its
  `seq` without either, the device warns and uploads its writes again with the
  same stamps and a new `batch_id`.
- **Rollback.** If `latest_seq` is below `highest_seq_seen`, the device warns
  and uploads its pending and unconfirmed writes again.
- **Unknown devices.** At least once a day, after reading to `latest_seq`, the
  device compares the relay's active devices with the member list. A device the
  relay lists that no member record names is checked again 10 minutes later,
  because a device joining with the recovery key is added at the relay a moment
  before its member record arrives (§10.3). If it is still unknown, the app
  shows "Unknown device in this space" with a Remove button. It never gets a
  key, and the next epoch start removes it (§8.1 step 3).
- **Member list.** The app's device list shows the member list, never the
  relay's.

### 11.5 Backoff

After a network error, `500`, `503`, or `429`, wait the larger of
`retry-after` and a random time between 0 and `min(15 min, 2 s × 2ⁿ)`, where `n`
counts consecutive failures. A `401 unauthenticated` gets one new session and
one retry.

### 11.6 Logging (R15)

No log, analytics, or crash report may contain: space keys, subkeys, device
private keys, the recovery secret or its text, recovery or QR PSKs, the
pairing secret or code, CPace scalars, `K`, `ISK`, `mac_key`, channel keys,
`qr_secret`, session tokens, mailbox tokens, or any decrypted plaintext. Each
branch adds a test that drives a full sync and pairing and checks its log sink
for the Appendix A values.

---

## 12. Limits and rate limits

### 12.1 Limits **[D16]**

| Limit | Value | Relay today |
|---|---|---|
| Batch envelope | One of 1, 2, 4, 8, 16, 32, or 64 KiB | `MAX_BATCH_BYTES` = 64 KiB |
| Stored batches per space | 10 MiB | `MAX_SPACE_BYTES` = 10 MiB |
| Read page | 100 batches and 1 MiB | `MAX_READ_BATCHES` = 100; byte cap new |
| Snapshot | 1–128 parts of exactly 64 KiB (8 MiB), counted apart from batches **[D11]** | New |
| Snapshot uploads | One per space, expires after 10 minutes | New |
| Active devices per space | 20 | New |
| Device IDs ever used per space | 200 | New |
| Challenge | 60 seconds, once; 4 outstanding per principal | New |
| Session token | 15 minutes; 4 live per principal | New |
| Mailbox | Claimed within 120 seconds, finished within 120 seconds of the claim; one claim; 6 messages of ≤ 4 KiB; number held back 10 minutes | New |
| Request body | 96 KiB for routes that carry an envelope, 16 KiB for the rest | New |
| Retention | Space deleted 12 months after last use | `RETENTION_MS` |

Worst case, a space stores 10 MiB of batches plus two 8 MiB snapshots (the
current one and an upload).

### 12.2 Rate limits

These starting values allow normal use with margin; the relay may change them,
and clients only honour `429` and `retry-after`.

**Per network address**, counted by the Workers rate-limiting binding in
60-second periods. The address is the counter's key and is never written to
the relay's storage or logs.

| Routes | Limit per 60 s |
|---|---|
| Create a space | 2 |
| Open a mailbox | 3 |
| Read a mailbox's first message, and claim | 10 |
| Recovery challenges and sessions | 5 |
| Device challenges and sessions | 20 |
| Everything else | 300 |

**Per device, space, or mailbox** (counted in that space's or mailbox's
Durable Object):

| Action | Limit |
|---|---|
| Append, per device | 120 per hour |
| Reads, including snapshot parts, per device | 2,000 per hour |
| Snapshot commits, per space | 24 per day |
| Epoch commits, per space | 10 per day |
| Device additions, per space | 10 per day |
| Mailbox messages and polls, per token | 60 per 60 s |

---

## 13. Relay storage: what stays and what changes

The relay on `sync` already has the storage model this protocol needs: one
Durable Object per space, reached by `getByName(space_id)`, with an append-only
SQLite log, sequence numbers that are never reused (`AUTOINCREMENT`), the
64 KiB and 10 MiB limits, a 100-batch read page, 12-month retention, and
`{ok, …}` results instead of exceptions. Nothing below contradicts it.

| Area | Today | Change |
|---|---|---|
| Space ID | Any string | The base64url `space_id` (22 characters); anything else is `400` |
| `batches` table | `seq`, `data` | Add `batch_id` (unique) and `device_id`. The relay has accepted no data, so the schema changes in place with no migration |
| `append(batch)` | Stores any 1 byte–64 KiB blob | `append(deviceId, envelope)`: checks the header (§5.3) and bucket, dedupes by `batch_id`, returns `{ok: true, seq, duplicate}`; errors become `bad_envelope`, `bad_size`, `stale_epoch`, `device_mismatch`, `batch_id_conflict`, and the existing `space_full` |
| `read(after, limit)` | Batches after a cursor | Also returns `epoch`, `latest_seq` (from `sqlite_sequence`), `stored_bytes`, `rotation_pending`, and the snapshot, starts after the snapshot when the cursor is older, and caps a page at 1 MiB. `bad_cursor` stays |
| New tables in `SyncSpace` | — | `space` (one row: epoch, recovery lookup and signing key, `rotation_pending`, current snapshot), `devices` (with tombstones), `challenges`, `sessions` (token hashes), `envelopes`, `snapshots` and `snapshot_parts`, rate counters |
| Snapshot size | — | Counted apart from `MAX_SPACE_BYTES` (§12.1); parts are 64 KiB rows, well under SQLite's 2 MB row limit in Durable Objects |
| New Durable Object classes | — | `Mailbox`, one per mailbox number, and `RecoveryIndex`, one per `lookup_id`, holding only the `space_id`. Both in migration `v2` (`new_sqlite_classes`) |
| Retention | Alarm 12 months after last use | Unchanged. The space's alarm and `deleteSpace()` also clear its `RecoveryIndex` entry; a stale entry is harmless, because a recovery login is checked against the space's current lookup |
| Rate limits | — | The Workers rate-limiting binding for per-address limits; per-device counters in `SyncSpace` |
| Routes | `/health` only | §7.1 in `src/index.ts`, with the error format in §3.3 |
| Logging | Off | Unchanged |

Cross-object steps are ordered so that a failure leaves nothing harmful: on
create, the `RecoveryIndex` binding comes first and is idempotent for the same
`space_id`; on a recovery replacement, the new binding is made before the epoch
commits and the old one is cleared after.

The relay's tests from FEATURES §11 apply, plus: the session signatures in
vectors `session` and `recovery` verify, and fail when any byte changes; the
envelope headers in `batch` and `snapshot` parse; every route enforces its
principal; and an epoch commit drops the old epoch's batches.

---

## 14. Security review

How this specification carries out FEATURES §6.

| Risk | Where | How |
|---|---|---|
| R1, R2 Low-entropy code, offline guessing | §9.2 | CPace: the code is never a key or verifier, and a transcript gives one guess per code. QR uses a 256-bit secret |
| R3 Man-in-the-middle in pairing | §9.2, §9.3 | Both devices' keys in ADa and ADb, explicit confirmation, `sid`; QR seals to the key in the code, authenticated by `qr_secret` |
| R4, R5 Derived or permanent key | §8 | Random space keys; new epochs on removal, recovery replacement, and reset; the code is erased |
| R6 Clock dependence, R11 Bluetooth tracking | §6.3 | Resolved in FEATURES: keys never depend on time, and there is no Bluetooth |
| R7 Nonce reuse | §5.2, §8.2, §9.2 | A random 192-bit nonce per envelope and pairing message; HPKE seals once per fresh ephemeral key |
| R8 Unauthenticated payload | §5.2 | AEAD with the space ID and the whole header as AAD |
| R9 Replay | §5.3, §7.3, §6.3 | Unique `batch_id`, part numbers in the AAD, an idempotent merge |
| R10 Timestamp forgery | §6.3, §12.2 | The 2000-01-01 floor, the 5-minute hold, per-device append limits; members are trusted |
| R12 Shoulder-surfing | §7.8, §9 | 2-minute mailboxes, one claim |
| R13 Denial of service | §11.5, §12 | Backoff, rate limits |
| R14 Plaintext at rest | §11.3 | Protected storage only (FEATURES §8) |
| R15 Secrets in logs | §11.6 | Named secrets and a log-sink test |
| R16 Custom cryptography | §1.3, Appendix A | Standard constructions only (CPace, HPKE, XChaCha20-Poly1305, HKDF), their published vectors, and libsodium or CryptoKit |
| R17 Open source | Whole document | The design is public; only random keys and the code are secret |
| S1 Metadata | §5.4, §3.2 | Padding buckets, 64 KiB snapshot parts, no stored addresses. §14.1 lists what remains |
| S2 Malicious relay | §11.4 | Own-batch confirmation, rollback detection |
| S3 Relay adds a device | §6.5, §8, §11.4 | Envelopes go only to the member list and are authenticated by the previous epoch's key; unknown devices are shown and removed at the next epoch |
| S4, S5 Abuse and cost | §12 | Size limits, bucket enforcement, rate limits, retention |
| S6 Leaked deployment credential | §4.3 | No relay signing secret; tokens are random and stored hashed |
| S7 Losing every device | §10 | The recovery key |
| S8 Stolen device | §7.6, §8 | Immediate revocation, a new epoch, uploads paused until it commits, and an offer to replace the recovery key |
| S9 Legal requests | §14.1 | The relay holds ciphertext and the metadata below |

### 14.1 What the relay sees

In addition to FEATURES §6.6: the number of epochs and when each started; that
a device was removed; snapshot sizes to the nearest 64 KiB; each device's
signing public key; the recovery lookup ID and signing public key; and, for a
mailbox, that pairing happened, when, and the CPace shares and both devices'
public keys in a code exchange. It never sees device names, platforms, the
member list, the recovery encryption key, or any key that decrypts.

### 14.2 Residual risks this design accepts

These add to FEATURES §6.7.

- **A removed device working with a malicious relay.** Key envelopes are
  authenticated by the previous epoch's key, which the removed device knows. If
  the relay hides the real removal from a device that has not yet seen it, the
  two can give that device a false next epoch and read what it uploads, until
  the device sees the real removal another way. The removed device also knows
  the recovery PSK, so with the relay it could hand a user who recovers a false
  key; replacing the recovery key when removing a lost or stolen device (§8.1)
  closes that.
- **A relay that refuses a new epoch.** Devices then pause uploads (§8.1), so
  this costs availability, not confidentiality.
- **A relay that shows different devices different logs.** §11.4 catches a
  device's own missing batches and a rollback, not a consistent fork.
- **Burning a pairing attempt.** Anyone can claim an open mailbox and use up
  its one attempt, which stops that pairing (not a leak). Four-digit numbers
  and per-address limits make this costly to do at random, and the user simply
  gets a new code.

---

## 15. Open decisions

The text above uses the recommended option for each. "Open choice" means
FEATURES-27.1.md leaves it open; "Refines FEATURES" means this draft changes or
adds to FEATURES-27.1.md and needs a yes.

| # | Kind | Decision | Options | Recommendation |
|---|---|---|---|---|
| D1 | Open choice | Encoding of HTTP bodies and plaintext | (a) JSON with base64url binary fields; (b) CBOR (RFC 8949); (c) raw binary bodies for envelopes, JSON for the rest | **(a).** Every platform parses JSON natively; the 33% base64 overhead on ≤ 1 MiB pages is small; plaintext is easy to inspect in tests |
| D2 | Open choice | Session token format and lifetime | (a) opaque 32-byte random token, stored as SHA-256 in the space, 15 minutes; (b) self-contained tokens signed by the relay (JWT or PASETO); (c) no tokens: sign every request | **(a).** No relay secret to leak (S6), instant revocation, one signature per sync. (b) needs a signing secret; (c) costs a signature per request and needs replay protection |
| D3 | Open choice | Public-key encryption for key envelopes and the QR welcome | (a) HPKE (RFC 9180) mode_psk, X25519, HKDF-SHA256, ChaCha20-Poly1305; (b) libsodium `crypto_box_seal` with the context inside the plaintext; (c) a custom X25519 + HKDF + XChaCha20-Poly1305 construction | **(a).** A standard with published vectors (R16), binds context through `info`, supports a PSK, and is in CryptoKit. (b) has no associated data or PSK; (c) is custom cryptography |
| D4 | Open choice | Authenticating key envelopes | (a) PSK from the previous epoch's key; (b) Ed25519 signature by the issuing member, checked against the member list; (c) none | **(a).** It stops relay substitution with no extra keys, and a device behind by several epochs walks the chain. (b) fails for a device that does not yet know the issuer. (c) lets the relay substitute keys and read everything; rejected |
| D5 | Refines FEATURES | Recovery key text (FEATURES §6.4 says 26 characters) | (a) 28 Crockford base32 characters with a 12-bit check, 7 groups of 4; (b) 26 characters, no check; (c) 12 BIP-39 words | **(a).** Typos are caught offline, before a lookup that would only say "not found" |
| D6 | Open choice | Recovery-key derivation | (a) HKDF-SHA256; (b) Argon2id | **(a).** A 128-bit random secret needs no stretching; Argon2id would add seconds on a TV for no gain |
| D7 | Open choice | Padding | (a) power-of-two buckets from 1 to 64 KiB, snapshot parts always 64 KiB, enforced by the relay; (b) Padmé; (c) multiples of 4 KiB | **(a).** Few distinct sizes (S1), simple rule, and the relay's 64 KiB limit is the top bucket |
| D8 | Refines FEATURES | Mailbox number length (FEATURES shows 2 digits, `52-4821-7730`) | (a) 4 digits: `0427-5247-7730`; (b) 2 digits; (c) 6 digits | **(a).** 2 digits allow only 100 pairings at once worldwide and let anyone burn them all; 6 digits make the code long to type |
| D9 | Open choice | CPace details | (a) CPACE-RISTR255-SHA512, `sid` from the new device, `CI` = label and origin, HMAC-SHA-512 tags; (b) the X25519 suite (needs an Elligator 2 map to Curve25519 that libsodium does not expose); (c) the mailbox number in `CI` (needs two requests to open a mailbox) | **(a).** Matches R16's libsodium ristretto255 functions and the CFRG vectors; `sid` already makes each session unique |
| D10 | Open choice | Compression before encryption | (a) none in version 1; (b) raw DEFLATE before padding | **(a).** Snapshots fit in 8 MiB without it, and parity and vectors stay simple. Revisit if snapshots grow |
| D11 | Refines FEATURES | Snapshot size | (a) up to 8 MiB, counted apart from the 10 MiB of batches; (b) one 10 MiB limit for everything | **(a).** With (b), a full space could not stage the snapshot that frees it |
| D12 | Refines FEATURES | Replacing the recovery key | (a) always starts a new epoch; (b) replaces it without a new epoch | **(a).** Makes "the old one stops working" hold even against a relay that kept the old envelope |
| D13 | Refines FEATURES | The third subkey (FEATURES §6.2 lists one for the member list) | (a) the member list rides in batches and snapshots; the third subkey is the key-envelope PSK; (b) a separate encrypted member-list object with its own subkey | **(a).** One merge path for everything, and members converge like watch state |
| D14 | Refines FEATURES | AAD contents (FEATURES: space ID, epoch, device ID, batch ID) | (a) space ID and the whole 50-byte header; (b) exactly the four fields | **(a).** Also binds kind, `covers_seq`, and part numbers, which snapshots need |
| D15 | Open choice | Device ID | (a) 16 random bytes per membership, never reused in a space; (b) a hash of the signing key | **(a).** Matches FEATURES §6.6 "random device IDs"; (b) is equally sound but ties the ID to one key |
| D16 | Open choice | Limits, rate limits, and session lifetime | The values in §12 and §4.3 | Start with them; change them on `sync` as usage shows |
| D17 | Open choice | Relay origin | The production domain | A custom domain before any app embeds the URL; it goes into every session signature (§4.2). Part of FEATURES §10 item 4 |
| D18 | Open choice | QR payload | (a) `edendale-sync:1:` and base64url, scanned in the app; (b) an `https://` link with the data in the fragment, so a phone's camera app opens Edendale | **(a).** Nothing leaves the app, and no universal-link setup is needed. (b) is friendlier but needs the Web branch's association files and care that the fragment never reaches a server |

Decisions elsewhere that this document depends on: FEATURES §10 item 4
(hosting and domain, D17), item 5 (approval of QR and CPace pairing, epochs,
and the recovery key), item 6 (the merge rule in §6.3), item 7 (whether the
recovery key can be skipped), and item 9 (self-hosting: a later version would
add the relay origin to the QR payload).

---

## Appendix A. Test vectors

### A.1 How to use them

- Every platform branch and `sync` copies the JSON in A.3 into its own tests
  and names the `main` commit it came from (FEATURES §5.2). The relay needs
  `session`, `batch` and `snapshot` (header parsing only), and the session
  signature in `recovery`.
- The vectors were computed by a reference implementation that first passed
  the published vectors in A.2. Inputs that would be random in use (nonces,
  scalars, HPKE ephemeral keys through `ikmE`) are fixed here.
- Fixed inputs are the first bytes of SHA-256(`edendale-sync/v1/test/` ‖ name),
  so the values are reproducible, but they are **test keys only**. Never ship
  them.
- Hex is lowercase; JSON strings are the exact UTF-8 bytes that were
  encrypted. Byte offsets in `must_fail` count from the start of the envelope.
- HPKE implementations that cannot fix the ephemeral key, such as CryptoKit,
  check the open direction: decrypt `enc` and `ct` with the recipient's secret
  key and compare the plaintext.

| Key in A.3 | What it checks |
|---|---|
| `identities` | The space ID and the keys of device A (the member) and device N (the new device) used throughout |
| `session` | §4.2 message layout and an Ed25519 signature by device A |
| `subkeys` | §5.1 HKDF subkeys for epochs 1 and 2 |
| `batch` | §5 a 1 KiB batch envelope: header, AAD, padding, the full envelope, and six changes that must fail to open |
| `padding` | §5.4 bucket boundaries |
| `snapshot` | §5.5 a 70,000-byte plaintext (byte `i` is `i mod 251`) in two 64 KiB parts |
| `epoch_envelope` | §8.2 the epoch 2 key envelope for device N, authenticated by epoch 1 |
| `recovery` | §10 the text with its check, a typo, the derived values, the recovery session signature, and a recovery envelope |
| `pairing_code` | §9.2 a full CPace exchange for code `0427-5247-7730`, the sealed join request and welcome, and a wrong code |
| `pairing_qr` | §9.3 the QR payload and text, the sealed welcome, and the acknowledgement |
| `merge` | §6.3 ordering, a tie decided by writer bytes, validity, holding, and duplicates. `expect.current` is the winning write's `id`; `expect.held` lists held writes |

### A.2 Published vectors to pass first

- CPace: draft-irtf-cfrg-cpace-21 Appendix B.3 (ristretto255 and SHA-512),
  including B.3.10 and B.3.11 for `scalar_mult_vfy`.
- ristretto255: RFC 9496 Appendix A.
- HPKE: RFC 9180 Appendix A.2.2 (DHKEM(X25519), HKDF-SHA256,
  ChaCha20-Poly1305, mode_psk).
- XChaCha20-Poly1305: draft-irtf-cfrg-xchacha-03 §2.2.1 and Appendix A.3.1.
- Ed25519: RFC 8032 §7.1. HKDF: RFC 5869 Appendix A.

### A.3 Vectors

```json
{
  "origin": "https://sync.example",
  "identities": {
    "space_id": "5f733b000572651590abd0c89c18b353",
    "space_id_b64u": "X3M7AAVyZRWQq9DInBizUw",
    "device_a": {
      "device_id": "178e67a51d905be9b481feb3ca07dfb6",
      "sign_seed": "d162ee71d4537c2f1350c8472cb03fbc85b8a577919b99fb0386b9990c306033",
      "sign_pk": "1794300cf74fe9a00c24c25e9f553207febdca8f0a0eac3025585474c077102a",
      "enc_sk": "38e8d07b52723b589fcdfe9e0cc73287d1eff123d1a49b8865c79720de8648e1",
      "enc_pk": "f0258ec868d04148b5156631727ee95bfb9b1f0382f268f82ae611dae486004c"
    },
    "device_n": {
      "device_id": "0c97ee1431da382a354ffe316a42b915",
      "sign_seed": "e7ecade95e298c660f478b61967f7559df0d784ecbc0096bb349a46aaabd7f77",
      "sign_pk": "14cda8748be4379468b45832acaba0199467379925ec6eacabd4306bdad10914",
      "enc_sk": "bd294e6af29d4365a9ad8433d27ea50cf16eb8416469b0f280bcd7c9e891b7b7",
      "enc_pk": "d48126fd183afd9b3b15aefec2aa1da752f60db7b408774c25c71ef00b123375"
    }
  },
  "session": {
    "challenge": "c4edd72ea7ce9c710f51b6c1140eb4f4cac12eed9a1e902ebfb3de83a99a2772",
    "message": "6564656e64616c652d73796e632f76312f73657373696f6e00001468747470733a2f2f73796e632e6578616d706c655f733b000572651590abd0c89c18b353178e67a51d905be9b481feb3ca07dfb6c4edd72ea7ce9c710f51b6c1140eb4f4cac12eed9a1e902ebfb3de83a99a2772",
    "signature": "3eee936dc490e0403bc4a18f20b84624992d4c50257291cd31a3669c269ce4eae8f880c72d66f5ccf290659fdbe3e6a2d9ddc53c36e4cf9e65a85006cec5f70b"
  },
  "subkeys": {
    "space_key_1": "10376a92792e95ef96a845f9369d7dfbd5d72dba22be6cbd014ea9808582ea7b",
    "space_key_2": "12d7b914ace072a9481adf25689594007160d7c0ec5237ee709d31bb56c7b40f",
    "batch_1": "71bf14b0111888b23622321ecf21d12fa779bba78c40aa8a949bfbe5f7cfe893",
    "snapshot_1": "90e0fad0775adfa0cb8690cb6f02b4cbe89533c35a53cfe8e5b97a33d6725540",
    "epoch_psk_1": "3a2eeaab4aadbabe0618d88cc7e9e952d52cd4526b99b798440c61993d22f630",
    "batch_2": "243d0085e59f8c4ce6f14aff24c799ef711bfb419df285cbad681c82481a35cd"
  },
  "batch": {
    "key": "71bf14b0111888b23622321ecf21d12fa779bba78c40aa8a949bfbe5f7cfe893",
    "batch_id": "89dd9709c8bc1e27a86dabbd6d95d608",
    "nonce": "c25841e049fec1bf03ce97d550ea4db68d4fcfe34bb63de4",
    "epoch": 1,
    "plaintext": "{\"v\":1,\"writes\":[{\"key\":\"movie:603\",\"reg\":\"progress\",\"hlc\":[1793563200000,0],\"writer\":\"eGlt5DkigkqA7sCBN5G1BQ\",\"val\":{\"pos\":0.4375,\"watched_s\":2950.5,\"done\":false,\"last\":1793563200000}},{\"key\":\"movie:603\",\"reg\":\"rating\",\"hlc\":[1793563200000,1],\"writer\":\"eGlt5DkigkqA7sCBN5G1BQ\",\"val\":17},{\"key\":\"episode:62085\",\"reg\":\"progress\",\"hlc\":[1793563201000,0],\"writer\":\"eGlt5DkigkqA7sCBN5G1BQ\",\"val\":{\"pos\":1,\"watched_s\":3480,\"done\":true,\"last\":1793563201000,\"show\":1396,\"season\":1,\"episode\":1}},{\"key\":\"tv:1396\",\"reg\":\"favourite\",\"hlc\":[1793563202000,0],\"writer\":\"eGlt5DkigkqA7sCBN5G1BQ\",\"val\":null}],\"members\":[{\"device\":\"DJfuFDHaOCo1T_4xakK5FQ\",\"sign_pk\":\"FM2odIvkN5RotFgyrKugGZRnN5kl7G6sq9Qwa9rRCRQ\",\"enc_pk\":\"1IEm_Rg6_Zs7Fa7-wqodp1L2Dbe0CHdMJcce8AsSM3U\",\"name\":\"Living room TV\",\"platform\":\"android-tv\",\"added_by\":\"F45npR2QW-m0gf6zygfftg\",\"hlc\":[1793563203000,0],\"writer\":\"eGlt5DkigkqA7sCBN5G1BQ\",\"removed\":false}]}",
    "plaintext_length": 911,
    "bucket": 1024,
    "header": "010100000001178e67a51d905be9b481feb3ca07dfb689dd9709c8bc1e27a86dabbd6d95d608000000000000000000000001",
    "aad": "5f733b000572651590abd0c89c18b353010100000001178e67a51d905be9b481feb3ca07dfb689dd9709c8bc1e27a86dabbd6d95d608000000000000000000000001",
    "padded_sha256": "d64b179d16fb435a0aa0119384371476f024045fffa00a4e999564762b5a85c9",
    "envelope": "010100000001178e67a51d905be9b481feb3ca07dfb689dd9709c8bc1e27a86dabbd6d95d608000000000000000000000001c25841e049fec1bf03ce97d550ea4db68d4fcfe34bb63de48eed0969eedae92e6d104636435f203e2a82cd85aa40f81c834076572fcd5e4dd7962ffc0204fd0ffe7ab857125cbd98209f97161c1cb356b8a4a110967e173a258b4aeb262c914a4286a7ce0355aad4b5ab576cd7346c0f043ca52f589584b0c7a0c82dc4fb6102f47fcc56c9694c984acb35e74e38c9a463e72fc983fcd1b045d3cc1e5f20a194aa101f81442daa83a02188a43ab2ec054ad69da520ed74745722a2aaa4d3e90af9d4adbf60f60de9a5cd6f46e89be91c08d040b628b764545d252cab1ea031e67c06de502e368f5433965f3a01a42e2e8486d259e0e931e29c6827883a40e24038a55320ed132acff6907222059bd395b4a9017966bab78092a302f84ad006b48635528738f6e8a896b346363084e87f693f1d9955b158ba5feb467553ee102a5e24f52c17e69acc727f6127f7977a33c481d1ae84e19a8eebd47195b1c6c45a488c66e8885005a25627fb91c112f26bda78ef1add9f382777bdcfde9da6d1529ea5bebac9e49e738924653e1a3c43ee78641c5c216985a8a920755915659bfefc10349b72bef416f692ffc1ea736950e58860d4b32f7d2676f12c54e7932a3525cfb4dc7faff4f9b2116e72ee7e9589ab559b64393154acd4577d193151961e0d2d7d388333ceb0e6785c0de92921eba6920ede632545790fe54f64c62370574395feee9ddc53a8155807f363bb27556e026dc875f6fc8a9cfe88f84dfb9e9bbd580602acadb6387a456b0e35e9bb76b40184a6d942dcb039296a158815af404ecee5a6710ba26ed8289b56f7e3ff4c0a4adddee1b0a6853bc51dc4d2d9a26f7e80c6ead0ab0a3dad645ed5fa678770910d9fdc0f36de28e125c8748ac022d437a4984a29164c2ed55f958cd9d7c9b1cb235d3c6289049cfbfd542c0f176873abea6032c03173c8617c589bee26d31314f1fe514c20c989fa2307dd70e780dae3dc0fe643315568451fde3e5c8da14a6f236e3415838d25c4e8dd3126f8bacbf256739d4116f86c1042d234d16e04faf5d6914ed3ac6cb1edd3b412467284016aa4ee13e3368f78e8a474a741ed17a50e1de1120e90400c959e4f21df2345e3217e992db498c9a6d085aa111b93289ee491f351d1f2f326f232ced3125de83686a6015de5815bf57f69c7f56bdf2aaad70a435c37108cf0b785133958ef7b94da2ef6f35f09359fd1c43fd657c2d4d1d42aa8ee025eebec177f544d717e0490effdcb9678439ef850bb94c6125b58f508d3693daae64d3570f09694feed4a93a848dd3757f960a9b5cf64b435e07d7556a981c746e8bf29e1cd9775b6505bec99598692becb6ec53dbd5b7e04c6",
    "envelope_sha256": "4fe569c4217c02ae53c9fc2a0df93b497d5563245b368f42b9fac6c0e3892cb1",
    "must_fail": [
      {"change":"flip bit 0 of byte 2 (epoch)","offset":2},
      {"change":"flip bit 0 of byte 10 (device_id)","offset":10},
      {"change":"flip bit 0 of byte 30 (batch_id)","offset":30},
      {"change":"flip bit 0 of byte 80 (ciphertext)","offset":80},
      {"change":"flip bit 0 of the last byte (tag)","offset":1023},
      {"change":"open with space_id = other_space_id","other_space_id":"bc4eeb446658772a18fb46432a0f243e"}
    ]
  },
  "padding": [
    {"plaintext_length":0,"bucket":1024},
    {"plaintext_length":1,"bucket":1024},
    {"plaintext_length":930,"bucket":1024},
    {"plaintext_length":931,"bucket":2048},
    {"plaintext_length":1953,"bucket":2048},
    {"plaintext_length":1954,"bucket":2048},
    {"plaintext_length":65442,"bucket":65536},
    {"plaintext_length":65443,"bucket":"too_large"}
  ],
  "snapshot": {
    "key": "90e0fad0775adfa0cb8690cb6f02b4cbe89533c35a53cfe8e5b97a33d6725540",
    "snapshot_id": "ee7aab27e76cc0afbd82290085541040",
    "covers_seq": 812,
    "plaintext": "70000 bytes; byte i = i mod 251",
    "part_capacity": 65442,
    "parts": [
      {
        "index": 0,
        "chunk_length": 65442,
        "nonce": "335a5e1846cbc5924af3138101d2f28ae7bdd350edf184dc",
        "header": "010200000001178e67a51d905be9b481feb3ca07dfb6ee7aab27e76cc0afbd82290085541040000000000000032c00000002",
        "envelope_sha256": "45c360d7b354ef26db1ae4deae6e096cc4ee2605ff6fe5477511c64c152cf755"
      },
      {
        "index": 1,
        "chunk_length": 4558,
        "nonce": "270b55ec800709d5f0ffb754a4bf1dabf053809ee2a6f391",
        "header": "010200000001178e67a51d905be9b481feb3ca07dfb6ee7aab27e76cc0afbd82290085541040000000000000032c00010002",
        "envelope_sha256": "c8ef50fa49bb19cd654575adf0a5a78969ad157ac45a60202c0d44ffb985743f"
      }
    ]
  },
  "epoch_envelope": {
    "epoch": 2,
    "recipient": "device_n",
    "info": "6564656e64616c652d73796e632f76312f65706f63682d656e76656c6f7065005f733b000572651590abd0c89c18b353000000020c97ee1431da382a354ffe316a42b915",
    "psk": "3a2eeaab4aadbabe0618d88cc7e9e952d52cd4526b99b798440c61993d22f630",
    "psk_id": "6564656e64616c652d73796e632f76312f65706f63682d70736b00000001",
    "ikmE": "35a7866d249036da0798119687e49364d91c99e77fcc1ae15aa93fafb2f7acd6",
    "skE": "09fa874e980862bad2ec65729104aec2313c3117c3b1a4184b4126dd2f0af952",
    "enc": "62f3039d08705047a0c164b786b292755a2b04b2c29e20e3807e922fd252df4d",
    "ct": "ac55c562c31de3eae48053d8d6a5c44b7f5f50397a7485f8affa154736a8eef5cc46d4958d0a2097884f2a163204452c",
    "plaintext": "12d7b914ace072a9481adf25689594007160d7c0ec5237ee709d31bb56c7b40f"
  },
  "recovery": {
    "secret": "fd9cab80e37ddfa4acb49fc4289e1e6f",
    "check_sha256": "edfd3259783e2c9664db0300f171c589a7bae1edb086267ed80319c0c02f4e85",
    "display": "ZPEA-Q073-FQFT-9B5M-KZ22-H7GY-DZPZ",
    "typo_example": "ZPEA-7073-FQFT-9B5M-KZ22-H7GY-DZPZ",
    "typo_result": "checksum_mismatch",
    "lookup_id": "945587abdbd1b797c2bba6ee834cfc00",
    "lookup_id_b64u": "lFWHq9vRt5fCu6bug0z8AA",
    "sign_seed": "f298ef8fb541113d116aa19fc92e1fb9647234e88ed2958e88d0bb410fe7bb87",
    "sign_pk": "256d6b64438a6c500eee7043c5c182c7f04c1a691993e9568ce1ab1606b157e4",
    "enc_sk": "9bdc674ca6ccc611c2b2b50168acb0f25c66c35b34a7fbbe9a14f9019392953a",
    "enc_pk": "635bf00cae80ef165d09ca3a1296a7c7d3d9ce5792b612fd3cb6924934c1756f",
    "psk": "11aeeabacd29ad00503a82b1327cf838d12b400d6b20b19d4a3525a30918544c",
    "session": {
      "challenge": "73204d81a9081296b699735340716c9c136fa251fc51263b2fcf5d139a57e1da",
      "message": "6564656e64616c652d73796e632f76312f7265636f766572792d73657373696f6e00001468747470733a2f2f73796e632e6578616d706c65945587abdbd1b797c2bba6ee834cfc0073204d81a9081296b699735340716c9c136fa251fc51263b2fcf5d139a57e1da",
      "signature": "57a2773226a66e27ec463964e95908b6ee3f8582684c85e68b3ae9f04eaccc72f19066b89eb37497cd00abfe85a36801efef8acef1f2370eb6fd62ee9b129a0d"
    },
    "envelope": {
      "epoch": 2,
      "info": "6564656e64616c652d73796e632f76312f7265636f766572792d656e76656c6f7065005f733b000572651590abd0c89c18b35300000002",
      "psk_id": "6564656e64616c652d73796e632f76312f7265636f766572792f70736b",
      "ikmE": "86afc375f9333b2640a0143fa0bbdfc798baacc487e120fc9628e11a47b20c84",
      "enc": "e52a881cbc7fc3798432f12afce4e02cb5c5d2d07216bd138eb7d4c881742119",
      "ct": "68cbf25e94b4d65eaa538ce4322a83937a1924b15d1056aeb93e061f3c4c17450e9e9d9249bf1afd662377651318bb5e",
      "plaintext": "12d7b914ace072a9481adf25689594007160d7c0ec5237ee709d31bb56c7b40f"
    }
  },
  "pairing_code": {
    "display": "0427-5247-7730",
    "mailbox_number": "0427",
    "secret": "52477730",
    "prs": "3532343737373330",
    "ci": "186564656e64616c652d73796e632f76312f70616972696e671468747470733a2f2f73796e632e6578616d706c65",
    "sid": "03c0ae158b2617efd5cbcc708b7350eb",
    "generator_string": "11435061636552697374726574746f32353508353234373737333064000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002e186564656e64616c652d73796e632f76312f70616972696e671468747470733a2f2f73796e632e6578616d706c651003c0ae158b2617efd5cbcc708b7350eb",
    "g": "ac05b504c5204d9f8c058a53015b68d0eeb32444791d78231531c315ce660571",
    "ADa": "0c97ee1431da382a354ffe316a42b91514cda8748be4379468b45832acaba0199467379925ec6eacabd4306bdad10914d48126fd183afd9b3b15aefec2aa1da752f60db7b408774c25c71ef00b123375",
    "ADb": "178e67a51d905be9b481feb3ca07dfb61794300cf74fe9a00c24c25e9f553207febdca8f0a0eac3025585474c077102af0258ec868d04148b5156631727ee95bfb9b1f0382f268f82ae611dae486004c",
    "ya": "1b3cf185508250fe3483f58983ef72814715d7797e37e5892bfbd71e9ed63202",
    "yb": "a5736688545b12edc416d2105c1f7dbddb680e2e1982a3f9ae2ce20ca147460c",
    "Ya": "128b9f7046b7720ba96cf6aca1500dab0682404e491189e9b1accbb2033bfc62",
    "Yb": "60a520387da0eb658e9f8551d9f444a80d6bfb007ea3f2b52056c1f3ce641a7c",
    "K": "ae5108ce717003851f985968efac120708754ae4333d41fe1e98b7ecf48d024a",
    "ISK": "8582791a5a26b8ff3701b109015f4c3c6734e695b7344a8d9a2c33a5c0b9ef54f95f9fc6dc9dd6effed29910e2aa1cfdc3e0615e347416807ac1f6305ae7d80e",
    "mac_key": "f57c8c11293b487c8b027e9cb3e4d0eb12bc2d86bd29e58a5cf32d22c6811a41d5bd2b2076af354fe5bc924a5dc374d46240fc2fa66bd03b2755b5fa4ec013a2",
    "Ta": "cbb1f4a64ea37778a3d5fa339dc659a0287d855acce1039e21a7f9ced4550809fa65a9579706ebb92665ed47c2e7e953c899301121c4e72c1acd9ea6158cde67",
    "Tb": "b91a6d65c2d0ce17df8b58a11cb58f7a23a7b84db28b1f7925a9b3d99fa9acf9a9ff028aebe9c6cb51e3555bde0370c0892d610290da74f83adb014a61395cb9",
    "key_n2m": "9e308ab5bec9100213c88125f3c446c677e0363dd4ff7f2a6695d6854946ab9d",
    "key_m2n": "76a4edf11966bf46063e76fcc20d203dfc70d7c4ae5b3962bebf30b44288124c",
    "message_1": "1103c0ae158b2617efd5cbcc708b7350eb128b9f7046b7720ba96cf6aca1500dab0682404e491189e9b1accbb2033bfc620c97ee1431da382a354ffe316a42b91514cda8748be4379468b45832acaba0199467379925ec6eacabd4306bdad10914d48126fd183afd9b3b15aefec2aa1da752f60db7b408774c25c71ef00b123375",
    "message_2": "1260a520387da0eb658e9f8551d9f444a80d6bfb007ea3f2b52056c1f3ce641a7c178e67a51d905be9b481feb3ca07dfb61794300cf74fe9a00c24c25e9f553207febdca8f0a0eac3025585474c077102af0258ec868d04148b5156631727ee95bfb9b1f0382f268f82ae611dae486004cb91a6d65c2d0ce17df8b58a11cb58f7a23a7b84db28b1f7925a9b3d99fa9acf9a9ff028aebe9c6cb51e3555bde0370c0892d610290da74f83adb014a61395cb9",
    "message_3": {
      "join_request": "{\"v\":1,\"name\":\"Living room TV\",\"platform\":\"android-tv\"}",
      "nonce": "1b5ab2aafdf19887e2edb88535a15669a65cc1e84e34e4ad",
      "bytes": "13cbb1f4a64ea37778a3d5fa339dc659a0287d855acce1039e21a7f9ced4550809fa65a9579706ebb92665ed47c2e7e953c899301121c4e72c1acd9ea6158cde671b5ab2aafdf19887e2edb88535a15669a65cc1e84e34e4ad6a49c6a30666374b66d699a555dc580fceb168fce21b836095751e6f84fa8a4ef1796fd30b2b8c6f0d54f96ee049ebfb1990b30906762476ed00e783e340b5b5d8787e9e589079"
    },
    "message_4": {
      "welcome": "{\"v\":1,\"space\":\"X3M7AAVyZRWQq9DInBizUw\",\"epoch\":2,\"key\":\"Ete5FKzgcqlIGt8laJWUAHFg18DsUjfucJ0xu1bHtA8\",\"member\":{\"device\":\"F45npR2QW-m0gf6zygfftg\",\"sign_pk\":\"F5QwDPdP6aAMJMJen1UyB_69yo8KDqwwJVhUdMB3ECo\",\"enc_pk\":\"8CWOyGjQQUi1FWYxcn7pW_ubHwOC8mj4KuYR2uSGAEw\"}}",
      "nonce": "f580014db62e93e7665c2e77f802bd01d13a12b15b6043f4",
      "bytes": "14f580014db62e93e7665c2e77f802bd01d13a12b15b6043f48b655f5208b85d59d3b3fdc6cc222c5825acacd51514a43325510ab341e86074c678e42d0faf81bdaca86db7937c725cac4f9675badc0102f8d00197e931260f87757f25911370d20b93d0c73ba07078305fbf9ba10d770b10c6b851da7654f8654dce0e8b2cbc849c8922659c1118dde3ffb7e00f73b0a597ae2ebd9e033ef58e45f8afb50e37800b6a250221600988ebf914f69314ca189c0ee1cb9e21a428825af0bc600d83c6d966e2b6f8b3f7e484ee53e7e756b4f4e753352343b6e5729cdfe5b5034cfa8f778183feb0050663045ea8fcfc9b89e334e423e0b5d1dccd2aae4ce468223759109f6486780b37367ec68072b2f90217a7b20f25905fb890d033cfa289e02708ea98976c57212603eb8b"
    },
    "wrong_code": {
      "typed_secret": "52477731",
      "Yb": "a0b33a93587096b4df12138627762cff701a6cb679d6c40a2b8880cfe024302a",
      "Tb_sent": "641998594d3eb53574da70aa2bf5d8bc57511fa07c98f9443f5fd96ceda14f4ac1a7ed26da307dad972ff1c98c850f05408dab000fb46055ea694bded07c7701",
      "Tb_expected_by_new_device": "a95a78895305ce5ed529a7ec6d3a9be33ff40053b1e4c474633fbb38774733dd210825a092408b93c0b1b671273ba0bd44788f28e0a88cca0542b09e96b111f7",
      "result": "mismatch: abort"
    }
  },
  "pairing_qr": {
    "mailbox_number": 427,
    "mailbox_id": "bdffe3f1a6f979340026a00a86e02a0e",
    "qr_secret": "6cd3c35b75693d5d89f99500aeb20ab2b7a637bc52a1334baaf29d31390efa0c",
    "name": "Living room TV",
    "payload": "0101abbdffe3f1a6f979340026a00a86e02a0e0c97ee1431da382a354ffe316a42b91514cda8748be4379468b45832acaba0199467379925ec6eacabd4306bdad10914d48126fd183afd9b3b15aefec2aa1da752f60db7b408774c25c71ef00b1233756cd3c35b75693d5d89f99500aeb20ab2b7a637bc52a1334baaf29d31390efa0c0e4c6976696e6720726f6f6d205456",
    "qr_text": "edendale-sync:1:AQGrvf_j8ab5eTQAJqAKhuAqDgyX7hQx2jgqNU_-MWpCuRUUzah0i-Q3lGi0WDKsq6AZlGc3mSXsbqyr1DBr2tEJFNSBJv0YOv2bOxWu_sKqHadS9g23tAh3TCXHHvALEjN1bNPDW3VpPV2J-ZUArrIKsremN7xSoTNLqvKdMTkO-gwOTGl2aW5nIHJvb20gVFY",
    "qr_text_length": 211,
    "message_1": "21",
    "psk": "a444d6f73c24104aff0835358ce62d0c80dcd894084f29ec5fb2fbf5d74ce75e",
    "psk_id": "6564656e64616c652d73796e632f76312f71722f70736b",
    "info": "6564656e64616c652d73796e632f76312f71722d77656c636f6d6500bdffe3f1a6f979340026a00a86e02a0e0c97ee1431da382a354ffe316a42b915",
    "ikmE": "006efd17bd19a2029efae692d665bfbd0f8b09461e7b560c618290e526490b82",
    "welcome": "{\"v\":1,\"space\":\"X3M7AAVyZRWQq9DInBizUw\",\"epoch\":2,\"key\":\"Ete5FKzgcqlIGt8laJWUAHFg18DsUjfucJ0xu1bHtA8\",\"member\":{\"device\":\"F45npR2QW-m0gf6zygfftg\",\"sign_pk\":\"F5QwDPdP6aAMJMJen1UyB_69yo8KDqwwJVhUdMB3ECo\",\"enc_pk\":\"8CWOyGjQQUi1FWYxcn7pW_ubHwOC8mj4KuYR2uSGAEw\"}}",
    "message_2": "22c0ffbfe0d343c1f0a21f66238634a7119976730246aef45d8413848c99c5574c3b305eda4b1c0b253c5d93a018d5a51c080aedf773bac3b8074d871c1c5f465ed47b62eb466a34b5de24f08eeca2e5f2d8ebfb920724467aedc998f94e1d3c16c38a71cf8b5b4fee6f49fb8a0670c24d5174e48530a63b45c0409c2c559aade19aa42172f919560087c4234849f865f65735fc190048809342cc0d3e7353162913de7bfdfb8e0c8b230cd835e4576b577a5118e6827588a0b7a05750f76361948631fdf523358ee81fc7f65a6c2887838171a9606ab290a7adc2a106633e10eee97bfa78c29060a319bfe6d58d65ba0df067ca19f11ec28ce9c1ddc6cd50a2def1120d178a9b71e85bffea6a409d13d3eff28022c63571bcbacfe35679808e484aef8bd7e62557a6f14fa17b3952b9d176e0",
    "ack_key": "e021b8c64dea39b57834082525a8f9dc370fb0af4fc2e5162c373b88b1ffc0df",
    "message_3": "23b7c3a9e5ae719a36d405bf7350b71699093406e70a8603fa89688109d932c510"
  },
  "merge": {
    "writers": {
      "a": "00112233445566778899aabbccddeeff",
      "a_b64u": "ABEiM0RVZneImaq7zN3u_w",
      "b": "f8112233445566778899aabbccddeeff",
      "b_b64u": "-BEiM0RVZneImaq7zN3u_w"
    },
    "min_valid_ms": 946684800000,
    "hold_ahead_ms": 300000,
    "cases": [
      {
        "name": "higher ms wins",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":0.2},
          {"id":"w2","hlc":[1793563200001,0],"writer":"a","val":0.3}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "input order does not matter",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w2","hlc":[1793563200001,0],"writer":"a","val":0.3},
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":0.2}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "counter breaks an ms tie",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,1],"writer":"a","val":0.2},
          {"id":"w2","hlc":[1793563200000,0],"writer":"b","val":0.3}
        ],
        "expect": {"current":"w1","held":[]}
      },
      {
        "name": "writer bytes break a full tie (not base64url text)",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":0.2},
          {"id":"w2","hlc":[1793563200000,0],"writer":"b","val":0.3}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "a newer clear beats a value",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":17},
          {"id":"w2","hlc":[1793563200000,5],"writer":"a","val":null}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "a write before 2000-01-01 is ignored",
        "now_ms": 1793563200000,
        "writes": [{"id":"w1","hlc":[946684799999,0],"writer":"a","val":9}],
        "expect": {"current":null,"held":[]}
      },
      {
        "name": "2000-01-01T00:00:00Z exactly is valid",
        "now_ms": 1793563200000,
        "writes": [{"id":"w1","hlc":[946684800000,0],"writer":"a","val":9}],
        "expect": {"current":"w1","held":[]}
      },
      {
        "name": "counter above 65535 is ignored",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,65536],"writer":"b","val":9},
          {"id":"w2","hlc":[1793563200000,0],"writer":"a","val":8}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "exactly 5 minutes ahead is applied",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":1},
          {"id":"w2","hlc":[1793563500000,0],"writer":"a","val":2}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "more than 5 minutes ahead is held",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":1},
          {"id":"w2","hlc":[1793563500001,0],"writer":"a","val":2}
        ],
        "expect": {"current":"w1","held":["w2"]}
      },
      {
        "name": "the held write applies once the clock catches up",
        "now_ms": 1793563200001,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":1},
          {"id":"w2","hlc":[1793563500001,0],"writer":"a","val":2}
        ],
        "expect": {"current":"w2","held":[]}
      },
      {
        "name": "a duplicate write changes nothing",
        "now_ms": 1793563200000,
        "writes": [
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":1},
          {"id":"w1","hlc":[1793563200000,0],"writer":"a","val":1}
        ],
        "expect": {"current":"w1","held":[]}
      }
    ]
  }
}
```
