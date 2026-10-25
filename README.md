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
| `meta-horizon` | The Android app packaged for Meta Quest headsets (Horizon OS) and its Meta Horizon Store delivery; downstream of `android` |

The platform branches begin with the same universal Markdown files:

- `README.md` — product and repository overview.
- `AGENTS.md` — rules for automated contributors.
- `CLAUDE.md` — concise agent entry point.
- `DESIGN.md` — shared visual and interaction language.
- `MODEL.md` — optional propose-first task protocol.

`TASKS.md` is intentionally not part of the new repository. Work is tracked
through the platform branch's issue and pull-request workflow.

## Meta Quest (Horizon OS)

The `meta-horizon` branch is the Android app packaged for Meta Quest headsets,
where it runs as a 2D panel on Horizon OS and ships through the Meta Horizon
Store. It is a platform branch downstream of `android`: android's work arrives
by merging `android` into `meta-horizon` (`git merge android`), and nothing
merges back. A fix found here that isn't specific to a headset goes to
`android` as its own change first. [HORIZON.md](HORIZON.md) is the plan, with
its decisions, device findings, and logs.

The build has one product flavor, `horizon`. Everything Horizon-specific lives
in `src/horizon` (the manifest overlay and `DevicePolicy`) and
`src/testHorizon`, so merges from `android` touch `src/main` alone. On a
headset the app is a handheld app, never the TV shell: `DevicePolicy` turns
off Picture-in-Picture (Horizon OS can't float it), Watch Next, the Video
Enhancement default, and the player's brightness and volume swipes (a panel
ignores both). Watch Trailer opens YouTube in the browser or the YouTube app
instead of the inline player, which shows no picture in a panel. The release
APK is arm64-only, as Meta requires; the debug APK keeps every ABI for
emulators. Both still need JDK 17 and the Android SDK, as below.

The player's Theater button opens the **immersive theater** (HORIZON.md
Section G): the video on a large screen inside an environment, through
Meta's Spatial SDK, which makes the app a hybrid app. The theater is its own
activity (`src/horizon/.../theater/`) with its own ExoPlayer, built with the
same renderers and data source as the panel player, so local, SMB, WebDAV,
SFTP, S3, and cloud sources play unchanged. A trigger press or a pinch shows
its controls (play and pause, skip, the timeline, audio and subtitle tracks,
Exit); B or Y, or Exit, returns to the panel; progress is saved as in the
player. Its environment is `src/horizon/assets/theater.glb` when the
`scenes` branch has exported one (the activity reads the `Screen` and `Seat`
nodes), else a generated starfield (`tools/horizon/make_skybox.py`). The SDK
is used without the Meta Platform SDK, without an account, and without the
Spatial Gradle plugin, whose usage-data switch the build never turns on.

Because Meta's SDK licence forbids a GPL-3.0 APK, this branch doesn't use
Jellyfin's FFmpeg package: `tools/horizon/build-ffmpeg-decoder.sh` builds
Media3's FFmpeg decoder library over an LGPL-2.1 FFmpeg, and the build fails
its store-rule check without it (see DTS and TrueHD, and Licence, below).

| Purpose | Command |
|---|---|
| FFmpeg decoder library (once, and after a pin changes) | `tools/horizon/build-ffmpeg-decoder.sh` → `src/horizon/jniLibs/<abi>/libffmpegJNI.so` (gitignored; needs `sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`) |
| Hermetic JVM tests | `./gradlew testHorizonDebugUnitTest` |
| Debug APK | `./gradlew assembleHorizonDebug` → `build/outputs/apk/horizon/debug/Edendale-horizon-debug.apk` |
| Release APK | `./gradlew assembleHorizonRelease` → `build/outputs/apk/horizon/release/Edendale-horizon-release.apk` (`…-release-unsigned.apk` without the key below) |
| Store-rule check | `./gradlew verifyHorizonRelease`: minSdk and targetSdk, `installLocation`, the supported-devices tag, Meta's prohibited permissions (`gradle/meta-prohibited-permissions.txt`), no TV entries, arm64 only, the size limit, `libffmpegJNI.so` present, no `org.jellyfin` artifact, and the v2 signature and certificate when signed |
| Instrumented tests | `./gradlew connectedHorizonDebugAndroidTest` on an emulator, never on your own headset: it uninstalls the app and its data afterwards. On a headset, `./gradlew installHorizonDebug installHorizonDebugAndroidTest` and then `adb shell am instrument` keep the data. |

### Signing

Meta distributes the APK as signed, so every Quest build uses the same key:
the Play upload key `New.jks`, alias `key0` (HORIZON.md D3). Copy
`horizon-signing.example.properties` to the gitignored
`.secret/horizon-signing.properties` and fill in the passwords, or export
`HORIZON_STORE_FILE`, `HORIZON_STORE_PASSWORD`, `HORIZON_KEY_ALIAS`, and
`HORIZON_KEY_PASSWORD`. Without either, `assembleHorizonRelease` writes an
unsigned APK and `verifyHorizonRelease` skips the signature checks. The key
and its passwords never enter the repository or a CI log.

### Versioning

`versionCode` and `versionName` in `defaultConfig` are `android`'s and arrive
with its merges. Every upload to the Meta Horizon Store needs a higher
`versionCode` than the store's last upload, and Play has the same rule, so the
code is bumped on `android` and merged here, never on this branch alone.

### The Spatial Simulator

Meta Quest Developer Hub bundles the `metavr` CLI at
`/Applications/Meta Quest Developer Hub.app/Contents/Resources/bin/metavr`
(not on `PATH`). `metavr tools install spatialsim` installs the Meta Spatial
Simulator, `metavr ssim start` boots it as `emulator-5554`, and then:

```sh
adb -s emulator-5554 install -r build/outputs/apk/horizon/debug/Edendale-horizon-debug.apk
adb -s emulator-5554 shell am start -n com.babasama.edendale/com.babasama.edendale.android.MainActivity
```

`metavr ui list` and `metavr ui tap --coords X,Y` (pixels of
`adb exec-out screencap -p`) drive the panel. The simulator decodes with
software codecs, so codec and performance results come only from a headset.

### Release

1. Merge `android` with the version bump, then run the commands above.
2. `./gradlew assembleHorizonRelease verifyHorizonRelease` with the signing
   properties in place.
3. Upload to the ALPHA channel:
   `metavr store dist upload --app-id <app id> --channel ALPHA --apk build/outputs/apk/horizon/release/Edendale-horizon-release.apk --notes "<notes>"`
   (a draft; `--publish` releases it to the channel's testers).
4. Install the build from the channel on a headset, run the headset checks
   below, then promote the build in the Meta Horizon Developer Dashboard.

### Manual checks on a headset

Before a release, on a Quest 3 (and a Quest 2 while the listing keeps it):
install, launch, the panel's size and resizing; Touch controllers and hands,
with B or Y as Back; Add Folder through the system picker, and the folder
surviving a restart; "Open with Edendale" from the Files app; H.264, HEVC, VP9,
and AV1 at 1080p and 4K, and HDR on the panel; the audio formats and the
equalizer; Google Drive, OneDrive, Dropbox, and TMDB sign-in from the panel's
browser and their return to the app; the trailer opening outside the app; the
headset off and on mid-playback; a second account on the headset; and,
offline, each source failing on its own while the app stays usable. For the
theater (HORIZON.md G.10): entering and leaving it from every source type;
the screen's placement, a recenter, and the headset off and on; controllers
and hands on the controls panel; DTS-HD MA, TrueHD, and E-AC-3; 30 minutes of
playback against VrApi's FPS line; and `dumpsys netstats` showing nothing
from the app's UID.

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
| WebDAV (Nextcloud, ownCloud, Synology, QNAP, pCloud, Koofr, rclone) | User and password (Basic or Digest), or guest; a self-signed certificate is approved on first use | The login once the server asks for it, `PROPFIND` listings, and byte ranges of the files that play |
| SFTP (any server you can reach over SSH) | User and password; the server's host key is approved on first use | The login, folder listings, and pipelined reads of the files that play |
| S3-compatible (AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO) | Access key ID and secret; a self-signed certificate is approved on first use | Signed listing requests and pre-signed byte-range requests |
| OneDrive (personal, work, school) | Microsoft sign-in, read-only | The sign-in, folder listings, and byte ranges from short-lived download links |
| Dropbox | Dropbox sign-in, read-only | The sign-in, folder listings, and byte ranges from temporary links |
| Google Drive | Google sign-in with Google's picker, limited to the files you choose (`drive.file`) | The sign-in, the list of files you chose, and byte ranges of the files that play |

Logins, key pairs, and account tokens are stored encrypted on the device,
excluded from backup and device transfer, and listed in **Settings →
Accounts**. Removing a source keeps them; remove them there. Linking another
folder on a server whose login is saved reuses it when the user and password
fields are left empty. A source's row shows its path, its account, and
whether its last scan failed.

### WebDAV servers

**Add Network Source → WebDAV** links a folder on a WebDAV server: Nextcloud
or ownCloud (`https://host/remote.php/dav/files/<user>/`), Synology, QNAP,
pCloud, Koofr, or `rclone serve webdav`. The server receives the login (Basic
or Digest, sent only after it asks), folder listings (`PROPFIND`), and the
byte ranges of what plays. The login is stored encrypted on the device,
excluded from backup and device transfer, and listed in Settings → Accounts.

Three kinds of address work (the same rules apply to S3 endpoints):

- `https://` with a certificate the device trusts connects straight away.
- `https://` with a self-signed or otherwise untrusted certificate shows the
  certificate's subject, SHA-256 fingerprint, and expiry the first time, for
  you to compare with the server's own and trust. The fingerprint is pinned
  per host and port on the device, excluded from backup and device transfer.
  If the server later presents a different certificate, Edendale refuses to
  connect until you trust the new one by linking the server again. Other
  servers keep the device's normal certificate and hostname checks.
- `http://` connects only to addresses on a private network, checked on the
  resolved address: `10/8`, `172.16/12`, `192.168/16`, `100.64/10`
  (Tailscale), `127/8`, `169.254/16`, `::1`, `fc00::/7`, and `fe80::/10`.
  Anything else gets a message that the server needs HTTPS. The app's network
  security configuration permits cleartext for this; every other connection
  Edendale makes uses `https://`, and a redirect from `https://` to `http://`
  is never followed.

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
B2, Cloudflare R2, Wasabi, or MinIO: the endpoint (`https://`, or `http://` for
a MinIO on your own network; the WebDAV section's certificate and private-network
rules apply), the region (`us-east-1` if left empty; `auto` for R2), the
bucket, and an access key ID and secret access key. Edendale signs its requests itself (AWS Signature
Version 4) and sends the service listings (`ListObjectsV2`) and pre-signed
byte-range requests for what plays. The key pair and the bucket's location are
stored encrypted on the device, excluded from backup and device transfer, and
listed in Settings → Accounts. Use a key that can only read that bucket.

### Cloud accounts

Google Drive, OneDrive, and Dropbox accounts sign in in a Custom Tab with
OAuth 2.0 and PKCE. On Android TV, OneDrive signs in with a code: the TV shows
the code, the address to enter it at, and a QR code for that address, and
waits while you approve on a phone or computer. Dropbox and Google Drive come
to a TV from a phone (see Phone-to-TV handoff). Edendale uses no provider SDK
and ships no client secret. OneDrive and Dropbox receive the sign-in, folder
listings, and the byte ranges of what plays.

Google Drive asks only for `drive.file`, so Edendale sees only the files you
choose. Signing in opens Google's picker in the same Custom Tab: tick the
videos to add, from My Drive, Shared with me, or a shared drive. There are no
folders to browse, and a picked folder never shares what's inside it, so the
picker offers video files only. Each account gets one source, **Videos you
chose**, which lists every video picked with that Google account on any
device. New episodes don't appear on
their own: **Choose More Videos** on the source's row (or on the account in
Settings → Accounts) opens the picker again and rescans. **Remove from
Library** (a long press on a movie or an episode, or the Menu key on a TV)
keeps the video's Drive file ID on this device, never backed up, so a rescan
skips it; picking it again brings it back. The action is offered only for
Drive videos and files added on their own, because any other source's next
scan would bring the file back. Shortcuts are followed,
Google formats are skipped, videos are recognized by file extension, and
subtitles beside a video load only if they were picked too. Google receives
the sign-in, the file listing (`files.list`), the account's name and email
(`about`), and the byte ranges of what plays.

Accounts linked by a build that asked for `drive.readonly` can't open the
picker, and their folder sources can't be listed under `drive.file`. The
first launch of this build removes them, without revoking anything, and says
once to link Google Drive again.

Nothing passes through an Edendale server, because there isn't one. The
refresh token for each account is stored encrypted on the device, excluded
from backup and device transfer. Access tokens are kept in memory only.
**Settings → Accounts** lists each account with its sources and Sign Out.
For Google Drive and Dropbox, **Sign Out and Revoke Access** also ends
Edendale's access at the provider; for Google Drive that signs out every TV
you handed the account to and forgets the videos you chose.

To build with a provider, add its client ID to `secrets.json`. An empty value
hides that provider:

| Key | Registration | Redirect URI |
|---|---|---|
| `MICROSOFT_OAUTH_CLIENT_ID` | An Entra public client for personal and work or school accounts (`Files.Read User.Read offline_access`), with public client flows enabled for TV sign-in | `msauth://com.babasama.edendale/VzSiQcXRmi2kyjzcA%2BmYLEtbGVs%3D` (Android platform: package `com.babasama.edendale` and signature hash `VzSiQcXRmi2kyjzcA+mYLEtbGVs=`) |
| `DROPBOX_APP_KEY` | A scoped app with Full Dropbox access (`files.metadata.read files.content.read account_info.read`) | `db-<app key>://2/token` |
| `GOOGLE_OAUTH_CLIENT_ID` | An Android OAuth client (package `com.babasama.edendale` and the SHA-1 of each signing certificate) with its custom URI scheme turned on (Advanced settings), in a Cloud project with the Google Picker API enabled and `https://www.googleapis.com/auth/drive.file` on the consent screen's Data Access page. `drive.file` is non-sensitive, so no scope verification is needed (D9, H.9.4) | `com.babasama.edendale:/oauth2redirect` |

### Phone-to-TV handoff

Google Drive videos are chosen in Google's picker, which a TV never opens,
and Dropbox has no device flow, so an Android TV asks a phone instead. On the TV, **Link Source →
provider → Continue on a Phone** shows a six-digit code; on a phone running
Edendale on the same network, **Settings → Accounts → Link to a TV** lists the
TVs found, takes the code, and then asks "Link Google Drive on Living Room?",
offering an account already linked on the phone, a fresh sign-in, or (for
SMB, SFTP, WebDAV, and S3) a saved login. Nothing is sent until you choose.
The TV checks what it received (it refreshes the token, or tests the login),
stores it in its own encrypted storage, and goes on to the folder picker; a
Google Drive account links **Videos you chose** straight away. The phone's
refresh token is reused rather than exchanged for another, because Google
allows 100 per account per client. Google grants picked files to the account,
not to one token, so the TV keeps seeing videos chosen later on any device:
its Google Drive source says to choose them on a phone or tablet, and a
rescan lists them. A TV refuses a Google account from a phone whose Edendale
still asks for `drive.readonly`. A saved login travels with the
server's pinned SSH host key or certificate, so the TV trusts the server the
way the phone does.

The two devices talk directly over the local network, never through a server:
the TV registers the DNS-SD service `_edendale-handoff._tcp` on a random TCP
port while the code is on screen, the phone finds it with Network Service
Discovery, both derive a key from the code with J-PAKE over P-256 (Bouncy
Castle's `ecjpake`), and everything after that is AES-256-GCM. On networks
that block discovery (guest Wi-Fi, some routers), the TV also shows its
address and port beside the code, and the phone's dialog takes that address
by hand. A wrong code
fails the key confirmation before anything is sent; three wrong codes replace
the code. A passive listener learns nothing, and an active attacker gets one
guess per handshake. The code and keys last only for the session, and nothing
about it is logged. The TV offers the handoff for every provider it has a
client ID for and for every server kind; OneDrive can also sign in with a code
on its own.

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
  and changed-key rejection), a Nextcloud or Synology server over HTTPS (a
  trusted certificate, then a self-signed one: the fingerprint prompt, a changed
  certificate refused, and plain `http://` on the LAN but not to a public
  address), an S3 bucket (AWS or R2), OneDrive (a personal and a work or school
  account), and Dropbox. For Google Drive: sign in, pick videos from My Drive,
  Shared with me, and a shared drive in Google's picker, play and seek one,
  remove one and rescan (it stays gone), use Choose More Videos (the source
  says how many were added, and a removed video picked again comes back), and
  check that a build with an older `drive.readonly` account removes it with
  the one-time notice. Then sign out in Settings → Accounts and check that the
  sources ask to sign in again. On a TV, sign in to OneDrive with the code
  from a phone, and hand over a Google Drive account and, separately, an SMB
  login from a phone (Link Source → Continue on a Phone; Settings → Accounts
  → Link to a TV on the phone): a wrong code is refused, the right one links
  the account, the TV then browses the folders (Google Drive links Videos you
  chose and lists what the phone picked), and a rescan on the TV lists videos
  picked on the phone afterwards.

### DTS and TrueHD

Media3 plays DTS, DTS-HD, and Dolby TrueHD through the device's own decoders
when it has them, or sends them undecoded to a receiver that accepts them.
Everywhere else, Media3's own FFmpeg audio decoder decodes them, and the
result goes through Audio Enhancement like any other decoded audio. On this
branch the decoder's classes (`androidx.media3.decoder.ffmpeg`, Apache-2.0)
live under `src/horizon/java`, copied from the Media3 tag the version catalog
names, and its native library is built, never committed:
`tools/horizon/build-ffmpeg-decoder.sh` runs Media3's own `build_ffmpeg.sh`
over FFmpeg 6.0.2 (`release/6.0` at `831958d`, Jellyfin's pin) with
Jellyfin's decoder list, for all four ABIs, with NDK r26b and the SDK's CMake
3.22.1, and refuses to continue unless FFmpeg's `config.h` reports "LGPL
version 2.1 or later". The `android` branch keeps Jellyfin's GPL-3.0 package;
this one can't, because the Spatial SDK's licence forbids use that would put
the SDK under an open-source licence (HORIZON.md D12, D13).

### Licence

The Meta Quest app built from this branch is not GPL-3.0 as a whole:

- Edendale's own code is under the repository's licence (MPL-2.0 on `main`).
- FFmpeg 6.0.2 is under the GNU Lesser General Public License v2.1 or later.
  Its corresponding source is the FFmpeg commit, the Media3 tag, and the
  script named above, and the app can be relinked with a modified FFmpeg by
  running that script. The licence text and an FFmpeg notice ship in the
  APK under `assets/licenses/` (`src/horizon/assets/licenses/`), and the
  About section of Settings names them.
- Media3's FFmpeg decoder classes are Apache-2.0.
- The Meta Spatial SDK is under the
  [Meta Platform Technologies SDK License Agreement](https://developers.meta.com/vr/licenses/oculussdk/),
  licensed for Meta's headsets, which is where this flavor runs. Edendale
  isn't affiliated with or endorsed by Meta.

### Dependencies and licences

| Dependency | Use | Licence |
|---|---|---|
| AndroidX Media3 1.9.0 (`media3-exoplayer`, `media3-ui`, `media3-session`, `media3-effect`, `media3-decoder`) | Playback, system media controls, video effects, the decoder base | Apache-2.0 |
| Media3 1.9.0 `decoder_ffmpeg` classes (vendored under `src/horizon/java`) | DTS, DTS-HD, and TrueHD decoding | Apache-2.0 |
| FFmpeg 6.0.2 (`libffmpegJNI.so`, built by `tools/horizon/build-ffmpeg-decoder.sh`) | The decoders behind them | LGPL-2.1-or-later |
| Meta Spatial SDK 0.14.0 (`meta-spatial-sdk`, `-toolkit`, `-vr`, `-compose`) | The immersive theater | Meta Platform Technologies SDK License |
| Checker Framework `checker-qual` (compile only) | Annotations the decoder classes import | MIT |
| AndroidX MediaRouter | Audio output switcher | Apache-2.0 |
| Jetpack Compose, Room, Activity, Lifecycle, DocumentFile, Security Crypto | UI, local records, encrypted settings | Apache-2.0 |
| jcifs-ng | SMB | LGPL-2.1 |
| OkHttp | Storage providers' HTTP | Apache-2.0 |
| AndroidX Browser | Custom Tabs for cloud sign-in | Apache-2.0 |
| sshj, with asn-one and SLF4J | SFTP | Apache-2.0 (SLF4J: MIT) |
| Bouncy Castle (bcprov, bcpkix, bcutil) | sshj's cryptography; J-PAKE for the phone-to-TV handoff | MIT (Bouncy Castle Licence) |
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
