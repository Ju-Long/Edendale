# Enhancement tracker

Requested Apple player fixes and enhancements, recorded on branch `apple-26.1`.
Checked items are implemented in this checkout and verified with the checks
listed below. Unchecked items remain unfinished. ADV-01 starts with community
timestamps; local detection and spatial AVKit playback remain future work.

## Errors and bug fixes

### Episode progression

- [x] **BUG-01 — Automatically play the next episode.** After an episode
  finishes, start playing the next available episode. Currently, playback does
  not advance automatically.
- [x] **BUG-02 — Update Continue Watching after an episode ends.** When returning
  to [DownloadedView.swift](Shared/Views/Downloaded/DownloadedView.swift), show
  the next stored episode in Continue Watching, including the next season when
  appropriate and available locally.

### Playlist selection

- [x] **BUG-03 — Select the currently playing file when opening the playlist.**
  Opening [PlayerPlaylistPanel.swift](Shared/Views/Player/PlayerPlaylistPanel.swift)
  should automatically select the file that is currently playing.
- [x] **BUG-04 — Make playlist selection visibly distinct.** Selecting another
  file in the playlist currently only produces a zoom effect without a highlight.
  The selected item should have both a larger font size and a white background
  with black text.

### Playback controls

- [ ] **BUG-05 — Keep the play/pause icon in sync after switching files.** When
  another file starts playing, display the pause icon. The button currently
  remains on the play icon even while playback is active.

## Enhancements

### Playlist presentation

- [x] **ENH-01 — Add artwork and stacked details to identified playlist items.**
  For an identified movie or show, display a landscape preview image at the
  leading edge of its entry in
  [PlayerPlaylistPanel.swift](Shared/Views/Player/PlayerPlaylistPanel.swift).
  Place the title and playtime beside the image in a vertically stacked,
  leading-aligned layout.

### Track selection

- [x] **ENH-02 — Add a video track selector.** Offer video track selection when
  the playing file contains more than one video track.
- [x] **ENH-03 — Add an audio track selector.** Offer audio track selection when
  the playing file contains more than one audio track.

### Audio settings

- [x] **ENH-04 — Add adjustable audio enhancement profiles.** Provide an
  adjustable, profile-based audio enhancer in
  [SettingsView.swift](Shared/Views/Settings/SettingsView.swift), with **Movies**
  as the default profile.
- [x] **ENH-05 — Add an audio booster toggle.** Add an audio booster in
  [PlayerSettingsPanel.swift](Shared/Views/Player/PlayerSettingsPanel.swift).
  The toggle defaults to **off**.

### Upcoming episode preview

- [ ] **ENH-06 — Show the upcoming video near the end of a TV episode.** With
  approximately **30 seconds remaining**, show the upcoming video at the
  **top right** of the player for a TV series. Movies should not show this
  preview.

## Advanced enhancements

- [x] **ADV-01, first stage — IntroDB timestamp lookup and manual skip prompts.**
  The SwiftVLC player uses [TheIntroDB](https://theintrodb.org/docs) to retrieve
  intro, recap, and credits ranges for identified movies and TV/anime episodes.
  **Skip Prompts** defaults off and is available in Settings and Player
  Adjustments. Once enabled, a button at the **bottom trailing** edge appears
  during a known segment, even with playback controls hidden. Skipping requires
  a button press. Bounded credits preserve following scenes; terminal credits
  use the existing completion, next-episode, and loop behavior.

  Anonymous requests use TMDB identifiers and actual runtime, start during
  playback, and do not delay import or playback. Temporary timestamps stay in
  memory for the session. The old fixed recap/credits auto-skips are removed;
  their preferences do not enable this new network feature. See
  [README.md](README.md#intro-recap-and-credits-prompts) for privacy, controls,
  provider constraints, and test commands.

- [ ] **ADV-01, future stages — Coverage beyond provider timestamps.** Spatial
  AVKit playback, season-zero specials, combined/alternate episode numbering,
  and local scene detection remain unimplemented. Community coverage and
  edition matching can leave gaps. The original local-detection reference is
  [recurring-content-detector](https://github.com/nielstenboom/recurring-content-detector.git).

## Notes for future implementation

- Preserve the requested playlist selection colors; define semantic tokens
  for that state when implementing it, following [DESIGN.md](DESIGN.md).
- Before changing product behavior, identify parity implications for the
  `android` and `windows` branches. Implementations remain native and separate;
  the `web` branch remains a static site.

## Implementation verification — 15 September 2026

- **Playlist:** BUG-03, BUG-04, and ENH-01 are integrated on `apple-26.1`.
  Current/focused rows use larger white/black styling. Episode rows and the
  current identified movie include landscape artwork and stacked details,
  with a placeholder when artwork is missing. Other folder siblings retain
  filename-only rows because their model metadata is unavailable here.
- **Track selectors:** ENH-02 and ENH-03 are integrated. Track highlights
  observe SwiftVLC selection refreshes even when track IDs remain unchanged.
- **Audio:** ENH-04 and ENH-05 are integrated. Movies is the default profile;
  profile adjustments persist locally. The booster defaults off, adds bounded
  preamp gain, and restores the unboosted setting when disabled. Live changes,
  player replacement, gain limits, and persistence have regression coverage.
  visionOS spatial/multiview playback in the system player is unaffected.
- **Episode progression:** BUG-01 and BUG-02 are integrated. Automatic advance
  preserves completion, follows stored episode order, and yields to newer
  manual requests. Continue Watching derives one next-up suggestion per show,
  including when the completed file was removed, while preserving active
  progress and visible-library filtering.
- **Checks passed:** unsigned macOS compilation and all 156 macOS unit tests,
  tvOS and visionOS Simulator builds, and `git diff --check`. Exact commands are in
  [README.md](README.md). Interactive playback, listening, remote navigation,
  and screen-reader checks remain unrun because this work is headless.
  A local silent fixture using VLC's dummy audio output verified real
  end-of-media advancement and completion saving without screen/audio access.
- **Test support:** debug unit-test hosts use in-memory stores and disable
  CloudKit so the suite runs without signing entitlements or opening the
  user's library and watch-progress databases.
- **Remaining work:** BUG-05 play/pause synchronization and ENH-06 upcoming
  episode preview remain unchecked. ADV-01's first stage is implemented;
  additional detection coverage and spatial AVKit support remain future work.
- **Parity:** the nine completed product changes also affect `android` and
  `windows`; their native implementations remain pending. `web` is unaffected.

## IntroDB implementation verification — 15 September 2026

- **Checks passed:** macOS app/test compilation and all **169 unit tests**;
  unsigned iOS, tvOS, and visionOS Simulator builds; `git diff --check`.
  Commands are documented in [README.md](README.md#apple-development).
- **Regression coverage:** canonical show IDs, missing and malformed data,
  null/no-segment values, multiple ranges and credits gaps, request limits,
  session cache lifetime, stale/cancelled responses, opt-in migration, and
  manual-seek validation. Real VLC fixtures verify terminal-credit progression,
  duplicate presses, loop behavior, paused seeking, and saved resume position.
- **Remaining verification:** visual layout, hardware remote navigation,
  VoiceOver, and timestamp alignment against real titles need on-device checks.
  Existing build warnings remain outside this feature's scope.
- **Parity:** this behavior also affects `android` and `windows`; their native
  implementations are pending. `web` remains static and is unaffected.
