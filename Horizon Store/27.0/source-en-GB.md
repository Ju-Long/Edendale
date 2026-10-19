# Store listing — source copy (en-GB)

Draft for the owner to approve (HORIZON.md F.1, F.6, F.7). Adapted from
`Play Console/26.0/source-en-GB.md` on `android`: the TV and phone wording is
gone, and the headset is named where it matters. Check each field's limit in
the dashboard before pasting; Meta doesn't publish them all.

## Name

```
Edendale
```

## Short description

```
Play your own movies and shows on your headset. Track what you watch. Free and open-source.
```

## Description

```
Edendale is a free, open-source video player and personal watch tracker for the movies and shows you already own. Point it at a folder on your headset or at your own storage, and Edendale builds a private library you control — no streaming catalogue, no subscription, and no Edendale account.

PLAY YOUR OWN VIDEO
• Open individual files, whole folders, or your own network storage: SMB shares, SFTP, WebDAV, S3 buckets, OneDrive, Dropbox, and the Google Drive videos you choose.
• A large, resizable panel with controller, hand, and keyboard input, powered by Media3.
• Pick up where you left off with saved progress for every title.

A LIBRARY THAT STAYS YOURS
• Edendale reads your files and keeps library and watch data on your headset.
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

PRIVATE BY DESIGN
• No analytics. No tracking. No ads.
• Library data stays on the headset, separate from portable watch state.
• Optional features — metadata, subtitles, trailers — never run until you ask.

Edendale is open-source software (GPL-3.0; source at https://github.com/Ju-Long/Edendale). Bring your own media; keep your own data.
```

## Category and tags

Entertainment → Media and video player. Tags: video player, media player,
movies, TV shows, library, watch tracker, open source.

## Links

| Field | Value |
|---|---|
| Website | https://edendale.babasama.com |
| Privacy policy | https://edendale.babasama.com/privacy/ (needs the Meta Quest paragraph, F.3, before submission) |
| Support | https://github.com/Ju-Long/Edendale/issues |
| Source | https://github.com/Ju-Long/Edendale (branch `meta-horizon`) |

## Input modes (F.6)

Select only what the headset checks passed (HORIZON.md Appendix 1): Touch
controllers and hand tracking. Look and Pinch only if the Spatial Simulator
run under B.5 passes.

## Price and regions (D9, owner)

Free, no in-app purchases, every region the store offers.

## Data Use Checkup (F.4, owner)

No Meta Platform SDK features; no data collected by the developer. The app
has no server. The services it talks to, each only after the viewer's own
action: TMDB (metadata), TheIntroDB (skip prompts, off by default), Wyzie
(subtitle search, with the viewer's own key), YouTube (trailers), and the
storage services the viewer links. The privacy policy lists them.

## Age rating (F.5, owner)

The IARC questionnaire: the app shows TMDB metadata and artwork for whatever
the viewer owns, plays the viewer's own files and YouTube trailers, has no
user-to-user features, no purchases, no ads, and no location use.

## Reviewer notes (F.7)

```
Edendale plays videos you already own and keeps a private library of them; it has no account, no purchases, and no streaming catalogue. The library starts empty.

To play something, copy any MP4 or MKV into the headset's Movies folder (for example with Meta Quest Developer Hub or adb: adb push clip.mp4 /sdcard/Movies/), then in Edendale open Downloaded → Add Folder and choose Movies. The files are listed at once; metadata (posters, episode titles) arrives from TMDB a moment later and needs internet access.

Alternatively, Downloaded → Link Source accepts a WebDAV, SFTP, SMB, or S3 server you control.

Sign-in to Google Drive, OneDrive, and Dropbox is optional and opens the system browser; the app ships no client secret and stores tokens only on the headset. Trailers (YouTube) play only after pressing Play on a title's page.

The app is free, open-source (GPL-3.0), and collects no analytics. Privacy policy: https://edendale.babasama.com/privacy/
```

If the owner wants reviewers to have media without copying files, add a
read-only WebDAV or S3 folder of CC-licensed clips (Blender's open films are
CC-BY) and put its address and login in the private notes, never in this file.
