# Edendale 27.1 — Feature Plan

A product and engineering plan for the 27.1 release. It states each requested
feature, the platform branch that would own it, the rules it must follow, the
security review of the proposed PIN-based sync, and the decisions needed before
implementation starts.

- **Branch:** `claude/v27-1-features-hteve3`, a documentation-only branch.
  Nothing in this document adds platform source, build files, or CI.
- **Status:** Proposal. No feature here is implemented.
- **Basis:** [AGENTS.md](AGENTS.md) (hard constraints 1–8),
  [README.md](README.md) (branch model), and [DESIGN.md](DESIGN.md).

> Read **§1** first. Two requested items conflict with existing hard
> constraints, and the sync design as written has security defects that change
> what should be built. Section §10 lists the decisions needed.

---

## 1. Summary

| # | Feature | Owning branch(es) | Constraint impact | Readiness |
|---|---|---|---|---|
| F1 | VR immersive space with Blender environments | `scenes` (sources), `meta-horizon` (Quest), `apple` (visionOS, if scoped) | None; exports are committed per platform | Feasible; needs performance budget |
| F2 | Parental control API | Each platform, native, local-only | None if kept device-local | Feasible; legal review needed |
| F3 | Expansion to Xbox | New `xbox` branch | Adds a branch; needs a new branch table entry | Needs a decision on the stack |
| F4 | Bluetooth P2P history sync from one PIN | Each platform, native | Touches constraint 6 (sync service) | **Redesign required** (§5, §6) |
| F5 | Timestamp-priority merge | Each platform, native | Touches constraint 7 (parity) | **Redesign required** (§5.3) |
| F6 | PIN-derived key, saved and rotated by time of day | Each platform, native | Touches constraint 3 (secrets) | **Redesign required** (§6) |
| F7 | Security review of the method | Documented here | — | Done (§6) |
| F8 | Account sign-in across ecosystem via the same method | Each platform | **Conflicts with constraint 4 ("no Edendale account")** | Blocked pending a decision (§4) |
| F9 | Background services that pass data while locked | Each platform, native | OS-limited, especially on iOS | Partly feasible; hardware validation needed (§8) |

---

## 2. Conflicts with existing hard constraints

These must be resolved before implementation. They are not implementation
details.

1. **F8 conflicts with "no Edendale account" (AGENTS.md constraint 4).** A
   sign-in that works across Android and iOS is an account in every practical
   sense. Two options:
   - **A. Keep the rule and reframe.** Call it a *sync group*: a set of devices
     paired by PIN, with no server, no profile, and no Edendale-held identity.
     This matches the rule's intent. The constraint text should still be
     amended so it does not read as forbidding the feature.
   - **B. Change the rule.** Allow an account. This would require a privacy
     policy change, a server (which Web's constraint 8 and the static model
     argue against), and a new credential store on every platform. Not
     recommended for 27.1.

   **Recommendation: A.** Pairing creates a sync group. There is no sign-in to
   an Edendale service and no cross-device identity held by Edendale.

2. **F3 introduces a new branch.** `xbox` must be added to the branch table in
   [AGENTS.md](AGENTS.md) and [README.md](README.md). This is a documentation
   change to `main` and must land there before the branch is created.

3. **F4 and F6 touch constraint 6** ("separate local and portable data"). Synced
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

- Controls are **device-local**. There is no Edendale account and no server,
  so a parent configures each device. This is a hard requirement under the
  current privacy rules.
- Rules the API can express:
  - Maximum content rating, from TMDB certification data already fetched by
    the user's enrichment. Titles without a certification are treated as
    restricted by default.
  - Hide titles, and block trailers and external links.
  - Lock settings and library edits behind a parental PIN.
- The API is a native interface on each platform (for example a Kotlin
  interface on Android, a Swift protocol on Apple, a C# interface on Windows).
  It is not a shared library.
- The parental PIN is **separate** from the sync PIN in §5. Reusing one PIN for
  both weakens both.
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

## 5. Features F4 and F5 — Bluetooth P2P history sync and timestamp priority

**Requested:** sync watch history between devices over Bluetooth P2P, with an
8-digit PIN that yields a symmetric key, and priority by the higher Unix
timestamp.

### 5.1 Product rules

- Sync moves **watch state only**: progress, completion, rating, review text,
  and last-watched time. File paths, library records, and access grants never
  leave the device (constraint 6).
- Sync runs in the background when both devices are in range and unlocked, and
  as far as §8 allows while locked.
- A sync group is created by pairing (§6.4). Devices outside the group are
  never sent data.
- Conflicts are resolved per record (§5.3), not per whole database.

### 5.2 Transport

- Bluetooth Low Energy (BLE) GATT, with the app's own protocol on top. Classic
  Bluetooth is not used because iOS does not expose it to third-party apps.
- BLE throughput is low (tens of KB/s in practice). The protocol must be
  **delta-based**: each side sends only records newer than the last sync
  watermark, in chunks of bounded size.
- BLE link-layer pairing (Just Works, legacy PIN) is **not** relied on for
  confidentiality. All sync protection happens at the application layer (§6).

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

- Each record carries `(timestamp, device_id)`. The higher timestamp wins; equal
  timestamps tie-break on `device_id` so both devices converge.
- Reject a timestamp more than a small tolerance (for example 5 minutes) ahead
  of the receiving device's clock, and reject timestamps before the title's
  first-release date or before 2000-01-01. Rejected records are logged locally
  as a sync warning, not applied.
- Merge per field, not per record: progress and rating are separate fields with
  separate timestamps. Two devices can then update different fields without
  losing either change.
- Store timestamps as **UTC milliseconds in a 64-bit integer**. Seconds in a
  32-bit integer overflow in 2038.

This keeps the user's requested priority (higher timestamp wins) and removes
the two defects above.

### 5.4 Sync flow (summary)

1. Devices discover each other only through the pairing advertisement (§6.4).
2. Each side sends its watermark (highest accepted timestamp per device).
3. Each side sends delta records in authenticated, encrypted chunks.
4. Each side merges per field (§5.3), acknowledges, and advances its watermark.
5. Sync is idempotent: replaying a chunk changes nothing.

---

## 6. Feature F6 — PIN-derived keys: security review

**Requested:** one party generates an 8-digit PIN; the PIN generates a
symmetric key that is saved permanently and rotated by time of day.

The security review is the reason §5 and this section differ from the request.
The method as written has the following problems. Severity assumes an attacker
who can observe Bluetooth traffic within radio range, and who may run a
malicious device.

| # | Risk | Why it matters | Severity | Mitigation |
|---|---|---|---|---|
| R1 | **Low-entropy secret.** 8 digits is about 26.6 bits (10⁸ values). | An attacker who captures any verifier or ciphertext tied only to the PIN can test every PIN offline in minutes to hours. Argon2id slows this but does not remove it. | Critical | Never use the PIN directly as a key or as a verifier. Use a password-authenticated key exchange (PAKE, §6.1). An attacker then gets **one guess per pairing attempt**, rate-limited. |
| R2 | **Offline guessing from a transcript.** | If the pairing transcript is recorded, R1 applies to the recording. | Critical | Same as R1: PAKE makes the transcript useless for offline guessing. |
| R3 | **Active man-in-the-middle during pairing.** | A relay in BLE range can sit between two devices and try guesses. | High | PAKE (CPace or SPAKE2) with explicit key confirmation. Each failed attempt counts against a limit. |
| R4 | **Rotation adds no secret.** Rotation by time of day uses a public input (the date and hour). | An attacker who knows the PIN can compute every future and past key. Rotation does not protect against PIN compromise. | High | Derive a random 256-bit **root key** during pairing (not from the PIN). Store the root key in Keychain (Apple) or Keystore (Android). Derive each time-window key with HKDF(root key, window index). Rotation then limits exposure of any single window's traffic. |
| R5 | **"Saved permanently" plus PIN-derived means one compromise is total.** | If the PIN or the stored key leaks, every past and future sync is readable. | High | The stored root key is the only long-lived secret. Re-pairing replaces it. The PIN is discarded after pairing and never stored. |
| R6 | **Clock dependence.** Time-of-day rotation needs both devices to agree on the window. | Clock skew or time-zone differences cause sync failures. Accepting a wide window to tolerate skew widens the set of valid keys. | Medium | Use UTC. Accept the current and previous window only. Use the HKDF scheme in R4, so skew affects only which window is tried. |
| R7 | **Nonce reuse.** A time-window counter that resets reuses AES-GCM nonces. | Nonce reuse under AES-GCM leaks plaintext and the authentication key. | Critical | Use XChaCha20-Poly1305 with a random 192-bit nonce per message, or AES-GCM-SIV. Never a counter that can reset. |
| R8 | **No authentication of the payload.** | Without an AEAD tag, a relay can alter records (for example, mark a title complete). | High | AEAD with associated data that binds the sync-group ID, both device IDs, and the window index. |
| R9 | **Replay.** | A recorded chunk can be replayed later. | Medium | Per-session nonces from both sides; reject chunks outside the watermark. Idempotent merge (§5.4) limits the damage. |
| R10 | **Timestamp forgery under LWW.** Any group member can write a huge timestamp and win every conflict. | Combined with §5.3's concern, a malicious *paired* device can overwrite the whole group. | High | Sanity bounds on timestamps (§5.3). Per-device rate limit on accepted records. Group members are trusted by design; the rule protects against accidents and buggy clocks, not a malicious member. Document that. |
| R11 | **Presence tracking over BLE.** Advertisements from a fixed identifier let observers follow a device. | Watching a person's advertisements reveals when they are home or near others. | Medium | Advertise a random identifier that rotates each window (derived from the root key, so only group members can match it). Advertise no device name. |
| R12 | **Pairing-time eavesdropping on the PIN display.** | The PIN is shown on one screen and typed on another. Shoulder-surfing or screen capture reveals it. | Medium | Show the PIN only in the pairing screen, expire it in 2 minutes, and invalidate it after one successful pairing or 5 failures. |
| R13 | **Denial of service.** | BLE flooding, oversized chunks, or endless connection attempts drain battery. | Medium | Maximum chunk size, a connection-attempt rate limit, and a backoff after failed handshakes. |
| R14 | **Plaintext exposure at rest.** Background sync may write decrypted history to unprotected storage. | Watch history is personal data. | High | Keep decrypted data only in the platform's protected storage class (iOS Data Protection; Android credential-encrypted storage). Never write plaintext to cache or logs. |
| R15 | **Secrets in logs or crash reports.** | PINs, root keys, and derived keys must never be logged (constraint 3). | High | Redact at the logging layer. Add a test that asserts no key material reaches the log sink. |
| R16 | **Custom cryptography.** | Hand-written protocols and primitives are a common source of failure, and open source makes review easier but does not make a design safe. | High | Use audited native libraries: libsodium (Android, Windows), CryptoKit or libsodium (Apple). Keep the protocol design small and published in the repository. |
| R17 | **Open-source assumption.** | Because the code is public, the only secret must be the random root key and the PIN. | — | Design principle: assume the protocol is fully known to the attacker. Security rests on R1–R5 mitigations, not secrecy of the design. |

### 6.1 Recommended key establishment

1. Device A displays an 8-digit PIN. The PIN is single-use and expires in 2
   minutes.
2. Both devices run **CPace** (or SPAKE2) with the PIN as the password. The
   result is a session key that only parties who knew the PIN can derive. A
   wrong PIN produces a mismatch, not a leak.
3. Both sides exchange key-confirmation MACs over the session key. A mismatch
   counts as one failed attempt. After 5 failed attempts, pairing is blocked
   on both devices.
4. On success, Device A generates a random 256-bit **root key** and sends it to
   Device B encrypted under the session key. Both store it in Keychain or
   Keystore, bound to the sync-group ID.
5. The PIN is discarded. It is never stored.

### 6.2 Recommended per-window keys

- Window index = floor(UTC unix seconds / window length), with window length
  set to one hour (not one day; see R4 for why rotation is only useful with a
  secret root).
- Window key = HKDF-SHA256(root key, salt = sync-group ID, info = window index).
- Messages use XChaCha20-Poly1305 with random nonces and AAD = sync-group ID ‖
  device IDs ‖ window index.

### 6.3 What the requested design gets right

- A short, human-enterable code for pairing is practical.
- Rotation is good hygiene once the root key is secret: it limits how much
  ciphertext any one key protects.
- "Saved permanently" is acceptable once the stored secret is random and
  device-bound, not PIN-derived.

### 6.4 Pairing advertisement and sign-in (F8)

- The sync group identifier is random. It is not derived from the PIN.
- Sign-in across platforms (F8) is the same pairing flow, creating a sync group
  with no server. Under §2 option A, this is the only "account" in 27.1.
- Losing every device means losing the sync group. There is no recovery,
  because there is no server. This must be stated in the UI.

### 6.5 Residual risks the design accepts

- A member of a sync group is trusted. A malicious member can forge records
  within the group (R10).
- If a device is stolen **unlocked**, its root key and history are exposed.
- Lost devices remain in the group until the user removes them. Removal needs
  a rekey, and the remaining devices must re-pair or receive a new root key.

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

This is constrained by the operating systems, and the constraint is strongest
on iOS.

- **Android.** A foreground service with the `connectedDevice` type can keep an
  established BLE connection while the screen is off. Scanning is throttled, and
  Doze and battery optimization can still stop work. Use a foreground service
  with a visible notification, and ask the user to exempt the app from battery
  optimization.
- **iOS.** Background Bluetooth requires declared background modes
  (`bluetooth-central`, `bluetooth-peripheral`). Background scanning must name
  specific service UUIDs. Advertising in the background drops the local name.
  Work on a locked device runs only when the system grants background time or
  when a previously connected peripheral is restored. Arbitrary background
  transfers are not guaranteed.
- **Android–iOS interop.** Both sides' background limits apply at the same
  time. Expect sync to complete reliably only when the app is foregrounded on
  at least one side, and to be best-effort otherwise.

**Product rules**

- Background sync is best-effort. The UI shows "last synced" time and never
  claims instant sync.
- No data is written to unprotected storage while locked (R14).

**Acceptance criteria**

- Hardware test matrix (Android model, iPhone model, both locked and unlocked,
  for at least 30 minutes) is documented before release.
- Sync resumes correctly after the OS suspends and restores the app.

---

## 9. Shared product rule changes and parity

Changes that affect more than one platform must name every affected branch
(constraint 7). The rules in §5.3 (timestamp priority), §4 (parental API), and
§6 (key handling) affect every platform that syncs or enforces controls. Each
branch implements and tests them natively:

| Rule | apple | android | meta-horizon | windows | web | xbox |
|---|---|---|---|---|---|---|
| Timestamp merge (§5.3) | Implement | Implement | Inherit from android | Implement | N/A (no sync) | Implement |
| Parental controls (§4) | Implement | Implement | Implement | Implement | N/A | Implement |
| Sync crypto (§6) | Implement | Implement | Inherit from android | Implement | N/A | Implement |

`meta-horizon` is downstream of `android`, so it inherits android changes; it
does not duplicate them by hand. The Web branch remains static and
credential-free (constraint 8), so it never syncs or stores history.

---

## 10. Decisions needed before implementation

1. **F8:** Confirm option A (sync group, no account) and amend the "no Edendale
   account" wording in [AGENTS.md](AGENTS.md) and [README.md](README.md).
2. **F4/F6:** Approve the PAKE-based pairing and random root key (§6.1–6.2)
   in place of the PIN-derived key.
3. **F5:** Approve per-field merge with bounded timestamps (§5.3) in place of
   plain whole-record LWW.
4. **F3:** Choose the Xbox playback and UI stack, or defer F3 to 27.2.
5. **F1:** Choose whether visionOS is in 27.1.
6. **F2:** Commission a legal review for age-rating and kids-category rules.
7. **F9:** Accept best-effort background sync and set the hardware test matrix.

Items 2 and 3 change the request, so they need an explicit yes before any
platform branch implements them.

---

## 11. Verification (for the eventual implementation)

- Each platform branch documents the exact native build and test commands in
  its own README when its implementation lands.
- Crypto: test vectors for CPace/SPAKE2 transcripts, HKDF window keys, and AEAD
  round-trips with tampered AAD and tampered ciphertext (both must fail).
- Merge: property tests that any two replicas converge regardless of message
  order, including clock-skew and future-timestamp cases.
- Log hygiene: an automated test that no PIN, root key, or window key appears in
  log output (R15).
- Parity: each affected branch runs the same rule test cases, implemented
  natively.

Documentation-only changes on this branch were checked by reading the
referenced files; no platform build applies.
