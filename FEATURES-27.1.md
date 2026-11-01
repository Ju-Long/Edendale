# Edendale 27.1 — Feature Plan

A product and engineering plan for the 27.1 release. It states each requested
feature, the platform branch that would own it, the rules it must follow, the
design and security review of cross-device sync, and the decisions needed
before implementation starts.

- **Location:** `main`, documentation only. Nothing in this document adds
  platform source, build files, or CI.
- **Status:** Proposal. No feature here is implemented.
- **Revised 2026-11-01:** cross-device sync moves from Bluetooth P2P to an
  end-to-end encrypted relay (§2, §5, §6, §8), and the project rules were
  amended the same day to allow it. The original request and its security
  review are kept for reference.
- **Basis:** [AGENTS.md](AGENTS.md) (hard constraints 1–8),
  [README.md](README.md) (branch model), and [DESIGN.md](DESIGN.md).

> Read **§1** first. The sync relay changed the project's privacy rules (§2),
> and the sync design differs from the original request for security reasons
> (§5, §6). Section §10 lists the decisions still needed.

---

## 1. Summary

| # | Feature | Owning branch(es) | Constraint impact | Readiness |
|---|---|---|---|---|
| F1 | VR immersive space with Blender environments | `scenes` (sources), `meta-horizon` (Quest), `apple` (visionOS, if scoped) | None; exports are committed per platform | Feasible; needs performance budget |
| F2 | Parental control API | Each platform, native, local-only | None if kept device-local | Feasible; legal review needed |
| F3 | Expansion to Xbox | New `xbox` branch | Adds a branch; needs a new branch table entry | Needs a decision on the stack |
| F4 | Cross-device history sync through an end-to-end encrypted relay (requested over Bluetooth P2P) | Each platform (native client); new `sync` branch (relay) | Constraints 4 and 6, CLAUDE.md, DESIGN.md, and README amended 2026-11-01 (§2) | Model and rules decided 2026-11-01; hosting pending (§5, §10) |
| F5 | Timestamp-priority merge | Each platform, native | Touches constraint 7 (parity) | **Redesign required** (§5.3); existing merge code needs changes |
| F6 | Pairing code and keys (requested: PIN-derived key, rotated by time of day) | Each platform, native | Touches constraint 3 (secrets) | **Redesign required** (§6) |
| F7 | Security review of the method | Documented here | — | Done; revised for the relay (§6) |
| F8 | Account sign-in across ecosystem via the same method | Each platform | Resolved without an Edendale account: joining a sync space (§2) | Decided 2026-11-01 (§6.4) |
| F9 | Background services that pass data while locked | Each platform, native | OS-limited | Simpler with a relay; best-effort (§8) |

---

## 2. Conflicts with existing hard constraints

These must be resolved before implementation. They are not implementation
details.

1. **The sync relay needed rule changes (applied 2026-11-01).** On 2026-11-01
   the plan moved sync from Bluetooth to an Edendale-operated relay that
   stores only end-to-end encrypted data (§5, §6). The relay has no sign-in
   and cannot read what it stores, but it is still an Edendale service that
   receives data from the apps, which the earlier rules excluded. The wording
   now on `main`:
   - **AGENTS.md constraint 4:** "Do not add analytics or an Edendale account
     (a sign-in identity such as an email address or password). Network access
     is limited to documented metadata, playback, user-controlled sync/storage
     services, and the optional Edendale sync relay, which stores only
     end-to-end encrypted data."
   - **AGENTS.md constraint 6:** "...use a documented user-controlled service
     or the end-to-end encrypted sync relay."
   - **DESIGN.md product behavior:** portable watch state "syncs only through
     a documented user-controlled service or the end-to-end encrypted sync
     relay."
   - **CLAUDE.md:** "There is no shared executable runtime or generated runtime
     bridge. The only backend is the optional sync relay on `sync`, which
     stores only ciphertext and which no platform builds against."
   - **README.md principles:** "Private by design" is unchanged. "Your data
     stays yours" becomes "local libraries remain local; sync uses a
     user-controlled platform service or the end-to-end encrypted Edendale
     relay, which cannot read what it stores."
   - **Branch tables** in AGENTS.md and README.md list `sync`, each with a
     short note that it is a service, not a platform.

   Still to do:
   - **Platform branches:** carry the amended universal files to each
     platform branch, as with any universal documentation change.
   - **Privacy policy** (`web`, 8 languages): it says Edendale "has no account
     system and no servers of its own". It must describe the relay and what
     the relay can see (§6.6), in the release that first offers sync.
   - **Store disclosures**, in that same release: the App Store privacy label,
     Google Play Data safety, the Meta Horizon Store, and the Microsoft Store.

   Constraints 1, 2, 5, and 8 need no change. Apps talk to the relay over a
   documented protocol, as they already do with TMDB; no platform links relay
   code or a generated client; no CI checks out another branch; sync stays off
   the import path; and the relay is a separate deployment, not part of the
   static website.

2. **F8 is resolved as sync spaces (decided 2026-11-01).** A sync space is a
   set of devices that share a random key. Joining one is the only sign-in,
   and the relay holds nothing it can read (§6.4). Accounts that keep readable
   data on a server were considered and rejected: a breach would expose every
   user's viewing history, and they need email sign-in, password resets, and
   an identity held by Edendale.

3. **F3 and the relay add branches.** `sync` was added to the branch tables in
   [AGENTS.md](AGENTS.md) and [README.md](README.md) on 2026-11-01. `xbox` must
   be added the same way, on `main`, before that branch is created.

4. **F4 and F6 touch constraint 6** ("separate local and portable data"). Synced
   watch state must stay independent of the local library. The sync design in
   §5 keeps that separation: only watch records move, never file paths or
   library records.

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

- Controls are **device-local** and are not synced. The sync space (§5)
  carries watch state only; syncing controls would let any device in the
  space change them. A parent configures each device.
- Rules the API can express:
  - Maximum content rating, from TMDB certification data already fetched by
    the user's enrichment. Titles without a certification are treated as
    restricted by default.
  - Hide titles, and block trailers and external links.
  - Lock settings and library edits behind a parental PIN.
- The API is a native interface on each platform (for example a Kotlin
  interface on Android, a Swift protocol on Apple, a C# interface on Windows).
  It is not a shared library.
- The parental PIN is **separate** from sync pairing codes and the recovery
  key (§6). None is derived from another.
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

## 5. Features F4 and F5 — Encrypted relay sync and timestamp priority

**Requested:** sync watch history between devices over Bluetooth P2P, with an
8-digit PIN that yields a symmetric key, and priority by the higher Unix
timestamp.

**Revised 2026-11-01:** devices sync through a small relay run by Edendale that
stores only end-to-end encrypted data. Bluetooth is dropped from 27.1. The
timestamp priority (§5.3), the short pairing code, and the security review (§6)
carry over.

Why a relay rather than Bluetooth:

- Bluetooth sync needs both devices near each other and awake at the same
  time, which iOS does not allow reliably in the background (§8). The relay
  holds changes until each device next connects: watch on the phone on Monday,
  and the TV picks up the position on Friday.
- TVs, Quest headsets, and PCs differ widely in Bluetooth support. All of them
  have a network connection.
- It fills the real gap. Today watch progress syncs only inside one ecosystem
  (iCloud on Apple, a OneDrive folder on Windows, and nothing live on
  Android); only TMDB favourites, watchlist, and ratings cross ecosystems.

### 5.1 Product rules

- Sync moves **watch state only**: progress, completion, last-watched time,
  favourite, watchlist, rating, and review text once a platform has reviews.
  File paths, library records, access grants, TMDB sessions, server and
  cloud-storage logins, and parental settings never leave the device
  (constraint 6).
- Records are keyed by media type and TMDB ID, as every platform's store
  already is, so they never depend on local files.
- Sync is **opt-in and off by default**. Turning it on creates a sync space or
  joins one (§6.1). There is no email address, password, or profile.
- A sync space holds one person's watch state, and every device in it sees the
  same history. Several spaces on one shared device (profiles) are out of scope
  for 27.1.
- The relay stores and returns ciphertext only. It never reads, validates, or
  merges records; each device merges for itself (§5.3).
- Every device keeps a full local copy. Sync never blocks playback, import, or
  classification (constraint 5). Offline, or with the relay down, the app
  works as it does today and uploads later.
- The UI shows when the device last synced and never claims instant sync.

### 5.2 Relay protocol

For each sync space, the relay keeps an append-only log of encrypted batches
and the latest encrypted snapshot. It also runs short-lived pairing mailboxes.

| Operation | Purpose |
|---|---|
| Create space | Registers a space with its first device's public keys and the recovery public key (§6.4). Rate-limited per network address. |
| Append | Adds one encrypted batch to the log and returns its sequence number. |
| Read | Returns the batches after the device's cursor, preceded by the latest snapshot if the cursor is older than it. |
| Snapshot | Stores an encrypted snapshot of the merged state at a sequence number, so the relay can drop older batches. |
| Devices | Adds a device (a member does this during pairing), removes one, and stores key envelopes for a new key epoch (§6.3). |
| Delete space | Deletes the space and everything stored for it. |
| Pairing mailbox | Carries the joining exchange (§6.1). Expires after 2 minutes or one attempt. |

- **Device authentication.** Each device creates its own signing key pair, and
  the relay stores only public keys. A device signs a fresh challenge from the
  relay to get a short-lived session token. There are no passwords and no
  long-lived tokens.
- **Batching.** Local changes are debounced (for example 10 seconds after the
  last change) and sent at once when playback stops or the app goes to the
  background. Progress during playback is coalesced: only the latest position
  is uploaded.
- **When devices sync.** On launch, on return to the foreground, after local
  changes, and in the background where the OS allows (§8). Continue Watching
  shows local data at once and updates in place when the pull returns.
- **No push notifications in 27.1.** Push would need the relay to hold Apple
  and Google push tokens and credentials. Live updates while two devices have
  the app open (for example over a WebSocket) are a later option.
- **Limits.** A maximum batch size (for example 64 KB), a maximum space size
  (for example 10 MB, far more than years of history), and per-device request
  rates. A device that reaches the space limit writes a snapshot and retries.
- **Specification.** The wire format, envelopes, and test vectors go in a
  `SYNC-PROTOCOL.md` on `main` before any branch implements them. Each branch
  copies the test vectors into its own native tests and names the `main`
  commit, as platform branches already do with `scenes` exports
  (constraint 7).

### 5.3 Priority by timestamp — and its risk

The requested rule is: the higher the Unix timestamp, the higher the priority,
so the newest write wins. This is last-writer-wins (LWW). It is simple, but it
has two problems:

1. **It trusts the clock.** A device with a clock set far in the future wins
   every conflict, and its values overwrite everyone else's progress until the
   clock catches up. A device with a clock set to 1970 loses every conflict.
2. **It loses information.** LWW on a whole record discards the other device's
   change. For example, Device A sets a rating while Device B updates progress;
   under whole-record LWW, one of those edits is lost.

**Recommended rule (replaces the plain rule):**

- Each value carries `(timestamp, device_id)`. The higher timestamp wins; equal
  timestamps tie-break on `device_id`, so every device picks the same winner.
- The timestamp is a **hybrid logical clock (HLC)**: the device's UTC clock in
  milliseconds, never lower than any timestamp the device has already seen,
  plus a counter for writes in the same millisecond. It keeps the requested
  rule and fixes the common handoff: a TV whose clock runs a few minutes slow
  still orders the position it writes after the phone's position it just
  received.
- **Merge per register, not per record.** Progress (position, watched seconds,
  completion, and last-watched time) is one register, because those values
  describe one viewing. Favourite, watchlist, rating, and review are each their
  own register. Two devices can change different registers without losing
  either change.
- **Hold, don't drop.** A write stamped more than 5 minutes ahead of the
  receiving device's clock is held, and applied once that clock catches up;
  the device does not advance its own clock from it. Dropping it would leave
  devices with different results, because each judges "ahead" by its own
  clock. Held writes travel inside snapshots, so compaction never loses them.
  Timestamps before 2000-01-01 are invalid on every device. The earlier rule
  about a title's first-release date is dropped: devices can hold different
  TMDB data, so they would not reject the same writes.
- **Clearing is a write.** Removing a rating, a favourite, or progress writes
  an empty value with a new timestamp. The empty value is kept (a few bytes),
  so an older copy on another device cannot bring the old value back.
- A device never re-stamps a value it received, by any path (§5.5), so one
  write that arrives twice is still one write.
- Store timestamps as **UTC milliseconds in a 64-bit integer**, plus the HLC
  counter. Seconds in a 32-bit integer overflow in 2038.

This keeps the user's requested priority (higher timestamp wins) and removes
the two defects above. The merge is idempotent and independent of order, so
devices converge whatever order batches arrive in, and a snapshot is simply
the merged state.

**Existing merge code to change.** The current merges were written for two
replicas and a pending push. Each branch changes its own:

| Branch | Code today | Change |
|---|---|---|
| `android` | `mergeUserMedia` and `mergeWatchProgress` in `src/main/kotlin/com/babasama/edendale/domain/UserMediaMerge.kt` | On a timestamp tie, the side with a pending push (its dirty flag) wins, otherwise the first argument. Dirty flags are local, so two devices can pick different winners: tie-break on device ID instead. Records with nothing set are pruned (`hasState`), which discards the time of the clear: keep them. |
| `windows` | `UserMediaMerger` in `Edendale.Windows/Core/UserMediaMerger.cs` | The same tie and pruning behavior (`Winner`, `HasState`), and the same change. |
| `apple` | `CDUserMedia` in `Shared/WatchProgress.xcdatamodeld` | One `updatedAt` covers favourite, watchlist, and rating. Add a timestamp per register; the change is additive and CloudKit-compatible. |
| Every client | Watch-progress and user-media stores | Store the HLC and the writing device's ID with every register. |

### 5.4 Sync flow (summary)

1. A device uploads its pending writes as one encrypted batch and records the
   sequence number it gets back.
2. It reads every batch after its cursor (or the latest snapshot, if it has
   fallen behind), decrypts them, and merges per register (§5.3).
3. It advances its cursor. Sync is idempotent: a batch read twice or replayed
   changes nothing.
4. From time to time (for example every 500 batches, and after every key
   change), a device writes a snapshot of the merged state, and the relay
   drops the batches it covers.
5. Each device checks that its own acknowledged batches are still in the log
   or covered by a snapshot. If they are not, it shows a sync warning and
   uploads them again (S2 in §6).

### 5.5 Coexistence with today's sync

- **iCloud (Apple) and the OneDrive folder (Windows)** keep working. They carry
  the same timestamps and device IDs as the relay, which needs the HLC and
  device-ID fields in CloudKit records and in the OneDrive JSON files (an
  additive change). A device uploads every change it applies that did not come
  from the relay, with its original stamp; duplicates are harmless because the
  merge is idempotent. An iPad that is not in the space still reaches it
  through an iPhone that is.
- **TMDB account sync** (favourites, watchlist, and ratings) stays a separate,
  optional service. TMDB keeps no per-field times, so a value pulled from TMDB
  that differs from local state becomes a new local write, stamped when it is
  pulled.
- **Backups and device transfer.** The device ID, device keys, and space key
  must never be restored onto another device: two devices with one identity
  break the tie-break and the key epochs. Android adds them to the
  `cloud-backup` and `device-transfer` exclusions in `data_extraction_rules.xml`
  and to `backup_rules.xml`; Apple stores them as this-device-only Keychain
  items; Windows keeps them out of the OneDrive folder. A restored device joins
  the space again.

### 5.6 The relay (`sync` branch)

- **Branch.** A new `sync` branch holds the relay's source, tests, CI, and
  deployment. No client code lives there, and no platform branch builds against
  it (constraints 1 and 2). It is already listed in the branch tables on
  `main` (§2).
- **Hosting (recommended): Cloudflare Workers, with one Durable Object per
  sync space.** Each space's log is a small SQLite database inside its own
  Durable Object, so spaces are isolated and there is no shared database to
  breach. There are no servers to patch, and TLS and DDoS protection are
  included. Watch data is small (a heavy viewer's encrypted history is a few
  MB); a rough estimate is close to $0 during a beta and tens of US dollars a
  month at tens of thousands of devices. Check the provider's current pricing
  before committing.
- **Alternative:** one small binary with SQLite on a VPS. It is easier for
  others to self-host, but the operator patches the server and manages TLS and
  backups.
- **Self-hosting.** The relay is open source like the apps. Letting a user
  point the app at their own relay is a later option (§10).
- **Deployment secrets.** The hosting API token lives only in the `sync`
  branch's protected CI environment, with the least privilege the provider
  allows (constraint 3). The apps hold no relay secret: the relay URL is
  public, and devices authenticate with their own keys.
- **Retention and deletion.** "Delete sync data" in the app deletes the space
  and everything stored for it at once. "Remove this device" revokes only that
  device. Spaces untouched for 12 months are deleted automatically; a device
  that returns afterwards can start a new space from its local copy.
- **No request logging.** The relay logs no request bodies and stores no
  network addresses. It uses addresses only in flight, for rate limits, and
  keeps aggregate counters (requests, errors, storage) for operations.
- **Operations.** Uptime monitoring, a spending cap or alert on the hosting
  account, an abuse contact, and a written process for legal requests. The
  relay can hand over only ciphertext and the metadata in §6.6.

---

## 6. Feature F6 — Pairing and keys: security review

**Requested:** one party generates an 8-digit PIN; the PIN generates a
symmetric key that is saved permanently and rotated by time of day.

The security review is the reason §5 and this section differ from the request.
Since the 2026-11-01 revision, pairing messages and the encrypted history pass
through the relay, so the relay is treated as untrusted throughout. Severity
assumes an attacker who can observe or alter traffic between devices and the
relay, including the relay's operator, and who may run a malicious device.

| # | Risk | Why it matters | Severity | Mitigation |
|---|---|---|---|---|
| R1 | **Low-entropy secret.** 8 digits is about 26.6 bits (10⁸ values). | An attacker who captures any verifier or ciphertext tied only to the code can test every code offline in minutes to hours. Argon2id slows this but does not remove it. | Critical | Never use the code as a key or a verifier. Use a password-authenticated key exchange (PAKE, §6.1): an attacker then gets **one guess per code**. Joining by QR code avoids the short secret entirely. |
| R2 | **Offline guessing from a transcript.** | The relay sees every pairing transcript, so R1 would apply to anything it records. | Critical | Same as R1: PAKE makes the transcript useless for offline guessing. |
| R3 | **Active man-in-the-middle during pairing.** | The relay, or anyone between a device and the relay, can try to sit between the two devices. | High | CPace with explicit key confirmation, bound to the mailbox and both devices' public keys. The QR code carries the new device's public key, so the relay cannot swap it. |
| R4 | **Rotation adds no secret.** Rotation by time of day uses a public input (the date and hour). | An attacker who knows the PIN can compute every future and past key. Rotation does not protect against PIN compromise. | High | The space key is random (256-bit) and never derived from the code. Time-of-day rotation is dropped; the key moves to a new **epoch** when a device is removed (§6.3). |
| R5 | **"Saved permanently" plus PIN-derived means one compromise is total.** | If the PIN or the stored key leaks, every past and future sync is readable. | High | The stored space key is the only long-lived shared secret, and it is random. Removing a device starts a new epoch. The code is discarded after pairing and never stored. |
| R6 | **Clock dependence.** Time-of-day rotation needs both devices to agree on the window. | Clock skew or time-zone differences cause sync failures. | Resolved | Keys no longer depend on time. Clocks only order merges, within the bounds in §5.3. |
| R7 | **Nonce reuse.** A time-window counter that resets reuses AES-GCM nonces. | Nonce reuse under AES-GCM leaks plaintext and the authentication key. | Critical | XChaCha20-Poly1305 with a random 192-bit nonce per batch. Never a counter that can reset. |
| R8 | **No authentication of the payload.** | Without an AEAD tag, the relay could alter records (for example, mark a title complete). | High | AEAD with associated data that binds the space ID, key epoch, uploading device ID, and batch ID (§6.2). |
| R9 | **Replay.** | The relay can replay, reorder, or duplicate batches. | Medium | Batch IDs are unique, and the per-register merge is idempotent, so a replayed batch changes nothing. |
| R10 | **Timestamp forgery under LWW.** Any member device can write a huge timestamp and win every conflict. | Combined with §5.3's concern, a malicious *member* can overwrite the whole space. | High | Timestamp bounds (§5.3) and per-device rate limits at the relay. Members are trusted by design; the rule protects against accidents and buggy clocks, not a malicious member. Document that. |
| R11 | **Presence tracking over BLE.** | Bluetooth advertisements let observers follow a device. | Resolved | No Bluetooth. Network metadata at the relay takes its place (S1). |
| R12 | **Shoulder-surfing the pairing code.** | The code is shown on one screen and typed or scanned on another. | Medium | Show the code and QR code only on the pairing screen; they expire in 2 minutes and allow one attempt. |
| R13 | **Denial of service.** | Floods drain battery or run up hosting costs. | Medium | Moves to the relay (S4, S5). Devices back off after failures. |
| R14 | **Plaintext exposure at rest.** Background sync may write decrypted history to unprotected storage. | Watch history is personal data. | High | Keep decrypted data only in the platform's protected storage (iOS Data Protection; Android credential-encrypted storage). Never write plaintext to caches or logs. Background sync on a locked iPhone needs "until first unlock" protection (§8). |
| R15 | **Secrets in logs or crash reports.** | Pairing codes, recovery keys, space keys, and device keys must never be logged (constraint 3). | High | Redact at the logging layer. Add a test that asserts no key material reaches the log sink. The relay logs no request bodies. |
| R16 | **Custom cryptography.** | Hand-written protocols and primitives are a common source of failure, and open source makes review easier but does not make a design safe. | High | Use audited native libraries: libsodium on every platform, or CryptoKit where it has the primitive. Build CPace from libsodium's ristretto255 functions and check it against the CFRG specification's test vectors. Keep the protocol small and published on `main`. |
| R17 | **Open-source assumption.** | Because the code is public, relay included, the only secrets are the random keys and the pairing code. | — | Design principle: assume the protocol and the relay are fully known to the attacker. Security rests on the mitigations above, not on secrecy of the design. |

**Risks the relay adds**

| # | Risk | Why it matters | Severity | Mitigation |
|---|---|---|---|---|
| S1 | **Metadata at the relay.** | The relay sees space IDs, random device IDs, batch sizes and times, and network addresses. Upload times show roughly when someone watches. | Medium | Store no network addresses and log no requests. Pad batches to fixed size buckets and debounce uploads. Disclose what remains (§6.6). |
| S2 | **Compromised or malicious relay.** | It holds every space's ciphertext. | Medium | It cannot read or forge records (R8). It can withhold, delay, or roll back batches; each device checks that its own acknowledged batches are still there and warns if not (§5.4). A rollback only delays convergence, and local copies are never lost. |
| S3 | **Relay adds a device of its own.** | A relay that registers its own key could try to obtain the space key. | High | The space key reaches a new device only through the pairing exchange (§6.1), which the relay cannot complete alone. The member list lives inside the encrypted state, so every device can show it, and a device the relay added appears in no member's list. |
| S4 | **Abuse as free anonymous storage.** | Anyone can create a space and store ciphertext. | Medium | A small per-space limit (§5.2), rate-limited space creation, no public read access, expiry after 12 months unused, and an abuse contact. |
| S5 | **Denial of service and cost.** | Floods can take the relay down or run up the hosting bill. | Medium | The provider's DDoS protection, per-address and per-device rate limits, and a spending cap or alert. When the relay is down, apps keep working locally. |
| S6 | **Leaked deployment credential.** | A stolen hosting token lets an attacker deploy a modified relay that records metadata or breaks sync. It still cannot read history. | High | The token lives only in the `sync` branch's protected environment, with least privilege, and the hosting account uses two-factor sign-in. |
| S7 | **Losing every device.** | No one, Edendale included, can decrypt the space. | Medium | The recovery key (§6.4). The app says plainly that without it the history is gone. |
| S8 | **Stolen device.** | An unlocked stolen device can read the space. | Medium | Remove it from another device: the relay revokes it and the space moves to a new epoch (§6.3). It keeps what it had already downloaded. |
| S9 | **Legal requests.** | The operator can be compelled to hand over what it holds. | Low | It holds only ciphertext and the metadata in §6.6. The privacy policy says so. |

### 6.1 Creating and joining a sync space

**Creating.** The first device generates a random space ID, a random 256-bit
space key (epoch 1), its own device key pairs, and a recovery key (§6.4). It
registers the space with its public keys and shows the recovery key once.

**Joining by QR code (preferred).**

1. The new device generates its key pairs, opens a pairing mailbox on the
   relay, and shows a QR code that holds the mailbox ID and its public keys.
2. A member device scans the code and asks the user to confirm ("Add Living
   room TV?").
3. The member registers the new device's signing key with the relay and posts
   the space ID, current epoch, and space key to the mailbox, encrypted to the
   public key from the QR code.
4. The new device decrypts it and syncs. The relay sees only ciphertext and
   cannot substitute its own key, because the key came from the QR code.

**Joining by code (no camera).** The same flow, with a typed code in place of
the QR code:

1. The new device shows a code such as `52-4821-7730`: a short mailbox number
   from the relay, then an 8-digit secret the relay never sees.
2. The user types it on a member device. Both devices run **CPace** through the
   mailbox, with the 8-digit secret as the password, bound to the mailbox
   number and both devices' public keys, and exchange key-confirmation MACs.
3. On success, the member registers the new device and sends the space key
   encrypted under the CPace session key. A wrong code produces a mismatch,
   not a leak.
4. Each code allows one attempt and expires after 2 minutes. A failure is
   shown on both devices. After 5 failed codes in a row, the new device waits
   longer before it offers another.

In both flows the member device decides who joins, and the code is discarded
after use. The relay rate-limits mailbox creation per network address.

### 6.2 Encryption and keys

- Device signing keys are Ed25519; device encryption keys are X25519. Each
  device creates its own and never exports the private halves.
- Subkeys for batches, snapshots, and the member list =
  HKDF-SHA256(the epoch's space key, salt = space ID, info = purpose ‖ epoch).
- Batches and snapshots use XChaCha20-Poly1305 with a random 192-bit nonce.
  AAD = space ID ‖ epoch ‖ uploading device ID ‖ batch ID. Batches are padded
  to fixed size buckets (S1).
- Keys stay on the device and out of backups (§5.5): Keychain items available
  after first unlock, this device only (Apple); encrypted with an Android
  Keystore key (Android); DPAPI-protected local app data, outside the OneDrive
  folder (Windows).

### 6.3 Removing a device: key epochs

- Any member device can remove another, for example a lost phone. The relay
  revokes the removed device at once, so it can no longer read or append.
- The removing device then generates the next epoch's space key, encrypts it
  to each remaining device's public key and to the recovery public key, stores
  those envelopes at the relay, and writes a fresh snapshot under the new
  epoch. The other devices pick up their envelopes on their next sync.
- The relay accepts one new epoch at a time. A device that loses that race
  fetches the new envelope and retries.
- Batches from older epochs are dropped once the new snapshot exists, so a
  device that joins later needs only the current key.
- This replaces the requested time-of-day rotation. A user can also start a
  new epoch on demand ("Reset sync keys").

### 6.4 Recovery and sign-in (F8)

- Joining a sync space is the only "sign-in" (F8). There is no Edendale
  account: no email address, password, or profile, and the relay holds nothing
  it can read (§2).
- When the space is created, the app shows a **recovery key** once: 128 random
  bits, shown as 26 letters and digits in groups, to write down or keep in a
  password manager. Neither the app nor the relay stores it.
- The recovery key derives a recovery key pair. Devices keep only the public
  half, and every new epoch's key is also encrypted to it (§6.3). The relay
  finds the space by a value derived from the recovery key, so the user needs
  nothing else. With it, a new device can authenticate, open the latest
  envelope, and join, even when every other device is gone.
- Any member device can replace the recovery key; the old one stops working.
- Losing every device **and** the recovery key loses the space. The app states
  this when sync is turned on, before the user can skip saving the key.

### 6.5 What the requested design gets right

- A short, human-enterable code for pairing is practical. It remains for
  devices that cannot scan a QR code.
- Changing keys over time is good hygiene once the key is secret. Key epochs
  keep the idea and tie it to the event that matters: a device leaving.
- "Saved permanently" is acceptable once the stored secret is random and
  device-bound, not derived from the code.

### 6.6 What the relay can see

- **Can see:** that a space exists and its random ID; the number of devices
  and their random IDs; when each device syncs; padded batch sizes and total
  stored size; and network addresses while a request is in flight.
- **Cannot see:** titles, TMDB IDs, progress, ratings, reviews, device names,
  the member list, or anything else inside batches, snapshots, and envelopes.

This is the summary the privacy policy and store disclosures draw on (§2).
Google Play's Data safety rules do not count end-to-end encrypted data as
collected; how the metadata above is declared, and how Apple's privacy label
treats it, is part of the review in §10.

### 6.7 Residual risks the design accepts

- A member of a sync space is trusted. A malicious member can forge records
  within the space (R10).
- If a device is stolen **unlocked**, its keys and history are exposed until
  another device removes it, and it keeps what it has already downloaded.
- The relay learns the metadata in §6.6.
- A device whose clock ran far ahead keeps a future timestamp after its clock
  is fixed, so other devices hold its writes until real time catches up
  (§5.3). The app shows a sync warning when its own clock is more than 5
  minutes behind its last timestamp.
- If the relay stops running, sync stops. Local data is unaffected.

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

With a relay, two devices no longer need to be awake at the same time. Each
device uploads and fetches whenever its operating system gives it time, and
the relay holds changes in between. What remains is each system's rule for
background network work:

- **Android (and `meta-horizon`).** A periodic WorkManager job with a network
  constraint (15 minutes is the shortest interval), plus a one-off job after
  local changes. Doze and app standby defer jobs to maintenance windows. No
  foreground service or battery-optimization exemption is needed.
  Credential-encrypted storage opens only after the first unlock since boot,
  so sync waits until then.
- **iOS and iPadOS.** A BackgroundTasks app-refresh task, which the system
  schedules based on how the app is used, plus a short background task that
  uploads pending changes when the app leaves the foreground. The user's
  Background App Refresh setting and Low Power Mode can stop it. To work on a
  locked device, the sync store and keys must be readable after first unlock
  (`NSFileProtectionCompleteUntilFirstUserAuthentication`, and Keychain items
  with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), not only while
  unlocked.
- **tvOS and visionOS.** Sync on launch and on return to the foreground, and
  background refresh where the system grants it, to be confirmed on hardware.
- **macOS and Windows.** Sync while the app runs. Background sync while it is
  closed is a later option.

**Product rules**

- Background sync is best-effort. Opening the app always syncs, and the UI
  shows the last-synced time.
- No data is written to unprotected storage while locked (R14).

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
(constraint 7). The rules in §5 (sync protocol and merge), §6 (pairing and
keys), and §4 (parental API) affect every platform that syncs or enforces
controls. Each branch implements and tests them natively:

| Rule | apple | android | meta-horizon | windows | web | xbox | sync |
|---|---|---|---|---|---|---|---|
| Relay protocol (§5.2) | Implement | Implement | Inherit from android | Implement | N/A (no sync) | Implement | Implement the relay side |
| Per-register merge (§5.3) | Implement | Implement | Inherit from android | Implement | N/A | Implement | N/A (cannot read data) |
| Pairing, keys, recovery (§6) | Implement | Implement | Inherit from android | Implement | N/A | Implement | Mailbox and envelope storage only |
| Parental controls (§4) | Implement | Implement | Implement | Implement | N/A | Implement | N/A |
| Privacy disclosures (§2) | App Store label | Play Data safety | Horizon Store | Microsoft Store | Privacy policy, 8 languages | Microsoft Store | Document retention and logging |

`meta-horizon` is downstream of `android`, so it inherits android changes; it
does not duplicate them by hand. The Web branch remains static and
credential-free (constraint 8): it never syncs or stores history, and only
publishes the updated privacy policy. `sync` is the relay, not a client: it
never sees plaintext and shares no code with any client (constraint 1). The
protocol and its test vectors are specified once on `main` (§5.2) and
implemented natively everywhere.

---

## 10. Decisions needed before implementation

1. **Sync model — decided 2026-11-01:** end-to-end encrypted sync spaces on an
   Edendale-operated relay, not accounts with readable data on a server (§2,
   §5, §6).
2. **Rules — decided 2026-11-01:** the §2 wording is applied to AGENTS.md,
   CLAUDE.md, DESIGN.md, and README.md on `main`, and `sync` is in the branch
   tables. It still has to reach the platform branches.
3. **Bluetooth:** drop it from 27.1 (recommended), and revisit only if offline
   device-to-device sync is wanted later.
4. **Hosting:** Cloudflare Workers with Durable Objects (recommended) or a VPS;
   and who owns the hosting account and the relay's domain.
5. **F4/F6:** approve QR and CPace pairing through the relay, random space keys
   with epochs, and the recovery key (§6.1–§6.4) in place of the PIN-derived
   key.
6. **F5:** approve the per-register HLC merge with a device-ID tie-break, and
   the changes to existing merge code (§5.3).
7. **Recovery key:** required before sync turns on, or skippable after a
   warning.
8. **Rollout order:** Android first, since it has no live sync today, then
   Apple and Windows; `meta-horizon` inherits Android.
9. **Self-hosting:** whether 27.1 lets a user enter their own relay address, or
   that waits for a later release.
10. **Legal and store review** of the relay: the metadata it processes
    (network addresses are personal data in some jurisdictions), retention,
    the hosting provider's data-processing terms, and each store's disclosure.
    This document does not provide legal advice.
11. **F3:** Choose the Xbox playback and UI stack, or defer F3 to 27.2.
12. **F1:** Choose whether visionOS is in 27.1.
13. **F2:** Commission a legal review for age-rating and kids-category rules.
14. **F9:** Accept best-effort background sync and set the locked-device test
    matrix (§8).

Items 3, 5, and 6 change the request, so they need an explicit yes before any
branch implements them.

---

## 11. Verification (for the eventual implementation)

- Each platform branch, and `sync`, documents its exact native build and test
  commands in its own README when its implementation lands.
- Protocol: test vectors in `SYNC-PROTOCOL.md` for CPace transcripts, HKDF
  subkeys, batch encryption with tampered AAD and tampered ciphertext (both
  must fail), key envelopes, and recovery-key derivation. Every client and the
  relay pass the same vectors.
- Merge: property tests that any number of replicas converge regardless of
  batch order, duplicates, and snapshots, including ties, clock skew, held
  future timestamps, and cleared values.
- Clients: each branch tests its sync client against an in-memory fake relay
  written natively in its own tests, so no CI checks out `sync` (constraint 2).
- Relay: tests that a non-member cannot read or append, that a removed device
  is refused at once, that limits hold, that deleting a space removes
  everything, and that no request body or network address is logged.
- End to end, before release: a manual run across Android, iPhone, Apple TV,
  Windows, and Quest against a staging relay. Pair by QR code and by typed
  code, watch on one device and resume on another, remove a device, and
  recover with the recovery key.
- Log hygiene: an automated test that no pairing code, recovery key, space key,
  or device key appears in log output (R15).
- Parity: each affected branch runs the same rule test cases, implemented
  natively.

This revision is documentation only. The code references in §5.3 and §5.5
were checked against the `android`, `apple`, and `windows` branches on
2026-11-01; no platform build applies.
