// Binds the WatchlistStore to the connected TMDB account. Kept out of the
// WinUI-free domain layer (and the linked test set) because it reaches into the
// account session; the store only calls these while authenticated, and any
// throw here is caught by the store's push/pull and left pending for retry.

using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

internal sealed class WatchlistRemoteAdapter(TmdbAccountService account) : IWatchlistRemote
{
    public async Task<IReadOnlyList<MediaItem>> WatchlistItemsAsync(string mediaType)
    {
        var session = RequireSession();
        return await WindowsCore.WatchlistItemsAsync(session.SessionId, session.AccountId, mediaType);
    }

    public Task SetWatchlistAsync(MediaRef reference, bool inWatchlist)
    {
        var session = RequireSession();
        return WindowsCore.SetWatchlistAsync(session.SessionId, session.AccountId, reference, inWatchlist);
    }

    private (string SessionId, int AccountId) RequireSession() =>
        account.CurrentSession ?? throw new InvalidOperationException("No connected TMDB session.");
}
