# Future plan: peer-to-peer device link

**Status:** exploration only. Nothing here is scheduled or implemented. It
records a design direction so it can be picked up later, reviewed, and split
into per-branch checklists (`ENHANCEMENT.md` on each platform branch) when the
work starts.

**Affected branches:** `apple-*` (tvOS, iOS, iPadOS, macOS, visionOS),
`android-*` (phone, tablet, Android TV), `windows-*`. Per AGENTS.md rule 7,
each branch implements this natively; only this protocol description is
shared. When the work is scheduled, this document should move to `main` as
the cross-platform protocol reference.

---

## 1. Goal

Let two Edendale installs on the same local network find each other and
exchange data directly, with no Edendale server and no Edendale account:

1. **Account and login handoff**: give a TV (or any device) a cloud account
   or server login that is tedious or impossible to enter there. This is
   today's Apple TV handoff (Apple §J.7), extended across platforms.
2. **Watch-history sync**: copy watch progress, and later favourites,
   watchlist, and ratings, between devices, including across ecosystems
   (Apple TV ↔ Android phone, Android TV ↔ iPhone, Windows ↔ anything).

### Example

The user wants their Apple TV to have the watch history from their Android
phone.

1. On Apple TV, the user opens **Settings → Nearby Devices → Import from a
   device**.
2. Apple TV lists every device on the same Wi-Fi that has Edendale open, for
   example "Pixel 9 (Android)", "Ju's iPhone (iOS)", "Study PC (Windows)".
3. The user picks "Pixel 9". Both screens show the same six-digit code.
4. The phone asks "Send watch history to Living Room? Code 482 913" and the
   user approves.
5. The phone sends its history, Apple TV merges it, and both show a summary.

If the user had started on the phone instead, the phone would be the importer
and Apple TV the exporter.

### Non-goals

- No relay, cloud mailbox, or internet rendezvous. Both devices must be on the
  same local network, and Edendale must be open on both.
- No continuous background sync. Every session is started by the user.
- No transfer of local library data: folder paths, bookmarks, security-scoped
  grants, SAF tree URIs, scanned file lists, subtitle caches, or device
  preferences (AGENTS.md rule 6).
- No media file transfer.

---

## 2. Background: what exists today

### Apple (§J.7, shipped)

`Shared/Controllers/Accounts/AccountHandoff.swift`,
`AccountHandoffCenter.swift`, `Shared/Views/Accounts/AccountHandoffRequestView.swift`,
and `Shared/Views/Downloaded/CloudAccountStep.swift`:

- Apple TV opens DeviceDiscoveryUI's `DevicePicker`. It shows iPhones and
  iPads that are signed in to the TV user's iCloud account (or a family
  member's) and have Edendale installed.
- The connection is `NWConnection(to:using: .applicationService)`, so the
  system authenticates the peer and encrypts the link.
- Messages are versioned JSON behind a 4-byte big-endian length, capped at
  64 KiB. The payload is a `CloudAccount` (refresh token and scopes) or a
  saved server login (with the SFTP host-key pin).
- The iPhone confirms, then answers. The TV refreshes the token before
  storing it.

**Why Android can't join it:** DeviceDiscoveryUI and application services are
Apple-only. `NSApplicationServicePlatformSupport` accepts only Apple
platforms, and trust comes from iCloud membership. An Android device can
neither appear in the picker nor open the connection.

### Android (§I.2, planned, not started)

`android-27.0:ENHANCEMENT.md` §I.2 already plans a phone-to-TV handoff over
Network Service Discovery (`_edendale-handoff._tcp`), with a short code, a
key agreement, AES-GCM, and the same framing as Apple. It stops for owner
review before implementation. This plan supersedes it, so the service type
and handshake match across platforms.

### Watch state on each platform

The portable records are already nearly the same shape on every branch:

| Field | Apple (`WatchProgress` / `CDWatchProgress`) | Android (`watch_progress`) |
|---|---|---|
| Key | `tmdbId` + `mediaType` | `storageKey` = `"movie:603"` / `"episode:62085"` |
| Position | `position` (0…1) | `position` |
| Watched time | `watchedSeconds` | `watchedSeconds` |
| Last watched | `lastWatchedAt: Date` | `lastWatchedEpochMillis` |
| Completed | `isCompleted` | `isCompleted` |
| Episode context | `showTmdbId`, `seasonNumber`, `episodeNumber` | same |

User media state differs. Apple's `CDUserMedia` has one `updatedAt` for
favourite, watchlist, and rating together. Android's `user_media` has a
timestamp and a dirty flag per field. Windows has `WatchProgressStore` and
`WatchlistStore`. See decision D4.

On Apple, watch progress lives in the CloudKit-backed store, so history
imported on Apple TV also reaches the user's other Apple devices through
iCloud.

---

## 3. Roles and user flow

- **Initiator = importer.** The device where the user starts the session
  browses, shows the list, and receives data.
- **Selected device = exporter.** It listens, asks its user for approval,
  and sends data.

This matches how the account handoff already works: the TV starts the
session and receives the account. For history, a two-way sync is two
sessions, one started on each device. A later "merge both ways" mode (D3)
needs no protocol change, because the importer can send its own page set
back after receiving.

Flow:

1. While Edendale is in the foreground, every device listens and advertises
   (§4.1). On iOS this stops when the app is suspended, which is acceptable
   because Edendale must be open.
2. The importer opens **Nearby Devices** and browses. It lists peers by name,
   platform, and capability, and hides peers that lack the needed capability
   or speak an incompatible protocol version.
3. The user picks a peer. The devices connect and run the handshake (§4.3).
   Both screens show the same six-digit code.
4. The importer's user checks the code. The exporter's user sees what is
   requested ("Link Google Drive on Living Room?" or "Send watch history to
   Living Room?") plus the code, and approves or declines.
5. The exporter sends the response. The importer validates it (refreshes the
   token, tests the login, or merges the history) and shows the result.

The exporter must always approve on its own screen. It is the side giving
data away, and the handshake proves only that the code matches, not that the
request is wanted.

---

## 4. Protocol (draft, version 1)

### 4.1 Discovery

- One DNS-SD service type for every feature: **`_edendale._tcp`** (local
  domain). This replaces Android's planned `_edendale-handoff._tcp`.
- TXT record:
  - `v=1`: protocol version.
  - `id=<uuid>`: a random install ID, regenerated on reinstall. It is never a
    hardware identifier.
  - `n=<name>`: display name (see below).
  - `p=apple-tv|ios|ipados|macos|visionos|android|android-tv|windows`
  - `c=handoff,history`: the capabilities this install offers.
- Display name: iOS 16+ returns a generic "iPhone" from `UIDevice.name`
  without a special entitlement, so Edendale should offer an editable device
  name in Settings, defaulting to the platform's best guess.
  `CloudAccountStep.swift` already sends `UIDevice.current.name`. tvOS and
  Android TV names are usually meaningful.
- The listener binds an ephemeral TCP port. The browser resolves it through
  DNS-SD.

### 4.2 Framing

Unchanged from Apple §J.7: a 4-byte big-endian length followed by a UTF-8
JSON body, read in full before decoding. The handshake frames are plaintext.
Every frame after the handshake is an AES-256-GCM ciphertext (§4.3). Each
frame is capped at 64 KiB. Larger payloads are paged (§6.3).

### 4.3 Handshake and pairing code

A numeric-comparison exchange modelled on Bluetooth LE Secure Connections.
The commitment step stops a man in the middle from choosing keys until the
codes collide. Every primitive exists natively: CryptoKit on Apple; JCA XDH
or Tink on Android (check the minimum API level, D5); CNG or BouncyCastle on
Windows.

```
I = importer (initiator), E = exporter

1. I → E  HelloI  { v, id, name, platform, caps, pkI }          pkI = X25519 ephemeral
2. E → I  HelloE  { v, id, name, platform, caps, pkE, cE }      cE = SHA-256(pkE ‖ pkI ‖ nE)
3. I → E  NonceI  { nI }                                        32 random bytes
4. E → I  NonceE  { nE }                                        I checks cE

shared   = X25519(sk, peer pk)
th       = SHA-256(HelloI ‖ HelloE ‖ nI ‖ nE)                   transcript hash
keys     = HKDF-SHA256(ikm: shared, salt: th, info: "edendale-p2p v1")
           → kI2E (32 B), kE2I (32 B), kCode (8 B)
code     = (kCode as UInt64) mod 1_000_000, zero-padded to 6 digits
```

- Both sides show `code`. The exporter's approval screen includes it, and
  the importer shows "Confirm this code appears on <peer>".
- Encrypted frames use a 12-byte nonce of a 4-byte direction tag plus an
  8-byte counter starting at 0, with no reuse. A failed tag check aborts the
  session.
- A version mismatch in either Hello aborts with `unsupportedVersion`, the
  existing `HandoffError` message "Update Edendale on both devices".
- A security review is required before shipping. If preferred, a Noise
  `XX`/`NN` pattern with an out-of-band code check is an alternative, but
  every branch must then agree on one library or an exact construction.

### 4.4 Remembered devices (later phase)

After a confirmed session, each side can store the peer's long-term identity
key: Ed25519 or P-256, exchanged inside the encrypted channel, kept in the
Keychain, Android Keystore, or Windows DPAPI. Later sessions with that peer
sign the transcript hash instead of showing a code. The exporter still
approves every request. **Settings → Nearby Devices → Remembered** lists and
forgets them. Remembered keys are device-local and never synced.

### 4.5 Messages

After the handshake, every message is a JSON object with `type`:

| Type | Direction | Body |
|---|---|---|
| `request` | I → E | `{ kind: "account", provider }`, `{ kind: "login", provider, host? }`, or `{ kind: "history", scopes: ["progress", "userMedia", "watchlist"], since?: epochMillis }` |
| `declined` | E → I | `{}`, sent when the user declines, the exporter is busy, or the request is unsupported |
| `account` | E → I | §5 payload |
| `login` | E → I | §5 payload |
| `historyPage` | E → I | `{ page, records: [...] }` (§6) |
| `historyEnd` | E → I | `{ pages, counts }` |
| `ack` | I → E | `{ imported, skipped }`, optional, so the exporter can show a summary |

One request per session. The exporter closes the connection after sending.
The importer applies a timeout: ten minutes for `account`/`login`, because
the exporter may run a full sign-in first (as today), and two minutes per
page for history.

---

## 5. Feature A: account and login handoff

The payloads are Apple §J.7's `AccountHandoff.Account` and
`AccountHandoff.Login`, with one addition:

```json
{
  "type": "account",
  "provider": "googleDrive",
  "subject": "…",
  "email": "…",
  "displayName": "…",
  "refreshToken": "…",
  "scopes": ["https://www.googleapis.com/auth/drive.readonly"],
  "driveID": null,
  "clientID": "…"
}
```

- **`clientID` (new):** the OAuth client that issued the refresh token. A
  refresh token can only be refreshed by its own client, and each platform
  registers its own (see below).
- Logins (SMB, NFS, SFTP, WebDAV, S3) carry the host, port, username, secret,
  and, for SFTP, the pinned host-key fingerprint, as Apple does today.
- The importer validates before storing: it refreshes the token or connects
  with the login. If validation fails, it stores nothing.
- The importer reuses the exporter's refresh token rather than minting
  another (Google allows 100 per account per client).

### Provider-by-provider feasibility across ecosystems

| Provider | Cross-platform handoff | Why |
|---|---|---|
| SMB, NFS, SFTP, WebDAV, S3 | Works | No tokens, just credentials |
| OneDrive | Works if all branches use **one** Entra app registration with iOS, Android, and desktop platforms added | The refresh token is tied to the app (client) ID |
| Dropbox | Works if all branches use **one** Dropbox app key, with each platform's redirect URI registered | Same reason |
| Google Drive | **Blocked until Android decision D9**, then needs verification | See below |

#### Google Drive: the hard part

- Apple uses a Google OAuth client of type **iOS**
  (`Shared/Controllers/Accounts/CloudProviders.swift`, `GOOGLE_DRIVE_CLIENT_ID`).
  Android will register its own client. Google issues one client per
  platform type, so the two branches will never share a client ID.
- A refresh token from the Android client can be refreshed only with the
  Android client ID. With the `clientID` field, Apple TV would refresh using
  the Android client's ID. For public clients (no secret) this is a plain
  token-endpoint request, so it will probably work, but **it must be tested
  with real tokens** before it is promised. Google could also tighten it at
  any time.
- Android decision D9 decides whether Android has a refresh token to hand off
  at all:
  - `AuthorizationClient` (Play Services) returns short-lived access tokens,
    and only issues an auth code for a *web* client with a secret, which a
    serverless app can't hold. **No handoff possible.**
  - PKCE through Custom Tabs with an Android-type client, if Google's
    redirect rules still allow it for this app: refresh token available.
    **Handoff possible**, subject to the test above.
  - Drive through WebDAV only (`rclone serve webdav`): it is a login, so it
    works.
- Recommendation: decide D9 with handoff in mind. Until then, cross-platform
  handoff offers every provider except Google Drive. Apple-to-Apple Google
  handoff keeps working through J.7.

### Keep DeviceDiscoveryUI for iPhone → Apple TV

The existing J.7 path needs no code (iCloud vouches for the peer) and is the
best experience between Apple devices. On Apple TV, **Link Source →
provider** would offer:

- **Continue on iPhone or iPad**: the existing DevicePicker.
- **Use another device**: the Nearby Devices list from this plan, with the
  code, for Android, Windows, Mac, or an iPhone signed in to a different
  Apple Account.

---

## 6. Feature B: watch-history sync

### 6.1 What is sent

| Scope | Records | Notes |
|---|---|---|
| `progress` | `{ tmdbId, mediaType, position, watchedSeconds, lastWatchedAt (epoch ms), isCompleted, showTmdbId?, seasonNumber?, episodeNumber? }` | Phase 1 |
| `userMedia` | `{ tmdbId, mediaType, favourite?, rating?, updatedAt per field }` | Phase 2, see D4 |
| `watchlist` | `{ tmdbId, mediaType, title, posterPath?, addedAt }` | Phase 2 |

- Only titles matched to TMDB are portable. Progress for an unmatched local
  file is keyed by its path, which is local library data, so it is never
  sent.
- `since` lets a repeat import send only records changed after the last
  successful import from that peer, if the importer remembers the peer
  (§4.4). Otherwise everything is sent.
- `mediaType` uses the existing raw values `movie` and `episode`.

### 6.2 Merge rules

These must be written once here and implemented natively, with tests, on
every branch:

1. Match records on `(tmdbId, mediaType)`.
2. A record missing locally is inserted.
3. For progress, the record with the later `lastWatchedAt` wins, field for
   field, except that `isCompleted` is sticky: if either side is completed,
   the result is completed. This avoids "un-watching" an episode because the
   other device last opened it at 10%.
4. `watchedSeconds` takes the maximum of the two. It is a lifetime total,
   and taking the max avoids double counting on repeated imports. (Exact
   totals across devices would need per-device counters. That isn't worth it
   now.)
5. For user media, each field follows last-writer-wins on its own timestamp
   (D4).
6. Deletions do not propagate in v1. Clearing history on one device doesn't
   clear it on another. Tombstones are a possible v2.
7. Imports are idempotent: importing the same data twice changes nothing.

On Apple, the merge writes into the CloudKit-backed `CDWatchProgress` store,
so the result spreads to the user's other Apple devices. The merge must go
through the same store API as playback (`WatchProgressStore`) so the
existing merge policy and UI refresh apply.

### 6.3 Paging

History can exceed the 64 KiB frame cap. The exporter sends `historyPage`
frames of up to about 200 records each (sized to stay under the cap), then
`historyEnd`. The importer applies all pages in one transaction after
`historyEnd`, so an interrupted session changes nothing.

---

## 7. Per-platform notes

### Apple

- Use `NWListener` with `NWListener.Service(type: "_edendale._tcp")` and an
  `NWBrowser` for `.bonjourWithTXTRecord`, over plain TCP. The encryption is
  in-app (§4.3), not TLS.
- Info.plist on every Apple target: `NSBonjourServices` = `_edendale._tcp`,
  plus `NSLocalNetworkUsageDescription`. tvOS already has one for servers,
  which should be widened to mention nearby devices. iOS needs one added. The
  first browse triggers the Local Network permission prompt, and Edendale
  must handle "denied" with a Settings hint.
- The listener runs only while the scene is active. Starting and stopping it
  should follow `scenePhase`, unlike `AccountHandoffCenter`, which runs from
  launch because DeviceDiscoveryUI requires it.
- Reuse `NWConnection.sendFrame`/`receiveFrame` and `ResumeOnce` from
  `AccountHandoff.swift`, and generalize the error enum.
- The Simulator supports Bonjour, so most of this can be tested without
  hardware, unlike DeviceDiscoveryUI.

### Android

- Use `NsdManager` to register and discover, and to resolve (on API 34+,
  `registerServiceInfoCallback`). Use a plain `ServerSocket`/`Socket` on a
  coroutine dispatcher.
- Some devices drop multicast without a `WifiManager.MulticastLock` while
  browsing. Acquire it only during a browse.
- Register in the foreground only, scoped to the app's process lifecycle
  (`ProcessLifecycleOwner`).
- Android TV and phone share the code. The TV uses D-pad focus in the device
  list and the approval dialog.
- §I.2 should be rewritten to point at this protocol.

### Windows

- Use `Windows.Networking.ServiceDiscovery.Dnssd` (`DnssdServiceInstance`,
  `DeviceWatcher` with the DNS-SD AQS filter) and `StreamSocketListener`.
- The firewall prompt appears on the first listen.

---

## 8. Threat model (summary)

| Threat | Mitigation |
|---|---|
| Passive eavesdropper on Wi-Fi | AES-256-GCM with ephemeral X25519 keys. Tokens never travel in clear. |
| Active man in the middle | Numeric comparison with commitment: success odds of 1 in 10⁶ per attempt, and every attempt needs a user approval on the exporter. |
| Rogue device spamming requests | The exporter shows at most one pending request. Others get `declined`. Requests are rate-limited per peer `id`. |
| A peer advertising a misleading name | The code check binds the session to the device in the user's hand. The name is a label, not trust. |
| Malicious payloads | Size caps, strict decoding, version check first, validation (token refresh or login test) before storing, and history field bounds (position 0…1, sane dates). |
| An exported refresh token being misused later | The same risk as J.7 today. The importer stores it in its own secure storage. Removing the account on the provider side revokes it everywhere. |
| Network fingerprinting through DNS-SD | Advertise only while the app is in the foreground. Use a random install ID and a user-chosen name. |

---

## 9. Privacy and documentation changes

- AGENTS.md rule 4 allows "user-controlled sync/storage services", and rule
  6 requires a "documented user-controlled service" for watch-state sync. A
  local, user-started, device-to-device link fits both. The README's privacy
  section and the Web branch's privacy page must describe it:
  - what is advertised on the local network
  - what each feature sends
  - that nothing leaves the local network
  - that the device name is user-editable
- No analytics, no Edendale account, no relay.
- The DESIGN.md patterns needed are a device list, a code display, and an
  approval sheet. They should reuse existing tokens. The code must be large,
  monospaced, and announced by screen readers in digit groups.

---

## 10. Open decisions

| # | Decision | Notes |
|---|---|---|
| D1 | Final service type: `_edendale._tcp` | Must be agreed before any branch ships, and must replace Android §I.2's `_edendale-handoff._tcp`. |
| D2 | Handshake construction | The numeric comparison in §4.3, or a Noise pattern. Needs a security review either way. |
| D3 | One-way only, or an optional "merge both ways" mode | The protocol supports both. The question is UX. |
| D4 | User-media timestamps | Apple would need per-field `updatedAt` (a `CDUserMedia` migration) to match Android, or v1 uses record-level last-writer-wins. |
| D5 | Android crypto library and minimum API | JCA XDH availability compared with Tink or BouncyCastle on the lowest supported API level. |
| D6 | Google client IDs across branches | Depends on Android D9 and a real-token test (§5). |
| D7 | Remembered devices (§4.4) in v1 or later | Later is simpler. The code check costs about two seconds. |
| D8 | Where the editable device name lives | Probably Settings → General on each platform. |

---

## 11. Suggested phases

1. **Protocol spec on `main`.** Move this document there, resolve D1, D2,
   and D5, and publish test vectors: fixed keys and nonces, with the expected
   `th`, keys, code, and one encrypted frame. Every branch tests against the
   same vectors without sharing code.
2. **Discovery and handshake.** Apple and Android, with the Nearby Devices
   list and the code screen, and no payloads yet. Hardware check: Apple TV ↔
   Android phone, both directions.
3. **Cross-platform login handoff.** SMB, SFTP, WebDAV, S3, then OneDrive and
   Dropbox once the client IDs are unified. Apple keeps J.7 for
   iPhone → Apple TV.
4. **Watch-progress import.** §6 with `progress` only.
5. **User media and watchlist.** After D4.
6. **Google Drive handoff across ecosystems.** After D9 and the D6 test.
7. **Windows.**
8. **Remembered devices.** Optional.

### Tests each branch needs

- Codec: framing, the size cap, version rejection, unknown `type`.
- Handshake: the shared test vectors; failure on a tampered Hello, a wrong
  commitment, a reused nonce, or a bad tag.
- Merge: every rule in §6.2, idempotency, and all-or-nothing behaviour when
  the session is cut mid-page.
- Validation: an account whose refresh fails is not stored.
- Hardware: each ordered pair (Apple TV → Android, Android → Apple TV,
  iPhone → Android TV, Android TV → iPhone, plus Windows when it lands),
  with Local Network permission granted and denied.

---

## References

- Apple §J.7 Apple TV sign-in: `ENHANCEMENT.md` on `apple-27.0`
- Android §I.2 phone-to-TV handoff and D9: `ENHANCEMENT.md` on `android-27.0`
- Network framework Bonjour: https://developer.apple.com/documentation/network/nwlistener/service
- Local network privacy: https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription
- Android NSD: https://developer.android.com/develop/connectivity/wifi/use-nsd
- Windows DNS-SD: https://learn.microsoft.com/uwp/api/windows.networking.servicediscovery.dnssd
- Bluetooth LE Secure Connections numeric comparison (Core Spec Vol 3,
  Part H, §2.3.5.6.2), the model for §4.3
- Google OAuth for iOS and desktop apps (public clients, refresh):
  https://developers.google.com/identity/protocols/oauth2/native-app
