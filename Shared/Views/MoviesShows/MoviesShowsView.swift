//
//  MoviesShowsView.swift
//  Edendale
//
//  The TMDB-driven library front page: a swipeable hero pager (Continue
//  Watching + every trending title, auto-advancing after `heroSceneDuration`),
//  poster shelves, and chip-filtered curated collections. Per-platform rules
//  pause the auto-advance — see `heroRotationPaused` and `tickHeroRotation()`.
//

import SwiftUI
import SwiftData
import Combine

/// Seconds each hero scene stays up before rotating to the next.
private let heroSceneDuration: Double = 10
/// How often the timer checks whether the scene is over.
private let heroTickInterval: Double = 0.1

/// Wall-clock bookkeeping for the hero's scene dwell. The timer only
/// *reads* it (mutations happen on pause/resume/scene changes), so ticks
/// never invalidate the view tree.
private struct HeroSceneClock {
    private var sceneStart = Date()
    private var pausedAccumulated: TimeInterval = 0
    private var pauseStart: Date?

    /// 0 → 1 across the scene's life; frozen while paused.
    func progress(at now: Date = Date()) -> Double {
        let pausedTime = pausedAccumulated + (pauseStart.map { now.timeIntervalSince($0) } ?? 0)
        let elapsed = now.timeIntervalSince(sceneStart) - pausedTime
        return min(max(elapsed / heroSceneDuration, 0), 1)
    }

    mutating func setPaused(_ paused: Bool, at now: Date = Date()) {
        if paused {
            if pauseStart == nil { pauseStart = now }
        } else if let start = pauseStart {
            pausedAccumulated += now.timeIntervalSince(start)
            pauseStart = nil
        }
    }

    /// Fresh dwell for a new scene; stays paused if currently paused.
    mutating func restart(at now: Date = Date()) {
        sceneStart = now
        pausedAccumulated = 0
        if pauseStart != nil { pauseStart = now }
    }
}

#if os(macOS)
/// Geometry needed to keep native trackpad paging, mouse dragging, and the
/// selected hero index in sync without relying on macOS 26-only APIs.
private struct HeroScrollMetrics: Equatable {
    var offset: CGFloat = 0
    var range: CGFloat = 0
    var pageWidth: CGFloat = 0
}
#endif

struct MoviesShowsView: View {
    @Environment(MoviesShowsModel.self) private var model
    @Environment(TMDBAccountStore.self) private var tmdbAccount
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    @Environment(\.modelContext) private var modelContext
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @Environment(PlayerSession.self) private var playerSession
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var path = NavigationPath()

    // MARK: Hero rotation state

    private let heroTimer = Timer.publish(every: heroTickInterval, on: .main, in: .common).autoconnect()

    @State private var heroIndex = 0
    #if !os(macOS)
    /// A programmatic forward wrap (last page → duplicate first page) is
    /// mid-animation; holds off the duplicate-page snap until it completes.
    @State private var isHeroWrapping = false
    #endif
    /// Source of truth for the scene dwell; see HeroSceneClock.
    @State private var heroClock = HeroSceneClock()
    /// Whether the downloaded library holds the current scene's title.
    @State private var heroInLibrary = false
    @State private var heroTrailer: TMDBVideo?
    @State private var heroTrailerUnavailable = false
    @State private var showingTrailer = false
    #if os(macOS)
    @State private var isHoveringHero = false
    @State private var isDraggingHero = false
    @State private var isHeroScrolling = false

    @State private var heroDragStartOffset: CGFloat?
    @State private var pendingHeroIndex: Int?
    @State private var heroScrollPosition = ScrollPosition(idType: Int.self)
    @State private var heroScrollMetrics = HeroScrollMetrics()
    #elseif os(iOS)
    @State private var isPressingHero = false
    #elseif os(tvOS)
    /// Whether the hero button currently has focus.
    @FocusState private var heroFocused: Bool
    /// The user has moved focus off the hero at least once. The trailer only
    /// starts on a deliberate *return* to the hero — never on the initial
    /// focus the page lands with.
    @State private var hasLeftHero = false
    #endif

    // MARK: Zoom transition state

    @Namespace private var zoomNamespace
    /// Shelf card the last push started from; nil for non-shelf pushes.
    @State private var zoomSource: ShelfZoomID?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch model.phase {
                case .idle, .loading:
                    ArchiveLoadingState()
                case .missingKey:
                    MissingKeyState()
                case .failed(let message):
                    ArchiveErrorState(message: message) {
                        Task { await model.retry(watchStore: watchStore) }
                    }
                case .loaded:
                    content
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
            #if !os(tvOS)
            .navigationTitle("Movies & Shows")
            #endif
            #if !os(tvOS) && !os(macOS)
            .navigationBarTitleDisplayMode(.automatic)
            #endif
            .navigationDestination(for: MediaRef.self) { ref in
                // tvOS skips the zoom transition: its `matchedTransitionSource`
                // clips shelf cards to their bounds, cutting off the focus lift.
                #if os(macOS) || os(tvOS)
                MediaDetailView(source: .tmdb(ref))
                #else
                if let zoomSource {
                    MediaDetailView(source: .tmdb(ref))
                        .navigationTransition(.zoom(sourceID: zoomSource, in: zoomNamespace))
                } else {
                    MediaDetailView(source: .tmdb(ref))
                }
                #endif
            }
            .navigationDestination(for: PersonRef.self) { PersonDetailView(person: $0) }
            .settingsToolbar()
        }
        .task { await model.load(watchStore: watchStore) }
        .task(id: audienceVerificationKey) {
            await youngAudienceFilter.verify(audienceRefs)
        }
        .onChange(of: visibleHeroRefs) { _, _ in
            heroIndex = 0
        }
    }

    // MARK: - Content

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 48) {
                if youngAudienceFilter.isVerifying(audienceRefs) {
                    HStack(spacing: 12) {
                        ProgressView().tint(Theme.gold)
                        Text("Verifying audience ratings").labelCaps()
                    }
                    .padding(.horizontal, edgeMargin)
                    .accessibilityElement(children: .combine)
                }

                if !heroScenes.isEmpty {
                    heroPager
                }

                if tmdbAccount.isSignedIn {
                    shelf(String(localized: "Trending"), items: youngAudienceFilter.visible(model.trending))
                }

                shelf(String(localized: "Popular Films"), items: youngAudienceFilter.visible(model.popularMovies))
                shelf(String(localized: "Popular Series"), items: youngAudienceFilter.visible(model.popularShows))
                shelf(String(localized: "Top Rated"), items: youngAudienceFilter.visible(model.topRated))
                collectionsSection
            }
            .padding(.bottom, 64)
        }
        #if !os(tvOS)
        .onReceive(heroTimer) { _ in tickHeroRotation() }
        #endif
    }

    // MARK: - Hero

    private var heroScenes: [MoviesShowsModel.Hero] {
        model.heroScenes.filter { youngAudienceFilter.allows($0.detail.ref) }
    }

    private var visibleHeroRefs: [MediaRef] {
        heroScenes.map { $0.detail.ref }
    }

    private var audienceRefs: [MediaRef] {
        model.heroScenes.map { $0.detail.ref }
            + model.trending.map(\.ref)
            + model.popularMovies.map(\.ref)
            + model.popularShows.map(\.ref)
            + model.topRated.map(\.ref)
            + model.collectionItems.map(\.ref)
    }

    private var audienceVerificationKey: YoungAudienceVerificationKey {
        YoungAudienceVerificationKey(
            isEnabled: youngAudienceFilter.isEnabled,
            contextIdentifier: youngAudienceFilter.contextIdentifier,
            refs: audienceRefs
        )
    }

    private var currentHero: MoviesShowsModel.Hero? {
        let scenes = heroScenes
        guard !scenes.isEmpty else { return nil }
        // heroIndex can sit on a duplicate edge page (-1 or count) mid-wrap;
        // Swift's % keeps the dividend's sign, so normalize before subscripting.
        let count = scenes.count
        return scenes[(heroIndex % count + count) % count]
    }

    /// The hero as a swipeable pager: every scene is a full hero page.
    /// iOS/tvOS/visionOS use a page-style TabView. macOS keeps a native
    /// paging ScrollView for trackpads and layers on mouse dragging.
    /// Every platform shows the shared `HeroPageIndicator` below its pager.
    /// The pager loops in both directions: duplicates of the last and first
    /// scenes sit at the edges, and landing on them snaps (invisibly) to the
    /// real pages.
    /// Per-scene state (clock, trailer, library check) is keyed off the
    /// *selected* scene here — pages all exist at once, so it can't live
    /// on the individual page like the old single-scene hero.
    private var heroPager: some View {
        Group {
            #if os(macOS)
            VStack(spacing: 16) {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 0) {
                        // Duplicate last page: paging backward past the first
                        // scene lands here, then snaps to the real last page.
                        if heroScenes.count > 1, let last = heroScenes.last {
                            heroSurface(last)
                                .containerRelativeFrame(.horizontal)
                                .id(-1)
                        }

                        ForEach(Array(heroScenes.enumerated()), id: \.offset) { index, hero in
                            heroSurface(hero)
                                .containerRelativeFrame(.horizontal)
                                .id(index)
                        }

                        // Duplicate first page: paging forward past the last
                        // scene lands here, then snaps to the real page 0.
                        if heroScenes.count > 1, let first = heroScenes.first {
                            heroSurface(first)
                                .containerRelativeFrame(.horizontal)
                                .id(heroScenes.count)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition($heroScrollPosition, anchor: .center)
                .onScrollGeometryChange(for: HeroScrollMetrics.self) { geometry in
                    HeroScrollMetrics(
                        offset: max(geometry.contentOffset.x + geometry.contentInsets.leading, 0),
                        range: max(geometry.contentSize.width - geometry.containerSize.width, 0),
                        pageWidth: geometry.containerSize.width
                    )
                } action: { _, metrics in
                    heroScrollMetrics = metrics
                    guard pendingHeroIndex == nil, !isDraggingHero,
                          let page = nearestHeroIndex(for: metrics)
                    else { return }
                    // Duplicate edge pages resolve to their real counterparts.
                    let count = heroScenes.count
                    let index = (page % count + count) % count
                    if index != heroIndex { heroIndex = index }
                }
                .onScrollPhaseChange { _, phase in
                    heroScrollPhaseDidChange(phase)
                }
                .contentShape(Rectangle())
                .highPriorityGesture(heroMouseDragGesture, isEnabled: !showingTrailer)
                .frame(height: heroHeight)

                if heroScenes.count > 1 {
                    HeroPageIndicator(
                        items: heroIndicatorItems,
                        selection: heroIndicatorSelection,
                        onSelect: { selectHeroScene($0) }
                    )
                    .padding(.horizontal, edgeMargin)
                }
            }
            .onHover { isHoveringHero = $0 }
            #else
            VStack(spacing: 16) {
                TabView(selection: $heroIndex) {
                    // Duplicate last page: swiping backward past the first
                    // scene lands here, then snaps to the real last page.
                    if heroScenes.count > 1, let last = heroScenes.last {
                        heroSurface(last)
                            .tag(-1)
                    }

                    ForEach(Array(heroScenes.enumerated()), id: \.offset) { index, hero in
                        heroSurface(hero)
                            .tag(index)
                    }

                    // Duplicate first page: swiping forward past the last
                    // scene lands here, then snaps to the real page 0.
                    if heroScenes.count > 1, let first = heroScenes.first {
                        heroSurface(first)
                            .tag(heroScenes.count)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(height: heroHeight)
                .onChange(of: heroIndex) { _, newValue in
                    // A manual swipe landed on a duplicate edge page; a
                    // programmatic wrap snaps in its animation completion.
                    guard !isHeroWrapping else { return }
                    if newValue == heroScenes.count {
                        snapHeroToStart()
                    } else if newValue == -1 {
                        snapHeroToEnd()
                    }
                }

                if heroScenes.count > 1 {
                    HeroPageIndicator(
                        items: heroIndicatorItems,
                        selection: heroIndicatorSelection,
                        onSelect: { selectHeroScene($0) }
                    )
                    .padding(.horizontal, edgeMargin)
                }
            }
            #endif
        }
        .task(id: currentHero?.detail.ref) {
            if let currentHero { resetScene(for: currentHero) }
        }
        .onChange(of: heroRotationPaused) { _, paused in
            heroClock.setPaused(paused)
        }
    }

    /// Dot to highlight — duplicate edge pages (-1 or count) read as their
    /// real counterparts mid-wrap.
    private var heroIndicatorSelection: Int {
        guard !heroScenes.isEmpty else { return 0 }
        let count = heroScenes.count
        return (heroIndex % count + count) % count
    }

    private var heroIndicatorItems: [HeroPageIndicatorItem] {
        heroScenes.enumerated().map { index, hero in
            HeroPageIndicatorItem(
                id: index,
                title: hero.detail.title,
                posterURL: TMDBImage.url(hero.detail.posterPath, size: .poster),
                placeholderIcon: hero.detail.ref.mediaType == .tv ? "tv" : "film"
            )
        }
    }

    #if os(macOS)
    /// Mouse drags drive the same scroll position as native trackpad paging.
    /// A nonzero threshold preserves ordinary clicks on the hero's buttons;
    /// once the drag wins, high priority prevents an accidental button action.
    private var heroMouseDragGesture: some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .local)
            .onChanged { value in
                let horizontal = abs(value.translation.width) > abs(value.translation.height)
                guard heroDragStartOffset != nil || horizontal,
                      heroScrollMetrics.pageWidth > 0
                else { return }

                if heroDragStartOffset == nil {
                    heroDragStartOffset = heroScrollMetrics.offset
                    isDraggingHero = true
                    pendingHeroIndex = nil
                }

                guard let startOffset = heroDragStartOffset else { return }
                let offset = min(
                    max(startOffset - value.translation.width, 0),
                    heroScrollMetrics.range
                )
                heroScrollPosition.scrollTo(x: offset)
            }
            .onEnded { value in
                guard let startOffset = heroDragStartOffset,
                      heroScrollMetrics.pageWidth > 0
                else {
                    heroDragStartOffset = nil
                    isDraggingHero = false
                    return
                }

                let pageWidth = heroScrollMetrics.pageWidth
                // The duplicate last page leads the content, shifting every
                // real page one slot right of its logical index (see
                // nearestHeroIndex) — without this the drag lands one page
                // ahead going forward and refuses to move going backward.
                let hasDuplicates = heroScenes.count > 1
                let startIndex = Int((startOffset / pageWidth).rounded()) - (hasDuplicates ? 1 : 0)
                let projected = abs(value.predictedEndTranslation.width) > abs(value.translation.width)
                    ? value.predictedEndTranslation.width
                    : value.translation.width
                let threshold = min(max(pageWidth * 0.18, 72), 180)
                let pageDelta: Int
                if abs(projected) < threshold {
                    pageDelta = 0
                } else {
                    pageDelta = projected < 0 ? 1 : -1
                }

                heroDragStartOffset = nil
                isDraggingHero = false
                selectHeroScene(startIndex + pageDelta)
            }
    }

    /// Nearest page for a scroll offset, including the duplicate edge pages
    /// (index -1 and count) when the pager loops.
    private func nearestHeroIndex(for metrics: HeroScrollMetrics) -> Int? {
        guard !heroScenes.isEmpty, metrics.pageWidth > 0 else { return nil }
        let hasDuplicates = heroScenes.count > 1
        
        let logicalIndex = (metrics.offset / metrics.pageWidth).rounded()
        let index = Int(logicalIndex) - (hasDuplicates ? 1 : 0)

        let firstPage = hasDuplicates ? -1 : 0
        let lastPage = hasDuplicates ? heroScenes.count : heroScenes.count - 1
        
        return min(max(index, firstPage), lastPage)
    }

    private func heroScrollPhaseDidChange(_ phase: ScrollPhase) {
        isHeroScrolling = phase.isScrolling

        switch phase {
        case .tracking, .interacting:
            // A new user gesture supersedes any in-flight timer/control jump.
            pendingHeroIndex = nil
        case .idle:
            if let target = pendingHeroIndex {
                heroIndex = target
                pendingHeroIndex = nil
            } else if !isDraggingHero,
                      let page = nearestHeroIndex(for: heroScrollMetrics) {
                let count = heroScenes.count
                heroIndex = (page % count + count) % count
            }

            // Settled on a duplicate edge page: swap it (invisibly,
            // identical content) for the real page.
            if !isDraggingHero,
               let page = nearestHeroIndex(for: heroScrollMetrics) {
                if page == heroScenes.count {
                    heroScrollPosition.scrollTo(id: 0, anchor: .center)
                } else if page == -1 {
                    heroScrollPosition.scrollTo(id: heroScenes.count - 1, anchor: .center)
                }
            }
        case .decelerating, .animating:
            break
        }
    }
    #endif

    /// Whether this page is the scene the shared per-scene state (trailer,
    /// clock, library check) currently belongs to.
    private func isCurrentScene(_ hero: MoviesShowsModel.Hero) -> Bool {
        hero.detail.ref == currentHero?.detail.ref
    }

    /// Per-platform interaction wrapper around the hero artwork/text stack.
    @ViewBuilder
    private func heroSurface(_ hero: MoviesShowsModel.Hero) -> some View {
        #if os(tvOS)
        // The hero is one full-bleed focusable button: swiping up from the
        // shelves lands here (fixing the unreachable hero), focus drives the
        // trailer (see `heroFocusDidChange`), and select opens the detail
        // page — including while the trailer is playing.
        Button {
            zoomSource = nil
            path.append(hero.detail.ref)
        } label: {
            heroLayers(hero)
        }
        .buttonStyle(HeroFocusButtonStyle())
        // The whole hero is already one button here, so it gets the whole
        // hero's reading rather than the concatenation of its fragments.
        .accessibilityLabel(hero.detail.title)
        .accessibilityValue(heroAccessibilityValue(hero))
        .accessibilityHint("Opens the archive record.")
        .focusSection()
        .focused($heroFocused)
        .onChange(of: heroFocused) { _, focused in
            // Every page observes the same FocusState; only the scene that
            // owns the shared trailer state may react.
            guard isCurrentScene(hero) else { return }
            heroFocusDidChange(focused, hero: hero)
        }
        #elseif os(macOS)
        // Hover is tracked by the pager so its poster controls also pause
        // rotation; the individual page only supplies its content.
        heroLayers(hero)
        #elseif os(iOS)
        // Rotation rule: press-and-hold anywhere on the hero holds the scene.
        heroLayers(hero)
            .onLongPressGesture(minimumDuration: .infinity, maximumDistance: 30, perform: {}) { pressing in
                isPressingHero = pressing
            }
        #else
        heroLayers(hero)
        #endif
    }

    private func heroLayers(_ hero: MoviesShowsModel.Hero) -> some View {
        ZStack(alignment: .bottomLeading) {
            heroBackdrop(hero)
                .frame(height: heroHeight)
                .frame(maxWidth: .infinity)

            VStack(alignment: .leading, spacing: 18) {
                Group {
                    if hero.isContinueWatching {
                        HStack(spacing: 12) {
                            Text("Continue Watching")
                                .labelCaps(Theme.gold)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .overlay {
                                    RoundedRectangle(cornerRadius: Theme.Radius.soft)
                                        .strokeBorder(Theme.goldDeep, lineWidth: 1)
                                }
                            if let remaining = hero.remainingText {
                                Text(remaining).labelCaps(Theme.gold)
                            }
                        }
                    }

                    Text(hero.detail.title)
                        .font(Typography.display(heroTitleSize))
                        .textCase(.uppercase)
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.6)

                    heroMeta(hero.detail)
                }
                .opacity(showingTrailer && isCurrentScene(hero) ? 0 : 1)
                // Badge, remaining time, title, year, studio, and genres are
                // one caption for one title — six stops would read as noise
                // before the actions below are ever reached.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(hero.detail.title)
                .accessibilityValue(heroAccessibilityValue(hero))
                .accessibilityHidden(showingTrailer && isCurrentScene(hero))
                // The hero's actions are reachable from its caption too, so
                // the whole scene can be acted on without walking down into
                // the button row.
                #if !os(tvOS)
                .accessibilityActions {
                    if heroInLibrary {
                        Button(
                            hero.isContinueWatching
                                ? String(localized: "Resume Playback")
                                : String(localized: "Play")
                        ) {
                            playOrOpen(hero)
                        }
                    }
                    Button(String(localized: "Details")) {
                        zoomSource = nil
                        path.append(hero.detail.ref)
                    }
                    if !heroTrailerUnavailable {
                        Button(trailerButtonTitle) { toggleTrailer(hero) }
                    }
                }
                #endif

                // On tvOS the whole hero is a single focusable button, so no
                // inner controls: select opens the detail page, where Play
                // and episodes live. Nested buttons would fight it for focus.
                #if !os(tvOS)
                VStack(alignment: .leading, spacing: 6) {
                    if heroInLibrary {
                        Button {
                            playOrOpen(hero)
                        } label: {
                            Label(
                                hero.isContinueWatching
                                    ? String(localized: "Resume Playback")
                                    : String(localized: "Play"),
                                image: .play
                            )
                        }
                        .archiveButtonStyle(.primary)
                    }

                    HStack(spacing: 14) {
                        Button {
                            zoomSource = nil
                            path.append(hero.detail.ref)
                        } label: {
                            Label("Details", image: .filmCircleInfo)
                        }
                        .archiveButtonStyle(.secondary)
                        
                        Button {
                            toggleTrailer(hero)
                        } label: {
                            Label(trailerButtonTitle, image: showingTrailer ? .xmark : .clapperboard)
                        }
                        .archiveButtonStyle(.secondary)
                        .disabled(heroTrailerUnavailable)
                    }
                }
                .padding(.top, 6)
                #endif
            }
            .padding(edgeMargin)
        }
    }

    @ViewBuilder
    private func heroBackdrop(_ hero: MoviesShowsModel.Hero) -> some View {
        // The trailer belongs to the selected scene only — without the
        // isCurrentScene gate every page would mount a player.
        if showingTrailer, isCurrentScene(hero), let trailer = heroTrailer {
            TrailerPlayerView(youTubeKey: trailer.key) {
                withAnimation { showingTrailer = false }
            }
            // The hero's caption is hidden while this plays, so the embed is
            // otherwise an unnamed region where the artwork used to be.
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Trailer")
        } else {
            BackdropImage(url: hero.detail.backdropURL)
        }
    }

    // MARK: trailerButtonTitle
    private var trailerButtonTitle: String {
        if heroTrailerUnavailable { return String(localized: "No Trailer") }
        return showingTrailer
            ? String(localized: "Hide Trailer")
            : String(localized: "Watch Trailer")
    }

    /// Everything the hero caption says besides the title: the Continue
    /// Watching badge and its remaining time, then the metadata row whose
    /// hairline dividers carry no meaning of their own.
    private func heroAccessibilityValue(_ hero: MoviesShowsModel.Hero) -> String {
        var parts: [String] = []
        if hero.isContinueWatching {
            parts.append(String(localized: "Continue watching"))
            if let remaining = hero.remainingText { parts.append(remaining) }
        }
        let detail = hero.detail
        if let year = detail.year { parts.append(String(year)) }
        if let attribution = detail.attribution { parts.append(attribution) }
        if !detail.genres.isEmpty {
            parts.append(detail.genres.prefix(2).joined(separator: ", "))
        }
        return parts.joined(separator: ", ")
    }

    private func heroMeta(_ detail: MediaDetail) -> some View {
        HStack(spacing: 14) {
            if let year = detail.year {
                Text(String(year))
                metaDivider
            }
            if let attribution = detail.attribution {
                Text(attribution)
                metaDivider
            }
            if !detail.genres.isEmpty {
                Text(detail.genres.prefix(2).joined(separator: " / "))
            }
        }
        .font(Typography.bodyLG)
        .foregroundStyle(Theme.textSecondary)
        .lineLimit(1)
    }

    private var metaDivider: some View {
        Rectangle()
            .fill(Theme.outline)
            .frame(width: 1, height: 14)
            .accessibilityHidden(true)
    }

    // MARK: - Hero rotation

    /// Per-platform "don't rotate away" rules. An active trailer always
    /// holds the scene (even on visionOS) so playback is never cut short.
    private var heroRotationPaused: Bool {
        if showingTrailer { return true }
        #if os(macOS)
        return isHoveringHero || isDraggingHero || isHeroScrolling
        #elseif os(iOS)
        return isPressingHero
        #elseif os(tvOS)
        return true // tvOS: no auto-rotation — the scene only changes by user action
        #else
        return false // visionOS: always transit
        #endif
    }

    /// Timer tick (10 Hz): checks whether the scene's dwell is up according
    /// to the per-platform rules, then rotates. Reads the clock only —
    /// mutating nothing keeps the tick free.
    private func tickHeroRotation() {
        guard case .loaded = model.phase, heroScenes.count > 1 else { return }
        guard !heroRotationPaused else { return }
        if heroClock.progress() >= 1 { advanceHeroScene() }
    }

    private func advanceHeroScene() {
        heroClock.restart()
        selectHeroScene(heroIndex + 1) // wraps past the last page
    }

    /// Commits a page selection and, on macOS, explicitly centers its
    /// full-width hero. All inputs—timer, dots, and mouse drag—pass through
    /// here so the indicator never drifts from the visible page.
    /// Stepping past the bounds loops: the pager animates onto a duplicate
    /// edge page, then snaps (invisibly) to the real page on the opposite side.
    private func selectHeroScene(_ requestedIndex: Int, animated: Bool = true) {
        guard !heroScenes.isEmpty else { return }
        let count = heroScenes.count
        let shouldAnimate = animated && !reduceMotion
        let wrapsForward = requestedIndex >= count && count > 1
        let wrapsBackward = requestedIndex < 0 && count > 1
        
        let index: Int
        if wrapsForward {
            index = 0
        } else if wrapsBackward {
            index = count - 1
        } else {
            index = min(max(requestedIndex, 0), count - 1)
        }

        #if os(macOS)
        pendingHeroIndex = shouldAnimate ? index : nil
        if shouldAnimate {
            withAnimation(.easeInOut(duration: 0.4)) {
                heroIndex = index
                let targetId = wrapsForward ? count : (wrapsBackward ? -1 : index)
                heroScrollPosition.scrollTo(
                    id: targetId,
                    anchor: .center
                )
            }
            // heroScrollPhaseDidChange snaps off the duplicate page on idle.
        } else {
            heroIndex = index
            heroScrollPosition.scrollTo(id: index, anchor: .center)
        }
        #else
        if (wrapsForward || wrapsBackward) && shouldAnimate {
            isHeroWrapping = true
            withAnimation(.easeInOut(duration: 0.4)) {
                heroIndex = wrapsForward ? count : -1
            } completion: {
                isHeroWrapping = false
                if wrapsForward {
                    snapHeroToStart()
                } else {
                    snapHeroToEnd()
                }
            }
        } else if shouldAnimate {
            withAnimation(.easeInOut(duration: 0.4)) {
                heroIndex = index
            }
        } else {
            heroIndex = index
        }
        #endif
    }

    #if !os(macOS)
    /// Trades the duplicate first page (heroIndex == count) for the real one
    /// without animation — identical content, so the jump is invisible.
    private func snapHeroToStart() {
        guard heroIndex == heroScenes.count else { return }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { heroIndex = 0 }
    }

    /// Trades the duplicate last page (heroIndex == -1) for the real one
    /// without animation — identical content, so the jump is invisible.
    private func snapHeroToEnd() {
        guard heroIndex == -1 else { return }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { heroIndex = heroScenes.count - 1 }
    }
    #endif

    /// Fresh per-scene state whenever the hero lands on a new title.
    private func resetScene(for hero: MoviesShowsModel.Hero) {
        heroClock.restart()
        showingTrailer = false
        heroTrailer = nil
        heroTrailerUnavailable = false
        heroInLibrary = isInLibrary(hero.detail.ref)
    }

    private func toggleTrailer(_ hero: MoviesShowsModel.Hero) {
        if showingTrailer {
            withAnimation { showingTrailer = false }
            return
        }
        startTrailer(hero)
    }

    private func startTrailer(_ hero: MoviesShowsModel.Hero) {
        guard TrailerPlayerView.isSupported else { return }
        Task {
            if heroTrailer == nil {
                let trailer = await model.trailer(for: hero.detail.ref)
                // Rapid paging can finish an older lookup after the scene has
                // changed; never attach that trailer to the new current page.
                guard isCurrentScene(hero) else { return }
                heroTrailer = trailer
            }
            guard isCurrentScene(hero) else { return }
            guard heroTrailer != nil else {
                heroTrailerUnavailable = true
                return
            }
            #if os(tvOS)
            // Focus may have moved on while the trailer lookup ran.
            guard heroFocused else { return }
            #endif
            withAnimation { showingTrailer = true }
        }
    }

    #if os(tvOS)
    /// Focus-driven trailer rules: never autoplay on arrival — start only
    /// when the user deliberately returns focus to the hero after visiting
    /// the shelves, and stop the moment focus moves down and away.
    private func heroFocusDidChange(_ focused: Bool, hero: MoviesShowsModel.Hero) {
        if focused {
            if hasLeftHero { startTrailer(hero) }
        } else {
            hasLeftHero = true
            if showingTrailer {
                withAnimation { showingTrailer = false }
            }
        }
    }
    #endif

    // MARK: - Library lookups

    /// Resume in the player when the item exists in the local library;
    /// otherwise open its detail page (shows pick episodes there).
    private func playOrOpen(_ hero: MoviesShowsModel.Hero) {
        Task {
            if hero.detail.ref.mediaType == .movie,
               let movie = Movie.first(tmdbId: hero.detail.ref.id, in: modelContext) {
                await playerSession.play(movie: movie)
            } else {
                zoomSource = nil
                path.append(hero.detail.ref)
            }
        }
    }

    /// Whether the downloaded library holds this title — gates the hero's
    /// Play button.
    private func isInLibrary(_ ref: MediaRef) -> Bool {
        switch ref.mediaType {
        case .movie: Movie.first(tmdbId: ref.id, in: modelContext) != nil
        case .tv: TVShow.first(tmdbId: ref.id, in: modelContext) != nil
        }
    }

    // MARK: - Shelves

    private func shelf(_ title: String, items: [TMDBMediaItem]) -> some View {
        MediaShelf(
            title: title,
            items: items,
            posterWidth: posterWidth,
            edgeMargin: edgeMargin,
            zoomNamespace: zoomNamespace
        ) { item in
            zoomSource = ShelfZoomID(shelf: title, ref: item.ref)
            path.append(item.ref)
        }
    }

    // MARK: - Curated collections

    private var collectionsSection: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: String(localized: "Curated Collections"))
                .padding(.horizontal, edgeMargin)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(model.collections, id: \.self) { collection in
                        FilterChip(
                            title: collection.title,
                            isSelected: model.selectedCollection == collection
                        ) {
                            Task { await model.loadCollection(collection) }
                        }
                    }
                }
                .padding(.horizontal, edgeMargin)
                // Room for the tvOS focus lift so it can't clip the grid below.
                #if os(tvOS)
                .padding(.vertical, 16)
                #endif
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Collection filters")

            LazyVGrid(columns: collectionColumns, spacing: collectionSpacing) {
                ForEach(Array(youngAudienceFilter.visible(model.collectionItems).prefix(12))) { item in
                    Button {
                        zoomSource = nil
                        path.append(item.ref)
                    } label: {
                        CollectionCard(item: item)
                    }
                    // tvOS: same reserved-bounds focus treatment as the shelves.
                    #if os(tvOS)
                    .buttonStyle(CardFocusButtonStyle())
                    #else
                    .buttonStyle(.plain)
                    #endif
                    .accessibilityHint("Opens the archive record.")
                }
            }
            .padding(.horizontal, edgeMargin)
            .opacity(model.isLoadingCollection ? 0.4 : 1)
            .animation(.easeOut(duration: 0.2), value: model.isLoadingCollection)
        }
        // Heading, filter chips, and grid are one section.
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Curated Collections")
    }

    // MARK: - Layout metrics

    private var edgeMargin: CGFloat {
        #if os(macOS)
        48
        #else
        horizontalSizeClass == .regular ? 48 : 20
        #endif
    }

    private var heroHeight: CGFloat {
        #if os(macOS)
        520
        #else
        horizontalSizeClass == .regular ? 520 : 420
        #endif
    }

    private var heroTitleSize: CGFloat {
        #if os(macOS)
        88
        #else
        horizontalSizeClass == .regular ? 88 : 54
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

    /// Curated-collection grid columns — wider 16:9 cards on tvOS so they
    /// read across the room.
    private var collectionColumns: [GridItem] {
        #if os(tvOS)
        [GridItem(.adaptive(minimum: 480), spacing: collectionSpacing)]
        #else
        [GridItem(.adaptive(minimum: 300), spacing: collectionSpacing)]
        #endif
    }

    /// tvOS cells rest scaled-down inside full-size slots (CardFocusButtonStyle),
    /// which adds its own visual breathing room between cards.
    private var collectionSpacing: CGFloat {
        #if os(tvOS)
        24
        #else
        20
        #endif
    }
}

// MARK: - Hero focus style (tvOS)

#if os(tvOS)
/// Focus treatment for the full-bleed hero button: no card lift or platter —
/// just a gold hairline so focus reads without distorting the artwork.
private struct HeroFocusButtonStyle: ButtonStyle {
    @Environment(\.isFocused) private var isFocused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                Rectangle()
                    .strokeBorder(isFocused ? Theme.gold : .clear, lineWidth: 3)
            }
            .animation(.easeOut(duration: 0.15), value: isFocused)
    }
}
#endif

// MARK: - Collection card

/// Wide 16:9 card with a title overlay, used in the curated-collections grid.
private struct CollectionCard: View {
    let item: TMDBMediaItem
    @State private var isHovering = false

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            BackdropImage(url: item.backdropURL)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(Typography.titleLG)
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                if let year = item.year {
                    Text(String(year))
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .padding(16)
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.card)
                .strokeBorder(isHovering ? Theme.outlineBright : Theme.hairline, lineWidth: 1)
        }
        .shadow(color: isHovering ? Theme.goldGlow : .clear, radius: 16)
        #if !os(tvOS)
        .onHover { isHovering = $0 }
        #endif
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.title)
        .accessibilityValue(item.year.map(String.init) ?? "")
    }
}

// MARK: - States

private struct ArchiveLoadingState: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .tint(Theme.gold)
            Text("Loading the archive").labelCaps()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

private struct MissingKeyState: View {
    var body: some View {
        VStack(spacing: 18) {
            // Archival illustration; the heading beneath says the same thing.
            Image(.clapperboard)
                .font(.system(size: 44))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text("The Projector Is Dark")
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textPrimary)
            Text("Add a TMDB API key to Shared/Secrets.xcconfig to browse movies and shows.")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Heading and its explanation are one empty state.
        .accessibilityElement(children: .combine)
    }
}

private struct ArchiveErrorState: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Group {
                Text("The Reel Snapped")
                    .font(Typography.headlineMD)
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.textPrimary)
                Text(message)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
            }
            // Heading and cause read together; Try Again stays its own
            // control.
            .accessibilityElement(children: .combine)
            Button("Try Again", action: retry)
                .archiveButtonStyle(.secondary)
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
