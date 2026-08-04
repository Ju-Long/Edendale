//
//  PersonDetailView.swift
//  Edendale
//
//  A person page: portrait on the left, biography on the right, filmography
//  underneath. Pushed from the search People grid and from the "Starring …"
//  chip; the chip filter mode itself is unchanged and reachable back from
//  here via "Show in Search".
//

import SwiftUI
import Kingfisher

struct PersonDetailView: View {
    let person: PersonRef

    @Environment(SearchCoordinator.self) private var searchCoordinator
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @State private var detail: PersonDetail?
    @State private var filmography: [TMDBMediaItem] = []
    @State private var isLoading = false
    @State private var loadFailed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                header
                if !filmography.isEmpty {
                    filmographySection
                } else if loadFailed {
                    failureState
                } else if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 40)
                        .accessibilityLabel("Loading filmography")
                }
            }
            .padding(.vertical, 24)
        }
        .background(Theme.background)
        #if !os(tvOS)
        .navigationTitle(detail?.name ?? person.name)
        #if os(iOS) || os(visionOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        #endif
        .navigationDestination(for: MediaRef.self) { MediaDetailView(source: .tmdb($0)) }
        .task(id: person.id) { await load() }
    }

    // MARK: - Header

    /// Portrait beside the biography, stacking on narrow screens so neither
    /// column drops below a readable width.
    @ViewBuilder
    private var header: some View {
        if isWide {
            HStack(alignment: .top, spacing: 28) {
                portrait
                biography
            }
            .padding(.horizontal, edgeMargin)
        } else {
            VStack(alignment: .leading, spacing: 20) {
                portrait
                    .frame(maxWidth: .infinity, alignment: .center)
                biography
            }
            .padding(.horizontal, edgeMargin)
        }
    }

    private var portrait: some View {
        ZStack {
            Theme.surface
            Image(.circleUserFill)
                .font(.system(size: portraitWidth * 0.4))
                .foregroundStyle(Theme.surfaceHigh)
            if let url = detail?.profileURL {
                KFImage(url)
                    .resizable()
                    .fade(duration: 0.25)
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: portraitWidth, height: portraitWidth * 1.5)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.glass))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.glass)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        }
        // The name beside it is the content; the portrait adds nothing to say.
        .accessibilityHidden(true)
    }

    private var biography: some View {
        VStack(alignment: .leading, spacing: 14) {
            Group {
                if let department = detail?.knownForDepartment {
                    Text(department).labelCaps(Theme.gold)
                }

                Text(detail?.name ?? person.name)
                    .font(Typography.headlineMD)
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let vitals = detail?.vitals {
                    Text(vitals)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            // Department, name, and vitals are one identification line; the
            // biography below stays separate so it can be skipped.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(detail?.name ?? person.name)
            .accessibilityValue(
                [detail?.knownForDepartment, detail?.vitals]
                    .compactMap { $0 }
                    .joined(separator: ", ")
            )
            .accessibilityAddTraits(.isHeader)

            if let biography = detail?.biography {
                ExpandableText(text: biography, collapsedLineLimit: 8)
            } else if !isLoading {
                Text("TMDB has no biography for this person yet.")
                    .font(Typography.bodyLG)
                    .foregroundStyle(Theme.textSecondary)
            }

            #if !os(tvOS)
            showInSearchButton
            #endif
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Bridge to the existing "Starring …" filter: same person, but as a
    /// filter over the Search tab rather than a page.
    private var showInSearchButton: some View {
        Button {
            searchCoordinator.pendingPerson = detail?.ref ?? person
        } label: {
            Label {
                Text("Show in Search")
            } icon: {
                Image(.magnifyingGlassPlay)
            }
        }
        .archiveButtonStyle(.ghost)
        .padding(.top, 4)
        .accessibilityHint("Filters the Search tab to this person's titles.")
    }

    // MARK: - Filmography

    private var filmographySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: String(localized: "Filmography"))
                .padding(.horizontal, edgeMargin)

            LazyVGrid(columns: gridColumns, alignment: .center, spacing: 20) {
                ForEach(filmography, id: \.ref) { item in
                    NavigationLink(value: item.ref) {
                        PosterCard(
                            title: item.title,
                            subtitle: item.detailedDateText,
                            posterURL: item.posterURL,
                            placeholderIcon: item.mediaType == .tv ? "tv" : "film",
                            width: posterWidth
                        )
                    }
                    #if os(tvOS)
                    .buttonStyle(CardFocusButtonStyle())
                    #else
                    .buttonStyle(.plain)
                    #endif
                    .accessibilityHint("Opens the archive record.")
                }
            }
            .padding(.horizontal, edgeMargin)
            .padding(.vertical, 14)
        }
        // Heading and grid are one section.
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Filmography")
    }

    private var failureState: some View {
        VStack(spacing: 12) {
            Image(.filmCircleExclamation)
                .font(.system(size: 40))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text("Could Not Load This Person")
                .font(Typography.titleLG)
                .foregroundStyle(Theme.textPrimary)
            Button("Try Again") { Task { await load(force: true) } }
                .archiveButtonStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    // MARK: - Loading

    /// Biography and filmography are separate TMDB endpoints, so they run
    /// concurrently and the header renders as soon as it lands.
    private func load(force: Bool = false) async {
        if !force && detail != nil { return }
        isLoading = true
        loadFailed = false
        defer { isLoading = false }

        let tmdb = TMDBService.shared
        async let credits = try? tmdb.filmography(personId: person.id)

        do {
            detail = try await tmdb.personDetail(personId: person.id)
        } catch is CancellationError {
            return
        } catch {
            print("Person detail failed: \(error)")
            loadFailed = true
        }

        filmography = await credits ?? []
        if !filmography.isEmpty { loadFailed = false }
    }

    // MARK: - Metrics

    private var isWide: Bool {
        #if os(macOS) || os(tvOS)
        true
        #else
        horizontalSizeClass == .regular
        #endif
    }

    private var portraitWidth: CGFloat {
        #if os(tvOS)
        300
        #else
        isWide ? 220 : 170
        #endif
    }

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
        240
        #else
        horizontalSizeClass == .regular ? 180 : 140
        #endif
    }

    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: 20)]
    }
}
