//
//  MediaDetailView.swift
//  Edendale
//
//  One detail page for everything: TMDB browse items and local library
//  items. Local items with a TMDB match load the full record; unmatched
//  files render from their own metadata.
//

import SwiftUI
import SwiftData
import Kingfisher

enum MediaDetailSource: Hashable {
    case tmdb(MediaRef)
    case localMovie(Movie)
    case localShow(TVShow)
}

struct MediaDetailView: View {
    let source: MediaDetailSource

    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(UserMediaStore.self) private var userMediaStore
    @Environment(SearchCoordinator.self) private var searchCoordinator
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @Environment(PlayerSession.self) private var playerSession

    /// Live library queries so a TMDB browse item resolves its local match —
    /// and re-resolves when a background enrichment fills in `tmdbId` while
    /// this page is open.
    @Query private var libraryMovies: [Movie]
    @Query private var libraryShows: [TVShow]

    @State private var detail: MediaDetail?

    @Environment(\.ratingProviders) private var ratingProviders
    @State private var ratings: [MediaRating] = []

    #if os(tvOS)
    /// tvOS can't embed a trailer, so we offer a hand-off to the YouTube app
    /// instead. Loaded lazily alongside the detail record.
    @State private var trailer: TMDBVideo?
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 40) {
                header
                VStack(alignment: .leading, spacing: 40) {
                    if isWide {
                        HStack(alignment: .top, spacing: 32) {
                            VStack(alignment: .leading, spacing: 40) {
                                archiveRecord
                                castSection
                            }
                            consensusSection
                                .frame(width: 300)
                        }
                    } else {
                        archiveRecord
                        consensusSection
                        castSection
                    }
                    if let localShow {
                        episodesSection(localShow)
                    } else if let detail, detail.ref.mediaType == .tv {
                        // No imported copy: browse the show's catalogue on
                        // TMDB instead of only counting its seasons.
                        TMDBSeasonBrowser(showId: detail.ref.id, seasons: detail.seasons)
                    }
                }
                .padding(.horizontal, edgeMargin)
            }
            .padding(.bottom, 64)
        }
        .background(Theme.background)
        .task { await loadDetail() }
        .task {
            if let mediaRef {
                await userMediaStore.refreshFromTMDB(mediaRef)
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        ZStack(alignment: .bottomLeading) {
            // Full-height hero: the backdrop scales to the visible container
            // height and any horizontal overflow is cropped (BackdropImage
            // aspect-fills and clips).
            BackdropImage(url: backdropURL)
                .ignoresSafeArea(.all, edges: .horizontal)
                .containerRelativeFrame(.vertical)
                .frame(maxWidth: .infinity)

            VStack(alignment: .leading, spacing: 16) {
                Group {
                    if let tagline = detail?.tagline {
                        Text(tagline)
                            .labelCaps(Theme.gold)
                    }

                    Text(title)
                        .font(Typography.display(titleSize))
                        .textCase(.uppercase)
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.5)

                    if let genre = genres.first {
                        Text(genre.uppercased())
                            .font(Typography.labelCaps)
                            .kerning(1)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .overlay {
                                RoundedRectangle(cornerRadius: Theme.Radius.soft)
                                    .strokeBorder(Theme.outline, lineWidth: 1)
                            }
                    }

                    metaRow
                }
                // Tagline, title, genre badge, year, runtime, and studio are
                // one masthead — read as one stop, with the title as the
                // heading the rotor lands on.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityValue(headerAccessibilityValue)
                .accessibilityAddTraits(.isHeader)

                actionRow
                    .padding(.top, 8)
            }
            .padding(edgeMargin)
        }
    }

    /// Everything the masthead says besides the title, in the order it is
    /// drawn. The metadata row's dot separators carry no meaning.
    private var headerAccessibilityValue: String {
        var parts: [String] = []
        if let tagline = detail?.tagline { parts.append(tagline) }
        if let genre = genres.first { parts.append(genre) }
        if let year { parts.append(String(year)) }
        if let runtimeText { parts.append(runtimeText) }
        if let attribution { parts.append(attribution) }
        return parts.joined(separator: ", ")
    }

    private var metaRow: some View {
        HStack(spacing: 12) {
            if let year { Text(String(year)) }
            
            if let runtimeText {
                dot
                Text(runtimeText)
            }
            
            if let attribution {
                dot
                Text(attribution.uppercased()).font(Typography.labelCaps).kerning(1)
            }
        }
        .font(Typography.bodySM)
        .foregroundStyle(Theme.textSecondary)
        .lineLimit(1)
    }

    private var dot: some View {
        Circle()
            .fill(Theme.outline)
            .frame(width: 3, height: 3)
            .accessibilityHidden(true)
    }

    private var actionRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            if isPlayable {
                Button {
                    play()
                } label: {
                    Label(playLabel, image: .play)
                }
                .archiveButtonStyle(.primary)
            }
            
            FlowLayout(spacing: 12, lineSpacing: 8) {
                if let ref = mediaRef {
                    Button {
                        userMediaStore.toggleWatchlist(ref)
                    } label: {
                        Label("Watchlist", image: userMediaStore.isInWatchlist(ref) ? .check : .plus)
                    }
                    .archiveButtonStyle(.secondary)
                    // Unlike Favorite and Mark Watched, this button's text
                    // never changes — only its glyph does, so membership has
                    // to be spoken.
                    .accessibilityAddTraits(.isToggle)
                    .accessibilityValue(userMediaStore.isInWatchlist(ref) ? Text("On") : Text("Off"))
                    
                    Button {
                        userMediaStore.toggleFavorite(ref)
                    } label: {
                        Label(
                            userMediaStore.isFavorite(ref)
                                ? String(localized: "Remove Favourite")
                                : String(localized: "Favorite"),
                            image: userMediaStore.isFavorite(ref) ? .heartFill : .heart
                        )
                    }
                    .archiveButtonStyle(.secondary, active: userMediaStore.isFavorite(ref))
                    
                    StarRatingControl(rating: userMediaStore.rating(for: ref)) { value in
                        userMediaStore.setRating(value, for: ref)
                    }
                }

                #if os(tvOS)
                if let trailer, TrailerPlayerView.canOpenExternally(trailer.key) {
                    Button {
                        TrailerPlayerView.openExternally(trailer.key)
                    } label: {
                        HStack(alignment: .center, spacing: 4) {
                            Image(.youtube)
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 28)
                                .accessibilityHidden(true)

                            Text("Watch Trailer")
                        }
                    }
                    .archiveButtonStyle(.secondary)
                    // Playback leaves the app entirely — worth saying before
                    // the jump, not after.
                    .accessibilityHint("Opens the YouTube app.")
                }
                #endif
            }
            
            if let watchKey {
                Button {
                    toggleWatched(watchKey)
                } label: {
                    Label(
                        isWatched(watchKey)
                            ? String(localized: "Watched")
                            : String(localized: "Mark Watched"),
                        image: isWatched(watchKey) ? .eyeSlash : .eye
                    )
                }
                .archiveButtonStyle(.ghost)
            }
        }
        // Play, Watchlist, Favorite, rating, and Mark Watched are one bank
        // of controls on this title.
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Actions")
    }

    // MARK: - Archive record

    @ViewBuilder
    private var archiveRecord: some View {
        if let overview, !overview.isEmpty {
            GlassCard {
                VStack(alignment: .leading, spacing: 14) {
                    Text("The Archive Record")
                        .font(Typography.headlineMD)
                        .textCase(.uppercase)
                        .foregroundStyle(Theme.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    ExpandableText(text: overview)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("The Archive Record")
        }
    }

    // MARK: - Cast
    @ViewBuilder
    private var castSection: some View {
        if let cast = detail?.cast, !cast.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text("Principal Cast")
                    .labelCaps(Theme.gold)
                    .accessibilityAddTraits(.isHeader)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 18) {
                        ForEach(cast) { member in
                            castCard(member)
                        }
                    }
                    .padding(.vertical, 4)
                }
                // Let the tvOS focus enlargement / glow bleed past the shelf.
                .scrollClipDisabled()
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Principal Cast")
        }
    }

    /// One cast portrait. Tapping routes to the Search tab filtered to this
    /// person's filmography. tvOS drops the system focus ring in favour of the
    /// reserved-bounds enlargement used by posters (see `CardFocusButtonStyle`).
    @ViewBuilder
    private func castCard(_ member: TMDBCastMember) -> some View {
        Button {
            searchCoordinator.pendingPerson = PersonRef(id: member.id, name: member.name)
        } label: {
            VStack(spacing: 8) {
                ZStack {
                    Theme.surface
                    Image(.film)
                        .font(.system(size: 20))
                        .foregroundStyle(Theme.surfaceHigh)
                    if let url = member.profileURL {
                        KFImage(url)
                            .resizable()
                            .fade(duration: 0.25)
                            .aspectRatio(contentMode: .fill)
                    }
                }
                .frame(width: 84, height: 84)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
                Text(member.name)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .frame(width: 90)
            }
        }
        #if os(tvOS)
        .buttonStyle(CardFocusButtonStyle())
        #else
        .buttonStyle(.plain)
        #endif
        // The button already folds its portrait and caption into one element;
        // this pins the reading to the name and says where the tap goes.
        .accessibilityLabel(member.name)
        .accessibilityHint("Shows this person's filmography.")
    }

    // MARK: - Critical consensus

    @ViewBuilder
    private var consensusSection: some View {
        if let detail, !ratings.isEmpty || detail.seasonCount != nil || detail.tagline != nil {
            VStack(alignment: .leading, spacing: 16) {
                Text("Critical Consensus")
                    .labelCaps(Theme.gold)
                    .accessibilityAddTraits(.isHeader)

                GlassCard(padding: 20) {
                    VStack(alignment: .leading, spacing: 20) {
                        ForEach(ratings, id: \.self) { rating in
                            ScoreTile(source: rating.source, value: rating.value, suffix: rating.suffix)
                        }
                        if let seasons = detail.seasonCount, let episodes = detail.episodeCount {
                            ScoreTile(
                                source: String(localized: "Catalogue"),
                                value: "\(seasons)",
                                suffix: catalogueSummary(seasons: seasons, episodes: episodes)
                            )
                        }
                    }
                }

                if let tagline = detail.tagline {
                    HStack(spacing: 14) {
                        // Quote rule, not content.
                        Rectangle()
                            .fill(Theme.goldDeep)
                            .frame(width: 2)
                            .accessibilityHidden(true)
                        Text("“\(tagline)”")
                            .font(Typography.bodyLG.italic())
                            .foregroundStyle(Theme.textPrimary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            // Heading, scorecard, and pull quote are one rail.
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Critical Consensus")
        }
    }

    // MARK: - Episodes (local shows)

    private func episodesSection(_ show: TVShow) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: String(localized: "Episodes"))
            ForEach(show.availableSeasons, id: \.self) { season in
                VStack(alignment: .leading, spacing: 0) {
                    Text("Season \(season)")
                        .font(Typography.titleLG)
                        .foregroundStyle(Theme.textPrimary)
                        .padding(.vertical, 12)
                        .accessibilityAddTraits(.isHeader)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: episodeSpacing) {
                            ForEach(show.episodes(for: season)) { episode in
                                episodeCard(episode, show: show)
                            }
                        }
                        .padding(.horizontal, edgeMargin)
                        .padding(.vertical, 14)
                    }
                    // Full-bleed shelf inside the padded page column so the
                    // hover/focus glow isn't clipped at the margins.
                    .scrollClipDisabled()
                    .padding(.horizontal, -edgeMargin)
                }
                // Each season heading and its shelf are one group.
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Season \(season)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Episodes")
    }

    @ViewBuilder
    private func episodeCard(_ episode: Episode, show: TVShow) -> some View {
        Button {
            playEpisode(episode)
        } label: {
            LandscapeCard(
                title: episode.displayTitle,
                subtitle: episodeSubtitle(episode),
                imageURL: episode.stillURL ?? show.backdropURL,
                placeholderIcon: "tv",
                width: episodeCardWidth,
                progress: episodeProgress(episode),
                isWatched: episode.tmdbId.map { watchStore.isWatched($0, mediaType: .episode) } ?? false
            )
        }
        #if os(tvOS)
        .buttonStyle(CardFocusButtonStyle())
        #else
        .buttonStyle(.plain)
        #endif
        .accessibilityHint("Plays this episode.")
        .contextMenu {
            if let tmdbId = episode.tmdbId {
                Button {
                    if watchStore.isWatched(tmdbId, mediaType: .episode) {
                        watchStore.remove(tmdbId: tmdbId, mediaType: .episode)
                    } else {
                        watchStore.markCompleted(tmdbId: tmdbId, mediaType: .episode)
                    }
                } label: {
                    Label(
                        watchStore.isWatched(tmdbId, mediaType: .episode)
                            ? String(localized: "Mark Unwatched")
                            : String(localized: "Mark Watched"),
                        image: "check"
                    )
                }
            }
        }
    }

    private func episodeSubtitle(_ episode: Episode) -> String {
        episode.duration > 0
            ? "\(episode.episodeCode) · \(episode.formattedDuration)"
            : episode.episodeCode
    }

    private func catalogueSummary(seasons: Int, episodes: Int) -> String {
        let seasonText = seasons == 1
            ? String(localized: "season")
            : String(localized: "seasons")
        let episodeText = episodes == 1
            ? String(localized: "1 episode")
            : String(localized: "\(episodes) episodes")
        return "\(seasonText) · \(episodeText)"
    }

    private func episodeProgress(_ episode: Episode) -> Double? {
        guard let tmdbId = episode.tmdbId,
              let progress = watchStore.progress(for: tmdbId, mediaType: .episode),
              !progress.isCompleted else { return nil }
        return progress.position
    }

    // MARK: - Data

    private func loadDetail() async {
        guard detail == nil, TMDBService.shared.isConfigured else { return }
        let ref: MediaRef? = switch source {
        case .tmdb(let ref): ref
        case .localMovie(let movie): movie.tmdbId.map { MediaRef(id: $0, mediaType: .movie) }
        case .localShow(let show): show.tmdbId.map { MediaRef(id: $0, mediaType: .tv) }
        }
        guard let ref else { return }
        detail = try? await TMDBService.shared.mediaDetail(ref)

        #if os(tvOS)
        trailer = try? await TMDBService.shared.bestTrailer(ref)
        #endif

        await withTaskGroup(of: [MediaRating].self) { group in
            for provider in ratingProviders {
                group.addTask {
                    (try? await provider.ratings(for: ref)) ?? []
                }
            }
            for await providerRatings in group {
                ratings.append(contentsOf: providerRatings)
            }
        }
    }

    private var title: String {
        if let detail { return detail.title }
        switch source {
        case .tmdb: return ""
        case .localMovie(let movie): return movie.displayTitle
        case .localShow(let show): return show.displayName
        }
    }

    private var backdropURL: URL? {
        if let url = detail?.backdropURL { return url }
        switch source {
        case .tmdb: return nil
        case .localMovie(let movie): return movie.backdropURL
        case .localShow(let show): return show.backdropURL
        }
    }

    private var overview: String? {
        if let text = detail?.overview, !text.isEmpty { return text }
        switch source {
        case .tmdb: return nil
        case .localMovie(let movie): return movie.overview
        case .localShow(let show): return show.overview
        }
    }

    private var year: Int? {
        if let year = detail?.year { return year }
        if case .localMovie(let movie) = source { return movie.releaseYear }
        if case .localShow(let show) = source { return show.firstAirDate.flatMap { Int($0.prefix(4)) } }
        return nil
    }

    private var runtimeText: String? {
        if let minutes = detail?.runtimeMinutes, minutes > 0 {
            return String(localized: "\(minutes) min")
        }
        if case .localMovie(let movie) = source, movie.duration > 0 { return movie.formattedDuration }
        return nil
    }

    private var attribution: String? { detail?.attribution }

    private var genres: [String] { detail?.genres ?? [] }

    // MARK: - Local library matches

    /// The imported movie backing this page, whether it was opened from the
    /// library or from a TMDB browse item.
    private var localMovie: Movie? {
        switch source {
        case .localMovie(let movie): movie
        case .tmdb(let ref) where ref.mediaType == .movie:
            libraryMovies.first { $0.tmdbId == ref.id }
        default: nil
        }
    }

    /// The imported show backing this page — a TMDB browse item resolves to
    /// its library match so its episodes are playable from Movies & Shows too.
    private var localShow: TVShow? {
        switch source {
        case .localShow(let show): show
        case .tmdb(let ref) where ref.mediaType == .tv:
            libraryShows.first { $0.tmdbId == ref.id }
        default: nil
        }
    }

    // MARK: - Playback

    private var isPlayable: Bool {
        localMovie != nil // shows use the per-episode play buttons below
    }

    private var playLabel: String {
        if let key = watchKey, let progress = watchStore.progress(for: key.id, mediaType: key.type),
           progress.position > 0, !progress.isCompleted {
            return String(localized: "Resume Playback")
        }
        return String(localized: "Play")
    }

    private func play() {
        Task {
            guard let movie = localMovie else { return }
            await playerSession.play(movie: movie)
        }
    }

    private func playEpisode(_ episode: Episode) {
        Task {
            await playerSession.play(episode: episode)
        }
    }

    // MARK: - MediaRef for actions

    private var mediaRef: MediaRef? {
        if let detail { return detail.ref }
        switch source {
        case .tmdb(let ref): return ref
        case .localMovie(let movie): return movie.tmdbId.map { MediaRef(id: $0, mediaType: .movie) }
        case .localShow(let show): return show.tmdbId.map { MediaRef(id: $0, mediaType: .tv) }
        }
    }

    // MARK: - Watch state

    /// TMDB id + type used for watch tracking; shows track per-episode instead.
    private var watchKey: (id: Int, type: WatchMediaType)? {
        switch source {
        case .tmdb(let ref) where ref.mediaType == .movie: (ref.id, .movie)
        case .localMovie(let movie): movie.tmdbId.map { ($0, .movie) }
        default: nil
        }
    }

    private func isWatched(_ key: (id: Int, type: WatchMediaType)) -> Bool {
        watchStore.isWatched(key.id, mediaType: key.type)
    }

    private func toggleWatched(_ key: (id: Int, type: WatchMediaType)) {
        if isWatched(key) {
            watchStore.remove(tmdbId: key.id, mediaType: key.type)
        } else {
            watchStore.markCompleted(tmdbId: key.id, mediaType: key.type)
        }
    }

    // MARK: - Layout metrics

    private var isWide: Bool {
        #if os(macOS)
        true
        #else
        horizontalSizeClass == .regular
        #endif
    }

    private var edgeMargin: CGFloat { isWide ? 48 : 20 }
    private var titleSize: CGFloat { isWide ? 80 : 48 }

    private var episodeCardWidth: CGFloat {
        #if os(tvOS)
        420
        #else
        isWide ? 300 : 250
        #endif
    }

    /// tvOS cards rest scaled-down inside full-size slots (CardFocusButtonStyle),
    /// so tighter spacing keeps the resting shelf at the same visual rhythm.
    private var episodeSpacing: CGFloat {
        #if os(tvOS)
        8
        #else
        20
        #endif
    }
}
