// TMDB account connector. Auth is TMDB v3's three-step user flow: request
// token → user approval in the default browser → session. The session id is
// a credential, so it is persisted DPAPI-protected (current user) in
// %LOCALAPPDATA%\Edendale\tmdb-session.bin and never enters the OneDrive
// replica. Favourites, watchlist, and ratings sync two-way through the
// Windows sync engine: on launch, after local edits (debounced), and on demand
// from Settings. Watch time has no TMDB API and never syncs here.

using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public sealed class TmdbAccountService
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private readonly UserMediaStore _store;
    private readonly object _gate = new();
    private TmdbSessionInfo? _session;
    private string? _pendingRequestToken;
    private string? _pendingApprovalUrl;
    private Timer? _syncDebounce;
    private int _syncRunning;

    /// <summary>Connection, pending approval, or sync-status changes.</summary>
    public event EventHandler? StateChanged;

    public TmdbAccountService(UserMediaStore store)
    {
        _store = store;
        _session = LoadSession();
        _store.EditedLocally += (_, _) => ScheduleSync();
    }

    public bool IsConnected => _session is not null;

    public bool HasPendingApproval => _pendingRequestToken is not null;

    public string? PendingApprovalUrl => _pendingApprovalUrl;

    public string? AccountLabel => _session?.Username ?? _session?.Name;

    /// <summary>Human-readable outcome of the last sync attempt, if any.</summary>
    public string? LastSyncStatus { get; private set; }

    public bool CanConnect => WindowsCore.HasTmdbCredentials;

    /// <summary>Step one: returns the URL the user must approve in a browser.</summary>
    public async Task<string> BeginConnectAsync()
    {
        var start = await WindowsCore.TmdbAuthStartAsync();
        _pendingRequestToken = start.RequestToken;
        _pendingApprovalUrl = start.ApprovalUrl;
        RaiseStateChanged();
        return start.ApprovalUrl;
    }

    /// <summary>Step two, after the user approved the token in the browser.</summary>
    public async Task CompleteConnectAsync()
    {
        var token = _pendingRequestToken
            ?? throw new InvalidOperationException(Loc.Get("Tmdb_NoPendingApproval"));
        var session = await WindowsCore.TmdbAuthFinishAsync(token);
        _pendingRequestToken = null;
        _pendingApprovalUrl = null;
        _session = session;
        SaveSession(session);
        RaiseStateChanged();
        _ = SyncNowAsync();
    }

    public void CancelPendingConnect()
    {
        _pendingRequestToken = null;
        _pendingApprovalUrl = null;
        RaiseStateChanged();
    }

    /// <summary>Invalidates the session server-side (best-effort) and forgets it.</summary>
    public async Task DisconnectAsync()
    {
        var session = _session;
        _session = null;
        _pendingRequestToken = null;
        _pendingApprovalUrl = null;
        try
        {
            File.Delete(AppPaths.TmdbSessionFile);
        }
        catch
        {
            // The DPAPI blob is useless without the session anyway.
        }
        RaiseStateChanged();
        if (session is not null)
        {
            try { await WindowsCore.TmdbLogoutAsync(session.SessionId); }
            catch { /* Offline logout is fine; the session expires server-side. */ }
        }
    }

    /// <summary>One sync round; returns null on success or an error message.</summary>
    public async Task<string?> SyncNowAsync()
    {
        var session = _session;
        if (session is null || !CanConnect) return Loc.Get("Tmdb_NotConnected");
        if (Interlocked.Exchange(ref _syncRunning, 1) == 1) return null;
        try
        {
            var outcome = await WindowsCore.SyncUserMediaAsync(
                session.SessionId, session.AccountId, _store.All);
            _store.ReplaceAll(outcome.Records);
            // "t" is the reader's own short clock — 24-hour where that is the
            // norm, "2:35 PM" where it is not.
            LastSyncStatus =
                Loc.Format("Tmdb_SyncStatus", DateTime.Now.ToString("t"), outcome.Pushed, outcome.Pulled);
            return null;
        }
        catch (Exception failure)
        {
            LastSyncStatus = Loc.Format("Tmdb_SyncFailed", failure.Message);
            return failure.Message;
        }
        finally
        {
            Interlocked.Exchange(ref _syncRunning, 0);
            RaiseStateChanged();
        }
    }

    /// <summary>Fire-and-forget launch sync when a session is stored.</summary>
    public void SyncOnLaunch()
    {
        if (IsConnected) _ = SyncNowAsync();
    }

    /// <summary>Local edits coalesce into one push a few seconds later.</summary>
    private void ScheduleSync()
    {
        if (!IsConnected) return;
        lock (_gate)
        {
            _syncDebounce?.Dispose();
            _syncDebounce = new Timer(
                _ => _ = SyncNowAsync(), null, TimeSpan.FromSeconds(5), Timeout.InfiniteTimeSpan);
        }
    }

    private void RaiseStateChanged() => StateChanged?.Invoke(this, EventArgs.Empty);

    // ------------------------------------------------------------------
    // DPAPI-protected persistence
    // ------------------------------------------------------------------

    private static TmdbSessionInfo? LoadSession()
    {
        try
        {
            if (!File.Exists(AppPaths.TmdbSessionFile)) return null;
            var plain = ProtectedData.Unprotect(
                File.ReadAllBytes(AppPaths.TmdbSessionFile), null, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<TmdbSessionInfo>(
                Encoding.UTF8.GetString(plain), JsonOptions);
        }
        catch
        {
            return null; // An unreadable blob just means signed out.
        }
    }

    private static void SaveSession(TmdbSessionInfo session)
    {
        try
        {
            var plain = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(session, JsonOptions));
            File.WriteAllBytes(
                AppPaths.TmdbSessionFile,
                ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser));
        }
        catch
        {
            // Failing to persist only means reconnecting next launch.
        }
    }
}
