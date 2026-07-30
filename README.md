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

## Web implementation

On `web`, the repository root contains Edendale's static Astro marketing
and verified-link site. It has no browser player, media library, watch tracker,
TMDB proxy, server runtime, account system, analytics, application persistence,
or runtime credentials. It is published in eight languages and matches the
visitor's browser language automatically; see [Languages](#languages).

### Requirements and local commands

Use npm 10 or newer with Node.js 22.12 or newer within the Node 22 release, or
Node.js 24. The checked-in [.nvmrc](.nvmrc) pins Node.js 22.22.1.

```sh
nvm use
npm ci
npm run check
npm run dev
npm run build
npm run preview
```

`npm run check` performs Astro and TypeScript validation; `astro build` does not
run these diagnostics, so this step is the only thing that catches type errors.
`npm run build` creates the production site in the generated, gitignored `dist/`
directory; `npm run preview` serves that completed build locally.

### CI and GitHub Pages

[Web CI](.github/workflows/ci.yml) runs `npm ci`, `npm run check`, and
`npm run build` for pull requests targeting `web`.
[GitHub Pages](.github/workflows/pages.yml) runs the same checks on every push to
`web`, builds only this branch's static site, uploads `dist/`, and deploys it
through the protected `github-pages` environment:

```sh
git push origin web
```

Both workflows then assert that `dist/` still contains `.nojekyll`, `CNAME`,
`.well-known/apple-app-site-association`, and `app_icon.png`. Those files are
referenced by path rather than imported, so nothing else in the build fails when
one goes missing. `actions/upload-pages-artifact` also stopped including
dotfiles by default in v4, which is why `include-hidden-files: true` is set.

Every action is pinned to a commit SHA. The deploy workflow does not cancel runs
already in progress, so a production deployment is always allowed to finish.
`workflow_dispatch` is declared but inert: GitHub only offers the manual trigger
for workflows present on the default branch, and `main` is documentation-only.
Recovering a skipped deploy therefore requires another push to `web`.

In **Settings → Pages**, select **GitHub Actions** as the source and configure
`edendale.babasama.com` as the custom domain. DNS must contain a CNAME from
`edendale.babasama.com` to `ju-long.github.io`; enable **Enforce HTTPS** after
the certificate is ready.

The Astro configuration defaults to the custom domain and a root base path. To
test a build for the repository's default project URL instead, clear the custom
domain:

```sh
EDENDALE_CUSTOM_DOMAIN="" npm run build
```

This fallback derives `https://ju-long.github.io/Edendale/` from
`GITHUB_REPOSITORY`.

### Languages

The site ships English, Spanish, French, German, Brazilian Portuguese,
Japanese, Korean, and Simplified Chinese through Astro's built-in
[i18n routing](https://docs.astro.build/en/guides/internationalization/),
configured with `prefixDefaultLocale: false`. English keeps the unprefixed
URLs and every other language is served from `/<locale>/`, the same shape
apple.com uses for `/` and `/jp/`.

| URL | Serves |
|---|---|
| `/` | English, and redirects a visitor whose browser asks for another shipped language to that language |
| `/en/` | English, with no redirect, canonical back to `/` |
| `/es/`, `/fr/`, `/de/`, `/pt-br/`, `/ja/`, `/ko/`, `/zh-hans/` | That language |
| `/search/`, `/media/`, `/library/`, `/play/`, `/404` | The app-link pages, which stay unprefixed |

Detection is a small inline script in `<head>`. It matches
`navigator.languages` against the shipped locales using RFC 4647 lookup — each
tag is tried, then progressively shortened, before moving to the next
preference — so `en-GB` resolves to English, `zh-Hans-CN` and `zh-TW` to
Simplified Chinese, and `pt-PT` to Brazilian Portuguese. It runs before the
body is parsed, redirects with `location.replace` so the back button still
works, and only ever runs on `/`. Every other URL is a fixed language.

DESIGN.md forbids this site from storing preferences, so nothing is written to
`localStorage` or a cookie. The URL is the memory instead: that is what `/en/`
exists for, and it is where the language picker in the footer sends anyone who
chooses English. The picker is a plain `<details>` disclosure of real links, so
it works with JavaScript disabled.

The app-link pages are the exception to per-language URLs. Universal Links match
the exact paths in `apple-app-site-association`, so a localized copy at
`/es/media/…` would be a link that silently stopped opening the app. Those pages
keep one unprefixed URL each and localize their text in the browser instead;
`SiteLayout`'s `localeMode="browser"` inlines every language's copy of the few
strings they use.

Adding a language:

1. Append an entry to [`src/i18n/locales.ts`](src/i18n/locales.ts) — the URL
   segment, its `lang` tag, its name in its own language, and the browser tags
   it claims.
2. Add its dictionary to [`src/i18n/ui.ts`](src/i18n/ui.ts). It is typed as a
   complete `Dictionary`, so `npm run check` fails until every key is present.
3. Check the headline widths. Headings carry authored line breaks (`\n` in the
   dictionary) so each language wraps where its words allow, and
   `--display-scale` in [`global.css`](src/styles/global.css) sizes the
   condensed display face per language — French and German need noticeably less
   than English. Languages marked `typeface: "cjk"` also get a platform CJK
   face, normal casing, and their own size ramp, because Bebas Neue has no CJK
   glyphs.

Every locale is currently left-to-right. `dir` is already wired from the locale
registry to `<html>`, but the stylesheet still uses some physical properties
(the skip link, the picker's alignment), so adding a right-to-left language
needs a pass over those first.

### Universal Links and App Links

The Apple association file is published at
`/.well-known/apple-app-site-association` for application identifier
`5678544286.com.BaBaSaMa.Edendale`. It owns `/search`, `/media/*`,
`/library/*`, and `/play/*`, matching the Apple app's declared routes.

GitHub Pages cannot control response headers. Apple requires this extensionless
endpoint to return HTTP 200 with `Content-Type: application/json` and no
redirect. Place a CDN or reverse proxy in front of the custom domain and add an
exact-path response-header rule without rewriting or redirecting the path.
Verify the live response before enabling Universal Links:

```sh
curl -I https://edendale.babasama.com/.well-known/apple-app-site-association
```

Android App Links remain intentionally disabled at the website layer until the
release signing certificate is known. At that point, add
`public/.well-known/assetlinks.json` with the real release certificate's SHA-256
fingerprint:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.babasama.edendale",
      "sha256_cert_fingerprints": ["REAL_RELEASE_SHA256_FINGERPRINT"]
    }
  }
]
```

Never publish a debug fingerprint or a placeholder value.

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
