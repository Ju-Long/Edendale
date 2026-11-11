---
updated: 2026-11-11
summary:
  - Edendale hat kein Kontosystem und keine eigenen Server. Die Apps enthalten keine Analysen, keine Werbung und kein Tracking.
  - Deine Mediathek, deine Einstellungen, gespeicherte Anmeldedaten und die Kindersicherung bleiben auf deinem Gerät. Was synchronisiert wird, läuft über einen Speicher oder Dienst, den du kontrollierst, etwa iCloud oder dein eigenes Google Drive, OneDrive, Dropbox oder deinen eigenen Server.
  - Die Synchronisierung zwischen Geräten ist aus, bis du sie einschaltest. Dann schreibt jedes Gerät deinen Wiedergabeverlauf als lesbare Datei in einen Speicher deiner Wahl, und Anmeldedaten gehen nur direkt von einem deiner Geräte zum anderen.
  - Edendale spricht Google Drive, OneDrive und Dropbox direkt von deinem Gerät aus an, nur um deine Videos aufzulisten und abzuspielen und seine eigenen Synchronisierungsdateien zu führen. In Google Drive sieht es nur die Videos, die du auswählst, und die Dateien, die es selbst anlegt.
  - Filminfos, Untertitelsuche, Hinweise zum Überspringen und Trailer kontaktieren die unten genannten Dienste – nur für den beschriebenen Zweck.
---

## Geltungsbereich

Diese Datenschutzerklärung gilt für die Edendale-Apps für Apple-Geräte (iPhone,
iPad, Mac, Apple TV und Apple Vision Pro), Android (einschließlich
Meta-Quest-Headsets) und Windows sowie für diese Website,
edendale.babasama.com. Edendale ist ein freies, quelloffenes Projekt, das
öffentlich auf
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale) entwickelt
wird. „Wir“ und „uns“ meint die Menschen, die es entwickeln.

## Wir erheben keine Daten über dich

Edendale hat kein Kontosystem und betreibt keine Server, die Informationen aus
den Apps empfangen. Die Apps enthalten keinen Code für Analysen, Werbung,
Tracking oder Absturzberichte. Deine Informationen erreichen uns nie, also
haben wir nichts zu verkaufen, zu vermieten oder weiterzugeben.

Wenn du deinem Gerät erlaubst, Diagnosedaten mit App-Entwicklern zu teilen,
kann uns der Store, aus dem du Edendale installiert hast (Apple, Google, Meta
oder Microsoft), Absturzberichte und zusammengefasste Nutzungsstatistiken gemäß
seiner eigenen Datenschutzerklärung bereitstellen. Wir nutzen sie nur, um
Fehler zu beheben.

## Was auf deinem Gerät bleibt

- **Deine Mediathek:** die Ordner und Quellen, die du hinzufügst; Namen,
  Größen, Datumsangaben und Laufzeiten der Dateien, die Edendale darin findet;
  und der Film oder die Folge, der jede Datei zugeordnet wurde.
- **Deine Einstellungen:** Vorlieben für Wiedergabe, Ton, Bild, Untertitel und
  Steuerung, einschließlich der für jeden Titel gemerkten Auswahl.
- **Untertitel, die du herunterlädst.**
- **Gespeicherte Anmeldedaten und verknüpfte Konten:** Server-Passwörter,
  S3-Zugriffsschlüssel und Anmelde-Tokens für Cloud-Dienste, einschließlich
  der Anmeldedaten für deinen Synchronisierungsort, aufbewahrt im geschützten
  Speicher des Systems: dem Schlüsselbund (Keychain) auf Apple-Geräten, einem
  mit dem Android Keystore verschlüsselten Speicher und dem
  Windows-Datenschutz (DPAPI). Kurzlebige Zugriffstokens werden nur im
  Arbeitsspeicher gehalten.
- **Kindersicherung:** die Altersfreigabe-Grenzen, die übrigen Schalter der
  Kindersicherung und die Kindersicherungs-PIN, die nur als gesalzener,
  langsamer Hash gespeichert wird. Edendale synchronisiert sie nie, sendet sie
  nie an ein anderes Gerät und nirgendwohin sonst.

Edendale liest Dateinamen auf deinem Gerät, um Filme und Folgen zu erkennen,
bevor es einen Online-Dienst kontaktiert, und lädt deine Videos nie
irgendwohin hoch.

## Was synchronisiert werden kann – und wohin

Edendale synchronisiert nur über Dienste, die du kontrollierst, und nur, wenn
du sie eingeschaltet hast:

- **Synchronisierung zwischen Geräten (ab Edendale 27.1):** dein
  Wiedergabeverlauf, über einen Speicher deiner Wahl, wie im nächsten
  Abschnitt beschrieben.
- **Apple-Geräte:** Mit iCloud werden dein Fortschritt, deine Bewertungen,
  Favoriten und deine Watchlist über deine private iCloud-Datenbank
  synchronisiert. Verknüpfte Konten und gespeicherte Anmeldedaten werden über
  den iCloud-Schlüsselbund mit deinem iPhone, iPad, Mac und deiner Apple
  Vision Pro synchronisiert. Apple TV behält eigene Kopien.
- **Windows:** Wenn du vor Edendale 27.1 die OneDrive-Replikation
  eingeschaltet hast, werden dein Fortschritt und der Status deiner Titel über
  einen Ordner in deinem eigenen OneDrive kopiert. Anmeldedaten und Konten
  verlassen das Gerät nie. Edendale 27.1 ersetzt sie durch die
  Synchronisierung zwischen Geräten: Es liest diese Dateien einmal ein und
  lässt sie, wo sie sind.
- **Android:** Die Android-eigene Datensicherung kann deine Mediathek und
  deine Wiedergabedaten enthalten. Anmeldedaten, Schlüssel, Konto-Tokens, die
  Synchronisierungseinstellungen sowie die Kindersicherungs-PIN und die
  Altersfreigabe-Grenzen sind von Sicherungen und Geräteübertragungen
  ausgeschlossen.
- **Meta Quest:** Wenn die Cloud-Sicherung in den Einstellungen des Headsets
  eingeschaltet ist, nutzt Metas Sicherung die von Android, mit denselben
  Ausschlüssen. Wenn du die App vom Headset entfernst, wird alles gelöscht,
  was sie dort gespeichert hat. Meta behält seine Sicherungen und stellt die
  neueste wieder her, wenn du Edendale erneut installierst, bis du sie unter
  „Cloud-Sicherung“ in den Einstellungen des Headsets löschst; siehe
  [Metas Hilfe zur Cloud-Sicherung](https://www.meta.com/help/quest/399621398228171/).
- **Dein TMDB-Konto (optional):** Wenn du dich bei The Movie Database
  anmeldest, hält Edendale deine Favoriten, deine Watchlist und deine
  Bewertungen mit diesem Konto abgeglichen. Dein Wiedergabefortschritt wird
  nie an TMDB gesendet.

## Synchronisierung zwischen Geräten

Ab Edendale 27.1 kann **Einstellungen → Synchronisierung** deinen
Wiedergabeverlauf auf all deinen Geräten gleich halten. Sie ist aus, bis du
sie einschaltest und einen Ort wählst. Edendale betreibt weiterhin keinen
Server und hat kein Kontosystem: Deine Geräte teilen den Verlauf nur über
einen Speicher, der dir bereits gehört.

- **Was eine Synchronisierungsdatei enthält:** Jedes Gerät schreibt eine
  Datei, `Edendale/<Name des Bereichs>/<Geräte-ID>.json`, mit deinem
  Wiedergabefortschritt und dem Status „gesehen“, deinen Bewertungen,
  Rezensionen, Favoriten und dem Watchlist-Status, jeweils nach der TMDB-ID
  des Titels; dem Namen und der Plattform des Geräts; einer zufälligen ID, die
  für die Synchronisierung erzeugt wird; und der Edendale-Version. Sie enthält
  nie deine Mediathek, Dateinamen oder -pfade, Anmeldedaten, deine
  TMDB-Sitzung oder die Kindersicherung.
- **Wo sie liegt:** an dem Ort, den du wählst und der dir gehört: in einem
  Ordner auf deinem Gerät (auch iCloud Drive oder ein Ordner, den eine andere
  Cloud-App bereitstellt), in Edendales eigenem iCloud-Drive-Ordner auf
  Apple-Geräten, in deinem Google Drive, in OneDrive (im App-Ordner,
  `Apps/Edendale`), in Dropbox oder auf deinem eigenen WebDAV-, SMB- oder
  SFTP-Server. Jedes Gerät liest dort die Dateien der anderen Geräte und führt
  sie auf dem Gerät zusammen. Die Dateien sind einfaches, lesbares JSON: Wer
  diesen Ordner oder dieses Konto öffnen kann, kann deinen Wiedergabeverlauf
  lesen. Edendale weist darauf hin, wenn du einen Ort wählst.
- **So hörst du auf:** **Ausschalten** unter Einstellungen →
  Synchronisierung beendet die Synchronisierung auf diesem Gerät und bietet
  an, seine Datei zu löschen; der Verlauf auf dem Gerät bleibt. Wenn du unter
  Einstellungen → Synchronisierung ein Gerät entfernst, wird dessen Datei
  gelöscht. Unter Android löscht **Wiedergabeverlauf löschen** den
  Fortschritt und die Gesehen-Markierungen auf dem Gerät und, bei
  eingeschalteter Synchronisierung, auf deinen anderen Geräten bei deren
  nächster Synchronisierung. Die Synchronisierungsdateien behalten danach bis
  zu ein Jahr lang für jeden gelöschten Titel eine Markierung ohne
  Fortschritt, damit eine ältere Kopie ihn nicht zurückbringen kann. Um alles
  zu entfernen, schalte die Synchronisierung auf jedem Gerät aus und lösche
  den Ordner `Edendale` aus deinem Speicher.
- **Ein weiteres Gerät einrichten:** **Anderes Gerät einrichten** sendet den
  Synchronisierungsort und seine Anmeldedaten sowie alle weiteren Konten und
  Anmeldedaten, die du ankreuzt, direkt von einem deiner Geräte zu einem
  anderen über dein lokales Netzwerk. Die Verbindung ist mit einem Schlüssel
  verschlüsselt, auf den sich beide Geräte einigen, wenn du einen QR-Code
  scannst oder einen auf dem anderen Gerät angezeigten Code eintippst, und
  das sendende Gerät lässt dich den Namen des anderen Geräts bestätigen, bevor
  etwas gesendet wird. Nichts läuft über uns oder über Dritte. Solange der
  Kopplungsbildschirm geöffnet ist, gibt das Gerät seinen Namen in deinem
  lokalen Netzwerk bekannt, damit das andere es findet. Zwischen Plattformen,
  zwischen denen sich eine Google-Anmeldung nicht übertragen lässt, werden nur
  die E-Mail-Adresse des Google-Kontos, der Name des Bereichs und die ID des
  Synchronisierungsordners gesendet, und du meldest dich auf dem neuen Gerät
  selbst bei Google an.
- **Ein Gerät, das du entfernst,** behält die Anmeldedaten, die es erhalten
  hat, und den Verlauf, den es bereits hat, bis du Edendales Zugriff bei
  deinem Speicheranbieter widerrufst oder das Passwort des Servers änderst.

## Online-Dienste, die Edendale nutzt

Jeder der folgenden Dienste erhält die IP-Adresse deines Geräts, wie bei
jeder Internetverbindung, und außerdem Folgendes:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  für Infos und Bilder zu Filmen und Serien: Titel und Jahr, die Edendale aus
  einem Dateinamen liest (nie den vollständigen Dateinamen, seinen Ordner oder
  die Datei selbst), und die TMDB-IDs der Titel, die du dir ansiehst. Wenn du
  dich anmeldest, auch deine TMDB-Sitzung.
- **[Wyzie Subs](https://wyzie.io/privacy)**, nur wenn du online nach
  Untertiteln suchst: die TMDB-ID des Titels, Staffel- und Folgennummer, die
  gewünschten Sprachen und deinen API-Schlüssel.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, nur solange
  „Hinweise zum Überspringen“ eingeschaltet ist (standardmäßig aus): die
  TMDB-ID des Titels, Staffel- und Folgennummer und die Laufzeit des Videos.
- **[YouTube](https://policies.google.com/privacy)**, nur wenn du einen Trailer
  abspielen möchtest. Auf Apple-Geräten und auf Android-Geräten außer Meta
  Quest spielt Edendale ihn im erweiterten Datenschutzmodus von YouTube ab
  (youtube-nocookie.com). Unter Windows öffnet es den Trailer auf youtube.com
  in deinem Browser, auf Meta Quest im Browser oder in der YouTube-App.
- **Die Speicher, die du verknüpfst**, und der Speicher, den du für die
  Synchronisierung wählst, beschrieben im nächsten Abschnitt.

## Speicher, die du verknüpfst

Edendale spielt Videos aus Ordnern auf deinem Gerät und aus Speichern ab, die
du verknüpfst: SMB-, NFS-, SFTP- und WebDAV-Server, S3-kompatible Speicher,
Google Drive, OneDrive und Dropbox. Welche Dienste verfügbar sind, hängt von
der Plattform ab. Jede Verbindung geht direkt von deinem Gerät zum gewählten
Dienst. Nichts läuft über einen Server, den wir betreiben.

- **Anmeldung:** Bei Google Drive, OneDrive und Dropbox meldest du dich auf
  der Seite des Anbieters an, über OAuth 2.0 mit PKCE – Edendale sieht dein
  Passwort also nie. Server-Anmeldedaten (Benutzernamen, Passwörter und
  Zugriffsschlüssel) werden nur an den Server gesendet, zu dem sie gehören.
- **Begrenzter Zugriff:** Google erhält nur `drive.file`; das umfasst die
  Videos, die du in Googles eigener Dateiauswahl auswählst, und die Dateien,
  die Edendale anlegt, und sonst nichts in deinem Drive. Microsoft:
  `Files.Read`, `Files.ReadWrite.AppFolder`, `User.Read` und
  `offline_access`. Dropbox: `account_info.read`, `files.metadata.read`,
  `files.content.read` und `files.content.write`. Edendale liest deine Videos
  nur: Es lädt sie nie hoch, ändert, teilt oder löscht sie oder irgendetwas
  anderes in deinem Speicher. Die einzigen Dateien, die es schreibt oder
  löscht, sind seine eigenen Synchronisierungsdateien, und nur, wenn du die
  Synchronisierung einschaltest: in seinem Ordner `Edendale` in Google Drive,
  in Dropbox oder auf dem Server oder in dem Ordner, den du gewählt hast, und
  im App-Ordner von OneDrive.
- **Was Edendale liest:** die ID, die E-Mail-Adresse und den Namen deines
  Kontos, um das Konto zu benennen und seine Quellen auseinanderzuhalten;
  Namen, Größen, Datumsangaben und Laufzeiten der Dateien und Ordner an den
  Orten, die du durchsuchst und verknüpfst, bei Google Drive die der Videos,
  die du auswählst; den Inhalt eines Videos nur, während du es abspielst; und,
  wenn du die Synchronisierung einschaltest, die Synchronisierungsdateien
  deiner Geräte.
- **Was Edendale behält:** Dateiinfos werden Teil deiner Mediathek auf dem
  Gerät. Anmelde-Tokens und Anmeldedaten kommen in den geschützten Speicher,
  wie oben beschrieben. Videodaten liegen während der Wiedergabe im
  Arbeitsspeicher und werden nie auf der Festplatte gespeichert.
- **Fernseher:** Ein Apple TV kann ein Konto oder Anmeldedaten von deinem
  iPhone oder iPad erhalten, ein Android TV von Edendale auf einem anderen
  Android-Gerät, jeweils über eine verschlüsselte Verbindung in deinem lokalen
  Netzwerk – nur nachdem du die Übertragung auf dem Fernseher gestartet und
  auf dem anderen Gerät bestätigt hast. Auf einem Fernseher kann sich OneDrive
  auch mit einem Code anmelden, den du auf einem anderen Gerät bestätigst. Ab
  Edendale 27.1 kann jedes deiner Geräte ein anderes auf diese Weise
  einrichten, wie unter „Synchronisierung zwischen Geräten“ beschrieben.

## Google-Nutzerdaten

Wenn du Google Drive verknüpfst, wählst du Videos in Googles eigener
Dateiauswahl aus. Sie öffnet sich bei der Anmeldung und jedes Mal, wenn du
**Weitere Videos auswählen** verwendest. Wenn du dich bei Google Drive nur
anmeldest, um dort Synchronisierungsdateien zu führen, öffnet sich die
Dateiauswahl nicht. Edendale greift auf Folgendes zu:

- die Videos, die du auswählst, und sonst nichts in deinem Google Drive
  (`drive.file`): Edendale listet sie auf, liest ihre Namen, Größen,
  Datumsangaben und Videolaufzeiten und streamt die, die du abspielen
  möchtest. Google teilt deine Auswahl zwischen den Edendale-Apps, sodass ein
  Video, das du auf einem Gerät auswählst, auch auf einem anderen Gerät
  erscheinen kann, das mit demselben Google-Konto angemeldet ist;
- wenn du Google Drive für die Synchronisierung wählst, den Ordner `Edendale`
  und die Synchronisierungsdateien, die Edendale selbst dort anlegt und die es
  liest, ersetzt und löscht, wie unter „Synchronisierung zwischen Geräten“
  beschrieben; und
- die Google-Drive-Nutzer-ID, die E-Mail-Adresse und den Namen deines Kontos,
  die Google Drive mit derselben Berechtigung bereitstellt, um anzuzeigen,
  welches Konto verknüpft ist, und deine Konten auseinanderzuhalten.

Edendale nutzt diese Daten ausschließlich, um seine Google-Drive-Quelle
bereitzustellen – die ausgewählten Videos auflisten und abspielen – und, wenn
du es so wählst, um deine Synchronisierungsdateien in deinem Drive zu führen.
Es lädt deine Videos nie hoch und ändert, teilt oder löscht weder sie noch
andere Dateien in deinem Drive. Wie bei jeder Quelle liest Edendale die
Dateinamen auf deinem Gerät, um Filme und Folgen zu erkennen, und sendet nur
den erkannten Titel und das Jahr an TMDB, um Infos abzurufen.

Die Daten bleiben auf deinen Geräten und in deinem eigenen Drive: Dateiinfos
in deiner Mediathek, das verknüpfte Konto (seine ID, E-Mail-Adresse, sein Name
und sein Anmelde-Token) im geschützten Speicher, wie oben beschrieben. Auf
Apple-Geräten synchronisiert der iCloud-Schlüsselbund das Konto mit deinen
anderen Apple-Geräten. Auf ein anderes Gerät gelangt es nur, wenn du eine
Übertragung von einem deiner Geräte bestätigst; zwischen Plattformen, zwischen
denen sich eine Google-Anmeldung nicht übertragen lässt, werden nur die
E-Mail-Adresse und die ID des Synchronisierungsordners gesendet. Wenn du ein
Video aus deiner Mediathek entfernst, behält Edendale seine Datei-ID nur auf
diesem Gerät, damit das Video nicht wieder aufgelistet wird. Google-Nutzerdaten
werden nie an uns oder an einen von uns betriebenen Server gesendet – wir
sehen oder lesen sie also nie. Sie werden nie verkauft, nie für Werbung
verwendet und nie genutzt, um Modelle für künstliche Intelligenz oder
maschinelles Lernen zu entwickeln, zu verbessern oder zu trainieren.

Um Edendales Zugriff zu beenden, entferne die Quelle (das entfernt auch ihre
Dateien aus deiner Mediathek) und melde dich unter **Einstellungen →
Accounts** ab. **Abmelden und Zugriff widerrufen** widerruft Edendales Zugriff
zusätzlich bei Google. Du kannst den Zugriff jederzeit über die
[Drittanbieter-Verbindungen deines Google-Kontos](https://myaccount.google.com/connections)
entfernen. Der Widerruf beendet den Zugriff auf allen Geräten, auch auf
Fernsehern, und Google vergisst die Videos, die du ausgewählt hast.
Synchronisierungsdateien, die Edendale angelegt hat, bleiben in deinem Drive,
bis du sie löschst. Wenn du die App löschst, wird alles gelöscht, was sie auf
diesem Gerät gespeichert hat. Eine Sicherung unter Android oder auf Meta Quest
kann eine Kopie deiner Mediathek behalten, aber nie das verknüpfte Konto oder
sein Anmelde-Token.

Die Nutzung von Informationen, die Edendale über Google-APIs erhält, und ihre
Übertragung an andere Apps erfolgen gemäß der
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(Richtlinie zu Nutzerdaten für Google API-Dienste), einschließlich der
Anforderungen zur eingeschränkten Nutzung (Limited Use).

## Microsoft- und Dropbox-Konten

OneDrive und Dropbox funktionieren ganz ähnlich: Lesezugriff auf die Ordner,
die du durchsuchst und verknüpfst, nur zum Auflisten und Abspielen deiner
Videos genutzt, und, wenn du einen davon für die Synchronisierung wählst,
Schreibzugriff nur für Edendales eigene Synchronisierungsdateien, im
App-Ordner von OneDrive (`Apps/Edendale`) oder im Ordner `Edendale` in deiner
Dropbox. Edendale ändert oder löscht dort nie etwas anderes. Kontodaten und
Anmelde-Tokens werden nur auf deinen Geräten gespeichert. Melde dich unter
**Einstellungen → Accounts** ab. Bei Dropbox beendet **Abmelden und Zugriff
widerrufen** auch Edendales Zugriff bei Dropbox. Du kannst Edendale außerdem
aus den
[Apps mit Zugriff auf dein Microsoft-Konto](https://account.live.com/consent/Manage)
oder aus deinen
[verbundenen Dropbox-Apps](https://www.dropbox.com/account/connected_apps)
entfernen. Synchronisierungsdateien bleiben in deinem Speicher, bis du sie
löschst. Ein Microsoft-Geschäfts- oder Schulkonto wird unter Umständen von
deiner Organisation verwaltet.

## Diese Website

Diese Website ist eine statische Seite, die auf GitHub Pages gehostet wird.
Sie setzt keine Cookies, speichert nichts in deinem Browser, hat keine
Formulare und lädt keine Analysen, Schriften oder Skripte von anderen
Websites. Sie wählt eine Sprache anhand der Einstellungen deines Browsers
aus, ohne etwas zu speichern, und deine Sprachwahl steht nur in der Adresse
der Seite. GitHub erhält als Host die üblichen Anfragedaten wie deine
IP-Adresse; siehe das
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Links, die die Edendale-App öffnen, werden auf deinem Gerät verarbeitet.

## Kinder

Edendale erhebt wissentlich keine personenbezogenen Daten – von niemandem,
auch nicht von Kindern. Die Apps senden uns nichts, also gibt es nichts, was
wir erheben könnten.

## Deine Möglichkeiten

Du kannst deine Daten jederzeit in der App ansehen, ändern oder löschen: eine
Quelle entfernen, dich von einem Konto abmelden, die Synchronisierung zwischen
Geräten, die iCloud-Synchronisierung, die OneDrive-Replikation oder die
Sicherung deines Geräts ausschalten, die Synchronisierungsdateien aus deinem
Speicher löschen oder die App löschen. Da wir keine personenbezogenen Daten
von dir haben, übst du deine Rechte auf Auskunft und Löschung über diese
Funktionen aus. Für Daten bei den oben genannten Diensten gelten deren eigene
Datenschutzerklärungen.

## Änderungen dieser Datenschutzerklärung

Wenn sich der Umgang der Apps mit Daten ändert, aktualisieren wir diese Seite
und das Datum oben. Jede Fassung ist öffentlich in der Projekthistorie auf
GitHub einsehbar.

## Kontakt

Fragen zu dieser Datenschutzerklärung oder zum Datenschutz bei Edendale
kannst du gern als Issue unter
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues)
stellen.
