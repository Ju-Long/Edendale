# DIFF: `apple` → `apple-27.0`

A porting guide to everything the `apple-27.0` branch added on top of `apple`,
written so the same product behavior can be explored natively on the `android`
and `windows` branches.

- **Range:** `git log apple..apple-27.0`, 27 commits from 2026-09-12 to
  2026-10-01 (`d60ff9e` → `4f8383a`). Merge base: `e538b6a`.
- **Size:** 199 files changed, about 38,900 insertions and 1,300 deletions.
  Roughly a third is tests; the deep design notes are in
  [ENHANCEMENT.md](ENHANCEMENT.md) (Sections A–J).
- **Nothing here is shared code.** AGENTS.md hard constraints 1 and 7 still
  apply: each platform reimplements and tests these rules natively. Apple
  file paths are references for behavior, constants, and test cases only.

> To get your bearings: read **§1** to see where each platform stands, scan
> the catalogue in **§2**, then go to the feature sections you want to port.
> Every feature section lists its product rules, the Apple sources, and a
> native approach for Android and for Windows.

---

## 1. Where each platform stands

| | Apple (`apple-27.0`) | Android (`origin/android`, 2026-08-10) | Windows (`origin/windows`, 2026-08-26) |
|---|---|---|---|
| Playback engine | AVFoundation for MP4/MOV, FFmpeg 7.1.1 for everything else, its own Metal renderer (SwiftVLC removed) | Media3 ExoPlayer 1.7.1 | LibVLCSharp.WinUI 3.10.1 + LibVLC 3.0.23 |
| SMB | libsmb2 through FFmpeg custom I/O, buffered | jcifs-ng + `SmbDataSource` | `NetworkShare.cs` (UNC) |
| Other remote sources | NFS, SFTP, WebDAV, S3, Google Drive, OneDrive, Dropbox | none | none (OneDrive is used only to sync watch state) |
| Skip intro/credits | Opt-in TheIntroDB prompts; the old timed auto-skip was removed | Still has the old auto-skips (`RECAP_LENGTH_MILLIS` 90 s, `CREDITS_LENGTH_MILLIS` 180 s) | none |
| Press-and-hold speed | Configurable per side, defaults 0.5× and 2.0× | Fixed `HOLD_SLOW_RATE` 0.5, `HOLD_FAST_RATE` **1.5** | none |
| Rate grid | 0.25–3.00× in 0.05 steps | same (`RATE_STEP` 0.05) | discrete switch in `PlayerControlsOverlay` |

What this means for porting:
- **Windows:** LibVLC already does most of what Apple had to build itself
  (container and codec coverage, equalizer, picture adjustments, subtitle
  formats). Windows mostly needs the *product rules*, not the pipeline.
- **Android:** Media3 covers most of it, but audio EQ, picture adjustments,
  upscaling, and DTS/TrueHD audio need Media3 extension points (custom
  `AudioProcessor`, video `Effect`s, the FFmpeg decoder extension).
- **The Apple equalizer bands and picture-adjustment ranges come from
  VLC.** They match LibVLC's 10-band equalizer and `adjust` filter exactly,
  so Windows can map them almost one to one.

---

## 2. Feature catalogue

Priority is a suggestion: **P1** is user-visible product behavior that should
reach parity, **P2** is worthwhile, and **P3** is optional or platform-bound.

| # | Feature | Kind | Android | Windows | Prio |
|---|---|---|---|---|---|
| 3.1 | App Controls: skip lengths and hold speeds | Product rule | Port | Port | P1 |
| 3.2 | Speed changes and seeks that never blank the picture | Playback quality | Check Media3 | Check LibVLC | P2 |
| 3.3 | Persisted loop, aspect fill, auto-PiP, per-title track/speed memory | Product rule | Port | Port | P1 |
| 3.4 | Episode auto-advance, Up Next card, Continue Watching next-up | Product rule | Port | Port | P1 |
| 3.5 | Skip prompts via TheIntroDB (replacing the timed auto-skip) | Product rule plus a network service | Port, **remove old auto-skip** | Port | P1 |
| 3.6 | Audio enhancement: 5 EQ profiles and a booster | Product rule plus DSP | Custom `AudioProcessor` | LibVLC `Equalizer` | P1 |
| 3.7 | Picture adjustments (brightness/contrast/gamma/saturation/hue) | Product rule plus video filter | Media3 effects | LibVLC `adjust` | P2 |
| 3.8 | Subtitle appearance and overlay placement | Product rule | `CaptionStyleCompat` | Overlay or freetype options | P1 |
| 3.9 | Video/audio track pickers in Player Adjustments | UI | Port | Port | P1 |
| 3.10 | Playlist panel redesign (new tokens) | Design | Port | Port | P2 |
| 3.11 | "Play From": several copies of one title | Product rule | Port | Port | P2 |
| 3.12 | Storage providers (Section J) | Major feature | Port | Port | P1 (staged) |
| 3.13 | Buffered remote reads (read-ahead, reconnect, keep-alive) | Playback quality | Port to `DataSource` | Port to `MediaInput` | P1 with 3.12 |
| 3.14 | TV sign-in for cloud accounts | Platform flow | Android TV | n/a | P2 |
| 3.15 | Desktop navigation, shortcuts, docked panels | Desktop UX | Large screens | Port | P2 |
| 3.16 | GPU upscaling, sharpening, denoise | Enhancement | Custom `GlEffect` | d3d11 upscale option | P3 |
| 3.17 | Frame interpolation ("Motion Smoothing") | Enhancement | Research | No native path | P3 |
| 3.18 | System integration: PiP, Now Playing, audio route, volume HUD | Platform | Mostly built in | SMTC / CompactOverlay | P2 |
| 3.19 | Settings page layout | Design | Optional | Optional | P3 |
| 4 | Apple-only work (no port) | — | — | — | — |

---

## 3. Features

### 3.1 App Controls: skip lengths and hold speeds

**Apple:** `Shared/Views/Player/PlayerControlPreferences.swift`,
`Shared/Views/Settings/AppControlsSection.swift`,
`PlayerChromeModel.skip(_:)` / `beginHold(on:)`, `PlayerPointerSurface.swift`
(macOS), `PlayerLogic.holdSide(x:y:active:)` (tvOS).
Tests: `PlayerControlPreferencesTests`, `PlayerLogicTests`.

Product rules:
- **Settings → App Controls** has four settings: **Skip Back**, **Skip
  Forward**, **Hold Left Side**, and **Hold Right Side**.
- Skip lengths are an enum of 10, 15, or 30 s, default 10. Each is shown as a
  segmented control with the matching arrow-rotate glyph, and screen readers
  announce the length.
- One skip length drives every skip: double-tap on either side of the video,
  Left/Right arrow keys, remote swipes and the TV timeline, accessibility skip
  actions, and the lock-screen/system skip buttons. The system buttons relabel
  as soon as a length changes. (Apple's PiP keeps the system's own skip
  length.)
- Hold speeds range from 0.25× to 3.00× in **0.25 steps** (not the 0.05 rate
  grid). Defaults: left 0.5×, right 2.0×. Stored values snap to the grid and
  clamp to the range; a non-finite value becomes the minimum.
- A hold applies a temporary override over the base rate and reverts on
  release. A short click still toggles controls; a drag does neither.
- The player reads preferences at each gesture, so a change applies without
  restarting playback. They are stored on the device and never synced.
- Storage keys: `player.skipBackwardSeconds`, `player.skipForwardSeconds`,
  `player.holdLeftRate`, `player.holdRightRate`. A missing or unrecognized
  value falls back to the default.

Android:
- Replace `HOLD_SLOW_RATE`/`HOLD_FAST_RATE` (0.5/1.5) and `SEEK_STEP_MILLIS`
  with a `DataStore`-backed preferences class.
- Media3's `ExoPlayer.Builder.setSeekBackIncrementMs` and
  `setSeekForwardIncrementMs` are fixed when the player is built. To make the
  notification and MediaSession skip buttons follow live changes, either
  rebuild the player between items or forward the session's seek-back and
  seek-forward commands to your own handler.
- Android TV: holding D-pad Left/Right is the natural counterpart to the Siri
  Remote's touch-surface hold. Keep the arm/release hysteresis idea
  (`holdArmMagnitude` 0.55, `holdReleaseMagnitude` 0.30) for touchpads.

Windows:
- Add an App Controls section to `SettingsPage`. Store the values next to the
  other device-local JSON under `%LOCALAPPDATA%\Edendale`.
- Arrow keys seek by the skip length. Click-and-hold on either half of the
  video sets `MediaPlayer.SetRate` to that side's hold speed and restores the
  base rate on release.
- Wire the skip length into `SystemMediaTransportControls`
  fast-forward/rewind.

### 3.2 Speed changes and seeks that never blank the picture

**Apple:** README "Speed changes and seeking";
`PlayerChromeModel.applyRate`, FFmpeg reader/decoder.
Tests: `PlayerTransportStateTests`, `FFmpegDecoderTests`,
`EnhancedVideoRenderingTests`.

Rules:
- A speed change only retimes the clock. It never seeks or flushes, so the
  picture never blanks.
- A seek or track switch keeps the last picture on screen until the first
  frame at the new position is decoded. Only switching media clears the
  surface.
- Audio and video resume together once that first frame is on screen. A
  seek while paused still shows the new frame.
- The 26.1 fix (`d60ff9e`): reapply the rate after resume, so rapid
  play/pause toggles don't stall the clock.

Android and Windows: ExoPlayer and LibVLC already keep the last frame across
seeks. Verify these cases rather than build anything:
- rapid play/pause at non-1× speeds;
- a speed change during playback (no black frame);
- a seek while paused (the new frame shows).

The Apple FFmpeg back-pressure details (4 s of audio queued ahead, 12 video
frames, 32 MB of held packets) only matter if you write your own demux/decode
loop.

### 3.3 Persisted player state and per-title preferences

**Apple:** `PlayerChromeModel` (`player.loopEnabled`, `player.aspectFill`,
auto-PiP default **on**), `Shared/Playback/PlayerPreferencesStore.swift`.

Rules:
- Loop, aspect fill, and auto-PiP persist globally on the device.
- Per title, the player remembers its speed, audio track, subtitle track (or
  that subtitles were off), and video track.
  - Key: `player.content.movie.<tmdbId>`, or `player.content.show.<showTmdbId>`
    for episodes, so a whole show shares one preference.
  - Unidentified files store nothing.
- When restoring, audio and subtitle tracks match **by language first, then
  by track name**. Only embedded subtitle tracks are remembered (downloaded
  `ext-*` tracks are not). A video track is restored by width×height, and
  only when the file has more than one.
- Progress is saved at least every **10 s** (raised from the earlier
  threshold).

Android: one Room table or `DataStore` keyed the same way. Apply after
`Player.Listener.onTracksChanged` using `TrackSelectionOverride`.

Windows: one JSON file in `%LOCALAPPDATA%\Edendale`. Apply after LibVLC's
`ESAdded` / `Playing` events using `SetAudioTrack`, `SetSpu`, and
`SetVideoTrack`.

### 3.4 Episode auto-advance, Up Next, and Continue Watching next-up

**Apple:** `PlayerLogic.nextEpisode(after:in:)`,
`upcomingEpisode(...)`, `highestCompletedPerShow(...)`;
`PlayerUpNextView.swift`; `PlayerSession`; `DownloadedView`.
Tests: `EpisodeProgressionTests`, `UpcomingEpisodePreviewTests`,
`ContinueWatchingTests`, `PlayerSessionTransitionTests`.

Rules:
- **Next episode:** the stored episode with the smallest (season, episode)
  that is strictly greater than the current one.
  - It crosses seasons.
  - Duplicate encodes of the same episode are skipped.
  - Season 0 specials advance among themselves and then into season 1; main
    seasons never fall back to season 0.
  - Returns nothing when the current episode is not in the show.
- **Auto-advance** happens at the natural end of an episode (≥ 95 %, or
  within 2 s of the end). A newer manual play request cancels a pending
  advance. Finished episodes keep their completed state.
- **Up Next card:**
  - Appears top-trailing during the **last 30 s** of a TV episode that has a
    stored successor.
  - Shows the still (falling back to the show backdrop), the episode code,
    and the title. Selecting it plays that episode.
  - Recomputed every tick, so seeking back hides it.
  - Hidden when Loop is on, for movies, when the duration is unknown, and on
    the last stored episode.
  - Stays reachable while controls are visible. Focus is visible, and
    animations honor Reduce Motion.
- **Continue Watching next-up:**
  - For a show with no episode in progress, suggest the stored episode after
    the furthest *completed* one.
  - This works even after the watched file was deleted.
  - The suggestion never writes watch progress.
  - Duplicate show records produce one card.

Android and Windows: this is pure domain logic. Port the functions together
with the test tables from the Apple test files.

### 3.5 Skip prompts via TheIntroDB

**Apple:** `Shared/Controllers/IntroDBService.swift`,
`Shared/Views/Player/PlayerSegmentController.swift`,
`Shared/Views/Settings/SegmentSkippingSection.swift`; README "Intro, recap,
and credits prompts". Tests: `IntroDBTests`.

Rules:
- **Opt-in.** The preference `player.segmentPromptsEnabled` defaults **off**,
  including on upgraded installs. Old auto-skip preferences must not enable
  it.
- **Remove the timed 90 s recap / 180 s credits auto-skip.** Android still
  has it in `PlayerLogic.kt`.
- **Request:** `GET https://api.theintrodb.org/v3/media` with
  - `tmdb_id`;
  - for episodes, `season` and `episode`, using the **show's** TMDB id and
    TMDB numbering;
  - `duration_ms`;
  - header `Accept: application/json`, timeout 8 s, no cookies, no disk
    cache.
- **Skip the lookup** when:
  - the TMDB id is outside 1…10,000,000;
  - season or episode is ≤ 0 (season 0 specials get no lookup);
  - the duration is ≤ 0, unknown, or over **21,600 s**.
- **Response handling:**
  - 404 → no segments.
  - 429 → back off for the largest of `Retry-After`, `X-RateLimit-Reset`, and
    `X-UsageLimit-Reset` (all in seconds), with a minimum of 60 s.
  - Reject a response whose `tmdb_id`, `type`, `season`, or `episode` doesn't
    match the request.
- **Decoding** (`intro`, `recap`, `credits` arrays of
  `{start_ms, end_ms}`):
  - Drop entries where both values are null.
  - Credits need `start > 0`. A null credits end means "until the end of the
    file", and that segment `reachesEnd`.
  - Intro and recap need an end.
  - Keep a segment only when `0 ≤ start < end ≤ duration`.
  - Deduplicate, drop **any segments that overlap each other** (never decide
    which content to cut), and sort by start.
- **Prompt:**
  - A **Skip Intro / Skip Recap / Skip Credits** button appears bottom
    trailing while the current time is inside a segment, even when controls
    are hidden.
  - It never skips automatically.
  - It is hidden while scrubbing or while a side panel covers the video.
  - Keyboard: **S** activates it. TV: Down from the hidden-controls surface
    focuses it.
  - After a press, it stays suppressed until playback leaves that range.
- **What a skip does:**
  - A bounded segment seeks to its end; gaps between credit ranges can hold
    extra scenes.
  - A credits segment that reaches the end marks the item complete, then
    advances to the next episode, ends playback, or restarts when Loop is on.
- **Caching:** memory only, per session, with at most 12 entries. It is
  cleared on close or when the setting is turned off. Nothing is written to
  disk or synced. Playback never waits for the lookup.
- **Privacy copy:** the provider receives the TMDB id, the
  season/episode numbers, the duration, and the device's IP address. The
  setting must say so.

Android: `HttpURLConnection` or OkHttp without a cache. Keep the state in the
player ViewModel. On Android TV, focus the prompt with D-pad Down.

Windows: `HttpClient` with no cookies. **S** is the keyboard accelerator.

### 3.6 Audio enhancement: EQ profiles and booster

**Apple:** `Shared/Controllers/AudioEnhancementController.swift`,
`Shared/Playback/AudioEQProcessor.swift`,
`Shared/Views/Settings/AudioEnhancementSection.swift`, Player Adjustments
booster toggle. Tests: `AudioEnhancementTests`.

Rules:
- Profiles are Flat, **Movies (default)**, Music, Dialogue, and Night Mode.
- Bands are 60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, and 16k Hz. These are
  LibVLC's own band frequencies.

  | Profile | Preamp | Bands (dB) |
  |---|---|---|
  | Flat | 0 | 0 0 0 0 0 0 0 0 0 0 |
  | Movies | −8 | 8 5 3 0 0 2 3 2 1 0 |
  | Music | −4 | 4 2 0 −1 −1 2 3 3 2 1 |
  | Dialogue | −6 | −3 −1 0 5 6 5 3 1 0 −1 |
  | Night Mode | −5 | −5 −2 1 4 5 5 3 1 0 −1 |

- Each profile's preamp offsets its peak boost, which leaves headroom. Night
  Mode changes the frequency balance only; it is **not** a compressor.
- The user can adjust the preamp and each band. Adjustments are stored
  separately and added to the profile values. **Changing the profile resets
  them.** Values clamp to −20…+20 dB.
- **Audio Booster** is off by default. It adds **+10 dB** of preamp through
  the same EQ (not the volume) and restores the previous setting when turned
  off.
- When the effective preamp and every band are 0, the processor is removed
  entirely.
- Settings apply live and carry across files.
- Keys: `audio.enhancementProfile`, `audio.enhancementPreamp`,
  `audio.enhancementBands`, `audio.boosterEnabled`.
- Filters: RBJ peaking biquad per band, 1-octave bandwidth, one cascade per
  channel. Bands at or above Nyquist pass through.

Android:
- Build a `BaseAudioProcessor` that implements the same biquads on float or
  16-bit PCM. Install it through
  `DefaultRenderersFactory.buildAudioSink(...)` →
  `DefaultAudioSink.Builder().setAudioProcessors(...)`.
- Avoid `android.media.audiofx.Equalizer`: its band count and frequencies
  depend on the device, so the profiles would not match.
- Passthrough output (AC3/DTS bitstream to a receiver) skips audio
  processors. Document that the EQ only applies to decoded PCM.

Windows:
- `new Equalizer()`, then `SetPreamp(...)` and `SetAmp(dB, band)` for bands
  0–9, then `MediaPlayer.SetEqualizer(eq)`.
- The bands and the ±20 dB range match exactly. Call
  `MediaPlayer.UnsetEqualizer()` when the result is flat.

### 3.7 Picture adjustments

**Apple:** `Shared/Controllers/VideoAdjustmentController.swift`,
`Shared/Views/Player/VideoAdjustmentControls.swift`,
`Shared/Playback/Enhancement/ColorAdjustment.metal`.

Rules:

| Adjustment | Range | Neutral | Step |
|---|---|---|---|
| Brightness | 0–2 | 1 | 0.05 |
| Contrast | 0–2 | 1 | 0.05 |
| Gamma | 0.25–3 | 1 | 0.05 |
| Saturation | 0–3 | 1 | 0.05 |
| Hue | 0–360° | 0 | 5° |

- Values are stored device-locally as one JSON blob (`video.adjustments`),
  normalized on load.
- **Show Original** temporarily applies neutral values without changing the
  stored ones. **Reset** returns to neutral.
- Neutral values skip filtering entirely. Leaving the player restores
  neutral on the engine.
- tvOS-style platforms use −/+ increment controls instead of sliders.

Android: Media3 `ExoPlayer.setVideoEffects(...)` with `media3-effect`'s
`Brightness`, `Contrast`, and `HslAdjustment` (hue and saturation). Gamma has
no built-in effect; use a `SingleColorLut` or a small custom `GlEffect`.
Rescale the ranges, because Media3 uses −1…1 for brightness and contrast.

Windows: LibVLC's `adjust` filter.
`SetAdjustInt(VideoAdjustOption.Enable, 1)`, then
`SetAdjustFloat(Brightness | Contrast | Gamma | Saturation | Hue, …)`.
The ranges came from VLC; map hue 0–360 onto LibVLC's signed hue range.

### 3.8 Subtitle appearance and overlay rules

**Apple:** `Shared/Views/Player/SubtitleAppearance.swift`,
`Shared/Views/Settings/SubtitleAppearanceSection.swift`,
`PlayerSubtitleOverlay.swift`, `TimedTextRenderer.swift`.
Tests: `SubtitleAppearanceTests`, `PlayerSubtitleOverlayTests`,
`SubtitleEngineTests`.

Rules:
- **Settings → Subtitles** offers four settings, using **named presets, not a
  free color picker**. TV platforms have no picker, and a fixed palette keeps
  every choice legible.
  - Font: System, Rounded, Serif, Monospaced. All are system families, so a
    choice never falls back to something else on another device.
  - Text color: **Parchment (default, the theme text color)**, White, Yellow
    `#FFE033`, Cyan `#59E6FF`, Green `#73F273`, Black.
  - Box color: **Ink (default, the theme background)**, Black, Charcoal
    (22 % white), Navy `#0F1A3D`, White.
  - Box opacity: 0–1, default 1, in 0.1 steps on TV and stored to whole
    percent. At 0 the box is removed and an outline still keeps text legible.
- Each glyph gets an outline: light around Black text, dark (theme
  background) around everything else.
- Changes apply to the cue already on screen. Bitmap subtitles (PGS/VobSub)
  keep their authored pixels.
- Keys: `subtitles.font`, `subtitles.textColor`,
  `subtitles.backgroundColor`, `subtitles.backgroundOpacity`. Device-local.
- **Placement:**
  - Text cues are positioned in the *visible* video rectangle (correct for
    fit or fill) and stay clear of visible transport controls.
  - Font size is `clamp(16, visibleHeight × 0.055, 48)` × the accessibility
    text scale.
  - Simultaneous cues stack. The overlay never intercepts input and updates
    while paused.
- **Downloaded subtitles:** SRT, WebVTT, and ASS use the same path, including
  CRLF files and UTF-16 files with a BOM. ASS fallback strips the packet
  fields from dialogue text.

Android: `SubtitleView.setStyle(CaptionStyleCompat(foreground, background,
window, edgeType = EDGE_TYPE_OUTLINE, edgeColor, typeface))` plus
`setApplyEmbeddedStyles(false)`. Media3 already parses SRT, WebVTT, SSA/ASS,
PGS, and VobSub. Scale with `setFractionalTextSize`, and set
`setBottomPaddingFraction` while controls are visible.

Windows: LibVLC's freetype options (`--freetype-font`, `--freetype-color`,
`--freetype-background-color`, `--freetype-background-opacity`,
`--freetype-outline-*`) are fixed when the `LibVLC` instance is created, so
a change needs a new instance or a media restart. For live changes like
Apple's, disable LibVLC's subtitle rendering for text tracks and draw cues
in a XAML overlay above the `VideoView` (the SwapChainPanel allows overlays).
That reuses the parsing already in `SubtitleService.cs`.

### 3.9 Track pickers in Player Adjustments

**Apple:** `PlayerSettingsPanel.swift`.

Rules:
- **Video Track** appears only when a file has more than one video track.
  Each row shows `name (language) — W×H`.
- **Audio Track** appears only when a file has more than one audio track.
  Each row shows `name (language) — Mono | Stereo | 5.1 | 7.1 | Nch`.
- The language is appended only when the name doesn't already contain it.
- Panel order: Video Track, Audio Track, Picture, Enhancement, then
  playback (speed in 0.05 steps, Skip Prompts, Audio Booster, Loop,
  Fit/Fill).
- The Enhancement **Preset** became a **menu** instead of a segmented
  control (`4f8383a`), because four segments truncated in narrow panels.

Android: `Tracks.groups` filtered by `C.TRACK_TYPE_VIDEO` / `TRACK_TYPE_AUDIO`.
Windows: `MediaPlayer.VideoTrackDescription` / `AudioTrackDescription`.

### 3.10 Playlist panel redesign

**Apple:** `PlayerPlaylistPanel.swift`; DESIGN.md. The new tokens exist
only in this branch's DESIGN.md so far, not on `main`, `android`, or
`windows`; copy the two rows and the selection paragraph into each branch's
DESIGN.md (and `main`'s) when porting.

Rules:
- New tokens: `PlaylistActiveBackground` `#FFFFFF` and `PlaylistActiveText`
  `#000000`.
- The current *or* focused row uses the white fill, black text, and the
  larger title size. A playing indicator tells the current file apart from a
  focused row.
- The panel opens scrolled to the current file.
- Identified episodes, and the current identified movie, show landscape
  artwork with the title and play time stacked. Unknown sibling files keep
  the file-name fallback.

Android and Windows: add both tokens to the platform resources
(`Theme.kt` / the XAML resource dictionary) and restyle the existing
`PlayerPlaylist.kt` / `PlayerPlaylistPanel.xaml`.

### 3.11 "Play From": several copies of one title

**Apple:** `Shared/Views/Detail/PlaybackSources.swift`,
`MediaDetailView.swift`. Tests: `PlaybackSourcesTests`.

Rules:
- The same TMDB id can be imported from several sources, for example a
  local folder and an SMB share. Each file stays its own record.
- **Order:** the page's own copy first, then local copies, then the rest by
  folder name (localized standard compare).
- **Play** starts the first copy whose source isn't marked
  offline/needs-sign-in, or the first copy when every source is.
- Movies get an icon-only **Play From** menu beside Play. Episodes get a
  **Play From** context-menu section, and the subtitle adds
  "· N sources".
- Each menu row shows the source name, then
  `Kind · filename` (plus "· Unavailable" when the source is offline).
- Episode slots merge every copy of the show into one list keyed by
  (season, episode), in airing order.

### 3.12 Storage providers (ENHANCEMENT.md Section J)

**Apple:** `Shared/Controllers/Connectors/*`, `Shared/Controllers/Accounts/*`,
`Shared/Playback/Remote/*`, `AddNetworkSourceView`, `ServerSourceForm`,
`CloudAccountStep`, `NetworkFolderPickerView`, `AccountsSection`,
`SourceRow`. Tests: `ConnectorTests`, `CloudListingTests`, `OAuthTests`,
`RemoteByteSourceTests`, `RemoteFFmpegTests`, `SFTPProtocolTests`,
`AccountHandoffTests`, `Support/HTTPStub.swift`.
Read ENHANCEMENT.md J.1–J.13 in full before starting. The essentials are
below.

**Providers:** Google Drive, OneDrive (personal and work/school), Dropbox,
WebDAV (Nextcloud, ownCloud, Synology, …), S3-compatible, NFS, and SFTP,
alongside local folders and SMB.
- Not planned: Box (it needs a client secret), MEGA, and FTP.
- Plex, Jellyfin, and Emby would be a separate feature.

**Canonical, credential-free stored URLs.** Keep these exact shapes so data
stays comparable across platforms. Raw kind values are persisted, so never
rename them.

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

- `<account>` is the first 32 hex digits of `SHA-256("<kind>:<subject>")`.
  - The subject is Google `sub`, the Microsoft user `id`, or Dropbox
    `account_id`.
  - For S3 it is `endpoint|bucket|accessKeyID`, with the endpoint lowercased
    and its trailing `/` trimmed.
  - It doubles as the credential-store key.
- Every URL ends with the real file name, so the filename parser runs
  unchanged and still runs **before** any network enrichment.

**Connector contract:** `validate()`, `list(directory)`, and
`enumerateVideos(under)`.
- The default enumeration is breadth-first, capped at **2,000 folders**, and
  skips dot-files. A failure at the root throws; a failure deeper down skips
  that branch only.
- Dropbox and OneDrive override it with their recursive listings.
- Entries carry `size`, `duration` (from Drive `videoMediaMetadata` or the
  Graph `video` facet, so items show a runtime instead of `--:--`), and
  `modified`.

**Library integration:**
- New nullable source fields: `displayPath` (for example
  `Google Drive › My Drive › Movies`), `accountKey`, `lastScannedAt`, and
  `changeCursor` (reserved for later).
- The automatic rescan on each library visit **skips remote sources scanned
  in the last 15 minutes**. A manual Rescan always scans.
- Failures are recorded **per source** (`offline` / `needsSignIn`) and shown
  on its row, not as a library-wide error, and are cleared by the next
  successful scan.
- **Removing a source never deletes its saved login or account.** Saved
  logins and accounts are managed in **Settings → Accounts**, which lists
  each with the number of sources using it.

**Streaming:** one random-access byte source per HTTP provider.
- Reads 4 MiB `Range` chunks, prefetches the next chunk while reads are
  sequential, and keeps 8 chunks (32 MiB). That keeps the MKV cues or MP4
  `moov` at the end of a file cached.
- Uses an ephemeral session (no disk cache or cookies) with system TLS
  validation.
- **Never** logs URLs, tokens, or headers.

| Response | Action |
|---|---|
| 206 | Serve the range |
| 200 at offset 0 | Accept (Graph may ignore `Range`) |
| 200 at any other offset | Retry once, then fail |
| 401 | One single-flight token refresh, then retry |
| 403 rate limit, 429, 5xx | Back off 0.5 / 1 / 2 s with jitter, then fail |
| 403 expired signed link, 410 (Dropbox) | Resolve a new link once |
| 404 | Fail with "This file is no longer in <provider>" |

- Never hand a remote scheme to an engine's built-in HTTP stack, which may
  skip TLS verification or leak a signed link. Apple's FFmpeg `openURL:`
  explicitly rejects them.

**Accounts:**
- OAuth authorization code + PKCE (S256), **no SDKs and no client secrets**.
- Refresh tokens are stored in secure storage, one item per account.
  Access tokens live only in memory. A single-flight refresh actor serves
  every waiter.
- Scopes:
  - Google: `openid email https://www.googleapis.com/auth/drive.readonly`.
  - Microsoft: `Files.Read User.Read offline_access` through the `/common`
    authority.
  - Dropbox: `files.metadata.read files.content.read account_info.read`,
    with `token_access_type=offline`.
- Client IDs and app keys come from gitignored local config. An empty value
  **hides that provider** in the build.

**Provider details** (ENHANCEMENT.md J.9 has the full list):
- Drive: list with `files.list` using `q='<id>' in parents and trashed =
  false`, `supportsAllDrives`, and `includeItemsFromAllDrives`.
  - Roots: My Drive, Shared with me, and Shared drives.
  - Follow shortcuts. Skip `application/vnd.google-apps.*` files. Filter
    videos by extension, not MIME type.
  - Stream with `alt=media` plus a Bearer token and `Range`. Never send
    `acknowledgeAbuse`.
- OneDrive: Graph `children` with `@odata.nextLink` paging. Stream from
  `@microsoft.graph.downloadUrl`, which needs no auth header, expires within
  minutes, and accepts `Range`.
- Dropbox: `list_folder` (recursive) and `/continue`. Stream from
  `get_temporary_link`, which lasts 4 h and then returns 410.
- WebDAV: `PROPFIND Depth: 1` with Basic or Digest auth. Decode relative and
  absolute `href`s. Nextcloud uses `/remote.php/dav/files/<user>/`.
- S3: SigV4 signing, `ListObjectsV2` with `delimiter=/`, and pre-signed GETs
  that are re-signed after a 403.
- NFS: the export needs the `insecure` option because mobile apps can't bind
  privileged ports. Say so in the connection error.
- SFTP: modern key exchange (curve25519/ECDH, Ed25519/ECDSA host keys,
  AES-GCM).
  - **Trust on first use:** show the SHA-256 fingerprint and key type, pin
    it, and refuse a changed key until the user approves it again.
  - Password login first; key login later.
  - Read with pipelined 32 KiB requests.

Android:
- Connectors: OkHttp for HTTP providers, jcifs-ng for SMB (already used), a
  maintained SSH library for SFTP (for example sshj or Apache MINA SSHD;
  check it supports the modern algorithms above), and an NFS client library
  if one fits.
- Playback: a Media3 `DataSource` per scheme, with a
  `ResolvingDataSource` / `DataSource.Factory` that maps `gdrive://` and the
  other schemes to the resolver. `SmbDataSource.kt` already follows this
  pattern.
- Secrets: `EncryptedSharedPreferences` (already a dependency through
  `security-crypto`). Accounts are **device-local**; Android has no Keychain
  sync equivalent.
- OAuth: Custom Tabs + PKCE for Microsoft and Dropbox. Google steers Android
  clients to the Google Identity Services `AuthorizationClient` instead of
  custom-scheme redirects, so check Google's current rules before choosing.
- Storage: add the nullable columns through a Room migration.

Windows:
- Connectors: `HttpClient` for HTTP providers and SMB through UNC (already
  used). SFTP needs a .NET SSH library with modern KEX. Windows has no
  default NFS client (it's an optional feature), so NFS may be out of scope.
- Playback: LibVLCSharp `MediaInput` (custom I/O: Open/Read/Seek/Close)
  wraps the byte source, so tokens and signed links never reach LibVLC's
  own HTTP access.
- Secrets: DPAPI (`ProtectedData`, already used for SMB and the TMDB
  session).
- OAuth: the system browser plus a **loopback redirect**
  (`http://127.0.0.1:<port>`, RFC 8252).
  - Microsoft and Dropbox allow loopback for public clients.
  - Google's "Desktop" client type issues a `client_secret` that Google says
    is not confidential. That conflicts with the "no client secrets
    anywhere" rule, so decide it explicitly (see §6).
- Watch-state sync through OneDrive already exists on Windows. Keep the
  OneDrive *storage* account separate from that sync, or make the
  relationship explicit in the UI.

Cross-branch obligations from J.12: the `web` branch needs privacy-policy
text for these providers (Google verification requires it), and `main`'s
README should list the supported storage services.

### 3.13 Buffered remote reads (SMB, NFS, SFTP)

**Apple:** `Shared/Playback/Remote/EDBufferedByteSource.{h,m}`,
`EDSMBFile.m`, `RemoteFileByteSource.swift`. Tests:
`BufferedByteSourceTests`.

Why it exists: the engine asks for 64 KiB at a time. Over a phone hotspot,
Tailscale, or a VPN, one round trip per 64 KiB caps throughput below video
bit rates and causes drops.

Rules:
- A worker thread fetches **1 MiB** chunks and keeps up to **48 MiB** ahead
  of the read position, within a **64 MiB** cache.
- The chunk a blocked read needs always goes first, so a seek never waits
  behind read-ahead.
- After a failure on a file that opened once, the connection is dropped and
  reopened with backoff delays of **0.25, 0.5, 1, 2, 4, and 8 s**. The read
  fails only after every retry has.
- An idle connection (paused playback) gets a **keep-alive after 20 s**.
- Cancellation fails blocked reads at once. The "connection lost" message
  names the host.

Android: wrap `SmbDataSource` (jcifs-ng) the same way: a background
prefetcher with a chunk cache, sitting behind the `DataSource.read` contract.
Media3's `CacheDataSource` is not a replacement; it caches to disk and doesn't
reconnect.

Windows: UNC reads go through the Windows SMB client, which has its own
read-ahead, so measure before building anything. For SFTP and other custom
sources, put the buffer inside the `MediaInput`.

### 3.14 TV sign-in for cloud accounts

**Apple:** `AccountHandoff.swift`, `AccountHandoffCenter.swift`,
`AccountHandoffRequestView.swift`; ENHANCEMENT.md J.7.

The underlying problem applies to Android TV too. Google's device-code flow
(the "enter a code on your phone" screen) **cannot grant
`drive.readonly`**; it only allows sign-in basics, YouTube, `drive.file`,
and `drive.appdata`. Dropbox has no device flow at all. Only OneDrive's
device-code flow works on a TV.

Apple's solution:
- The TV asks a nearby iPhone or iPad running Edendale (found through
  DeviceDiscoveryUI over an encrypted connection).
- The phone confirms ("Link Google Drive on Living Room?"), signs in or
  reuses an account, and sends a versioned message: JSON behind a 4-byte
  big-endian length, at most 64 KiB, with provider, account key, email,
  refresh token, and scopes.
- The TV validates it with a refresh and stores it locally. The TV reuses the
  phone's refresh token, because Google allows only 100 per account per
  client.
- The same channel carries SMB, SFTP, WebDAV, and S3 logins, which are
  tedious to type with a remote.

Android TV has no DeviceDiscoveryUI. Options to explore:
- Network Service Discovery (`_edendale-handoff._tcp`) plus a pairing code
  shown on the TV, authenticated with a PAKE or an ECDH exchange confirmed by
  that code.
- OneDrive device code on the TV directly.
- Typed logins for SMB, NFS, and so on.

It must stay device-to-device. A web relay would be an Edendale server,
which AGENTS.md forbids.

Windows: not applicable.

### 3.15 Desktop navigation, shortcuts, and docked panels

**Apple (macOS):** `RootSidebar.swift`, `AppCommands.swift`,
`SectionHeader.swift` (scrubber), `PlayerChromeModel.docksSidePanels`.
Tests: `LibrarySectionsTests`.

Rules worth bringing to Windows (and to Android large-screen windows):
- **Sidebar order:** Movies & Shows, Watchlist, Downloaded, Search, then
  Settings.
  - Watchlist and Downloaded expand into child rows: Movies and TV Shows;
    and Continue Watching, Movies, and TV Shows.
  - A child row appears only while its section has titles for the current
    audience setting. If the open section empties, the sidebar returns to
    the parent page.
  - The search query survives navigation.
- The **Continue Watching page** lists every resumable title; the shelf keeps
  its 12-item cap. The **Movies page** includes movies that are also in
  Continue Watching.
- **Shortcuts:**

  | Action | macOS | Windows equivalent |
  |---|---|---|
  | Toggle sidebar | ⌘B | Ctrl+B |
  | Add media folder (Downloaded pages) | ⌘N | Ctrl+N |
  | Link network source (Downloaded pages) | ⌥⌘N | Ctrl+Alt+N |
  | Rescan library (when a source is linked) | ⌘R | Ctrl+R / F5 |

- **Link Source form:**
  - The address field is focused on open. Tab and Shift+Tab move between
    fields.
  - Return connects when every field is filled; otherwise it jumps to the
    first empty field.
  - A guest connection (no username or password) is one click.
- **Docked player panels:** the playlist and Player Adjustments dock as a
  trailing sidebar, narrowing the video instead of covering it.
  - Clicking the video leaves the panel open. Esc closes the panel; a second
    Esc leaves the player.
  - Controls still auto-hide. Up Next and skip prompts stay available.
  - On WinUI this is a `SplitView` in inline mode.
- **Season shelves:** each season's heading rule acts as that shelf's scroll
  indicator and scrubber. Drag the gold thumb or click the rule.

### 3.16 GPU upscaling, sharpening, and denoise

**Apple:** ENHANCEMENT.md Section E; `Shared/Playback/Enhancement/*`.
Tests: `MetalEnhancementPipelineTests`, `EnhancedVideoRenderingTests`.

Rules:
- **Presets:**
  - Off.
  - Sharpen Only (CAS, no upscale).
  - **Balanced (default):** upscale plus CAS.
  - High Quality: upscale, CAS, and temporal denoise.
- Sharpness 0–1 (default 0.5) and a denoise strength slider. **Show
  Original** compares against the unprocessed picture.
- A resolution label reads, for example, "720p → 2160p".
- **Upscale target:** below 1080p, scale to the smaller of 1080p and the
  display. At 1080p, scale to the display when it is 4K. At or above the
  display resolution, skip the upscale (CAS only).
- **Budget:** under 8 ms per frame. Over budget, drop denoise first, then the
  upscale.

Android: a custom Media3 `GlEffect` / `GlShaderProgram`. CAS is a single
3×3-tap pass and ports directly; FSR1 EASU covers the upscale. Measure on
mid-range devices and gate by device capability.

Windows: LibVLC 3.0.19 and later added GPU "Super Resolution" (NVIDIA, Intel,
or AMD) in the d3d11 output. Check the upscale-mode option on the bundled
LibVLC 3.0.23 and expose it as a single toggle. A custom shader pipeline
inside LibVLC isn't practical.

### 3.17 Frame interpolation ("Motion Smoothing")

**Apple:** ENHANCEMENT.md Section I; `FrameInterpolator.swift`,
`MotionEstimation.metal`, `FrameInterpolation.metal`,
`FrameInterpolationScheduler.swift`, `MetalFXInterpolatorBackend.swift`.
Tests: `FrameInterpolationTests`.

Summary:
- Motion is estimated hierarchically on the GPU: 16×16 blocks with a ±16 px
  search, refined over 4×4 sub-blocks, then made per-pixel.
- The synthetic frame comes from bidirectional, occlusion-aware warping.
- Scene cuts are detected on the GPU: a block counts as unmatched when its
  error is above 0.06, and 30 % unmatched blocks means a cut.
- The display shows 2× the source rate, and only for sources at **≤ 30 fps**.
- The real frame N is held back by one refresh, so the synthetic frame shows
  first.
- Interpolation resets on seek, pause, track switch, and media switch.
- Sources wider than 1920 px run motion estimation at half resolution.

Android: possible as GL effects, but costly. Treat it as research and gate
it to high-end devices.

Windows: LibVLC has no hook for it. Driver features (AMD Fluid Motion Frames,
NVIDIA) are outside the app. Skip it.

### 3.18 System integration

**Apple:** `SampleBufferPiPSource.swift`, `NowPlayingBridge.swift`,
`AudioSessionManager.swift`, `PlayerAudioRouteButton.swift`,
`SystemVolumeController.swift`.

Rules worth carrying over:
- **PiP on phones:** starting PiP dismisses the full-screen player but keeps
  the session. Restore returns to the same session; closing PiP ends it.
  Transport controls follow the playback state. The automatic-PiP preference
  never disables the manual PiP button.
- **Now Playing / lock screen:** the skip buttons use the App Controls
  lengths (§3.1).
- **Audio output route button** ("Audio Output") in the player.
- **Volume HUD** when the system volume changes during playback.

Android:
- PiP: `PictureInPictureParams`, with `setAutoEnterEnabled` on API 31+.
- Now Playing: a `media3-session` `MediaSession`.
- Audio route: the system output switcher (`MediaRouter` /
  `MediaRouteButton`).

Windows:
- Compact overlay through `AppWindow.SetPresenter(CompactOverlay)`.
- `SystemMediaTransportControls` for media keys and the volume flyout.
- Audio device selection through LibVLC `AudioOutputDeviceSet`.

### 3.19 Settings page layout

**Apple:** `SettingsLayout.swift`, `SettingsView.swift`.

Settings section order: About (version, sync note, and attribution at the
top on TV/desktop so focus-scrolling can reach it), Audience, Startup
(desktop), **Audio Enhancement, Subtitles, Skip Prompts, App Controls**,
Sources, **Accounts**, then TMDB. Keep the new sections in that relative
order for consistency across platforms.

---

## 4. Apple-only work (no port needed)

These solve problems specific to Apple frameworks or tooling:
- Replacing SwiftVLC with AVFoundation + FFmpeg and building a Metal renderer
  (Sections A–D, G). Windows already has LibVLC and Android has Media3.
- FFmpeg XCFramework build, symbol isolation from libvlc's bundled FFmpeg,
  and Xcode Cloud compile (`Vendor/FFmpeg/*`, `ci_scripts/ci_post_clone.sh`).
- Hardware-only AV1 through VideoToolbox. On Android, check that MediaCodec
  supports AV1 on target devices, or use the dav1d extension. On Windows,
  LibVLC uses dav1d.
- `AVPictureInPictureController` single-controller rules, and the macOS 27
  PiP layout workaround.
- iOS foreground hang (drawing inside a SwiftUI update blocked on
  `nextDrawable`) and VideoToolbox session invalidation after backgrounding.
  The only general lesson: rebuild hardware decoders after returning from
  the background, and fall back to software if the rebuild fails.
- Moving credentials into `.secret/` (Android uses `secrets.example.json`,
  Windows uses user secrets plus `tools/Edendale.Secrets`).
- Unit-test host App Group isolation, `ChassisDoubleTapDetector`
  (CoreMotion), visionOS routing, the spatial-audio entitlement, and
  DeviceDiscoveryUI plumbing.

---

## 5. Test parity checklist

Port the *cases*, not the code. The Apple file is where to find each case.

| Behavior | Apple test file | Android | Windows |
|---|---|---|---|
| Skip/hold preferences, snapping, defaults | `PlayerControlPreferencesTests` | ☐ | ☐ |
| Hold side, rate grid | `PlayerLogicTests` | ☐ | ☐ |
| Transport state on rate change, seek, pause | `PlayerTransportStateTests` | ☐ | ☐ |
| Next episode, specials, duplicates | `EpisodeProgressionTests` | ☐ | ☐ |
| Up Next window, loop, last episode | `UpcomingEpisodePreviewTests` | ☐ | ☐ |
| Continue Watching next-up, no progress writes | `ContinueWatchingTests` | ☐ | ☐ |
| Auto-advance vs. a manual request | `PlayerSessionTransitionTests` | ☐ | ☐ |
| IntroDB request/decoding, overlaps, 429 cooldown, prompts | `IntroDBTests` | ☐ | ☐ |
| EQ profiles, clamping, booster, reset on profile change | `AudioEnhancementTests` | ☐ | ☐ |
| Subtitle appearance keys, opacity snapping | `SubtitleAppearanceTests` | ☐ | ☐ |
| Overlay placement, CRLF, UTF-16 BOM | `PlayerSubtitleOverlayTests`, `SubtitleEngineTests` | ☐ | ☐ |
| Play From order and preference | `PlaybackSourcesTests` | ☐ | ☐ |
| Sidebar child rows | `LibrarySectionsTests` | (large screen) | ☐ |
| Canonical URLs, account keys, factory | `ConnectorTests` | ☐ | ☐ |
| Recorded provider listings | `CloudListingTests` | ☐ | ☐ |
| PKCE (RFC 7636 App. B vector), device code, single-flight refresh | `OAuthTests` | ☐ | ☐ |
| Range/refresh/410/ignored-range/backoff/cancel | `RemoteByteSourceTests` | ☐ | ☐ |
| Read-ahead, reconnect, keep-alive | `BufferedByteSourceTests` | ☐ | ☐ |
| SFTP framing and fingerprints (match `ssh-keygen -l`) | `SFTPProtocolTests` | ☐ | ☐ |
| Handoff encoding and version rejection | `AccountHandoffTests` | ☐ (if TV handoff) | n/a |

No real credentials in tests or CI. Use HTTP stubs that serve local fixtures
with `Range` support; Apple's `EdendaleTests/Support/HTTPStub.swift` is the
model.

---

## 6. Open decisions to settle per platform

1. **Account sync.** Apple syncs accounts through iCloud Keychain (except to
   Apple TV). Android and Windows have no equivalent, so accounts will be
   device-local, which is also the stricter reading of AGENTS.md constraint 6.
   Settle and document it in each README.
2. **Google on Windows.** Desktop OAuth clients carry a non-confidential
   `client_secret`. Either accept that as a documented exception, or ship
   Google Drive on Windows only through WebDAV (for example
   `rclone serve webdav`).
3. **Google on Android.** Choose between the Identity Services
   `AuthorizationClient` and raw PKCE, and between a separate Android
   OAuth client and a shared one. Restricted-scope verification for
   `drive.readonly` is shared across clients in the same Cloud project.
4. **Home-server TLS.** Apple allows local networking
   (`NSAllowsLocalNetworking`). Decide on self-signed certificates with
   per-host pinning versus requiring valid HTTPS for WebDAV on the LAN, and
   apply the same decision everywhere.
5. **Android's old auto-skip.** Remove it when skip prompts land, and don't
   migrate its preference into the new opt-in (§3.5).
6. **Hold-speed default on Android.** The right side moves from 1.5× to the
   new 2.0× default unless the user had changed it.

---

## 7. Suggested order

**Windows:** 3.1 → 3.3 → 3.4 → 3.5 → 3.6 → 3.7 → 3.8 → 3.9/3.10 → 3.15 →
3.11 → 3.12 (WebDAV/SFTP/S3 first, then OneDrive and Dropbox, then Google
after decision 2) → 3.18 → 3.16.

**Android:** 3.1 → 3.5 (including removing the auto-skip) → 3.3 → 3.4 → 3.8 →
3.9/3.10 → 3.6 → 3.13 (harden SMB) → 3.11 → 3.12 → 3.14 (Android TV) → 3.7 →
3.16.

Each step is product behavior. Update that branch's README with the exact
commands and settings, per AGENTS.md working rule 4.
