//
//  RemoteContentResolver.swift
//  Edendale
//
//  How RemoteByteSource asks for a remote file's bytes. Each HTTP provider
//  supplies a resolver (see ConnectorFactory): Google Drive and WebDAV
//  answer with an authorized request to the file itself; OneDrive, Dropbox,
//  and S3 with a short-lived pre-authorized link. `ProviderResponse` turns
//  the HTTP status of a failed request into what to do next.
//

import Foundation

nonisolated protocol RemoteContentResolver: Sendable {
    var kind: MediaSourceKind { get }
    /// Requests are pre-authorized links, so a refresh fetches a new link
    /// rather than a new access token.
    var usesPreauthorizedLinks: Bool { get }
    /// A request for the file's bytes: Bearer-authorized (Drive), or a
    /// short-lived pre-authorized link (OneDrive, Dropbox, S3). `refresh`
    /// forces a new token or link after a 401, 403, or 410.
    func contentRequest(refresh: Bool) async throws -> URLRequest
    /// A login for HTTP authentication challenges (WebDAV Basic or Digest).
    func credential(for protectionSpace: URLProtectionSpace) -> URLCredential?
}

nonisolated extension RemoteContentResolver {
    var usesPreauthorizedLinks: Bool { false }

    func credential(for protectionSpace: URLProtectionSpace) -> URLCredential? { nil }
}

// MARK: - Response classification

nonisolated enum ProviderResponse {

    enum Action: Equatable, Sendable {
        /// Get a new access token or link, once, then retry.
        case refresh
        /// Wait (for `Retry-After` when given), then retry.
        case backoff(TimeInterval?)
        case fail(ConnectorError)
    }

    /// What to do about a non-2xx response to a listing or content request.
    static func action(
        status: Int,
        body: Data,
        headers: [AnyHashable: Any],
        kind: MediaSourceKind,
        preauthorizedLink: Bool
    ) -> Action {
        let provider = kind.displayName
        switch status {
        case 401:
            return .refresh
        case 403:
            let text = String(decoding: body.prefix(16_384), as: UTF8.self).lowercased()
            if text.contains("ratelimitexceeded") || text.contains("rate_limit_exceeded")
                || text.contains("slowdown") || text.contains("too_many_requests") {
                return .backoff(retryAfter(headers))
            }
            if text.contains("cannotdownloadabusivefile") {
                return .fail(.abusiveFile)
            }
            if text.contains("downloadquotaexceeded") {
                return .fail(.rateLimited(provider: provider))
            }
            // An expired signed link (S3's "Request has expired") or a
            // OneDrive download URL past its lifetime: resolve a new one.
            return preauthorizedLink ? .refresh : .fail(.accessDenied(provider: provider))
        case 404:
            return .fail(.notFound(provider: provider))
        case 409:
            // Dropbox reports endpoint errors as 409 with a summary.
            let text = String(decoding: body.prefix(16_384), as: UTF8.self)
            if text.contains("not_found") { return .fail(.notFound(provider: provider)) }
            return .fail(.serverError(provider: provider, status: status))
        case 410:
            // Dropbox temporary links expire after four hours.
            return .refresh
        case 408, 429, 500, 502, 503, 504:
            return .backoff(retryAfter(headers))
        default:
            return .fail(.serverError(provider: provider, status: status))
        }
    }

    /// `Retry-After` in seconds, capped so a hostile value can't stall
    /// playback for minutes.
    static func retryAfter(_ headers: [AnyHashable: Any]) -> TimeInterval? {
        let value = headers.first { ($0.key as? String)?.lowercased() == "retry-after" }?.value as? String
        guard let value, let seconds = TimeInterval(value.trimmingCharacters(in: .whitespaces)),
              seconds >= 0
        else { return nil }
        return min(seconds, 10)
    }

    /// Transient transport failures worth retrying with backoff.
    static func isTransient(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut, .networkConnectionLost, .cannotConnectToHost, .dnsLookupFailed,
             .cannotFindHost, .resourceUnavailable, .badServerResponse:
            true
        default:
            false
        }
    }
}
