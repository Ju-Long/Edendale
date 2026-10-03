//
//  SubtitleCacheStore.swift
//  Edendale
//
//  Keeps downloaded online subtitles on this device. Files live in
//  Caches/Subtitles, one per provider subtitle; a SwiftData record ties each
//  to the video it was downloaded for (SubtitleVideoKey), so playing that
//  video again reattaches it without a search or a download, whichever
//  address the video is read from. A subtitle the cache already holds is
//  reused instead of downloaded again.
//
//  At launch, `prune()` deletes records unused for more than a month and
//  records whose file the system purged, then any file no record refers to.
//

import Foundation
import SwiftData

@MainActor
final class SubtitleCacheStore {
    private let modelContext: ModelContext
    private let directory: URL
    private let fileManager: FileManager
    private let calendar: Calendar
    private let now: () -> Date

    init(
        modelContext: ModelContext,
        directory: URL = SubtitleCacheStore.defaultDirectory,
        fileManager: FileManager = .default,
        calendar: Calendar = .current,
        now: @escaping () -> Date = Date.init
    ) {
        self.modelContext = modelContext
        self.directory = directory
        self.fileManager = fileManager
        self.calendar = calendar
        self.now = now
    }

    /// Caches/Subtitles: re-downloadable data, and the only place tvOS
    /// lets an app keep files.
    nonisolated static var defaultDirectory: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return caches.appendingPathComponent("Subtitles", isDirectory: true)
    }

    // MARK: - Reading

    /// The subtitles kept for `videoKey` whose files are still there, most
    /// recently used first.
    func subtitles(for videoKey: String) -> [CachedSubtitle] {
        let descriptor = FetchDescriptor<CachedSubtitle>(
            predicate: #Predicate { $0.videoKey == videoKey },
            sortBy: [SortDescriptor(\.lastUsedAt, order: .reverse)]
        )
        let records = (try? modelContext.fetch(descriptor)) ?? []
        return records.filter { fileManager.fileExists(atPath: fileURL(for: $0).path) }
    }

    func fileURL(for subtitle: CachedSubtitle) -> URL {
        fileURL(named: subtitle.fileName)
    }

    /// The file already downloaded for `subtitle`, for this video or another.
    func existingFile(for subtitle: WyzieSubtitle) -> URL? {
        let url = fileURL(named: WyzieSubtitleService.cacheFileName(for: subtitle))
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: - Writing

    /// Saves a downloaded file and returns where it is.
    func save(_ data: Data, for subtitle: WyzieSubtitle) throws -> URL {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = fileURL(named: WyzieSubtitleService.cacheFileName(for: subtitle))
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Records that `subtitle` belongs to `videoKey` and was used now.
    @discardableResult
    func remember(_ subtitle: WyzieSubtitle, videoKey: String) -> CachedSubtitle {
        let date = now()
        let storageKey = CachedSubtitle.makeStorageKey(subtitleID: subtitle.id, videoKey: videoKey)
        let descriptor = FetchDescriptor<CachedSubtitle>(
            predicate: #Predicate { $0.storageKey == storageKey }
        )
        let record: CachedSubtitle
        if let existing = try? modelContext.fetch(descriptor).first {
            record = existing
            record.lastUsedAt = date
        } else {
            record = CachedSubtitle(
                subtitleID: subtitle.id,
                videoKey: videoKey,
                fileName: WyzieSubtitleService.cacheFileName(for: subtitle),
                displayName: Self.displayName(for: subtitle),
                language: subtitle.language,
                date: date
            )
            modelContext.insert(record)
        }
        persist()
        return record
    }

    /// Marks subtitles reattached to a playback as used now.
    func markUsed(_ subtitles: [CachedSubtitle]) {
        guard !subtitles.isEmpty else { return }
        let date = now()
        for subtitle in subtitles {
            subtitle.lastUsedAt = date
        }
        persist()
    }

    // MARK: - Pruning

    /// Deletes subtitles unused for more than a month, records whose file
    /// is gone, and files no remaining record refers to (including downloads
    /// from before records were kept). Returns the number of records deleted.
    @discardableResult
    func prune() -> Int {
        let current = now()
        let cutoff = calendar.date(byAdding: .month, value: -1, to: current)
            ?? current.addingTimeInterval(-30 * 24 * 60 * 60)
        let records = (try? modelContext.fetch(FetchDescriptor<CachedSubtitle>())) ?? []

        var removed = 0
        var keptFiles: Set<String> = []
        for record in records {
            if record.lastUsedAt < cutoff
                || !fileManager.fileExists(atPath: fileURL(for: record).path) {
                modelContext.delete(record)
                removed += 1
            } else {
                keptFiles.insert(fileURL(for: record).lastPathComponent)
            }
        }
        if removed > 0 { persist() }

        // A file shared by several videos stays while any of them keeps it.
        let files = (try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        )) ?? []
        for file in files where !keptFiles.contains(file.lastPathComponent) {
            try? fileManager.removeItem(at: file)
        }
        return removed
    }

    // MARK: - Helpers

    /// Only ever a name inside the folder, whatever a record holds.
    private func fileURL(named name: String) -> URL {
        directory.appendingPathComponent(
            URL(fileURLWithPath: name).lastPathComponent,
            isDirectory: false
        )
    }

    private func persist() {
        do {
            try modelContext.save()
        } catch {
            print("[SubtitleCacheStore] Failed to save: \(error)")
        }
    }

    /// The language, plus the release it was timed for when the provider
    /// names one, e.g. "English · Movie.2010.1080p.BluRay".
    static func displayName(for subtitle: WyzieSubtitle) -> String {
        let language = subtitle.display.trimmingCharacters(in: .whitespacesAndNewlines)
        let release = (subtitle.release ?? subtitle.fileName)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let base = language.isEmpty ? subtitle.language.uppercased() : language
        return release.isEmpty ? base : "\(base) · \(release)"
    }
}
