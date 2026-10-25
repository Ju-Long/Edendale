# HORIZON.md — Edendale on the Meta Horizon Store

Implementation plan and checklist for shipping the Android app to Meta Quest
headsets through the Meta Horizon Store, as a 2D ("panel") app. It is written
for an implementing agent, and for the owner to review the finished work
against.

| | |
|---|---|
| Baseline | `android` at `88d699d` (2026-10-10), where `meta-horizon` starts; `android-27.0.1` (`9df06dd`, Google Drive on `drive.file`) merged in on 2026-10-19 |
| Store | Meta Horizon Store, 2D Android app on Horizon OS |
| Work branch | `meta-horizon`, the Meta Quest platform branch downstream of `android` (decision D0, revised 2026-10-19) |
| Requirements read | Meta developer documentation as of 2026-10-10 (Sources, at the end) |
| Written | 2026-10-10 |

**Why this is mostly packaging.** Horizon OS runs ordinary Android apps in a
floating panel. Touch maps to controller rays, hand pinches, and (on Meta's VR
glasses) Look and Pinch. The usual blocker is Google Mobile Services, which
Horizon OS lacks. Edendale doesn't use GMS: there's no Play services, Firebase,
Play Billing, or Google Sign-In SDK in `build.gradle.kts`. The work is:

1. **Packaging.** Meta wants a v2-signed, 64-bit APK with minSdk 29 or higher
   (Edendale's is 26), `installLocation="auto"`, and a supported-devices tag.
   The TV-only parts of the manifest have to go.
2. **A second signing key,** plus the things tied to the key: the Google OAuth
   client, the Entra redirect, and `assetlinks.json`.
3. **Runtime behavior that only a headset answers:** the folder picker, the
   Custom Tab sign-in, PiP, HDR on an SDR panel, the enhancement GPU budget,
   and controller and hand input.
4. **Store paperwork:** listing art, the privacy policy, the Data Use
   Checkup, the age rating, and reviewer notes.
5. **Delivery:** a release path with its own protected credentials.

---

## How to work through this document

### Before you start

- [x] **S.1** `git fetch origin`. Confirm you're on `meta-horizon` and that it
  contains `origin/android` (`git merge-base --is-ancestor origin/android HEAD`).
- [x] **S.2** Read this branch's `README.md`, `AGENTS.md`, `CLAUDE.md`, and
  `DESIGN.md`, and skim `ENHANCEMENT.md` (Android 27.0) for the conventions
  this plan reuses.
- [x] **S.3** Read Section 0 and stop if any decision your step depends on is
  still **Open**.
- [x] **S.4** Toolchain: JDK 17
  (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
  on the owner's Mac) and the Android SDK through `local.properties`.
  `secrets.json` is optional.
- [x] **S.5** Run `./gradlew testDebugUnitTest assembleDebug` before changing
  anything and record the result in the Handoff log.

### Commands

The one flavor is `horizon` (A.1); this branch has no `play` flavor, since
Play ships from `android`.

| Purpose | Command |
|---|---|
| JVM tests | `./gradlew testHorizonDebugUnitTest` |
| Debug APK | `./gradlew assembleHorizonDebug` → `build/outputs/apk/horizon/debug/Edendale-horizon-debug.apk` |
| Release APK | `./gradlew assembleHorizonRelease` (signed when the A.3 properties exist; `…-unsigned.apk` otherwise) |
| Store-rule check | `./gradlew verifyHorizonRelease` (E.1) |
| Bring in android's work | `git merge android` (and resolve, rebuild, retest) |
| Simulator | `metavr ssim start`, then `adb -s emulator-5554 install -r <apk>` and `adb -s emulator-5554 shell am start -n com.babasama.edendale/com.babasama.edendale.android.MainActivity` (`metavr` is in Meta Quest Developer Hub's `Contents/Resources/bin`) |
| Upload to a test channel (by hand) | `metavr store dist upload --app-id <id> --channel ALPHA --apk <apk> --notes "<notes>"` (a draft unless `--publish`; uses `metavr auth login`) |
| Upload from CI (E.3) | `ovr-platform-util upload-quest-build --age-group TEENS_AND_ADULTS --app-id <id> --app-secret <secret> --apk <apk> --channel ALPHA` |

### Hard rules

These restate AGENTS.md and CLAUDE.md for this work. Breaking one fails review.

1. **The `android` branch doesn't change from here.** This branch takes
   android's work by merge and sends nothing back. A fix that isn't specific
   to a headset (the trailer `Referer`, for one) goes to `android` as its own
   change, with the owner's go-ahead, and returns by merge. Keep `src/main` as
   android has it wherever a hook into `src/horizon` will do, so the merges
   stay small; `git diff android -- src/main` lists the hooks.
2. **No Meta Platform SDK, analytics, or account.** Horizon-specific behavior
   comes from Android APIs and a build-time flag. Any Meta library (D.5) needs
   an owner decision first.
3. **Secrets stay out of git.** Keystores, Meta's app secret, and
   `secrets.json` live in gitignored files (`.secret/`, `*.jks`,
   `keystore.properties`) or in a protected CI environment. Certificate
   fingerprints and the Meta App ID aren't secret and may be written down.
4. **Privacy rules still hold:** classify before network, trailers only on an
   explicit action, and network access only to documented services.
5. **Strings:** every new user-facing string goes into all 19 `values*`
   locales.
6. **Cross-branch work** (privacy policy and `assetlinks.json` on `web`;
   branch tables on `main`) happens only with the owner's go-ahead, as its own
   change on that branch.

### Workflow for each step

Implement, add or adjust tests, run the commands above, tick the box, and add
a Handoff row. Commit messages start with the step ID
(`Horizon A.2: split TV-only manifest entries into the play flavor`). Anything
that departs from this plan goes in the Deviations log, and anything learned
on a device goes in Findings.

### Stop and ask the owner when

- a step depends on an **Open** decision;
- an upload or store review rejects something this plan didn't expect;
- a fix would change Play-build behavior;
- an install, download, account, or console action is needed (Meta dashboard,
  Google Cloud, Entra, or the headset).

---

## 0. Decisions

| ID | Question | Proposal | Status |
|---|---|---|---|
| D0 | Where does the Horizon build live? | **`meta-horizon` is a platform branch downstream of `android`**, like the repository's other platform branches: `android` merges into it (`git merge android`) and nothing merges back, so a fix that isn't headset-specific goes to `android` first. The branch carries one `horizon` flavor whose manifest overlay, code, and tests live in `src/horizon` and `src/testHorizon`, so the merges touch `src/main` only where `DevicePolicy` hooks in. Meta's credentials stay in their own workflow and protected environment. The branch tables on `main` need a row (Hard rule 6). | **Decided 2026-10-19** (owner): separate branch, kept current from `android`; replaces the 2026-10-10 choice of a flavor on `android` |
| D1 | Application ID | Keep `com.babasama.edendale`. The Google and Microsoft redirects and the App Links are built on it, and the Meta store only requires uniqueness within its own catalogue. | Open |
| D2 | SDK levels for `horizon` | minSdk **32**, Meta's recommendation for Quest-only apps (allowed range 29–34). targetSdk: keep **36** if the P.6 upload probe accepts it. Meta's table allows 32–36 for 2D apps, but its 2026-03-01 rule says apps created after that date "must set targetSdkVersion to 34". Otherwise use 34 for `horizon` only. | Open |
| D3 | Signing key | **Reuse the Play upload key** `~/Documents/Edendale/New.jks`, alias `key0`. Its certificate (`CN=Long Ju`, expires 2051-10-03) has SHA-1 `E9:BF:A7:55:DE:A6:92:0B:9F:24:C6:B5:15:51:DB:B2:C1:46:1F:32`, SHA-256 `F2:CE:0D:97:57:66:4C:1C:BB:C0:4E:2B:0F:B8:E6:9A:4D:79:4A:40:7A:C5:16:73:D0:F8:68:23:87:7E:FF:65`, and Base64 SHA-1 `6b+nVd6mkgufJMa1FVHbssFGHzI=`. Meta distributes the APK as signed, so every Quest update must be signed with this key, even if Play's upload key is ever reset. Keep `New.jks` and its password backed up. | **Decided 2026-10-10** (owner): reuse `New.jks` |
| D4 | Supported devices (`com.oculus.supportedDevices`) | `quest2\|questpro\|quest3\|quest3s`, plus `vrglasses` if Look and Pinch passes in the Spatial Simulator (B.5). Quest 2 and Pro have no AV1 hardware decoder, and the app has no software AV1 decoder. | Open |
| D5 | Entitlement check | **Skip.** It's only recommended for 2D apps (VRC.Quest.Security.1), the app is free and GPL-3.0, and it would need the Meta Platform SDK and a network call to Meta. | Open |
| D6 | Video Enhancement default on Horizon | **Off** (as on TV) until B.8 measures it. The compositor already resamples the panel, and the GPU is shared with rendering the headset's view. | Open |
| D7 | Local files if the system folder picker is missing on Quest | Use MediaStore video queries with `READ_MEDIA_VIDEO`, which Meta allows after review, with the justification "media player for the user's own videos". Only if B.2 fails. | Open |
| D8 | Test hardware | The owner's **Quest 3** for every hardware check; the Spatial Simulator for Look and Pinch and quick UI runs. Quest 2, Pro, and 3S stay untested unless someone lends one; record that in Findings if D4 lists them. | **Decided 2026-10-10** (owner): Quest 3 |
| D9 | Price and regions | Free, no in-app purchases, every region the store offers. | Open |
| D10 | Release automation | Upload by hand (dashboard or a local `ovr-platform-util`) until the first approval, then add E.3's workflow. | Open |
| D11 | Scope of Section D (Quest-only improvements) for the first release | None of Section D for the first release. D.1 (handoff to the headset) moves up if typing logins on the headset proves painful in B.11. | Open |

---

## 1. What Meta requires, and where Edendale stands

| Requirement (VRC) | Meta's rule | Edendale today | Step |
|---|---|---|---|
| Package (Packaging.2, .5, .6) | APK with v2 signature, 64-bit, APK ≤ 1 GB | Play gets an AAB; the release APK is unsigned; FFmpeg ships all four ABIs | A.1, A.3, E.1 |
| SDK levels (Packaging.4) | minSdk 29–34 (32 recommended); 2D targetSdk 32–36; apps created after 2026-03-01: 34 | minSdk 26, targetSdk 36, compileSdk 36 | D2, P.6 |
| Manifest (Packaging.1) | `installLocation="auto"`, `com.oculus.supportedDevices`, launcher `MAIN`/`LAUNCHER` exported, not debuggable | none of the Meta entries; `LEANBACK_LAUNCHER` filter and banner too | A.2 |
| Unsupported features (Packaging.3) | nothing the headset lacks may be required | `leanback` and `touchscreen` are `required="false"` | A.2 |
| Permissions (Security.2) | 117 prohibited; 20 need review | `INTERNET`, `WRITE_EPG_DATA` (TV Watch Next) | A.2 |
| Google Mobile Services | unavailable | not used | none |
| Panel size | `<layout android:defaultWidth/defaultHeight>`; Meta's example is 1024×640 dp | 1280×800 dp (library), 1280×720 dp (player) | B.5 |
| Input (Input.7, .8) | controllers and hands both work, the system gesture stays reserved; B/Y is Back; Look and Pinch reads the accessibility tree; targets ≥ 48 dp | touch, keyboard, TalkBack semantics (27.0 B.1) | B.5 |
| Media (guidance for streaming apps) | H.264, HEVC, VP9, AV1 on Quest 3 and later; DD, DD+, Atmos; no AC-4; never change system volume | Media3 plus FFmpeg audio; no `AudioManager` volume calls | B.7–B.9 |
| Functional.1, .3, .4, .12 | no crashes or stuck states, no data loss, works for every user account on the headset | — | Appendix 1 |
| Privacy.1–.5 | a policy URL covering what's collected, how it's used, and how to delete it | `/privacy/` on `web` names "Android" but not Quest | F.3 |
| Assets (Asset.1–.7) | transparent logo, clean cover art, 5 screenshots, no other VR platforms' hardware | none yet | F.2 |

**Unclear in Meta's docs.** Their release-manifest page also says the launcher
activity's `excludeFromRecents` "must be `true`", without saying whether that
covers 2D apps. For a panel app it would hide Edendale from Recents. Don't add
it unless the P.6 probe flags it.

---

## 2. Build order

| Phase | Sections | Needs |
|---|---|---|
| 0. Accounts and spike | P | Owner: Meta account, app, key, tools |
| 1. Build variant | A | D0, D1, D2, D3 |
| 2. Horizon OS behavior | B | A; a headset (D8) for B.2–B.11 |
| 3. Sign-in and links | C | A.3's key; owner console work |
| 4. Delivery | E | A; D10 |
| 5. Store listing and review | F | B, C, and E passed; owner dashboard work |
| Later | D | D11 |

---

## Section P — Accounts and a spike

The spike answers the questions that could change this plan, before the
restructuring in A starts.

- [ ] **P.1** *(Owner)* Create a Meta developer account and organization in the
  Meta Horizon Developer Dashboard, turn on two-factor sign-in, and finish any
  verification it asks for.
- [ ] **P.2** *(Owner)* Create the app as a Horizon Store 2D Android app named
  "Edendale". Record the App ID and creation date here; the creation date
  decides D2's targetSdk rule. Keep the App Secret in `.secret/` (gitignored).
- [x] **P.3** Signing key per D3: `New.jks` already exists, and its
  fingerprints are recorded in D3 (read from
  `~/Desktop/upload_certificate.pem` on 2026-10-10).
- [ ] **P.4** *(Owner approves the downloads)* The Meta tools.
  - Meta Quest Developer Hub 6.5.2 is installed and bundles the `metavr` CLI
    (1.6.0) at `/Applications/Meta Quest Developer Hub.app/Contents/Resources/bin/metavr`.
    It isn't on `PATH`.
  - Sign in with `metavr auth login`.
  - Install the Spatial Simulator with `metavr tools install spatialsim`
    (770 MiB, macOS arm64). Its prerequisites (Command Line Tools, the SDK's
    `emulator` and `platform-tools`) are already on the Mac.
  - Uploads go through `metavr store dist upload`, so `ovr-platform-util`
    (`metavr tools install platform-utils`) is only needed for CI (E.3).
  - The Android Studio route works too: Studio 2026.1 is newer than the
    required Otter 2025.2. Install the Meta Horizon plugin, then use Meta
    Horizon → Virtual Devices and Tools.
  - Turn on developer mode on the Quest 3: the developer account must belong
    to a verified organization (P.1); then, in the Meta Horizon phone app,
    Devices → the headset → Headset Settings → Developer Mode; then connect a
    USB-C data cable and allow USB debugging in the headset.
- [ ] **P.5** Spike build in a throwaway `git worktree` in the scratchpad,
  never the shared checkout. Start from `android` with minSdk 32, the A.2
  Horizon manifest entries, the EPG permission and leanback entries removed,
  arm64-v8a only, and the P.3 key. Install it on the simulator, and on a
  headset if one is available. Then try each of these and record the results
  in Findings:
  - launching the app; the panel size and the wide navigation;
  - Add Folder (the system folder picker);
  - opening a video from the headset's file manager ("Open with");
  - playing an MP4 and an MKV with subtitles: double-tap skip, hold for speed,
    scrubbing;
  - PiP (is `FEATURE_PICTURE_IN_PICTURE` present?);
  - Settings → Accounts → Google Drive sign-in (which browser opens, and
    whether the redirect comes back to the app);
  - a trailer;
  - Back on the controller's B/Y button.

  *Status 2026-10-10:* simulator done (Findings). The worktree is
  `<scratchpad>/horizon-spike` (debug signing), and the test media (`EdendaleTest`,
  including two 60 s *Severance* clips made by `tools/make_clip.swift`) is in
  `<scratchpad>/media`. Still to do: the same run on the Quest 3, where
  someone wears the headset for controllers, hands, and B/Y.

  *Paused 2026-10-10 at the owner's request.* The Quest 3 is authorized for USB
  debugging. The spike debug APK, including the trailer fix, is installed on
  it, and the test videos are in `/sdcard/Movies/EdendaleTest`. The probe
  results are in Findings. Not yet run on the headset: linking the folder,
  playback, the trailer, controllers, hands, and B/Y. When P.5 is done,
  remove the test videos and uninstall the spike from the headset.

  *2026-10-19:* the real `horizon` flavor build (`4ad9712`, debug) replaced
  the spike on the simulator and passed the same run (Findings): launch,
  folder link and scan, playback, no PiP, Back, saved progress. The headset
  run is scheduled for 2026-10-20; see "Headset run" below.
- [ ] **P.6** Upload the spike APK to the **ALPHA** channel (private), then
  read Manage Builds → Test Status. Record what the validator says about
  targetSdk 36, minSdk, permissions, ABIs, and `excludeFromRecents`. This
  settles D2. Leave the build in ALPHA; nobody outside the channel sees it.
- [ ] **P.7** If P.5 or P.6 turns up a blocker (no folder picker, sign-in
  can't return to the app, upload refused), update this plan and Section 0
  before Section A starts.

### Headset run, 2026-10-20

The owner wears the Quest 3 (serial `2G97C5ZJ2B023W`, already authorized for
USB debugging); `adb` is `~/Library/Android/sdk/platform-tools/adb`.

1. Fresh build: `./gradlew assembleHorizonDebug` (JDK 17). Then
   `adb uninstall com.babasama.edendale` (the 2026-10-10 spike is still
   installed) and `adb install -r build/outputs/apk/horizon/debug/Edendale-horizon-debug.apk`.
   The 2026-10-10 test videos are still in `/sdcard/Movies/EdendaleTest`.
2. **P.5 on the headset:** launch; panel size and resizing; Add Local Folder
   → Movies → EdendaleTest; the MP4 and the MKV with subtitles; double-tap
   skip, press-and-hold speed, scrubbing; B/Y as Back out of a panel, then
   the player; controllers, hands, and switching between them; the trailer
   (picture and sound); "Open with Edendale" from the Files app; Settings →
   Accounts → Google Drive (the picker flow from `android-27.0.1`).
3. **B.2** access survives a restart; **B.4** headset off and on
   mid-playback (does it resume?); **B.5** hit targets and labels; **B.6**
   the brightness control; **B.9** stereo and E-AC-3; **B.11** one network
   source over Wi-Fi (the Mac Studio's SMB share, or WebDAV).
4. **B.7** with real media over SMB: HEVC 10-bit, AV1, HDR10, and Dolby
   Vision profile 8 (`/Volumes/Extra` on the Mac has Atmos DD+ titles).
5. Owner, after the run: copy `horizon-signing.example.properties` to
   `.secret/horizon-signing.properties` with `New.jks`'s passwords, run
   `./gradlew assembleHorizonRelease verifyHorizonRelease` (now with the
   signature checks), create the app in the dashboard (P.2), and upload to
   ALPHA (P.6) to settle D2.
6. Record everything in Findings, tick the B steps that passed, and clean up
   the test videos and the debug build from the headset at the end.

*Status 2026-10-25:* the run happened on 10-25 instead (the owner wore the
headset; Claude drove `adb` from the Mac). Done: step 1; from step 2,
everything except "Open with"; from step 3, B.2's restart, B.4, B.6, and the
E-AC-3 half of B.9. B.4, B.6, and B.10 changed the build (Deviations,
2026-10-25). Still to do: "Open with Edendale", the thumbstick and hand-pinch
findings (B.5), B.7 beyond H.264 and 8-bit HEVC, B.8, the rest of B.9, B.11,
steps 4–6, and the cleanup. The headset keeps the 10-25 debug build, the
linked `EdendaleTest` folder, and the owner's Google Drive link.

---

## Section A — The `horizon` build variant

- [x] **A.1** Add a `store` flavor dimension with `play` (the default; its
  output stays the same) and `horizon`. For `horizon`: minSdk and targetSdk
  per D2, `ndk { abiFilters += "arm64-v8a" }`, and a
  `BuildConfig.HORIZON_OS` boolean (false for `play`). The application ID
  stays shared (D1). Before and after this step, save `aapt2 dump badging` and
  the merged manifest of the Play debug and release builds. Put the diff in
  the Handoff log: it may show only the A.2 moves.
- [x] **A.2** Split the manifest:
  - `src/main/AndroidManifest.xml` keeps what both stores share.
  - `src/play/AndroidManifest.xml` takes the TV-only entries:
    `WRITE_EPG_DATA`, `<uses-feature android.software.leanback>`, the
    `LEANBACK_LAUNCHER` intent filter, and `android:banner`.
  - `src/horizon/AndroidManifest.xml` adds `android:installLocation="auto"`,
    `<uses-feature android:name="android.hardware.vr.headtracking" android:required="false" android:version="1" />`,
    and `<meta-data android:name="com.oculus.supportedDevices" android:value="…" />`
    on the application (D4). It also adds
    `android:supportsPictureInPicture="false"` on `PlayerActivity` if P.5 or
    B.3 shows PiP misbehaving.
  - Leave the `<layout>` sizes as they are until B.5 says otherwise.
- [x] **A.3** Release signing for `horizon` only, read from a gitignored
  properties file (`.secret/horizon-signing.properties`: store path
  `~/Documents/Edendale/New.jks`, store password, alias `key0`, key password)
  or from environment variables in CI. Play keeps its current manual signing. With
  neither present, the build stays unsigned, as it is today. Keep v2 signing
  on (AGP's default) and `debuggable` false.
- [x] **A.4** Versioning: one `versionCode`/`versionName` in `defaultConfig`
  for both stores. Every upload to either store needs a higher `versionCode`
  than that store's last upload. Write this rule into the README's release
  section.
- [x] **A.5** Rename the tasks everywhere: `ci.yml` and `instrumented.yml`
  (`testPlayDebugUnitTest`, `connectedPlayDebugAndroidTest`, …), the README's
  commands, and the APK paths (`build/outputs/apk/<flavor>/<type>/Edendale-<flavor>-<type>.apk`).
- [x] **A.T1** JVM test for a pure `DevicePolicy` function from
  (`isTelevision`, `isHorizonOs`) to the shell (TV or handheld/large-screen),
  Watch Next on or off, handoff receiver on or off, PiP offered or not, and the
  enhancement default. B.1 introduces it; the test covers every combination.

---

## Section B — Horizon OS behavior

Each step is a device check first. Change code only where the check fails,
and record the result in Findings either way.

- [x] **B.1** Add `DevicePolicy` (A.T1) and route through it everything that
  currently reads `isTelevisionDevice()` and that Horizon needs to differ in:
  Watch Next publishing (`EdendaleApplication`), the PiP offer, and the
  enhancement default (`VideoEffectsController`). A Quest isn't a TV; check
  that its `uiMode` type (likely `UI_MODE_TYPE_VR_HEADSET`) never selects the
  TV shell.
- [ ] **B.2** Local folders: Downloaded → Add Folder and Settings → Sources use
  `OpenDocumentTree`. Check that the picker exists on Horizon OS and that the
  persisted URI survives a restart. If not, implement D7 behind
  `DevicePolicy`. Also check "Open with Edendale" from the headset's file
  manager (`PlayerActivity`'s `video/*` filter).

  *2026-10-25, Quest 3:* the picker and the persisted URI pass (Findings), so
  D7 isn't needed. "Open with Edendale" is still to check.
- [x] **B.3** PiP: `PlayerActivity` already gates on
  `FEATURE_PICTURE_IN_PICTURE`. Check that the Auto PiP setting and the PiP
  button disappear when the feature is absent. If the feature is present but
  PiP behaves badly in a panel, turn it off for `horizon` (A.2).
- [x] **B.4** Lifecycle: playback keeps going while another panel has focus;
  it pauses when the panel closes or the headset comes off (`onStop`). Today
  `onStart` sets `playWhenReady = true`, so putting the headset back on
  resumes playback by itself. Decide with the owner whether Horizon should stay
  paused instead, and log any change as a Deviation. Progress is saved on stop
  (VRC Functional.4).

  *2026-10-25, Quest 3:* taking the headset off paused playback, and putting
  it back on resumed it by itself. The owner keeps the auto-resume, so nothing
  changes. *Obsession* then showed in Continue Watching with its time left.
- [ ] **B.5** Input:
  - Run the player and library with Touch controllers (ray and trigger),
    hands (pinch), and Look and Pinch in the simulator: double-tap skip,
    press-and-hold speed, scrubbing, shelf scrolling with the thumbstick, and
    B/Y closing a panel and then the player.
  - Audit hit targets below 48 dp in the player chrome and the settings rows.
  - Look and Pinch finds targets through the accessibility tree, so every
    clickable needs a label.
  - Check the panel's default size: whether 1280×800 dp opens at a comfortable
    size and keeps the wide navigation (≥ 1100 dp), and whether resizing works.
    Tune the `horizon` `<layout>` only if needed.

  *2026-10-25, Quest 3:* with controllers, double-tap skip, press-and-hold
  speed, the seek bar, and B/Y (Adjustments, then the player) all work, and
  resizing the panel works. Two problems remain: a double pinch with hands is
  hard to land as a double-tap, and the thumbstick doesn't scroll the home
  rows. The hit-target audit and Look and Pinch are still to do.
- [x] **B.6** Brightness: the player's brightness control writes
  `window.attributes.screenBrightness` (`PlayerActivity`). If that has no
  effect in a panel, hide the control on Horizon. Picture → Brightness, done in
  the shader, stays.

  *2026-10-25:* neither the left swipe (window brightness) nor the right swipe
  (player volume) made a visible or audible change on the Quest 3, so the owner
  had both taken off the headset: `DevicePolicy.offersSwipeAdjustments` is
  false on Horizon, and the gesture layer ignores vertical swipes there.
  Picture → Brightness and the Audio section stay.
- [ ] **B.7** Video, on a Quest 3/3S, and on a Quest 2 if D4 keeps it: H.264,
  HEVC 10-bit, VP9, and AV1 (Quest 3 only) at 1080p and 4K; 4K60; and the
  time to first frame. HDR10 and Dolby Vision profile 8 on the SDR LCD panel:
  if they look washed out, file a follow-up step (Media3 tone mapping) rather
  than shipping it silently. Unsupported codecs should get the existing
  localized error.

  *2026-10-25, Quest 3:* H.264 (`c2.qti.avc.decoder`, including a 4K `.mov`
  from Google Drive) and 8-bit HEVC (`c2.qti.hevc.decoder`, a 1080p x265 MKV
  from Drive) play on the hardware decoders; the player opened in 0.6 s.
  Still to do: HEVC 10-bit, VP9, AV1, 4K60, HDR10, and Dolby Vision.
- [ ] **B.8** Enhancement (D6): run the one-time GPU check and the governor at
  720p and 1080p for ten minutes, watching dropped frames and thermal
  stepping. Set the Horizon default from the result.
- [ ] **B.9** Audio: stereo, 5.1/7.1 downmix, E-AC-3 and Atmos (Meta lists
  Dolby support), DTS and TrueHD through the FFmpeg decoder (arm64), the
  equalizer, and Audio Booster. Player Adjustments → Audio Output: if
  `SystemOutputSwitcherDialogController.showDialog` returns false on Horizon,
  hide the row there. Don't add system-volume control; Meta prohibits it.

  *2026-10-25, Quest 3:* 5.1 AAC (`c2.android.aac.decoder`) sounded right to
  the owner, and `decoder-eac3.mkv` played through `c2.dolby.eac3.decoder`.
  Still to do: Atmos, 7.1, DTS and TrueHD through FFmpeg, the equalizer,
  Audio Booster, and the Audio Output row.
- [ ] **B.10** Trailers: the `youtube-nocookie.com` embed plays in the
  headset's WebView and still starts only on an explicit action.

  *2026-10-25:* in a panel on the Quest 3, the embed played sound but no
  picture. At the owner's request, Watch Trailer on Horizon now opens
  `https://www.youtube.com/watch?v=<key>` with `ACTION_VIEW`
  (`DevicePolicy.playsTrailersInline` is false), still only on that tap. The
  Quest hands the link to the Meta Quest Browser, although YouTube VR is
  installed. Still to check: that the browser shows the picture.
- [ ] **B.11** Network sources over Wi-Fi: SMB, WebDAV over HTTPS with a
  self-signed certificate, SFTP, and S3. Note how long typing a login takes on
  the headset keyboard (input for D11). Also check plain `http://` on the LAN
  and its refusal for a public address.
- [ ] **B.12** Wording: list the strings Horizon users see that mention a
  phone, TV, or remote. Change only the ones that are wrong on a headset, in
  all 19 locales.

---

## Section C — Things tied to the signing key

- [ ] **C.1** Google Drive *(owner, Google Cloud console)*: check whether an
  Android OAuth client for `com.babasama.edendale` with `New.jks`'s SHA-1
  (`E9:BF:A7:55:…:1F:32`, D3) already exists; if not, register one with the
  custom URI scheme on. Put it in the same project, so the
  `drive.readonly` verification and the consent screen apply; confirm that in
  the console. If its client ID differs from Play's, `generateAppSecrets`
  reads `GOOGLE_OAUTH_CLIENT_ID_HORIZON` for the `horizon` flavor and falls
  back to the shared key. Then test on the headset: the sign-in opens a
  Custom Tab or the browser, comes back through
  `com.babasama.edendale:/oauth2redirect`, and a folder links and plays.
- [ ] **C.2** OneDrive *(owner, Entra)*: add an Android platform redirect with
  `New.jks`'s Base64 SHA-1, `6b+nVd6mkgufJMa1FVHbssFGHzI=` (D3). The manifest's
  current `msauth` path, `VzSiQcXRmi2kyjzcA+mYLEtbGVs=`, belongs to another
  certificate. Make the manifest's `msauth` path a
  per-flavor manifest placeholder, and give `CloudProviders` the matching URI
  per flavor. (Without MSAL nothing checks the hash, so reusing Play's
  redirect would also work, but registering it properly is cheap and avoids a
  hidden dependency.) If the browser flow fails on the headset, offer the TV's
  device-code sign-in on Horizon too, through `DevicePolicy`.
- [ ] **C.3** Dropbox: its redirect depends only on the scheme. Test the
  sign-in, revoke, and playback.
- [ ] **C.4** App Links *(owner approves a `web` change)*: add `New.jks`'s
  SHA-256 (`F2:CE:0D:97:…:7E:FF:65`, D3) to
  `public/.well-known/assetlinks.json` on `web`. `web`'s README says to leave
  upload-key fingerprints out, so add an exception to that README: this key
  signs the Quest release. Update `web`'s CI assertion to match, then check
  `adb shell pm get-app-links com.babasama.edendale` on the headset.
- [ ] **C.5** TMDB sign-in (the approval page) opens and returns on the
  headset.

---

## Section D — Quest-only improvements (scope by D11)

- [ ] **D.1** Handoff to the headset: let Horizon act as the receiver, using
  the TV's Link Source → Continue on a Phone, since typing passwords on a
  headset is slow. Network Service Discovery (mDNS) is supported on Horizon
  OS. Relabel the phone side's "Link to a TV" to cover headsets, in all 19
  locales.
- [ ] **D.2** Player in its own panel: open `PlayerActivity` with
  `FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK`
  so the library and the video sit side by side. This is Horizon's equivalent
  of PiP.
- [ ] **D.3** Thumbstick left and right in the player skip by the App Controls
  lengths (`onGenericMotionEvent`).
- [ ] **D.4** App-level volume and mute in the player through
  `player.volume`, which Meta's media guidance recommends.
- [ ] **D.5** Hover highlights under gaze through Meta's Gaze SDK for Compose.
  This adds a Meta dependency, so check its licence and network behavior with
  the owner first.
- [ ] **D.6** (A separate future plan.) An immersive theater and 3D, 180°, or
  360° playback with the Meta Spatial SDK. That would make Edendale a hybrid
  app with the immersive VRCs, so it's out of scope here.

---

## Section E — Delivery

- [x] **E.1** `verifyHorizonRelease` Gradle task, run on the merged manifest
  and the built APK. It fails the build when any of these is wrong:
  - minSdk and targetSdk (D2);
  - `installLocation="auto"`;
  - `com.oculus.supportedDevices` present;
  - no permission from Meta's prohibited list (copy the list into a test
    resource, with the date it was copied);
  - no `WRITE_EPG_DATA` and no `LEANBACK_LAUNCHER`;
  - not debuggable;
  - `lib/` holds only `arm64-v8a`;
  - the APK is under 1 GB;
  - when signed, `apksigner verify --verbose --print-certs` shows the v2
    scheme and the P.3 SHA-256.
- [x] **E.2** CI (`ci.yml`): build and test both flavors, and run
  `verifyHorizonRelease` on the unsigned Horizon release, without signature
  checks. Triggers: `android`, plus `meta-horizon` until it merges. No secrets.
- [ ] **E.3** (After D10.) `horizon-release.yml`:
  - Triggered only by `workflow_dispatch`, running in the protected
    environment `meta-horizon-store` with the owner as required reviewer.
  - Secrets: the keystore (Base64), its passwords and alias, `META_APP_ID`,
    `META_APP_SECRET`, and `SECRETS_JSON` (TMDB and the OAuth client IDs; no
    Wyzie key, since Edendale ships none).
  - Steps: build, verify, then upload to **ALPHA** only, with a pinned
    `ovr-platform-util` checked against a SHA-256. Promotion stays manual.
    Play credentials never enter this environment.
- [x] **E.4** README: a "Meta Quest" section covering the flavor, its
  commands, signing, the simulator, `am instrument` on a headset (never
  `connected…AndroidTest` on the owner's device, since it uninstalls the app),
  release channels, the versioning rule, and a "Manual device checks" entry
  for a headset.
- [x] **E.5** Release procedure in the README: bump the version, build, run
  `verifyHorizonRelease`, upload to ALPHA, install from the channel on the
  headset, check, then promote.

---

## Section F — Store listing and review

- [ ] **F.1** Listing text *(owner approves; drafted 2026-10-19 in `Horizon Store/27.0/source-en-GB.md`, with F.4–F.7's answers)*:
  - name "Edendale";
  - short and long descriptions (honest about needing your own videos or
    storage);
  - category;
  - website `https://edendale.babasama.com`, a support link, and the privacy
    URL `https://edendale.babasama.com/privacy/`;
  - keywords.

  Optional: localized listings in the app's languages.
- [ ] **F.2** Art: Hero 3000×900, Cover Landscape 2560×1440, Cover Square
  1440×1440, Cover Portrait 1008×1440, Mini Landscape 1080×360, Icon 512×512
  (no transparency), and a logo with a transparent background (Asset.1,
  required). Also five distinct 2560×1440 screenshots from the headset or the
  simulator, with no added text, badges, or other platforms' hardware (Asset.5
  and .6). For the art in the screenshots, use posters and frames you have
  rights to: Blender's open films (*Big Buck Bunny*, *Sintel*, *Tears of
  Steel*) are CC-BY, whereas a library full of studio posters is IP the owner
  answers for. A trailer is optional and at most 2 minutes (Asset.7).
- [ ] **F.3** Privacy policy *(owner approves a `web` change)*: name Meta
  Quest in `/privacy/` (8 languages). Say that the store (Meta) may receive
  diagnostics under the headset's own settings, as the existing store
  paragraph does, and that removing the app deletes its data. Check the
  policy against Privacy.2–.4.
- [ ] **F.4** Data Use Checkup *(owner)*: declare no Platform SDK features and
  no data collected by the developer.
- [ ] **F.5** Age rating *(owner)*: answer the IARC questionnaire. The app
  shows TMDB metadata and artwork and plays the user's own files and YouTube
  trailers; it has no user-to-user features.
- [ ] **F.6** Input metadata (Tracking.2): select only the input modes Appendix
  1 passed, likely Touch controllers and hand tracking.
- [ ] **F.7** Reviewer notes. Reviewers start with an empty library, so give
  them a way to play something:
  - a read-only WebDAV or S3 folder of CC-licensed clips, with its credentials
    in the private review notes;
  - or steps to copy an MP4 into the headset's Movies folder.

  Also explain the TMDB metadata, and that there's no account and no
  purchases.
- [ ] **F.8** Licence: the APK is GPL-3.0 (the FFmpeg decoder, android D5).
  Read Meta's developer distribution agreement and its end-user terms for
  anything that adds restrictions, and link the source from the listing.
  Raise anything doubtful with the owner before submitting.
- [ ] **F.9** Submit from ALPHA, at least two weeks before the target date
  (Meta's guidance). Record each review response in Findings, fix, resubmit,
  and release to LIVE when approved.

---

## Appendix 1 — Device test matrix

| Area | Quest 3/3S | Quest 2 (if in D4) | Spatial Simulator |
|---|---|---|---|
| Install, launch, panel size, resize | ✓ | ✓ | ✓ |
| Controllers, hands, switching between them (Input.7) | ✓ | ✓ | — |
| Look and Pinch | — | — | ✓ |
| Folder picker, Open with | ✓ | | ✓ |
| Codecs, 4K, HDR (B.7) | ✓ | H.264, HEVC, VP9 | — |
| Enhancement governor (B.8) | ✓ | ✓ | — |
| Audio formats, EQ (B.9) | ✓ | | — |
| SMB, WebDAV, SFTP, S3 (B.11) | ✓ | | ✓ |
| Google Drive, OneDrive, Dropbox, TMDB sign-in (C) | ✓ | | ✓ |
| Second user account on the headset (Functional.12) | ✓ | | — |
| Offline: sources fail per row, the app stays usable (Functional.3, .7) | ✓ | | ✓ |
| Headset off and on mid-playback (B.4) | ✓ | | — |

## Appendix 2 — Review checklist

For whoever reviews the implementation:

1. Compare the ticked boxes with `git log android..meta-horizon`.
2. Run `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease`.
3. `git diff android -- src/main`: only the `DevicePolicy` hooks and fixes
   bound for `android` may differ, and `git diff android -- src/main/AndroidManifest.xml`
   must be empty.
4. Grep every new string across the 19 locale files.
5. Check that no secret, keystore, or `secrets.json` is staged, and that E.3
   holds no Play credentials.
6. Read the Deviations and Handoff logs, and check that each device claim in
   Findings names the device and build.

---

## Findings

| Date | Device / build | Finding | Follow-up |
|---|---|---|---|
| 2026-10-10 | Spatial Simulator 207.0.0.83.331 (Android 14, API 34, `ro.product.manufacturer=Oculus`); P.5 spike debug APK from `88d699d` | Installs and launches as a panel at 1280×800 dp (1600×1000 px at 200 dpi), so the `<layout>` default is honored. The wide navigation and its child rows show, and TMDB loads. The `uiMode` type is NORMAL (`0x21`), so the handheld/large-screen shell is used, not TV. Features include `horizonos.software.horizon_os`, `horizonos.software.gaze_input`, and `android.software.picture_in_picture`. | B.1: `horizonos.software.horizon_os` is available as a runtime marker if one is ever needed; the flavor flag stays primary. |
| 2026-10-10 | Simulator, spike | Folder picker: `OpenDocumentTree` resolves to DocumentsUI. Movies → EdendaleTest → Use this folder → Allow linked the folder; the scan classified five movies and matched the generated *Severance* episodes to TMDB. | B.2: repeat on the Quest 3 and check that access survives a restart. D7's fallback isn't needed so far. |
| 2026-10-10 | Simulator, spike | Playback in the panel: the H.264/AAC MP4 plays (the simulator decodes with goldfish software codecs, so it says nothing about real codecs); E01 auto-advanced to E02; progress was saved (Continue Watching). The player opens in the library's panel (same task), so its own 1280×720 `<layout>` isn't used. `KEYCODE_BACK` closes the player; Home hides the panel without PiP. The media session registers with Horizon's `SpatialWindowResourceService`. | B.4, B.5, B.7 on the Quest 3 (B/Y as Back, hands, controllers). |
| 2026-10-10 | Simulator, spike | **PiP is broken on Horizon OS.** The feature is reported, so the PiP button shows. Tapping it moves the player task to `mode=pinned`, but the pinned window stays nearly panel-sized and covers the library instead of floating small. | B.3: turn PiP off for `horizon` (`supportsPictureInPicture="false"`, with `DevicePolicy` hiding the button and Auto PiP); D.2's side-by-side panel replaces it. |
| 2026-10-10 | Simulator, spike | Sign-in opens in `com.oculus.browser`'s `CustomTabActivity` inside the panel, and `com.babasama.edendale:/oauth2redirect` resolves to `OAuthRedirectActivity`. Google refused the request: "Error 400: invalid_request. Custom URI scheme is not enabled for your Android client." That is a setting on the OAuth client whose ID is in `secrets.json`, so it isn't specific to Horizon. | Owner: in Google Cloud → Credentials → that Android client → Advanced settings, enable the custom URI scheme. Also check whether the Play build ships the same client ID. Then retest (C.1). |
| 2026-10-10 | Simulator, spike | Trailer: the YouTube embed shows "Video player configuration error", Error 153. `MediaDetailScreen` calls `loadUrl` on the embed URL with no `Referer`, and YouTube now requires embeds to identify the embedding site. Not specific to Horizon; the Play build very likely has the same failure. | **Fixed 2026-10-10 (uncommitted, in the shared checkout):** `TrailerEmbed` sends `Referer: https://<applicationId>/`, the identity YouTube's Required Minimum Functionality asks of apps that load the embed URL directly. `TrailerEmbedTest`; 470 JVM tests pass. On the simulator, Error 153 is gone and the trailer's audio plays, but its picture doesn't show in `screencap`. To do: watch it on the Quest 3 and on a phone. |
| 2026-10-10 | Simulator, spike | Add Network Source: the Google Drive chip sits past the right edge of the horizontally scrolling chip row, and nothing shows that the row scrolls. | B.5: wrap the chips (`FlowRow`) or add a scroll cue; this applies to every large screen. |
| 2026-10-10 | Owner's Quest 3 (`eureka`, Horizon OS v207, Android 14, API 34), read-only probe | 4128×2208 at 200 dpi; `uiMode` NORMAL (`0x21`). **No `android.software.picture_in_picture`**, unlike the simulator, so the existing feature check already hides PiP on the headset; B.3 still turns it off explicitly. No `android.hardware.touchscreen` (Edendale declares it `required="false"`). Has `horizonos.software.horizon_os`, `oculus.software.handtracking`, and `horizonos.software.emulated_gamepad`. Hardware decoders: `c2.qti` AV1, AVC, HEVC, VP9, and MV-HEVC; `c2.dolby.decoder.hevc` (Dolby Vision); `c2.dolby.eac3.decoder`. DocumentsUI handles `OPEN_DOCUMENT_TREE`; `com.oculus.browser` provides Custom Tabs; the Google redirect resolves to `OAuthRedirectActivity`. WebView 149. | B.7: test the hardware decoders, Dolby Vision, and E-AC-3 on the headset. D.6 (later): MV-HEVC means spatial video is decodable. |
| 2026-10-10 | Spike APKs | The merged manifest gains `WAKE_LOCK` (on Meta's review-required list) and `ACCESS_NETWORK_STATE` from `media3-exoplayer`. Edendale never calls `setWakeMode`. Badging: minSdk 32, targetSdk 36, `installLocation` auto, `arm64-v8a` only, supported-devices tag present. Release APK 70.5 MB unsigned; debug 91.6 MB. | A.2: remove `WAKE_LOCK` with `tools:node="remove"` in the Horizon manifest if P.6 flags it. |
| 2026-10-19 | `horizon` flavor, release `91c29d1`+`4ad9712` (unsigned) | Merged manifest: `installLocation` auto, minSdk 32, targetSdk 36, permissions `INTERNET`, `ACCESS_NETWORK_STATE`, and AndroidX's `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` only (`WAKE_LOCK` and `WRITE_EPG_DATA` gone), no leanback feature, no `LEANBACK_LAUNCHER`, no banner, `supportsPictureInPicture="false"`, supported devices `quest2\|questpro\|quest3\|quest3s`. `src/main/AndroidManifest.xml` is byte-identical to android's. Release APK 70.6 MB, `lib/arm64-v8a` only; debug 96.2 MB with every ABI. `aapt2` badging also shows an implied `android.hardware.screen.landscape` feature (required) from `PlayerActivity`'s `sensorLandscape`. | P.6: watch whether Meta's validator objects to the implied landscape feature; if so, declare it `required="false"` in the Horizon manifest. |
| 2026-10-19 | Spatial Simulator 207.0.0.83.331, `4ad9712` debug | Install and launch as a panel; `am start -W` reported 28 s to the first frame on this cold start (software rendering; the simulator says nothing about headset timing). The wide navigation shows and TMDB loads. Downloaded → Add Local Folder opened DocumentsUI in the last folder; Use this folder → Allow linked `Movies/EdendaleTest`: 7 items, five movies and *Severance* with two episodes matched. Play on episode 1 opened `PlayerActivity` in the same task (`mode=multi-window`, never pinned); the chrome has no PiP button and Adjustments → Playback has no Auto PiP row, although the simulator reports `android.software.picture_in_picture`, so `DevicePolicy` is in effect. Back closed the Adjustments panel, then the player; the episode row shows the saved progress. | B.3 done on the simulator; repeat the run on the Quest 3 (Headset run, 2026-10-20). |
| 2026-10-25 | Owner's Quest 3 (Horizon OS v207, Android 14), `667149c` debug (0.27.0.1, versionCode 3) | Replaced the 10-10 spike (uninstalled first). Cold start to the first frame took 1.9 s (`am start -W`); the panel opens at 1600×1000 px (1280×800 dp), the wide navigation shows, TMDB loads, and resizing works (owner). `adb exec-out screencap` returns both eyes of the composited view (4128×2208, passthrough included), and `uiautomator dump` plus `input tap`/`input swipe` in panel pixels drive the app from the Mac. | Recipe for later headset runs. |
| 2026-10-25 | Quest 3, `667149c` debug | **Google Drive sign-in works on the headset.** The account page opened in `com.oculus.browser`'s Custom Tab, `com.babasama.edendale:/oauth2redirect` returned to `OAuthRedirectActivity`, and Google's picker linked "Videos you chose": five movies, four matched to TMDB (*ToS-4k-1920.mov* names no film). The 10-10 "Custom URI scheme is not enabled" refusal is gone. Downloaded → Add Local Folder → `Movies/EdendaleTest` linked and scanned too. After `am force-stop` and a relaunch, a *Severance* episode opened from the persisted tree URI in 0.6 s, with no permission error. | B.2 passes apart from "Open with"; C.1's Google part works on the headset. |
| 2026-10-25 | Quest 3, `667149c` debug | Playback: hardware `c2.qti.avc.decoder` (H.264, including the 4K ToS `.mov` streamed from Drive) and `c2.qti.hevc.decoder` (1080p x265), `c2.android.aac.decoder` for 5.1 AAC, and `c2.dolby.eac3.decoder` for E-AC-3. Subtitles show. Picking an Enhancement preset for the first time installed the effects pipeline and re-prepared the player (2.7 s over Drive); the default stayed Off (D6). | B.7, B.9 partly done (see the steps). |
| 2026-10-25 | Quest 3, `667149c` debug | Input with controllers: double-tap skip, press-and-hold speed, the seek bar, and B/Y work. With hands, a double pinch rarely lands as a double-tap. **The thumbstick doesn't scroll the home rows.** The vertical swipes for brightness and volume changed nothing the owner could see or hear. Headset off paused playback and headset on resumed it. | B.5: thumbstick scrolling and the double-pinch are open. B.6 and B.4: see the steps. |
| 2026-10-25 | Quest 3, `667149c` debug | **The inline trailer plays sound but no picture.** Edendale's process created `c2.qti.av1.decoder` for each try, so the WebView decoded the video but the panel never showed it (the simulator's 10-10 result, now on hardware). With the change, Watch Trailer's `ACTION_VIEW` resolved to `com.oculus.browser` (`BrowserActivity`) without a chooser, although `com.google.android.apps.youtube.vr.oculus` is installed. | B.10: the owner checks that the browser shows the picture. |
| 2026-10-25 | Quest 3, `667149c` debug | **Media3 1.9 asks for a wake lock by default.** `ExoPlayerImpl` uses `C.WAKE_MODE_LOCAL` when the app never calls `setWakeMode`, and Edendale doesn't, so each playback logs `WakeLockManager: WAKE_LOCK permission not granted, can't acquire wake lock for playback`. It's only a warning: the panel pauses when the headset comes off, and `FLAG_KEEP_SCREEN_ON` keeps it awake while playing. The 10-19 A.2 Deviation's reason ("Edendale never sets a wake mode") is wrong on this point. | Leave `WAKE_LOCK` removed; if the warning should go, set `C.WAKE_MODE_NONE` on Horizon through `DevicePolicy`. |
| 2026-10-25 | Quest 3, `667149c`+the 10-25 change, debug | Installed over the old build (library and Drive link kept). With `adb shell input swipe`, vertical swipes on either half of the player left the AudioFlinger track at 0 dB and set no `sbrt` on the player window, while a tap still showed the controls and a hold still gave `speed=2.0`. Watch Trailer opened the Meta Quest Browser. | B.6 verified on the headset; B.10 waits for the owner. |

## Deviations log

| Date | Step | Deviation | Why |
|---|---|---|---|
| 2026-10-19 | D0, A.1 | `meta-horizon` is a platform branch downstream of `android`, with one `horizon` flavor and no `play` flavor; android isn't touched, so A.1's before/after badging diff of the Play build doesn't apply. The `android` task names (`testDebugUnitTest`) no longer exist on this branch. | Owner's decision on 2026-10-19: keep a separate branch and pull android's work into it. |
| 2026-10-19 | S.1, baseline | `android-27.0.1` (`7f7ff09`, `9df06dd`: Google Drive on `drive.file` with Google's picker, version 0.27.0.1) was merged into `meta-horizon` although it isn't on `android` yet. | Google refused `drive.readonly`, so a Quest build on the old flow would lose Google Drive; android will carry the same commits, so the later `git merge android` is trivial. |
| 2026-10-19 | A.1 | `abiFilters arm64-v8a` sits on the release build type, not the flavor; the debug APK keeps every ABI. | The x86_64 CI emulator and any Intel emulator still need the FFmpeg decoder's native library; Meta's 64-bit rule is about the uploaded APK. |
| 2026-10-19 | A.2 | `WAKE_LOCK` (declared by `media3-exoplayer`) is removed in the Horizon manifest before the P.6 probe flags it. | It's on Meta's review-required list, and Edendale never sets a wake mode: the player keeps the panel on with `FLAG_KEEP_SCREEN_ON`. One fewer thing to justify in review. |
| 2026-10-19 | A.2 | The TV-only entries aren't moved into a `play` manifest; `src/horizon/AndroidManifest.xml` removes them with `tools:node="remove"`, `tools:remove`, and `tools:replace`, and `src/main/AndroidManifest.xml` stays byte-identical to android's. | Keeps `git merge android` conflict-free. |
| 2026-10-19 | B.1, A.T1 | `DevicePolicy` and its test live in `src/horizon` and `src/testHorizon`; `src/main` only gains the four hooks (`MainActivity`, `EdendaleApplication`, `PlayerActivity`, and an `enhancementDefault` parameter on `VideoEffectsController`). The handoff receiver isn't in the policy: it's TV-shell-only already, and D.1 is deferred by D11. | Smallest possible `src/main` diff for the merges. |
| 2026-10-19 | E.1 | `verifyHorizonRelease` is defined at the end of `build.gradle.kts`, not in an applied `horizon-verify.gradle.kts`. | An applied Kotlin script can't resolve the Android Gradle plugin's classes (`com.android.build.api.*`); tried and failed on AGP 8.10.1. |
| 2026-10-19 | E.2 | `ci.yml` triggers on `meta-horizon` only, and `instrumented.yml` likewise. | Nothing here ships from `android`. |
| 2026-10-25 | A.2 | Correction to the 10-19 `WAKE_LOCK` row: Media3 1.9 does request a local wake lock by default (Findings, 2026-10-25). `WAKE_LOCK` stays removed. | The request fails with a warning only, and the player pauses when the headset comes off. |
| 2026-10-25 | B.4 | Horizon keeps the handheld lifecycle: the headset off pauses, and the headset back on resumes by itself. | Owner's choice after trying it on the Quest 3. |
| 2026-10-25 | B.6 | Both vertical swipes are off on Horizon, not only the brightness one B.6 named: `DevicePolicy.offersSwipeAdjustments`, read by `PlayerScreen`'s gesture layer through `PlayerActivity.devicePolicy` (now `internal`). So Horizon has no app-level volume control in the player; D.4 would add it back. | Owner's choice: neither swipe made a visible or audible change in a panel. |
| 2026-10-25 | B.10 | Watch Trailer opens YouTube's watch page outside the app (`ACTION_VIEW`, `TrailerEmbed.watchUrl`) instead of the inline embed, through `DevicePolicy.playsTrailersInline` in `MediaDetailScreen`. | Owner's choice: the inline embed played sound with no picture on the Quest 3. |

## Handoff log

| Date | Step | Who | Result / commands run |
|---|---|---|---|
| 2026-10-10 | P.5 (simulator) | Claude | Spike = `88d699d` + minSdk 32, `abiFilters arm64-v8a`, TV entries removed, `installLocation`, head-tracking feature, supported devices. `./gradlew assembleDebug assembleRelease` (JDK 17) passed. `metavr ssim start`; `adb install -r`; driven with `metavr ui tap/list`, `adb shell cmd package resolve-activity`, and `dumpsys`. No headset was connected. |
| 2026-10-10 | D0, D3, D8, P.3 | Owner, Claude | Owner chose the flavor, reusing `New.jks`, and their Quest 3. Fingerprints read with `openssl x509 -in ~/Desktop/upload_certificate.pem -noout -fingerprint -sha1/-sha256`. |
| 2026-10-19 | D0 (revised), S.1–S.5 | Owner, Claude | Owner: keep `meta-horizon` as a separate branch and pull android's work into it. Claude removed the stale worktrees and merged local branches, moved the checkout to `meta-horizon`, restored the stashed HORIZON.md and trailer fix (`f04259b`), merged `android-27.0.1` (`695f375`), and ran the baseline `./gradlew testDebugUnitTest assembleDebug` (JDK 17): 480 tests pass, 42 s. |
| 2026-10-19 | A.1–A.5, A.T1, B.1, B.3, E.1, E.2, E.4, E.5 | Claude | `91c29d1` (flavor, manifest overlay, signing, verification, CI, README) and `4ad9712` (`DevicePolicy`). `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease assembleHorizonDebugAndroidTest`: 485 tests pass (the five `DevicePolicyTest` cases included), BUILD SUCCESSFUL in 1m 20s; `verifyHorizonRelease` passed and reported the unsigned APK (signature checks skipped). Simulator run in Findings. Not done: the headset run, signing (the owner's keystore passwords), P.2, P.6, E.3 (D10), and the cross-branch rows on `main` and the privacy policy on `web` (Hard rule 6). |
| 2026-10-19 | F.1, F.4–F.7 | Claude | Draft `f8225e9` in `Horizon Store/27.0/`; the owner approves the text and answers. F.2 art and F.3 (`web`) are untouched. |
| 2026-10-25 | Headset run: B.2, B.4, B.6, B.10 | Owner, Claude | Owner wore the Quest 3 (`2G97C5ZJ2B023W`, re-authorized for USB debugging) and ran the input, playback, Drive, folder, headset-off, and trailer checks. Claude installed `667149c` debug (`adb uninstall` then `install -r`), recorded `adb logcat`, read `dumpsys`, the UI tree, and the app's database, and ran B.2's restart. On the owner's decisions Claude added `offersSwipeAdjustments` and `playsTrailersInline` to `DevicePolicy` and their hooks in `PlayerScreen` and `MediaDetailScreen`. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug` (JDK 17): 486 tests pass. The new debug build is installed on the headset and checked there (Findings). |

## Sources

- [Make your existing app compatible with Meta VR devices](https://developers.meta.com/horizon/resources/2d-product-flavors/) (flavors, manifest, permissions, simulator)
- [Application manifests for release builds](https://developers.meta.com/horizon/resources/publish-mobile-manifest/)
- [Meta Horizon apps must target Android 14 starting March 1](https://developers.meta.com/horizon/blog/meta-quest-apps-android-14-march-1/)
- [VRC requirements by store, including the 2D column](https://developers.meta.com/horizon/resources/publish-quest-req)
- [Prohibited permissions](https://developers.meta.com/horizon/resources/permissions-prohibited/) and [permissions that need review](https://developers.meta.com/horizon/resources/permissions-review-required/)
- [Features overview for Android apps](https://developers.meta.com/vr/documentation/android-apps/features-overview) and [media requirements](https://developers.meta.com/horizon/documentation/android-apps/media-requirements)
- [Meta Spatial Simulator](https://developers.meta.com/horizon/documentation/android-apps/spatial-sim-overview)
- [Store asset guidelines](https://developers.meta.com/horizon/resources/asset-guidelines/)
- [Uploading Meta Quest apps](https://developers.meta.com/vr/resources/publish-upload-overview/) and [OVR Platform Utility](https://developers.meta.com/vr/resources/publish-reference-platform-command-line-utility/)
