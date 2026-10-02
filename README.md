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

## Windows development

The `windows` branch contains a self-contained C# and WinUI 3 desktop
application. Filename parsing, library rules, watch and user-media merging,
TMDB access, persistence, playback, SMB integration, routing, and tests are
implemented natively in this branch; no shared runtime or generated bridge is
required.

The current feature build includes sidebar navigation with section rows, a
custom title bar, a full-featured player (below), movie and show shelves,
folder and network-source import with background metadata enrichment, search,
detail and person pages with Play From, online subtitle search, and settings.

Playback is powered by the official LibVLCSharp WinUI control and the bundled
LibVLC 3 engine. It does not require a separate VLC installation and gives the
player VLC's container, codec, hardware-decoding, embedded-track, and external
subtitle support on x86, x64, and ARM64. The tradeoff is about 100 MB of native
VLC runtime files in each architecture-specific build before packaging.

### Prerequisites

- Windows 10 version 1809 (build 17763) or later; Windows 11 is recommended.
- Visual Studio 2022 17.10 or later with the **Windows application
  development** workload.
- .NET 8 SDK.

The Windows App SDK 1.7, LibVLCSharp, and the native LibVLC engine are restored
through NuGet. Use the **Edendale (Unpackaged)** launch profile for ordinary
development; it does not require an MSIX certificate. Store packaging remains
available from the same project.

### Build and test

Open `Edendale.Windows.sln` in Visual Studio, choose **x64** or **ARM64**, and
run with F5. From a Visual Studio Developer Command Prompt:

```powershell
msbuild Edendale.Windows.sln -restore -p:Platform=x64 -p:Configuration=Debug
```

Use Visual Studio MSBuild for the app because WinUI PRI tooling is not
available through the standalone .NET SDK on every machine. The WinUI-free
domain tests target plain .NET 8 and can also run on macOS or Linux:

```powershell
dotnet test Edendale.Windows.Tests/Edendale.Windows.Tests.csproj
```

### API credentials

Run the credentials tool from the repository root. It needs only the .NET 8
SDK the build already requires, so there is no script execution policy to
work around:

```bash
dotnet run --project tools/Edendale.Secrets
```

It prompts for each credential with the input hidden. Enter keeps a value that
is already set, so adding one key does not mean retyping the others, and `-`
clears an optional one. To see what is configured without revealing anything:

```bash
dotnet run --project tools/Edendale.Secrets -- --show
```

For scripted setup, a flag reads one line from standard input. Values are
never accepted as command-line arguments, which would leave them in shell
history and in the process list:

```bash
printf '%s\n' "$WYZIE_KEY" | dotnet run --project tools/Edendale.Secrets -- --wyzie-key -
```

The tool writes the gitignored root `secrets.json` with
`TMDB_READ_ACCESS_TOKEN`, `TMDB_API_KEY`, `WYZIE_API_KEY`, and the optional
`ONEDRIVE_CLIENT_ID` and `DROPBOX_APP_KEY`. It serializes
the file rather than concatenating it, writes through a sibling temporary file
that is restricted to the current Windows user before any secret reaches it,
renames that over the destination, and verifies the resulting ACL. It never
prints a value — only how many characters each one has. A local build embeds
the file as a private assembly resource. Environment variables of the same
names override it at runtime and are an alternative for local or test
processes. CI runs without credentials; do not commit the file or distribute a
locally built binary containing personal credentials.

For Visual Studio Debug launches, **Manage User Secrets** or the local Secrets
connected service can hold the same flat keys. Debug startup loads those
values into the current process before Edendale creates its services. Existing
environment variables take priority, and User Secrets then override the
embedded root `secrets.json`. This is development-only: .NET User Secrets are
not encrypted and do not travel in a Release/MSIX package. Store builds still
need the intended distribution credentials in the root `secrets.json` or the
protected release environment.

The Wyzie key is optional. Get one at
[store.wyzie.io/redeem](https://store.wyzie.io/redeem); leave the prompt empty
to build without it, and the player hides the online subtitle search rather
than offering a dead entry.

The OneDrive and Dropbox IDs are optional too, and an empty value hides that
provider from Link Source. Both are public-client identifiers for OAuth with
PKCE: no client secret exists for either, and none ships.

- **OneDrive:** register a Microsoft Entra application as a public client
  (Mobile and desktop) with the redirect URI `http://localhost` and the
  delegated `Files.Read` and `User.Read` permissions. Microsoft ignores the
  port of a loopback redirect, so Edendale listens on any free one.
- **Dropbox:** create a scoped app with Full Dropbox access, the redirect URI
  `http://127.0.0.1:49735/`, and the `files.metadata.read`,
  `files.content.read`, and `account_info.read` scopes.

> Windows PowerShell 5.1 mangles multi-line strings piped into a native
> program. Use a file redirect or `cmd /c` when feeding several values at once,
> or just run the tool interactively.

### Online subtitles

The player's subtitles button offers a search against
[Wyzie Subs](https://docs.wyzie.io), alongside whatever tracks the file already
carries. It runs only when the reader opens the browser — never on import and
never on the playback fast path.

Wyzie matches on an id, so a request sends the item's TMDB id — the series id
plus season and episode for television — with the wanted ISO 639-1 languages
and the API key. Neither the file nor its name leaves the device. Because the
lookup is by id, **an item the library has not matched to TMDB cannot be
searched at all**; the browser says so instead of showing an empty list. The
search asks for SubRip only, since that is what the Windows timed-text reader
renders.

Results are ordered by the reader's language preference, then human-authored
uploads over machine-translated ones, then popularity. Picking one downloads it
straight from the returned URL, decodes it using the character set the service
reported — subtitles are routinely published in a legacy code page — and
rewrites it as UTF-8 in `%LOCALAPPDATA%\Edendale\Subtitles` before attaching it
to LibVLC, so re-selecting one costs nothing. That cache is device-local and
never enters the OneDrive replica.

There is no account and no session to store: a key is the whole of the
service's authentication, and its allowance is per key per day.

### Languages

Every user-facing string lives in `Edendale.Windows/Strings/<language>/Resources.resw`.
XAML reads its copy through `x:Uid`, which MRT Core resolves; code-behind reads
the same catalogue through `Services/Loc.cs`. `SectionHeader` takes a `TitleKey`
instead, because `x:Uid` cannot reach a custom dependency property.

`Core`, `Models`, and the data services are compiled into
`Edendale.Windows.Tests`, which has no reference to MRT Core and no resource
map, so they read copy through `Core/AppText.cs`. The app points
`AppText.Resolver` at `Loc` on startup; without a resolver it falls back to the
English defaults it carries, which keeps those tests hermetic.

Headers and button labels the design sets in capitals are stored in natural case
where the control uppercases them itself, and in capitals where it does not — so
translations for scripts without case are left alone. `en-US` is the
`DefaultLanguage` and the fallback for every lookup; the other locales match the
set the Apple branch ships.

When adding UI text, add the key to `Strings/en-US/Resources.resw` first, then
to each translated file. A missing entry falls back to English rather than
failing.

`en-AU`, `en-CA`, and `en-GB` are the exception: they hold only the keys whose
spelling differs from `en-US` (favourite, catalogue) and inherit everything
else, so a new key belongs in them only when it spells something differently.

Dates, times, and numbers shown to the reader are formatted in
`CultureInfo.CurrentCulture`, which follows the regional format Windows is set
to and moves independently of the UI language: the search date range takes its
field order from the culture's long date, playback rate takes its decimal mark,
and the sync clock uses `"t"` rather than a fixed 24-hour pattern. Dates that
are keys rather than copy — the release-heatmap `yyyy-MM-dd` strings and the
bounds sent to TMDB — stay on `CultureInfo.InvariantCulture` so a Buddhist or
Hijri regional calendar cannot reach the wire, mirroring the Gregorian
`HeatmapCalendar` the Apple branch pins.

### Player

The player follows DIFF.md §3.1–3.11 and §3.18 natively:

- **App Controls:** Settings sets the skip lengths (10, 15, or 30 s each way),
  used by ←/→, the skip buttons, double taps on touch, the media keys, and a
  controller's bumpers. Holding either half of the video for 0.4 s plays at
  that side's speed until it is released.
- **Keyboard:** Space plays or pauses, ↑/↓ change the volume in 5 % steps,
  Ctrl+↑/↓ change brightness, M mutes, F or F11 toggles full screen, S takes
  a skip prompt, and Esc backs out one layer at a time (a panel, full screen
  or Picture in Picture, then the player). A HUD confirms each change and
  screen readers hear it.
- **Player Adjustments,** docked beside the video: video, audio, and subtitle
  tracks; picture adjustments with Show Original; video enhancement; speed on
  a 0.05 grid from 0.25× to 3×; Audio Booster; Headphone Surround; skip
  prompts; Loop; Fit or Fill; audio and subtitle delay; and a chapter list.
  Chapter starts are also marked on the timeline.
- **Remembered state:** per title, the speed and the audio, subtitle, and
  video tracks; device-wide, Loop, Fit or Fill, and the audio, picture,
  subtitle, and enhancement settings. All of it stays in
  `%LOCALAPPDATA%\Edendale\player-settings.json` and never replicates.
- **Episodes:** at the end of an episode the next one plays, and an Up Next
  card appears for the last 30 s. Continue Watching suggests the episode
  after the last one finished.
- **Skip prompts,** off by default: TheIntroDB marks intros, recaps, and
  credits, and a button skips them only when pressed.
- **Audio Enhancement and Subtitles** in Settings: equalizer profiles with a
  preamp and ten bands, and subtitle font, size, color, background, and
  outline presets. Rounded is not offered on Windows, which ships no rounded
  family.
- **Saved subtitles:** a subtitle downloaded from the online search is kept
  for that movie or episode. Playing the title again, from any copy, attaches
  it without a search, and the one that was on last time is on again. One not
  turned on for 30 days is deleted; Settings → Subtitles can switch that off
  or remove them all.
- **Play From:** a title imported from several sources plays the first
  reachable copy; the detail page lists every copy.
- **Windows integration:** the media flyout, media keys, and headset buttons;
  previous, play/pause, and next buttons on the taskbar thumbnail; a
  Continue Watching jump list; an audio output picker; the display kept awake
  during playback; an Xbox controller (A plays or pauses, the bumpers skip,
  the D-pad seeks and sets the volume, the triggers drive the hold speeds, B
  backs out); and in full screen, the display switches to a whole multiple
  of the video's frame rate when the monitor offers one.

### Video enhancement

Player Adjustments → Enhancement uses only what the bundled LibVLC 3 ships
(ENHANCEMENT.md "Option A"):

| Preset | What it does |
|---|---|
| Off | Bilinear scaling |
| Balanced (default) | GPU super resolution where supported (NVIDIA RTX with driver 530 or later, Intel Xe or Arc, AMD in x64 builds), otherwise the graphics driver's video-processor scaler |
| High Quality | Balanced plus AMD's denoise and artifact removal; offered only where that exists (AMD, x64) |

- Super resolution applies only while the video is smaller than the window;
  the label under the preset reads, for example, "1280×720 → 3840×2160".
- **Motion Smoothing** (AMD's frame-rate doubler) shows only on AMD GPUs in
  x64 builds, for sources at 30 fps or less, and doubles the rate ("24 fps →
  48 fps"). It is smoothest on high-refresh displays.
- Changing an option reopens the video at the same position, which takes
  about a second. Show Original compares against the unprocessed picture the
  same way.
- AMD's denoiser and Motion Smoothing work only on hardware-decoded video.
  For a file LibVLC decodes in software they do nothing, and the labels
  still describe what was requested.
- Enhancement applies on battery too, as on Apple, and the choices are
  remembered on this device.
- On a laptop with two GPUs running Edendale on the integrated one, super
  resolution needs Edendale set to **High performance** in Windows Settings →
  System → Display → Graphics; the panel says so.
- HDR video stays tone-mapped to SDR, because LibVLCSharp's video surface is
  8-bit.

### Storage providers

Settings → Sources and the Downloaded page link these, through one Link
Source form (DIFF.md §3.12):

| Kind | How Edendale reaches it |
|---|---|
| Local folder | The file system |
| SMB | UNC paths through Windows' own SMB client |
| SFTP | SSH.NET, password login, curve25519/ECDH key exchange, Ed25519/ECDSA host keys, AES-GCM; SHA-1, CBC, 3DES, and DSA are refused |
| WebDAV | PROPFIND with Basic or Digest login (Nextcloud, ownCloud, Synology, QNAP, `rclone serve webdav`) |
| S3-compatible | Signature Version 4 (AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO) |
| NFS | LibVLC's bundled NFS client; the export needs the `insecure` option |
| OneDrive | Microsoft Graph, personal and work or school accounts |
| Dropbox | Dropbox API v2 |

- **Google Drive** is not offered directly: its desktop OAuth clients carry
  a client secret, which Edendale never ships (D9). Reach Drive through
  WebDAV with `rclone serve webdav`.
- **TLS:** HTTPS sources need a certificate Windows trusts; self-signed
  certificates are refused (D10). Plain HTTP reaches only local addresses
  (`.local` and unqualified names, private and Tailscale IP ranges).
- **SFTP host keys** are trusted on first use: Edendale shows the SHA-256
  fingerprint and key type, pins it, and refuses a changed key until it is
  approved again.
- **Streaming:** HTTP sources play through 4 MiB range reads with read-ahead
  and an 8-chunk cache; SFTP through a 1 MiB-chunk buffer that reads up to
  48 MiB ahead and reconnects with backoff. LibVLC never sees a token or a
  signed link.
- A source that is offline or needs signing in says so on its own row. The
  automatic rescan skips remote sources scanned in the last 15 minutes;
  Rescan, Ctrl+R, and F5 always scan.
- Removing a source keeps its login. Settings → Accounts lists every saved
  login and account with the sources using it, and forgets or signs out.

### Data and privacy

Library, watch-progress, user-media, and player-settings JSON live under
`%LOCALAPPDATA%\Edendale`. When the user has configured OneDrive, watch and
user-media state can replicate through their OneDrive folder.

Subtitles downloaded from Wyzie Subs are kept in
`%LOCALAPPDATA%\Edendale\Subtitles`, indexed by title in
`saved-subtitles.json`, and never replicate. Each is deleted after 30 days
without being turned on, unless that's switched off in Settings → Subtitles.

Every login stays on this device (D11). The TMDB session, SMB, SFTP, WebDAV,
and S3 logins, and OneDrive and Dropbox refresh tokens are protected with DPAPI
for the current Windows user and never enter the OneDrive replica. Access
tokens exist only in memory. Pinned SSH host keys are stored beside them. A
OneDrive account linked as a storage source is separate from the OneDrive
folder used for replication (D12): signing it out leaves replication alone,
and turning replication off leaves the source alone.

Network access is limited to:

- TMDB, for metadata and artwork;
- the sources the user links (SMB, NFS, SFTP, WebDAV, S3-compatible,
  OneDrive through `graph.microsoft.com` and `login.microsoftonline.com`,
  and Dropbox through `api.dropboxapi.com`, `www.dropbox.com`, and
  `dl.dropboxusercontent.com`), to list and play the user's files;
- Wyzie Subs, only when the user opens the online subtitle search;
- TheIntroDB, only while skip prompts are switched on: it receives the
  title's TMDB id, the season and episode numbers, the video's duration, and
  the user's IP address;
- a user-initiated YouTube trailer action.

Import classifies and persists local filenames before optional TMDB
enrichment begins. Edendale never logs URLs, tokens, or request headers.

### CI and release

`.github/workflows/ci.yml` runs the domain tests once and builds the x86, x64,
and ARM64 Release configurations only for pushes and pull requests targeting
`windows`. It uses no credentials. Each architecture uploads its unpackaged
build as the workflow artifact `edendale-windows-<arch>`, kept for 30 days.

ARM32 and architecture-neutral builds are not produced. The Windows App SDK
ships no `win-arm` runtime, and the self-contained WinUI runtime requires a
concrete architecture.

To create the current unpackaged Release build on Windows:

```powershell
msbuild Edendale.Windows.sln -restore -p:Platform=x64 -p:Configuration=Release
```

Substitute `x86` or `ARM64` for another architecture.

CI artifacts are unsigned, carry no API credentials, and still require the
.NET 8 Desktop Runtime. They are for verifying a change, not for distribution.

### Releases

`.github/workflows/release.yml` produces the shipping build. Pushing a `v*`
tag, or running the workflow manually with a version, builds a packaged MSIX
for each architecture, combines them into one signed `.msixbundle`, and opens a
draft GitHub Release. Unlike a CI build, a release package is self-contained:
it carries both the Windows App SDK and .NET, so a user installs nothing first.

```powershell
git tag v26.0
git push origin v26.0
```

The package carries the Windows App SDK, .NET, and the architecture-matched
LibVLC engine; users do not need to install VLC separately. The current version
lives in `Directory.Build.props` as `VersionPrefix`; its
four-part local Store-package form is mirrored in `Package.appxmanifest`. Tags
may be two-part or three-part — `v26.0` and `v26.0.0` both build 26.0.0 — and
the release attaches to whichever tag was actually pushed. Assemblies carry
three parts and the MSIX identity four, so 26.0 widens to 26.0.0 and 26.0.0.0.

Both release jobs run in the protected `release` environment, so no secret is
readable until the environment's reviewers approve the run. It requires:

| Secret | Purpose |
|---|---|
| `TMDB_READ_ACCESS_TOKEN` | Embedded so releases enrich metadata out of the box |
| `TMDB_API_KEY` | Fallback for the token above |
| `WYZIE_API_KEY` | Online subtitle lookup |
| `SIGNING_CERTIFICATE_BASE64` | Base64 of the code-signing `.pfx` |
| `SIGNING_CERTIFICATE_PASSWORD` | Password for that `.pfx` |

The workflow reads the certificate's subject and stamps it into
`Package.appxmanifest` as the package `Publisher`, because MSIX refuses to
install when the two differ by even a space. Signing material never enters the
repository, and `secrets.json` is written at build time and deleted before any
artifact is uploaded.

A release build embeds the TMDB credential in the shipped binary. That
credential is extractable from a public package and all traffic bills to the
account that owns it, which is an accepted trade for working enrichment on
first launch.

To build a packaged MSIX locally, opt in explicitly — everyday builds stay
unpackaged so `F5` needs no certificate:

```powershell
msbuild Edendale.Windows\Edendale.Windows.csproj -restore -p:Platform=x64 -p:Configuration=Release -p:RuntimeIdentifier=win-x64 -p:EdendalePackaged=true -p:GenerateAppxPackageOnBuild=true -p:AppxPackageSigningEnabled=false
```

Visual Studio recognizes `Edendale.Windows` as a single-project MSIX app while
the **Edendale (Unpackaged)** launch profile keeps ordinary F5 runs
unpackaged. After creating the matching **MSIX or PWA app** in Partner Center,
reload the solution, right-click the `Edendale.Windows` project, and choose
**Package and Publish → Associate App with the Store**. The architecture
publish profiles under `Properties/PublishProfiles` select the matching
`win-x86`, `win-x64`, or `win-arm64` runtime.

### Installing a sideloaded release

Edendale ships outside the Microsoft Store, so Windows will not install the
package until its signing certificate is trusted. Install the public
certificate into `Local Machine\Trusted People`, then open the `.msixbundle`.

The package declares the `broadFileSystemAccess` restricted capability. The
library stores plain filesystem paths and re-reads them on later launches, so
without it a folder added today would be unreadable tomorrow. That capability
requires written justification for Store submission and is frequently refused,
which is why distribution is sideloaded rather than through the Store.

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
