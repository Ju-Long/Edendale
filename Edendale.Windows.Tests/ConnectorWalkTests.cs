using Microsoft.VisualStudio.TestTools.UnitTesting;
using Edendale.Windows.Core;

namespace Edendale.Windows.Tests;

/// <summary>
/// The breadth-first enumeration every connector shares (DIFF.md §3.12),
/// ported from ConnectorWalkTests in Apple's ConnectorTests.swift.
/// </summary>
[TestClass]
public sealed class ConnectorWalkTests
{
    private static string Folder(string path) => $"davs://nas.local{path}/";

    private static ConnectorEntry File(string path)
    {
        var url = $"davs://nas.local{path}";
        return new ConnectorEntry(SourceUrl.FileName(url), url, IsDirectory: false);
    }

    private static ConnectorEntry Directory(string path) =>
        new(path.Split('/').Last(), Folder(path), IsDirectory: true);

    private static Func<string, CancellationToken, Task<IReadOnlyList<ConnectorEntry>>> Tree(
        Dictionary<string, ConnectorEntry[]> tree, params string[] failing) =>
        (directory, _) =>
        {
            if (failing.Contains(directory)) throw new ConnectorException(ConnectorFailure.ListingFailed, directory);
            return Task.FromResult<IReadOnlyList<ConnectorEntry>>(tree.GetValueOrDefault(directory) ?? []);
        };

    [TestMethod]
    public async Task WalksBreadthFirstSkippingHiddenAndNonVideo()
    {
        var list = Tree(new()
        {
            [Folder("")] = [Directory("/Movies"), Directory("/.Trash"), File("/Heat.1995.mkv"), File("/notes.txt")],
            [Folder("/Movies")] = [File("/Movies/Alien.1979.mp4"), Directory("/Movies/Broken"), File("/Movies/.hidden.mkv")],
            [Folder("/.Trash")] = [File("/.Trash/Old.2001.mkv")],
        }, Folder("/Movies/Broken"));

        var videos = await ConnectorWalk.VideosAsync(Folder(""), list, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "Heat.1995.mkv", "Alien.1979.mp4" }, videos.Select(v => v.Name).ToArray());
    }

    [TestMethod]
    public async Task AFailingTopFolderThrows()
    {
        var list = Tree([], Folder(""));
        await Assert.ThrowsExceptionAsync<ConnectorException>(() =>
            ConnectorWalk.VideosAsync(Folder(""), list, CancellationToken.None));
    }

    [TestMethod]
    public async Task CyclesAndRunawayTreesStop()
    {
        // A shortcut loop lists the same folder again.
        var loop = Tree(new() { [Folder("/a")] = [Directory("/a"), File("/a/Heat.1995.mkv")] });
        Assert.AreEqual(1, (await ConnectorWalk.VideosAsync(Folder("/a"), loop, CancellationToken.None)).Count);

        var calls = 0;
        var deep = await ConnectorWalk.VideosAsync(Folder("/0"), (_, _) =>
        {
            var depth = Interlocked.Increment(ref calls);
            return Task.FromResult<IReadOnlyList<ConnectorEntry>>([Directory($"/{depth}")]);
        }, CancellationToken.None, maxDirectories: 5);
        Assert.AreEqual(0, deep.Count);
        Assert.AreEqual(5, calls);
        Assert.AreEqual(2000, ConnectorWalk.MaxDirectories);
    }

    [TestMethod]
    public async Task CancellationStopsTheWalk()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsExceptionAsync<OperationCanceledException>(() =>
            ConnectorWalk.VideosAsync(Folder(""), Tree([]), cancellation.Token));
    }

    [TestMethod]
    public void SortsFoldersFirstThenNamesInExplorerOrder()
    {
        var sorted = ConnectorWalk.Sorted(
        [
            File("/Episode 10.mkv"),
            Directory("/season 2"),
            File("/episode 2.mkv"),
            Directory("/Season 10"),
        ]);
        CollectionAssert.AreEqual(
            new[] { "season 2", "Season 10", "episode 2.mkv", "Episode 10.mkv" },
            sorted.Select(entry => entry.Name).ToArray());
    }

    [TestMethod]
    public void SignInFailuresNeedTheUser()
    {
        Assert.IsTrue(new ConnectorException(ConnectorFailure.SignInRequired, "OneDrive").NeedsUserAction);
        Assert.IsTrue(new ConnectorException(ConnectorFailure.HostKeyMismatch, "nas").NeedsUserAction);
        Assert.IsTrue(new ConnectorException(ConnectorFailure.AuthenticationFailed, "nas").NeedsUserAction);
        Assert.IsFalse(new ConnectorException(ConnectorFailure.Unreachable, "nas").NeedsUserAction);
        Assert.IsFalse(new ConnectorException(ConnectorFailure.RateLimited, "Dropbox").NeedsUserAction);
        StringAssert.Contains(new ConnectorException(ConnectorFailure.Unreachable, "nas.local").Message, "nas.local");
        StringAssert.Contains(new ConnectorException(ConnectorFailure.ServerError, "WebDAV", 503).Message, "503");
    }
}
