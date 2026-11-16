---
updated: 2026-11-16
summary:
  - Edendale has no account system and no servers of its own. The apps contain no analytics, advertising, or tracking.
  - Your library, settings, saved logins, and parental controls stay on your device. Anything that syncs goes through storage or a service you control, such as iCloud or your own Google Drive, OneDrive, Dropbox, or server.
  - Sync between devices is off until you turn it on. Each device then writes your watch history as a readable file to storage you choose, and logins move only directly between your own devices.
  - Edendale reaches Google Drive, OneDrive, and Dropbox directly from your device, only to list and play your videos and to keep its own sync files. In Google Drive it sees only the videos you pick and the files it creates.
  - Movie details, subtitle search, skip prompts, and trailers contact the services named below, only for the purpose described.
---

## Who this policy covers

This policy covers the Edendale apps for Apple devices (iPhone, iPad, Mac,
Apple TV, and Apple Vision Pro), Android (including Meta Quest headsets), and
Windows, and this website, edendale.babasama.com. Edendale is a free,
open-source project developed in public at
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale). "We" and
"us" mean the people who develop it.

## We don't collect your data

Edendale has no account system and runs no servers that receive information
from the apps. The apps contain no analytics, advertising, tracking, or
crash-reporting code. Your information never reaches us, so we have nothing to
sell, rent, or share.

If you let your device share diagnostics with app developers, the store you
installed Edendale from (Apple, Google, Meta, or Microsoft) may give us crash
reports and aggregate usage statistics under its own privacy policy. We use
them only to fix problems.

## What stays on your device

- **Your library:** the folders and sources you add; the names, sizes, dates,
  and durations of the files Edendale finds in them; and the movie or episode
  each file was matched to.
- **Your settings:** playback, audio, picture, subtitle, and control
  preferences, including the choices remembered for each title.
- **Subtitles you download.**
- **Saved logins and linked accounts:** server passwords, S3 access keys,
  cloud sign-in tokens (including the login for your sync location), your
  TMDB sign-in, and your subtitle API key, kept in the system's protected
  storage: the Keychain on Apple devices, storage encrypted with the Android
  Keystore, and Windows Data Protection (DPAPI). Short-lived access tokens are
  kept only in memory.
- **Parental controls:** the Young Audience Friendly switch and, on Android
  and Meta Quest, also the rating limits, the other parental switches, and
  the parental PIN, which is stored only as a salted, slow hash. Edendale
  never syncs them, sends them to another device, or sends them anywhere else.
  Your device's own backup may keep the Young Audience Friendly switch, but
  never the PIN or the rating limits.
- **What your device shows:** the titles you're watching, with their artwork
  and progress, can appear outside the app: in Edendale's widgets, on Apple
  TV's Top Shelf, in the Windows taskbar's jump list, in the system's media
  controls while something plays, and, if you turn on **Continue Watching on
  Home Screen**, in Android TV's Continue Watching row, which the TV's home
  screen app can read. Anyone who can see your device can see them there.

Edendale reads file names on your device to recognize movies and episodes
before it contacts any online service, and it never uploads your videos.

## What can sync, and where

Edendale syncs only through services you control, and only when you have
turned them on:

- **Sync between devices (Edendale 27.1 and later):** your watch history,
  through storage you choose, as described in the next section.
- **Apple devices:** with iCloud, your watch progress, ratings, reviews,
  favorites, and watchlist sync through your private iCloud database. Linked
  accounts, saved logins, your TMDB sign-in, and your subtitle API key sync
  through iCloud Keychain to your iPhone, iPad, Mac, and Apple Vision Pro.
  Apple TV keeps its own copies. Your device's own backups include Edendale's
  data, as they do any app's.
- **Windows:** before Edendale 27.1, Edendale copied your watch progress and
  title states through the `Apps/Edendale` folder in your own OneDrive
  whenever OneDrive was set up on the PC. Logins and accounts never left the
  device. Edendale 27.1 stops copying: it reads those files once, leaves them
  in place, and offers that folder as your sync location. Sync stays off
  until you agree.
- **Android:** Android's own backup can include your library, with the email
  address or name of the account each linked source belongs to, your watch
  data, your settings, and the Young Audience Friendly switch. Logins, keys,
  account tokens, sync settings, the parental PIN, and the other parental
  controls are excluded from backups and device transfers.
- **Meta Quest:** if Cloud backup is on in the headset's settings, Meta's
  backup uses Android's, with the same exclusions. Removing the app from the
  headset deletes everything it stored there. Meta keeps its backups, and
  restores the latest if you install Edendale again, until you delete them
  under Cloud backup in the headset's settings; see
  [Meta's help on cloud backup](https://www.meta.com/help/quest/399621398228171/).
- **Your TMDB account (optional):** if you sign in to The Movie Database,
  Edendale keeps your favorites, watchlist, and ratings in step with that
  account. Watch progress is never sent to TMDB.

## Sync between devices

In Edendale 27.1 and later, **Settings → Sync** can keep your watch history
the same on all your devices. It is off until you turn it on and choose a
location. Edendale still runs no server and has no account: your devices
share the history only through storage you already own.

- **What a sync file holds:** each device writes one file,
  `Edendale/<space name>/<device ID>.json`, with your watch progress and
  completion, ratings, reviews, favorites, and watchlist state, each keyed by
  the title's TMDB ID and stamped with the time it last changed and the device
  that changed it; the space name; the device's name and platform; a random ID
  made for sync; and the Edendale version. It never holds your library, file
  names or paths, logins, your TMDB session, or parental controls.
- **Where it lives:** in the location you choose and own: a folder on your
  device (iCloud Drive, or a folder another cloud app provides, included),
  Edendale's own iCloud Drive folder on Apple devices, your Google Drive,
  OneDrive (in its app folder, `Apps/Edendale`), Dropbox, or your own WebDAV,
  SMB, or SFTP server. Each device reads the other devices' files from there
  and combines them on the device. The files are plain, readable JSON, so
  anyone who can open that folder or account can read your watch history;
  Edendale says so when you choose a location.
- **How to stop:** **Turn Off** in Settings → Sync stops syncing on that
  device and offers to delete its file; the history on the device stays.
  Removing a device in Settings → Sync deletes that device's file. Clearing a
  title's progress, or **Delete Watch History** on Android, clears it on the
  device and, with sync on, on your other devices as they next sync; the sync
  files then keep a marker for each cleared title, without its progress, for
  up to a year, so that an older copy can't bring it back. To remove
  everything, turn sync off on every device and delete the `Edendale` folder
  from your storage.
- **Setting up another device:** **Set Up Another Device** sends the sync
  location and its login, and any other accounts and logins you tick, directly
  from one of your devices to another over your local network. One device
  shows a QR code, which the other scans or opens as a link, or, where both
  devices offer it, a code you type on the other. The two devices use it to
  agree on a key that encrypts the connection, and the sending device asks
  you to confirm the other device's name before anything is sent. Nothing
  passes through us or anyone else. On iPhone, iPad, Android phones and
  tablets, and Meta Quest 3 and 3S, Edendale can scan the code with the
  camera: it asks for camera access only when you choose to scan, reads the
  code on the device, and never saves or sends the camera's images. While the
  pairing screen is open, the device announces its name and platform on your
  local network so the other one can find it.
  Between platforms where a Google sign-in can't be moved, only the Google
  account's email address, the space name, and the sync folder's ID are sent,
  and you sign in to Google on the new device.
- **A device you remove** keeps the login it received, and the history it
  already has, until you revoke Edendale's access at your storage provider or
  change the server's password.

## Online services Edendale uses

Each service below receives your device's IP address, as any internet
connection does, along with the following:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  for movie and show details, artwork, and age ratings: the title Edendale
  reads from a file name, sometimes with its year (never the full file name,
  its folder, or the file itself), what you type into search, and the TMDB
  IDs of the titles you browse or whose details Edendale looks up. If you
  sign in, also your TMDB session.
- **[Wyzie Subs](https://wyzie.io/privacy)**, only when you search for
  subtitles online: the title's TMDB ID, the season and episode numbers, the
  languages and subtitle formats Edendale asks for, whether to include
  subtitles for the deaf and hard of hearing, and an API key: yours if you
  entered one, otherwise Edendale's own.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, only while Skip
  Prompts is on (it is off by default): the title's TMDB ID, the season and
  episode numbers, and the video's duration.
- **[YouTube](https://policies.google.com/privacy)**, only when you choose to
  play a trailer. On Apple devices other than Apple TV, and on Android
  devices other than Meta Quest, Edendale plays it in YouTube's
  privacy-enhanced mode (youtube-nocookie.com). On Apple TV, it opens the
  trailer in the YouTube app; on Windows, on youtube.com in your browser; and
  on Meta Quest, in the browser or the YouTube app.
- **The storage you link**, and the storage you choose for sync, described in
  the next section.

## Storage you link

Edendale plays videos from folders on your device and from storage you link:
SMB, NFS, SFTP, and WebDAV servers, S3-compatible storage, Google Drive,
OneDrive, and Dropbox. The services available differ by platform. Every
connection goes directly from your device to the service you chose. Nothing
passes through a server we operate.

- **Signing in:** Google Drive, OneDrive, and Dropbox sign you in on the
  provider's own page using OAuth 2.0 with PKCE, so Edendale never sees your
  password. Server logins (user names, passwords, and access keys) are sent
  only to the server they belong to.
- **Limited access:** Google gets only `drive.file`, which covers the videos
  you pick in Google's own file picker and the files Edendale creates, and
  nothing else in your Drive. Microsoft: `Files.Read`,
  `Files.ReadWrite.AppFolder`, `User.Read`, and `offline_access`. Dropbox:
  `account_info.read`, `files.metadata.read`, `files.content.read`, and
  `files.content.write`. Edendale only reads your videos: it never uploads,
  changes, shares, or deletes them or anything else in your storage. The only
  files it writes or deletes are its own sync files, and only if you turn on
  sync: in its `Edendale` folder in Google Drive, Dropbox, or the server or
  folder you chose, and in OneDrive's app folder.
- **What Edendale reads:** your account's ID, email address, and name, to
  label the account and keep its sources apart; the names, sizes, dates, and
  durations of the files and folders in the locations you browse and link,
  or, for Google Drive, of the videos you pick; the contents of a video only
  while you play it; and, if you turn on sync, your devices' sync files.
- **What Edendale keeps:** file details become part of your library on the
  device. Sign-in tokens and logins go into protected storage, as described
  above. Video data is held in memory while it plays and is never saved to
  disk.
- **TVs:** an Apple TV can receive an account or login from your iPhone or
  iPad, and an Android TV from Edendale on another Android device, over an
  encrypted connection on your local network, only after you start the
  transfer on the TV and confirm it on the other device. While an Android TV
  waits, it announces its name on your local network. On a TV, OneDrive can
  also sign in with a code that you approve on another device. From Edendale
  27.1, any of your devices can set up another one this way, as described in
  Sync between devices.

## Google user data

When you link Google Drive, you choose videos in Google's own file picker,
which opens when you sign in and again whenever you use **Choose More
Videos**. If you sign in to Google Drive only to keep sync files there, the
picker doesn't open. Edendale accesses:

- the videos you pick, and nothing else in your Google Drive (`drive.file`):
  Edendale lists them, reads their names, sizes, dates, and video durations,
  and streams the ones you choose to play. Google shares your picks across
  Edendale's apps, so a video you pick on one device can also appear on
  another device signed in to the same Google account;
- if you choose Google Drive for sync, the `Edendale` folder and the sync
  files that Edendale itself creates there, which it reads, replaces, and
  deletes as described in Sync between devices; and
- your Google Drive user ID, email address, and name, which Google Drive
  provides with that same permission, to show which account is linked and to
  tell your accounts apart.

Edendale uses this data only to provide its Google Drive source, listing the
videos you picked and playing them, and, if you choose it, to keep your sync
files in your Drive. It never uploads, changes, shares, or deletes your videos
or any other file in your Drive. As with any source, Edendale reads file names
on your device to recognize movies and episodes, and sends only the recognized
title, sometimes with its year, to TMDB to look up details.

The data stays on your devices and in your own Drive: file details in your
library, and the linked account (its ID, email address, name, and sign-in
token) in protected storage, as described above. On Apple devices, iCloud
Keychain syncs the account to your other Apple devices. It reaches another
device only when you confirm a transfer from one of yours; between platforms
where a Google sign-in can't be moved, only the email address and the sync
folder's ID are sent. If you remove a video from your library, Edendale may
keep its file ID on that device only, so that the video isn't listed again.
Google user data is never sent to us or to any server we operate, so we never
see or read it. It is never sold, never used for advertising, and never used
to develop, improve, or train artificial intelligence or machine learning
models.

To end Edendale's access, remove the source, which also removes its files from
your library, and sign out in **Settings → Accounts**. **Sign Out and Revoke
Access**, or **Sign Out** on Windows, also revokes Edendale's access at
Google. You can remove it at any
time from your
[Google Account's third-party connections](https://myaccount.google.com/connections).
Revoking access ends it on every device, TVs included, and Google forgets the
videos you picked. Sync files that Edendale created stay in your Drive until
you delete them. Deleting the app deletes everything it stored on that device.
A backup on Android or Meta Quest may keep a copy of your library, including
the email address or name of the account its videos come from, but never the
account's sign-in token.

Edendale's use and transfer to any other app of information received from
Google APIs will adhere to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Microsoft and Dropbox accounts

OneDrive and Dropbox work much the same way: read access to the folders you
browse and link, used only to list and play your videos, and, if you choose
one for sync, write access used only for Edendale's own sync files, in
OneDrive's app folder (`Apps/Edendale`) or in the `Edendale` folder in your
Dropbox. Edendale never changes or deletes anything else there. Account
details and sign-in tokens are stored only on your devices. Sign out in
**Settings → Accounts**. On Apple devices and Android, **Sign Out and Revoke
Access** also ends Edendale's access at Dropbox. You can also remove Edendale
from the
[apps that can access your Microsoft account](https://account.live.com/consent/Manage)
or from your
[Dropbox connected apps](https://www.dropbox.com/account/connected_apps).
Sync files stay in your storage until you delete them. A work or school
Microsoft account may be managed by your organization.

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
source, sign out of an account, turn off sync between devices, iCloud sync, or
your device's backup, delete the sync files (and, on Windows, the older
OneDrive copies) from your storage, or delete the app. Because we hold none of
your personal data, those controls are how you exercise your rights to access
or delete it. Data held by the services above is covered by their own privacy
policies.

## Changes to this policy

When the apps' handling of data changes, we update this page and the date at
the top. Every revision is public in the project's history on GitHub.

## Contact

Questions about this policy or about Edendale's privacy are welcome as an
issue at
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
