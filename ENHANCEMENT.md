# Windows 27.0 Enhancements

Tracks the `windows-27.0` work that brings apple-27.0's product behavior to
Windows, plus Windows gaps found while comparing the branches.

- **Product rules:** [DIFF.md on `main`](https://github.com/Ju-Long/Edendale/blob/main/DIFF.md)
  is the source for every feature numbered 3.x below. This file lists the
  Windows tasks and repeats only the constants needed to build and test them.
- **Apple design notes:**
  [ENHANCEMENT.md on `apple-27.0`](https://github.com/Ju-Long/Edendale/blob/apple-27.0/ENHANCEMENT.md).
  Apple test files named below live in `EdendaleTests/` on that branch.
- **Video enhancement uses Option A:** only what LibVLC 3 already ships
  (GPU super resolution, the graphics driver's video processor, and AMD's
  frame-rate doubler and denoiser). No custom swapchain or shader pipeline;
  see [Not planned](#not-planned).
- **Last reviewed:** 2026-10-01, against apple-27.0 `4f8383a`. The branch
  now bundles LibVLC 3.0.24 (F.1).

## How to use this file

- Change `- [ ]` to `- [x]` as each task lands. Tick a feature in the
  [Tracker](#tracker) only when every box in its section is ticked and the
  tests pass.
- Boxes that are already ticked record what Windows had before this tracker
  started.
- Port rules and test cases natively (AGENTS.md hard constraints 1 and 7).
  When Windows has to behave differently, say so in the section and record
  the difference in DIFF.md on `main`.
- Put pure rules in `Edendale.Windows/Core/` and link each new file into
  `Edendale.Windows.Tests/Edendale.Windows.Tests.csproj`. That project links
  source files instead of referencing the WinUI app.
- Store new settings as device-local JSON under `%LOCALAPPDATA%\Edendale`
  (`Services/AppPaths.cs`), never in the OneDrive replica.
- Add UI text to `Strings/en-US/Resources.resw` first, then to the 14
  translated locales. `en-AU`, `en-CA`, and `en-GB` only take keys whose
  spelling differs. apple-27.0's string catalog has translations of the same
  copy.
- Run the checks from the README:

```powershell
dotnet test Edendale.Windows.Tests/Edendale.Windows.Tests.csproj
msbuild Edendale.Windows.sln -restore -p:Platform=x64 -p:Configuration=Debug
```

## Windows today

What `windows-27.0` (`47d532f`) has before this work:

- LibVLCSharp.WinUI 3.10.1 with LibVLC 3.0.23.1, drawn into `VideoView`'s
  swapchain.
- A full-window player (not full screen) with play/pause, ±10 s skip buttons,
  Space, ←/→, and Esc.
- A speed button that cycles 1.0 → 1.25 → 1.5 → 2.0 → 0.5, and a Fit/Fill
  button that resets every time.
- Picture in picture through the compact-overlay window, and a playlist panel.
- A subtitles flyout with audio and subtitle tracks and the online search.
- Resume, progress written every 5 s, and completion at 95 %. The player
  closes at the end of a file.
- Local folders and SMB shares, a Continue Watching shelf, and OneDrive
  replication of watch state.
- Settings: About, Audience, Startup, Sources, TMDB Account, Your Archive, and
  Attribution.

## Tracker

Priority follows DIFF.md: **P1** parity, **P2** worthwhile, **P3** optional.

**Foundation**

- [ ] [F.1 LibVLC 3.0.24](#f1-libvlc-3024) (P1)
- [x] [F.2 Device-local player settings](#f2-device-local-player-settings) (P1)
- [x] [F.3 Reopen at the same position](#f3-reopen-at-the-same-position) (P1)
- [x] [F.4 Player Adjustments panel](#f4-player-adjustments-panel) (P1)
- [x] [F.5 Player HUD](#f5-player-hud) (P2)

**Player** (DIFF.md §3.1–3.11)

- [x] [3.1 App Controls](#31-app-controls) (P1)
- [ ] [3.2 Seek and speed checks](#32-seek-and-speed-checks) (P2)
- [x] [3.3 Remembered player state](#33-remembered-player-state) (P1)
- [x] [3.4 Auto-advance and Up Next](#34-auto-advance-and-up-next) (P1)
- [x] [3.5 Skip prompts](#35-skip-prompts) (P1)
- [x] [3.6 Audio enhancement](#36-audio-enhancement) (P1)
- [ ] [3.7 Picture adjustments](#37-picture-adjustments) (P2)
- [ ] [3.8 Subtitle appearance](#38-subtitle-appearance) (P1)
- [x] [3.9 Track pickers](#39-track-pickers) (P1)
- [x] [3.10 Playlist redesign](#310-playlist-redesign) (P2)
- [x] [3.11 Play From](#311-play-from) (P2)

**Library and sources** (DIFF.md §3.12–3.15 and 3.19)

- [ ] [3.12 Storage providers](#312-storage-providers) (P1, staged)
- [ ] [3.13 Buffered remote reads](#313-buffered-remote-reads) (P1, with 3.12)
- [x] [3.15 Desktop navigation and shortcuts](#315-desktop-navigation-and-shortcuts) (P2)
- [x] [3.19 Settings layout](#319-settings-layout) (P3)

**Video enhancement, Option A** (DIFF.md §3.16–3.17)

- [x] [E.1 GPU capability probe](#e1-gpu-capability-probe) (P2)
- [x] [E.2 Option builder](#e2-option-builder) (P2)
- [x] [E.3 Applying the options](#e3-applying-the-options) (P2)
- [x] [E.4 Enhancement controls](#e4-enhancement-controls) (P2)
- [ ] [E.5 Hardware test matrix](#e5-hardware-test-matrix) (P2)
- [ ] [E.6 Documentation](#e6-documentation) (P2)

**System integration and Windows gaps** (DIFF.md §3.18, plus gaps DIFF.md
doesn't list)

- [x] [3.18 Media keys and audio output](#318-media-keys-and-audio-output) (P2)
- [x] [W.1 Full screen](#w1-full-screen) (P1)
- [x] [W.2 Volume and mute](#w2-volume-and-mute) (P1)
- [ ] [W.3 Keep the display awake](#w3-keep-the-display-awake) (P1)

**Windows extras** (new behavior; tell the other branches before shipping)

- [x] [X.1 Xbox controller](#x1-xbox-controller) (P3)
- [x] [X.2 Taskbar](#x2-taskbar) (P3)
- [ ] [X.3 Headphone surround](#x3-headphone-surround) (P3)
- [x] [X.4 Refresh-rate matching](#x4-refresh-rate-matching) (P3)
- [ ] [X.5 NFS through LibVLC](#x5-nfs-through-libvlc) (P3)
- [x] [X.6 Audio and subtitle delay](#x6-audio-and-subtitle-delay) (P3)
- [x] [X.7 Chapters](#x7-chapters) (P3)

Suggested order: F.1 → F.2 → W.1–W.3 → 3.1 → F.3 → F.4 → 3.3 → 3.4 → 3.5 →
3.6 → 3.7 → E.1–E.6 → 3.8 → 3.9 and 3.10 → F.5 → 3.15 → 3.11 → 3.18 →
3.12 and 3.13.

### Open items

Every task that changes code has landed, and `dotnet test` passes. What's
still open needs Windows hardware or a change on another branch.

**Checks on Windows**

- F.1: playback in the x64, ARM64, and x86 builds.
- 3.2: the seek and speed checks.
- 3.7: the two-hour soak test.
- 3.8: subtitle size at 720p, 1080p, and 4K; bitmap subtitles; and SRT,
  WebVTT, and ASS files with CRLF line endings or UTF-16.
- 3.13: UNC throughput over a VPN or Tailscale.
- E.5: the hardware test matrix.
- W.3: the 5-minute screen timeout check.
- X.3: Headphone Surround compared with Windows Sonic.
- X.5: NFS against a real export.
- Live sign-ins to OneDrive and Dropbox, and SFTP, WebDAV, and S3 against
  real servers. The tests use stub handlers only.

**Changes on other branches** (AGENTS.md hard constraint 7)

- `main` DIFF.md: the Windows differences from D1–D3 and D5–D10, and the
  Windows-only extras X.1–X.7.
- `main` README: the Windows provider list (3.12).
- `main` DESIGN.md: the `PlaylistActiveBackground` and `PlaylistActiveText`
  rows (3.10).
- `web`: the privacy text for linked storage providers (DIFF.md J.12).
- `apple-27.0`: X.1–X.7 are new behavior. Apple can adopt them or record them
  as Windows-only.

## Decisions

Settle these before building the sections that depend on them. Tick a
decision once its outcome is written into the section, and into DIFF.md on
`main` when it changes product behavior.

- [ ] **D1 Enhancement presets** (E.2, E.4). Option A can't do Apple's Sharpen
  Only preset or the Sharpness and Denoise sliders. Proposal: keep Apple's
  names and offer Off, Balanced (default), and High Quality, showing High
  Quality only where a GPU denoiser exists (AMD, x64).
  **Outcome:** as proposed. Sharpen Only, Sharpness, and Denoise are hidden.
  DIFF.md row pending on `main`.
- [ ] **D2 Show Original** (E.4). Under Option A it reopens the player, which
  takes about a second. Offer it that way, or leave it out on Windows.
  **Outcome:** offered that way, as a toggle that reopens at the same
  position. It isn't stored. DIFF.md row pending on `main`.
- [ ] **D3 Motion Smoothing** (E.2). It works only on AMD GPUs in x64 builds
  and always doubles the frame rate. 24 → 48 fps still judders on 60 Hz
  displays but is smooth on 144 Hz. Ship it this way for 27.0?
  **Outcome:** shipped this way, for sources at 30 fps or less and
  independent of the preset. X.4's refresh-rate matching helps on 60 Hz
  displays. DIFF.md row pending on `main`.
- [x] **D4 Battery** (E.2). Super Resolution and Motion Smoothing raise GPU
  power. Apply them on battery too (Apple's behavior), or only when plugged
  in?
  **Outcome:** applied on battery too, as on Apple.
- [ ] **D5 Remember enhancement choices** (E.3, E.4). Apple keeps the preset
  and Motion Smoothing in memory for the session only. Persisting them on
  Windows avoids reopening LibVLC between files.
  **Outcome:** persisted device-locally (`video.enhancementPreset` and
  `video.motionSmoothing` in `player-settings.json`). DIFF.md row pending on
  `main`.
- [ ] **D6 Auto picture in picture** (3.3). Windows has no "app went to the
  background" moment. Skip it, or switch to the compact overlay on minimize.
  **Outcome:** skipped. Minimizing never opens the compact overlay. DIFF.md
  row pending on `main`.
- [ ] **D7 Subtitle position** (3.8). LibVLC can't lift subtitles while the
  controls are visible. Accept the overlap, or use a fixed higher margin.
  **Outcome:** the overlap is accepted. DIFF.md row pending on `main`.
- [ ] **D8 Rounded subtitle font** (3.8). Windows ships no rounded font
  family. Bundle an OFL-licensed one (the app already ships OFL fonts), or
  drop Rounded on Windows.
  **Outcome:** Rounded is dropped on Windows, and no font is bundled.
  DIFF.md row pending on `main`.
- [ ] **D9 Google Drive** (3.12). DIFF.md §6.2: accept the desktop client's
  non-confidential secret, or offer Drive only through WebDAV.
  **Outcome:** Drive only through WebDAV (`rclone serve webdav`), so no
  client secret ships. DIFF.md §6.2 answer pending on `main`.
- [ ] **D10 Home-server TLS** (3.12). DIFF.md §6.4: self-signed certificates
  with per-host pinning, or valid HTTPS only.
  **Outcome:** valid HTTPS only. A certificate Windows doesn't trust fails
  with its own message, and plain HTTP reaches only local-network hosts.
  DIFF.md §6.4 answer pending on `main`.
- [x] **D11 Device-local accounts** (3.12). DIFF.md §6.1: confirm, and say so
  in the README.
  **Outcome:** confirmed. Logins and refresh tokens are protected with DPAPI
  on this device, and the README's "Data and privacy" says so.
- [x] **D12 OneDrive** (3.12). Keep the OneDrive storage account separate
  from the existing OneDrive watch-state replica, or link them in the UI.
  **Outcome:** kept separate. Signing the storage account out leaves
  replication alone, and turning replication off leaves the source alone.

## Foundation

### F.1 LibVLC 3.0.24

3.0.24 fixes the Direct3D 11 adjust filter and texture leaks, which 3.7
depends on. It also moves FFmpeg from 4.4 to 8.1 and includes over 130
security fixes.

- [x] Bump `VideoLAN.LibVLC.Windows` from 3.0.23.1 to 3.0.24 in
  `Edendale.Windows/Edendale.Windows.csproj`.
- [x] Update the version in `THIRD-PARTY-NOTICES.md`.
- [x] Confirm the x64 package still has the Option A switches with the
  commands below; each should print a count above zero.
- [ ] Play a local MKV (H.264 and 10-bit HEVC), an AV1 file, an external SRT,
  and a file on an SMB share in the x64, ARM64, and x86 builds. Not run yet:
  needs Windows machines.

```bash
V=~/.nuget/packages/videolan.libvlc.windows/3.0.24/build/x64/plugins
grep -a -c amf_frc "$V/d3d11/libdirect3d11_filters_plugin.dll"
grep -a -c d3d11-upscale-mode "$V/video_output/libdirect3d11_plugin.dll"
```

### F.2 Device-local player settings

One store for the new player settings, so 3.1, 3.3, 3.5–3.8, and E.4 share
loading, normalizing, and saving.

- [x] Add `AppPaths.PlayerSettingsFile` (`player-settings.json`) next to
  `audience.json`. It stays out of the OneDrive replica.
- [x] Add a `PlayerSettingsStore` that uses Apple's key names (for example
  `player.skipBackwardSeconds`), replaces missing or unrecognized values with
  defaults, and writes through a temporary file and a rename.
- [x] Raise a `Changed` event so the player picks up new values at the next
  gesture without restarting playback.
- [x] Tests: defaults, an unreadable file, and unknown values.

### F.3 Reopen at the same position

LibVLC 3 reads video-output options (upscaling mode, video filters, and
subtitle fonts) when it opens the video output, and they appear to come from
the `LibVLC` instance's arguments. Changing one then needs a new instance.
3.8, E.3, and X.3 depend on this.

- [x] First check whether per-media options (`media.AddOption(":…")`) reach
  the video output. If they do, use them instead and skip the new instance.
  They don't: LibVLC 3's video output inherits options from the media player
  and its `LibVLC` instance, not from the input that carries per-media
  options. `MainWindow.EnsureEngine` builds the instance.
- [x] Add a reopen helper in `MainWindow`:
  1. remember the time, rate, and selected tracks;
  2. stop and dispose the `MediaPlayer`, which holds the video output and the
     swapchain;
  3. create the new `LibVLC` with `VideoView`'s swapchain options plus the new
     arguments;
  4. dispose the old instance only afterwards, so LibVLC's plugins stay loaded;
  5. play, then restore the time, rate, and tracks.
- [x] Show a short "Applying…" state instead of a black frame.
- [x] Keep the existing `ReferenceEquals(player, _mediaPlayer)` guards, so the
  stopped player never writes watch progress.

### F.4 Player Adjustments panel

Windows has no Player Adjustments panel yet. 3.3, 3.5–3.7, 3.9, and E.4 add
their controls to it.

- [x] Add `Controls/PlayerAdjustmentsPanel.xaml`, opened from a new toolbar
  button, with the playlist panel's placement and close behavior.
- [x] Section order from DIFF.md §3.9: Video Track, Audio Track, Picture,
  Enhancement, then playback (speed, Skip Prompts, Audio Booster, Loop,
  Fit/Fill).
- [x] Replace the cycling speed button with a speed control on DIFF.md's rate
  grid: 0.25–3.00× in 0.05 steps. Compare rates with a tolerance;
  `PlayerControlsOverlay.SpeedButton_Click` compares floats exactly.
- [x] Every control is reachable with Tab and named for screen readers
  (`AutomationProperties.Name`).

### F.5 Player HUD

Apple shows a small pill near the top for volume, mute, brightness, speed
while holding, and skips. Windows has nothing similar. 3.1, 3.7, and W.2 use
it.

- [x] Add a pill that ignores input (`IsHitTestVisible="False"`), 72 px below
  the top, fading out shortly after the last change. The hold-speed pill stays
  until release.
- [x] Show: volume %, Muted or Unmuted, brightness %, speed (for example 2×),
  and "Back 10 seconds" or "Forward 10 seconds".
- [x] Screen readers hear the same text (a live region).
- [x] Skip the fade when Windows animations are off
  (`UISettings.AnimationsEnabled`).

## Player

### 3.1 App Controls

DIFF.md §3.1. Windows skips a fixed 10 s and has no press-and-hold speed.

- [x] ←/→ skip while the player has focus (fixed at 10 s).
- [x] Add `Core/PlayerControlPreferences.cs`:
  - skip lengths of 10, 15, or 30 s, default 10;
  - hold speeds of 0.25–3.00× in 0.25 steps, defaults 0.5× (left) and 2.0×
    (right);
  - stored values snap to the grid and clamp; a non-finite value becomes
    0.25×;
  - keys `player.skipBackwardSeconds`, `player.skipForwardSeconds`,
    `player.holdLeftRate`, and `player.holdRightRate` in the F.2 store.
- [x] Settings → App Controls: Skip Back and Skip Forward as three-way choices
  with arrow-rotate icons; Hold Left Side and Hold Right Side as number boxes
  in 0.25 steps. Screen readers announce the length, for example "Skip back,
  15 seconds".
- [x] One length drives every skip, read at each gesture:
  - the toolbar buttons (`PlayerControlsOverlay.SkipBack_Click` and
    `SkipForward_Click`);
  - ←/→ (`MainWindow.PlayerOverlay_KeyDown`);
  - media keys (3.18);
  - touch double-taps (below).
- [x] The skip buttons' icons, tooltips, and screen-reader names show the
  current length.
- [x] Holding the left or right half of the video still for 0.4 s plays at
  that side's hold speed, and releasing restores the base rate. A short click
  still shows or hides the controls; a drag does neither. The HUD shows the
  speed (F.5).
- [x] Touch only (`PointerDeviceType.Touch`): double-tapping the left or right
  third skips, and the center plays or pauses. A mouse double-click toggles
  full screen (W.1).
- [x] Tests: port the cases in `PlayerControlPreferencesTests` and the hold
  cases in `PlayerLogicTests`.

### 3.2 Seek and speed checks

DIFF.md §3.2. LibVLC should already keep the last frame; check rather than
build.

- [ ] Rapid play/pause at 0.5×, 1.5×, and 2× keeps the rate and never stalls.
- [ ] A speed change during playback shows no black frame and keeps the pitch.
- [ ] A seek while paused shows the new frame.
- [ ] Switching audio or subtitle tracks keeps the picture until the new frame
  arrives.
- [ ] File an issue for anything LibVLC gets wrong.

Not run yet: these checks need a Windows machine.

### 3.3 Remembered player state

DIFF.md §3.3.

- [x] Progress is saved at least every 10 s (Windows writes every 5 s).
- [x] Loop: a Loop toggle in F.4. At the natural end the file restarts from 0
  instead of finishing. Key `player.loopEnabled`.
- [x] Fit/Fill: store `player.aspectFill` and apply it when playback starts.
- [x] Auto picture in picture: skipped (D6).
- [x] Per title, remember the speed, audio track, subtitle track (or
  subtitles off), and video track:
  - key `player.content.movie.<tmdbId>`, or `player.content.show.<showTmdbId>`
    for episodes, so a whole show shares one entry;
  - files without a TMDB id store nothing.
- [x] Restore them once LibVLC reports the tracks (`ESAdded` or `Playing`):
  - match audio and subtitles by language first, then by name. Read both from
    `Media.Tracks`, because LibVLCSharp's `AudioTrackDescription` has only an
    id and a name;
  - remember only embedded subtitles. Track which subtitle ids came from
    `AddSlave` (downloads and external files) and skip them;
  - match the video track by width × height, and only when the file has more
    than one.
- [x] Tests: key derivation and track matching.

### 3.4 Auto-advance and Up Next

DIFF.md §3.4. Today `MainWindow.MediaPlayer_EndReached` marks the item
complete and closes the player.

- [x] Completion at 95 % (`WatchProgressStore.CompletionThreshold`).
- [x] Add `Core/EpisodeProgression.cs` with a next-episode rule:
  - the stored episode with the smallest (season, episode) strictly after the
    current one, crossing seasons;
  - duplicate files of one episode are skipped;
  - season 0 specials advance among themselves and then into season 1; main
    seasons never fall back to season 0;
  - nothing when the current episode isn't in the show.
- [x] At the natural end (≥ 95 %, or within 2 s of the end), keep the
  completed state and play the next episode instead of closing. A newer manual
  play request cancels a pending advance.
- [x] An Up Next card, top right, during the last 30 s of a TV episode that
  has a stored successor:
  - the episode still (falling back to the show backdrop), the episode code,
    and the title; selecting it plays that episode;
  - recomputed on every progress tick, so seeking back hides it;
  - hidden with Loop on, for movies, when the duration is unknown, and on the
    last stored episode;
  - reachable with the keyboard while controls are visible, and still when
    Windows animations are off.
- [x] Continue Watching on the Downloaded page: for a show with no episode in
  progress, suggest the stored episode after the furthest completed one.
  - It works after the watched file is deleted.
  - It never writes watch progress.
  - Duplicate show records give one card.
- [x] Tests: port `EpisodeProgressionTests`, `UpcomingEpisodePreviewTests`,
  `ContinueWatchingTests`, and `PlayerSessionTransitionTests`.

### 3.5 Skip prompts

DIFF.md §3.5 (TheIntroDB). An opt-in network feature. Windows never had the
old timed auto-skip, so there is nothing to remove.

- [x] Setting `player.segmentPromptsEnabled`, default off, in Settings → Skip
  Prompts and Player Adjustments → Skip Prompts. The setting says that the
  TMDB id, season and episode, duration, and the device's IP address go to
  TheIntroDB.
- [x] `Services/IntroDbClient.cs` sends `GET https://api.theintrodb.org/v3/media`
  with:
  - `tmdb_id`, plus `season` and `episode` for episodes (the show's TMDB id
    and numbering), and `duration_ms`;
  - `Accept: application/json`, an 8 s timeout, no cookies
    (`UseCookies = false`), and no disk cache.
- [x] Skip the lookup when the TMDB id is outside 1…10,000,000, the season or
  episode is ≤ 0, or the duration is unknown, ≤ 0, or over 21,600 s.
- [x] Responses:
  - 404 means no segments;
  - 429 backs off for the largest of `Retry-After`, `X-RateLimit-Reset`, and
    `X-UsageLimit-Reset`, at least 60 s;
  - reject a response whose `tmdb_id`, `type`, `season`, or `episode` doesn't
    match the request.
- [x] Decode `intro`, `recap`, and `credits` as DIFF.md describes. Keep
  segments with 0 ≤ start < end ≤ duration; a null credits end reaches the end
  of the file; drop segments that overlap each other.
- [x] The prompt:
  - shows Skip Intro, Skip Recap, or Skip Credits at the bottom right while
    playback is inside a segment, even with the controls hidden;
  - is hidden while scrubbing or while a panel covers the video (a docked
    panel doesn't);
  - is activated with **S**;
  - stays hidden after a press until playback leaves that segment;
  - never skips on its own.
- [x] A bounded segment seeks to its end. Credits that reach the end mark the
  item complete, then advance (3.4), close the player, or restart with Loop on.
- [x] Cache in memory only, at most 12 entries, cleared on close or when the
  setting is turned off. Playback never waits for the lookup.
- [x] Add TheIntroDB to the README's "Data and privacy" network list (AGENTS.md
  hard constraint 4).
- [x] Tests: port `IntroDBTests` using a stub `HttpMessageHandler`.

### 3.6 Audio enhancement

DIFF.md §3.6. LibVLC's equalizer uses the same ten bands (60, 170, 310, and
600 Hz; 1, 3, 6, 12, 14, and 16 kHz) and the same ±20 dB range.

| Profile | Preamp | Bands (dB) |
|---|---|---|
| Flat | 0 | 0 0 0 0 0 0 0 0 0 0 |
| Movies (default) | −8 | 8 5 3 0 0 2 3 2 1 0 |
| Music | −4 | 4 2 0 −1 −1 2 3 3 2 1 |
| Dialogue | −6 | −3 −1 0 5 6 5 3 1 0 −1 |
| Night Mode | −5 | −5 −2 1 4 5 5 3 1 0 −1 |

- [x] `Core/AudioEnhancement.cs` holds the profiles above. User adjustments to
  the preamp and each band are stored separately and added to the profile.
  Changing the profile resets them, and every value clamps to −20…+20 dB.
- [x] Audio Booster, off by default, adds +10 dB of preamp through the same
  equalizer (not the volume) and restores the previous value when turned off.
- [x] Keys `audio.enhancementProfile`, `audio.enhancementPreamp`,
  `audio.enhancementBands`, and `audio.boosterEnabled`.
- [x] Apply with `new Equalizer()`, `SetPreamp`, `SetAmp(dB, band)` for bands
  0–9, and `MediaPlayer.SetEqualizer`. Call `UnsetEqualizer()` when the
  effective preamp and every band are 0. Apply changes live and to every new
  `MediaPlayer`.
- [x] Settings → Audio Enhancement (profile, preamp, ten bands, and reset), and
  Player Adjustments → Audio Booster.
- [x] Night Mode is a frequency balance, not a compressor. Keep LibVLC's
  `compressor` filter out of it.
- [x] Tests: port `AudioEnhancementTests`.

### 3.7 Picture adjustments

DIFF.md §3.7. Needs F.1, which fixes LibVLC's Direct3D 11 adjust filter.

| Adjustment | Range | Neutral | Step |
|---|---|---|---|
| Brightness | 0–2 | 1 | 0.05 |
| Contrast | 0–2 | 1 | 0.05 |
| Gamma | 0.25–3 | 1 | 0.05 |
| Saturation | 0–3 | 1 | 0.05 |
| Hue | 0–360° | 0 | 5° |

- [x] `Core/VideoAdjustments.cs` holds the table above, stored as one JSON
  value `video.adjustments` and normalized on load.
- [x] Apply with `SetAdjustInt(VideoAdjustOption.Enable, 1)` and
  `SetAdjustFloat(...)`. LibVLC's hue runs −180…180, so send `h − 360` when
  `h > 180`. Neutral values turn the filter off, and leaving the player
  restores neutral.
- [x] Player Adjustments → Picture: a slider per row, Show Original (neutral
  values without changing the stored ones), and Reset.
- [x] Ctrl+↑/↓ change brightness by one step and show the HUD (F.5), like
  ⌘↑/↓ on macOS.
- [ ] Soak test: two hours with adjustments on, and memory use stays flat.
  Not run yet: needs a Windows machine.
- [x] Tests: ranges, normalization, and hue mapping.

### 3.8 Subtitle appearance

DIFF.md §3.8. LibVLC 3 draws subtitle text itself and doesn't hand subtitle
text to the app, so a XAML overlay could only cover files Edendale reads
itself. Use LibVLC's text-renderer options for every text track instead.
They're `LibVLC` instance arguments, applied through F.3.

- [x] `Core/SubtitleAppearance.cs` with named presets, not a color picker:
  - font: System (Segoe UI), Serif (Georgia), and Monospaced (Consolas).
    Rounded is dropped (D8);
  - text: Parchment (default, `#E4E1E9`), White, Yellow `#FFE033`, Cyan
    `#59E6FF`, Green `#73F273`, and Black;
  - box: Ink (default, `#0A0A0F`), Black, Charcoal `#383838`, Navy `#0F1A3D`,
    and White;
  - box opacity 0–1, default 1, stored in whole percent. At 0 the box is
    removed and the outline keeps text legible;
  - outline: light around Black text, `#0A0A0F` around everything else;
  - keys `subtitles.font`, `subtitles.textColor`, `subtitles.backgroundColor`,
    and `subtitles.backgroundOpacity`.
- [x] Map them to LibVLC arguments:
  - `--freetype-font`, `--freetype-color`, `--freetype-background-color`, and
    `--freetype-background-opacity`;
  - `--freetype-outline-color` and `--freetype-outline-thickness`;
  - `--freetype-rel-fontsize` for size (picture height ÷ value, so 18 is about
    5.5 %). DIFF.md's 16–48 pt clamp can't be expressed; check 720p, 1080p,
    and 4K (not run yet);
  - `--sub-text-scale` from Windows' text size (`UISettings.TextScaleFactor`).
- [x] Settings → Subtitles with a preview. During playback, changes apply
  through F.3.
- [x] Placement: the overlap with the controls is accepted (D7).
- [ ] Bitmap subtitles (PGS, VobSub) keep their authored pixels. LibVLC
  already does this; check it.
- [ ] Check external SRT, WebVTT, and ASS files with CRLF line endings and
  UTF-16 with a BOM. Wyzie downloads are already rewritten as UTF-8.
  Not run yet, like the bitmap check above: both need a Windows machine.
- [x] Tests: port `SubtitleAppearanceTests` (keys and opacity snapping), plus
  the mapping to LibVLC arguments.

### 3.9 Track pickers

DIFF.md §3.9. Today the subtitles flyout lists audio tracks by name when a file
has more than one.

- [x] Audio tracks can be chosen from the subtitles flyout.
- [x] Player Adjustments → Video Track, only when the file has more than one:
  `name (language) — W×H`.
- [x] Player Adjustments → Audio Track, only when the file has more than one:
  `name (language) — Mono | Stereo | 5.1 | 7.1 | Nch`. Append the language
  only when the name doesn't already contain it. Channels and sizes come from
  `Media.Tracks`.
- [x] Once the panel has audio tracks, remove them from the subtitles flyout.
  Subtitles and the online search stay there.
- [x] Tests: label formatting (channel names and the language rule).

### 3.10 Playlist redesign

DIFF.md §3.10.

- [x] Add the tokens `PlaylistActiveBackground` `#FFFFFF` and
  `PlaylistActiveText` `#000000` to `DESIGN.md`, and as
  `EdendalePlaylistActiveBackgroundColor`/`Brush` and
  `EdendalePlaylistActiveTextColor`/`Brush` in `App.xaml`. `main`'s DESIGN.md
  needs the same rows in a separate change.
- [x] The current or focused row has a white fill, black text, and a larger
  title. A playing indicator tells the current file apart from a focused row.
- [x] The panel opens scrolled to the current file.
- [x] Identified episodes, and the current identified movie, show landscape
  artwork with the title and play time stacked. Unidentified files keep the
  file name.

### 3.11 Play From

DIFF.md §3.11. The Windows library keeps one record per file, so one TMDB id
can already have several copies.

- [x] `Core/PlaybackSources.cs` orders the copies: the page's own copy first,
  then local copies, then the rest by folder name (culture-aware compare). Play
  starts the first copy whose source isn't offline or waiting for sign-in
  (3.12), or the first copy when every source is.
- [x] Movies: an icon-only Play From menu beside Play on `DetailPage`.
- [x] Episodes: a Play From section in the episode's context menu, and the
  subtitle adds "· N sources".
- [x] Each menu row shows the source name, then `Kind · filename`, plus
  "· Unavailable" for an offline source.
- [x] Season lists merge every copy of the show into one list keyed by
  (season, episode), in airing order.
- [x] Tests: port `PlaybackSourcesTests`.

## Library and sources

### 3.12 Storage providers

DIFF.md §3.12 and apple-27.0 ENHANCEMENT.md Section J. Staged: library
plumbing first, then WebDAV, SFTP, and S3, then OneDrive and Dropbox, then
Google Drive after D9.

**Stage 1: library plumbing**

- [x] Local folders and SMB shares (UNC paths, logins protected with DPAPI).
- [x] Canonical, credential-free item URLs and account keys from DIFF.md
  (`<account>` is the first 32 hex digits of `SHA-256("<kind>:<subject>")`).
  Every URL ends with the real file name, so filename parsing still runs
  first.
- [x] New nullable `LibraryFolder` fields: `DisplayPath`, `AccountKey`,
  `LastScannedAt`, and `ChangeCursor`.
- [x] Automatic rescans skip remote sources scanned in the last 15 minutes;
  a manual Rescan always scans.
- [x] Each source records its own failure (offline, or needs sign-in), shown
  on its row and cleared by the next successful scan.
- [x] Settings → Accounts lists saved logins and accounts with the number of
  sources using each. Removing a source never deletes its login.
- [x] Connectors provide `Validate`, `List`, and `EnumerateVideos`.
  Enumeration is breadth-first, stops at 2,000 folders, and skips dot-files. A
  failure at the root throws; a deeper failure skips only that branch.

**Stage 2: streaming**

- [x] One random-access byte source per HTTP provider behind LibVLCSharp's
  `MediaInput`, so tokens and signed links never reach LibVLC:
  - 4 MiB `Range` reads, prefetching while reads are sequential, with 8
    chunks cached;
  - DIFF.md's response table: 206, 200, 401 refresh, 403/429/5xx backoff,
    410 new link, and the 404 message.
- [x] Never log URLs, tokens, or headers.

**Stage 3: providers**

- [x] WebDAV: `PROPFIND Depth: 1`, Basic or Digest login.
- [x] SFTP with trust on first use: show the SHA-256 fingerprint and key type,
  pin it, and refuse a changed key until the user approves it again. Candidate
  library: SSH.NET (MIT); check its key-exchange and cipher list against
  DIFF.md.
- [x] S3-compatible: SigV4 signing, `ListObjectsV2`, and pre-signed GETs
  re-signed after a 403.
- [x] OneDrive and Dropbox: OAuth authorization code with PKCE through the
  system browser and a loopback redirect (`http://127.0.0.1:<port>`).
  - Refresh tokens are stored with DPAPI; access tokens stay in memory.
  - A single refresh serves every waiting request.
- [x] Google Drive: through WebDAV with `rclone serve webdav` (D9).
- [x] NFS: through LibVLC (X.5).

**Stage 4: docs and secrets**

- [x] Client IDs and app keys come from the gitignored `secrets.json` through
  `tools/Edendale.Secrets`. An empty value hides that provider.
- [x] README: the supported providers, and the outcomes of D10, D11, and D12.
- [ ] Ask for the matching changes on other branches (DIFF.md J.12): privacy
  text on `web`, and the provider list in `main`'s README. Listed under
  [Open items](#open-items); this branch can't change them.
- [x] Tests: port `ConnectorTests`, `CloudListingTests`, `OAuthTests` (with the
  RFC 7636 Appendix B vector), `RemoteByteSourceTests`, and the
  `SFTPProtocolTests` fingerprint cases (matching `ssh-keygen -l`). Use a stub
  HTTP handler and local fixtures, never real credentials.

### 3.13 Buffered remote reads

DIFF.md §3.13.

- [ ] First measure UNC throughput over a VPN or Tailscale; Windows' SMB
  client has its own read-ahead. Not run yet: SMB stays on Windows' client
  until it's measured.
- [x] For `MediaInput` sources (SFTP and the others), a worker reads 1 MiB
  chunks up to 48 MiB ahead, within a 64 MiB cache. The chunk a blocked read
  needs always goes first.
- [x] After a failure, reconnect with delays of 0.25, 0.5, 1, 2, 4, and 8 s.
  Send a keep-alive after 20 s idle. Cancellation fails blocked reads at once,
  and the error names the host.
- [x] Tests: port `BufferedByteSourceTests`.

### 3.15 Desktop navigation and shortcuts

DIFF.md §3.15.

- [x] Sidebar order: Movies & Shows, Watchlist, Downloaded, Search, then
  Settings.
- [x] The Continue Watching shelf stops at 12 items.
- [x] Watchlist and Downloaded expand into child rows (NavigationView
  `MenuItems`):
  - Watchlist: Movies and TV Shows;
  - Downloaded: Continue Watching, Movies, and TV Shows;
  - a child row shows only while its section has titles for the current
    audience setting, and if the open section empties, the sidebar returns to
    the parent page.
- [x] Choosing a row opens that page at its root, and the search query
  survives navigation.
- [x] A Continue Watching page lists every resumable title. The Movies page
  includes movies that are also in Continue Watching.
- [x] Shortcuts:
  - Ctrl+B toggles the sidebar;
  - Ctrl+N adds a media folder and Ctrl+Alt+N links a network source (on the
    Downloaded pages);
  - Ctrl+R or F5 rescans (when a source is linked).
- [x] The Link Source dialog focuses the address on open. Enter connects when
  every field is filled and otherwise moves to the first empty field. A guest
  connection (no username or password) is one click.
- [x] The playlist and Player Adjustments dock as a trailing panel (an inline
  `SplitView`) that narrows the video.
  - Clicking the video keeps the panel open.
  - Esc closes the panel; a second Esc leaves the player.
  - Controls still auto-hide, and Up Next and skip prompts stay available.
- [x] On season shelves, the heading rule doubles as the shelf's scroll
  indicator and scrubber: drag the gold thumb or click the rule.
- [x] Tests: port `LibrarySectionsTests`.

### 3.19 Settings layout

DIFF.md §3.19.

- [x] Order: About, Audience, Startup, Audio Enhancement, Subtitles, Skip
  Prompts, App Controls, Sources, Accounts, TMDB Account, then the
  Windows-only Your Archive and Attribution.

## Video enhancement (Option A)

DIFF.md §3.16–3.17, built only from what LibVLC 3 ships. Confirmed in LibVLC
3.0.23.1 and again in 3.0.24:

| LibVLC switch | What it does | Where it works |
|---|---|---|
| `--d3d11-upscale-mode=linear` | Bilinear scaling (LibVLC's default) | Everywhere |
| `--d3d11-upscale-mode=processor` | The graphics driver's video-processor scaler | Any hardware GPU |
| `--d3d11-upscale-mode=super` | AI super resolution: NVIDIA RTX Video Super Resolution, Intel VSR, or AMD's AMF VideoSR | NVIDIA RTX with a recent driver, Intel Xe or Arc, and AMD in x64 builds; only while the video is smaller than the window |
| `--video-filter=amf_vqenhancer` | AMD denoise and compression-artifact removal | AMD, x64 builds, hardware-decoded video |
| `--video-filter=amf_frc` | AMD Frame Rate Doubler: 2× motion interpolation | AMD, x64 builds, hardware-decoded video |

Not possible under Option A: HDR output and NVIDIA's SDR-to-HDR
(`--d3d11-hdr-mode`), because `VideoView`'s swapchain is 8-bit; Sharpness and
Denoise sliders; and frame generation on NVIDIA, Intel, or Snapdragon.

### E.1 GPU capability probe

- [x] `Core/GpuCapabilities.cs` is a pure model. It takes the vendor, driver
  version, process architecture, and AMF availability, and returns
  `SuperResolution` (yes, maybe, or no), `MotionSmoothing`, `GpuDenoise`, and
  `VideoProcessor`.
- [x] `Services/GpuProbe.cs` reads the default DXGI adapter, the one
  `VideoView` creates its device on. SharpDX.DXGI is already in the package
  graph through LibVLCSharp.WinUI, so no new dependency is needed.
  - Vendor ids: NVIDIA `0x10DE`, AMD `0x1002`, Intel `0x8086`. The software
    adapter (`0x1414`/`0x8C`, or the software flag) turns everything off.
  - NVIDIA: VLC enables super resolution when the driver version's last two
    parts give `third × 10000 + fourth > 153000` (31.0.15.3118 gives 153118,
    roughly driver 530 and later). Mirror that check.
  - Intel: VLC can't detect support and always tries, so report "maybe". It
    needs an 11th-gen Core (Xe) GPU or Arc.
  - AMD: only in x64 processes (`RuntimeInformation.ProcessArchitecture`).
    `amfrt64.dll` must load (`NativeLibrary.TryLoad`). VLC's source notes AMF
    1.4.34 for the doubler; read it with `AMFQueryVersion`.
- [x] Probe once per launch, off the UI thread, and cache the result.
- [x] Hybrid laptops: if a dedicated GPU exists but isn't the default adapter,
  flag it for E.4.
- [x] Tests: each vendor, the driver boundary, ARM64 and x86 without AMD
  features, and the software adapter.

### E.2 Option builder

A pure function turns the settings, capabilities, and source into LibVLC
arguments and the labels E.4 shows, so every fallback is unit-tested.

Proposed mapping (confirm with D1):

| Preset | LibVLC arguments |
|---|---|
| Off | `--d3d11-upscale-mode=linear` |
| Balanced (default) | `super` when supported or "maybe", otherwise `processor`; `linear` on the software adapter |
| High Quality | Balanced plus `amf_vqenhancer`, where available |

- [x] `Core/VideoEnhancementOptions.cs`:
  - takes the preset (D1), Motion Smoothing on or off, the power state (D4),
    the capabilities (E.1), and the source's width, height, and frame rate;
  - returns the argument list, the labels, and which controls to show.
- [x] Motion Smoothing adds `amf_frc` only when it's available and the source
  runs at 30 fps or less (Apple's limit). An unknown frame rate means no
  Motion Smoothing. Filter order: `--video-filter=amf_vqenhancer:amf_frc`.
- [x] The same inputs always give the same list, so E.3 can compare lists to
  decide whether LibVLC needs reopening.
- [x] Labels: "1280×720 → 3840×2160" when Super Resolution applies (the source
  is smaller than the fitted video area in physical pixels), and
  "24 fps → 48 fps" with Motion Smoothing.
- [x] Tests: every row above, each fallback, the 30 fps limit, filter order,
  and stable output.

### E.3 Applying the options

- [x] Before playing, read the source's size and frame rate with
  `Media.Parse(MediaParseOptions.ParseLocal)` and a short timeout.
- [x] Build the arguments (E.2). If they differ from the running instance's,
  reopen through F.3 before playing; otherwise reuse the instance.
- [x] A change in Player Adjustments reopens at the same position (F.3).
- [x] Debug builds add `--frc-indicator`, AMD's on-screen marker, to show the
  doubler running.
- [x] `amf_frc` and `amf_vqenhancer` need hardware-decoded frames, so they do
  nothing for files LibVLC decodes in software. Decide how the label reports
  that, for example by watching LibVLC's log for the filter starting.
  Decided: the labels show what was requested, and the README says the AMD
  filters do nothing for software-decoded files. LibVLC's log isn't
  watched.

### E.4 Enhancement controls

- [x] Player Adjustments → Enhancement: a Preset menu (D1), plus a Motion
  Smoothing toggle when E.1 allows it. The toggle shows only for sources at
  30 fps or less.
- [x] The resolution label sits under the preset, and the frame-rate label
  under Motion Smoothing (E.2).
- [x] Show Original reopens at the same position (D2).
- [x] Hide what Option A can't do: Sharpen Only, Sharpness, Denoise, and
  Apple's MetalFX interpolator toggle.
- [x] If E.1 flagged a hybrid laptop running on its integrated GPU, explain
  that Super Resolution needs Edendale set to "High performance" in Windows
  Settings → System → Display → Graphics.
- [x] Persist the choices device-locally (D5).

### E.5 Hardware test matrix

On each machine, check Off, Balanced, High Quality, and Motion Smoothing with
SDR 720p and 1080p files, an HDR10 file, and a 60 fps file, then tick it.
Not run yet: none of this hardware was available.

- [ ] NVIDIA RTX: Super Resolution is visibly sharper on 720p in a 4K window,
  and the quality level follows the NVIDIA app.
- [ ] NVIDIA GTX: falls back to the video processor.
- [ ] AMD Radeon, x64: Super Resolution, High Quality denoise, and Motion
  Smoothing doubling 24 fps.
- [ ] AMD integrated graphics (680M or 780M class): the same, plus a
  dropped-frame check, because VLC uses AMD's full-quality motion search.
- [ ] Intel Xe or Arc: Super Resolution on SDR video up to 1080p.
- [ ] Intel UHD (before Xe): nothing fails, and it looks like the video
  processor.
- [ ] Snapdragon X (ARM64): video processor only, and no AMD controls.
- [ ] x86 build: no AMD controls.
- [ ] Software adapter (a VM or Remote Desktop): enhancement is off, and
  playback works.
- [ ] Laptop on battery: behaves as D4 decided.
- [ ] HDR10 file on Balanced: still plays, tone-mapped to SDR as today.

### E.6 Documentation

- [x] README "Video enhancement": the presets, what each GPU gets, Motion
  Smoothing's AMD x64 limit, the hybrid-laptop setting, and HDR staying SDR.
- [ ] Record the Windows differences from §3.16–3.17 (D1–D5) in DIFF.md on
  `main`. Listed under [Open items](#open-items).

## System integration and Windows gaps

### 3.18 Media keys and audio output

DIFF.md §3.18.

- [x] Picture in picture through the compact-overlay window
  (`MainWindow.SetCompactOverlay`).
- [x] System Media Transport Controls through
  `SystemMediaTransportControlsInterop.GetForWindow`:
  - play, pause, and next and previous episode;
  - rewind and fast-forward by the App Controls lengths (3.1);
  - the title, episode, and artwork in the Windows media flyout;
  - media keys and headset buttons.
- [x] An Audio Output picker in the player toolbar
  (`MediaPlayer.AudioOutputDeviceEnum` and `SetOutputDevice`). When no device
  is chosen, follow Windows' default device.

### W.1 Full screen

Apple's player toggles full screen with **F**. Windows only fills the window.

- [x] F, F11, and a mouse double-click on the video toggle
  `AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen)`, and the toolbar
  gets a full-screen button.
- [x] Esc peels back one layer at a time: an open panel, then full screen (or
  the compact overlay), then the player.
- [x] Leaving the player restores the previous window state.

### W.2 Volume and mute

On Apple, ↑/↓ change the volume in 5 % steps, M mutes, and any volume change
unmutes. Windows has no volume control in the player.

- [x] ↑/↓ and the mouse wheel over the video change `MediaPlayer.Volume` in
  5 % steps. M toggles mute, and any volume change unmutes. The HUD shows each
  change (F.5).
- [x] The toolbar gets a volume slider and a mute button.
- [x] The level carries over to the next file in the session.

### W.3 Keep the display awake

Nothing in the Windows branch prevents sleep during playback. LibVLC's own
screen-saver block needs a video window, which the WinUI swapchain path
doesn't create (check this).

- [x] Call `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED |
  ES_SYSTEM_REQUIRED)` while playing, and clear it on pause, at the end, and
  on close.
- [ ] Check: with a 5-minute screen timeout, a two-hour file plays without the
  display sleeping, and the display can sleep while paused. Not run yet:
  needs a Windows machine.

## Windows extras

New behavior with no Apple counterpart. Under AGENTS.md hard constraint 7,
name the other affected branches before shipping any of these.

### X.1 Xbox controller

- [x] `Windows.Gaming.Input.Gamepad` while the player has focus: A plays or
  pauses, the bumpers skip, the D-pad seeks, and B backs out like Esc.
- [x] Triggers drive the hold speeds. Pressing past 0.55 starts that side's
  hold speed, and easing below 0.30 releases it (the thresholds Apple uses for
  the Siri Remote's touch surface).

### X.2 Taskbar

- [x] Previous, play/pause, and next buttons on the taskbar thumbnail
  (`ITaskbarList3`).
- [x] A jump list of Continue Watching items using the existing `edendale://`
  routes (packaged builds).

### X.3 Headphone surround

- [x] A Headphone Surround toggle for 5.1 and 7.1 audio using LibVLC's binaural
  renderer (`--spatialaudio-headphones`, with the HRTF file the build already
  ships in `hrtfs/`), applied through F.3. It's off by default.
- [ ] Compare it with Windows Sonic before choosing a default. Not run yet:
  needs a Windows machine and headphones.

### X.4 Refresh-rate matching

- [x] In full screen, switch the display to a multiple of the source rate
  (for example 24p at 48, 72, or 120 Hz) when the monitor offers one, and
  restore it on exit. This removes judder at no GPU cost and helps Motion
  Smoothing.

### X.5 NFS through LibVLC

- [x] Windows has no NFS client by default, but LibVLC bundles one (`libnfs`).
  Try playing `nfs://` URLs and listing folders by parsing a folder URL. The
  export still needs the `insecure` option. `Services/Remote/NfsConnector.cs`
  lists by parsing the folder URL and plays the `nfs://` URL directly.
- [ ] Try it against a real export. Not run yet: no NFS server was
  available.

### X.6 Audio and subtitle delay

- [x] Per-file audio and subtitle offsets in Player Adjustments
  (`SetAudioDelay` and `SetSpuDelay`), not remembered between files.

### X.7 Chapters

- [x] Chapter marks on the timeline and a chapter list
  (`FullChapterDescriptions`).

## Not planned

Option A was chosen over these for 27.0. Revisit them if its limits matter.

- **Own swapchain:** needed for HDR output and NVIDIA's SDR-to-HDR.
  LibVLCSharp's `VideoView` creates an 8-bit swapchain and VLC keeps that
  format, so HDR video is tone-mapped to SDR.
- **Shader pipeline after LibVLC:** CAS sharpening, temporal denoise, FSR 1
  upscaling, and a port of Apple's Motion Smoothing for every GPU. Embedded
  subtitles would be interpolated along with the picture.
- **Custom LibVLC filter plugin:** runs where `amf_frc` does, but builds
  against VLC-internal headers and must be rebuilt for every LibVLC update.
- **Engine change:** LibVLC 4 (unreleased), or FFmpeg with Edendale's own
  renderer.
- **Windows ML models:** RIFE or super-resolution models on GPUs and NPUs.
  Real-time performance is unproven.
- **Vendor SDKs outside LibVLC:** NVIDIA Optical Flow frame-rate up-conversion,
  Intel VPL AI frame interpolation and super resolution, and AMD AMF used
  directly.
- **3.14 TV sign-in:** no Windows counterpart.
- **DIFF.md §4:** Apple-only work.

## Test parity

From DIFF.md §5. Tick a line when its cases are ported to
`Edendale.Windows.Tests` and pass.

- [x] `PlayerControlPreferencesTests` (3.1)
- [x] `PlayerLogicTests`: hold side and rate grid (3.1, F.4)
- [ ] `PlayerTransportStateTests`: covered by the 3.2 checks (not run yet)
- [x] `EpisodeProgressionTests` (3.4)
- [x] `UpcomingEpisodePreviewTests` (3.4)
- [x] `ContinueWatchingTests` (3.4)
- [x] `PlayerSessionTransitionTests` (3.4)
- [x] `IntroDBTests` (3.5)
- [x] `AudioEnhancementTests` (3.6)
- [x] `SubtitleAppearanceTests` (3.8)
- [ ] `PlayerSubtitleOverlayTests` and `SubtitleEngineTests`: covered by the
  3.8 file checks, since LibVLC draws subtitles (not run yet)
- [x] `PlaybackSourcesTests` (3.11)
- [x] `LibrarySectionsTests` (3.15)
- [x] `ConnectorTests`, `CloudListingTests`, `OAuthTests`, and
  `RemoteByteSourceTests` (3.12)
- [x] `SFTPProtocolTests`: fingerprint cases only (3.12)
- [x] `BufferedByteSourceTests` (3.13)
- [x] Windows only: `GpuCapabilitiesTests` and `VideoEnhancementOptionsTests`
  (E.1, E.2)
