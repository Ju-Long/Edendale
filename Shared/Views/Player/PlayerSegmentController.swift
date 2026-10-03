import Foundation

/// Session-only timestamp state, independent of the video engine and its UI.
@MainActor
@Observable
final class PlayerSegmentController {
    static let preferenceKey = "player.segmentPromptsEnabled"
    typealias Lookup = @Sendable (IntroDBRequest) async throws -> [PlaybackSegment]

    enum SkipAction: Equatable {
        case seek(TimeInterval)
        case finish
    }

    var isEnabled: Bool {
        didSet {
            guard isEnabled != oldValue else { return }
            defaults.set(isEnabled, forKey: Self.preferenceKey)
            invalidateLookup()
            if !isEnabled { cache.removeAll() }
            loadIfNeeded()
        }
    }
    private(set) var segments: [PlaybackSegment] = []
    private(set) var isLoading = false
    private(set) var currentTime: TimeInterval = 0
    private var duration: TimeInterval?
    private var isSeekable = false
    private var itemID: UUID?
    private var media: IntroDBMedia?
    private var attemptedRequest: IntroDBRequest?
    private var generation = UUID()
    private var suppressedSegment: PlaybackSegment?
    private var task: Task<Void, Never>?
    private var cache: [IntroDBRequest: [PlaybackSegment]] = [:]
    private let defaults: UserDefaults
    private let lookup: Lookup

    init(defaults: UserDefaults? = nil, lookup: Lookup? = nil) {
        let defaults = defaults ?? AppIdentifiers.defaults
        self.defaults = defaults
        self.lookup = lookup ?? { try await IntroDBService.shared.segments(for: $0) }
        // The former auto-skip preferences do not opt a user into network
        // access. The new setting starts off, including for existing installs.
        isEnabled = defaults.bool(forKey: Self.preferenceKey)
    }

    var activeSegment: PlaybackSegment? {
        guard isEnabled, isSeekable, let duration, duration.isFinite else { return nil }
        return segments.first {
            $0 != suppressedSegment && $0.end <= duration && $0.contains(currentTime)
        }
    }

    func begin(itemID: UUID, media: IntroDBMedia?) {
        invalidateLookup()
        self.itemID = itemID
        self.media = media
        currentTime = 0
        duration = nil
        isSeekable = false
    }

    func update(time: TimeInterval, duration: TimeInterval?, isSeekable: Bool) {
        currentTime = time
        self.duration = duration
        self.isSeekable = isSeekable
        if let suppressedSegment, !suppressedSegment.contains(time) {
            self.suppressedSegment = nil
        }
        loadIfNeeded()
    }

    /// Revalidate against the engine's latest time at button press. Suppress
    /// repeat presses until playback exits this range; rewinding can reveal it.
    func consumeSkip(at time: TimeInterval, duration: TimeInterval?, isSeekable: Bool) -> SkipAction? {
        update(time: time, duration: duration, isSeekable: isSeekable)
        guard let segment = activeSegment else { return nil }
        suppressedSegment = segment
        return segment.reachesEnd ? .finish : .seek(segment.end)
    }

    func end() {
        invalidateLookup()
        itemID = nil
        media = nil
        duration = nil
        isSeekable = false
        cache.removeAll()
    }

    private func invalidateLookup() {
        generation = UUID()
        task?.cancel()
        task = nil
        segments = []
        attemptedRequest = nil
        suppressedSegment = nil
        isLoading = false
    }

    private func loadIfNeeded() {
        guard isEnabled, itemID != nil, let media, let duration,
              attemptedRequest == nil,
              let request = IntroDBRequest(media: media, duration: duration)
        else { return }
        attemptedRequest = request
        if let cached = cache[request] {
            segments = cached
            return
        }
        isLoading = true
        let expectedGeneration = generation
        let lookup = lookup
        task = Task { [weak self] in
            do {
                let result = try await lookup(request)
                guard !Task.isCancelled, let self, self.generation == expectedGeneration else { return }
                // Small, bounded, in-memory cache for this playback session.
                if self.cache.count >= 12 { self.cache.removeAll() }
                self.cache[request] = result
                self.segments = result
                self.isLoading = false
            } catch {
                guard !Task.isCancelled, let self, self.generation == expectedGeneration else { return }
                self.isLoading = false
                // No retry on every time event and no playback interruption.
                // A later playback request can try again; 429s also have a
                // provider-wide cooldown in IntroDBService.
            }
        }
    }
}
