//
//  DownloadedView.swift
//  Edendale
//
//  The local library: a Continue Watching shelf of half-finished titles,
//  poster grids for movies and shows, and the linked sources beneath.
//  Empty state modeled on Infuse's files screen, restyled for the archive.
//

import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct DownloadedView: View {
    @Environment(LibraryController.self) private var library
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(PlayerSession.self) private var playerSession
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @Query(sort: \Movie.dateAdded, order: .reverse) private var movies: [Movie]
    @Query(sort: \TVShow.dateAdded, order: .reverse) private var shows: [TVShow]
    @Query(sort: \VideoFolder.dateAdded) private var folders: [VideoFolder]

    @State private var path = NavigationPath()
    @State private var showImporter = false
    @State private var showLinkSource = false

    private var isEmpty: Bool { movies.isEmpty && shows.isEmpty && folders.isEmpty }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if isEmpty {
                    EmptyLibraryState(
                        addAction: { showImporter = true },
                        linkAction: { showLinkSource = true }
                    )
                } else {
                    libraryContent
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
            // Re-scan every linked source each time the library appears so
            // files added outside the app surface without a manual rescan.
            // The @Query rows refresh automatically once new items are saved.
            .task { await library.rescanAllFolders() }
            // tvOS renders a large navigation title as a giant mid-screen overlay.
            #if !os(tvOS)
            .navigationTitle("Downloaded")
            #endif
            .toolbar {
                if !isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                    #if os(tvOS)
                        Button {
                            showLinkSource = true
                        } label: {
                            Label("Link Network Source…", image: .link)
                        }
                        .archiveButtonStyle(.ghost)
                    #else
                        addMenu
                    #endif
                    }
                }
            }
            .navigationDestination(for: Movie.self) { MediaDetailView(source: .localMovie($0)) }
            .navigationDestination(for: TVShow.self) { MediaDetailView(source: .localShow($0)) }
            .navigationDestination(for: PersonRef.self) { PersonDetailView(person: $0) }
            .settingsToolbar()
            // Sheet on every platform: AddNetworkSourceView brings its own
            // NavigationStack, and a second navigationDestination(isPresented:)
            // on this path-driven stack is what wedged the flow on tvOS.
            .sheet(isPresented: $showLinkSource) { AddNetworkSourceView() }
            #if !os(tvOS)
            .fileImporter(
                isPresented: $showImporter,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: true
            ) { result in
                guard case .success(let urls) = result else { return }
                Task {
                    for url in urls {
                        await library.importFolder(url: url)
                    }
                }
            }
            #endif
        }
    }

    // MARK: - Toolbar

    private var addMenu: some View {
        Menu {
            Button {
                showImporter = true
            } label: {
                Label("Add Media Folder…", image: .folderCirclePlus)
            }
            Button {
                showLinkSource = true
            } label: {
                Label("Link Source…", image: .link)
            }
        } label: {
            Image(.plus)
        }
        .accessibilityLabel("Add Source")
    }

    // MARK: - Library content

    private var libraryContent: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 40) {
                statusRows
                continueWatchingShelf
                moviesSection
                showsSection
                sourcesSection
            }
            .padding(.vertical, 24)
            .padding(.bottom, 40)
        }
    }

    @ViewBuilder
    private var statusRows: some View {
        if library.isImporting || library.isEnriching {
            HStack(spacing: 12) {
                ProgressView().tint(Theme.gold)
                Text(
                    library.isImporting
                        ? String(localized: "Cataloguing new files")
                        : String(localized: "Enriching metadata")
                )
                    .labelCaps()
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .combine)
        }

        if let message = library.errorMessage {
            Text(message)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, edgeMargin)
        }
    }

    // MARK: - Continue watching

    /// Everything half-watched that maps back to a local file, newest first.
    private var resumeItems: [ResumeItem] {
        let items: [ResumeItem] = watchStore.inProgress.compactMap { progress in
            switch progress.mediaType {
            case .movie:
                return movies.first { $0.tmdbId == progress.tmdbId }
                    .map { ResumeItem(progress: progress, payload: .movie($0)) }
            case .episode:
                return shows.lazy.flatMap(\.episodes).first { $0.tmdbId == progress.tmdbId }
                    .map { ResumeItem(progress: progress, payload: .episode($0)) }
            }
        }
        return Array(items.prefix(12))
    }

    /// tmdbIds of movies already surfaced in Continue Watching, so the poster
    /// grid can drop them and avoid showing the same title (and progress bar)
    /// twice. Derived from the shelf's actual items — a movie in progress but
    /// beyond the shelf's cap still appears in the grid rather than vanishing.
    private var resumeMovieIDs: Set<Int> {
        Set(resumeItems.compactMap { item in
            if case .movie(let movie) = item.payload { return movie.tmdbId }
            return nil
        })
    }

    @ViewBuilder
    private var continueWatchingShelf: some View {
        let items = resumeItems
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Continue Watching"))
                    .padding(.horizontal, edgeMargin)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: shelfSpacing) {
                        ForEach(items) { item in
                            Button {
                                resume(item)
                            } label: {
                                LandscapeCard(
                                    title: item.title,
                                    subtitle: item.subtitle,
                                    imageURL: item.imageURL,
                                    placeholderIcon: item.placeholderIcon,
                                    width: resumeCardWidth,
                                    progress: item.progress.position
                                )
                            }
                            #if os(tvOS)
                            .buttonStyle(CardFocusButtonStyle())
                            #else
                            .buttonStyle(.plain)
                            #endif
                            .accessibilityHint("Resumes playback.")
                        }
                    }
                    .padding(.horizontal, edgeMargin)
                    .padding(.vertical, 14)
                }
                // The hover/focus glow may bleed past the shelf bounds
                // instead of being clipped to a hard edge.
                .scrollClipDisabled()
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Continue Watching")
        }
    }

    private func resume(_ item: ResumeItem) {
        Task {
            switch item.payload {
            case .movie(let movie): await playerSession.play(movie: movie)
            case .episode(let episode): await playerSession.play(episode: episode)
            }
        }
    }

    // MARK: - Poster grids

    @ViewBuilder
    private var moviesSection: some View {
        // Anything currently in Continue Watching is shown there with its
        // resume progress; leaving it out here keeps each movie to one card.
        let gridMovies = movies.filter { movie in
            guard let id = movie.tmdbId else { return true }
            return !resumeMovieIDs.contains(id)
        }
        if !gridMovies.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Movies"))
                LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
                    ForEach(gridMovies) { movie in
                        NavigationLink(value: movie) {
                            PosterCard(
                                title: movie.displayTitle,
                                subtitle: movieSubtitle(movie),
                                posterURL: movie.posterURL,
                                placeholderIcon: "film",
                                width: posterWidth,
                                isWatched: movieWatched(movie)
                            )
                        }
                        .posterButtonStyle()
                        .accessibilityHint("Opens the archive record.")
                        .contextMenu {
                            Button(role: .destructive) {
                                library.removeMovie(movie)
                            } label: {
                                Label("Remove", image: .trashCan)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Movies")
        }
    }

    @ViewBuilder
    private var showsSection: some View {
        if !shows.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "TV Shows"))
                LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
                    ForEach(shows) { show in
                        NavigationLink(value: show) {
                            PosterCard(
                                title: show.displayName,
                                subtitle: showSubtitle(show),
                                posterURL: show.posterURL,
                                placeholderIcon: "tv",
                                width: posterWidth
                            )
                        }
                        .posterButtonStyle()
                        .accessibilityHint("Opens the archive record.")
                        .contextMenu {
                            Button(role: .destructive) {
                                library.removeTVShow(show)
                            } label: {
                                Label("Remove", image: .trashCan)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("TV Shows")
        }
    }

    private func movieSubtitle(_ movie: Movie) -> String {
        var parts: [String] = []
        if let year = movie.releaseYear { parts.append(String(year)) }
        if movie.duration > 0 { parts.append(movie.formattedDuration) }
        if parts.isEmpty { parts.append(String(localized: "Awaiting metadata")) }
        return parts.joined(separator: " · ")
    }

    private func showSubtitle(_ show: TVShow) -> String {
        let seasons = show.availableSeasons.count
        let episodes = show.episodes.count
        let seasonText = seasons == 1
            ? String(localized: "1 season")
            : String(localized: "\(seasons) seasons")
        let episodeText = episodes == 1
            ? String(localized: "1 episode")
            : String(localized: "\(episodes) episodes")
        return "\(seasonText) · \(episodeText)"
    }

    private func movieWatched(_ movie: Movie) -> Bool {
        guard let id = movie.tmdbId else { return false }
        return watchStore.isWatched(id, mediaType: .movie)
    }

    // MARK: - Sources

    @ViewBuilder
    private var sourcesSection: some View {
        if !folders.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Sources"))
                VStack(spacing: 0) {
                    ForEach(folders) { folder in
                        FolderRow(folder: folder)
                            .contextMenu {
                                Button {
                                    Task { await library.rescanFolder(folder) }
                                } label: {
                                    Label("Rescan", image: .arrowRotateRight)
                                }
                                Button(role: .destructive) {
                                    library.removeFolder(folder)
                                } label: {
                                    Label("Remove", image: .trashCan)
                                }
                            }
                            .overlay(alignment: .bottom) {
                                Rectangle()
                                    .fill(Theme.hairline)
                                    .frame(height: 1)
                                    .accessibilityHidden(true)
                            }
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Sources")
        }
    }

    // MARK: - Layout metrics

    private var edgeMargin: CGFloat {
        #if os(macOS) || os(tvOS)
        48
        #else
        horizontalSizeClass == .regular ? 48 : 20
        #endif
    }

    private var posterWidth: CGFloat {
        #if os(macOS)
        180
        #elseif os(tvOS)
        // Bigger targets read well across the room and give the focus effect
        // something substantial to lift.
        240
        #else
        horizontalSizeClass == .regular ? 180 : 140
        #endif
    }

    private var resumeCardWidth: CGFloat {
        #if os(macOS)
        320
        #elseif os(tvOS)
        420
        #else
        horizontalSizeClass == .regular ? 320 : 260
        #endif
    }

    /// Fixed-width columns so PosterCard's set width fills each cell exactly.
    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: gridSpacing)]
    }

    /// tvOS cells rest scaled-down inside full-size slots (CardFocusButtonStyle),
    /// which adds its own visual breathing room between cards.
    private var gridSpacing: CGFloat {
        #if os(tvOS)
        24
        #else
        20
        #endif
    }

    private var shelfSpacing: CGFloat {
        #if os(tvOS)
        8
        #else
        20
        #endif
    }
}

// MARK: - Continue watching item

/// A half-watched progress record joined back to its local library item.
private struct ResumeItem: Identifiable {
    enum Payload {
        case movie(Movie)
        case episode(Episode)
    }

    let progress: WatchProgress
    let payload: Payload

    var id: String { "\(progress.mediaType.rawValue)-\(progress.tmdbId)" }

    var title: String {
        switch payload {
        case .movie(let movie): movie.displayTitle
        case .episode(let episode): episode.show?.displayName ?? episode.displayTitle
        }
    }

    var subtitle: String {
        switch payload {
        case .movie: String(localized: "\(Int(progress.position * 100))% watched")
        case .episode(let episode): "\(episode.episodeCode) · \(episode.displayTitle)"
        }
    }

    var imageURL: URL? {
        switch payload {
        case .movie(let movie): movie.backdropURL
        case .episode(let episode): episode.stillURL ?? episode.show?.backdropURL
        }
    }

    var placeholderIcon: String {
        switch payload {
        case .movie: "film"
        case .episode: "tv"
        }
    }
}

// MARK: - Poster button style

private extension View {
    /// tvOS: reserved-bounds focus — the card rests small and expands to
    /// fill its slot, so the growth can never clip and no row highlight is
    /// painted behind it. `.plain` elsewhere avoids extra chrome.
    @ViewBuilder
    func posterButtonStyle() -> some View {
        #if os(tvOS)
        self.buttonStyle(CardFocusButtonStyle())
        #else
        self.buttonStyle(.plain)
        #endif
    }
}

// MARK: - Rows

private struct FolderRow: View {
    let folder: VideoFolder

    var body: some View {
        HStack(spacing: 14) {
            // The glyph only restates the kind already named in `subtitle`.
            Image(folder.isRemote ? .link : .folderOpen)
                .font(.system(size: 18))
                .foregroundStyle(Theme.textSecondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(folder.name)
                    .font(Typography.text(15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(subtitle)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer()
        }
        .padding(.vertical, 12)
        // Icon, name, and counts are one source; Rescan and Remove arrive as
        // custom actions from the context menu attached by the caller.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(folder.name)
        .accessibilityValue(subtitle)
    }

    private var subtitle: String {
        var parts = [folder.totalItemCount == 1
            ? String(localized: "1 item")
            : String(localized: "\(folder.totalItemCount) items")]
        if folder.isRemote {
            let host = folder.remoteURL?.host()
            parts.append([folder.sourceKind.displayName, host].compactMap(\.self).joined(separator: " · "))
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Empty state

private struct EmptyLibraryState: View {
    let addAction: () -> Void
    let linkAction: () -> Void
    @State private var showSyncInfo = false

    var body: some View {
        VStack(spacing: 32) {
            ZStack {
                RoundedRectangle(cornerRadius: Theme.Radius.glass)
                    .fill(Theme.surfaceLow)
                Image(.clapperboard)
                    .font(.system(size: 72))
                    .foregroundStyle(Theme.surfaceHigh)
            }
            .frame(width: 240, height: 220)
            // The archival illustration; the heading below carries the message.
            .accessibilityHidden(true)

            VStack(spacing: 12) {
                Text("The Library\nIs Silent")
                    .font(Typography.display(56))
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                Text(bodyMessage)
                    .font(Typography.bodyLG)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 340)
            }
            // Heading and supporting copy are one empty state; the actions
            // below stay separate controls.
            .accessibilityElement(children: .combine)

            actions
        }
        .padding(48)
        .alert("Private by Design", isPresented: $showSyncInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Your library index stays on this device. Watch progress syncs privately through your own iCloud — nothing is sent anywhere else.")
        }
    }

    /// tvOS has no user-browsable local folders, so network shares are the
    /// only way in there.
    private var bodyMessage: String {
        #if os(tvOS)
        String(localized: "Local folders aren’t reachable on Apple TV. Link a network share (SMB) to build your library.")
        #else
        String(localized: "Connect your local film collection to begin your cinematic journey.")
        #endif
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 16) {
            #if os(tvOS)
            Button(action: linkAction) {
                Label("Link Network Source", image: .link)
            }
            .archiveButtonStyle(.primary)
            #else
            Button(action: addAction) {
                Label("Add Media Folder", image: .folderCirclePlus)
            }
            .archiveButtonStyle(.primary)

            Button(action: linkAction) {
                Label("Link Network Source", image: .link)
            }
            .archiveButtonStyle(.secondary)
            #endif

            Button("Learn About Syncing") { showSyncInfo = true }
                .archiveButtonStyle(.ghost)
        }
    }
}
