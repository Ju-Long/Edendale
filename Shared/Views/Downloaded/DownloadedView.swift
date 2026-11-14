//
//  DownloadedView.swift
//  Edendale
//
//  The local library: a Continue Watching shelf of half-finished titles,
//  poster grids for movies and shows, and the linked sources beneath.
//  Empty state modeled on Infuse's files screen, restyled for the archive.
//  The macOS sidebar also opens each section as a page of its own; the
//  tab bar's section picker does the same on the other platforms (see
//  DownloadedSectionPicker).
//

import SwiftUI
import SwiftData
import UniformTypeIdentifiers

/// One part of the Downloaded page shown on its own — the Downloaded row's
/// children in the macOS sidebar, and the choices of the tab bar's section
/// picker elsewhere. A `nil` section is the whole page.
enum DownloadedSection: Hashable, CaseIterable {
    case continueWatching, movies, shows

    var title: String {
        switch self {
        case .continueWatching: String(localized: "Continue Watching")
        case .movies: String(localized: "Movies")
        case .shows: String(localized: "TV Shows")
        }
    }

    /// The label in the tab bar's section picker, where four choices share
    /// one phone-width row. VoiceOver still reads `title`.
    var shortTitle: String {
        switch self {
        case .continueWatching: String(localized: "Watching")
        case .movies, .shows: title
        }
    }

    /// The section still to show once `available` has changed: one that has
    /// emptied (its last title removed, or hidden by the audience filter)
    /// falls back to the whole page.
    static func resolved(_ selection: DownloadedSection?, available: [DownloadedSection]) -> DownloadedSection? {
        guard let selection, available.contains(selection) else { return nil }
        return selection
    }

    /// Symbol asset for the section's sidebar row.
    var icon: String {
        switch self {
        case .continueWatching: "hourglass-half"
        case .movies: "film"
        case .shows: "tv"
        }
    }

    /// The sections with something to show, in sidebar order.
    static func available(
        hasResumeItems: Bool,
        movies: [Movie],
        shows: [TVShow]
    ) -> [DownloadedSection] {
        allCases.filter { section in
            switch section {
            case .continueWatching: hasResumeItems
            case .movies: !movies.isEmpty
            case .shows: !shows.isEmpty
            }
        }
    }
}

struct DownloadedView: View {
    /// Shows only this section (a macOS sidebar child row, or the tab bar's
    /// section picker); `nil` shows the whole library.
    var section: DownloadedSection? = nil
    /// The section picker's selection, when the page itself shows the
    /// picker above its content: where the tab bar can't carry it (Apple
    /// TV, Apple Vision Pro, iOS before 26.1). `nil` shows no picker here.
    var sectionSelection: Binding<DownloadedSection?>? = nil
    /// Reports how many pages are pushed on the stack, so the tab bar's
    /// picker shows only over the root page.
    var onNavigationDepthChange: ((Int) -> Void)? = nil

    @Environment(LibraryController.self) private var library
    @Environment(CloudAccountStore.self) private var cloudAccounts
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(PlayerSession.self) private var playerSession
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @Query(sort: \Movie.dateAdded, order: .reverse) private var movies: [Movie]
    @Query(sort: \TVShow.dateAdded, order: .reverse) private var shows: [TVShow]
    @Query(sort: \VideoFolder.dateAdded) private var folders: [VideoFolder]

    @State private var path = NavigationPath()
    /// Back to the top when the section picker changes the page.
    @State private var scrollPosition = ScrollPosition(edge: .top)
    @State private var showImporter = false
    @State private var showLinkSource = false
    /// A rescan requested from the menu bar (⌘R) is running. The automatic
    /// sweep on appearance stays silent.
    @State private var isRescanning = false

    private var isEmpty: Bool {
        visibleMovies.isEmpty && visibleShows.isEmpty && folders.isEmpty
    }

    private var visibleMovies: [Movie] {
        movies.filter { movie in
            guard youngAudienceFilter.isEnabled else { return true }
            guard let id = movie.tmdbId else { return false }
            return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .movie))
        }
    }

    private var visibleShows: [TVShow] {
        shows.filter { show in
            guard youngAudienceFilter.isEnabled else { return true }
            guard let id = show.tmdbId else { return false }
            return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .tv))
        }
    }

    private var audienceRefs: [MediaRef] {
        movies.compactMap { movie in
            movie.tmdbId.map { MediaRef(id: $0, mediaType: .movie) }
        } + shows.compactMap { show in
            show.tmdbId.map { MediaRef(id: $0, mediaType: .tv) }
        }
    }

    private var audienceVerificationKey: YoungAudienceVerificationKey {
        YoungAudienceVerificationKey(
            isEnabled: youngAudienceFilter.isEnabled,
            contextIdentifier: youngAudienceFilter.contextIdentifier,
            refs: audienceRefs
        )
    }

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
            .task(id: audienceVerificationKey) {
                await youngAudienceFilter.verify(audienceRefs)
            }
            // tvOS renders a large navigation title as a giant mid-screen overlay.
            #if os(macOS)
            .navigationTitle(section?.title ?? String(localized: "Downloaded"))
            // "Movies" alone could be the Watchlist page's; name the parent.
            .navigationSubtitle(section == nil ? "" : String(localized: "Downloaded"))
            #elseif !os(tvOS)
            // The section picker names the section, and the section's own
            // heading leads the page; a title repeating it read twice.
            .navigationTitle(String(localized: "Downloaded"))
            #endif
            .toolbar {
                if !isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                    #if os(tvOS)
                        Button {
                            showLinkSource = true
                        } label: {
                            Label("Link Source…", image: .link)
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
        }
        .onChange(of: path.count, initial: true) { _, depth in
            onNavigationDepthChange?(depth)
        }
        // Presented from the stack itself rather than its root page so the
        // macOS menu commands below can open them over a pushed detail page.
        // Sheet on every platform: AddNetworkSourceView brings its own
        // NavigationStack, and a second navigationDestination(isPresented:)
        // on this path-driven stack is what wedged the flow on tvOS.
        .sheet(isPresented: $showLinkSource) { AddNetworkSourceView().tvSheetInsets() }
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
        #if os(macOS)
        // File ▸ Add Media Folder… (⌘N), Link Source… (⌥⌘N), and
        // Rescan Library (⌘R) act on whichever Downloaded page is showing.
        .focusedSceneValue(\.libraryCommands, libraryCommands)
        #endif
    }

    #if os(macOS)
    /// Nothing is offered while one of the add flows is already up.
    private var libraryCommands: LibraryCommands? {
        guard !showImporter, !showLinkSource else { return nil }
        let canRescan = !folders.isEmpty && !isRescanning
        return LibraryCommands(
            addFolder: { showImporter = true },
            linkSource: { showLinkSource = true },
            rescan: canRescan ? { rescanLibrary() } : nil
        )
    }

    /// ⌘R scans every source, including remote ones the automatic sweep
    /// skipped because they were scanned recently.
    private func rescanLibrary() {
        isRescanning = true
        Task {
            await library.rescanAllFolders(force: true)
            isRescanning = false
        }
    }
    #endif

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
                if let sectionSelection {
                    let sections = availableSections
                    if DownloadedSectionPicker.isOffered(for: sections) {
                        DownloadedSectionChips(selection: sectionSelection, sections: sections, edgeMargin: edgeMargin)
                    }
                }
                statusRows
                switch section {
                case nil:
                    // Built once for the page: the shelf, and the movies the
                    // grid leaves to it.
                    let resume = resumeItems
                    continueWatchingShelf(resume)
                    moviesSection(hiding: Self.movieIDs(in: resume))
                    showsSection
                    sourcesSection
                case .continueWatching:
                    continueWatchingGrid
                case .movies:
                    moviesSection(hiding: [])
                case .shows:
                    showsSection
                }
            }
            .padding(.vertical, 24)
            .padding(.bottom, 40)
        }
        .holdsBackgroundWorkWhileScrolling()
        .scrollPosition($scrollPosition)
        .onChange(of: section) {
            scrollPosition.scrollTo(edge: .top)
        }
    }

    @ViewBuilder
    private var statusRows: some View {
        if youngAudienceFilter.isVerifying(audienceRefs) {
            HStack(spacing: 12) {
                ProgressView().tint(Theme.gold)
                Text("Verifying audience ratings").labelCaps()
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .combine)
        }

        if isRescanning {
            HStack(spacing: 12) {
                ProgressView().tint(Theme.gold)
                Text("Rescanning sources").labelCaps()
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .combine)
        }

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

    private var resumeItems: [ResumeItem] {
        ResumeItem.items(watchStore: watchStore, movies: visibleMovies, shows: visibleShows, isOffline: library.isOffline)
    }

    /// The sections with something to show: the section picker's choices.
    private var availableSections: [DownloadedSection] {
        DownloadedSection.available(
            hasResumeItems: !resumeItems.isEmpty,
            movies: visibleMovies,
            shows: visibleShows
        )
    }

    /// tmdbIds of movies already surfaced in Continue Watching, so the poster
    /// grid can drop them and avoid showing the same title (and progress bar)
    /// twice. Derived from the shelf's actual items — a movie in progress but
    /// beyond the shelf's cap still appears in the grid rather than vanishing.
    private static func movieIDs(in items: [ResumeItem]) -> Set<Int> {
        Set(items.compactMap { item in
            if case .movie(let movie) = item.payload { return movie.tmdbId }
            return nil
        })
    }

    @ViewBuilder
    private func continueWatchingShelf(_ items: [ResumeItem]) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Continue Watching"))
                    .padding(.horizontal, edgeMargin)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: shelfSpacing) {
                        ForEach(items) { item in
                            resumeCard(item)
                        }
                    }
                    .padding(.horizontal, edgeMargin)
                    .padding(.vertical, 14)
                }
                // The hover/focus glow may bleed past the shelf bounds
                // instead of being clipped to a hard edge.
                .scrollClipDisabled()
                .holdsBackgroundWorkWhileScrolling()
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Continue Watching")
        }
    }

    /// Continue Watching as a page of its own: every resumable title in a
    /// grid rather than the capped shelf.
    @ViewBuilder
    private var continueWatchingGrid: some View {
        let items = ResumeItem.items(
            watchStore: watchStore,
            movies: visibleMovies,
            shows: visibleShows,
            limit: nil,
            isOffline: library.isOffline
        )
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Continue Watching"))
                LazyVGrid(
                    columns: [GridItem(
                        .adaptive(minimum: resumeCardWidth, maximum: resumeCardWidth),
                        spacing: gridSpacing
                    )],
                    alignment: .center,
                    spacing: gridSpacing
                ) {
                    ForEach(items) { item in
                        resumeCard(item)
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Continue Watching")
        }
    }

    private func resumeCard(_ item: ResumeItem) -> some View {
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
        .accessibilityHint(item.isNextUp ? "Plays next episode." : "Resumes playback.")
        .contextMenu {
            // A next-up card has no saved position of its own.
            if !item.isNextUp {
                clearProgressButton(tmdbId: item.progress.tmdbId, mediaType: item.progress.mediaType)
            }
        }
    }

    /// Deletes the saved position, which takes the title out of Continue
    /// Watching; it syncs to other devices as a removal.
    private func clearProgressButton(tmdbId: Int, mediaType: WatchMediaType) -> some View {
        Button(role: .destructive) {
            watchStore.remove(tmdbId: tmdbId, mediaType: mediaType)
        } label: {
            Label("Clear Progress", image: .trashCan)
        }
    }

    private func resume(_ item: ResumeItem) {
        Task {
            await playerSession.play(item)
        }
    }

    // MARK: - Poster grids

    /// On the whole page, anything currently in Continue Watching is shown
    /// there with its resume progress; leaving it out here (`hiding`) keeps
    /// each movie to one card. The Movies page on its own lists every movie.
    @ViewBuilder
    private func moviesSection(hiding resumeMovieIDs: Set<Int>) -> some View {
        // One card per title: copies in other sources, or the same share
        // linked through another endpoint, are chosen on its page. Grouped
        // once here, not once per card.
        let movies = visibleMovies
        let copiesByKey = Dictionary(grouping: movies, by: { PlaybackSources.copyKey($0) })
        let titles = PlaybackSources.distinct(movies, key: { PlaybackSources.copyKey($0) })
        let gridMovies = titles.filter { movie in
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
                            if let id = movie.tmdbId, let progress = watchStore.progress(for: id, mediaType: .movie),
                               !progress.isCompleted, progress.position > 0 {
                                clearProgressButton(tmdbId: id, mediaType: .movie)
                            }
                            removeButtons(
                                copies: copies(of: movie, in: copiesByKey),
                                folder: \.folder,
                                filePath: \.filePath,
                                remove: library.removeMovie
                            )
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
        let shows = visibleShows
        let copiesByKey = Dictionary(grouping: shows, by: { PlaybackSources.copyKey($0) })
        let titles = PlaybackSources.distinct(shows, key: { PlaybackSources.copyKey($0) })
        if !titles.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "TV Shows"))
                LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
                    ForEach(titles) { show in
                        NavigationLink(value: show) {
                            PosterCard(
                                title: show.displayName,
                                subtitle: showSubtitle(show, copies: copies(of: show, in: copiesByKey)),
                                posterURL: show.posterURL,
                                placeholderIcon: "tv",
                                width: posterWidth
                            )
                        }
                        .posterButtonStyle()
                        .accessibilityHint("Opens the archive record.")
                        .contextMenu {
                            removeButtons(
                                copies: copies(of: show, in: copiesByKey),
                                folder: \.folder,
                                filePath: { $0.folder?.folderPath ?? $0.name },
                                remove: library.removeTVShow
                            )
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

    /// Every copy of the card's movie in the visible library, the card's
    /// first; `copiesByKey` is the visible movies grouped by copy key.
    private func copies(of movie: Movie, in copiesByKey: [String: [Movie]]) -> [Movie] {
        let others = (copiesByKey[PlaybackSources.copyKey(movie)] ?? []).filter { $0.id != movie.id }
        return PlaybackSources.order(primary: movie, others: others, folder: \.folder)
    }

    /// Every copy of the card's show in the visible library, the card's first.
    private func copies(of show: TVShow, in copiesByKey: [String: [TVShow]]) -> [TVShow] {
        let others = (copiesByKey[PlaybackSources.copyKey(show)] ?? []).filter { $0.id != show.id }
        return PlaybackSources.order(primary: show, others: others, folder: \.folder)
    }

    /// Remove for a title in one source; one per source when the card
    /// stands for copies in several, so removing one leaves the others.
    @ViewBuilder
    private func removeButtons<Item: Identifiable>(
        copies: [Item],
        folder: @escaping (Item) -> VideoFolder?,
        filePath: @escaping (Item) -> String,
        remove: @escaping (Item) -> Void
    ) -> some View {
        if copies.count == 1, let only = copies.first {
            Button(role: .destructive) {
                remove(only)
            } label: {
                Label("Remove", image: .trashCan)
            }
        } else {
            ForEach(copies) { copy in
                Button(role: .destructive) {
                    remove(copy)
                } label: {
                    Label(
                        "Remove from \(PlaybackSources.title(folder: folder(copy), filePath: filePath(copy)))",
                        image: .trashCan
                    )
                }
            }
        }
    }

    private func showSubtitle(_ show: TVShow, copies: [TVShow]) -> String {
        // Counted across every copy: an episode in two sources is one.
        let slots = PlaybackSources.episodeSlots(primary: show, others: Array(copies.dropFirst()))
        let seasons = Set(slots.map(\.season)).count
        let episodes = slots.count
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
                                #if !os(tvOS)
                                if let account = googleAccount(for: folder) {
                                    Button {
                                        chooseMoreVideos(account)
                                    } label: {
                                        Label("Choose More Videos", image: .plus)
                                    }
                                }
                                #endif
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

    /// The linked account behind a Google Drive source, if it's still here.
    private func googleAccount(for folder: VideoFolder) -> CloudAccount? {
        guard folder.sourceKind == .googleDrive, let key = folder.accountKey else { return nil }
        return cloudAccounts.accounts(of: .googleDrive).first { $0.key == key }
    }

    #if !os(tvOS)
    /// Google's picker again; the source's row then says what was added.
    private func chooseMoreVideos(_ account: CloudAccount) {
        Task {
            do {
                try await library.chooseMoreVideos(for: account, accounts: cloudAccounts)
            } catch OAuthError.cancelled {
            } catch {
                library.errorMessage = error.localizedDescription
            }
        }
    }
    #endif

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

/// A half-watched or next-up progress record joined back to its local
/// library item. Next-up items have no persisted progress — they surface
/// the successor episode after a completed one without writing watch data.
struct ResumeItem: Identifiable {
    enum Payload {
        case movie(Movie)
        case episode(Episode)
    }

    let progress: WatchProgress
    let payload: Payload
    let isNextUp: Bool

    init(progress: WatchProgress, payload: Payload, isNextUp: Bool = false) {
        self.progress = progress
        self.payload = payload
        self.isNextUp = isNextUp
    }

    /// Continue Watching for the given (audience-filtered) library, most
    /// recently watched first: movies in progress, each show's in-progress
    /// episode, or the stored episode after its furthest completed one.
    /// A title in several sources is one card, played from the copy the
    /// viewer played last unless its source is offline. `limit` caps the
    /// shelf; `nil` returns every item.
    @MainActor
    static func items(
        watchStore: WatchProgressStore,
        movies: [Movie],
        shows: [TVShow],
        limit: Int? = 12,
        isOffline: (VideoFolder?) -> Bool = { _ in false }
    ) -> [ResumeItem] {
        var items: [ResumeItem] = []

        for progress in watchStore.inProgress where progress.mediaType == .movie {
            let copies = movies.filter { $0.tmdbId == progress.tmdbId }
            if let movie = PlaybackSources.preferred(copies, isOffline: { isOffline($0.folder) }, lastPlayed: \.lastPlayedAt) {
                items.append(ResumeItem(progress: progress, payload: .movie(movie)))
            }
        }

        var showIDs: [Int] = []
        var copiesByShow: [Int: [TVShow]] = [:]
        for show in shows {
            guard let showId = show.tmdbId else { continue }
            if copiesByShow[showId] == nil { showIDs.append(showId) }
            copiesByShow[showId, default: []].append(show)
        }

        for showId in showIDs {
            let copies = copiesByShow[showId] ?? []
            // The copy played last (its source) first, then the others.
            let preferred = PlaybackSources.preferred(copies, isOffline: { isOffline($0.folder) }, lastPlayed: \.lastPlayedAt)
            let ordered = copies.filter { $0.id == preferred?.id } + copies.filter { $0.id != preferred?.id }
            func episode(where matches: (Episode) -> Bool) -> Episode? {
                for copy in ordered {
                    if let episode = copy.episodes.first(where: matches) { return episode }
                }
                return nil
            }

            guard let latest = watchStore.progressMap.values
                .filter({ $0.mediaType == .episode && $0.showTmdbId == showId })
                .max(by: { $0.lastWatchedAt < $1.lastWatchedAt })
            else { continue }

            if !latest.isCompleted, latest.position > 0,
               let ep = episode(where: { $0.tmdbId == latest.tmdbId }) {
                items.append(ResumeItem(progress: latest, payload: .episode(ep)))
            } else if latest.isCompleted,
                      let season = latest.seasonNumber,
                      let epNum = latest.episodeNumber,
                      // The earliest stored episode after it on any copy…
                      let nextOrder = ordered.flatMap(\.episodes)
                          .map({ ($0.seasonNumber, $0.episodeNumber) })
                          .filter({ $0 > (season, epNum) })
                          .min(by: { $0 < $1 }),
                      // …from the preferred copy that has it.
                      let next = episode(where: { ($0.seasonNumber, $0.episodeNumber) == nextOrder }) {
                items.append(ResumeItem(
                    progress: WatchProgress(
                        tmdbId: next.tmdbId ?? 0,
                        mediaType: .episode,
                        position: 0,
                        showTmdbId: showId,
                        seasonNumber: next.seasonNumber,
                        episodeNumber: next.episodeNumber,
                        lastWatchedAt: latest.lastWatchedAt
                    ),
                    payload: .episode(next),
                    isNextUp: true
                ))
            }
        }

        let sorted = items.sorted {
            if $0.progress.lastWatchedAt == $1.progress.lastWatchedAt {
                return $0.id < $1.id
            }
            return $0.progress.lastWatchedAt > $1.progress.lastWatchedAt
        }
        guard let limit else { return sorted }
        return Array(sorted.prefix(limit))
    }

    /// The Continue Watching item for one title — the movie's resume point,
    /// or the show's latest-watched episode (the one after it once that is
    /// finished) — from the copy the shelf would play. `nil` when nothing
    /// in the library resumes it. `movies` and `shows` may hold any titles.
    @MainActor
    static func latest(
        for ref: MediaRef,
        watchStore: WatchProgressStore,
        movies: [Movie],
        shows: [TVShow],
        isOffline: (VideoFolder?) -> Bool = { _ in false }
    ) -> ResumeItem? {
        switch ref.mediaType {
        case .movie:
            items(
                watchStore: watchStore,
                movies: movies.filter { $0.tmdbId == ref.id },
                shows: [],
                limit: nil,
                isOffline: isOffline
            ).first
        case .tv:
            items(
                watchStore: watchStore,
                movies: [],
                shows: shows.filter { $0.tmdbId == ref.id },
                limit: nil,
                isOffline: isOffline
            ).first
        }
    }

    var id: String {
        if isNextUp, case .episode(let ep) = payload {
            return "nextup-\(ep.id)"
        }
        return "\(progress.mediaType.rawValue)-\(progress.tmdbId)"
    }

    var title: String {
        switch payload {
        case .movie(let movie): movie.displayTitle
        case .episode(let episode): episode.show?.displayName ?? episode.displayTitle
        }
    }

    var subtitle: String {
        switch payload {
        case .movie: return String(localized: "\(Int(progress.position * 100))% watched")
        case .episode(let episode):
            if isNextUp {
                return String(localized: "Up Next · \(episode.episodeCode)")
            }
            return "\(episode.episodeCode) · \(episode.displayTitle)"
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
    @Environment(LibraryController.self) private var library
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
                if let status {
                    Text(status)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.gold)
                        .fixedSize(horizontal: false, vertical: true)
                }
                #if os(tvOS)
                if folder.sourceKind == .googleDrive {
                    Text(GoogleDriveConnector.chooseOnAnotherDeviceNote)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                #endif
            }
            Spacer()
        }
        .padding(.vertical, 12)
        // Icon, name, and counts are one source; Rescan and Remove arrive as
        // custom actions from the context menu attached by the caller.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(folder.name)
        .accessibilityValue([subtitle, status].compactMap(\.self).joined(separator: ", "))
    }

    /// Why the source couldn't be scanned, or what Choose More Videos did.
    private var status: String? {
        library.sourceStates[folder.id]?.message ?? library.sourceNotes[folder.id]
    }

    private var subtitle: String {
        var parts = [folder.totalItemCount == 1
            ? String(localized: "1 item")
            : String(localized: "\(folder.totalItemCount) items")]
        if folder.isRemote {
            parts.append([folder.sourceKind.displayName, accountOrHost].compactMap(\.self).joined(separator: " · "))
        }
        return parts.joined(separator: " · ")
    }

    /// The server, or for accounts and buckets the account, never the
    /// account-key hash their URLs use as a host.
    private var accountOrHost: String? {
        switch folder.sourceKind {
        case .googleDrive, .oneDrive, .dropbox, .s3: folder.username
        default: folder.remoteURL?.host()
        }
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
        String(localized: "Local folders aren’t reachable on Apple TV. Link a network share or cloud storage to build your library.")
        #else
        String(localized: "Connect your local film collection to begin your cinematic journey.")
        #endif
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 16) {
            #if os(tvOS)
            Button(action: linkAction) {
                Label("Link Source", image: .link)
            }
            .archiveButtonStyle(.primary)
            #else
            Button(action: addAction) {
                Label("Add Media Folder", image: .folderCirclePlus)
            }
            .archiveButtonStyle(.primary)

            Button(action: linkAction) {
                Label("Link Source", image: .link)
            }
            .archiveButtonStyle(.secondary)
            #endif

            Button("Learn About Syncing") { showSyncInfo = true }
                .archiveButtonStyle(.ghost)
        }
    }
}
