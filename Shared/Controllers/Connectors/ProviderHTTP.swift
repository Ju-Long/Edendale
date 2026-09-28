//
//  ProviderHTTP.swift
//  Edendale
//
//  Authorized requests to a cloud provider's API on behalf of one linked
//  account: a Bearer token from CloudTokenProvider, one refresh after a 401,
//  and backoff on rate limits and server errors (see ProviderResponse).
//  Listing and link resolution for Google Drive, OneDrive, and Dropbox all
//  go through here. Tokens and URLs are never logged.
//

import Foundation

nonisolated struct ProviderHTTP: Sendable {
    let kind: MediaSourceKind
    let accountKey: String
    var tokens: CloudTokenProvider = .shared
    var session: URLSession = .shared
    /// Delays before each retry of a rate-limited request.
    var backoffDelays: [TimeInterval] = [0.5, 1, 2]

    /// Sends the request `build` makes with a fresh access token and returns
    /// a 2xx response. Other statuses throw a ConnectorError.
    func send(_ build: (String) throws -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        var rejected: String?
        var refreshed = false
        var backoffs = 0

        while true {
            try Task.checkCancellation()
            let token = try await tokens.accessToken(kind: kind, accountKey: accountKey, rejecting: rejected)
            var request = try build(token)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.cachePolicy = .reloadIgnoringLocalCacheData

            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch let error as URLError where ProviderResponse.isTransient(error) {
                guard backoffs < backoffDelays.count else { throw error }
                try await Task.sleep(for: .milliseconds(Int(backoffDelays[backoffs] * 1000)))
                backoffs += 1
                continue
            }
            guard let http = response as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }
            if (200..<300).contains(http.statusCode) {
                return (data, http)
            }

            switch ProviderResponse.action(
                status: http.statusCode,
                body: data,
                headers: http.allHeaderFields,
                kind: kind,
                preauthorizedLink: false
            ) {
            case .refresh:
                guard !refreshed else { throw ConnectorError.signInRequired(provider: kind.displayName) }
                refreshed = true
                rejected = token
            case .backoff(let retryAfter):
                guard backoffs < backoffDelays.count else {
                    throw ConnectorError.rateLimited(provider: kind.displayName)
                }
                let delay = retryAfter ?? backoffDelays[backoffs]
                try await Task.sleep(for: .milliseconds(Int(delay * 1000)))
                backoffs += 1
            case .fail(let error):
                throw error
            }
        }
    }

    /// `send`, decoding a JSON body.
    func json<T: Decodable>(
        _ type: T.Type,
        decoder: JSONDecoder = ProviderHTTP.decoder,
        _ build: (String) throws -> URLRequest
    ) async throws -> T {
        let (data, _) = try await send(build)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw ConnectorError.serverError(provider: kind.displayName, status: 200)
        }
    }

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = ISO8601.date(from: string) { return date }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Bad date")
        }
        return decoder
    }()

    /// RFC 3339 dates with or without fractional seconds.
    nonisolated enum ISO8601 {
        static func date(from string: String) -> Date? {
            if let date = try? Date(string, strategy: .iso8601) { return date }
            return try? Date(
                string,
                strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)
            )
        }
    }

    /// A JSON POST body.
    static func jsonRequest(_ url: URL, body: [String: Any]) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }
}
