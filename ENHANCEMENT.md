# Enhancement tracker

Requested Apple player fixes and enhancements, recorded on branch `apple-26.1`.
Checked items are implemented in this checkout and verified with the checks
listed below. Unchecked items remain unfinished. Advanced enhancements are
deferred for a later discussion.

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

## Advanced enhancements — deferred

- [ ] **ADV-01 — Explore dynamic segment detection and skip prompts.** Revisit
  later to compare possible solutions before choosing an implementation.
  Detect recaps, theme songs, opening scenes, and credit scenes across **anime,
  TV shows, and movies**. When an applicable segment is detected, display a
  **Skip** prompt button at the **bottom trailing** edge of the player so the
  user can choose to skip it.

  Inspiration:
  [recurring-content-detector](https://github.com/nielstenboom/recurring-content-detector.git).
  Detecting segments while the user is already watching may be difficult;
  begin with small research steps and compare alternative approaches. No
  detection method, processing schedule, or dependency has been selected.

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
  episode preview remain unchecked. ADV-01 remains deferred.
- **Parity:** the nine completed product changes also affect `android` and
  `windows`; their native implementations remain pending. `web` is unaffected.
