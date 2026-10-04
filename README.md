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

### App Controls and player memory

**Settings → App Controls** sets the skip lengths (10, 15, or 30 seconds,
for back and forward separately) and the hold speeds (0.25× to 3.00× for each
side). One skip length drives every skip: double-tap, the on-screen buttons,
the D-pad and TV timeline, media keys, Picture-in-Picture actions, and the
system media controls. Touching and holding a side of the video plays at that
side's speed until you let go; on Android TV, holding fast-forward or rewind
does the same. Changes apply at once, even to a floating video.

Loop and Fit/Fill are remembered on the device. For each movie, and for each
show as a whole, the player remembers the speed, the audio track, the
subtitle track or that subtitles were off, and the video track, matching
tracks by language and then by name. These settings stay on the device and
are never synced.

### Network shares

SMB shares stream through a buffered reader: a worker thread fetches 1 MiB
chunks and keeps up to 48 MiB ahead of playback within a 64 MiB memory cache
(16 MiB within 24 MiB on low-memory devices), so a slow link such as a phone
hotspot or a VPN doesn't stall playback. A dropped connection is reopened
after 0.25, 0.5, 1, 2, 4, and 8 seconds before playback gives up with a
message naming the server, and a paused connection gets a keep-alive every
20 seconds. Nothing is written to disk.

### Storage services

**Add Network Source** links a folder from any of these. Edendale lists the
folder, classifies file names on the device, and then enriches matches from
TMDB, as it does for local folders. Every connection goes from the device
straight to the service; nothing passes through an Edendale server.

| Service | Signs in with | The service receives |
|---|---|---|
| SMB (Windows, macOS, NAS shares) | User and password, or guest | The login, folder listings, and reads of the files that play |
| WebDAV (Nextcloud, ownCloud, Synology, QNAP, pCloud, Koofr, rclone) | User and password (Basic or Digest), or guest | The login once the server asks for it, `PROPFIND` listings, and byte ranges of the files that play |
| SFTP (any server you can reach over SSH) | User and password; the server's host key is approved on first use | The login, folder listings, and pipelined reads of the files that play |
| S3-compatible (AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO) | Access key ID and secret | Signed listing requests and pre-signed byte-range requests |
| OneDrive (personal, work, school) | Microsoft sign-in, read-only | The sign-in, folder listings, and byte ranges from short-lived download links |
| Dropbox | Dropbox sign-in, read-only | The sign-in, folder listings, and byte ranges from temporary links |
| Google Drive | Waits for D9 | — |

Logins, key pairs, and account tokens are stored encrypted on the device,
excluded from backup and device transfer, and listed in **Settings →
Accounts**. Removing a source keeps them; remove them there. Linking another
folder on a server whose login is saved reuses it when the user and password
fields are left empty. A source's row shows its path, its account, and
whether its last scan failed.

### WebDAV servers

**Add Network Source → WebDAV** links a folder on a WebDAV server: Nextcloud
or ownCloud (`https://host/remote.php/dav/files/<user>/`), Synology, QNAP,
pCloud, Koofr, or `rclone serve webdav`. The address must be `https://` with a
certificate the device trusts; plain HTTP isn't supported yet. The server
receives the login (Basic or Digest, sent only after it asks), folder
listings (`PROPFIND`), and the byte ranges of what plays. The login is stored
encrypted on the device, excluded from backup and device transfer, and listed
in Settings → Accounts.

### SFTP

**Add Network Source → SFTP** links a folder on any server you can reach over
SSH, with a username and password (key logins come later). The first time
Edendale connects, it shows the server's host key (its type and `SHA256:`
fingerprint, as `ssh-keygen -l` prints them) for you to compare and trust.
The key is pinned per host and port. If a server later presents a different
key, Edendale refuses to connect until you trust the new key by linking the
server again. Logins and pinned keys stay on the device, excluded from
backup and device transfer. Playback reads through the same read-ahead and
reconnect as SMB. Servers that offer only older encryption, or only key
logins, get a message that says so.

### S3-compatible storage

**Add Network Source → S3** links a bucket or a folder in it on AWS, Backblaze
B2, Cloudflare R2, Wasabi, or MinIO: the endpoint (`https://`), the region
(`us-east-1` if left empty; `auto` for R2), the bucket, and an access key ID
and secret access key. Edendale signs its requests itself (AWS Signature
Version 4) and sends the service listings (`ListObjectsV2`) and pre-signed
byte-range requests for what plays. The key pair and the bucket's location are
stored encrypted on the device, excluded from backup and device transfer, and
listed in Settings → Accounts. Use a key that can only read that bucket.

### Cloud accounts

OneDrive and Dropbox accounts sign in in a Custom Tab with OAuth 2.0 and
PKCE. On Android TV, OneDrive signs in with a code: the TV shows the code, the
address to enter it at, and a QR code for that address, and waits while you
approve on a phone or computer. Dropbox and Google don't offer TV sign-in. Edendale uses no provider SDK and ships no client secret. Google Drive
waits for the owner's decision on how Android signs in (D9). Each provider
receives the sign-in, folder listings, and the byte ranges of what plays.
Nothing passes through an Edendale server, because there isn't one. The
refresh token for each account is stored encrypted on the device, excluded
from backup and device transfer. Access tokens are kept in memory only.
**Settings → Accounts** lists each account with its sources and Sign Out.
For Dropbox, **Sign Out and Revoke Access** also ends Edendale's access at
the provider.

To build with a provider, add its client ID to `secrets.json`. An empty value
hides that provider:

| Key | Registration | Redirect URI |
|---|---|---|
| `MICROSOFT_OAUTH_CLIENT_ID` | An Entra public client for personal and work or school accounts (`Files.Read User.Read offline_access`), with public client flows enabled for TV sign-in | `msauth://com.babasama.edendale/VzSiQcXRmi2kyjzcA%2BmYLEtbGVs%3D` (Android platform: package `com.babasama.edendale` and signature hash `VzSiQcXRmi2kyjzcA+mYLEtbGVs=`) |
| `DROPBOX_APP_KEY` | A scoped app with Full Dropbox access (`files.metadata.read files.content.read account_info.read`) | `db-<app key>://2/token` |
| `GOOGLE_OAUTH_CLIENT_ID` | Waits for D9 | — |

### Sources and accounts

Each visit to Downloaded rescans local folders; a remote source is rescanned
only if its last scan was over 15 minutes ago, and **Rescan** always scans.
A source that can't be reached, or whose login was refused, says so on its own
row in Settings → Sources and in Downloaded, where its rescan button becomes
Try Again, instead of an error for the whole library. Removing a source keeps its login: **Settings →
Accounts** lists every saved login with the number of sources using it, and
removes a login only when you ask.

When the same title is imported from several sources, each file stays its
own record. **Play** uses the first copy whose source is reachable (the page's
own copy, then local folders, then the rest by source name), and **Play
From** picks a copy: beside Play for movies, and in an episode's long-press
menu (the menu key on TV) for episodes.

### Audio Enhancement

**Settings → Audio Enhancement** applies a 10-band equalizer (60 Hz to 16 kHz)
through a Media3 audio processor: choose Flat, Movies (the default), Music,
Dialogue, or Night Mode, then fine-tune the preamp and each band; changing the
profile resets the adjustments. **Audio Booster** (also in Player Adjustments →
Playback) adds 10 dB of gain through the equalizer for quiet recordings.
Settings are device-local, apply live, and carry across files. Flat settings
pass audio through unchanged. The equalizer applies only to audio Edendale
decodes: surround sound passed through untouched to a receiver or TV plays
without it.

### Picture and Enhancement

**Player Adjustments → Picture** sets brightness, contrast, gamma, saturation,
and hue, saved on the device; **Show Original** compares without changing them,
and **Reset** returns to neutral. **Enhancement** upscales and sharpens video
on the GPU: Off, Sharpen Only, Balanced (upscale and sharpen), or High Quality
(adds temporal denoise), with Sharpness and Denoise and the resolution change
shown as `1280×720 → 1920×1080`. Enhancement settings last until the app
closes and are never saved. It starts at Balanced on phones and tablets that
pass a one-time on-device GPU check, and at Off on Android TV and on devices
that don't. HDR and Dolby Vision video skip every effect.

The effects run through Media3's video effects (`androidx.media3:media3-effect`)
and are installed only while something needs them; otherwise video goes
straight from the decoder to the screen. A governor keeps the passes within
8 ms of GPU time per frame, dropping denoise and then the upscale when a device
falls behind, runs hot, or saves battery. Nothing about performance leaves the
device.

The upscaler is AMD FidelityFX Super Resolution 1.0's EASU, ported to GLSL in
`src/main/assets/shaders/edendale_easu_es3.glsl` under the MIT licence
(Copyright © 2021 Advanced Micro Devices, Inc.; the notice is kept in the
shader). Sharpening, denoise, the Lanczos fallback, and the color math port
Apple's Metal shaders.

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

### Keyboard and large screens

In windows at least 1100 dp wide, the navigation lists Watchlist → Movies and
TV Shows, and Downloaded → Continue Watching, Movies, and TV Shows, as rows of
their own while each has titles for the current audience setting. The
Continue Watching page lists every title in progress; the shelf on the
Downloaded page keeps its 12. In the player, the playlist and Player
Adjustments dock beside the video instead of covering it.

With a hardware keyboard:

| Keys | Action |
|---|---|
| Ctrl+B | Hide or show the navigation (wide windows) |
| Ctrl+N | Add a media folder (Downloaded) |
| Ctrl+Alt+N | Link a network source (Downloaded) |
| Ctrl+R or F5 | Rescan every source (Downloaded) |
| Space | Play or pause |
| ← and → | Skip back or forward by the App Controls lengths |
| S | Skip an intro, recap, or credits when offered |
| Esc | Close a panel, then the player |

Meta+/ lists them. While a player panel is open, Space and the arrows go to
its controls. In the Link Source form the address field starts focused, Tab
and Shift+Tab move between fields, and Enter connects; user and password are
optional, for guest access. A show's episode shelf scrolls by dragging or
tapping the rule beside the Episodes heading.

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

### Continue Watching on the TV home screen

On Android TV and Google TV, **Settings → Android TV → Continue Watching on Home
Screen** (off by default) copies the Continue Watching shelf into the home
screen's Watch Next row through the platform TV provider
(`android.media.tv.TvContract.WatchNextPrograms`, written with the TV provider's
`WRITE_EPG_DATA` permission, which is granted at install). Each movie and each show gets
one row: a title in progress with its position, or a show's next episode.
Selecting a row opens the player at the saved position, just like the shelf
card. Rows update after playback pauses or stops, disappear when a title is
finished or its file is deleted, and all disappear when the setting is turned
off. A row removed from the home screen comes back only after you watch that
title again. Titles hidden by Young Audience Friendly never appear.

The rows stay on the TV and Edendale sends nothing over the network for them,
but the launcher (Google's app on Google TV) can read them, which is why the
setting is opt-in. Google plans to end support for this API in the second half
of 2027 in favor of the Engage SDK, which needs partner enrollment and user
accounts that Edendale doesn't have.

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

Two probes for the frame-generation experiment (ENHANCEMENT.md G.1) are
skipped unless asked for. Install both APKs with
`./gradlew installDebug installDebugAndroidTest`, which keeps the app's data,
then time Apple's coarse motion search at 1080p on the device's GPU:

```sh
adb shell am instrument -w -r -e g1 true -e class com.babasama.edendale.android.player.video.framegen.CoarseMotionEstimationInstrumentedTest com.babasama.edendale.test/androidx.test.runner.AndroidJUnitRunner
```

The second plays a clip that has audio through a prototype effect that adds a
frame between each pair of frames, and reports when each frame was released
and presented. It opens the app's main screen and turns SurfaceFlinger's
timestats on for the run:

```sh
adb push clip.mp4 /data/local/tmp/
adb shell am instrument -w -r -e g1Clip /data/local/tmp/clip.mp4 -e g1Fps 24 -e class com.babasama.edendale.android.player.video.framegen.FrameGenerationPlaybackInstrumentedTest com.babasama.edendale.test/androidx.test.runner.AndroidJUnitRunner
```

Build an unsigned release APK with:

```sh
./gradlew assembleRelease
```

The commands produce `build/outputs/apk/debug/Edendale-debug.apk` and
`build/outputs/apk/release/Edendale-release-unsigned.apk`. Release signing is
not stored in the repository and must be supplied through protected local or
CI configuration before distribution.

### Manual device checks

Some behavior needs real hardware and isn't covered by the JVM or instrumented
tests. Before a release, check on:

- **A recent phone:** skip lengths and hold speeds by touch and in
  Picture-in-Picture; per-title track memory; the track pickers and playlist
  with TalkBack; subtitle appearance in Fit and Fill; the Up Next card and skip
  prompts; equalizer changes while playing; Picture and Enhancement presets on
  SDR video, with HDR video bypassed.
- **A mid-range phone (Adreno 6xx or Mali-G57 class):** Balanced and High
  Quality on 720p and 1080p video without dropped frames, and the governor
  stepping down when the device runs hot.
- **An Android TV box on a 4K panel, and a TV set running Google TV:** the
  remote's hold speeds and skip-prompt focus, the playlist with the D-pad, the
  system media controls, enhancement output at the panel's resolution, and
  Continue Watching on the home screen (rows appear, open the player at the
  saved position, and disappear when the setting is turned off).
- **A tablet, Chromebook, or desktop-windowing device:** the navigation's child
  rows, docked panels, the keyboard shortcuts, and Meta+/.
- **A Bluetooth headset:** play, pause, and skip from its buttons.
- **An SMB server over a phone hotspot or Tailscale:** steady playback, and
  recovery after the connection drops.
- **Storage services:** link a folder, scan it, and play and seek a file on an
  SFTP server (password authentication, host-key fingerprint check on first use,
  and changed-key rejection), a Nextcloud or Synology server over HTTPS, an S3
  bucket (AWS or R2), OneDrive (a personal and a work or school account), and
  Dropbox. Then sign out in Settings → Accounts and check that the sources ask to
  sign in again. On a TV, sign in to OneDrive with the code from a phone.

### DTS and TrueHD

Media3 plays DTS, DTS-HD, and Dolby TrueHD through the device's own decoders
when it has them, or sends them undecoded to a receiver or TV that accepts
them. Everywhere else, Jellyfin's build of Media3's FFmpeg audio decoder
(`org.jellyfin.media3:media3-ffmpeg-decoder`, one native library per ABI)
decodes them, and the result goes through Audio Enhancement like any other
decoded audio.

### Licence

That FFmpeg decoder is licensed under the GNU General Public License v3.0, so
the Android app built from this branch, as a whole, is distributed under the
GPL-3.0. Its source is this branch plus the libraries it names; the decoder's
own source is at <https://github.com/jellyfin/jellyfin-androidx-media>.

### Dependencies and licences

| Dependency | Use | Licence |
|---|---|---|
| AndroidX Media3 1.9.0 (`media3-exoplayer`, `media3-ui`, `media3-session`, `media3-effect`) | Playback, system media controls, video effects | Apache-2.0 |
| `org.jellyfin.media3:media3-ffmpeg-decoder` 1.9.0+1 | DTS and TrueHD decoding | GPL-3.0 |
| AndroidX MediaRouter | Audio output switcher | Apache-2.0 |
| Jetpack Compose, Room, Activity, Lifecycle, DocumentFile, Security Crypto | UI, local records, encrypted settings | Apache-2.0 |
| jcifs-ng | SMB | LGPL-2.1 |
| OkHttp | Storage providers' HTTP | Apache-2.0 |
| AndroidX Browser | Custom Tabs for cloud sign-in | Apache-2.0 |
| sshj, with asn-one and SLF4J | SFTP | Apache-2.0 (SLF4J: MIT) |
| Bouncy Castle (bcprov, bcpkix, bcutil) | sshj's cryptography | MIT (Bouncy Castle Licence) |
| Coil | Images | Apache-2.0 |
| ZXing core | TMDB sign-in QR code | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | Concurrency, JSON | Apache-2.0 |
| AMD FidelityFX Super Resolution 1.0 (EASU, ported to GLSL) | Upscaling | MIT |
| Nunito | The Rounded subtitle font | SIL OFL 1.1 |

The build reads `TMDB_READ_ACCESS_TOKEN`, `TMDB_API_KEY`, and
`WYZIE_API_KEY` from `secrets.json`. This release adds
`MICROSOFT_OAUTH_CLIENT_ID`, `DROPBOX_APP_KEY`, and `GOOGLE_OAUTH_CLIENT_ID`
(see Cloud accounts).

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
