# Edendale 27.1 — Feature Plan

A product and engineering plan for the 27.1 release. It states each requested
feature, the platform branch that would own it, the rules it must follow, the
design and security review of cross-device sync, and the decisions needed
before implementation starts.

- **Location:** `main`, documentation only. Nothing in this document adds
  platform source, build files, or CI.
- **Status:** Proposal. No feature here is implemented.
- **Revised 2026-11-02:** cross-device sync moves from the end-to-end
  encrypted relay chosen on 2026-11-01 to plain JSON files on storage the user
  already owns (§5), and the project-rule amendments of 2026-11-01 are
  reverted (§2). The device-to-device handoff stays, and now carries the sync
  location's login to a new device so it needs no second setup (§6). The
  original request and both earlier designs are summarized in §5.7.
- **Basis:** [AGENTS.md](AGENTS.md) (hard constraints 1–8),
  [README.md](README.md) (branch model), and [DESIGN.md](DESIGN.md).

> Read **§1** first. The sync design differs from the original request for
> security reasons (§5, §6), and §10 lists the decisions still needed and the
> cleanup the 2026-11-01 design left behind.

---

## 1. Summary

| # | Feature | Owning branch(es) | Constraint impact | Readiness |
|---|---|---|---|---|
| F1 | VR immersive space with Blender environments | `scenes` (sources), `meta-horizon` (Quest), `apple` (visionOS, if scoped) | None; exports are committed per platform | Feasible; needs performance budget |
| F2 | Parental control API | Each platform, native, local-only | None if kept device-local | Feasible; legal review needed |
| F3 | Expansion to Xbox | New `xbox` branch | Adds a branch; needs a new branch table entry | Needs a decision on the stack |
| F4 | Cross-device history sync through JSON files on the user's own storage (requested over Bluetooth P2P; a relay was considered on 2026-11-01) | Each platform, native; no new branch | None: constraints 4 and 6 already allow a documented user-controlled service (§2) | Model decided 2026-11-02 (§5); layout and scopes in §5.2, §10 |
| F5 | Timestamp-priority merge | Each platform, native | Touches constraint 7 (parity) | Decided 2026-11-02: one `updateTime` field, newest wins (§5.3); existing merge code needs changes |
| F6 | Pairing code and keys (requested: PIN-derived key, rotated by time of day) | Each platform, native | Touches constraint 3 (secrets) | **Redesigned** (§6): keys protect only the handoff channel; Edendale encrypts nothing at rest |
| F7 | Security review of the method | Documented here | — | Done; revised for files and the handoff (§6.5) |
| F8 | Account sign-in across ecosystem via the same method | Each platform | Resolved without an Edendale account: the handoff carries the storage login (§6.3) | Decided 2026-11-02; Google Drive needs a sign-in across ecosystems (§6.4) |
| F9 | Background services that pass data while locked | Each platform, native | OS-limited | Best-effort (§8) |

---

## 2. Conflicts with existing hard constraints

These must be resolved before implementation. They are not implementation
details.

1. **No rule change is needed (amendments reverted 2026-11-02).** Constraint 4
   limits network access to "documented metadata, playback, and
   user-controlled sync/storage services", and constraint 6 says synced watch
   state must "use a documented user-controlled service". The user's own
   Google Drive, OneDrive, Dropbox, iCloud Drive, WebDAV, SMB, or SFTP server,
   or a folder they pick, is exactly that. The 2026-11-01 amendments (the
   relay exception in constraints 4 and 6, the `sync` row in the branch tables
   of AGENTS.md and README.md, CLAUDE.md's "only backend" sentence, README's
   "Your data stays yours" principle, and DESIGN.md's portable-watch-state
   line) are reverted on `main` in this revision.

   Still to do:
   - **Platform branches:** none of them received the 2026-11-01 wording, so
     nothing propagates. If one did, revert it there the same way.
   - **Privacy policy** (`web`, 8 languages): "no account system and no
     servers of its own" stays true. In the release that first offers sync it
     gains a paragraph on the sync files and the handoff (§6.6).
   - **Store disclosures**, in that same release: watch state leaves the
     device only to storage the user chose, on the user's own account, at the
     user's request. Confirm how Google Play's Data safety form and the App
     Store privacy label treat user-initiated transfers to the user's own
     cloud account during store review (§10).
   - **Cleanup** of the relay work: the `sync` branch, its worktree, the
     deployed Worker, and the protocol draft from 2026-11-01 (§10).

2. **F8 is resolved as the handoff (decided 2026-11-02).** A new device
   receives the sync location and its login from a device the user already set
   up, over an encrypted connection on the local network (§6). There is no
   Edendale account, no email address, no password, and no Edendale server.

3. **F3 adds a branch.** `xbox` must be added to the branch tables in
   [AGENTS.md](AGENTS.md) and [README.md](README.md), on `main`, before that
   branch is created. `sync` is removed from them in this revision.

4. **F4 and F6 touch constraint 6** ("separate local and portable data"). Only
   watch records go into the sync files: never file paths, library records,
   access grants, TMDB sessions, storage logins, or parental settings. Logins
   move only device to device over the handoff's encrypted channel, as the
   shipped Apple TV and Android TV handoff already does; they never pass
   through the sync location. Constraint 6's "access grants stay local" is
   read as "never through a service", which is how the handoff was built.

---

## 3. Feature F1 — VR immersive space with Blender environments

**Requested:** a VR immersive space whose environments are built in Blender.

**Product rules**

- The environments are a *theater space* for watching. The player sits in an
  environment; playback stays on the same native player.
- Environments are authored in Blender on the `scenes` branch as `.blend`
  sources, textures, and headless export scripts. No executable code and no CI
  live on `scenes`.
- Each platform commits only the export it needs and names the `scenes` commit
  in the commit message:
  - `meta-horizon`: a GLB, loaded by the Android-based Quest app.
  - `apple`: a USDZ, if visionOS is in scope.
- Trailers still require an explicit user action in the immersive space
  (constraint 4).

**Native approach**

- Quest: the native Android stack on `meta-horizon` (OpenXR-capable runtime);
  renders the GLB and places the player on a screen mesh.
- visionOS (if scoped): RealityKit on `apple`, loading the USDZ.

**Acceptance criteria**

- Each environment has a documented triangle, texture-memory, and draw-call
  budget, measured on the target headset.
- Frame rate holds at the headset's native refresh rate while playing video.
- Reduced-motion users can turn off camera drift and ambient animation.
- Comfort settings (fixed horizon, vignette on movement) are available.
- Environment exports are reproducible from the `scenes` commit named in the
  platform commit.

**Open questions:** whether the Blender scene budget includes baked lighting;
whether visionOS is in 27.1 or deferred.

---

## 4. Feature F2 — Parental control API

**Requested:** a parental-control API for young-audience control.

**Product rules**

- Controls are **device-local** and are not synced. Sync (§5) carries watch
  state only; syncing controls would let any synced device change them. A
  parent configures each device.
- Rules the API can express:
  - Maximum content rating, from TMDB certification data already fetched by
    the user's enrichment. Titles without a certification are treated as
    restricted by default.
  - Hide titles, and block trailers and external links.
  - Lock settings and library edits behind a parental PIN.
- The API is a native interface on each platform (for example a Kotlin
  interface on Android, a Swift protocol on Apple, a C# interface on Windows).
  It is not a shared library.
- The parental PIN is **separate** from the handoff pairing code (§6). Neither
  is derived from the other.
- The PIN is stored as a salted, slow hash (Argon2id, or PBKDF2 where Argon2id
  is unavailable), never in plaintext, and never logged.

**Acceptance criteria**

- A restricted profile cannot change the rating ceiling, the parental PIN, or
  sync settings without the PIN.
- Failed-PIN attempts are rate-limited with an increasing delay.
- No parental setting is sent over the network.

**Legal note:** Age-related rules differ by jurisdiction (for example COPPA in
the US and the UK Age Appropriate Design Code). Store age-rating declarations
and any "kids" category listing need a compliance review before release. This
document does not provide legal advice.

---

## 5. Features F4 and F5 — File sync on the user's storage, and timestamp priority

**Requested:** sync watch history between devices over Bluetooth P2P, with an
8-digit PIN that yields a symmetric key, and priority by the higher Unix
timestamp.

**Revised 2026-11-02:** each device writes its watch state as a JSON file into
a folder on storage the user already owns, and reads the other devices' files
from the same folder. Edendale runs nothing. The timestamp priority is kept
and made exact (§5.3). The device-to-device transfer is kept for the one thing
a new device cannot get from the folder: the login that reaches it (§6).

Why files on the user's storage rather than a relay or Bluetooth:

- **Nothing to host, pay for, protect, or disclose.** Edendale never holds
  anyone's history, so no breach, outage, or legal request at Edendale can
  touch it, and the privacy policy's "no servers of its own" stays true.
- **It fills the same gap.** Today watch progress syncs only inside one
  ecosystem (iCloud on Apple, a OneDrive folder on Windows, nothing live on
  Android). Google Drive, OneDrive, Dropbox, and WebDAV, SMB, and SFTP servers
  are reachable from every platform.
- **Most of it exists.** `windows` already replicates `user-media.json` and
  `watch-progress.json` into `%OneDrive%\Apps\Edendale\`. `apple` and
  `android` already have the connectors for every provider above and a
  phone-to-TV handoff that carries a cloud account or a server login.
- **Unlike Bluetooth,** the two devices never need to be near each other or
  awake at the same time: the folder holds the files in between.
- **What is given up:** the relay's end-to-end encryption (the storage
  provider, and anyone with access to the folder, can read the files: §6.5),
  and the ability to cut off a removed device (§6.5, H6).

### 5.1 Product rules

- Sync moves **watch state only**: progress, completion, favourite, watchlist,
  rating, and review text once a platform has reviews. File paths, library
  records, access grants, TMDB sessions, server and cloud-storage logins, and
  parental settings never go into the sync files (constraint 6).
- Records are keyed by media type and TMDB ID (plus season and episode for
  progress), as every platform's store already is, so they never depend on
  local files.
- Sync is **opt-in and off by default**. Turning it on means choosing a sync
  location and a space name (§5.2), or receiving both from another device
  (§6). There is no email address, password, or profile.
- **One sync location per device at a time.** Choosing another replaces it.
  The local copy is untouched; the files in the old location stay until the
  user deletes them, and the app offers to.
- **A space is a folder.** Two devices pointing at the same location and space
  name are in the same space. One location can hold several spaces (one per
  person in a household, for example); switching spaces on a shared device is
  out of scope for 27.1 beyond choosing a different name.
- Every device keeps a full local copy. Sync never blocks playback, import, or
  classification (constraint 5). Offline, or with the location unreachable,
  the app works as it does today and syncs later.
- The UI shows the location, the space name, and when the device last synced,
  and never claims instant sync.
- **The files are readable JSON.** When a location is chosen, the app says so
  in one sentence: anyone who can open this folder can read your watch
  history. A shared family account or a shared NAS folder is the user's
  choice.

### 5.2 Sync locations and layout

| Location | `apple` (iOS, iPadOS, macOS, visionOS) | `apple` (tvOS) | `android`, `meta-horizon` | `windows` | Access |
|---|---|---|---|---|---|
| **A folder** the user picks. On a phone or computer this includes iCloud Drive and any folder another cloud app exposes (the Google Drive, Dropbox, and OneDrive apps through Files on Apple, document providers on Android, their desktop clients on Windows and macOS) | Files picker; security-scoped bookmark | **No.** tvOS has no file picker and no iCloud Drive ([Apple QA1935](https://developer.apple.com/library/archive/qa/qa1935/_index.html)) | Storage Access Framework folder picker with a persisted permission; most TVs and the Quest have no picker UI, so they use the API providers | Folder picker; `%OneDrive%` and `%USERPROFILE%\iCloudDrive` offered as presets when present | Filesystem |
| **iCloud Drive, the app's own folder** (`iCloud Drive/Edendale`): the app's ubiquity container with public document scope, visible in Files and Finder | Yes (needs the iCloud Documents capability, §10) | No (see above) | No. Apple offers no iCloud Drive client or API for Android | Through iCloud for Windows, which syncs iCloud Drive to a local folder; the app treats it as a folder location. Verify on a PC before release (§11) | Filesystem |
| **Google Drive** | API, `drive.file` | API; login by handoff only, same ecosystem (§6.4) | API, `drive.file` | API, `drive.file` (`windows-27.0.2`; the `windows` branch still asks `drive.readonly`) | OAuth |
| **OneDrive** | Microsoft Graph API | API; device-code sign-in or handoff | API | API | OAuth |
| **Dropbox** | API | API; login by handoff | API | API | OAuth |
| **WebDAV, SMB, SFTP** (and any other server connector a branch gives write support) | Yes | Yes; login by handoff | Yes | Yes | Saved login |

Reading media from iCloud Drive follows the same table: on Windows the iCloud
for Windows folder is a folder like any other, and `windows` can list it as an
"iCloud Drive" preset next to OneDrive; on tvOS and Android there is nothing to
read from.

**Layout.** At the root of the location:

```
Edendale/
  <space-name>/
    <device-id>.json      one file per device, written only by that device
```

On OneDrive through the API the folder is the app's own folder,
`Apps/Edendale/<space-name>/`, which is where `windows` already keeps its
replica (§10, decision 1). The space name is a folder name: 1–64 characters,
no path separators, trimmed. The device ID is a random UUID made when sync is
first turned on, kept out of backups (§5.5).

**One file per device, not one file per space.** Two devices writing the same
file race each other: Google Drive's API has no conditional update, and the
desktop clients of iCloud Drive, OneDrive, and Dropbox resolve a race by
creating a "conflicted copy". With one file per device there is nothing to
lock and nothing to lose: each device writes only its own file, reads all the
others, and merges locally (§5.3). Removing a device from a space is deleting
its file; its records already live in every other file. The exact file
format, limits, and validation are in [SYNC-PROTOCOL.md](SYNC-PROTOCOL.md).

### 5.3 Priority by timestamp

**Decided 2026-11-02:** one standard field, `updateTime`, on every record and
at the top of every device file: UTC milliseconds since 1970 in a 64-bit
integer. For each record key, the copy with the highest `updateTime` replaces
the others, as a whole. That is the requested rule, higher timestamp wins,
with four small rules so it stays safe without a second field:

- **Ties.** Equal `updateTime` values are broken by the lexically smaller
  device ID, so every device picks the same winner.
- **Monotonic stamping.** A device stamps a write with
  `max(now, highest updateTime it has applied + 1)`. A TV whose clock runs a
  few minutes slow still orders the position it writes after the phone's
  position it just applied, instead of losing to it.
- **Far-future guard.** A record stamped more than 24 hours ahead of the
  device's own clock is not applied and does not raise the device's
  high-water mark, and the app shows a sync warning naming the device whose
  file carries it. A missing `updateTime`, or one before 2000-01-01, counts
  as 0 and loses to anything.
- **Clearing is a write.** Removing a rating, a favourite, or progress writes
  the record with the value cleared and a new `updateTime` (a tombstone), so
  an older copy on another device cannot bring the old value back. A device
  may drop tombstones older than a year when it writes its file.

Whole-record wins means that when two devices change different fields of the
same record inside one sync interval (a rating on the phone and the watchlist
flag on the TV, within seconds), only the newer record survives. That is
accepted for simplicity. Progress and user media are separate records, so the
common case, rating a title on the phone while the TV plays it, loses nothing.
A device never re-stamps a value it received, by any path (§5.5), so one write
that arrives by two routes is still one write.

**Existing merge code to change.** The current merges were written for two
replicas and a pending push. Each branch changes its own:

| Branch | Code today | Change |
|---|---|---|
| `android` | `mergeUserMedia` and `mergeWatchProgress` in `src/main/kotlin/com/babasama/edendale/domain/UserMediaMerge.kt` | On a timestamp tie, the side with a pending push (its dirty flag) wins, otherwise the first argument. Dirty flags are local, so two devices can pick different winners: tie-break on device ID instead. Records with nothing set are pruned (`hasState`), which discards the time of the clear: keep them as tombstones. |
| `windows` | `UserMediaMerger` in `Edendale.Windows/Core/UserMediaMerger.cs` | The same tie and pruning behavior (`Winner`, `HasState`), and the same change. `CloudSyncService` moves from the two shared replica files to the per-device layout (§5.5). |
| `apple` | `CDUserMedia` in `Shared/WatchProgress.xcdatamodeld` | Already one `updatedAt` per record, which matches the one-field rule. Add the writing device's ID, keep cleared records as tombstones, and store the stamp as milliseconds. Additive and CloudKit-compatible. |
| Every client | Watch-progress and user-media stores | Store `updateTime` and the writing device's ID with every record. |

### 5.4 Sync flow (summary)

1. **Read.** List `Edendale/<space-name>/`. For every other device's file
   whose modification time or ETag changed since the last read, download it,
   validate it, and merge its records into the local store (§5.3).
2. **Write.** After local changes, write the device's whole merged state to
   its own file: debounced 10 seconds after the last change, and at once when
   playback stops or the app goes to the background. Progress during playback
   is coalesced into the latest position. Writes replace the file in one step
   (upload and replace on the APIs; write a temporary file and rename on a
   filesystem).
3. **When.** On launch, on return to the foreground, after local changes,
   every 5 minutes while the app is in the foreground, and in the background
   where the OS allows (§8). Continue Watching shows local data at once and
   updates in place when a read returns.
4. **Idempotent.** Reading a file twice changes nothing; a file that fails
   validation is skipped with a warning and never deletes local data.
5. **Change detection per provider.** Drive: `files.list` on the space folder
   with `modifiedTime`. OneDrive: a delta query, or `lastModifiedDateTime`.
   Dropbox: `list_folder` (its long-poll endpoint is a later option). WebDAV:
   `PROPFIND` with `getetag` or `getlastmodified`. Folders: modification
   dates, and a file watcher while the app runs.

### 5.5 Coexistence with today's sync

- **iCloud (Apple) keeps working.** CloudKit records gain the device ID and
  the millisecond stamp (additive). A device in both iCloud and a sync space
  bridges them: it writes to its device file everything it applied from
  iCloud with the original stamp, and the merge is idempotent, so duplicates
  are harmless. An iPad that is not in the space still reaches it through an
  iPhone that is.
- **The OneDrive replica on Windows** (`%OneDrive%\Apps\Edendale\` with
  `user-media.json` and `watch-progress.json`) is superseded by the layout in
  §5.2. On first launch after the update, `windows` reads the old files once
  into local state and offers the OneDrive folder as the default location.
  Whether the old files are then deleted is decision 3 in §10.
- **TMDB account sync** (favourites, watchlist, and ratings) stays a separate,
  optional service. TMDB keeps no per-field times, so a value pulled from TMDB
  that differs from local state becomes a new local write, stamped when it is
  pulled.
- **Backups and device transfer.** The device ID must never be restored onto
  another device: two devices with one ID break the tie-break and overwrite
  each other's file. Android adds it to the `cloud-backup` and
  `device-transfer` exclusions in `data_extraction_rules.xml` and to
  `backup_rules.xml`; Apple stores it as a this-device-only Keychain item;
  Windows keeps it outside every synced folder. A restored device makes a new
  ID and re-reads the space; its old records are already in the other files.

### 5.6 What the 2026-11-02 revision removes

- No `sync` branch, no relay, no Worker, no hosting account or deployment
  token, and no relay row in the branch tables. The `sync` branch (pushed to
  the remote as `59ff3b0`) and the Worker deployed on 2026-11-01 are deleted
  by the owner (§10, decision 8).
- No space key, key epochs, recovery key, pairing mailbox, or envelope format.
  The only secret Edendale handles for sync is the location's login, which
  stays in platform-protected storage and moves only through the handoff.
- The 2026-11-01 protocol draft (relay API, device authentication, encrypted
  envelopes) is replaced by [SYNC-PROTOCOL.md](SYNC-PROTOCOL.md) on `main`,
  which now specifies the file format, the merge, and the handoff.

### 5.7 History of this feature

| Date | Design | Why it changed |
|---|---|---|
| Request | Bluetooth P2P, 8-digit PIN that yields a symmetric key rotated by time of day, higher timestamp wins | Both devices must be near each other and awake at once, which iOS does not allow reliably in the background; TVs, headsets, and PCs differ widely in Bluetooth support; a PIN-derived key is guessable offline (§6.5, H3) |
| 2026-11-01 | End-to-end encrypted sync spaces on an Edendale-run relay (Cloudflare Workers, one Durable Object per space), CPace pairing, random space key with epochs, recovery key | An Edendale service to host, fund, defend against abuse, and disclose in the privacy policy and every store; a protocol of its own to specify and implement on five platforms |
| 2026-11-02 | JSON files on the user's own storage, one per device, `updateTime` wins; handoff carries the login | This revision |

---

## 6. Feature F6 — Handoff, pairing, and security review

**Requested:** one party generates an 8-digit PIN; the PIN generates a
symmetric key that is saved permanently and rotated by time of day.

**What stays and what changes.** Edendale no longer encrypts anything it
stores: the sync files are plain JSON, protected by the user's storage
provider the way everything else in that account is. Cryptography is used in
one place, the **handoff**: a direct connection between two of the user's
devices on the local network that carries the sync location's login (and, if
the user chooses, other linked accounts and server logins) to a device that
has not been set up. The threat model for the handoff is an attacker on the
same network who may run a malicious device. The threat model for the files
is the storage provider and anyone else who can open the folder.

### 6.1 What the handoff carries

- **The sync location**: provider, space name, and its login. For a cloud
  account that is the provider's stable user ID, email, display name, refresh
  token, granted scopes, and (OneDrive) drive ID; for a server it is host,
  port, credential, and for SFTP the approved host-key fingerprint. These are
  exactly the `Account` and `Login` payloads the shipped Apple and Android
  handoff sends today. A folder location cannot be transferred: the receiving
  device picks its own folder, and the space name is enough once it points at
  the same storage.
- **Other linked sources**, each ticked by the user on the sending device:
  the cloud accounts and saved server logins the receiving device would
  otherwise have to link one at a time, as the TV handoff does now.
- **Never:** the local library, file paths, the TMDB session, parental
  settings, or the sending device's device ID.

The receiving device validates what it gets (refreshes a token, or connects
to the server), stores it in the platform's protected storage (Keychain,
Android Keystore-encrypted storage, DPAPI), makes its own device ID, and
starts syncing. The sending device shows what it is about to send ("Send the
Google Drive login and the space 'Family' to Living Room TV?") and the result.

### 6.2 Pairing

**What ships today.** Apple: the TV finds iPhones and iPads with
DeviceDiscoveryUI and connects over an application service, which the system
encrypts; same iCloud account, Apple devices only. Android: the TV advertises
`_edendale-handoff._tcp` with DNS-SD and shows a 6-digit code; both sides run
J-PAKE over P-256 keyed by the code (Bouncy Castle), confirm the key, and talk
over AES-256-GCM. Both stay as they are for their own ecosystems.

**For every other pair** (iPhone to Android TV, Android phone to Apple TV,
Mac to Windows, Quest to anything), every platform implements one protocol,
version 2 of the Android one, specified in [SYNC-PROTOCOL.md](SYNC-PROTOCOL.md):

- **Discovery.** The device that will *receive* advertises
  `_edendale-handoff._tcp` while its pairing screen is open (Bonjour on Apple,
  `NsdManager` on Android, DNS-SD on Windows). A QR code can also carry the
  host and port so no discovery is needed.
- **Pairing by QR code (preferred).** The device without a camera shows a QR
  code holding its ephemeral X25519 public key and a random 128-bit secret;
  the device with a camera scans it. The scanner proves it saw the secret, the
  displayer's key came from the QR, and both derive one session key, so
  neither side can be impersonated by someone else on the network. Which side
  sends the login is independent of which side showed the code: the user
  picked the direction on the first screen. A phone can therefore set up a
  TV, a PC, or a headset, and a PC can set up a phone, with the phone always
  doing the scanning. The primitives (X25519, HKDF-SHA256, HMAC-SHA256,
  AES-256-GCM) exist natively on every platform.
- **Pairing by typed code.** The 6-digit code with J-PAKE over P-256, as
  Android ships. It is offered where a vetted J-PAKE exists: Bouncy Castle on
  Android, Quest, and Windows (.NET). CryptoKit has no PAKE, so Apple devices
  do not offer the typed code to non-Apple devices in 27.1; between two Apple
  devices the existing handoff applies, and a pair with no camera on either
  side (a Windows PC and an Apple TV) is bridged with a phone (decision 2,
  §10).
- A code or QR code is valid for 2 minutes and one attempt. A wrong code
  fails the key confirmation before anything is sent; both devices show the
  failure. After 5 failed codes in a row the receiving device waits longer
  before offering another.

### 6.3 F8 — one setup across the ecosystem

Joining a space on a new device is either receiving the location and login
through the handoff (§6.1), or choosing the same location and typing the same
space name by hand. Nothing else exists: no account, no recovery key.

### 6.4 Google Drive across ecosystems

Edendale uses three Google OAuth clients: an iOS-type client shared by iOS,
iPadOS, macOS, tvOS, and visionOS; an Android-type client; and a Desktop-type
client, with Google's non-secret "client secret", on Windows. Google binds a
refresh token to the client that obtained it, so a token moved from an iPhone
to an Android TV or a PC is refused. OneDrive uses one Entra app registration
and Dropbox one app key on every platform, so their tokens transfer, and
server logins are plain credentials. Hence:

- Within one ecosystem (iPhone to Apple TV or Mac, Android phone to Android
  TV or Quest), the handoff carries the Google refresh token, as it does
  today.
- Across ecosystems, the handoff carries the Google account's email, the space
  name, and the space folder's Drive ID instead. The receiving device opens
  Google sign-in with that email as the login hint and, once signed in, uses
  the folder. `drive.file` access is granted to the Cloud project, not to one
  client, so the folder the iPhone created is visible to the Android or
  Windows client of the same project without the picker; this is confirmed by
  the probe in §11 before implementation.
- Apple TV and Android TV cannot sign in to Google themselves (Google's device
  flow does not grant `drive.file` with the picker), so for Google Drive they
  need a phone from the same ecosystem. The UI says so when the location is
  Google Drive.

### 6.5 Security review

Severity assumes an attacker on the user's local network who may run a
malicious device, plus a storage provider or household member who can open
the sync folder.

| # | Risk | Why it matters | Severity | Mitigation |
|---|---|---|---|---|
| H1 | **Plaintext watch history at the storage provider.** | The provider, and anyone who can open the account or the folder, can read titles, progress, ratings, and reviews. | Medium | Accepted by design: it is the user's own storage, chosen by the user. Say so when a location is chosen. Never put logins, tokens, or file paths in the files. The folder is the user's to delete. |
| H2 | **Tampering with files** by the provider or a co-user of the folder. | Records could be altered (a title marked complete, a rating changed). | Low | Accepted. Files are validated on read (types, ranges, timestamps); malformed files are skipped with a warning and never remove local data. Per-device signatures are a later option. |
| H3 | **Low-entropy pairing code.** 6 digits is about 20 bits. | Anything derived from the code alone can be guessed offline. | Critical if misused | The code is never a key or a verifier. J-PAKE gives an attacker one guess per attempt; the QR code carries a 128-bit random secret instead; 2-minute expiry and one attempt. |
| H4 | **Active man-in-the-middle on the network during the handoff.** | Steals the refresh token or server password in transit. | High | J-PAKE key confirmation, or the QR code's key and secret, bind the session to the two devices; AES-256-GCM with direction-and-counter nonces afterwards. |
| H5 | **Refresh tokens and passwords** in transit and on the receiving device. | Long-lived credentials to the user's storage. | High | Sent only inside the authenticated channel; stored in Keychain, Keystore-encrypted storage, or DPAPI; never written to sync files, logs, caches, or backups. |
| H6 | **A stolen or removed device keeps access.** | There is no key to rotate: the device keeps the storage login and its local copy. | Medium | Revoke Edendale at the provider (Google, Microsoft, and Dropbox account security pages) or change the server password, and delete the device's file. The app links to the provider's page from "Remove device". The device keeps what it already has. |
| H7 | **Far-future timestamp** from a buggy clock or a malicious member. | Wins every conflict. | Medium | The 24-hour guard and monotonic stamping (§5.3). Members of a space are trusted by design; the rule protects against accidents, not a malicious member. Document that. |
| H8 | **Shoulder-surfing the code or QR code.** | Shown on one screen and typed or scanned on another. | Medium | Shown only on the pairing screen; 2 minutes; one attempt; the sending device confirms the receiving device's name before sending. |
| H9 | **Plaintext exposure at rest on devices.** Background sync may write history to unprotected storage. | Watch history is personal data. | High | Keep it only in the platform's protected storage (iOS Data Protection, Android credential-encrypted storage). Never write to caches or logs. Background sync on a locked iPhone needs "until first unlock" protection (§8). |
| H10 | **Secrets in logs or crash reports.** | Codes, QR secrets, session keys, tokens, passwords (constraint 3). | High | Redact at the logging layer. A test asserts no key material or token reaches the log sink. |
| H11 | **Custom cryptography.** | Hand-written protocols fail. | High | Only native or audited primitives: CryptoKit, Android Keystore and JCA, .NET, Bouncy Castle's J-PAKE checked against RFC 8236's test vectors. The handoff composes them in the smallest way and is published on `main` with test vectors. |
| H12 | **Shared storage accounts** (a family OneDrive, a shared NAS folder). | Others in the household can read and alter history. | Low | Say so when choosing a location, and suggest a folder only the user can open. |
| H13 | **Presence over DNS-SD.** | A device advertising a service can be noticed on the network. | Low | Advertise only while the pairing screen is open. |
| H14 | **Open-source assumption.** | The code, the file format, and the handoff are public. | — | Design principle: assume everything but the random values and the login is known to the attacker. |

**What the requested design gets right.** A short, human-enterable code is
practical and stays for devices that cannot scan. "Saved permanently" is fine
for what is actually long-lived here, the storage login, because it is stored
in platform-protected storage and never derived from the code. Rotation by
time of day is dropped: it adds no secret.

### 6.6 What the privacy policy says

- Edendale has no account system and no servers of its own (unchanged).
- When the user turns on sync, Edendale writes their watch records (titles by
  TMDB ID, progress, ratings, watchlist, favourites, and reviews) as readable
  JSON files into a folder on storage the user chose, under the user's own
  account with that provider. Edendale never receives them.
- When the user sets up another device, Edendale can send the storage login
  to it directly over the local network, encrypted; nothing is sent to
  Edendale or to any other party.
- The user removes the data by deleting the folder.

### 6.7 Residual risks the design accepts

- The storage provider and anyone with access to the folder can read and
  alter the files (H1, H2).
- A member device is trusted; a removed device keeps what it downloaded and
  its login until the user revokes it at the provider (H6, H7).
- A device whose clock ran far ahead keeps winning until other devices' clocks
  pass its stamps; the 24-hour guard keeps the damage to a day (§5.3).
- If the location is unreachable, sync stops. Local data is unaffected.

---

## 7. Feature F3 — Expansion to Xbox

**Requested:** an Xbox version.

**Product rules**

- Lives on a new `xbox` branch. It must not depend on `windows`, which is
  WinUI 3 with LibVLC; that stack's support on Xbox is not established and
  needs investigation before commitment.
- Xbox developer credentials and store submission secrets stay in a protected
  environment on `xbox` (constraint 3).
- Input is controller-first: navigation, focus, and remote-style input must
  follow DESIGN.md and the platform's accessibility rules.

**Open questions**

- Which playback engine runs on Xbox.
- Whether the Xbox build reuses the Windows data layer (it cannot share code;
  the rules must be reimplemented natively, constraint 7).
- Store certification requirements and timing.

**Acceptance criteria**

- Branch added to AGENTS.md and README.md on `main` first.
- Xbox CI runs only on `xbox` and builds only the Xbox package.

---

## 8. Feature F9 — Background services while the device is locked

**Requested:** background services that pass data while the device is locked.

With files in a folder, two devices never need to be awake at the same time.
Each device writes and reads whenever its operating system gives it time, and
the folder holds the files in between. What remains is each system's rule for
background work:

- **Android (and `meta-horizon`).** A periodic WorkManager job with a network
  constraint (15 minutes is the shortest interval), plus a one-off job after
  local changes. Doze and app standby defer jobs to maintenance windows. No
  foreground service or battery-optimization exemption is needed.
  Credential-encrypted storage opens only after the first unlock since boot,
  so sync waits until then.
- **iOS and iPadOS.** A BackgroundTasks app-refresh task, which the system
  schedules based on how the app is used, plus a short background task that
  writes pending changes when the app leaves the foreground. The user's
  Background App Refresh setting and Low Power Mode can stop it. To work on a
  locked device, the sync store and the location's login must be readable
  after first unlock (`NSFileProtectionCompleteUntilFirstUserAuthentication`,
  and Keychain items with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`),
  not only while unlocked.
- **tvOS and visionOS.** Sync on launch and on return to the foreground, and
  background refresh where the system grants it, to be confirmed on hardware.
- **macOS and Windows.** Sync while the app runs. For folder locations the
  provider's own client (OneDrive, iCloud for Windows, Dropbox) uploads the
  file after the app wrote it, so a write before quitting is enough.
  Background sync while the app is closed is a later option.

**Product rules**

- Background sync is best-effort. Opening the app always syncs, and the UI
  shows the last-synced time.
- No data is written to unprotected storage while locked (H9).

**Acceptance criteria**

- A locked-device test matrix (an Android phone, an iPhone, an Android TV, an
  Apple TV, and a Quest, each locked or idle for at least an hour) records when
  background sync ran. It is documented before release.
- Sync resumes correctly after the OS suspends and restores the app.
- A change made on one device shows on another after one sync, at the latest
  when the second device opens the app.

---

## 9. Shared product rule changes and parity

Changes that affect more than one platform must name every affected branch
(constraint 7). The rules in §5 (locations, file format, and merge), §6
(handoff and pairing), and §4 (parental API) affect every platform that syncs
or enforces controls. Each branch implements and tests them natively:

| Rule | apple | android | meta-horizon | windows | web | xbox |
|---|---|---|---|---|---|---|
| Sync locations and file layout (§5.2) | Implement | Implement | Inherit from android | Implement (replaces the OneDrive replica) | N/A (no sync) | Implement |
| `updateTime` merge (§5.3) | Implement | Implement | Inherit from android | Implement | N/A | Implement |
| Handoff v2 and pairing (§6.2) | Implement (QR; keep the Apple-only handoff) | Implement (QR and code; keep v1 for Android TV) | Inherit from android | Implement (QR and code) | N/A | Implement |
| Google Drive across ecosystems (§6.4) | Implement | Implement | Inherit from android | Implement | N/A | Implement |
| Parental controls (§4) | Implement | Implement | Implement | Implement | N/A | Implement |
| Privacy disclosures (§6.6) | App Store label | Play Data safety | Horizon Store | Microsoft Store | Privacy policy, 8 languages | Microsoft Store |

`meta-horizon` is downstream of `android`, so it inherits android changes; it
does not duplicate them by hand. The Web branch remains static and
credential-free (constraint 8): it never syncs or stores history, and only
publishes the updated privacy policy. The file format, the merge rule, and the
handoff are specified once on `main` ([SYNC-PROTOCOL.md](SYNC-PROTOCOL.md))
with test vectors that every branch copies into its own native tests, naming
the `main` commit, as platform branches already do with `scenes` exports.

---

## 10. Decisions needed before implementation

**Decided 2026-11-02**

- **Sync model:** JSON files on storage the user owns; no Edendale service of
  any kind. The 2026-11-01 relay and the 2026-11-01 rule amendments are
  withdrawn (§2, §5).
- **Merge:** one `updateTime` field per record and per file; the newest
  record wins as a whole, with the tie-break, stamping, and guard rules of
  §5.3.
- **One sync location per device** at a time (§5.1).
- **One file per device** inside each space folder, never one shared file
  per space (§5.2).
- **Google Drive across ecosystems:** the handoff carries the login only
  within an ecosystem; across ecosystems the new device signs in to Google
  again, with the account pre-filled (§6.4). Every other provider's login
  transfers.
- **iCloud Drive as a location:** yes on iPhone, iPad, Mac, and Vision Pro
  (the app's own iCloud Drive folder, or any folder picked in Files), and on
  Windows through iCloud for Windows as a folder. Not on Apple TV, Android, or
  Quest, which have no iCloud Drive at all; Apple TV keeps its CloudKit sync
  with other Apple devices.
- **Bluetooth:** dropped from 27.1.

**Still open**

1. **OneDrive path:** the app folder (`Apps/Edendale/`, scope
   `Files.ReadWrite.AppFolder`, where the Windows replica already lives) or
   any path (`Files.ReadWrite`). Recommended: the app folder.
2. **Typed-code pairing on Apple** to non-Apple devices: skip for 27.1 and
   rely on QR plus a phone as the bridge (recommended), or adopt a PAKE
   library on Apple.
3. **Windows migration:** delete the old `user-media.json` and
   `watch-progress.json` after importing them, or leave them.
4. **Tombstone retention:** one year (proposed).
5. **Scopes and capabilities to change** (owner actions in each provider's
   console, then each branch):
   - Google: none. `drive.file` already allows creating and reading the
     app's own folder and files. `windows` still asks `drive.readonly` on its
     release branch; `windows-27.0.2` has `drive.file`.
   - OneDrive: add `Files.ReadWrite.AppFolder` (decision 1) as a delegated
     permission on the Entra app registration and in every client's scope
     list. Existing sign-ins must consent again; the apps prompt when the
     refresh returns the old scopes.
   - Dropbox: enable `files.content.write` on the app in the Dropbox App
     Console and add it to every client's scope list; existing sign-ins must
     authorize again.
   - iCloud Drive (`apple`): add iCloud Documents to the iCloud capability
     (`com.apple.developer.icloud-services` gains `CloudDocuments`, with
     `com.apple.developer.ubiquity-container-identifiers`), and
     `NSUbiquitousContainers` in Info.plist with
     `NSUbiquitousContainerIsDocumentScopePublic` set, so the folder appears in
     Files, Finder, and iCloud for Windows. The folder stays hidden until the
     first file is written.
   - Folders: Android needs a persisted Storage Access Framework permission
     for the picked tree; Windows keeps the picked folder in its future-access
     list; Apple keeps a security-scoped bookmark.
   - Servers: no scopes; the WebDAV, SMB, and SFTP connectors need write
     support (`PUT` and `MKCOL`, SMB create and write, SFTP write) on every
     branch that offers them as locations.
6. **Rollout order:** Android first, since it has no live sync today, then
   Apple and Windows; `meta-horizon` inherits Android.
7. **Legal and store review:** how Google Play's Data safety form and the App
   Store privacy label treat user-initiated transfers to the user's own cloud
   account. This document does not provide legal advice.
8. **Cleanup of the relay work** (owner): done on 2026-11-02 for the Worker
   (`edendale-sync` deleted), the `sync` and protocol-draft branches and
   worktrees (the `sync` branch was never on the remote). Still to do: revoke
   the Cloudflare API token and delete the GitHub `sync-production`
   environment that holds a copy of it.
9. **F3:** Choose the Xbox playback and UI stack, or defer F3 to 27.2.
10. **F1:** Choose whether visionOS is in 27.1.
11. **F2:** Commission a legal review for age-rating and kids-category rules.
12. **F9:** Accept best-effort background sync and set the locked-device test
    matrix (§8).

---

## 11. Verification (for the eventual implementation)

- Each platform branch documents its exact native build and test commands in
  its own README when its implementation lands.
- **File format and merge:** the test vectors in
  [SYNC-PROTOCOL.md](SYNC-PROTOCOL.md) (device files in, merged state out,
  covering ties, tombstones, the far-future guard, monotonic stamping, and
  malformed files) pass on every client. Property tests that any number of
  devices converge regardless of the order files are read.
- **Handoff:** the key-schedule vectors in SYNC-PROTOCOL.md (HKDF and HMAC
  outputs for the published X25519 vectors), the QR payload vector, RFC 8236's
  J-PAKE vectors where J-PAKE is used, and a wrong-code and a wrong-secret
  case that must both fail before any payload is sent.
- **Connectors:** writes against local servers in each branch's tests (a
  WebDAV server, an SMB share, an SFTP server), and the API providers against
  recorded responses.
- **Probes before implementation:** (1) a folder created under `drive.file` by
  the iOS client is listed by the Android and Windows clients of the same
  Cloud project; (2) the app's iCloud Drive folder appears in iCloud for
  Windows on a PC; (3) OneDrive's app folder works for a personal Microsoft
  account with `Files.ReadWrite.AppFolder`.
- **End to end, before release:** a manual run across Android, iPhone, Apple
  TV, Windows, and Quest, with Google Drive, OneDrive, Dropbox, a WebDAV
  share, and a picked folder as locations. Pair by QR code and by typed code,
  watch on one device and resume on another, remove a device, switch
  locations, and set up a device with a Google Drive location across
  ecosystems.
- **Log hygiene:** an automated test that no pairing code, QR secret, session
  key, refresh token, or password appears in log output (H10).
- **Parity:** each affected branch runs the same rule test cases, implemented
  natively.

This revision is documentation only. The code references in §5.3, §5.5, §6.2,
and §6.4 were checked against the `android`, `android-27.0.1`, `apple`,
`windows`, and `windows-27.0.2` branches on 2026-11-02; no platform build
applies.
