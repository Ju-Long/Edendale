using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The dependency-free OAuth client (DIFF.md §3.12), ported from Apple's
/// OAuthTests: the RFC 7636 PKCE vector, each provider's authorization URL,
/// token responses, and the single-flight token provider. Token endpoints
/// are stubs; no real credential is used anywhere.
/// </summary>
[TestClass]
public sealed class OAuthTests
{
    private sealed class PlainProtector : ISecretProtector
    {
        public byte[] Protect(byte[] data) => data;
        public byte[] Unprotect(byte[] data) => data;
    }

    private string _directory = "";

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-oauth-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_directory, recursive: true);

    private static OAuthConfiguration Configuration(bool scopeToToken = true) => new()
    {
        Kind = MediaSourceKind.OneDrive,
        ClientId = "client-123",
        AuthorizationEndpoint = "https://oauth.example/authorize",
        TokenEndpoint = "https://oauth.example/token",
        Scopes = ["Files.Read", "offline_access"],
        SendsScopeToTokenEndpoint = scopeToToken,
    };

    // ------------------------------------------------------------------
    // PKCE
    // ------------------------------------------------------------------

    [TestMethod]
    public void MatchesTheRfc7636AppendixBVector()
    {
        Assert.AreEqual("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    [TestMethod]
    public void VerifiersAreLongUnreservedAndUnique()
    {
        var verifier = Pkce.MakeVerifier();
        Assert.AreEqual(43, verifier.Length);
        Assert.IsTrue(verifier.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '.' or '_' or '~'));
        Assert.AreNotEqual(verifier, Pkce.MakeVerifier());
        Assert.AreNotEqual(Pkce.MakeState(), Pkce.MakeState());
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    [TestMethod]
    public void BuildsEachProvidersAuthorizationUrl()
    {
        var microsoft = CloudProviders.Configuration(MediaSourceKind.OneDrive, "guid")!;
        CollectionAssert.AreEqual(new[] { "Files.Read", "User.Read", "offline_access" }, microsoft.Scopes.ToArray());
        Assert.IsTrue(microsoft.SendsScopeToTokenEndpoint);
        Assert.IsNull(microsoft.LoopbackPort);

        var url = new OAuthClient(microsoft).AuthorizationUrl("http://127.0.0.1:50123/", "state-1", "challenge-1");
        var uri = new Uri(url);
        var query = OAuthClient.ParseQuery(uri.Query);
        Assert.AreEqual("login.microsoftonline.com", uri.Host);
        Assert.AreEqual("/common/oauth2/v2.0/authorize", uri.AbsolutePath);
        Assert.AreEqual("guid", query["client_id"]);
        Assert.AreEqual("code", query["response_type"]);
        Assert.AreEqual("http://127.0.0.1:50123/", query["redirect_uri"]);
        Assert.AreEqual("Files.Read User.Read offline_access", query["scope"]);
        Assert.AreEqual("state-1", query["state"]);
        Assert.AreEqual("challenge-1", query["code_challenge"]);
        Assert.AreEqual("S256", query["code_challenge_method"]);
        Assert.AreEqual("select_account", query["prompt"]);
        // No client secret, ever.
        Assert.IsFalse(query.ContainsKey("client_secret"));

        var dropbox = CloudProviders.Configuration(MediaSourceKind.Dropbox, "appkey")!;
        StringAssert.Contains(new OAuthClient(dropbox).AuthorizationUrl("http://127.0.0.1:49735/", "s", "c"), "token_access_type=offline");
        Assert.AreEqual(CloudProviders.DropboxLoopbackPort, dropbox.LoopbackPort);
        CollectionAssert.AreEqual(new[] { "files.metadata.read", "files.content.read", "account_info.read" }, dropbox.Scopes.ToArray());

        var google = CloudProviders.Configuration(MediaSourceKind.GoogleDrive, "123-abc.apps.googleusercontent.com", "GOCSPX-secret")!;
        CollectionAssert.AreEqual(new[] { "openid", "email", "https://www.googleapis.com/auth/drive.readonly" }, google.Scopes.ToArray());
        Assert.IsNull(google.LoopbackPort);
        Assert.IsFalse(google.SendsScopeToTokenEndpoint);
        var googleUri = new Uri(new OAuthClient(google).AuthorizationUrl("http://127.0.0.1:50123/", "s", "c"));
        var googleQuery = OAuthClient.ParseQuery(googleUri.Query);
        Assert.AreEqual("accounts.google.com", googleUri.Host);
        Assert.AreEqual("/o/oauth2/v2/auth", googleUri.AbsolutePath);
        Assert.AreEqual("offline", googleQuery["access_type"]);
        Assert.AreEqual("select_account consent", googleQuery["prompt"]);
        Assert.AreEqual("S256", googleQuery["code_challenge_method"]);
        // The Desktop client's secret goes to the token endpoint only (D9).
        Assert.IsFalse(googleQuery.ContainsKey("client_secret"));
        Assert.AreEqual("https://oauth2.googleapis.com/token", google.TokenEndpoint);
    }

    [TestMethod]
    public async Task OnlyGoogleSendsItsClientSecretAndOnlyToTheTokenEndpoint()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object> { ["access_token"] = "a", ["expires_in"] = 3600 }));
        var google = new OAuthClient(CloudProviders.Configuration(MediaSourceKind.GoogleDrive, "id", "GOCSPX-secret")!, stub.Client);
        await google.ExchangeAsync("code", "verifier", "http://127.0.0.1:1/", CancellationToken.None);
        await google.RefreshAsync("refresh", CancellationToken.None);
        Assert.AreEqual("GOCSPX-secret", stub.Requests[0].Form["client_secret"]);
        Assert.AreEqual("GOCSPX-secret", stub.Requests[1].Form["client_secret"]);

        // Without a secret configured, none is sent; OneDrive never has one.
        var noSecret = new OAuthClient(CloudProviders.Configuration(MediaSourceKind.GoogleDrive, "id")!, stub.Client);
        await noSecret.RefreshAsync("refresh", CancellationToken.None);
        Assert.IsFalse(stub.Requests[2].Form.ContainsKey("client_secret"));
        Assert.IsNull(CloudProviders.Configuration(MediaSourceKind.OneDrive, "guid", "ignored")!.ClientSecret);
    }

    [TestMethod]
    public void TheGoogleSecretComesFromItsOwnKeyAndOnlyForGoogle()
    {
        var original = CloudClientIds.Source;
        try
        {
            CloudClientIds.Source = name => name switch
            {
                "GOOGLE_DRIVE_CLIENT_ID" => "123-abc.apps.googleusercontent.com",
                "GOOGLE_DRIVE_CLIENT_SECRET" => "GOCSPX-secret",
                _ => "",
            };
            Assert.IsTrue(CloudProviders.IsConfigured(MediaSourceKind.GoogleDrive));
            Assert.AreEqual("GOCSPX-secret", CloudProviders.Configuration(MediaSourceKind.GoogleDrive)!.ClientSecret);
            Assert.IsNull(CloudClientIds.SecretFor(MediaSourceKind.OneDrive));
            Assert.IsNull(CloudClientIds.SecretFor(MediaSourceKind.Dropbox));

            // The client ID alone decides whether Drive is offered.
            CloudClientIds.Source = name => name == "GOOGLE_DRIVE_CLIENT_SECRET" ? "GOCSPX-secret" : "";
            Assert.IsFalse(CloudProviders.IsConfigured(MediaSourceKind.GoogleDrive));
        }
        finally
        {
            CloudClientIds.Source = original;
        }
    }

    [TestMethod]
    public async Task GoogleIdentityComesFromTheIdToken()
    {
        static string Segment(string json) => Pkce.Base64Url(System.Text.Encoding.UTF8.GetBytes(json));
        var idToken = $"{Segment("""{"alg":"RS256"}""")}.{Segment("""{"sub":"110169484474386276334","email":"me@example.com","name":"Me"}""")}.signature";
        var identity = await CloudProviders.IdentityAsync(MediaSourceKind.GoogleDrive,
            new OAuthTokenResponse { AccessToken = "a", IdToken = idToken }, null, CancellationToken.None);
        Assert.AreEqual("110169484474386276334", identity.Subject);
        Assert.AreEqual("me@example.com", identity.Email);
        Assert.AreEqual("Me", identity.DisplayName);
        Assert.AreEqual("fc33299258dcfba4155a09b5ec6ea6c9", SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, identity.Subject));

        var missing = await Assert.ThrowsExceptionAsync<OAuthException>(() => CloudProviders.IdentityAsync(MediaSourceKind.GoogleDrive,
            new OAuthTokenResponse { AccessToken = "a" }, null, CancellationToken.None));
        Assert.AreEqual(OAuthFailure.MalformedResponse, missing.Failure);
        Assert.IsNull(CloudProviders.DecodeJwtClaims("not-a-jwt"));
        Assert.IsNull(CloudProviders.DecodeJwtClaims("a.!!!.c"));
    }

    [TestMethod]
    public void AGoogleSignInWithoutDriveAccessIsRefused()
    {
        var withoutDrive = new OAuthTokenResponse { AccessToken = "a", Scope = "openid https://www.googleapis.com/auth/userinfo.email" };
        var error = Assert.ThrowsException<OAuthException>(() => CloudProviders.CheckGrantedScopes(MediaSourceKind.GoogleDrive, withoutDrive));
        Assert.AreEqual(OAuthFailure.DriveAccessMissing, error.Failure);
        StringAssert.Contains(error.Message, "leave Drive access selected");

        CloudProviders.CheckGrantedScopes(MediaSourceKind.GoogleDrive,
            new OAuthTokenResponse { AccessToken = "a", Scope = "openid https://www.googleapis.com/auth/drive.readonly" });
        // Other providers, and a response that lists no scopes, aren't checked.
        CloudProviders.CheckGrantedScopes(MediaSourceKind.OneDrive, withoutDrive);
        CloudProviders.CheckGrantedScopes(MediaSourceKind.GoogleDrive, new OAuthTokenResponse { AccessToken = "a" });
    }

    [TestMethod]
    public void AnEmptyClientIdHidesTheProvider()
    {
        var original = CloudClientIds.Source;
        try
        {
            CloudClientIds.Source = name => name == "DROPBOX_APP_KEY" ? "appkey" : "";
            Assert.IsTrue(CloudProviders.IsConfigured(MediaSourceKind.Dropbox));
            Assert.IsFalse(CloudProviders.IsConfigured(MediaSourceKind.OneDrive));
            Assert.IsFalse(CloudProviders.IsConfigured(MediaSourceKind.GoogleDrive));
        }
        finally
        {
            CloudClientIds.Source = original;
        }
    }

    [TestMethod]
    public void ReadsTheAuthorizationCodeOnlyWithTheMatchingState()
    {
        Assert.AreEqual("abc", OAuthClient.AuthorizationCode("?code=abc&state=s1", "s1", "OneDrive"));
        Assert.AreEqual(OAuthFailure.StateMismatch,
            Assert.ThrowsException<OAuthException>(() => OAuthClient.AuthorizationCode("code=abc&state=s1", "other", "OneDrive")).Failure);
        Assert.AreEqual(OAuthFailure.AuthorizationDenied,
            Assert.ThrowsException<OAuthException>(() => OAuthClient.AuthorizationCode("error=access_denied&state=s1", "s1", "OneDrive")).Failure);
        Assert.AreEqual(OAuthFailure.MissingAuthorizationCode,
            Assert.ThrowsException<OAuthException>(() => OAuthClient.AuthorizationCode("state=s1", "s1", "OneDrive")).Failure);
    }

    [TestMethod]
    public async Task ExchangesTheCodeWithTheVerifier()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object>
        {
            ["access_token"] = "access-1",
            ["token_type"] = "Bearer",
            ["expires_in"] = 3600,
            ["refresh_token"] = "refresh-1",
            ["scope"] = "Files.Read offline_access",
        }));
        var client = new OAuthClient(Configuration(), stub.Client);

        var tokens = await client.ExchangeAsync("the-code", "the-verifier", "http://127.0.0.1:1234/", CancellationToken.None);
        Assert.AreEqual("access-1", tokens.AccessToken);
        Assert.AreEqual("refresh-1", tokens.RefreshToken);
        Assert.AreEqual(3600, tokens.ExpiresIn);
        CollectionAssert.AreEqual(new[] { "Files.Read", "offline_access" }, tokens.GrantedScopes!.ToArray());

        var request = stub.Requests[0];
        Assert.AreEqual("POST", request.Method);
        Assert.AreEqual("application/x-www-form-urlencoded", request.Header("Content-Type"));
        var form = request.Form;
        Assert.AreEqual("authorization_code", form["grant_type"]);
        Assert.AreEqual("the-code", form["code"]);
        Assert.AreEqual("the-verifier", form["code_verifier"]);
        Assert.AreEqual("client-123", form["client_id"]);
        Assert.AreEqual("http://127.0.0.1:1234/", form["redirect_uri"]);
        Assert.AreEqual("Files.Read offline_access", form["scope"]);
        Assert.IsFalse(form.ContainsKey("client_secret"));
    }

    [TestMethod]
    public async Task MapsAnInvalidGrantToSignInAgain()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object>
        {
            ["error"] = "invalid_grant",
            ["error_description"] = "Token has been expired or revoked.",
        }, 400));
        var client = new OAuthClient(Configuration(), stub.Client);
        var error = await Assert.ThrowsExceptionAsync<OAuthException>(() => client.RefreshAsync("old", CancellationToken.None));
        Assert.AreEqual(OAuthFailure.InvalidGrant, error.Failure);
        var form = stub.Requests[0].Form;
        Assert.AreEqual("refresh_token", form["grant_type"]);
        Assert.AreEqual("old", form["refresh_token"]);
    }

    [TestMethod]
    public void FormEncodingEscapesReservedCharacters()
    {
        Assert.AreEqual("a%20b=x%2By%26z%3D1%2F2", OAuthClient.FormEncode([("a b", "x+y&z=1/2")]));
    }

    [TestMethod]
    public async Task TheLoopbackRedirectReturnsTheCallbackQuery()
    {
        using var redirect = new LoopbackRedirect(port: null);
        Assert.AreEqual($"http://127.0.0.1:{redirect.Port}/", redirect.RedirectUri);
        var waiting = redirect.WaitForQueryAsync("<p>Done</p>", CancellationToken.None);

        using var http = new HttpClient();
        // A stray request (a favicon) is answered and ignored.
        var favicon = await http.GetAsync($"{redirect.RedirectUri}favicon.ico");
        Assert.AreEqual(System.Net.HttpStatusCode.NotFound, favicon.StatusCode);
        var page = await http.GetStringAsync($"{redirect.RedirectUri}?code=abc&state=s1");
        Assert.AreEqual("<p>Done</p>", page);
        Assert.AreEqual("abc", OAuthClient.AuthorizationCode(await waiting, "s1", "OneDrive"));
    }

    // ------------------------------------------------------------------
    // Token provider
    // ------------------------------------------------------------------

    private static CloudAccount Account(string refreshToken = "refresh-1") => new()
    {
        Kind = "onedrive",
        Subject = "user-1",
        Email = "me@example.com",
        RefreshToken = refreshToken,
        DriveId = "drive-1",
    };

    private (CloudTokenProvider Tokens, CloudAccountVault Vault) Provider(HttpStub stub, Func<DateTimeOffset>? now = null)
    {
        var vault = new CloudAccountVault(Path.Combine(_directory, Guid.NewGuid().ToString("N")), new PlainProtector());
        vault.Save(Account());
        return (new CloudTokenProvider(vault, stub.Client, _ => Configuration(), now), vault);
    }

    [TestMethod]
    public async Task ConcurrentRequestsShareOneRefresh()
    {
        var refreshes = 0;
        var stub = new HttpStub(_ =>
        {
            var count = Interlocked.Increment(ref refreshes);
            Thread.Sleep(200);
            return HttpStub.Json(new Dictionary<string, object> { ["access_token"] = $"access-{count}", ["expires_in"] = 3600 });
        });
        var (tokens, _) = Provider(stub);
        var account = Account();

        var results = await Task.WhenAll(Enumerable.Range(0, 10).Select(_ =>
            Task.Run(() => tokens.AccessTokenAsync(MediaSourceKind.OneDrive, account.Key))));
        Assert.IsTrue(results.All(token => token == "access-1"));
        Assert.AreEqual(1, refreshes);
        // Cached afterwards.
        Assert.AreEqual("access-1", await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, account.Key));
        Assert.AreEqual(1, refreshes);
    }

    [TestMethod]
    public async Task ARejectedTokenIsRefreshedOnceAndRotatedTokensAreKept()
    {
        var refreshes = 0;
        var stub = new HttpStub(_ =>
        {
            var count = Interlocked.Increment(ref refreshes);
            return HttpStub.Json(new Dictionary<string, object>
            {
                ["access_token"] = $"access-{count}",
                ["refresh_token"] = $"refresh-{count + 1}",
                ["expires_in"] = 3600,
            });
        });
        var (tokens, vault) = Provider(stub);
        var account = Account();

        var first = await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, account.Key);
        var second = await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, account.Key, rejected: first);
        Assert.AreEqual("access-2", second);
        // A caller holding the already-replaced token doesn't refresh again.
        Assert.AreEqual("access-2", await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, account.Key, rejected: first));
        Assert.AreEqual(2, refreshes);
        // Rotated refresh tokens are stored.
        Assert.AreEqual("refresh-3", vault.Account(MediaSourceKind.OneDrive, account.Key)?.RefreshToken);
        Assert.AreEqual("refresh-2", stub.Requests[^1].Form["refresh_token"]);
    }

    [TestMethod]
    public async Task ARevokedGrantMeansSignInAgain()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object> { ["error"] = "invalid_grant" }, 400));
        var (tokens, _) = Provider(stub);
        var error = await Assert.ThrowsExceptionAsync<ConnectorException>(() => tokens.AccessTokenAsync(MediaSourceKind.OneDrive, Account().Key));
        Assert.AreEqual(ConnectorFailure.SignInRequired, error.Failure);
        // So does an account that isn't there at all.
        error = await Assert.ThrowsExceptionAsync<ConnectorException>(() => tokens.AccessTokenAsync(MediaSourceKind.OneDrive, "missing"));
        Assert.AreEqual(ConnectorFailure.SignInRequired, error.Failure);
    }

    [TestMethod]
    public async Task ExpiringTokensAreRefreshedEarly()
    {
        var refreshes = 0;
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object>
        {
            ["access_token"] = $"access-{Interlocked.Increment(ref refreshes)}",
            ["expires_in"] = 60,
        }));
        var (tokens, _) = Provider(stub);
        // A 60-second token is inside the two-minute refresh margin.
        await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, Account().Key);
        await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, Account().Key);
        Assert.AreEqual(2, refreshes);
    }
}
