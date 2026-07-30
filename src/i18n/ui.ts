import type { LocalePath } from "./locales.ts";

/**
 * English is the source text. Every other dictionary is typed as a complete
 * `Dictionary`, so `npm run check` fails on a missing or misspelled key rather
 * than shipping an untranslated string.
 *
 * A `\n` inside a heading is an authored line break: `SiteLayout`'s `lines()`
 * helper turns it into `<br>`, which lets each language break its own headline
 * where the words allow.
 */
const en = {
  "meta.home.title": "Edendale — Your library, your history",
  "meta.home.description":
    "A free, open-source video player and personal watch tracker, built natively for Apple platforms, Android, and Windows.",
  "meta.link.title": "Open in Edendale",
  "meta.link.description": "Continue this link in the Edendale app.",
  "meta.notFound.description":
    "Continue this link in the Edendale app, or return to the Edendale project site.",
  "meta.socialAlt": "Edendale — your private cinematic archive.",

  "chrome.skipToContent": "Skip to content",
  "chrome.homeAria": "Edendale home",
  "chrome.brandTagline": "Personal cinema",
  "chrome.navAria": "Primary navigation",
  "chrome.navFeatures": "Features",
  "chrome.navPlatforms": "Platforms",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Your stories stay yours.",
  "chrome.footerNote": "Free and open source · No analytics · Built with care",
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
  "hero.privacyBody": "Your files stay on your devices.",

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
  "meta.socialAlt": "Edendale: tu archivo cinematográfico privado.",

  "chrome.skipToContent": "Saltar al contenido",
  "chrome.homeAria": "Inicio de Edendale",
  "chrome.brandTagline": "Cine personal",
  "chrome.navAria": "Navegación principal",
  "chrome.navFeatures": "Funciones",
  "chrome.navPlatforms": "Plataformas",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Tus historias siguen siendo tuyas.",
  "chrome.footerNote":
    "Libre y de código abierto · Sin analíticas · Hecho con cuidado",
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
  "hero.privacyBody": "Tus archivos se quedan en tus dispositivos.",

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
  "meta.socialAlt": "Edendale — votre archive cinématographique privée.",

  "chrome.skipToContent": "Aller au contenu",
  "chrome.homeAria": "Accueil Edendale",
  "chrome.brandTagline": "Cinéma personnel",
  "chrome.navAria": "Navigation principale",
  "chrome.navFeatures": "Fonctionnalités",
  "chrome.navPlatforms": "Plateformes",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Vos histoires restent les vôtres.",
  "chrome.footerNote":
    "Libre et open source · Aucune analyse · Conçu avec soin",
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
  "hero.privacyBody": "Vos fichiers restent sur vos appareils.",

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
  "meta.socialAlt": "Edendale – dein privates Filmarchiv.",

  "chrome.skipToContent": "Zum Inhalt springen",
  "chrome.homeAria": "Edendale-Startseite",
  "chrome.brandTagline": "Persönliches Kino",
  "chrome.navAria": "Hauptnavigation",
  "chrome.navFeatures": "Funktionen",
  "chrome.navPlatforms": "Plattformen",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Deine Geschichten bleiben deine.",
  "chrome.footerNote":
    "Frei und quelloffen · Keine Analysen · Mit Sorgfalt gebaut",
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
  "hero.privacyBody": "Deine Dateien bleiben auf deinen Geräten.",

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
  "meta.socialAlt": "Edendale — seu acervo de cinema particular.",

  "chrome.skipToContent": "Ir para o conteúdo",
  "chrome.homeAria": "Início do Edendale",
  "chrome.brandTagline": "Cinema pessoal",
  "chrome.navAria": "Navegação principal",
  "chrome.navFeatures": "Recursos",
  "chrome.navPlatforms": "Plataformas",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "Suas histórias continuam suas.",
  "chrome.footerNote":
    "Livre e de código aberto · Sem analytics · Feito com cuidado",
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
  "hero.privacyBody": "Seus arquivos ficam nos seus dispositivos.",

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
  "meta.socialAlt": "Edendale — あなただけのプライベートな映画アーカイブ。",

  "chrome.skipToContent": "本文へスキップ",
  "chrome.homeAria": "Edendale ホーム",
  "chrome.brandTagline": "パーソナルシネマ",
  "chrome.navAria": "メインナビゲーション",
  "chrome.navFeatures": "機能",
  "chrome.navPlatforms": "対応プラットフォーム",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "あなたの物語は、あなたのもの。",
  "chrome.footerNote": "無料・オープンソース · 解析なし · ていねいに作りました",
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
  "hero.privacyBody": "ファイルはあなたのデバイスに残ります。",

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
  "meta.socialAlt": "Edendale — 나만의 사적인 영화 아카이브.",

  "chrome.skipToContent": "본문으로 건너뛰기",
  "chrome.homeAria": "Edendale 홈",
  "chrome.brandTagline": "개인 영화관",
  "chrome.navAria": "기본 탐색",
  "chrome.navFeatures": "기능",
  "chrome.navPlatforms": "플랫폼",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "당신의 이야기는 당신의 것으로.",
  "chrome.footerNote": "무료 오픈 소스 · 분석 없음 · 정성껏 만들었습니다",
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
  "hero.privacyBody": "파일은 내 기기에 그대로 남습니다.",

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
  "meta.socialAlt": "Edendale — 属于你自己的私人影库。",

  "chrome.skipToContent": "跳到主要内容",
  "chrome.homeAria": "Edendale 首页",
  "chrome.brandTagline": "私人影院",
  "chrome.navAria": "主导航",
  "chrome.navFeatures": "功能",
  "chrome.navPlatforms": "平台",
  "chrome.navGithub": "GitHub",
  "chrome.footerTagline": "你的故事，始终属于你。",
  "chrome.footerNote": "自由开源 · 无分析统计 · 用心打造",
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
  "hero.privacyBody": "文件始终留在你的设备上。",

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
