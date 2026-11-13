// Where a guest connects for a scanned QR code (SYNC-PROTOCOL.md §9.1, §9.3;
// HandoffV2Session.swift `connect(to:)` and `findService` on apple-27.1).
// The code's address comes first, for 10 seconds: a host on another subnet,
// or an address on an adapter the guest can't reach, leaves the connection
// waiting rather than failed. Then the guest browses for the host by the
// code's name, or takes the only version-2 host on the network, for 15
// seconds, and connects to it within 15 more. All of this happens before the
// `hello`, so the single-use code is never spent on a host that wasn't there.

namespace Edendale.Windows.Core.Handoff;

public static class HandoffTargets
{
    /// <summary>How long the code's own address gets before browsing takes over.</summary>
    public static readonly TimeSpan DirectConnectTimeout = TimeSpan.FromSeconds(10);

    /// <summary>How long the guest browses for the host the code names.</summary>
    public static readonly TimeSpan BrowseTimeout = TimeSpan.FromSeconds(15);

    /// <summary>How long the connection to a browsed host may take.</summary>
    public static readonly TimeSpan BrowsedConnectTimeout = TimeSpan.FromSeconds(15);

    /// <summary>
    /// The browsed host to try once the code's address failed: the one named
    /// exactly as the code's `n`, else the only host found, else null (wait
    /// for more, or ask the person to pick). The browser lists version-2
    /// hosts only.
    /// </summary>
    public static T? Fallback<T>(IReadOnlyList<T> peers, Func<T, string> nameOf, string? codeName) where T : class
    {
        if (!string.IsNullOrEmpty(codeName) && peers.FirstOrDefault(peer => nameOf(peer) == codeName) is { } named) return named;
        return peers.Count == 1 ? peers[0] : null;
    }
}
