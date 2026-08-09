# ASC — App Store Connect metadata

Localized App Store metadata for **Edendale**, distributed across iOS, macOS,
tvOS, and visionOS (all version **26.0**). Managed with the
[`asc`](https://github.com/rork/asc) CLI.

> Apple record: app `6789041949` (`com.BaBaSaMa.Edendale`). All four platform
> versions exist at `26.0` in state `PREPARE_FOR_SUBMISSION`.

## Layout

```
ASC/
├── config.json          # app id, per-platform version ids, limits, planned locales
├── AuthKey_*.p8         # ASC API key — git-ignored, never commit
├── iOS/       <locale>.json
├── macOS/     <locale>.json
├── tvOS/      <locale>.json
└── visionOS/  <locale>.json
```

One JSON file per **locale**, one folder per **platform**. Every file uses the
**same keys** — only the copy changes. The description (and keywords /
promotional text / what's new) is written **per platform**; the remaining fields
are identical across platforms.

## Fields & Apple limits

| Key                | Level        | Limit | Notes |
|--------------------|--------------|------:|-------|
| `name`             | app-info¹    | 30    | Shared across all platforms |
| `subtitle`         | app-info¹    | 30    | Shared across all platforms |
| `privacyPolicyUrl` | app-info¹    | —     | Shared across all platforms |
| `description`      | version²     | 4000  | **Per platform** |
| `keywords`         | version²     | 100   | Comma-separated, no spaces |
| `promotionalText`  | version²     | 170   | Editable without a review |
| `whatsNew`         | version²     | 4000  | Release notes (updates only) |
| `marketingUrl`     | version²     | —     | `https://edendale.babasama.com` |
| `supportUrl`       | version²     | —     | `https://edendale.babasama.com` |
| `version`          | version²     | —     | `26.0` |
| `copyright`        | version²     | —     | `asc versions update --copyright` |

¹ **app-info** fields are one record per app (per locale), shared by every
platform. Set them once — not per platform.
² **version** fields belong to each platform's App Store version, so they are
applied per platform.

Validate all files against these limits any time:

```bash
node -e 'const fs=require("fs"),L={name:30,subtitle:30,promotionalText:170,keywords:100,description:4000,whatsNew:4000};for(const f of require("child_process").execSync("ls */*.json").toString().trim().split("\n")){const d=JSON.parse(fs.readFileSync(f));for(const k in L){const n=[...(d[k]||"")].length;if(n>L[k])console.log("!!",f,k,n+"/"+L[k]);}}console.log("done")'
```

## Auth

The `asc` CLI is already authenticated on this machine (keychain profile, shared
across the developer account). Verify with `asc doctor`. The `AuthKey_*.p8` here
is a git-ignored convenience copy for CI / other machines.

## Deploy

The live deploy uses the canonical **`asc metadata apply`** workflow — one pass
per platform applies **both** app-info (`name`, `subtitle`, `privacyPolicyUrl`)
and version localizations (`description`, `keywords`, `promotionalText`,
`marketingUrl`, `supportUrl`). A derived, git-ignored `.build/metadata/<platform>/`
tree is generated from the review files (`app-info/<locale>.json` +
`version/26.0/<locale>.json`), then applied. Always `--dry-run` first.

```bash
# Preview, then drop --dry-run to write:
asc metadata apply --app 6789041949 --version 26.0 --platform IOS       --dir ./ASC/.build/metadata/iOS       --dry-run
asc metadata apply --app 6789041949 --version 26.0 --platform MAC_OS    --dir ./ASC/.build/metadata/macOS     --dry-run
asc metadata apply --app 6789041949 --version 26.0 --platform TV_OS     --dir ./ASC/.build/metadata/tvOS      --dry-run
asc metadata apply --app 6789041949 --version 26.0 --platform VISION_OS --dir ./ASC/.build/metadata/visionOS  --dry-run

# Copyright is separate (not covered by metadata apply):
asc versions update --version-id 6c60bdf6-5e02-46b6-891e-e113f17cc3b0 --copyright "2026 Long Ju"   # IOS
asc versions update --version-id 10c13fa3-8bce-4652-b58a-4a08d2f02591 --copyright "2026 Long Ju"   # MAC_OS
asc versions update --version-id dd846b84-7d7e-47d0-8e28-f6c5647e10d4 --copyright "2026 Long Ju"   # TV_OS
asc versions update --version-id 81e983cc-437a-412e-9a1d-bf32fbea00a5 --copyright "2026 Long Ju"   # VISION_OS
```

> **`whatsNew` caveat:** App Store Connect **locks the "What's New" field on an
> app's first version**. It is kept in the `ASC/<platform>/<locale>.json` files
> but **excluded from the build tree for 26.0**. Include it for the next update
> (26.1+), when the field becomes editable.

## Status

- [x] English (`en-US`) copy for all four platforms
- [x] 18 locales translated + validated (72 files)
- [x] **Deployed to ASC v26.0** (2026-08-09): app-info + version localizations +
      copyright, all four platforms, all 18 locales — verified
- [ ] `whatsNew` — deploy with the next update (locked on the first version)
- [ ] Publish `https://edendale.babasama.com` and the `…/edendale/privacy` pages
      **before submitting for review** (metadata saves fine now; Apple validates
      the URLs at submission)
