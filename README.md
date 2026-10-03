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

## Android development

The `android` branch contains a self-contained Kotlin application for
Android phones, tablets, TV, and resizable desktop-style windows. Jetpack
Compose owns the UI, Room owns local records, Media3 owns playback, and the
branch implements filename parsing, TMDB access, account synchronization,
search, release calendars, and merge rules natively.

Requirements:

- JDK 17.
- Android SDK 35; the app supports API 26 and newer. Set `ANDROID_HOME` or
  create a gitignored `local.properties`.

TMDB- and Wyzie-backed features are optional. To enable them locally, copy
`secrets.example.json` to the gitignored `secrets.json`, then add the TMDB API
Read Access Token and legacy v3 API key and, optionally, `WYZIE_API_KEY` for
online subtitle search. Missing credentials leave the app buildable and disable
only the dependent features.

### Redeeming your own Wyzie API key

Wyzie keys are issued per person; Edendale ships none and never shares one
between readers. To get your own:

1. Visit <https://store.wyzie.io/redeem>.
2. Complete the steps on the site to redeem a subscription for your account.
3. Copy the API key the site issues you.
4. Either paste it into Settings → Subtitles in the app, or add it to
   `secrets.json` as `WYZIE_API_KEY` for a build-time default.

A key entered in the app’s Settings is stored encrypted on the device, is
excluded from backup and device transfer, and overrides the build-time key.
Removing it in Settings falls back to the build-time key when one exists.
Never commit a redeemed key: `secrets.json` is gitignored and must stay that
way.

Online subtitle search, like trailer playback, starts only after an explicit
user action; opening the player or its settings panel never sends a search.

### Intro, recap, and credits prompts

Enable **Settings → Playback → Skip Prompts**, or **Player Adjustments →
Playback → Skip Prompts**, to use community timestamps from
[TheIntroDB](https://theintrodb.org/docs). The setting defaults off. The legacy
90-second recap and final-three-minutes credits auto-skips have been removed;
their saved preferences do not enable the new network feature.

During a known segment, a **Skip Intro**, **Skip Recap**, or **Skip Credits**
button appears at the bottom trailing edge, independently of hidden playback
controls. Press the button to skip; playback never skips automatically. On
keyboard platforms, **S** activates the visible prompt. On Android TV, **Down**
from the hidden-controls surface focuses the prompt when one is available.
Prompts hide while scrubbing or while a side panel covers the video. Bounded
credits seek only to that range's end, preserving gaps for additional scenes.
A terminal credits skip marks the item complete and advances to the next
stored episode, ends playback if none exists, or restarts the file when Loop
Video is enabled.

The native Kotlin client calls `GET https://api.theintrodb.org/v3/media` directly
from the device after the player reports a finite duration. Movies use their
TMDB ID; episodes use the **show's** TMDB ID plus TMDB season/episode numbers.
Requests include the video duration in milliseconds to help match release
versions. No account, API key, filename, video upload, library scan, or server
proxy is involved. The provider receives the media identifiers, runtime, and
the device's public IP address. See its
[privacy policy](https://theintrodb.org/docs/privacy) and
[usage terms](https://theintrodb.org/docs/terms).

Only a small in-memory cache exists during the playback session (at most 12
items); timestamps are not saved to the library, disk, watch progress, or
backup. Closing playback or disabling prompts clears the cache. Missing data,
network errors, invalid ranges, and rate limits leave normal playback
available without a prompt. Playback and initial import never wait for this
service.

### Network shares

SMB shares stream through a buffered reader: a worker thread fetches 1 MiB
chunks and keeps up to 48 MiB ahead of playback within a 64 MiB memory cache
(16 MiB within 24 MiB on low-memory devices), so a slow link such as a phone
hotspot or a VPN doesn't stall playback. A dropped connection is reopened
after 0.25, 0.5, 1, 2, 4, and 8 seconds before playback gives up with a
message naming the server, and a paused connection gets a keep-alive every
20 seconds. Nothing is written to disk.

### Subtitle appearance

**Settings → Subtitles** sets how text subtitles look: the font (System,
Rounded, Serif, or Monospaced), the text color, and the color and opacity of
the box behind each cue, as named presets with a live preview and a Reset.
The choices are device-local and apply at once, even to a video floating in
Picture-in-Picture. Text cues are drawn inside the visible part of the picture
in both Fit and Fill, sized from its height and the system caption font scale,
and move above the transport controls while they show. Image-based subtitles
(PGS, VobSub) keep their authored look.

The Rounded font is [Nunito](https://github.com/googlefonts/nunito) by The
Nunito Project Authors, under the SIL Open Font License 1.1. The app bundles
static Regular and Bold instances (`src/main/res/font/nunito_*.ttf`) made from
Google Fonts' `ofl/nunito/Nunito[wght].ttf` with fontTools'
`varLib.instancer`; they keep the font's copyright and licence metadata.

### System media controls

While a video plays, the player publishes a Media3 `MediaSession`
(`androidx.media3:media3-session`), so headset buttons, Bluetooth controls,
Google Assistant, and Android TV's system media UI reach it, including in
Picture-in-Picture. Skip back and forward use the **Settings → App Controls**
lengths, and next and previous play the neighboring playlist entry. The session
lives only as long as the player: there is no background playback service, and
playback still pauses when the player leaves the screen. The artwork it reports
is the TMDB image the library already shows.

On phones and tablets, **Player Adjustments → Playback → Audio Output** opens
the system output switcher (`androidx.mediarouter:mediarouter`).

Run the hermetic JVM tests and build the debug APK:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Run the instrumented tests in `src/androidTest` on a connected device or a
running emulator. They need no network access or credentials:

```sh
./gradlew connectedDebugAndroidTest
```

The task installs the debug app and test APKs and uninstalls both afterwards,
which also deletes the app's data on that device, so prefer an emulator. CI
runs the same suite on an API 35 emulator in
`.github/workflows/instrumented.yml`, separately from the hermetic
`build-and-test` job.

Build an unsigned release APK with:

```sh
./gradlew assembleRelease
```

The commands produce `build/outputs/apk/debug/Edendale-debug.apk` and
`build/outputs/apk/release/Edendale-release-unsigned.apk`. Release signing is
not stored in the repository and must be supplied through protected local or
CI configuration before distribution.

### Languages

Every user-facing string lives in `src/main/res/values/strings.xml`, with
translations in `values-<qualifier>/strings.xml` for the same locales the Apple
branch ships. Composables read them through `stringResource` and
`pluralStringResource`; view models and `LibraryRepository`, which have no
Compose scope, read them through `AppStrings` in `Localization.kt`.

Labels are stored in natural case. The view layer applies `.uppercase()` where
the design calls for capitals, so scripts without case are left alone. Counts
use `<plurals>` so each language gets its own CLDR categories rather than a
hardcoded singular and plural.

`Localization.kt` also maps `SearchScope` and `CollectionFilter` onto the
catalogue, so the strings those types carry stay translatable; the mapping is
exhaustive, so adding a case fails the build instead of leaking English.
Formatting helpers in `LibraryPresentation.kt` take a formatter argument with an
English default, which keeps the hermetic tests in `src/test` runnable without a
`Context`.

`res/xml/locales_config.xml` lists the shipping languages, so Android 13+ offers
Edendale in Settings → System → Languages → App languages and a reader can pick
a language for this app alone. Every tag there needs a matching `values-`
directory. A missing entry falls back to English rather than failing.

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

---

This product uses the TMDB API but is not endorsed or certified by TMDB.
