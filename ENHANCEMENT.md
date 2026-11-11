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
| D9 | Google Drive sign-in | **Decided by the owner (2026-10-04): PKCE through Custom Tabs** (H.9.1's option 1), the flow H.6 already uses. Edendale gets an Android OAuth client with its custom URI scheme turned on, and the redirect is `com.babasama.edendale:/oauth2redirect`. If Google's verification refuses the custom scheme, ask the owner before falling back to Identity Services' `AuthorizationClient` (option 2), because it adds Play Services. **Amended 2026-10-17 (H.9.4):** the scope is now `drive.file` with Google's picker, so no restricted-scope verification is needed; the Custom Tab flow stays (D23). | — |
| D10 | Home-server TLS | **Decided by the owner (2026-10-04), as proposed:** valid HTTPS always works; self-signed HTTPS works after the user approves the certificate's SHA-256 fingerprint, pinned per host; plain `http://` (`dav://`) only to private-network addresses. DIFF decision 4 asks Apple and Windows to match (L.4 note 6). | — |
| D11 | Enhancement default | Apple starts each playback at Balanced, Sharpness 0.5, Denoise 0.5, Motion Smoothing off, and doesn't save changes. Android matches that (in memory, not saved), except: Off on devices that fail the capability check (F.6); Off on Android TV until F.1.6 passes on a real TV; and HDR / Dolby Vision always bypassed in this release. **Changed 2026-11-09 (owner request, P.9):** Android saves the viewer's preset, Sharpness, and Denoise on the device (`video.enhancement`, never Show Original); a saved choice wins on every device class, TV included, and the F.6 governor and the HDR / Dolby Vision bypass still apply. With nothing saved, the default is as above. Apple still starts each playback at Balanced. | — |
| D12 | Frame generation | Phones and tablets only, off by default, behind the experiment in G.1. Never offered on Android TV. | G.2–G.6 |
| D13 | MediaSession scope | A `MediaSession` owned by `PlayerActivity`. No `MediaSessionService` and no background playback; playback still pauses in `onStop`. | — |
| D14 | Instrumented tests | Add `src/androidTest` for GL, parser, and migration checks, run on a device or emulator. Adding them to CI is optional (A.5.4). | — |
| D15 | Watch Next row | **Decided by the owner (2026-10-04): build it** on the platform's `WatchNextPrograms`, knowing Google ends support in the second half of 2027 (I.3.1). Opt-in, off by default. | — |
| D16 | SFTP library | sshj (Apache-2.0) if it passes H.4.1; otherwise Apache MINA SSHD (Apache-2.0). | — |
| D17 | NFS | **Decided by the owner (2026-10-04): no NFS on Android.** H.10.1 found no maintained NFSv3 client under a compatible licence. | — |
| D18 | Rounded subtitle font | Android has no system rounded font. Default: bundle Nunito (SIL OFL 1.1) as the "Rounded" choice and credit it in Attribution. The owner may prefer to drop "Rounded" on Android. | — |
| D19 | Release version | **Decided by the owner (2026-10-04): `versionName` `0.27`** while the app waits for Google Play's approval for the Play Store; the owner picks the numbering after that. `versionCode` goes from 1 to 2, since every upload needs a higher code. **2026-10-18:** the owner set `versionName` `0.27.0.1` for the Google Drive `drive.file` update (H.9.4), on branch `android-27.0.1`; `versionCode` goes to 3. **2026-11-09:** the owner set `versionName` `0.27.1` for 27.1 on branch `android-27.1`; `versionCode` goes to 4. | — |
| D20 | Existing Google accounts (H.9.4; the migration plan's D1) | **Decided by the owner (2026-10-17): clean break.** At launch, remove every Google Drive account whose scopes lack `drive.file`, and every Google Drive source that isn't "Videos you chose" or that such an account read, without revoking anything; show a one-time notice to link Google Drive again. Old keys (the ID token's `sub`) don't match the new `permissionId` keys, and folder sources can't be listed under `drive.file`. | — |
| D21 | Removing one Drive video (H.9.4; the plan's D2) | **Decided by the owner (2026-10-17): a hidden-ID list per source, kept on the device.** Rescans skip hidden IDs, so a removed video doesn't come back; picking it again does. Google can't unshare one file with an app. The list is device-local library data: never backed up, synced, or handed to a TV. | — |
| D22 | Folder picks (the plan's D3) | **Settled by the spike (S5): files only.** A picked folder never grants its contents, so `allow_folder_selection` is never sent and listings skip folders. | — |
| D23 | Android sign-in (the plan's D4) | **Settled by the spike (S2): keep the Custom Tab URL flow** (D9). It passed once the owner turned on the Android client's custom URI scheme on 2026-10-17; Google Identity Services isn't needed, so the Quest and the TV handoff keep working. | — |
| D24 | `openid` and `email` in the console (the plan's D5) | **Decided by the owner (2026-10-17): keep them for now**, and remove them from Data Access once no shipped build requests them. This build requests only `drive.file`. | — |

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

#### H.9 amendment — `drive.file` and Google's picker (2026-10-17)

Google's Third-Party Data Safety team refused `drive.readonly` four times: restricted Drive scopes are only for sync, backup, productivity, and security apps. The owner moved the Cloud project to the non-sensitive `drive.file` and chose Google's own picker, which opens inside the sign-in page (`trigger_onepick=true`; the redirect returns `picked_file_ids`). The plan is the owner's private page "Edendale drive.file Migration" (rules R1–R13, the spike, decisions D1–D5); its Android parts are copied here. Its D1–D5 are §0's D20–D24. The other branches (apple, windows, meta-horizon, web) implement the same rules natively in their own sessions; meta-horizon gets this work through its `horizon` flavor once it lands on `android`.

Spike results that bind Android (run 2026-10-17 with the owner signing in; nothing committed or printed):
- **S2:** Google first refused the Android client ("Custom URI scheme is not enabled for your Android client"), even without the picker, so Google sign-in on Android was already broken. After the owner turned the setting on, the same flow passed with picked IDs and a refresh token (through `ASWebAuthenticationSession` on a Mac; recheck in a Custom Tab, H.9.D1). On 2026-10-18 the client ID in this machine's `secrets.json` was checked without signing in: Google's auth page goes on to its sign-in page for `com.babasama.edendale:/oauth2redirect` with `drive.file` and every picker parameter, while an unregistered scheme gets "Access blocked". CI builds have no `secrets.json`, so they don't offer Google Drive.
- **S4:** a grant belongs to the Google account and the Cloud project, not to one client or token. Older refresh tokens see later picks, and the Android client's token saw files picked through the iOS client. A TV's handed-over token therefore sees new picks.
- **S5:** a picked folder grants only itself, never its contents (D22).
- **S6, S7:** `video/*` empties the picker; an explicit list shows a flat, video-only list without folders; no filter shows every file type with Drive's folders, Shared with me, shared drives, and search. On 2026-10-18 the owner tried the unfiltered picker on the phone and chose the video-only list (H.9.4). Drive's MIME types for `.srt` and `.ass` are still unknown, so the list has no subtitle types, and subtitles beside a video load only if they were picked.
- **S8:** `about.get` with `fields=user(permissionId,emailAddress,displayName)` works under `drive.file`, and `permissionId` is the same for every client.
- **S9:** `alt=media` with `Range` returns 206: streaming is unchanged.

Rules (R1–R13, as Android applies them):
- [x] **H.9.4** Scope, picker, callback, and identity (R1–R4; `CloudProviders`, `OAuthClient`, `CloudSignIn`). `GOOGLE_SCOPES` is `https://www.googleapis.com/auth/drive.file` alone (the picker flow allows no other scope, so `openid` and `email` are gone). The authorization request keeps PKCE and adds `access_type=offline`, `prompt=consent select_account`, `trigger_onepick=true`, `allow_multiple=true`, and `mimetypes=video/mp4,video/x-matroska,video/quicktime,video/x-msvideo,video/webm,video/mp2t,video/mpeg,video/x-m4v,video/x-ms-wmv,video/3gpp`; never `video/*` and never `allow_folder_selection`. `OAuthClient.authorizationResponse` reads `code`, `state`, `error`, and `picked_file_ids` (comma-separated, after decoding); `error=access_denied` is `AuthorizationDenied`, and cancelling the coroutine changes nothing. A token whose granted scopes lack `drive.file` fails with "Edendale needs access to the Google Drive files you choose…" and stores nothing. Identity comes from `about.get`; the account key is SHA-256 of `gdrive:<permissionId>` (the ID-token decoder is gone).
- [x] **H.9.5** One source per account, "Videos you chose" (R6, R8, R9; `GoogleDriveConnector`). Its URL is `gdrive://<account>/~chosen/Google%20Drive`, fixed so it reads the same in every language; the library names it "Videos you chose" and its path starts with the account's email. It lists with `files.list` (`q=trashed = false`, the existing fields, `pageSize=1000`, paging, `supportsAllDrives`, `includeItemsFromAllDrives`); folders, folder shortcuts, and Google formats are skipped; shortcuts to files are followed; videos are recognized by extension. Nothing else lists: the My Drive, Shared with me, and Shared drives roots and the folder step in Link Source are gone, and linking an account that already has a source rescans it. Streaming (`files/<id>?alt=media` with `Range` and `supportsAllDrives`) is unchanged.
- [x] **H.9.6** Choose More Videos (R5, R7; `CloudAccounts.chooseMoreVideos`, `ChooseMoreVideosState`, `LibraryRepository.addChosenVideos`). On a Google Drive source's row (Downloaded and Settings → Sources) and on the account in Settings → Accounts, phones and tablets only. It reruns the picker with `prompt=consent` and `login_hint=<email>`, stores the newest refresh token, and never revokes the old one (Google would end the whole grant, the new token and every TV's included). Signing in as another Google account stores nothing and says which account to use. Then the source rescans, or is added if this device has none, and its row says "Added N videos." or "The videos you chose were already in your library." until its next scan; the picked IDs are used only for that count and to unhide (D21). While the picker is open the row says "Waiting for Google Drive…" and the button is Cancel.
- [x] **H.9.7** Existing accounts (R11, D20; `GoogleDriveMigration`, `SourceScanRules.outdatedGoogleDriveSources`, `LibraryRepository`). At each launch, before any scan, Google accounts whose scopes lack `drive.file` are signed out without revoking, and Google Drive sources that aren't "Videos you chose" (or that such an account read) are removed with their items. When anything went, a one-time "Link Google Drive Again" notice explains why and how (on a TV: choose on a phone or tablet first). The pending notice survives a restart until dismissed. A TV also refuses a handed-over Google account that still has `drive.readonly` scopes.
- [x] **H.9.8** Removing one video (R12, D21; `GoogleDriveHiddenItems`, `GoogleDriveStore`, `SourceScanRules.removalLasts`). Android had no per-video Remove, so the owner asked for one (2026-10-18): **Remove from Library** in a movie poster's long-press menu in Downloaded and in an episode row's long-press menu on a show's page (the Menu key on TV), with a trash icon. It's offered only where removing lasts: Google Drive videos and files added on their own; any other source's next scan would bring the file straight back. Removing a Drive movie or episode adds its file ID to its source's hidden list in `edendale_google_drive.xml`, which `backup_rules.xml` and `data_extraction_rules.xml` exclude from cloud backup and device transfer. Scans skip hidden IDs; picking one again unhides it; removing the source clears its list.
- [x] **H.9.9** TVs (R10; `AccountHandoff`, Link Source). A TV never opens the picker and can't sign in to Google on its own. Link Source → Google Drive on a TV says to choose videos on a phone or tablet and offers Continue on a Phone; a handed-over account (or one already linked on the TV) links "Videos you chose" straight away. The TV's Google Drive source row says "Choose videos for this source in Edendale on a phone or tablet, then rescan here." and keeps Rescan. On the phone, the handoff's Google Drive step says the TV lists every video chosen with the account, and that signing in opens the picker.
- [x] **H.9.10** Words (R13). Changed in all 19 string files: the Google Drive description ("Videos you choose from Google Drive."), its privacy note on phones and on TVs (the generic "reads folder listings" note stays for OneDrive and Dropbox), the errors, the relink notice, the handoff note, and the revoke note in Settings → Accounts ("…and Google forgets the videos you chose."). Removed from all 19: `gdrive_my_drive`, `gdrive_shared_with_me`, `gdrive_shared_drives`. English copy is Apple's where Apple has the same control, with "phone or tablet" for "iPhone, iPad, or Mac" and "TV" for "Apple TV"; the notice names Android's **Add Network Source**.
- [x] **H.9.11** Docs: the README (storage table, Cloud accounts, the `GOOGLE_OAUTH_CLIENT_ID` registration, Phone-to-TV handoff, manual checks) and a `_comment` in `secrets.example.json`.
- [x] **H.9.T2** (JVM) `OAuthTest` (the scope string, the picker and Choose More parameters, `picked_file_ids` with an encoded or plain comma, none, and `error=access_denied`, `about.get` identity, the scope check, Choose More keeping the newest token without a revoke and refusing another account), `GoogleDriveTest` (the root and `canIndex`, nothing else lists, the paged picked listing with folders skipped, one listing per scan, failures, streaming), `ConnectorTest` (the fixed source URL and file IDs), `AccountHandoffTest` (a `drive.file` account round-trips; a `drive.readonly` one is refused before any token request; no TV device flow for Google), and `SourceScanWalkTest` (hidden IDs skipped, hiding and unhiding).
- [x] **H.9.T3** (JVM) `GoogleDriveMigrationTest` (D20): old accounts go and every other account stays; old folder sources, orphans, rows with no stored kind, and "Videos you chose" read by an old account go; current sources and other providers stay; a second launch removes nothing.
- [ ] **H.9.D1** (2026-10-18, run on the emulators; see the Handoff log: D20 with a real old account, picker sign-in, playback and seeking, Choose More Videos, removing a Drive video and picking it again, and the handoff to a TV all passed; still open: removing an episode, a new pick reaching the TV by rescan, shared drives, and a physical phone) On a phone, signed in by the owner: sign in through the Custom Tab (the S2 recheck), pick videos (from My Drive, Shared with me, and a shared drive), play and seek one, remove one and rescan, Choose More Videos (the count, and a removed video picked again), then hand the account to a TV AVD and rescan there after picking more on the phone. Also an old `drive.readonly` account on a build from before this change, then this build: the notice and the removal. Ask the owner before starting their AVDs.
- [ ] **H.9.12** Notes for other branches (not done here). `web`: the privacy policy (`src/content/privacy/en.md` and its 7 translations, one new `updated` date) must say Google sign-in asks only for `drive.file` (no `openid`, `email`, or `drive.readonly`); Edendale sees only the files picked in Google's picker (at sign-in and from Choose More Videos) and reads their names, sizes, dates, video durations, and bytes to list and play them, and never uploads, changes, or deletes anything; identity comes from Drive's `about` (permission ID, email, display name), not an ID token; a removed Drive video's file ID stays on that device only; Sign Out and Revoke Access, or myaccount.google.com/connections, ends the grant on every device, TVs included, and Google forgets the picks; keep the Limited Use and no-AI-training sentences. `meta-horizon`: picks this up through the `horizon` flavor; HORIZON.md C.1's `drive.readonly` verification note no longer applies, and the 2026-10-10 "Custom URI scheme is not enabled" finding was fixed by the owner on 2026-10-17.

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

Google Drive videos are chosen in Google's picker, which a TV never opens (H.9.9), and Dropbox has no device flow at all. So a phone running Edendale signs in and hands the account to the TV, device to device only: a web relay would be an Edendale server, which AGENTS.md forbids. (Before H.9.4 the reason was that Google's device flow can't grant `drive.readonly`.)

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

## Section M — 27.1 file sync and handoff version 2 (FEATURES-27.1.md §5, §6, §8; SYNC-PROTOCOL.md on `main` at 768f368, which clarified §9.7's `Login` after c620b21)

Decided by the owner on 2026-11-02: no Edendale server of any kind. Each device writes one JSON file, `Edendale/<space-name>/<device-id>.json`, to storage the user owns, reads the other devices' files and merges them locally by `updateTime` (newest wins as a whole; ties to the lexically smaller device ID; monotonic stamping; a 24-hour far-future guard; tombstones kept a year). The phone-to-TV handoff becomes version 2 so a new device receives the sync location and its login. Open decisions take the recommended answers: OneDrive uses the app folder (`Files.ReadWrite.AppFolder`), tombstones are kept one year. Work branch: `claude/epic-jepsen-88f7b0` from `android-27.0.1`, PR against `android`.

**Rules:** Hard rules 2 (no credential in a log, a sync file, or a message), 3 (no server), 5 (the device ID and the location in `edendale_sync.xml`, excluded from backup and transfer), and 8 apply. Every rule is pure Kotlin under `sync/` and `handoff/` with the Android adapters under `android/sync/` and `android/handoff/`.

- [x] **M.1** Connector write support: WebDAV `PUT`, `MKCOL`, `GET`, `DELETE` and `getetag` in the listing (`WebDavConnector`, `WebDav.PROPFIND_BODY`); SFTP `readAll`, `writeReplacing` (temporary file and rename, with the SFTP-v3 fallback), `mkdirs`, `remove`, `listFiles` (`Sftp`); SMB `listFiles`, `mkdirs`, `read`, `writeReplacing`, `delete` (`SmbClient`). `ConnectorEntry.changeTag` carries the server's change tag.
- [x] **M.2** Sync location (`SyncProvider`, `SyncLocation`, `SyncSettingsStore`, `SyncStorageFactory`, `ChooseLocationDialog`): a picked folder with a persisted read and write SAF permission (`FolderSyncStorage`), Google Drive under `drive.file` (`GoogleDriveSyncStorage`: `Edendale/` in the Drive root, `files.create` multipart and `files.update` media, `modifiedTime` stamps, the handed-over folder ID used as is), OneDrive's app folder (`OneDriveSyncStorage`: `/me/drive/special/approot`, `PUT …:/content` under 4 MiB, `eTag` stamps), Dropbox (`DropboxSyncStorage`: `files/upload` with `mode: overwrite` on the content host, `rev` stamps, ASCII-only `Dropbox-API-Arg`), WebDAV (`WebDavSyncStorage`), SMB (`SmbSyncStorage`), SFTP (`SftpSyncStorage`). `CloudProviders.ONE_DRIVE_SCOPES` adds `Files.ReadWrite.AppFolder` and `DROPBOX_SCOPES` adds `files.content.write`; `hasSyncWriteAccess` gates an older account, and the Sync screen says to sign in again.
- [x] **M.3** Device file and merge (`SyncRecords`, `DeviceFileCodec`, `SyncLedger`): §5 and §6 validation (format, space, device ID against the file name, 8 MiB and 100,000-record limits, integer fields, ratings on the half-step grid, 10,000-scalar reviews, duplicate keys), §7.1–7.3 merge, stamping, invalid stamps as 0, far-future guard, §6.3 tombstone retention at write time (366 days, matching the Apple branch so M3's tombstone is still written at the vector's clock). Android keeps the portable records in their own Room tables (`sync_watch_progress`, `sync_user_media`, schema 4) and projects them onto `watch_progress` and `user_media` (`RoomSyncLocalStore`); local writes are mirrored and stamped by `SyncRecorder` from `LocalDataStore`, the library's Mark Watched, and the TMDB account sync (a pulled value is a new local write). `watch_progress` gains `durationSeconds` so positions travel in seconds.
- [x] **M.4** Triggers (`SyncEngine`, `SyncBackoff`, `SyncTriggers`, `SyncCoordinator`, `SyncWorker`): one sync lists, reads changed files by stamp, validates, merges, and writes this device's file only when the ledger changed since the last write; on launch, on return to the foreground, 10 s after the last local change, at once when playback stops (`PlayerActivity.onStop`) or the app goes to the background, every 5 min in the foreground, and every 15 min in the background through WorkManager with a network constraint; failures back off 1, 2, 4, 8 min up to an hour; reading a file twice changes nothing.
- [x] **M.5** Handoff version 2 (`HandoffV2`, `SecureChannelV2`, `HandoffProtocolV2`; Android `PairingHost`, `PairingGuest`, `SyncHandoff`, `PairingDialog`): DNS-SD `_edendale-handoff._tcp` with TXT `v=2`, `name`, `platform` while the pairing screen is open; QR pairing (X25519 plus a 16-byte secret, `edendale://handoff?v=2&n&h&p&k&s`, HMAC proof and confirmation over the 83-byte transcript, HKDF-SHA256 key); typed-code pairing with J-PAKE participant IDs `edendale-host` and `edendale-guest` and the `edendale-handoff-v2` HKDF info; the AES-256-GCM channel with the 4-byte direction tag ‖ 8-byte counter nonce and the label as AAD; `role`, `offer`, `result`, and `error` messages; the Google ecosystem rule (`offerFor`); 2-minute, one-attempt codes and the 5-failure lockout. The receiver validates each item (token refresh, server connection) before storing it and turns sync on when the location's login made it; a folder location or a Google location from another ecosystem is finished by the viewer (pick a folder; sign in with the hint). Version 1 stays: `HandoffHost` (Link Source) and `PairingHost` peek the first frame's `v` to tell the two apart. Android shows QR codes and reads them as pasted or tapped links (the `edendale` scheme reaches `MainActivity`); there is no in-app camera scanner.
- [x] **M.T1** (JVM) `SyncVectorsTest` (Appendix A.1 M1–M8, §5/§6 file rules, space names, backoff), `SyncConvergenceTest` (random devices, clocks, and read orders converge; a second read changes nothing), `SyncEngineTest` (one sync end to end, stamps, warnings, failures), `SyncStoragesTest` (WebDAV against an in-memory DAV server, SFTP against MINA SSHD, Drive, OneDrive, and Dropbox against recorded request shapes), `HandoffV2Test` (RFC 7748 §6.1, RFC 5869 A.1, RFC 4231 1–4, Appendix A.2 key schedule and QR link, A.3 nonces, messages, the Google rule, whole exchanges by QR and by code, wrong secret and wrong code before any payload, expiry, same role, decline, version 1 beside version 2), `LogHygieneTest` (H10), `SyncRowsTest` (row conversions).
- [x] **M.6** Docs: README (Sync between devices, the scopes in the `secrets.json` table, dependencies, manual checks) and this section.
- [ ] **M.D1** On two devices (ask the owner before using their AVDs): every location kind, resume across devices, the far-future warning, pairing by QR link and by code both ways, a wrong code and a stale QR code, Remove a Device, Turn Off with and without deleting the file, and an older OneDrive or Dropbox account asked to consent again. Not run on this branch so far.
- [ ] **M.7** Owner: add `Files.ReadWrite.AppFolder` as a delegated permission on the Entra app registration, and enable `files.content.write` on the Dropbox app; until then OneDrive and Dropbox consent fails for the new scopes. Then the §11 probes: a `drive.file` folder made by the iOS client is listed by the Android client of the same Cloud project; OneDrive's app folder works for a personal account.
- [x] **M.8** (2026-11-09, owner request) Choose a Location signs in on its own, so a library on SMB can keep its history in Google Drive without linking Drive as a source: on phones and tablets, below Linked accounts, **Sign In to Google Drive**, **Sign In to OneDrive**, and **Sign In to Dropbox** (headed Another Account when an account is already linked; only the preset provider during a handoff follow-up). `CloudAccounts.signInForSync` stores the account like any sign-in but links no library source; for Google it uses `CloudProviders.syncSignInConfiguration`, which drops the picker's `trigger_onepick`, `allow_multiple`, and `mimetypes` and keeps `access_type=offline` and `prompt=consent select_account`, because `drive.file` already covers the files Edendale creates. The new account is chosen at once; tapping the waiting row again stops a sign-in whose Custom Tab was closed. Save stays disabled until a folder, account, or saved login is chosen. The handoff follow-up's Google sign-in uses the same picker-free path. TVs are unchanged (Set Up Another Device). Existing strings only. **Deviation from `apple-27.1`:** iOS's sync setup reuses Link Source's account step, so its Google sign-in still opens the picker there.

---

## Section N — Android XR immersive theater (FEATURES-27.1.md §3, F1; HORIZON.md Section G and the visionOS theater on `apple` as references)

Decided by the owner on 2026-11-08: the immersive theater that `apple` (visionOS, PR #13/#14 and 862f7e6) and `meta-horizon-27.1` (the Quest, G.14) have comes to Android XR, as part of Android 27.1 on this branch. The viewer taps a Theater chip in the player and sits in one of the three Blender rooms from the `scenes` branch (Movie Theatre, Projector Room, Performance Theatre) with the video on the room's screen, a controls dock that follows the head, a seat map, house lights, and Recentre. Nothing is copied from the other branches (Hard rule 1): the rules below are reimplemented in Kotlin and tested in the hermetic JVM suite; the Swift and Horizon sources are references for behavior, constants, and test cases, read with `git show origin/apple-27.1:<path>` and `git show origin/meta-horizon-27.1:<path>`.

**Rules:** Hard rules 1 (branch scope), 3 (no network: the theater adds no traffic of its own), 5 (the theater's preferences stay on the device and are never synced), 6 (nothing starts without the viewer: the chip is the explicit action), and 8 apply. Every rule is pure Kotlin under `src/main/kotlin/com/babasama/edendale/android/xr/` with no Android, Media3, or Jetpack XR import, tested under `src/test/kotlin/com/babasama/edendale/android/xr/`; the Jetpack XR adapters live in the `:xr` module.

**Decisions**

| # | Decision | Why |
|---|---|---|
| N-D1 | **Module.** The theater is a Play Feature Delivery module, `:xr` (directory `xr/`, namespace `com.babasama.edendale.android.xr`), delivered at install time only to devices that declare `android.software.xr.api.spatial` (`<dist:conditions><dist:device-feature>`), fused into a universal APK. It holds the Jetpack XR libraries, the three rooms, and the theater activity. The base keeps the pure rules, the handover, the chip, and the string the module title refers to (`xr_module_title`). | Phones and TVs never download the 23 MB of rooms or the 6 MB renderer, as the Quest's 2D store build carries neither (HORIZON.md D15) and `apple` ships the USDZ only to visionOS. The app is the root Gradle project, so the module depends on it as `project(":")`. |
| N-D2 | **Detection.** The base offers the theater when `PackageManager.hasSystemFeature("android.software.xr.api.spatial")` is true **and** the `xr` split is installed (`ApplicationInfo.splitNames`), and only while a video is loaded. A debug install of the base APK alone, or a phone, never shows the chip. | AGP merges the feature's activity into the base manifest, so resolving the activity proves nothing; the split name does. |
| N-D3 | **Libraries.** `androidx.xr.scenecore:scenecore` 1.0.0, `androidx.xr.runtime:runtime` 1.0.0, and `androidx.xr.arcore:arcore` 1.0.0 (Apache-2.0), feature-only. `androidx.xr.compose` (still beta) is not used; the panels are `PanelEntity`s hosting `ComposeView`s. | Stable artifacts; the base APK is unchanged in size. |
| N-D4 | **No Play Services.** SceneCore depends on ARCore for Jetpack XR, whose `arcore-play-services` runtime wraps Google's ARCore SDK (`com.google.ar:core`, Google Play Services for AR, for phones). Both are excluded from the feature; the theater exists only where the OpenXR runtime does. | "Stop and ask" lists Play Services; the exclusion keeps the rule without asking the owner to accept it. |
| N-D5 | **Head pose.** ARCore for Jetpack XR's `ArDevice` (`DeviceTrackingMode` in the session `Config`), read every frame and converted into the activity space. The dock follows it with the visionOS follower's dead zones and ease; the room is placed from it once. | SceneCore 1.0.0 has no head entity. |
| N-D6 | **Frame and math.** Metres, right-handed, +Y up, the viewer's ahead is −Z and +X to the right (glTF's and Jetpack XR's frame, and the visionOS theater's). A yaw is a rotation about +Y, positive to the left. `TheaterMath.kt` holds the few vector and quaternion operations the rules need, so the rules and their tests need no SDK. | The visionOS cases port one to one; the Quest's +Z-ahead convention is not carried over. |
| N-D7 | **Entry and exit.** The chip hands the item, its position, speed, and the audio and subtitle choices to `XrTheaterActivity`, which owns its own ExoPlayer (built as `PlayerActivity` builds its player: `EdendaleRenderersFactory` with the EQ, `EdendaleDataSource`, the same audio attributes), and the 2D player finishes. The theater requests Full Space; **Exit** returns to the 2D player at the current position (`PlayerActivity` gains an optional position extra; progress is written first), and a **natural end** writes the completed progress and returns to the library (`MainActivity`), as the Quest does. There is no auto-advance or playlist in the theater in this release; Up Next is a dock card that returns to the 2D player on the next episode. | The Quest and visionOS differ here; returning to the player keeps the viewer's place, and the library handles what comes next. |
| N-D8 | **Media3 stays 1.9.0.** SceneCore declares Media3 1.10.0; the feature excludes `androidx.media3` so the base's 1.9.0 (D1, the Jellyfin decoder) is the only copy in the process. | A second Media3 in the split would shadow the base's classes. |
| N-D9 | **Rooms.** The three GLBs are exported from the `scenes` branch's `.blend` files with `tools/export_gltf.py` (Blender 5.2.3 LTS) into `xr/src/main/assets/theater/<id>.glb`, from the seat-forward scenes with the `SeatMap` markers (the state the visionOS USDZ of 862f7e6 came from: `scenes` 06fb0c7 plus the owner's uncommitted edits in the `claude/scenes-bigger-screen` worktree). The commit names that state; once the owner commits `scenes`, the hash is recorded here. The rooms' emission-only materials are kept as exported if the renderer shows them with their baked colours; if they draw black or washed out, an android-side `tools/xr/prepare_theater_glb.py` moves the emission into an unlit base colour (what the Quest needed) and the Deviations log says so. | FEATURES-27.1 §3: exports reproducible from the `scenes` commit named in the platform commit. |
| N-D10 | **Rooms, seats, lights, last room.** The Room list, the seat map (`Seat_RR_NN` markers under `SeatMap`, the best seat nearest the `Seat` node, chosen seat per room), Lights (Normal 1, Dim 0.3, Dark 0.06; the `Screen` and `Projector_beam` nodes untouched) and Recentre (yaw only when level, the whole room tilted when lying back) follow the visionOS theater. The last room is remembered (the Quest's choice; visionOS has no default). Preferences live in the `theater` SharedPreferences file (`room`, `lights`, `seat.<room id>` = `"row-number"`), device-only, never synced; they are harmless to back up and are not added to the exclusion lists. | Parity across the three headsets. |
| N-D11 | **Dock.** 0.65 m from the eyes, 45° below eye level, 0.4 m wide, following the head with 12° / 15 cm dead zones (58° to the left while the seat panel sits there), ease rate 8 /s; the seat panel 0.28 m wide to its left. The dock never hides (it sits below the line of sight); a tap on the room shows nothing extra. Subtitles are a transparent panel 2 cm in front of the picture, sized to the picture's shape (`TheaterGeometry.canvas`), the 2D player's `PlayerSubtitleOverlay` inside it. | visionOS geometry; fixes the Quest's fixed-16:9 subtitle panel. |
| N-D12 | **Picture.** A `SurfaceEntity` quad sized by aspect-fitting the video inside the room's `Screen` quad (the width rules unless the height overflows: 4:3 pillarboxed, 2.39:1 letterboxed), 2 cm in front of the mesh so it cannot z-fight; the room's dark screen surface shows around it. Video Enhancement is off in the theater (its pipeline draws into a `SurfaceView`). | The Quest's rule; the picture keeps the file's shape. |
| N-D13 | **Comfort (FEATURES-27.1 §3).** Seated, no locomotion, nothing animates but the video and a 0.2 s / 0.3 s room fade on a seat change; Recentre re-seats the viewer. The reduced-motion and comfort criteria are met by construction and said so in the README. | — |

- [x] **N.1** The `:xr` module: `include(":xr")`, `dynamicFeatures += setOf(":xr")`, the catalog entries (`xr-scenecore`, `xr-runtime`, `xr-arcore`), `xr/build.gradle.kts` (plugins by id, since the root applies them; the exclusions of N-D4 and N-D8), the feature manifest with conditional install-time delivery and fusing, `xr_module_title` in the base. `./gradlew assembleDebug` builds `build/outputs/apk/debug/Edendale-debug.apk` (unchanged size) and `xr/build/outputs/apk/debug/xr-debug.apk`; `adb install-multiple` installs both and `dumpsys package` lists `splits=[base, xr]`.
- [x] **N.2** Math and GLB reading (base): `TheaterMath.kt` (`Vec3`, `Quat`, `RoomPose`, `HeadFrame`, `angleGap`) and `TheaterGlb.kt`, which reads a room's GLB header and JSON chunk only (never the textures): the world position of `Seat`, the world bounds of the `Screen` quad (its POSITION accessor bounds through the node's world matrix, parents composed, TRS or `matrix`), and the `SeatMap` children as `(name, world position)`; named errors for a non-GLB, a truncated stream, a missing node, a screen without a mesh, and a looping hierarchy.
- [x] **N.3** Geometry and seats (base): `TheaterGeometry.kt` (`screenPlacement` from the screen bounds with the 5 mm lift, `fitPicture(aspect, width, height)`, `canvasSize(aspect)` at 1440 rows with even sides, `subtitleCanvas`, `roomPose(seat, screenCenter)`, `recenterPose(head)` with the 20° level pitch, 15° near-vertical gaze and 1.2 m seated eye height, `Dock`-independent helpers), `TheaterSeatMap.kt` (`SeatID` marker and storage forms, the map sorted front row first, `best` within 0.5 m of the `Seat` node, `layout(size, screenCenterX, screenWidth)` with the 40 pt screen band and separate across/along scales, `nearestSeat`, `rowPitch`), `TheaterRoom` (ids `movie-theatre`, `projector-room`, `performance-theatre`; `assetPath`; `DEFAULT`; `fromId`), `TheaterLights` (`NORMAL` 1, `DIM` 0.3, `DARK` 0.06) and `TheaterPreferencesRules` (keys and parsing).
- [x] **N.4** Dock (base): `TheaterDock.kt` with the constants of N-D11, `yaw(facing)`, `pose(eye, yaw)` (the panel's +Z toward the eyes), `sideTurn`, and `DockFollower` (snap on the first update, the asymmetric dead zone, IEEE remainder for the shortest turn, settle within 1° / 5 mm, heading and position independent, no heading change while the gaze is near vertical).
- [x] **N.5** Entry (base): `XrTheaterHandover` (the Quest's extras, `edendale.theater.*`, built by `PlayerActivity.enterTheater()` from the selected tracks, `chrome.baseRate`, the position and the episode context; `from(intent)` null for anything else; the activity named as a string, `com.babasama.edendale.android.xr.theater.XrTheaterActivity`), `XrTheater.isAvailable(context)` per N-D2 (pure `XrTheaterRules.offers(hasSpatialFeature, splitInstalled, hasVideo)`), the Theater chip after the PiP chip in `PlayerControlsOverlay` (`ic_theater`, `player_theater`), `PlayerActivity.EXTRA_POSITION` (`edendale.positionMillis`, honoured once when present), and the strings in all 19 locale files.
- [x] **N.6** The activity (`:xr`): `Session.create(activity)` (every other `SessionCreateResult` finishes with a log line and returns to the player), `Config` with device tracking, `scene.requestFullSpaceMode()`, passthrough opacity 0 and a black environment, the room as a `GltfModelEntity` under the activity space posed by `roomPose(seat, screenCenter)` with the seat read back from the loaded model's nodes (`GltfModelEntity.nodes` by name) and the GLB's JSON as the fallback, the picture `SurfaceEntity` (`Shape.Quad`, `StereoMode.MONO`, `player.setVideoSurface(surface)`), the subtitle panel, the player built as `PlayerActivity` builds its own, the handover's choices applied after the per-title preferences (`ContentPlayerPreferences`), progress every 5 s while playing and on pause, stop, end, and exit (`LocalDataStore.updateWatchProgress`, `COMPLETE_FRACTION`), `sync.flushSoon()` in `onStop`, pause on `onPause` and resume on `onResume`, `onBackPressedDispatcher` = Exit, the two exit paths of N-D7, a failure card on `onPlayerError`, and a log line per room load naming where `Seat` and `Screen` were found.
- [x] **N.7** Dock and seat panel (`:xr`): a `PanelEntity` per panel hosting a `ComposeView` under `EdendaleTheme`; a `Choreographer` loop reads the head, drives `DockFollower`, and poses both panels (the resting eye `(0, 1.2, 0)` after 45 frames without tracking); the dock: Close (exit), title and subtitle, timeline with scrub preview, skip back and forward with the viewer's intervals, play/pause (a spinner while buffering), Skip Intro/Recap/Credits through `PlayerSegmentController`, the Up Next card (returns to the player on the next episode), Audio and Subtitles lists in place of the transport, the Room list; the seat panel (only when the room has markers): the map on a `Canvas` with the screen bar, pinch to sit, slide to sweep, the seat name in the header, Your Seat / Best Seat, Lights, Recentre with its caption. Colors from `EdendaleColors` only; every icon control has a `contentDescription`.
- [x] **N.8** Rooms (`:xr`): the three GLBs under `xr/src/main/assets/theater/`, exported per N-D9; switching rooms destroys the old entity and places the new one at that room's remembered seat while the video keeps playing; the last room is written to `theater`/`room`; a missing or unreadable GLB logs and shows the picture alone 5 m ahead at head height (the Quest's fallback, without the sky).
- [x] **N.9** Lights and Recentre (`:xr`): Lights scales the brightness of every baked material except the `Screen` and `Projector_beam` nodes (per-node material override with the room's own textures where the SDK allows it; otherwise the Deviations log records what was possible); Recentre applies `recenterPose(head)` to a container above the room, resets the follower, and fades the room 0.2 s out and 0.3 s in; a system space change (`Scene.setSpaceChangedListener`) re-reads the head and re-places.
- [x] **N.10** Strings: the module's own `strings.xml` in all 19 locale folders, taken verbatim from the visionOS theater's catalogue (`Shared/Theater.xcstrings`: Theatre, the three rooms, Leave Theatre, Seats, Screen, Your Seat, Best Seat, Row %1$d Seat %2$d, Lights, Normal, Dim, Dark, Recentre, its caption) and the Quest's (`player_theater`, Exit theater, Position, the failure line); British spelling in the base `values`, the en-rUS overrides as the catalogue has them.
- [x] **N.T1** (JVM) `TheaterMathTest`, `TheaterGlbTest` (the Quest's eight GLB cases adapted, plus `SeatMap` markers through parents and a missing `SeatMap`), `TheaterGeometryTest` and `TheaterSeatMapTest` (the 32 platform-independent visionOS cases: canvas, screen and seat, seat map, recentring, lights, subtitles, plus the Quest's picture-fit cases), `TheaterDockTest` (the 12 dock cases), `XrTheaterHandoverTest` (round trip of every extra, null for a foreign intent), `XrTheaterRulesTest`.
- [x] **N.11** Docs: README (an "Immersive theater (Android XR)" section: what it is, the rooms and their `scenes` state, the dock, seats, lights, Recentre, what stays on the device, the module and its delivery, the build and install commands, comfort), the dependency table (Jetpack XR, Apache-2.0), Manual device checks (an Android XR headset bullet), and this file's Tracking table.
- [x] **N.D1** (2026-11-09, on throwaway AVDs; the panels were driven through accessibility actions, see Findings; the dock's 12° dead zone rests on its JVM cases and the seat map was not tapped: a stored seat and Best Seat stood in) On the Android XR emulator (the Google Play XR system image, API 34; a throwaway AVD, not the owner's): the chip appears only on XR with the split installed (base-only install and a phone AVD show none); enter from a local file and a network source; each of the three rooms loads facing the viewer with the picture on its screen, baked colours showing; the dock follows a head turn past 12° and stays still inside it; seat changes across the map; Lights; Recentre; subtitles and an audio switch; Exit returns to the player at the position; the natural end returns to the library with the title complete; progress reaches Continue Watching and the 27.1 sync flush runs; the emulator's recenter.
- [ ] **N.D2** Owner, on a headset: frame rate at the display's native rate while playing in each room (the heaviest is the Performance Theatre, 149k triangles), 30 minutes for heat and dropped frames, the GPU memory of the 4096² atlases (about 85 MB each with mipmaps; KTX2 is the next step if it matters), the beam as a faint haze, lying-back Recentre, and the Play delivery itself (the split arrives with the app on an XR device and on no other).

---

## Section O — Parental controls (FEATURES-27.1.md §4, F2)

Decided by the owner on 2026-11-08 ("apply the other enhancements listed on main"): F2 comes to Android first (FEATURES-27.1.md §10, rollout order), building on the Young Audience filter the app already has (`YoungAudienceFilter.kt`, `ContentCertification.kt`, `TmdbContentCertificationProvider.kt`) rather than beside it. No other branch has F2 yet; `apple-27.1` only renamed its section to "Parental Controls". Open questions take the recommended answers below; the owner can change any of them.

**Rules:** FEATURES-27.1 §4 verbatim: controls are device-local and never synced, handed off, or sent anywhere (Hard rule 5; constraint 6); the ceiling comes from TMDB certification data; titles without a certification are restricted; the PIN is separate from the pairing code, stored as a salted slow hash (Argon2id), never in plaintext, never logged (Hard rule 2); a restricted profile cannot change the ceiling, the PIN, or sync settings without the PIN; failed attempts back off with an increasing delay; no parental setting goes over the network. The API is a Kotlin interface; nothing is shared with another branch.

**Decisions**

| # | Decision | Why |
|---|---|---|
| O-D1 | **What "lock settings" covers:** the whole Settings sheet (one gate where `onOpenSettings` fires, on TV and handhelds). The player's own Adjustments panel is not gated. | One gate covers the Wyzie key, accounts, Link to a TV, Skip Prompts, Watch Next, and every link in Settings; the player panel only adjusts playback. |
| O-D2 | **PIN:** digits only, 4–8 long, entered masked (`NumberPassword`); on TV through the on-screen keyboard. | The two code dialogs already do this; it is the first branch to ship, so it sets the parity rule. |
| O-D3 | **Back-off:** attempts 1–3 free; then 30 s, 1 min, 5 min, 15 min, and 60 min for every further failure; `failures` and the deadline persist (wall clock and elapsed-realtime, the later wins) so a force-stop or clock change doesn't reset them; a correct PIN resets. The dialog shows the countdown. | The pairing lockout is in-memory only; this one must survive. |
| O-D4 | **Unlock window:** a correct PIN unlocks for 5 minutes or until the Settings sheet closes, whichever comes first; a gated action outside Settings (a library edit, a pairing link) asks again after that. | A parent changing several rows types the PIN once. |
| O-D5 | **Region:** the device region, as the filter uses today; when TMDB publishes no certification list for it, the ceiling and the per-title lookups both use `US` and the section says so. | The ceiling and the lookups must agree, or everything is "unrated" and hidden. |
| O-D6 | **Young Audience Friendly stays as it is** (PG / PG-13 and their TV labels), as a preset beside the new ceiling; with both on, both must allow a title. Redefining the preset is a product-rule change for every branch (the Windows U.3 critique) and is left to the owner. | Parity (constraint 7). |
| O-D7 | **Stores:** the preset stays in `edendale_audience` (backed up, as today); the PIN, the ceiling, the switches, the back-off state, and the cached certification lists live in a new `EncryptedSharedPreferences` file `edendale_parental`, excluded from backup and transfer in both rule files. A restored device keeps the filter but has no PIN. | Hard rule 5; the Keystore key never leaves the device anyway. |
| O-D8 | **Titles without a TMDB id** (un-enriched files, "Open with") are restricted while a ceiling or the preset is on, in lists and at playback. | FEATURES §4: no certification means restricted. |
| O-D9 | **Offline:** today's behaviour stays (unverified titles stay hidden until a lookup succeeds); persisting certifications in Room is a later option. | No schema change in 27.1. |
| O-D10 | **Trailers and links:** with the switch on, the detail page hides Watch Trailer and never requests `/videos`; the links in Settings sit behind the Settings lock; the OAuth sign-in and provider-console links sit behind the account flows, which the library lock covers. | — |
| O-D11 | **Argon2id** (Bouncy Castle's lightweight API, already a dependency): 64 MiB, 3 iterations, parallelism 1, 16-byte salt, 32-byte hash, parameters stored beside the hash; the same on TV. PBKDF2 is the fallback branch Android never takes. | ≈0.2–0.5 s on a phone; nothing new to depend on. |
| O-D12 | The "Audience" section becomes **"Parental Controls"**, in the same place (second, after About), as `apple-27.1` names it. | Parity. |
| O-D13 | A pairing link (`edendale://handoff`) while the controls are locked asks for the PIN first; a wrong or cancelled PIN drops the link. | Receiving an offer turns sync on, which the acceptance criteria forbid without the PIN. |
| O-D14 | The Sync section stays readable while locked; every button in it asks for the PIN. | The criteria forbid changing, not seeing. |
| O-D15 | The legal review of age-rating and kids-category rules (FEATURES §10 item 11) and the Play declarations are the owner's; no "kids" category claim is made. | — |

- [x] **O.1** Pure rules, `src/main/kotlin/com/babasama/edendale/parental/` (no Android imports): `CertificationEntry`, `CertificationLists(region, movie, tv)`, `RatingCeiling(region, movie, tv)`, `ParentalPolicy(youngAudienceFriendly, ceiling, hideRestrictedTitles, blockTrailersAndLinks, lockSettings, lockLibraryEdits)` with `isActive`; `ParentalRules`: `allows(certification, mediaType, ceiling, lists)` (normalise as the preset does, look the label up in that type's list, unknown or `NR` or no list → false, `order <= ceiling's order`), `parseCertificationLists(payload, region)` for `/certification/{movie,tv}/list`, `effectiveRegion(region, available)` (O-D5), `retryDelayMillis(failures)` (O-D3), `decision(lookup, mediaType, policy, lists)` combining the preset and the ceiling; `PinHasher` (Argon2id per O-D11, `PinRecord(kdf, m, t, p, salt, hash)`, constant-time compare, the `CharArray` wiped); `PinRateLimit` (pure state machine over `failures`, two deadlines, `now`).
- [x] **O.2** Store: `interface ParentalStore` (strings, booleans, longs, remove, listener) with `EncryptedParentalStore` over `edendale_parental` (the `TmdbSessionStore` shape; `commit()` on `Dispatchers.IO`) and `InMemoryParentalStore` for tests; `edendale_parental.xml` added to `backup_rules.xml` and to both sections of `data_extraction_rules.xml`.
- [x] **O.3** The API: `interface ParentalControls` (`policy: StateFlow<ParentalPolicy>`, `hasPin`, `lockedUntilMillis`, `isUnlocked(now)`, `lock()`, `verifyPin`, `setPin(current?, new)`, `clearPin(current)`, `updatePolicy(now) { }` refused while a PIN is set and locked, `lists: StateFlow<CertificationLists?>`) and `DeviceParentalControls`; `TmdbApi.certificationList(type)` beside `contentCertification`; the lists fetched when the section opens or a ceiling is active and none is cached, kept in memory and in the store as JSON with a fetch time, refreshed after a day; a `parentalPolicyChanges(context)` flow for the Watch Next publisher.
- [x] **O.4** The filter: `YoungAudienceFilter` takes the policy and the lists; `audienceDecision` becomes `ParentalRules.decision`; `isEnabled` means `policy.isActive`; the cache key includes the policy so a ceiling change clears decisions as a region change does; every surface in the filter's list (home, Downloaded, Search, Watchlist, Filmography, detail, Watch Next) is unchanged.
- [x] **O.5** Gates: a `rememberParentalGate()` helper that shows the PIN dialog unless unlocked; Settings opening (O-D1); library edits (Add Local Folder, Link Network Source, Remove, Rescan, Remove from Library, Choose More Videos, the keyboard shortcuts; the automatic rescan is not an edit); every Sync button, both pairing roles, Remove a Device, Turn Off, and the pairing deep link (O-D13); `PlayerActivity` refuses a restricted or id-less item while a policy is active (Watch Next rows and deep links included) and `loadPlayerPlaylist`'s entries are filtered the way `visibleLibrary` filters (episodes follow their show), so the panel, Up Next, auto-advance, and MediaSession next/previous never reach a restricted title; the trailer rule of O-D10.
- [x] **O.6** Settings UI: the "Parental Controls" section (O-D12): Young Audience Friendly; Maximum movie rating and Maximum TV rating rows opening a picker of the region's labels with "No limit" first and the region in the detail line (loading and error lines when the lists aren't there); Hide restricted titles; Block trailers and links; Require PIN for settings; Require PIN for library changes; Set / Change / Remove PIN; a note that nothing here leaves the device. `PinDialog` (masked digits, Go submits, the error line, the countdown) and the two-step Set/Change PIN dialog. D-pad reachable, radio semantics in the picker. Strings in all 19 locale files (English copy in the research note; Apple's "Parental Controls" verbatim); the eight existing `audience_*`/`settings_young_audience_*` keys get their missing translations at the same time.
- [x] **O.7** Docs: README (a "Parental controls" subsection: what each switch does, the device-local store and backup exclusion, the Argon2id PIN and the back-off, what is gated, that nothing is sent anywhere), Privacy unchanged, and this file's Tracking table.
- [x] **O.T1** (JVM) `ParentalRulesTest` (US movie `G` and `PG-13` under a `PG-13` ceiling allowed, `R` not; GB `12A` vs `12`; a TV label never qualifies a movie; `NR`, unknown, blank, `Unrated`, `Unavailable`; `PG-13`/`PG 13`/`PG13`; list parsing from a recorded payload; the US fallback), `PinHasherTest` (fixed salt is deterministic, wrong PIN fails, record round trip, the array is wiped, the PBKDF2 branch), `PinRateLimitTest` (the schedule, persistence across a "relaunch" through the in-memory store, the later deadline wins, success resets), `ParentalControlsTest` (a locked profile cannot change the ceiling, the PIN, or the policy; unlock expiry; the deep-link rule), `YoungAudienceFilterTest` extended with ceiling cases, a `TmdbApiTest` case that `/certification/movie/list` goes out with no extra parameter, and a `LogHygieneTest`-style check that no PIN, salt, or hash reaches the log sink; a test that the sync device file and the handoff offer carry no parental field.
- [x] **O.D1** (2026-11-09, on throwaway phone and TV AVDs; TalkBack itself was not run, the picker's and the switches' radio and toggle states were read from the accessibility tree) On the phone and TV emulators (ask the owner before using theirs): set a PIN, see the back-off after four wrong tries and its countdown survive a force-stop, set a `PG-13` ceiling and watch an `R` title disappear from Home, Search, Downloaded, the player's episode list and Watch Next, open its detail page and get the restricted state, the trailer button gone with the switch on, Settings and Add Local Folder asking for the PIN, a pairing link asking for the PIN, Remove PIN, and TalkBack on the picker.

---

## Section P — The owner's ten requests (2026-11-09, outside the plan)

Asked on 2026-11-09 and built in scratch worktrees off a snapshot of the shared checkout (Room v5 as the shared base), each stream implemented, reviewed by two reviewers, and fixed, then merged with a cross-cutting review. Android first: Apple and Windows don't have these yet (Deviations log).

**Rules:** Hard rules 4 and 5 hold. The source memory, the brightness override, and Rotation Lock stay on the device and are never synced or backed up; the Enhancement preset is a device preference. Room goes to **v5** (`MIGRATION_4_5`, `schemas/.../5.json`): `library_folder.endpoint` and `library_folder.scanStartedAt`.

**Decisions taken without asking (the owner may overrule):** brightness stays a window override (no `WRITE_SETTINGS`); a tap on a multi-source episode opens Play From; the resume label reuses "Resume"; Rotation Lock is session-only, as on iOS; the Enhancement preset is stored only once the viewer picks one; a merged poster carries no chevron of its own (Play From sits beside the page's Play, as on iOS, and on the poster's long press).

- [x] **P.1** Downloaded and Search open the detail page, as on iOS ("when selecting a downloaded item, it does not open detail view like iOS does"). One page for every title: `DetailSource { Tmdb, LocalMovie, LocalShow }` (Apple's `MediaDetailSource`); `detailHeader` falls back to the library row, so an unmatched file or show, a build without TMDB, or an offline device still gets a page. A movie page has Play/Resume (`PlayerLogic.resumeFraction`, shared with the player) and `PlayFromButton`; a show page lists the library's episodes by season, merged across every row with the same key or TMDB id (`pageShow`), in place of TMDB's season browser, with the episode rows the old show page had. A TMDB page for a title the library holds does the same. `pageGateRef` + `detailRestricted` fail closed for an unmatched movie or show while a parental rule is on (O-D8; the old show page never checked). Marks are read before TMDB and shown only once read. The shell keeps its saved state under full-screen pages (`ShellReturn.kt`): Back lands on the same row, and on TV on the same poster; a TV show page opens on its first episode. The open page survives process death. `LocalShowHost`, `LocalShowScreen`, and `openShowKey` are gone. `edendale://library/…` links stay unsupported (their ids are Apple's UUIDs).
- [x] **P.2** One item per title across endpoints ("direct SMB and Tailscale SMB, 2 different items are shown, make it into 1 single item"). `fileIdentity` names a file apart from its link: SMB/NFS/SFTP/WebDAV path without host or port (`dav` = `davs`), S3 bucket and key, the provider's file ids, a SAF document id. `libraryTitles` unites rows that share a TMDB id or a file identity (union-find, transitive), so an alias whose match is pending joins its matched twin and plays under its id; rows matched to different TMDB titles never unite. `showTitles` merges show rows by TMDB id and counts each episode once. Downloaded and Search show one `LibraryMovieTile` per title ("N sources"); its long press (Menu on TV) opens Play From, then Remove from Library per copy whose removal lasts. Search groups before it matches, and matches again after the parental filter. Continue Watching, the tile's page, and Play start the same copy (`moviePlayStart`). TV's focus return keys a poster by a file of its title (`posterKey`), so a match while its page is open doesn't lose it.
- [x] **P.3** Play From names copies by endpoint ("longs-mac-studio.local", "100.x", "Google Drive - email"): `SourceEndpoint`, `library_folder.endpoint` (set at link time and refreshed from the vault once per process, never the network), order local → LAN → Tailscale (100.64/10, `*.ts.net`, fd7a:115c:a1e0::/48) → other. Source status lines and Settings' Remove dialog no longer show a 32-hex account key.
- [x] **P.4** Delete Watch History, here and through sync (`WatchHistorySettings.kt`, `SyncCoordinator.deleteWatchHistory`): with sync on, a full read, then one tombstone per title and an empty `watch_progress` in one transaction, and this device's file written at once; with sync off, a local purge without tombstones. Continue Watching's long press offers Clear Progress, as on Apple's resume card. The home hero drops a deleted record's resume state.
- [x] **P.5** An index interrupted by app termination resumes at the next launch: `scanStartedAt` is set when a scan begins and cleared by its single-transaction commit (`LibraryDao.applyScan`) or a recorded failure; `MainActivity.onCreate` runs `LibraryRepository.resumeInterruptedWork()` once per process (marked sources rescan regardless of the 15-minute throttle, then a TMDB pass while matches are pending). One scan per source at a time; enrichment through `CoalescingRunner` with UPDATE-only match writes. `lastScannedAt` is now written with the results (D.3).
- [x] **P.6** The next episode stays on its source: the playing source counts as reachable in the player's episode list (`playerEpisodeRows`, `whilePlaying`), and a device-local `PlaybackSourceMemory` (`edendale_playback_sources`, excluded from backup and transfer, never synced, forgotten with its source) starts the show page, Continue Watching, Watch Next, the hero, the detail page, and the player loader on the source a title last played from. A launch that fell back past a flagged remembered source doesn't overwrite it (`PlaybackSources.remembersSource`, `EXTRA_REMEMBER_SOURCE`).
- [x] **P.7** Selecting an episode with several sources opens Play From at a trailing chevron, the last-played copy checked ("Last played"); the player's Episodes panel rows get the same chevron and switch in place, carrying the position once the episode has started (`PlayerLogic.copySwitchStartMillis`). Episode rows were restructured so the D-pad reaches the watched tick.
- [x] **P.8** Player swipes move the device's media volume (`STREAM_MUSIC`, the hearing warning honoured, the player's gain only on fixed-volume routes) and the screen brightness (SystemUI's slider curve through a window override held only while the player is top-resumed and not in PiP; the viewer's own slider wins). None on TV; no brightness on XR.
- [x] **P.9** The Enhancement preset, Sharpness, and Denoise are saved on the device (`video.enhancement`, never Show Original) and win on every device class; D11 amended.
- [x] **P.10** A Rotation Lock chip beside Picture in Picture: the exact orientation on screen, session-only, kept through episode switches, PiP, and relaunches; hidden on TV, XR, multi-window, PiP, and Android 16 at sw ≥ 600 dp.
- [x] **P.T1** (JVM) `LibraryTitlesTest`, `DetailPageTest`, `PlaybackSourcesTest`, `SourceEndpointTest`, `LibraryPresentationTest`, `PlayerPlaylistTest`, `PlayerLogicTest`, `WatchNextTest`, `SyncHistoryClearTest`, `SyncRowsTest`, `ContinueWatchingClearProgressTest`, `HomeCatalogProgressTest`, `SourceScanRulesTest`, `ScanPlanTest`, `CoalescingRunnerTest`, `LibraryActivityTest`, `DeviceVolumeTest`, `ScreenBrightnessTest`, `OrientationLockTest`, `PlayerChromeStateTest`, `PlayerControlPreferencesTest`, `VideoEnhancementTest`; instrumented `EdendaleMigrationInstrumentedTest` (4 → 5), `LibraryDaoInstrumentedTest`, `WatchHistoryClearInstrumentedTest` (12 tests, pass on a throwaway API 36 TV emulator, 2026-11-10). Whole suite: 840 JVM tests, 0 failures.
- [x] **P.D1** (2026-11-10, on the throwaway `edendale_phone` (API 37) and `edendale_tv` (API 36) emulators; one WebDAV share served from the Mac on two ports, 10.0.2.2:18080 and :18081, standing in for a LAN name and a Tailscale address, plus the phone's own folder) Downloaded shows one poster per title ("3 sources" for local + both links; "2 sources" for an unmatched file reached through both links; Severance "3 episodes" from nine files); the long press and the page's Play From name "This device", "10.0.2.2:18080", "10.0.2.2:18081"; a Play From pick plays from that link (server log), is remembered, marked "Last played", and after a force-stop Continue Watching and the page's Resume start it; with that link down and flagged, Resume falls back to the phone's copy without forgetting the pick, and returns to it once the flag clears; a show page lists each episode once, an episode's tap opens Play From, the next episode (media Next) stays on the link playing, and a font-size relaunch carries on with the episode and position playing; Search's From Your Library shows one tile per title with the same menu; an unmatched file's page shows its own title with Play and Play From; Clear Progress and Delete Watch History (sync off) empty Continue Watching; with Young Audience Friendly on, the unmatched file and Severance are hidden, and with hiding off their pages show the restricted state. TV: the Menu key opens a poster's Play From and Back closes only the menu; a movie page opens on Resume and a show page on its first episode, with the hero in view; D-pad Down walks the episodes; Back returns focus to the poster. One fix (Findings).
- [ ] **P.D2** Owner, on hardware: one share over the real LAN and Tailscale; Delete Everywhere with sync on across two devices; killing the app during a long scan and the next launch finishing it; the volume and brightness swipes against the hardware keys and Quick Settings on Android 12+ and 8–11; Rotation Lock after connecting a keyboard; the XR panel.

---

## Section Q — The Downloaded page's section picker (2026-11-11, outside the plan)

Asked by the owner on 2026-11-11: a selection at the top of Downloaded for All, Watching, Movies, and Shows, as segmented buttons, at the top left on larger displays. `apple-27.1` has the same choice as `DownloadedSectionPicker` (`94a8b4f`); Windows has the sections but no picker yet (Deviations log).

**Rules:** nothing new is stored or sent: the choice is the navigation row the shell already saves (`NavigationItem.Saver`). The rules are pure Kotlin in `LibrarySections.kt`, tested in the hermetic suite; colors come from `EdendaleColors` and the Material scheme alone.

- [x] **Q.1** The picker. `DownloadedSection.pickerChoices` (the whole page, then each section with something to show) and `isPickerOffered` (two or more sections, since one section alone lists what the whole page lists); `shortTitle` so four choices share a phone-width row (`section_all`, `section_watching_short`, `section_shows_short`, in `values` and the 14 translated locales; the four `values-en-r*` override files fall back). `ArchiveSegmentedButtons`: one outlined pill, the chosen segment a dark fill with gold ink and a gold edge, a focused one filled gold as on the TV tab strip, radio semantics with the section's full name for TalkBack, 48 dp tall, a label that shrinks before it ellipsizes, and Start and End stopping at the ends. Material's own `SegmentedButton` keeps 26 dp for a check mark in every segment, which four labels can't spare at phone width. `DownloadedSectionPicker` is pinned under the top bar on phones and tablets while the bar scrolls away above it, across the width under 600 dp and at the top left at its own width from there; on TV it is the first item under the page heading.
- [x] **Q.2** One page for every section. `NavigationItem.shown` (a Downloaded section shows at every width, a Watchlist section only beside its rows), `resolved(…, sectionRows)` (a section falls back to the whole page once it empties, or once the picker stops being offered where no row lists it; a list still loading is left alone), and `lights` (a rail, a bottom bar, and the TV strip keep the tab lit over its sections; extended navigation lights the section's own row). `DownloadedScreen` is no longer keyed by its section: a choice keeps the page, its sweep, and the remote's focus, and starts the new section at the top with the bar out; a page built afresh (Back from a title) keeps the place it saved. The section list is read only on the Downloaded tab or beside the section rows. At 1100 dp and wider the Downloaded rows and the picker show the same choice and either changes it (owner, 2026-11-11: "Keep both").
- [x] **Q.T1** (JVM) `LibrarySectionsTest`: the choices and when they are offered, the short titles, a loading list left alone, the fallback where no row lists a section, sections at every width, the tab staying lit. Whole suite: 845 JVM tests, 0 failures.
- [x] **Q.D1** (2026-11-11, on the throwaway `edendale_phone` (API 37) and `edendale_tv` (API 36) emulators; see the Handoff log for who ran what) A phone at 448 dp, and at 360 dp in German, in French at 130 % text, and in Mexican Spanish; a tablet at 900 dp with the rail; a desktop window at 1280 dp with extended navigation; a TV with the D-pad (Down from the tab strip lands on the picker, Up reaches Add a source, Left and Right stop at the ends, Down reaches the posters, and a choice keeps the remote on the picker). After the review's fixes, again at 900 dp (the rail keeps Downloaded lit with Movies chosen) and at 448 dp (the picker is there on arriving from another tab; Shows lists the one show). Not run: a right-to-left layout, TalkBack itself, and the TV after the review's fixes.
- [ ] **Q.D2** Owner, on hardware: TalkBack reading a segment by its full name and state; a phone at the largest font size; a foldable crossing 600 dp with a section chosen; a TV remote; and whether the short labels read well in each language (the review questioned Dutch "Bezig" and German "Begonnen" for Watching).

---

## Section R — Gaps from the 2026-11-11 review

On 2026-11-11 six reviewers went over `android-27.1`, each finding checked by a second reviewer. These are the confirmed gaps an agent could build, one commit each; the owner's items (M.7, the Play notes, the pull request) and the hardware checks stay where they are.

**Rules:** Hard rules 2, 3, 5, and 8 apply. Strings go in `values` and the 14 translated locales; the four `values-en-r*` files change only where they already carry a key. Pure rules are tested in the hermetic suite.

- [x] **R.1** Link Source's privacy notes said Edendale asks for "read-only access" and "never uploads or changes anything", but 27.1 sync writes its own files (`drive.file`, `Files.ReadWrite.AppFolder`, `files.content.write`). The notes now follow `apple-27.1`'s `CloudAccountStep.privacyNote`: Google Drive "sees only those and the sync files it creates … and changes nothing else" (the TV note likewise), OneDrive "writes only to its own Apps/Edendale folder, when you sync there" (`link_source_onedrive_privacy`, new), and Dropbox "writes only its sync files, in the Edendale folder, when you sync there" (`link_source_cloud_privacy`, which loses its provider argument). The translations take Apple's catalogue in each file's own terms. The four `values-en-r*` files carried the three old keys and were updated too. README's Storage services table and Cloud accounts say the same. `StringResourcesTest` checks the notes in every locale and that every locale keeps the English placeholders.
- [x] **R.2** A TV that received a Google Drive location from another ecosystem, or a folder location, was offered a sign-in or a folder picker it can't complete, and every follow-up reported `location` as stored before anything was. `HandoffV2.locationStep(location, receiverPlatform)` now decides: `Store`, `PickFolder`, `GoogleSignIn`, or `Unavailable(reason)` where the receiver is a TV (`canPickFolder`, `canSignInToGoogle`; the reasons are Apple's, `unavailable` and `google-sign-in-unavailable`). `SyncHandoff.receive` reports an unavailable location as failed and shows `sync_google_tv_note` (§9.8: "set up Google Drive from an Android phone") or the new `sync_folder_tv_note` with Close only (`FollowUp.OnAnotherDevice`); a phone's folder or Google follow-up leaves `location` out of the result until the viewer finishes it, and the sender then says so (`HandoffV2.locationPending`, `pairing_location_pending`). `HandoffV2Test` covers the steps on a phone, the Quest, and both TVs, an iPhone's Google location reaching an Android TV, and the sender's line. **Deviation from `apple-27.1`:** Apple's phone receiver still lists `location` as stored before the follow-up (`SyncHandoff.receive`).
- [x] **R.3** FEATURES-27.1.md §5.1: files in the old location stay until the user deletes them, "and the app offers to"; only Turn Off offered. `SyncCoordinator.enable` now remembers the location it replaces (`SyncLocation.sameFiles`, and the pure `SyncLocation.previousAfter`: the latest one replaced wins, as on Apple, and a remembered location that is chosen again is forgotten, so the app never offers to delete the file it writes) in `edendale_sync.xml` (`previousLocation`, excluded from backup and transfer with the rest of the file). Settings → Sync then shows Apple's note, "This device's file is still in %1$s (%2$s). Delete it, or keep it there.", with **Delete My File There** (`deletePreviousFile`, `SyncEngine.removeOwnFile` against the old storage; "Deleting…" while it runs; a failure is shown and the location kept for another try) and **Keep It**, both behind the parental PIN. A handoff that replaces the location gets the same offer. The strings are `apple-27.1`'s own copy for this control with its translations (`SyncSection.swift`), rather than the Turn Off strings, whose buttons say "Turn Off". `SyncLocationChangeTest`: what counts as the same files, the remembered location across replace, return, turn off and on, and deleting the old file leaving the new location, the other devices' files, and the local copy alone.
- [x] **R.4** SYNC-PROTOCOL.md §9.7 (clarified on `main` at `768f368`, merged as `0106d0e`): during 27.1 a reader also accepts the nested `credential` shape Apple builds before 27.1 send, reading a `Login` without `user` from `credential` (`username`, `password`, `port`, and `s3` with `usesPathStyle`). `AccountHandoff.decodeLogin` threw `MalformedMessage` instead, which failed the whole offer. It now falls back to `credential` when `user` is absent; the flat fields win when both are there (Apple 27.1 writes both); a login with neither is still malformed. Android still writes only the flat shape. `HandoffV2Test`: an offer whose location login (SFTP, port in `credential`) and extra login (S3) are nested, both shapes in one object, and neither. Section M's header now names `768f368`.
- [ ] **R.5** The trailer embed sends a `Referer`, so YouTube doesn't answer with error 153.
- [ ] **R.6** Small items: README's schema number and release steps for the `:xr` module, the Watchlist strings in every locale, the instrumented workflow's branches.
- [ ] **R.7** Deviations recorded: no one-off WorkManager job after local changes (FEATURES §8), no folder watcher (§5.4), the cloud account's e-mail in Auto Backup.
- [ ] **R.D1** On the throwaway `edendale_phone` and `edendale_tv` emulators: the TV note for a Google location and a folder location, the delete-old-file prompt, and a trailer from the detail page.

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
- **The XR emulator stalls on any non-zero start position of the Tears of
  Steel MOV (found 2026-11-09).** `setMediaItem(item, positionMs)` leaves
  ExoPlayer in BUFFERING for good (state 2, 33 s buffered, no video size) on
  the Google Play XR image (API 34, `c2.goldfish.h264.decoder`), in the 2D
  player (`edendale.positionMillis` 1000, 4000, 8193) as much as in the
  theater, while a start from 0 plays to the end; the 5 s Matroska fixture
  (`decoder-subtitles.mkv`) seeks fine. The theater's picture was black for
  an evening for that reason alone — a canvas painted on the same
  `SurfaceEntity` showed, and the player sat in state 2 even with no video
  surface attached. The hardware check should use a real file.
- **The XR emulator takes no pointer input for spatial panels (found
  2026-11-09).** The emulator's gRPC hand, mouse and eye injection, `input
  tap`, and ENTER / DPAD_CENTER / SPACE on a focused dock button activate
  nothing; TAB moves the focus pill; the head pose (`xr_head_rotation_event`)
  works. So the seat map, the Room list, Recentre, Lights, Up Next, and the
  audio and subtitle lists were not tapped on the emulator; Lights and the
  rooms were checked by presetting the `theater` preferences file with
  `run-as` before launch. Back (`input keyevent 4`) does reach the panels.
  **Accessibility actions do (2026-11-09):** `uiautomator dump --windows`
  lists the dock and the seat panel as embedded windows with every label, and
  a throwaway self-instrumenting APK (built from the SDK's `javac`, `d8`,
  `aapt2`, and `apksigner`; target package itself, so `am instrument` doesn't
  kill the app) presses a node's clickable ancestor with `ACTION_CLICK`, seeks
  the Position slider with `ACTION_SET_PROGRESS`, and fills text fields with
  `ACTION_SET_TEXT`. With it N.D1 ran in full.
- **In Full Space the system Back goes to the focused panel window (found
  2026-11-09).** `dumpsys window` showed `FocusedWindows: Embedded{}` (a
  `PanelEntity`'s window); the activity's own window had no focus, and with
  `mainPanelEntity.setEnabled(false)` nothing had. Hence the N.7 Deviation.
- **SceneCore 1.0.0 material facts (found 2026-11-09).** Material overrides
  live on `GltfModelNode` (`setMaterialOverride(material[, primitiveIndex])`,
  `clearMaterialOverride`), not on `GltfModelEntity`; `KhronosPbrMaterial`
  and `KhronosUnlitMaterial` take textures and factors; `Texture.create(
  session, path)` loads from `assets/` only ("expects a path relative to
  `assets/`" for an absolute path). The rooms' baked materials are a black
  base colour with an emissive atlas at factor 1, the screen's a textureless
  emissive 0.012, the beam's a base-colour texture with BLEND, so "every
  baked node" is "every node whose material has an emissive texture".
- **The activity space's origin is not the floor (found 2026-11-08).** On the
  emulator the tracked head sits 0.15 m above the activity space's y = 0, so
  `TheaterStageRules.seatingPose` puts the seat's floor 1.2 m under the eyes
  rather than at y = 0; a headset's real height is N.D2's to check. The
  system's recentre moves the activity space and both `setSpaceChangedListener`
  and `addOriginChangedListener` fire; the stage re-seats on either.
- **The theater's lists and Lights didn't say which item is chosen (found
  2026-11-09 in N.D1, fixed in bc9d5c6).** The Audio, Subtitles, and Room rows
  and the Normal / Dim / Dark buttons showed the choice only in gold and a
  check icon, and the chevron that closes a list had no label. The rows are
  selectable radio buttons now, Lights carries radio semantics, and the
  chevron reads Close; on the emulator the chosen track, room, and level
  report `checked=true`.
- **A WebDAV sync location prefilled `https://` for a plain-http server
  (found 2026-11-09 in N.D1, fixed in c678e74).** The saved login keeps only
  the host and port; the chooser now takes the scheme from a source linked on
  that server (`dav://` is http), else https (`WebDavSyncFolderTest`).
- **Settings switch rows had no on/off state for TalkBack (found 2026-11-09 in
  O.D1, pre-existing, fixed in 58a8fd1).** `SettingsSwitchRow`'s `Switch` had
  `onCheckedChange = null` inside a plain clickable `Surface`, so every switch
  row (the six Parental Controls rows among them) read as a button. The row is
  Material's toggleable `Surface` with the Switch role now.
- **A second pairing link could vanish with no prompt (found 2026-11-09 in
  O.D1, fixed in 708c0d8).** A scanned link opens a new `MainActivity` over the
  old one; each window has its own gate over the one process-wide
  `PendingPairing.link`. With an earlier link's PIN dialog still up below, that
  window refused the new link as a second question, and the refusal cleared the
  shared link before the new window composed. A newer pairing link now replaces
  the waiting one, a refusal clears only the link it refused, and a window
  whose link went away withdraws its dialog. (Sending an identical link to the
  task's root activity is swallowed by Android itself: the task comes forward
  and no intent is delivered.)

- **An interrupted scan looked complete (found 2026-11-09, fixed in P.5).**
  A scan stamped its source as freshly scanned before storing what it found,
  so a source killed while its results were being written stayed incomplete
  until 15 minutes had passed and Downloaded was opened, and TMDB matching
  waited for another successful scan. The stamp now lands in the scan's one
  transaction, and a `scanStartedAt` left behind is rescanned at launch.
- **The next episode left a source its last scan had flagged (found
  2026-11-09, fixed in P.6).** `playerEpisodes` kept the playing copy but
  moved every later episode to another source whenever the playing source's
  last scan had failed, which happens routinely with one share linked over the
  LAN and through Tailscale; the Episodes panel also labelled the playing
  source Unavailable. Nothing remembered the source between launches either.
- **Closing the player, or switching copies, before an item started wiped
  its resume point (found in review 2026-11-09, fixed in P.7; the close case
  was pre-existing).** Until READY the player reads 0:00. Progress writes now
  wait until the item has started.
- **An episode row's watched tick was unreachable with the D-pad
  (pre-existing, fixed in P.7).** Compose's 2-D focus search never moves from a
  focused node into its own children; the row's tappable part and its trailing
  controls are now siblings, and so are the player panel's.
- **Source status lines and Settings' Remove dialog named cloud and S3
  sources by their 32-hex account key (pre-existing, fixed in P.3).**
- **The volume swipe never moved the device volume, and the brightness swipe
  leaked (pre-existing, fixed in P.8).** The old volume swipe set ExoPlayer's
  gain, so every swipe started at 100% and the lowered gain stacked on the
  device volume. The old brightness swipe was linear against a curved system
  slider, clamped manufacturer ranges such as 2047 to 100%, and leaked to the
  whole display in Picture in Picture and split screen. From Android 12 the
  system adds `FLAG_SHOW_UI` to the safe-volume warning itself, so a retry
  with it only adds the volume panel.
- **The library show page never asked the parental filter (pre-existing,
  fixed in P.1).** `LocalShowHost` drew any show it was handed, so an
  unmatched show opened from Search played while a title rule was on. The
  show's page now fails closed like a movie's.
- **A player relaunch replayed its launch intent (pre-existing for episode
  switches, fixed 2026-11-10).** Changes outside the player's `configChanges`
  (a keyboard, font scale, language) rebuild `PlayerActivity` from its intent,
  so after Up Next or a Play From pick the first item restarted, at a Watch
  Next or theater position if it had one, and recorded its source over the
  pick. `switchTo` now keeps the intent on the item playing, and the saved
  state carries the position and whether the source is still to be recorded.
- **On TV the detail page scrolled its own hero away (found on the TV
  emulator 2026-11-10, fixed).** Compose's default on TV puts whatever takes
  focus 30% down the screen, so a movie page opening on Resume lost the top of
  its title, and a show page opening on its first episode lost the whole hero.
  The page's list now keeps something already on screen where it is, reveals
  something partly on screen with a little room (a focused card grows), and
  leaves anything further to the platform (`OnScreenStaysBringIntoViewSpec`).
- **Delete Watch History's reach (P.4, for the owner).** Tombstones are per
  key, so a deletion can't reach titles a device learns of afterwards (an
  offline device's older watching); a local "cleared before" marker would need
  an owner decision. With sync on, other devices' files keep the history until
  each of them syncs, and a device that stopped syncing keeps it until Remove
  a Device deletes its file. Also open: whether Continue Watching should get a
  per-show dismissal of the Up Next card (Apple has none), and whether the
  deletion should also forget the device-local "Last played" source and the
  per-title track memory (speed, audio and subtitle tracks), which say a
  title was played; today both stay, as preferences.

- **The Theater chip could hand a title over before the parental check had
  ruled on it (found 2026-11-11 in the review of the merge into
  `meta-horizon-27.1`; fixed).** With a title rule on, `startWhenPermitted`
  waits for the controls and the title's verdict before it loads anything,
  but the player's chrome, and so the Theater chip, is on screen at once,
  and `enterTheater` asked only for a player. A tap in that wait sent the
  item's URI to the theater, which plays what it is handed, and the player
  finished before its own refusal. `enterTheater` now returns until
  `itemStarted`: the item has passed the check and reached READY, which is
  also when its position stops reading 0 over the stored resume point. The
  same wait holds after a Play From switch. Not run on the XR emulator
  (the check needs a slow or failing TMDB lookup); the guard is one line.

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
| H.9.8 | The hidden-ID list lives in its own SharedPreferences file (`edendale_google_drive.xml`, excluded from backup and transfer) rather than a column on the source, as Apple's `VideoFolder.hiddenItemIDs` is. | The Room database is part of Auto Backup and a column would need a v4 migration; D21 keeps the list device-local, and a restored library can't bring removed IDs back. | 7f7ff09 |
| H.9.6 | Choose More Videos runs from the screen it was started on, with Cancel on the same button while the Custom Tab is open; a failure shows as the library's error notice rather than an alert. | Closing a Custom Tab doesn't tell the app, so the row needs its own Cancel, as Link Source's sign-in has; Android's library already reports failures in that notice on both Downloaded and Settings. | 7f7ff09 |
| H.9.7 | A TV refuses a handed-over Google account whose scopes lack `drive.file`, not only at launch. The relink notice says Android's **Add Network Source** and closes with Dismiss. | A phone still on an older build would otherwise hand the TV a `drive.readonly` token, under which "Videos you chose" lists all of Drive. Android's menu item is Add Network Source, and Dismiss is the app's existing word for closing a notice. | 7f7ff09 |
| H.9.9 | The TV's Link Source reads its accounts before stopping the handoff host, not after. | Found on the emulators (2026-10-18): stopping first changed the `LaunchedEffect`'s key, so its next suspension cancelled it and a handed-over cloud account (Google Drive, OneDrive, or Dropbox) was stored but never used; the TV stayed on the provider step. A login handoff has no suspension there, which is why the 2026-10-07 WebDAV run passed. | 7f7ff09 |
| M.3 | The portable records live in their own Room tables (`sync_watch_progress`, `sync_user_media`) and are projected onto `watch_progress` and `user_media`, instead of adding `updateTime` and `device` columns to those tables and rewriting `mergeUserMedia`/`mergeWatchProgress`. `UserMediaMerge.kt` is unchanged and still serves only the TMDB engine's shape. | Android keys episodes by the episode's TMDB id while the sync key is show, season, and episode; a received episode nobody here knows has no row to land in but must still travel on. Reviews, which Android doesn't write, must survive a round trip untouched. Separate tables keep the file a faithful copy of the merged view and keep device-specific columns (dirty flags, display snapshots) out of it. | 0cb6569 |
| M.3 | The player stores the title's duration (`durationSeconds`, schema 4); older rows and Mark Watched derive one from the fraction, or use 1 s with the position at its end. | The sync record carries seconds and a duration; Android rows held a fraction only. | 0cb6569 |
| M.5 | The `offer.location` carries an extra `url` for server locations (the credential-free root URL of the folder that holds `Edendale/`, the Apple branch's spelling; `path` is read too), and the `role` message a `platform`. QR codes are scanned with the system camera (the link opens Edendale) or pasted, not with an in-app scanner. | SYNC-PROTOCOL.md's Location has no field for the folder inside a WebDAV, SMB, or SFTP server, and §9.8 reads the platform from the TXT record and the role message; both are unknown fields other clients ignore (noted for the Apple and Windows sessions). An in-app scanner needs a camera dependency; the deep link does the job on Android 9+ cameras and Lens. | 0cb6569 |
| M.4 | `androidx.work` added for the background job. | FEATURES-27.1.md §8 asks for a periodic WorkManager job; Apache-2.0, no Play Services. | 0cb6569 |
| H.9.8 | Remove from Library is new on Android and appears only for Google Drive videos and files added on their own, where Apple's Remove is on every movie and show. Removing a whole show isn't offered; its episodes are removed one by one. | Android rescans local folders on every Downloaded visit and remote sources every 15 minutes, so removing a file from any other source would undo itself almost at once. The owner asked for single videos. | 7f7ff09 |
| H.9.4 | `PlayFromMenu.kt` is unchanged, though the plan lists it with the folder step. | It only labels copies by source kind and file name; Android's folder step for cloud accounts lives in `LibraryComponents.kt`. | 7f7ff09 |
| I.2.2 | The TV's code block also shows the TV's own address and port, and the phone's dialog takes that address by hand under the list of TVs found. | Multicast DNS doesn't cross some home routers or guest networks, and two emulators on one host can't see each other's discovery at all; the typed address is how the emulator check ran. | f9ac696 |
| O.1 | `parseCertificationLists(moviePayload, tvPayload, region)` takes both list payloads and picks one region for both types (the device's when both publish it, else US); `parseCertificationList(payload, region)` reads one list; `effectiveRegion` has a second form over `CertificationLists?` for the filter's region provider. `PinHasher` is a class whose constructor carries the Argon2 cost (defaults are O-D11) rather than an object. `PinRateLimit` is an immutable value, and a stored deadline is only trusted while it is at most one delay away. | A TV label must never be compared against another country's scale, and the per-title lookups must read the same region as the ceiling. The tests hash at a cheap cost while the shipped defaults stay 64 MiB / 3 / 1. After a reboot the uptime deadline would otherwise re-arm an expired lock, or lengthen a running one by the old uptime. | 13ea90d |
| O.3 | `ParentalControls` also exposes `ready`, `listsStatus`, and `refreshLists(force)`; `PinResult` has `PinRequired` and `InvalidPin`; `DeviceParentalControls` takes the preset store, the hasher, a region provider, a scope, and two dispatchers besides the store, the lists source, and the clock; a failed list fetch is not retried automatically for 30 s; `setPin` needs the current PIN even inside the unlock window; `audiencePreferenceChanges` now reports `policy.isActive`. | The filter and the gates must know when the store has been read (they fail closed until then) and the picker needs loading and error lines; the preset lives in another file (O-D7); the hermetic tests need injected clocks and dispatchers; every surface's `verify` asks for the lists, so a dead network must not be hammered; the Set/Change dialog asks for the current PIN anyway (research note §9.7). | 7d4e6fd |
| O.4 | `YoungAudienceFilter` takes a `CoroutineScope` (the ViewModel's `viewModelScope`; the publisher's scope) and `youngAudienceFilter(context, scope)` follows; `audienceDecision()` is removed rather than renamed (`ParentalRules.decision` replaces it) and `AudienceDecision` moved to the `parental` package; `shows(ref)` is new and `visible()` honours `hideRestrictedTitles`, while `allows(ref)` stays the gate; `isEnabled` is true until the controls are ready. | The controls' StateFlows have to reach Compose state for the other screens to recompose, and the preset setter calls a suspend function; a cold start must not flash a restricted title; list surfaces and gates need different answers once "Hide restricted titles" can be off (O.5 moves the `allows`-based list surfaces to `shows`/`visible`). | fa6e921 |
| O.5 | The gate's rules are a pure `ParentalGateRules` object (`requiresPin`, `dropsOnWrongPin`, `playbackRef`, `permitsPlayback`, `allowsTrailers`) under a `ParentalGate` class, and `rememberParentalGate(isTelevision)` takes the layout flag; the deep-link rule is tested in `ParentalGateTest` and `ParentalGateRulesTest` rather than `ParentalControlsTest`; the Watch Next publisher keeps hiding restricted titles while "Hide restricted titles" is off (`visibleLibrary(strict = true)`); a folder playlist's loose episodes carry their show's TMDB id; the player holds its title and start until the controls have read their store and the title is verified, and re-filters the rows when the policy or the lists change, with `switchTo` refusing a row from an older list with a toast until they reload. | The JVM suite reaches a rule object but not Compose, and the PIN dialog needs the TV layout; the home screen cannot show a restricted state; an episode is judged by its show, which the folder branch never named; an "Open with" launch arrives before the store is read; the panel, Up Next, and the media session all read the one list. | 612f13e |
| O.6 | `SettingsScreen` lost its `audienceFilter` parameter (the section reads the controls directly) and the section lives in its own file, `ParentalControlsSettings.kt`; a lock switch turned on before any PIN exists opens Set PIN and applies the switch afterwards; Set/Change PIN verifies the current PIN at its first step, and `setPin` verifies it again; the ceiling becomes `null` once neither type has a limit; the rating rows name the region as a localized country name; two strings beyond the research note's list (`parental_next`, `parental_set_pin_first`) and a detail line under each of the four switches; `watchlist_no_young_audience_titles` and `filmography_no_young_audience_titles` keep their PG / PG-13 wording under a ceiling. | The preset toggle goes through `updatePolicy` like every other row; a lock without a PIN would lock nothing; a wrong current PIN should be reported before the new PIN is typed twice; `isActive` treats an empty ceiling as none anyway; a bare code means little in a detail line; the stepped dialog needs a button label and the lock switches a reason; the plan lists those two keys for translation, and new copy for them is the owner's call. | 6966395 |
| N.6 | The 2D player stops (`ExoPlayer.stop()`) before the theater activity starts; a `content:` item rides as the theater intent's data with a read grant. | The file is never decoded twice while the theater opens at the same position; a file the player was only handed (Open With) stays readable in the theater. | 676cb15 |
| N.7 | Each panel's view answers the system Back itself (a `FrameLayout` around the `ComposeView` forwards `KEYCODE_BACK` to the activity's dispatcher), and once seated the main panel moves 4 m under the floor instead of being disabled. | In Full Space the focused window is a panel's embedded window, not the activity's, so Back never reached `onBackPressedDispatcher`; with the main panel disabled no window had focus at all. | 676cb15 |
| N.9 | Lights re-material the baked nodes (`TheaterLightsRules.dimmableNodes`: a material with an emissive atlas, less `Screen` and the beam) with `KhronosPbrMaterial` copies through `GltfModelNode.setMaterialOverride` — black base colour, the node's own atlas as the emissive texture, the level as the emissive factor; Normal clears them. The atlases are copied out of each GLB at build time into `assets/theater/<room>-<image>.<jpg|png>` by the module's `extractTheaterAtlases` task (+7 MB in the split). | SceneCore 1.0.0 cannot scale a loaded material, and `Texture.create` reads assets only (an absolute path is refused), so the atlases must exist as files; generating them keeps a re-exported room and its lights from drifting apart. | 676cb15 |
| N.9 | Recentre, and a system space change, re-seat at once; there is no 0.2 s / 0.3 s fade on them (the seat-change fade stays). | The re-seat is the first seating's own path (`seated = false`); the fade was not added to it. Whether it is wanted is N.D2's to judge. | 6ea1d7e |
| N-D12 | The picture sits 3 cm in front of the room's screen (the subtitle panel 2 cm further), not 2 cm. | A margin for a coarser depth buffer at 10 m than the 2 cm was chosen on; invisible from the seats. | 676cb15 |
| P.1 | A TMDB show page whose show the library holds lists the library's episodes in place of TMDB's season browser, and on TV those episodes come straight after the hero. | Apple's page resolves its library match; on TV the remote starts on the first episode, as it starts on Play for a movie. | 64917ca |
| P.2 | One poster per title: copies are the same file through another link (path without host, S3 key, cloud file id, SAF document) or one TMDB id. The poster has no chevron; its long press opens Play From. | The owner asked for one item for a share linked over the LAN and Tailscale; Apple has no file identity and shows one poster per row. | 64917ca |
| P.3 | Play From names copies by endpoint and orders them local → LAN → Tailscale → other → endpoint → name → path; `library_folder.endpoint` (v5) stores the cloud email or name and the S3 endpoint URL. | Apple names copies by folder, which is identical for one share linked twice. | 64917ca |
| P.4 | Continue Watching's action is Clear Progress, not "Remove"; `HistoryDeletion` has a Failed state; the read before clearing is `SyncEngine.sync(readEveryFile = true)`. | Matches Apple's resume card (an episode after a finished one comes back as Up Next); a Room failure would otherwise leave the button disabled; the JVM tests run the same code. | 64917ca |
| P.5 | An interrupted scan and pending TMDB matches resume at the next launch. | Owner request; `apple-27.1` doesn't, and sets `lastScannedAt` before inserting (`LibraryController.swift:376`). | 64917ca |
| P.6, P.7 | A device-local memory starts titles on the source they last played from; the playing source counts as reachable; a tap on a multi-source episode opens Play From at a visible chevron, and the player's panel switches copies in place. | Apple has no memory across launches, ignores scan status, and has only a context menu. | 64917ca |
| P.8–P.10 | Device volume and window brightness from the swipes; the Enhancement preset saved (D11 amended); a session-only Rotation Lock chip. | Owner requests; Apple starts each playback at Balanced. | 64917ca |
| Q.1, Q.2 | The picker is segmented buttons at every width and on TV, with the labels All, Watching, Movies, and Shows; from 1100 dp it shows beside the Downloaded rows. `apple-27.1` puts segments in the tab bar's accessory on iPhone and iPad (a menu while the bar is minimised), chips with the full names on Apple TV and Vision Pro, and the sidebar rows alone on the Mac; it shortens only Continue Watching and names the group "Downloaded sections". Android's group has no spoken name of its own; each segment says its full name and state. | The owner's wording and "keep both" (2026-11-11). Material has no tab bar accessory, and one control serves touch, a keyboard, and the D-pad. Other branches (hard constraint 7): `apple-27.1` has the picker; `windows-27.1` has the sections and no picker; `web` is unaffected. | 147c2f2 |

| R.2 | A location the receiver still has to finish (a folder to pick, a Google sign-in) is left out of the result's `stored` until the viewer finishes it, and the sender says the other device still has to finish it. A TV reports a folder or an account-less Google location as failed with Apple's reasons. | The review found `location` reported as stored while nothing was; `apple-27.1` reports it as stored on phones (and as failed on tvOS, as here). Apple's sender lists the items it got back, so it simply shows no location. | |
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
| 2026-10-18 | H.9.4–H.9.11, H.9.T2, H.9.T3: `drive.file` and Google's picker (Claude, desktop session; branch `android-27.0.1` from `android` at 88d699d) | On JDK 17 (Temurin 17.0.20.1): `./gradlew test assembleDebug`: pass (477 JVM tests in each of `testDebugUnitTest` and `testReleaseUnitTest`, 0 failures); `assembleDebugAndroidTest`: pass. `./gradlew lintDebug`: 375 errors, the same 375 (by check and file) as `android` at 88d699d, so none new. | not run yet (H.9.D1 waits for the owner's go-ahead on their AVDs and phone). Without signing in, the `secrets.json` client's auth page accepted the Android redirect with `drive.file` and every picker parameter (S2's setting is on). | Committed as 7f7ff09 on `android-27.0.1`, with version 0.27.0.1 (code 3) in the next commit. Apple's uncommitted work in `kind-wiles-4b96ba` was read for wording only. |
| 2026-10-18 | H.9.D1 on the emulators (Claude, desktop session; the owner signed in) | `./gradlew test assembleDebug` on JDK 17 after each change below: pass (477 JVM tests per variant). | Pixel_10_Pro_XL (Android 17, API 37, windowed, `-gpu host`) and Television_4K (API 36, windowed). **D20:** over the 2026-10-08 build, whose library held a `drive.readonly` account and a My Drive › Movies source with 4 films, the new build's first launch showed "Link Google Drive Again" and removed both; Dismiss cleared the flag and a relaunch showed nothing. The owner then wiped both AVDs. **Fresh install:** launching opened no sign-in on its own. **Sign-in (S2 recheck):** Link Source → Google Drive showed the new copy and Sign In; Chrome's first-run screen came first (the owner answered it), then Google's consent page for "only the specific Google Drive files you use with this app" and the picker; the redirect returned and "Videos you chose" imported the 4 picked films, all matched on TMDB. On this wiped image Chrome then served the picker in a normal tab instead of a Custom Tab, and the custom-scheme redirect still returned to the app. **Playback:** a picked MP4 streamed; dragging the scrubber to 1:01:34 of 1:31:44 kept it playing with the buffer ahead. **Choose More Videos:** the row showed it on phones, Settings showed it on the source and the account; the run returned through the consent page and picker, and the row said "The videos you chose were already in your library." (a rescan also found 4, so the owner's picks were the files already linked). **Picker filter:** at the owner's request the `mimetypes` list was removed (the picker then showed folders and every file type) and, after one look, restored. **Handoff:** TV Link Source → Google Drive showed the TV copy and only Continue on a Phone; the phone (typed address `10.0.2.2:<port>` through `adb forward`, the code) asked "Link Google Drive on sdk_google_atv64_arm64?" with the TV note; the TV stored the account but stayed on the provider step (the bug in Deviations, H.9.9); after the fix the TV linked "Videos you chose" straight away, imported the same 4 films, showed the choose-on-a-phone note with Rescan and Remove and no Choose More Videos, and streamed an HEVC 5.1 MKV with the handed-over token. | **Remove from Library (added at the owner's request):** on the phone, a long press on Obsession opened the menu; Remove took it out of the library and put its Drive file ID in the source's hidden list; a manual Rescan kept it out ("Google Drive · 3 items"); Choose More Videos with Obsession picked again brought it back, emptied the hidden list, and the row said "Added 1 video.". On the TV the Menu key on a focused poster opened the same item; the TV kept its own copy, since hidden lists stay on each device. Not run: removing an episode (no episodes were picked), a new pick appearing on the TV after a rescan (no new videos were picked), shared drives. |
| 2026-11-02 to 2026-11-03 | M.1–M.6, M.T1 (Claude, desktop session epic-jepsen, worktree from `android-27.0.1`) | `./gradlew testDebugUnitTest assembleDebug`: pass (517 JVM tests, 0 failures; 478 before this work). `OAuthTest` updated: the OneDrive scope list now carries `Files.ReadWrite.AppFolder` (decision 5). Strings: every new key in all 19 files. No log line carries a code, secret, key, token, or password (`LogHygieneTest`). | not run (no device or emulator used; the owner's AVDs were not started) | SYNC-PROTOCOL.md from `main` at c620b21. Not verified on a device: every M.D1 check. Needs the owner (M.7): `Files.ReadWrite.AppFolder` on the Entra app, `files.content.write` on the Dropbox app; until then consent for those two providers fails. J-PAKE is Bouncy Castle's; RFC 8236's example run is not replicated in the tests (the participant's randomness can't be injected), the round trip and wrong-code cases are. |
| 2026-11-08 to 2026-11-09 | N.1–N.11, N.T1, N.D1 (Claude, desktop session; branch `android-27.1` from `android` at 9f99413) | `./gradlew testDebugUnitTest --tests 'com.babasama.edendale.android.xr.*' assembleDebug`: pass (98 JVM tests). `xr-debug.apk` 50 MB (Jetpack XR and Impress 32 MB, the rooms 23 MB, their atlases 7 MB). | Throwaway AVD `edendale_xr` (Google Play XR image, API 34, headless, `-gpu host`; head driven over the emulator's gRPC `xr_head_rotation_event`): the Theater chip on XR with the split and none on the base alone; the three rooms load facing the viewer with the picture on the screen (the Matroska fixture, paused and playing); dock and seat panel follow a 45° look down; Back → the 2D player at the position; the natural end → the library; Lights Dim and Dark in each room with the picture and the beam untouched. **Not driven** (no pointer input, see Findings): seat changes, the Room list, Recentre, Up Next, audio and subtitle switches; Continue Watching and the sync flush not checked here. | The Tears of Steel MOV stalls on any non-zero start on this emulator (Findings); the picture was black for that reason, not the surface. |
| 2026-11-09 | Review and merge of O.5–O.7; N.D1 completed; O.D1; four fixes (Claude, desktop session) | O.1–O.4 were cherry-picked earlier (38e5a45…023ca61); O.5–O.7 came from the worktree agent's 612f13e, 6966395, 6ac139c, 8d0c077, reviewed (gates at every Settings, library-edit, Sync, and pairing-link entry; the player's start and `switchTo`; the playlist filter; the trailer rule) and cherry-picked as 4b6a348, ba8195a, 4c0c6be, 9601579. `./gradlew testDebugUnitTest assembleDebug` after each step: pass; 672 JVM tests after the merge, 678 at 708c0d8, 0 failures. | Throwaway AVDs only: `edendale_xr` (XR image, API 34, `-gpu host`), `edendale_phone` (Pixel 10 Pro XL profile, API 37), `edendale_tv` (4K TV, API 36), the last two headless with SwiftShader; three 3-minute Severance episodes and three films (The Matrix R, Inception PG-13, Paddington PG) with two audio and two subtitle tracks from a scratch ffmpeg; Apache `mod_dav` on 127.0.0.1:18080 as the network source and the sync location. **N.D1:** the chip on XR and none on the phone; entered from a local folder and from WebDAV; Audio Track → French and Subtitles → French (the line on the screen matched the SRT); Room → Projector Room while playing (remembered, with Dim); a stored Row 4 Seat 2 restored and Best Seat → Row 2 Seat 5; Recentre after a 35° head turn put the screen ahead, as did the emulator's recenter; seeking near the end showed Up Next, which opened S01E03 in the 2D player; Exit from 1:33 returned there; the natural end of S01E01 returned to the library with it complete; Continue Watching listed S01E03; the theater's stop ran `sync (playback stopped)` and PUT the device file with S01E03 at 136 s. **O.D1:** PIN set (stored encrypted, never in logcat); four wrong PINs → "Try again in 00:28", still counting after a force-stop, then the right PIN; PG-13 / TV-14 removed The Matrix and Severance from Downloaded, Continue Watching, Search, and the player's folder list, and The Matrix from the TV's Play Next row; with Hide restricted titles off they returned to the lists, the player refused The Matrix with the toast, and its detail page showed Restricted; Watch Trailer gone with the switch on; Settings and Add Local Folder asked; a pairing link asked and a wrong PIN dropped it; Remove PIN; the picker's radio states; on TV the PIN went in through the on-screen keypad with the D-pad. | Fixes: bc9d5c6 (theater list and Lights selection, the Close label), c678e74 (WebDAV sync folder scheme), 58a8fd1 (switch rows' state), 708c0d8 (a newer pairing link). For the owner, not changed: the ceiling picker offers `NR`, which as a maximum blocks every rated title; Exit from a paused theater resumes playing in the 2D player; Remove PIN leaves the two Require-PIN switches on (inert until a PIN is set again); on TV the keypad covers the PIN dialog's buttons (its → key submits). N.D2 and the legal review (O-D15) stay the owner's. |
| 2026-11-09 | Settings laid out as on iOS 27.1 (Claude, desktop session; owner request, outside the plan) | `./gradlew assembleDebug testDebugUnitTest`: pass (681 JVM tests, 3 new in `SettingsPageTest`). | Throwaway `edendale_settings_phone` (copy of `edendale_phone`, API 37): every page opens, Back returns to the list from each page and closes the sheet from the list; `edendale_tv` read-only (API 36): D-pad walks the rows, Select opens a page with focus on Back, Back returns focus to the row, Back on the list closes Settings. | A first page of categories (Player Controls, Audio, Subtitles, Library, Sync, Accounts; Android TV on a TV), then Parental Controls (Young Audience Friendly in place, the rest under Ratings, Locks, and PIN) and About (version, TMDB notice, Nunito credit, TheIntroDB link on handhelds). Removed: About's Watch Progress and TMDB Access rows, the Backup and Privacy sections, the open-source line, the Android TV layout note, and "Using the key from secrets.json." The phone sheet no longer dismisses itself on Back (`shouldDismissOnBackPress = false`): Material 3 1.3.2 registers that callback on the sheet window ahead of any `BackHandler`, so Settings closes a page or the sheet itself. Action rows wrap (`FlowRow`) instead of cutting labels short. Six new strings in 19 locales, fifteen unused ones removed. |
| 2026-11-09 | M.8 (Claude, desktop session; owner request) | `./gradlew testDebugUnitTest --tests 'com.babasama.edendale.oauth.*' --tests 'com.babasama.edendale.sync.*' assembleDebug`, then the whole `testDebugUnitTest`: pass, 682 JVM tests, 0 failures (`OAuthTest` 25 tests, one new: the sync sign-in URL has no picker parameters and keeps the refresh-token ones; Link Source keeps the picker; OneDrive and Dropbox configurations unchanged). | Throwaway `edendale_phone` (API 37): the dialog lists the linked Google account, then Another Account with the three sign-in rows; Save disabled until a choice; Sign In to Google Drive opened Google's account chooser, and the Custom Tab's URL had `access_type`, `prompt`, and `scope` but no `trigger_onepick`, `allow_multiple`, or `mimetypes`; closing the tab left "Waiting for Google Drive…", and tapping it restored the row. The consent itself was not completed (the owner's account). | Not run: a full sign-in from the dialog, OneDrive and Dropbox from the dialog, and the iPhone ↔ Android sync (M.D1, on hold). |
| 2026-11-10 | P.D1 (Claude, desktop session) | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass, 840 JVM tests. `am instrument` for `EdendaleMigrationInstrumentedTest`, `LibraryDaoInstrumentedTest`, `WatchHistoryClearInstrumentedTest`: OK (12 tests). | Throwaway `edendale_phone` (API 37) and `edendale_tv` (API 36, 4K), WebDAV from `httpd` on 127.0.0.1:18080 and :18081 (reached as 10.0.2.2), which link played read from the server log: everything in P.D1 passed. | One fix, the detail page's TV focus scroll. Sync stayed off; no owner account was used. |
| 2026-11-09 to 2026-11-10 | P.1–P.10, P.T1 (Claude, desktop sessions; owner request, outside the plan) | Five streams (index, sync, player, sources, detail) in scratch worktrees off a snapshot of the shared tree, each implemented, reviewed twice, and fixed; cherry-picked onto the Room v5 base with three conflicts resolved by keeping both sides (Continue Watching's card, `PlayerLogic`); then P.1's show page and P.2's merged posters, and a cross-cutting review. `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: pass, 839 JVM tests, 0 failures (682 before). Two final reviewers (P.1/P.2, and the seams between streams) confirmed the three conflict resolutions and found one real cross-stream bug, fixed: a copy whose TMDB match was still pending could be the one Play, Continue Watching, Up Next, or the player's episode list started, so it played with no id (no resume, no progress, refused under a parental rule); it now plays under its title's id. Also fixed from the review: a movie and a show sharing a TMDB id number crashed Downloaded (duplicate list keys); rows matched to different titles could unite through one path; a tile's Remove went for every removable copy at once; TV's focus return lost a poster matched while its page was open; a long season composed as one lazy item; a player relaunch replayed its launch intent. Instrumented migration, DAO, and history-clear tests compiled, not run. | Throwaway `edendale_phone` (2026-11-09, earlier streams only): v4 → v5 upgrade, Rotation Lock, the volume swipe moving `STREAM_MUSIC`, the brightness override. P.D1 open. | Applied to the shared checkout uncommitted. |
| 2026-11-11 | Q.1, Q.2, Q.T1, Q.D1 (Claude, two desktop sessions; owner request, outside the plan) | The first session built the picker: `./gradlew testDebugUnitTest`: pass, 844 JVM tests. The second reviewed it (six reviewers, each finding checked by a second) and fixed four things: the navigation rail lost its Downloaded highlight once a section was chosen (`NavigationItem.lights`, with a test); the segment ends stopped on Left and Right, which a right-to-left layout turns the wrong way (now Start and End); the bar colour read the scroll offset on every frame (`derivedStateOf`); and the shell read the section list on every tab at every width, with five more library queries and the ratings lookups (now only on the Downloaded tab or beside the section rows). Then `./gradlew testDebugUnitTest assembleDebug` on a clean snapshot of the tree, without `secrets.json`: pass, 845 JVM tests, 0 failures; `Edendale-debug.apk` and `xr-debug.apk` build. | First session, throwaway `edendale_phone` (API 37) and `edendale_tv` (API 36): 448 dp; 360 dp in German, French at 130 % text, and Mexican Spanish; 900 dp with the rail; 1280 dp with extended navigation; the TV with the D-pad. Two fixes there: the segments' own text style (the theme's label tracking clipped German "TV-Serien" and wrapped French "En cours" at 360 dp), and Right on the last segment no longer jumps into the posters. Second session, `edendale_phone` with the fixed build: at 900 dp the rail keeps Downloaded lit with Movies chosen; every segment is 48 dp tall (96 px at 320 dpi, 144 px at 480 dpi); at 448 dp the picker shows on arriving from Movies & Shows. A right-to-left layout was not checked: the force-RTL setting alone didn't mirror the emulator. | Review findings left as they are: the labels are short on TV and wide windows too, where the full names would fit (the owner's wording); the picker can appear a frame after the list on Back from a title, since the shell's section list starts again (not seen on the emulator). Committed as `147c2f2`. The same review listed what else 27.1 still owes; none of it is this section's: the Link Source privacy lines still say Edendale never uploads although sync writes its own files, a TV offered a Google sign-in it can't complete after a handoff from another ecosystem, no offer to delete this device's file when the sync location changes, the handoff `Login` reader without the nested `credential` fallback, and the trailer `Referer` fix still on `android-trailer-referer`. |
| 2026-11-11 | N.5, O.5: the Theater chip waits for the item (Claude, desktop session; found by the review of the merge into `meta-horizon-27.1`) | `./gradlew testDebugUnitTest assembleDebug` on a clean snapshot: pass, 845 JVM tests, 0 failures. | not run (the wait needs a parental rule and a slow certification lookup on the XR emulator) | `PlayerActivity.enterTheater` returns until `itemStarted` (Findings). No pure rule to test: the flag is the activity's. |

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
| H.9.4–H.9.11 | `drive.file` and Google's picker (D20–D24) | Code, tests (H.9.T2, H.9.T3), strings, and docs committed on `android-27.0.1`; H.9.D1 mostly passed on the emulators 2026-10-18; Remove from Library added at the owner's request; a TV handoff bug fixed on the way; H.9.12 (other branches) open | 7f7ff09 |
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
| L.3 | Release | Version 0.27 (code 2) set; release.txt waits until the owner asks; 0.27.0.1 (code 3) for the `drive.file` update on `android-27.0.1` (2026-10-18); 0.27.1 (code 4) for 27.1 on `android-27.1` (2026-11-09) | 86df492 |
| L.4 | Notes for other branches | Complete (notes in Section L) | |
| M.1 | Connector write support (WebDAV, SFTP, SMB) | Complete | 0cb6569 |
| M.2 | Sync location and storages, new scopes | Complete (consent needs the owner's console changes, M.7) | 0cb6569 |
| M.3 | Device file, merge, Room v4, projection | Complete | 0cb6569 |
| M.4 | Triggers, backoff, background job | Complete (device check not run) | 0cb6569 |
| M.5 | Handoff version 2 and pairing UI | Complete (device check not run; no in-app camera scanner) | 0cb6569 |
| M.T1 | JVM tests: vectors, convergence, storages, handoff, log hygiene | Complete | 0cb6569 |
| M.6 | README and this file | Complete | 32d04ca |
| M.D1 | Two-device checks | Not run | |
| M.7 | Owner's console changes and probes | Open | |
| N.1 | The `:xr` feature module and the Section N plan | Complete (the split installs beside the base on the XR emulator: `splits=[base, xr]`) | e427c6e |
| N.2 | `TheaterGlb`: seat, screen bounds, seat markers from a room's GLB | Complete (15 JVM tests) | f39f6d2 |
| N.3 | Geometry, seat map, rooms, lights, preference rules | Complete (41 JVM tests: the 32 visionOS cases, the Quest's picture fit, the rooms' spans) | c611335 |
| N.4 | Dock geometry and head follower | Complete (12 JVM tests) | 75a1b70 |
| N.5 | Theater chip, handover, return position, strings, rooms | Complete (9 JVM tests; the rooms are the seat-forward exports with the seat maps) | 6d9acc2 |
| N.6 | The activity, the stage, the player facade, the picture and subtitles | Complete (emulator: the picture on the room's screen, Back to the player at the position, the natural end to the library) | 44f9e5e, 6ea1d7e, 676cb15 |
| N.7 | Dock and seat panel, head follower | Complete (emulator: both panels follow a 45° look down; the controls themselves can't be tapped there, see Findings) | 6ea1d7e, 676cb15 |
| N.8 | Rooms, switching, the fallback | Complete (all three rooms load on the emulator; the Room list wasn't driven there) | 6ea1d7e, 676cb15 |
| N.9 | Lights and Recentre | Complete (Dim and Dark verified in all three rooms; Recentre re-seats without a fade, see Deviations) | 676cb15 |
| N.10 | The module's strings in 19 locales | Complete | 6d9acc2 |
| N.T1 | JVM tests | Complete (98 tests under `com.babasama.edendale.android.xr`) | 676cb15 |
| N.11 | README and this file | Complete | |
| N.D1 | XR emulator checks | Complete (every item on the emulator, the panels pressed through accessibility; two fixes: bc9d5c6, c678e74) | bc9d5c6, c678e74 |
| N.D2 | Owner, on a headset | Not run | |
| O.1 | Pure parental rules, PIN hash, back-off | Complete (22 JVM tests) | 13ea90d |
| O.2 | `ParentalStore`, the encrypted store, backup exclusions | Complete | 72f79bf |
| O.3 | `ParentalControls`, `DeviceParentalControls`, the TMDB lists, the change flow | Complete (13 JVM tests: the controls, log hygiene, the device file and offer, the list endpoints) | 7d4e6fd |
| O.4 | `YoungAudienceFilter` over the policy and the lists | Complete (9 JVM tests; whole suite 655, 0 failures) | fa6e921 |
| O.5 | Gates, the player, the trailer rule, list surfaces on `shows()` | Complete (15 JVM tests: the gate rules, the gate, the playlist filter) | 612f13e |
| O.6 | The Parental Controls section, the PIN dialogs, strings in 19 locales | Complete | 6966395 |
| O.7 | README and this file | Complete | 6ac139c |
| O.T1 | JVM tests | Complete (the deep-link rule and the gate in `ParentalGateTest` and `ParentalGateRulesTest`; whole suite 672, 0 failures) | 612f13e |
| O.D1 | Phone and TV emulator checks | Complete (phone API 37 and TV API 36; two fixes: 58a8fd1, 708c0d8) | 58a8fd1, 708c0d8 |
| P.1 | Downloaded and Search open the detail page; shows on it | Complete (JVM) | 64917ca |
| P.2 | One poster per title across endpoints | Complete (JVM) | 64917ca |
| P.3 | Play From endpoint labels and order | Complete (JVM) | 64917ca |
| P.4 | Delete Watch History; Clear Progress | Complete (JVM) | 64917ca |
| P.5 | Interrupted index resumes at launch | Complete (JVM; DAO instrumented test not run) | 64917ca |
| P.6 | Next episode stays on its source; source memory | Complete (JVM) | 64917ca |
| P.7 | Episode Play From menu and player panel chevron | Complete (JVM) | 64917ca |
| P.8 | Swipes move device volume and brightness | Complete (phone emulator spot checks) | 64917ca |
| P.9 | Enhancement preset saved | Complete (JVM) | 64917ca |
| P.10 | Rotation Lock chip | Complete (phone emulator spot check) | 64917ca |
| P.T1 | JVM tests | Complete (840, 0 failures; 12 instrumented on the TV emulator) | 64917ca |
| P.D1 | Emulator checks | Complete (phone API 37, TV API 36; one fix: the detail page's TV focus scroll) | 64917ca |
| P.D2 | Owner, on hardware | Not run | |
| Q.1 | The Downloaded section picker: choices, segments, strings | Complete | 147c2f2 |
| Q.2 | One Downloaded page for every section; the navigation rules | Complete | 147c2f2 |
| Q.T1 | JVM tests | Complete (845, 0 failures) | 147c2f2 |
| Q.D1 | Emulator checks | Complete (phone API 37 at 448, 360, 900, and 1280 dp; TV API 36; the rail fix checked again at 900 dp; right-to-left not run) | 147c2f2 |
| Q.D2 | Owner, on hardware | Not run | |
| R.1 | Privacy notes cover the sync files | Complete | 462602d |
| R.2 | TV handoff: a note instead of a sign-in it can't complete | Complete (JVM; device check in R.D1) | c42e7a7 |
| R.3 | Changing the location offers to delete the old file | Complete (JVM; device check in R.D1) | 2106eb8 |
| R.4 | Handoff `Login` reader: nested `credential` | Complete (JVM) | |
| R.5 | Trailer `Referer` | Open | |
| R.6 | Small items | Open | |
| R.7 | Deviations recorded | Open | |
| R.D1 | Emulator checks | Not run | |
