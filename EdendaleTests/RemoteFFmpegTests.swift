//
//  RemoteFFmpegTests.swift
//  EdendaleTests
//
//  FFmpeg reading through RemoteByteSource: the subtitle fixture, served by
//  a URLProtocol stub with Range support, opens with the same media info as
//  the local file, seeks, and switches subtitle tracks — including after the
//  stub rejects the first token. The reader also refuses remote URLs it
//  would otherwise hand to FFmpeg's own (unverified) protocols.
//

import Foundation
import Testing
@testable import Edendale

private final class RemoteFFmpegTestResources: NSObject {}

@Suite(.serialized)
struct RemoteFFmpegTests {

    private func fixture(_ name: String) throws -> URL {
        let bundle = Bundle(for: RemoteFFmpegTestResources.self)
        return try #require(bundle.url(forResource: name, withExtension: "mkv")
            ?? bundle.url(forResource: name, withExtension: "mkv", subdirectory: "Fixtures"))
    }

    /// A byte source serving `data`, rejecting the first token when asked.
    private func remoteSource(serving data: Data, host: String, rejectFirstToken: Bool = false) -> RemoteByteSource {
        HTTPStub.register(host: host) { request in
            if rejectFirstToken, request.header("Authorization") == "Bearer token0" {
                return StubResponse.text("expired", status: 401)
            }
            return StubResponse.file(data, request: request)
        }
        return RemoteByteSource(
            resolver: StubResolver(host: host),
            // Small chunks make the open cross many chunk boundaries.
            configuration: RemoteByteSource.Configuration(chunkSize: 32 * 1024, cachedChunks: 4),
            sessionConfiguration: HTTPStub.configuration()
        )
    }

    /// Runs blocking reader work on a GCD thread, as FFmpegWorker does, so
    /// the byte source's loads never wait on a blocked test thread.
    private func onWorker<T>(_ work: @escaping () throws -> T) async throws -> T {
        nonisolated(unsafe) let work = work
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global().async {
                nonisolated(unsafe) let result = Result { try work() }
                continuation.resume(with: result)
            }
        }
    }

    @Test func opensSeeksAndSwitchesTracksThroughARemoteByteSource() async throws {
        let url = try fixture("decoder-subtitles")
        let data = try Data(contentsOf: url)
        let host = HTTPStub.uniqueHost("ffmpeg")
        defer { HTTPStub.unregister(host: host) }
        let source = remoteSource(serving: data, host: host, rejectFirstToken: true)

        let local = EDFFmpegReader(hardwareDecoding: false)
        let reader = EDFFmpegReader(hardwareDecoding: false)
        nonisolated(unsafe) let readers = (local, reader)
        defer {
            readers.0.close()
            readers.1.close()
        }
        try await onWorker {
            try readers.0.open(url: url)
            try readers.1.open(byteSource: source, name: url.lastPathComponent)
        }

        // The same media info as the file itself.
        let localInfo = local.mediaInfo
        let remoteInfo = reader.mediaInfo
        #expect(remoteInfo["duration"] as? Double == localInfo["duration"] as? Double)
        #expect(remoteInfo["width"] as? Int == localInfo["width"] as? Int)
        #expect(remoteInfo["height"] as? Int == localInfo["height"] as? Int)
        for kind in ["video", "audio", "subtitle"] {
            let remoteTracks = (remoteInfo[kind] as? [[String: Any]] ?? []).map { $0["codec"] as? String }
            let localTracks = (localInfo[kind] as? [[String: Any]] ?? []).map { $0["codec"] as? String }
            #expect(remoteTracks == localTracks)
            #expect(!remoteTracks.isEmpty)
        }

        // Decodes video and audio.
        let decoded = try await onWorker { () -> (video: Bool, audio: Bool) in
            var video = false
            var audio = false
            for _ in 0..<200 where !(video && audio) {
                let frames = try readers.1.readBatch()
                video = video || frames.contains { $0.pixelBuffer != nil }
                audio = audio || frames.contains { $0.audioSampleBuffer != nil }
                if readers.1.atEnd { break }
            }
            return (video, audio)
        }
        #expect(decoded.video)
        #expect(decoded.audio)

        // Seeks.
        let afterSeek = try await onWorker { () -> Double? in
            try readers.1.seek(seconds: 2)
            for _ in 0..<200 {
                if let time = try readers.1.readBatch().first(where: { $0.pixelBuffer != nil })?.presentationTime {
                    return time
                }
            }
            return nil
        }
        #expect(try #require(afterSeek) >= 1.95)

        // Switches to a subtitle track and reads its first cue.
        let tracks = try #require(reader.mediaInfo["subtitle"] as? [[String: Any]])
        let index = try #require(tracks.first?["index"] as? Int)
        let subtitle = try await onWorker { () -> (format: String?, cue: Bool) in
            let configuration = try readers.1.selectSubtitleTrack(index)
            try readers.1.seek(seconds: 0)
            for _ in 0..<200 {
                if try readers.1.readBatch().contains(where: { $0.subtitle != nil }) {
                    return (configuration["format"] as? String, true)
                }
            }
            return (configuration["format"] as? String, false)
        }
        #expect(subtitle.format == "ass")
        #expect(subtitle.cue)

        // Every byte came over the stub, in ranges, after one token refresh.
        let requests = HTTPStub.requests(to: host)
        #expect(requests.count > 3)
        #expect(requests.allSatisfy { $0.header("Range")?.hasPrefix("bytes=") == true })
        #expect(requests.first?.header("Authorization") == "Bearer token0")
        #expect(requests.dropFirst().allSatisfy { $0.header("Authorization") == "Bearer token1" })
    }

    @Test func aFailedRemoteOpenReportsTheProvidersReason() async throws {
        let host = HTTPStub.uniqueHost("ffmpeg")
        HTTPStub.register(host: host) { _ in StubResponse.text("gone", status: 404) }
        defer { HTTPStub.unregister(host: host) }
        let message: String? = try await onWorker {
            let source = RemoteByteSource(
                resolver: StubResolver(host: host, kind: .dropbox, preauthorized: true),
                sessionConfiguration: HTTPStub.configuration()
            )
            let reader = EDFFmpegReader(hardwareDecoding: false)
            defer { reader.close() }
            do {
                try reader.open(byteSource: source, name: "Missing.mkv")
                return nil
            } catch {
                return error.localizedDescription
            }
        }
        #expect(message == "This file is no longer in Dropbox.")
    }

    @Test func refusesRemoteURLsOutsideTheByteSourcePath() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        for location in ["https://example.com/movie.mkv", "gdrive://abc/123/Movie.mkv", "dav://nas.local/Movie.mkv"] {
            #expect(throws: (any Error).self) {
                try reader.open(url: URL(string: location)!)
            }
        }
    }

    @Test func routesEveryRemoteSchemeToFFmpeg() async throws {
        for location in [
            "smb://nas.local/Media/Movie.mp4", "nfs://nas.local/export/Movie.mp4",
            "sftp://nas.local/home/me/Movie.mp4", "davs://cloud.example.com/dav/Movie.mp4",
            "dav://nas.local/dav/Movie.mp4", "s3://abc/bucket/Movie.mp4",
            "gdrive://abc/id/Movie.mp4", "onedrive://abc/d/i/Movie.mp4", "dropbox://abc/id%3Ax/Movie.mp4"
        ] {
            let url = try #require(URL(string: location))
            #expect(FormatRouter.isRemote(url: url))
            #expect(await FormatRouter.route(url) == .ffmpeg)
        }
        #expect(!FormatRouter.isRemote(url: URL(fileURLWithPath: "/tmp/Movie.mp4")))
        #expect(!ConnectorFactory.streamsThroughByteSource(URL(string: "smb://nas.local/a/b.mkv")!))
        #expect(ConnectorFactory.streamsThroughByteSource(URL(string: "gdrive://abc/id/b.mkv")!))
    }
}
