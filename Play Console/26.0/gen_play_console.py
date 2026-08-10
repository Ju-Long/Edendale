#!/usr/bin/env python3
"""Generate Google Play Console store-listing CSVs for Edendale.

Emits two files into the "Play Console" folder:
  - store-listings.csv          all 87 locales; English family filled, brand
                                name pre-filled everywhere, other cells blank
                                for "Import translations with AI" to populate.
  - store-listing-source.csv    single en-GB source row (minimal valid import).

Columns match the field labels shown in the Play Console store-listing editor.
"""
import csv
import os

# (display name, Play Console locale code) — exact order/codes from the console.
LOCALES = [
    ("English (United Kingdom)", "en-GB"),  # default
    ("Afrikaans", "af"),
    ("Albanian", "sq"),
    ("Amharic", "am"),
    ("Arabic", "ar"),
    ("Armenian", "hy-AM"),
    ("Azerbaijani", "az-AZ"),
    ("Bangla", "bn-BD"),
    ("Basque", "eu-ES"),
    ("Belarusian", "be"),
    ("Bulgarian", "bg"),
    ("Burmese", "my-MM"),
    ("Catalan", "ca"),
    ("Chinese (Hong Kong)", "zh-HK"),
    ("Chinese (Simplified)", "zh-CN"),
    ("Chinese (Traditional)", "zh-TW"),
    ("Croatian", "hr"),
    ("Czech", "cs-CZ"),
    ("Danish", "da-DK"),
    ("Dutch", "nl-NL"),
    ("English (Australia)", "en-AU"),
    ("English (Canada)", "en-CA"),
    ("English (United States)", "en-US"),
    ("English (India)", "en-IN"),
    ("English (Singapore)", "en-SG"),
    ("English (South Africa)", "en-ZA"),
    ("Estonian", "et"),
    ("Filipino", "fil"),
    ("Finnish", "fi-FI"),
    ("French (Canada)", "fr-CA"),
    ("French (France)", "fr-FR"),
    ("Galician", "gl-ES"),
    ("Georgian", "ka-GE"),
    ("German", "de-DE"),
    ("Greek", "el-GR"),
    ("Gujarati", "gu"),
    ("Hebrew", "iw-IL"),
    ("Hindi", "hi-IN"),
    ("Hungarian", "hu-HU"),
    ("Icelandic", "is-IS"),
    ("Indonesian", "id"),
    ("Italian", "it-IT"),
    ("Japanese", "ja-JP"),
    ("Kannada", "kn-IN"),
    ("Kazakh", "kk"),
    ("Khmer", "km-KH"),
    ("Korean", "ko-KR"),
    ("Kyrgyz", "ky-KG"),
    ("Lao", "lo-LA"),
    ("Latvian", "lv"),
    ("Lithuanian", "lt"),
    ("Macedonian", "mk-MK"),
    ("Malay (Malaysia)", "ms-MY"),
    ("Malay", "ms"),
    ("Malayalam", "ml-IN"),
    ("Marathi", "mr-IN"),
    ("Mongolian", "mn-MN"),
    ("Nepali", "ne-NP"),
    ("Norwegian", "no-NO"),
    ("Persian", "fa"),
    ("Persian (U.A.E.)", "fa-AE"),
    ("Persian (Afghanistan)", "fa-AF"),
    ("Persian (Iran)", "fa-IR"),
    ("Polish", "pl-PL"),
    ("Portuguese (Brazil)", "pt-BR"),
    ("Portuguese (Portugal)", "pt-PT"),
    ("Punjabi", "pa"),
    ("Romanian", "ro"),
    ("Romansh", "rm"),
    ("Russian", "ru-RU"),
    ("Serbian", "sr"),
    ("Sinhala", "si-LK"),
    ("Slovak", "sk"),
    ("Slovenian", "sl"),
    ("Spanish (Latin America)", "es-419"),
    ("Spanish (Spain)", "es-ES"),
    ("Spanish (United States)", "es-US"),
    ("Swahili", "sw"),
    ("Swedish", "sv-SE"),
    ("Tamil", "ta-IN"),
    ("Telugu", "te-IN"),
    ("Thai", "th"),
    ("Turkish", "tr-TR"),
    ("Ukrainian", "uk"),
    ("Urdu", "ur"),
    ("Vietnamese", "vi"),
    ("Zulu", "zu"),
]

# Locales that already read as English — fill with the source copy verbatim
# rather than asking the AI to "translate" English into English.
ENGLISH_FAMILY = {
    "en-GB", "en-AU", "en-CA", "en-US", "en-IN", "en-SG", "en-ZA",
}

APP_NAME = "Edendale"  # brand name — kept identical in every locale.

SHORT_DESCRIPTION = (
    "Play your own movies and shows. Track what you watch. Free and open-source."
)

FULL_DESCRIPTION = """Edendale is a free, open-source video player and personal watch tracker for the movies and shows you already own. Point it at your own files, imported folders, or a supported network source, and Edendale builds a private library you control — no streaming catalogue, no subscription, and no Edendale account.

PLAY YOUR OWN VIDEO
• Open individual files, whole folders, or supported network sources.
• Smooth playback on phones, tablets, Android TV, and large resizable windows, powered by Media3.
• Pick up where you left off with saved progress for every title.

A LIBRARY THAT STAYS YOURS
• Edendale reads your files and keeps library and watch data on your device.
• Filenames are classified locally before any optional metadata lookup.
• There is no Edendale account to create and nothing to sign in to.

RICH METADATA, ON YOUR TERMS
• Optionally enrich your library with details from TMDB: movies, shows, people, seasons, episodes, and release dates.
• Browse cast and crew, artwork, and release calendars.
• Trailers open only after you choose to play them.

TRACK WHAT YOU WATCH
• Record progress, ratings, and written reviews.
• Keep watch state and personal notes to yourself.
• Sort your library with watchlists and filters, including a young-audience filter.

BUILT FOR EVERY SCREEN
• One design that adapts to touch, remote, keyboard, and mouse.
• Full Android TV and tablet layouts with D-pad focus support.
• Optional online subtitle search that you trigger yourself.

PRIVATE BY DESIGN
• No analytics. No tracking. No ads.
• Device library data stays on the device, separate from portable watch state.
• Optional features — metadata, subtitles, trailers — never run until you ask.

Edendale is open-source software. Bring your own media; keep your own data."""

HEADER = ["Language", "App name", "Short description", "Full description"]


def build_rows():
    try:
        from translations_data import TRANSLATIONS
    except ImportError:
        TRANSLATIONS = {}

    rows, missing = [], []
    for _name, code in LOCALES:
        if code in ENGLISH_FAMILY:
            short, full = SHORT_DESCRIPTION, FULL_DESCRIPTION
        else:
            t = TRANSLATIONS.get(code)
            if t:
                short, full = t["short"], t["full"]
            else:
                short, full = "", ""
                missing.append(code)
        rows.append([code, APP_NAME, short, full])

    if missing:
        print("WARNING: no translation for:", ", ".join(missing))
    return rows


def write_csv(path, rows):
    # RFC 4180: quote every field so embedded newlines/commas survive.
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
        writer.writerow(HEADER)
        writer.writerows(rows)


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="Play Console folder path")
    args = ap.parse_args()

    rows = build_rows()
    write_csv(os.path.join(args.out, "store-listings.csv"), rows)

    source_row = [["en-GB", APP_NAME, SHORT_DESCRIPTION, FULL_DESCRIPTION]]
    write_csv(os.path.join(args.out, "store-listing-source.csv"), source_row)

    # Enforce Google Play limits: name<=30, short<=80, full<=4000.
    over_name = [r[0] for r in rows if len(r[1]) > 30]
    over_short = [(r[0], len(r[2])) for r in rows if len(r[2]) > 80]
    over_full = [(r[0], len(r[3])) for r in rows if len(r[3]) > 4000]
    filled = [r for r in rows if r[2] and r[3]]

    print("locales:", len(LOCALES), "| filled:", len(filled), "/", len(rows))
    print("longest short:", max(len(r[2]) for r in rows), "/ 80")
    print("longest full :", max(len(r[3]) for r in rows), "/ 4000")
    if over_name:
        print("OVER app name (>30):", over_name)
    if over_short:
        print("OVER short (>80):", over_short)
    if over_full:
        print("OVER full (>4000):", over_full)
    if not (over_name or over_short or over_full):
        print("all within limits: OK")


if __name__ == "__main__":
    main()
