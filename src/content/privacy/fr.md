---
updated: 2026-11-16
summary:
  - Edendale n’a ni système de compte ni serveurs à lui. Les apps ne contiennent ni analyse, ni publicité, ni pistage.
  - Votre bibliothèque, vos réglages, vos identifiants enregistrés et le contrôle parental restent sur votre appareil. Ce qui se synchronise passe par un stockage ou un service que vous contrôlez, comme iCloud ou votre propre Google Drive, OneDrive, Dropbox ou serveur.
  - La synchronisation entre appareils est désactivée tant que vous ne l’activez pas. Chaque appareil écrit alors votre historique de visionnage dans un fichier lisible, sur le stockage de votre choix, et les identifiants ne passent que directement d’un de vos appareils à un autre.
  - Edendale contacte Google Drive, OneDrive et Dropbox directement depuis votre appareil, uniquement pour lister et lire vos vidéos et tenir ses propres fichiers de synchronisation. Dans Google Drive, il ne voit que les vidéos que vous choisissez et les fichiers qu’il crée.
  - Les informations sur les films, la recherche de sous-titres, les boutons pour passer et les bandes-annonces contactent les services indiqués ci-dessous, uniquement dans le but décrit.
---

## Champ d’application

Cette politique couvre les apps Edendale pour les appareils Apple (iPhone,
iPad, Mac, Apple TV et Apple Vision Pro), Android (y compris les casques Meta
Quest) et Windows, ainsi que ce site web, edendale.babasama.com. Edendale est
un projet libre et open source, développé publiquement sur
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale). « Nous »
désigne les personnes qui le développent.

## Nous ne collectons pas vos données

Edendale n’a pas de système de compte et n’exploite aucun serveur qui
recevrait des informations des apps. Les apps ne contiennent aucun code
d’analyse, de publicité, de pistage ou de rapport de plantage. Vos
informations ne nous parviennent jamais : nous n’avons donc rien à vendre, à
louer ni à partager.

Si vous autorisez votre appareil à partager des diagnostics avec les
développeurs d’apps, la boutique où vous avez installé Edendale (Apple, Google,
Meta ou Microsoft) peut nous transmettre des rapports de plantage et des
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
  serveurs, clés d’accès S3, jetons de connexion aux services en ligne (y
  compris l’identifiant de votre emplacement de synchronisation), votre
  connexion TMDB et votre clé d’API de sous-titres, conservés dans le stockage
  protégé du système : le trousseau (Keychain) sur les
  appareils Apple, un stockage chiffré avec l’Android Keystore, et la
  protection des données de Windows (DPAPI). Les jetons d’accès de courte
  durée ne sont conservés qu’en mémoire.
- **Contrôle parental :** l’option « Adapté au jeune public » et, sous
  Android et sur Meta Quest, également les limites de classification par âge,
  les autres options du contrôle parental et le code PIN parental, qui n’est
  enregistré que sous forme de hachage lent et salé. Edendale ne les
  synchronise jamais, ne les envoie jamais à un autre appareil ni nulle part
  ailleurs. La sauvegarde propre à votre appareil peut garder l’option
  « Adapté au jeune public », mais jamais le code PIN ni les limites de
  classification.
- **Ce que votre appareil affiche :** les titres que vous regardez, avec leur
  visuel et leur progression, peuvent apparaître hors de l’app : dans les
  widgets d’Edendale, dans le Top Shelf de l’Apple TV, dans la liste de
  raccourcis de la barre des tâches de Windows, dans les commandes multimédias
  du système pendant la lecture et, si vous activez **Reprendre la lecture sur
  l’écran d’accueil**, dans la rangée « Reprendre la lecture » d’Android TV,
  que l’app d’écran d’accueil du téléviseur peut lire. Toute personne qui voit
  votre appareil peut les y voir.

Edendale lit les noms de fichiers sur votre appareil pour reconnaître les
films et les épisodes avant de contacter le moindre service en ligne, et il
n’envoie jamais vos vidéos nulle part.

## Ce qui peut se synchroniser, et où

Edendale ne synchronise que par des services que vous contrôlez, et seulement
si vous les avez activés :

- **Synchronisation entre appareils (Edendale 27.1 et versions
  ultérieures) :** votre historique de visionnage, via le stockage de votre
  choix, comme décrit dans la section suivante.
- **Appareils Apple :** avec iCloud, votre progression, vos notes, vos
  critiques, vos favoris et votre liste de suivi se synchronisent via votre
  base de données iCloud privée. Les comptes associés, les identifiants
  enregistrés, votre connexion TMDB et votre clé d’API de sous-titres se
  synchronisent via le trousseau iCloud avec votre iPhone, votre iPad, votre
  Mac et votre Apple Vision Pro. L’Apple TV conserve ses propres copies. Les
  sauvegardes propres à votre appareil incluent les données d’Edendale, comme
  celles de toute app.
- **Windows :** avant Edendale 27.1, Edendale copiait votre progression et
  l’état de vos titres via le dossier `Apps/Edendale` de votre propre
  OneDrive dès que OneDrive était configuré sur le PC. Les identifiants et
  les comptes ne quittaient jamais l’appareil. Edendale 27.1 arrête cette
  copie : il lit ces fichiers une fois, les laisse en place et propose ce
  dossier comme emplacement de synchronisation. La synchronisation reste
  désactivée tant que vous n’avez pas accepté.
- **Android :** la sauvegarde d’Android peut inclure votre bibliothèque (avec
  l’adresse e-mail ou le nom du compte auquel appartient chaque source
  associée), vos données de visionnage, vos réglages et l’option « Adapté au
  jeune public ».
  Les identifiants, les clés, les jetons de compte, les réglages de
  synchronisation, le code PIN parental et le reste du contrôle parental sont
  exclus des sauvegardes et des transferts entre appareils.
- **Meta Quest :** si la sauvegarde dans le cloud est activée dans les
  paramètres du casque, la sauvegarde de Meta s’appuie sur celle d’Android,
  avec les mêmes exclusions. Retirer l’app du casque efface tout ce qu’elle y
  a enregistré. Meta conserve ses sauvegardes, et restaure la plus récente si
  vous réinstallez Edendale, jusqu’à ce que vous les supprimiez depuis la
  sauvegarde dans le cloud des paramètres du casque ; voir
  [l’aide de Meta sur la sauvegarde dans le cloud](https://www.meta.com/help/quest/399621398228171/).
- **Votre compte TMDB (facultatif) :** si vous vous connectez à The Movie
  Database, Edendale synchronise vos favoris, votre liste de suivi et vos
  notes avec ce compte. La progression de visionnage n’est jamais envoyée à
  TMDB.

## Synchronisation entre appareils

À partir d’Edendale 27.1, **Réglages → Synchronisation** peut garder votre
historique de visionnage identique sur tous vos appareils. Elle est
désactivée tant que vous ne l’activez pas et ne choisissez pas d’emplacement.
Edendale n’a toujours ni serveur ni système de compte : vos appareils
partagent l’historique uniquement via un stockage qui vous appartient déjà.

- **Contenu d’un fichier de synchronisation :** chaque appareil écrit un
  fichier, `Edendale/<nom de l’espace>/<identifiant de l’appareil>.json`, avec
  votre progression de visionnage et l’état « vu », vos notes, critiques,
  favoris et l’état de votre liste de suivi, chacun repéré par l’identifiant
  TMDB du titre et accompagné de l’heure de sa dernière modification et de
  l’appareil qui l’a faite ; le nom de l’espace ; le nom et la plateforme de
  l’appareil ; un identifiant
  aléatoire créé pour la synchronisation ; et la version d’Edendale. Il ne
  contient jamais votre bibliothèque, de noms ni de chemins de fichiers,
  d’identifiants de connexion, votre session TMDB ni le contrôle parental.
- **Où il se trouve :** à l’emplacement que vous choisissez et qui vous
  appartient : un dossier de votre appareil (y compris iCloud Drive ou un
  dossier fourni par une autre app de stockage en ligne), le dossier iCloud
  Drive propre à Edendale sur les appareils Apple, votre Google Drive,
  OneDrive (dans son dossier d’application, `Apps/Edendale`), Dropbox, ou
  votre propre serveur WebDAV, SMB ou SFTP. Chaque appareil y lit les
  fichiers des autres appareils et les fusionne sur l’appareil. Les fichiers
  sont du JSON simple et lisible : toute personne qui peut ouvrir ce dossier
  ou ce compte peut lire votre historique de visionnage. Edendale vous le
  signale lorsque vous choisissez un emplacement.
- **Pour arrêter :** **Désactiver** dans Réglages → Synchronisation arrête la
  synchronisation sur cet appareil et propose de supprimer son fichier ;
  l’historique reste sur l’appareil. Retirer un appareil dans Réglages →
  Synchronisation supprime le fichier de cet appareil. Effacer la progression
  d’un titre, ou **Supprimer l’historique** sous Android, l’efface sur
  l’appareil et, si la synchronisation est activée, sur vos autres appareils
  lors de leur prochaine synchronisation. Les fichiers de synchronisation
  gardent ensuite, pendant un an au plus, un marqueur pour chaque titre
  effacé, sans sa progression, afin qu’une copie plus ancienne ne puisse pas
  le faire revenir. Pour tout supprimer, désactivez la synchronisation sur
  chaque appareil et supprimez le dossier `Edendale` de votre stockage.
- **Configurer un autre appareil :** **Configurer un autre appareil** envoie
  l’emplacement de synchronisation et son identifiant, ainsi que les autres
  comptes et identifiants que vous cochez, directement d’un de vos appareils à
  un autre via votre réseau local. Un appareil affiche un code QR, que l’autre
  scanne ou ouvre comme un lien, ou, si les deux appareils le proposent, un
  code que vous saisissez sur l’autre. Les deux appareils s’en servent pour
  convenir d’une clé qui chiffre la connexion, et l’appareil qui envoie vous
  demande de confirmer le nom de l’autre avant d’envoyer quoi que ce soit.
  Rien ne passe par nous ni par qui que ce soit d’autre. Sur iPhone, iPad, les
  téléphones et tablettes Android, et les Meta Quest 3 et 3S, Edendale peut
  scanner le code avec la caméra : il ne demande l’accès à la caméra que
  lorsque vous choisissez de scanner, lit le code sur l’appareil, et
  n’enregistre ni n’envoie jamais les images de la caméra. Tant que l’écran
  d’association est ouvert, l’appareil annonce son nom et sa plateforme sur
  votre réseau local pour que l’autre puisse le trouver. Entre des plateformes
  vers lesquelles une connexion Google ne peut pas être transférée, seuls
  l’adresse e-mail du compte Google, le nom de l’espace et l’identifiant du
  dossier de synchronisation sont envoyés, et vous vous connectez vous-même à
  Google sur le nouvel appareil.
- **Un appareil que vous retirez** garde l’identifiant qu’il a reçu, et
  l’historique qu’il possède déjà, jusqu’à ce que vous révoquiez l’accès
  d’Edendale chez votre fournisseur de stockage ou changiez le mot de passe du
  serveur.

## Services en ligne utilisés par Edendale

Chacun des services ci-dessous reçoit l’adresse IP de votre appareil, comme
toute connexion à Internet, ainsi que ce qui suit :

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  pour les informations, les visuels et les classifications par âge des films
  et séries : le titre qu’Edendale lit dans un nom de fichier, parfois avec
  l’année (jamais le nom complet, son dossier ni le fichier lui-même), ce que
  vous tapez dans la recherche, et les identifiants TMDB des titres que vous
  consultez ou dont Edendale cherche les informations. Si vous vous connectez,
  également votre session TMDB.
- **[Wyzie Subs](https://wyzie.io/privacy)**, uniquement lorsque vous cherchez
  des sous-titres en ligne : l’identifiant TMDB du titre, les numéros de
  saison et d’épisode, les langues et formats de sous-titres qu’Edendale
  demande, s’il faut inclure les sous-titres pour sourds et malentendants, et
  une clé d’API : la vôtre si vous en avez saisi une, sinon celle d’Edendale.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, uniquement tant que
  les Boutons pour passer sont activés (ils sont désactivés par défaut) :
  l’identifiant TMDB du titre, les numéros de saison et d’épisode, et la durée
  de la vidéo.
- **[YouTube](https://policies.google.com/privacy)**, uniquement lorsque vous
  choisissez de lire une bande-annonce. Sur les appareils Apple autres que
  l’Apple TV, et sur les appareils Android autres que les Meta Quest, Edendale
  la lit dans le mode de confidentialité renforcée de YouTube
  (youtube-nocookie.com). Sur l’Apple TV, il ouvre la bande-annonce dans l’app
  YouTube ; sous Windows, sur youtube.com dans votre navigateur ; et sur Meta
  Quest, dans le navigateur ou l’app YouTube.
- **Les stockages que vous associez**, et celui que vous choisissez pour la
  synchronisation, décrits dans la section suivante.

## Les stockages que vous associez

Edendale lit des vidéos depuis des dossiers de votre appareil et depuis les
stockages que vous associez : serveurs SMB, NFS, SFTP et WebDAV, stockage
compatible S3, Google Drive, OneDrive et Dropbox. Les services disponibles
varient selon la plateforme. Chaque connexion va directement de votre appareil
au service choisi. Rien ne passe par un serveur que nous exploitons.

- **Connexion :** pour Google Drive, OneDrive et Dropbox, vous vous connectez
  sur la page du fournisseur via OAuth 2.0 avec PKCE : Edendale ne voit
  jamais votre mot de passe. Les identifiants de serveurs (noms d’utilisateur,
  mots de passe et clés d’accès) ne sont envoyés qu’au serveur auquel ils
  appartiennent.
- **Accès limité :** Google n’obtient que `drive.file`, qui couvre les vidéos
  que vous choisissez dans le sélecteur de fichiers de Google et les fichiers
  qu’Edendale crée, et rien d’autre dans votre Drive. Microsoft :
  `Files.Read`, `Files.ReadWrite.AppFolder`, `User.Read` et `offline_access`.
  Dropbox : `account_info.read`, `files.metadata.read`, `files.content.read`
  et `files.content.write`. Edendale se contente de lire vos vidéos : il ne
  les envoie, ne les modifie, ne les partage ni ne les supprime jamais, pas
  plus que quoi que ce soit d’autre dans vos stockages. Les seuls fichiers
  qu’il écrit ou supprime sont ses propres fichiers de synchronisation, et
  uniquement si vous activez la synchronisation : dans son dossier `Edendale`
  sur Google Drive, sur Dropbox ou sur le serveur ou dans le dossier que vous
  avez choisi, et dans le dossier d’application de OneDrive.
- **Ce qu’Edendale lit :** l’identifiant, l’adresse e-mail et le nom de votre
  compte, pour le nommer et séparer ses sources ; les noms, tailles, dates et
  durées des fichiers et dossiers aux emplacements que vous parcourez et
  associez ou, pour Google Drive, des vidéos que vous choisissez ; le contenu
  d’une vidéo uniquement pendant que vous la regardez ; et, si vous activez la
  synchronisation, les fichiers de synchronisation de vos appareils.
- **Ce qu’Edendale conserve :** les informations sur les fichiers rejoignent
  votre bibliothèque sur l’appareil. Les jetons de connexion et les
  identifiants vont dans le stockage protégé, comme décrit plus haut. Les
  données vidéo restent en mémoire pendant la lecture et ne sont jamais
  enregistrées sur le disque.
- **Téléviseurs :** une Apple TV peut recevoir un compte ou un identifiant
  depuis votre iPhone ou votre iPad, et un téléviseur Android TV depuis
  Edendale sur un autre appareil Android, via une connexion chiffrée sur
  votre réseau local, uniquement après que vous avez lancé le transfert sur
  le téléviseur et l’avez confirmé sur l’autre appareil. Pendant qu’un
  téléviseur Android TV attend, il annonce son nom sur votre réseau local. Sur
  un téléviseur,
  OneDrive peut aussi se connecter avec un code que vous approuvez sur un
  autre appareil. À partir d’Edendale 27.1, n’importe lequel de vos appareils
  peut en configurer un autre de cette façon, comme décrit dans
  « Synchronisation entre appareils ».

## Données utilisateur Google

Lorsque vous associez Google Drive, vous choisissez des vidéos dans le
sélecteur de fichiers de Google, qui s’ouvre à la connexion et chaque fois que
vous utilisez **Choisir d’autres vidéos**. Si vous vous connectez à Google
Drive uniquement pour y tenir les fichiers de synchronisation, le sélecteur ne
s’ouvre pas. Edendale accède :

- aux vidéos que vous choisissez, et à rien d’autre dans votre Google Drive
  (`drive.file`) : Edendale les liste, lit leurs noms, tailles, dates et
  durées vidéo, et diffuse celles que vous choisissez de regarder. Google
  partage vos choix entre les apps Edendale : une vidéo choisie sur un
  appareil peut donc aussi apparaître sur un autre appareil connecté au même
  compte Google ;
- si vous choisissez Google Drive pour la synchronisation, au dossier
  `Edendale` et aux fichiers de synchronisation qu’Edendale lui-même y crée,
  qu’il lit, remplace et supprime comme décrit dans « Synchronisation entre
  appareils » ; et
- à l’identifiant d’utilisateur Google Drive, à l’adresse e-mail et au nom de
  votre compte, que Google Drive fournit avec cette même autorisation, pour
  afficher le compte associé et distinguer vos comptes.

Edendale utilise ces données uniquement pour fournir sa source Google Drive
(lister les vidéos que vous avez choisies et les lire) et, si vous le
choisissez, pour tenir vos fichiers de synchronisation dans votre Drive. Il
n’envoie, ne modifie, ne partage ni ne supprime jamais vos vidéos ni aucun
autre fichier de votre Drive. Comme pour toute source, Edendale lit les noms
de fichiers sur votre appareil pour reconnaître les films et les épisodes, et
n’envoie à TMDB que le titre reconnu, parfois avec l’année, afin d’obtenir
les informations.

Les données restent sur vos appareils et dans votre propre Drive : les
informations sur les fichiers dans votre bibliothèque, et le compte associé
(son identifiant, son adresse e-mail, son nom et son jeton de connexion) dans
le stockage protégé, comme décrit plus haut. Sur les appareils Apple, le
trousseau iCloud synchronise le compte avec vos autres appareils Apple. Il
n’arrive sur un autre appareil que si vous confirmez un transfert depuis l’un
des vôtres ; entre des plateformes vers lesquelles une connexion Google ne
peut pas être transférée, seuls l’adresse e-mail et l’identifiant du dossier
de synchronisation sont envoyés. Si vous retirez une vidéo de votre
bibliothèque, Edendale peut conserver son identifiant de fichier sur cet
appareil uniquement, pour qu’elle ne soit plus listée. Les données utilisateur
Google ne nous sont jamais envoyées, ni à aucun serveur que nous exploitons :
nous ne les voyons ni ne les lisons jamais. Elles ne sont jamais vendues,
jamais utilisées à des fins publicitaires et jamais utilisées pour développer,
améliorer ou entraîner des modèles d’intelligence artificielle ou
d’apprentissage automatique.

Pour mettre fin à l’accès d’Edendale, retirez la source (ce qui retire aussi
ses fichiers de votre bibliothèque) et déconnectez-vous dans **Réglages →
Comptes**. **Se déconnecter et révoquer l’accès**, ou **Se déconnecter** sous
Windows, révoque aussi l’accès d’Edendale chez Google. Vous pouvez le retirer
à tout moment depuis les
[connexions tierces de votre compte Google](https://myaccount.google.com/connections).
Révoquer l’accès y met fin sur tous les appareils, téléviseurs compris, et
Google oublie les vidéos que vous avez choisies. Les fichiers de
synchronisation créés par Edendale restent dans votre Drive jusqu’à ce que
vous les supprimiez. Supprimer l’app efface tout ce qu’elle a enregistré sur
cet appareil. Une sauvegarde sous Android ou sur Meta Quest peut garder une
copie de votre bibliothèque, y compris l’adresse e-mail ou le nom du compte
d’où viennent ses vidéos, mais jamais le jeton de connexion du compte.

L’utilisation et le transfert vers toute autre app, par Edendale, des
informations reçues des API Google respecteront la
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(règles relatives aux données utilisateur des services d’API Google), y
compris les exigences d’utilisation limitée (Limited Use).

## Comptes Microsoft et Dropbox

OneDrive et Dropbox fonctionnent de façon très similaire : accès en lecture
aux dossiers que vous parcourez et associez, utilisé uniquement pour lister
et lire vos vidéos, et, si vous choisissez l’un d’eux pour la
synchronisation, accès en écriture utilisé uniquement pour les propres
fichiers de synchronisation d’Edendale, dans le dossier d’application de
OneDrive (`Apps/Edendale`) ou dans le dossier `Edendale` de votre Dropbox.
Edendale n’y modifie ni n’y supprime jamais rien d’autre. Les informations du
compte et les jetons de connexion ne sont conservés que sur vos appareils.
Déconnectez-vous dans **Réglages → Comptes**. Sur les appareils Apple et sous
Android, **Se déconnecter et révoquer l’accès** met aussi fin à l’accès
d’Edendale chez Dropbox. Vous
pouvez également retirer Edendale des
[apps ayant accès à votre compte Microsoft](https://account.live.com/consent/Manage)
ou de vos
[apps connectées Dropbox](https://www.dropbox.com/account/connected_apps).
Les fichiers de synchronisation restent dans votre stockage jusqu’à ce que
vous les supprimiez. Un compte Microsoft professionnel ou scolaire peut être
géré par votre organisation.

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
synchronisation entre appareils, la synchronisation iCloud ou la sauvegarde de
votre appareil, supprimer les fichiers de synchronisation (et, sous Windows,
les anciennes copies OneDrive) de votre stockage, ou supprimer l’app. Comme
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
