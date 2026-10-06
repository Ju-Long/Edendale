---
updated: 2026-10-06
summary:
  - Edendale has no account system and no servers of its own. The apps contain no analytics, advertising, or tracking.
  - Your library, settings, and saved logins stay on your device. Anything that syncs goes through a service you control, such as iCloud or your own OneDrive.
  - Google Drive, OneDrive, and Dropbox get read-only access, are reached directly from your device, and are used only to list and play your videos.
  - Movie details, subtitle search, skip prompts, and trailers contact the services named below, only for the purpose described.
---

## Who this policy covers

This policy covers the Edendale apps for Apple devices (iPhone, iPad, Mac,
Apple TV, and Apple Vision Pro), Android, and Windows, and this website,
edendale.babasama.com. Edendale is a free, open-source project developed in
public at [github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale).
"We" and "us" mean the people who develop it.

## We don't collect your data

Edendale has no account system and runs no servers that receive information
from the apps. The apps contain no analytics, advertising, tracking, or
crash-reporting code. Your information never reaches us, so we have nothing to
sell, rent, or share.

If you let your device share diagnostics with app developers, the store you
installed Edendale from (Apple, Google, or Microsoft) may give us crash reports
and aggregate usage statistics under its own privacy policy. We use them only
to fix problems.

## What stays on your device

- **Your library:** the folders and sources you add; the names, sizes, dates,
  and durations of the files Edendale finds in them; and the movie or episode
  each file was matched to.
- **Your settings:** playback, audio, picture, subtitle, and control
  preferences, including the choices remembered for each title.
- **Subtitles you download.**
- **Saved logins and linked accounts:** server passwords, S3 access keys, and
  cloud sign-in tokens, kept in the system's protected storage: the Keychain on
  Apple devices, storage encrypted with the Android Keystore, and Windows Data
  Protection (DPAPI). Short-lived access tokens are kept only in memory.

Edendale reads file names on your device to recognize movies and episodes
before it contacts any online service, and it never uploads your videos.

## What can sync, and where

Edendale syncs only through services you control, and only when you have
turned them on:

- **Apple devices:** with iCloud, your watch progress, ratings, favorites, and
  watchlist sync through your private iCloud database. Linked accounts and
  saved logins sync through iCloud Keychain to your iPhone, iPad, Mac, and
  Apple Vision Pro. Apple TV keeps its own copies.
- **Windows:** if you turn on OneDrive replication, your watch progress and
  title states are copied through a folder in your own OneDrive. Logins and
  accounts never leave the device.
- **Android:** Android's own backup can include your library and watch data.
  Logins, keys, and account tokens are excluded from backups and device
  transfers.
- **Your TMDB account (optional):** if you sign in to The Movie Database,
  Edendale keeps your favorites, watchlist, and ratings in step with that
  account. Watch progress is never sent to TMDB.

## Online services Edendale uses

Each service below receives your device's IP address, as any internet
connection does, along with the following:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  for movie and show details and artwork: the title and year Edendale reads
  from a file name (never the full file name, its folder, or the file itself)
  and the TMDB IDs of the titles you browse. If you sign in, also your TMDB
  session.
- **[Wyzie Subs](https://wyzie.io/privacy)**, only when you search for
  subtitles online: the title's TMDB ID, the season and episode numbers, the
  languages you ask for, and your API key.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, only while Skip
  Prompts is on (it is off by default): the title's TMDB ID, the season and
  episode numbers, and the video's duration.
- **[YouTube](https://policies.google.com/privacy)**, only when you choose to
  play a trailer. On Apple and Android devices, Edendale plays it in YouTube's
  privacy-enhanced mode (youtube-nocookie.com). On Windows, it opens the
  trailer on youtube.com in your browser.
- **The storage you link**, described in the next section.

## Storage you link

Edendale plays videos from folders on your device and from storage you link:
SMB, NFS, SFTP, and WebDAV servers, S3-compatible storage, Google Drive,
OneDrive, and Dropbox. The services available differ by platform; Google Drive
is currently available on Apple devices. Every connection goes directly from
your device to the service you chose. Nothing passes through a server we
operate.

- **Signing in:** Google Drive, OneDrive, and Dropbox sign you in on the
  provider's own page using OAuth 2.0 with PKCE, so Edendale never sees your
  password. Server logins (user names, passwords, and access keys) are sent
  only to the server they belong to.
- **Read-only access:** Edendale requests read-only permissions. Google:
  `openid`, `email`, and `drive.readonly`. Microsoft: `Files.Read`,
  `User.Read`, and `offline_access`. Dropbox: `account_info.read`,
  `files.metadata.read`, and `files.content.read`. Edendale cannot create,
  change, share, or delete anything in your storage.
- **What Edendale reads:** your account's ID and email address, to label the
  account and keep its sources apart; the names, sizes, dates, and durations of
  the files and folders in the locations you browse and link; and the contents
  of a video only while you play it.
- **What Edendale keeps:** file details become part of your library on the
  device. Sign-in tokens and logins go into protected storage, as described
  above. Video data is held in memory while it plays and is never saved to
  disk.
- **TVs:** an Apple TV can receive an account or login from your iPhone or iPad
  over an encrypted connection on your local network, only after you start the
  transfer on the TV and confirm it on the phone or tablet. On a TV, OneDrive
  can also sign in with a code that you approve on another device.

## Google user data

When you link Google Drive, Edendale accesses:

- your Google Account's unique ID and email address (`openid` and `email`), to
  show which account is linked and to tell your accounts apart; and
- the files and folders in your Google Drive (`drive.readonly`): Edendale lists
  the folders you browse and link, reads the names, sizes, dates, and video
  durations of the files in them, and streams the videos you choose to play.

Edendale uses this data only to provide its Google Drive source: browsing for
a folder, listing the videos in it, and playing them. As with any source,
Edendale reads file names on your device to recognize movies and episodes, and
sends only the recognized title and year to TMDB to look up details.

The data stays on your devices: file details in your library, and the linked
account (its ID, email address, and sign-in token) in the Keychain, which
iCloud Keychain syncs to your other Apple devices. It reaches an Apple TV only
when you confirm a transfer from your iPhone or iPad. Google user data is never
sent to us or to any server we operate, so we never see or read it. It is never
sold, never used for advertising, and never used to develop, improve, or train
artificial intelligence or machine learning models.

To end Edendale's access, remove the source, which also removes its files from
your library, and sign out in **Settings → Accounts**. **Sign Out and Revoke
Access** also revokes Edendale's access at Google. You can remove it at any
time from your
[Google Account's third-party connections](https://myaccount.google.com/connections).
Deleting the app deletes everything it stored on that device.

Edendale's use and transfer to any other app of information received from
Google APIs will adhere to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Microsoft and Dropbox accounts

OneDrive and Dropbox work the same way: read-only access, used only to list and
play your videos, and stored only on your devices. Sign out in **Settings →
Accounts**. For Dropbox, **Sign Out and Revoke Access** also ends Edendale's
access at Dropbox. You can also remove Edendale from the
[apps that can access your Microsoft account](https://account.live.com/consent/Manage)
or from your
[Dropbox connected apps](https://www.dropbox.com/account/connected_apps).
A work or school Microsoft account may be managed by your organization.

## This website

This website is a static site hosted on GitHub Pages. It sets no cookies,
stores nothing in your browser, has no forms, and loads no analytics, fonts,
or scripts from other sites. It picks a language from your browser's settings
without storing anything, and your choice of language is kept only in the page
address. GitHub, as the host, receives standard request information such as
your IP address; see the
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Links that open the Edendale app are handled on your device.

## Children

Edendale doesn't knowingly collect personal information from anyone,
including children. The apps send nothing to us, so there is nothing for us
to collect.

## Your choices

You can see, change, or delete your data in the app at any time: remove a
source, sign out of an account, turn off iCloud sync or OneDrive replication,
or delete the app. Because we hold none of your personal data, those controls
are how you exercise your rights to access or delete it. Data held by the
services above is covered by their own privacy policies.

## Changes to this policy

When the apps' handling of data changes, we update this page and the date at
the top. Every revision is public in the project's history on GitHub.

## Contact

Questions about this policy or about Edendale's privacy are welcome as an
issue at
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
