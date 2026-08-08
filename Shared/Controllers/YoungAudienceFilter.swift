//
//  YoungAudienceFilter.swift
//  Edendale
//
//  Persisted PG / PG-13 audience preference plus cached TMDB certification
//  verification. List responses do not carry certifications, so lookups are
//  deliberately separate from browse and from the local import fast path.
//

import Foundation
import Observation

nonisolated enum ContentCertificationLookup: Equatable, Sendable {
    case found(String)
    case unrated
    case unavailable
}

nonisolated protocol ContentCertificationProviding: Sendable {
    var contextIdentifier: String { get }
    func certification(for ref: MediaRef) async -> ContentCertificationLookup
}

nonisolated enum YoungAudienceCertificationPolicy {
    /// PG and PG-13 are accepted exactly after normalizing punctuation and
    /// spacing. Television services use TV-PG / TV-14 in regions such as the
    /// United States, while regions such as Singapore use PG / PG13 for both.
    static func allows(_ certification: String, for mediaType: TMDBMediaType) -> Bool {
        let normalized = certification.uppercased().filter {
            $0.isLetter || $0.isNumber
        }

        if normalized == "PG" || normalized == "PG13" {
            return true
        }

        return mediaType == .tv && (normalized == "TVPG" || normalized == "TV14")
    }
}

@MainActor
@Observable
final class YoungAudienceFilter {
    nonisolated static let defaultsKey = "audience.youngAudienceFriendly"

    var isEnabled: Bool {
        didSet {
            defaults.set(isEnabled, forKey: Self.defaultsKey)
        }
    }

    private enum Decision {
        case allowed
        case blocked
        /// Network/authentication failures fail closed but can be retried when
        /// a later verification task is triggered.
        case unavailable
    }

    private var decisions: [MediaRef: Decision] = [:]
    private(set) var certificationContextIdentifier: String

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let provider: any ContentCertificationProviding

    convenience init() {
        self.init(
            defaults: AppIdentifiers.defaults,
            provider: TMDBContentCertificationProvider.shared
        )
    }

    init(
        defaults: UserDefaults,
        provider: any ContentCertificationProviding
    ) {
        self.defaults = defaults
        self.provider = provider
        certificationContextIdentifier = provider.contextIdentifier
        isEnabled = defaults.bool(forKey: Self.defaultsKey)
    }

    var contextIdentifier: String {
        synchronizeCertificationContext()
        return certificationContextIdentifier
    }

    /// Turning the preference off is a synchronous, zero-network bypass.
    func allows(_ ref: MediaRef) -> Bool {
        synchronizeCertificationContext()
        return !isEnabled || decisions[ref] == .allowed
    }

    /// Preserves the source ordering and restores the original list verbatim
    /// when the preference is off.
    func visible(_ items: [TMDBMediaItem]) -> [TMDBMediaItem] {
        synchronizeCertificationContext()
        guard isEnabled else { return items }
        return items.filter { decisions[$0.ref] == .allowed }
    }

    /// Unknown refs count as verifying as soon as they enter a surface. That
    /// keeps the UI fail-closed without briefly showing unverified artwork.
    func isVerifying(_ refs: [MediaRef]) -> Bool {
        synchronizeCertificationContext()
        guard isEnabled else { return false }
        return Self.canonical(refs).contains { decisions[$0] == nil }
    }

    /// Resolves every requested ref before returning. Overlapping callers are
    /// coalesced by the provider; each caller still awaits its own refs so a
    /// playback/deep-link gate never races a background shelf verification.
    func verify(_ refs: [MediaRef]) async {
        synchronizeCertificationContext()
        guard isEnabled else { return }

        let unresolved = Self.canonical(refs).filter {
            decisions[$0] == nil || decisions[$0] == .unavailable
        }
        guard !unresolved.isEmpty else { return }

        let batchSize = 8
        var start = unresolved.startIndex
        while start < unresolved.endIndex {
            let end = min(start + batchSize, unresolved.endIndex)
            let batch = Array(unresolved[start..<end])
            let provider = provider

            let results = await withTaskGroup(
                of: (MediaRef, ContentCertificationLookup).self,
                returning: [(MediaRef, ContentCertificationLookup)].self
            ) { group in
                for ref in batch {
                    group.addTask {
                        (ref, await provider.certification(for: ref))
                    }
                }

                var values: [(MediaRef, ContentCertificationLookup)] = []
                for await value in group {
                    values.append(value)
                }
                return values
            }

            for (ref, lookup) in results {
                decisions[ref] = switch lookup {
                case .found(let certification):
                    YoungAudienceCertificationPolicy.allows(
                        certification,
                        for: ref.mediaType
                    ) ? .allowed : .blocked
                case .unrated:
                    .blocked
                case .unavailable:
                    .unavailable
                }
            }

            if Task.isCancelled { return }
            start = end
        }
    }

    private static func canonical(_ refs: [MediaRef]) -> [MediaRef] {
        Array(Set(refs)).sorted { lhs, rhs in
            if lhs.mediaType != rhs.mediaType {
                return lhs.mediaType.rawValue < rhs.mediaType.rawValue
            }
            return lhs.id < rhs.id
        }
    }

    private func synchronizeCertificationContext() {
        let current = provider.contextIdentifier
        guard current != certificationContextIdentifier else { return }
        certificationContextIdentifier = current
        decisions.removeAll()
    }
}

nonisolated struct YoungAudienceVerificationKey: Hashable {
    let isEnabled: Bool
    let contextIdentifier: String
    let refs: [MediaRef]

    init(isEnabled: Bool, contextIdentifier: String, refs: [MediaRef]) {
        self.isEnabled = isEnabled
        self.contextIdentifier = contextIdentifier
        self.refs = Array(Set(refs)).sorted { lhs, rhs in
            if lhs.mediaType != rhs.mediaType {
                return lhs.mediaType.rawValue < rhs.mediaType.rawValue
            }
            return lhs.id < rhs.id
        }
    }
}

// MARK: - TMDB certification payloads

nonisolated struct TMDBMovieReleaseDatesResponse: Decodable, Sendable {
    let results: [TMDBMovieReleaseDateRegion]

    func certification(in regionCode: String) -> String? {
        guard let region = results.first(where: {
            $0.iso31661.caseInsensitiveCompare(regionCode) == .orderedSame
        }) else { return nil }

        let priority = [3: 0, 2: 1, 4: 2, 5: 3, 6: 4, 1: 5]
        let ratedReleases = region.releaseDates
            .filter { !$0.certification.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let bestPriority = ratedReleases.map({ priority[$0.type] ?? Int.max }).min()
        else { return nil }

        let preferredReleases = ratedReleases.filter {
            (priority[$0.type] ?? Int.max) == bestPriority
        }
        let distinctCertifications = Set(preferredReleases.map {
            $0.certification.uppercased().filter { $0.isLetter || $0.isNumber }
        })
        // Different certifications at the same preferred release tier can
        // represent separate edits/cuts. Never pick the more permissive one.
        guard distinctCertifications.count == 1 else { return nil }

        return preferredReleases
            .sorted { ($0.releaseDate ?? "9999") < ($1.releaseDate ?? "9999") }
            .first?
            .certification
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

nonisolated struct TMDBMovieReleaseDateRegion: Decodable, Sendable {
    let iso31661: String
    let releaseDates: [TMDBMovieReleaseDate]
}

nonisolated struct TMDBMovieReleaseDate: Decodable, Sendable {
    let certification: String
    let releaseDate: String?
    let type: Int
}

nonisolated struct TMDBTVContentRatingsResponse: Decodable, Sendable {
    let results: [TMDBTVContentRating]

    func certification(in regionCode: String) -> String? {
        results.first {
            $0.iso31661.caseInsensitiveCompare(regionCode) == .orderedSame
                && !$0.rating.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }?.rating.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

nonisolated struct TMDBTVContentRating: Decodable, Sendable {
    let iso31661: String
    let rating: String
}

extension TMDBService {
    func contentCertification(for ref: MediaRef, regionCode: String) async throws -> String? {
        switch ref.mediaType {
        case .movie:
            let response = try await fetch(
                TMDBMovieReleaseDatesResponse.self,
                path: "/movie/\(ref.id)/release_dates"
            )
            return response.certification(in: regionCode)

        case .tv:
            let response = try await fetch(
                TMDBTVContentRatingsResponse.self,
                path: "/tv/\(ref.id)/content_ratings"
            )
            return response.certification(in: regionCode)
        }
    }
}

// MARK: - Cached production provider

private actor ContentCertificationRequestGate {
    private let limit: Int
    private var active = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) {
        self.limit = max(limit, 1)
    }

    func acquire() async {
        if active < limit {
            active += 1
            return
        }

        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func release() {
        if waiters.isEmpty {
            active -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

actor TMDBContentCertificationProvider: ContentCertificationProviding {
    nonisolated static let shared = TMDBContentCertificationProvider()

    nonisolated private let fixedRegionCode: String?
    private let gate: ContentCertificationRequestGate
    private var cache: [ContentCertificationRequestKey: ContentCertificationLookup] = [:]
    private var inFlight: [ContentCertificationRequestKey: Task<ContentCertificationLookup, Never>] = [:]

    nonisolated var contextIdentifier: String {
        fixedRegionCode
            ?? Locale.autoupdatingCurrent.region?.identifier.uppercased()
            ?? ""
    }

    init(
        regionCode: String? = nil,
        maximumConcurrentRequests: Int = 8
    ) {
        fixedRegionCode = regionCode?.uppercased()
        gate = ContentCertificationRequestGate(limit: maximumConcurrentRequests)
    }

    func certification(for ref: MediaRef) async -> ContentCertificationLookup {
        let regionCode = contextIdentifier
        guard !regionCode.isEmpty else { return .unrated }
        let requestKey = ContentCertificationRequestKey(ref: ref, regionCode: regionCode)
        if let cached = cache[requestKey] { return cached }
        if let pending = inFlight[requestKey] { return await pending.value }

        let gate = gate
        let task = Task<ContentCertificationLookup, Never> { @MainActor in
            await gate.acquire()

            let result: ContentCertificationLookup
            do {
                if let certification = try await TMDBService.shared.contentCertification(
                    for: ref,
                    regionCode: regionCode
                ) {
                    result = .found(certification)
                } else {
                    result = .unrated
                }
            } catch {
                result = .unavailable
            }

            await gate.release()
            return result
        }

        inFlight[requestKey] = task
        let result = await task.value
        inFlight[requestKey] = nil

        // Authentication and transient network failures may recover later.
        if result != .unavailable {
            cache[requestKey] = result
        }
        return result
    }
}

nonisolated private struct ContentCertificationRequestKey: Hashable, Sendable {
    let ref: MediaRef
    let regionCode: String
}
