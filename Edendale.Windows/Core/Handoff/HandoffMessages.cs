// The messages after the channel is up (SYNC-PROTOCOL.md §9.6–§9.8): role,
// offer, result, and error, with the version-1 Account and Login payload
// shapes the Apple and Android handoffs already send, and the Google Drive
// ecosystem rule. ToString never prints a token or password.

using System.Text.Json;
using System.Text.Json.Nodes;

namespace Edendale.Windows.Core.Handoff;

public enum HandoffRole
{
    /// <summary>Holds the logins and sends them.</summary>
    Sender,
    /// <summary>Gets the logins and stores them.</summary>
    Receiver,
}

/// <summary>`{"type":"role","role":…,"name":…,"platform":…}`; the platform is this build's addition for §9.8.</summary>
public sealed record HandoffRoleMessage(HandoffRole Role, string Name, string? Platform)
{
    public static string RoleName(HandoffRole role) => role == HandoffRole.Sender ? "sender" : "receiver";
}

/// <summary>A cloud account as the version-1 handoff carries it (§9.7).</summary>
public sealed record HandoffAccount(
    string Kind,
    string Subject,
    string? Email,
    string? DisplayName,
    string RefreshToken,
    IReadOnlyList<string> Scopes,
    string? DriveId)
{
    /// <summary>Never the token, and not the email either: this can end up in a log line.</summary>
    public override string ToString() => $"Account({Kind})";
}

public sealed record HandoffS3(string Endpoint, string Region, string Bucket, bool PathStyle);

/// <summary>A server login as the version-1 handoff carries it (§9.7).</summary>
public sealed record HandoffLogin(
    string Kind,
    string Host,
    int? Port,
    string User,
    string Password,
    string? HostKeyFingerprint = null,
    string? CertificateFingerprint = null,
    HandoffS3? S3 = null,
    IReadOnlyList<string>? Addresses = null)
{
    /// <summary>Never the password.</summary>
    public override string ToString() => $"Login({Kind}, {Host}{(Port is int port ? ":" + port : "")})";
}

/// <summary>
/// The `location` of an offer (§9.6). <see cref="Url"/> is the
/// credential-free root URL of a server location (`url` on the wire, the
/// field the Apple and Android branches added; `path` is read too).
/// </summary>
public sealed record HandoffLocation(
    string Provider,
    string Space,
    HandoffAccount? Account = null,
    HandoffLogin? Login = null,
    string? GoogleAccountHint = null,
    string? GoogleFolderId = null,
    string? Url = null)
{
    public override string ToString() => $"Location({Provider}, {Space})";
}

/// <summary>What the sender offers, or a decline.</summary>
public sealed record HandoffOffer(
    bool Approved,
    HandoffLocation? Location = null,
    IReadOnlyList<HandoffAccount>? Accounts = null,
    IReadOnlyList<HandoffLogin>? Logins = null)
{
    public static readonly HandoffOffer Declined = new(false);

    public IReadOnlyList<HandoffAccount> AccountList => Accounts ?? [];
    public IReadOnlyList<HandoffLogin> LoginList => Logins ?? [];

    public bool IsEmpty => Location is null && AccountList.Count == 0 && LoginList.Count == 0;

    public override string ToString() => Approved
        ? $"Offer(location: {Location?.ToString() ?? "none"}, {AccountList.Count} accounts, {LoginList.Count} logins)"
        : "Offer(declined)";
}

public sealed record HandoffFailure(string Item, string Reason);

/// <summary>What the receiver stored and what failed (§9.6 step 4).</summary>
public sealed record HandoffResult(IReadOnlyList<string> Stored, IReadOnlyList<HandoffFailure> Failed)
{
    public static readonly HandoffResult Empty = new([], []);

    /// <summary>The `stored` entry for an account: `account:&lt;kind&gt;:&lt;subject&gt;`.</summary>
    public static string AccountItem(HandoffAccount account) => $"account:{account.Kind}:{account.Subject}";

    /// <summary>The `stored` entry for a login: `login:&lt;kind&gt;:&lt;host&gt;`.</summary>
    public static string LoginItem(HandoffLogin login) => $"login:{login.Kind}:{login.Host}";

    public const string LocationItem = "location";
}

/// <summary>§9.8: which platforms share a Google OAuth client.</summary>
public static class HandoffEcosystem
{
    public static string? Ecosystem(string? platform) => platform switch
    {
        "ios" or "ipados" or "macos" or "tvos" or "visionos" => "apple",
        "android" or "androidtv" or "horizon" => "android",
        "windows" or "xbox" => "windows",
        _ => null,
    };

    /// <summary>A Google refresh token moves only between platforms of one ecosystem; unknown platforms never qualify.</summary>
    public static bool SharesGoogleClient(string? platformA, string? platformB)
    {
        var a = Ecosystem(platformA);
        return a is not null && a == Ecosystem(platformB);
    }

    /// <summary>Platforms that cannot sign in to Google themselves (§9.8).</summary>
    public static bool CannotSignInToGoogle(string? platform) => platform is "tvos" or "androidtv";
}

public static class HandoffMessages
{
    // ------------------------------------------------------------------
    // role
    // ------------------------------------------------------------------

    public static byte[] EncodeRole(HandoffRoleMessage role) => HandoffFrames.Message("role", writer =>
    {
        writer.WriteString("role", HandoffRoleMessage.RoleName(role.Role));
        writer.WriteString("name", role.Name);
        if (role.Platform is not null) writer.WriteString("platform", role.Platform);
    });

    public static HandoffRoleMessage DecodeRole(byte[] body)
    {
        var fields = HandoffFrames.Expect(body, "role", requireVersion: false);
        var role = HandoffFrames.Require(fields, "role") switch
        {
            "sender" => HandoffRole.Sender,
            "receiver" => HandoffRole.Receiver,
            _ => throw new HandoffException(HandoffError.Malformed, "role"),
        };
        return new HandoffRoleMessage(role, HandoffFrames.String(fields, "name") ?? "", HandoffFrames.String(fields, "platform"));
    }

    // ------------------------------------------------------------------
    // offer
    // ------------------------------------------------------------------

    public static byte[] EncodeOffer(HandoffOffer offer) => HandoffFrames.Message("offer", writer =>
    {
        writer.WriteString("status", offer.Approved ? "approved" : "declined");
        if (!offer.Approved) return;
        if (offer.Location is { } location)
        {
            writer.WriteStartObject("location");
            writer.WriteString("provider", location.Provider);
            writer.WriteString("space", location.Space);
            if (location.Account is { } account) WriteAccount(writer, "account", account);
            if (location.Login is { } login) WriteLogin(writer, "login", login);
            if (location.GoogleAccountHint is { } hint) writer.WriteString("googleAccountHint", hint);
            if (location.GoogleFolderId is { } folder) writer.WriteString("googleFolderId", folder);
            if (location.Url is { } url) writer.WriteString("url", url);
            writer.WriteEndObject();
        }
        writer.WriteStartArray("accounts");
        foreach (var account in offer.AccountList) WriteAccount(writer, null, account);
        writer.WriteEndArray();
        writer.WriteStartArray("logins");
        foreach (var login in offer.LoginList) WriteLogin(writer, null, login);
        writer.WriteEndArray();
    });

    public static HandoffOffer DecodeOffer(byte[] body)
    {
        var fields = HandoffFrames.Expect(body, "offer", requireVersion: false);
        var status = HandoffFrames.Require(fields, "status");
        if (status == "declined") return HandoffOffer.Declined;
        if (status != "approved") throw new HandoffException(HandoffError.Malformed, "status");

        HandoffLocation? location = null;
        if (fields["location"] is JsonObject locationNode)
        {
            location = new HandoffLocation(
                HandoffFrames.Require(locationNode, "provider"),
                HandoffFrames.Require(locationNode, "space"),
                locationNode["account"] is JsonObject account ? ReadAccount(account) : null,
                locationNode["login"] is JsonObject login ? ReadLogin(login) : null,
                HandoffFrames.String(locationNode, "googleAccountHint"),
                HandoffFrames.String(locationNode, "googleFolderId"),
                HandoffFrames.String(locationNode, "url") ?? HandoffFrames.String(locationNode, "path"));
        }
        var accounts = new List<HandoffAccount>();
        if (fields["accounts"] is JsonArray accountNodes)
        {
            foreach (var node in accountNodes)
            {
                if (node is JsonObject account) accounts.Add(ReadAccount(account));
            }
        }
        var logins = new List<HandoffLogin>();
        if (fields["logins"] is JsonArray loginNodes)
        {
            foreach (var node in loginNodes)
            {
                if (node is JsonObject login) logins.Add(ReadLogin(login));
            }
        }
        return new HandoffOffer(true, location, accounts, logins);
    }

    private static void WriteAccount(Utf8JsonWriter writer, string? name, HandoffAccount account)
    {
        if (name is null) writer.WriteStartObject(); else writer.WriteStartObject(name);
        writer.WriteString("kind", account.Kind);
        writer.WriteString("subject", account.Subject);
        if (account.Email is { } email) writer.WriteString("email", email); else writer.WriteNull("email");
        if (account.DisplayName is { } display) writer.WriteString("displayName", display); else writer.WriteNull("displayName");
        writer.WriteString("refreshToken", account.RefreshToken);
        writer.WriteStartArray("scopes");
        foreach (var scope in account.Scopes) writer.WriteStringValue(scope);
        writer.WriteEndArray();
        if (account.DriveId is { } drive) writer.WriteString("driveId", drive); else writer.WriteNull("driveId");
        writer.WriteEndObject();
    }

    private static HandoffAccount ReadAccount(JsonObject node)
    {
        var scopes = new List<string>();
        if (node["scopes"] is JsonArray array)
        {
            foreach (var scope in array)
            {
                if (scope is JsonValue value && value.TryGetValue<string>(out var text)) scopes.Add(text);
            }
        }
        var refreshToken = HandoffFrames.Require(node, "refreshToken");
        if (refreshToken.Length == 0) throw new HandoffException(HandoffError.Malformed, "empty token");
        return new HandoffAccount(
            HandoffFrames.Require(node, "kind"),
            HandoffFrames.Require(node, "subject"),
            HandoffFrames.String(node, "email"),
            HandoffFrames.String(node, "displayName"),
            refreshToken,
            scopes,
            HandoffFrames.String(node, "driveId"));
    }

    private static void WriteLogin(Utf8JsonWriter writer, string? name, HandoffLogin login)
    {
        if (name is null) writer.WriteStartObject(); else writer.WriteStartObject(name);
        writer.WriteString("kind", login.Kind);
        writer.WriteString("host", login.Host);
        if (login.Port is int port) writer.WriteNumber("port", port); else writer.WriteNull("port");
        writer.WriteString("user", login.User);
        writer.WriteString("password", login.Password);
        if (login.HostKeyFingerprint is { } key) writer.WriteString("hostKeyFingerprint", key); else writer.WriteNull("hostKeyFingerprint");
        if (login.CertificateFingerprint is { } certificate) writer.WriteString("certificateFingerprint", certificate); else writer.WriteNull("certificateFingerprint");
        if (login.S3 is { } s3)
        {
            writer.WriteStartObject("s3");
            writer.WriteString("endpoint", s3.Endpoint);
            writer.WriteString("region", s3.Region);
            writer.WriteString("bucket", s3.Bucket);
            writer.WriteBoolean("pathStyle", s3.PathStyle);
            writer.WriteEndObject();
        }
        writer.WriteStartArray("addresses");
        foreach (var address in login.Addresses ?? []) writer.WriteStringValue(address);
        writer.WriteEndArray();
        writer.WriteEndObject();
    }

    private static HandoffLogin ReadLogin(JsonObject node)
    {
        int? port = node["port"] is JsonValue portValue && portValue.TryGetValue<int>(out var number) ? number : null;
        HandoffS3? s3 = null;
        if (node["s3"] is JsonObject s3Node)
        {
            s3 = new HandoffS3(
                HandoffFrames.Require(s3Node, "endpoint"),
                HandoffFrames.String(s3Node, "region") ?? "us-east-1",
                HandoffFrames.Require(s3Node, "bucket"),
                s3Node["pathStyle"] is JsonValue pathStyle && pathStyle.TryGetValue<bool>(out var flag) && flag);
        }
        var addresses = new List<string>();
        if (node["addresses"] is JsonArray array)
        {
            foreach (var address in array)
            {
                if (address is JsonValue value && value.TryGetValue<string>(out var text)) addresses.Add(text);
            }
        }
        return new HandoffLogin(
            HandoffFrames.Require(node, "kind"),
            HandoffFrames.Require(node, "host"),
            port,
            HandoffFrames.String(node, "user") ?? "",
            HandoffFrames.String(node, "password") ?? "",
            HandoffFrames.String(node, "hostKeyFingerprint"),
            HandoffFrames.String(node, "certificateFingerprint"),
            s3,
            addresses);
    }

    // ------------------------------------------------------------------
    // result
    // ------------------------------------------------------------------

    public static byte[] EncodeResult(HandoffResult result) => HandoffFrames.Message("result", writer =>
    {
        writer.WriteStartArray("stored");
        foreach (var item in result.Stored) writer.WriteStringValue(item);
        writer.WriteEndArray();
        writer.WriteStartArray("failed");
        foreach (var failure in result.Failed)
        {
            writer.WriteStartObject();
            writer.WriteString("item", failure.Item);
            writer.WriteString("reason", failure.Reason);
            writer.WriteEndObject();
        }
        writer.WriteEndArray();
    });

    public static HandoffResult DecodeResult(byte[] body)
    {
        var fields = HandoffFrames.Expect(body, "result", requireVersion: false);
        var stored = new List<string>();
        if (fields["stored"] is JsonArray storedNodes)
        {
            foreach (var node in storedNodes)
            {
                if (node is JsonValue value && value.TryGetValue<string>(out var text)) stored.Add(text);
            }
        }
        var failed = new List<HandoffFailure>();
        if (fields["failed"] is JsonArray failedNodes)
        {
            foreach (var node in failedNodes)
            {
                if (node is JsonObject failure) failed.Add(new HandoffFailure(HandoffFrames.String(failure, "item") ?? "", HandoffFrames.String(failure, "reason") ?? ""));
            }
        }
        return new HandoffResult(stored, failed);
    }
}
