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

The current feature build includes sidebar navigation, a custom title bar,
full-window playback with progress writes, movie and show shelves, folder
import with background metadata enrichment, search, detail and person pages,
online subtitle search, and settings.

### Prerequisites

- Windows 10 version 1809 (build 17763) or later; Windows 11 is recommended.
- Visual Studio 2022 17.10 or later with the **Windows application
  development** workload.
- .NET 8 SDK.

The Windows App SDK 1.7 is restored through NuGet. Development builds are
unpackaged and self-contained, so they do not require an MSIX certificate or a
separately installed Windows App SDK runtime.

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
`TMDB_READ_ACCESS_TOKEN`, `TMDB_API_KEY`, and `WYZIE_API_KEY`. It serializes
the file rather than concatenating it, writes through a sibling temporary file
that is restricted to the current Windows user before any secret reaches it,
renames that over the destination, and verifies the resulting ACL. It never
prints a value — only how many characters each one has. A local build embeds
the file as a private assembly resource. Environment variables of the same
names override it at runtime and are an alternative for local or test
processes. CI runs without credentials; do not commit the file or distribute a
locally built binary containing personal credentials.

The Wyzie key is optional. Get one at
[store.wyzie.io/redeem](https://store.wyzie.io/redeem); leave the prompt empty
to build without it, and the player hides the online subtitle search rather
than offering a dead entry.

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
rewrites it as UTF-8 in `%LOCALAPPDATA%\Edendale\Subtitles`, so re-selecting
one costs nothing. That cache is device-local and never enters the OneDrive
replica.

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

### Data and privacy

Library, watch-progress, and user-media JSON live under
`%LOCALAPPDATA%\Edendale`. When the user has configured OneDrive, watch and
user-media state can replicate through their OneDrive. The TMDB session and SMB
credentials remain device-local and are protected with DPAPI.

Network access is limited to TMDB, user-selected SMB and OneDrive resources,
and a user-initiated YouTube trailer action. Import classifies and persists
local filenames before optional TMDB enrichment begins.

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
git tag v0.26
git push origin v0.26
```

The current version lives in `Directory.Build.props` as `VersionPrefix`. Tags
may be two-part or three-part — `v0.26` and `v0.26.0` both build 0.26.0 — and
the release attaches to whichever tag was actually pushed. Assemblies carry
three parts and the MSIX identity four, so 0.26 widens to 0.26.0 and 0.26.0.0.

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
