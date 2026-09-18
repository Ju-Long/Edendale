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

Before a local build, copy `Shared/Example.xcconfig` to
`Shared/Secrets.xcconfig` and add the TMDB read access token. The generated
file is gitignored. `WYZIE_API_KEY` is optional and enables online subtitle
search; claim a free key at https://store.wyzie.io/redeem, or enter it later in
Settings.

Build the local FFmpeg XCFramework (requires Xcode and downloads FFmpeg 7.1.1
source), then resolve dependencies and inspect the shared schemes:

```sh
bash Vendor/FFmpeg/build-ffmpeg.sh
xcodebuild -resolvePackageDependencies -project Edendale.xcodeproj
xcodebuild -list -project Edendale.xcodeproj
```

The FFmpeg source download is checked against a pinned SHA-256 checksum. Its
source, intermediate frameworks, and final XCFramework are gitignored; commit
the build script and Xcode project references, not the generated binaries.
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
stores with CloudKit disabled. Normal app launches and UI tests retain their
usual persistence. The command above excludes UI tests; run the full signed
test command when an interactive test environment is available.

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

### Picture in Picture and subtitles

On iOS/iPadOS, starting PiP dismisses the full-screen player while preserving
playback and file access. Restore returns to the same session; closing PiP ends
the hidden session. PiP transport controls refresh from the playback state and
use the media clock. The automatic PiP preference does not disable the manual
PiP button.

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

The playlist opens at the current file and highlights current or focused rows
with larger text on a white background. Identified episodes and the current
identified movie include landscape artwork and stacked title/playtime details;
unknown sibling files retain their filename fallback. Player Adjustments
offers video and audio track selection when a file contains multiple tracks
of that type.

Settings includes adjustable Flat, Movies (default), Music, Dialogue, and Night
Mode equalizer profiles. Profile selection and adjustments persist locally;
changing profiles resets the adjustments. Player Adjustments also offers an
Audio Booster, off by default, that adds 10 dB of preamp gain within the
equalizer's bounds and restores the unboosted setting when disabled. Profiles
and booster changes apply during playback and carry over between files.
Night Mode changes the frequency balance; it does not compress dynamic range.
These controls do not affect visionOS spatial/multiview playback in the system
player. tvOS uses remote-operated increment/decrement controls for adjustments.

Episodes automatically advance to the next stored episode in season/episode
order, including the next season and stored specials, without replaying an
alternate file of the same episode. A newer manual playback request cancels
pending automatic advancement. Finished episodes retain their completed watch
state. Continue Watching suggests the next stored episode after the furthest
completed one when that show has no episode already in progress. This also
works after removing a watched file and does not create watch progress for the
unwatched suggestion. Duplicate show records produce one next-up card.

When a TV episode has a locally stored successor, an **Up Next** card appears
at the top right during its final 30 seconds. It shows artwork, the episode
code and title, and starts that episode when selected. The card clears when
seeking earlier, enabling Loop Video, or ending playback, and remains
reachable when controls are visible. Movies, files without episode context,
unknown durations, and the final stored episode do not show it. The native
visionOS system-player route uses the same preview rule. Artwork has a
readable fallback, focus is visible, and transitions honor Reduce Motion.

### Intro, recap, and credits prompts

Enable **Settings → Playback → Skip Prompts**, or **Player Adjustments →
Playback → Skip Prompts**, to use community timestamps from
[TheIntroDB](https://theintrodb.org/docs). The setting defaults off. The old
90-second recap and final-three-minutes credits auto-skips have been removed;
their saved preferences do not enable the new network feature.

During a known segment, a **Skip Intro**, **Skip Recap**, or **Skip Credits**
button appears at the bottom trailing edge, independently of hidden playback
controls. Press the button to skip; playback never skips automatically. On
keyboard platforms, **S** activates the visible prompt. On tvOS, **Down** from
the hidden-controls surface focuses the prompt when one is available. Prompts
hide while scrubbing or using a side panel. Bounded credits seek only to that
range's end, preserving gaps for additional scenes. A terminal credits skip
marks the item complete and advances to the next stored episode, ends playback
if none exists, or restarts the file when Loop Video is enabled.

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

### Xcode Cloud

Xcode Cloud automatically runs the executable
`ci_scripts/ci_post_clone.sh`. Configure `TMDB_READ_ACCESS_TOKEN` as a secret
workflow environment variable; `TMDB_API_KEY` is an optional legacy fallback.
`WYZIE_API_KEY` is also an optional secret workflow variable. The script
generates the gitignored `Shared/Secrets.xcconfig` in Xcode Cloud's temporary
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
