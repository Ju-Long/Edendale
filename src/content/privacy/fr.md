---
updated: 2026-10-06
summary:
  - Edendale n’a ni système de compte ni serveurs à lui. Les apps ne contiennent ni analyse, ni publicité, ni pistage.
  - Votre bibliothèque, vos réglages et vos identifiants enregistrés restent sur votre appareil. Ce qui se synchronise passe par un service que vous contrôlez, comme iCloud ou votre propre OneDrive.
  - Google Drive, OneDrive et Dropbox obtiennent un accès en lecture seule, sont contactés directement depuis votre appareil et servent uniquement à lister et lire vos vidéos.
  - Les informations sur les films, la recherche de sous-titres, les boutons pour passer et les bandes-annonces contactent les services indiqués ci-dessous, uniquement dans le but décrit.
---

## Champ d’application

Cette politique couvre les apps Edendale pour les appareils Apple (iPhone,
iPad, Mac, Apple TV et Apple Vision Pro), Android et Windows, ainsi que ce
site web, edendale.babasama.com. Edendale est un projet libre et open source,
développé publiquement sur
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale).
« Nous » désigne les personnes qui le développent.

## Nous ne collectons pas vos données

Edendale n’a pas de système de compte et n’exploite aucun serveur qui
recevrait des informations des apps. Les apps ne contiennent aucun code
d’analyse, de publicité, de pistage ou de rapport de plantage. Vos
informations ne nous parviennent jamais : nous n’avons donc rien à vendre, à
louer ni à partager.

Si vous autorisez votre appareil à partager des diagnostics avec les
développeurs d’apps, la boutique où vous avez installé Edendale (Apple, Google
ou Microsoft) peut nous transmettre des rapports de plantage et des
statistiques d’utilisation agrégées, selon sa propre politique de
confidentialité. Nous ne les utilisons que pour corriger des problèmes.

## Ce qui reste sur votre appareil

- **Votre bibliothèque :** les dossiers et sources que vous ajoutez ; les
  noms, tailles, dates et durées des fichiers qu’Edendale y trouve ; et le
  film ou l’épisode auquel chaque fichier a été associé.
- **Vos réglages :** préférences de lecture, d’audio, d’image, de sous-titres
  et de commandes, y compris les choix mémorisés pour chaque titre.
- **Les sous-titres que vous téléchargez.**
- **Identifiants enregistrés et comptes associés :** mots de passe de
  serveurs, clés d’accès S3 et jetons de connexion aux services en ligne,
  conservés dans le stockage protégé du système : le trousseau (Keychain) sur
  les appareils Apple, un stockage chiffré avec l’Android Keystore, et la
  protection des données de Windows (DPAPI). Les jetons d’accès de courte
  durée ne sont conservés qu’en mémoire.

Edendale lit les noms de fichiers sur votre appareil pour reconnaître les
films et les épisodes avant de contacter le moindre service en ligne, et il
n’envoie jamais vos vidéos nulle part.

## Ce qui peut se synchroniser, et où

Edendale ne synchronise que par des services que vous contrôlez, et seulement
si vous les avez activés :

- **Appareils Apple :** avec iCloud, votre progression, vos notes, vos favoris
  et votre liste de suivi se synchronisent via votre base de données iCloud
  privée. Les comptes associés et les identifiants enregistrés se
  synchronisent via le trousseau iCloud avec votre iPhone, votre iPad, votre
  Mac et votre Apple Vision Pro. L’Apple TV conserve ses propres copies.
- **Windows :** si vous activez la réplication OneDrive, votre progression et
  l’état de vos titres sont copiés via un dossier de votre propre OneDrive.
  Les identifiants et les comptes ne quittent jamais l’appareil.
- **Android :** la sauvegarde d’Android peut inclure votre bibliothèque et vos
  données de visionnage. Les identifiants, les clés et les jetons de compte
  sont exclus des sauvegardes et des transferts entre appareils.
- **Votre compte TMDB (facultatif) :** si vous vous connectez à The Movie
  Database, Edendale synchronise vos favoris, votre liste de suivi et vos
  notes avec ce compte. La progression de visionnage n’est jamais envoyée à
  TMDB.

## Services en ligne utilisés par Edendale

Chacun des services ci-dessous reçoit l’adresse IP de votre appareil, comme
toute connexion à Internet, ainsi que ce qui suit :

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  pour les informations et les visuels des films et séries : le titre et
  l’année qu’Edendale lit dans un nom de fichier (jamais le nom complet, son
  dossier ni le fichier lui-même) et les identifiants TMDB des titres que vous
  consultez. Si vous vous connectez, également votre session TMDB.
- **[Wyzie Subs](https://wyzie.io/privacy)**, uniquement lorsque vous cherchez
  des sous-titres en ligne : l’identifiant TMDB du titre, les numéros de
  saison et d’épisode, les langues demandées et votre clé d’API.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, uniquement tant que
  les Boutons pour passer sont activés (ils sont désactivés par défaut) :
  l’identifiant TMDB du titre, les numéros de saison et d’épisode, et la durée
  de la vidéo.
- **[YouTube](https://policies.google.com/privacy)**, uniquement lorsque vous
  choisissez de lire une bande-annonce. Sur les appareils Apple et Android,
  Edendale la lit dans le mode de confidentialité renforcée de YouTube
  (youtube-nocookie.com). Sous Windows, il ouvre la bande-annonce sur
  youtube.com dans votre navigateur.
- **Les stockages que vous associez**, décrits dans la section suivante.

## Les stockages que vous associez

Edendale lit des vidéos depuis des dossiers de votre appareil et depuis les
stockages que vous associez : serveurs SMB, NFS, SFTP et WebDAV, stockage
compatible S3, Google Drive, OneDrive et Dropbox. Les services disponibles
varient selon la plateforme ; Google Drive est pour l’instant disponible sur
les appareils Apple. Chaque connexion va directement de votre appareil au
service choisi. Rien ne passe par un serveur que nous exploitons.

- **Connexion :** pour Google Drive, OneDrive et Dropbox, vous vous connectez
  sur la page du fournisseur via OAuth 2.0 avec PKCE : Edendale ne voit
  jamais votre mot de passe. Les identifiants de serveurs (noms d’utilisateur,
  mots de passe et clés d’accès) ne sont envoyés qu’au serveur auquel ils
  appartiennent.
- **Accès en lecture seule :** Edendale demande des autorisations en lecture
  seule. Google : `openid`, `email` et `drive.readonly`. Microsoft :
  `Files.Read`, `User.Read` et `offline_access`. Dropbox :
  `account_info.read`, `files.metadata.read` et `files.content.read`.
  Edendale ne peut rien créer, modifier, partager ni supprimer dans vos
  stockages.
- **Ce qu’Edendale lit :** l’identifiant et l’adresse e-mail de votre compte,
  pour le nommer et séparer ses sources ; les noms, tailles, dates et durées
  des fichiers et dossiers aux emplacements que vous parcourez et associez ;
  et le contenu d’une vidéo uniquement pendant que vous la regardez.
- **Ce qu’Edendale conserve :** les informations sur les fichiers rejoignent
  votre bibliothèque sur l’appareil. Les jetons de connexion et les
  identifiants vont dans le stockage protégé, comme décrit plus haut. Les
  données vidéo restent en mémoire pendant la lecture et ne sont jamais
  enregistrées sur le disque.
- **Téléviseurs :** une Apple TV peut recevoir un compte ou un identifiant
  depuis votre iPhone ou votre iPad via une connexion chiffrée sur votre
  réseau local, uniquement après que vous avez lancé le transfert sur le
  téléviseur et l’avez confirmé sur le téléphone ou la tablette. Sur un
  téléviseur, OneDrive peut aussi se connecter avec un code que vous
  approuvez sur un autre appareil.

## Données utilisateur Google

Lorsque vous associez Google Drive, Edendale accède :

- à l’identifiant unique et à l’adresse e-mail de votre compte Google
  (`openid` et `email`), pour afficher le compte associé et distinguer vos
  comptes ; et
- aux fichiers et dossiers de votre Google Drive (`drive.readonly`) : Edendale
  liste les dossiers que vous parcourez et associez, lit les noms, tailles,
  dates et durées vidéo des fichiers qu’ils contiennent, et diffuse les vidéos
  que vous choisissez de regarder.

Edendale utilise ces données uniquement pour fournir sa source Google Drive :
choisir un dossier, en lister les vidéos et les lire. Comme pour toute source,
Edendale lit les noms de fichiers sur votre appareil pour reconnaître les
films et les épisodes, et n’envoie à TMDB que le titre et l’année reconnus
afin d’obtenir les informations.

Les données restent sur vos appareils : les informations sur les fichiers dans
votre bibliothèque, et le compte associé (son identifiant, son adresse e-mail
et son jeton de connexion) dans le trousseau, que le trousseau iCloud
synchronise avec vos autres appareils Apple. Il n’arrive sur une Apple TV que
si vous confirmez un transfert depuis votre iPhone ou votre iPad. Les données
utilisateur Google ne nous sont jamais envoyées, ni à aucun serveur que nous
exploitons : nous ne les voyons ni ne les lisons jamais. Elles ne sont jamais
vendues, jamais utilisées à des fins publicitaires et jamais utilisées pour
développer, améliorer ou entraîner des modèles d’intelligence artificielle ou
d’apprentissage automatique.

Pour mettre fin à l’accès d’Edendale, retirez la source (ce qui retire aussi
ses fichiers de votre bibliothèque) et déconnectez-vous dans **Réglages →
Comptes**. **Se déconnecter et révoquer l’accès** révoque aussi l’accès
d’Edendale chez Google. Vous pouvez le retirer à tout moment depuis les
[connexions tierces de votre compte Google](https://myaccount.google.com/connections).
Supprimer l’app efface tout ce qu’elle a enregistré sur cet appareil.

L’utilisation et le transfert vers toute autre app, par Edendale, des
informations reçues des API Google respecteront la
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(règles relatives aux données utilisateur des services d’API Google), y
compris les exigences d’utilisation limitée (Limited Use).

## Comptes Microsoft et Dropbox

OneDrive et Dropbox fonctionnent de la même façon : accès en lecture seule,
utilisé uniquement pour lister et lire vos vidéos, et conservé uniquement sur
vos appareils. Déconnectez-vous dans **Réglages → Comptes**. Pour Dropbox,
**Se déconnecter et révoquer l’accès** met aussi fin à l’accès d’Edendale chez
Dropbox. Vous pouvez également retirer Edendale des
[apps ayant accès à votre compte Microsoft](https://account.live.com/consent/Manage)
ou de vos
[apps connectées Dropbox](https://www.dropbox.com/account/connected_apps).
Un compte Microsoft professionnel ou scolaire peut être géré par votre
organisation.

## Ce site web

Ce site web est un site statique hébergé sur GitHub Pages. Il ne dépose aucun
cookie, n’enregistre rien dans votre navigateur, ne comporte aucun formulaire
et ne charge ni outils d’analyse, ni polices, ni scripts depuis d’autres
sites. Il choisit une langue d’après les réglages de votre navigateur sans
rien enregistrer, et la langue que vous choisissez n’est conservée que dans
l’adresse de la page. GitHub, en tant qu’hébergeur, reçoit les informations
habituelles de chaque requête, comme votre adresse IP ; voir la
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Les liens qui ouvrent l’app Edendale sont traités sur votre appareil.

## Enfants

Edendale ne collecte sciemment aucune information personnelle, y compris
auprès d’enfants. Les apps ne nous envoient rien : il n’y a donc rien que nous
puissions collecter.

## Vos choix

Vous pouvez consulter, modifier ou supprimer vos données dans l’app à tout
moment : retirer une source, vous déconnecter d’un compte, désactiver la
synchronisation iCloud ou la réplication OneDrive, ou supprimer l’app. Comme
nous ne détenons aucune de vos données personnelles, ces commandes sont le
moyen d’exercer vos droits d’accès et d’effacement. Les données conservées par
les services ci-dessus relèvent de leurs propres politiques de
confidentialité.

## Modifications de cette politique

Lorsque la façon dont les apps traitent les données change, nous mettons à
jour cette page et la date indiquée en haut. Chaque révision est publique
dans l’historique du projet sur GitHub.

## Contact

Vos questions sur cette politique ou sur la confidentialité d’Edendale sont
les bienvenues sous forme de ticket sur
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
