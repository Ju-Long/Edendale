# Play Console

Google Play store-listing metadata for the Android build of Edendale. This
folder holds the **source copy** and the **import files** used to populate the
"Main store listing" and its translations in Play Console.

Nothing here is a credential and nothing here is built into the app; it is
release/marketing metadata for the Android delivery pipeline.

## Files

| File | Purpose |
|---|---|
| [`source-en-GB.md`](source-en-GB.md) | Human-readable master copy (default language). Edit here first. |
| `store-listings.csv` | All 87 Play Console locales, one row each, for bulk / AI translation import. |
| `store-listing-source.csv` | Just the en-GB source row — the minimal, always-valid import. |

## The two import paths in Play Console

In **Main store listing → Import translations with AI**, the *Import a file*
button takes a **CSV** (this feature does not accept XML — Android XML resources
under `src/main/res/values-*/strings.xml` localize the *app*, not the *store
listing*, which is a separate system).

1. **Let AI translate from the source** — upload **`store-listing-source.csv`**
   (or the single en-GB row), then let Play Console's AI generate the other
   languages you have activated. This is the lowest-risk path.
2. **Bulk import a full sheet** — upload **`store-listings.csv`**. Every locale
   from your screenshots is enumerated with a `Language` code. The app name is
   pre-filled in every row (a brand name should not be translated), the seven
   English locales carry the full copy, and the remaining rows leave
   *Short description* / *Full description* blank for the AI to fill.

If the import dialog offers its own **downloadable template**, compare its
header row against ours and rename columns to match if they differ — Google does
not publish a fixed public schema for this feature, so these column labels come
from the field names shown in the editor UI (`Language`, `App name`,
`Short description`, `Full description`). The valuable, hard-to-assemble part —
the 87 exact locale codes — stays correct regardless of header naming.

## Column format

`store-listings.csv` is RFC 4180 CSV, UTF-8, every field quoted so the
multi-line full description survives:

```
Language,App name,Short description,Full description
```

- `Language` — Play Console locale code (e.g. `en-GB`, `pt-BR`, `zh-Hant` is
  written `zh-TW` here, Hebrew is `iw-IL`, Filipino is `fil`, Latin-American
  Spanish is `es-419`), exactly as listed in the console.
- The default language is `en-GB`; keep it as the first data row.

## Character limits (enforced by Google Play)

| Field | Limit | Current |
|---|---|---|
| App name | 30 | 8 |
| Short description | 80 | 75 |
| Full description | 4000 | 1773 |

## Regenerating

The CSVs are generated from a single source of truth so escaping stays correct.
After editing the copy, re-run the generator (kept outside the repo; ask the
agent to regenerate, or copy it back in) with:

```sh
python3 gen_play_console.py --out "Play Console"
```

To change the copy, edit `SHORT_DESCRIPTION` / `FULL_DESCRIPTION` in the
generator (and mirror the change in `source-en-GB.md`), then regenerate.

## Locales included (87)

en-GB *(default)*, af, sq, am, ar, hy-AM, az-AZ, bn-BD, eu-ES, be, bg, my-MM,
ca, zh-HK, zh-CN, zh-TW, hr, cs-CZ, da-DK, nl-NL, en-AU, en-CA, en-US, en-IN,
en-SG, en-ZA, et, fil, fi-FI, fr-CA, fr-FR, gl-ES, ka-GE, de-DE, el-GR, gu,
iw-IL, hi-IN, hu-HU, is-IS, id, it-IT, ja-JP, kn-IN, kk, km-KH, ko-KR, ky-KG,
lo-LA, lv, lt, mk-MK, ms-MY, ms, ml-IN, mr-IN, mn-MN, ne-NP, no-NO, fa, fa-AE,
fa-AF, fa-IR, pl-PL, pt-BR, pt-PT, pa, ro, rm, ru-RU, sr, si-LK, sk, sl, es-419,
es-ES, es-US, sw, sv-SE, ta-IN, te-IN, th, tr-TR, uk, ur, vi, zu.
