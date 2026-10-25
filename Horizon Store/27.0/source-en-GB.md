# Store listing — source copy (en-GB)

Draft for the owner to approve (HORIZON.md F.1, F.6, F.7). Adapted from
`Play Console/26.0/source-en-GB.md` on `android`: the TV and phone wording is
gone, and the headset is named where it matters. Check each field's limit in
the dashboard before pasting; Meta doesn't publish them all.

## Name

```
Edendale
```

## Short description (at most 500 characters)

```
Edendale is a free, open-source video player and watch tracker for the movies and shows you already own. Add a folder on your Quest or link your own storage (SMB, SFTP, WebDAV, S3, Google Drive, OneDrive, or Dropbox) and Edendale builds a private library with posters, cast, and episode details from TMDB. Pick up where you left off, rate and review what you watch, and keep it all on your headset. No account, no subscription, no ads, no analytics.
```

## Description (Markdown, at most 1,500 characters)

```markdown
Edendale turns the videos you already own into a private cinema on your Meta Quest. There's no streaming catalogue, no subscription, and no Edendale account: bring your own movies and shows, and keep your own data.

**Bring your own library**
- Add folders from your headset's storage.
- Link storage you control: SMB, SFTP, WebDAV, S3, OneDrive, Dropbox, and the Google Drive videos you choose.
- Filenames are recognised on the headset first; posters, cast, seasons, and episodes then arrive from TMDB.

**A player made for the panel**
- A large, resizable panel you drive with Touch controllers or your hands. Thumbsticks scroll whatever you point at.
- H.264, HEVC, VP9, and AV1 (Quest 3 and later), with surround sound including DTS and Dolby TrueHD.
- Subtitles, an equalizer, picture controls, and optional Skip Intro and Skip Credits prompts.

**Track what you watch**
- Resume every title where you left off.
- Rate, review, and keep a watchlist.
- Browse cast, crew, and upcoming release dates.

**Private by design**
- No ads, no analytics, no tracking.
- Your library and watch history stay on your headset.
- Metadata, subtitle search, and trailers run only when you ask, and a trailer plays only after you press Play.

Edendale is free and open source: https://github.com/Ju-Long/Edendale
```

The video line depends on B.7 passing on the headset; drop a codec from it if B.7 does.

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
