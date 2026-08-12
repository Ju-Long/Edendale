import json
import os
import glob

# Translations for the updated strings
translations = {
    "en": {
        "intro_iOS": "Edendale is a free, open-source video player and personal watch tracker for iPhone and iPad. It plays video files you already own and organizes them into a private library: no account, no ads, no analytics. Edendale does not provide, host, or stream any movies or TV shows.",
        "intro_macOS": "Edendale is a free, open-source video player and personal watch tracker for the Mac. It plays video files you already own and organizes them into a private library: no account, no ads, no analytics. Edendale does not provide, host, or stream any movies or TV shows.",
        "intro_tvOS": "Edendale is a free, open-source video player and personal watch tracker for Apple TV. It plays video files you already own on the big screen: no account, no ads, no analytics. Edendale does not provide, host, or stream any movies or TV shows.",
        "intro_visionOS": "Edendale is a free, open-source video player and personal watch tracker for Apple Vision Pro. It plays video files you already own in your own space: no account, no ads, no analytics. Edendale does not provide, host, or stream any movies or TV shows.",
        "H1": "PLAY YOUR OWN VIDEO FILES",
        "H2": "ORGANIZE YOUR PERSONAL COLLECTION",
        "H3": "TRACK YOUR VIEWING",
        "bullet_enrich": "- Edendale reads your filenames locally, then optionally enriches them with catalogue information from The Movie Database (TMDB).",
        "bullet_view_ios": "- View titles, seasons, episodes, cast, crew, release dates, and trailers sourced from TMDB.",
        "bullet_view_tvos": "- View cast, crew, seasons, episodes, and release dates sourced from TMDB.",
        "bullet_tvos_intro": "- Browse your personal video collection with catalogue artwork sourced from TMDB.",
        "bullet_visionos_intro": "- A spatial interface with ornament tabs and catalogue artwork sourced from TMDB.",
        "disclaimer": "All catalogue metadata and artwork displayed in Edendale is provided by the TMDB API for informational purposes. Edendale uses the TMDB API but is not endorsed or certified by TMDB. Screenshots show the app interface with sample data from TMDB."
    },
    "de-DE": {
        "intro_iOS": "Edendale ist ein kostenloser, quelloffener Video-Player und persönlicher Watch-Tracker für iPhone und iPad. Die App spielt Videodateien ab, die du bereits besitzt, und organisiert sie in einer privaten Mediathek: Kein Konto, keine Werbung, keine Analysen. Edendale stellt keine Filme oder Fernsehsendungen zur Verfügung, hostet oder streamt diese nicht.",
        "intro_macOS": "Edendale ist ein kostenloser, quelloffener Video-Player und persönlicher Watch-Tracker für den Mac. Die App spielt Videodateien ab, die du bereits besitzt, und organisiert sie in einer privaten Mediathek: Kein Konto, keine Werbung, keine Analysen. Edendale stellt keine Filme oder Fernsehsendungen zur Verfügung, hostet oder streamt diese nicht.",
        "intro_tvOS": "Edendale ist ein kostenloser, quelloffener Video-Player und persönlicher Watch-Tracker für Apple TV. Die App spielt Videodateien, die du bereits besitzt, auf dem großen Bildschirm ab: Kein Konto, keine Werbung, keine Analysen. Edendale stellt keine Filme oder Fernsehsendungen zur Verfügung, hostet oder streamt diese nicht.",
        "intro_visionOS": "Edendale ist ein kostenloser, quelloffener Video-Player und persönlicher Watch-Tracker für Apple Vision Pro. Die App spielt Videodateien, die du bereits besitzt, in deinem eigenen Raum ab: Kein Konto, keine Werbung, keine Analysen. Edendale stellt keine Filme oder Fernsehsendungen zur Verfügung, hostet oder streamt diese nicht.",
        "H1": "SPIELE DEINE EIGENEN VIDEODATEIEN AB",
        "H2": "ORGANISIERE DEINE PERSÖNLICHE SAMMLUNG",
        "H3": "VERFOLGE DEINE WIEDERGABE",
        "bullet_enrich": "- Edendale liest deine Dateinamen lokal und reichert sie optional mit Kataloginformationen von The Movie Database (TMDB) an.",
        "bullet_view_ios": "- Zeige Titel, Staffeln, Episoden, Besetzung, Crew, Veröffentlichungsdaten und Trailer an, die von TMDB stammen.",
        "bullet_view_tvos": "- Zeige Besetzung, Crew, Staffeln, Episoden und Veröffentlichungsdaten an, die von TMDB stammen.",
        "bullet_tvos_intro": "- Durchstöbere deine persönliche Videosammlung mit Katalog-Bildmaterial, das von TMDB stammt.",
        "bullet_visionos_intro": "- Eine räumliche Benutzeroberfläche mit Ornament-Tabs und Katalog-Bildmaterial, das von TMDB stammt.",
        "disclaimer": "Alle in Edendale angezeigten Katalogmetadaten und Bildmaterialien werden von der TMDB-API zu Informationszwecken bereitgestellt. Edendale nutzt die TMDB-API, wird jedoch nicht von TMDB unterstützt oder zertifiziert. Screenshots zeigen die App-Oberfläche mit Beispieldaten von TMDB."
    },
    "fr-FR": {
        "intro_iOS": "Edendale est un lecteur vidéo gratuit et open-source ainsi qu'un outil de suivi personnel pour iPhone et iPad. Il lit les fichiers vidéo que vous possédez déjà et les organise dans une bibliothèque privée : pas de compte, pas de publicité, pas d'analyse. Edendale ne fournit, n'héberge ni ne diffuse aucun film ou série télévisée.",
        "intro_macOS": "Edendale est un lecteur vidéo gratuit et open-source ainsi qu'un outil de suivi personnel pour Mac. Il lit les fichiers vidéo que vous possédez déjà et les organise dans une bibliothèque privée : pas de compte, pas de publicité, pas d'analyse. Edendale ne fournit, n'héberge ni ne diffuse aucun film ou série télévisée.",
        "intro_tvOS": "Edendale est un lecteur vidéo gratuit et open-source ainsi qu'un outil de suivi personnel pour Apple TV. Il lit les fichiers vidéo que vous possédez déjà sur grand écran : pas de compte, pas de publicité, pas d'analyse. Edendale ne fournit, n'héberge ni ne diffuse aucun film ou série télévisée.",
        "intro_visionOS": "Edendale est un lecteur vidéo gratuit et open-source ainsi qu'un outil de suivi personnel pour Apple Vision Pro. Il lit les fichiers vidéo que vous possédez déjà dans votre propre espace : pas de compte, pas de publicité, pas d'analyse. Edendale ne fournit, n'héberge ni ne diffuse aucun film ou série télévisée.",
        "H1": "LISEZ VOS PROPRES FICHIERS VIDÉO",
        "H2": "ORGANISEZ VOTRE COLLECTION PERSONNELLE",
        "H3": "SUIVEZ VOTRE VISIONNAGE",
        "bullet_enrich": "- Edendale lit vos noms de fichiers localement, puis les enrichit facultativement avec des informations de catalogue provenant de The Movie Database (TMDB).",
        "bullet_view_ios": "- Affichez les titres, les saisons, les épisodes, le casting, l'équipe, les dates de sortie et les bandes-annonces provenant de TMDB.",
        "bullet_view_tvos": "- Affichez le casting, l'équipe, les saisons, les épisodes et les dates de sortie provenant de TMDB.",
        "bullet_tvos_intro": "- Parcourez votre collection vidéo personnelle avec des illustrations de catalogue provenant de TMDB.",
        "bullet_visionos_intro": "- Une interface spatiale avec des onglets d'ornement et des illustrations de catalogue provenant de TMDB.",
        "disclaimer": "Toutes les métadonnées de catalogue et les illustrations affichées dans Edendale sont fournies par l'API TMDB à titre informatif. Edendale utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB. Les captures d'écran montrent l'interface de l'application avec des données d'exemple de TMDB."
    },
    "es-ES": {
        "intro_iOS": "Edendale es un reproductor de vídeo gratuito y de código abierto, y un registro de seguimiento personal para iPhone y iPad. Reproduce archivos de vídeo que ya posees y los organiza en una biblioteca privada: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_macOS": "Edendale es un reproductor de vídeo gratuito y de código abierto, y un registro de seguimiento personal para Mac. Reproduce archivos de vídeo que ya posees y los organiza en una biblioteca privada: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_tvOS": "Edendale es un reproductor de vídeo gratuito y de código abierto, y un registro de seguimiento personal para Apple TV. Reproduce archivos de vídeo que ya posees en la pantalla grande: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_visionOS": "Edendale es un reproductor de vídeo gratuito y de código abierto, y un registro de seguimiento personal para Apple Vision Pro. Reproduce archivos de vídeo que ya posees en tu propio espacio: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "H1": "REPRODUCE TUS PROPIOS ARCHIVOS DE VÍDEO",
        "H2": "ORGANIZA TU COLECCIÓN PERSONAL",
        "H3": "SIGUE TUS REPRODUCCIONES",
        "bullet_enrich": "- Edendale lee los nombres de tus archivos de forma local y, opcionalmente, los enriquece con información de catálogo de The Movie Database (TMDB).",
        "bullet_view_ios": "- Visualiza títulos, temporadas, episodios, reparto, equipo, fechas de estreno y tráileres proporcionados por TMDB.",
        "bullet_view_tvos": "- Visualiza reparto, equipo, temporadas, episodios y fechas de estreno proporcionados por TMDB.",
        "bullet_tvos_intro": "- Explora tu colección de vídeos personal con ilustraciones de catálogo proporcionadas por TMDB.",
        "bullet_visionos_intro": "- Una interfaz espacial con pestañas de adorno e ilustraciones de catálogo proporcionadas por TMDB.",
        "disclaimer": "Todos los metadatos de catálogo y las ilustraciones que se muestran en Edendale son proporcionados por la API de TMDB con fines informativos. Edendale utiliza la API de TMDB pero no está avalado ni certificado por TMDB. Las capturas de pantalla muestran la interfaz de la aplicación con datos de muestra de TMDB."
    },
    "es-MX": {
        "intro_iOS": "Edendale es un reproductor de video gratuito y de código abierto, y un registro de seguimiento personal para iPhone y iPad. Reproduce archivos de video que ya posees y los organiza en una biblioteca privada: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_macOS": "Edendale es un reproductor de video gratuito y de código abierto, y un registro de seguimiento personal para Mac. Reproduce archivos de video que ya posees y los organiza en una biblioteca privada: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_tvOS": "Edendale es un reproductor de video gratuito y de código abierto, y un registro de seguimiento personal para Apple TV. Reproduce archivos de video que ya posees en la pantalla grande: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "intro_visionOS": "Edendale es un reproductor de video gratuito y de código abierto, y un registro de seguimiento personal para Apple Vision Pro. Reproduce archivos de video que ya posees en tu propio espacio: sin cuenta, sin anuncios, sin análisis. Edendale no proporciona, aloja ni transmite películas o programas de televisión.",
        "H1": "REPRODUCE TUS PROPIOS ARCHIVOS DE VIDEO",
        "H2": "ORGANIZA TU COLECCIÓN PERSONAL",
        "H3": "SIGUE TUS REPRODUCCIONES",
        "bullet_enrich": "- Edendale lee los nombres de tus archivos de forma local y, opcionalmente, los enriquece con información de catálogo de The Movie Database (TMDB).",
        "bullet_view_ios": "- Ve títulos, temporadas, episodios, elenco, equipo, fechas de estreno y avances proporcionados por TMDB.",
        "bullet_view_tvos": "- Ve elenco, equipo, temporadas, episodios y fechas de estreno proporcionados por TMDB.",
        "bullet_tvos_intro": "- Explora tu colección de videos personal con arte de catálogo proporcionado por TMDB.",
        "bullet_visionos_intro": "- Una interfaz espacial con pestañas ornamentales y arte de catálogo proporcionado por TMDB.",
        "disclaimer": "Todos los metadatos de catálogo y el arte que se muestran en Edendale son proporcionados por la API de TMDB con fines informativos. Edendale utiliza la API de TMDB pero no está avalado ni certificado por TMDB. Las capturas de pantalla muestran la interfaz de la aplicación con datos de muestra de TMDB."
    },
    "it": {
        "intro_iOS": "Edendale è un lettore video gratuito e open source, e un tracker personale per le visioni su iPhone e iPad. Riproduce i file video che già possiedi e li organizza in una libreria privata: nessun account, nessuna pubblicità, nessuna analisi. Edendale non fornisce, ospita né trasmette film o programmi TV.",
        "intro_macOS": "Edendale è un lettore video gratuito e open source, e un tracker personale per le visioni su Mac. Riproduce i file video che già possiedi e li organizza in una libreria privata: nessun account, nessuna pubblicità, nessuna analisi. Edendale non fornisce, ospita né trasmette film o programmi TV.",
        "intro_tvOS": "Edendale è un lettore video gratuito e open source, e un tracker personale per le visioni su Apple TV. Riproduce i file video che già possiedi sul grande schermo: nessun account, nessuna pubblicità, nessuna analisi. Edendale non fornisce, ospita né trasmette film o programmi TV.",
        "intro_visionOS": "Edendale è un lettore video gratuito e open source, e un tracker personale per le visioni su Apple Vision Pro. Riproduce i file video che già possiedi nel tuo spazio: nessun account, nessuna pubblicità, nessuna analisi. Edendale non fornisce, ospita né trasmette film o programmi TV.",
        "H1": "RIPRODUCI I TUOI FILE VIDEO",
        "H2": "ORGANIZZA LA TUA COLLEZIONE PERSONALE",
        "H3": "TIENI TRACCIA DELLE TUE VISIONI",
        "bullet_enrich": "- Edendale legge i nomi dei file localmente, per poi arricchirli facoltativamente con informazioni di catalogo provenienti da The Movie Database (TMDB).",
        "bullet_view_ios": "- Visualizza titoli, stagioni, episodi, cast, troupe, date di uscita e trailer forniti da TMDB.",
        "bullet_view_tvos": "- Visualizza cast, troupe, stagioni, episodi e date di uscita forniti da TMDB.",
        "bullet_tvos_intro": "- Esplora la tua collezione video personale con copertine di catalogo fornite da TMDB.",
        "bullet_visionos_intro": "- Un'interfaccia spaziale con schede ornamentali e copertine di catalogo fornite da TMDB.",
        "disclaimer": "Tutti i metadati di catalogo e le copertine visualizzati in Edendale sono forniti dall'API di TMDB a scopo informativo. Edendale utilizza l'API di TMDB ma non è approvato né certificato da TMDB. Gli screenshot mostrano l'interfaccia dell'app con dati di esempio di TMDB."
    },
    "ja": {
        "intro_iOS": "Edendaleは、iPhoneおよびiPad用の無料でオープンソースの動画プレーヤー兼個人的な視聴トラッカーです。あなたが既に所有している動画ファイルを再生し、それらをプライベートなライブラリに整理します。アカウント、広告、分析は一切ありません。Edendaleは、映画やテレビ番組の提供、ホスト、ストリーミングを行いません。",
        "intro_macOS": "Edendaleは、Mac用の無料でオープンソースの動画プレーヤー兼個人的な視聴トラッカーです。あなたが既に所有している動画ファイルを再生し、それらをプライベートなライブラリに整理します。アカウント、広告、分析は一切ありません。Edendaleは、映画やテレビ番組の提供、ホスト、ストリーミングを行いません。",
        "intro_tvOS": "Edendaleは、Apple TV用の無料でオープンソースの動画プレーヤー兼個人的な視聴トラッカーです。あなたが既に所有している動画ファイルを大画面で再生します。アカウント、広告、分析は一切ありません。Edendaleは、映画やテレビ番組の提供、ホスト、ストリーミングを行いません。",
        "intro_visionOS": "Edendaleは、Apple Vision Pro用の無料でオープンソースの動画プレーヤー兼個人的な視聴トラッカーです。あなたが既に所有している動画ファイルをあなた自身の空間で再生します。アカウント、広告、分析は一切ありません。Edendaleは、映画やテレビ番組の提供、ホスト、ストリーミングを行いません。",
        "H1": "所有している動画ファイルを再生",
        "H2": "個人的なコレクションを整理",
        "H3": "視聴履歴を追跡",
        "bullet_enrich": "- Edendaleはファイル名をローカルで読み取り、The Movie Database (TMDB) のカタログ情報でオプションで補足します。",
        "bullet_view_ios": "- TMDBから提供されるタイトル、シーズン、エピソード、キャスト、スタッフ、公開日、予告編を表示します。",
        "bullet_view_tvos": "- TMDBから提供されるキャスト、スタッフ、シーズン、エピソード、公開日を表示します。",
        "bullet_tvos_intro": "- TMDBから提供されるカタログアートワークを使って、個人の動画コレクションを閲覧します。",
        "bullet_visionos_intro": "- オーナメントタブとTMDBから提供されるカタログアートワークを備えた空間インターフェース。",
        "disclaimer": "Edendaleに表示されるすべてのカタログメタデータとアートワークは、情報提供を目的としてTMDB APIによって提供されています。EdendaleはTMDB APIを使用していますが、TMDBによって承認または認定されていません。スクリーンショットには、TMDBのサンプルデータを使用したアプリのインターフェースが表示されています。"
    },
    "ko": {
        "intro_iOS": "Edendale은 iPhone 및 iPad용 무료 오픈 소스 비디오 플레이어이자 개인 시청 기록 트래커입니다. 이미 소유하고 있는 비디오 파일을 재생하고 비공개 라이브러리로 정리합니다. 계정, 광고, 분석이 없습니다. Edendale은 영화나 TV 프로그램을 제공, 호스팅 또는 스트리밍하지 않습니다.",
        "intro_macOS": "Edendale은 Mac용 무료 오픈 소스 비디오 플레이어이자 개인 시청 기록 트래커입니다. 이미 소유하고 있는 비디오 파일을 재생하고 비공개 라이브러리로 정리합니다. 계정, 광고, 분석이 없습니다. Edendale은 영화나 TV 프로그램을 제공, 호스팅 또는 스트리밍하지 않습니다.",
        "intro_tvOS": "Edendale은 Apple TV용 무료 오픈 소스 비디오 플레이어이자 개인 시청 기록 트래커입니다. 이미 소유하고 있는 비디오 파일을 큰 화면에서 재생합니다. 계정, 광고, 분석이 없습니다. Edendale은 영화나 TV 프로그램을 제공, 호스팅 또는 스트리밍하지 않습니다.",
        "intro_visionOS": "Edendale은 Apple Vision Pro용 무료 오픈 소스 비디오 플레이어이자 개인 시청 기록 트래커입니다. 이미 소유하고 있는 비디오 파일을 나만의 공간에서 재생합니다. 계정, 광고, 분석이 없습니다. Edendale은 영화나 TV 프로그램을 제공, 호스팅 또는 스트리밍하지 않습니다.",
        "H1": "자신의 비디오 파일 재생",
        "H2": "개인 컬렉션 정리",
        "H3": "시청 기록 추적",
        "bullet_enrich": "- Edendale은 로컬에서 파일 이름을 읽은 다음 The Movie Database(TMDB)의 카탈로그 정보로 선택적으로 보강합니다.",
        "bullet_view_ios": "- TMDB에서 제공하는 제목, 시즌, 에피소드, 출연진, 제작진, 출시일 및 예고편을 확인하세요.",
        "bullet_view_tvos": "- TMDB에서 제공하는 출연진, 제작진, 시즌, 에피소드 및 출시일을 확인하세요.",
        "bullet_tvos_intro": "- TMDB에서 제공하는 카탈로그 아트워크로 개인 비디오 컬렉션을 탐색하세요.",
        "bullet_visionos_intro": "- 오너먼트 탭과 TMDB에서 제공하는 카탈로그 아트워크가 있는 공간 인터페이스.",
        "disclaimer": "Edendale에 표시되는 모든 카탈로그 메타데이터와 아트워크는 정보 제공의 목적으로 TMDB API에서 제공합니다. Edendale은 TMDB API를 사용하지만 TMDB의 보증이나 인증을 받지 않았습니다. 스크린샷은 TMDB의 샘플 데이터가 있는 앱 인터페이스를 보여줍니다."
    },
    "nl-NL": {
        "intro_iOS": "Edendale is een gratis, open-source videospeler en persoonlijke kijker-tracker voor iPhone en iPad. Het speelt videobestanden af die je al bezit en organiseert ze in een privébibliotheek: geen account, geen advertenties, geen analyses. Edendale biedt, host of streamt geen films of tv-programma's.",
        "intro_macOS": "Edendale is een gratis, open-source videospeler en persoonlijke kijker-tracker voor de Mac. Het speelt videobestanden af die je al bezit en organiseert ze in een privébibliotheek: geen account, geen advertenties, geen analyses. Edendale biedt, host of streamt geen films of tv-programma's.",
        "intro_tvOS": "Edendale is een gratis, open-source videospeler en persoonlijke kijker-tracker voor Apple TV. Het speelt videobestanden die je al bezit af op het grote scherm: geen account, geen advertenties, geen analyses. Edendale biedt, host of streamt geen films of tv-programma's.",
        "intro_visionOS": "Edendale is een gratis, open-source videospeler en persoonlijke kijker-tracker voor Apple Vision Pro. Het speelt videobestanden die je al bezit af in je eigen ruimte: geen account, geen advertenties, geen analyses. Edendale biedt, host of streamt geen films of tv-programma's.",
        "H1": "SPEEL JE EIGEN VIDEOBESTANDEN AF",
        "H2": "ORGANISEER JE PERSOONLIJKE COLLECTIE",
        "H3": "HOUD BIJ WAT JE KIJKT",
        "bullet_enrich": "- Edendale leest je bestandsnamen lokaal en verrijkt ze vervolgens optioneel met catalogusinformatie van The Movie Database (TMDB).",
        "bullet_view_ios": "- Bekijk titels, seizoenen, afleveringen, cast, crew, releasedatums en trailers afkomstig van TMDB.",
        "bullet_view_tvos": "- Bekijk cast, crew, seizoenen, afleveringen en releasedatums afkomstig van TMDB.",
        "bullet_tvos_intro": "- Blader door je persoonlijke videocollectie met catalogusillustraties afkomstig van TMDB.",
        "bullet_visionos_intro": "- Een ruimtelijke interface met ornamenttabbladen en catalogusillustraties afkomstig van TMDB.",
        "disclaimer": "Alle catalogusmetagegevens en illustraties die in Edendale worden weergegeven, worden voor informatieve doeleinden geleverd door de TMDB API. Edendale gebruikt de TMDB API maar wordt niet onderschreven of gecertificeerd door TMDB. Schermafbeeldingen tonen de app-interface met voorbeeldgegevens van TMDB."
    },
    "pt-BR": {
        "intro_iOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de exibição para iPhone e iPad. Ele reproduz arquivos de vídeo que você já possui e os organiza em uma biblioteca privada: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_macOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de exibição para Mac. Ele reproduz arquivos de vídeo que você já possui e os organiza em uma biblioteca privada: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_tvOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de exibição para Apple TV. Ele reproduz arquivos de vídeo que você já possui na tela grande: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_visionOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de exibição para Apple Vision Pro. Ele reproduz arquivos de vídeo que você já possui no seu próprio espaço: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "H1": "REPRODUZA SEUS PRÓPRIOS ARQUIVOS DE VÍDEO",
        "H2": "ORGANIZE SUA COLEÇÃO PESSOAL",
        "H3": "ACOMPANHE O QUE VOCÊ ASSISTE",
        "bullet_enrich": "- O Edendale lê seus nomes de arquivo localmente e os enriquece opcionalmente com informações de catálogo do The Movie Database (TMDB).",
        "bullet_view_ios": "- Veja títulos, temporadas, episódios, elenco, equipe, datas de lançamento e trailers fornecidos pelo TMDB.",
        "bullet_view_tvos": "- Veja elenco, equipe, temporadas, episódios e datas de lançamento fornecidos pelo TMDB.",
        "bullet_tvos_intro": "- Navegue pela sua coleção de vídeos pessoal com artes de catálogo fornecidas pelo TMDB.",
        "bullet_visionos_intro": "- Uma interface espacial com guias de ornamento e artes de catálogo fornecidas pelo TMDB.",
        "disclaimer": "Todos os metadados e artes de catálogo exibidos no Edendale são fornecidos pela API TMDB para fins informativos. O Edendale usa a API TMDB, mas não é endossado ou certificado pelo TMDB. As capturas de tela mostram a interface do aplicativo com dados de exemplo do TMDB."
    },
    "pt-PT": {
        "intro_iOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de visualização para iPhone e iPad. Reproduz ficheiros de vídeo que já possui e organiza-os numa biblioteca privada: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_macOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de visualização para Mac. Reproduz ficheiros de vídeo que já possui e organiza-os numa biblioteca privada: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_tvOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de visualização para Apple TV. Reproduz ficheiros de vídeo que já possui no ecrã grande: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "intro_visionOS": "Edendale é um reprodutor de vídeo gratuito e de código aberto e um rastreador pessoal de visualização para Apple Vision Pro. Reproduz ficheiros de vídeo que já possui no seu próprio espaço: sem conta, sem anúncios, sem análises. O Edendale não fornece, hospeda ou transmite filmes ou programas de TV.",
        "H1": "REPRODUZA OS SEUS PRÓPRIOS FICHEIROS DE VÍDEO",
        "H2": "ORGANIZE A SUA COLEÇÃO PESSOAL",
        "H3": "ACOMPANHE O QUE ASSISTE",
        "bullet_enrich": "- O Edendale lê os seus nomes de ficheiro localmente e enriquece-os opcionalmente com informações de catálogo do The Movie Database (TMDB).",
        "bullet_view_ios": "- Veja títulos, temporadas, episódios, elenco, equipa, datas de lançamento e trailers fornecidos pelo TMDB.",
        "bullet_view_tvos": "- Veja elenco, equipa, temporadas, episódios e datas de lançamento fornecidos pelo TMDB.",
        "bullet_tvos_intro": "- Navegue pela sua coleção de vídeos pessoal com ilustrações de catálogo fornecidas pelo TMDB.",
        "bullet_visionos_intro": "- Uma interface espacial com separadores de ornamento e ilustrações de catálogo fornecidas pelo TMDB.",
        "disclaimer": "Todos os metadados e ilustrações de catálogo exibidos no Edendale são fornecidos pela API TMDB para fins informativos. O Edendale utiliza a API TMDB, mas não é endossado ou certificado pelo TMDB. As capturas de ecrã mostram a interface da aplicação com dados de exemplo do TMDB."
    },
    "ru": {
        "intro_iOS": "Edendale — это бесплатный видеоплеер с открытым исходным кодом и личный трекер просмотров для iPhone и iPad. Он воспроизводит видеофайлы, которыми вы уже владеете, и упорядочивает их в частную медиатеку: никаких аккаунтов, рекламы и аналитики. Edendale не предоставляет, не размещает и не транслирует фильмы или телешоу.",
        "intro_macOS": "Edendale — это бесплатный видеоплеер с открытым исходным кодом и личный трекер просмотров для Mac. Он воспроизводит видеофайлы, которыми вы уже владеете, и упорядочивает их в частную медиатеку: никаких аккаунтов, рекламы и аналитики. Edendale не предоставляет, не размещает и не транслирует фильмы или телешоу.",
        "intro_tvOS": "Edendale — это бесплатный видеоплеер с открытым исходным кодом и личный трекер просмотров для Apple TV. Он воспроизводит видеофайлы, которыми вы уже владеете, на большом экране: никаких аккаунтов, рекламы и аналитики. Edendale не предоставляет, не размещает и не транслирует фильмы или телешоу.",
        "intro_visionOS": "Edendale — это бесплатный видеоплеер с открытым исходным кодом и личный трекер просмотров для Apple Vision Pro. Он воспроизводит видеофайлы, которыми вы уже владеете, в вашем собственном пространстве: никаких аккаунтов, рекламы и аналитики. Edendale не предоставляет, не размещает и не транслирует фильмы или телешоу.",
        "H1": "ВОСПРОИЗВОДИТЕ СВОИ СОБСТВЕННЫЕ ВИДЕОФАЙЛЫ",
        "H2": "ОРГАНИЗУЙТЕ СВОЮ ЛИЧНУЮ КОЛЛЕКЦИЮ",
        "H3": "ОТСЛЕЖИВАЙТЕ СВОИ ПРОСМОТРЫ",
        "bullet_enrich": "- Edendale считывает имена ваших файлов локально, а затем по желанию дополняет их информацией из каталога The Movie Database (TMDB).",
        "bullet_view_ios": "- Просматривайте названия, сезоны, эпизоды, актерский состав, съемочную группу, даты выхода и трейлеры, предоставленные TMDB.",
        "bullet_view_tvos": "- Просматривайте актерский состав, съемочную группу, сезоны, эпизоды и даты выхода, предоставленные TMDB.",
        "bullet_tvos_intro": "- Просматривайте свою личную коллекцию видео с иллюстрациями из каталога TMDB.",
        "bullet_visionos_intro": "- Пространственный интерфейс с вкладками-орнаментами и иллюстрациями из каталога TMDB.",
        "disclaimer": "Все метаданные каталога и иллюстрации, отображаемые в Edendale, предоставляются API TMDB в ознакомительных целях. Edendale использует API TMDB, но не одобрен и не сертифицирован TMDB. На скриншотах показан интерфейс приложения с примерами данных от TMDB."
    },
    "sv": {
        "intro_iOS": "Edendale är en gratis, öppen källkodsbaserad videospelare och personlig visningsspårare för iPhone och iPad. Den spelar upp videofiler du redan äger och organiserar dem i ett privat bibliotek: inget konto, inga annonser, ingen analys. Edendale tillhandahåller, värd eller strömmar inga filmer eller TV-program.",
        "intro_macOS": "Edendale är en gratis, öppen källkodsbaserad videospelare och personlig visningsspårare för Mac. Den spelar upp videofiler du redan äger och organiserar dem i ett privat bibliotek: inget konto, inga annonser, ingen analys. Edendale tillhandahåller, värd eller strömmar inga filmer eller TV-program.",
        "intro_tvOS": "Edendale är en gratis, öppen källkodsbaserad videospelare och personlig visningsspårare för Apple TV. Den spelar upp videofiler du redan äger på den stora skärmen: inget konto, inga annonser, ingen analys. Edendale tillhandahåller, värd eller strömmar inga filmer eller TV-program.",
        "intro_visionOS": "Edendale är en gratis, öppen källkodsbaserad videospelare och personlig visningsspårare för Apple Vision Pro. Den spelar upp videofiler du redan äger i ditt eget utrymme: inget konto, inga annonser, ingen analys. Edendale tillhandahåller, värd eller strömmar inga filmer eller TV-program.",
        "H1": "SPELA UPP DINA EGNA VIDEOFILER",
        "H2": "ORGANISERA DIN PERSONLIGA SAMLING",
        "H3": "SPÅRA DITT TITTANDE",
        "bullet_enrich": "- Edendale läser dina filnamn lokalt och berikar dem sedan valfritt med kataloginformation från The Movie Database (TMDB).",
        "bullet_view_ios": "- Visa titlar, säsonger, avsnitt, skådespelare, team, lanseringsdatum och trailers hämtade från TMDB.",
        "bullet_view_tvos": "- Visa skådespelare, team, säsonger, avsnitt och lanseringsdatum hämtade från TMDB.",
        "bullet_tvos_intro": "- Bläddra i din personliga videosamling med katalogbilder hämtade från TMDB.",
        "bullet_visionos_intro": "- Ett rumsligt gränssnitt med prydnadsflikar och katalogbilder hämtade från TMDB.",
        "disclaimer": "All katalogmetadata och konstverk som visas i Edendale tillhandahålls av TMDB API i informationssyfte. Edendale använder TMDB API men är varken godkänd eller certifierad av TMDB. Skärmdumpar visar appgränssnittet med exempeldata från TMDB."
    },
    "zh-Hans": {
        "intro_iOS": "Edendale 是一款免费开源的视频播放器和个人观影记录工具，适用于 iPhone 和 iPad。它可以播放您已拥有的视频文件，并将它们整理成一个私人的资料库：无需账号，没有广告，没有分析。Edendale 不提供、不托管也不流式传输任何电影或电视节目。",
        "intro_macOS": "Edendale 是一款免费开源的视频播放器和个人观影记录工具，适用于 Mac。它可以播放您已拥有的视频文件，并将它们整理成一个私人的资料库：无需账号，没有广告，没有分析。Edendale 不提供、不托管也不流式传输任何电影或电视节目。",
        "intro_tvOS": "Edendale 是一款免费开源的视频播放器和个人观影记录工具，适用于 Apple TV。它可以在大屏幕上播放您已拥有的视频文件：无需账号，没有广告，没有分析。Edendale 不提供、不托管也不流式传输任何电影或电视节目。",
        "intro_visionOS": "Edendale 是一款免费开源的视频播放器和个人观影记录工具，适用于 Apple Vision Pro。它可以在您自己的空间内播放您已拥有的视频文件：无需账号，没有广告，没有分析。Edendale 不提供、不托管也不流式传输任何电影或电视节目。",
        "H1": "播放您自己的视频文件",
        "H2": "整理您的个人收藏",
        "H3": "追踪您的观影记录",
        "bullet_enrich": "- Edendale 在本地读取您的文件名，然后选择性地使用来自 The Movie Database (TMDB) 的目录信息来丰富它们。",
        "bullet_view_ios": "- 查看来自 TMDB 的标题、剧集、演职人员、发行日期和预告片。",
        "bullet_view_tvos": "- 查看来自 TMDB 的演职人员、剧集和发行日期。",
        "bullet_tvos_intro": "- 使用来自 TMDB 的目录海报浏览您的个人视频收藏。",
        "bullet_visionos_intro": "- 带有装饰标签和来自 TMDB 的目录海报的空间界面。",
        "disclaimer": "Edendale 中显示的所有目录元数据和艺术作品均由 TMDB API 提供，仅供参考。Edendale 使用了 TMDB API，但未经 TMDB 认可或认证。屏幕截图显示了带有 TMDB 示例数据的应用程序界面。"
    },
    "zh-Hant": {
        "intro_iOS": "Edendale 是一款免費開源的影片播放器與個人觀影紀錄工具，適用於 iPhone 和 iPad。它可以播放您已擁有的影片檔案，並將它們整理成一個私人的資料庫：無需帳號，沒有廣告，沒有分析。Edendale 不提供、不託管也不串流傳輸任何電影或電視節目。",
        "intro_macOS": "Edendale 是一款免費開源的影片播放器與個人觀影紀錄工具，適用於 Mac。它可以播放您已擁有的影片檔案，並將它們整理成一個私人的資料庫：無需帳號，沒有廣告，沒有分析。Edendale 不提供、不託管也不串流傳輸任何電影或電視節目。",
        "intro_tvOS": "Edendale 是一款免費開源的影片播放器與個人觀影紀錄工具，適用於 Apple TV。它可以在大螢幕上播放您已擁有的影片檔案：無需帳號，沒有廣告，沒有分析。Edendale 不提供、不託管也不串流傳輸任何電影或電視節目。",
        "intro_visionOS": "Edendale 是一款免費開源的影片播放器與個人觀影紀錄工具，適用於 Apple Vision Pro。它可以在您專屬的空間內播放您已擁有的影片檔案：無需帳號，沒有廣告，沒有分析。Edendale 不提供、不託管也不串流傳輸任何電影或電視節目。",
        "H1": "播放您自己的影片檔案",
        "H2": "整理您的個人收藏",
        "H3": "追蹤您的觀影紀錄",
        "bullet_enrich": "- Edendale 在本機讀取您的檔案名稱，然後選擇性地使用來自 The Movie Database (TMDB) 的目錄資訊來豐富它們。",
        "bullet_view_ios": "- 查看來自 TMDB 的標題、集數、演職員、發行日期和預告片。",
        "bullet_view_tvos": "- 查看來自 TMDB 的演職員、集數和發行日期。",
        "bullet_tvos_intro": "- 使用來自 TMDB 的目錄海報瀏覽您的個人影片收藏。",
        "bullet_visionos_intro": "- 帶有裝飾標籤和來自 TMDB 的目錄海報的空間介面。",
        "disclaimer": "Edendale 中顯示的所有目錄中繼資料和藝術作品均由 TMDB API 提供，僅供參考。Edendale 使用了 TMDB API，但未經 TMDB 認可或認證。螢幕截圖顯示了帶有 TMDB 範例資料的應用程式介面。"
    }
}

# The English-based locales just use 'en' translations
english_locales = ["en-GB", "en-AU", "en-CA"]
for loc in english_locales:
    translations[loc] = translations["en"]

# Map folder to platform specific keys
platform_mapping = {
    "iOS": {
        "intro_key": "intro_iOS",
        "h1_idx": 1,
        "h2_idx": 2,
        "h3_idx": 3,
        "view_bullet_idx": 2,
        "bullet_view_key": "bullet_view_ios"
    },
    "macOS": {
        "intro_key": "intro_macOS",
        "h1_idx": 2,
        "h2_idx": 3,
        "h3_idx": 4,
        "view_bullet_idx": 2,
        "bullet_view_key": "bullet_view_ios"
    },
    "tvOS": {
        "intro_key": "intro_tvOS",
        "h1_idx": 2,
        "h2_idx": 3,
        "h3_idx": 4,
        "view_bullet_idx": 2,
        "bullet_view_key": "bullet_view_tvos"
    },
    "visionOS": {
        "intro_key": "intro_visionOS",
        "h1_idx": 2,
        "h2_idx": 3,
        "h3_idx": 4,
        "view_bullet_idx": 2,
        "bullet_view_key": "bullet_view_ios"
    }
}

def update_file(path, locale, platform):
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    desc = data.get("description", "")
    sections = desc.split("\n\n")
    
    if locale not in translations:
        return
        
    t = translations[locale]
    p_info = platform_mapping[platform]
    
    # 0. Intro
    sections[0] = t[p_info["intro_key"]]
    
    # tvOS / visionOS section 1 special bullet update
    if platform == "tvOS":
        sec1_lines = sections[1].split("\n")
        if len(sec1_lines) > 2:
            sec1_lines[2] = t["bullet_tvos_intro"]
            sections[1] = "\n".join(sec1_lines)
    elif platform == "visionOS":
        sec1_lines = sections[1].split("\n")
        if len(sec1_lines) > 1:
            sec1_lines[1] = t["bullet_visionos_intro"]
            sections[1] = "\n".join(sec1_lines)

    # H1
    if p_info["h1_idx"] < len(sections):
        sec_lines = sections[p_info["h1_idx"]].split("\n")
        sec_lines[0] = t["H1"]
        sections[p_info["h1_idx"]] = "\n".join(sec_lines)
        
    # H2
    if p_info["h2_idx"] < len(sections):
        sec_lines = sections[p_info["h2_idx"]].split("\n")
        sec_lines[0] = t["H2"]
        if len(sec_lines) > 1:
            sec_lines[1] = t["bullet_enrich"]
        if len(sec_lines) > p_info["view_bullet_idx"]:
            sec_lines[p_info["view_bullet_idx"]] = t[p_info["bullet_view_key"]]
        sections[p_info["h2_idx"]] = "\n".join(sec_lines)
        
    # H3
    if p_info["h3_idx"] < len(sections):
        sec_lines = sections[p_info["h3_idx"]].split("\n")
        sec_lines[0] = t["H3"]
        sections[p_info["h3_idx"]] = "\n".join(sec_lines)
        
    # Disclaimer (last section)
    sections[-1] = t["disclaimer"]
    
    data["description"] = "\n\n".join(sections)
    
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")

def main():
    platforms = ["iOS", "macOS", "tvOS", "visionOS"]
    for platform in platforms:
        for file in glob.glob(f"{platform}/*.json"):
            locale = os.path.basename(file).replace('.json', '')
            if locale == "en-US":
                continue # We already updated en-US via git changes!
            print(f"Updating {platform}/{locale}.json")
            update_file(file, locale, platform)

if __name__ == "__main__":
    main()
