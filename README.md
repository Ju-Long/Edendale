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
TMDB access, persistence, playback, SMB integration, file sync, the device
handoff, routing, and tests are implemented natively in this branch; no shared
runtime or generated bridge is required.

The current feature build includes sidebar navigation with section rows, a
custom title bar, a full-featured player (below), movie and show shelves,
folder and network-source import with background metadata enrichment, search,
detail and person pages with Play From, online subtitle search, sync of watch
state through storage the user owns, setting up another device over the local
network, and settings.

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

On a machine that has only a newer .NET runtime (the tests target .NET 8),
let the test host roll forward:

```bash
DOTNET_ROLL_FORWARD=Major dotnet test Edendale.Windows.Tests/Edendale.Windows.Tests.csproj
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
`GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET`, `ONEDRIVE_CLIENT_ID`,
and `DROPBOX_APP_KEY` ([secrets.example.json](secrets.example.json) lists
them). It serializes
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

The Google Drive, OneDrive, and Dropbox IDs are optional too, and an empty
client ID hides that provider from Link Source. All three sign in with OAuth
and PKCE through the system browser and a loopback redirect on `127.0.0.1`.
OneDrive and Dropbox are public clients with no secret. Google's Desktop
client type also issues a client secret its token endpoint expects; Google
documents it as not confidential, because every copy of a desktop app carries
it (D9). It stays in the gitignored `secrets.json` like the TMDB token.

- **Google Drive:** in Google Cloud, enable the Google Drive API and the
  Google Picker API, add only `https://www.googleapis.com/auth/drive.file` on
  the Google Auth Platform's Data Access page, and create an OAuth client of
  type **Desktop app**. Its client ID and secret are the two Google values.
  Desktop clients accept any loopback port, so no redirect URI is
  registered. Edendale asks for `drive.file` alone. Linking a source opens
  Google's picker inside the sign-in page, so Edendale sees only the files
  the user picks; signing in for the sync location skips the picker, since
  Edendale's own folder needs no pick.
  `drive.file` is non-sensitive: publishing needs no scope verification or
  security assessment. While the consent screen is in Testing, only listed
  test users can sign in and their sign-in lasts 7 days.
- **OneDrive:** register a Microsoft Entra application for personal and work
  or school accounts, with the delegated `Files.Read`,
  `Files.ReadWrite.AppFolder`, and `User.Read` permissions and **Allow public
  client flows** on. `Files.ReadWrite.AppFolder` is new in 27.1: it lets
  Edendale write its sync files in the app's own folder, `Apps/Edendale/`, and
  nowhere else (FEATURES-27.1.md §10, decision 1). Until the registration
  lists it, a sign-in that asks for it can fail. Accounts linked before 27.1
  keep refreshing with the scopes they were granted, so their sources keep
  working; they consent once more when they become the sync location. Add `http://127.0.0.1` to
  `publicClient.redirectUris` in its Manifest: the portal's Redirect URI box
  refuses http loopback addresses, and a registered `http://localhost` doesn't
  match `127.0.0.1`. Microsoft ignores the port of a loopback redirect, so
  Edendale listens on any free one.
- **Dropbox:** create a scoped app with Full Dropbox access, the redirect URI
  `http://127.0.0.1:49735/`, and the `files.metadata.read`,
  `files.content.read`, `files.content.write`, and `account_info.read` scopes.
  `files.content.write` is new in 27.1, for the sync files in `/Edendale/`.
  Dropbox refuses a sign-in that asks for a scope its App Console hasn't
  enabled, so enable it before shipping this build.

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
never enters the sync files.

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

### Library and Settings

- **Settings** has nine categories, listed beside the open page: General,
  Player Controls, Audio, Subtitles, Library, Sync, Accounts, Parental
  Controls, and About. In a narrow window (under 720 epx) the list becomes a
  picker above the page. Ctrl+, opens Settings at the category used last,
  with the keyboard on its row. A build without TMDB credentials hides the
  TMDB section, and About shows the build's version ("27.1.0 (abc1234)" in a
  release).
- **One title, several sources:** copies of a title tie by their TMDB match,
  or by the parsed title (and year, for a movie) until there is one.
  Downloaded and Search show one card per title, and a show's card counts its
  seasons and episodes across every copy. Remove on a card held by several
  sources offers one "Remove from …" per source.
- **Play From** names each copy's source and endpoint ("Media (nas.local)"),
  then its kind and file, "Last Played", and "Unavailable", with the copy
  played last first. Clicking an episode that several sources hold opens Play
  From first. Play, Continue Watching, the Movies & Shows hero, the jump
  list, and `edendale://play` links start the reachable copy played last,
  else the first reachable one. Which copy played last is library data on
  this PC and never syncs.
- **Downloaded sections:** chips for All, Continue Watching, Movies, and TV
  Shows appear on the page when two sections or more have titles, and always
  show the same choice as the sidebar rows. A chip changes the section in
  place; a section that empties falls back to All.
- **TMDB matching** sweeps the whole library at launch, after an import or a
  rescan, and on each visit to Downloaded: every movie and show without a
  match, and every unmatched episode of a matched show. A title TMDB has no
  match for is asked again after 7 days, or at once on Rescan, Ctrl+R, or F5.
  A TMDB that is unreachable, refuses the token, limits the rate, or fails
  ends the sweep, and the rest waits for the next one.
- **Clear Progress** removes a saved position: on a Continue Watching card,
  on an in-progress movie's poster, and on an in-progress episode on its
  show's page. It is written as a removal, so other devices drop it too.
- The rotating hero on Movies & Shows holds its scene while the player is
  open, and Continue Watching catches up when the player closes.

### Player

The player follows DIFF.md §3.1–3.11 and §3.18 natively:

- **Player Controls:** Settings → Player Controls sets the skip lengths (10,
  15, or 30 s each way), used by ←/→, the skip buttons, double taps on touch,
  the media keys, and a controller's bumpers. Holding either half of the
  video for 0.4 s plays at that side's speed until it is released.
- **Keyboard:** Space plays or pauses, ↑/↓ change the volume in 5 % steps,
  Ctrl+↑/↓ dim or brighten the player, M mutes, F or F11 toggles full
  screen, S takes a skip prompt, and Esc backs out one layer at a time (a
  panel, full screen or Picture in Picture, then the player). A HUD confirms
  each change and screen readers hear it.
- **Dimming:** Ctrl+↑/↓ is the player's own brightness, as ⌘↑/↓ is on a
  Mac. It starts at 100 %, the picture as it is, and dims in 5 % steps to
  15 % under a black layer below the controls. The level holds from episode
  to episode and resets when the player closes; the picture's own brightness
  is in Player Adjustments.
- **Buffering:** a gold ring takes the play button's place while a file
  opens, while LibVLC refills after a seek, and when a stream has stalled for
  a second, also with the controls hidden. Screen readers hear "Buffering"
  when a playing stream stalls.
- **Pointer:** the mouse pointer hides after resting 5 s over the video.
  Moving it, clicking, leaving the video, or switching windows brings it
  back.
- **Player Adjustments,** docked beside the video: video, audio, and subtitle
  tracks; picture adjustments with Show Original; video enhancement; speed on
  a 0.05 grid from 0.25× to 3×; Audio Booster; Headphone Surround; skip
  prompts; Loop; Fit or Fill; audio and subtitle delay; and a chapter list.
  Chapter starts are also marked on the timeline.
- **Remembered state:** per title, the speed and the audio, subtitle, and
  video tracks; device-wide, Loop, Fit or Fill, and the audio, picture,
  subtitle, and enhancement settings. All of it stays in
  `%LOCALAPPDATA%\Edendale\player-settings.json` and never syncs.
- **Episodes:** at the end of an episode the next one plays, and an Up Next
  card appears for the last 30 s. Continue Watching suggests the episode
  after the last one finished. A remote stream that drops before the end
  shows why and keeps its place; it doesn't count as finished or move on.
- **Skip prompts,** off by default: TheIntroDB marks intros, recaps, and
  credits, and a button skips them only when pressed.
- **Settings → Audio:** equalizer profiles, a preamp slider that shows its
  level before Audio Booster, and the ten bands as a graph around a 0 dB
  line. Drag a point, or press ↑/↓ on a focused band, to move it 1 dB (Page
  Up/Down 3 dB, Home/End to −20 or +20 dB, ←/→ to the next band). Choosing a
  profile moves every point to its levels and resets the adjustments.
- **Settings → Subtitles:** font, size, color, background, and outline
  presets. Rounded is not offered on Windows, which ships no rounded family.
- **Saved subtitles:** a subtitle downloaded from the online search is kept
  for that movie or episode. Playing the title again, from any copy, attaches
  it without a search, and the one that was on last time is on again. One not
  turned on for 30 days is deleted; Settings → Subtitles can switch that off
  or remove them all.
- **Play From:** a title in several sources plays the copy played last, or
  the first reachable one ([Library and Settings](#library-and-settings)).
  Auto-advance, Up Next, Next and Previous, and the Episodes panel stay on
  the playing source; an episode only another source holds plays from there,
  and the one after returns to the playing source.
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

**Frame Generation** (Player Adjustments → Enhancement, off by default) is
Edendale's own, on NVIDIA, Intel, and AMD GPUs. It doubles 30 fps and slower
video by motion-compensated interpolation, and upscales to the window's
physical pixels with Lanczos-3. NVIDIA runs it as CUDA kernels through the
driver (`nvcuda.dll`, no CUDA runtime ships), and Intel and AMD run the same
algorithm as Direct3D 11 compute shaders. On AMD it is the alternative to
Motion Smoothing: turning either on turns the other off. While it's on:

- Edendale draws LibVLC's frames on its own swap chain, about half a frame
  late, and delays the audio to match.
- Scene cuts hold the previous frame, and poorly matched areas blend the two
  frames instead of warping them.
- 10-bit and HDR sources, speeds above 1.5×, and any GPU failure fall back to
  normal playback.
- The picture comes from Edendale's own swap chain, so the driver's super
  resolution (Balanced) doesn't apply on top; Lanczos-3 does the upscaling.
- After changing `FrameGeneration.cu`, rebuild the PTX with
  `python3 tools/build-frame-generation-ptx.py` (NVRTC from
  `pip install nvidia-cuda-nvrtc-cu12`) and commit it.

### Storage providers

Settings → Library and the Downloaded page link these, through one Link
Source form (DIFF.md §3.12):

| Kind | How Edendale reaches it |
|---|---|
| Local folder | The file system |
| SMB | UNC paths through Windows' own SMB client |
| SFTP | SSH.NET, password login, curve25519/ECDH key exchange, Ed25519/ECDSA host keys, AES-GCM; SHA-1, CBC, 3DES, and DSA are refused |
| WebDAV | PROPFIND with Basic or Digest login (Nextcloud, ownCloud, Synology, QNAP, `rclone serve webdav`) |
| S3-compatible | Signature Version 4 (AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO) |
| NFS | LibVLC's bundled NFS client; the export needs the `insecure` option |
| Google Drive | Drive API v3 with `drive.file`: only the videos chosen in Google's picker |
| OneDrive | Microsoft Graph, personal and work or school accounts |
| Dropbox | Dropbox API v2 |

- **Cloud accounts** appear in Link Source only when this build has their
  client ID (see [API credentials](#api-credentials)).
- **Google Drive** has nothing to browse. Google's picker opens during
  sign-in, files only (a picked folder grants nothing inside it), and each
  account gets one source, "Videos you chose", listing every video picked on
  any device. **Choose More Videos** on the source's row or in Settings →
  Accounts reopens the picker. A video removed from the library stays hidden
  on this PC until it's picked again. Google Drive follows shortcuts, skips
  Docs, Sheets, and other Google formats, and refuses files Google flags as
  harmful rather than overriding the warning. Google accounts and folder
  sources from builds before `drive.file` are removed at launch, with a
  notice to link Google Drive again.
- **TLS:** HTTPS sources need a certificate Windows trusts; self-signed
  certificates are refused (D10). Plain HTTP reaches only local addresses
  (`.local` and unqualified names, private and Tailscale IP ranges).
- **SFTP host keys** are trusted on first use: Edendale shows the SHA-256
  fingerprint and key type, pins it, and refuses a changed key until it is
  approved again.
- **Streaming:** HTTP sources play through 4 MiB range reads with read-ahead
  and an 8-chunk cache. SFTP plays through a 1 MiB-chunk buffer that reads
  48 MiB ahead, or up to 384 MiB while Edendale uses less than 500 MiB of
  memory; keeps what it buffered across a brief jump, such as a seek that
  reads the file's index first; and reconnects with backoff. LibVLC never
  sees a token or a signed link.
- **Writes:** linking a source never changes it. The only files Edendale
  writes are its own sync files, at the location chosen in Settings → Sync,
  and Link Source says so for each cloud account.
- A source that is offline or needs signing in says so on its own row. The
  automatic rescan skips remote sources scanned in the last 15 minutes;
  Rescan, Ctrl+R, and F5 always scan.
- Removing a source keeps its login. Settings → Accounts lists every saved
  login and account with the sources using it, and forgets or signs out.

### Sync between devices

Release 27.1 replaces the OneDrive replica with sync on storage the user owns
([FEATURES-27.1.md](https://github.com/Ju-Long/Edendale/blob/main/FEATURES-27.1.md)
§5 and §6 on `main`; the file format, merge, and handoff are
[SYNC-PROTOCOL.md](https://github.com/Ju-Long/Edendale/blob/main/SYNC-PROTOCOL.md),
whose Appendix A vectors the tests copy from `main` at `915b40d`). Edendale
runs no server: each device writes one readable JSON file,
`Edendale/<space-name>/<device-id>.json`, reads the other devices' files, and
merges them locally. Sync is off until the user chooses a location in Settings
→ Sync, or receives one from another device.

- **What travels:** watch progress (in seconds, with completion), favourites,
  the watchlist flag, ratings, and review text written on another platform,
  keyed by TMDB id (show, season, and episode for an episode). Never titles,
  posters, the library, file paths, logins, or the TMDB session.
- **Merge:** per record, the newest `updateTime` wins as a whole; equal stamps
  go to the lexically smaller device ID. Every local write is stamped
  `max(now, highest stamp applied + 1)`, a record stamped more than a day
  ahead of this PC's clock is refused with a warning that names the device,
  and a cleared value is kept as a tombstone for 366 days so an older copy
  can't bring it back. This PC keeps its own records stamped that far ahead
  out of its file until its clock passes them, and lowers a high-water stamp
  that far ahead at launch. Files that break the format are skipped whole and
  never remove local data.
- **Locations:** one per PC. The OneDrive folder on this PC (its root is
  `%OneDrive%\Apps`, so the files land in `Apps\Edendale\<space>`, the folder
  the OneDrive API location and the old replica use), the iCloud for Windows
  folder (`%USERPROFILE%\iCloudDrive`), any picked folder (kept in the
  future-access list), Google Drive under `drive.file` (the app creates
  `Edendale/` and the space folder; no picker), OneDrive's app folder through
  Microsoft Graph, Dropbox (`/Edendale/`), and WebDAV, SMB, or SFTP servers
  whose logins are saved. Folder writes go to `<id>.json.tmp`, then a rename;
  API writes replace the file in one upload.
- **Choosing a location:** Google Drive, OneDrive, and Dropbox ask for no
  videos or folder, and Google's sign-in skips the picker. An account linked
  before 27.1 is marked in the list and signs in again when chosen, and a
  sign-in that still lacks write access is refused with nothing saved. A
  picked folder that is moved or renamed is followed. A cloud file Windows
  refuses to download (a OneDrive, iCloud, or Dropbox placeholder) shows a
  warning naming its device.
- **When:** at launch, on return to the foreground, 10 seconds after the last
  change, at once when playback stops or the window loses focus or closes,
  and every 5 minutes in the foreground. Unchanged files (by modification
  time, ETag, Drive `modifiedTime`, Graph `eTag`, or Dropbox `rev`) are not
  read again, and reading a file twice changes nothing. Failures back off 1,
  2, 4, and 8 minutes up to an hour, and the sync runs again when the wait
  ends. A sync asked for while one runs (Sync Now, playback stopping, a new
  location) runs right after it.
- **Migration:** the first launch of 27.1 reads `%OneDrive%\Apps\Edendale\`
  `user-media.json` and `watch-progress.json` once, stamps each record with
  its own time and this PC's ID, merges them, and leaves both files in place.
  A PC with OneDrive is then offered its OneDrive folder once, with the space
  `Family` suggested so every PC that accepts lands in the same space. The
  watchlist flag is reconciled at launch without new stamps, so a first 27.1
  launch can't take titles off another device's watchlist.
- **Remove a device** deletes its file; its records are already in every
  other file. The dialog links to the provider's account page, because the
  removed device keeps its login until access is revoked there (H6). A
  device whose file is gone leaves the list at the next sync, and a removed
  PC that syncs again writes a new file and reappears.
- **Device ID and state:** `%LOCALAPPDATA%\Edendale\sync-state.json` holds
  this PC's random device ID, the location (without credentials), the space,
  the high-water stamp, and the stamps of the files read. It is never written
  to a synced folder, so the ID can't reach another PC.

**Setting up another device** (handoff version 2, SYNC-PROTOCOL.md §9) moves
the sync location and, if the user ticks them, other linked accounts and saved
logins, directly over the local network. Settings → Sync starts it with
**Set Up Another Device…** (this PC sends) or **Receive from Another
Device…** (this PC receives); the person then picks which device shows the
code, and the instructions name the other device's entry:

- **This PC shows the code** (host): a QR code
  (`edendale://handoff?v=2&n&h&p&k&s`, X25519 key and a 16-byte secret) and a
  6-digit code, each valid for 2 minutes and one attempt. While that screen is
  open the PC advertises `_edendale-handoff._tcp` with DNS-SD (TXT `v=2`,
  `name`, `platform`); it stops when the screen closes, and the QR code and
  the code still work if registering takes over 5 seconds. The address in
  the QR code is the adapter with a default gateway, which skips Hyper-V and
  WSL switches. Five failures in a row lock pairing for a minute.
- **This PC enters the code** (guest): it browses for hosts and takes the
  6-digit code (J-PAKE over P-256, participant IDs `edendale-host` and
  `edendale-guest`) or a QR link pasted from the other device. It tries the
  code's address for 10 seconds, then looks for the host by name (or the
  only host) for 15 more. A PC has no QR scanner of its own, but an
  `edendale://handoff` link opened from Windows Camera or a phone asks to
  pair with that device, sending or receiving, and starts at once.
- After pairing, frames are AES-256-GCM with the direction-and-counter nonce
  of §9.5. The sender shows what it is about to send, with the receiver's
  name, before anything goes. The receiver refreshes each token, connects to
  each server (and for SFTP compares the host key the sender approved), and
  keeps only what validates. A Google refresh token goes only to another
  Windows PC; a phone, Mac, or TV gets the account's email and the space
  folder's Drive ID and signs in to Google itself (§9.8). A folder location
  sends only the space; the receiving device picks its own folder.
- The receiver answers before its first sync, which then runs on its own;
  its progress and errors show in Settings → Sync. The sender's result names
  each item ("OneDrive account", "SFTP login for nas.local"), with the reason
  when there is one. A location received this way offers to delete this PC's
  file at the old location. Logins are written flat (§9.7), and Apple's
  nested `credential` is read too.
- The packaged build adds inbound TCP firewall rules for the pairing
  listener on private and domain networks; public networks stay closed. An
  unpackaged build gets Windows' prompt instead.

The typed-code J-PAKE is a port of Bouncy Castle Java's `ecjpake` (the code
the Android handoff runs) onto Bouncy Castle C#'s elliptic-curve arithmetic,
since Bouncy Castle C# ships only the finite-field variant. RFC 8236 publishes
no test vectors, so the tests check agreement, the wrong-code failure at round
3, refused forgeries, and the wire shape the Android handoff reads.

Sync and handoff log lines (`Core/Sync/SyncLog`) carry counts, file names,
stamps, and error classes only; a test holds every line to that (H10).

### Data and privacy

Library, watch-progress, user-media, and player-settings JSON live under
`%LOCALAPPDATA%\Edendale`. When the user turns on sync, watch and user-media
state also travels as one readable JSON file per device through the folder or
account they chose (above); anyone who can open that folder can read it, and
the app says so when a location is chosen. The library, including which copy
of a title played last and the IDs of Google Drive videos removed from it,
never syncs.

Subtitles downloaded from Wyzie Subs are kept in
`%LOCALAPPDATA%\Edendale\Subtitles`, indexed by title in
`saved-subtitles.json`, and never sync. Each is deleted after 30 days
without being turned on, unless that's switched off in Settings → Subtitles.

Every login stays on this device (D11) unless the user sends it to another
of their devices with the handoff. The TMDB session, SMB, SFTP, WebDAV, and S3
logins, and Google Drive, OneDrive, and Dropbox refresh tokens are protected
with DPAPI for the current Windows user and never enter a sync file or a log.
Access tokens exist only in memory. Pinned SSH host keys are stored beside
them. The TMDB session never travels, not even in the handoff.

Network access is limited to:

- TMDB, for metadata and artwork;
- the sources the user links (SMB, NFS, SFTP, WebDAV, S3-compatible,
  Google Drive through `accounts.google.com`, `oauth2.googleapis.com`, and
  `www.googleapis.com`,
  OneDrive through `graph.microsoft.com` and `login.microsoftonline.com`,
  and Dropbox through `api.dropboxapi.com`, `www.dropbox.com`, and
  `dl.dropboxusercontent.com`), to list and play the user's files;
- the sync location the user chooses (a folder on this PC, Google Drive,
  OneDrive through `graph.microsoft.com`, Dropbox through
  `api.dropboxapi.com` and `content.dropboxapi.com`, or a WebDAV, SMB, or SFTP
  server), to read and write the sync files;
- the user's other devices on the local network, only while a pairing screen
  is open: DNS-SD on the local link, then one direct, encrypted connection;
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
