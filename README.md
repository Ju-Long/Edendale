# Edendale

Edendale is a free, open-source video player and personal watch/review tracker.
It plays a user's own movies and shows, enriches them with TMDB metadata, and
keeps library and watch data under the user's control.

## Features

- Play local video from individual files, imported folders, and supported
  network sources.
- Build a private library from device-local records and file access.
- Classify files locally before starting optional TMDB enrichment.
- Browse metadata for movies, shows, people, seasons, episodes, trailers, and
  release dates.
- Track progress, ratings, reviews, and user-media state without an Edendale
  account.
- Use the navigation, accessibility, input, storage, and media capabilities
  native to each supported platform.

Trailers open only after an explicit user action. Edendale contains no
analytics.

## Repository model

Edendale uses one long-lived branch per platform. Each branch owns its source,
tests, dependencies, CI workflow, release configuration, and documentation.
There is no shared executable runtime and no platform consumes another
platform's implementation.

| Branch | Responsibility |
|---|---|
| `main` | Universal documentation and repository metadata only |
| `apple` | Apple application and Apple-specific delivery |
| `android` | Android application and Android-specific delivery |
| `windows` | Windows application and Windows-specific delivery |
| `web` | Static website and Web-specific delivery |

The platform branches begin with the same universal Markdown files:

- `README.md` — product and repository overview.
- `AGENTS.md` — rules for automated contributors.
- `CLAUDE.md` — concise agent entry point.
- `DESIGN.md` — shared visual and interaction language.
- `MODEL.md` — optional propose-first task protocol.

`TASKS.md` is intentionally not part of the new repository. Work is tracked
through the platform branch's issue and pull-request workflow.

## Apple development

The `apple` branch contains the native multiplatform Xcode project for
iOS, iPadOS, macOS, tvOS, and visionOS. It requires Xcode 26.5 or newer.

Local credentials live in the gitignored `.secret/` folder at the repository
root, including the App Store Connect `AuthKey_*.p8` used by `ASC/`. Before a
local build, copy `Shared/Example.xcconfig` to `.secret/Secrets.xcconfig` and
add the TMDB read access token:

```sh
mkdir -p .secret
cp Shared/Example.xcconfig .secret/Secrets.xcconfig
```

`WYZIE_API_KEY` is optional and enables online subtitle search; claim a free
key at https://store.wyzie.io/redeem, or enter it later in Settings.

Build the local FFmpeg XCFramework (requires Xcode and downloads FFmpeg 7.1.1
source), then resolve dependencies and inspect the shared schemes:

```sh
bash Vendor/FFmpeg/build-ffmpeg.sh
xcodebuild -resolvePackageDependencies -project Edendale.xcodeproj
xcodebuild -list -project Edendale.xcodeproj
```

The FFmpeg source download is checked against a pinned SHA-256 checksum. If
ffmpeg.org keeps failing, the script falls back to GitHub's archive of the same
release tag, which has its own pinned checksum. FFmpeg's source, intermediate
frameworks, and final XCFramework are gitignored; commit the build script and
Xcode project references, not the generated binaries.
For a faster single-platform setup, use
`bash Vendor/FFmpeg/build-ffmpeg.sh --platform ios` (also accepts `macos`,
`tvos`, and `visionos`). This replaces the XCFramework with only that platform's
device/simulator variants; rerun without `--platform` to restore all platforms.
Build-option changes automatically invalidate cached slices. Use `--clean`
to force a rebuild after changing FFmpeg source.

The build isolates FFmpeg's symbols with Xcode's `ld` and `nmedit` tools so
Edendale's decoder cannot bind to the different FFmpeg bundled in SwiftVLC.
Older XCFrameworks must be regenerated with the command above; the first build
also refreshes cached slices that lack `-fno-common` or PCM audio decoders.
Both app targets keep using the static framework, with no additional runtime
or package dependency.

Run the native macOS build and tests:

```sh
xcodebuild build -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS'
```

Run the unit suite without signing or screen access:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' -only-testing:EdendaleTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Debug unit-test hosts use in-memory library, watchlist, and watch-progress
stores with CloudKit disabled. In place of the `group.com.BaBaSaMa.Edendale`
App Group preferences, which an unsigned macOS host would share with the Debug
app and widgets, they use a per-process `EdendaleUnitTestHost-<pid>` suite that
is emptied at launch and exit (an empty plist may remain in
`~/Library/Preferences`). Normal app launches and UI tests retain their usual
persistence. The command above excludes UI tests; run the full signed test
command when an interactive test environment is available.

For GPU upscaling and rendering regression checks with Metal API validation:

```sh
TEST_RUNNER_MTL_DEBUG_LAYER=1 xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/MetalEnhancementPipelineTests \
  -only-testing:EdendaleTests/EnhancedVideoRenderingTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Upscaling uses a private, render-target-capable output texture for MetalFX and
falls back to Lanczos when caller-provided textures do not meet MetalFX's usage
or storage requirements. These checks require a Metal-capable Mac.

FFmpeg 7.1's AV1 decoder only drives hardware accelerators, and it has no
VideoToolbox one, so `EDFFmpegReader` demuxes AV1 with FFmpeg and decodes it in
its own VideoToolbox session. That decoder is hardware-only (Apple M3, A17 Pro,
or later). Other devices and all simulators report that they cannot decode AV1
video instead of playing its audio alone.

For FFmpeg startup, library compatibility, video dimensions, audio decoding,
seeking, and track-switching regression checks, run:

```sh
bash Vendor/FFmpeg/test-symbol-isolation.sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' -only-testing:EdendaleTests/FFmpegDecoderTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Compile the tvOS app without signing:

```sh
xcodebuild build -project Edendale.xcodeproj -scheme 'Edendale TV' \
  -destination 'generic/platform=tvOS Simulator' CODE_SIGNING_ALLOWED=NO
```

Compile the iOS/iPadOS app without signing:

```sh
xcodebuild build -project Edendale.xcodeproj -scheme Edendale \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

Compile the visionOS app without launching a simulator:

```sh
xcodebuild build -project Edendale.xcodeproj -scheme Edendale \
  -destination 'generic/platform=visionOS Simulator' CODE_SIGNING_ALLOWED=NO
```

The FFmpeg script builds the visionOS Simulator slice for arm64 only, so the
project excludes x86_64 when building for the visionOS Simulator
(`EXCLUDED_ARCHS[sdk=xrsimulator*]`). The iOS and tvOS Simulator slices still
include x86_64.

### Picture in Picture and subtitles

On iOS/iPadOS, starting PiP dismisses the full-screen player while preserving
playback and file access. Restore returns to the same session; closing PiP ends
the hidden session. PiP transport controls refresh from the playback state and
use the media clock. The automatic PiP preference does not disable the manual
PiP button.

Each playback engine keeps one `AVPictureInPictureController` for its PiP
display layer. AVKit holds an unretained reference from the layer to its first
controller, so switching media stops PiP and flushes the layer instead of
replacing the controller.

On macOS, AVKit lays out the PiP panel's video from the display layer's bounds
and never fits it to the panel. While PiP is open, the layer takes the panel's
size (following resizes) and is scaled back over the player, so the panel
shows the whole frame instead of its bottom-left corner.

Embedded FFmpeg subtitles decode alongside audio/video. Changing tracks uses a
bounded seek at the current position instead of scanning the entire file, and
preserves the playing/paused state. ASS fallback rendering strips packet fields
from dialogue text. `PlayerScreen` hosts a native SwiftUI subtitle overlay above
the video and PiP source layer. It follows the playback clock, updates while
paused, displays simultaneous text cues, and positions bitmap cues relative to
the fitted/filled video. Text stays clear of visible transport controls and the
overlay does not intercept gestures. SRT, WebVTT, and ASS downloads use the same
overlay as embedded tracks, including CRLF files and UTF-16 files with a BOM.

Run the playback regressions, then compile the iOS/iPadOS app:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/FFmpegDecoderTests \
  -only-testing:EdendaleTests/SubtitleEngineTests \
  -only-testing:EdendaleTests/PlayerSessionTransitionTests \
  -only-testing:EdendaleTests/EnhancedVideoRenderingTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
xcodebuild build -project Edendale.xcodeproj -scheme Edendale \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

The subtitle fixture contains generated test video/audio with SRT and ASS
tracks; see `EdendaleTests/Fixtures/generate-subtitle-fixture.sh` to reproduce it.
On an iPhone/iPad, additionally verify manual PiP, a single pause/play tap,
return to the app, restore/close, and subtitle selection on the original media.
These are Apple playback fixes; other platform branches require no rule change.

For the subtitle overlay, download/import, and decoder regressions (including
rendered-pixel checks), run:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/PlayerSubtitleOverlayTests \
  -only-testing:EdendaleTests/SubtitleEngineTests \
  -only-testing:EdendaleTests/FFmpegDecoderTests \
  -only-testing:EdendaleTests/WyzieSubtitleTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -only-testing:EdendaleTests/PlayerSubtitleOverlayTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

The second command requires the named simulator; choose an installed iPhone or
iPad simulator if it is unavailable. These tests use synthetic embedded tracks
and downloaded-file payloads without contacting the subtitle provider. A PNG
preview of the actual overlay is written to the test host's temporary directory;
its path is printed in the test log.

### Downloaded subtitles

A subtitle downloaded from **Player Adjustments → Online Subtitles** is kept
on the device in `Caches/Subtitles`, and a local SwiftData store
(`SubtitleCache`, never synced) records which video it belongs to, its file
name inside that folder (the app container's path changes across updates),
and when it was last used. When the same video plays again, its kept
subtitles are added to the subtitle list without a search or a download; the
one used last is selected unless the title's saved choice is an embedded
track or Off. Picking a result the device already holds, for this video or
another, reuses the file instead of downloading it again.

A video is identified by its TMDB match (movie, or show plus season and
episode) and its file name, ignoring case and Unicode form. The server
address, port, scheme, and folder are not part of it, so one file reached
through several endpoints of the same device (a LAN IP and a Tailscale IP, a
host name and an address) shares its subtitles, as does a local copy.
Another release of the same title, whose timing may differ, keeps its own.

At each launch, subtitles neither downloaded nor reattached for more than a
month are deleted, as are records whose file the system purged and files no
record refers to (including downloads saved before records were kept). Run
the cache regressions:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/SubtitleCacheTests \
  -only-testing:EdendaleTests/WyzieSubtitleTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Settings → Subtitles sets the text subtitle font, colours, and background
opacity. On tvOS each of these rows opens a list of its choices, the way the
system Settings app does. Android and Windows need
independent native implementations of the subtitle cache; the static Web
branch is unaffected.

The playlist opens at the current file and highlights current or focused rows
with larger text on a white background. Identified episodes and the current
identified movie include landscape artwork and stacked title/playtime details;
unknown sibling files retain their filename fallback. Player Adjustments
offers video and audio track selection when a file contains multiple tracks
of that type.

Settings → Audio includes adjustable Flat, Movies (default), Music, Dialogue,
and Night Mode equalizer profiles. On iPhone, iPad, Mac, and Apple Vision Pro
the ten bands are always shown as a graph whose center line is 0 dB: choosing
a profile moves each band's point to that profile's level, and dragging a point
up or down adjusts its band in 1 dB steps (the held point fills and shows its
level). The preamp slider likewise shows its level before the booster. Profile
selection and adjustments persist locally; changing profiles resets the
adjustments. Player Adjustments also
offers an Audio Booster, off by default, that adds 10 dB of preamp gain within
the equalizer's bounds and restores the unboosted setting when disabled.
Profiles and booster changes apply during playback and carry over between files.
Night Mode changes the frequency balance; it does not compress dynamic range.
These controls do not affect E-AC-3 audio (see [Surround and spatial
audio](#surround-and-spatial-audio)) or visionOS spatial/multiview playback in
the system player. On tvOS, **Show equalizer** lists the preamp and the ten
bands, and each opens a list of levels in 1 dB steps.

Episodes automatically advance to the next stored episode in season/episode
order, including the next season and stored specials, without replaying an
alternate file of the same episode. A newer manual playback request cancels
pending automatic advancement. Finished episodes retain their completed watch
state. Continue Watching suggests the next stored episode after the furthest
completed one when that show has no episode already in progress. This also
works after removing a watched file and does not create watch progress for the
unwatched suggestion. Duplicate show records produce one next-up card.

The Movies & Shows hero leads with the title watched most recently. Its
**Resume Playback** goes straight to the player with what Continue Watching
would play for that title: the movie from its saved position, or the show's
episode watched last (the next stored episode once that one is finished),
from the copy played last. The store is read at the tap, so progress synced
from another device since the hero loaded counts. A title with nothing to
resume in the library opens its detail page instead. The hero's Continue
Watching scene also follows the store: progress that syncs in, or playback
here, changes its title and time left without a relaunch. On Apple TV the
hero stays one button that opens the detail page.

Run the hero tests:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' -only-testing:EdendaleTests/HeroResumeTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Android and Windows need independent native implementations of hero resume;
the static Web branch is unaffected.

When a TV episode has a locally stored successor, an **Up Next** card appears
at the top right during its final 30 seconds. It shows artwork, the episode
code and title, and starts that episode when selected. The card clears when
seeking earlier, enabling Loop Video, or ending playback, and remains
reachable when controls are visible. Movies, files without episode context,
unknown durations, and the final stored episode do not show it. The native
visionOS system-player route uses the same preview rule. Artwork has a
readable fallback, focus is visible, and transitions honor Reduce Motion.

### Titles in several sources

One file can reach the library more than once: from two sources, or from
one share linked through two endpoints (its LAN host name, such as
`longs-mac-studio.local`, and its Tailscale address). Each copy keeps its
own library record, tied to the others by its TMDB match, or before it has
one by its parsed title (and year). The Downloaded grids and Search's
library results show one card per title; Remove on a card held by several
sources offers one Remove per source.

The detail page's **Play From** chevron beside Play lists every copy of a
movie, and tapping an episode held by several sources opens the same menu
before the player starts. Each choice names the source and its endpoint
(the server and any port given, or the account for cloud sources), then
the kind and file name, and marks the source played last and any source
the last scan couldn't reach. Playing a copy remembers it on this device
(`lastPlayedAt` on the movie or on the show copy, library data that never
syncs). Play, Continue Watching, and widget and Shortcuts routes start the
reachable copy played last, else the first reachable one.

Automatic advance, the Up Next card, and its preview stay on the copy that
is playing. Only an episode that copy lacks comes from another copy (the
first whose source is reachable), and the copy after that returns to the
chosen source as soon as it has the next episode, so an episode stored in
only one source is never skipped.

**Clear Progress** deletes a saved position: from a Continue Watching card's
context menu, an in-progress episode card's context menu on the show page,
or an in-progress movie's poster on the Movies page. It is written as a
removal (a tombstone), so other devices and sync spaces drop it too.

**Mark Watched** on an episode (a show page's episode card, or the TMDB
season browser) records the episode's show and its season and episode
numbers, as finishing it in the player does. Before, a hand-made mark
carried only the episode's TMDB ID, so Continue Watching never suggested
the episode after it and the sync file, which names an episode by show,
season, and number, left the mark out.

TMDB enrichment no longer runs per import only. A sweep of the whole
library runs at launch, after every import or rescan, and on every visit
to Downloaded, and looks up each movie and show without a TMDB match and
each episode without one whose show is matched, including episodes added
to a show that was matched earlier (which previously stayed unmatched and
saved no watch progress). A title is indexed once it has a TMDB ID. One
TMDB answered without a match for is marked (`metadataCheckedAt`) and asked
about again after seven days, or at once on a forced rescan (⌘R or a
source's Rescan). A lookup that fails because TMDB is unreachable, refuses
the credential, or rate-limits marks nothing and ends that sweep, so the
next sweep resumes where it stopped. The sweep runs on the library's own
task: leaving the page doesn't cancel it, and requests made while it runs
queue a single extra pass.

The sweep keeps the main thread free for the viewer. Its TMDB lookups (the
keychain read for a connected account, the request, and the JSON decoding)
run off the main actor. What they find is written to the library in
batches, at most one every two seconds, each with a single save, because
every save refreshes each page showing the library. A batch waits while
the viewer scrolls a library page or asks to play, and for as long as the
player is open, then goes in once nothing has happened for 1.5 seconds
(`InteractionGate`); the lookups carry on meanwhile. The widget, Top Shelf,
and Siri snapshot is rebuilt once a burst of library changes has been quiet
for three seconds and nothing is scrolling, and only the episodes that fit
its 500-item catalog are built. Downloaded builds its Continue Watching
shelf once per refresh, not once per movie card. On a library of 400 movies
and 1,000 episodes in a Debug build, matching 360 titles at 25 ms per
lookup used to keep the main thread busy 97% of the time, with a freeze of
about 280 ms per title, and took 102 seconds; it now takes 10 seconds, with
the main thread answering within 0.2 ms at the 95th percentile and its
longest pause at 115 ms.

Run the regressions:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/LibraryEnrichmentTests \
  -only-testing:EdendaleTests/InteractionGateTests \
  -only-testing:EdendaleTests/WidgetSnapshotTests \
  -only-testing:EdendaleTests/MultipleSourcesTests \
  -only-testing:EdendaleTests/PlaybackSourcesTests \
  -only-testing:EdendaleTests/EpisodeProgressionTests \
  -only-testing:EdendaleTests/ContinueWatchingTests \
  -only-testing:EdendaleTests/UpcomingEpisodePreviewTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

These are product rules: the Android and Windows branches need their own
native implementations; the static Web branch is unaffected.

### Surround and spatial audio

FFmpeg playback keeps a track's channels. Decoded audio reaches the renderer
as stereo, 5.1, or 7.1 PCM, the smallest with as many channels as the source,
labelled with the matching Core Audio channel layout, so the system downmixes
it for the current output, sends it to an HDMI receiver, or spatializes it on
supported headphones. The back surrounds of 5.1 play as its surround pair,
6.1's back center splits between the rear pair, and layouts beyond 7.1 fold
into 7.1. Mono and stereo play as before.

E-AC-3 reaches the renderer undecoded on devices with a system E-AC-3 decoder,
object audio (JOC) included. Each track is described with the `dec3`
configuration that the system's own E-AC-3 parser derives from its first
packet, which flags object audio the way an MP4 or HLS stream would. A track
that can't be described this way, or a device without the decoder, decodes in
FFmpeg to surround PCM instead, without the object audio; a packet that isn't
one six-block access unit is skipped. After a seek, passed-through sound resumes with the first whole unit
at or after the target, at most 32 ms after the picture. AC-3, DTS, TrueHD,
and other codecs still decode in FFmpeg.

Audio enhancement doesn't apply to E-AC-3: equalizing needs decoded audio,
which would drop the object audio. FFmpeg playback passes it through
untouched, and AVFoundation playback installs no equalizer tap when the file's
first audio track is E-AC-3. Settings → Audio says so. Enhancement
of decoded surround covers every channel.

When the renderer drops its queued audio because the output changed, or the
output hardware changes format (for example, a receiver switching on), FFmpeg
playback refills audio from the playhead so the new output is configured for
the track's channels. On iOS, tvOS, and visionOS the audio session declares
multichannel content support.

The first audio timestamp after opening or seeking is taken as the stream
gives it, and later ones continue the sample clock. Earlier, a first frame
that started within 50 ms of a seek target was moved onto the target and
played that much early or late against the picture.

Run the audio regressions:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/FFmpegDecoderTests \
  -only-testing:EdendaleTests/AVFoundationDecoderTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

`EdendaleTests/Fixtures/decoder-eac3.mkv` is synthetic: 5.1 E-AC-3 with a
different tone on each channel, plus a stereo AC-3 track. Regenerate it with
`bash EdendaleTests/Fixtures/generate-eac3-fixture.sh`, which needs an ffmpeg
CLI with the eac3 and ac3 encoders. Passthrough tests run only where the
system can decode E-AC-3. Whether object audio reaches the listener depends on
the device and route (a receiver, or headphones with spatial audio), so check
it on hardware. Keeping E-AC-3 out of audio enhancement is a product rule:
Android and Windows need their own native implementations if they adopt it;
the static Web branch is unaffected.

### Speed changes and seeking

Changing the playback speed, from Player Adjustments or by holding a side of
the video or the Siri Remote's touch surface, only retimes the media clock.
Decoded frames stay queued, so the picture never blanks. A seek or track
switch keeps the current picture on screen until the first frame at the new
position has decoded; only a media change clears the video surface. FFmpeg
seeks decode from the previous keyframe but skip the frames before the target
that no other frame references, shortening the post-seek freeze with hardware
decoding. Frames from the target on are unchanged.

FFmpeg playback restarts the clock only once that first frame is on screen, so
picture and sound resume together; a seek while paused shows it as well.
FFmpeg decoding never waits for the audio renderer. Decoded audio that the
renderer cannot take yet is queued, up to 4 s ahead of playback, so video keeps
decoding when a file stores its audio a second or more ahead of the video.
Video decodes only while fewer than 12 frames are queued, and reads for audio
alone hold video packets undecoded (up to 32 MB), so 60 fps files and files
that store video ahead of audio, such as broadcast TS, no longer drop frames.

Run the seek and speed regressions:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/FFmpegDecoderTests \
  -only-testing:EdendaleTests/EnhancedVideoRenderingTests \
  -only-testing:EdendaleTests/PlayerTransportStateTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Keep the Mac's display awake while they run: the macOS display link drives
FFmpeg playback time, so tests that wait for the clock stall while the display
sleeps. These are Apple playback fixes; other platform branches require no rule
change.

### Returning from the background

iOS invalidates hardware decoder sessions while an app is in the background
without Picture in Picture. On return, FFmpeg playback refills video at the
current position with a new decoder. A decoder that fails mid-stream is
replaced once and resumes at the next keyframe; if the replacement fails again
before decoding a frame, software decoding takes over.

The video surface never draws from inside a SwiftUI update. While paused, it
redraws once on a later main-queue turn, and on iOS and tvOS drawing resumes
only when the app is active again. A draw inside an update, after a return
from the background, used to block the main thread waiting for a Metal
drawable and froze the app. The regressions above cover both; on a device,
pause an HEVC `.mkv`, leave the app for ten seconds, return, and tap Play.

SMB playback reads through a buffered connection that reconnects after a
drop, which a lock or a VPN such as Tailscale changing networks causes. The
libsmb2 inside libVLC 4 (6.1) frees a failed `smb2_close` request's callback
data while the request is still queued, then frees it again when the
connection is destroyed. That heap corruption crashed the app on return from
the Lock Screen or background. `EDSMBFile` therefore never calls
`smb2_close` (logging off closes the file on the server), destroys a
connection that has failed without another round trip, and reads into
buffers it frees only after the connection is gone, since a failed request
can still point at them. Its idle keep-alive queries the file: libsmb2 6.1's
`smb2_echo` always fails, which dropped every paused connection after 20
seconds. On a device, pause an SMB video, lock for a few minutes (or switch
between Wi-Fi and Tailscale), unlock, and play.

### Saving progress while playing

The player saves the position every ten seconds of playback. While the
player is up, that save reaches the watch store, Core Data, and iCloud at
once. The pages behind the player refresh only when it closes or moves to
Picture in Picture, and the sync file is written when playback stops (or
at the five-minute sync).

Each save used to refresh every page showing the library on the main
thread. It did so again when Core Data reported the save back to the watch
and user-media stores, as it does for the app's own saves, and the widget
snapshot was rebuilt three seconds later. A sync could also start, timed to
land just before the next save. With 400 movies and 1,000 episodes, a Debug
build on a Mac stalled about 60 ms after every save and again three seconds
later, so the video dropped frames every ten seconds. Now no save causes a
pause over 8 ms, and both stores skip a reload that changes nothing.

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/WatchProgressPlaybackSaveTests \
  -only-testing:EdendaleTests/SyncCoordinatorTests \
  CODE_SIGNING_ALLOWED=NO
```

### Intro, recap, and credits prompts

Enable **Settings → Player Controls → Playback → Skip Prompts**, or **Player
Adjustments → Playback → Skip Prompts**, to use community timestamps from
[TheIntroDB](https://theintrodb.org/docs). The setting defaults off. The old
90-second recap and final-three-minutes credits auto-skips have been removed;
their saved preferences do not enable the new network feature.

During a known segment, a **Skip Intro**, **Skip Recap**, or **Skip Credits**
button appears at the bottom trailing edge, independently of hidden playback
controls. Press the button to skip; playback never skips automatically. On
keyboard platforms, **S** activates the visible prompt. On tvOS, **Down** from
the hidden-controls surface focuses the prompt when one is available. Prompts
hide while scrubbing or while a side panel covers the video; the macOS panels
dock beside the video instead, so prompts stay available there. Bounded
credits seek only to that range's end, preserving gaps for additional scenes.
A terminal credits skip marks the item complete and advances to the next
stored episode, ends playback if none exists, or restarts the file when Loop
Video is enabled.

The native Swift client calls `GET https://api.theintrodb.org/v3/media` directly
from the device after the player reports a finite duration. Movies use their
TMDB ID; episodes use the **show's** TMDB ID plus TMDB season/episode numbers.
Requests include the video duration in milliseconds to help match release
versions. No account, API key, filename, video upload, library scan, or server
proxy is involved. The provider receives the media identifiers, runtime, and
the device's public IP address. See its
[privacy policy](https://theintrodb.org/docs/privacy) and
[usage terms](https://theintrodb.org/docs/terms).

Only a small in-memory cache exists during the playback session; timestamps
are not saved to the library, disk, watch progress, or iCloud. Closing playback
or disabling prompts clears the cache. Missing data, network errors, invalid
ranges, and rate limits leave normal playback available without a prompt.
Playback and initial import never wait for this service.

Coverage depends on community submissions and the local file's edition; the
provider can fall back to the most popular edition even when duration is sent.
Unidentified files, season-zero specials, and runtimes beyond the provider's
six-hour timestamp range receive no lookup. Anime uses TMDB episode numbering;
combined episodes and alternate numbering are not remapped. Preview segments
and local audio/video detection are outside this first stage. Prompts work in
the SwiftVLC player on iOS, iPadOS, macOS, tvOS, and standard visionOS playback;
visionOS spatial/multiview playback through AVKit needs a separate integration.

The macOS unit command above includes API decoding, identity matching, request
deduplication, failures, stale responses, preference migration, and real VLC
skip/progress/loop regression tests. Android and Windows need independent
native implementations of the same behavior; the static Web branch is unaffected.

### Player controls

**Settings → Player Controls** sets how far a skip jumps and how fast playback
runs while a side of the video is held. **Skip Back** and **Skip Forward**
each offer 10, 15, or 30 seconds (default 10) in a segmented control drawn
with the matching arrow-rotate glyphs; VoiceOver reads each segment's length.
On tvOS each opens a list of the lengths.
The lengths apply to double-taps on either side of the video, the Left and
Right Arrow keys, Siri Remote swipes and the tvOS timeline, the VoiceOver skip
actions, and the Lock Screen and Control Center skip buttons, which relabel as
soon as a length changes. Picture in Picture keeps the system's skip length.

**Hold Left Side** and **Hold Right Side** set the press-and-hold speeds from
0.25× to 3.00× in quarter steps (defaults 0.5× and 2×) with a stepper, or a
list of speeds on tvOS. Touch and hold on iOS and iPadOS, pinch and hold on
visionOS, click and hold in the macOS player, or rest a thumb on either side
of the Siri Remote's touch surface; the speed reverts on release. A short
macOS click still shows or hides the controls, and a drag does neither. The
preferences are stored on the device and are not synced.

Run the preference and hold-rule tests:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/PlayerControlPreferencesTests \
  -only-testing:EdendaleTests/PlayerLogicTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Android and Windows need independent native implementations of these
settings; the static Web branch is unaffected.

### Player guide

The first time a video opens on a device, a player guide covers it and
playback waits until the guide closes; a guide opened mid-playback pauses and
resumes the video the same way. Each page pairs a looping illustration with
one control, chosen for the platform's input: tap, double-tap, swipe, hold, and
hold-and-slide on iOS and iPadOS (keys too while a keyboard is attached); the
same with look-and-tap and pinch gestures on visionOS; click, click-and-hold,
and the key commands on macOS; and clickpad, swipe, spin, resting thumb,
Play/Pause, and Back on tvOS. The tvOS spin page plays the remote's jog by the
player's own rules: the first circle only engages it, then the playhead's
chevrons follow the thumb's speed as it winds forward and back. The last page
explains the toolbar chips. The pages quote the current **Player Controls**
skip lengths and hold speeds.

The illustrations are SwiftUI drawings rather than recorded GIFs, so they
follow those settings, the archive palette, and every localization. Under
Reduce Motion each holds a single frame. Swipe, the arrow keys, or the Back and
Next buttons turn pages. Escape, the close chip, or tvOS Back close the guide.
Reopen it with the info chip in **Player Adjustments** or the player guide
entry at the end of **Settings → Player Controls**. Closing the guide stores
`player.guideSeenVersion` in the device's preferences; raise a platform's
number in `PlayerGuide.version(for:)` to show its revised guide once more
(tvOS is at 2 for the spin page, so Apple TV shows the guide once again).

Run the guide tests:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/PlayerGuideTests \
  -only-testing:EdendaleTests/PlayerTransportStateTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Android and Windows need their own native guides for their own controls; the
static Web branch is unaffected.

### Time and battery in landscape

A Face ID iPhone draws no status bar in landscape, whatever an app asks for.
While the player's controls are up in landscape, its top bar shows the time
(in the device's 12- or 24-hour format) and the battery level, with a bolt
while charging, above the title. They hide with the controls, as the status
bar does in portrait. Across the app Edendale asks the system to keep its
status bar in landscape, which an iPhone with a Home button can honor; when
the system shows one (the top safe-area inset is not zero), the player leaves
its own out. A level the system can't report, as in the Simulator, leaves
the battery out.

Android and Windows decide their own landscape system bars; the static Web
branch is unaffected.

### Tabletop playback (iPhone Duo)

Stand an iPhone Duo half-open in portrait, like a small laptop, and the
player splits at the fold the way the system player does: the picture fills
the upper half and the controls lie on the flat lower half. From the fold
down they are the close button and toolbar chips, the title, the timeline,
skip back, play/pause and skip forward (drawn with the **Player Controls**
skip lengths), and the player's volume. These controls cover nothing, so they
stay up instead of hiding after five seconds. The playlist and Player
Adjustments panels open in their place below the fold, and a tap on the
picture closes them. The picture keeps its gestures, the Up Next card, skip
prompts, subtitles, and the gesture HUD. Opening the phone flat, closing it,
turning it to landscape (where the fold runs down the middle of the picture),
or sharing the screen with another app returns the full-screen layout.

iOS 27.1's `UIHingeInteraction` says whether the hinge is closed, partly
open, or fully open, but not where the fold lies. The Duo's inner screen folds
across its middle in portrait, so `PlayerTabletopLayout` splits at the
window's midline when the window is portrait and fills the screen.

Run the tabletop tests:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=iOS Simulator,name=iPhone Duo' \
  -only-testing:EdendaleTests/PlayerTabletopTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO ARCHS=arm64
```

Android needs its own native implementation for foldables (Jetpack
WindowManager reports a half-opened `FoldingFeature`); Windows and the static
Web branch are unaffected.

### Immersive theater (visionOS)

On Apple Vision Pro the player's toolbar has a **Theatre** chip (the
clapperboard). It opens one of three rooms around the viewer as a full
immersive space, with the viewer seated near the front so the screen fills
54–62° of the view: **Movie Theatre** (a stadium cinema, row 3 of 11, 12 m
screen 10.55 m ahead), **Projector Room** (a 1920s hall, row 2 of 6, 5.6 m
screen 5.5 m ahead), and **Performance Theatre** (a proscenium stage, row 4 of
14, 11 m screen 9.25 m ahead). It opens only when the viewer picks a room.
Picking another room while one is open swaps it in place.
**Leave Theatre**, the Digital Crown, closing the player, or the next item
going to the system player (spatial and multiview video) closes it.

- **Picture.** The screen shows the frames the player draws, after Video
  Enhancement, so paused frames, seeks, and Up Next follow it without a second
  decoder. `EnhancedVideoView` hands each finished frame to
  `VisionTheaterScreenFeed`: the player window's view until that window has
  closed, then the theater's own, hidden under the floor. The feed draws it,
  fitted and letterboxed, into a RealityKit `LowLevelTexture` of 1440 rows in
  the screen's shape, under an unlit, untone-mapped material. RealityKit
  reads a `LowLevelTexture` as Display P3, so the feed converts the player's
  sRGB pixels to P3 primaries; colours then match the 2D window. The canvas
  has no mipmaps: from the best seat each screen is about 2,000–2,300 display
  pixels wide, and RealityKit sampled the lower levels of a mipmapped canvas
  as blank.
- **Subtitles** are the 2D player's subtitle overlay, laid out in the screen's
  shape and floated 2 cm in front of the picture.
- **Controls.** While the theater is open the controls are on a panel of
  their own (`VisionTheaterDock`) that never hides: close, Theatre,
  Playlist, Adjustments, the title, the timeline, skip back, play/pause, skip
  forward, a Skip Intro prompt and the Up Next card. A playlist or
  adjustments panel opens in its place. It floats 0.65 m from the eyes and
  45° below eye level, tilted to face them, so looking 45° down meets it
  centred, and it follows the way the viewer faces: ARKit's world tracking
  (no permission prompt) reports the head's pose each frame. Turns under 12°
  and moves under 15 cm leave it still, so it holds while the viewer looks
  across it or leans in to touch it; a bigger turn brings it back in front in
  about half a second. The geometry is `TheaterLayout.Dock` and
  `DockFollower`.
- **Player window.** The main window closes once the theater is open, so
  nothing floats in the room but the picture and the controls. visionOS has
  no way to hide a window, only to close it. Leaving the theater, closing the
  player, or the Digital Crown opens it again, in front of the viewer, with
  the player still playing (or the library, after closing the player). The
  library comes back on its first tab. The window's `WindowGroup` is named
  `main` on visionOS for this.
- **Seat panel.** A second panel (`VisionTheaterSeatPanel`) floats to the
  left of the controls, at the same distance and 45° below eye level, and
  follows the head with them. Turning left to look at it holds both still,
  up to 46° further than on the right. It has:
  - **Seats**, a map of the room's seats from above with the screen on top:
    the viewer's seat in gold, the best seat (the default, near the front)
    outlined. Pinch a seat to sit in it; hold the pinch and move the hand to
    slide along the seats, named as they light, and let go to sit. **Best
    Seat** goes back. Each room marks every seat with an empty,
    `Seat_<row>_<number>` under `SeatMap`, on the floor under it
    (`TheaterSeatMap`). The chosen seat is put on the immersive space's
    origin, the floor under the viewer, and the room turns about the
    vertical until the screen's centre is straight ahead, so an end seat
    sees the screen at an angle without looking over a shoulder
    (`TheaterLayout.roomPose`). Changing seats fades the room out for 0.2 s
    and back in for 0.3 s: a room sliding past someone sitting still makes
    them queasy. Each room remembers its seat on the device.
  - **Lights**: **Normal**, **Dim**, or **Dark**, for every room, remembered
    on the device. The rooms' light is baked into emissive materials, so the
    lights scale every room material's emissive intensity, to 30% or 6%: the
    screen's baked glow on the room dims with the house lights, and the
    picture and the Projector Room's beam stay as they are.
  - **Recentre** turns the theater to where the viewer looks, behind the
    same fade. Within 20° of level it only turns, and its floor stays on the
    real floor. Lying back or reclined, it tilts back with the viewer, so
    the screen is in front of their eyes as it is from the seat, with the
    seat's eyes 1.2 m above its floor placed on theirs; the panels then sit
    below their line of sight as they lie, towards the chest. A head tipped
    to one side doesn't tip the theater (`TheaterLayout.recenterPose`).
    visionOS doesn't tilt an immersive space for a viewer lying down, so the
    app does. Recentring lasts until the theater closes.
- **Placement.** The picture is a quad laid over the room's `Screen`, 5 mm in
  front of it. The picture, the subtitles, and the room move together as
  one entity, which carries the seat's pose, inside another that carries
  the recentring.
- **Not offered** in the system player route for spatial and multiview video
  (`VisionNativePlayerScreen`).

The rooms are USDZ exports in `Shared/Theaters/`. The project's
synchronized-folder exceptions give them only to the visionOS build: about
22 MB, which the iOS, macOS, and tvOS apps don't carry. Their sources are on
the `scenes` branch, and each export commit names the `scenes` commit it came
from. To export them again, run this from a checkout of `scenes`, with
Blender 5.2 LTS, then copy `out/*.usdz` here:

```sh
for name in movie-theatre projector-room performance-theatre; do
  Blender --background scenes/$name/$name.blend \
    --python tools/export_usd.py -- out/$name.usdz
done
```

The menu and window strings are in `Shared/Theater.xcstrings`, in every app
locale. Frame rate, heat, and comfort haven't been measured on a headset yet;
everything above was checked in the visionOS 27.2 simulator, except the
panels following a turn of the head and recentring while lying back, which
the simulator can't script and the unit tests cover. In RealityKit the
Projector Room's haze beam draws as a flat grey wedge, unlike Blender's
preview; that needs a change to the scene on `scenes`.

Run the theater tests on macOS (geometry and bundled exports) and on a visionOS
simulator (which also loads every room, finds its `Screen`, `Seat`, and every
seat of its seat map, and checks that the screen is straight ahead from each):

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/TheaterLayoutTests \
  -only-testing:EdendaleTests/PlayerGuideTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=visionOS Simulator,name=Apple Vision Pro' \
  -only-testing:EdendaleTests/TheaterLayoutTests \
  -parallel-testing-enabled NO ARCHS=arm64 CODE_SIGN_IDENTITY=- \
  CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=
```

The Quest theater is built natively on `meta-horizon` from the same scenes'
GLB exports. Android phones, Windows, and the static Web branch have no
theater.

### macOS navigation, shortcuts, and player panels

The macOS library window uses a split view instead of the adaptive tab view.
Its sidebar lists Movies & Shows, Watchlist, Downloaded, and Search; Settings
opens in its own window from **Edendale ▸ Settings…** (⌘,) instead of a
sidebar row. That window has a sidebar of its own, like System Settings, listing
General, Player Controls, Audio, Subtitles, Library, Sync, Accounts, Parental
Controls, and About beside the open category. (iPhone and iPad open Settings as
a sheet from the gear button in each page's toolbar. There, and on Apple TV and
Apple Vision Pro, Settings opens on the list of categories, and each one opens
its own page; only the Mac has General. iPhone, iPad, Apple TV, and Apple
Vision Pro show Parental Controls and About in place at the end of that list
instead of as pages, in the system's grouped list.) On Apple TV, Back always
returns from a pushed page, even when a swipe off the page's edge has opened
the tab sidebar over it, and a cast member on a title's page opens that
person's page in place rather than in the Search tab. Parental Controls holds Young Audience Friendly, a local preference that shows only titles rated
PG or PG-13 (or TV-PG/TV-14). Watchlist and Downloaded are pages of their own and also
disclose their sections as child rows, so one section opens without scrolling
the whole page:

- **Watchlist:** Movies and TV Shows.
- **Downloaded:** Continue Watching, Movies, and TV Shows. The Continue
  Watching page lists every resumable title in a grid (the shelf keeps its
  12-item cap). The Movies page lists every movie, including those also in
  Continue Watching.

A child row appears only while its section has titles for the current
audience setting. If the open section empties, the sidebar returns to the
parent page. Choosing a row always opens that page at its root, and the
search query survives visits to other pages.

On iPhone and iPad (iOS 26.1 and later) the Downloaded sections extend out
of the tab bar instead: while the Downloaded tab's root page is showing and
at least two sections have titles, the tab bar's bottom accessory offers
All, Watching (Continue Watching), Movies, and TV Shows. It sits above the
tab bar on iPhone and at the foot of the screen on iPad, and becomes a menu
naming the current choice beside a minimized tab bar. It hides on pushed
pages and on other tabs, keeps its choice, scrolls the page back to the top
on a change, and falls back to All when the chosen section empties; the
page keeps the Downloaded title. A SwiftUI `TabSection` can't do this: on
iPhone its tabs become separate top-level tabs (iOS 26.5 and 27.2 alike),
and its header and `defaultSectionExpansion` apply only in a sidebar.
Apple TV, Apple Vision Pro, and iOS before 26.1 have no tab bar accessory,
so there the Downloaded page shows the same choices as chips above its
content. Android and Windows need their own native section picker; the
static Web branch is unaffected.

Menu bar commands:

| Command | Shortcut | Available |
|---|---|---|
| Edendale ▸ Settings… | ⌘, | Always |
| View ▸ Hide/Show Sidebar | ⌘B | Library window |
| File ▸ Add Media Folder… | ⌘N | Downloaded pages |
| File ▸ Link Network Source… | ⌥⌘N | Downloaded pages |
| File ▸ Rescan Library | ⌘R | Downloaded pages with linked sources |

The File commands replace File ▸ New Window; clicking the Dock icon reopens a
closed library window. Rescanning uses the same duplicate-safe sweep that runs
when the Downloaded page appears and shows a status row while it runs.

The Link Source sheet opens with the server address focused. Tab and
Shift-Tab move between the address, username, and password fields. Return
connects once all three are filled and otherwise moves to the first empty
field; a guest connection (no username or password) is still one click on
Connect.

In the Now Playing window, the playlist and Player Adjustments panels dock as
a trailing sidebar: the video narrows beside the panel instead of being
covered. Clicking the video leaves the panel open. Its close control, the
toolbar control that opened it, or Escape close it; Escape then leaves the
player. With a panel docked, playback controls still auto-hide and the Up
Next card and skip prompts remain available. Other platforms keep the
overlaid panel.

On macOS, each season's episode shelf in a show's detail page has a heading
rule that works as the shelf's scroll indicator and scrubber, like the shelf
headings on Movies & Shows. The TMDB episode browser for shows that are not
in the library uses the Episodes heading the same way. Drag the gold thumb or
click the rule to move through the season.

These are macOS presentation changes; other platform branches require no rule
change.

### Storage providers

Settings → Library → Link Source adds network and cloud sources. Each connects
straight from the device to the provider. Edendale has no server or account of
its own, logins and refresh tokens stay in the Keychain, and nothing in a source
is uploaded or changed. The only files Edendale ever writes are its own sync
files, and only to the location chosen in Settings → Sync (below). Accounts and
logins sync through iCloud Keychain, except to Apple TV, which gets them from a
nearby iPhone or iPad (Continue on iPhone or iPad).

| Source | How it links | What Edendale reads |
|---|---|---|
| SMB, SFTP, WebDAV | A server login | Folder listings and the files you play |
| NFS | No login (AUTH_SYS) | Folder listings and the files you play |
| S3-compatible storage | An access key | Object listings and the files you play |
| Google Drive | Google sign-in with only the `drive.file` scope; Google's picker opens during sign-in | Only the files you pick: their names, sizes, dates, and video durations, and the bytes you play |
| OneDrive | Microsoft sign-in (`Files.Read Files.ReadWrite.AppFolder User.Read offline_access`); a code on Apple TV | Folder listings and the files you play; sync files in `Apps/Edendale/` |
| Dropbox | Dropbox sign-in (`files.metadata.read files.content.read files.content.write account_info.read`) | Folder listings and the files you play; sync files in `/Edendale/` |

Google Drive has nothing to browse. Each account has one source, "Videos you
chose", listing every file picked with that Google account on any device or
platform (Google grants picks per account, not per sign-in). Choose More Videos
on the source or in Settings → Accounts reopens the picker; Apple TV never
opens it and lists what was chosen elsewhere after a rescan. Removing a Drive
video keeps its file ID on that device so rescans skip it; picking it again
brings it back. A picked folder grants only the folder, so the picker offers
files only. Builds from before `drive.file` used `drive.readonly` folder
sources: the first launch of this build removes those accounts and sources and
asks once to link Google Drive again (ENHANCEMENT.md J.14).

Client IDs go in `.secret/Secrets.xcconfig` (see `Shared/Example.xcconfig`).
They aren't secrets for these PKCE clients, and no client secret ships:

- **Google:** in one Google Cloud project, enable the Google Drive API and the
  Google Picker API, create an OAuth client of type iOS for
  `com.BaBaSaMa.Edendale` (Google uses it for macOS too), and list only
  `https://www.googleapis.com/auth/drive.file` under Data Access. It is
  non-sensitive, so it needs no scope verification, demo video, or security
  assessment. While the app is in Testing, add test users; their refresh
  tokens expire after 7 days. Set `GOOGLE_DRIVE_CLIENT_ID`.
- **Microsoft:** an Entra public client app for personal and work or school
  accounts, with the redirect `msauth.com.BaBaSaMa.Edendale://auth` and public
  client flows on (Apple TV's device code). Set `ONEDRIVE_CLIENT_ID`.
- **Dropbox:** a scoped app with Full Dropbox access; it redirects to
  `db-<app key>://2/token`. Set `DROPBOX_APP_KEY`.

Checks that need hardware and a real account: the picker inside the sign-in
sheet on an iPhone, Choose More Videos, playback, removing a video, and handing
the account to Apple TV (DeviceDiscoveryUI lists no devices in Simulator).

The `Files.ReadWrite.AppFolder` and `files.content.write` scopes arrived with
27.1's sync (below). Until the Entra app registration lists the first as a
delegated permission and the Dropbox App Console enables the second, consent
fails for new OneDrive and Dropbox sign-ins; accounts signed in before 27.1
keep working as sources but must sign in again before they can hold sync
files (Settings → Sync says so).

### Sync across devices

Settings → Sync keeps watch state in step with the user's other devices, on
every platform, through storage the user owns, as
[FEATURES-27.1.md](https://github.com/Ju-Long/Edendale/blob/main/FEATURES-27.1.md)
§5–§6 and
[SYNC-PROTOCOL.md](https://github.com/Ju-Long/Edendale/blob/main/SYNC-PROTOCOL.md)
on `main` (commit `915b40d`) specify. Off by default. Each device writes one
readable JSON file, `Edendale/<space name>/<device-id>.json`, holding its whole
merged watch state, reads the other devices' files, and merges them locally.
Only watch records travel: progress, completion, favourite, watchlist, rating,
and review text; never file paths, library records, logins, or TMDB sessions.
Anyone who can open the folder can read the files, and the app says so when a
location is chosen.

| Location | On | How |
|---|---|---|
| A folder picked in Files or Finder (iCloud Drive and other cloud apps' folders included) | iOS, iPadOS, macOS, visionOS | Security-scoped bookmark; `<id>.json.tmp` renamed over the file |
| The app's own iCloud Drive folder (`iCloud Drive/Edendale`) | iOS, iPadOS, macOS, visionOS | The ubiquity container's `Documents`, coordinated writes, files downloaded on read |
| Google Drive | All (Apple TV by handoff only) | `drive.file`; the app's `Edendale/<space>` folder in My Drive. Signing in for sync skips Google's picker: there is nothing to choose |
| OneDrive | All | `Files.ReadWrite.AppFolder`; `Apps/Edendale/<space>/` |
| Dropbox | All | `files.content.write`; `/Edendale/<space>/` |
| WebDAV, SMB, SFTP | All | A folder picked on the server with its saved login; the connectors gained `MKCOL`/`PUT`, libsmb2 writes, and SFTP write, mkdir, remove, and rename |

Choosing Google Drive, OneDrive, or Dropbox in Settings → Sync never asks for
videos or a folder. A OneDrive or Dropbox account linked before 27.1 is marked
in the account list and signs in again from there, and an Apple TV asking an
iPhone or iPad for the account gets a sign-in without Google's picker
(ENHANCEMENT.md K.8).

Every record carries one `updateTime` (UTC milliseconds) and the ID of the
device that wrote it. The newest record wins as a whole; equal stamps go to
the lexically smaller device ID; a local write is stamped
`max(now, highest stamp applied + 1)`; a stamp more than a day ahead of the
device's clock is ignored with a warning naming the device; a stamp before
2000 or missing counts as 0; a removal is a tombstone that stays, and
tombstones older than a year are omitted when writing. Sync runs at launch,
on return to the foreground, ten seconds after the last local change (at once
when playback stops or the app goes to the background; the position the
player saves while it is up waits for playback to stop or the five-minute
sync), every five minutes while active, and on iOS through Background App
Refresh when the system allows. Failures back off 1, 2, 4, 8 … minutes up
to an hour, except that launch and a return to the foreground always sync at
once. A launch or
foreground sync asked for while another is still running (one iOS suspended
in the background) runs as soon as that one ends, even if it failed. A file whose
stamp (modification time, ETag, Drive `modifiedTime`, Graph `eTag`, or
Dropbox `rev`) has not changed is not read again, and reading a file twice
changes nothing.

iCloud keeps syncing watch state between Apple devices as before: the
CloudKit rows gained the stamp, the writing device, and the tombstone flag,
so a device in both iCloud and a sync space bridges them with the original
stamps. The device ID is a this-device-only Keychain item, never restored to
another device; a restored device makes a new one. The watchlist flag now
travels with the favourite and rating as one record, so a title added to the
watchlist on one device reaches the others' watchlist cards and TMDB mirror.
At launch, rows from before 27.1 take the flag from the local watchlist (and a
listed title without a row gets one), so the first sync never tells other
devices those titles left it; a stamped row that disagrees changed elsewhere,
and the watchlist follows it. An episode from another device is matched to
this device's rows and library first, then to TMDB one season list at a time
(at most 20 per sync); until then it waits, and still travels on in this
device's file. Rows that arrived through CloudKit stamped more than a day
ahead stay local and are left out of the file until the clock passes them.
Apple TV has no file picker and no iCloud Drive, so it offers the account and
server locations and receives them from another device; it signs with its own
`Edendale-tvOS.entitlements`, without iCloud Documents. On macOS the sandbox
allows incoming connections, so a Mac can show a pairing code, and read-write
access to folders the user picks, so it can write sync files there; library
folders are still only read.

**Handing a location to another device** (Settings → Sync → Set Up Another
Device, or Receive from Another Device) carries the location, its login, and
any ticked accounts and saved logins over the local network, encrypted, to a
device on any platform. The host advertises `_edendale-handoff._tcp` while its
pairing screen is open and shows a QR code holding an ephemeral X25519 key and
a 16-byte secret; an iPhone or iPad scans it (a Mac, Apple TV, or Vision Pro
shows one), both derive one AES-256-GCM key (HKDF-SHA256, HMAC-SHA256 over the
transcript), and the sender confirms the receiver's name before anything is
sent. Which side sends is chosen first, independently of who shows the code.
A code is valid for two minutes and one attempt. A Google Drive login moves
only to another Apple device; other platforms receive the account's email and
the folder ID and sign in to Google again with the account pre-filled. A
folder location sends only the space name; the receiver picks its own folder.
The app's iCloud Drive folder goes only to Apple devices: to a Windows PC it
goes as a folder (iCloud for Windows shows it), and to Android or Quest not at
all. Logins travel in both shapes the branches use (Apple's nested
`credential`, Android's flat `user` and `password`), and either is read. A
code opened as a link, from the Camera app for example, asks whether this
device sends or receives. Apple-to-Apple account requests keep the
DeviceDiscoveryUI handoff above.
Typed-code pairing (J-PAKE) is not offered on Apple in 27.1; a pair with no
camera on either side is bridged with a phone.

Run the sync and handoff tests, which carry SYNC-PROTOCOL.md's Appendix A
vectors (merge cases M1–M8, the key schedule for RFC 7748's X25519 pair, the
QR URI, the wrong-secret case, the channel nonces), a property test that
devices converge regardless of read order, provider tests against recorded
responses, a two-device sync in memory, Android-shaped handoff messages, the
migration of a store written by the shipped Core Data model, and a
log-hygiene test that no key, secret, token, or password reaches the log:

```sh
xcodebuild test -project Edendale.xcodeproj -scheme Edendale \
  -destination 'platform=macOS' \
  -only-testing:EdendaleTests/SyncMergeTests \
  -only-testing:EdendaleTests/SyncFileFormatTests \
  -only-testing:EdendaleTests/SyncStorageTests \
  -only-testing:EdendaleTests/SyncCoordinatorTests \
  -only-testing:EdendaleTests/SyncLogHygieneTests \
  -only-testing:EdendaleTests/HandoffV2Tests \
  -only-testing:EdendaleTests/HandoffInteropTests \
  -only-testing:EdendaleTests/WatchStateMigrationTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

Checks that need hardware: the QR pairing between two devices (Bonjour and the
camera do nothing in Simulator), iCloud Drive and a picked folder on a device,
each provider with a real account, Background App Refresh timing, the
first launch's Core Data migration of a real existing store (the test covers a
synthetic one), and the macOS sandbox entitlements in a signed build. The iCloud Documents
capability needs the container's schema deployed in the CloudKit dashboard for
the new fields (`updateTime`, `deviceID`, `cleared`, `review`, `watchlist`)
before a release build syncs them. Android and Windows implement the same
format and handoff natively; the static Web branch only updates its privacy
policy.

### tvOS Top Shelf

When Edendale sits in the Apple TV Home Screen's top row and has focus, the
`TopShelfExtension` target (`TopShelf/`, embedded in **Edendale TV**) fills
the screen with a full-screen carousel (`TVTopShelfCarouselContent`, details
style): Continue Watching first, then this week's Trending titles, each over
its TMDB backdrop (w1280 at 1x, original at 2x). Swiping up enters full screen.
Continue Watching items have **Play** (resumes through `edendale://play/…`)
and **More Info** (the library page); Trending items only open their detail
page, so a trailer still starts only from inside the app. If no item has a
backdrop, the extension falls back to "Continue Watching" and "Trending"
poster rows. With no snapshot at all it returns nothing, and the static Top
Shelf images in Brand Assets show.

The extension reads only the display snapshot the app already publishes to the
App Group for widgets (`edendale.widget.snapshot.v1`). It decodes its own copy
of that model (`TopShelf/TopShelfSnapshot.swift`) and compiles nothing from
`Shared/` except `Localizable.xcstrings`. It makes no network requests: the
Home Screen fetches the `image.tmdb.org` URLs itself. The snapshot's Top Shelf
fields (`backdropURL`, `overview`, `releaseDate`, `duration`, `detailLink`)
are optional, so the iOS widget extension and older snapshots keep decoding.
After the tvOS app publishes a changed snapshot it calls
`TVTopShelfContentProvider.topShelfContentDidChange()`.

Device builds need the `com.BaBaSaMa.Edendale.TopShelf` App ID with the
`group.com.BaBaSaMa.Edendale` App Group. The target is the plain
`com.apple.product-type.app-extension` type that Xcode's TV Top Shelf template
uses. The legacy `tv-app-extension` type links `_TVExtensionMain`, and the
extension then exits at launch with status 3.

To check it in Simulator, build and install **Edendale TV** ad-hoc signed (see
above), launch it once so it fetches Trending, then move it into the dock's
first slot and return home. Swipe up for full screen, and click to show the
summary and buttons.

### Xcode Cloud

Xcode Cloud automatically runs the executable
`ci_scripts/ci_post_clone.sh`. Configure `TMDB_READ_ACCESS_TOKEN` as a secret
workflow environment variable; `TMDB_API_KEY` is an optional legacy fallback.
`WYZIE_API_KEY` is also an optional secret workflow variable. The script
generates the gitignored `.secret/Secrets.xcconfig` in Xcode Cloud's temporary
checkout without printing credential values.

The same post-clone script downloads and compiles FFmpeg for
`CI_PRODUCT_PLATFORM`, including simulator slices for test actions, and creates
`Vendor/FFmpeg/FFmpeg.xcframework` before the app build. No FFmpeg environment
variables, hosting credentials, or committed binary are required. Allow extra
build time for the source compilation. Both app targets link the resulting
static framework without embedding it. Their FFmpeg paths are resolved by the
Xcode project, so `Secrets.xcconfig` only needs credentials.

To reproduce the iOS archive locally after building FFmpeg:

```sh
xcodebuild archive -project Edendale.xcodeproj -scheme Edendale \
  -destination 'generic/platform=iOS' -archivePath build/Edendale.xcarchive \
  CODE_SIGNING_ALLOWED=NO
```

This unsigned archive checks compilation and linking; Xcode Cloud manages
signing and distribution. Configure the Cloud workflow to use this Apple branch
and the shared `Edendale` scheme. The post-clone hook does not upload or release
the app or build another platform branch.

### Branch contract

- `main` remains free of application source, build systems, generated output,
  deployment workflows, and platform secrets.
- A platform branch contains only the files needed to build, test, package, and
  deploy that platform.
- CI triggers only for its owning branch and uses only that platform's
  toolchain, credentials, and protected deployment environment.
- Product behavior is kept aligned through documentation and native parity
  tests, not a shared library, generated bridge, or copied build output.
- Universal documentation changes land on `main` first and are then propagated
  to platform branches without bringing platform code back into `main`.

## CI/CD isolation

Every platform branch must be independently buildable and deployable. Its
workflow should:

1. Trigger only for that branch and its pull requests.
2. Restore only the platform's dependencies.
3. Run that platform's checks and tests.
4. Produce only that platform's artifacts.
5. Gate credential-bearing release steps behind a protected environment.

Build commands, prerequisites, artifact names, and release procedures belong in
the `README.md` of the relevant platform branch after its implementation is
added.

## Design

All platforms follow the Cinematic Minimalism system in
[DESIGN.md](DESIGN.md). Implementations use native UI, navigation,
accessibility, and input conventions while preserving Edendale's shared
hierarchy, color semantics, typography, and privacy principles.

## Principles

- **Native first:** every platform is understandable, testable, and buildable
  without another platform's toolchain.
- **Private by design:** no Edendale account, telemetry, or analytics.
- **Your data stays yours:** local libraries remain local; any documented sync
  uses a user-controlled platform service.
- **Performance first:** classify and persist quickly, then enrich in the
  background.
- **Isolated delivery:** a platform can change, test, and release without
  affecting another platform's pipeline.

## License

Edendale is licensed under the [Mozilla Public License 2.0](LICENSE).

---

This product uses the TMDB API but is not endorsed or certified by TMDB.
