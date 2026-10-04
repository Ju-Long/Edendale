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
| D9 | Google Drive sign-in | **Owner**, after H.9.1. Google steers Android apps to Identity Services' `AuthorizationClient` (Play Services, closed source) and restricts custom-scheme redirects for Android clients. Options: `AuthorizationClient`; PKCE through Custom Tabs if Google's current rules allow it for this app; or Drive only through WebDAV (for example `rclone serve webdav`). | H.9 |
| D10 | Home-server TLS | **Owner**; must match Apple and Windows (DIFF decision 4). Proposed: valid HTTPS always works; self-signed HTTPS works after the user approves the certificate's SHA-256 fingerprint, pinned per host; plain `http://` (`dav://`) only to private-network addresses. | H.3.4 |
| D11 | Enhancement default | Apple starts each playback at Balanced, Sharpness 0.5, Denoise 0.5, Motion Smoothing off, and doesn't save changes. Android matches that (in memory, not saved), except: Off on devices that fail the capability check (F.6); Off on Android TV until F.1.6 passes on a real TV; and HDR / Dolby Vision always bypassed in this release. | — |
| D12 | Frame generation | Phones and tablets only, off by default, behind the experiment in G.1. Never offered on Android TV. | G.2–G.6 |
| D13 | MediaSession scope | A `MediaSession` owned by `PlayerActivity`. No `MediaSessionService` and no background playback; playback still pauses in `onStop`. | — |
| D14 | Instrumented tests | Add `src/androidTest` for GL, parser, and migration checks, run on a device or emulator. Adding them to CI is optional (A.5.4). | — |
| D15 | Watch Next row | Research first (I.3.1). Build it only if the launchers on target devices still read `WatchNextPrograms`. Opt-in, off by default. | I.3.2–I.3.3 |
| D16 | SFTP library | sshj (Apache-2.0) if it passes H.4.1; otherwise Apache MINA SSHD (Apache-2.0). | — |
| D17 | NFS | Optional, last. Only if an Apache-, MIT-, or BSD-licensed NFSv3 client works on Android without privileged ports. | H.10 |
| D18 | Rounded subtitle font | Android has no system rounded font. Default: bundle Nunito (SIL OFL 1.1) as the "Rounded" choice and credit it in Attribution. The owner may prefer to drop "Rounded" on Android. | — |
| D19 | Release version | **Owner.** `versionName` is `0.26` and `versionCode` is `1` today. | L.3 |

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

## 2. Build order

Each phase leaves the app shippable. Within a phase, follow the listed order.

1. **Foundations:** A.1 → A.5.
2. **Player basics:** B.1 → C.4 → C.5 → B.2 → C.1 → C.2 → C.3 → C.6 → B.4 →
   B.3 → B.5 → B.6.
3. **SMB and library rules:** D.1 → D.2 → D.3 → D.4 → D.5.
4. **Audio:** E.1, then E.2 once D5 is decided.
5. **Picture:** F.1 → F.8 in order.
6. **Storage providers:** H.1 → H.2 → H.3 → H.4 → H.5 → H.6 → H.7 → H.8, with
   H.11 alongside; H.9 after D9; H.10 optional; H.12 any time.
7. **Android TV:** I.1 → I.2 → I.3.
8. **Large screens and keyboard:** J.1 → J.6 (independent; may move earlier).
9. **Frame generation:** G.1, then G.2 → G.6 only if G.1 recommends going ahead
   and the owner agrees.

Section K (Settings layout) applies whenever a phase adds a Settings section.
Section L (docs and release) closes the work.

This follows DIFF §7's Android order, with MediaSession added to phase 2 and SMB
hardening moved ahead of the audio work.

---

## Section A — Foundations

### A.1 — Branch and baseline

- [x] **A.1.1** S.1–S.5 are done and the baseline result is in the Handoff log.

### A.2 — Media3 upgrade (D1)

- [x] **A.2.1** In `gradle/libs.versions.toml`, set `media3 = "1.9.0"`. Every
  `androidx.media3` artifact uses `version.ref = "media3"`; Media3 requires all
  its modules at the same version.
- [x] **A.2.2** Fix compile errors and deprecations from 1.7 to 1.9 without
  changing behavior. List each API change in the commit message.
- [ ] **A.2.3** **On device:** a local file, an SMB file, attaching a Wyzie
  subtitle, PiP, switching items from the playlist, and resume all still work,
  on a phone and on Android TV.

**Acceptance:** the JVM suite and `assembleDebug` pass; the device checks are
recorded.

### A.3 — Player preference store

All new device-local player settings go through one small layer.

- [x] **A.3.1** Create `player/PlayerPreferences.kt` (or a `prefs` subpackage)
  with:
  - pure parse and normalize functions for each setting (no Android imports),
    tested on the JVM;
  - a thin adapter over the SharedPreferences file `"player"`, which
    `PlayerActivity` already opens, readable from both `MainActivity`
    (Settings) and `PlayerActivity`;
  - change notification (`OnSharedPreferenceChangeListener`) so an open player,
    including one in PiP, picks up changes made in Settings.
- [x] **A.3.2** Key names match Apple's exactly (each step lists them). Missing,
  unparseable, or unknown values fall back to the default; out-of-range values
  are normalized on read and never throw.

### A.4 — Strings and translations

- [x] **A.4.1** Each step adds its strings to `values/strings.xml` and all 18
  locales in the same commit, with the name prefixes `settings_`, `player_`,
  `sources_`, `accounts_`, `audio_`, and `video_`. Removed strings disappear
  from every locale.

### A.5 — Test conventions

- [x] **A.5.1** JVM tests live under `src/test/kotlin/com/babasama/edendale/…`,
  mirroring the main package, and use `kotlin.test` and JUnit 4 like the
  existing suite.
- [x] **A.5.2** For each Apple test file a step names, port **the cases** (not
  the code), and keep case names recognizable (for example
  `nextEpisodeCrossesSeasons`). Read them with
  `git show origin/apple-27.0:EdendaleTests/<File>.swift`.
- [x] **A.5.3** Add an `androidTest` source set (D14):
  `androidTestImplementation` for `androidx.test.ext:junit` and
  `androidx.test:runner` (plus `androidx.room:room-testing` for D.2), and
  `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
  Instrumented tests never need network access or credentials.
- [x] **A.5.4** Optional: a separate CI job that runs instrumented tests on an
  emulator, triggered only for this branch and using no secrets (AGENTS.md hard
  constraint 2). The existing `build-and-test` job keeps running exactly what
  it runs today. Done as `.github/workflows/instrumented.yml` (pushes and pull
  requests for `android` and `android-27.0`, plus manual runs; API 35
  `google_apis` x86_64 emulator; actions pinned by commit). It passes
  actionlint but hasn't run on GitHub yet.
- [x] **A.5.5** Network tests never reach the internet. Use transport fakes, or
  the JDK's built-in `com.sun.net.httpserver.HttpServer` on `127.0.0.1` when
  real HTTP behavior matters (`Range`, redirects, status codes). OkHttp's
  `mockwebserver` is acceptable as a `testImplementation` dependency.
- [x] **A.5.6** Binary fixtures (short video or audio files) are generated by a
  checked-in script (for example `src/test/fixtures/generate.sh`, using the
  `ffmpeg` command-line tool) and stay under 1 MB each.

---

## Section B — Player controls and state

### B.1 — App Controls: skip lengths and hold speeds (DIFF §3.1)

**Rules:**
- **Settings → App Controls** has four settings: Skip Back, Skip Forward, Hold
  Left Side, and Hold Right Side.
- Skip lengths are 10, 15, or 30 s (default 10). The keys
  `player.skipBackwardSeconds` and `player.skipForwardSeconds` store the whole
  seconds; any other stored value reads as 10. Each is a segmented control
  showing the matching arrow-rotate glyph (`ic_arrow_rotate_left_10/15/30` and
  `ic_arrow_rotate_right_10/15/30` already exist). TalkBack announces the
  length.
- One skip length drives every skip: double-tap on either side, the ± controls,
  D-pad seeks and the TV timeline, short presses of the media fast-forward and
  rewind keys (D2), accessibility skip actions, the PiP actions (icon and title
  follow the length), and MediaSession seek back/forward (C.6). Changes apply
  live, including to a video floating in PiP.
- Hold speeds range from 0.25× to 3.00× in **0.25 steps**. Defaults: left 0.5×,
  right 2.0×. Keys `player.holdLeftRate` and `player.holdRightRate` (float). A
  stored value snaps to the 0.25 grid and clamps to the range; a non-finite
  value becomes 0.25; a missing key means the default.
- A hold temporarily overrides the base speed and reverts on release. A short
  tap still toggles the controls, and a drag does neither (existing behavior).
- The player reads the preferences at each gesture, so a change applies
  without restarting playback. They stay on the device and are never synced.

**Checklist:**
- [x] **B.1.1** Pure `SkipInterval` enum (10/15/30), `normalizedHoldRate`,
  parsing, and defaults (A.3).
- [x] **B.1.2** Replace `PlayerLogic.HOLD_SLOW_RATE`, `HOLD_FAST_RATE`, and
  `holdRate(...)` with a side function (left or right half) plus the stored
  rates. Replace every use of `SEEK_STEP_MILLIS` with the stored lengths, and
  update KDoc that mentions 0.5×, 1.5×, or 10 s.
- [x] **B.1.3** Wire the lengths into the `PlayerGestureLayer` double-tap, the ±
  controls in `PlayerControlsOverlay`, the `RevealCatcher` D-pad seeks
  (`remoteSeek`), `handleTransportKey`, and `pipActions` (icon and label for
  each length).
- [x] **B.1.4** TV (D2): fast-forward or rewind held for 400 ms or more calls
  `beginHoldRate` with the right or left rate until key up; a shorter press
  skips. Key repeats while the key is held must not also seek.
- [x] **B.1.5** The Settings → App Controls section (position per K), with the
  two segmented skip controls and two −/value/+ hold steppers. The value is
  shown like `0.50×` via `PlayerLogic.rateLabel`. It works with the D-pad on TV.
- [x] **B.1.6** `PlayerActivity` listens for preference changes and refreshes
  the PiP params (and the MediaSession, once C.6 lands).

**Tests (JVM):**
- [x] **B.1.T1** `PlayerControlPreferencesTest`: defaults; 10/15/30 round trip;
  unknown integers (0, 20, −10) read as 10; hold snapping (0.3 → 0.25,
  0.4 → 0.5, 2.6 → 2.5); clamping (0.1 → 0.25, 5 → 3.0); NaN and ±∞ → 0.25;
  missing keys → 0.5 / 2.0. Port the remaining cases from
  `PlayerControlPreferencesTests`.
- [x] **B.1.T2** `PlayerLogicTest`: the hold side for the left half, the right
  half, and the exact midpoint (keep the old boundary: `x < width / 2` is left).

**On device:**
- [ ] **B.1.D1** Change Skip Forward to 30 s while a video floats in PiP: the
  PiP button relabels, and double-tap, D-pad, and media keys skip 30 s.
- [ ] **B.1.D2** Android TV: a short ⏩ press skips; holding ⏩ plays at the
  right hold speed and reverts on release.

**Acceptance:** no code path still uses a fixed 10 s skip or the 0.5×/1.5×
holds; the tests pass; the device checks are recorded.

### B.2 — Persisted player state and per-title memory (DIFF §3.3)

**Rules:**
- Loop (`player.loopEnabled`, default false) and Fit/Fill (`player.aspectFill`,
  default false, meaning Fit) persist globally on the device. Auto-PiP keeps its
  existing key `player.autoPiP` (default true).
- For each title, remember the speed, the audio track, the subtitle track or
  that subtitles were off, and the video track.
  - Key `player.content.movie.<tmdbId>`, or `player.content.show.<showTmdbId>`
    for episodes, so a whole show shares one entry. Files without a TMDB id
    store nothing.
  - Value: a JSON object with the optional fields `speed`,
    `audioTrackLanguage`, `audioTrackName`, `subtitleEnabled`,
    `subtitleTrackLanguage`, `subtitleTrackName`, `videoTrackWidth`, and
    `videoTrackHeight` (Apple's `ContentPlayerPreferences`).
  - Store these in a separate SharedPreferences file, `"player_content"`, so
    the main `"player"` file stays small.
- Restore once per item, on the first `onTracksChanged` that lists the item's
  tracks (Media3 reports empty tracks first whenever a new item replaces the
  playing one):
  - Audio and subtitle tracks match by **language first, then track name**.
  - Only embedded subtitle tracks are remembered or restored. Sideloaded Wyzie
    tracks never are: give their `SubtitleConfiguration` ids an `ext-` prefix
    (Apple's convention), exclude ids starting with `ext-`, and update the id
    matching in `selectPendingOnlineSubtitle`.
  - The video track is restored by width × height, and only when the file has
    more than one video track.
  - `subtitleEnabled == false` restores Off.
- Save on a speed change, a user track choice, switching items, and `onStop`.
  Restoring never triggers a save.
- Progress keeps saving every 5 s. That is more often than Apple's 10 s, so it
  doesn't change.

**Checklist:**
- [x] **B.2.1** Persist Loop and Fit/Fill; `PlayerChromeState` reads them at
  start.
- [x] **B.2.2** A pure model, JSON codec, content-key function, and matching
  functions over plain data (for example
  `TrackCandidate(id, language, label, width, height)`), independent of Media3
  types.
- [x] **B.2.3** A Media3 adapter: build the candidates from `Tracks`, apply them
  with `TrackSelectionOverride` / `setTrackTypeDisabled`, and apply the speed
  with `chrome.setRate`.
- [x] **B.2.4** The `ext-` prefix for sideloaded subtitle ids.

**Tests (JVM):**
- [x] **B.2.T1** Content keys for a movie and an episode (show key); missing ids
  store nothing.
- [x] **B.2.T2** JSON round trip; unknown fields are ignored; corrupt JSON
  yields no preferences.
- [x] **B.2.T3** Matching: language beats name; the name is used when the
  language is missing or absent from the file; `ext-` tracks are never chosen
  or saved; the video track is restored only with more than one track and an
  exact W×H match; Off is restored.

**On device:**
- [ ] **B.2.D1** Pick a non-default audio track and subtitle in episode 1, then
  open episode 2 of the same show: both carry over. Loop and Fill survive
  closing the player.

### B.3 — Track pickers and panel order (DIFF §3.9)

**Rules:**
- **Video Track** appears only when the file has more than one video track.
  Rows read `name (language) — W×H`.
- **Audio Track** appears only when the file has more than one audio track.
  Rows read `name (language) — <channels>`, where 1 channel is Mono, 2 is
  Stereo, 6 is 5.1, 8 is 7.1, and any other count N is `N ch`.
- The language is appended only when the name doesn't already contain it
  (case-insensitive, checked against both the display language name and the
  language code). A track with no label falls back to its language, then to
  "Track N" (the existing `trackOptionLabel` behavior).
- **Panel order**, taken from Apple's `PlayerSettingsPanel.swift` (DIFF §3.9
  lists a different order): Speed · Video Track · Audio Track · Subtitles ·
  Online Subtitles · Playback (Skip Prompts, Loop, Audio Booster, Auto Picture
  in Picture, then Android's Audio Output) · Aspect Ratio · Picture ·
  Enhancement.
- The Enhancement preset is a dropdown menu, not a segmented control (Apple
  commit `4f8383a`: four segments truncate in narrow panels).

**Checklist:**
- [x] **B.3.1** Pure label builders (channel label, language-suffix rule,
  resolution), tested on the JVM.
- [x] **B.3.2** `videoTrackOptions` and `audioTrackOptions` built from
  `Tracks.groups` (`C.TRACK_TYPE_VIDEO` / `C.TRACK_TYPE_AUDIO`), selected with
  `TrackSelectionOverride`, and remembered per B.2.
- [x] **B.3.3** Reorder the panel; sections from later steps slot into place as
  they land.

**Tests (JVM):**
- [x] **B.3.T1** Labels for: "English — Stereo"; a "Commentary" track in
  English with 6 channels → "Commentary (English) — 5.1"; a name that already
  contains the language ("English Commentary") gets no suffix; 1, 3, and 8
  channels → Mono, "3 ch", 7.1.

**On device:**
- [ ] **B.3.D1** A multi-audio MKV shows Audio Track and switches immediately;
  a single-audio file hides the section.

### B.4 — Subtitle appearance and placement (DIFF §3.8)

**Rules:**
- Settings → Subtitles gains four settings, as named presets with no free
  color picker, above the existing Wyzie key:

  | Setting | Key | Values (stored raw value) | Default |
  |---|---|---|---|
  | Font | `subtitles.font` | `system`, `rounded`, `serif`, `monospaced` | `system` |
  | Text color | `subtitles.textColor` | `parchment` (#E4E1E9, `EdendaleColors.TextPrimary`), `white` (#FFFFFF), `yellow` (#FFE033), `cyan` (#59E6FF), `green` (#73F273), `black` (#000000) | `parchment` |
  | Box color | `subtitles.backgroundColor` | `ink` (#0A0A0F, `EdendaleColors.Background`), `black` (#000000), `charcoal` (#383838), `navy` (#0F1A3D), `white` (#FFFFFF) | `ink` |
  | Box opacity | `subtitles.backgroundOpacity` | 0–1, stored rounded to 0.01; TV steps by 0.1 | 1 |

  - Unknown raw values read as the default. A non-finite opacity reads as 1,
    and opacity clamps to 0–1.
  - A Reset action restores all four defaults.
- Fonts: System → `Typeface.DEFAULT`, Serif → `Typeface.SERIF`, Monospaced →
  `Typeface.MONOSPACE`, Rounded → the bundled Nunito (D18).
- Every glyph gets an outline: white around Black text, and
  `EdendaleColors.Background` around every other color. At opacity 0 the box
  disappears and the outline keeps the text legible. Build a
  `CaptionStyleCompat` with the text color as foreground, the box color times
  the opacity as background, a transparent window color, `EDGE_TYPE_OUTLINE`,
  the outline color as edge color, and the font's typeface. Also call
  `setApplyEmbeddedStyles(false)` and `setApplyEmbeddedFontSizes(false)`.
- Changes apply to the cue already on screen, including after the user returns
  from Settings while the video floats in PiP. Bitmap subtitles (PGS, VobSub)
  keep their authored pixels.
- **Placement:**
  - Cues are laid out inside the **visible video rectangle**, which is correct
    for both Fit and Fill, and stay clear of visible transport controls
    (`setBottomPaddingFraction` while the controls show).
  - Size: `clamp(16, visibleHeightDp × 0.055, 48)` dp, times the system caption
    font scale (`CaptioningManager.getFontScale()`), applied with
    `setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, size)`.
  - Simultaneous cues stack. The overlay never takes input, and it updates
    while paused.
  - `PlayerView`'s built-in subtitle view sits inside the content frame, which
    extends past the screen in Fill mode. Hide it and draw a `SubtitleView`
    sized to the visible rectangle, fed from `Player.Listener.onCues`.
- Downloaded (Wyzie) SRT, WebVTT, and ASS files go through the same path,
  including files with CRLF line endings and UTF-16 files with a BOM.

**Checklist:**
- [x] **B.4.1** A pure model: enums with raw values, the color table, the
  outline rule, opacity normalization, and the size formula.
- [x] **B.4.2** The Settings UI (pickers on handhelds, steppers or menus on TV)
  with Reset.
- [x] **B.4.3** The custom `SubtitleView` placed in the visible rectangle for
  Fit and Fill, with bottom padding while the controls show.
- [x] **B.4.4** Bundle Nunito per D18 under `res/font/`, and credit it in
  Attribution and the README.

**Tests:**
- [x] **B.4.T1** (JVM) `SubtitleAppearanceTest`: keys, defaults, unknown raw
  values, opacity rounding (0.123 → 0.12, −1 → 0, 2 → 1, NaN → 1), the outline
  rule, the size clamp at 16 and 48 and its multiplication by the scale, and
  the visible-rectangle math for Fit and Fill.
- [x] **B.4.T2** (instrumented) Media3 parses each fixture into the expected cue
  text: SRT with CRLF, SRT in UTF-16 LE with a BOM, WebVTT, and ASS dialogue
  with override tags.

**On device:**
- [ ] **B.4.D1** Yellow on Navy at 50 % is legible; in Fill mode the cue stays
  on screen; showing the controls lifts the cue; a PGS track keeps its look.

### B.5 — Playlist panel redesign (DIFF §3.10)

**Rules:**
- New tokens: `PlaylistActiveBackground` `#FFFFFF` and `PlaylistActiveText`
  `#000000`.
- The current **or** focused row uses the white fill, black text, and the larger
  title size. A playing indicator tells the current file apart from a row that
  only has focus.
- The panel opens scrolled to the current file.
- Identified episodes, and the current identified movie, show 16:9 artwork (the
  episode still, falling back to the show backdrop; the movie backdrop) with the
  title and the play time stacked beside it. Unknown sibling files keep the
  file-name row.

**Checklist:**
- [x] **B.5.1** Add both tokens to `EdendaleColors`, and add the two table rows
  and the "Playlist selection uses…" paragraph to this branch's `DESIGN.md`,
  copied verbatim from `git show origin/apple-27.0:DESIGN.md`.
- [x] **B.5.2** Extend `PlaylistEntry` with an artwork path and runtime, from
  `LibraryEpisodeEntity.stillPath`, the show's or movie's `backdropPath`, and
  `runtimeMinutes`.
- [x] **B.5.3** Restyle `PlaylistRow`; scroll to the current entry when the
  panel opens; TV focus applies the active style.

**On device:**
- [ ] **B.5.D1** TV: moving focus through the list shows the white row on the
  focused item and the playing indicator on the current one.

### B.6 — Speed changes and seeks (DIFF §3.2)

Verification only; Media3 already keeps the last frame on screen.

- [ ] **B.6.D1** Rapid play/pause at 1.5× doesn't stall the clock.
- [ ] **B.6.D2** Changing speed during playback never shows a black frame.
- [ ] **B.6.D3** A seek while paused shows the new frame.

If any check fails, record it in the Deviations log with the device and file.
Don't build a workaround without asking the owner.

---

## Section C — Binge flow and system integration

### C.1 — Episode progression rules (DIFF §3.4)

**Rules:**
- **Next episode:** among the show's stored episodes, the one with the smallest
  (season, episode) that is strictly greater than the current one.
  - It crosses seasons, and duplicate encodes of the same (season, episode) are
    skipped.
  - Season 0 specials advance among themselves and then into season 1; main
    seasons never fall back to season 0.
  - It returns nothing when the current episode isn't in the show.
- **Up Next window:** offer the next episode when the remaining time is more
  than 0 and at most 30 s, Loop is off, the duration is known, and a next
  episode exists.
- **Next-up for Continue Watching:** for each show, the furthest completed
  (season, episode) in watch progress. Only shows with no episode in progress
  get a candidate.

**Checklist:**
- [x] **C.1.1** A pure `EpisodeProgression` object over plain data (season,
  episode, id or URI), with no Room types.

**Tests (JVM):**
- [x] **C.1.T1** Port every case from `EpisodeProgressionTests` and
  `UpcomingEpisodePreviewTests`: crossing seasons, duplicates, specials into
  season 1, no fallback to season 0, the last episode, an episode not in the
  show, Loop on, an unknown duration, and the 30 s boundary.

### C.2 — Auto-advance and the Up Next card (DIFF §3.4)

**Rules:**
- At the natural end of an episode (`STATE_ENDED`; `PlayerLogic.isNaturalEnd`
  is 95 % or within 2 s of the end), write completed progress, then play the
  next episode in place, staying in PiP if it is active. With no next episode,
  finish as today.
- A newer manual play request (the playlist panel, the Up Next card, media keys)
  cancels a pending advance. Use a generation counter.
- Finished episodes keep their completed state; switching never overwrites it
  with a partial position.
- **Up Next card:**
  - It appears top-trailing during the last 30 s of a TV episode that has a
    stored successor (the C.1 window). It's recomputed on every tick, so seeking
    back hides it.
  - It shows the episode still (falling back to the show backdrop), the episode
    code (`S01E02`), and the title. Selecting it plays that episode.
  - It's hidden when Loop is on, for movies, when the duration is unknown, on
    the last stored episode, and in PiP.
  - It stays reachable while the controls are visible, can take focus on TV with
    a visible focus state, and uses a fade under reduced motion.

**Checklist:**
- [x] **C.2.1** Replace `finish()` on `STATE_ENDED` with the advance logic,
  reusing `switchTo`.
- [x] **C.2.2** A pure transition coordinator (generation counter, pending
  advance, manual override), tested on the JVM.
- [x] **C.2.3** An `UpNextCard` composable, wired into `PlayerScreen`.

**Tests:**
- [x] **C.2.T1** (JVM) Port the `PlayerSessionTransitionTests` cases: a natural
  end advances; a manual request during the advance wins; the last episode
  finishes; Loop on restarts instead; completion is written before the switch.

**On device:**
- [ ] **C.2.D1** Let an episode end: the next one starts and the first shows as
  completed. In PiP, the next episode plays inside the PiP window.

**Review notes (2026-10-02, on the uncommitted C.2 work):**
- [x] **C.2.R1** Store the card's label in natural case and apply
  `.uppercase()` in the view (A.4). `player_up_next` is in capitals today
  ("UP NEXT", "ALS NÄCHSTES", and so on).
- [x] **C.2.R2** `player_up_next_hint` is in all 19 string files but nothing
  uses it. Use it, for example as the card's click label, or remove it
  everywhere.
- [x] **C.2.R3** D-pad Up on the hidden-controls surface now focuses the Up
  Next card instead of revealing the controls; D3 only redirects Down, for the
  skip prompt. Keep Up revealing the controls, or record the change and its
  reason in the Deviations log.
- The card needs no focus handling of its own when it disappears: since
  83699b9, `PlayerScreen` seeds focus again whenever the focused node leaves.
- Resolved 2026-10-03: the label is stored as "Up Next" (and so on) and
  upper-cased in the card; `player_up_next_hint` is the card's TalkBack click
  label (Apple's accessibility hint); D-pad Up on the hidden-controls surface
  reveals the controls again. On TV the card is reached from the revealed
  controls: Down from the top row focuses it while it shows, and Down from
  the card returns to the transport row. No Deviations entry is needed.

### C.3 — Continue Watching next-up (DIFF §3.4)

**Rules:**
- For a show with no episode in progress, suggest the stored episode after the
  furthest completed one, even if the watched file has since been deleted.
- The suggestion never writes watch progress.
- Duplicate show records produce one card.
- In-progress items keep their current behavior. Next-up cards join the list,
  ordered by the completed episode's last-watched time. The shelf keeps its
  12-item cap.

**Checklist:**
- [x] **C.3.1** Extend `continueWatching` in `LibraryPresentation.kt` (pure)
  with next-up entries (fraction 0, no progress bar).
- [x] **C.3.2** Pick duplicate copies deterministically with D.5's preferred
  copy (until D.5 lands: the first by natural path order) instead of
  `toMap()`'s last-one-wins.

**Tests (JVM):**
- [x] **C.3.T1** Port `ContinueWatchingTests`: next-up after the furthest
  completed episode; none when an episode is in progress; it works after the
  file was deleted; no progress writes; duplicate shows give one card; the cap
  of 12; ordering.

### C.4 — Remove the timed auto-skip (D6)

- [x] **C.4.1** Delete `RECAP_LENGTH_MILLIS`, `CREDITS_LENGTH_MILLIS`,
  `MINIMUM_SKIPPABLE_MILLIS`, `recapSkipTargetMillis`, and `creditsStartMillis`
  from `PlayerLogic`; the `recapPending` / `creditsHandled` logic and the skip
  parts of `onPlaybackTick` from `PlayerActivity`; `skipRecap` and
  `skipCredits` from `PlayerChromeState`; the two toggles from the Playback
  section; the strings `player_skip_recap`, `player_skip_recap_detail`,
  `player_skip_credits`, and `player_skip_credits_detail` from every locale;
  and their tests.
- [x] **C.4.2** On the first launch after the upgrade, remove the stored keys
  `player.skipRecap` and `player.skipCredits`. Skip Prompts never reads them.
- [x] **C.4.3** Update the KDoc on `isNaturalEnd`, which explains its 95 % arm
  through skip-credits.

**Tests (JVM):**
- [x] **C.4.T1** With `player.skipRecap = true` and `player.skipCredits = true`
  stored, Skip Prompts still reads as off.

### C.5 — Skip prompts via TheIntroDB (DIFF §3.5)

**Rules:**
- **Opt-in.** `player.segmentPromptsEnabled`, default **off**, including on
  upgraded installs.
- **Request:** `GET https://api.theintrodb.org/v3/media` with the query
  parameters `tmdb_id`; `season` and `episode` for episodes (with the **show's**
  TMDB id and the stored season and episode numbers); and `duration_ms`
  (rounded). Header `Accept: application/json`. Timeout 8 s. No cookies and no
  cache (`useCaches = false`).
- **Skip the lookup** when the TMDB id is outside 1…10,000,000; when the season
  or episode is 0 or less (so season 0 specials get no lookup); or when the
  duration is unknown, 0 or less, or over 21,600 s.
- **Response:**
  - 404 means no segments. Any other non-200 status is an error, and no prompt
    shows.
  - 429 starts a provider-wide cooldown: the largest of `Retry-After`,
    `X-RateLimit-Reset`, and `X-UsageLimit-Reset` (seconds), with a minimum of
    60 s. No request is sent until it ends. One `IntroDbService` serves the
    whole app (Apple's `IntroDBService.shared`), so the cooldown holds across
    items and player sessions.
  - Reject a body whose `tmdb_id`, `type` (`movie` or `tv`), `season`, or
    `episode` doesn't match the request.
- **Decoding** (`intro`, `recap`, and `credits` arrays of
  `{start_ms, end_ms}`, each value nullable):
  - Drop entries where both values are null.
  - Credits need `start > 0`. A null credits end means "until the end of the
    file", and that segment `reachesEnd`.
  - Intro and recap need an end; a null start means 0.
  - Keep a segment only when `0 ≤ start < end ≤ duration`.
  - Deduplicate, drop **every** segment that overlaps another (never decide
    which content to cut), and sort by start.
- **Prompt:**
  - A **Skip Intro**, **Skip Recap**, or **Skip Credits** button shows
    bottom-trailing while the position is inside a segment, even when the
    controls are hidden. It never skips automatically.
  - It's hidden while scrubbing (`chrome.isScrubbing`), while a side panel
    covers the video, and in PiP.
  - The **S** key on a hardware keyboard activates it. TV: see D3.
  - After a press, it stays hidden until playback leaves that segment.
- **What a skip does:** a bounded segment seeks to its end. A credits segment
  that `reachesEnd` writes completed progress, then advances to the next
  episode (C.2), finishes, or restarts when Loop is on.
- **Cache:** in memory only, per player session, with at most 12 entries (clear
  them all when it's full). It's cleared when the player closes and when the
  setting is turned off. Nothing is written to disk. Playback never waits for
  the lookup, and a response for a previous item is discarded (generation
  token).
- **Copy:** Settings → Skip Prompts and the panel toggle use Apple's text: "Look
  up intro, recap, and credits timestamps with TheIntroDB" and "When enabled,
  TheIntroDB receives the title’s TMDB ID, episode numbers, video duration, and
  your IP address. Skipping always requires a button press."

**Checklist:**
- [x] **C.5.1** A `com.babasama.edendale.introdb` package with request
  validation, a transport interface, the decoder, and the cooldown, all pure.
- [x] **C.5.2** `AndroidIntroDbTransport` in `AndroidEdendaleCore.kt`
  (`HttpURLConnection`, an 8 s overall timeout, no cache, and response headers
  exposed for 429).
- [x] **C.5.3** A pure segment controller: the active segment, suppression, the
  result of a skip (`Seek(endMs)` or `Finish`), the cache, and enable/disable.
- [x] **C.5.4** The prompt UI in `PlayerScreen`, the S key, and TV focus (D3).
- [x] **C.5.5** The Settings → Skip Prompts section and the Playback toggle in
  the player panel.
- [x] **C.5.6** README: TheIntroDB as an opt-in network service, and what it
  receives.

**Tests (JVM):**
- [x] **C.5.T1** Port all `IntroDBTests` cases: the URL and parameters for a
  movie and an episode; every skip-the-lookup bound (id 0 and 10,000,001,
  season 0, episode 0, duration 0 s, 21,600 s accepted, 21,601 s rejected);
  404; the 429 cooldown with each header and the 60 s floor; a mismatched
  identity rejected; the decoding rules; overlap rejection; deduplication;
  sorting.
- [x] **C.5.T2** Controller: suppression until the range is left; the skip
  result for bounded and `reachesEnd` segments; the 12-entry cap; clearing on
  disable; the transport is never called while the setting is off.

**On device:**
- [ ] **C.5.D1** With the setting on, a title known to TheIntroDB shows Skip
  Intro, and pressing it (or S on a keyboard) seeks past the intro.

### C.6 — MediaSession and system surfaces (DIFF §3.18, D13)

**Rules:**
- Add `androidx.media3:media3-session` (same version as the rest, D1).
  `PlayerActivity` owns one `MediaSession` for its lifetime and releases it in
  `onDestroy`.
- Wrap ExoPlayer in a `ForwardingSimpleBasePlayer` (or `ForwardingPlayer`) that:
  - reports the seek back/forward increments from App Controls and implements
    `seekBack()` / `seekForward()` with them, following live changes (B.1.6);
  - advertises `COMMAND_SEEK_TO_NEXT` / `COMMAND_SEEK_TO_PREVIOUS` when a
    playlist neighbor exists, and routes them to `switchToNeighbor`;
  - exposes metadata: the title, the episode code or show, and the TMDB artwork
    URI.
- Headset buttons, Google Assistant ("pause", "skip forward"), Bluetooth
  controls, and Android TV's system media UI then reach the player, including in
  PiP. Keep the `dispatchKeyEvent` handling for the focused window, and never
  handle a key twice.
- The PiP actions follow App Controls (B.1).
- **Audio Output** row in the panel's Playback section, on handhelds only: opens
  the system output switcher with
  `androidx.mediarouter.app.SystemOutputSwitcherDialogController.showDialog(context)`
  (mediarouter 1.8.x). If it returns false, show a short message that the
  switcher isn't available.
- The system volume UI belongs to the platform; there's nothing to build
  (DIFF's volume HUD is Apple-only).

**Checklist:**
- [x] **C.6.1** Dependency and session lifecycle.
- [x] **C.6.2** The forwarding player, with increments and next/previous.
- [x] **C.6.3** The Audio Output row.

**On device:**
- [ ] **C.6.D1** A Bluetooth headset's play/pause and next buttons work while
  the player is in PiP; "Hey Google, pause" works; skips follow App Controls.
- [ ] **C.6.D2** Android TV: the system media UI shows the title and controls.

---

## Section D — SMB hardening and library rules

### D.1 — Buffered SMB reads (DIFF §3.13)

Why: Media3 reads in small pieces, so one SMB round trip per read caps
throughput below video bitrates over a phone hotspot, Tailscale, or a VPN.

**Rules:**
- A worker thread fetches **1 MiB** chunks and keeps up to **48 MiB** ahead of
  the read position, within a **64 MiB** cache.
- The chunk a blocked read needs always goes first, so a seek never waits
  behind read-ahead.
- After a failure on a file that opened once, drop the connection and reopen it
  with delays of **0.25, 0.5, 1, 2, 4, and 8 s**. The read fails only after
  every retry has failed.
- An idle connection (paused playback) gets a **keep-alive after 20 s**.
- Cancellation (`close()`, player release) fails blocked reads at once. The
  "connection lost" message names the host.
- **Android addition:** on low-memory devices (`ActivityManager.isLowRamDevice()`
  or a `memoryClass` under 192 MB), keep 16 MiB ahead within a 24 MiB cache.
- Media3 reuses one `DataSource` for a media period and reopens it on every
  seek. Keep the buffered source (connection and cache) across `close()` →
  `open()` for the same URI, so the container index at the end of the file
  stays cached. Release it when a different URI opens, or after 30 s without an
  open.

**Checklist:**
- [x] **D.1.1** A pure `BufferedByteSource` over an interface (`open`,
  `read(position, buffer, offset, length)`, `length`, `keepAlive`, `close`),
  with an injectable clock and sleeper so tests run in virtual time.
- [x] **D.1.2** A jcifs-ng implementation of the interface over
  `SmbRandomAccessFile` (keep-alive is a cheap metadata call).
- [x] **D.1.3** `SmbDataSource` serves reads from the buffered source; remove
  the 512 KiB `readAhead` path.

**Tests (JVM):**
- [x] **D.1.T1** Port `BufferedByteSourceTests`: sequential reads fill
  read-ahead up to the limit; a seek gets its chunk first; reconnect after a
  failure follows the delay sequence; the read fails after the last retry;
  keep-alive after 20 s idle; cancellation unblocks a waiting read; the cache
  never exceeds its limit; low-memory sizing; reuse across close/open for the
  same URI.

**On device:**
- [ ] **D.1.D1** Play a high-bitrate SMB file over a phone hotspot or
  Tailscale: no stalls at the file's bitrate. Turning Wi-Fi off and on mid-play
  resumes after a reconnect.

### D.2 — Source records (Room version 3)

**Rules** (DIFF §3.12, "Library integration"):
- `library_folder` gains nullable columns: `kind` (raw values from H.1: `local`,
  `smb`, `nfs`, `sftp`, `webdav`, `s3`, `gdrive`, `onedrive`, `dropbox`),
  `displayPath`, `accountKey`, `lastScannedAt` (epoch milliseconds),
  `changeCursor` (reserved for later), and `status` (`offline`, `needsSignIn`,
  or null).
- Migration 2 → 3 adds the columns and fills in `kind` (`smb://` → `smb`,
  `content://` → `local`). No data is dropped.

**Checklist:**
- [x] **D.2.1** Turn on schema export first: `exportSchema = true` and the KSP
  argument `room.schemaLocation` set to `$projectDir/schemas` (also add that
  folder to the `androidTest` assets). Build at version 2 and commit the
  version 2 schema JSON.
- [x] **D.2.2** Add the columns, set `version = 3`, add `MIGRATION_2_3`, and
  register it next to `MIGRATION_1_2` in `EdendaleApplication`. Commit the
  version 3 schema JSON.

**Tests:**
- [x] **D.2.T1** (JVM) The `kind` backfill function.
- [x] **D.2.T2** (instrumented) `MigrationTestHelper` from 2 to 3 keeps every
  row and fills in `kind`.

### D.3 — Rescan throttle and per-source status

**Rules:**
- The automatic rescan on each Downloaded visit skips **remote** sources
  scanned in the last **15 minutes**. Local sources always rescan. A manual
  Rescan always scans.
- Failures are recorded **per source**: `offline` for unreachable hosts and
  timeouts, `needsSignIn` for authentication failures. They show on that
  source's row in Settings → Sources and in Downloaded, not as a library-wide
  error. The next successful scan clears them.
- `lastScannedAt` is set after every successful scan.

**Checklist:**
- [x] **D.3.1** Pure throttle and error-classification functions.
- [x] **D.3.2** `LibraryRepository.scan` records `status` and `lastScannedAt`;
  `rescanAll` applies the throttle; a manual rescan bypasses it (add a Rescan
  action for each source if none exists).
- [x] **D.3.3** Source rows show the status with a retry action.

**Tests (JVM):**
- [x] **D.3.T1** The throttle at 14:59, 15:00, and 15:01 since the last scan;
  local sources are never throttled; jcifs authentication errors classify as
  `needsSignIn` and I/O errors as `offline`.

### D.4 — Keep logins; Settings → Accounts

**Rules:**
- **Removing a source never deletes its saved login or account** (DIFF §3.12;
  Apple §J.11, decision 5).
- **Settings → Accounts** lists every saved login (SMB host and user for now;
  Section H adds cloud accounts and other servers) with the number of sources
  using it, and a Remove action with confirmation. Removing a login keeps its
  sources; their next scan shows "needs sign-in".

**Checklist:**
- [x] **D.4.1** `removeFolder` stops calling `removeCredentials`. Update
  `remove_source_message_smb` and `remove_source_message_generic` in every
  locale to say the login stays in Settings → Accounts.
- [x] **D.4.2** `SmbCredentialsStore` can list its hosts; add the Accounts
  section with usage counts.

**Tests (JVM):**
- [x] **D.4.T1** Usage counts per host (case-insensitive host match, like
  `SmbClient.hostOf`).

### D.5 — Play From: several copies of one title (DIFF §3.11)

**Rules:**
- The same TMDB id can be imported from several sources, and each file stays
  its own record.
- **Order:** the page's own copy first, then copies in local folders, then the
  rest by source name (natural, case-insensitive comparison).
- **Play** starts the first copy whose source isn't `offline` or `needsSignIn`,
  or the first copy when every source is.
- Movies get an icon-only **Play From** menu beside Play when there is more
  than one copy. Episodes get a **Play From** section in the episode's context
  menu (long-press; on TV, the menu key), and the subtitle adds "· N sources".
- Each menu row shows the source name, then `Kind · filename`, plus
  "· Unavailable" when the source is offline.
- Episode slots merge every copy of the show into one list keyed by (season,
  episode), in season and episode order (ties broken by path).

**Checklist:**
- [x] **D.5.1** Pure ordering, preference, and slot functions.
- [x] **D.5.2** UI on the local movie and show details, and everywhere Play
  starts a local file (Continue Watching uses the preferred copy).

**Tests (JVM):**
- [x] **D.5.T1** Port `PlaybackSourcesTests`.

---

## Section E — Audio

### E.1 — Equalizer profiles and Audio Booster (DIFF §3.6)

**Rules:**
- Profiles (raw values): `flat`, `movies` (**default**), `music`, `dialogue`,
  `nightMode`.
- Bands: 60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, and 16000 Hz.

  | Profile | Preamp (dB) | Bands (dB) |
  |---|---|---|
  | Flat | 0 | 0 0 0 0 0 0 0 0 0 0 |
  | Movies | −8 | 8 5 3 0 0 2 3 2 1 0 |
  | Music | −4 | 4 2 0 −1 −1 2 3 3 2 1 |
  | Dialogue | −6 | −3 −1 0 5 6 5 3 1 0 −1 |
  | Night Mode | −5 | −5 −2 1 4 5 5 3 1 0 −1 |

- Night Mode is equalization only; it is not a compressor.
- User adjustments (preamp and each band) are stored separately and added to
  the profile. **Changing the profile resets them.** Every value clamps to
  −20…+20 dB.
- **Audio Booster** (default off) adds **+10 dB** to the preamp, through the EQ
  rather than the volume. The effective preamp clamps to ±20 dB. Turning the
  booster off restores the previous setting.
- Settings apply live and carry across files. Keys:
  `audio.enhancementProfile` (raw value), `audio.enhancementPreamp` (float),
  `audio.enhancementBands` (a JSON array of 10 floats; any other length reads as
  zeros), and `audio.boosterEnabled` (boolean).
- **Filter**, matching Apple's `AudioEQProcessor.swift`: each band is an RBJ
  peaking biquad with a 1-octave bandwidth:
  - `A = 10^(gain/40)`, `w0 = 2π·f/fs`,
    `alpha = sin(w0) · sinh(ln(2)/2 · 1 · w0/sin(w0))`
  - `b0 = 1 + alpha·A`, `b1 = −2·cos(w0)`, `b2 = 1 − alpha·A`,
    `a0 = 1 + alpha/A`, `a1 = −2·cos(w0)`, `a2 = 1 − alpha/A`, all divided by
    `a0`.
  - A band at or above the Nyquist frequency, or with |gain| < 0.01 dB, passes
    through.
  - One cascade per channel. The preamp gain `10^(preamp/20)` multiplies the
    cascade's output.
- When the effective preamp and every band are 0, audio passes through
  bit-exact.
- **Android specifics:**
  - A `BaseAudioProcessor` that accepts `PCM_16BIT` and `PCM_FLOAT` at any
    sample rate and channel count, and computes in floating point with state
    per channel. 16-bit output clamps to [−32768, 32767]. (Apple has no limiter;
    Android must never wrap around.)
  - Install it through a `DefaultRenderersFactory` subclass that overrides
    `buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)`
    to return
    `DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(eq)).build()`.
  - Media3 evaluates `isActive()` only when the sink reconfigures. So keep the
    processor active, pass audio through bit-exact while the settings are flat,
    and apply new coefficients at the next buffer boundary (no reconfiguration,
    no glitch).
  - Passthrough and offload (AC3, E-AC3, or DTS sent undecoded to a receiver)
    bypass audio processors. Settings → Audio Enhancement says the EQ applies
    only to audio Edendale decodes.
  - Don't use `android.media.audiofx.Equalizer`; its bands depend on the
    device.
- **UI:** Settings → Audio Enhancement (a profile menu, preamp and band sliders
  or steppers, Reset Adjustments, and the booster toggle). The player panel's
  Playback section has the Audio Booster toggle with Apple's detail text,
  "Increase audio gain for quiet recordings".

**Checklist:**
- [x] **E.1.1** Pure profile model, clamping, persistence parsing, coefficient
  function, and a pure DSP core (`process(FloatArray, channels)`).
- [x] **E.1.2** `EqAudioProcessor` and the renderers-factory wiring in
  `PlayerActivity`.
- [x] **E.1.3** The Settings section and the panel toggle.

**Tests (JVM):**
- [x] **E.1.T1** Port `AudioEnhancementTests`: the profile table, the Movies
  default, clamping, the booster's +10 dB with clamping, reset on profile
  change, unknown raw values, and a wrong band count.
- [x] **E.1.T2** Coefficients for (1 kHz, +6 dB, 48 kHz) and (60 Hz, −5 dB,
  44.1 kHz) match the formula; bands pass through at or above Nyquist (the 12,
  14, and 16 kHz bands at 22.05 kHz) and when |gain| < 0.01 dB.
- [x] **E.1.T3** DSP: a sine at a band's center frequency gains that band's
  boost within ±0.5 dB; flat settings are bit-exact; 16-bit output clamps at
  full scale.

**On device:**
- [ ] **E.1.D1** Switching profile mid-play is glitch-free; the booster is
  audibly louder; HDMI passthrough to a receiver is unaffected.

### E.2 — DTS and TrueHD decoding (D5)

- [x] **E.2.1** Record the owner's D5 choice here: (a) Jellyfin decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`), confirmed by the owner on 2026-10-02.
- [x] **E.2.2** For (a): add `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`
  and call `setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` (platform
  decoders first, FFmpeg as the fallback), and update the README's licence
  section for a GPL-3.0 APK. For (b): an NDK build of Media3's FFmpeg extension
  with an LGPL-only FFmpeg configured for just the needed audio decoders, a
  checked-in build script, and CI that builds it without secrets.
- [x] **E.2.3** Generated fixtures (A.5.6) with a DTS track and with a TrueHD
  track play with sound on a phone that has neither decoder, and the EQ applies
  to their decoded audio.

---

## Section F — Picture adjustments and video enhancement

### F.1 — Effects plumbing (Android-specific)

**Rules:**
- Use Media3's video effects: `ExoPlayer.setVideoEffects(...)` with Edendale
  `GlEffect`s, from `androidx.media3:media3-effect` (same version, D1).
- **Install on demand.** Media3 requires `setVideoEffects` before `prepare()`
  to set up the effects pipeline at all. When nothing needs effects at the start
  of playback (Picture neutral and Enhancement Off), don't install it, so video
  goes straight from the decoder to the screen. Turning something on mid-play
  installs the pipeline and re-prepares at the current position, the way
  `attachOnlineSubtitle` already re-prepares.
- **Live changes.** Effects read their settings from a thread-safe holder on
  each frame. While paused, call `setVideoEffects(VideoFrameProcessor.REDRAW)`
  so the paused frame updates (Show Original, sliders).
- **Output size.** In the effects path, the output takes the `SurfaceView`'s
  size. Call `SurfaceHolder.setFixedSize` with the target size (F.3), and treat
  the panel's real resolution as the "display":
  `Display.getMode().physicalWidth` / `physicalHeight`. TV boxes often draw
  their interface at 1080p on a 4K panel, and without this they would downscale
  4K video.
- **HDR and Dolby Vision:** bypass every effect. Picture and Enhancement show
  "Not available for HDR video". Detect HDR from the selected video track's
  `Format.colorInfo` (`C.COLOR_TRANSFER_ST2084` or `C.COLOR_TRANSFER_HLG`) or a
  Dolby Vision MIME type.
- Shaders are GLSL ES 3.0 fragment shaders under `src/main/assets/shaders/`,
  drawn by `BaseGlShaderProgram` subclasses. Keep the passes in plain classes
  behind a thin `GlEffect` wrapper, so they can move into a custom renderer if
  G.1 needs one.

**Checklist:**
- [x] **F.1.1** The dependency, the install-on-demand logic, and the
  re-prepare path.
- [x] **F.1.2** The settings holder, and REDRAW while paused.
- [x] **F.1.3** The fixed surface size from the target, using the physical
  display mode.
- [x] **F.1.4** The HDR and Dolby Vision bypass, with the panel message.
- [ ] **F.1.5** **On device:** compare effects installed but neutral against
  effects not installed: no visible difference, no added stutter, and 4K video
  on a 4K TV box still renders at 4K.
- [ ] **F.1.6** **On a real Android TV set** (not only a box): check whether
  video drawn through the effects path loses the TV's own picture processing.
  Record the finding. D11 keeps TV at Off until this passes.

### F.2 — Picture adjustments (DIFF §3.7)

**Rules:**

| Adjustment | Range | Neutral | Step |
|---|---|---|---|
| Brightness | 0–2 | 1 | 0.05 |
| Contrast | 0–2 | 1 | 0.05 |
| Gamma | 0.25–3 | 1 | 0.05 |
| Saturation | 0–3 | 1 | 0.05 |
| Hue | 0–360° | 0 | 5° |

- Stored on the device as one JSON object under the key `video.adjustments`
  (fields `brightness`, `contrast`, `gamma`, `saturation`, `hue`), normalized on
  load: a non-finite value becomes neutral, then the value clamps and snaps to
  its step.
- **Show Original** temporarily applies neutral values without changing the
  stored ones. **Reset** returns every value to neutral.
- Neutral values skip the effect entirely.
- **Math.** Port `ColorAdjustment.metal` exactly in one GLSL pass. Media3's
  built-in `Brightness`, `Contrast`, and `HslAdjustment` use different math and
  ranges. In order:
  1. `rgb *= brightness`
  2. `rgb = (rgb − 0.5) · contrast + 0.5`
  3. if gamma ≠ 1: `rgb = pow(max(rgb, 0), 1/gamma)`
  4. `luma = dot(rgb, (0.2126, 0.7152, 0.0722))`; `rgb = mix(luma, rgb, saturation)`
  5. rotate the hue about the axis (1, 1, 1)/√3 by `hue` degrees (skip when
     |hue| < 0.001)
  6. clamp to 0…1
- TV uses −/+ steppers instead of sliders.
- The panel section is **Picture**, after Aspect Ratio.

**Checklist:**
- [x] **F.2.1** The pure model, normalization, and JSON.
- [x] **F.2.2** `ColorAdjustmentEffect`, the GLSL port.
- [x] **F.2.3** The panel UI with Show Original and Reset.

**Tests:**
- [x] **F.2.T1** (JVM) Normalization (snapping, clamping, non-finite values),
  neutral detection, and the JSON round trip.
- [x] **F.2.T2** (JVM) A Kotlin reference of the color math gives hand-computed
  results: neutral is the identity; saturation 0 gives the luma; a 120° hue turn
  moves pure red to pure green.
- [x] **F.2.T3** (instrumented) Render a solid-color texture through the
  effect, read the pixels back, and match the Kotlin reference within 1/255.

### F.3 — Upscaler (DIFF §3.16; Apple §E.2)

**Rules:**
- **Target resolution:** port `SpatialUpscaler.targetResolution` exactly,
  including its even-size rounding:
  - A source at or above the display in both dimensions isn't upscaled
    (sharpen only).
  - A source below 1080p (width under 1920 and height under 1080) scales to fit
    within min(1920, display width) × min(1080, display height).
  - A 1080p source scales to the display when the display is 4K (width at least
    3840 or height at least 2160).
  - "Display" means the video's on-screen viewport in physical pixels: the
    physical display mode (F.1) on TV, and the visible video rectangle on phones
    and tablets.
- **Upscaler:** AMD FSR 1 EASU (MIT licence; keep its copyright notice in the
  shader and the README) takes the place of Apple's MetalFX. Prototype first
  with Media3's built-in `LanczosResample.scaleToFit(width, height)`, which is
  also Apple's fallback, to prove F.1's plumbing before writing EASU.
- **Benchmark** Qualcomm's Snapdragon GSR (BSD-3-Clause) against EASU on a
  mid-range phone. Use GSR only if it's clearly cheaper at similar quality, and
  record the numbers in Findings.
- The panel's resolution label uses Apple's format, `W×H → W×H` (for example
  `1280×720 → 1920×1080`), and omits the target when there's no upscale.

**Checklist:**
- [x] **F.3.1** The pure target-resolution function and the label.
- [x] **F.3.2** The Lanczos prototype with F.1's surface sizing.
- [x] **F.3.3** The EASU GLSL port.
- [ ] **F.3.4** The GSR benchmark, recorded in Findings.

**Tests:**
- [x] **F.3.T1** (JVM) Port the `targetResolution*` cases from
  `MetalEnhancementPipelineTests` (720p, 1080p, and 4K sources; even
  dimensions), and add 720p on 1080p and 4K displays, 1080p on 1080p and 4K,
  4K on 4K, odd sizes rounded to even, and ultrawide and portrait sources.
- [x] **F.3.T2** (instrumented) EASU on a step-edge image: the output size is
  correct and the edge stays monotonic, with no ringing beyond ±2/255.

### F.4 — Contrast Adaptive Sharpening (Apple §E.3)

- Port the math in `CASShader.metal` exactly (not AMD's reference variant), so
  Sharpness means the same thing on both platforms. Sharpness ranges 0–1,
  default 0.5, step 0.05; 0 skips the pass.
- [x] **F.4.1** The CAS GLSL port.
- [x] **F.4.T1** (instrumented) Sharpness 0 is the identity; on a blurred edge,
  local contrast grows with sharpness; flat areas don't change.

### F.5 — Temporal denoise (Apple §E.4)

- Port `TemporalDenoise.metal`: a pair of history textures, blended with the
  current frame where the difference is under the motion threshold (0.08).
  Strength ranges 0–1, default 0.5. It runs only in High Quality.
- Reset the history on seek (`GlShaderProgram.flush()`), preset change, item
  switch, and size change.
- [x] **F.5.1** The denoise GLSL port with history handling.
- [x] **F.5.T1** (instrumented) A static noisy input converges toward its mean;
  a moving edge doesn't ghost (a difference above the threshold uses the current
  frame); `flush()` clears the history.

### F.6 — Budget and capability (Apple §E.6)

**Rules:**
- Budget: under **8 ms** of GPU time per frame for all enhancement passes
  together. Over budget, drop denoise first, then the upscale. Sharpening at
  the source size always stays.
- Measure with GPU timer queries (`GL_EXT_disjoint_timer_query`) where the
  driver supports them, reading each result a few frames later so nothing
  stalls. Otherwise use ExoPlayer's dropped-frame count (`DecoderCounters`).
  Use a rolling window with hysteresis so stages don't flap on and off.
- Also step down when `PowerManager.getCurrentThermalStatus()` reaches
  `THERMAL_STATUS_MODERATE` or battery saver is on.
- **Capability check (D11):** run once per device and keep the result locally:
  OpenGL ES 3.0 or later, not `isLowRamDevice()`, and a short offscreen
  benchmark of the Balanced passes at 1080p that stays under the budget.
  Devices that fail start at Off, but the user can still pick any preset.
- Nothing about performance leaves the device.

**Checklist:**
- [x] **F.6.1** A pure governor state machine (inputs: frame times, thermal
  status, battery saver; output: the active stages), tested on the JVM.
- [x] **F.6.2** Timer-query or dropped-frame measurement, and the thermal
  listener.
- [x] **F.6.3** The capability check and its cached result.

**Tests (JVM):**
- [x] **F.6.T1** An over-budget sequence drops denoise, then the upscale; it
  recovers with hysteresis; `THERMAL_STATUS_MODERATE` forces a step down;
  sharpening is never dropped.

### F.7 — Enhancement UI

- The panel's last section is **Enhancement**, with: a Preset menu (Off,
  Sharpen Only, Balanced, High Quality, Apple's names); a Sharpness slider
  (0–1, step 0.05, disabled when Off); a Denoise slider (High Quality only); a
  Show Original toggle; the resolution label (F.3); and, later, Motion
  Smoothing (G.5).
- The settings live in memory for the app process and aren't saved (D11).
- TV uses −/+ steppers.
- [x] **F.7.1** The UI.
- [ ] **F.7.D1** **On device:** a 720p file on a 1080p phone shows
  `1280×720 → 1920×1080`; Show Original toggles while paused (REDRAW); changing
  the preset doesn't stall playback.

### F.8 — Enhancement acceptance

- [ ] **F.8.1** A 720p test file upscaled to 1080p on a phone, and to 4K on a TV
  box, looks visibly sharper than Off and stays within budget on the reference
  devices. Turning everything Off restores the direct path from the next item.
- [ ] **F.8.2** Findings record each device, its GPU, the time per pass, and the
  stages the governor kept.

---

## Section G — Frame generation experiment (DIFF §3.17; Apple §I; D12)

**Scope:** phones and tablets only; sources at 30 fps or less; displays whose
refresh rate is at least twice the source rate (`Display.getSupportedModes()`).
Never on Android TV, because TVs do their own motion smoothing. Off by default.

### G.1 — Feasibility and go/no-go

- [ ] **G.1.1** One-day test: a custom `GlShaderProgram` that outputs an extra
  frame at the midpoint timestamp between two input frames (start with a plain
  50/50 blend). Media3 documents that effects which change frame timestamps
  aren't supported during playback, so check:
  - Are the midpoint frames released on time (frame timestamps from
    `VideoFrameMetadataListener`)?
  - Does A/V sync hold?
  - Do seeks and flushes behave?
  - Does the display switch to twice the frame rate?
- [ ] **G.1.2** If G.1.1 fails, prototype a custom renderer instead: ExoPlayer
  renders into a `SurfaceTexture` Edendale owns, and a GL thread runs
  enhancement and interpolation and presents each frame with
  `eglPresentationTimeANDROID`, using the buffer timestamps. Request twice the
  frame rate with `Surface.setFrameRate`.
- [ ] **G.1.3** Estimate the cost: port only the coarse motion-estimation pass,
  and time it at 1080p on a recent flagship and on a mid-range phone.
- [ ] **G.1.4** Write **Findings — G.1** below, with the presentation path
  chosen, the timings, and a go/no-go recommendation. **Stop for owner review
  before G.2.**

Going ahead needs all of these: a 1080p synthetic frame (24 → 48 fps) in under
8 ms on a recent flagship; A/V sync within one display refresh; and no crash or
desync across seeks, pauses, and track switches.

### G.2 — Motion estimation (Apple §I.1)

- [ ] **G.2.1** GLSL ES 3.1 compute shaders porting `MotionEstimation.metal`:
  coarse 16×16 blocks searched ±16 px with the per-pixel offset cost; a 4×4
  refinement searched ±4 px around the coarse vector; and densifying to
  per-pixel vectors (RG16F), with an optional 3×3 median filter.
- [ ] **G.2.2** Scene-cut counting on the GPU: a block is unmatched when its
  best match still differs by more than 0.06 mean luma, and a cut is 30 % or
  more unmatched blocks. On a cut, the synthetic slot repeats frame N−1, with
  no CPU readback.
- [ ] **G.2.3** Sources wider than 1920 px run motion estimation at half
  resolution.

### G.3 — Warping and blending (Apple §I.2)

- [ ] **G.3.1** Port `FrameInterpolation.metal`: a two-way warp at t = 0.5,
  occlusion-aware blending, and hole filling from frame N.

### G.4 — Scheduling and presentation (Apple §I.3–I.4)

- [ ] **G.4.1** Port `FrameInterpolationScheduler` as a pure Kotlin state
  machine: the synthetic frame first and the real frame one refresh later. Show
  the real frame directly for the first frame, after seeks, after dropped
  frames, and after gaps of 1.5 frame durations or more. No interpolation while
  paused or scrubbing.
- [ ] **G.4.2** Reset on seek, pause, track switch, item switch, and size
  change. History is committed after interpolating, never before.
- [ ] **G.4.3** Wire it into the presentation path chosen in G.1.

### G.5 — Gating and UI (Apple §I.6)

- [ ] **G.5.1** A **Motion Smoothing** toggle in the Enhancement section, shown
  only when eligible, labeled with the rates (for example `24 fps → 48 fps`).
- [ ] **G.5.2** The governor turns Motion Smoothing off first when over budget
  or under thermal pressure, because it costs more than any enhancement stage.

### G.6 — Tests and acceptance (Apple §I.7–I.8)

- [ ] **G.6.T1** (JVM) Port the `FrameInterpolationScheduler` draw-order cases
  from `FrameInterpolationTests`.
- [ ] **G.6.T2** (instrumented) Port the GPU cases: a horizontal pan gives
  non-zero vectors of the right sign; a static scene gives near-zero vectors;
  unrelated frames trip the scene cut and repeat frame N−1; letterbox bars and
  flat areas stay unchanged; a fade of about 4 % blends; the interpolated pan
  lands at the midpoint.
- [ ] **G.6.D1** **On device:** 30 minutes of 24 fps playback with Motion
  Smoothing on a flagship. Record the battery drop and thermal state, and
  review artifacts on pans, cuts, fades, and letterboxed scenes.

---

## Section H — Storage providers (DIFF §3.12; Apple §J)

Read Section J of `git show origin/apple-27.0:ENHANCEMENT.md` in full before
starting. Not planned: Box (it needs a client secret), MEGA, and FTP. Plex,
Jellyfin, and Emby would be a separate feature.

### H.1 — Connector contract, canonical URLs, and account keys

**Rules:**
- Raw kind values are persisted and must never be renamed: `local`, `smb`,
  `nfs`, `sftp`, `webdav`, `s3`, `gdrive`, `onedrive`, `dropbox`.
- Item URLs are canonical and credential-free:

  | Kind | Item URL |
  |---|---|
  | `smb` | `smb://host/share/path/Name.ext` |
  | `nfs` | `nfs://host/export/path/Name.ext` |
  | `sftp` | `sftp://host[:port]/path/Name.ext` |
  | `webdav` | `davs://host[:port]/path/Name.ext` (`dav://` for plain HTTP) |
  | `s3` | `s3://<account>/<bucket>/<key path>/Name.ext` |
  | `gdrive` | `gdrive://<account>/<fileId>/Name.ext` |
  | `onedrive` | `onedrive://<account>/<driveId>/<itemId>/Name.ext` |
  | `dropbox` | `dropbox://<account>/<fileId>/Name.ext` |

- `<account>` is the first 32 hex digits of `SHA-256("<kind>:<subject>")`. The
  subject is Google's `sub`, the Microsoft user `id`, or Dropbox's
  `account_id`. For S3 it is `endpoint|bucket|accessKeyID`, with the endpoint
  lowercased and its trailing `/` trimmed. The account key is also the
  credential-store key.
- Every URL ends with the real file name, so the filename parser runs unchanged,
  before any enrichment.
- **Connector contract:** `validate()`, `list(directory)`, and
  `enumerateVideos(under)`. The default enumeration is breadth-first, capped at
  **2,000 folders**, and skips dot-files. A failure at the root throws; a
  failure deeper down skips that branch only. Dropbox and OneDrive override it
  with their recursive listings. Entries carry `size`, `duration` (Drive
  `videoMediaMetadata.durationMillis`, Graph `video.duration`), and `modified`.
- **Library:** new sources fill in `kind`, `displayPath` (for example
  `Google Drive › My Drive › Movies`), `accountKey`, and `lastScannedAt` (D.2).

**Checklist:**
- [x] **H.1.1** Pure URL builders and parsers, the account-key function, the
  connector interface, and the default enumerator.
- [x] **H.1.2** Move SMB scanning onto the interface, with no behavior change.
- [x] **H.1.T1** (JVM) Port `ConnectorTests`: URL round trips for every kind
  (including percent-encoding and names with spaces), account keys (copy
  Apple's expected strings), the enumeration cap, skipping dot-files, and root
  versus branch failures.

### H.2 — Remote byte source and Media3 data source (Apple §J.5)

**Rules:**
- One random-access byte source per HTTP provider: **4 MiB** `Range` chunks,
  prefetching the next chunk while reads are sequential, and keeping the last
  **8 chunks** (32 MiB), so MKV cues and the MP4 `moov` box at the end of a file
  stay cached.
- An OkHttp client with no cache, no cookie jar, and system TLS validation.
  **Never** log URLs, tokens, or headers.

  | Response | Action |
  |---|---|
  | 206 | Serve the range |
  | 200 at offset 0 | Accept it (Graph may ignore `Range`) |
  | 200 at any other offset | Retry once, then fail |
  | 401 | One single-flight token refresh, then retry |
  | 403 rate limit, 429, 5xx | Back off 0.5, 1, then 2 s with jitter, then fail |
  | 403 expired signed link, 410 (Dropbox) | Resolve a new link once |
  | 404 | Fail with "This file is no longer in <provider>" |

- Media3: one Edendale `DataSource` that dispatches by scheme (`smb`, `nfs`,
  `sftp`, `dav`, `davs`, `s3`, `gdrive`, `onedrive`, `dropbox`) to the right
  byte source, installed as the base source of `DefaultDataSource` the way
  `SmbDataSource` is today. Never hand a provider's HTTPS URL or token to
  Media3's `DefaultHttpDataSource`.
- SFTP and NFS byte sources reuse D.1's buffered source.

**Checklist:**
- [x] **H.2.1** `RemoteByteSource` (pure logic over a small HTTP interface) and
  its OkHttp implementation.
- [x] **H.2.2** The scheme-dispatching `DataSource`.
- [x] **H.2.T1** (JVM) Port `RemoteByteSourceTests` against a local
  Range-capable stub (A.5.5): chunking, prefetch, cached backward seeks, a 401
  that triggers exactly one refresh shared by concurrent readers, a 410 that
  resolves a new link, an ignored `Range` at offset 0 and elsewhere, the backoff
  sequence, the 404 message, and cancellation.

### H.3 — WebDAV

- `PROPFIND` with `Depth: 1` (OkHttp), using Basic or Digest authentication.
  Decode relative and absolute `href`s, with percent-decoding. Suggest
  `/remote.php/dav/files/<user>/` for Nextcloud and ownCloud.
- [x] **H.3.1** The connector and the listing parser.
- [x] **H.3.2** The server form (address, user, password), with the login saved
  in an encrypted store keyed by host and port and excluded from backup.
- [ ] **H.3.3** The valid-HTTPS path, end to end.
- [ ] **H.3.4** (D10) Self-signed certificates with fingerprint pinning, and LAN
  `dav://`, once the owner decides.
- [x] **H.3.T1** (JVM) Recorded `PROPFIND` responses from Nextcloud, Synology,
  and Apache `mod_dav`: folders versus files, `href`s, sizes, dates, and
  percent-encoded names.

### H.4 — SFTP

- Modern algorithms are required: curve25519 or ECDH key exchange, Ed25519 or
  ECDSA host keys, and AES-GCM (or ChaCha20-Poly1305).
- **Trust on first use:** show the host key's SHA-256 fingerprint, formatted
  like `ssh-keygen -l` (`SHA256:<base64 without padding>`), and its key type.
  Pin it per host and port, and refuse a changed key until the user approves it
  again.
- Password login first; key login later. Reads are pipelined 32 KiB requests.
- [ ] **H.4.1** Check that sshj 0.41.x (D16) connects to a current OpenSSH (9.x)
  with default settings, on Android. (sshj needs a full BouncyCastle provider
  registered in place of Android's stripped-down one.) Record the negotiated
  algorithms in Findings. Fall back to MINA SSHD if sshj fails.
- [ ] **H.4.2** The connector, the byte source (buffered, D.1), and the host-key
  pin store (device-local, not secret, but excluded from backup along with the
  logins).
- [ ] **H.4.T1** (JVM) Fingerprints match `ssh-keygen -l` for Ed25519, ECDSA,
  and RSA test keys; the pin-and-compare logic; and listing and ranged reads
  against an in-process SFTP server (MINA SSHD as a `testImplementation`).

### H.5 — S3-compatible storage

- SigV4 signing; `ListObjectsV2` with `delimiter=/` and continuation tokens;
  streaming through pre-signed GETs that are re-signed after a 403. The form
  takes the endpoint, region, bucket, access key ID, and secret key (stored
  encrypted and excluded from backup).
- [x] **H.5.1** A pure SigV4 signer.
- [x] **H.5.2** The connector and byte source.
- [x] **H.5.T1** (JVM) SigV4 against AWS's published signature test vectors;
  listing pagination fixtures from AWS, MinIO, and Backblaze B2.

### H.6 — OAuth and accounts (Apple §J.6)

**Rules:**
- The authorization-code flow with **PKCE (S256)** through Custom Tabs
  (`androidx.browser`), with **no provider SDKs and no client secrets**. A
  dedicated exported redirect activity checks `state` and hands back the code.
- The device authorization grant (RFC 8628) for OneDrive on TV (I.1): handle
  `authorization_pending`, `slow_down` (add 5 s to the interval), access
  denied, and `expired_token`.
- Refresh tokens live in an encrypted store, one entry per account
  (`cloud-account-<kind>-<accountKey>`: provider, subject, display email,
  refresh token, and granted scopes), excluded from backup. Access tokens live
  only in memory. One single-flight refresh per account serves every waiter
  (a `Mutex`).
- Scopes: Google `openid email https://www.googleapis.com/auth/drive.readonly`;
  Microsoft `Files.Read User.Read offline_access` through the `/common`
  authority; Dropbox `files.metadata.read files.content.read account_info.read`
  with `token_access_type=offline`.
- Client IDs come from `secrets.json` (`GOOGLE_OAUTH_CLIENT_ID`,
  `MICROSOFT_OAUTH_CLIENT_ID`, `DROPBOX_APP_KEY`) through `generateAppSecrets`.
  Add them, empty, to `secrets.example.json`. An empty value **hides that
  provider**. Registering the apps with each provider is the owner's job.
- Settings → Accounts (D.4) also lists cloud accounts (provider, email, and the
  sources using each) with Sign Out. A source whose account is gone shows "Sign
  in again".

**Checklist:**
- [x] **H.6.1** PKCE, the authorization URL builder, and the token and
  device-code clients, pure where possible.
- [x] **H.6.2** The account store and token provider.
- [x] **H.6.3** The redirect activity and the Custom Tabs launcher.
- [x] **H.6.T1** (JVM) Port `OAuthTests`: the RFC 7636 Appendix B vector
  (verifier `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` gives challenge
  `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`); the URL parameters for each
  provider; token and device-code responses; a single refresh for concurrent
  callers; and no token in any string that could reach a log or URL.

### H.7 — OneDrive

- Graph `children` with `@odata.nextLink` paging, plus the recursive-listing
  override. Stream from `@microsoft.graph.downloadUrl`, which needs no auth
  header, expires within minutes, and accepts `Range`.
- [ ] **H.7.1** The connector and the download-link resolver.
- [ ] **H.7.T1** (JVM) Recorded Graph responses for personal and work or school
  accounts: paging, folders, and the video facet's duration.

### H.8 — Dropbox

- `list_folder` (recursive) and `/continue`. Stream from `get_temporary_link`,
  which lasts 4 hours and then returns 410, so resolve a new link.
- [ ] **H.8.1** The connector and the link resolver.
- [ ] **H.8.T1** (JVM) Recorded listing pages, and 410 handling through H.2.

### H.9 — Google Drive (blocked by D9)

- [ ] **H.9.1** A research note in Findings: Google's current rules for OAuth on
  Android (custom schemes, App Links, `AuthorizationClient`), and what the owner
  must register. **Stop for D9.**
- [ ] **H.9.2** List with `files.list` using
  `q='<id>' in parents and trashed = false`, `supportsAllDrives=true`, and
  `includeItemsFromAllDrives=true`. Roots:
  My Drive, Shared with me, and Shared drives. Follow shortcuts; skip
  `application/vnd.google-apps.*` files; filter videos by file extension, not
  MIME type. Stream with `alt=media`, a Bearer token, and `Range`. Never send
  `acknowledgeAbuse`.
- [ ] **H.9.T1** (JVM) Recorded responses: paging, shortcuts, shared drives,
  and filtering.

### H.10 — NFS (optional, D17)

- The export needs the `insecure` option, because Android apps can't bind
  privileged ports. The connection error must say so.
- [ ] **H.10.1** Evaluate a client library (licence and Android
  compatibility) and record the result in Findings before building anything.

### H.11 — Link Source flow and Accounts

- Link Source: pick a provider (only those whose client IDs or credentials are
  configured), then fill in the server form or sign in to the cloud account,
  then pick a folder in a browser that uses `list(directory)`, then save and
  scan.
- Saved logins and accounts are reused, and removing a source keeps them (D.4).
- Source rows show the `displayPath`, the status (D.3), and the account.
- The README lists every provider and what it receives: the user's sign-in,
  folder listings, and file byte ranges.
- [ ] **H.11.1** The flow and the folder picker, usable with a TV remote.
- [ ] **H.11.2** The README provider and privacy section.

### H.12 — Experiment: cloud apps in the system folder picker

- [ ] **H.12.1** With the Google Drive, OneDrive, Dropbox, and Nextcloud apps
  installed, check which ones appear in Add Folder
  (`ACTION_OPEN_DOCUMENT_TREE`), whether scanning works, and whether playback
  streams or copies the whole file first. Record the results in Findings. No
  code.

---

## Section I — Android TV

### I.1 — OneDrive sign-in on the TV

- The device-code flow (H.6), showing a QR code for `verification_uri` plus the
  code (Microsoft doesn't support `verification_uri_complete`). Reuse the QR
  rendering in `TmdbApprovalQrCode.kt`.
- [ ] **I.1.1** The TV sign-in screen.
- [ ] **I.1.D1** On a TV: sign in from a phone, link a folder, and play a file.

### I.2 — Phone-to-TV handoff (DIFF §3.14; Apple §J.7)

Google's device flow can't grant `drive.readonly`, and Dropbox has no device
flow at all. So a phone running Edendale signs in and hands the account to the
TV, device to device only: a web relay would be an Edendale server, which
AGENTS.md forbids.

**Rules:**
- Discovery uses Network Service Discovery with the service type
  `_edendale-handoff._tcp`. The TV shows a short code.
- Authentication: a PAKE keyed by the code (for example BouncyCastle's J-PAKE),
  or an ECDH exchange confirmed by comparing codes on both screens, then
  AES-GCM for the payload.
- Messages are JSON behind a 4-byte big-endian length, at most 64 KiB, and
  versioned; unknown versions are rejected. The payload is a provider, account
  key, email, refresh token, and scopes, or a server login (SMB, SFTP, WebDAV,
  S3).
- The phone asks for confirmation ("Link Google Drive on <TV name>?") and
  offers an account already linked there, or a fresh sign-in.
- The TV validates the payload (refreshes the token, or tests the login) before
  storing it locally. It reuses the phone's refresh token, because Google
  allows only 100 refresh tokens per account per client.
- [ ] **I.2.1** A design note in Findings: protocol, crypto library, and threat
  model. **Stop for owner review before implementing.**
- [ ] **I.2.2** The implementation on both sides.
- [ ] **I.2.T1** (JVM) Port `AccountHandoffTests`: encoding, the size cap, and
  version rejection; plus a key-agreement round trip, and failure with a wrong
  code.
- [ ] **I.2.D1** Link Drive, and separately an SMB login, from a phone to a TV.

### I.3 — Watch Next row (D15)

- [ ] **I.3.1** A research note: do the target launchers (Google TV and the
  Android TV home screen) still read `TvContractCompat.WatchNextPrograms`, or
  only Google's Engage SDK? **Stop if it's Engage only.**
- [ ] **I.3.2** An opt-in setting in Settings → Android TV, off by default, with
  a note that the launcher can see these titles.
- [ ] **I.3.3** Publish in-progress titles (type CONTINUE) and next-up episodes
  (type NEXT), with `edendale://` deep links to the existing play routes.
  Update them as progress changes; remove them on completion, deletion, or when
  the setting is turned off.

---

## Section J — Large screens and keyboard (DIFF §3.15)

- [x] **J.1** Extended navigation (`WideShell` with `extendedNavigation`): the
  order is Movies & Shows, Watchlist, Downloaded, Search, then Settings.
  Watchlist expands into Movies and TV Shows; Downloaded expands into Continue
  Watching, Movies, and TV Shows. A child row appears only while its section has
  titles for the current audience setting; if the open section empties, the
  navigation returns to the parent page. The search query survives navigation.
- [x] **J.2** A Continue Watching page lists every resumable title (the shelf
  keeps its 12-item cap). The Movies page includes movies that are also in
  Continue Watching.
- [x] **J.3** Hardware-keyboard shortcuts:
  - Ctrl+B toggles the navigation.
  - On Downloaded pages, Ctrl+N adds a media folder and Ctrl+Alt+N links a
    network source.
  - Ctrl+R or F5 rescans, when a source exists.
  - In the player: Space plays and pauses; ← and → skip by the App Controls
    lengths; S activates the skip prompt; Esc closes a panel, then leaves the
    player.

  Register them with `onProvideKeyboardShortcuts` in both activities, so
  Meta+/ lists them.
- [x] **J.4** The Link Source form: the address field has focus when it opens;
  Tab and Shift+Tab move between fields; Enter connects when every required
  field is filled, and otherwise focuses the first empty one; a guest
  connection (no user or password) takes one tap.
- [x] **J.5** Docked player panels when the window is at least 1100 dp wide: the
  playlist and Player Adjustments dock as a trailing sidebar that narrows the
  video instead of covering it. Tapping the video leaves the panel open. Esc or
  Back closes the panel, and a second press leaves the player. The controls
  still auto-hide, and Up Next and skip prompts stay available.
- [x] **J.6** Season shelves: each season heading's rule acts as that shelf's
  scroll indicator and scrubber; drag the gold thumb or tap the rule
  (`SeasonBrowser.kt`).
- [x] **J.T1** (JVM) Port `LibrarySectionsTests`: child-row visibility and the
  return to the parent page.

---

## Section K — Settings layout (DIFF §3.19)

The final order on handhelds and TV:

1. About: version and sync note. On TV and in wide windows, attribution moves
   here too, so focus-scrolling can reach it.
2. Audience
3. Android TV (TV only)
4. Audio Enhancement (E.1)
5. Subtitles (B.4, plus the existing Wyzie key)
6. Skip Prompts (C.5)
7. App Controls (B.1)
8. Sources
9. Accounts (D.4, H.6)
10. TMDB Account
11. Backup
12. Privacy
13. Attribution (handhelds; on TV and in wide windows it's part of About)

- [ ] **K.1** Each phase inserts its section in this order; check the final
  order once every phase is done.

---

## Section L — Documentation, release, and other branches

- [ ] **L.1** This branch's `README.md` covers: the new settings; the network
  services and what each receives (TheIntroDB, storage providers); new
  dependencies and their licences (Media3 1.9.0, media3-session, media3-effect,
  mediarouter, OkHttp, androidx.browser, sshj, FSR 1, Snapdragon GSR if used,
  Nunito, and FFmpeg per D5); the new `secrets.json` keys;
  `connectedDebugAndroidTest`; and the manual device checks.
- [x] **L.2** This branch's `DESIGN.md` has the playlist tokens (B.5.1).
- [ ] **L.3** Release (D19, only when the owner asks): bump `versionCode` and
  `versionName`, and draft `Play Console/27.0/release.txt` in the 26.0 format.
- [x] **L.4** Leave notes for the owner (this isn't done on this branch):
  `main`'s `DESIGN.md` needs the playlist tokens; `main`'s README should list
  the supported storage services; the `web` branch needs privacy-policy text
  for the providers (Google verification requires it); and D2, D3, D4, and D18
  are Android-specific choices the Apple and Windows branches should know
  about.

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
   Drive, and NFS. Android 27.0 has SMB only until Section H lands, and Google
   Drive there waits on D9.
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
- **REDRAW needs Media3's replayable frame cache (F.1.2, 2026-10-04).**
  `setVideoEffects(VideoFrameProcessor.REDRAW)` fails playback with
  "Replaying when enableReplayableCache is set to false" unless the video
  graph was built with `PlaybackVideoGraphWrapper.Builder.setEnableReplayableCache(true)`,
  which ExoPlayer's default video renderer doesn't do. `EdendaleRenderersFactory`
  now builds `ReplayableVideoRenderer`, a `MediaCodecVideoRenderer` overriding
  `createPlaybackVideoGraphWrapper` to turn it on. The cache holds one frame and
  exists only on the effects path. Verified on the API 35 emulator.
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
| J.3 | Space, ← and → act only while no panel is open; with a panel open they go to its sliders and switches. Esc on TV follows Back (panel, then controls, then the player). Ctrl+B also hides the compact rail (600–1100 dp). Meta+/ labels it "Hide Sidebar"/"Show Sidebar", Apple's strings. | A focused slider needs the arrows; Apple's player reads keys the focused control left unhandled. | 983546a |
| J.4 | The address field takes focus when the form opens on phones and tablets, not on TV. | On TV, a focused text field opens the full-screen keyboard over the dialog before the viewer chose a field. | 983546a |
| J.6 | The scrubber is on the Episodes heading of `SeasonBrowser` (the selected season's shelf), on phones and tablets as well as wide windows; on TV the rule is a read-only indicator. | Android's local show page lists seasons vertically, so the TMDB season browser has the only horizontal season shelf (as in Apple's `TMDBSeasonBrowser`). A finger can drag the thumb too; a remote scrolls the shelf itself. | 983546a |

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
| B.2 | Persisted state and per-title memory | Complete | 15db4ef, 9b57892 |
| B.3 | Track pickers and panel order | Complete | 9820139 |
| B.4 | Subtitle appearance and placement | Complete | 7b1c028 |
| B.5 | Playlist panel redesign | Complete | 0987890 |
| B.6 | Speed and seek checks | Code checked; device checks not run | — |
| C.1 | Episode progression rules | Complete | 1f01315 |
| C.2 | Auto-advance and Up Next | Complete | fd6c2e9, 7cbb292 |
| C.3 | Continue Watching next-up | Complete | a2a3d46 |
| C.4 | Remove timed auto-skip | Complete | 4a13b2f |
| C.5 | TheIntroDB skip prompts | Complete | 4c2be70, 451d7f5, 83699b9 |
| C.6 | MediaSession and system surfaces | Complete (C.6.D1, C.6.D2 not run) | 4f8ff82 |
| D.1 | Buffered SMB reads | Complete (D.1.D1 not run) | bdcc646 |
| D.2 | Source records (Room v3) | Complete | 752fdbf, e5a1539 |
| D.3 | Rescan throttle and per-source status | Complete | 0acda8b |
| D.4 | Keep logins; Accounts | Complete | 6510b37 |
| D.5 | Play From | Complete | 94970b0 |
| E.1 | EQ profiles and booster | Complete (E.1.D1 not run) | 20c1f63 |
| E.2 | DTS and TrueHD (D5) | Complete | c58c5fb |
| F.1 | Effects plumbing | Complete (F.1.5, F.1.6 need devices) | bba68d8 |
| F.2 | Picture adjustments | Complete | bba68d8 |
| F.3 | Upscaler | Complete (F.3.4 needs a mid-range phone) | bba68d8 |
| F.4 | Sharpening | Complete | bba68d8 |
| F.5 | Temporal denoise | Complete | bba68d8 |
| F.6 | Budget and capability | Complete | bba68d8 |
| F.7 | Enhancement UI | Complete (F.7.D1 not run) | bba68d8 |
| F.8 | Enhancement acceptance | Not run (needs devices) | |
| G.1 | Frame generation feasibility | Not started | |
| G.2 | Motion estimation | Blocked (G.1) | |
| G.3 | Warping and blending | Blocked (G.1) | |
| G.4 | Scheduling and presentation | Blocked (G.1) | |
| G.5 | Gating and UI | Blocked (G.1) | |
| G.6 | Tests and acceptance | Blocked (G.1) | |
| H.1 | Connector contract | Complete | 0b43a91 |
| H.2 | Remote byte source | Complete | 91cbc0e |
| H.3 | WebDAV | H.3.1, H.3.2, H.3.T1 complete; H.3.3 HTTPS end to end not run (needs a real server); H.3.4 blocked (D10) | c9b6f6a |
| H.4 | SFTP | Not started | |
| H.5 | S3 | Complete (device check not run) | 90072dc |
| H.6 | OAuth and accounts | Complete (sign-in not run: needs registered client IDs) | ed065ea |
| H.7 | OneDrive | Not started | |
| H.8 | Dropbox | Not started | |
| H.9 | Google Drive (D9) | Blocked | |
| H.10 | NFS (optional) | Not started | |
| H.11 | Link Source flow | Not started | |
| H.12 | Folder-picker experiment | Not started | |
| I.1 | OneDrive on TV | Not started | |
| I.2 | Phone-to-TV handoff | Not started | |
| I.3 | Watch Next row | Not started | |
| J.1 | Navigation child rows | Complete (device check not run) | 983546a |
| J.2 | Continue Watching and Movies pages | Complete (device check not run) | 983546a |
| J.3 | Keyboard shortcuts | Complete (device check not run) | 983546a |
| J.4 | Link Source keyboard behavior | Complete (device check not run) | 983546a |
| J.5 | Docked player panels | Complete (device check not run) | aa8b2ba |
| J.6 | Season shelf scrubber | Complete (device check not run) | 983546a |
| K.1 | Settings order | Not started | |
| L.1 | README | In progress (A–F, J, H.2 covered; H and I providers pending) | d60d466 |
| L.2 | DESIGN.md | Complete | 0987890 |
| L.3 | Release | Blocked (D19) | |
| L.4 | Notes for other branches | Complete (notes in Section L) | |
