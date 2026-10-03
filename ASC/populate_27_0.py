#!/usr/bin/env python3
"""
Populate version 27.0 fields across all ASC platform metadata files and config.json.
"""

import json
import os
import glob

# Version 27.0 release notes across all 18 supported locales and 4 platforms
WHATS_NEW = {
    "en-US": {
        "iOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equalizer: Choose from Flat, Movies, Music, Dialogue, and Night Mode EQ presets, plus an Audio Booster (+10 dB) for clear sound at any volume.\n"
            "• Surround & Spatial Audio: Multi-channel surround playback (5.1 and 7.1) and system passthrough for Dolby Digital Plus (E-AC-3) with spatial object audio.\n"
            "• Skip Prompts: Jump past intros, recaps, and credits with community timestamps from TheIntroDB.\n"
            "• Up Next Card: Seamlessly queue and play the next episode during the final 30 seconds of a show.\n"
            "• Subtitle Caching & Customization: Downloaded subtitles are saved per video for instant reuse without re-downloading. Customize subtitle font, colors, and background opacity.\n"
            "• High-Performance Network Streaming: Enhanced SMB and SFTP playback with buffered streaming and automatic reconnect when switching networks.\n"
            "• Customizable App Controls: Choose skip jump lengths (10s, 15s, 30s) and press-and-hold playback speeds (0.25× to 3.0×).\n"
            "• Picture in Picture & Background Resume: Smooth background transitions and improved PiP playback."
        ),
        "macOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equalizer: Flat, Movies, Music, Dialogue, and Night Mode presets, plus an Audio Booster (+10 dB gain).\n"
            "• Surround & Spatial Audio: Multi-channel surround sound (5.1 and 7.1) and system passthrough for Dolby Digital Plus (E-AC-3).\n"
            "• Skip Prompts: Community timestamps from TheIntroDB allow one-key (S) skipping of intros, recaps, and end credits.\n"
            "• Modern Desktop Navigation: Split view sidebar with quick access to sections, plus dockable Now Playing sidebars for playlist and adjustments.\n"
            "• Up Next & Episode Scrubber: Up Next card for next-episode progression and interactive season scrubber rules.\n"
            "• Subtitle Caching & Styling: Downloaded subtitles stay with each video for instant reuse; customize font, color, and background opacity.\n"
            "• High-Performance Network Streaming: Enhanced SMB and SFTP playback with buffered streaming and connection recovery.\n"
            "• Metal Video Rendering: Hardware-accelerated decoding, Metal upscaling, and GPU motion smoothing.\n"
            "• App Controls & Shortcuts: Custom skip intervals, press-and-hold speeds, and new menu shortcuts (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equalizer: Fine-tune audio on the big screen with Flat, Movies, Music, Dialogue, and Night Mode presets, plus an Audio Booster (+10 dB).\n"
            "• Surround Sound: Full multi-channel audio (5.1 and 7.1) and passthrough for Dolby Digital Plus (E-AC-3) to your AV receiver or soundbar.\n"
            "• Skip Prompts: Focus and skip intros, recaps, and credits using community timestamps from TheIntroDB.\n"
            "• Up Next Episode Card: An Up Next prompt appears in the final 30 seconds for quick progression to the next episode.\n"
            "• Subtitle Caching & Remote-Friendly Styling: Downloaded subtitles are kept locally per video; remote-operated controls for subtitle text size, color, and background opacity.\n"
            "• Network Streaming: Stream smoothly from your home network shares (SMB and SFTP) with robust buffering.\n"
            "• Remote-Optimized Controls: Customizable skip durations and hold-to-seek speeds designed for the Siri Remote."
        ),
        "visionOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equalizer: Tailor playback with Flat, Movies, Music, Dialogue, and Night Mode presets, plus Audio Booster (+10 dB).\n"
            "• Surround & Spatial Audio: Multi-channel surround sound (5.1 and 7.1) and spatial object audio passthrough for E-AC-3.\n"
            "• Skip Prompts: Seamlessly skip intros, recaps, and credits with community timestamps from TheIntroDB.\n"
            "• Up Next Card: Effortlessly jump to the next episode during the final 30 seconds.\n"
            "• Subtitle Caching & Styling: Downloaded subtitles are remembered per video; personalize font, colors, and background opacity.\n"
            "• High-Performance Streaming: Stream video over SMB and SFTP with buffered playback and automatic reconnect.\n"
            "• Spatial Player Controls: Native pinch-and-hold playback speed controls and customizable skip intervals."
        ),
    },
    "en-GB": {
        "iOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equaliser: Choose from Flat, Films, Music, Dialogue, and Night Mode EQ presets, plus an Audio Booster (+10 dB) for clear sound at any volume.\n"
            "• Surround & Spatial Audio: Multi-channel surround playback (5.1 and 7.1) and system passthrough for Dolby Digital Plus (E-AC-3) with spatial object audio.\n"
            "• Skip Prompts: Jump past intros, recaps, and credits with community timestamps from TheIntroDB.\n"
            "• Up Next Card: Seamlessly queue and play the next episode during the final 30 seconds of a show.\n"
            "• Subtitle Caching & Customisation: Downloaded subtitles are saved per video for instant reuse without re-downloading. Customise subtitle font, colours, and background opacity.\n"
            "• High-Performance Network Streaming: Enhanced SMB and SFTP playback with buffered streaming and automatic reconnect when switching networks.\n"
            "• Customisable App Controls: Choose skip jump lengths (10s, 15s, 30s) and press-and-hold playback speeds (0.25× to 3.0×).\n"
            "• Picture in Picture & Background Resume: Smooth background transitions and improved PiP playback."
        ),
        "macOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equaliser: Flat, Films, Music, Dialogue, and Night Mode presets, plus an Audio Booster (+10 dB gain).\n"
            "• Surround & Spatial Audio: Multi-channel surround sound (5.1 and 7.1) and system passthrough for Dolby Digital Plus (E-AC-3).\n"
            "• Skip Prompts: Community timestamps from TheIntroDB allow one-key (S) skipping of intros, recaps, and end credits.\n"
            "• Modern Desktop Navigation: Split view sidebar with quick access to sections, plus dockable Now Playing sidebars for playlist and adjustments.\n"
            "• Up Next & Episode Scrubber: Up Next card for next-episode progression and interactive season scrubber rules.\n"
            "• Subtitle Caching & Styling: Downloaded subtitles stay with each video for instant reuse; customise font, colour, and background opacity.\n"
            "• High-Performance Network Streaming: Enhanced SMB and SFTP playback with buffered streaming and connection recovery.\n"
            "• Metal Video Rendering: Hardware-accelerated decoding, Metal upscaling, and GPU motion smoothing.\n"
            "• App Controls & Shortcuts: Custom skip intervals, press-and-hold speeds, and new menu shortcuts (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equaliser: Fine-tune audio on the big screen with Flat, Films, Music, Dialogue, and Night Mode presets, plus an Audio Booster (+10 dB).\n"
            "• Surround Sound: Full multi-channel audio (5.1 and 7.1) and passthrough for Dolby Digital Plus (E-AC-3) to your AV receiver or soundbar.\n"
            "• Skip Prompts: Focus and skip intros, recaps, and credits using community timestamps from TheIntroDB.\n"
            "• Up Next Episode Card: An Up Next prompt appears in the final 30 seconds for quick progression to the next episode.\n"
            "• Subtitle Caching & Remote-Friendly Styling: Downloaded subtitles are kept locally per video; remote-operated controls for subtitle text size, colour, and background opacity.\n"
            "• Network Streaming: Stream smoothly from your home network shares (SMB and SFTP) with robust buffering.\n"
            "• Remote-Optimised Controls: Customisable skip durations and hold-to-seek speeds designed for the Siri Remote."
        ),
        "visionOS": (
            "What's New in Version 27.0:\n\n"
            "• Audio Enhancement & Equaliser: Tailor playback with Flat, Films, Music, Dialogue, and Night Mode presets, plus Audio Booster (+10 dB).\n"
            "• Surround & Spatial Audio: Multi-channel surround sound (5.1 and 7.1) and spatial object audio passthrough for E-AC-3.\n"
            "• Skip Prompts: Seamlessly skip intros, recaps, and credits with community timestamps from TheIntroDB.\n"
            "• Up Next Card: Effortlessly jump to the next episode during the final 30 seconds.\n"
            "• Subtitle Caching & Styling: Downloaded subtitles are remembered per video; personalise font, colours, and background opacity.\n"
            "• High-Performance Streaming: Stream video over SMB and SFTP with buffered playback and automatic reconnect.\n"
            "• Spatial Player Controls: Native pinch-and-hold playback speed controls and customisable skip intervals."
        ),
    },
    "de-DE": {
        "iOS": (
            "Neu in Version 27.0:\n\n"
            "• Audio-Verbesserung & Equalizer: Wähle aus EQ-Profilen für Flach, Filme, Musik, Dialog und Nachtmodus, plus ein Audio-Booster (+10 dB) für klaren Ton bei jeder Lautstärke.\n"
            "• Surround- & Spatial-Audio: Mehrkanal-Surround-Wiedergabe (5.1 und 7.1) und System-Passthrough für Dolby Digital Plus (E-AC-3) mit 3D-Audio.\n"
            "• Segmente überspringen: Überspringe Intros, Rückblenden und Abspänne mit Community-Zeitstempeln von TheIntroDB.\n"
            "• Nächste Folge (Up Next): Starte nahtlos die nächste Folge während der letzten 30 Sekunden einer Episode.\n"
            "• Untertitel-Cache & Anpassung: Heruntergeladene Untertitel werden pro Video für sofortige Wiederverwendung gespeichert. Schriftart, Farben und Hintergrundtransparenz anpassbar.\n"
            "• Leistungsstarkes Netzwerk-Streaming: Verbessertes SMB- und SFTP-Streaming mit gepufferter Wiedergabe und automatischer Wiederverbindung.\n"
            "• Anpassbare App-Steuerung: Wähle Sprungweiten (10s, 15s, 30s) und Wiedergabegeschwindigkeiten bei Gedrückthalten (0,25× bis 3,0×).\n"
            "• Bild-in-Bild & Hintergrund-Wiedergabe: Reibungsloser Wechsel in den Hintergrund und verbesserte PiP-Wiedergabe."
        ),
        "macOS": (
            "Neu in Version 27.0:\n\n"
            "• Audio-Verbesserung & Equalizer: Profile für Flach, Filme, Musik, Dialog und Nachtmodus sowie ein Audio-Booster (+10 dB).\n"
            "• Surround- & Spatial-Audio: Mehrkanal-Surround (5.1 und 7.1) und Passthrough für Dolby Digital Plus (E-AC-3).\n"
            "• Segmente überspringen: Ein Tastendruck (S) überspringt Intros, Rückblenden und Abspänne via TheIntroDB.\n"
            "• Moderne Desktop-Navigation: Split-View-Seitenleiste mit schnellem Bereichszugriff sowie andockbare Seitenleisten im Player für Playlist und Einstellungen.\n"
            "• Nächste Folge & Episoden-Scrubber: Up-Next-Karte zur Folgenfortsetzung und interaktive Staffel-Scrubber.\n"
            "• Untertitel-Cache & Gestaltung: Untertitel bleiben beim jeweiligen Video gespeichert; Schriftart, Farbe und Hintergrunddeckkraft anpassbar.\n"
            "• Netzwerk-Streaming: Verbessertes SMB- und SFTP-Streaming mit Pufferung und Verbindungsreparatur.\n"
            "• Metal-Videorendering: Hardwarebeschleunigte Dekodierung, Metal-Upscaling und GPU-Bewegungsglättung.\n"
            "• Steuerung & Kurzbefehle: Einstellbare Sprungweiten, Tastenkombinationen (⌘B, ⌘N, ⌥⌘N, ⌘R) und Haltetasten-Geschwindigkeit."
        ),
        "tvOS": (
            "Neu in Version 27.0:\n\n"
            "• Audio-Verbesserung & Equalizer: Feinabstimmung für das Wohnzimmer mit Profilen für Flach, Filme, Musik, Dialog und Nachtmodus sowie Audio-Booster (+10 dB).\n"
            "• Surround-Sound: Mehrkanalton (5.1 und 7.1) und Passthrough für Dolby Digital Plus (E-AC-3) an deinen AV-Receiver oder deine Soundbar.\n"
            "• Segmente überspringen: Fokussiere und überspringe Intros, Rückblenden und Abspänne mit TheIntroDB.\n"
            "• Nächste Folge: Eine Up-Next-Einblendung erscheint in den letzten 30 Sekunden zum schnellen Start der nächsten Folge.\n"
            "• Untertitel-Cache & Fernbedienungs-Bedienung: Untertitel werden pro Video gespeichert; einfache Einstellung von Größe, Farbe und Deckkraft mit der Siri Remote.\n"
            "• Netzwerk-Streaming: Flüssiges Abspielen von Netzwerkfreigaben (SMB und SFTP) mit zuverlässiger Pufferung.\n"
            "• Optimierte Steuerung: Einstellbare Sprungintervalle und Suchgeschwindigkeiten für die Siri Remote."
        ),
        "visionOS": (
            "Neu in Version 27.0:\n\n"
            "• Audio-Verbesserung & Equalizer: Klanganpassung mit Profilen für Flach, Filme, Musik, Dialog und Nachtmodus sowie Audio-Booster (+10 dB).\n"
            "• Surround- & Spatial-Audio: Mehrkanal-Surround (5.1 und 7.1) und räumlicher E-AC-3 Passthrough.\n"
            "• Segmente überspringen: Intros, Rückblenden und Abspänne mühelos mit TheIntroDB überspringen.\n"
            "• Nächste Folge: Während der letzten 30 Sekunden direkt zur nächsten Episode wechseln.\n"
            "• Untertitel-Cache & Design: Heruntergeladene Untertitel werden pro Video gemerkt; Schriftart, Farben und Deckkraft personalisierbar.\n"
            "• Netzwerk-Streaming: Videowiedergabe über SMB und SFTP mit Pufferung und automatischer Wiederverbindung.\n"
            "• Räumliche Steuerung: Native Steuerung der Wiedergabegeschwindigkeit durch Zusammendrücken und Halten sowie anpassbare Sprungweiten."
        ),
    },
    "fr-FR": {
        "iOS": (
            "Nouveautés de la version 27.0 :\n\n"
            "• Amélioration audio et égaliseur : Choisissez parmi les préréglages Plat, Films, Musique, Dialogue et Mode nuit, plus un amplificateur audio (+10 dB) pour un son net à tout volume.\n"
            "• Son surround et audio spatial : Lecture surround multicanal (5.1 et 7.1) et passthrough système pour Dolby Digital Plus (E-AC-3) avec audio spatial.\n"
            "• Passer les intros et génériques : Sautez les intros, récapitulatifs et génériques grâce aux repères communautaires de TheIntroDB.\n"
            "• Carte Épisode suivant : Enchaînez facilement sur l'épisode suivant durant les 30 dernières secondes.\n"
            "• Cache et personnalisation des sous-titres : Les sous-titres téléchargés sont conservés par vidéo sans nouveau téléchargement. Personnalisez la police, les couleurs et l'opacité.\n"
            "• Diffusion réseau haute performance : Lecture optimisée via SMB et SFTP avec mise en mémoire tampon et reconnexion automatique.\n"
            "• Contrôles personnalisables : Choisissez la durée de saut (10s, 15s, 30s) et les vitesses lors du maintien appuyé (0,25× à 3,0×).\n"
            "• Image dans l'image et reprise en arrière-plan : Transitions fluides en arrière-plan et amélioration du mode PiP."
        ),
        "macOS": (
            "Nouveautés de la version 27.0 :\n\n"
            "• Amélioration audio et égaliseur : Préréglages Plat, Films, Musique, Dialogue et Mode nuit, avec amplificateur audio (+10 dB).\n"
            "• Son surround et spatial : Surround multicanal (5.1 et 7.1) et passthrough Dolby Digital Plus (E-AC-3).\n"
            "• Passer les intros et génériques : Une seule touche (S) suffit pour sauter les intros et génériques via TheIntroDB.\n"
            "• Navigation de bureau moderne : Barre latérale scindée pour un accès rapide aux sections, et panneaux latéraux intégrés au lecteur pour la playlist et les réglages.\n"
            "• Épisode suivant et contrôle temporel : Carte Épisode suivant et réglette interactive pour parcourir la saison.\n"
            "• Cache et style des sous-titres : Les sous-titres restent associés à chaque vidéo ; personnalisation de la police, de la couleur et de l'opacité.\n"
            "• Lecture réseau haute performance : Streaming SMB et SFTP amélioré avec tamponnage et rétablissement de connexion.\n"
            "• Rendu vidéo Metal : Décodage accéléré, mise à l'échelle Metal et lissage de mouvement sur le GPU.\n"
            "• Contrôles et raccourcis : Intervalles de saut personnalisés, vitesses d'appui long et nouveaux raccourcis clavier (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Nouveautés de la version 27.0 :\n\n"
            "• Amélioration audio et égaliseur : Ajustez le son sur grand écran avec les modes Plat, Films, Musique, Dialogue et Mode nuit, plus amplificateur audio (+10 dB).\n"
            "• Son surround : Audio multicanal complet (5.1 et 7.1) et passthrough Dolby Digital Plus (E-AC-3) vers votre ampli ou barre de son.\n"
            "• Passer les intros : Mettez en surbrillance et sautez les intros et génériques avec TheIntroDB.\n"
            "• Carte Épisode suivant : Une notification apparaît dans les 30 dernières secondes pour lancer rapidement la suite.\n"
            "• Cache et réglage des sous-titres : Les sous-titres sont conservés par vidéo ; réglage aisé de la taille, couleur et opacité avec la télécommande.\n"
            "• Streaming réseau : Lecture fluide depuis vos partages réseau (SMB et SFTP) avec mise en mémoire tampon robuste.\n"
            "• Contrôles optimisés pour télécommande : Durées de saut et vitesses de balayage configurables pour la Siri Remote."
        ),
        "visionOS": (
            "Nouveautés de la version 27.0 :\n\n"
            "• Amélioration audio et égaliseur : Personnalisez le son avec les modes Plat, Films, Musique, Dialogue et Mode nuit, plus amplificateur audio (+10 dB).\n"
            "• Son surround et spatial : Audio surround multicanal (5.1 et 7.1) et passthrough audio spatial E-AC-3.\n"
            "• Passer les intros : Sautez sans effort les intros et génériques grâce à TheIntroDB.\n"
            "• Carte Épisode suivant : Passez à l'épisode suivant en toute fluidité durant les 30 dernières secondes.\n"
            "• Cache et personnalisation des sous-titres : Les sous-titres téléchargés sont mémorisés par vidéo ; personnalisez police, couleur et opacité.\n"
            "• Streaming haute performance : Lecture vidéo via SMB et SFTP avec mémoire tampon et reconnexion automatique.\n"
            "• Contrôles spatiaux : Contrôle de vitesse naturel par pincement maintenu et intervalles de saut ajustables."
        ),
    },
    "es-ES": {
        "iOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Elige entre los ajustes Plana, Películas, Música, Diálogo y Modo nocturno, más un potenciador de audio (+10 dB) para un sonido nítido a cualquier volumen.\n"
            "• Sonido envolvente y espacial: Reproducción envolvente multicanal (5.1 y 7.1) y transferencia directa para Dolby Digital Plus (E-AC-3) con audio espacial.\n"
            "• Omitir intros y créditos: Salta intros, resúmenes y créditos con marcas de tiempo comunitarias de TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Reproduce sin interrupciones el siguiente capítulo durante los últimos 30 segundos.\n"
            "• Caché y aspecto de subtítulos: Los subtítulos descargados se guardan por vídeo para reutilizarlos al instante. Personaliza la fuente, colores y opacidad de fondo.\n"
            "• Transmisión en red de alto rendimiento: Reproducción mejorada por SMB y SFTP con búfer de datos y reconexión automática al cambiar de red.\n"
            "• Controles personalizables: Elige la duración del salto (10s, 15s, 30s) y la velocidad al mantener pulsado (0,25× a 3,0×).\n"
            "• Imagen dentro de imagen y segundo plano: Transiciones fluidas en segundo plano y mejoras en la reproducción PiP."
        ),
        "macOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Ajustes para Plana, Películas, Música, Diálogo y Modo nocturno, además de potenciador de audio (+10 dB).\n"
            "• Sonido envolvente y espacial: Envolvente multicanal (5.1 y 7.1) y transferencia directa de Dolby Digital Plus (E-AC-3).\n"
            "• Omitir intros y créditos: Salta intros y créditos con una sola tecla (S) mediante TheIntroDB.\n"
            "• Navegación de escritorio moderna: Barra lateral dividida con acceso rápido a secciones y paneles acoplables en el reproductor para listas y ajustes.\n"
            "• Siguiente episodio y deslizador de temporada: Tarjeta de siguiente capítulo y control interactivo para recorrer la temporada.\n"
            "• Caché y estilo de subtítulos: Los subtítulos quedan vinculados a cada vídeo; personaliza tipografía, color y opacidad.\n"
            "• Transmisión en red de alto rendimiento: Reproducción SMB y SFTP optimizada con búfer y recuperación de conexión.\n"
            "• Renderizado de vídeo Metal: Decodificación acelerada por hardware, escalado Metal y suavizado de movimiento por GPU.\n"
            "• Controles y atajos de teclado: Intervalos de salto a medida, velocidades al mantener pulsado y nuevos atajos (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Ajusta el sonido en la pantalla grande con los modos Plana, Películas, Música, Diálogo y Modo nocturno, más potenciador (+10 dB).\n"
            "• Sonido envolvente: Audio multicanal completo (5.1 y 7.1) y transferencia de Dolby Digital Plus (E-AC-3) a tu receptor o barra de sonido.\n"
            "• Omitir intros: Enfoca y salta intros, resúmenes y créditos gracias a TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Aviso en los últimos 30 segundos para pasar rápidamente al siguiente capítulo.\n"
            "• Caché y aspecto para mando a distancia: Subtítulos guardados por vídeo; controles cómodos para tamaño, color y opacidad con el mando.\n"
            "• Transmisión en red: Reproducción fluida desde tus carpetas compartidas (SMB y SFTP) con un búfer fiable.\n"
            "• Controles optimizados para el mando: Duración de salto y velocidad al mantener pulsado diseñadas para el Siri Remote."
        ),
        "visionOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Personaliza el sonido con los modos Plana, Películas, Música, Diálogo y Modo nocturno, más potenciador (+10 dB).\n"
            "• Sonido envolvente y espacial: Envolvente multicanal (5.1 y 7.1) y transferencia de audio espacial E-AC-3.\n"
            "• Omitir intros: Salta fácilmente intros, resúmenes y créditos con TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Salta al siguiente episodio con fluidez durante los últimos 30 segundos.\n"
            "• Caché y estilo de subtítulos: Los subtítulos descargados se recuerdan por vídeo; personaliza fuente, color y opacidad.\n"
            "• Transmisión de alto rendimiento: Vídeo fluido por SMB y SFTP con búfer y reconexión automática.\n"
            "• Controles espaciales: Ajuste de velocidad manteniendo el pellizco e intervalos de salto configurables."
        ),
    },
    "es-MX": {
        "iOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Elige entre Plano, Películas, Música, Diálogo y Modo nocturno, más un amplificador de audio (+10 dB) para sonido claro a cualquier volumen.\n"
            "• Audio envolvente y espacial: Reproducción envolvente multicanal (5.1 y 7.1) y transferencia directa para Dolby Digital Plus (E-AC-3) con audio espacial.\n"
            "• Saltar intros y créditos: Salta intros, resúmenes y créditos con marcas de tiempo comunitarias de TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Reproduce sin interrupciones el siguiente capítulo durante los últimos 30 segundos.\n"
            "• Caché y aspecto de subtítulos: Los subtítulos descargados se guardan por video para reutilizarlos al instante. Personaliza fuente, colores y opacidad de fondo.\n"
            "• Transmisión en red de alto rendimiento: Reproducción mejorada por SMB y SFTP con búfer de datos y reconexión automática al cambiar de red.\n"
            "• Controles personalizables: Elige la duración del salto (10s, 15s, 30s) y la velocidad al mantener presionado (0.25× a 3.0×).\n"
            "• Imagen en imagen y segundo plano: Transiciones fluidas en segundo plano y mejoras en la reproducción PiP."
        ),
        "macOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Ajustes para Plano, Películas, Música, Diálogo y Modo nocturno, además de amplificador de audio (+10 dB).\n"
            "• Audio envolvente y espacial: Envolvente multicanal (5.1 y 7.1) y transferencia directa de Dolby Digital Plus (E-AC-3).\n"
            "• Saltar intros y créditos: Salta intros y créditos con una sola tecla (S) mediante TheIntroDB.\n"
            "• Navegación de escritorio moderna: Barra lateral dividida para acceso rápido a secciones y paneles acoplables en el reproductor para listas y ajustes.\n"
            "• Siguiente episodio y deslizador de temporada: Tarjeta de siguiente capítulo y control interactivo para recorrer la temporada.\n"
            "• Caché y estilo de subtítulos: Los subtítulos quedan vinculados a cada video; personaliza tipografía, color y opacidad.\n"
            "• Transmisión en red de alto rendimiento: Reproducción SMB y SFTP optimizada con búfer y recuperación de conexión.\n"
            "• Renderizado de video Metal: Decodificación acelerada por hardware, escalado Metal y suavizado de movimiento por GPU.\n"
            "• Controles y atajos de teclado: Intervalos de salto a medida, velocidades al mantener presionado y nuevos atajos (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Ajusta el sonido en la pantalla grande con Plano, Películas, Música, Diálogo y Modo nocturno, más amplificador (+10 dB).\n"
            "• Sonido envolvente: Audio multicanal completo (5.1 y 7.1) y transferencia de Dolby Digital Plus (E-AC-3) a tu receptor o barra de sonido.\n"
            "• Saltar intros: Enfoca y salta intros, resúmenes y créditos gracias a TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Aviso en los últimos 30 segundos para pasar rápidamente al siguiente capítulo.\n"
            "• Caché y aspecto para control remoto: Subtítulos guardados por video; controles cómodos para tamaño, color y opacidad con el control.\n"
            "• Transmisión en red: Reproducción fluida desde tus carpetas compartidas (SMB y SFTP) con un búfer confiable.\n"
            "• Controles optimizados para el control: Duración de salto y velocidad al mantener presionado diseñadas para el Siri Remote."
        ),
        "visionOS": (
            "Novedades de la versión 27.0:\n\n"
            "• Mejora de audio y ecualizador: Personaliza el sonido con Plano, Películas, Música, Diálogo y Modo nocturno, más amplificador (+10 dB).\n"
            "• Audio envolvente y espacial: Envolvente multicanal (5.1 y 7.1) y transferencia de audio espacial E-AC-3.\n"
            "• Saltar intros: Salta fácilmente intros, resúmenes y créditos con TheIntroDB.\n"
            "• Tarjeta Siguiente episodio: Salta al siguiente episodio con fluidez durante los últimos 30 segundos.\n"
            "• Caché y estilo de subtítulos: Los subtítulos descargados se recuerdan por video; personaliza fuente, color y opacidad.\n"
            "• Transmisión de alto rendimiento: Video fluido por SMB y SFTP con búfer y reconexión automática.\n"
            "• Controles espaciales: Ajuste de velocidad manteniendo el pellizco e intervalos de salto configurables."
        ),
    },
    "it": {
        "iOS": (
            "Novità della versione 27.0:\n\n"
            "• Miglioramento audio ed equalizzatore: Scegli tra i profili Piatto, Film, Musica, Dialogo e Notte, più un Audio Booster (+10 dB) per un suono chiaro a qualsiasi volume.\n"
            "• Audio surround e spaziale: Riproduzione surround multicanale (5.1 e 7.1) e passthrough di sistema per Dolby Digital Plus (E-AC-3) con audio spaziale.\n"
            "• Salta intro e titoli di coda: Salta intro, riassunti e titoli di coda grazie ai timestamp della community di TheIntroDB.\n"
            "• Scheda Prossimo episodio: Avvia facilmente il prossimo episodio durante gli ultimi 30 secondi di visione.\n"
            "• Cache e personalizzazione dei sottotitoli: I sottotitoli scaricati vengono salvati per ciascun video senza doverli riscaricare. Personalizza font, colori e opacità dello sfondo.\n"
            "• Streaming di rete ad alte prestazioni: Riproduzione migliorata su SMB e SFTP con buffering fluido e riconnessione automatica al cambio di rete.\n"
            "• Controlli personalizzabili: Scegli la durata del salto (10s, 15s, 30s) e la velocità di riproduzione tenendo premuto (da 0,25× a 3,0×).\n"
            "• Picture in Picture e ripresa in background: Transizioni fluide in background e riproduzione PiP perfezionata."
        ),
        "macOS": (
            "Novità della versione 27.0:\n\n"
            "• Miglioramento audio ed equalizzatore: Profili Piatto, Film, Musica, Dialogo e Notte, oltre all'Audio Booster (+10 dB di guadagno).\n"
            "• Audio surround e spaziale: Audio surround multicanale (5.1 e 7.1) e passthrough per Dolby Digital Plus (E-AC-3).\n"
            "• Salta intro e titoli di coda: Salta intro e crediti con un solo tasto (S) tramite TheIntroDB.\n"
            "• Navigazione desktop moderna: Barra laterale con vista divisa per accedere rapidamente alle sezioni e pannelli agganciabili per playlist e regolazioni.\n"
            "• Prossimo episodio e scrubber di stagione: Scheda Prossimo episodio per avanzare nella serie e scrubber interattivo per la stagione.\n"
            "• Cache e stile dei sottotitoli: I sottotitoli restano associati a ogni video; personalizza carattere, colore e opacità dello sfondo.\n"
            "• Streaming di rete ad alte prestazioni: Riproduzione SMB e SFTP ottimizzata con buffering e ripristino della connessione.\n"
            "• Rendering video Metal: Decodifica con accelerazione hardware, upscaling Metal e fluidità di movimento via GPU.\n"
            "• Controlli e scorciatoie: Intervalli di salto personalizzati, velocità con pressione prolungata e nuove scorciatoie da tastiera (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Novità della versione 27.0:\n\n"
            "• Miglioramento audio ed equalizzatore: Ottimizza l'audio sul grande schermo con i profili Piatto, Film, Musica, Dialogo e Notte, più Audio Booster (+10 dB).\n"
            "• Suono surround: Audio multicanale completo (5.1 e 7.1) e passthrough Dolby Digital Plus (E-AC-3) verso sintoamplificatori o soundbar.\n"
            "• Salta intro: Seleziona e salta intro, riassunti e titoli di coda con TheIntroDB.\n"
            "• Scheda Prossimo episodio: Un avviso negli ultimi 30 secondi permette di passare subito all'episodio successivo.\n"
            "• Cache e stile per telecomando: Sottotitoli salvati localmente per ogni video; comandi facili da telecomando per dimensione, colore e opacità.\n"
            "• Streaming di rete: Riproduzione fluida dalle condivisioni di rete (SMB e SFTP) con un buffering solido.\n"
            "• Controlli ottimizzati per telecomando: Tempi di salto e velocità di scorrimento progettati per Siri Remote."
        ),
        "visionOS": (
            "Novità della versione 27.0:\n\n"
            "• Miglioramento audio ed equalizzatore: Personalizza l'audio con i profili Piatto, Film, Musica, Dialogo e Notte, più Audio Booster (+10 dB).\n"
            "• Suono surround e spaziale: Surround multicanale (5.1 e 7.1) e passthrough audio spaziale E-AC-3.\n"
            "• Salta intro: Salta agevolmente intro, riassunti e titoli di coda con TheIntroDB.\n"
            "• Scheda Prossimo episodio: Passa al prossimo episodio in modo fluido durante gli ultimi 30 secondi.\n"
            "• Cache e stile dei sottotitoli: I sottotitoli scaricati vengono ricordati per ogni video; personalizza font, colori e opacità.\n"
            "• Streaming ad alte prestazioni: Video fluido su SMB e SFTP con buffering e riconnessione automatica.\n"
            "• Controlli spaziali: Controllo naturale della velocità pizzicando e tenendo premuto, con intervalli di salto regolabili."
        ),
    },
    "ja": {
        "iOS": (
            "バージョン 27.0 の新機能：\n\n"
            "• オーディオ補正とイコライザー：フラット、映画、音楽、セリフ、ナイトモードのプリセットに加え、小音量でもクリアに聞こえるオーディオブースター（+10 dB）を搭載。\n"
            "• サラウンド＆空間オーディオ：マルチチャンネルサラウンド（5.1ch / 7.1ch）再生、およびDolby Digital Plus（E-AC-3）空間オーディオのシステムパススルーに対応。\n"
            "• イントロ・クレジットのスキップ：TheIntroDBのコミュニティタイムスタンプにより、OP、あらすじ、EDをワンタップでスキップ。\n"
            "• 「次を再生」カード：エピソード終了前の30秒間に次のエピソードへの再生案内を表示。\n"
            "• 字幕キャッシュとカスタマイズ：ダウンロードした字幕は動画ごとに自動保存され、再ダウンロード不要で即座に再利用可能。フォント、文字色、背景の不透明度も自由に設定できます。\n"
            "• 高性能ネットワーク再生：SMBおよびSFTPでのバッファリング再生が向上し、ネットワーク切り替え時も自動復帰。\n"
            "• 操作性のカスタマイズ：スキップ秒数（10秒、15秒、30秒）と長押し時の再生速度（0.25倍〜3.0倍）を細かく調整可能。\n"
            "• ピクチャ・イン・ピクチャとバックグラウンド復帰：バックグラウンド遷移の安定性とPiP再生のスムーズさを強化。"
        ),
        "macOS": (
            "バージョン 27.0 の新機能：\n\n"
            "• オーディオ補正とイコライザー：フラット、映画、音楽、セリフ、ナイトモードの各プリセットとオーディオブースター（+10 dB）。\n"
            "• サラウンド＆空間オーディオ：5.1ch / 7.1ch サラウンド再生とDolby Digital Plus（E-AC-3）パススルー。\n"
            "• スキップ機能：TheIntroDB連携により、キーボードの「S」キーひとつでイントロやクレジットをスキップ。\n"
            "• モダンなデスクトップUI：セクションに素早くアクセスできる分割サイドバーと、再生画面にドッキング可能なプレイリスト・調整パネル。\n"
            "• 次のエピソードとシーズン調整：スムーズな連続再生カードとインタラクティブなタイムラインスクラバー。\n"
            "• 字幕キャッシュとスタイル設定：字幕ファイルを動画ごとに自動保持。フォント、色、背景の透明度を自在にカスタマイズ。\n"
            "• 高速ネットワーク再生：SMB/SFTPのバッファリングと接続復旧を大幅に強化。\n"
            "• Metalビデオレンダリング：ハードウェアアクセラレーションデコード、Metalアップスケーリング、GPUフレーム補間（モーションスムージング）。\n"
            "• ショートカットと操作設定：スキップ時間や長押し速度の設定、便利なメニューショートカット（⌘B、⌘N、⌥⌘N、⌘R）を追加。"
        ),
        "tvOS": (
            "バージョン 27.0 の新機能：\n\n"
            "• オーディオ補正とイコライザー：大画面に合わせてフラット、映画、音楽、セリフ、ナイトモードを選択可能。オーディオブースター（+10 dB）も搭載。\n"
            "• サラウンドサウンド：5.1ch / 7.1ch のマルチチャンネル音声、AVアンプへのDolby Digital Plus（E-AC-3）パススルーに対応。\n"
            "• イントロスキップ：Siri Remoteのフォーカス操作でOPやEDを素早くスキップ。\n"
            "• 「次を再生」カード：エピソード終盤の30秒間に次の話へ進む案内カードを表示。\n"
            "• 字幕キャッシュとリモコン設定：字幕を動画ごとにローカル保持。リモコンでサイズや色、背景の不透明度をスムーズに調整可能。\n"
            "• ネットワーク再生：ホームネットワーク上の共有フォルダ（SMB / SFTP）から安定したバッファリングで快適再生。\n"
            "• リモコン最適化：Siri Remote向けにスキップ時間や長押し送り速度をカスタマイズ可能。"
        ),
        "visionOS": (
            "バージョン 27.0 の新機能：\n\n"
            "• オーディオ補正とイコライザー：フラット、映画、音楽、セリフ、ナイトモードのプリセットとオーディオブースター（+10 dB）。\n"
            "• サラウンド＆空間オーディオ：5.1ch / 7.1ch サラウンドとE-AC-3空間オーディオのパススルー。\n"
            "• スキップ機能：TheIntroDBにより、オープニングやエンディングを手軽にスキップ。\n"
            "• 次を再生カード：残り30秒で次のエピソードへスムーズに移行。\n"
            "• 字幕キャッシュとスタイル設定：字幕データを動画単位で自動保持し、フォントや色、背景の透明度を調整可能。\n"
            "• 高性能ストリーミング：SMBおよびSFTPからのバッファリング再生と自動再接続。\n"
            "• 空間プレイヤー操作：ピンチ＆ホールドでの速度変更やカスタムスキップ秒数に対応。"
        ),
    },
    "ko": {
        "iOS": (
            "버전 27.0의 새로운 기능:\n\n"
            "• 오디오 향상 및 이퀄라이저: 플랫, 영화, 음악, 대화, 야간 모드 EQ 프리셋과 모든 음량에서 또렷한 사운드를 제공하는 오디오 부스터(+10 dB) 지원.\n"
            "• 서라운드 및 공간 음향: 멀티채널 서라운드(5.1 및 7.1) 재생 및 공간 음향을 포함한 Dolby Digital Plus(E-AC-3) 시스템 패스스루.\n"
            "• 오프닝/엔딩 건너뛰기: TheIntroDB 커뮤니티 타임스탬프를 통해 인트로, 요약 및 엔딩 크레딧을 간편하게 건너뜁니다.\n"
            "• 다음 에피소드(Up Next) 카드: 회차 종료 30초 전에 다음 에피소드로 바로 이어지는 카드가 표시됩니다.\n"
            "• 자막 캐시 및 사용자 지정: 다운로드한 자막이 동영상별로 저장되어 다시 받지 않고 즉시 로드됩니다. 글꼴, 색상, 배경 투명도 설정 지원.\n"
            "• 고성능 네트워크 스트리밍: 버퍼링이 개선된 SMB 및 SFTP 재생 및 네트워크 전환 시 자동 재연결.\n"
            "• 맞춤형 앱 제어: 건너뛰기 시간(10초, 15초, 30초) 및 길게 누를 때의 재생 속도(0.25배~3.0배) 지정.\n"
            "• 화면 속 화면(PiP) 및 백그라운드 복귀: 백그라운드 전환 안정화 및 향상된 PiP 재생."
        ),
        "macOS": (
            "버전 27.0의 새로운 기능:\n\n"
            "• 오디오 향상 및 이퀄라이저: 플랫, 영화, 음악, 대화, 야간 모드 프리셋 및 오디오 부스터(+10 dB 증폭).\n"
            "• 서라운드 및 공간 음향: 멀티채널 서라운드(5.1 및 7.1) 및 Dolby Digital Plus(E-AC-3) 시스템 패스스루.\n"
            "• 인트로 및 크레딧 건너뛰기: TheIntroDB 연동으로 'S' 키 하나로 인트로와 엔딩 크레딧을 즉시 스킵.\n"
            "• 모던 데스크탑 탐색: 섹션 바로가기가 포함된 분할 사이드바와 재생 화면에 고정 가능한 플레이리스트 및 조절 패널.\n"
            "• 다음 회차 카드 및 시즌 탐색기: 연속 재생을 위한 다음 에피소드 안내 및 직관적인 시즌 스크러버.\n"
            "• 자막 캐시 및 스타일링: 자막이 각 동영상에 유지되어 즉시 재사용되며, 서체, 색상, 배경 투명도 맞춤 설정 지원.\n"
            "• 고성능 네트워크 스트리밍: 버퍼링 및 연결 복구 기능이 강화된 SMB/SFTP 재생.\n"
            "• Metal 비디오 렌더링: 하드웨어 가속 디코딩, Metal 업스케일링 및 GPU 모션 스무딩(프레임 보간).\n"
            "• 조작 및 단축키: 건너뛰기 간격, 길게 누르기 속도 및 새로운 메뉴 단축키(⌘B, ⌘N, ⌥⌘N, ⌘R) 추가."
        ),
        "tvOS": (
            "버전 27.0의 새로운 기능:\n\n"
            "• 오디오 향상 및 이퀄라이저: 대화면 환경에 맞춘 플랫, 영화, 음악, 대화, 야간 모드 프리셋 및 오디오 부스터(+10 dB).\n"
            "• 서라운드 사운드: 풍부한 멀티채널 오디오(5.1 및 7.1)와 AV 리시버/사운드바용 Dolby Digital Plus(E-AC-3) 패스스루.\n"
            "• 인트로 건너뛰기: Siri Remote로 포커스하여 인트로, 요약 및 크레딧을 쉽게 건너뜁니다.\n"
            "• 다음 에피소드 안내: 마지막 30초 동안 다음 에피소드로 빠르게 넘어가는 카드가 표시됩니다.\n"
            "• 자막 캐시 및 리모컨 설정: 동영상별 자막 로컬 보관, 리모컨으로 크기, 색상 및 배경 투명도를 손쉽게 조절.\n"
            "• 네트워크 스트리밍: 강력한 버퍼링을 바탕으로 가정 내 네트워크 공유 폴더(SMB 및 SFTP)에서 매끄럽게 스트리밍.\n"
            "• 리모컨 최적화 제어: Siri Remote에 맞춘 건너뛰기 시간 및 탐색 속도 설정."
        ),
        "visionOS": (
            "버전 27.0의 새로운 기능:\n\n"
            "• 오디오 향상 및 이퀄라이저: 플랫, 영화, 음악, 대화, 야간 모드 프리셋 및 오디오 부스터(+10 dB).\n"
            "• 서라운드 및 공간 음향: 5.1 및 7.1 멀티채널 서라운드와 E-AC-3 공간 음향 패스스루.\n"
            "• 건너뛰기 기능: TheIntroDB를 활용해 인트로와 크레딧을 간편하게 스킵.\n"
            "• 다음 에피소드 카드: 종료 30초 전 자연스럽게 다음 회차로 이어지는 인터페이스.\n"
            "• 자막 캐시 및 스타일: 자막 자동 기억 및 글꼴, 색상, 투명도 개인화.\n"
            "• 고성능 스트리밍: SMB/SFTP 버퍼링 재생 및 네트워크 끊김 시 자동 재연결.\n"
            "• 공간 플레이어 제어: 핀치 앤 홀드 속도 조절 및 맞춤 건너뛰기 시간 지원."
        ),
    },
    "nl-NL": {
        "iOS": (
            "Nieuw in versie 27.0:\n\n"
            "• Audioverbetering & equalizer: Kies uit Flat, Films, Muziek, Dialoog en Nachtmodus, plus een Audio Booster (+10 dB) voor helder geluid bij elk volume.\n"
            "• Surround & ruimtelijke audio: Meerkanaals surroundweergave (5.1 en 7.1) en systeempassthrough voor Dolby Digital Plus (E-AC-3) met ruimtelijke audio.\n"
            "• Intro's overslaan: Sla intro's, samenvattingen en aftitelingen over met community-tijdstempels van TheIntroDB.\n"
            "• Volgende aflevering (Up Next): Schakel tijdens de laatste 30 seconden moeiteloos door naar de volgende aflevering.\n"
            "• Ondertitelcache & weergave: Gedownloade ondertitels worden per video bewaard voor direct hergebruik. Pas lettertype, kleuren en achtergrondtransparantie aan.\n"
            "• Krachtige netwerkstreaming: Verbeterde SMB- en SFTP-weergave met gebufferde streaming en automatisch herstel van verbindingen.\n"
            "• Aanpasbare app-bediening: Kies de gewenste sprongduur (10s, 15s, 30s) en afspeelsnelheden bij ingedrukt houden (0,25× tot 3,0×).\n"
            "• Beeld-in-beeld & achtergrondhervatting: Vloeiende overgangen naar de achtergrond en verbeterde PiP-weergave."
        ),
        "macOS": (
            "Nieuw in versie 27.0:\n\n"
            "• Audioverbetering & equalizer: Profielen voor Flat, Films, Muziek, Dialoog en Nachtmodus, plus Audio Booster (+10 dB versterking).\n"
            "• Surround & ruimtelijke audio: Meerkanaals surround (5.1 en 7.1) en passthrough voor Dolby Digital Plus (E-AC-3).\n"
            "• Intro's overslaan: Sla met één toets (S) intro's en aftitelingen over via TheIntroDB.\n"
            "• Moderne desktopnavigatie: Gesplitste navigatiebalk voor snelle toegang tot secties en vastzetbare zijpanelen voor afspeellijsten en instellingen.\n"
            "• Volgende aflevering & seizoensregelaar: Up Next-kaart voor afleveringsverloop en interactieve schuifbalk voor seizoenen.\n"
            "• Ondertitelcache & stijl: Ondertitels blijven gekoppeld aan elke video; lettertype, kleur en achtergronddekking zijn aanpasbaar.\n"
            "• Krachtige netwerkstreaming: Geoptimaliseerde SMB- en SFTP-streaming met buffer en verbindingsherstel.\n"
            "• Metal-videorendering: Hardwareversnelde decodering, Metal-upscaling en GPU-bewegingsvloeiendheid.\n"
            "• Bediening & sneltoetsen: Aanpasbare sprongintervallen, zoeksnelheden en nieuwe menutoetsen (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Nieuw in versie 27.0:\n\n"
            "• Audioverbetering & equalizer: Stem geluid op het grote scherm af met Flat, Films, Muziek, Dialoog en Nachtmodus, plus Audio Booster (+10 dB).\n"
            "• Surround-geluid: Volledige meerkanaals audio (5.1 en 7.1) en passthrough voor Dolby Digital Plus (E-AC-3) naar je versterker of soundbar.\n"
            "• Intro's overslaan: Selecteer en sla intro's en aftitelingen over dankzij TheIntroDB.\n"
            "• Volgende aflevering: Een melding in de laatste 30 seconden om snel door te gaan naar de volgende aflevering.\n"
            "• Ondertitelcache & afstandsbediening: Ondertitels lokaal bewaard per video; handige bediening voor tekstgrootte, kleur en achtergrond met de Siri Remote.\n"
            "• Netwerkstreaming: Vloeiend streamen vanaf je netwerkbronnen (SMB en SFTP) met robuuste buffering.\n"
            "• Afstandsbediening-bediening: Configureerbare sprongtijden en zoeksnelheden voor de Siri Remote."
        ),
        "visionOS": (
            "Nieuw in versie 27.0:\n\n"
            "• Audioverbetering & equalizer: Stem af met Flat, Films, Muziek, Dialoog en Nachtmodus, plus Audio Booster (+10 dB).\n"
            "• Surround & ruimtelijke audio: Meerkanaals surround (5.1 en 7.1) en E-AC-3 ruimtelijke passthrough.\n"
            "• Intro's overslaan: Eenvoudig intro's en aftitelingen overslaan met TheIntroDB.\n"
            "• Volgende aflevering: Ga moeiteloos verder in de laatste 30 seconden van een show.\n"
            "• Ondertitelcache & stijl: Ondertitels automatisch onthouden per video; personaliseer lettertype, kleur en transparantie.\n"
            "• Hoogwaardige streaming: Videoweergave over SMB en SFTP met buffering en automatisch herverbinden.\n"
            "• Ruimtelijke bediening: Snelheidscontrole door samen te knijpen en vast te houden, met aanpasbare sprongtijden."
        ),
    },
    "pt-BR": {
        "iOS": (
            "Novidades da versão 27.0:\n\n"
            "• Aprimoramento de áudio e equalizador: Escolha entre os perfis Plano, Filmes, Música, Diálogo e Modo Noturno, além de um Amplificador de Áudio (+10 dB) para som nítido em qualquer volume.\n"
            "• Áudio surround e espacial: Reprodução surround multicanal (5.1 e 7.1) e passthrough do sistema para Dolby Digital Plus (E-AC-3) com áudio espacial.\n"
            "• Pular aberturas e créditos: Pule aberturas, recapitulações e créditos com marcações comunitárias do TheIntroDB.\n"
            "• Cartão Próximo Episódio: Transição perfeita para o próximo episódio durante os últimos 30 segundos da exibição.\n"
            "• Cache e personalização de legendas: Legendas baixadas são salvas por vídeo para reuso imediato. Ajuste fonte, cores e opacidade do fundo.\n"
            "• Streaming de rede de alto desempenho: Reprodução aprimorada em SMB e SFTP com buffer estável e reconexão automática.\n"
            "• Controles personalizáveis: Escolha os intervalos de salto (10s, 15s, 30s) e as velocidades ao manter pressionado (0,25× a 3,0×).\n"
            "• Picture in Picture e retomada em segundo plano: Transições suaves em segundo plano e melhorias no PiP."
        ),
        "macOS": (
            "Novidades da versão 27.0:\n\n"
            "• Aprimoramento de áudio e equalizador: Perfis Plano, Filmes, Música, Diálogo e Modo Noturno, além de Amplificador de Áudio (+10 dB).\n"
            "• Áudio surround e espacial: Surround multicanal (5.1 e 7.1) e passthrough para Dolby Digital Plus (E-AC-3).\n"
            "• Pular aberturas e créditos: Atalho de tecla única (S) para pular aberturas e créditos via TheIntroDB.\n"
            "• Navegação de mesa moderna: Barra lateral dividida para acesso rápido a seções e painéis acopláveis no player para playlists e ajustes.\n"
            "• Próximo episódio e navegador de temporadas: Cartão para continuar a série e régua interativa de episódios.\n"
            "• Cache e estilo de legendas: Legendas vinculadas a cada vídeo; personalize tipografia, cores e opacidade do fundo.\n"
            "• Streaming de rede de alta velocidade: Reprodução SMB e SFTP com buffer reforçado e recuperação de conexão.\n"
            "• Renderização de vídeo Metal: Decodificação acelerada por hardware, upscaling Metal e suavização de movimento por GPU.\n"
            "• Controles e atalhos: Intervalos de salto ajustáveis, velocidades de pressão prolongada e novos atalhos de menu (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Novidades da versão 27.0:\n\n"
            "• Aprimoramento de áudio e equalizador: Ajuste o som na tela grande com os modos Plano, Filmes, Música, Diálogo e Modo Noturno, mais Amplificador (+10 dB).\n"
            "• Som surround: Áudio multicanal completo (5.1 e 7.1) e passthrough de Dolby Digital Plus (E-AC-3) para seu receiver ou soundbar.\n"
            "• Pular aberturas: Foque e pule aberturas e créditos usando o TheIntroDB.\n"
            "• Cartão Próximo Episódio: Aviso nos últimos 30 segundos para avançar rapidamente ao próximo capítulo.\n"
            "• Cache e estilo para controle: Legendas salvas por vídeo; controles fáceis de tamanho, cor e opacidade no Siri Remote.\n"
            "• Streaming de rede: Reprodução fluida a partir de compartilhamentos (SMB e SFTP) com buffer robusto.\n"
            "• Controles otimizados: Intervalos de salto e velocidades de busca sob medida para o Siri Remote."
        ),
        "visionOS": (
            "Novidades da versão 27.0:\n\n"
            "• Aprimoramento de áudio e equalizador: Ajuste o som com os modos Plano, Filmes, Música, Diálogo e Modo Noturno, mais Amplificador (+10 dB).\n"
            "• Áudio surround e espacial: Surround multicanal (5.1 e 7.1) e passthrough de áudio espacial E-AC-3.\n"
            "• Pular aberturas: Pule facilmente aberturas e créditos com o TheIntroDB.\n"
            "• Cartão Próximo Episódio: Avance com fluidez nos últimos 30 segundos de reprodução.\n"
            "• Cache e estilo de legendas: Legendas lembradas automaticamente por vídeo; personalize fonte, cores e opacidade.\n"
            "• Streaming de alto desempenho: Vídeo fluido por SMB e SFTP com buffer e reconexão automática.\n"
            "• Controles espaciais: Controle de velocidade segurando o gesto de pinça e intervalos de salto configuráveis."
        ),
    },
    "pt-PT": {
        "iOS": (
            "Novidades da versão 27.0:\n\n"
            "• Melhoria de áudio e equalizador: Escolha entre os perfis Plano, Filmes, Música, Diálogo e Modo Noturno, além de um Amplificador de Áudio (+10 dB) para som nítido em qualquer volume.\n"
            "• Áudio surround e espacial: Reprodução surround multicanal (5.1 e 7.1) e passthrough do sistema para Dolby Digital Plus (E-AC-3) com áudio espacial.\n"
            "• Ignorar introduções e créditos: Salte introduções, resumos e créditos com marcadores comunitários do TheIntroDB.\n"
            "• Cartão Episódio Seguinte: Transição suave para o próximo episódio durante os últimos 30 segundos de reprodução.\n"
            "• Cache e personalização de legendas: Legendas descarregadas são guardadas por vídeo para reutilização imediata. Ajuste tipo de letra, cores e opacidade do fundo.\n"
            "• Transmissão de rede de alto desempenho: Reprodução melhorada através de SMB e SFTP com buffer estável e reconexão automática.\n"
            "• Controlos personalizáveis: Escolha os intervalos de salto (10s, 15s, 30s) e velocidades ao premir continuamente (0,25× a 3,0×).\n"
            "• Imagem na imagem e retoma em segundo plano: Transições suaves em segundo plano e melhorias na reprodução PiP."
        ),
        "macOS": (
            "Novidades da versão 27.0:\n\n"
            "• Melhoria de áudio e equalizador: Perfis Plano, Filmes, Música, Diálogo e Modo Noturno, além de Amplificador de Áudio (+10 dB).\n"
            "• Áudio surround e espacial: Surround multicanal (5.1 e 7.1) e passthrough para Dolby Digital Plus (E-AC-3).\n"
            "• Ignorar introduções e créditos: Atalho de tecla única (S) para saltar introduções e créditos com o TheIntroDB.\n"
            "• Navegação de secretária moderna: Barra lateral dividida para acesso rápido a secções e painéis acopláveis no leitor para listas e ajustes.\n"
            "• Episódio seguinte e seletor de temporadas: Cartão para continuar a série e barra interativa de episódios.\n"
            "• Cache e estilo de legendas: Legendas associadas a cada vídeo; personalize tipografia, cores e opacidade do fundo.\n"
            "• Transmissão de rede de alta velocidade: Reprodução SMB e SFTP com buffer reforçado e recuperação de ligação.\n"
            "• Renderização de vídeo Metal: Descodificação acelerada por hardware, upscaling Metal e suavização de movimento por GPU.\n"
            "• Controlos e atalhos: Intervalos de salto ajustáveis, velocidades de pressão prolongada e novos atalhos de menu (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Novidades da versão 27.0:\n\n"
            "• Melhoria de áudio e equalizador: Ajuste o som no grande ecrã com os modos Plano, Filmes, Música, Diálogo e Modo Noturno, mais Amplificador (+10 dB).\n"
            "• Som surround: Áudio multicanal completo (5.1 e 7.1) e passthrough de Dolby Digital Plus (E-AC-3) para o seu recetor ou soundbar.\n"
            "• Ignorar introduções: Foque e salte introduções e créditos através do TheIntroDB.\n"
            "• Cartão Episódio Seguinte: Aviso nos últimos 30 segundos para avançar rapidamente para o próximo capítulo.\n"
            "• Cache e estilo no comando: Legendas guardadas por vídeo; controlos fáceis de tamanho, cor e opacidade no Siri Remote.\n"
            "• Transmissão de rede: Reprodução fluida a partir de partilhas de rede (SMB e SFTP) com buffer robusto.\n"
            "• Controlos otimizados: Intervalos de salto e velocidades de pesquisa à medida para o Siri Remote."
        ),
        "visionOS": (
            "Novidades da versão 27.0:\n\n"
            "• Melhoria de áudio e equalizador: Ajuste o som com os modos Plano, Filmes, Música, Diálogo e Modo Noturno, mais Amplificador (+10 dB).\n"
            "• Áudio surround e espacial: Surround multicanal (5.1 e 7.1) e passthrough de áudio espacial E-AC-3.\n"
            "• Ignorar introduções: Salte facilmente introduções e créditos com o TheIntroDB.\n"
            "• Cartão Episódio Seguinte: Avance com fluidez nos últimos 30 segundos de reprodução.\n"
            "• Cache e estilo de legendas: Legendas memorizadas automaticamente por vídeo; personalize tipo de letra, cores e opacidade.\n"
            "• Transmissão de alto desempenho: Vídeo fluido por SMB e SFTP com buffer e reconexão automática.\n"
            "• Controlos espaciais: Controlo de velocidade mantendo o gesto de pinça e intervalos de salto configuráveis."
        ),
    },
    "ru": {
        "iOS": (
            "Что нового в версии 27.0:\n\n"
            "• Улучшение звука и эквалайзер: Предустановки «Нейтральный», «Фильмы», «Музыка», «Диалоги» и «Ночной режим», плюс усиление звука (+10 дБ) для четкого звучания на любой громкости.\n"
            "• Объемный и пространственный звук: Многоканальное воспроизведение (5.1 и 7.1) и сквозная передача Dolby Digital Plus (E-AC-3) с пространственным звуком.\n"
            "• Пропуск заставок и титров: Пропускайте интро, краткие содержания и финальные титры по меткам TheIntroDB.\n"
            "• Карточка «Далее»: Удобный переход к следующей серии в последние 30 секунд воспроизведения.\n"
            "• Кэш и оформление субтитров: Загруженные субтитры сохраняются для каждого видео без повторного скачивания. Настройка шрифта, цветов и прозрачности фона.\n"
            "• Высокопроизводительное сетевое вещание: Улучшенное воспроизведение по SMB и SFTP с буферизацией и автоподключением при смене сети.\n"
            "• Настраиваемое управление: Выбор интервала перемотки (10, 15, 30 с) и скорости воспроизведения при удержании (от 0,25× до 3,0×).\n"
            "• «Картинка в картинке» и фон: Плавный переход в фоновый режим и оптимизация режима PiP."
        ),
        "macOS": (
            "Что нового в версии 27.0:\n\n"
            "• Улучшение звука и эквалайзер: Предустановки эквалайзера и усилитель звука (+10 дБ).\n"
            "• Объемный и пространственный звук: Многоканальный звук (5.1 и 7.1) и сквозная передача Dolby Digital Plus (E-AC-3).\n"
            "• Пропуск заставок: Быстрый пропуск интро и титров клавишей «S» благодаря TheIntroDB.\n"
            "• Современный десктопный интерфейс: Разделенная боковая панель и прикрепляемые панели плейлиста и настроек в окне плеера.\n"
            "• Карточка следующей серии и таймлайн сезонов: Быстрый переход к следующей серии и интерактивная навигация по сезону.\n"
            "• Кэш и стили субтитров: Субтитры сохраняются для каждого файла; настройка шрифта, цвета и прозрачности фона.\n"
            "• Сетевое воспроизведение: Оптимизированный стриминг по SMB и SFTP с буферизацией и восстановлением связи.\n"
            "• Рендеринг видео через Metal: Аппаратное декодирование, апскейлинг Metal и сглаживание движения на GPU.\n"
            "• Управление и горячие клавиши: Настраиваемые интервалы перемотки и новые сочетания клавиш (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Что нового в версии 27.0:\n\n"
            "• Улучшение звука и эквалайзер: Настройка звучания для ТВ с профилями для фильмов, музыки, диалогов и ночи, плюс усилитель (+10 дБ).\n"
            "• Объемный звук: Многоканальный звук (5.1 и 7.1) и передача Dolby Digital Plus (E-AC-3) на ресивер или саундбар.\n"
            "• Пропуск заставок: Выбор и пропуск интро и титров с помощью TheIntroDB.\n"
            "• Карточка следующей серии: Подсказка в последние 30 секунд для мгновенного включения следующей серии.\n"
            "• Кэш и удобная настройка субтитров: Субтитры сохраняются локально; простая настройка размера, цвета и прозрачности с пульта.\n"
            "• Сетевое воспроизведение: Плавный просмотр из сетевых папок (SMB и SFTP) с надежной буферизацией.\n"
            "• Управление с пульта: Настраиваемые интервалы перемотки для Siri Remote."
        ),
        "visionOS": (
            "Что нового в версии 27.0:\n\n"
            "• Улучшение звука и эквалайзер: Предустановки эквалайзера и усилитель звука (+10 дБ).\n"
            "• Объемный и пространственный звук: Многоканальный объемный звук (5.1 и 7.1) и пространственный звук E-AC-3.\n"
            "• Пропуск заставок: Легкий пропуск интро и титров с TheIntroDB.\n"
            "• Карточка следующей серии: Бесшовный переход к следующему эпизоду за 30 секунд до конца.\n"
            "• Кэш и стили субтитров: Запоминание субтитров для каждого видео; настройка шрифтов, цветов и прозрачности.\n"
            "• Потоковая передача: Воспроизведение по SMB и SFTP с буферизацией и автопереподключением.\n"
            "• Пространственное управление: Изменение скорости удержанием жеста щипка и настройка интервалов перемотки."
        ),
    },
    "sv": {
        "iOS": (
            "Nyheter i version 27.0:\n\n"
            "• Ljudförbättring och equalizer: Välj mellan profiler för Platt, Film, Musik, Dialog och Nattläge, samt en Ljudförstärkare (+10 dB) för klart ljud vid varje volym.\n"
            "• Surround och rumsligt ljud: Flerkanaligt surroundljud (5.1 och 7.1) och systemgenomströmning för Dolby Digital Plus (E-AC-3) med rumsligt ljud.\n"
            "• Hoppa över intron och eftertexter: Hoppa förbi intron, sammanfattningar och eftertexter med tidsstämplar från TheIntroDB.\n"
            "• Nästa avsnitt-kort (Up Next): Starta smidigt nästa avsnitt under de sista 30 sekunderna av ett program.\n"
            "• Undertextcache och anpassning: Nedladdade undertexter sparas per video för direkt återanvändning. Anpassa typsnitt, färger och bakgrundsopacitet.\n"
            "• Högpresterande nätverksströmning: Förbättrad SMB- och SFTP-uppspelning med buffring och automatisk återanslutning.\n"
            "• Anpassningsbar styrning: Välj hoppintervall (10s, 15s, 30s) och uppspelningshastigheter vid hålltryck (0,25× till 3,0×).\n"
            "• Bild-i-bild och bakgrundsåterupptagning: Smidiga övergångar till bakgrunden och förbättrad PiP-uppspelning."
        ),
        "macOS": (
            "Nyheter i version 27.0:\n\n"
            "• Ljudförbättring och equalizer: Förinställningar för Platt, Film, Musik, Dialog och Nattläge samt Ljudförstärkare (+10 dB).\n"
            "• Surround och rumsligt ljud: Flerkanaligt surroundljud (5.1 och 7.1) och passthrough för Dolby Digital Plus (E-AC-3).\n"
            "• Hoppa över segment: Hoppa över intron och eftertexter med en tangent (S) via TheIntroDB.\n"
            "• Modern skrivbordsnavigering: Delad sidofältsvy för snabb åtkomst samt dockningsbara sidopaneler i spelaren för spellistor och inställningar.\n"
            "• Nästa avsnitt och säsongsreglage: Kort för nästa avsnitt och interaktiv säsongsnavigering.\n"
            "• Undertextcache och stil: Undertexter behålls för varje video; anpassa typsnitt, färg och bakgrundens opacitet.\n"
            "• Nätverksströmning: Optimerad SMB- och SFTP-strömning med buffring och anslutningsåterställning.\n"
            "• Metal-videorenderering: Hårdvaruaccelererad avkodning, Metal-uppskalning och GPU-rörelseutjämning.\n"
            "• Reglage och kortkommandon: Anpassade hoppintervall, hållhastigheter och nya menykommandon (⌘B, ⌘N, ⌥⌘N, ⌘R)."
        ),
        "tvOS": (
            "Nyheter i version 27.0:\n\n"
            "• Ljudförbättring och equalizer: Justera ljudet på stor skärm med profilerna Platt, Film, Musik, Dialog och Nattläge, plus Ljudförstärkare (+10 dB).\n"
            "• Surroundljud: Flerkanaligt ljud (5.1 och 7.1) och genomströmning av Dolby Digital Plus (E-AC-3) till förstärkare eller soundbar.\n"
            "• Hoppa över intron: Fokusera och hoppa över intron och eftertexter med TheIntroDB.\n"
            "• Nästa avsnitt: En avisering visas under de sista 30 sekunderna för snabb start av nästa del.\n"
            "• Undertextcache och fjärrstyrning: Undertexter sparas per video; enkel justering av storlek, färg och opacitet med Siri Remote.\n"
            "• Nätverksströmning: Smidig uppspelning från nätverksresurser (SMB och SFTP) med stabil buffring.\n"
            "• Fjärrkontrollskontroller: Anpassningsbara hoppintervall och sökhastigheter utformade för Siri Remote."
        ),
        "visionOS": (
            "Nyheter i version 27.0:\n\n"
            "• Ljudförbättring och equalizer: Skräddarsy ljudet med profiler för Platt, Film, Musik, Dialog och Nattläge samt Ljudförstärkare (+10 dB).\n"
            "• Surround och rumsligt ljud: Flerkanaligt surroundljud (5.1 och 7.1) och rumslig E-AC-3-passthrough.\n"
            "• Hoppa över segment: Hoppa smidigt över intron och eftertexter med TheIntroDB.\n"
            "• Nästa avsnitt-kort: Gå sömlöst vidare till nästa avsnitt under de sista 30 sekunderna.\n"
            "• Undertextcache och stil: Nedladdade undertexter sparas per video; anpassa typsnitt, färger och opacitet.\n"
            "• Högpresterande strömning: Strömma över SMB och SFTP med buffring och automatisk återanslutning.\n"
            "• Rumsbaserad styrning: Justera hastighet genom att nypa och hålla, samt anpassningsbara hoppintervall."
        ),
    },
    "zh-Hans": {
        "iOS": (
            "版本 27.0 的更新内容：\n\n"
            "• 音频增强与均衡器：提供平直、电影、音乐、对白和夜间模式等 EQ 预设，并配备音频增强器（+10 dB），在任何音量下均能呈现清晰声效。\n"
            "• 环绕声与空间音频：支持多声道环绕声（5.1 和 7.1）播放，以及带空间对象的 Dolby Digital Plus (E-AC-3) 系统透传。\n"
            "• 片头与片尾跳过提示：借助 TheIntroDB 社区时间戳，一键跳过片头、前情提要和演职员表。\n"
            "• “接下来播放”卡片：在剧集结束前 30 秒显示引导卡片，无缝衔接下一集。\n"
            "• 字幕缓存与外观自定义：下载的字幕按视频自动持久化保存，无需重复下载；支持自定义字幕字体、颜色和背景不透明度。\n"
            "• 高性能网络串流：优化 SMB 和 SFTP 串流播放，提供缓冲保护并在网络切换时自动恢复连接。\n"
            "• 自定义播放控制：可调节快进/快退跳跃时长（10秒、15秒、30秒）以及按住屏幕两侧的倍速播放（0.25× 至 3.0×）。\n"
            "• 画中画与后台恢复：平滑的后台过渡体验，进一步优化画中画 (PiP) 播放。"
        ),
        "macOS": (
            "版本 27.0 的更新内容：\n\n"
            "• 音频增强与均衡器：平直、电影、音乐、对白和夜间模式预设，以及音频增强器（+10 dB 增益）。\n"
            "• 环绕声与空间音频：多声道环绕声（5.1 和 7.1）及 Dolby Digital Plus (E-AC-3) 系统透传。\n"
            "• 片头片尾跳过：通过 TheIntroDB 时间戳，按快捷键 S 即可快速跳过片头和片尾。\n"
            "• 现代桌面端导航：采用分栏侧边栏快速访问各版块，播放界面支持停靠播放列表和播放调节面板。\n"
            "• 下一集卡片与季度快进条：剧集连续播放卡片与交互式整季进度导航条。\n"
            "• 字幕缓存与排版风格：下载字幕随视频自动保留；支持自定义字体、颜色和背景透明度。\n"
            "• 高性能网络串流：增强 SMB 与 SFTP 网络播放，具备数据缓冲与断线自动重连。\n"
            "• Metal 视频渲染管道：硬件加速解码、Metal 超分辨率缩放和 GPU 动态平滑（帧插入）。\n"
            "• 快捷操作与偏好设置：自定义跳跃时长、长按倍速以及新增菜单快捷键（⌘B、⌘N、⌥⌘N、⌘R）。"
        ),
        "tvOS": (
            "版本 27.0 的更新内容：\n\n"
            "• 音频增强与均衡器：在大屏幕上微调音效，支持平直、电影、音乐、对白和夜间模式，以及音频增强器（+10 dB）。\n"
            "• 环绕声输出：完整的多声道音频（5.1 和 7.1），支持将 Dolby Digital Plus (E-AC-3) 透传至功放或回音壁。\n"
            "• 片头跳过提示：使用 Siri Remote 遥控器快速聚焦并跳过片头、提要和片尾。\n"
            "• 下一集卡片：在剧集最后 30 秒弹出提示，迅速进入下一集。\n"
            "• 字幕缓存与遥控器调节：下载字幕按视频本地保留；可通过遥控器轻松调节字幕大小、颜色与背景透明度。\n"
            "• 网络共享播放：通过稳定的缓冲流式播放本地家庭网络共享（SMB 和 SFTP）。\n"
            "• 专为遥控器优化的控制：专为 Siri Remote 设计的可自定义跳跃时长与长按快进速度。"
        ),
        "visionOS": (
            "版本 27.0 的更新内容：\n\n"
            "• 音频增强与均衡器：平直、电影、音乐、对白和夜间模式预设，加音频增强器（+10 dB）。\n"
            "• 环绕声与空间音频：多声道环绕声（5.1 和 7.1）以及 E-AC-3 空间音频透传。\n"
            "• 跳过提示：通过 TheIntroDB 轻松跳过片头、回顾和片尾。\n"
            "• 下一集卡片：最后 30 秒无缝提示并衔接下一集。\n"
            "• 字幕缓存与样式：下载字幕自动记录于对应视频；自由定制字体、色彩和不透明度。\n"
            "• 高性能串流：支持 SMB 与 SFTP 视频串流，附带缓冲与自动重连功能。\n"
            "• 空间播放器控制：捏合长按手势调速与自定义跳跃时长。"
        ),
    },
    "zh-Hant": {
        "iOS": (
            "版本 27.0 的更新內容：\n\n"
            "• 音訊增強與等化器：提供平直、電影、音樂、對白和夜間模式等 EQ 預設，並配備音訊增強器（+10 dB），在任何音量下皆能呈現清晰音效。\n"
            "• 環繞聲與空間音訊：支援多聲道環繞聲（5.1 和 7.1）播放，以及具備空間物件的 Dolby Digital Plus (E-AC-3) 系統直通。\n"
            "• 片頭與片尾略過提示：透過 TheIntroDB 社群時間戳記，一鍵略過片頭、前情提要和演職員表。\n"
            "• 「接下來播放」卡片：在劇集結束前 30 秒顯示導引卡片，無縫銜接下一集。\n"
            "• 字幕快取與外觀自訂：下載的字幕依影片自動保留，無需重複下載；支援自訂字幕字體、色彩與背景不透明度。\n"
            "• 高效能網路串流：最佳化 SMB 和 SFTP 串流播放，提供緩衝保護並在網路切換時自動恢復連線。\n"
            "• 自訂播放控制：可設定快進/快退跳轉時間（10秒、15秒、30秒）以及長按螢幕兩側的倍速播放（0.25× 至 3.0×）。\n"
            "• 子母畫面與背景恢復：平滑的背景過渡體驗，進一步最佳化子母畫面 (PiP) 播放。"
        ),
        "macOS": (
            "版本 27.0 的更新內容：\n\n"
            "• 音訊增強與等化器：平直、電影、音樂、對白和夜間模式預設，以及音訊增強器（+10 dB 增益）。\n"
            "• 環繞聲與空間音訊：多聲道環繞聲（5.1 和 7.1）及 Dolby Digital Plus (E-AC-3) 系統直通。\n"
            "• 片頭片尾略過：透過 TheIntroDB 時間戳記，按下快捷鍵 S 即可快速略過片頭與片尾。\n"
            "• 現代桌面端導覽：採用分割側邊欄快速存取各區塊，播放介面支援停靠播放清單與播放調整面板。\n"
            "• 下一集卡片與季度進度軸：劇集連續播放卡片與互動式整季進度導覽條。\n"
            "• 字幕快取與排版風格：下載字幕隨影片自動保留；支援自訂字體、色彩與背景透明度。\n"
            "• 高效能網路串流：增強 SMB 與 SFTP 網路播放，具備資料快取與斷線自動重連。\n"
            "• Metal 視訊算圖管道：硬體加速解碼、Metal 超解析度縮放與 GPU 動態平滑（影格插入）。\n"
            "• 快捷操作與偏好設定：自訂跳轉時間、長按倍速以及新增選單快捷鍵（⌘B、⌘N、⌥⌘N、⌘R）。"
        ),
        "tvOS": (
            "版本 27.0 的更新內容：\n\n"
            "• 音訊增強與等化器：在大螢幕上微調音效，支援平直、電影、音樂、對白和夜間模式，以及音訊增強器（+10 dB）。\n"
            "• 環繞聲輸出：完整的多聲道音訊（5.1 和 7.1），支援將 Dolby Digital Plus (E-AC-3) 直通至擴大機或聲霸。\n"
            "• 片頭略過提示：使用 Siri Remote 遙控器快速聚焦並略過片頭、提要和片尾。\n"
            "• 下一集卡片：在劇集最後 30 秒彈出提示，迅速進入下一集。\n"
            "• 字幕快取與遙控器調整：下載字幕依影片本地保留；可透過遙控器輕鬆調整字幕大小、色彩與背景透明度。\n"
            "• 網路共享播放：透過穩定的緩衝串流播放本地家庭網路共享（SMB 和 SFTP）。\n"
            "• 專為遙控器最佳化的控制：專為 Siri Remote 設計的可自訂跳轉時間與長按快進速度。"
        ),
        "visionOS": (
            "版本 27.0 的更新內容：\n\n"
            "• 音訊增強與等化器：平直、電影、音樂、對白和夜間模式預設，加音訊增強器（+10 dB）。\n"
            "• 環繞聲與空間音訊：多聲道環繞聲（5.1 和 7.1）以及 E-AC-3 空間音訊直通。\n"
            "• 略過提示：透過 TheIntroDB 輕鬆略過片頭、回顧與片尾。\n"
            "• 下一集卡片：最後 30 秒無縫提示並銜接下一集。\n"
            "• 字幕快取與樣式：下載字幕自動記錄於對應影片；自由自訂字型、色彩與不透明度。\n"
            "• 高效能串流：支援 SMB 與 SFTP 視訊串流，附帶緩衝與自動重連功能。\n"
            "• 空間播放器控制：捏合長按手勢調速與自訂跳轉時間。"
        ),
    },
}

# English regional locales fall back to en-US
WHATS_NEW["en-AU"] = WHATS_NEW["en-US"]
WHATS_NEW["en-CA"] = WHATS_NEW["en-US"]

def populate_all():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(base_dir, "config.json")
    
    # 1. Update config.json
    with open(config_path, "r", encoding="utf-8") as f:
        config = json.load(f)
    
    config["versionString"] = "27.0"
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("Updated ASC/config.json with versionString = 27.0")

    # 2. Update all platform locale JSON files
    platforms = ["iOS", "macOS", "tvOS", "visionOS"]
    total_updated = 0
    
    for platform in platforms:
        platform_dir = os.path.join(base_dir, platform)
        for json_file in sorted(glob.glob(os.path.join(platform_dir, "*.json"))):
            locale = os.path.basename(json_file).replace(".json", "")
            with open(json_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            
            data["version"] = "27.0"
            
            # Select whatsNew for this locale & platform
            if locale in WHATS_NEW and platform in WHATS_NEW[locale]:
                data["whatsNew"] = WHATS_NEW[locale][platform]
            else:
                data["whatsNew"] = WHATS_NEW["en-US"][platform]
            
            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
                f.write("\n")
            total_updated += 1
            print(f"Updated {platform}/{locale}.json -> version 27.0")

    print(f"\nSuccessfully populated {total_updated} files with version 27.0 fields.")

if __name__ == "__main__":
    populate_all()
