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
and settings.

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

### TMDB credentials

Run the Windows initializer from the repository root:

```powershell
.\init.ps1
```

If local execution policy blocks the script:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\init.ps1
```

The script writes the gitignored root `secrets.json` with
`TMDB_READ_ACCESS_TOKEN` and `TMDB_API_KEY`, restricts the file to the current
Windows user, and never prints either value. A local build embeds the file as a
private assembly resource. Environment variables with the same names are an
alternative for local or test processes. CI runs without credentials; do not
commit the file or distribute a locally built binary containing personal
credentials.

### Data and privacy

Library, watch-progress, and user-media JSON live under
`%LOCALAPPDATA%\Edendale`. When the user has configured OneDrive, watch and
user-media state can replicate through their OneDrive. The TMDB session and SMB
credentials remain device-local and are protected with DPAPI.

Network access is limited to TMDB, user-selected SMB and OneDrive resources,
and a user-initiated YouTube trailer action. Import classifies and persists
local filenames before optional TMDB enrichment begins.

### CI and release

`.github/workflows/ci.yml` builds the x64 Release configuration and runs the
domain tests only for pushes and pull requests targeting `windows`. It
uses no credentials and publishes no artifacts.

To create the current unpackaged Release build on Windows:

```powershell
msbuild Edendale.Windows.sln -restore -p:Platform=x64 -p:Configuration=Release
```

No signed installer or deployment workflow is configured yet. Any future
packaging must remain Windows-only, keep signing material out of the
repository, and gate credential-bearing release steps behind a protected
environment.

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
