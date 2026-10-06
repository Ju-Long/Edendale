---
updated: 2026-10-06
summary:
  - Edendale hat kein Kontosystem und keine eigenen Server. Die Apps enthalten keine Analysen, keine Werbung und kein Tracking.
  - Deine Mediathek, deine Einstellungen und gespeicherte Anmeldedaten bleiben auf deinem Gerät. Was synchronisiert wird, läuft über einen Dienst, den du kontrollierst, etwa iCloud oder dein eigenes OneDrive.
  - Google Drive, OneDrive und Dropbox erhalten nur Lesezugriff, werden direkt von deinem Gerät aus angesprochen und dienen nur dazu, deine Videos aufzulisten und abzuspielen.
  - Filminfos, Untertitelsuche, Hinweise zum Überspringen und Trailer kontaktieren die unten genannten Dienste – nur für den beschriebenen Zweck.
---

## Geltungsbereich

Diese Datenschutzerklärung gilt für die Edendale-Apps für Apple-Geräte
(iPhone, iPad, Mac, Apple TV und Apple Vision Pro), Android und Windows sowie
für diese Website, edendale.babasama.com. Edendale ist ein freies,
quelloffenes Projekt, das öffentlich auf
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale) entwickelt
wird. „Wir“ und „uns“ meint die Menschen, die es entwickeln.

## Wir erheben keine Daten über dich

Edendale hat kein Kontosystem und betreibt keine Server, die Informationen aus
den Apps empfangen. Die Apps enthalten keinen Code für Analysen, Werbung,
Tracking oder Absturzberichte. Deine Informationen erreichen uns nie, also
haben wir nichts zu verkaufen, zu vermieten oder weiterzugeben.

Wenn du deinem Gerät erlaubst, Diagnosedaten mit App-Entwicklern zu teilen,
kann uns der Store, aus dem du Edendale installiert hast (Apple, Google oder
Microsoft), Absturzberichte und zusammengefasste Nutzungsstatistiken gemäß
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
  S3-Zugriffsschlüssel und Anmelde-Tokens für Cloud-Dienste, aufbewahrt im
  geschützten Speicher des Systems: dem Schlüsselbund (Keychain) auf
  Apple-Geräten, einem mit dem Android Keystore verschlüsselten Speicher und
  dem Windows-Datenschutz (DPAPI). Kurzlebige Zugriffstokens werden nur im
  Arbeitsspeicher gehalten.

Edendale liest Dateinamen auf deinem Gerät, um Filme und Folgen zu erkennen,
bevor es einen Online-Dienst kontaktiert, und lädt deine Videos nie
irgendwohin hoch.

## Was synchronisiert werden kann – und wohin

Edendale synchronisiert nur über Dienste, die du kontrollierst, und nur, wenn
du sie eingeschaltet hast:

- **Apple-Geräte:** Mit iCloud werden dein Fortschritt, deine Bewertungen,
  Favoriten und deine Watchlist über deine private iCloud-Datenbank
  synchronisiert. Verknüpfte Konten und gespeicherte Anmeldedaten werden über
  den iCloud-Schlüsselbund mit deinem iPhone, iPad, Mac und deiner Apple
  Vision Pro synchronisiert. Apple TV behält eigene Kopien.
- **Windows:** Wenn du die OneDrive-Replikation einschaltest, werden dein
  Fortschritt und der Status deiner Titel über einen Ordner in deinem eigenen
  OneDrive kopiert. Anmeldedaten und Konten verlassen das Gerät nie.
- **Android:** Die Android-eigene Datensicherung kann deine Mediathek und
  deine Wiedergabedaten enthalten. Anmeldedaten, Schlüssel und Konto-Tokens
  sind von Sicherungen und Geräteübertragungen ausgeschlossen.
- **Dein TMDB-Konto (optional):** Wenn du dich bei The Movie Database
  anmeldest, hält Edendale deine Favoriten, deine Watchlist und deine
  Bewertungen mit diesem Konto abgeglichen. Dein Wiedergabefortschritt wird
  nie an TMDB gesendet.

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
- **[YouTube](https://policies.google.com/privacy)**, nur wenn du einen
  Trailer abspielen möchtest. Auf Apple- und Android-Geräten spielt Edendale
  ihn im erweiterten Datenschutzmodus von YouTube ab (youtube-nocookie.com).
  Unter Windows öffnet es den Trailer auf youtube.com in deinem Browser.
- **Die Speicher, die du verknüpfst**, beschrieben im nächsten Abschnitt.

## Speicher, die du verknüpfst

Edendale spielt Videos aus Ordnern auf deinem Gerät und aus Speichern ab, die
du verknüpfst: SMB-, NFS-, SFTP- und WebDAV-Server, S3-kompatible Speicher,
Google Drive, OneDrive und Dropbox. Welche Dienste verfügbar sind, hängt von
der Plattform ab; Google Drive gibt es derzeit auf Apple-Geräten. Jede
Verbindung geht direkt von deinem Gerät zum gewählten Dienst. Nichts läuft
über einen Server, den wir betreiben.

- **Anmeldung:** Bei Google Drive, OneDrive und Dropbox meldest du dich auf
  der Seite des Anbieters an, über OAuth 2.0 mit PKCE – Edendale sieht dein
  Passwort also nie. Server-Anmeldedaten (Benutzernamen, Passwörter und
  Zugriffsschlüssel) werden nur an den Server gesendet, zu dem sie gehören.
- **Nur Lesezugriff:** Edendale fordert nur Leseberechtigungen an. Google:
  `openid`, `email` und `drive.readonly`. Microsoft: `Files.Read`,
  `User.Read` und `offline_access`. Dropbox: `account_info.read`,
  `files.metadata.read` und `files.content.read`. Edendale kann in deinem
  Speicher nichts erstellen, ändern, teilen oder löschen.
- **Was Edendale liest:** die ID und E-Mail-Adresse deines Kontos, um das
  Konto zu benennen und seine Quellen auseinanderzuhalten; Namen, Größen,
  Datumsangaben und Laufzeiten der Dateien und Ordner an den Orten, die du
  durchsuchst und verknüpfst; und den Inhalt eines Videos nur, während du es
  abspielst.
- **Was Edendale behält:** Dateiinfos werden Teil deiner Mediathek auf dem
  Gerät. Anmelde-Tokens und Anmeldedaten kommen in den geschützten Speicher,
  wie oben beschrieben. Videodaten liegen während der Wiedergabe im
  Arbeitsspeicher und werden nie auf der Festplatte gespeichert.
- **Fernseher:** Ein Apple TV kann ein Konto oder Anmeldedaten von deinem
  iPhone oder iPad über eine verschlüsselte Verbindung in deinem lokalen
  Netzwerk erhalten – nur nachdem du die Übertragung auf dem Fernseher
  gestartet und auf dem Telefon oder Tablet bestätigt hast. Auf einem
  Fernseher kann sich OneDrive auch mit einem Code anmelden, den du auf einem
  anderen Gerät bestätigst.

## Google-Nutzerdaten

Wenn du Google Drive verknüpfst, greift Edendale auf Folgendes zu:

- die eindeutige ID und die E-Mail-Adresse deines Google-Kontos (`openid` und
  `email`), um anzuzeigen, welches Konto verknüpft ist, und deine Konten
  auseinanderzuhalten; und
- die Dateien und Ordner in deinem Google Drive (`drive.readonly`): Edendale
  listet die Ordner auf, die du durchsuchst und verknüpfst, liest Namen,
  Größen, Datumsangaben und Videolaufzeiten der Dateien darin und streamt die
  Videos, die du abspielen möchtest.

Edendale nutzt diese Daten ausschließlich, um seine Google-Drive-Quelle
bereitzustellen: einen Ordner auswählen, die Videos darin auflisten und sie
abspielen. Wie bei jeder Quelle liest Edendale die Dateinamen auf deinem
Gerät, um Filme und Folgen zu erkennen, und sendet nur den erkannten Titel
und das Jahr an TMDB, um Infos abzurufen.

Die Daten bleiben auf deinen Geräten: Dateiinfos in deiner Mediathek, das
verknüpfte Konto (seine ID, E-Mail-Adresse und sein Anmelde-Token) im
Schlüsselbund, den der iCloud-Schlüsselbund mit deinen anderen Apple-Geräten
synchronisiert. Auf ein Apple TV gelangt es nur, wenn du eine Übertragung von
deinem iPhone oder iPad bestätigst. Google-Nutzerdaten werden nie an uns oder
an einen von uns betriebenen Server gesendet – wir sehen oder lesen sie also
nie. Sie werden nie verkauft, nie für Werbung verwendet und nie genutzt, um
Modelle für künstliche Intelligenz oder maschinelles Lernen zu entwickeln, zu
verbessern oder zu trainieren.

Um Edendales Zugriff zu beenden, entferne die Quelle (das entfernt auch ihre
Dateien aus deiner Mediathek) und melde dich unter **Einstellungen →
Accounts** ab. **Abmelden und Zugriff widerrufen** widerruft Edendales Zugriff
zusätzlich bei Google. Du kannst den Zugriff jederzeit über die
[Drittanbieter-Verbindungen deines Google-Kontos](https://myaccount.google.com/connections)
entfernen. Wenn du die App löschst, wird alles gelöscht, was sie auf diesem
Gerät gespeichert hat.

Die Nutzung von Informationen, die Edendale über Google-APIs erhält, und ihre
Übertragung an andere Apps erfolgen gemäß der
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(Richtlinie zu Nutzerdaten für Google API-Dienste), einschließlich der
Anforderungen zur eingeschränkten Nutzung (Limited Use).

## Microsoft- und Dropbox-Konten

OneDrive und Dropbox funktionieren genauso: Lesezugriff, nur zum Auflisten und
Abspielen deiner Videos genutzt und nur auf deinen Geräten gespeichert. Melde
dich unter **Einstellungen → Accounts** ab. Bei Dropbox beendet **Abmelden und
Zugriff widerrufen** auch Edendales Zugriff bei Dropbox. Du kannst Edendale
außerdem aus den
[Apps mit Zugriff auf dein Microsoft-Konto](https://account.live.com/consent/Manage)
oder aus deinen
[verbundenen Dropbox-Apps](https://www.dropbox.com/account/connected_apps)
entfernen. Ein Microsoft-Geschäfts- oder Schulkonto wird unter Umständen von
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
Quelle entfernen, dich von einem Konto abmelden, die iCloud-Synchronisierung
oder die OneDrive-Replikation ausschalten oder die App löschen. Da wir keine
personenbezogenen Daten von dir haben, übst du deine Rechte auf Auskunft und
Löschung über diese Funktionen aus. Für Daten bei den oben genannten Diensten
gelten deren eigene Datenschutzerklärungen.

## Änderungen dieser Datenschutzerklärung

Wenn sich der Umgang der Apps mit Daten ändert, aktualisieren wir diese Seite
und das Datum oben. Jede Fassung ist öffentlich in der Projekthistorie auf
GitHub einsehbar.

## Kontakt

Fragen zu dieser Datenschutzerklärung oder zum Datenschutz bei Edendale
kannst du gern als Issue unter
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues)
stellen.
