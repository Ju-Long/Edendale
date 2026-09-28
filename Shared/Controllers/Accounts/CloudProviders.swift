//
//  CloudProviders.swift
//  Edendale
//
//  Per-provider OAuth settings and the identity lookup that turns a fresh
//  token into a CloudAccount. Client IDs and app keys aren't secrets for
//  PKCE clients, but they still come from `.secret/Secrets.xcconfig` through
//  Info.plist, like TMDB_READ_ACCESS_TOKEN:
//
//    GOOGLE_DRIVE_CLIENT_ID → GoogleDriveClientID  (an OAuth client of type iOS;
//                                                  Google uses it for macOS too)
//    ONEDRIVE_CLIENT_ID     → OneDriveClientID     (an Entra public client)
//    DROPBOX_APP_KEY        → DropboxAppKey        (a scoped app, Full Dropbox)
//
//  An empty value leaves that provider unavailable in the build.
//

import Foundation

nonisolated enum CloudProviders {

    // MARK: - Client IDs

    static func clientID(for kind: MediaSourceKind, bundle: Bundle = .main) -> String? {
        let key: String
        switch kind {
        case .googleDrive: key = "GoogleDriveClientID"
        case .oneDrive: key = "OneDriveClientID"
        case .dropbox: key = "DropboxAppKey"
        default: return nil
        }
        let value = (bundle.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // An unset build setting reaches Info.plist verbatim as "$(NAME)".
        guard !value.isEmpty, !value.hasPrefix("$(") else { return nil }
        return value
    }

    static func isConfigured(_ kind: MediaSourceKind, bundle: Bundle = .main) -> Bool {
        configuration(for: kind, bundle: bundle) != nil
    }

    // MARK: - OAuth configurations

    static let googleScopes = [
        "openid",
        "email",
        "https://www.googleapis.com/auth/drive.readonly"
    ]
    static let oneDriveScopes = ["Files.Read", "User.Read", "offline_access"]
    static let dropboxScopes = ["files.metadata.read", "files.content.read", "account_info.read"]

    /// Personal and work/school Microsoft accounts both sign in through
    /// the `common` authority.
    static let microsoftAuthority = URL(string: "https://login.microsoftonline.com/common/oauth2/v2.0/")!

    static func configuration(for kind: MediaSourceKind, bundle: Bundle = .main) -> OAuthConfiguration? {
        guard let clientID = clientID(for: kind, bundle: bundle) else { return nil }
        return configuration(for: kind, clientID: clientID)
    }

    static func configuration(for kind: MediaSourceKind, clientID: String) -> OAuthConfiguration? {
        switch kind {
        case .googleDrive:
            // An iOS-type client redirects to its reversed client ID.
            let scheme = googleRedirectScheme(clientID: clientID)
            return OAuthConfiguration(
                kind: kind,
                clientID: clientID,
                authorizationEndpoint: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
                tokenEndpoint: URL(string: "https://oauth2.googleapis.com/token")!,
                deviceAuthorizationEndpoint: nil,
                redirectURI: "\(scheme):/oauth2redirect",
                callbackScheme: scheme,
                scopes: googleScopes,
                additionalAuthorizationParameters: ["prompt": "select_account"]
            )
        case .oneDrive:
            let scheme = "msauth.com.BaBaSaMa.Edendale"
            return OAuthConfiguration(
                kind: kind,
                clientID: clientID,
                authorizationEndpoint: microsoftAuthority.appendingPathComponent("authorize"),
                tokenEndpoint: microsoftAuthority.appendingPathComponent("token"),
                deviceAuthorizationEndpoint: microsoftAuthority.appendingPathComponent("devicecode"),
                redirectURI: "\(scheme)://auth",
                callbackScheme: scheme,
                scopes: oneDriveScopes,
                additionalAuthorizationParameters: ["prompt": "select_account"],
                sendsScopeToTokenEndpoint: true
            )
        case .dropbox:
            // The scheme Dropbox's own SDKs use for mobile and desktop apps.
            let scheme = "db-\(clientID)"
            return OAuthConfiguration(
                kind: kind,
                clientID: clientID,
                authorizationEndpoint: URL(string: "https://www.dropbox.com/oauth2/authorize")!,
                tokenEndpoint: URL(string: "https://api.dropboxapi.com/oauth2/token")!,
                deviceAuthorizationEndpoint: nil,
                redirectURI: "\(scheme)://2/token",
                callbackScheme: scheme,
                scopes: dropboxScopes,
                additionalAuthorizationParameters: ["token_access_type": "offline"]
            )
        default:
            return nil
        }
    }

    /// `com.googleusercontent.apps.<id>` for a client ID
    /// `<id>.apps.googleusercontent.com`.
    static func googleRedirectScheme(clientID: String) -> String {
        let suffix = ".apps.googleusercontent.com"
        let id = clientID.hasSuffix(suffix) ? String(clientID.dropLast(suffix.count)) : clientID
        return "com.googleusercontent.apps.\(id)"
    }

    /// How Apple TV can sign in to a provider on its own. Google limits its
    /// device flow to scopes that can't browse a movie folder, and Dropbox
    /// has none, so both come to the TV through AccountHandoff.
    static func supportsDeviceCode(_ kind: MediaSourceKind) -> Bool {
        kind == .oneDrive
    }

    // MARK: - Identity

    struct Identity: Equatable, Sendable {
        let subject: String
        let email: String?
        let displayName: String?
        var driveID: String? = nil
    }

    /// Who a fresh token belongs to: Google's ID token, Microsoft Graph
    /// `/me` and `/me/drive`, or Dropbox `get_current_account`.
    static func identity(
        for kind: MediaSourceKind,
        tokens: OAuthTokenResponse,
        session: URLSession = .shared
    ) async throws -> Identity {
        switch kind {
        case .googleDrive:
            guard let idToken = tokens.idToken, let claims = decodeJWTClaims(idToken),
                  let subject = claims["sub"] as? String
            else { throw OAuthError.malformedResponse(provider: kind.displayName) }
            return Identity(
                subject: subject,
                email: claims["email"] as? String,
                displayName: claims["name"] as? String
            )

        case .oneDrive:
            struct Me: Decodable {
                let id: String
                let displayName: String?
                let mail: String?
                let userPrincipalName: String?
            }
            struct Drive: Decodable { let id: String }
            let me: Me = try await getJSON(
                URL(string: "https://graph.microsoft.com/v1.0/me?$select=id,displayName,mail,userPrincipalName")!,
                token: tokens.accessToken, provider: kind, session: session
            )
            let drive: Drive = try await getJSON(
                URL(string: "https://graph.microsoft.com/v1.0/me/drive?$select=id")!,
                token: tokens.accessToken, provider: kind, session: session
            )
            return Identity(
                subject: me.id,
                email: me.mail ?? me.userPrincipalName,
                displayName: me.displayName,
                driveID: drive.id
            )

        case .dropbox:
            struct Account: Decodable {
                struct Name: Decodable { let displayName: String? }
                let accountId: String
                let email: String?
                let name: Name?
            }
            var request = URLRequest(url: URL(string: "https://api.dropboxapi.com/2/users/get_current_account")!)
            request.httpMethod = "POST"
            request.setValue("Bearer \(tokens.accessToken)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200..<300).contains(status) else {
                throw OAuthError.http(provider: kind.displayName, status: status)
            }
            guard let account = try? OAuthClient.decoder.decode(Account.self, from: data) else {
                throw OAuthError.malformedResponse(provider: kind.displayName)
            }
            return Identity(subject: account.accountId, email: account.email, displayName: account.name?.displayName)

        default:
            throw OAuthError.notConfigured(provider: kind.displayName)
        }
    }

    /// The payload of a JWT. Signature checks are unnecessary here: the ID
    /// token came straight from Google's token endpoint over TLS (OpenID
    /// Connect Core §3.1.3.7).
    static func decodeJWTClaims(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2, let data = PKCE.decodeBase64URL(String(parts[1])) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    // MARK: - Revocation

    /// Whether Edendale can end its own access at the provider: Google and
    /// Dropbox have revocation endpoints; a Microsoft grant is removed from
    /// the account's app permissions page.
    static func supportsRevocation(_ kind: MediaSourceKind) -> Bool {
        kind == .googleDrive || kind == .dropbox
    }

    /// Where the user manages app access themselves.
    static func accessManagementURL(for kind: MediaSourceKind) -> URL? {
        switch kind {
        case .googleDrive: URL(string: "https://myaccount.google.com/connections")
        case .oneDrive: URL(string: "https://account.live.com/consent/Manage")
        case .dropbox: URL(string: "https://www.dropbox.com/account/connected_apps")
        default: nil
        }
    }

    /// Best effort: ends the grant behind `refreshToken` (and every access
    /// token from it, including an Apple TV's that shares it).
    static func revoke(
        kind: MediaSourceKind,
        refreshToken: String,
        accessToken: String?,
        session: URLSession = .shared
    ) async {
        switch kind {
        case .googleDrive:
            var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(OAuthClient.formEncode([("token", refreshToken)]).utf8)
            _ = try? await session.data(for: request)
        case .dropbox:
            // Revoking an access token also disables its refresh token.
            guard let accessToken else { return }
            var request = URLRequest(url: URL(string: "https://api.dropboxapi.com/2/auth/token/revoke")!)
            request.httpMethod = "POST"
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
            _ = try? await session.data(for: request)
        default:
            break
        }
    }

    // MARK: - Helpers

    private static func getJSON<T: Decodable>(
        _ url: URL,
        token: String,
        provider: MediaSourceKind,
        session: URLSession
    ) async throws -> T {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw OAuthError.http(provider: provider.displayName, status: status)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw OAuthError.malformedResponse(provider: provider.displayName)
        }
    }
}
