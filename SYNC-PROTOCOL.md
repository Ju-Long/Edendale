# Edendale Sync Format and Handoff Protocol, version 1

This document specifies how Edendale devices share watch state through JSON
files on storage the user owns, how they merge those files, and how one
device hands its storage login to another over the local network. It is the
specification that [FEATURES-27.1.md](FEATURES-27.1.md) §5 and §6 refer to.

- **Location:** `main`, documentation only. Each platform branch implements
  this natively and copies the test vectors in Appendix A into its own tests,
  naming the `main` commit they came from.
- **Status:** Draft, 2026-11-02. It replaces the relay protocol draft of
  2026-11-01, which was never implemented.
- **Rules:** [AGENTS.md](AGENTS.md) constraints 3 (secrets), 4 (privacy),
  6 (separate local and portable data), and 7 (parity).

---

## 1. Conventions

### 1.1 Terms

| Term | Meaning |
|---|---|
| **Sync location** | Storage the user owns that holds the sync files: a folder, the app's iCloud Drive folder, a Google Drive, OneDrive, or Dropbox account, or a WebDAV, SMB, or SFTP server. A device has at most one. |
| **Space** | A folder in the location, named by the user, holding one device file per device. Devices pointing at the same location and space name are in the same space. |
| **Device file** | `Edendale/<space-name>/<device-id>.json`: one device's whole merged watch state. Only that device writes it. |
| **Record** | One watch-progress or user-media entry, keyed by TMDB identity (§6). |
| **Tombstone** | A record whose values are cleared, kept so an older copy cannot bring the value back. |
| **`updateTime`** | The one timestamp field: UTC milliseconds since 1970-01-01 as an integer. |
| **Handoff** | The device-to-device transfer of a sync location and its login, or of other linked logins, over the local network. |
| **Host** and **guest** | In a handoff, the host advertises and shows the code or QR code; the guest finds it and types or scans. |
| **Sender** and **receiver** | In a handoff, the sender holds the login and the receiver gets it. Independent of host and guest. |

### 1.2 JSON rules

- UTF-8 without a byte-order mark. Field names are camelCase. Unknown fields
  are ignored on read. A device writes only the fields defined here.
- Times are integers (milliseconds). Identifiers are strings. Booleans are
  `true` or `false`, never 0 and 1. Absent and `null` mean the same thing.
- Numbers that must be integers (`tmdbId`, `season`, `episode`, `updateTime`)
  are rejected if they carry a fraction. `position` and `duration` are
  decimals in seconds.

### 1.3 Notation

`‖` is byte concatenation. `b64u(x)` is base64url without padding (RFC 4648
§5). Byte strings in Appendix A are lowercase hex. `HMAC`, `HKDF`, and `SHA-256`
are RFC 2104, RFC 5869, and FIPS 180-4; `X25519` is RFC 7748; `AES-256-GCM` is
NIST SP 800-38D with a 12-byte nonce and a 16-byte tag.

---

## 2. Overview

1. The user turns sync on, chooses a location and a space name (or receives
   both through a handoff, §9), and the device makes a random device ID.
2. The device writes its whole watch state as its device file (§5).
3. On launch, on return to the foreground, after local changes, and
   periodically, the device lists the space, reads the device files that
   changed, merges their records into its local store (§7), and rewrites its
   own file.
4. Every record carries `updateTime` and the ID of the device that wrote it.
   The highest `updateTime` wins, the whole record at a time.
5. Nothing but watch records is ever written to the location. Logins reach a
   new device only through the handoff (§9).

---

## 3. Identifiers

- **Device ID:** a version-4 UUID in lowercase hyphenated form, made when sync
  is first turned on. It is stored where backups and device transfers cannot
  copy it (FEATURES-27.1.md §5.5). A device that loses it makes a new one.
- **Space name:** 1 to 64 Unicode scalar values after trimming whitespace;
  must not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, or control
  characters, and must not be `.` or `..`. Compared exactly.
- **Platform**, in the device file: one of `ios`, `ipados`, `macos`, `tvos`,
  `visionos`, `android`, `androidtv`, `horizon`, `windows`, `xbox`.

---

## 4. Sync locations and layout

```
<root of the location>/
  Edendale/
    <space-name>/
      <device-id>.json
```

| Location | Root | Write | Change detection |
|---|---|---|---|
| Folder (incl. iCloud Drive, and folders exposed by other cloud apps) | The picked folder | Write `<device-id>.json.tmp`, then rename over the target | Modification time; a file watcher while the app runs |
| The app's iCloud Drive folder (Apple, not tvOS) | The ubiquity container's `Documents` directory | Coordinated write (`NSFileCoordinator`) of the whole file | `NSMetadataQuery` or modification time |
| Google Drive | The Drive root; the app creates `Edendale` and the space folder | `files.create` for a new file, `files.update` with `uploadType=media` to replace it | `files.list` with `'<space folder id>' in parents` and `fields=files(id,name,modifiedTime,size)` |
| OneDrive | The app folder, `Apps/Edendale/` (`/me/drive/special/approot`) | `PUT …:/content` for files under 4 MiB, an upload session above | `/delta` on the space folder, or `lastModifiedDateTime` |
| Dropbox | `/Edendale/` | `files/upload` with `mode: overwrite` | `files/list_folder` on the space folder (`longpoll` is a later option) |
| WebDAV | The configured base | `MKCOL` for folders, `PUT` for the file | `PROPFIND` depth 1 for `getetag` and `getlastmodified` |
| SMB, SFTP | The configured share or path | Create folders, write `<device-id>.json.tmp`, rename over the target | Directory listing with modification times |

Each device records, per other device file, the stamp it last read
(modification time, ETag, Drive `modifiedTime`, Graph `eTag`, or Dropbox
`rev`) and skips files whose stamp has not changed.

**One location per device.** Switching location keeps the local store, makes
no change to the old location, and offers to delete the device's file there.
Switching space name within one location behaves the same way.

---

## 5. Device file

```json
{
  "format": 1,
  "space": "Family",
  "device": {
    "id": "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a",
    "name": "Living Room TV",
    "platform": "tvos",
    "app": "27.1"
  },
  "updateTime": 1762041600000,
  "watchProgress": [
    {
      "type": "movie",
      "tmdbId": 603,
      "position": 1234.5,
      "duration": 8160,
      "completed": false,
      "device": "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a",
      "updateTime": 1762041600000
    }
  ],
  "userMedia": [
    {
      "type": "tv",
      "tmdbId": 1399,
      "favourite": true,
      "watchlist": false,
      "rating": 8.5,
      "review": null,
      "device": "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b",
      "updateTime": 1762038000000
    }
  ]
}
```

| Field | Type | Rule |
|---|---|---|
| `format` | integer | `1`. A reader skips a file with a higher `format` and shows "update Edendale to sync with <device name>". |
| `space` | string | Must equal the folder's space name; otherwise the file is skipped. |
| `device.id` | string | Must equal the file name without `.json`; otherwise the file is skipped. |
| `device.name` | string | 1–64 characters, shown in the UI. |
| `device.platform` | string | §3. Unknown values are shown as "another device". |
| `device.app` | string | The app version, for support. Informational. |
| `updateTime` | integer | The highest `updateTime` of any record in the file, or 0. |
| `watchProgress` | array | §6.1. Keys are unique within the file. |
| `userMedia` | array | §6.2. Keys are unique within the file. |

**Limits.** A file over 8 MiB, or with more than 100,000 records in total, is
skipped with a warning. A `review` is at most 10,000 Unicode scalar values.
A heavy viewer's history is well under 1 MiB.

**Validation.** A file that is not valid JSON, fails any rule above, or whose
records fail §6 is skipped entirely; the warning names the device. Skipping
never removes local data. A record received from another device keeps its
`device` and `updateTime` when the reader stores it and later writes its own
file: a device never re-stamps a value it received.

---

## 6. Records

### 6.1 `watchProgress`

| Field | Type | Rule |
|---|---|---|
| `type` | string | `movie` or `tv`. |
| `tmdbId` | integer | TMDB movie or TV series ID, ≥ 1. |
| `season`, `episode` | integer | Present together, ≥ 0, for `tv`; absent for `movie`. |
| `position` | number | Seconds, ≥ 0, finite. Absent in a tombstone. |
| `duration` | number | Seconds, > 0, finite. Absent in a tombstone. |
| `completed` | boolean | Whether the viewing counts as finished. Absent in a tombstone. |
| `cleared` | boolean | `true` marks a tombstone; then `position`, `duration`, and `completed` are absent. |
| `device` | string | The device ID that wrote this version of the record. |
| `updateTime` | integer | When it was written (§7). |

**Key:** `type:tmdbId` for a movie, `type:tmdbId:season:episode` for an
episode. The `updateTime` of a progress record is also its last-watched time;
there is no second time field.

### 6.2 `userMedia`

| Field | Type | Rule |
|---|---|---|
| `type` | string | `movie` or `tv`. |
| `tmdbId` | integer | ≥ 1. |
| `favourite` | boolean | |
| `watchlist` | boolean | |
| `rating` | number or null | 0.5 to 10.0 in steps of 0.5, or `null`. |
| `review` | string or null | At most 10,000 scalar values, or `null`. |
| `device` | string | As above. |
| `updateTime` | integer | As above. |

**Key:** `type:tmdbId`. A record with `favourite` and `watchlist` false and
`rating` and `review` null is the tombstone; it is kept, not dropped.

### 6.3 Tombstone retention

A device may omit from its own file any tombstone whose `updateTime` is more
than 365 days old. A device that was offline longer than that may bring a
cleared value back; the user clears it again.

---

## 7. Merge

### 7.1 Rule

For each record key, the copy with the highest `updateTime` wins and replaces
the others as a whole. Equal `updateTime` values are broken by the lexically
smaller `device` string (bytewise on the lowercase UUID). The rule is applied
when reading another device's file into the local store and when the local
store is written out, so a device file is always that device's merged view.

```
for each record r in file F (another device's file):
    if r.updateTime > now + 86_400_000:          # far-future guard, 24 h
        flag F.device; skip r
    local = store[key(r)]
    if local is absent
       or r.updateTime > local.updateTime
       or (r.updateTime == local.updateTime and r.device < local.device):
        store[key(r)] = r                        # verbatim, device and updateTime included
    highWater = max(highWater, r.updateTime)
```

### 7.2 Stamping a local write

```
stamp = max(now, highWater + 1)
record.updateTime = stamp
record.device = thisDevice.id
highWater = stamp
```

`now` is the device's UTC clock in milliseconds. `highWater` is the highest
`updateTime` the device has applied or written, persisted with the store. A
device whose clock is behind therefore still orders its new write after what
it just received. The far-future guard keeps a bad clock on one device from
dragging every other device's stamps a year ahead.

### 7.3 Invalid stamps

A missing `updateTime`, a non-integer, or a value below `946_684_800_000`
(2000-01-01T00:00:00Z) counts as 0: the record loses to any valid record and
never raises `highWater`.

### 7.4 Properties

The merge is deterministic, idempotent, and independent of the order in
which files are read: any set of devices that have read each other's latest
files hold the same records. Appendix A.1 gives the cases every client must
pass; each branch also runs a property test over random record sets and
random read orders.

---

## 8. Client procedure

### 8.1 When

- On launch and on return to the foreground: read, then write if anything
  changed locally since the last write.
- After a local change: write 10 seconds after the last change, and at once
  when playback stops or the app goes to the background. Progress during
  playback is coalesced into the latest position.
- Every 5 minutes while the app is in the foreground: read.
- In the background where the OS allows (FEATURES-27.1.md §8): read and write.

### 8.2 One sync

1. List the space folder. Create `Edendale/` and the space folder if missing.
2. For each `<uuid>.json` whose name is not this device's ID and whose stamp
   changed: download, validate (§5), merge (§7).
3. If the local store changed since the last write (by the user or by step 2),
   write this device's file as one replacement (§4).
4. Record the stamps read and the time of the sync for the UI.

A listing that fails leaves the local store untouched. A write that fails is
retried at the next trigger. Failures back off: 1, 2, 4, 8 minutes, capped at
an hour, reset by a success. A listing that returns no files at all (an empty
or deleted space) is not an instruction to clear anything.

### 8.3 Removing a device

From any device: delete `<device-id>.json`. The removed device's records are
already in every other file. If the removed device syncs again it recreates
its file from its local store, so the user is also told how to revoke the
login at the provider (FEATURES-27.1.md §6.5, H6).

### 8.4 Migration from the Windows replica

On first launch after the update, `windows` reads `%OneDrive%\Apps\Edendale\
user-media.json` and `watch-progress.json` once, stamps each record with its
existing timestamp (ties resolved by the device's own ID) and the device's
own ID, merges them into the local store, and offers the OneDrive folder as
the default location. Decision 3 in FEATURES-27.1.md §10 says whether the old
files are then deleted.

### 8.5 Logging

Sync logs record counts, file names, stamps, and error classes. They never
contain record contents, review text, titles, tokens, passwords, or URLs with
credentials.

---

## 9. Handoff, version 2

Version 1 is the shipped Android phone-to-TV handoff (DNS-SD, 6-digit code,
J-PAKE over P-256, AES-256-GCM, JSON frames behind a 4-byte length). Version
2 keeps its framing and its J-PAKE path, adds QR pairing so every platform
can take part, and makes the direction of the transfer independent of who
showed the code. Version 1 stays in service between Android phones and Android
TVs; a device that offers both advertises version 2 and falls back to 1 when
the other side only speaks 1.

### 9.1 Discovery

The **host** advertises `_edendale-handoff._tcp` with DNS-SD while its pairing
screen is open, with TXT records `v=2` and `name=<device name>`, and stops
when the screen closes. The **guest** browses for it. A QR code may carry the
host's address and port so the guest connects without browsing. One handoff
at a time per host.

### 9.2 Framing

Each frame is a 4-byte big-endian length followed by that many bytes. The
length is at most 65,536 (64 KiB); a larger length or a truncated frame closes
the connection. Before the channel is established, frame bodies are UTF-8
JSON objects with `"v": 2` and a `"type"`. After it, frame bodies are AES-GCM
ciphertexts (§9.5) whose plaintexts are such JSON objects.

Timeouts, from version 1: 30 seconds for each handshake read, 10 minutes for
the receiver's wait for the sender's offer (the sender may be signing in), 2
minutes for the sender's wait for the result.

### 9.3 Pairing by QR code

The host makes an ephemeral X25519 key pair `(skH, pkH)` and a 16-byte random
`secret` from a secure generator, and shows:

```
edendale://handoff?v=2&n=<name>&h=<host>&p=<port>&k=<b64u(pkH)>&s=<b64u(secret)>
```

`n` is the host's device name, URL-encoded. `h` and `p` are optional (the
guest may discover the host instead). The guest scans it, makes its own
ephemeral pair `(skG, pkG)`, connects, and both compute:

```
label      = "edendale-handoff-v2"                       (ASCII bytes)
transcript = label ‖ pkH ‖ pkG                           (83 bytes)
shared     = X25519(skG, pkH) = X25519(skH, pkG)
proof      = HMAC-SHA256(key = secret, msg = "guest" ‖ transcript)
key        = HKDF-SHA256(ikm = shared, salt = secret, info = transcript, L = 32)
confirm    = HMAC-SHA256(key = key, msg = "host" ‖ transcript)
```

1. Guest → host: `{"v":2,"type":"hello","pk":"<b64u(pkG)>","proof":"<b64u(proof)>"}`.
2. The host recomputes `proof` with its `secret` and compares in constant
   time. On mismatch it sends `{"v":2,"type":"error","reason":"secret"}` and
   closes; the guest shows "the code has expired or was wrong". On match it
   computes `shared`, `key`, and `confirm`.
3. Host → guest: `{"v":2,"type":"confirm","mac":"<b64u(confirm)>"}`. The
   guest verifies `confirm` under its own `key`; on mismatch it closes and
   shows the same message.
4. Both sides hold `key`; the channel is established (§9.5).

The guest is authenticated to the host because only a device that read the
QR code knows `secret`; the host is authenticated to the guest because `pkH`
came from the QR code, so an attacker on the network cannot complete the key
agreement. `secret`, `skH`, `skG`, and `shared` are discarded when the
connection closes. A QR code is valid for 2 minutes and one `hello`; a second
`hello` on the same code is refused.

### 9.4 Pairing by typed code

As version 1: the host shows a 6-digit code from a secure generator; both
sides run J-PAKE over P-256 (RFC 8236) with the code as the password and
participant IDs `edendale-host` and `edendale-guest`, exchange the three
rounds (round 3 is the key confirmation), and derive the 32-byte channel
`key` with `HKDF-SHA256(ikm = J-PAKE shared key, salt = empty, info =
"edendale-handoff-v2")`. A wrong code fails the round-3 confirmation before
anything else is sent; the host answers `{"v":2,"type":"error","reason":"code"}`
and closes. A code is valid for 2 minutes and one attempt; after 5 failures in
a row the host waits 60 seconds before showing another code. Offered only on
platforms with a vetted J-PAKE (FEATURES-27.1.md §6.2).

### 9.5 Channel

AES-256-GCM under `key`. The 12-byte nonce is a 4-byte big-endian direction
tag ‖ an 8-byte big-endian counter that starts at 0 and increases by 1 per
frame in that direction: tag `1` for guest → host, tag `2` for host → guest.
The additional authenticated data is the ASCII bytes of
`edendale-handoff-v2`. A frame that fails to open closes the connection. The
first guest → host frame uses nonce `00000001 0000000000000000`; the third
host → guest frame uses `00000002 0000000000000002`.

### 9.6 Messages after the channel is up

1. Each side sends `{"type":"role","role":"sender"|"receiver","name":"<device name>"}`.
   If both chose the same role, both show "choose who sends on one device"
   and close.
2. The sender shows what it is about to send, with the receiver's name, and
   waits for the user. On decline it sends `{"type":"offer","status":"declined"}`.
3. Sender → receiver, on approval:

```json
{
  "type": "offer",
  "status": "approved",
  "location": {
    "provider": "googleDrive",
    "space": "Family",
    "account": { "...": "an Account (§9.7), or absent" },
    "login": { "...": "a Login (§9.7), or absent" },
    "googleAccountHint": "person@example.com",
    "googleFolderId": "1AbC..."
  },
  "accounts": [ { "...": "Account" } ],
  "logins": [ { "...": "Login" } ]
}
```

   `location` is absent when the sender has no sync location or the user
   unticked it. For a folder location only `provider: "folder"` and `space`
   are sent; the receiver picks its own folder. `accounts` and `logins` are
   the other linked sources the user ticked.
4. The receiver validates each item (refreshes a token, or connects to the
   server with the saved credential and, for SFTP, checks the host key
   fingerprint), stores what validates in platform-protected storage, and
   answers `{"type":"result","stored":["location","account:<kind>:<subject>", …],"failed":[{"item":"…","reason":"…"}]}`.
5. Both close. The sender shows the result.

### 9.7 Payloads

`Account` and `Login` are the version-1 shapes, unchanged:

```json
{ "kind": "googleDrive", "subject": "…", "email": "…", "displayName": "…",
  "refreshToken": "…", "scopes": ["…"], "driveId": null }

{ "kind": "sftp", "host": "nas.local", "port": 22,
  "credential": { "…": "as the platform's NetworkCredential" },
  "hostKeyFingerprint": "SHA256:…" }
```

`kind` values are the platform's `MediaSourceKind` raw values, which the
branches already keep identical for the version-1 handoff.

### 9.8 Google Drive rule

The sender includes a Google `Account` only when the receiver's platform
(from the `role` message's name, and the DNS-SD TXT record `platform` the
host advertises) uses the same Google OAuth client: `ios`, `ipados`, `macos`,
`tvos`, and `visionos` share one; `android`, `androidtv`, and `horizon` share
one; `windows` has its own. Otherwise it sends `googleAccountHint` and
`googleFolderId` and no `Account`, and the receiver opens Google sign-in with
`login_hint` set to the hint, then uses the folder ID. A receiver that cannot
sign in to Google at all (`tvos`, `androidtv`) shows "set up Google Drive
from an iPhone or iPad" or "from an Android phone" instead.

### 9.9 Errors

| `reason` | Meaning |
|---|---|
| `version` | The other side's `v` is not supported. |
| `secret` | The QR proof did not verify. |
| `code` | The typed code's key confirmation failed. |
| `role` | Both sides chose the same role. |
| `busy` | The host already has a handoff in progress. |
| `malformed` | A frame was not valid for its position in the protocol. |

Every error frame before the channel is up is plaintext JSON with `"v": 2`;
after it, errors travel inside the channel.

---

## 10. Security notes

The review is FEATURES-27.1.md §6.5. In short: the files are protected only by
the storage account; the handoff is protected by J-PAKE or by the QR code's
key and secret, then AES-GCM; logins are never written to the location, logs,
or backups; a removed device is cut off only by revoking its login at the
provider. Clients use only native or audited cryptographic libraries, check
the J-PAKE implementation against RFC 8236's vectors, and check this
document's vectors (Appendix A) in their own tests.

---

## 11. Open decisions

Numbered as in FEATURES-27.1.md §10: the OneDrive app folder (1), typed-code
pairing on Apple (2), Windows migration (3), and tombstone retention (4). This
document assumes the recommended answer to each. One file per device (§4) was
confirmed on 2026-11-02 and is no longer open.

---

## Appendix A. Test vectors

### A.1 Merge

Device IDs used below: `A` = `0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a`,
`B` = `0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b`. The reading device's clock is
`now = 1793577600000` (2026-11-02T00:00:00Z) unless stated. Expected output is the
reading device's store for the keys involved.

**M1, newest wins.** Local (from A): `movie:603` position 100, `updateTime`
1762000000000, device A. File B: `movie:603` position 200, `updateTime`
1762000001000, device B. → Store holds B's record verbatim (position 200,
device B, `updateTime` 1762000001000).

**M2, tie-break.** Local: `tv:1399` favourite true, `updateTime`
1762000002000, device B. File A: `tv:1399` favourite false, watchlist true,
`updateTime` 1762000002000, device A. → A's record wins (`"0a…" < "0b…"`).
Reading the files in the other order gives the same result.

**M3, tombstone wins.** Local: `tv:1399` rating 8.0, `updateTime`
1762000003000, device A. File B: `tv:1399` favourite false, watchlist false,
rating null, review null, `updateTime` 1762000004000, device B. → Store holds
B's tombstone; the key is present, not removed. Writing the file out includes
the tombstone.

**M4, far-future guard.** File B: `movie:603` position 300, `updateTime`
1793750400000 (48 hours ahead). → Not applied, `highWater` unchanged, a warning
names device B. A record stamped exactly `1793664000000` (24 hours ahead) is
applied; one stamped `1793664000000 + 1` is not.

**M5, monotonic stamping.** A device with `now = 1762000000000` and
`highWater = 1762000005000` writes a new rating. → The record's `updateTime`
is 1762000005001, and `highWater` becomes 1762000005001. With
`now = 1762000009000` instead, the stamp is 1762000009000.

**M6, invalid stamps.** File B: `movie:603` with `updateTime` 1500000000
(seconds, below 2000-01-01 in milliseconds) and another file's `movie:604`
with no `updateTime`. → Both count as 0: they lose to any local record for
the same key, are stored if the key is absent locally, and never raise
`highWater`.

**M7, skipped file.** A file named `B.json` whose `device.id` is A's ID, or
with `"format": 2`, or 9 MiB long, or `"space": "Other"` inside the `Family`
folder. → The whole file is skipped with a warning naming the file; the store
and `highWater` are unchanged.

**M8, order independence.** Files A and B as in M1 and M3 read in either
order, twice each. → The same store both times; the second read changes
nothing.

### A.2 QR pairing key schedule

Keys are the X25519 vectors of RFC 7748 §6.1, with Alice as the host and Bob
as the guest; `secret` is the bytes `00 01 … 0f`.

```
pkH        = 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a
pkG        = de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f
shared     = 4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742
secret     = 000102030405060708090a0b0c0d0e0f
transcript = "edendale-handoff-v2" ‖ pkH ‖ pkG            (83 bytes)

proof      = d0a69fb97e78208a179410b52ab43f2ad6805e7c5eb2354d112f40ac678db12a
key        = 2e53bf75ce5fd3de9d5ce33c5b245c12a7bb7da14d0857d12bca26fbed33fa01
confirm    = 0c68fa3fa1adfdef7c07066855502ea9ace5610b06ece6a4494a7824d1cca7e1
```

QR code for this host (no address, name "Living Room TV"):

```
edendale://handoff?v=2&n=Living%20Room%20TV&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAECAwQFBgcICQoLDA0ODw
```

Guest `hello` for this exchange:

```json
{"v":2,"type":"hello","pk":"3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08","proof":"<b64u of proof above>"}
```

**Wrong secret.** With `secret = ff 01 02 … 0f` on the guest's side, the proof
is `c53f739e04e85fcbe125d5a78c6692b04957d131b6e5f0f01fc32cb8b2ca297e`; the host must answer `{"v":2,"type":"error","reason":"secret"}`
and close without computing or sending `confirm`.

### A.3 Channel nonces

```
guest → host, frame 0:  000000010000000000000000
guest → host, frame 1:  000000010000000000000001
host → guest, frame 2:  000000020000000000000002
AAD (ASCII):            65 64 65 6e 64 61 6c 65 2d 68 61 6e 64 6f 66 66 2d 76 32
```

AES-256-GCM itself is checked against NIST's GCM test vectors or the
platform library's own; this document adds no AES vectors.

### A.4 External vectors to pass first

- X25519: RFC 7748 §6.1 (the pair above).
- HKDF-SHA256: RFC 5869 Appendix A, test case 1.
- HMAC-SHA256: RFC 4231 test cases 1–4.
- J-PAKE over P-256: RFC 8236's example run, where the typed-code path is
  implemented.
