//
//  RebufferPolicy.swift
//  Edendale
//
//  When streamed playback stops to refill, and when it carries on.
//
//  A byte source (SMB, NFS, SFTP, a cloud provider) reads ahead of FFmpeg,
//  but a link slower than the video, or one that drops out for longer than
//  the read-ahead lasts, still empties it. The player holds only about half
//  a second of decoded video and a second of sound past that. Left alone,
//  its clock would run on while frames arrive late: the picture turns into
//  stills that fall ever further behind and the sound is dropped. Instead
//  playback stops when both the player and the source have run out, and
//  starts again once the source holds enough that it doesn't stop again at
//  once.
//

import Foundation

nonisolated struct RebufferPolicy: Sendable, Equatable {
    /// What a byte source holds past the reader's position (see
    /// `ByteSource.bufferedBytesAhead`).
    struct Source: Sendable, Equatable {
        /// Fetched bytes, contiguous from the end of the last read.
        var bytes: Int64
        /// Nothing more arrives until reading goes on: the read-ahead is
        /// full, reaches the end of the file, or stopped after a failure.
        var complete: Bool
    }

    /// The file's average bytes per second, or nil when its size or
    /// duration is unknown.
    var byteRate: Double?
    /// Seconds of media the source must hold before playback resumes. A
    /// source whose whole read-ahead is shorter resumes when that is full.
    var resumeSeconds = 8.0
    /// A source holding less than this many seconds counts as empty.
    var drySeconds = 0.25
    /// The same two limits in bytes, for a file with no known byte rate.
    var resumeBytes: Int64 = 8 << 20
    var dryBytes: Int64 = 256 << 10
    /// A file without a picture stops once its sound is this close, in
    /// seconds, to running out.
    var audioFloor = 0.05
    /// With a picture, playback stops when no decoded frame waits and the
    /// sound has less than this many seconds left. The picture holds about
    /// half a second and the sound a second, so the picture runs out first;
    /// sound that is still well ahead means the picture is only sparse.
    var audioFloorWithVideo = 0.5

    /// The file's size and duration give the byte rate.
    init(length: Int64 = -1, duration: Double = .nan) {
        if length > 0, duration.isFinite, duration > 0 {
            byteRate = Double(length) / duration
        }
    }

    /// The source has nothing to give and more is still on its way.
    func isDry(_ source: Source) -> Bool {
        guard !source.complete else { return false }
        if let byteRate { return Double(source.bytes) < byteRate * drySeconds }
        return source.bytes < dryBytes
    }

    /// Whether running playback should stop to refill.
    /// - Parameters:
    ///   - queuedVideoFrames: Decoded frames waiting to be shown; nil when
    ///     no picture is being decoded.
    ///   - audioAhead: Seconds of decoded sound past the clock; nil for a
    ///     file without sound.
    func shouldPause(source: Source, queuedVideoFrames: Int?, audioAhead: Double?) -> Bool {
        let starving: Bool
        switch (queuedVideoFrames, audioAhead) {
        case (let frames?, let audio?): starving = frames == 0 && audio < audioFloorWithVideo
        case (let frames?, nil): starving = frames == 0
        case (nil, let audio?): starving = audio <= audioFloor
        case (nil, nil): starving = false
        }
        return starving && isDry(source)
    }

    /// Whether stopped playback may carry on.
    func canResume(_ source: Source) -> Bool {
        if source.complete { return true }
        if let byteRate { return Double(source.bytes) >= byteRate * resumeSeconds }
        return source.bytes >= resumeBytes
    }
}
