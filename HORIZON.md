# HORIZON.md — Edendale on the Meta Horizon Store

Implementation plan and checklist for shipping the Android app to Meta Quest
headsets through the Meta Horizon Store, as a 2D ("panel") app. It is written
for an implementing agent, and for the owner to review the finished work
against.

| | |
|---|---|
| Baseline | `android` at `88d699d` (2026-10-10), where `meta-horizon` starts; `android-27.0.1` (`9df06dd`, Google Drive on `drive.file`) merged in on 2026-10-19 |
| Store | Meta Horizon Store, 2D Android app on Horizon OS (a hybrid app if Section G ships) |
| Work branch | `meta-horizon`, the Meta Quest platform branch downstream of `android` (decision D0, revised 2026-10-19) |
| Requirements read | Meta developer documentation as of 2026-10-10 (Sources, at the end) |
| Written | 2026-10-10; Section G (immersive theater) added 2026-10-25, with scene authoring (G.7, D17) the same day; G.1–G.9 and G.T1 built 2026-10-25 (Handoff) |

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

Section G is separate: an optional immersive theater that would make Edendale
a hybrid app, with its own decisions (D12–D17).

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
| FFmpeg decoder library (G.1; once, and after a pin changes) | `tools/horizon/build-ffmpeg-decoder.sh` → `src/horizon/jniLibs/<abi>/libffmpegJNI.so`, gitignored; needs `sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"` |
| JVM tests | `./gradlew testHorizonDebugUnitTest` |
| Debug APK | `./gradlew assembleHorizonDebug` → `build/outputs/apk/horizon/debug/Edendale-horizon-debug.apk` |
| Release APK | `./gradlew assembleHorizonRelease` (signed when the A.3 properties exist; `…-unsigned.apk` otherwise) |
| Store-rule check | `./gradlew verifyHorizonRelease` (E.1) |
| With the immersive theater (D15) | add `-Phorizon.theater=true` to any command; `gradle.properties` keeps it off, so the release is the 2D app |
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
   comes from Android APIs and a build-time flag. Any Meta library (D.5, and
   the Spatial SDK in Section G, D12) needs an owner decision first.
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
| D0 | Where does the Horizon build live? | **`meta-horizon` is a platform branch downstream of `android`**, like the repository's other platform branches: `android` merges into it (`git merge android`) and nothing merges back, so a fix that isn't headset-specific goes to `android` first. The branch carries one `horizon` flavor whose manifest overlay, code, and tests live in `src/horizon` and `src/testHorizon`, so the merges touch `src/main` only where `DevicePolicy` hooks in. Meta's credentials stay in their own workflow and protected environment. The branch tables on `main` need a row (Hard rule 6); added 2026-10-25 (`b221902`). | **Decided 2026-10-19** (owner): separate branch, kept current from `android`; replaces the 2026-10-10 choice of a flavor on `android` |
| D1 | Application ID | Keep `com.babasama.edendale`. The Google and Microsoft redirects and the App Links are built on it, and the Meta store only requires uniqueness within its own catalogue. | Open |
| D2 | SDK levels for `horizon` | minSdk **32**, Meta's recommendation for Quest-only apps (allowed range 29–34). targetSdk: keep **36** if the P.6 upload probe accepts it. Meta's table allows 32–36 for 2D apps, but its 2026-03-01 rule says apps created after that date "must set targetSdkVersion to 34". Otherwise use 34 for `horizon` only. | Open |
| D3 | Signing key | **Reuse the Play upload key** `~/Documents/Edendale/New.jks`, alias `key0`. Its certificate (`CN=Long Ju`, expires 2051-10-03) has SHA-1 `E9:BF:A7:55:DE:A6:92:0B:9F:24:C6:B5:15:51:DB:B2:C1:46:1F:32`, SHA-256 `F2:CE:0D:97:57:66:4C:1C:BB:C0:4E:2B:0F:B8:E6:9A:4D:79:4A:40:7A:C5:16:73:D0:F8:68:23:87:7E:FF:65`, and Base64 SHA-1 `6b+nVd6mkgufJMa1FVHbssFGHzI=`. Meta distributes the APK as signed, so every Quest update must be signed with this key, even if Play's upload key is ever reset. Keep `New.jks` and its password backed up. | **Decided 2026-10-10** (owner): reuse `New.jks` |
| D4 | Supported devices (`com.oculus.supportedDevices`) | `quest2\|questpro\|quest3\|quest3s`, plus `vrglasses` if Look and Pinch passes in the Spatial Simulator (B.5). Quest 2 and Pro have no AV1 hardware decoder, and the app has no software AV1 decoder. | Open |
| D5 | Entitlement check | **Skip.** It's only recommended for 2D apps (VRC.Quest.Security.1), the app is free and open source (MPL-2.0, over an LGPL-2.1 FFmpeg since G.1), and it would need the Meta Platform SDK and a network call to Meta. | Open |
| D6 | Video Enhancement default on Horizon | **Off** (as on TV) until B.8 measures it. The compositor already resamples the panel, and the GPU is shared with rendering the headset's view. | Open |
| D7 | Local files if the system folder picker is missing on Quest | Use MediaStore video queries with `READ_MEDIA_VIDEO`, which Meta allows after review, with the justification "media player for the user's own videos". Only if B.2 fails. | Open |
| D8 | Test hardware | The owner's **Quest 3** for every hardware check; the Spatial Simulator for Look and Pinch and quick UI runs. Quest 2, Pro, and 3S stay untested unless someone lends one; record that in Findings if D4 lists them. | **Decided 2026-10-10** (owner): Quest 3 |
| D9 | Price and regions | Free, no in-app purchases, every region the store offers. | Open |
| D10 | Release automation | Upload by hand (dashboard or a local `ovr-platform-util`) until the first approval, then add E.3's workflow. | Open |
| D11 | Scope of Section D (Quest-only improvements) for the first release | None of Section D for the first release. D.1 (handoff to the headset) moves up if typing logins on the headset proves painful in B.11. | Open |
| D12 | Meta Spatial SDK for the theater (Section G) | **Allow** `com.meta.spatial` (`meta-spatial-sdk`, `-toolkit`, `-vr`, a release at least two weeks old) in the `horizon` flavor only, as an exception to Hard rule 2. It's closed source under Meta's SDK licence. No Meta Platform SDK, no account, and no Spatial Gradle plugin. It can't ship before D13. | Open; **built under this proposal on 2026-10-25** (G.4–G.8), the owner's decision still needed before release |
| D13 | FFmpeg decoder on this branch | **Replace Jellyfin's GPL-3.0 package with Media3's own decoder over an LGPL-2.1 FFmpeg**, built by `tools/horizon/build-ffmpeg-decoder.sh` (G.1). Jellyfin publishes this same configuration under GPL-3.0. It decodes the same formats: DTS, DTS-HD MA, and TrueHD were verified on the Quest 3 on 2026-10-25. `android` keeps its own D5. Meta's licence forbids use that would subject the SDK to an open-source licence (§1.2.8), and a GPL-3.0 APK would, so D12 needs this first. It also stands alone if the owner wants the Quest APK off GPL-3.0. | Open; **built under this proposal on 2026-10-25** (G.1, G.2) |
| D14 | How the decoder gets into a build | **Build it; don't commit the binary.** Commit Media3's decoder classes (Apache-2.0, headers kept) under `src/horizon/java`. Keep `src/horizon/jniLibs/` gitignored, with the script run before Gradle, locally and in CI: NDK r26b, about 75 s, as `apple` does with `Vendor/FFmpeg`. The alternative is to commit the stripped library (1.4 MB per ABI) and have CI rebuild it and compare; rebuilds differ only in the ELF build ID. | Open; **built under this proposal on 2026-10-25** (the script, the gitignore, and CI, G.1) |
| D15 | When the theater ships | **After the 2D release.** Sections A–F ship first, and the theater follows as an update, provided G.0 confirms that an app created as 2D can add an immersive activity later. | Open; **the first ALPHA build is 2D** (owner, 2026-10-26), so the theater is now built only with `-Phorizon.theater=true` (`src/horizonTheater`, Deviations). Whether it follows as an update still waits on G.0 |
| D16 | APK size with the SDK | **Accept AGP's default**, which stores native libraries uncompressed and page-aligned: 130.1 MB against 70.6 MB, far under Meta's 1 GB limit, and the libraries load without extraction. The alternative is `packaging.jniLibs.useLegacyPackaging = true` for `horizon`. It shrinks the download (the SDK's 47.8 MB library compresses to about 19 MB) but extracts the libraries at install. | Open; the build keeps AGP's default (nothing set) |
| D17 | The scenes: tool and where the sources live | **Blender, not Unity, with the sources on a `scenes` branch** (G.7, G.13). Blender produces data: glTF for this branch and USD for a visionOS counterpart on `apple`. Unity would be a second runtime inside each app, which the repository's no-shared-runtime rule forbids, and a theater has no game loop, physics, or scripting to justify one. The `scenes` branch holds the `.blend` files, their textures, and the export scripts, and nothing executable. Each platform branch commits only its exported artifact (here a GLB under `src/horizon/assets/`) and no build or CI checks `scenes` out, so AGENTS.md's hard constraints 1 and 2 hold. `scenes` needs a row in `main`'s branch tables (Hard rule 6). The alternative was keeping the sources under `tools/horizon/scenes/` here. | **Decided 2026-10-25** (owner) |

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
| Immersive requirements (hybrid apps) | the immersive VRCs apply to the immersive activity (the rows below; G.9, from Meta's per-VRC pages on 2026-10-25, since the VRC table itself wasn't reachable from the build container, so the owner checks the Comfort, Boundary, and Input rows against it) | the theater, Section G | G.9 |
| Performance.1 (immersive: required) | an interactive app renders at 72 Hz or more (60 fps at least); a media app may use 60 Hz where the device offers it | the spike ran at 90/90 fps with 2.5 ms of an 11.1 ms frame; the theater draws one unlit environment, the video layer, and two panels | G.10 measures the real build with VrApi's FPS line |
| Functional.1 | no crashes, freezes, or stuck states over 45 minutes | the theater is one activity with no network of its own; a playback error shows a message and Exit | G.10 (30 minutes of playback) |
| Functional.2 | a single-player app pauses when the system asks: the headset off, the Universal Menu (Meta button) | `onHMDUnmounted`, `onVRPause`, and `SessionState.VISIBLE` pause; mounted, ready, and `FOCUSED` resume | G.6, G.10 |
| Functional.3, .4 | no stuck state; no data loss | Exit, B/Y, and the Meta button all leave; progress is written every five seconds, on pause, on stop, and on exit | G.6 |
| Functional.5 | responds to positional tracking, not only orientation | the SDK's head pose places the screen (6DOF); the viewer can lean and look around it | G.7 |
| Functional.12 | works for every entitled user on the headset | the theater reads the same per-user app data as the panel; no account | G.10 (second account) |
| Input (controllers and hands; the system gesture reserved) | both controllers and hands drive the app; the Meta button is the system's | `TheaterInputSystem` reads triggers, A/X, B/Y, and hand pinches; the SDK's ray clicks the controls panel; nothing reads the Meta button | G.8, G.10 |
| Comfort (seated, no locomotion) | no artificial locomotion or camera motion that isn't the viewer's | the `LocomotionSystem` is unregistered; nothing moves but the video; a recenter re-places the room | G.6, G.7 |
| Boundary | the app respects the boundary (Guardian); a seated app doesn't disable it | nothing is set; the SDK keeps the system's boundary | G.10 confirms with the boundary drawn |

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
| Later | G (immersive theater) | D12–D17; G.0 before P.2 |

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
  *2026-10-26:* the signed 2D build (`0.27.0.1`, versionCode 3, 70.7 MB,
  no theater) is staged in the gitignored `release/horizon/0.27.0.1-alpha/`
  with its SHA-256 (Handoff). P.2 (the app and its App ID) and the upload
  are the owner's.
  *2026-10-26, evening:* the 27.0 store candidate was rebuilt from `04fc499`
  (D.3 sticks) with the flavor's `versionName` `27.0` (Deviations) into
  `release/horizon/27.0/Edendale-horizon-27.0-3.apk`: versionCode 3, 2D
  (`verifyHorizonRelease`; no `com.meta.spatial` class, no Spatial SDK
  library, two activities), New.jks v2, SHA-256 `9c9ada05…c081280284`. It
  replaces `0.27.0.1-alpha`, which has the same versionCode, so upload only
  one of them. The store draft no longer mentions the theater.
  *2026-10-26, later:* the flavor's `versionCode` is now 4 (Deviations), and
  `Edendale-horizon-27.0-4.apk` was built from `d99ff4a` (B.5 label) beside
  it: `27.0`, versionCode 4, 70.7 MB, 2D, New.jks v2, `verifyHorizonRelease`
  passed, SHA-256 `2d9de4ac…09e65753`.
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
2026-10-25). Thumbstick scrolling was fixed the same day, and the owner
confirmed it with the real sticks (B.5). Still to do: "Open with Edendale",
the hand-pinch finding (B.5), B.7 beyond H.264 and 8-bit HEVC, B.8, the rest of B.9, B.11,
steps 4–6, and the cleanup. The headset keeps the 10-25 debug build, the
linked `EdendaleTest` folder, and the owner's Google Drive link.

*Status 2026-10-26:* a second full run on the D.3 build (the owner wore the
headset; Claude read `adb` from the Mac). Launch, the library, Settings,
details, search, the player's taps and holds, E-AC-3, and headset off and on
pass; "Open with Edendale" from the Files app passes (B.2). The real Touch
controllers showed that the thumbstick seek and the Menu button only worked
by injection: the sticks arrive as wheel events at the ray, now read by
`ThumbstickSeeker`, and Horizon OS keeps the Menu button, so D.7 is dropped
(Findings, Deviations). The owner tested the network sources and media
(round 6) themselves. Still to do: the hand-pinch finding, B.7's HDR
and Dolby Vision judgement in the Findings,
B.8, and the cleanup. The headset keeps the 10-26 debug build (D.3 fix, no
Menu shortcut).

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
- [x] **B.2** Local folders: Downloaded → Add Folder and Settings → Sources use
  `OpenDocumentTree`. Check that the picker exists on Horizon OS and that the
  persisted URI survives a restart. If not, implement D7 behind
  `DevicePolicy`. Also check "Open with Edendale" from the headset's file
  manager (`PlayerActivity`'s `video/*` filter).

  *2026-10-25, Quest 3:* the picker and the persisted URI pass (Findings), so
  D7 isn't needed. "Open with Edendale" is still to check.

  *2026-10-26, Quest 3:* "Open with Edendale" passes. Opening
  `video_640x360.mp4` and `decoder-eac3.mkv` in the Files app
  (`com.oculus.systemutilities`) sent `ACTION_VIEW` with a
  `content://com.oculus.systemutilities.fileprovider/…` URI straight to
  `PlayerActivity`, with no chooser. It opened in Edendale's own task in 0.6 s
  (2.2 s from a cold start) and played (`c2.qti.avc.decoder`,
  `c2.dolby.eac3.decoder`); B returned to the library.
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
  resizing the panel works. A double pinch with hands is hard to land as a
  double-tap. The thumbstick didn't scroll the home rows: Horizon sends both
  sticks as joystick RX/RY axes, which nothing read, so on Horizon
  `ThumbstickScroller` (through `DevicePolicy.scrollsWithThumbstick`) turns a
  held stick into a scroll wheel where the ray last pointed: rows scroll
  sideways and the page up and down. The owner confirmed it with both real
  sticks; the speed feels right. The hit-target audit and Look and Pinch are
  still to do.

  *2026-10-26, Quest 3:* hit-target and label audit from `uiautomator dump`
  every 2–3 s while the owner walked the home page, all of Settings, a series'
  details page, search, and the player with Adjustments open. Every control is
  at least 48 dp once it isn't clipped by a scroll edge (Settings' steppers,
  the rating stars, Fit/Fill, Audio Output, Clear date). Three clickables
  showed no label. Two were caught mid-motion: the home hero's outgoing card
  while the carousel rotated, and the seventh cast card at the right edge of
  its row, whose text lay beyond the visible part; settled cards carry their
  text. The third was real: while buffering, the player's centre Play/Pause
  button holds only the spinner, so it had no label. `PlayerScreen` now puts
  the Play/Pause label on the button itself (the icon has none), which also
  helps TalkBack on every Android device. The Adjustments subtitle row "Off"
  read 46 dp tall in its one dump, next to the panel's edge. The real sticks
  and Menu button: D.3, D.7.
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
- [x] **B.10** Trailers: the `youtube-nocookie.com` embed plays in the
  headset's WebView and still starts only on an explicit action.

  *2026-10-25:* in a panel on the Quest 3, the embed played sound but no
  picture. At the owner's request, Watch Trailer on Horizon now opens
  `https://www.youtube.com/watch?v=<key>` with `ACTION_VIEW`
  (`DevicePolicy.playsTrailersInline` is false), still only on that tap. The
  Quest hands the link to the Meta Quest Browser, although YouTube VR is
  installed. The owner confirmed picture and sound there, and the browser's
  media process decoded the trailer with `c2.qti.av1.decoder`.
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
- [x] **D.3** Thumbstick left and right in the player skip by the App Controls
  lengths (`onGenericMotionEvent`).

  *2026-10-26:* done as a push-and-hold, the split the remote's rewind and
  fast-forward keys already had: a push past 60% of the stick's travel that
  springs back skips by the App Controls lengths; one held past 400 ms plays
  at the hold speeds until the stick comes back. Up, down, and diagonal pushes
  do nothing. A D-pad's left and right (a gamepad, or the virtual device's
  hat) get the same press-and-hold on Horizon instead of the keyboard's
  repeating seeks. While a panel is open the sticks scroll its list where the
  ray points, as the library does (B.5). `ThumbstickSeeker` and
  `ThumbstickSeekTest` in `src/horizon` and `src/testHorizon`;
  `DevicePolicy.seeksWithThumbstick`; `PlayerActivity` gains
  `dispatchGenericMotionEvent`, `dispatchTouchEvent`, and the D-pad branch in
  `dispatchKeyEvent`.

  *2026-10-26, real controllers:* the build above only worked by injection.
  The Touch controllers' sticks reach a 2D panel as wheel events at the ray,
  as Meta's Android input guide says (`ACTION_SCROLL`, `AXIS_HSCROLL` and
  `AXIS_VSCROLL`, only while the ray is over the panel), never as the joystick
  axes `adb shell input joystick` injects (Findings). `ThumbstickSeeker` now
  reads them too: a run of mostly sideways wheel events past
  `WHEEL_ENGAGE` (0.15, about half a push) is a push, and the run stopping for
  150 ms is the stick springing back. The joystick path stays for gamepads.
  With an open panel the wheel events pass through and scroll its list
  natively. The owner confirmed flicks (skip) and holds (2×, 0.5×) with the
  real sticks; 517 JVM tests pass.
- [ ] **D.4** App-level volume and mute in the player through
  `player.volume`, which Meta's media guidance recommends.
- [ ] **D.5** Hover highlights under gaze through Meta's Gaze SDK for Compose.
  This adds a Meta dependency, so check its licence and network behavior with
  the owner first.
- [ ] **D.6** Immersive theater: now Section G, with its own decisions
  (D12–D17). 3D, 180°, and 360° playback is G.12.
- [ ] ~~**D.7**~~ *Dropped 2026-10-26.* The left Touch controller's Menu button opens and closes the
  player's Adjustments, as Meta's input guidance asks of in-app menus
  (VRC.Quest.Input.1). *2026-10-26:* `DevicePolicy.menuButtonOpensAdjustments`;
  `PlayerActivity.dispatchKeyEvent` toggles `PlayerPanel.SETTINGS` on
  `KEYCODE_MENU` and `KEYCODE_BUTTON_START` (Findings: the controller's key
  layout can't be read, so both codes the Menu button could carry are taken),
  where the handheld player treated Menu as Back. The library keeps its
  keyboard toggle (Ctrl+B) only. Injected on the headset, `KEYCODE_MENU` is
  taken by Horizon OS before the app (Findings, 2026-10-26); `BUTTON_START`
  and `BUTTON_SELECT` reach the player and toggle Adjustments. Whether the
  controller's Menu button is one of those is the owner's check: if pressing
  it in the player opens the system menu instead, the button is the OS's in
  2D apps and this step becomes a gamepad-only nicety.

  *2026-10-26, real controllers:* the Menu button is the OS's. Each press
  reached the controller's virtual device as `BTN_START`, and vrshell's
  `SystemButtonHandler` took it (`handleKeyEvent: 108`, "standalone menu
  short press") before any window; the player never saw it. Injected keys
  skip that handler, which is why the earlier check passed. The owner chose to
  remove the shortcut: `menuButtonOpensAdjustments` and the
  `KEYCODE_MENU`/`BUTTON_START` branch are gone, and the Adjustments button in
  the chrome is the way in. VRC.Quest.Input.1's Menu-button rule can only
  apply to immersive apps (Section G).

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
- [ ] **F.8** Licence: with G.1 and G.2 the APK is no longer GPL-3.0 as a
  whole: Edendale's code is MPL-2.0, FFmpeg is LGPL-2.1 (its notice and
  licence text ship in the APK, and the README names the corresponding
  source), Media3's decoder is Apache-2.0, and the Spatial SDK is under Meta's
  SDK licence. Read Meta's developer distribution agreement and its end-user
  terms for anything that adds restrictions, and link the source from the
  listing. Raise anything doubtful with the owner before submitting.
- [ ] **F.9** Submit from ALPHA, at least two weeks before the target date
  (Meta's guidance). Record each review response in Findings, fix, resubmit,
  and release to LIVE when approved.

---

## Section G — Immersive theater (hybrid app)

An optional mode, opened from the player, that shows the video on a large
screen inside an environment instead of in a panel. It uses Meta's Spatial
SDK, so Edendale becomes a hybrid app: the library and the player stay 2D
panels, and the theater is a separate immersive activity. The 2D release
doesn't need any of G (D15).

**What the 2026-10-25 spike settled.** The spike is on the local branch
`horizon-immersive-spike` (`37ef2bd`, not pushed); details are in Findings.

- Spatial SDK 0.14.0 (`meta-spatial-sdk`, `-toolkit`, `-vr`) builds in the
  `horizon` flavor through `horizonImplementation`, adds no permission except
  hand tracking, and passes `verifyHorizonRelease`.
- The test scene was a skybox and a 4 m screen 5 m away. It ran at 90 fps on
  the Quest 3, using about 22% of the GPU and 2.5 ms of an 11.1 ms frame.
- Video goes from Media3 straight to the SDK's surface panel through
  Edendale's own `EdendaleRenderersFactory` and `EdendaleDataSource`. This
  played: HEVC up to 4K, H.264, AAC, E-AC-3, DTS, DTS-HD MA, and TrueHD.
- The app's UID sent and received nothing while the theater ran.
- The blocker was the licence. Meta's SDK licence forbids any use that would
  subject the SDK to an open-source licence (§1.2.8). This branch's APK is
  GPL-3.0 as a whole because of Jellyfin's FFmpeg decoder. That package is
  Media3's stock FFmpeg build, which FFmpeg itself reports as LGPL 2.1, so
  building it ourselves (D13) removes the GPL and keeps DTS, DTS-HD MA, and
  TrueHD.

- [ ] **G.0** *(Before P.2.)* Find out whether an app created as a 2D Android
  app (P.2) can later ship an update that adds an immersive activity, or
  whether the app type is fixed when the app is created. Ask Meta if the docs
  don't say. If it's fixed, raise it with the owner before P.2, since it
  decides D15. *2026-10-25: a web search found Meta's hybrid-app pages but no
  statement either way; Meta's developer site wasn't reachable from the
  build container. Still the owner's question to Meta.*
- [x] **G.1** The LGPL decoder (D13, D14). Take
  `tools/horizon/build-ffmpeg-decoder.sh` from the spike branch (written
  again from this list on 2026-10-25; the spike branch isn't pushed):
  - It runs Media3's `build_ffmpeg.sh` unmodified, from the Media3 tag that
    matches `libs.versions.toml` (1.9.0). The FFmpeg is `release/6.0` at
    `831958d` (6.0.2), Jellyfin's pin.
  - It uses Jellyfin's decoder list:
    `flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd`.
  - It builds `libffmpegJNI.so` with the SDK's CMake 3.22.1 and NDK r26b
    (`sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`).
  - It stops unless FFmpeg's `config.h` says "LGPL version 2.1 or later".
  - Extend it to all four ABIs. The debug APK keeps every ABI (A.1's
    Deviation), so the x86_64 CI emulator would otherwise lose DTS and TrueHD.

  Then:
  - Put Media3's decoder classes (`androidx.media3.decoder.ffmpeg`) in
    `src/horizon/java`, with `horizonCompileOnly`
    `org.checkerframework:checker-qual`, which the classes import.
  - Remove the `jellyfin-media3-ffmpeg-decoder` dependency from this branch
    (log it as a Deviation from android's D5).
  - CI builds the library (D14).
  - `verifyHorizonRelease` also fails when `lib/arm64-v8a/libffmpegJNI.so` is
    missing or an `org.jellyfin` artifact is in the release runtime classpath.

  G.1 doesn't depend on the theater. If the owner takes only D13, it moves the
  Quest APK off GPL-3.0 on its own. *Done 2026-10-25: the script also checks
  the vendored classes against the Media3 tag, refuses `CONFIG_GPL`,
  `CONFIG_NONFREE`, and `CONFIG_VERSION3`, and writes
  `src/horizon/jniLibs/BUILD-INFO.txt`; `horizonImplementation
  media3-decoder` supplies the decoder base classes; CI caches the FFmpeg
  build on the script's hash. Not run here (no NDK in the container): the
  owner's first run is in the Handoff.*
- [x] **G.2** Licence and notices (after G.1). *Done 2026-10-25 apart from the
  owner's reading of the agreement: README, `src/horizon/assets/licenses/`
  (`LGPL-2.1.txt`, `FFMPEG-NOTICE.txt`), the About line overridden in the
  `horizon` flavor's 19 locales (the app has no licence screen; About holds
  the TMDB, Nunito, and project lines), D5, and F.8. The SDK packages'
  notice files (§1.3) couldn't be inspected here, since Google's Maven
  repository was unreachable; check the AARs' `META-INF` on the first build.*
  - **README:** rewrite the Licence section and the dependency table. The
    Horizon build is no longer GPL-3.0 as a whole:
    - Edendale's own code is under the repository's licence (MPL-2.0 on
      `main`).
    - FFmpeg 6.0.2 is LGPL-2.1, built by the script. Its source is the FFmpeg
      commit plus the Media3 tag plus the script.
    - Media3's decoder is Apache-2.0.
    - The Spatial SDK is under Meta's SDK licence.

    Replace the Jellyfin row.
  - **LGPL-2.1 obligations:**
    - Ship the licence text and an FFmpeg notice with the app.
    - Offer the corresponding source (the links above).
    - Keep relinking possible: the app is open source, and the script rebuilds
      `libffmpegJNI.so`.

    Check how the app shows licences today (jcifs-ng is already LGPL-2.1), and
    add FFmpeg there.
  - **Meta's licence:** include any notice files the SDK's packages carry
    (§1.3), and don't imply a partnership with Meta (§3.4). The SDK is
    licensed only for Meta-approved hardware (§1.1), which the `horizon`
    flavor is.
  - Update D5 (it says "the app is free and GPL-3.0") and F.8 to match.
  - *(Owner)* Read the agreement (Sources); this plan's reading of it isn't
    legal advice. In particular, decide whether LGPL-2.1 §6 sits comfortably
    beside the SDK's ban on reverse engineering (§1.2.4). §6 asks that the work
    using FFmpeg permit reverse engineering for debugging. The usual reading is
    that it does: the SDK doesn't use FFmpeg, and Edendale's own code is open
    source.
- [ ] **G.3** Privacy *(the owner approves a `web` change)*. Meta's licence
  (§1.4.1) requires a prominent notice that third parties, Meta included, may
  collect or receive information from the app. *2026-10-25: F.4's draft
  answer names the SDK (Horizon Store/27.0); the `web` paragraph and the
  release-candidate measurement remain.*
  - Extend F.3: name the Spatial SDK and say what it may send under Meta's
    terms. Mention that the spike measured nothing from the app's UID, which
    can't show anything the OS collects itself.
  - Repeat that measurement on the release candidate (G.10).
  - Update F.4's answers if Meta's Data Use Checkup asks about SDKs.
  - Don't use Meta's Spatial Gradle plugin, which has its own usage-data
    switch (`allowUsageDataCollection` in Meta's samples). If it's ever
    needed, set that switch to false.
- [x] **G.4** The Spatial SDK and the manifest (D12). *Done 2026-10-25, with
  `-compose` added for G.8's panels and `exported="true"` as in Meta's
  sample (G.10 tries `false`).*
  - Add `spatialsdk` to `libs.versions.toml`, and `horizonImplementation`
    dependencies on `meta-spatial-sdk`, `-toolkit`, and `-vr`. 0.14.0 was
    released on 2026-09-11. Add no other Spatial SDK module unless a step needs
    it.
  - In `src/horizon/AndroidManifest.xml`:
    - **The theater activity:** `com.oculus.intent.category.VR` and `DEFAULT`,
      `singleTask`, `Theme.Black.NoTitleBar.Fullscreen`, and the
      configuration changes from Meta's hybrid sample. Check whether
      `exported="false"` works, since only Edendale starts it.
    - **`<horizonos:uses-horizonos-sdk minSdkVersion="69" targetSdkVersion="69">`:**
      without it, Horizon OS logs that SDK managers will stop working.
    - **Hand tracking:** `oculus.software.handtracking`
      (`required="false"`) and `com.oculus.permission.HAND_TRACKING`. Without
      them, a "controllers required" launch check blocks the theater while the
      controllers are asleep. Neither of the `gradle/meta-*` lists names the
      permission.
    - **`<uses-native-library android:name="libossdk.oculus.so" android:required="false" />`.**
  - Keep `android.hardware.vr.headtracking` at `required="false"`, so the panel
    app installs as before. Run P.6's validator on a hybrid build (G.0).
- [x] **G.5** Entering the theater: a Theater button in the player's chrome.
  *Done 2026-10-25: `DevicePolicy.offersImmersiveTheater`, the chip in
  `PlayerControlsOverlay`, `PlayerActivity.enterTheater()`, and
  `TheaterHandover` (the item, position, speed, the audio and subtitle
  choices, and the episode context).*
  - A new `DevicePolicy.offersImmersiveTheater` (true on Horizon only) shows
    it, and only while a video is loaded. It's an explicit action.
  - The `src/main` hook is the button and a callback, like PiP's.
  - The player hands over the media item, the position, the audio and
    subtitle choices, and the episode context, then finishes.
  - The theater owns its own ExoPlayer, built with `EdendaleRenderersFactory`
    and `EdendaleDataSource` as in the spike, so local, SMB, WebDAV, SFTP, S3,
    and cloud sources work unchanged.
  - New strings go into all 19 locales.
- [x] **G.6** Leaving the theater, and its lifecycle. *Done 2026-10-25 in
  `TheaterActivity`; the exit path and the pause/resume hooks follow Meta's
  hybrid and media samples and need the G.10 run.*
  - The theater needs its own exit. In the spike, `KEYCODE_BACK` sent by
    `adb` didn't close it. Give the controls panel (G.8) an Exit button, and
    handle the controllers' B/Y where the SDK reports them.
  - Exit returns to Home with the panel focused, through Meta's hybrid-sample
    pattern: a `HOME` intent carrying `extra_launch_in_home_pending_intent`
    for `MainActivity`. A plain `finish()` also returns to Home, but leaves the
    panel unfocused.
  - Save progress on exit and on stop, as `PlayerActivity` does
    (Functional.4), so Continue Watching picks up where the theater left off.
  - Taking the headset off pauses, and putting it on resumes (B.4's choice).
    The Meta button, Quit, and a recenter must leave no stuck state.
- [x] **G.7** The scene. *Done 2026-10-25 for the code: `TheaterScene` reads
  `Screen` and `Seat` from `theater.glb` (JVM-tested), `TheaterLayout` places
  the room and the screen from the head pose and the video's aspect ratio
  (JVM-tested), the activity re-places after a recenter, and the sky is a
  generated starfield (`tools/horizon/make_skybox.py`). No GLB ships yet:
  `scenes` has no scene, and the container has no Blender. Two conventions
  are a first-run check (G.10): that the SDK imports glTF's -Z as its own
  forward, and that `Quaternion.lookRotationAroundY(viewer − panel)` faces a
  panel at the viewer, as Meta's samples use it.*
  - **Environment:** an equirectangular skybox (`mesh://skybox`), unlit so it
    costs little, and optionally a floor or room model. Art must be made in
    house or carry a licence that fits the repository. The spike's starfield
    comes from `tools/horizon/make_skybox.swift`. Passthrough instead of the
    skybox (`com.oculus.feature.PASSTHROUGH`) is an option if the owner wants
    it.
  - **Screen:** `VideoSurfacePanelRegistration`, which renders direct to a
    compositor layer. Size it from the video's aspect ratio (the spike's fixed
    16:9 quad stretches others); the default is 4 m wide at 5 m.
  - **Placement:** place the screen from the head pose once tracking reports a
    nonzero position (the first query returns (0, 0, 0)), and place it again
    after a recenter.
  - **Fine detail:** 1–2 px line pairs from a 4K source alias at this size.
    That's the headset's resolution, not a bug. A readable panel with mipmaps
    would soften it, at a performance cost, so only try it if the owner
    minds.
  - **Authoring (D17):** make the environment in Blender and export glTF; no
    Unity. Blender is the modelling, texturing, and baking tool, the Spatial
    SDK loads GLB directly, and Unity would be a second engine in the APK.
    - Sources live on the `scenes` branch (D17, G.13). This branch commits
      only `src/horizon/assets/<scene>.glb`, and the commit that adds or
      updates a GLB names the `scenes` commit it was exported from.
    - One `.blend` per scene at real-world scale: the seat at the origin and
      the screen straight ahead. Name the screen quad `Screen` and the seat
      empty `Seat`, so the activity places `VideoSurfacePanelRegistration`
      from the GLB rather than from constants, and a visionOS counterpart
      finds the same two nodes.
    - Bake all lighting to textures with Cycles and export unlit (emissive or
      base-colour) materials; the theater has no real-time lights. Keep the
      surfaces around the screen dark and neutral so they don't wash out the
      picture.
    - Export GLB with Blender's glTF exporter (it converts Blender's Z-up to
      glTF's Y-up; apply modifiers; power-of-two textures). Compose in Meta
      Spatial Editor, from the Meta Quest Developer Hub, only if a scene needs
      more than one asset. The spike's `mesh://skybox` becomes a GLB loaded
      from assets.
    - The same `.blend` exports USD for an `apple` visionOS theater, where
      Reality Composer Pro adds the docking region. That is apple's plan, not
      this branch's; here the source only has to stay exportable to both.
    - Budget: the spike's skybox and quad took 2.5 ms of the 11.1 ms frame at
      90 fps, so a room may spend the rest on draw calls and overdraw. Measure
      with G.10's VrApi line rather than against a triangle count.
- [x] **G.8** Controls and subtitles in the theater. *Done 2026-10-25:
  `TheaterPanels.kt` (a controls card under the screen; the subtitle overlay
  reused in a panel 2 cm in front of it) and `TheaterInputSystem`. The theater
  builds no `VideoEffectsController`, so Enhancement is off there by
  construction.*
  - A small controls panel under the screen: play and pause, seek, audio and
    subtitle tracks, and Exit. Show it on trigger or pinch and hide it after a
    few seconds. Use `ComposeViewPanelRegistration`, or the SDK's `-compose`
    module.
  - Subtitles go in a panel just in front of the screen. The 2D player draws
    them in Compose over its `SurfaceView`, which the surface panel doesn't
    have.
  - Controllers and hands both work (Input.7).
  - Video Enhancement is off in the theater, through `DevicePolicy`, because
    its pipeline renders into a `SurfaceView`.
- [x] **G.9** Immersive store requirements. *Done 2026-10-25 from Meta's
  per-VRC pages (the table page wasn't reachable); the owner checks the
  Comfort, Boundary, and Input rows against it.* Read the immersive column of
  Meta's VRC page (Sources). In Section 1, list which requirements apply to a
  seated theater with no locomotion (frame rate, comfort, the boundary,
  input, the Meta button, performance) and how G meets each. Then update:
  - F.2: theater screenshots are allowed, and likely the best ones;
  - F.6: the input modes;
  - F.7: tell reviewers how to open the theater.
- [ ] **G.10** Device checks on the Quest 3 (Appendix 1):
  - entering and leaving the theater from every source type;
  - video on the surface panel: 1080p and 4K HEVC, AV1, HDR10, and Dolby
    Vision;
  - audio: DTS-HD MA, TrueHD, E-AC-3 and Atmos, and 7.1. For TrueHD, use
    Dolby-encoded samples, not FFmpeg's own encoder (Findings);
  - 30 minutes of playback for heat and dropped frames (VrApi's per-second
    FPS line);
  - controllers and hands, recenter, headset off and on, and a second user
    account;
  - the network measurement: `dumpsys netstats` for the app's UID, and its
    sockets;
  - cold and warm entry times.

  Also check whether the Spatial Simulator runs the theater at all.
- [x] **G.T1** Tests (*written 2026-10-25; the JVM ones passed in a scratch
  JVM project, since the container has no Android SDK; the instrumented one
  hasn't run*):
  - JVM: the screen's size and pose as a pure function of the video's aspect
    ratio and the head pose, and the new `DevicePolicy` flag.
  - Instrumented: `FfmpegLibrary.isAvailable()` is true, and
    `FfmpegLibrary.supportsFormat` accepts DTS, DTS-HD, and TrueHD. Run it
    with `am instrument`, never `connected…AndroidTest` on the owner's
    headset.
- [ ] **G.11** Size (D16). The release APK grows from 70.6 MB to 130.1 MB.
  Almost all of that is `libMetaSpatialSDK.so` (47.8 MB), stored
  uncompressed by default; the decoder swap doesn't change the size. Record
  the installed size on the headset, and decide D16 before release.
- [ ] **G.12** *(Later; not in G's first release.)* 3D side-by-side and
  over-under, 180°, and 360° video, and MV-HEVC spatial video, through the
  surface panel's stereo modes and equirectangular shapes. The Quest 3 has an
  MV-HEVC decoder (Findings, 2026-10-10).
- [x] **G.13** *(Before G.7's first scene; Hard rule 6 for the `main` rows.)*
  The `scenes` branch (D17).
  - Create it from `main` (`git switch -c scenes main`), so it carries the
    universal documents and nothing from any platform. It holds
    `scenes/<name>/<name>.blend` with that scene's textures beside it,
    `tools/` with the export scripts, and a README that says what the branch
    is, how to export, and the licence of every piece of art (made in house,
    or under a licence that fits the repository, as G.7 requires). No app
    code, no platform CI, no credentials.
  - The export scripts run Blender headless, so an export is reproducible:
    `Blender --background scenes/<name>/<name>.blend --python
    tools/export_gltf.py -- out/<name>.glb`, and `tools/export_usd.py` for
    `apple`. A script applies modifiers, exports the baked textures saved
    with the `.blend` (it bakes nothing itself), and fails if `Screen` or
    `Seat` is missing.
  - A platform branch commits the export only: here
    `src/horizon/assets/<name>.glb`, with the `scenes` commit it came from in
    the commit message. No build or CI checks `scenes` out (AGENTS.md hard
    constraints 1 and 2).
  - Size: commit `.blend` files and textures as plain files. If one passes
    about 50 MB, raise Git LFS with the owner before adding it; GitHub
    refuses files over 100 MB.
  - On `main`, as its own change with the owner's go-ahead: add `scenes` to
    the branch tables in `README.md` and `AGENTS.md` ("Scene sources:
    `.blend` files, textures, and export scripts; no executable code"). The
    `meta-horizon` row that D0 asks for can go in the same change.

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
| Immersive theater (G.10) | ✓ | | check whether it runs |

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
7. For Section G:
   - `tools/horizon/build-ffmpeg-decoder.sh` passes its LGPL check;
   - `./gradlew dependencies --configuration horizonReleaseRuntimeClasspath`
     lists no `org.jellyfin` artifact;
   - the theater's only `src/main` change is the player's Theater button and
     its callback.

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
| 2026-10-25 | Quest 3, `667149c` debug | Input with controllers: double-tap skip, press-and-hold speed, the seek bar, and B/Y work. With hands, a double pinch rarely lands as a double-tap. **The thumbstick doesn't scroll the home rows.** The vertical swipes for brightness and volume changed nothing the owner could see or hear. Headset off paused playback and headset on resumed it. | B.5: the double-pinch is open; thumbstick scrolling is fixed (two rows down). B.6 and B.4: see the steps. |
| 2026-10-25 | Quest 3, `667149c` debug | **The inline trailer plays sound but no picture.** Edendale's process created `c2.qti.av1.decoder` for each try, so the WebView decoded the video but the panel never showed it (the simulator's 10-10 result, now on hardware). With the change, Watch Trailer's `ACTION_VIEW` resolved to `com.oculus.browser` (`BrowserActivity`) without a chooser, although `com.google.android.apps.youtube.vr.oculus` is installed. There the trailer plays with picture and sound (owner), decoded by `c2.qti.av1.decoder` in `com.oculus.browser:privileged_process0`. | B.10 done. |
| 2026-10-25 | Quest 3, `667149c` debug | **Media3 1.9 asks for a wake lock by default.** `ExoPlayerImpl` uses `C.WAKE_MODE_LOCAL` when the app never calls `setWakeMode`, and Edendale doesn't, so each playback logs `WakeLockManager: WAKE_LOCK permission not granted, can't acquire wake lock for playback`. It's only a warning: the panel pauses when the headset comes off, and `FLAG_KEEP_SCREEN_ON` keeps it awake while playing. The 10-19 A.2 Deviation's reason ("Edendale never sets a wake mode") is wrong on this point. | Leave `WAKE_LOCK` removed; if the warning should go, set `C.WAKE_MODE_NONE` on Horizon through `DevicePolicy`. |
| 2026-10-25 | Quest 3, `667149c`+the 10-25 change, debug | Installed over the old build (library and Drive link kept). With `adb shell input swipe`, vertical swipes on either half of the player left the AudioFlinger track at 0 dB and set no `sbrt` on the player window, while a tap still showed the controls and a hold still gave `speed=2.0`. Watch Trailer opened the Meta Quest Browser. | B.6 and B.10 verified on the headset. |
| 2026-10-25 | Quest 3, `c25a463`, `getevent -lt` | **Both thumbsticks reach a panel as one joystick's RX/RY.** Pointing at a home row, the owner pushed each stick left, right, up, and down: every push came through `/dev/input/event5` (`KEYBOARD \| GAMEPAD \| JOYSTICK`) as `ABS_RX`/`ABS_RY` (Android `AXIS_RX`/`AXIS_RY`, flat 0.125), never `ABS_X`/`ABS_Y` or the hat. Android turns only X/Y and the hat into D-pad keys, and Compose reads no joystick, so nothing scrolled. The trigger arrives as `BTN_TR2` plus `BTN_TR`, the grip as `KEY_RIGHTSHIFT`. Android 14's `input` has no wheel injection, but `input joystick motionevent MOVE x y` injects X/Y. | B.5: `ThumbstickScroller` (next row). |
| 2026-10-25 | Quest 3, `c25a463`+`ThumbstickScroller`, debug | With the pointer set on the Trending row (a touch-down that was then cancelled, so nothing opened) and `input joystick motionevent MOVE 0.9 0` held for 1 s, the row scrolled about 1,280 px and stick-left scrolled it back. Stick down over the hero scrolled the page (Trending from y 817 to 165), and up scrolled it back. Down with the pointer on a row scrolled the page, not the row. No errors in the log. | B.5: the owner then tried the real sticks with the ray: rows and the page scroll, and the speed feels right. |
| 2026-10-25 | Owner's Quest 3 (Horizon OS v207); immersive spike, debug, application ID `com.babasama.edendale.spike` beside the real build (commit `37ef2bd`) | **The Spatial SDK theater works.** It's an `AppSystemActivity` with a skybox and a `VideoSurfacePanelRegistration` screen, playing through `EdendaleRenderersFactory`. 4K and 1080p HEVC used `c2.qti.hevc.decoder`, H.264 with AAC played, and E-AC-3 used `c2.dolby.eac3.decoder`. VrApi reported 90/90 fps, no stale frames, GPU about 22%, CPU and GPU 2.5 ms per frame, and 46 °C. Cold launch into the theater took 1.7–2.5 s, and 0.65 s from the panel. `adb screencap` is black in the theater, but `metavr capture screenshot` (metacam) captures the eye view. The SDK starts its own stereo OpenSL ES player when the scene loads. | G.10 repeats this on the real build. |
| 2026-10-25 | Quest 3, immersive spike | Network: after `dumpsys netstats --poll`, the spike's UID had no entry under "UID stats", and `/proc/net/{tcp,udp}{,6}` showed no sockets, across several runs. The SDK logged "Analytics module not available" and "analytics: disabled". Its native library does contain Meta's analytics and QPL code. Anything the OS collects itself can't be seen this way. | G.3. |
| 2026-10-25 | Quest 3, immersive spike | Manifest and launch: without `horizonos:uses-horizonos-sdk`, `VrosSystemServiceRegistry` logged that the app retrieves an HzOS SDK manager without declaring a minimum HzOS SDK version and "must fix this to continue to access this SDK in the future"; declaring 69 silenced it. With the controllers asleep, launching the theater stopped at vrshell's `LaunchCheckControllerRequiredDialogActivity`; declaring hand-tracking support removed it. | G.4. |
| 2026-10-26 | Quest 3 (vrshell 209.0.0.454.433), `dumpsys input`, `/system/usr/keychars/Generic.kcm` | The controllers' virtual device is vendor `0x2833` product `0x0160` with `/odm/usr/keylayout/oculus-device.kl`, which adb can't read (permission denied), so the Menu button's Android key code isn't known from the device. Its scan codes are a gamepad's: `BTN_START`, `BTN_SELECT`, and `BTN_MODE` are all declared. The device uses `Generic.kcm`, whose fallbacks apply when an app leaves a gamepad key unhandled: `BUTTON_START` → `DPAD_CENTER`, `BUTTON_SELECT` → `MENU`, `BUTTON_MODE` → `HOME`, `BUTTON_A` → `DPAD_CENTER`, `BUTTON_B` → `BACK`. Meta's 2D-app input page names only B/Y (Back) and the joystick (`AXIS_HSCROLL`/`AXIS_VSCROLL` scroll by default). With Meta Casting running on the Mac, `adb exec-out screencap` and `screenrecord` return black; `metavr capture screenshot` still gives the eye view, and `metavr capture video start`/`stop` (hidden commands) save nothing. | D.7 takes `KEYCODE_MENU` and `KEYCODE_BUTTON_START`; a `BUTTON_SELECT` Menu would arrive as the `MENU` fallback. The owner's press settles which. |
| 2026-10-26 | Quest 3, D.3/D.7 build (debug), `adb shell input`, `dumpsys media_session`, `uiautomator dump` | In the player with *Obsession* playing: `input keyevent KEYCODE_MENU` never reached the app; the system's `KeyboardShortcutHandler` (vrshell) logged `dispatchAction: menu_button keyCode=82` and the player's chrome stayed hidden. `KEYCODE_BUTTON_START` and `KEYCODE_BUTTON_SELECT` (whose `Generic.kcm` fallback is `MENU`, raised inside the app's own window) both toggled Adjustments. `input joystick motionevent MOVE 0.9 0` then `MOVE 0 0` skipped +10 s, `-0.9` skipped −10 s; held for 1.2 s the session reported `speed=2.0` (right) and `speed=0.5` (left) and `1.0` again on release, with no skip; a vertical push changed nothing. `KEYCODE_DPAD_RIGHT` skipped +10 s; `--longpress KEYCODE_DPAD_LEFT` slowed briefly and did not skip. | So a keyboard's Menu key belongs to Horizon OS in a 2D app; D.7 works if the controller's Menu button arrives as `BUTTON_START` or `BUTTON_SELECT`, which the owner's press on this build shows (Adjustments opens, or the system menu does). A `uiautomator dump` with no labelled nodes means the player's chrome is hidden, not a failed dump. |
| 2026-10-26 | Owner's Quest 3 (Horizon OS v207, vrshell 209), D.3/D.7 build with the real Touch controllers; `getevent -lt`, `logcat`, and a temporary log in `PlayerActivity.dispatchGenericMotionEvent`/`dispatchKeyEvent` (removed afterwards) | **Injected input misled the D.3/D.7 checks.** The Menu button reached the controller's virtual device as `BTN_START`, and vrshell's `SystemButtonHandler` took every press (`handleKeyEvent: 108` → "standalone menu short press") before any window; the player never saw it. The trigger and grip pass through the same handler (103, 105) and reach the virtual device as `BTN_TR`/`BTN_TR2`. No thumbstick motion appeared on the virtual devices (no `EV_ABS` in two recordings). In the player, the stick came as `ACTION_SCROLL` from source `0x2` (`SOURCE_CLASS_POINTER`) at the ray: `AXIS_HSCROLL` about ±0.27–0.34 a frame at a full push, every 13–16 ms (72 Hz), rising from about 0.05 over two or three frames and tailing off, `AXIS_VSCROLL` 0 on a sideways push, positive to the right, and no event at all on release. Alongside came `ACTION_HOVER_MOVE` from source `0x1002`. `adb shell input joystick` sends joystick X/Y instead, which is why the injected run passed. | D.3 reads the wheel runs; D.7 dropped (Deviations). Meta's Android input guide says this: the stick scrolls scrollable views by default and arrives as `AXIS_HSCROLL`/`AXIS_VSCROLL`, one controller at a time, only while its ray is over the panel. The 10-25 RX/RY reading (B.5) wasn't seen again; whether RX/RY ever reach the home page wasn't re-checked. |
| 2026-10-26 | Quest 3, the full run on the D.3 build | Cold start to MainActivity's first frame took 2.3 s; the home page, Settings, a series' details page, search, and the player passed (B.5's audit, B.2). Settings → Accounts → Google Drive → Choose More Videos opened the Google page in a Custom Tab (`com.oculus.browser`'s `CustomTabActivity`). Closing it without finishing left the account row on "Waiting for Google Drive…" with Cancel until Cancel was tapped: `ChooseMoreVideosState` waits for the redirect, the same on every Android device. Taking the headset off paused playback and putting it on resumed it (owner; B.4 unchanged). | The Drive wait is an android-branch question, not Horizon's. |
| 2026-10-26 | Quest 3, `metavr capture screenshot` | Each `metavr capture screenshot` is a MetaCam `TAKE_SCREENSHOT`: it saves `/sdcard/Oculus/Screenshots/com.oculus.vrshell-<date>-<time>.jpg` in the headset's gallery, plays the shutter sound (the `c2.android.vorbis.decoder` lines every few seconds), and queues an upload to the owner's Meta camera roll (`VR_AUTO_SYNC`). A 3 s capture loop left 255 files, deleted from the headset afterwards; uploaded copies stay in the Meta Horizon app. `adb exec-out screencap -p` saves nothing (black while Meta Casting runs). | Don't loop `metavr capture`; use `screencap`, `uiautomator dump`, and logs, and take single `metavr` shots only with the owner's go-ahead. |
| 2026-10-25 | Quest 3, immersive spike | Placement and exit: the head entity's pose reads (0, 0, 0) on the first query after `onSceneReady`, so the screen first landed at floor level; waiting for a nonzero pose (two or three tries at 100 ms) fixed it. `adb shell input keyevent KEYCODE_BACK` didn't close the theater. `finish()` returned to Home with the Edendale panel open but unfocused; the `HOME` intent with `extra_launch_in_home_pending_intent` returned with the panel focused. | G.6, G.7. |
| 2026-10-25 | Quest 3, immersive spike with the LGPL decoder (G.1) | **Media3 1.9.0's FFmpeg decoder over an LGPL-2.1 FFmpeg 6.0.2 plays DTS, DTS-HD MA, and TrueHD.** It replaced Jellyfin's package; the arm64 `libffmpegJNI.so` is 1,462,264 bytes, against Jellyfin's 1,462,200. Results in the theater and in the 2D player: DTS 5.1 through `ffmpegLavc60.3.100-dca` to a 5.1 track; DTS-HD MA 7.1 24-bit (FATE `dts/master_audio_7.1_24bit.dts`) as `audio/vnd.dts.hd`, 8 channels, to a 7.1 track (`0x18fc`); Dolby TrueHD 5.1 (FATE `lossless-audio/truehd_5.1.raw`) through `-truehd` to a 5.1 track. No `ffmpeg_jni` errors. In a control run, Jellyfin's package played the same DTS 5.1 file in the 2D player. | G.1. B.9: DTS through FFmpeg passes with either decoder. |
| 2026-10-25 | Quest 3 and the Mac, both decoders | **TrueHD from FFmpeg's own experimental encoder doesn't decode in Media3.** Every packet fails with "Assignment of matrix channel 0 to invalid output channel -22" and "Invalid data found when processing input". That happens with Jellyfin's decoder and the LGPL build alike, and on the Mac when libavcodec runs without a parser first. Media3's JNI doesn't preset a channel layout for TrueHD. Dolby-encoded TrueHD decodes. | Test TrueHD (B.9, G.10) with Dolby-encoded samples only. |
| 2026-10-25 | Immersive spike, release (unsigned) | Size: 70.6 MB grows to 130.1 MB. `libMetaSpatialSDK.so` is 47.8 MB stored uncompressed (AGP's default; 18.6 MB gzipped), and `-vr` adds `libMetaSpatialSDKIsdk.so` (3.8 MB). The SDK's packages declare no permissions, and `verifyHorizonRelease` passes with `HAND_TRACKING` added. | D16, G.11. |
| 2026-10-26 | Spatial Simulator; signed 2D release `0.27.0.1` (versionCode 3) | The release build (not debuggable, signed with `New.jks`) installs and launches as a panel; TMDB loads; Add Local Folder → EdendaleTest links and scans; Severance S01E01 plays, and the DTS fixture opens with `FfmpegAudioRenderer` loaded. The player's chrome shows only the playlist and panel buttons: no Theater, no PiP. The simulator reports `android.hardware.screen.landscape`, so the required landscape feature `aapt` infers from `PlayerActivity`'s `sensorLandscape` is satisfied there. | Check `adb shell pm list features` on the Quest 3; if the feature is missing, P.6's validator or the store's device filter may refuse the build, and the manifest would need `<uses-feature android:name="android.hardware.screen.landscape" android:required="false" />`. |

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
| 2026-10-25 | D.6 | The immersive theater is planned in this document, as Section G with decisions D12–D16, instead of in a separate plan. | Owner's request, after the spike. |
| 2026-10-25 | D12–D16, G | Section G was built while D12–D16 are still Open, under each decision's proposal. | The owner asked for Section G to be built; the table records the state, and release waits on the decisions. |
| 2026-10-25 | G.1 | `tools/horizon/build-ffmpeg-decoder.sh` was written from G.1's list, not taken from `horizon-immersive-spike`, and builds at API 32 (the flavor's minSdk) where Jellyfin builds at 23. | The spike branch isn't pushed; the library never runs below the flavor's minSdk. |
| 2026-10-25 | G.1 | The decoder's Java classes live under `src/horizon/java`; `ExperimentalFfmpegVideoRenderer` is vendored with them although nothing uses it. | The script checks every class against the Media3 tag, so the set stays whole. |
| 2026-10-25 | G.2 | The in-app notice is the `horizon` flavor overriding `settings_open_source` in 19 locales, not a new row in Settings. | The app has no licence screen, and a resource override keeps `src/main` as android has it (Hard rule 1). |
| 2026-10-25 | G.4, G.8 | `meta-spatial-sdk-compose` is a fourth Spatial SDK module. | G.8's panels are `ComposeViewPanelRegistration`, which that module provides; nothing else from the SDK is added. |
| 2026-10-25 | G.5 | `src/main` gains the Theater chip, the callback, `enterTheater()`, `ic_theater.xml`, and `player_theater` in 19 locales. | The hook G.5 allows; Appendix 2 item 7 lists it. |
| 2026-10-25 | G.7 | No `theater.glb` ships; the sky is a starfield from `tools/horizon/make_skybox.py` (Python, where the spike used Swift). | `scenes` holds no scene yet, and Blender isn't in the build container; the activity loads the GLB when it exists. |
| 2026-10-25 | G.T1 | The JVM tests ran in a scratch JVM project (Kotlin 2.2.0, kotlinx.serialization 1.8.1, JUnit 4), not through `testHorizonDebugUnitTest`. | The container has no Android SDK, and `dl.google.com` is blocked by its proxy, so Gradle can't configure the Android build there. |
| 2026-10-26 | D15, G.4 | The theater's Spatial SDK code (`TheaterActivity`, `TheaterInputSystem`, `TheaterPanels`), its manifest entries (the activity, `uses-horizonos-sdk`, hand tracking, `libossdk.oculus.so`), and its resources (ids, the panel theme, the sky, the `theater_*` strings) moved to `src/horizonTheater/`, which Gradle adds to the `horizon` source sets only with `horizon.theater=true`; `gradle.properties` sets it to `false`. The Spatial SDK dependencies are conditional too. `TheaterHandover` names the activity by class name, and `DevicePolicy.resolve` takes `hasTheater` (`BuildConfig.HORIZON_THEATER`). The 2D About line (`settings_open_source`, 19 locales) drops the Spatial SDK clause; `src/horizonTheater` overrides it with the 10-25 text. `verifyHorizonRelease` fails a 2D build that carries any of the theater, and a theater build whose manifest entry and class don't agree; CI builds both. | The owner chose a 2D-only first ALPHA build. Off by default so a release can't carry the SDK by accident while D12 and D15 are open. The layer's code and resources join the flavor and variant source sets in the android DSL: AGP 8.10.1's variant API (`sources.kotlin`/`res.addStaticSourceDirectory`) was tried first and silently applied only the manifest, which gave a theater APK declaring `TheaterActivity` without its class. |
| 2026-10-26 | D.3 | A push does more than D.3's "skip": a held stick plays at the hold speeds, a D-pad's left and right get the same press-and-hold on Horizon (where the handheld keyboard code repeats seeks), and with a panel open the sticks scroll its list (natively for the controllers' wheel events, through `ThumbstickScroller` for a gamepad's joystick). `src/main` gains `dispatchGenericMotionEvent`, `dispatchTouchEvent`, `onWindowFocusChanged`, the listener, and the D-pad branch in `PlayerActivity`; `handleFastForwardRewindKey` takes an `isForward` parameter. *2026-10-26 (real controllers):* `ThumbstickSeeker` also reads the wheel runs Horizon sends for the Touch controllers' sticks (`ACTION_SCROLL`, D.3). | Owner's request (2026-10-26): push skips, hold changes speed, for the stick and the D-pad alike. The hooks follow B.1's pattern, so android stays untouched. |
| 2026-10-26 | D.7 (new, then dropped) | The Menu button toggled Adjustments in the player; the step wasn't in Section D. The same day the real controllers showed that Horizon OS keeps the Menu button in 2D apps, and the owner had the shortcut removed: `DevicePolicy.menuButtonOpensAdjustments` and the `KEYCODE_MENU`/`BUTTON_START` branch in `PlayerActivity` are gone, so Menu keys fall back to the handheld handling. | Owner's request (2026-10-26), and Meta's VRC.Quest.Input.1 asks that in-app menus open from the left controller's Menu button; removed on the owner's decision once `SystemButtonHandler` was seen taking it (Findings). |
| 2026-10-26 | B.5 | `src/main`'s `PlayerScreen` puts the centre button's Play/Pause label on the button (`Modifier.semantics`) instead of its icon, so the button keeps a label while the buffering spinner replaces the icon. | Found by the headset audit, but it isn't a headset fix: it belongs on `android` too, as its own change (branch model), with the owner's go-ahead. |
| 2026-10-26 | A.4 | The `horizon` flavor sets its own `versionName`, `27.0`, over android's `0.27.0.1`, and, later the same day, its own `versionCode`, 4, over android's 3. | Owner's requests: Settings (which shows `versionName`) and the Horizon Store should read 27.0, and the next Horizon build is 4. Each store only compares a code with its own last upload, so the code no longer waits for a bump on `android`. Set both again for each Horizon release. |

## Handoff log

| Date | Step | Who | Result / commands run |
|---|---|---|---|
| 2026-10-26 | B.5 labels | Claude | Of the audit's three unlabeled clickables, two were mid-motion dumps (the rotating hero's outgoing card, a half-visible cast card); the player's centre button while buffering was real and now carries its label on the button (Deviations). `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonDebugAndroidTest` (JDK 17): 517 tests, one failure in `BufferedByteSourceTest.evictsChunksFarBehindTheReadPositionAndStaysWithinTheCache` (`ConcurrentModificationException`, untouched code), which passed on three `--rerun`s; installed with `adb install -r`. Not checked on the headset: it was asleep, and keeping it awake with the `prox_close` broadcast only brought up Guardian's tracking-lost dialog. |
| 2026-10-26 | Full app run; D.3 fix, D.7 dropped, B.2, B.5 | Owner, Claude | The owner wore the Quest 3 through six rounds; Claude read `logcat`, `getevent`, `uiautomator dump`, and `dumpsys` from the Mac. The installed build matched the checkout (SHA-256). The real controllers broke D.3 and D.7 (Findings); on the owner's decisions `ThumbstickSeeker` gained the wheel path (`WHEEL_ENGAGE`, `WHEEL_GAP_MILLIS`, 2 new tests) and D.7 was removed. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug` (JDK 17): 517 tests pass; installed with `adb install -r` (data kept) and confirmed by the owner with the real sticks. 255 `metavr` screenshots from this run were deleted from `/sdcard/Oculus/Screenshots` on the owner's go-ahead; two from 14:08 and 14:14 were left. Not done: labels for the three clickables (B.5), round 6 (network and media; the owner tested it), uncommitted. |
| 2026-10-26 | D.3, D.7 (owner's request: Menu opens the sidebar; stick and D-pad left/right skip, hold for 2×/0.5×) | Owner, Claude | Uncommitted in the main checkout. `ThumbstickSeeker.kt` (+ `ThumbstickSeekTest`, 6 cases), `DevicePolicy.seeksWithThumbstick`/`menuButtonOpensAdjustments` (+ 1 test), `ThumbstickScroller.scrolling`, and the `PlayerActivity` hooks. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug` (JDK 17): BUILD SUCCESSFUL, the Horizon tests pass. Installed `Edendale-horizon-debug.apk` on the Quest 3 (`adb install -r`, 14:10) over the 10-25 build and drove the player by injection (Findings). Meta Casting access on the Mac was declined, so the eye view came from `metavr capture screenshot`. Not verified: the physical Menu button's key code, and the owner's report of the panel flashing while browsing in "focus view" (no repro recipe yet; `screencap`/`screenrecord` are black while casting and `metavr` video saves nothing). |
| 2026-10-10 | P.5 (simulator) | Claude | Spike = `88d699d` + minSdk 32, `abiFilters arm64-v8a`, TV entries removed, `installLocation`, head-tracking feature, supported devices. `./gradlew assembleDebug assembleRelease` (JDK 17) passed. `metavr ssim start`; `adb install -r`; driven with `metavr ui tap/list`, `adb shell cmd package resolve-activity`, and `dumpsys`. No headset was connected. |
| 2026-10-10 | D0, D3, D8, P.3 | Owner, Claude | Owner chose the flavor, reusing `New.jks`, and their Quest 3. Fingerprints read with `openssl x509 -in ~/Desktop/upload_certificate.pem -noout -fingerprint -sha1/-sha256`. |
| 2026-10-19 | D0 (revised), S.1–S.5 | Owner, Claude | Owner: keep `meta-horizon` as a separate branch and pull android's work into it. Claude removed the stale worktrees and merged local branches, moved the checkout to `meta-horizon`, restored the stashed HORIZON.md and trailer fix (`f04259b`), merged `android-27.0.1` (`695f375`), and ran the baseline `./gradlew testDebugUnitTest assembleDebug` (JDK 17): 480 tests pass, 42 s. |
| 2026-10-19 | A.1–A.5, A.T1, B.1, B.3, E.1, E.2, E.4, E.5 | Claude | `91c29d1` (flavor, manifest overlay, signing, verification, CI, README) and `4ad9712` (`DevicePolicy`). `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease assembleHorizonDebugAndroidTest`: 485 tests pass (the five `DevicePolicyTest` cases included), BUILD SUCCESSFUL in 1m 20s; `verifyHorizonRelease` passed and reported the unsigned APK (signature checks skipped). Simulator run in Findings. Not done: the headset run, signing (the owner's keystore passwords), P.2, P.6, E.3 (D10), and the cross-branch rows on `main` and the privacy policy on `web` (Hard rule 6). |
| 2026-10-19 | F.1, F.4–F.7 | Claude | Draft `f8225e9` in `Horizon Store/27.0/`; the owner approves the text and answers. F.2 art and F.3 (`web`) are untouched. |
| 2026-10-25 | Headset run: B.2, B.4, B.6, B.10 | Owner, Claude | Owner wore the Quest 3 (`2G97C5ZJ2B023W`, re-authorized for USB debugging) and ran the input, playback, Drive, folder, headset-off, and trailer checks. Claude installed `667149c` debug (`adb uninstall` then `install -r`), recorded `adb logcat`, read `dumpsys`, the UI tree, and the app's database, and ran B.2's restart. On the owner's decisions Claude added `offersSwipeAdjustments` and `playsTrailersInline` to `DevicePolicy` and their hooks in `PlayerScreen` and `MediaDetailScreen`. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug` (JDK 17): 486 tests pass. The new debug build is installed on the headset and checked there (Findings). |
| 2026-10-25 | B.5 (thumbstick), B.10 | Owner, Claude | Owner confirmed the browser trailer (`c25a463`) and pushed both sticks while Claude recorded `getevent -lt` (Findings). Claude added `ThumbstickScroller` and `DevicePolicy.scrollsWithThumbstick` in `src/horizon`, with `MainActivity` gaining the hook (`dispatchGenericMotionEvent`, `dispatchTouchEvent`), plus `ThumbstickScrollTest`. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug` (JDK 17): 490 tests pass. Installed on the Quest and checked with injected joystick input (Findings). |
| 2026-10-25 | D.6 → Section G (spike and plan) | Owner, Claude | The spike worktree was cut from `7ab7624` in Claude's scratchpad and committed to the local branch `horizon-immersive-spike` (`37ef2bd`, not pushed). It was installed on the Quest 3 as `com.babasama.edendale.spike`, beside the real build; the owner's build and data weren't touched. NDK r26b (owner's go-ahead) and the SDK's CMake 3.22.1, which the JNI build needs, were installed with `sdkmanager`. `tools/horizon/build-ffmpeg-decoder.sh` rebuilt the library in 74 s, and the result differs from the tested build only in its 20-byte ELF build ID. `./gradlew assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease` (JDK 17) passed. Before the headset run, an LGPL host build on the Mac round-tripped TrueHD bit-exact and decoded DTS. Still on the headset: the spike app and its test files (`adb uninstall com.babasama.edendale.spike` removes both). Not done: everything in Section G, and decisions D12–D16. |
| 2026-10-25 | G.7, D17 (scene authoring) | Owner, Claude | The owner asked whether to build the theater scenes in Unity or Blender: Blender (D17), because both headsets load exported data natively and Unity would be a shared runtime. The pipeline is G.7's Authoring bullet; where the `.blend` sources live is still the owner's call. Tooling on the Mac, outside the repository: Blender Lab's `blender_mcp` (clone at `~/Codes/blender_mcp`; its add-on was already installed and listening in Blender 5.2.3) was registered with Claude Code at user scope with `claude mcp add -s user blender -e BLENDER_PATH=/Applications/Blender.app/Contents/MacOS/Blender -- uv --directory ~/Codes/blender_mcp/mcp run --python 3.14 blender-mcp`, pinned to Python 3.14 (uv-managed) because the Mac's default Python is 3.15. A stdio client listed its 26 tools and read the open file's path info from the running Blender; the `*_for_cli` tools, which start a background Blender, weren't exercised. |
| 2026-10-25 | D17 (decided) | Owner | The owner chose the `scenes` branch: `.blend` sources and export scripts there, and each platform branch commits only its export. Recorded in D17, G.7's Authoring bullet, and the new G.13. The branch itself and the `main` rows (Hard rule 6) aren't created yet. |
| 2026-10-25 | G.1–G.9, G.T1 | Claude (cloud session, no headset, no Android SDK) | Built Section G on `meta-horizon` from the plan, under D12–D16's proposals (Deviations). Sources read: Media3 `1.9.0` (`7cc1056`), Meta's Spatial SDK samples (0.14.0: Hybrid, MediaPlayer, PremiumMedia), Jellyfin's build (`ffmpeg` at `831958d`, `media` at `7cc1056`), and `origin/scenes`. Ran: `bash -n` on the script; `python3 -I tools/horizon/make_skybox.py`; a scratch JVM project with `TheaterLayout`, `TheaterScene`, and their tests: 16 tests pass. **Not run:** the script itself, any Gradle task, the instrumented test, and every device check: the container has no NDK or Android SDK and can't reach `dl.google.com` or `developers.meta.com`. **Owner, in order:** 1. decide D12–D16 and ask Meta G.0; 2. `sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`, then `tools/horizon/build-ffmpeg-decoder.sh` (expect four `libffmpegJNI.so` and "LGPL version 2.1 or later"); 3. `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease` (JDK 17) and fix what the Spatial SDK's 0.14.0 API rejects, since nothing here was compiled against it; 4. `installHorizonDebug installHorizonDebugAndroidTest` and `am instrument` for `FfmpegLibraryInstrumentedTest`; 5. G.10 on the Quest 3, first checking the room's orientation, the screen facing the seat, the controls card under it, `exported="false"`, and the Exit return (G.7's two conventions); 6. G.3's `web` paragraph and G.11's installed size. |
| 2026-10-25 | G.13 | Owner, Claude | Owner's go-ahead under Hard rule 6. Done in two worktrees in Claude's scratchpad, so this checkout stayed on `meta-horizon`: `main` was fast-forwarded to the already prepared `main-meta-horizon` (`b221902`, the `meta-horizon` rows) and got `9a9162f`, the `scenes` rows plus a paragraph in `README.md` and `AGENTS.md`; `scenes` was cut from that `main` and got `0b0ea32`: its README, `.gitignore` (`/out/`, `*.blend1`), `tools/export_gltf.py`, `tools/export_usd.py`, and `tools/_theater_scene.py`. Checked with Blender 5.2.3 LTS headless on a generated scene (Seat, a 4 m Screen 5 m ahead, a floor with an unapplied array modifier and a packed texture): the GLB keeps `Screen` at (0, 1.5, -5) and `Seat` at the origin, 6 triangles (modifier applied), 1 image; the USDZ holds `theater.usdc` plus the texture, `upAxis = "Y"`, the conversion as a rotation on `/root`; a scene without `Seat` exits 1 and writes nothing. Both worktrees were removed. Pushed on the owner's go-ahead: `origin/main` is at `9a9162f` and `origin/scenes` at `0b0ea32`. |
| 2026-10-26 | G.1, G.T1 (first build and headset run) | Claude | The owner asked for a test of the 10-25 cloud session's G.1. Done in a scratch worktree detached at `d4a73ec`, so this checkout stayed as it was. Two fixes were needed, in this commit: `build.gradle.kts` called `horizonImplementation(…)` and `horizonCompileOnly(…)` as accessors, which the Kotlin DSL doesn't generate for flavor configurations, so Gradle failed while configuring; they're quoted now, as the spike had them. `tools/horizon/build-ffmpeg-decoder.sh` sent CMake's configure log into `build/ffmpeg-decoder/jni-build/` before that directory existed; it now creates each ABI's build directory first. Then the script ran in 90 s: four ABIs, the vendored classes match Media3 1.9.0, `config.h` says "LGPL version 2.1 or later", and the arm64 `libffmpegJNI.so` is 1,462,976 bytes (API 32). `./gradlew testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease` (JDK 17): 507 tests pass, and the release is 130.1 MB with the library and no `org.jellyfin` artifact; with `src/horizon/jniLibs` moved away, `verifyHorizonRelease` fails on the missing library. On the Quest 3, a debug build with a scratch-only `.ffmpegtest` application ID suffix was installed beside the owner's build and uninstalled afterwards; the owner's build and data weren't touched. `am instrument`: `FfmpegLibraryInstrumentedTest` (3 tests) and `FfmpegAudioInstrumentedTest` (the DTS 5.1 and TrueHD 5.1 fixtures) pass. A scratch test, not committed, played two 40 s DTS-HD MA 5.1 24-bit Blu-ray excerpts from the owner's library (deleted afterwards): the headset has no platform DTS decoder, `ffmpegLavc60.3.100-dca` decoded them to 6 channels at 48 kHz for their full length, and every channel's peak equals the same FFmpeg 6.0.2 decoding them on the Mac. Of the 10-25 row's list, steps 2–4 are done. **Not run:** the theater on the headset (G.10). |
| 2026-10-26 | D15, P.6 (ALPHA package) | Owner, Claude | Owner asked for an ALPHA package and chose 2D only (theater held back) and a build without the upload. The owner put `New.jks`'s passwords in `.secret/horizon-signing.properties` (Claude created it from the example, without them). Claude added the `horizon.theater` switch (Deviations). Ran (JDK 17): `./gradlew clean`, then `testHorizonDebugUnitTest assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease`: 508 tests pass, the store check passes, signed with the Quest key (v2 only), 70.7 MB, "2D panel app"; and `-Phorizon.theater=true assembleHorizonDebug assembleHorizonRelease verifyHorizonRelease`: passes at 130.1 MB with the theater's class, resources, and About line in the APK. `apksigner verify --print-certs` shows SHA-256 `f2ce0d97…7eff65`; `aapt2 dump badging` shows `com.babasama.edendale` 3 / `0.27.0.1`, minSdk 32, targetSdk 36, arm64-v8a, `installLocation` auto, INTERNET and ACCESS_NETWORK_STATE only. Spatial Simulator run in Findings (the old debug build there was uninstalled first). The APK is `release/horizon/0.27.0.1-alpha/Edendale-horizon-0.27.0.1-3.apk`, SHA-256 `0e01c8a26beffc788c84673e9069b5bb9333d06d751c4a9945fea30fddace442`. Not done: P.2, the upload (P.6), the headset install from the channel, and `pm list features` on the Quest 3. `Horizon Store/27.0/source-en-GB.md` still describes the theater, which a 2D submission doesn't have. |

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
- Section G: [Meta Platform Technologies SDK License Agreement](https://developers.meta.com/vr/licenses/oculussdk/) (read 2026-10-25), [Spatial SDK packages](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-packages/), [non-essential developer data use](https://developers.meta.com/horizon/policy/non-essential-developer-data-use/), and [Meta's Spatial SDK samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples) (MIT; `HybridSample`, `MediaPlayerSample`, `PremiumMediaSample`)
- Section G, decoder: [Media3's FFmpeg decoder](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg), [Jellyfin's build of it](https://github.com/jellyfin/jellyfin-androidx-media), [FFmpeg's licence notes](https://ffmpeg.org/legal.html), and the [FATE sample suite](https://fate-suite.ffmpeg.org/)
