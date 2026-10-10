# Upcoming Windows Fixes

Fixes found after the 27.0.1 Store build and held for the next version
change. Each item says why it is broken in the packaged (MSIX) build, how to
fix it, and how to check the fix.

- **Found:** 2026-10-10, against `windows-27.0` `2f3670e` (27.0.1).
- **Why both items below are broken:** the Store build is packaged
  (`WindowsPackageType=MSIX` in the `.csproj`). Both features still register
  themselves the way an unpackaged app does. These findings come from reading
  the code and the Windows packaging rules. They have not yet been
  reproduced on an installed Store build.

## How to use this file

- Change `- [ ]` to `- [x]` as each task lands.
- When a version change picks up an item, move it into that version's
  tracker and delete it here.

## U.1 Launch at startup

### What's wrong

[`StartupService`](Edendale.Windows/Services/StartupService.cs) writes
`"<exe path>"` to `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`. Its
own header comment says a packaged build should use `StartupTask` instead.

1. **Registry virtualization.** A packaged desktop app's writes to `HKCU`
   go into a private registry copy that belongs only to that package.
   Explorer reads the real `Run` key at sign-in, so it never sees the entry.
   `IsEnabled` reads back through the same private copy, so the Settings
   toggle shows **On** but nothing launches.
2. **The path changes on every update.** `Environment.ProcessPath` points
   into `C:\Program Files\WindowsApps\LongJu.Edendale_<version>_<arch>__…\`.
   Even without virtualization, the saved path breaks after the next Store
   update.

### Fix

- [ ] Add the `desktop` namespace
  (`http://schemas.microsoft.com/appx/manifest/desktop/windows10`) to
  `Package.appxmanifest` and declare a startup task under `<Application>`:

  ```xml
  <Extensions>
    <desktop:Extension Category="windows.startupTask">
      <desktop:StartupTask TaskId="EdendaleStartup" Enabled="false" DisplayName="Edendale" />
    </desktop:Extension>
  </Extensions>
  ```

- [ ] Rewrite `StartupService` around
  `Windows.ApplicationModel.StartupTask.GetAsync("EdendaleStartup")`:
  - `IsEnabled` is true when the state is `Enabled` or `EnabledByPolicy`.
  - Turning it on calls `RequestEnableAsync()`.
  - Turning it off calls `Disable()`.
  - `SetEnabled` becomes async; update the Settings toggle to match.
- [ ] Handle `DisabledByUser` and `DisabledByPolicy`. Once the user turns
  Edendale off in Task Manager → Startup apps, the app cannot turn it back
  on. Show a note pointing to Task Manager (or Settings → Apps → Startup)
  instead of silently failing. The note needs a new string in all 18
  `Strings\*\Resources.resw` files.
- [ ] Remove any leftover `Run` value from 27.0.x installs. It lives only
  in the package's private registry copy, so deleting it once on first launch
  is enough.

### Check

- [ ] Install the packaged build, turn the toggle on, sign out and back in:
  Edendale starts.
- [ ] Edendale appears under Task Manager → Startup apps. Turning it off
  there makes the toggle show the Task Manager note.
- [ ] Update the package to a higher version: startup still works.

## U.2 Open With and `edendale://` links

### What's wrong

[`ActivationService.Register()`](Edendale.Windows/Services/ActivationService.cs)
registers both at runtime through
`ActivationRegistrationManager.RegisterForProtocolActivation` and
`RegisterForFileTypeActivation`. The Windows App SDK supports those calls
only for unpackaged apps. A packaged app has to declare protocols and file
types in `Package.appxmanifest`, and Edendale's manifest has no
`<Extensions>` section.

The calls fail in the packaged build, and the empty `catch { }` blocks hide
the failure. As a result:

- Edendale never appears under "Open with" for video files.
- `edendale://` links don't open the app.

`Handle()` and `OpenFiles()` are fine and need no changes. Jump-list
entries still work because they launch with the route as a command-line
argument instead of through the protocol.

### Fix

- [ ] Declare the protocol in `Package.appxmanifest` (same `<Extensions>`
  block as U.1):

  ```xml
  <uap:Extension Category="windows.protocol">
    <uap:Protocol Name="edendale">
      <uap:DisplayName>Edendale</uap:DisplayName>
    </uap:Protocol>
  </uap:Extension>
  ```

- [ ] Declare the video file types with a `windows.fileTypeAssociation`
  extension. The list must match `VideoFiles.SupportedExtensions` (used by
  `LibraryService.SupportedVideoExtensions`). Add a test that compares the
  manifest list with that constant, so the two stay in sync.

  ```xml
  <uap:Extension Category="windows.fileTypeAssociation">
    <uap:FileTypeAssociation Name="video">
      <uap:DisplayName>Video</uap:DisplayName>
      <uap:SupportedFileTypes>
        <uap:FileType>.mkv</uap:FileType>
        <!-- …one entry per supported extension… -->
      </uap:SupportedFileTypes>
    </uap:FileTypeAssociation>
  </uap:Extension>
  ```

- [ ] Skip `ActivationService.Register()` when the app runs with package
  identity, and keep it for unpackaged debug runs.
- [ ] Update the file's header comment, which still says registration uses
  the unpackaged path.

### Check

- [ ] Right-click an `.mkv` file → Open with: Edendale is listed and plays
  the file without importing it.
- [ ] `start edendale://…` from a terminal opens the matching route, and a
  bad link shows the activation message.
- [ ] With Edendale already running, both reuse the open window.

## U.3 Young Audience filter hides the youngest ratings

### What's wrong

[`YoungAudienceCertificationPolicy.Allows`](Edendale.Windows/Core/YoungAudienceCertification.cs)
accepts only `PG` and `PG13` for any title, plus `TVPG` and `TV14` for shows.
Every other rating is hidden, including G, TV-Y, TV-Y7 and TV-G, which
are the most child-friendly. A filter called "Young Audience Friendly"
therefore hides the titles best suited to young viewers.

The app's own strings say "PG or PG-13" and don't mention the TV ratings:

- `SettingsYoungAudienceDescription.Text`: "Only show movies and series
  rated PG or PG-13, including equivalent TV labels."
- `Watchlist_NoYoungAudienceTitles`, `Person_NoYoungAudienceTitles`,
  `DetailAudienceRestrictedMessage.Text`: "PG or PG-13".

The 27.0.1 Store listing (all 18 languages) describes the code as it is
today: "shows only titles rated PG, PG-13, TV-PG or TV-14". If this
changes, update the listing too.

### Decide first

- [ ] Check the product rule in DIFF.md on `main` and the Apple branch's
  `YoungAudienceFilter.swift`. The class is a port of that file, so the
  PG/PG-13-only window may be intentional (a "teen" band rather than
  "everything up to PG-13"). This is shared product behavior: if it
  changes, change it natively on every platform branch, not only Windows
  (AGENTS.md).

### Fix (if the rule becomes "up to PG-13 / TV-14")

- [ ] Also allow `G`, `TVY`, `TVY7` and `TVG` (after `Normalize`). Decide
  whether regional ratings such as Singapore's `G` / `PG` need the same
  treatment.
- [ ] Update `YoungAudienceCertificationPolicy` tests with the new allowed
  and rejected ratings.
- [ ] Reword the four strings above in all 18 `Strings\*\Resources.resw`
  files.
- [ ] Update the Store listing's Young Audience sentence (Description and
  Feature6) in every language.

### Fix (if the rule stays as it is)

- [ ] Reword the four strings so they name TV-PG and TV-14, matching the
  code and the Store listing.

## U.4 Translation consistency

Found by the listing translators while matching the Store text to the
app's own wording. The listing follows the first (button) label in each
case.

- [ ] **Picture in Picture has two names** in some languages.
  `Player_PictureInPicture` (the button) disagrees with
  `Player_ExitPictureInPicture` and `Player_PipUnavailable`:

  | Locale | Button | Exit / unavailable messages |
  |---|---|---|
  | ja | ピクチャインピクチャ | ピクチャー イン ピクチャー |
  | ko | 화면 속 화면 | PIP 모드 |
  | nl | Beeld in beeld | Picture-in-Picture |
  | es-ES, es-MX | Imagen dentro de imagen | Imagen en imagen |
  | pt-PT | Picture in Picture | Picture-in-Picture |

- [ ] **German mixes informal and formal address.** The app uses "du",
  but these strings use "Sie" / "Ihre":
  - `Subtitles_QuotaReached`
  - `Enhancement_HybridLaptop`
  - `SettingsAppControlsDescription.Text`
  - `SettingsSkipPromptsPrivacy.Text`
- [ ] **Dutch "trending" and "popular" read the same.**
  `Section_TrendingToday` is "Vandaag populair", next to
  `Section_PopularFilms` / `Section_PopularSeries` ("Populaire …").
  Consider "Vandaag trending" or "Trending vandaag".

## Also found while preparing the 27.0.1 listing

- [ ] The `SettingsNetworkNote` string (all `Strings\*\Resources.resw`)
  says trailers use YouTube's privacy-enhanced embed. The app actually
  opens youtube.com in the browser when the user clicks a trailer.
- [ ] README says Edendale ships outside the Microsoft Store because of
  `broadFileSystemAccess`. This contradicts the Store submission. Update it,
  and keep the written justification for that restricted capability ready
  for certification.
- [ ] `Directory.Build.props` still has `VersionPrefix` 26.0.0 while the
  manifest is 27.0.1.0.
- [ ] Confirm before advertising: the release build has client IDs for
  Google Drive, OneDrive, and Dropbox and a Wyzie key. Google Drive needs
  Google's app verification before anyone other than listed test users can
  sign in.
- [ ] Screenshots: replace the Store screenshot of the old player with the
  27.0 player.
