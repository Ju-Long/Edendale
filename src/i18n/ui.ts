import type { LocalePath } from "./locales.ts";

/**
 * English is the source text. Every other dictionary is typed as a complete
 * `Dictionary`, so `npm run check` fails on a missing or misspelled key rather
 * than shipping an untranslated string.
 *
 * A `\n` inside a heading is an authored line break: `SiteLayout`'s `lines()`
 * helper turns it into `<br>`, which lets each language break its own headline
 * where the words allow.
 *
 * `{date}` in `privacy.updated` is replaced with the policy's effective date,
 * formatted for the language. The policy text itself lives in
 * `src/content/privacy/`, one Markdown file per language.
 */
const en = {
  "meta.home.title": "Edendale — Your library, your history",
  "meta.home.description":
    "A free, open-source video player and personal watch tracker, built natively for Apple platforms, Android, and Windows.",
  "meta.link.title": "Open in Edendale",
  "meta.link.description": "Continue this link in the Edendale app.",
  "meta.notFound.description":
    "Continue this link in the Edendale app, or return to the Edendale project site.",
  "meta.privacy.title": "Privacy policy — Edendale",
  "meta.privacy.description":
    "How Edendale handles your data: no account, no Edendale servers, no analytics, and read-only access to the storage you link.",
  "meta.socialAlt": "Edendale — your private cinematic archive.",

  "chrome.skipToContent": "Skip to content",
  "chrome.homeAria": "Edendale home",
  "chrome.brandTagline": "Personal cinema",
  "chrome.navAria": "Primary navigation",
  "chrome.navFeatures": "Features",
  "chrome.navPlatforms": "Platforms",
  "chrome.navPrivacy": "Privacy",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Your stories stay yours.",
  "chrome.footerNote": "Free and open source · No analytics · Built with care",
  "chrome.footerPrivacy": "Privacy policy",
  "chrome.footerSource": "Source on GitHub",

  "language.label": "Language",
  "language.aria": "Choose a language",

  "hero.eyebrow": "Private by design",
  "hero.title": "Your library.\nYour history.\nYours.",
  "hero.lede":
    "Edendale turns the movies and shows you already own into a beautiful personal archive—without turning your viewing habits into somebody else’s data.",
  "hero.ctaSource": "View on GitHub",
  "hero.ctaExplore": "Explore the apps",
  "hero.trustAria": "Edendale principles",
  "hero.trustLocal": "Local-first library",
  "hero.trustAnalytics": "No analytics",
  "hero.trustOpenSource": "Open source",
  "hero.visualAria": "A stylized view of the Edendale personal archive",
  "hero.windowTitle": "The Archive",
  "hero.windowStatus": "Local",
  "hero.windowResume": "Continue watching",
  "hero.windowMoment": "Sunday evening",
  "hero.windowNowPlaying": "Now playing from your library",
  "hero.windowPickUp": "Pick up exactly where you left off.",
  "hero.privacyTitle": "Nothing leaves your library",
  "hero.privacyBody": "Your files stay where you keep them.",

  "proof.aria": "Supported experiences",
  "proof.archiveTitle": "One archive",
  "proof.archiveBody": "Movies, shows, and progress",
  "proof.playbackTitle": "Native playback",
  "proof.playbackBody": "Built for every platform",
  "proof.cloudTitle": "Your cloud",
  "proof.cloudBody": "Private sync where available",
  "proof.telemetryTitle": "Zero telemetry",
  "proof.telemetryBody": "No profiles, ads, or analytics",

  "features.eyebrow": "A better personal archive",
  "features.title": "Made for the collection\nyou already have.",
  "features.lede":
    "Edendale does the useful work—organizing, enriching, and remembering—while keeping you in control.",
  "features.libraryTitle": "Build a library from your files",
  "features.libraryBody":
    "Choose your folders and Edendale sorts films and episodes locally, then adds the useful details in the background.",
  "features.progressTitle": "Remember every story",
  "features.progressBody":
    "Continue where you stopped and keep a personal watch history across your own devices.",
  "features.privacyTitle": "Private at the foundation",
  "features.privacyBody":
    "No accounts to sell, no viewing profile, and no analytics watching what you watch.",

  "sources.eyebrow": "Your sources",
  "sources.title": "Play it from\nwherever it lives.",
  "sources.lede":
    "Link a folder on this device, a server at home, or a cloud drive. Edendale lists it, sorts the files on your device, and streams only what you play, straight from the source to your screen.",
  "sources.deviceTitle": "On this device",
  "sources.deviceBody":
    "The folders and drives you already use, read where they are, never copied.",
  "sources.deviceFolders": "Folders",
  "sources.deviceDrives": "External drives",
  "sources.networkTitle": "On your network",
  "sources.networkBody":
    "Servers and NAS shares at home, with their logins kept in your device’s protected storage.",
  "sources.cloudTitle": "In the cloud",
  "sources.cloudBody":
    "Cloud drives sign you in on the provider’s own page, so Edendale never sees your password.",
  "sources.accessTitle": "Read-only, and only to play",
  "sources.accessBody":
    "Edendale asks Google Drive, OneDrive, and Dropbox for read-only access: to show which account is linked, list the folders you choose, and stream the videos you play. It can’t change or delete anything there, and nothing passes through an Edendale server.",
  "sources.accessLink": "How Edendale handles your data",
  "sources.availability": "Available services differ by platform.",

  "player.eyebrow": "The player",
  "player.title": "Every detail,\ntuned by you.",
  "player.lede":
    "The Edendale player gives you the controls a home cinema deserves, and every online extra waits until you ask for it.",
  "player.skipTitle": "Skip prompts",
  "player.skipBody":
    "Skip Intro, Recap, and Credits buttons from TheIntroDB’s community timestamps. Off until you turn it on, and it never skips on its own.",
  "player.nextTitle": "Up Next",
  "player.nextBody":
    "Episodes roll on to the next one you have, and Continue Watching remembers what comes after the last one you finished.",
  "player.soundTitle": "Sound",
  "player.soundBody":
    "Equalizer profiles for movies, music, dialogue, and late nights, plus an Audio Booster for quiet mixes.",
  "player.pictureTitle": "Picture",
  "player.pictureBody":
    "Fine-tune brightness, contrast, and color, with GPU upscaling and sharpening on supported hardware.",
  "player.subtitlesTitle": "Subtitles",
  "player.subtitlesBody":
    "Choose the font, colors, and background, or search online when a file doesn’t have your language.",
  "player.controlsTitle": "Controls",
  "player.controlsBody":
    "Set how far skips jump and how fast a press-and-hold plays, with touch, keyboard, mouse, or remote.",

  "platforms.eyebrow": "Native where it matters",
  "platforms.title": "At home on\nevery screen.",
  "platforms.lede":
    "Each Edendale app is built in the language and interface toolkit of its platform. Familiar controls, thoughtful performance, no shared web shell.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "Phones · tablets · large screens",
  "platforms.windowsDevices": "A focused desktop archive",

  "closing.eyebrow": "The lights are coming up",
  "closing.title": "Make your collection\nfeel like yours again.",
  "closing.body":
    "Edendale is free, open source, and in active development. Follow the project and help shape what comes next.",
  "closing.cta": "Follow development",

  "privacy.eyebrow": "Privacy",
  "privacy.title": "Privacy policy",
  "privacy.lede":
    "Edendale is built so that your library, your viewing history, and your files stay under your control. This page explains exactly what the apps and this website handle, and what they never touch.",
  "privacy.updated": "Effective {date}",
  "privacy.summaryTitle": "The short version",
  "privacy.tocTitle": "On this page",
  "privacy.translationNote":
    "This translation is provided for convenience. If it differs from the English version, the English version applies.",
  "privacy.readEnglish": "Read the English version",

  "link.eyebrow": "App link",
  "link.heading": "Continue in Edendale",
  "link.headingNotFound": "This link opens in Edendale",
  "link.body":
    "This link belongs to the Edendale app. If it did not open automatically, use the button below or return to the project site.",
  "link.open": "Open in Edendale",
  "link.visit": "Visit Edendale",
  "link.unavailable":
    "Edendale is not installed or this link is unavailable on this device.",
  "link.explicit": "The app opens only after you choose Open in Edendale.",
} as const;

export type UIKey = keyof typeof en;
export type Dictionary = Readonly<Record<UIKey, string>>;

const es: Dictionary = {
  "meta.home.title": "Edendale — Tu biblioteca, tu historial",
  "meta.home.description":
    "Un reproductor de vídeo libre y de código abierto con seguimiento personal de lo que ves, creado de forma nativa para las plataformas de Apple, Android y Windows.",
  "meta.link.title": "Abrir en Edendale",
  "meta.link.description": "Continúa este enlace en la app de Edendale.",
  "meta.notFound.description":
    "Continúa este enlace en la app de Edendale o vuelve al sitio del proyecto.",
  "meta.privacy.title": "Política de privacidad — Edendale",
  "meta.privacy.description":
    "Cómo trata Edendale tus datos: sin cuenta, sin servidores de Edendale, sin analíticas y con acceso de solo lectura al almacenamiento que vinculas.",
  "meta.socialAlt": "Edendale: tu archivo cinematográfico privado.",

  "chrome.skipToContent": "Saltar al contenido",
  "chrome.homeAria": "Inicio de Edendale",
  "chrome.brandTagline": "Cine personal",
  "chrome.navAria": "Navegación principal",
  "chrome.navFeatures": "Funciones",
  "chrome.navPlatforms": "Plataformas",
  "chrome.navPrivacy": "Privacidad",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Tus historias siguen siendo tuyas.",
  "chrome.footerNote":
    "Libre y de código abierto · Sin analíticas · Hecho con cuidado",
  "chrome.footerPrivacy": "Política de privacidad",
  "chrome.footerSource": "Código en GitHub",

  "language.label": "Idioma",
  "language.aria": "Elegir idioma",

  "hero.eyebrow": "Privado por diseño",
  "hero.title": "Tu biblioteca.\nTu historial.\nTuyo.",
  "hero.lede":
    "Edendale convierte las películas y series que ya tienes en un archivo personal precioso, sin convertir lo que ves en los datos de otra persona.",
  "hero.ctaSource": "Ver en GitHub",
  "hero.ctaExplore": "Explorar las apps",
  "hero.trustAria": "Principios de Edendale",
  "hero.trustLocal": "Biblioteca local primero",
  "hero.trustAnalytics": "Sin analíticas",
  "hero.trustOpenSource": "Código abierto",
  "hero.visualAria": "Una vista estilizada del archivo personal de Edendale",
  "hero.windowTitle": "El archivo",
  "hero.windowStatus": "Local",
  "hero.windowResume": "Seguir viendo",
  "hero.windowMoment": "Domingo por la tarde",
  "hero.windowNowPlaying": "Reproduciendo desde tu biblioteca",
  "hero.windowPickUp": "Retoma justo donde lo dejaste.",
  "hero.privacyTitle": "Nada sale de tu biblioteca",
  "hero.privacyBody": "Tus archivos se quedan donde los guardas.",

  "proof.aria": "Experiencias disponibles",
  "proof.archiveTitle": "Un solo archivo",
  "proof.archiveBody": "Películas, series y progreso",
  "proof.playbackTitle": "Reproducción nativa",
  "proof.playbackBody": "Creada para cada plataforma",
  "proof.cloudTitle": "Tu nube",
  "proof.cloudBody": "Sincronización privada donde esté disponible",
  "proof.telemetryTitle": "Cero telemetría",
  "proof.telemetryBody": "Sin perfiles, anuncios ni analíticas",

  "features.eyebrow": "Un archivo personal mejor",
  "features.title": "Hecho para la colección\nque ya tienes.",
  "features.lede":
    "Edendale hace el trabajo útil (organizar, enriquecer y recordar) mientras tú mantienes el control.",
  "features.libraryTitle": "Crea una biblioteca con tus archivos",
  "features.libraryBody":
    "Elige tus carpetas y Edendale ordena películas y episodios en local; luego añade los detalles útiles en segundo plano.",
  "features.progressTitle": "Recuerda cada historia",
  "features.progressBody":
    "Continúa donde lo dejaste y guarda un historial personal en tus propios dispositivos.",
  "features.privacyTitle": "Privado desde los cimientos",
  "features.privacyBody":
    "Sin cuentas que vender, sin perfil de visionado y sin analíticas vigilando lo que ves.",

  "sources.eyebrow": "Tus fuentes",
  "sources.title": "Reprodúcelo desde\ndonde lo guardes.",
  "sources.lede":
    "Vincula una carpeta de este dispositivo, un servidor de casa o una unidad en la nube. Edendale la recorre, ordena los archivos en tu dispositivo y transmite solo lo que reproduces, directamente del origen a tu pantalla.",
  "sources.deviceTitle": "En este dispositivo",
  "sources.deviceBody":
    "Las carpetas y unidades que ya usas, leídas donde están, sin copiarlas.",
  "sources.deviceFolders": "Carpetas",
  "sources.deviceDrives": "Unidades externas",
  "sources.networkTitle": "En tu red",
  "sources.networkBody":
    "Servidores y NAS de casa, con sus credenciales guardadas en el almacenamiento protegido de tu dispositivo.",
  "sources.cloudTitle": "En la nube",
  "sources.cloudBody":
    "En las unidades en la nube inicias sesión en la página del propio proveedor, así que Edendale nunca ve tu contraseña.",
  "sources.accessTitle": "Solo lectura, y solo para reproducir",
  "sources.accessBody":
    "Edendale pide a Google Drive, OneDrive y Dropbox acceso de solo lectura: para mostrar qué cuenta está vinculada, listar las carpetas que eliges y transmitir los vídeos que reproduces. No puede cambiar ni borrar nada allí, y nada pasa por un servidor de Edendale.",
  "sources.accessLink": "Cómo trata Edendale tus datos",
  "sources.availability":
    "Los servicios disponibles varían según la plataforma.",

  "player.eyebrow": "El reproductor",
  "player.title": "Cada detalle,\na tu medida.",
  "player.lede":
    "El reproductor de Edendale te da los controles que merece un cine en casa, y cada extra en línea espera a que tú lo pidas.",
  "player.skipTitle": "Avisos para omitir",
  "player.skipBody":
    "Botones para omitir la intro, el resumen y los créditos con las marcas de tiempo de la comunidad de TheIntroDB. Desactivados hasta que los actives, y nunca omiten nada por sí solos.",
  "player.nextTitle": "A continuación",
  "player.nextBody":
    "Los episodios pasan al siguiente que tengas, y Seguir viendo recuerda qué viene después del último que terminaste.",
  "player.soundTitle": "Sonido",
  "player.soundBody":
    "Perfiles de ecualizador para películas, música, diálogos y noches tranquilas, además de un amplificador de audio para mezclas con poco volumen.",
  "player.pictureTitle": "Imagen",
  "player.pictureBody":
    "Ajusta el brillo, el contraste y el color, con escalado y nitidez por GPU en el hardware compatible.",
  "player.subtitlesTitle": "Subtítulos",
  "player.subtitlesBody":
    "Elige la fuente, los colores y el fondo, o busca en línea cuando un archivo no incluye tu idioma.",
  "player.controlsTitle": "Controles",
  "player.controlsBody":
    "Decide cuánto avanza cada salto y a qué velocidad se reproduce al mantener pulsado, con pantalla táctil, teclado, ratón o mando.",

  "platforms.eyebrow": "Nativa donde importa",
  "platforms.title": "Como en casa en\ncada pantalla.",
  "platforms.lede":
    "Cada app de Edendale se crea con el lenguaje y el kit de interfaz de su plataforma. Controles familiares, buen rendimiento y ninguna capa web compartida.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "Móviles · tablets · pantallas grandes",
  "platforms.windowsDevices": "Un archivo de escritorio enfocado",

  "closing.eyebrow": "Se encienden las luces",
  "closing.title": "Haz que tu colección\nvuelva a sentirse tuya.",
  "closing.body":
    "Edendale es libre, de código abierto y está en desarrollo activo. Sigue el proyecto y ayuda a decidir qué viene después.",
  "closing.cta": "Seguir el desarrollo",

  "privacy.eyebrow": "Privacidad",
  "privacy.title": "Política de\nprivacidad",
  "privacy.lede":
    "Edendale está hecho para que tu biblioteca, tu historial y tus archivos sigan bajo tu control. Esta página explica exactamente qué manejan las apps y este sitio web, y qué no tocan nunca.",
  "privacy.updated": "Vigente desde el {date}",
  "privacy.summaryTitle": "En resumen",
  "privacy.tocTitle": "En esta página",
  "privacy.translationNote":
    "Esta traducción se ofrece por comodidad. Si difiere de la versión en inglés, prevalece la versión en inglés.",
  "privacy.readEnglish": "Leer la versión en inglés",

  "link.eyebrow": "Enlace de la app",
  "link.heading": "Continuar en Edendale",
  "link.headingNotFound": "Este enlace se abre en Edendale",
  "link.body":
    "Este enlace pertenece a la app de Edendale. Si no se abrió automáticamente, usa el botón de abajo o vuelve al sitio del proyecto.",
  "link.open": "Abrir en Edendale",
  "link.visit": "Ir a Edendale",
  "link.unavailable":
    "Edendale no está instalada o este enlace no está disponible en este dispositivo.",
  "link.explicit": "La app solo se abre cuando eliges Abrir en Edendale.",
};

const fr: Dictionary = {
  "meta.home.title": "Edendale — Votre bibliothèque, votre historique",
  "meta.home.description":
    "Un lecteur vidéo libre et open source avec suivi personnel de vos visionnages, développé nativement pour les plateformes Apple, Android et Windows.",
  "meta.link.title": "Ouvrir dans Edendale",
  "meta.link.description": "Poursuivez ce lien dans l’app Edendale.",
  "meta.notFound.description":
    "Poursuivez ce lien dans l’app Edendale ou revenez au site du projet.",
  "meta.privacy.title": "Politique de confidentialité — Edendale",
  "meta.privacy.description":
    "Comment Edendale traite vos données : aucun compte, aucun serveur Edendale, aucune analyse, et un accès en lecture seule aux stockages que vous associez.",
  "meta.socialAlt": "Edendale — votre archive cinématographique privée.",

  "chrome.skipToContent": "Aller au contenu",
  "chrome.homeAria": "Accueil Edendale",
  "chrome.brandTagline": "Cinéma personnel",
  "chrome.navAria": "Navigation principale",
  "chrome.navFeatures": "Fonctionnalités",
  "chrome.navPlatforms": "Plateformes",
  "chrome.navPrivacy": "Confidentialité",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Vos histoires restent les vôtres.",
  "chrome.footerNote":
    "Libre et open source · Aucune analyse · Conçu avec soin",
  "chrome.footerPrivacy": "Politique de confidentialité",
  "chrome.footerSource": "Code source sur GitHub",

  "language.label": "Langue",
  "language.aria": "Choisir une langue",

  "hero.eyebrow": "Confidentiel par conception",
  "hero.title": "Votre bibliothèque.\nVotre historique.\nÀ vous.",
  "hero.lede":
    "Edendale transforme les films et séries que vous possédez déjà en une superbe archive personnelle, sans transformer vos habitudes de visionnage en données pour quelqu’un d’autre.",
  "hero.ctaSource": "Voir sur GitHub",
  "hero.ctaExplore": "Découvrir les apps",
  "hero.trustAria": "Principes d’Edendale",
  "hero.trustLocal": "Bibliothèque locale d’abord",
  "hero.trustAnalytics": "Aucune analyse",
  "hero.trustOpenSource": "Open source",
  "hero.visualAria": "Une vue stylisée de l’archive personnelle Edendale",
  "hero.windowTitle": "L’archive",
  "hero.windowStatus": "Local",
  "hero.windowResume": "Reprendre",
  "hero.windowMoment": "Dimanche soir",
  "hero.windowNowPlaying": "Lecture depuis votre bibliothèque",
  "hero.windowPickUp": "Reprenez exactement où vous vous êtes arrêté.",
  "hero.privacyTitle": "Rien ne quitte votre bibliothèque",
  "hero.privacyBody": "Vos fichiers restent là où vous les rangez.",

  "proof.aria": "Expériences prises en charge",
  "proof.archiveTitle": "Une seule archive",
  "proof.archiveBody": "Films, séries et progression",
  "proof.playbackTitle": "Lecture native",
  "proof.playbackBody": "Conçue pour chaque plateforme",
  "proof.cloudTitle": "Votre cloud",
  "proof.cloudBody": "Synchronisation privée là où c’est possible",
  "proof.telemetryTitle": "Zéro télémétrie",
  "proof.telemetryBody": "Ni profils, ni publicités, ni analyses",

  "features.eyebrow": "Une meilleure archive personnelle",
  "features.title": "Pensé pour la collection\nque vous avez déjà.",
  "features.lede":
    "Edendale fait le travail utile — organiser, enrichir, mémoriser — tout en vous laissant aux commandes.",
  "features.libraryTitle": "Créez une bibliothèque à partir de vos fichiers",
  "features.libraryBody":
    "Choisissez vos dossiers : Edendale trie films et épisodes en local, puis ajoute les détails utiles en arrière-plan.",
  "features.progressTitle": "Retenez chaque histoire",
  "features.progressBody":
    "Reprenez là où vous vous êtes arrêté et conservez un historique personnel sur vos propres appareils.",
  "features.privacyTitle": "Confidentiel dès les fondations",
  "features.privacyBody":
    "Aucun compte à revendre, aucun profil de visionnage et aucune analyse pour surveiller ce que vous regardez.",

  "sources.eyebrow": "Vos sources",
  "sources.title": "Lisez vos vidéos\nlà où elles sont.",
  "sources.lede":
    "Associez un dossier de cet appareil, un serveur à la maison ou un stockage en ligne. Edendale le parcourt, trie les fichiers sur votre appareil et ne diffuse que ce que vous regardez, directement de la source à votre écran.",
  "sources.deviceTitle": "Sur cet appareil",
  "sources.deviceBody":
    "Les dossiers et disques que vous utilisez déjà, lus sur place, jamais copiés.",
  "sources.deviceFolders": "Dossiers",
  "sources.deviceDrives": "Disques externes",
  "sources.networkTitle": "Sur votre réseau",
  "sources.networkBody":
    "Serveurs et NAS à la maison, avec leurs identifiants conservés dans le stockage protégé de votre appareil.",
  "sources.cloudTitle": "Dans le cloud",
  "sources.cloudBody":
    "Pour les stockages en ligne, vous vous connectez sur la page du fournisseur : Edendale ne voit jamais votre mot de passe.",
  "sources.accessTitle": "En lecture seule, juste pour regarder",
  "sources.accessBody":
    "Edendale ne demande à Google Drive, OneDrive et Dropbox qu’un accès en lecture seule : pour afficher le compte associé, parcourir les dossiers que vous choisissez et diffuser les vidéos que vous regardez. Il ne peut rien y modifier ni supprimer, et rien ne passe par un serveur Edendale.",
  "sources.accessLink": "Comment Edendale traite vos données",
  "sources.availability":
    "Les services disponibles varient selon la plateforme.",

  "player.eyebrow": "Le lecteur",
  "player.title": "Chaque détail,\nà votre goût.",
  "player.lede":
    "Le lecteur Edendale vous donne les commandes que mérite un cinéma à la maison, et chaque option en ligne attend que vous la demandiez.",
  "player.skipTitle": "Boutons pour passer",
  "player.skipBody":
    "Des boutons pour passer l’introduction, le résumé et le générique, grâce aux repères de la communauté TheIntroDB. Désactivés tant que vous ne les activez pas, ils ne passent jamais rien d’eux-mêmes.",
  "player.nextTitle": "À suivre",
  "player.nextBody":
    "Les épisodes s’enchaînent avec le suivant que vous possédez, et Reprendre la lecture retient ce qui vient après le dernier épisode terminé.",
  "player.soundTitle": "Son",
  "player.soundBody":
    "Des profils d’égaliseur pour les films, la musique, les dialogues et les soirées tardives, plus un amplificateur audio pour les mixages trop discrets.",
  "player.pictureTitle": "Image",
  "player.pictureBody":
    "Réglez la luminosité, le contraste et les couleurs, avec mise à l’échelle et netteté par GPU sur le matériel compatible.",
  "player.subtitlesTitle": "Sous-titres",
  "player.subtitlesBody":
    "Choisissez la police, les couleurs et le fond, ou cherchez en ligne quand un fichier n’a pas votre langue.",
  "player.controlsTitle": "Commandes",
  "player.controlsBody":
    "Réglez la durée des sauts et la vitesse d’un appui prolongé, au toucher, au clavier, à la souris ou à la télécommande.",

  "platforms.eyebrow": "Native là où ça compte",
  "platforms.title": "À l’aise sur\nchaque écran.",
  "platforms.lede":
    "Chaque app Edendale est écrite dans le langage et la boîte à outils d’interface de sa plateforme. Des commandes familières, des performances soignées, aucune coque web partagée.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "Téléphones · tablettes · grands écrans",
  "platforms.windowsDevices": "Une archive de bureau épurée",

  "closing.eyebrow": "Les lumières se rallument",
  "closing.title": "Que votre collection\nredevienne la vôtre.",
  "closing.body":
    "Edendale est libre, open source et en développement actif. Suivez le projet et aidez à décider de la suite.",
  "closing.cta": "Suivre le développement",

  "privacy.eyebrow": "Confidentialité",
  "privacy.title": "Politique de\nconfidentialité",
  "privacy.lede":
    "Edendale est conçu pour que votre bibliothèque, votre historique et vos fichiers restent sous votre contrôle. Cette page explique précisément ce que les apps et ce site traitent, et ce à quoi ils ne touchent jamais.",
  "privacy.updated": "En vigueur depuis le {date}",
  "privacy.summaryTitle": "L’essentiel",
  "privacy.tocTitle": "Sur cette page",
  "privacy.translationNote":
    "Cette traduction est fournie par commodité. En cas de divergence avec la version anglaise, la version anglaise prévaut.",
  "privacy.readEnglish": "Lire la version anglaise",

  "link.eyebrow": "Lien d’app",
  "link.heading": "Continuer dans Edendale",
  "link.headingNotFound": "Ce lien s’ouvre dans Edendale",
  "link.body":
    "Ce lien appartient à l’app Edendale. S’il ne s’est pas ouvert automatiquement, utilisez le bouton ci-dessous ou revenez au site du projet.",
  "link.open": "Ouvrir dans Edendale",
  "link.visit": "Aller sur Edendale",
  "link.unavailable":
    "Edendale n’est pas installée ou ce lien n’est pas disponible sur cet appareil.",
  "link.explicit":
    "L’app ne s’ouvre qu’après avoir choisi Ouvrir dans Edendale.",
};

const de: Dictionary = {
  "meta.home.title": "Edendale — Deine Sammlung, dein Verlauf",
  "meta.home.description":
    "Ein freier, quelloffener Videoplayer mit persönlichem Wiedergabeverlauf – nativ entwickelt für Apple-Plattformen, Android und Windows.",
  "meta.link.title": "In Edendale öffnen",
  "meta.link.description": "Setze diesen Link in der Edendale-App fort.",
  "meta.notFound.description":
    "Setze diesen Link in der Edendale-App fort oder kehre zur Projektseite zurück.",
  "meta.privacy.title": "Datenschutzerklärung — Edendale",
  "meta.privacy.description":
    "Wie Edendale mit deinen Daten umgeht: kein Konto, keine Edendale-Server, keine Analysen und nur Lesezugriff auf die Speicher, die du verknüpfst.",
  "meta.socialAlt": "Edendale – dein privates Filmarchiv.",

  "chrome.skipToContent": "Zum Inhalt springen",
  "chrome.homeAria": "Edendale-Startseite",
  "chrome.brandTagline": "Persönliches Kino",
  "chrome.navAria": "Hauptnavigation",
  "chrome.navFeatures": "Funktionen",
  "chrome.navPlatforms": "Plattformen",
  "chrome.navPrivacy": "Datenschutz",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Deine Geschichten bleiben deine.",
  "chrome.footerNote":
    "Frei und quelloffen · Keine Analysen · Mit Sorgfalt gebaut",
  "chrome.footerPrivacy": "Datenschutzerklärung",
  "chrome.footerSource": "Quellcode auf GitHub",

  "language.label": "Sprache",
  "language.aria": "Sprache wählen",

  "hero.eyebrow": "Privat von Grund auf",
  "hero.title": "Deine Sammlung.\nDein Verlauf.\nDeins.",
  "hero.lede":
    "Edendale macht aus den Filmen und Serien, die du bereits besitzt, ein schönes persönliches Archiv – ohne dein Sehverhalten zu den Daten anderer zu machen.",
  "hero.ctaSource": "Auf GitHub ansehen",
  "hero.ctaExplore": "Apps entdecken",
  "hero.trustAria": "Grundsätze von Edendale",
  "hero.trustLocal": "Lokale Mediathek zuerst",
  "hero.trustAnalytics": "Keine Analysen",
  "hero.trustOpenSource": "Quelloffen",
  "hero.visualAria":
    "Eine stilisierte Ansicht des persönlichen Edendale-Archivs",
  "hero.windowTitle": "Das Archiv",
  "hero.windowStatus": "Lokal",
  "hero.windowResume": "Weiterschauen",
  "hero.windowMoment": "Sonntagabend",
  "hero.windowNowPlaying": "Läuft aus deiner Mediathek",
  "hero.windowPickUp": "Mach genau dort weiter, wo du aufgehört hast.",
  "hero.privacyTitle": "Nichts verlässt deine Mediathek",
  "hero.privacyBody": "Deine Dateien bleiben, wo du sie aufbewahrst.",

  "proof.aria": "Unterstützte Erlebnisse",
  "proof.archiveTitle": "Ein Archiv",
  "proof.archiveBody": "Filme, Serien und Fortschritt",
  "proof.playbackTitle": "Native Wiedergabe",
  "proof.playbackBody": "Für jede Plattform gebaut",
  "proof.cloudTitle": "Deine Cloud",
  "proof.cloudBody": "Private Synchronisierung, wo verfügbar",
  "proof.telemetryTitle": "Null Telemetrie",
  "proof.telemetryBody": "Keine Profile, Werbung oder Analysen",

  "features.eyebrow": "Ein besseres persönliches Archiv",
  "features.title": "Gemacht für die Sammlung,\ndie du schon hast.",
  "features.lede":
    "Edendale erledigt die nützliche Arbeit – ordnen, anreichern, erinnern – und lässt dir die Kontrolle.",
  "features.libraryTitle": "Baue eine Mediathek aus deinen Dateien",
  "features.libraryBody":
    "Wähle deine Ordner: Edendale sortiert Filme und Folgen lokal und ergänzt die nützlichen Details im Hintergrund.",
  "features.progressTitle": "Behalte jede Geschichte",
  "features.progressBody":
    "Mach dort weiter, wo du aufgehört hast, und führe einen persönlichen Verlauf über deine eigenen Geräte hinweg.",
  "features.privacyTitle": "Privat im Fundament",
  "features.privacyBody":
    "Keine Konten zum Verkaufen, kein Sehprofil und keine Analysen, die mitschauen.",

  "sources.eyebrow": "Deine Quellen",
  "sources.title": "Spiel es ab,\nwo immer es liegt.",
  "sources.lede":
    "Verknüpfe einen Ordner auf diesem Gerät, einen Server zu Hause oder einen Cloud-Speicher. Edendale liest ihn ein, sortiert die Dateien auf deinem Gerät und streamt nur, was du abspielst – direkt von der Quelle auf deinen Bildschirm.",
  "sources.deviceTitle": "Auf diesem Gerät",
  "sources.deviceBody":
    "Die Ordner und Laufwerke, die du schon nutzt – direkt dort gelesen, nie kopiert.",
  "sources.deviceFolders": "Ordner",
  "sources.deviceDrives": "Externe Laufwerke",
  "sources.networkTitle": "In deinem Netzwerk",
  "sources.networkBody":
    "Server und NAS-Freigaben zu Hause, deren Anmeldedaten im geschützten Speicher deines Geräts bleiben.",
  "sources.cloudTitle": "In der Cloud",
  "sources.cloudBody":
    "Bei Cloud-Speichern meldest du dich auf der Seite des Anbieters an, deshalb sieht Edendale dein Passwort nie.",
  "sources.accessTitle": "Nur lesen, nur zum Abspielen",
  "sources.accessBody":
    "Edendale bittet Google Drive, OneDrive und Dropbox nur um Lesezugriff: um das verknüpfte Konto anzuzeigen, die gewählten Ordner aufzulisten und die Videos zu streamen, die du abspielst. Es kann dort nichts ändern oder löschen, und nichts läuft über einen Edendale-Server.",
  "sources.accessLink": "Wie Edendale mit deinen Daten umgeht",
  "sources.availability":
    "Welche Dienste verfügbar sind, hängt von der Plattform ab.",

  "player.eyebrow": "Der Player",
  "player.title": "Jedes Detail,\nnach deinem Geschmack.",
  "player.lede":
    "Der Edendale-Player gibt dir die Steuerung, die ein Heimkino verdient, und jedes Online-Extra wartet, bis du es willst.",
  "player.skipTitle": "Hinweise zum Überspringen",
  "player.skipBody":
    "Schaltflächen zum Überspringen von Intro, Rückblick und Abspann, basierend auf den Zeitmarken der TheIntroDB-Community. Aus, bis du sie einschaltest – und sie überspringen nie von selbst.",
  "player.nextTitle": "Als Nächstes",
  "player.nextBody":
    "Folgen gehen in die nächste über, die du hast, und „Weiter ansehen“ merkt sich, was nach der zuletzt beendeten Folge kommt.",
  "player.soundTitle": "Ton",
  "player.soundBody":
    "Equalizer-Profile für Filme, Musik, Dialoge und späte Abende, dazu ein Audio-Booster für leise Abmischungen.",
  "player.pictureTitle": "Bild",
  "player.pictureBody":
    "Stelle Helligkeit, Kontrast und Farbe fein ein, mit GPU-Hochskalierung und Schärfung auf unterstützter Hardware.",
  "player.subtitlesTitle": "Untertitel",
  "player.subtitlesBody":
    "Wähle Schrift, Farben und Hintergrund, oder suche online, wenn eine Datei deine Sprache nicht enthält.",
  "player.controlsTitle": "Steuerung",
  "player.controlsBody":
    "Lege fest, wie weit Sprünge reichen und wie schnell langes Drücken abspielt – per Touch, Tastatur, Maus oder Fernbedienung.",

  "platforms.eyebrow": "Nativ, wo es zählt",
  "platforms.title": "Zu Hause auf\njedem Bildschirm.",
  "platforms.lede":
    "Jede Edendale-App entsteht in der Sprache und dem Oberflächen-Toolkit ihrer Plattform. Vertraute Bedienung, durchdachte Leistung, keine gemeinsame Web-Hülle.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "Smartphones · Tablets · große Bildschirme",
  "platforms.windowsDevices": "Ein fokussiertes Desktop-Archiv",

  "closing.eyebrow": "Das Licht geht an",
  "closing.title": "Lass deine Sammlung\nwieder wie deine wirken.",
  "closing.body":
    "Edendale ist frei, quelloffen und in aktiver Entwicklung. Folge dem Projekt und gestalte mit, was als Nächstes kommt.",
  "closing.cta": "Entwicklung verfolgen",

  "privacy.eyebrow": "Datenschutz",
  "privacy.title": "Datenschutz",
  "privacy.lede":
    "Edendale ist so gebaut, dass deine Mediathek, dein Verlauf und deine Dateien unter deiner Kontrolle bleiben. Diese Seite erklärt genau, was die Apps und diese Website verarbeiten – und was sie nie anrühren.",
  "privacy.updated": "Gültig ab {date}",
  "privacy.summaryTitle": "Das Wichtigste in Kürze",
  "privacy.tocTitle": "Auf dieser Seite",
  "privacy.translationNote":
    "Diese Übersetzung dient der Bequemlichkeit. Weicht sie von der englischen Fassung ab, gilt die englische Fassung.",
  "privacy.readEnglish": "Englische Fassung lesen",

  "link.eyebrow": "App-Link",
  "link.heading": "In Edendale fortsetzen",
  "link.headingNotFound": "Dieser Link öffnet sich in Edendale",
  "link.body":
    "Dieser Link gehört zur Edendale-App. Falls er sich nicht automatisch geöffnet hat, nutze die Schaltfläche unten oder kehre zur Projektseite zurück.",
  "link.open": "In Edendale öffnen",
  "link.visit": "Zu Edendale",
  "link.unavailable":
    "Edendale ist nicht installiert oder dieser Link ist auf diesem Gerät nicht verfügbar.",
  "link.explicit":
    "Die App öffnet sich erst, wenn du „In Edendale öffnen“ wählst.",
};

const ptBR: Dictionary = {
  "meta.home.title": "Edendale — Sua biblioteca, seu histórico",
  "meta.home.description":
    "Um reprodutor de vídeo livre e de código aberto com histórico pessoal do que você assiste, desenvolvido nativamente para as plataformas Apple, Android e Windows.",
  "meta.link.title": "Abrir no Edendale",
  "meta.link.description": "Continue este link no app Edendale.",
  "meta.notFound.description":
    "Continue este link no app Edendale ou volte ao site do projeto.",
  "meta.privacy.title": "Política de privacidade — Edendale",
  "meta.privacy.description":
    "Como o Edendale trata seus dados: sem conta, sem servidores do Edendale, sem analytics e com acesso somente leitura ao armazenamento que você vincula.",
  "meta.socialAlt": "Edendale — seu acervo de cinema particular.",

  "chrome.skipToContent": "Ir para o conteúdo",
  "chrome.homeAria": "Início do Edendale",
  "chrome.brandTagline": "Cinema pessoal",
  "chrome.navAria": "Navegação principal",
  "chrome.navFeatures": "Recursos",
  "chrome.navPlatforms": "Plataformas",
  "chrome.navPrivacy": "Privacidade",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Suas histórias continuam suas.",
  "chrome.footerNote":
    "Livre e de código aberto · Sem analytics · Feito com cuidado",
  "chrome.footerPrivacy": "Política de privacidade",
  "chrome.footerSource": "Código no GitHub",

  "language.label": "Idioma",
  "language.aria": "Escolher idioma",

  "hero.eyebrow": "Privado por concepção",
  "hero.title": "Sua biblioteca.\nSeu histórico.\nSeu.",
  "hero.lede":
    "O Edendale transforma os filmes e séries que você já tem em um belo acervo pessoal — sem transformar o que você assiste em dado de outra pessoa.",
  "hero.ctaSource": "Ver no GitHub",
  "hero.ctaExplore": "Conhecer os apps",
  "hero.trustAria": "Princípios do Edendale",
  "hero.trustLocal": "Biblioteca local primeiro",
  "hero.trustAnalytics": "Sem analytics",
  "hero.trustOpenSource": "Código aberto",
  "hero.visualAria": "Uma visão estilizada do acervo pessoal do Edendale",
  "hero.windowTitle": "O acervo",
  "hero.windowStatus": "Local",
  "hero.windowResume": "Continuar assistindo",
  "hero.windowMoment": "Domingo à noite",
  "hero.windowNowPlaying": "Reproduzindo da sua biblioteca",
  "hero.windowPickUp": "Retome exatamente de onde parou.",
  "hero.privacyTitle": "Nada sai da sua biblioteca",
  "hero.privacyBody": "Seus arquivos ficam onde você os guarda.",

  "proof.aria": "Experiências disponíveis",
  "proof.archiveTitle": "Um só acervo",
  "proof.archiveBody": "Filmes, séries e progresso",
  "proof.playbackTitle": "Reprodução nativa",
  "proof.playbackBody": "Feita para cada plataforma",
  "proof.cloudTitle": "Sua nuvem",
  "proof.cloudBody": "Sincronização privada onde houver",
  "proof.telemetryTitle": "Zero telemetria",
  "proof.telemetryBody": "Sem perfis, anúncios ou analytics",

  "features.eyebrow": "Um acervo pessoal melhor",
  "features.title": "Feito para a coleção\nque você já tem.",
  "features.lede":
    "O Edendale faz o trabalho útil — organizar, enriquecer e lembrar — enquanto você mantém o controle.",
  "features.libraryTitle": "Monte uma biblioteca com seus arquivos",
  "features.libraryBody":
    "Escolha suas pastas e o Edendale organiza filmes e episódios localmente, depois acrescenta os detalhes úteis em segundo plano.",
  "features.progressTitle": "Lembre de cada história",
  "features.progressBody":
    "Continue de onde parou e mantenha um histórico pessoal nos seus próprios dispositivos.",
  "features.privacyTitle": "Privado desde a base",
  "features.privacyBody":
    "Sem contas para vender, sem perfil de consumo e sem analytics observando o que você assiste.",

  "sources.eyebrow": "Suas origens",
  "sources.title": "Assista de onde\nestiver guardado.",
  "sources.lede":
    "Vincule uma pasta deste dispositivo, um servidor em casa ou um armazenamento na nuvem. O Edendale lista o conteúdo, organiza os arquivos no seu dispositivo e transmite só o que você assiste, direto da origem para a sua tela.",
  "sources.deviceTitle": "Neste dispositivo",
  "sources.deviceBody":
    "As pastas e unidades que você já usa, lidas onde estão, sem cópias.",
  "sources.deviceFolders": "Pastas",
  "sources.deviceDrives": "Unidades externas",
  "sources.networkTitle": "Na sua rede",
  "sources.networkBody":
    "Servidores e NAS em casa, com os logins guardados no armazenamento protegido do seu dispositivo.",
  "sources.cloudTitle": "Na nuvem",
  "sources.cloudBody":
    "Nos armazenamentos na nuvem, você entra pela página do próprio provedor, então o Edendale nunca vê sua senha.",
  "sources.accessTitle": "Somente leitura, só para assistir",
  "sources.accessBody":
    "O Edendale pede ao Google Drive, OneDrive e Dropbox apenas acesso somente leitura: para mostrar qual conta está vinculada, listar as pastas que você escolhe e transmitir os vídeos que você assiste. Ele não pode alterar nem apagar nada lá, e nada passa por um servidor do Edendale.",
  "sources.accessLink": "Como o Edendale trata seus dados",
  "sources.availability":
    "Os serviços disponíveis variam conforme a plataforma.",

  "player.eyebrow": "O reprodutor",
  "player.title": "Cada detalhe,\ndo seu jeito.",
  "player.lede":
    "O reprodutor do Edendale traz os controles que um cinema em casa merece, e cada recurso online espera você pedir.",
  "player.skipTitle": "Botões para pular",
  "player.skipBody":
    "Botões para pular a abertura, o resumo e os créditos com as marcações da comunidade do TheIntroDB. Ficam desativados até você ativar, e nunca pulam nada sozinhos.",
  "player.nextTitle": "A seguir",
  "player.nextBody":
    "Os episódios seguem para o próximo que você tem, e Continuar assistindo lembra o que vem depois do último que você terminou.",
  "player.soundTitle": "Som",
  "player.soundBody":
    "Perfis de equalizador para filmes, música, diálogos e madrugadas, além de um amplificador de áudio para mixagens baixas.",
  "player.pictureTitle": "Imagem",
  "player.pictureBody":
    "Ajuste brilho, contraste e cor, com ampliação e nitidez por GPU no hardware compatível.",
  "player.subtitlesTitle": "Legendas",
  "player.subtitlesBody":
    "Escolha a fonte, as cores e o fundo, ou busque online quando um arquivo não tem o seu idioma.",
  "player.controlsTitle": "Controles",
  "player.controlsBody":
    "Defina quanto cada salto avança e a velocidade ao manter pressionado, no toque, no teclado, no mouse ou no controle remoto.",

  "platforms.eyebrow": "Nativo onde importa",
  "platforms.title": "Em casa em\ncada tela.",
  "platforms.lede":
    "Cada app do Edendale é escrito na linguagem e no kit de interface da sua plataforma. Controles familiares, desempenho cuidadoso, nenhuma casca web compartilhada.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "Celulares · tablets · telas grandes",
  "platforms.windowsDevices": "Um acervo de desktop enxuto",

  "closing.eyebrow": "As luzes estão acendendo",
  "closing.title": "Faça sua coleção\nparecer sua de novo.",
  "closing.body":
    "O Edendale é livre, de código aberto e está em desenvolvimento ativo. Acompanhe o projeto e ajude a definir o que vem a seguir.",
  "closing.cta": "Acompanhar o desenvolvimento",

  "privacy.eyebrow": "Privacidade",
  "privacy.title": "Política de\nprivacidade",
  "privacy.lede":
    "O Edendale foi feito para que sua biblioteca, seu histórico e seus arquivos continuem sob o seu controle. Esta página explica exatamente o que os apps e este site tratam, e o que eles nunca tocam.",
  "privacy.updated": "Em vigor desde {date}",
  "privacy.summaryTitle": "Em resumo",
  "privacy.tocTitle": "Nesta página",
  "privacy.translationNote":
    "Esta tradução é oferecida por conveniência. Se houver divergência com a versão em inglês, prevalece a versão em inglês.",
  "privacy.readEnglish": "Ler a versão em inglês",

  "link.eyebrow": "Link do app",
  "link.heading": "Continuar no Edendale",
  "link.headingNotFound": "Este link abre no Edendale",
  "link.body":
    "Este link pertence ao app Edendale. Se ele não abriu automaticamente, use o botão abaixo ou volte ao site do projeto.",
  "link.open": "Abrir no Edendale",
  "link.visit": "Ir para o Edendale",
  "link.unavailable":
    "O Edendale não está instalado ou este link não está disponível neste dispositivo.",
  "link.explicit":
    "O app só abre depois que você escolhe Abrir no Edendale.",
};

const ja: Dictionary = {
  "meta.home.title": "Edendale — あなたのライブラリ、あなたの視聴履歴",
  "meta.home.description":
    "Apple の各プラットフォーム、Android、Windows それぞれにネイティブで作られた、無料でオープンソースの動画プレーヤーと視聴記録アプリ。",
  "meta.link.title": "Edendale で開く",
  "meta.link.description": "このリンクを Edendale アプリで続けます。",
  "meta.notFound.description":
    "このリンクを Edendale アプリで続けるか、プロジェクトサイトに戻ってください。",
  "meta.privacy.title": "プライバシーポリシー — Edendale",
  "meta.privacy.description":
    "Edendale のデータの扱い：アカウントなし、Edendale のサーバーなし、解析なし。リンクしたストレージには読み取り専用でアクセスします。",
  "meta.socialAlt": "Edendale — あなただけのプライベートな映画アーカイブ。",

  "chrome.skipToContent": "本文へスキップ",
  "chrome.homeAria": "Edendale ホーム",
  "chrome.brandTagline": "パーソナルシネマ",
  "chrome.navAria": "メインナビゲーション",
  "chrome.navFeatures": "機能",
  "chrome.navPlatforms": "対応プラットフォーム",
  "chrome.navPrivacy": "プライバシー",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "あなたの物語は、あなたのもの。",
  "chrome.footerNote": "無料・オープンソース · 解析なし · ていねいに作りました",
  "chrome.footerPrivacy": "プライバシーポリシー",
  "chrome.footerSource": "GitHub のソース",

  "language.label": "言語",
  "language.aria": "言語を選択",

  "hero.eyebrow": "設計からプライベート",
  "hero.title": "あなたのライブラリ。\nあなたの視聴履歴。\nあなたのもの。",
  "hero.lede":
    "Edendale は、すでに手元にある映画やドラマを美しい個人アーカイブに変えます。あなたの視聴傾向を、誰かのデータに変えることはありません。",
  "hero.ctaSource": "GitHub で見る",
  "hero.ctaExplore": "アプリを見る",
  "hero.trustAria": "Edendale の原則",
  "hero.trustLocal": "ローカル優先のライブラリ",
  "hero.trustAnalytics": "解析なし",
  "hero.trustOpenSource": "オープンソース",
  "hero.visualAria": "Edendale の個人アーカイブを様式化した画面",
  "hero.windowTitle": "アーカイブ",
  "hero.windowStatus": "ローカル",
  "hero.windowResume": "続きを見る",
  "hero.windowMoment": "日曜の夜",
  "hero.windowNowPlaying": "ライブラリから再生中",
  "hero.windowPickUp": "止めたところから、そのまま続きを。",
  "hero.privacyTitle": "ライブラリの外には出ません",
  "hero.privacyBody": "ファイルは、あなたが置いた場所にそのまま。",

  "proof.aria": "対応している体験",
  "proof.archiveTitle": "ひとつのアーカイブ",
  "proof.archiveBody": "映画、ドラマ、視聴の進み具合",
  "proof.playbackTitle": "ネイティブ再生",
  "proof.playbackBody": "すべてのプラットフォーム向けに構築",
  "proof.cloudTitle": "あなたのクラウド",
  "proof.cloudBody": "利用できる場所ではプライベートに同期",
  "proof.telemetryTitle": "テレメトリーはゼロ",
  "proof.telemetryBody": "プロフィールも広告も解析もなし",

  "features.eyebrow": "より良い個人アーカイブ",
  "features.title": "すでにある\nコレクションのために。",
  "features.lede":
    "Edendale は、整理し、情報を補い、記憶するという役に立つ仕事を引き受けます。主導権はあなたのままで。",
  "features.libraryTitle": "手元のファイルからライブラリを作る",
  "features.libraryBody":
    "フォルダを選ぶだけで、Edendale が映画とエピソードをローカルで仕分けし、必要な情報をバックグラウンドで補います。",
  "features.progressTitle": "すべての物語を覚えておく",
  "features.progressBody":
    "止めたところから再開し、自分のデバイス間で個人の視聴履歴を保てます。",
  "features.privacyTitle": "土台からプライベート",
  "features.privacyBody":
    "売られるアカウントも、視聴プロフィールも、見ているものを監視する解析もありません。",

  "sources.eyebrow": "あなたのソース",
  "sources.title": "どこにあっても、\nそのまま再生。",
  "sources.lede":
    "このデバイスのフォルダ、自宅のサーバー、クラウドストレージをリンクできます。Edendale は中身を一覧にし、ファイルをデバイス上で仕分けて、再生する部分だけをソースから画面へ直接ストリーミングします。",
  "sources.deviceTitle": "このデバイス",
  "sources.deviceBody": "いつものフォルダやドライブを、コピーせずにその場で読み込みます。",
  "sources.deviceFolders": "フォルダ",
  "sources.deviceDrives": "外部ドライブ",
  "sources.networkTitle": "自宅のネットワーク",
  "sources.networkBody": "自宅のサーバーや NAS の共有フォルダ。ログイン情報はデバイスの保護されたストレージに保管されます。",
  "sources.cloudTitle": "クラウド",
  "sources.cloudBody":
    "クラウドストレージへのサインインは各サービス自身のページで行うため、Edendale がパスワードを見ることはありません。",
  "sources.accessTitle": "読み取り専用、再生のためだけに",
  "sources.accessBody":
    "Edendale が Google Drive、OneDrive、Dropbox に求めるのは読み取り専用のアクセスだけです。リンク中のアカウントの表示、選んだフォルダの一覧、再生する動画のストリーミングにのみ使います。そこにあるものを変更・削除することはできず、Edendale のサーバーを経由するものもありません。",
  "sources.accessLink": "Edendale のデータの扱い",
  "sources.availability": "利用できるサービスはプラットフォームによって異なります。",

  "player.eyebrow": "プレーヤー",
  "player.title": "細部まで、\nあなた好みに。",
  "player.lede":
    "Edendale のプレーヤーには、ホームシアターにふさわしい操作がそろっています。オンラインの機能は、あなたが求めるまで動きません。",
  "player.skipTitle": "スキップボタン",
  "player.skipBody":
    "TheIntroDB のコミュニティによるタイムスタンプをもとに、イントロ、これまでのあらすじ、クレジットをスキップするボタンを表示します。オンにするまでは使われず、勝手にスキップすることもありません。",
  "player.nextTitle": "次はこちら",
  "player.nextBody": "エピソードは手元にある次の話へ自動で進み、「視聴を続ける」は最後に見終えた話の次を覚えています。",
  "player.soundTitle": "サウンド",
  "player.soundBody":
    "映画、音楽、ダイアログ、夜間向けのイコライザープロファイルに加え、音量の小さいミックス向けのオーディオブースターも備えています。",
  "player.pictureTitle": "映像",
  "player.pictureBody":
    "明るさ、コントラスト、色を細かく調整できます。対応ハードウェアでは GPU によるアップスケールとシャープ化も使えます。",
  "player.subtitlesTitle": "字幕",
  "player.subtitlesBody": "フォント、色、背景を選べます。ファイルにあなたの言語がなければ、オンラインで検索することもできます。",
  "player.controlsTitle": "操作",
  "player.controlsBody": "スキップの秒数や長押し中の再生速度を、タッチ、キーボード、マウス、リモコンに合わせて設定できます。",

  "platforms.eyebrow": "必要なところはネイティブで",
  "platforms.title": "どの画面でも、\n自分の家のように。",
  "platforms.lede":
    "Edendale の各アプリは、そのプラットフォームの言語と UI ツールキットで作られています。慣れた操作、行き届いたパフォーマンス、共通の Web シェルはありません。",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "スマートフォン · タブレット · 大画面",
  "platforms.windowsDevices": "目的に集中したデスクトップアーカイブ",

  "closing.eyebrow": "客席の照明がついていきます",
  "closing.title": "コレクションを、\nもう一度あなたのものに。",
  "closing.body":
    "Edendale は無料でオープンソース、開発が活発に続いています。プロジェクトをフォローして、次に来るものを一緒に形づくってください。",
  "closing.cta": "開発をフォロー",

  "privacy.eyebrow": "プライバシー",
  "privacy.title": "プライバシーポリシー",
  "privacy.lede":
    "Edendale は、ライブラリも視聴履歴もファイルも、あなたの管理下に置いたままにするよう作られています。このページでは、アプリとこのサイトが何を扱い、何に決して触れないのかを具体的に説明します。",
  "privacy.updated": "{date}施行",
  "privacy.summaryTitle": "要点",
  "privacy.tocTitle": "このページの内容",
  "privacy.translationNote": "この翻訳は便宜のために提供しています。英語版と内容が異なる場合は、英語版が優先されます。",
  "privacy.readEnglish": "英語版を読む",

  "link.eyebrow": "アプリリンク",
  "link.heading": "Edendale で続ける",
  "link.headingNotFound": "このリンクは Edendale で開きます",
  "link.body":
    "このリンクは Edendale アプリのものです。自動的に開かなかった場合は、下のボタンを使うか、プロジェクトサイトに戻ってください。",
  "link.open": "Edendale で開く",
  "link.visit": "Edendale を見る",
  "link.unavailable":
    "Edendale がインストールされていないか、このリンクはこのデバイスでは利用できません。",
  "link.explicit": "「Edendale で開く」を選んだときだけ、アプリが開きます。",
};

const ko: Dictionary = {
  "meta.home.title": "Edendale — 나의 라이브러리, 나의 시청 기록",
  "meta.home.description":
    "Apple 플랫폼, Android, Windows 각각에 네이티브로 만든 무료 오픈 소스 동영상 플레이어이자 개인 시청 기록 앱입니다.",
  "meta.link.title": "Edendale에서 열기",
  "meta.link.description": "이 링크를 Edendale 앱에서 이어서 봅니다.",
  "meta.notFound.description":
    "이 링크를 Edendale 앱에서 이어서 보거나 프로젝트 사이트로 돌아가세요.",
  "meta.privacy.title": "개인정보 처리방침 — Edendale",
  "meta.privacy.description":
    "Edendale이 데이터를 다루는 방식: 계정 없음, Edendale 서버 없음, 분석 없음. 연결한 저장소에는 읽기 전용으로만 접근합니다.",
  "meta.socialAlt": "Edendale — 나만의 사적인 영화 아카이브.",

  "chrome.skipToContent": "본문으로 건너뛰기",
  "chrome.homeAria": "Edendale 홈",
  "chrome.brandTagline": "개인 영화관",
  "chrome.navAria": "기본 탐색",
  "chrome.navFeatures": "기능",
  "chrome.navPlatforms": "플랫폼",
  "chrome.navPrivacy": "개인정보",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "당신의 이야기는 당신의 것으로.",
  "chrome.footerNote": "무료 오픈 소스 · 분석 없음 · 정성껏 만들었습니다",
  "chrome.footerPrivacy": "개인정보 처리방침",
  "chrome.footerSource": "GitHub 소스",

  "language.label": "언어",
  "language.aria": "언어 선택",

  "hero.eyebrow": "설계부터 사적으로",
  "hero.title": "나의 라이브러리.\n나의 시청 기록.\n나의 것.",
  "hero.lede":
    "Edendale은 이미 가지고 있는 영화와 시리즈를 아름다운 개인 아카이브로 만듭니다. 무엇을 보는지가 다른 누군가의 데이터가 되는 일 없이.",
  "hero.ctaSource": "GitHub에서 보기",
  "hero.ctaExplore": "앱 살펴보기",
  "hero.trustAria": "Edendale의 원칙",
  "hero.trustLocal": "로컬 우선 라이브러리",
  "hero.trustAnalytics": "분석 없음",
  "hero.trustOpenSource": "오픈 소스",
  "hero.visualAria": "Edendale 개인 아카이브를 양식화한 화면",
  "hero.windowTitle": "아카이브",
  "hero.windowStatus": "로컬",
  "hero.windowResume": "이어서 보기",
  "hero.windowMoment": "일요일 저녁",
  "hero.windowNowPlaying": "내 라이브러리에서 재생 중",
  "hero.windowPickUp": "멈춘 그 지점에서 그대로 이어집니다.",
  "hero.privacyTitle": "라이브러리 밖으로 나가지 않습니다",
  "hero.privacyBody": "파일은 당신이 둔 곳에 그대로 있습니다.",

  "proof.aria": "지원하는 경험",
  "proof.archiveTitle": "하나의 아카이브",
  "proof.archiveBody": "영화, 시리즈, 시청 진행률",
  "proof.playbackTitle": "네이티브 재생",
  "proof.playbackBody": "플랫폼마다 직접 만들었습니다",
  "proof.cloudTitle": "나의 클라우드",
  "proof.cloudBody": "가능한 곳에서는 비공개 동기화",
  "proof.telemetryTitle": "텔레메트리 제로",
  "proof.telemetryBody": "프로필도, 광고도, 분석도 없음",

  "features.eyebrow": "더 나은 개인 아카이브",
  "features.title": "이미 가진 컬렉션을\n위해 만들었습니다.",
  "features.lede":
    "Edendale은 정리하고, 정보를 채우고, 기억하는 쓸모 있는 일을 대신합니다. 주도권은 그대로 당신에게.",
  "features.libraryTitle": "내 파일로 라이브러리 만들기",
  "features.libraryBody":
    "폴더만 고르면 Edendale이 영화와 에피소드를 기기 안에서 정리하고, 필요한 정보를 백그라운드에서 채웁니다.",
  "features.progressTitle": "모든 이야기를 기억합니다",
  "features.progressBody":
    "멈춘 곳부터 이어 보고, 내 기기들에 걸쳐 개인 시청 기록을 유지합니다.",
  "features.privacyTitle": "바탕부터 사적으로",
  "features.privacyBody":
    "팔아넘길 계정도, 시청 프로필도, 무엇을 보는지 지켜보는 분석도 없습니다.",

  "sources.eyebrow": "나의 소스",
  "sources.title": "어디에 있든\n그대로 재생하세요.",
  "sources.lede":
    "이 기기의 폴더, 집에 있는 서버, 클라우드 저장소를 연결하세요. Edendale은 목록을 읽고, 기기에서 파일을 정리한 뒤, 재생하는 부분만 소스에서 화면으로 바로 스트리밍합니다.",
  "sources.deviceTitle": "이 기기",
  "sources.deviceBody": "이미 쓰고 있는 폴더와 드라이브를 복사하지 않고 그 자리에서 읽습니다.",
  "sources.deviceFolders": "폴더",
  "sources.deviceDrives": "외장 드라이브",
  "sources.networkTitle": "내 네트워크",
  "sources.networkBody": "집에 있는 서버와 NAS 공유 폴더. 로그인 정보는 기기의 보호된 저장소에 보관됩니다.",
  "sources.cloudTitle": "클라우드",
  "sources.cloudBody":
    "클라우드 저장소는 서비스 자체 페이지에서 로그인하므로 Edendale은 비밀번호를 볼 수 없습니다.",
  "sources.accessTitle": "읽기 전용, 재생을 위해서만",
  "sources.accessBody":
    "Edendale은 Google Drive, OneDrive, Dropbox에 읽기 전용 접근만 요청합니다. 연결된 계정을 표시하고, 선택한 폴더의 목록을 보여 주고, 재생하는 동영상을 스트리밍하는 데에만 씁니다. 그곳의 어떤 것도 바꾸거나 지울 수 없으며, Edendale 서버를 거치는 것도 없습니다.",
  "sources.accessLink": "Edendale의 데이터 처리 방식",
  "sources.availability": "사용할 수 있는 서비스는 플랫폼마다 다릅니다.",

  "player.eyebrow": "플레이어",
  "player.title": "모든 디테일을\n내 취향대로.",
  "player.lede":
    "Edendale 플레이어는 홈 시네마에 어울리는 조작을 갖추고 있으며, 온라인 기능은 당신이 원할 때까지 기다립니다.",
  "player.skipTitle": "건너뛰기 버튼",
  "player.skipBody":
    "TheIntroDB 커뮤니티의 타임스탬프로 인트로, 요약, 크레딧을 건너뛰는 버튼을 보여 줍니다. 켜기 전까지는 꺼져 있고, 스스로 건너뛰는 일은 없습니다.",
  "player.nextTitle": "다음 에피소드",
  "player.nextBody":
    "에피소드는 가지고 있는 다음 화로 이어지고, ‘시청 계속하기’는 마지막으로 다 본 화의 다음을 기억합니다.",
  "player.soundTitle": "사운드",
  "player.soundBody":
    "영화, 음악, 대화, 늦은 밤을 위한 이퀄라이저 프로필과 소리가 작은 믹스를 위한 오디오 부스터를 갖췄습니다.",
  "player.pictureTitle": "화면",
  "player.pictureBody":
    "밝기, 대비, 색상을 세밀하게 조정하고, 지원되는 하드웨어에서는 GPU 업스케일링과 선명화를 사용합니다.",
  "player.subtitlesTitle": "자막",
  "player.subtitlesBody": "글꼴, 색상, 배경을 고르고, 파일에 원하는 언어가 없으면 온라인에서 찾아보세요.",
  "player.controlsTitle": "조작",
  "player.controlsBody":
    "건너뛰는 시간과 길게 누를 때의 재생 속도를 터치, 키보드, 마우스, 리모컨에 맞게 설정하세요.",

  "platforms.eyebrow": "중요한 곳은 네이티브로",
  "platforms.title": "어느 화면에서나\n제자리처럼.",
  "platforms.lede":
    "모든 Edendale 앱은 해당 플랫폼의 언어와 인터페이스 툴킷으로 만들어집니다. 익숙한 조작, 세심한 성능, 공용 웹 껍데기는 없습니다.",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "스마트폰 · 태블릿 · 큰 화면",
  "platforms.windowsDevices": "군더더기 없는 데스크톱 아카이브",

  "closing.eyebrow": "객석에 불이 들어옵니다",
  "closing.title": "내 컬렉션을\n다시 내 것처럼.",
  "closing.body":
    "Edendale은 무료 오픈 소스이며 활발히 개발 중입니다. 프로젝트를 팔로우하고 다음에 올 것을 함께 만들어 주세요.",
  "closing.cta": "개발 팔로우하기",

  "privacy.eyebrow": "개인정보",
  "privacy.title": "개인정보 처리방침",
  "privacy.lede":
    "Edendale은 라이브러리와 시청 기록, 파일이 계속 당신의 관리 아래 있도록 만들어졌습니다. 이 페이지는 앱과 이 웹사이트가 무엇을 다루고 무엇에는 절대 손대지 않는지 정확히 설명합니다.",
  "privacy.updated": "{date} 시행",
  "privacy.summaryTitle": "요약",
  "privacy.tocTitle": "이 페이지의 내용",
  "privacy.translationNote":
    "이 번역은 편의를 위해 제공됩니다. 영어 버전과 내용이 다를 경우 영어 버전이 우선합니다.",
  "privacy.readEnglish": "영어 버전 읽기",

  "link.eyebrow": "앱 링크",
  "link.heading": "Edendale에서 계속하기",
  "link.headingNotFound": "이 링크는 Edendale에서 열립니다",
  "link.body":
    "이 링크는 Edendale 앱의 링크입니다. 자동으로 열리지 않았다면 아래 버튼을 사용하거나 프로젝트 사이트로 돌아가세요.",
  "link.open": "Edendale에서 열기",
  "link.visit": "Edendale 둘러보기",
  "link.unavailable":
    "Edendale이 설치되어 있지 않거나 이 기기에서는 이 링크를 사용할 수 없습니다.",
  "link.explicit": "‘Edendale에서 열기’를 선택해야만 앱이 열립니다.",
};

const zhHans: Dictionary = {
  "meta.home.title": "Edendale — 你的片库，你的观影记录",
  "meta.home.description":
    "一款自由开源的视频播放器与个人观影记录工具，为 Apple 各平台、Android 和 Windows 分别原生打造。",
  "meta.link.title": "在 Edendale 中打开",
  "meta.link.description": "在 Edendale 应用中继续打开此链接。",
  "meta.notFound.description":
    "在 Edendale 应用中继续打开此链接，或返回项目网站。",
  "meta.privacy.title": "隐私政策 — Edendale",
  "meta.privacy.description":
    "Edendale 如何处理你的数据：没有账号，没有 Edendale 服务器，没有分析统计，对你关联的存储只有只读访问权限。",
  "meta.socialAlt": "Edendale — 属于你自己的私人影库。",

  "chrome.skipToContent": "跳到主要内容",
  "chrome.homeAria": "Edendale 首页",
  "chrome.brandTagline": "私人影院",
  "chrome.navAria": "主导航",
  "chrome.navFeatures": "功能",
  "chrome.navPlatforms": "平台",
  "chrome.navPrivacy": "隐私",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "你的故事，始终属于你。",
  "chrome.footerNote": "自由开源 · 无分析统计 · 用心打造",
  "chrome.footerPrivacy": "隐私政策",
  "chrome.footerSource": "GitHub 源码",

  "language.label": "语言",
  "language.aria": "选择语言",

  "hero.eyebrow": "从设计之初就保护隐私",
  "hero.title": "你的片库。\n你的观影记录。\n都属于你。",
  "hero.lede":
    "Edendale 把你已经拥有的电影和剧集变成一座漂亮的私人影库，而不会把你的观看习惯变成别人的数据。",
  "hero.ctaSource": "在 GitHub 上查看",
  "hero.ctaExplore": "了解各平台应用",
  "hero.trustAria": "Edendale 的原则",
  "hero.trustLocal": "本地优先的片库",
  "hero.trustAnalytics": "无分析统计",
  "hero.trustOpenSource": "开源",
  "hero.visualAria": "Edendale 个人影库的风格化示意画面",
  "hero.windowTitle": "影库",
  "hero.windowStatus": "本地",
  "hero.windowResume": "继续观看",
  "hero.windowMoment": "周日傍晚",
  "hero.windowNowPlaying": "正在播放你片库中的内容",
  "hero.windowPickUp": "从上次停下的地方继续。",
  "hero.privacyTitle": "没有任何内容离开你的片库",
  "hero.privacyBody": "文件始终留在你存放它们的地方。",

  "proof.aria": "支持的体验",
  "proof.archiveTitle": "一座影库",
  "proof.archiveBody": "电影、剧集与观看进度",
  "proof.playbackTitle": "原生播放",
  "proof.playbackBody": "为每个平台分别打造",
  "proof.cloudTitle": "你自己的云",
  "proof.cloudBody": "在可用之处进行私密同步",
  "proof.telemetryTitle": "零遥测",
  "proof.telemetryBody": "没有画像、广告或分析统计",

  "features.eyebrow": "更好的私人影库",
  "features.title": "为你已有的\n收藏而生。",
  "features.lede":
    "Edendale 负责那些有用的事——整理、补全、记住——而主动权始终在你手里。",
  "features.libraryTitle": "用你的文件建立片库",
  "features.libraryBody":
    "选好文件夹，Edendale 会在本地整理电影和剧集，再在后台补上有用的信息。",
  "features.progressTitle": "记住每一个故事",
  "features.progressBody":
    "从停下的地方继续，并在你自己的设备之间保留私人观影记录。",
  "features.privacyTitle": "隐私是地基",
  "features.privacyBody":
    "没有可供出售的账号，没有观看画像，也没有分析工具盯着你在看什么。",

  "sources.eyebrow": "你的来源",
  "sources.title": "无论存在哪里，\n都能直接播放。",
  "sources.lede":
    "关联这台设备上的文件夹、家里的服务器或云端存储。Edendale 读取目录，在你的设备上整理文件，只把你正在播放的内容从来源直接串流到屏幕上。",
  "sources.deviceTitle": "这台设备",
  "sources.deviceBody": "你正在使用的文件夹和硬盘，原地读取，从不复制。",
  "sources.deviceFolders": "文件夹",
  "sources.deviceDrives": "外接硬盘",
  "sources.networkTitle": "你的网络",
  "sources.networkBody": "家中的服务器和 NAS 共享，登录信息保存在设备受保护的存储中。",
  "sources.cloudTitle": "云端",
  "sources.cloudBody": "云端存储在服务商自己的页面上登录，因此 Edendale 永远看不到你的密码。",
  "sources.accessTitle": "只读，只为播放",
  "sources.accessBody":
    "Edendale 只向 Google Drive、OneDrive 和 Dropbox 申请只读访问权限：用来显示已关联的账号、列出你选择的文件夹，以及串流你播放的视频。它无法更改或删除其中的任何内容，也没有任何数据经过 Edendale 的服务器。",
  "sources.accessLink": "Edendale 如何处理你的数据",
  "sources.availability": "可用的服务因平台而异。",

  "player.eyebrow": "播放器",
  "player.title": "每个细节，\n都由你来调。",
  "player.lede": "Edendale 播放器提供家庭影院应有的各种控制，而每一项联网功能都会等你开口才启用。",
  "player.skipTitle": "跳过按钮",
  "player.skipBody":
    "根据 TheIntroDB 社区提供的时间点，显示跳过片头、前情提要和片尾的按钮。在你开启之前保持关闭，也从不自行跳过。",
  "player.nextTitle": "即将播放",
  "player.nextBody": "剧集会接着播放你拥有的下一集，「继续观看」会记住你看完的最后一集之后是哪一集。",
  "player.soundTitle": "声音",
  "player.soundBody": "为电影、音乐、对白和深夜准备的均衡器预设，另有音量增强，适合音量偏小的混音。",
  "player.pictureTitle": "画面",
  "player.pictureBody": "精细调整亮度、对比度和色彩；在支持的硬件上，还能用 GPU 放大和锐化画面。",
  "player.subtitlesTitle": "字幕",
  "player.subtitlesBody": "选择字体、颜色和背景；文件里没有你的语言时，也可以在线搜索。",
  "player.controlsTitle": "操控",
  "player.controlsBody": "按触控、键盘、鼠标或遥控器，设定每次跳转的秒数和长按时的播放速度。",

  "platforms.eyebrow": "在要紧之处保持原生",
  "platforms.title": "在每块屏幕上\n都自在如家。",
  "platforms.lede":
    "每个 Edendale 应用都用所在平台的语言和界面工具包编写。熟悉的操作、用心的性能，没有共用的网页外壳。",
  "platforms.appleDevices": "iPhone · iPad · Mac · Vision Pro · Apple TV",
  "platforms.androidDevices": "手机 · 平板 · 大屏设备",
  "platforms.windowsDevices": "专注的桌面影库",

  "closing.eyebrow": "灯光渐渐亮起",
  "closing.title": "让你的收藏\n重新像你自己的。",
  "closing.body":
    "Edendale 自由、开源，并在持续开发中。关注这个项目，一起决定接下来的方向。",
  "closing.cta": "关注开发进展",

  "privacy.eyebrow": "隐私",
  "privacy.title": "隐私政策",
  "privacy.lede":
    "Edendale 的设计让你的片库、观影记录和文件始终由你掌控。本页会具体说明这些应用和本网站会处理什么，以及绝不会碰什么。",
  "privacy.updated": "自{date}起生效",
  "privacy.summaryTitle": "要点",
  "privacy.tocTitle": "本页内容",
  "privacy.translationNote": "本译文仅为方便阅读而提供。如与英文版本有出入，以英文版本为准。",
  "privacy.readEnglish": "阅读英文版本",

  "link.eyebrow": "应用链接",
  "link.heading": "在 Edendale 中继续",
  "link.headingNotFound": "此链接将在 Edendale 中打开",
  "link.body":
    "此链接属于 Edendale 应用。如果没有自动打开，请使用下方按钮，或返回项目网站。",
  "link.open": "在 Edendale 中打开",
  "link.visit": "访问 Edendale",
  "link.unavailable": "尚未安装 Edendale，或此链接在当前设备上不可用。",
  "link.explicit": "只有当你选择「在 Edendale 中打开」时，应用才会启动。",
};

export const dictionaries: Readonly<Record<LocalePath, Dictionary>> = {
  en,
  es,
  fr,
  de,
  "pt-br": ptBR,
  ja,
  ko,
  "zh-hans": zhHans,
};
