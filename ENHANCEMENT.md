# ENHANCEMENT.md — Android 27.0

Implementation plan and checklist for the Android 27.0 release: parity with
Apple's 27.0 release (`apple-27.0`), plus Android-specific gaps found while
comparing the two branches. It is written for an implementing agent, and for
the owner to review the finished work against.

| | |
|---|---|
| Baseline | `android` at `ad00efc` (2026-08-10) |
| Parity target | `origin/apple-27.0` at `4f8383a` |
| Feature guide | `DIFF.md` on `main`: `git show origin/main:DIFF.md` |
| Apple design notes | `git show origin/apple-27.0:ENHANCEMENT.md` (Sections A–J) |
| Work branch | `android-27.0` (decision D0) |
| Written | 2026-10-01 |

Nothing in this plan is shared code. AGENTS.md hard constraints 1 and 7 apply:
every rule is reimplemented and tested natively in Kotlin. Apple files are
references for behavior, constants, and test cases only. Read them with
`git show origin/apple-27.0:<path>`.

**Reference conventions.** "DIFF §3.4" is a section of DIFF.md on `main`.
"Apple §I.3" is a section of Apple's ENHANCEMENT.md. A bare "B.2" is a section
of this file. Where DIFF.md and the Apple source disagree, the Apple source
wins, and the step says so.

---

## How to work through this document

### Before you start

- [x] **S.1** Run `git fetch origin` and confirm `origin/main` and
  `origin/apple-27.0` exist.
- [x] **S.2** Create `android-27.0` from `android` and do all work there. Never
  commit to `main`, `apple*`, `windows*`, or `web`.
- [x] **S.3** Read this branch's `README.md`, `AGENTS.md`, `CLAUDE.md`, and
  `DESIGN.md`.
- [x] **S.4** Set up the toolchain: JDK 17, and Android SDK 35 through
  `ANDROID_HOME` or a gitignored `local.properties` (`sdk.dir=…`).
  `secrets.json` (copied from `secrets.example.json`) is optional; a missing
  key disables only the feature that needs it.
- [x] **S.5** Run `./gradlew testDebugUnitTest assembleDebug` before changing
  anything, and record the result in the Handoff log at the end of this file.

### Commands

| Purpose | Command |
|---|---|
| Hermetic JVM tests | `./gradlew testDebugUnitTest` |
| One test class | `./gradlew testDebugUnitTest --tests 'com.babasama.edendale.android.player.PlayerControlPreferencesTest'` |
| Debug APK | `./gradlew assembleDebug` (writes `build/outputs/apk/debug/Edendale-debug.apk`) |
| Instrumented tests (after A.5) | `./gradlew connectedDebugAndroidTest` (needs a device or emulator) |
| Lint | `./gradlew lintDebug` |

### Hard rules

These restate AGENTS.md and CLAUDE.md for this work. Breaking one fails review.

1. **Branch scope.** Change only Android code, tests, CI, and docs on this
   branch. Don't copy code or binary files from other branches; read them for
   reference. Generate binary test fixtures with a checked-in script instead of
   copying Apple's. (Universal documentation text, such as the DESIGN.md rows in
   B.5.1, may be copied verbatim.)
2. **Secrets.** Never hardcode, commit, or log credentials. New client IDs and
   app keys go through `secrets.json` → the `generateAppSecrets` Gradle task →
   generated Kotlin objects, exactly like `TMDB_READ_ACCESS_TOKEN`. An empty
   value hides the dependent feature. Never log tokens, passwords, request
   headers, or URLs that carry tokens or signatures, not even in debug builds.
3. **Privacy.** No analytics, telemetry, crash upload, Edendale server, or
   Edendale account. The only network services are TMDB, Wyzie (after an
   explicit search), TheIntroDB (only while the user has Skip Prompts on), and
   storage providers the user links. Performance measurements stay on the
   device.
4. **Classify before network.** Every new source parses file names locally and
   stores the records before any TMDB enrichment starts.
5. **Device-local data.** New preferences, accounts, logins, and SSH host-key
   pins stay on the device and are never synced. Every new credential store is
   excluded from cloud backup and device transfer in both
   `src/main/res/xml/data_extraction_rules.xml` and
   `src/main/res/xml/backup_rules.xml`, next to `edendale_smb_credentials.xml`.
6. **Explicit user action.** Trailers and Wyzie searches still start only from
   an explicit action, and TheIntroDB lookups only while the opt-in setting is on.
7. **No `TASKS.md`.** This file is the plan.
8. **Don't weaken existing tests** to make a change pass. If an existing test
   encodes a rule this plan changes, update the test and say so in the commit
   message.

### Code conventions

- **Pure rules, thin adapters.** Put product rules in Kotlin objects or
  functions with no Android or Media3 imports, the way `PlayerLogic` is
  written, and test them in `src/test` with the hermetic JVM suite. Keep
  SharedPreferences, Media3, Room, and Compose code thin around them. The JVM
  suite has no Robolectric; don't add it.
- **Networking.** Follow the `WyzieTransport` / `TmdbTransport` pattern: a
  transport interface, an `HttpURLConnection` implementation in
  `AndroidEdendaleCore.kt`, and a fake in tests (see `FakeTransport` in
  `TmdbApiTest.kt`). Storage providers (Section H) use OkHttp instead, because
  `HttpURLConnection` rejects WebDAV's `PROPFIND` method.
- **JSON.** Parse with kotlinx-serialization-json's `JsonElement` API, as
  `TmdbApi` and `WyzieSubtitleService` do. Don't add the serialization compiler
  plugin.
- **UI.** Compose, reusing the player panel components (`PanelRow`,
  `ToggleRow`, `SegmentChip`, `PanelLabel`, `PanelHeader`) and
  `SettingsSection`. Colors come only from `EdendaleColors` in `Theme.kt`. A new
  color is a new named token, added to `DESIGN.md` as well.
- **Strings.** Every user-facing string goes in
  `src/main/res/values/strings.xml` and is translated into all 18 locale
  folders: `values-b+zh+Hans`, `values-b+zh+Hant`, `values-de`,
  `values-en-rAU`, `values-en-rCA`, `values-en-rGB`, `values-en-rUS`,
  `values-es-rES`, `values-es-rMX`, `values-fr`, `values-it`, `values-ja`,
  `values-ko`, `values-nl`, `values-pt-rBR`, `values-pt-rPT`, `values-ru`,
  `values-sv`. Store labels in natural case and apply `.uppercase()` in the
  view; use `<plurals>` for counts; use `AppStrings` (`Localization.kt`) outside
  Compose. Where Apple has English copy for the same control, use it verbatim
  (it's in the Swift file the step references). A removed string is removed
  from every locale.
- **TV and keyboard.** Every new control is reachable with the D-pad, shows
  `tvFocusLift`, and fits the Back layering in `PlayerActivity.handleBack`. TV
  gets −/+ steppers where handhelds get sliders.
- **Accessibility.** Icon-only controls get a `contentDescription`. Steppers
  and segmented controls announce their value (for example "15 seconds").
- **Reduced motion.** New animations (the Up Next card, docked panels) fall back
  to a fade when the system animator duration scale is 0.

### Workflow for each step

1. Read the step and its Apple references (Appendix 1).
2. Port the listed tests first where possible, then implement.
3. Run `./gradlew testDebugUnitTest assembleDebug`. Both must pass.
4. Do the step's **On device** checks if a device is available, and record the
   device model, Android version, and result in the Handoff log. If no device
   was available, write "not run". Never report a check you didn't run.
5. Tick the step's boxes and update the Tracking table (status and commit).
6. Commit with the message `Android 27.0: <step id> <summary>`, at most one
   step per commit.
7. Update `README.md` when a step adds a setting, command, dependency,
   permission, or network service (AGENTS.md working rule 4).
8. Record anything you did differently from this plan in the Deviations log,
   with the reason.

### Stop and ask the owner when

- the step depends on a decision in §0 marked **Owner** that is still open;
- a dependency's licence isn't compatible with Apache-2.0, MIT, BSD, or
  MPL-2.0, or the dependency pulls in Google Play Services;
- the step needs real credentials, a registered OAuth app, or a real account
  (never register apps or create accounts yourself);
- a Room migration would drop or rewrite existing user data;
- Media3 or the platform behaves differently from what this plan assumes
  (write down what you observed).

---

## 0. Decisions

Defaults apply unless the owner changes them. **Owner** rows block the listed
steps until they are answered.

| ID | Topic | Decision | Blocks |
|---|---|---|---|
| D0 | Branch | Work on `android-27.0`, created from `android`; merge back through a pull request. | — |
| D1 | Media3 version | Upgrade every `androidx.media3` module together from 1.7.1 to **1.9.0**. Media3 1.8.0 added `VideoFrameProcessor.REDRAW` (needed by F.1), and 1.9.0 is the newest version Jellyfin's FFmpeg decoder is built for (D5). If D5 rules Jellyfin out, the latest stable Media3 is fine instead. | — |
| D2 | TV hold speeds | Holding the remote's fast-forward or rewind key for 400 ms or more engages the right or left hold speed until the key is released. A shorter press skips by the App Controls length. Holding D-pad Left/Right keeps its current repeat-scrub. | — |
| D3 | TV skip-prompt focus | While a skip prompt is visible, D-pad Down from the hidden-controls surface focuses the prompt. Otherwise Down keeps opening the timeline. | — |
| D4 | Subtitle style default | Apple parity: System font, Parchment text, Ink box at 100 %. Edendale's presets replace the system caption style that `PlayerView` applies today; text size still follows the system caption font scale (B.4). | — |
| D5 | DTS/TrueHD decoding | **Decided by the owner: (a) Jellyfin decoder** (`org.jellyfin.media3:media3-ffmpeg-decoder`), confirmed 2026-10-02. The APK will be GPL-3.0. | — |
| D6 | Old auto-skip | Remove the timed 90 s recap / 180 s credits skip. Delete `player.skipRecap` and `player.skipCredits`, and never read them into Skip Prompts (DIFF decision 5). | — |
| D7 | Hold-speed migration | None: Android never stored a hold speed, so everyone gets the new defaults, 0.5× and 2.0×. | — |
| D8 | Accounts | Device-local only; excluded from backup and device transfer (DIFF decision 1). | — |
| D9 | Google Drive sign-in | **Decided by the owner (2026-10-04): PKCE through Custom Tabs** (H.9.1's option 1), the flow H.6 already uses. Edendale gets an Android OAuth client with its custom URI scheme turned on, and the redirect is `com.babasama.edendale:/oauth2redirect`. If Google's verification refuses the custom scheme, ask the owner before falling back to Identity Services' `AuthorizationClient` (option 2), because it adds Play Services. | — |
| D10 | Home-server TLS | **Decided by the owner (2026-10-04), as proposed:** valid HTTPS always works; self-signed HTTPS works after the user approves the certificate's SHA-256 fingerprint, pinned per host; plain `http://` (`dav://`) only to private-network addresses. DIFF decision 4 asks Apple and Windows to match (L.4 note 6). | — |
| D11 | Enhancement default | Apple starts each playback at Balanced, Sharpness 0.5, Denoise 0.5, Motion Smoothing off, and doesn't save changes. Android matches that (in memory, not saved), except: Off on devices that fail the capability check (F.6); Off on Android TV until F.1.6 passes on a real TV; and HDR / Dolby Vision always bypassed in this release. | — |
| D12 | Frame generation | Phones and tablets only, off by default, behind the experiment in G.1. Never offered on Android TV. | G.2–G.6 |
| D13 | MediaSession scope | A `MediaSession` owned by `PlayerActivity`. No `MediaSessionService` and no background playback; playback still pauses in `onStop`. | — |
| D14 | Instrumented tests | Add `src/androidTest` for GL, parser, and migration checks, run on a device or emulator. Adding them to CI is optional (A.5.4). | — |
| D15 | Watch Next row | **Decided by the owner (2026-10-04): build it** on the platform's `WatchNextPrograms`, knowing Google ends support in the second half of 2027 (I.3.1). Opt-in, off by default. | — |
| D16 | SFTP library | sshj (Apache-2.0) if it passes H.4.1; otherwise Apache MINA SSHD (Apache-2.0). | — |
| D17 | NFS | **Decided by the owner (2026-10-04): no NFS on Android.** H.10.1 found no maintained NFSv3 client under a compatible licence. | — |
| D18 | Rounded subtitle font | Android has no system rounded font. Default: bundle Nunito (SIL OFL 1.1) as the "Rounded" choice and credit it in Attribution. The owner may prefer to drop "Rounded" on Android. | — |
| D19 | Release version | **Decided by the owner (2026-10-04): `versionName` `0.27`** while the app waits for Google Play's approval for the Play Store; the owner picks the numbering after that. `versionCode` goes from 1 to 2, since every upload needs a higher code. | — |

---

## 1. Baseline

Where `android` stood at `ad00efc`, and which step changes each item.

| Area | Today | Where | Step |
|---|---|---|---|
| Skip length | Fixed 10 s for double-tap, ± buttons, D-pad, media keys, PiP actions | `PlayerLogic.SEEK_STEP_MILLIS`, `PlayerActivity.pipActions` | B.1 |
| Hold speeds | Fixed 0.5× / 1.5× | `PlayerLogic.HOLD_SLOW_RATE` / `HOLD_FAST_RATE` | B.1 |
| Loop, Fit/Fill | Reset every session | `PlayerChromeState.loopEnabled`, `aspectFill` | B.2 |
| Per-title memory | None | — | B.2 |
| Track pickers | Subtitles only | `PlayerScreen.SubtitleSection` | B.3 |
| Subtitle style | `PlayerView` default (the system caption style when enabled) | — | B.4 |
| Playlist panel | Text rows with a gold play icon | `PlayerScreen.PlaylistPanel` | B.5 |
| End of an episode | `finish()` | `PlayerActivity`, `STATE_ENDED` listener | C.2 |
| Continue Watching | In-progress titles only; duplicate copies collapse (`toMap()` keeps the last) | `LibraryPresentation.continueWatching` | C.3, D.5 |
| Intro and credits | Timed auto-skip (90 s recap, 180 s credits) | `PlayerLogic`, `PlayerActivity.onPlaybackTick`, `PlayerChromeState` | C.4, C.5 |
| System media controls | No `MediaSession`; media keys work only while the player window has focus | `PlayerActivity.dispatchKeyEvent` | C.6 |
| SMB reads | One synchronous 512 KiB read-ahead, no reconnect | `SmbDataSource` | D.1 |
| Source records | Tree URI, name, and date only; Room v2 with schema export off | `LibraryFolderEntity`, `EdendaleDatabase` | D.2 |
| Rescans | Every source on every Downloaded visit; errors are library-wide | `DownloadedScreen`, `LibraryRepository.scan` | D.3 |
| Source removal | Deletes the server's saved login once no source uses it | `LibraryRepository.removeFolder` | D.4 |
| Audio EQ and booster | None | — | E.1 |
| DTS and TrueHD | Silent where the device has no decoder | — | E.2 |
| Picture adjustments, enhancement | None | — | F |
| Frame generation | None | — | G |
| Other storage providers | None | — | H |

---

## 2. Build order and status

Each phase leaves the app shippable.

### Completed phases (landed on `android-27.0`)
- **Phase 1 — Foundations:** A.1 → A.5 (Complete)
- **Phase 2 — Player basics:** B.1 → C.4 → C.5 → B.2 → C.1 → C.2 → C.3 → C.6 → B.4 → B.3 → B.5 → B.6 (Complete)
- **Phase 3 — SMB and library rules:** D.1 → D.2 → D.3 → D.4 → D.5 (Complete)
- **Phase 4 — Audio:** E.1, E.2 (Complete)
- **Phase 8 — Large screens and keyboard:** J.1 → J.6 (Complete)
- **Settings layout:** K.1 (Complete)
- **Landed storage providers:** H.1, H.2, H.4, H.5, H.6, H.7, H.8, H.10 (dropped per D17), H.11 (Complete)
- **Landed TV features:** I.1.1, I.2.1, I.3.1–I.3.3, I.3.T1 (Complete)
- **Landed picture enhancement pipeline:** F.1–F.7 code & unit tests (Complete)
- **Documentation:** L.1, L.2, L.4 (Complete)

### Active and remaining phases
1. **Storage providers:**
   - H.3: WebDAV valid-HTTPS path (H.3.3) is a device check; H.3.4 (D10) landed
   - H.9: landed (e965a3a); a real sign-in still needs the owner's Android OAuth client
   - H.12: System folder picker experiment (H.12.1)
2. **Android TV:**
   - I.2: landed (a45cf05); the device check I.2.D1 remains, and the owner's review of the finished work
   - Device checks: I.1.D1 (OneDrive TV sign-in), I.3.D1 (Watch Next store instrumented test on TV)
3. **Picture adjustments & enhancement verification:**
   - Device checks & benchmarks on physical hardware: F.1.5, F.1.6, F.3.4 (Snapdragon GSR benchmark), F.7.D1, F.8.1, F.8.2
4. **Frame generation experiment:**
   - G.1 feasibility probe and findings complete (`3bf5199`, Findings — G.1)
   - G.2 → G.6: Blocked pending physical phone timings and owner go/no-go decision
5. **Release close-out:**
   - L.3: Draft `Play Console/27.0/release.txt` when owner requests (`versionCode 2` / `versionName 0.27` already set in `86df492`)

---

## Completed sections summary

The following sections are fully implemented, tested, and landed on `android-27.0`. Full audit logs and commits are in the [Tracking](#tracking) table, [Deviations log](#deviations-log), and [Handoff log](#handoff-log).

| Section | Scope | Key Commits |
|---|---|---|
| **Section A — Foundations** | A.1–A.5: Media3 1.9.0 upgrade, `PlayerPreferences`, 18-locale string infrastructure, `androidTest` suite & CI workflow | `b0c04f0`–`1c9c58d` |
| **Section B — Player controls and state** | B.1–B.6: App Controls (skip lengths 10/15/30 s, hold rates 0.25–3.00×), persisted state & per-title memory (`ContentPlayerPreferences`), video/audio track pickers & panel reorder, subtitle appearance & visible rect placement (`SubtitleAppearance`), playlist panel redesign, speed/seek verification | `9b2fbd5`–`7b1c028` |
| **Section C — Binge flow and system integration** | C.1–C.6: Episode progression rules (`EpisodeProgression`), auto-advance & `UpNextCard`, Continue Watching next-up shelf, removed timed auto-skip, TheIntroDB skip prompts (`introdb`), `MediaSession` & system surfaces (`ForwardingPlayer`) | `1f01315`–`eb1f5f9` |
| **Section D — SMB hardening and library rules** | D.1–D.5: Buffered SMB reads (`BufferedByteSource`), Room v3 source records with `kind`/`accountKey`, rescan throttle (15 min) & per-source status (`offline`/`needsSignIn`), keep logins in Settings → Accounts, Play From multi-source resolution | `bdcc646`–`94970b0` |
| **Section E — Audio** | E.1–E.2: 10-band peaking EQ & Audio Booster (+10 dB) processor (`EqAudioProcessor`), DTS & TrueHD software decoding via Jellyfin FFmpeg extension (`org.jellyfin.media3:media3-ffmpeg-decoder`) | `20c1f63`, `c58c5fb` |
| **Section J — Large screens and keyboard** | J.1–J.6: Extended navigation child rows, Continue Watching/Movies pages, hardware keyboard shortcuts (`onProvideKeyboardShortcuts`), Link Source form keyboard navigation, docked player panels (≥1100 dp), season shelf scrubber | `983546a`, `aa8b2ba` |
| **Section K — Settings layout** | K.1: Settings section order verified across handhelds and TV | `08c55db` |

---

## Section F — Picture adjustments and video enhancement

**Implementation status:**
F.1–F.7 (Effects plumbing with on-demand install and REDRAW support, Color adjustments GLSL port, AMD FSR 1 EASU upscaler, CAS sharpening with divide-by-zero guard, temporal denoise with history textures, performance budget governor and capability check, and Enhancement UI in player panel) are fully implemented and unit/instrumented tested. Landed in `bba68d8`, `948a8d5`, and `753dd89`.

Remaining items are manual hardware device checks and benchmarks:

- [ ] **F.1.5** **On device:** compare effects installed but neutral against effects not installed: no visible difference, no added stutter, and 4K video on a 4K TV box still renders at 4K.
- [ ] **F.1.6** **On a real Android TV set** (not only a box): check whether video drawn through the effects path loses the TV's own picture processing. Record the finding. D11 keeps TV at Off until this passes.
- [ ] **F.3.4** The Snapdragon GSR benchmark against EASU on a mid-range phone, recorded in Findings.
- [ ] **F.7.D1** **On device:** a 720p file on a 1080p phone shows `1280×720 → 1920×1080`; Show Original toggles while paused (REDRAW); changing the preset doesn't stall playback.
- [ ] **F.8.1** A 720p test file upscaled to 1080p on a phone, and to 4K on a TV box, looks visibly sharper than Off and stays within budget on the reference devices. Turning everything Off restores the direct path from the next item.
- [ ] **F.8.2** Findings record each device, its GPU, the time per pass, and the stages the governor kept.

---

## Section G — Frame generation experiment (DIFF §3.17; Apple §I; D12)

**Scope:** phones and tablets only; sources at 30 fps or less; displays whose refresh rate is at least twice the source rate (`Display.getSupportedModes()`). Never on Android TV, because TVs do their own motion smoothing. Off by default.

### G.1 — Feasibility status
- [x] **G.1.1** One-day test: `MidpointFrameEffect` with 50/50 blend proved Media3 1.9.0's effects path releases added frames on time, sync holds, and seeks/flushes behave (`3bf5199`, Findings — G.1).
- [x] **G.1.2** Custom renderer: not needed, since G.1.1 passed on Media3 1.9.0 (nothing to build).
- [ ] **G.1.3** Estimate the cost: Coarse motion-estimation probe built (`CoarseMotionEstimationInstrumentedTest`), tested on emulator. Real phone timings not run.
- [x] **G.1.4** **Findings — G.1** recorded with recommendation: conditional go pending phone timings. **Stop for owner review before G.2.**

### G.2 — Motion estimation (Apple §I.1) [Blocked on G.1 go/no-go]

- [ ] **G.2.1** GLSL ES 3.1 compute shaders porting `MotionEstimation.metal`: coarse 16×16 blocks searched ±16 px with the per-pixel offset cost; a 4×4 refinement searched ±4 px around the coarse vector; and densifying to per-pixel vectors (RG16F), with an optional 3×3 median filter.
- [ ] **G.2.2** Scene-cut counting on the GPU: a block is unmatched when its best match still differs by more than 0.06 mean luma, and a cut is 30 % or more unmatched blocks. On a cut, the synthetic slot repeats frame N−1, with no CPU readback.
- [ ] **G.2.3** Sources wider than 1920 px run motion estimation at half resolution.

### G.3 — Warping and blending (Apple §I.2) [Blocked]

- [ ] **G.3.1** Port `FrameInterpolation.metal`: a two-way warp at t = 0.5, occlusion-aware blending, and hole filling from frame N.

### G.4 — Scheduling and presentation (Apple §I.3–I.4) [Blocked]

- [ ] **G.4.1** Port `FrameInterpolationScheduler` as a pure Kotlin state machine: the synthetic frame first and the real frame one refresh later. Show the real frame directly for the first frame, after seeks, after dropped frames, and after gaps of 1.5 frame durations or more. No interpolation while paused or scrubbing.
- [ ] **G.4.2** Reset on seek, pause, track switch, item switch, and size change. History is committed after interpolating, never before.
- [ ] **G.4.3** Wire it into the presentation path chosen in G.1.

### G.5 — Gating and UI (Apple §I.6) [Blocked]

- [ ] **G.5.1** A **Motion Smoothing** toggle in the Enhancement section, shown only when eligible, labeled with the rates (for example `24 fps → 48 fps`).
- [ ] **G.5.2** The governor turns Motion Smoothing off first when over budget or under thermal pressure, because it costs more than any enhancement stage.

### G.6 — Tests and acceptance (Apple §I.7–I.8) [Blocked]

- [ ] **G.6.T1** (JVM) Port the `FrameInterpolationScheduler` draw-order cases from `FrameInterpolationTests`.
- [ ] **G.6.T2** (instrumented) Port the GPU cases: a horizontal pan gives non-zero vectors of the right sign; a static scene gives near-zero vectors; unrelated frames trip the scene cut and repeat frame N−1; letterbox bars and flat areas stay unchanged; a fade of about 4 % blends; the interpolated pan lands at the midpoint.
- [ ] **G.6.D1** **On device:** 30 minutes of 24 fps playback with Motion Smoothing on a flagship. Record the battery drop and thermal state, and review artifacts on pans, cuts, fades, and letterboxed scenes.

---

## Section H — Storage providers (DIFF §3.12; Apple §J)

**Implementation status:**
- **H.1 (Connector contract & account keys):** Landed in `0b43a91`.
- **H.2 (Remote byte source & Media3 data source):** Landed in `91cbc0e`.
- **H.4 (SFTP via sshj & Bouncy Castle):** Landed in `7d061b1`, `c42421c`.
- **H.5 (S3-compatible storage & SigV4):** Landed in `90072dc`.
- **H.6 (OAuth PKCE & account vault):** Landed in `ed065ea`, `90b891f`, `83f6baf`.
- **H.7 (OneDrive Graph connector):** Landed in `4c83c40`.
- **H.8 (Dropbox connector):** Landed in `4b73e0f`.
- **H.10 (NFS):** Dropped by owner per D17 (`2d28460`).
- **H.11 (Link Source flow & Accounts):** Landed in `57ec4d1`, `f92a1b0`.

### H.3 — WebDAV

- `PROPFIND` with `Depth: 1` (OkHttp), using Basic or Digest authentication. Decode relative and absolute `href`s, with percent-decoding. Suggest `/remote.php/dav/files/<user>/` for Nextcloud and ownCloud.
- [x] **H.3.1** The connector and the listing parser (`c9b6f6a`).
- [x] **H.3.2** The server form (address, user, password), with the login saved in an encrypted store keyed by host and port and excluded from backup (`c9b6f6a`).
- [ ] **H.3.3** The valid-HTTPS path, end to end.
- [x] **H.3.4** (D10) Self-signed certificates with fingerprint pinning, and LAN `dav://`, for WebDAV and S3-compatible endpoints (`PinnedTrustManager`, `PrivateNetworkDns`, `TlsPinStore`; JVM tests `TlsTest` and the WebDAV D10 cases):
  - When system validation fails, Link Source shows the server certificate's SHA-256 fingerprint and subject, as it does for SFTP host keys, and connects only after the user trusts it. Pin it per host and port in a device-local store excluded from backup. Refuse a changed certificate until the user trusts it again. Everything else keeps system validation and hostname checks.
  - `dav://` (and an `http://` S3 endpoint) connects only to private-network addresses, checked on the resolved address: 10/8, 172.16/12, 192.168/16, 100.64/10 (Tailscale), 127/8, 169.254/16, `::1`, `fc00::/7`, and `fe80::/10`. Anything else gets a message that the address needs HTTPS.
  - `usesCleartextTraffic="false"` makes OkHttp refuse every `http://` request, so this needs a network security config that permits cleartext, with the private-address check in the app as the gate.
  - Listing and playback (H.2) follow the same rules. Update the README's WebDAV and S3 sections.
- [x] **H.3.T1** (JVM) Recorded `PROPFIND` responses from Nextcloud, Synology, and Apache `mod_dav`: folders versus files, `href`s, sizes, dates, and percent-encoded names (`c9b6f6a`).

### H.9 — Google Drive (D9)

- D9 (decided 2026-10-04): sign in with H.6's PKCE flow through Custom Tabs. Change the Google configuration from the reversed-client-ID scheme to the Android client's redirect, `com.babasama.edendale:/oauth2redirect`, add its intent filter to `OAuthRedirectActivity`, offer Google Drive in Link Source whenever `GOOGLE_OAUTH_CLIENT_ID` is set, and update the README's Google rows. Not on Android TV, where Drive arrives through I.2. The owner registers the Android OAuth client (package name and the SHA-1 of each signing certificate), turns on its custom URI scheme, and sets up the consent screen and verification for `drive.readonly` (H.9.1).
- [x] **H.9.1** Research note in Findings: Google's rules for OAuth on Android and owner registration steps (`2d28460`).
- [x] **H.9.2** (`GoogleDriveConnector`, `GoogleDriveContentResolver`; e965a3a) List with `files.list` using `q='<id>' in parents and trashed = false`, `supportsAllDrives=true`, and `includeItemsFromAllDrives=true`. Roots: My Drive, Shared with me, and Shared drives. Follow shortcuts; skip `application/vnd.google-apps.*` files; filter videos by file extension, not MIME type. Stream with `alt=media`, a Bearer token, and `Range`. Never send `acknowledgeAbuse`.
- [x] **H.9.T1** (JVM) Recorded responses: paging, shortcuts, shared drives, and filtering (`GoogleDriveTest`, e965a3a).

### H.12 — Experiment: cloud apps in the system folder picker

- [ ] **H.12.1** With the Google Drive, OneDrive, Dropbox, and Nextcloud apps installed, check which ones appear in Add Folder (`ACTION_OPEN_DOCUMENT_TREE`), whether scanning works, and whether playback streams or copies the whole file first. Record the results in Findings. No code.

---

## Section I — Android TV

**Implementation status:**
- **I.1.1 (OneDrive TV sign-in UI):** Landed in `0f00786`.
- **I.2.1 (Phone-to-TV handoff design):** Landed in `2d28460` (Findings — I.2.1; approved by owner).
- **I.3 (Watch Next row):** I.3.1 research, I.3.2 settings toggle, I.3.3 publisher, I.3.T1 tests landed in `2d28460`, `f99a366`, and `6b67628`.

### I.1 — OneDrive sign-in on the TV
- [x] **I.1.1** The TV sign-in screen (`0f00786`).
- [ ] **I.1.D1** On a TV: sign in from a phone, link a folder, and play a file.

### I.2 — Phone-to-TV handoff (DIFF §3.14; Apple §J.7)

Google's device flow can't grant `drive.readonly`, and Dropbox has no device flow at all. So a phone running Edendale signs in and hands the account to the TV, device to device only: a web relay would be an Edendale server, which AGENTS.md forbids.

**Rules:**
- Discovery uses Network Service Discovery with the service type `_edendale-handoff._tcp`. The TV shows a short code.
- Authentication: a PAKE keyed by the code (BouncyCastle's J-PAKE), then AES-GCM for the payload.
- Messages are JSON behind a 4-byte big-endian length, at most 64 KiB, and versioned; unknown versions are rejected. The payload is a provider, account key, email, refresh token, and scopes, or a server login (SMB, SFTP, WebDAV, S3).
- The phone asks for confirmation ("Link Google Drive on <TV name>?") and offers an account already linked there, or a fresh sign-in.
- The TV validates the payload (refreshes the token, or tests the login) before storing it locally. It reuses the phone's refresh token, because Google allows only 100 refresh tokens per account per client.
- [x] **I.2.1** Design note in Findings: protocol, crypto library, threat model (`2d28460`). Owner approved to proceed with implementation.
- [x] **I.2.2** The implementation on both sides (`handoff` package: `AccountHandoff`, `HandoffCrypto`, `HandoffProtocol`; Android `HandoffHost`, `HandoffDiscovery`, `HandoffToTelevisionDialog`, Link Source's Continue on a Phone; a45cf05).
- [x] **I.2.T1** (JVM) Port `AccountHandoffTests`: encoding, the size cap, and version rejection; plus a key-agreement round trip, and failure with a wrong code (`AccountHandoffTest`, 14 cases including a whole exchange over a loopback socket and the TV's adoption of a refreshed or refused token; a45cf05).
- [ ] **I.2.D1** Link Drive, and separately an SMB login, from a phone to a TV. (2026-10-07: a WebDAV login was handed from the phone emulator to the TV emulator end to end, with the wrong-code and decline paths; Drive and SMB still need a real Google account and an SMB server.)

### I.3 — Watch Next row (D15)
- [x] **I.3.1** Research note: Google TV WatchNextPrograms vs Engage SDK (`2d28460`).
- [x] **I.3.2** Opt-in setting in Settings → Android TV, off by default (`f99a366`).
- [x] **I.3.3** Publish in-progress and next-up titles; update on progress change, remove on completion/disable (`f99a366`, `6b67628`).
- [x] **I.3.T1** (JVM) `WatchNextTest` and `LibraryPresentationTest` (`f99a366`).
- [x] **I.3.D1** (instrumented, TV only) `WatchNextStoreInstrumentedTest` writes, updates, and removes a row through the TV provider; on a TV, the row appears in the home screen, opens the player at the saved position, and goes when the setting is turned off. Checked 2026-10-07 on the Television_4K emulator (Google TV launcher): see the Handoff log.

---

## Section L — Documentation, release, and other branches

- [x] **L.1** This branch's `README.md` covers new settings, network services, dependencies & licences, `secrets.json`, and manual checks (`be55c1b`).
- [x] **L.2** This branch's `DESIGN.md` has playlist tokens (`0987890`).
- [ ] **L.3** Release (D19: `0.27`): `versionCode 2` and `versionName 0.27` set in `86df492`. When the owner asks, draft `Play Console/27.0/release.txt` in the 26.0 format.
- [x] **L.4** Leave notes for the owner (this isn't done on this branch): `main`'s `DESIGN.md` needs the playlist tokens; `main`'s README should list the supported storage services; the `web` branch needs privacy-policy text for the providers (Google verification requires it); and D2, D3, D4, and D18 are Android-specific choices the Apple and Windows branches should know about.

### L.4 notes for the owner (2026-10-04)

None of these changes belong on `android-27.0`; each is for the branch named.

1. **`main`'s `DESIGN.md`** has no playlist tokens. Copy two rows from this
   branch's color table, `PlaylistActiveBackground` `#FFFFFF` ("Current or
   focused playlist row fill") and `PlaylistActiveText` `#000000` ("Text and
   icons on an active playlist row"). Also copy the paragraph after the table
   that begins "Playlist selection uses the requested white fill".
2. **`main`'s README** says only "supported network sources". It should
   list the storage services and note that platforms differ. Today Apple 27.0
   has SMB, WebDAV, SFTP, S3-compatible storage, OneDrive, Dropbox, Google
   Drive, and NFS. Android 27.0 has SMB, WebDAV, SFTP, S3-compatible storage,
   OneDrive, and Dropbox. Google Drive comes with H.9 (D9 is decided), and
   Android won't have NFS (D17).
3. **The `web` branch** has no privacy policy, only the "Nothing leaves your
   library" hero copy. Google's OAuth verification for Drive requires a
   policy page at a public URL. Microsoft's publisher verification and
   Dropbox's production approval also expect one. It should say that sign-in
   goes straight to the provider; that tokens stay in the device's keystore
   (Keychain, Android Keystore) and are never sent to an Edendale server
   (there isn't one); that Edendale asks only for read-only access to files
   and the account's name to label it (Apple's scopes: `drive.readonly`;
   `Files.Read`, `User.Read`, `offline_access`; `files.metadata.read`,
   `files.content.read`, `account_info.read`); and that nothing is shared
   with third parties. For
   Google it must also include the Limited Use statement from the Google API
   Services User Data Policy. The site already serves
   `.well-known/microsoft-identity-association.json`.
4. **Android-specific choices the Apple and Windows branches should know
   about:**
   - **D2:** on TV remotes, holding fast-forward or rewind for 400 ms or more
     plays at the hold speed until release; a shorter press skips. D-pad
     Left/Right keeps its repeat-scrub.
   - **D3:** on TV, D-pad Down focuses a visible skip prompt and otherwise
     opens the timeline.
   - **D4:** Edendale's subtitle presets replace the system caption style,
     but text size still follows the system caption font scale.
   - **D18:** Android has no system rounded font, so the subtitle "Rounded"
     choice is a bundled Nunito (SIL OFL 1.1), credited in Attribution.
5. **The `apple` branch's CAS shader** returns NaN (black) in flat areas at
   Sharpness 1. See the Findings entry "Apple's CAS divides 0 by 0 at
   Sharpness 1" for the one-line fix Android uses.
6. **D10, decided for Android on 2026-10-04:** valid HTTPS always works,
   self-signed HTTPS after the user approves the certificate's SHA-256
   fingerprint (pinned per host), and plain HTTP only to private-network
   addresses. DIFF decision 4 asks every platform to match. Apple today
   allows plain HTTP to `.local` names, unqualified names, and any IP
   address (ATS `NSAllowsLocalNetworking`), and has no self-signed option.

---

## Appendix 1 — Apple reference index

Paths are on `origin/apple-27.0`; tests are under `EdendaleTests/`.

| Step | Apple source | Apple tests |
|---|---|---|
| B.1 | `Shared/Views/Player/PlayerControlPreferences.swift`, `Shared/Views/Settings/AppControlsSection.swift`, `Shared/Views/Player/PlayerLogic.swift`, `Shared/Views/Player/PlayerPointerSurface.swift` | `PlayerControlPreferencesTests`, `PlayerLogicTests` |
| B.2 | `Shared/Playback/PlayerPreferencesStore.swift`, `Shared/Views/Player/PlayerChromeModel.swift` | — |
| B.3 | `Shared/Views/Player/PlayerSettingsPanel.swift` | — |
| B.4 | `Shared/Views/Player/SubtitleAppearance.swift`, `Shared/Views/Settings/SubtitleAppearanceSection.swift`, `Shared/Views/Player/PlayerSubtitleOverlay.swift` | `SubtitleAppearanceTests`, `PlayerSubtitleOverlayTests`, `SubtitleEngineTests` |
| B.5 | `Shared/Views/Player/PlayerPlaylistPanel.swift`, `DESIGN.md` | — |
| B.6 | `Shared/Views/Player/PlayerChromeModel.swift` (`applyRate`) | `PlayerTransportStateTests` |
| C.1–C.3 | `Shared/Views/Player/PlayerLogic.swift`, `Shared/Views/Player/PlayerUpNextView.swift`, `Shared/Views/Player/PlayerSession.swift`, `Shared/Views/Downloaded/DownloadedView.swift` | `EpisodeProgressionTests`, `UpcomingEpisodePreviewTests`, `ContinueWatchingTests`, `PlayerSessionTransitionTests` |
| C.5 | `Shared/Controllers/IntroDBService.swift`, `Shared/Views/Player/PlayerSegmentController.swift`, `Shared/Views/Settings/SegmentSkippingSection.swift` | `IntroDBTests` |
| D.1 | `Shared/Playback/Remote/EDBufferedByteSource.h`, `EDBufferedByteSource.m`, `RemoteFileByteSource.swift` | `BufferedByteSourceTests` |
| D.5 | `Shared/Views/Detail/PlaybackSources.swift`, `Shared/Views/Detail/MediaDetailView.swift` | `PlaybackSourcesTests` |
| E.1 | `Shared/Controllers/AudioEnhancementController.swift`, `Shared/Playback/AudioEQProcessor.swift`, `Shared/Views/Settings/AudioEnhancementSection.swift` | `AudioEnhancementTests` |
| F.2 | `Shared/Controllers/VideoAdjustmentController.swift`, `Shared/Views/Player/VideoAdjustmentControls.swift`, `Shared/Playback/Enhancement/ColorAdjustment.metal` | — |
| F.3–F.7 | `Shared/Playback/Enhancement/EnhancementPipeline.swift`, `SpatialUpscaler.swift`, `LanczosUpscaler.metal`, `CASShader.metal`, `TemporalDenoise.metal`, `Shared/Views/Player/VideoEnhancementControls.swift` | `MetalEnhancementPipelineTests`, `EnhancedVideoRenderingTests` |
| G | `Shared/Playback/Enhancement/MotionEstimation.metal`, `FrameInterpolation.metal`, `FrameInterpolator.swift`, `Shared/Playback/Rendering/FrameInterpolationScheduler.swift` | `FrameInterpolationTests` |
| H | `Shared/Controllers/Connectors/`, `Shared/Controllers/Accounts/`, `Shared/Playback/Remote/`, `Shared/Views/Settings/AccountsSection.swift` | `ConnectorTests`, `CloudListingTests`, `OAuthTests`, `RemoteByteSourceTests`, `SFTPProtocolTests`, `Support/HTTPStub.swift` |
| I.2 | `Shared/Controllers/Accounts/AccountHandoff.swift`, `AccountHandoffCenter.swift`, `Shared/Views/Accounts/AccountHandoffRequestView.swift` | `AccountHandoffTests` |
| J | `Shared/Views/RootSidebar.swift`, `Shared/Views/AppCommands.swift`, `Shared/Views/Components/SectionHeader.swift` | `LibrarySectionsTests` |
| K | `Shared/Views/Settings/SettingsLayout.swift`, `Shared/Views/Settings/SettingsView.swift` | — |

---

## Appendix 2 — Device test matrix

| Device | Needed for |
|---|---|
| Recent flagship phone (Android 15 or later) | B, C, E, F, G |
| Mid-range phone (Adreno 6xx or Mali-G57 class) | F.3.4, F.6, G.1.3 |
| Android TV box on a 4K panel | A.2.3, B.1.D2, B.5.D1, C.6.D2, F.1.5, I |
| A TV set running Android TV or Google TV | F.1.6 |
| Tablet, Chromebook, or desktop-windowing device | J |
| Bluetooth headset | C.6.D1 |
| SMB server reachable over a hotspot or Tailscale | D.1.D1 |

---

## Appendix 3 — Review checklist

When a phase is reported done, the reviewer checks:

1. `git log android..android-27.0` and the diff for the phase's steps: every
   ticked box has its code and its tests.
2. `./gradlew testDebugUnitTest assembleDebug` passes on a clean checkout, and
   the listed instrumented tests pass.
3. Constants and keys match this file (search for each key name).
4. Nothing forbidden: changes to other branches, secrets, analytics, logs that
   contain URLs or tokens, network calls not listed here, or a `TASKS.md`.
5. Every new string exists in all 18 locales.
6. Every new credential store is excluded from backup and device transfer.
7. README and DESIGN.md are updated as listed.
8. The Handoff log has device results (or "not run"), and the Deviations log
   explains every change from the plan.

---

## Findings

Record research results and decision notes here: G.1, F.1.6, F.3.4, H.4.1,
H.9.1, H.10.1, H.12.1, I.2.1, and I.3.1.

- **PiP on phones is wider than the screen (found 2026-10-02, pre-existing).**
  `PlayerActivity`'s `<layout android:minWidth="400dp" android:minHeight="240dp">`,
  in the manifest since the 0.26 import (0c08c8b), is also the minimum PiP size,
  so a 16:9 PiP window is at least 427×240 dp. On a 411 dp-wide Pixel 7 emulator
  it opened at 1120×630 px, partly off screen. Twice, expanding it after opening
  the main screen left the player without input focus: taps did nothing and a
  key press ended in an ANR ("Application does not have a focused window"). A
  build without the two attributes opened PiP at 598×336 px and expanded
  normally. Suggested fix: drop `minWidth` and `minHeight` and keep the default
  size for freeform windows.
  **Fixed 2026-10-03 (51f2cca):** both attributes are gone. On the same
  emulator, PiP from Home opened at 598×336 px, fully on screen, and expanding
  it returned a focused player (a media key paused it; no ANR).

- **FFmpeg's TrueHD encoder versus Jellyfin's decoder (E.2, 2026-10-04).** FFmpeg
  7.1's experimental `truehd` encoder writes a 16-bit stereo stream that the
  FFmpeg 6.0 decoder in `org.jellyfin.media3:media3-ffmpeg-decoder` 1.9.0+1
  rejects packet by packet ("Invalid data found when processing input"). The
  same encoder's 5.1 24-bit output decodes, so the E.2.3 fixture is 5.1 at 24
  bits, as discs carry it. Real Blu-ray TrueHD isn't affected.
- **No video size through the effects pipeline (F.1, 2026-10-04).** Media3
  1.9.0's `MediaCodecVideoRenderer` drops the video sink's size reports
  (a TODO for b/292111083), so while effects are installed
  `Player.getVideoSize()` stays 0 × 0. PlayerView then kept its frame at the
  screen's shape and Media3 letterboxed the picture inside it, so Fill did
  nothing. That is the default on phones that pass the capability check, which
  start at Balanced. Cues also used the whole screen, and PiP was always 16:9.
  Found on the API 35 emulator during the review (1280×720 file on a 20:9
  screen: the content frame stayed 2400×1080 in Fill). `VideoEffectsController`
  now supplies the selected track's size (rotation and pixel shape applied)
  while the pipeline is installed, and PlayerView's frame, the subtitle overlay,
  and the PiP shape use it. After the fix, Fill gives a 2400×1350 frame and Fit
  gives 1920×1080. Recheck after a Media3 upgrade; once Media3 reports the size
  again, the stand-in can go.
- **REDRAW needs Media3's replayable frame cache (F.1.2, 2026-10-04).**
  `setVideoEffects(VideoFrameProcessor.REDRAW)` fails playback with
  "Replaying when enableReplayableCache is set to false" unless the video
  graph was built with `PlaybackVideoGraphWrapper.Builder.setEnableReplayableCache(true)`,
  which ExoPlayer's default video renderer doesn't do. `EdendaleRenderersFactory`
  now builds `ReplayableVideoRenderer`, a `MediaCodecVideoRenderer` overriding
  `createPlaybackVideoGraphWrapper` to turn it on. The cache holds one frame and
  exists only on the effects path. Verified on the API 35 emulator.
  `PlaybackVideoGraphWrapper` and its builder are `@RestrictTo(LIBRARY_GROUP)`
  in Media3 1.9.0, so lint reports `RestrictedApi` there, and a Media3 upgrade
  can change them without notice. Recheck REDRAW while paused after every
  Media3 upgrade.
- **Apple's CAS divides 0 by 0 at Sharpness 1 (F.4, 2026-10-04).** In
  `CASShader.metal`, the final `sum / (1 + 4w)` has `w = −0.25·sqrt(amp)` at
  Sharpness 1, and `amp` is 1 in every flat area at or below mid-gray, so those
  pixels come out NaN (black on the GPU). Android computes the same value as
  `e + 4w·(mean − e) / max(1 + 4w, 1e−4)`, identical wherever Apple's is
  defined. The Apple branch should take the same fix (L.4).
- **Enhancement on the emulator (F.6.3, 2026-10-04).** The capability
  benchmark (720p EASU to 1080p, then CAS at 1080p, offscreen) measured
  1.24 ms per frame on the API 35 arm64 emulator (host GPU). That says nothing
  about phones; F.3.4, F.6, and F.8.2 still need the reference devices.
- **sshj on Android against OpenSSH 10.3 (H.4.1, 2026-10-04).** sshj 0.41.1
  with `DefaultConfig`, on the API 35 arm64 emulator, connected to OpenSSH
  10.3p1 (macOS, default algorithms) and negotiated `curve25519-sha256`, an
  `ssh-ed25519` host key, and `chacha20-poly1305@openssh.com` both ways; it
  listed a folder and read a range with pipelined 32 KiB requests
  (`SftpOpenSshInstrumentedTest`, given the server through instrumentation
  arguments). Android's built-in "BC" provider is a stripped copy, so
  `Sftp.ensureProvider()` replaces it with Bouncy Castle 1.84 under the same
  name, last in line, and points sshj at it; the rest of the app keeps its
  default providers. The APK needs one packaging exclusion: Bouncy Castle's and
  jspecify's multi-release jars all carry `META-INF/versions/9/OSGI-INF/MANIFEST.MF`.
  MINA SSHD stays a test-only dependency (H.4.T1); no fallback was needed.
- **H.9.1 — Google Drive sign-in on Android (research, 2026-10-04; D9 is the
  owner's).** Google disables custom URI scheme redirects by default for new
  Android OAuth clients ("vulnerable to app impersonation") but lets a
  developer turn them on in the client's Advanced Settings; it recommends
  Google Identity Services instead. The options:
  1. **PKCE in a Custom Tab with an Android client and the custom scheme turned
     on** (redirect `com.babasama.edendale:/oauth2redirect`). This is Apple's
     flow and reuses H.6 unchanged, and a public client gets a refresh token
     without a secret. The risk is that Google tightens the rule further, or
     that its restricted-scope review (needed for `drive.readonly`) objects.
  2. **`AuthorizationClient` (Google Identity Services).** Google's
     recommendation, but it needs Google Play services and the closed-source
     `play-services-auth`. It returns only a one-hour access token. A refresh
     token needs a server holding a client secret, which Edendale can't have,
     so the app would call `authorize()` again (silently once granted) each
     time a token expires.
  3. **Drive only through WebDAV** (for example `rclone serve webdav`), which
     works today through H.3 and needs no Google registration.

  Either sign-in option needs the same registration: an Android OAuth client
  (package `com.babasama.edendale` plus the SHA-1 of each signing
  certificate), the consent screen with `drive.readonly`, and restricted-scope
  verification (a public homepage and privacy policy on `web`, and a demo
  video). While the consent screen is in Testing, refresh tokens expire after
  7 days. Neither option reaches Android TV: Google's device flow can't grant
  `drive.readonly` (Apple §J.7). Suggested: option 1 for parity with Apple,
  with option 2 as the fallback if verification refuses the custom scheme.
  Sources: Google's 2023 announcement of custom URI scheme restrictions, the
  API Console help page on OAuth clients, and the Android authorization guide.
  **Owner decision (2026-10-04): option 1** (D9).
- **H.10.1 — NFS client (evaluation, 2026-10-04; D17).** The only NFSv3
  client that fits D17's licences is EMC's `com.emc.ecs:nfs-client`
  (Apache-2.0). Its last release, 1.1.0, was in July 2022, and it depends on
  Netty 3.10.6.Final (end of life since 2016), commons-lang3, and SLF4J.
  Without root it connects from an unprivileged port, so an export needs the
  `insecure` option, as on Apple. dCache's nfs4j is a server under LGPL;
  libnfs (Apple's route through libvlc) is LGPL-2.1; Sun's WebNFS client
  (YANFS) is unmaintained. Recommendation: don't build H.10 now. The library
  is unmaintained and brings an end-of-life Netty, and there's no NFS server
  here to test against. If the owner wants NFS, vendor the client's RPC/XDR
  layer on top of a plain socket rather than taking Netty 3.
  **Owner decision (2026-10-04): skip NFS** (D17).
- **H.12.1 — Cloud apps in the system folder picker (research, 2026-10-04; not
  checked on a device).** Add Folder uses `ACTION_OPEN_DOCUMENT_TREE`, and a
  provider appears there only if it supports subtree selection
  (`FLAG_SUPPORTS_IS_CHILD` and `isChildDocument`); "few cloud storage
  providers seem to support" it (CommonsWare). Nextcloud's app has since 2020
  (nextcloud/android #303); OpenCloud added it in July 2026. Google Drive's
  provider is widely reported not to offer folder trees. Dropbox and
  OneDrive need checking. Providers usually download the whole file before
  `openFile` returns (Android's `openProxyFileDescriptor` makes streaming
  possible, but few providers use it), so even a listed provider would play
  only after a full download. The device check needs a phone with the Play
  Store and the four apps signed in; the emulator image here has neither. It
  stays open as a device check, and nothing in the app depends on it.
- **I.2.1 — Phone-to-TV handoff design (2026-10-04). Stop for owner review
  before I.2.2.**
  - *Discovery:* the TV registers `_edendale-handoff._tcp` with NsdManager
    on a random port while its Link Source shows "Continue on a phone", and
    shows a 6-digit code that changes for each session. The phone browses for
    the service and lists TVs by name.
  - *Key agreement:* J-PAKE over P-256 keyed by the code, using Bouncy
    Castle's `org.bouncycastle.crypto.agreement.jpake`. Bouncy Castle is
    already in the app for sshj (H.4), and J-PAKE needs no extra dependency.
    Its third round confirms the key, so a wrong code fails before any
    payload is sent. HKDF-SHA256 derives an AES-256-GCM key, and each
    message's nonce is its counter. ECDH with codes compared on both screens
    would also work, but it depends on the viewer actually comparing them.
    Android's own X25519 (`XDH`) arrives only in API 33, and minSdk is 26.
  - *Messages:* JSON behind a 4-byte big-endian length, at most 64 KiB, with
    `"v": 1`; unknown versions and oversized frames close the connection. The
    payload is either a provider, account key, email, refresh token, and
    scopes, or a server login (kind, host, port, user, password, and for S3
    its configuration).
  - *Phone:* it asks "Link <provider> on <TV name>?" and offers an account
    already linked there or a fresh sign-in, and sends nothing until the
    viewer confirms. It reuses its refresh token, because Google allows 100
    per account per client.
  - *TV:* it refreshes the token, or tests the login, before storing it. It
    allows three attempts per code, then shows a new code.
  - *Threat model:* a passive listener on the network learns nothing. An
    active attacker gets one online guess per handshake, which is a 1 in
    10^6 chance before the code changes. A malicious app on the phone can't
    start a transfer, since the viewer confirms in Edendale. The code and
    keys exist only for the session, and nothing is logged. A stolen TV holds
    the same tokens it would after a direct sign-in, and Sign Out (with
    Revoke Access) ends them. Out of scope: handoff between Android and Apple
    devices (Apple uses DeviceDiscoveryUI).
  - *Owner (2026-10-04):* proceed with this design; the owner reviews and
    adjusts it with the finished work.
- **I.3.1 — Watch Next (research, 2026-10-04): stop for the owner.** Google
  TV still shows Watch Next programs (`TvContractCompat.WatchNextPrograms`) in
  its "continue watching 1.0" row, but Google's May 2026 post says that API
  "will lose support in the 2nd half of 2027". All new Continue Watching
  integrations are meant to use the Engage SDK. Engage needs Google's
  enrollment ("express interest … if eligible"), an `AccountProfile` with an
  account ID, and adult profiles only. Edendale has no accounts and isn't a
  streaming partner, so Engage is out of reach. Building I.3.2–I.3.3 on Watch
  Next would work today on Google TV and the older Android TV home screen,
  but only until the second half of 2027. It isn't "Engage only" yet, but
  close enough that the owner should decide; I.3.2–I.3.3 wait.
- **Findings — G.1: frame generation feasibility (2026-10-04). Stop for
  owner review before G.2.**
  - *Where it ran:* the owner's Pixel_10_Pro_XL emulator (Android 17, API
    37). Its GPU is SwiftShader, which is software GL running on the Mac's
    CPU, and its panel has only a 60 Hz mode. That answers how Media3
    schedules frames. It says nothing about what a phone's GPU costs, and
    it can't show a refresh-rate switch. The probes are in `src/androidTest`
    under `player/video/framegen`, and the README shows how to run them on
    a phone.
  - *G.1.1, the presentation path: Media3's effects path works in 1.9.0.*
    `MidpointFrameEffect` is a test-only `GlShaderProgram` that outputs a
    50/50 blend at the midpoint timestamp before each frame whose
    predecessor is less than 1.5 frame durations earlier. It announces input
    capacity only while two output textures are free, because
    `BaseGlShaderProgram` can't output two frames per input.
    `FrameGenerationPlaybackInstrumentedTest` plays 30 s clips with two AAC
    tracks into a SurfaceView with the app's renderers
    (`EdendaleRenderersFactory`), and compares each run with the same
    pipeline with midpoints off. It ran 12 times.
    - *Released on time:* yes. ExoPlayer schedules each added frame by its
      own timestamp, like any other frame (`VideoFrameRenderControl`), and
      `VideoFrameMetadataListener` reports it. At 24 → 48 fps (720p), each
      run released 776–802 frames, 385–399 of them midpoints. SurfaceFlinger's
      timestats counted 48.2–48.7 fps presented, with 3–6 of about 235
      frames dropped (47.2 fps and 9 dropped after Balanced enhancement).
      Without midpoints it was 24.0 fps, with none dropped.
      At 30 → 60 fps (360p), 60.2 fps were released and 61.7 fps presented,
      with 1 dropped. One 24 fps run stalled early on the software GPU: after
      a 42 ms GL-thread stall, 14 frames were dropped and one midpoint was
      shown 113 ms late.
    - *A/V sync:* every frame, real or midpoint, was released on the
      display refresh nearest its time on ExoPlayer's audio clock. At 48 fps
      on the 60 Hz panel, midpoints landed within 12.5 ms of their exact
      time (one refresh is 16.7 ms), because they fall between refreshes.
      At 60 fps, real frames and midpoints both landed within 0.3 ms. Real
      frames were spread the same way with midpoints on as off.
    - *Seeks, pauses, and track switches:* each seek called `flush()`. The
      first frame arrived 43–97 ms later at the target, and no frame from
      before a seek was released after it. Each run had three seeks, two of
      them 150 ms apart. Pausing and resuming, REDRAW while paused (F.1.2's
      path), switching the audio language, and running after Edendale's
      Balanced effect all kept playing without errors.
    - *Display rate:* ExoPlayer's frame-rate estimator
      (`VideoFrameReleaseHelper`) measures the output timestamps, so once
      it locks on, the SurfaceView votes `Surface.setFrameRate` at 48 Hz
      (60 Hz for a 30 fps source). SurfaceFlinger shows the vote as
      ExactOrMultiple. Before the estimator locks on, the vote is the
      source rate, which `DefaultVideoFrameProcessor` reports as its output
      rate. The emulator's panel has only 60 Hz, so
      the display couldn't switch. On 60 Hz, 48 fps can't be shown evenly:
      present intervals alternated between one refresh and two (16–17 ms
      and 30–33 ms). At 60 fps (360p) they were mostly one refresh
      (13–19 ms on the software GPU).
    - *Headroom:* without midpoints, frames reach ExoPlayer's release stage
      about 45 ms before they're due (median; the stage takes a frame at
      most 50 ms before it's due). With midpoints the median is 21–35 ms, because a
      midpoint can be made only once the frame after it has been decoded.
      Holding the GL thread 8 ms per midpoint, as a stand-in for motion
      estimation and warping, kept 24 → 48 fps on time in two runs: 48.2
      and 49.1 fps presented, with 4 and 1 frames dropped. One 2 s segment
      of the second run lost 10 frames. Holding it about 19 ms (8 ms plus
      waiting for the software GPU to finish) was too much: 36 fps
      presented, 60 dropped. At 30 → 60 fps in 720p, the software GPU fell
      behind even without extra work, and SurfaceFlinger dropped 84 and 105
      of about 300 frames in two runs. Doubling the output textures to 12
      didn't help, and 360p ran clean, so the limit was throughput rather
      than lookahead. With the 19 ms hold at 30 fps, only 131 of 490
      midpoints were released. Late midpoints are simply dropped, and real
      frames keep their timing.
    - *What this means for G.4:* Media3 delivers frames ahead of their
      release time, so the effect can output the midpoint and then frame N,
      each with its own timestamp, and ExoPlayer paces them. Apple's
      scheduler holds N back by one refresh because its renderer gets
      frames only when they're due. Android doesn't need that hold. G.4.1's
      port reduces to the rules about when to blend: neighbors closer than
      1.5 frame durations, and a reset after a flush, an end of stream, or
      a size change.
    - *Risks:* Media3 documents that effects which change frame timestamps
      aren't supported during playback (`ExoPlayer.setVideoEffects`). They
      work in 1.9.0, but nothing promises they will after an upgrade, as
      with the REDRAW cache above. Rerun the probe after every Media3
      upgrade. Not checked: speeds other than 1× (at 1.5×, 48 fps means 72
      frames a second), Picture-in-Picture, and 1080p sources.
  - *G.1.2:* not needed, because G.1.1 passed. The enhancement passes stay
    in plain classes (F.1), so a custom renderer is still possible if a
    Media3 upgrade breaks this path.
  - *G.1.3, the cost: measured only on the emulator.*
    `CoarseMotionEstimationInstrumentedTest` ports `motionEstimationCoarse`
    exactly: 16×16 blocks, a ±16 px search, Apple's charge per pixel of
    offset, and its unmatched-block threshold. Each variant found a known
    6 × −4 px pan in every interior block. Median of 5 runs at 1080p, timed
    with `glFinish`, since the emulator has no GPU timer queries:

    | Variant | SwiftShader (ms) |
    |---|---|
    | Fragment pass, RGBA input (the exact port) | 106 |
    | Fragment pass after a one-byte luma prepass | 82 |
    | Half resolution: downscale, luma, and search at 960×540 | 22 |
    | Compute shader (GLSL ES 3.1), RGBA input, atomic count | 106 |

    These are CPU timings of a software renderer, so only the ratios carry
    over: the luma prepass saves about a quarter, half resolution about
    four fifths, and the compute port costs the same as the fragment pass.
    (It has no shared-memory tiling yet.) The fragment pass needs only
    OpenGL ES 3.0. On ES 3.0 it would count unmatched blocks in a reduction
    pass instead of an atomic. The flagship and mid-range timings that the
    go/no-go needs weren't measured.
  - *Recommendation: a conditional go, pending G.1.3 on phones.* The
    presentation path works without a custom renderer, and sync holds. Go
    ahead with G.2 only if the coarse pass on a recent flagship takes about
    2 ms or less at 1080p, so that the whole synthetic frame fits in 8 ms.
    Apple's plan budgets 1–2 ms of its 3–6 ms total on an M1. On mid-range
    phones, use the half-resolution path or don't offer the feature. Before
    G.5, the owner should also tighten D12's display rule. Offer Motion
    Smoothing only when `Display.getSupportedModes()` has a mode at an
    integer multiple of twice the source rate: 24 fps needs 48, 96, or
    144 Hz; 30 fps works on 60 and 120 Hz; 25 fps needs 50 or 100 Hz. A
    phone with only 60 and 120 Hz shows 48 fps unevenly (one and two
    refreshes, or two and three), which defeats the purpose for most films.
    An even picture from 24 fps on a 120 Hz panel would need 5×
    interpolation (four synthetic frames per source frame), which is
    outside this plan. Also turn it off at speeds above 1×.
- **TV Settings: the Wyzie API Key field traps D-pad focus (found 2026-10-07 on the Television_4K emulator, pre-existing).** Walking Settings with the D-pad lands on the Wyzie "API Key" text field (shown even when the build has a key: "Using the key from secrets.json."), the on-screen keyboard opens, and every further Down press goes to the keyboard. Back closes the keyboard but focus stays on the field, so the sections below (Playback, Sources, Accounts, TMDB, Backup) can only be reached by scrolling with a pointer. Suggested fix: on TV, don't show the field when a key is built in, or make it a button that opens the keyboard only on Select. **Fixed 2026-10-08:** `KeyboardOptions(showKeyboardOnFocus = false)` isn't honored by the String `OutlinedTextField`, and Compose's own D-pad handling ignores keys from virtual devices, so on TV the field now sits behind `TvTextFieldGate` (ArchiveComponents.kt): the remote lands on the gate (gold ring, no keyboard), Select or a tap moves focus into the field and opens the keyboard, and Back, Up, or Down hand focus back. Checked on the Television_4K emulator: Down walks from the subtitle rows past API Key to Playback, Sources, Accounts, TMDB, Backup, and Privacy; Select, typing, Back, Back, and Down reach Save.
- **Auto-advance can pick a copy on an offline source, and the player shows the raw failure (found 2026-10-07 on the Television_4K emulator, pre-existing).** With Severance imported from a dead SFTP source (left by an earlier session) and from a reachable WebDAV source, S01E01 played from WebDAV and the end-of-episode advance opened S01E02 from the SFTP copy, which failed with "Unable to Play — Unreachable(host=10.0.2.2)": the `ConnectorFailure` printed through `toString()` rather than its localized message, and the choice didn't follow D.5's reachable-copy rule. Opening S01E02 from the launcher's Play Next row played the WebDAV copy. Suggested fixes: route C.2's next item through `PlaybackSources.order` with the sources' last status, and map `ConnectorException` through `connectorFailureMessage` in the player's error view. **Fixed 2026-10-08:** the player's episode list listed every copy, and `episodesForShow` returns copies of one episode in no set order. `PlaybackSources.playerEpisodes` now keeps one copy per episode: the playing copy for its own episode, then a copy on the playing copy's source while that source is reachable, else the first reachable copy in Play From order. The list also merges show rows that share a TMDB id, as the show page does. `PlaybackFailure.Connector` shows a `ConnectorException` through `connectorFailureMessage`, and a lost connection whose reconnect failed in a connector keeps only the host. Checked on the Television_4K emulator with the same library: S01E01 → S01E02 → S01E03 all played from WebDAV (the server logged each GET), and opening the SFTP copy showed "Can't reach 10.0.2.2. Check that the server is on, …".
- **TV: a touch tap on a settings switch row doesn't toggle it (2026-10-07, Television_4K emulator, cosmetic).** `SettingsSwitchRow` toggles on D-pad Select; `input tap` on the row changed nothing, which doesn't matter on a remote-driven TV but may on a touch-screen Android TV box. **Not a switch-row bug (2026-10-08):** taps toggle Young Audience and Continue Watching on Home Screen, including straight after D-pad focus. The TV keyboard floats over the screen and takes every touch while it's up: with the Wyzie field's keyboard open, a tap on the Skip Prompts switch outside the keyboard did nothing. The 10-07 tap came while the trapped field had the keyboard open; the gate above stops the keyboard from opening as focus passes.
- **A linked cloud folder or S3 prefix scanned the whole account (found 2026-10-08 by the owner on the Pixel_10_Pro_XL emulator; fixed the same day).** Linking My Drive › Movies imported 48 videos from all over Drive. `LibraryRepository.scan` walked `connector.root`, which is the linked folder for SMB, SFTP, and WebDAV but the whole account for Google Drive (the picker's root: My Drive, Shared with me, and Shared drives), OneDrive, and Dropbox, and the bucket for S3. `SourceScanRules.enumerate` now walks `folder.treeUri` (`SourceScanWalkTest`: a Drive folder lists only its own and its subfolder's files and never asks for the root; an S3-style connector starts at the linked prefix). On the owner's emulator the automatic rescan left the 4 films in Movies and dropped the other 44 rows as stale. Downloaded also named every remote source "Network share"; it now gives the provider (Google Drive, WebDAV, …) and keeps "Network share" for SMB.
- **SMB to the owner's Mac Studio (2026-10-08).** Both macOS shares (Extra, the Public folder) are marked for guest access, but the Mac has no Guest User account, so `smbutil view -G` and the app's guest login get "Logon failure: unknown user name or bad password." The phone emulator reached the server at `192.168.1.6:445`; a full check needs a Mac account's login (with Windows File Sharing turned on for that account in File Sharing → Options) or the Guest User allowed to connect to shared folders. The app shows jcifs's English text for this failure rather than a localized message.
- **Emulators and the handoff (2026-10-07).** Two emulators on one host don't see each other's DNS-SD, so the handoff was checked by forwarding the TV's port with `adb forward` and typing `10.0.2.2:<port>` into the phone's new address field (which is why that field exists; see Deviations). The XR_Headset AVD (API 34) only boots reliably with `-gpu host`; under `swiftshader_indirect` its system server died twice. Its keyboard autocompletes `adb shell input text` into fields ("…/Severance/to the office"), so Link Source there was driven with Enter (J.4's keyboard rule), which works.

---

## Deviations log

| Step | What changed from the plan | Why | Commit |
|---|---|---|---|
| B.4.2 | Font and colors are chips on handhelds as well as TV (Apple's macOS/tvOS layout), with a slider for opacity on handhelds and −/+ on TV. | One control that works under a finger and a remote; every preset stays visible. | 7b1c028 |
| C.3.2, D.5 | C.3 first picked duplicate copies by natural path order; D.5 replaced that with the preferred-copy rule, as planned. `PlayerLogic.naturalOrder` makes the old `naturalCompare` total for the tie-break. | `naturalCompare` calls "E01" and "E1" equal, which left the choice to scan order. | a2a3d46, 94970b0 |
| D.1 | Added a playback error view (Apple's "Unable to Play" with the cause and Close). | The player had no error display, so the host-named "connection lost" message D.1 asks for would never have reached the viewer. | bdcc646 |
| D.3 | A local folder's failed scan is recorded as `offline`. | The plan defines the statuses for remote hosts; a revoked grant or an unmounted card is closest to "unreachable", and "sign in" means nothing for a local folder. | 0acda8b |
| D.5 | Android has no local movie detail page, so the movie Play From menu sits beside Play on the Movies & Shows hero; poster taps in Downloaded and Search play the preferred copy. | That's where Android starts a local movie. | 94970b0 |
| F.1–F.7 | One commit for the section instead of one per step. | The passes run in one GL program and share the holder, controller, and shaders; split commits wouldn't build alone. | bba68d8 |
| F.1.1 | Once the pipeline is installed, it stays for the rest of that player session even if everything is turned Off (the passes then draw a plain copy); the next session starts on the direct path again. | Media3 creates the effects sink when the video renderer is first enabled and doesn't remove it. | bba68d8 |
| F.1.3 | `SurfaceHolder.setFixedSize` is applied only on TV, and only while the effects path upscales. | On phones and tablets the SurfaceView already has the visible rectangle's size, which is the upscaler's display there. | bba68d8 |
| F.3.2 | The Lanczos fallback ports Apple's `LanczosUpscaler.metal` instead of Media3's `LanczosResample`. | It runs inside the same GL program as the other passes, and matches Apple's fallback exactly. | bba68d8 |
| F.4.1 | CAS uses an algebraically identical form of Apple's last step that stays defined at Sharpness 1. | Apple's form is 0/0 in flat areas at Sharpness 1 (see Findings). | bba68d8 |
| H.1 | `enumerateVideos` returns the videos with a `complete` flag rather than a bare list. | Android's scanner deletes rows for missing files only after a full listing; Apple's walk silently skips unreadable branches. | 0b43a91 |
| H.1.2 | SMB item URLs keep jcifs's spelling (`SmbFile.url`) instead of `SourceUrl.server`'s percent-encoding. Moving SMB onto the walk also skips dot-files (macOS `._` files), caps the walk at 2,000 folders, and records a share whose top folder can't be listed as failed (D.3) instead of a partial scan. | Every SMB row already stored uses jcifs's spelling, so a rescan must produce the same strings or it would re-import the whole share. The other three follow the shared walk. | 0b43a91 |
| H.2 | The byte source asks the resolver once and reuses the request for every chunk until a 401 or an expired link; the first load to see one refreshes and the others take the result. H.2.T1 runs against a small `ServerSocket` HTTP server in `src/test`. | A pre-authorized link (OneDrive) shouldn't be resolved per chunk, and the single flight has to hold within one source as well as per account. The JDK's `com.sun.net.httpserver` isn't on the Android unit-test compile classpath, and the socket server needs no new dependency. | 91cbc0e |
| H.3 | Add Network Source gained an SMB / WebDAV choice (an early slice of H.11's Link Source), and the form links through the library itself. WebDAV logins live in a new encrypted `ServerLoginStore` (kind, host, port); SMB keeps its own store and key names. A `dav://` address is refused with its own message until D10. | The server form already existed for SMB; H.11 adds the cloud providers to the same chooser. Moving SMB logins would sign every existing source out. | c9b6f6a |
| H.5 | Path- or host-style addressing is worked out from the endpoint and bucket (Apple's rule) rather than asked for; an `http://` endpoint is refused until D10. | Apple's form has no such switch, and Android blocks cleartext traffic anyway. | 90072dc |
| H.6 | Microsoft redirects to `msauth.com.babasama.edendale://auth`, a custom-scheme redirect the owner adds to the Entra app's mobile and desktop platform. Dropbox uses Apple's `db-<app key>://2/token`. The Google configuration exists (and is tested) but isn't offered until D9. Sign-in starts from Link Source in H.7, H.8, and H.11; Settings → Accounts lists accounts and signs out. | Android's Entra platform type needs the release signing hash, which a custom scheme avoids; Apple's Dropbox redirect needs no new registration. | ed065ea |
| H.6 | Since 2026-10-04, Microsoft redirects to `msauth://com.babasama.edendale/VzSiQcXRmi2kyjzcA%2BmYLEtbGVs%3D` instead of `msauth.com.babasama.edendale://auth`. The manifest matches the decoded hash as the path. | The owner registered the Entra app's Android platform, which generates this redirect from the package name and a signing certificate's SHA-1. Without MSAL nothing compares the hash with the app's signature, so debug and release builds share it. | 90b891f |
| H.7 | OneDrive enumerates with the breadth-first walk instead of a recursive `delta` override; folder-level `delta` works only for personal accounts. Linking a OneDrive folder from the UI comes with H.11. | Apple's connector does the same, and work or school accounts are part of the target. | 4c83c40 |
| H.11 | Link Source stays one dialog: a row of provider chips (servers, then the cloud providers this build can sign in to), then the server form or the account step, then the folder browser. On TV, cloud providers wait for I.1's device code. A cloud source's path starts with the account's email, so its row names the account. | The servers' form already lived there (H.3, H.5), and one flow keeps D-pad focus in one place. | 57ec4d1 |
| I.3.3 | Rows open the player through an explicit `PlayerActivity` intent (the shelf card's own request), stored as the row's intent URI, instead of `edendale://` links. Each show gets one row (its most recent episode), not one per episode. Rows are written 8 s after the last library change, so playback's 5 s progress saves update them when playback pauses or stops. | The `edendale://` routes open a detail page by design, not the player at a position. Google's Watch Next guidance asks for one program per series. Writing on every progress save would churn the launcher's row. | f99a366 |
| J.3 | Space, ← and → act only while no panel is open; with a panel open they go to its sliders and switches. Esc on TV follows Back (panel, then controls, then the player). Ctrl+B also hides the compact rail (600–1100 dp). Meta+/ labels it "Hide Sidebar"/"Show Sidebar", Apple's strings. | A focused slider needs the arrows; Apple's player reads keys the focused control left unhandled. | 983546a |
| J.4 | The address field takes focus when the form opens on phones and tablets, not on TV. | On TV, a focused text field opens the full-screen keyboard over the dialog before the viewer chose a field. | 983546a |
| J.6 | The scrubber is on the Episodes heading of `SeasonBrowser` (the selected season's shelf), on phones and tablets as well as wide windows; on TV the rule is a read-only indicator. | Android's local show page lists seasons vertically, so the TMDB season browser has the only horizontal season shelf (as in Apple's `TMDBSeasonBrowser`). A finger can drag the thumb too; a remote scrolls the shelf itself. | 983546a |
| H.3.4 | A valid certificate whose name doesn't match the typed host (an IP address for a server whose certificate names its hostname) is offered for review and pinning like a self-signed one, instead of failing with a bare hostname error. The pin check also runs in the hostname verifier, because a resumed TLS session skips the trust manager. | One flow for every certificate the device can't accept on its own; a resumed session must still honor a pin removed or changed since the last handshake. | 65219dd |
| H.3.4 | `android:usesCleartextTraffic="false"` is replaced by a network security configuration that permits cleartext, and the storage providers' OkHttp client no longer follows `https://` → `http://` redirects. Plain `http://` requests go through a DNS resolver that refuses any address outside the private networks, on the addresses OkHttp is about to connect to. | The platform flag can't carve out private addresses; the DNS gate checks the resolved address, as D10 asks, without resolving twice. The other HTTP clients only use `https://` URLs and `HttpURLConnection` never changes scheme on a redirect. | 65219dd |
| I.2.2 | The TV answers the phone's approved response with a `result` message (stored, or why not) before closing, where Apple's TV only closes the connection. A handed-over login also carries the phone's linked source URLs on that login, its pinned SSH host key (as on Apple) and pinned certificate (D10), and for S3 the bucket's location; the TV tests the login (SMB lists the shares, SFTP its home folder, S3 the bucket, WebDAV the first linked folder) before storing it. | The phone otherwise learns nothing when the TV refuses the token or login; the addresses let the TV's Link Source start browsing where the phone did, and the pins let the TV trust the server the way the phone already does. | a45cf05 |
| I.2.2 | On the TV, Link Source offers every provider the build has a client ID for (Google Drive and Dropbox arrive through the handoff) and every server kind gets Continue on a Phone; the handoff host and its DNS-SD registration exist only while the code is on screen, and three wrong codes replace the code. | I.2.1's design: the TV asks a phone where it can't sign in itself, and the code and keys exist only for the session. | a45cf05 |
| I.2.2 | The TV's code block also shows the TV's own address and port, and the phone's dialog takes that address by hand under the list of TVs found. | Multicast DNS doesn't cross some home routers or guest networks, and two emulators on one host can't see each other's discovery at all; the typed address is how the emulator check ran. | f9ac696 |

---

## Handoff log

| Date | Steps | Commands run and result | Device checks (model, Android version, result) | Notes |
|---|---|---|---|---|
| 2026-10-01 | S.1–S.5, A.1 | `./gradlew testDebugUnitTest assembleDebug`: pass (46 tasks executed, 0 failures) | not run | Baseline verified clean on android-27.0 branch |
| 2026-10-01 | A.2 | `./gradlew testDebugUnitTest assembleDebug`: pass (0 failures) | not run | Upgraded Media3 from 1.7.1 to 1.9.0 across all media3 dependencies |
| 2026-10-01 | A.3 | `./gradlew testDebugUnitTest assembleDebug`: pass (0 failures) | not run | Implemented PlayerPreferences and PlayerControlPreferencesTest |
| 2026-10-01 | A.4 | — | not run | Established string prefixes and 18-locale translation convention |
| 2026-10-01 | A.5 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass | not run | Configured androidTest source set, runner, dependencies, and generate.sh |
| 2026-10-01 | B.1 | `./gradlew testDebugUnitTest assembleDebug`: pass (0 failures) | not run (no emulator/device connected in CI environment) | Skip lengths (10/15/30) and hold rates (0.25-3.00x) wired to gestures, controls, PiP, TV D2 keys, and Settings |
| 2026-10-01 | C.4 | `./gradlew testDebugUnitTest assembleDebug`: pass (0 failures, 110 tests) | not run (no emulator/device connected in CI environment) | Removed timed auto-skip constants, methods, latches, toggles, obsolete strings across all locales, and cleaned up legacy keys |
| 2026-10-01 | C.5 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (0 failures, 123 tests) | not run (no emulator/device connected in CI environment) | Implemented TheIntroDB pure introdb package, transport in AndroidEdendaleCore, PlayerSegmentController, UI prompt, S key, TV D3 focus, Playback panel toggle, Settings section with links, and README docs |
| 2026-10-01 | B.2 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (0 failures, 149 tests) | not run (no emulator/device connected in CI environment) | Persisted loop/aspectFill globally, added ContentPlayerPreferences model/rules/codec/store, Media3 track adapter, ext- prefix on sideloaded subtitles, and per-title memory save/restore |
| 2026-10-01 | C.1 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (0 failures, 173 tests) | not run (no emulator/device connected in CI environment) | Pure EpisodeProgression rules (nextEpisode, upcomingEpisode, highestCompletedPerShow, nextUpEpisodes) and 24 JVM tests |
| 2026-10-02 | Review of A.1–C.1; fixes to C.5 and B.2 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` on a clean checkout of 9b57892: pass (183 tests, 0 failures). `./gradlew lintDebug`: fails with 311 errors; 308 predate this branch, and 3 are new (`UnsafeOptInUsageError` in `ContentPlayerPreferences.kt`, from B.2). | not run (no device or emulator on the review Mac) | C.5 (451d7f5): one process-wide `IntroDbService`, so a 429 cooldown holds across items and player sessions. B.2 (9b57892): Media3 1.9.0 first reports empty tracks when an item replaces another, which used up the restore, so remembered tracks never carried across a switch; the restore now waits for the first non-empty report. Unticked A.2.3 (its device check wasn't run) and A.5.4 (no CI job was added). A.2.2 needed no source changes: a clean build shows no Media3 deprecation warnings. Corrected C.1's commit to 1f01315. D5 confirmed by the owner, so E.2 is no longer blocked. |
| 2026-10-02 | Accessibility and focus fixes to B.1 and C.5 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` on a clean checkout of 83699b9: pass (185 tests, 0 failures); with the uncommitted C.2 work on top: pass (194 tests). `./gradlew lintDebug`: 311 errors, none new. | not run (no device or emulator on the review Mac) | B.1 (8ac41f9): the skip segments are a radio group that reports the chosen length; each hold-speed − and + names its row ("Hold Left Side, slower"; 2 new strings in every locale); the speed is a polite live region; the player's ± glyphs follow App Controls changes while it's open. C.5 (83699b9): on TV, focus is seeded again when the focused node disappears, such as a skip prompt whose segment ended. TalkBack and TV focus still need device checks. Review notes for the C.2 work are under C.2. |
| 2026-10-02 | Emulator test run (Claude): A.5, B.1, B.2, C.2, C.5 | Built fd6c2e9 (debug) and installed it; instrumented smoke test (`am instrument`): pass. | Android emulator, Pixel 7 profile, Android 15 (API 35, Google APIs, arm64), local test files only. B.1: App Controls exposes the chosen skip length as checked and labels the steppers; changing Skip Back to 30 s while the video was in PiP relabeled the PiP action, and the PiP actions, on-screen buttons, and short media-key presses all skipped by the set lengths; a long ⏩ press didn't skip; holding the right side showed 2.00× and reverted. B.2: a French subtitle chosen in S01E01 carried to S01E02 after a playlist switch and was saved as `player.content.show.<id>`; Loop survived reopening the player. C.2: S01E01 → S01E02 → S01E03 advanced on their own, the Up Next card named the next episode and was absent on the last, and the player closed after it. C.5: Skip Prompts is off by default. | Not run: double-tap, TV (B.1.D2, D3, the C.5 TV focus fix), audio track memory (no picker until B.3), Fill, completion state and resume (no TMDB ids without a token), SMB, Wyzie, C.5.D1. A diagnostic log showed Media3 reporting empty tracks first after a playlist switch, with the B.2 restore now running on the next report. Correction to the review row above: the old restore bug did not stop a language choice from carrying over within a session, because the player keeps the preferred language (the pre-fix build 451d7f5 also showed French); it affected choices the player doesn't carry, such as a video track chosen by size or titles with different saved choices. PiP size issue: see Findings. |
| 2026-10-03 | C.2 review fixes (R1–R3), B.1 KDoc, phone PiP size | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (194 tests, 0 failures). | Android emulator, Pixel 7 profile, Android 15 (API 35): instrumented smoke test (`am instrument`) pass; PiP from Home opened at 598×336 px (was 1120×630 px) and expanded back to a focused player. Not run: the C.2 TV focus path (no TV image). | 7cbb292 resolves C.2.R1–R3 (natural-case label, `player_up_next_hint` as the TalkBack click label, D-pad Up reveals the controls again; on TV, Down from the top row reaches the card). 1e4a1ec updates two comments B.1.2 missed. 51f2cca drops the player's `minWidth`/`minHeight` (Findings). ff97b39 makes the player panel and Settings helpers internal so later sections can live in their own files. C.2.D1 stays open (completion state and advancing inside PiP). |
| 2026-10-03 to 2026-10-04 | Review of the CLI's C.2/PiP commits; A.5.4 picked up; C.3, C.6, B.4, B.3, B.5, D.1–D.5, E.1, E.2, F.1–F.7 implemented (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass at every commit; 309 JVM tests, 0 failures, at bba68d8. Instrumented suite (`am instrument -e package com.babasama.edendale`, 19 tests): all pass at bba68d8. | Android emulator, Pixel 7 profile, Android 15 (API 35, Google APIs, arm64), headless (no display checks were run, at the owner's request): subtitle parsing (SRT CRLF and UTF-16 BOM, WebVTT, ASS), the Room 2 → 3 migration, the EQ processor's formats, bit-exact flat audio, live updates, and clamping, DTS 5.1 and TrueHD 5.1 decoded by FFmpeg through the EQ, every enhancement pass with pixel readback, the effects pipeline through ExoPlayer (installed before prepare, installed mid-play with re-prepare, REDRAW while paused), the capability benchmark (1.24 ms). | Not run: every display, touch, TalkBack, TV, Bluetooth, SMB-over-hotspot, HDMI passthrough, and real-GPU check (B.1.D1/D2, B.2.D1, B.3.D1, B.4.D1, B.5.D1, B.6, C.2.D1, C.5.D1, C.6.D1/D2, D.1.D1, E.1.D1, F.1.5/F.1.6, F.3.4, F.7.D1, F.8). The CLI's commits 7cbb292, 1e4a1ec, 51f2cca, ff97b39, e8835f1, and its A.5.4 CI job (cherry-picked as 1c9c58d; all four pinned action SHAs exist) were reviewed and look right. Steps since then are split with the CLI session (edendale-12), which works in its own worktrees: it reviews C.3–E.2 and takes H.2, the pure H layers, J.1–J.4, J.6, and the research notes. |
| 2026-10-04 | J.5, K Attribution, L.2, J.1–J.4, J.6, L.4 (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass at every commit; 322 JVM tests, 0 failures, at 983546a. | not run (headless, at the owner's request) | The CLI session hit its usage limit before it read the split proposal, with clean worktrees and nothing running, so this session took Section J (planned for the CLI above) and left it a message. Still the CLI's: review of C.3–E.2, H (pure layers first), the G.1 prototype, and the research notes. Not run: J.1–J.6 window, keyboard, and TalkBack checks; Meta+/ lists. K.1's final check and L.1 wait for Sections H and I. |
| 2026-10-04 | H.1–H.3, H.5–H.8, H.11, I.1, L.1 README, K.1 order check (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass at every commit; 411 JVM tests, 0 failures, at 08c55db. Instrumented suite (`am instrument -e package com.babasama.edendale`, 25 tests): all pass at 08c55db. | Android emulator, API 35 (headless): the data-source chain (local file through `EdendaleDataSource`, a Drive item failing with "sign in"), OAuth redirects through the manifest (Microsoft and Dropbox schemes), and the encrypted account vault. | Not run: every real-service check (no registered client IDs or test servers on this machine): H.3.3 (WebDAV over valid HTTPS end to end), S3, OneDrive, and Dropbox linking and playback, I.1.D1 (TV code sign-in). Split with the CLI session (edendale-12): it takes H.4 SFTP, the research notes (H.9.1, H.10.1, H.12.1, I.2.1, I.3.1), the G.1 prototype, and review of C.3–H.5. Waiting on the owner: D9 (Google Drive), D10 (H.3.4), D19 (L.3). |
| 2026-10-04 | C.6 review fix (eb1f5f9) | `./gradlew testDebugUnitTest`: pass (412 JVM tests, 0 failures). | Android emulator, API 35: session skips follow App Controls in PiP and from another app (+10 s, -15 s); next and previous switch episodes in PiP only when a playlist neighbor exists. | The session advertised plain "previous" even with no entry before, where the wrapped one-item player restarted the item, and next and previous without a neighbor fell through to the wrapped player. Next and previous now appear only when a playlist neighbor exists, matching media keys on the focused window (eb1f5f9). |
| 2026-10-04 | H.4 SFTP (7d061b1, c42421c) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass at 7d061b1 (419 JVM tests, 0 failures; 26 instrumented tests pass). | Android emulator, API 35: H.4.1 connected to OpenSSH 10.3 on default port with default algorithms (curve25519-sha256, ssh-ed25519, chacha20-poly1305; see Findings). | SFTP through sshj 0.41.1 with full Bouncy Castle provider registered; trust on first use with host-key verification and pinning; SftpBufferedFile pipelined 32 KiB ranged reads through D.1 buffer; Link Source UI with Port field and saved logins. H.4.T1 in-process MINA SSHD server tests pass. |
| 2026-10-04 | Research notes H.9.1, H.10.1, H.12.1, I.2.1, I.3.1 (2d28460) | — (documentation only) | not run | Research notes and findings recorded for owner decisions: H.9.1 (Google Drive custom-scheme vs AuthorizationClient / D9), H.10.1 (NFSv3 unmaintained / Netty 3), H.12.1 (Storage Access Framework provider behavior), I.2.1 (Local handoff protocol design with NSD, J-PAKE, AES-GCM), I.3.1 (Android TV Watch Next deprecation and Engage SDK). |
| 2026-10-04 | F.6 review fix (753dd89) | `./gradlew testDebugUnitTest`: pass. | not run | PackageInfoCompat reads version code on API 26 and 27 without NoSuchMethodError (lint NewApi). REDRAW finding updated regarding PlaybackVideoGraphWrapper RestrictedApi. |
| 2026-10-04 | H.11 review fix (f92a1b0) | `./gradlew testDebugUnitTest`: pass. | not run | Dropped unused link_source_sign_in across all 19 strings.xml files (lint UnusedResources). |
| 2026-10-04 | F.1 review fix (948a8d5) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (419 JVM tests, 0 failures; 26 instrumented tests pass). GitHub Actions instrumented test workflow passed twice. | Android emulator, API 35: with a 1280x720 file on a 20:9 screen, Fill gives 2400x1350 content frame and Fit gives 1920x1080 with effects pipeline installed; VideoEffectsPipelineInstrumentedTest checks controller size after mid-play install (2 of 2 pass). | Media3 1.9.0 drops video size when effects pipeline installed; VideoEffectsController keeps selected track size while effects pipeline is installed so PlayerView content frame, subtitle overlay, and PiP aspect ratio differentiate Fit and Fill correctly. |
| 2026-10-04 | Review of C.3–H.5 and its emulator checks, with fixes eb1f5f9 (C.6), 753dd89 (F.6), f92a1b0 (H.11), and 948a8d5 (F.1) (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug`: pass at 948a8d5 (419 JVM tests, 0 failures); `VideoEffectsPipelineInstrumentedTest`: 2 of 2 at 948a8d5. `lintDebug`: its new findings were the two fixed here (NewApi in `EnhancementCapability`, UnusedResources `link_source_sign_in`); the rest are MissingTranslation for the `en-r*` files (they override only some strings) and for the audience keys missing from every locale on `android`, RestrictedApi (a false positive on `ComponentActivity.dispatchKeyEvent`, and `PlaybackVideoGraphWrapper`, see Findings), and WrongConstant on the session's command arrays. Strings: every key in all 19 files; the new keys left identical to English are real cognates (Mono, Gamma, Port, Stereo). No logging calls were added on this branch. | Android emulator edendale_api35 (API 35 arm64, headless, SwiftShader GL). B.3.D1 pass: a two-audio MKV lists "Main (English) — Stereo" and "Commentaire (French) — Stereo", and switching carries on playing; a single-audio file has no Audio Track section. B.4.D1 pass except the PGS part (no PGS fixture): Yellow on Navy at 50 % reads well in the Settings preview and in the player; cues clear the controls while they show and drop back when they hide (one early capture had a cue over the seek bar with the controls up, and three replays didn't reproduce it); in Fill the cue stays on screen. B.6.D1 pass: after ten play/pause toggles at 1.5× the clock ran at 1.51×. B.6.D2 pass: a screen recording through ten speed changes had no black frame (220 frames, luma 93.3–93.4). B.6.D3 pass: a skip while paused at 1:48 showed the 1:58 frame. F.7.D1 partly: the label read `1280×720 → 1920×1080`; Show Original while paused redrew the frame and restored it exactly; one preset change mid-play (Balanced → Off) kept playing; under SwiftShader the governor later dropped the upscale (label `1280×720`), as F.6 intends. PiP: the CLI's report of a black, unfocused player after expanding PiP over Settings didn't reproduce on a fresh boot (4 of 4 expansions drew, took focus, and answered media keys and double-tap); its emulator had been up for hours with leftover rotation leashes in SurfaceFlinger. | Found and fixed: Fill did nothing while effects were installed (Media3 1.9.0 reports no video size there; see Findings), F.6's cache key on API 26–27, and an unused string. Reviewed with no further findings: B.3, B.4, B.5 (DESIGN.md rows match Apple's), C.3, D.1, D.2 (schema export and migration wiring), E.1 (profile table, keys, filter constants), E.2 (README licence), and F (HDR bypass, thermal guard, EASU licence notice). Not run: B.1.D1/D2, B.2.D1 (needs TMDB ids; this build has no token), B.5.D1, C.2.D1, C.5.D1, C.6.D1/D2, D.1.D1, E.1.D1, F.1.5, F.1.6, F.3.4, F.8, the PGS part of B.4.D1. G.1 not started. The emulator later went away; emulator-5554 is now the owner's own Android Studio AVD, so nothing more was run. |
| 2026-10-04 | Owner decisions (521519d), L.3 version 0.27 (86df492), and the H.6 Microsoft redirect (90b891f) (Claude, CLI session) | On a scratch worktree of 6a09c33 plus 86df492 and 90b891f: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (419 JVM tests, 0 failures). The debug APK reports versionCode 2 and versionName 0.27, and its manifest routes `msauth` / `com.babasama.edendale` / `/VzSiQcXRmi2kyjzcA+mYLEtbGVs=` to `OAuthRedirectActivity` (aapt2). | not run: `OAuthRedirectInstrumentedTest` needs an emulator; `edendale_api35` no longer exists, and the only one running was the owner's own Pixel_10_Pro_XL. | The owner decided D9 (PKCE through Custom Tabs), D10 (as proposed), D17 (no NFS), and D19 (0.27), and asked for I.2 to be built from I.2.1's design and reviewed at the end; I.3 is still open. The Microsoft redirect is the one the owner registered for Entra's Android platform (see Deviations). Owner steps still to do: the Google Android OAuth client and `drive.readonly` verification (H.9), and a check that the Entra app allows public client flows, which TV sign-in needs (I.1). |
| 2026-10-04 | H.6 redirect test (83f6baf) and I.3 Watch Next (f99a366) (Claude, desktop session, finishing edendale-99's I.3 after it hit its usage limit) | At f99a366: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (436 JVM tests, 0 failures). Strings: both new keys in all 19 files. | The owner's Pixel_10_Pro_XL emulator (Android 17, API 37, 16 KB pages): `OAuthRedirectInstrumentedTest` 4 of 4 after 83f6baf (the Dropbox case had assumed a build without an app key); the whole instrumented suite 26 of 26 at 83f6baf and 27 of 27 at f99a366, with `WatchNextStoreInstrumentedTest` skipped (no TV provider on a phone). `libffmpegJNI.so` and `libandroidx.graphics.path.so` are 16 KB aligned in the APK and in their ELF LOAD segments. | I.3 built as the owner decided (D15). Not run: I.3.D1 (needs a TV device or emulator; the owner's Television_4K AVD would do). |
| 2026-10-04 | Device checks B.2.D1 and C.2.D1 (Claude, desktop session) | — (checks only; built at 95d8551 with the owner's `secrets.json`, so TMDB identifies titles) | The owner's Pixel_10_Pro_XL emulator (Android 17, API 37). Media: three 60 s episodes made with a scratch ffmpeg 7.1.1 (testsrc counter, H.264 via VideoToolbox, `eng` "Main" and `fre` "Commentaire" AAC, `eng` and `fre` SRT), named `Severance S01E0n.mkv` so TMDB matches show 95396, in a local folder linked through the system picker. B.2.D1 pass: French audio and French subtitles chosen in S01E01 were saved as `player.content.show.95396`; after closing the player, S01E02 opened in a new player with both restored, Fill restored (content frame 2992×1683 on a 2992×1344 screen), and Loop Video still on. C.2.D1 pass: S01E01 played to its end, was stored as completed (position 1.0), and S01E02 started on its own; S01E02 then ended in Picture-in-Picture and S01E03 started inside the PiP window (task still pinned, session metadata "In Perpetuity, S01E03", French subtitles carried); the show page lists S01E01 and S01E02 as watched. | On this emulator the F.6 capability check stored `fail`, so Enhancement starts at Off here. The test episodes stay in `/sdcard/Movies/EdendaleTest/Severance` for later checks. |
| 2026-10-04 | G.1 feasibility: G.1.1 and the G.1.3 probe (3bf5199), Findings — G.1 (Claude, desktop session) | At 3bf5199 on a scratch worktree: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (436 JVM tests, 0 failures). `FrameGenerationPlaybackInstrumentedTest` and `CoarseMotionEstimationInstrumentedTest` run with `am instrument`; without their arguments both are skipped. | The owner's Pixel_10_Pro_XL emulator (Android 17, API 37, SwiftShader GL, 60 Hz only), used with the owner's permission and the peer sessions' emulator lock. The debug build (0.27) replaced the 0.26 build installed there; the app's data was kept. Playback probe, 12 runs: 24 → 48 fps at 720p (with and without 8 ms of simulated work per midpoint, after Balanced enhancement, with REDRAW while paused), and 30 → 60 fps at 720p and 360p, each with a midpoints-off baseline; SurfaceFlinger timestats were turned on for each run and off afterwards. Motion search at 1080p: the RGBA, luma-prepass, half-resolution, and compute variants. Results are in Findings — G.1. | Not run: G.1.3 on a flagship and a mid-range phone (no phones), a refresh-rate switch (the emulator has one mode), speeds other than 1×, PiP. Test clips were made with a scratch ffmpeg (testsrc2, VideoToolbox H.264, two AAC tracks) and aren't committed; the probe takes any clip with audio. Stop for the owner's go/no-go before G.2. |
| 2026-10-07 | H.3.4 (D10): certificate pinning and LAN `http://` for WebDAV and S3 (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug`: pass (444 JVM tests, 0 failures). New `TlsTest` runs OkHttp against a local HTTPS server with a self-signed certificate (untrusted → review, pinned → connects, changed → refused, another port's pin ignored), the private-network ranges, and the cleartext DNS gate; `WebDavTest` lists through a pinned self-signed server and refuses `dav://` to a public address. | not run (no device; the real-server checks in README's "Manual device checks" cover the D10 flow) | `bcpkix-jdk18on` added to the test classpath only, to mint the test certificates. Strings: 10 keys (9 new, `connector_insecure_connection` reworded) in all 19 files. |
| 2026-10-07 | H.9.2 and H.9.T1: Google Drive (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug`: pass (450 JVM tests, 0 failures). `GoogleDriveTest`: the virtual root and `canIndex`, a paged listing with shortcuts followed and Google formats skipped, extension-based video detection, shared drives with `corpora=drive`, Shared with me, `about` on validate, a revoked refresh token → sign in, and streaming through the Bearer token with one refresh after a 401. | not run (no Google OAuth client registered; `OAuthRedirectInstrumentedTest` gained a Google case for the next emulator run) | D9 wired: the redirect is `com.babasama.edendale:/oauth2redirect` (`CloudProviders.GOOGLE_REDIRECT_URI`), matched in the manifest by scheme and `sspPrefix` because the URI has no authority; the reversed-client-ID scheme is gone. Link Source offers Google Drive whenever `GOOGLE_OAUTH_CLIENT_ID` is set (phones and tablets; TV through I.2). The cloud browser now reports whether the listed folder can be linked instead of hiding unlinkable subfolders, so Shared drives stays browsable. Strings: 4 new keys in all 19 files. |
| 2026-10-07 | I.2.2 and I.2.T1: phone-to-TV handoff (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug`: pass (464 JVM tests, 0 failures). `AccountHandoffTest`: frames and the three message types round-trip, unknown versions and malformed bodies and oversized frames are rejected, J-PAKE over P-256 agrees one key on both sides and a wrong code fails only at the key confirmation, the AES-GCM channel refuses tampering, replay, and the wrong direction, a whole exchange runs over a loopback socket (approved, wrong code, declined, rejected by the TV, wrong provider), and the TV keeps an account whose token refreshes (reusing the phone's refresh token) and refuses one that doesn't. | not run (I.2.D1 needs a TV and a phone on one network; no device here) | Bouncy Castle's `ecjpake` (already in the APK for sshj) does the key agreement; `ECSchnorrZKP`'s constructor is package-private, so the received proofs are rebuilt through reflection (release builds aren't minified). Network Service Discovery needs no new permission. Strings: 27 new keys in all 19 files. |
| 2026-10-07 | Emulator run of H.3.4 (D10), H.9, I.2, I.3.D1, C.6.D2 (Claude, desktop session) | Built f9ac696 (debug, with the owner's `secrets.json`); `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (464 JVM tests). Instrumented suite (`am instrument -e package com.babasama.edendale`): 30 of 30 on the Pixel_10_Pro_XL emulator (Watch Next and the opt-in probes skipped) and 30 of 30 on the Television_4K emulator (`WatchNextStoreInstrumentedTest` ran there), including the new Google redirect case on both. | Pixel_10_Pro_XL (Android 17, API 37, headless SwiftShader), Television_4K (Android 16, API 36, Google TV launcher, headless), XR_Headset (API 34, `-gpu host`); a Python WebDAV server on the Mac (`10.0.2.2` from the emulators) with two self-signed certificates on 8443 and Basic auth on 8080, serving the three Severance test episodes. **D10 (phone):** `https://10.0.2.2:8443/Severance/` showed Verify Certificate with `CN=nas.local`, the server's exact SHA-256 fingerprint, and the expiry; Trust pinned `10.0.2.2|8443` and imported 3 items; a `davs://` episode played (frame and subtitle cue drawn, ranged GETs on the server); after the server switched to the second certificate, Rescan marked the source "Sign in to 10.0.2.2 again", re-linking showed Certificate Changed with the new fingerprint, and Trust New Certificate replaced the pin and listed the folder; `http://10.0.2.2:8080/` with the Basic login listed and imported; `http://example.com/dav/` got "Plain http:// works only for servers on your own network". **H.9 (phone):** the Google Drive chip appears with its description and Sign In to Google Drive (sign-in itself needs the owner's registered client). **I.2 (TV + phone):** TV Link Source → WebDAV → Continue on a Phone showed the code and `10.0.2.15:<port>`; the phone's Link to a TV took the typed address and code, showed "Link WebDAV on sdk_google_atv64_arm64?" with the saved login `me @ 10.0.2.2:8080`, and ended with "Linked on … Continue on the TV"; the TV tested the login against the phone's folder, stored it (Settings → Accounts lists it), filled its form, and opened the folder browser, and Import scanned the source. A wrong code failed on both sides with the right messages and the TV kept waiting; the right code with Decline left the phone on the TV list and the TV saying so. **I.3.D1 (TV):** with Continue Watching on Home Screen on (toggled by D-pad), playing an episode put a Severance card in the Google TV launcher's Play Next row; selecting it opened Edendale's player; turning the setting off removed the row. **C.6.D2 (TV):** `dumpsys media_session` showed an active Edendale session, PLAYING, with "Good News About Hell, S01E01". **XR:** the app launched in the rail layout, the Settings sheet, Link to a TV, Add Network Source, and the certificate review all rendered. | Fixed during the run (f9ac696): the TV's Connect button showed under the code; the phone named the TV by its typed address; Try Again kept the wrong code; and the typed-address fallback itself. Found, not fixed: see the three new Findings (TV focus trap in the Wyzie field, auto-advance onto an offline copy with a raw error, switch rows ignoring touch on TV). Not run: a Google Drive or SMB handoff (no Google account or SMB server), H.3.3 against a real server, I.1.D1, F.*, G.1.3, H.12.1. The emulators were shut down afterwards and the shared lock released. |
| 2026-10-08 | Google Drive scan scope, the three TV findings, F.7.D1 on the emulator, SMB to the owner's Mac (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass (468 JVM tests, 0 failures; new: `SourceScanWalkTest`, a `PlaybackSourcesTest` case for the player's episode list, a `PlaybackFailureTest` case for connector failures). Instrumented suite (`am instrument -e package com.babasama.edendale`): 30 of 30 on Pixel_10_Pro_XL and 30 of 30 on Television_4K. | Pixel_10_Pro_XL (API 37, headless SwiftShader) with the owner's Google account linked; Television_4K (API 36, headless); a Python WebDAV server on 127.0.0.1:8080 serving the Severance test episodes. **Drive (phone):** the installed 10-07 build had 48 movies under My Drive › Movies; after the fix, the automatic rescan left 4. **TV:** see the three Findings for the Wyzie gate, auto-advance from WebDAV with the SFTP source offline, the localized connector error, and taps on switch rows. **F.7.D1 (phone emulator, functional only):** a 1280×720 WebDAV episode shows `1280×720` with the preset Off and `1280×720 → 1920×1080` after Balanced; while paused, Show Original changes the drawn frame and turning it off restores the enhanced frame exactly (screenshot diff 0), so REDRAW works; switching High Quality → Sharpen Only → Off → Balanced while playing kept the position advancing with no stall. With the preset turned Off mid-item the label still reads `→ 1920×1080`, as F.8.1 expects (the direct path returns from the next item). **SMB:** see Findings. | Not run: F.1.5, F.1.6, F.3.4, F.8, and G.1.3 (no physical phone, TV box, or TV set; emulator GPU timings are SwiftShader on the Mac's CPU), a Drive or SMB handoff (I.2.D1), an SMB login to the Mac. |

---

## Tracking

| Step | Description | Status | Commit |
|---|---|---|---|
| S | Before you start | Complete | — |
| A.1 | Branch and baseline | Complete | b0c04f0 |
| A.2 | Media3 1.9.0 | Complete | f975bc1 |
| A.3 | Player preference store | Complete | 27f16f7 |
| A.4 | Strings and translations | Complete | 6f93abb |
| A.5 | Test conventions | Complete | 4310f03, 1c9c58d |
| B.1 | App Controls | Complete | 9b2fbd5, 8ac41f9 |
| B.2 | Persisted state and per-title memory | Complete (B.2.D1 checked on the emulator) | 15db4ef, 9b57892 |
| B.3 | Track pickers and panel order | Complete (B.3.D1 checked on the emulator) | 9820139 |
| B.4 | Subtitle appearance and placement | Complete (B.4.D1 checked on the emulator except the PGS part) | 7b1c028 |
| B.5 | Playlist panel redesign | Complete | 0987890 |
| B.6 | Speed and seek checks | Complete (B.6.D1–D3 checked on the API 35 emulator) | — |
| C.1 | Episode progression rules | Complete | 1f01315 |
| C.2 | Auto-advance and Up Next | Complete (C.2.D1 checked on the emulator) | fd6c2e9, 7cbb292 |
| C.3 | Continue Watching next-up | Complete | a2a3d46 |
| C.4 | Remove timed auto-skip | Complete | 4a13b2f |
| C.5 | TheIntroDB skip prompts | Complete | 4c2be70, 451d7f5, 83699b9 |
| C.6 | MediaSession and system surfaces | Complete (C.6.D2 checked on the TV emulator: an active session with the episode's title and playback state; C.6.D1 not run) | 4f8ff82, eb1f5f9 |
| D.1 | Buffered SMB reads | Complete (D.1.D1 not run) | bdcc646 |
| D.2 | Source records (Room v3) | Complete | 752fdbf, e5a1539 |
| D.3 | Rescan throttle and per-source status | Complete | 0acda8b |
| D.4 | Keep logins; Accounts | Complete | 6510b37 |
| D.5 | Play From | Complete | 94970b0 |
| E.1 | EQ profiles and booster | Complete (E.1.D1 not run) | 20c1f63 |
| E.2 | DTS and TrueHD (D5) | Complete | c58c5fb |
| F.1 | Effects plumbing | Complete (F.1.5, F.1.6 need devices) | bba68d8, 948a8d5 |
| F.2 | Picture adjustments | Complete | bba68d8 |
| F.3 | Upscaler | Complete (F.3.4 needs a mid-range phone) | bba68d8 |
| F.4 | Sharpening | Complete | bba68d8 |
| F.5 | Temporal denoise | Complete | bba68d8 |
| F.6 | Budget and capability | Complete | bba68d8, 753dd89 |
| F.7 | Enhancement UI | Complete (F.7.D1 partly checked on the emulator; see the Handoff log) | bba68d8 |
| F.8 | Enhancement acceptance | Not run (needs devices) | |
| G.1 | Frame generation feasibility | G.1.1 passed on the emulator (Media3's effects path releases added frames on time); G.1.2 not needed; G.1.3 probe built, phone timings not run; Findings — G.1 written. Waiting for phone timings and the owner's go/no-go | 3bf5199 |
| G.2 | Motion estimation | Blocked (G.1) | |
| G.3 | Warping and blending | Blocked (G.1) | |
| G.4 | Scheduling and presentation | Blocked (G.1) | |
| G.5 | Gating and UI | Blocked (G.1) | |
| G.6 | Tests and acceptance | Blocked (G.1) | |
| H.1 | Connector contract | Complete | 0b43a91 |
| H.2 | Remote byte source | Complete | 91cbc0e |
| H.3 | WebDAV | Complete; D10 checked on the phone emulator against a local server (self-signed review, pinned playback, changed certificate refused and re-trusted, LAN `http://` with a login, public `http://` refused); H.3.3 against a real server with a trusted certificate not run | c9b6f6a, 65219dd |
| H.4 | SFTP | Complete (H.4.1 checked on the emulator against OpenSSH 10.3) | 7d061b1, c42421c |
| H.5 | S3 | Complete (device check not run) | 90072dc |
| H.6 | OAuth and accounts | Complete (Microsoft redirect registered by the owner; sign-in not run) | ed065ea, 90b891f |
| H.7 | OneDrive | Complete (linking UI with H.11; not run against a real account) | 4c83c40 |
| H.8 | Dropbox | Complete (linking UI with H.11; not run against a real account) | 4b73e0f |
| H.9 | Google Drive (D9) | Complete; the owner signed in on the Pixel_10_Pro_XL emulator and linked My Drive › Movies; a scan imported the whole account until the 2026-10-08 fix (Findings), after which only the folder's 4 films remain | 2d28460, e965a3a |
| H.10 | NFS | Dropped by the owner (2026-10-04, D17) | 2d28460 |
| H.11 | Link Source flow | Complete (device check not run; cloud sign-in needs registered client IDs) | 57ec4d1, f92a1b0 |
| H.12 | Folder-picker experiment | Research note in Findings; device check not run (needs Play Store apps) | 2d28460 |
| I.1 | OneDrive on TV | I.1.1 complete; I.1.D1 not run (needs a TV and a registered client ID) | 0f00786 |
| I.2 | Phone-to-TV handoff | Complete; I.2.D1 partly run on the emulators (a WebDAV login, wrong code, decline); Drive and SMB handoffs need a real account and server; the owner reviews the finished work | 2d28460, a45cf05, f9ac696 |
| I.3 | Watch Next row | Complete (I.3.D1 checked on the Television_4K emulator) | 2d28460, f99a366, 6b67628 |
| J.1 | Navigation child rows | Complete (device check not run) | 983546a |
| J.2 | Continue Watching and Movies pages | Complete (device check not run) | 983546a |
| J.3 | Keyboard shortcuts | Complete (device check not run) | 983546a |
| J.4 | Link Source keyboard behavior | Complete (device check not run) | 983546a |
| J.5 | Docked player panels | Complete (device check not run) | aa8b2ba |
| J.6 | Season shelf scrubber | Complete (device check not run) | 983546a |
| K.1 | Settings order | Complete (final order matches the plan across all phases) | 08c55db |
| L.1 | README | Complete (covers sshj, SFTP, and manual device checks) | be55c1b |
| L.2 | DESIGN.md | Complete | 0987890 |
| L.3 | Release | Version 0.27 (code 2) set; release.txt waits until the owner asks | 86df492 |
| L.4 | Notes for other branches | Complete (notes in Section L) | |
