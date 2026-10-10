using Microsoft.VisualStudio.TestTools.UnitTesting;
using Edendale.Windows.Core;
using Edendale.Windows.Services;

namespace Edendale.Windows.Tests;

/// <summary>
/// The detail page's Play From menu (DIFF.md §3.11), ported from Apple's
/// PlaybackSourcesTests: copies of one title across sources, their order,
/// the copy Play picks when a source is unreachable, a show's episodes merged
/// across its copies, and how each copy is described.
/// </summary>
[TestClass]
public sealed class PlaybackSourcesTests
{
    private readonly Dictionary<Guid, LibraryFolder> _folders = [];

    private LibraryFolder Folder(string name, MediaSourceKind kind)
    {
        var path = kind switch
        {
            MediaSourceKind.Local => $@"C:\{name}",
            MediaSourceKind.Smb => $@"\\{name}\share",
            _ => $"{kind.Scheme()}://{name.ToLowerInvariant()}/",
        };
        var folder = new LibraryFolder { Name = name, Path = path, Kind = kind.RawValue() };
        _folders[folder.Id] = folder;
        return folder;
    }

    private static LibraryMovie Movie(string path, LibraryFolder folder) =>
        new() { Title = "Heat", FilePath = path, FolderId = folder.Id, TmdbId = 949 };

    private LibraryFolder? FolderOf(Guid id) => _folders.GetValueOrDefault(id);

    [TestMethod]
    public void OrdersThePagesCopyFirstThenLocalThenByName()
    {
        var page = Movie(@"\\nas\films\Heat.2160p.mkv", Folder("NAS", MediaSourceKind.Smb));
        var zeta = Movie("sftp://zeta/Heat.mkv", Folder("Zeta", MediaSourceKind.Sftp));
        var alpha = Movie("nfs://alpha/Heat.mkv", Folder("Alpha", MediaSourceKind.Nfs));
        var local = Movie(@"C:\Movies\Heat.mkv", Folder("Movies", MediaSourceKind.Local));

        var ordered = PlaybackSources.Order(page, [zeta, alpha, local], movie => FolderOf(movie.FolderId));
        CollectionAssert.AreEqual(
            new[] { page, local, alpha, zeta }.Select(m => m.FilePath).ToArray(),
            ordered.Select(m => m.FilePath).ToArray());
    }

    [TestMethod]
    public void NamesCompareInExplorerOrder()
    {
        var page = Movie(@"C:\A\Heat.mkv", Folder("A", MediaSourceKind.Local));
        var nas10 = Movie("sftp://nas10/Heat.mkv", Folder("NAS 10", MediaSourceKind.Sftp));
        var nas2 = Movie("sftp://nas2/Heat.mkv", Folder("nas 2", MediaSourceKind.Sftp));
        var ordered = PlaybackSources.Order(page, [nas10, nas2], movie => FolderOf(movie.FolderId));
        CollectionAssert.AreEqual(new[] { page, nas2, nas10 }, ordered.ToArray());
    }

    [TestMethod]
    public void PlaySkipsUnavailableSources()
    {
        Assert.AreEqual("laptop", PlaybackSources.Preferred(["nas", "laptop"], copy => copy == "nas"));
        Assert.AreEqual("nas", PlaybackSources.Preferred(["nas", "laptop"], _ => false));
        // Every source unreachable: still try the page's own.
        Assert.AreEqual("nas", PlaybackSources.Preferred(["nas", "laptop"], _ => true));
        Assert.IsNull(PlaybackSources.Preferred(Array.Empty<string>(), _ => false));
    }

    [TestMethod]
    public void MergesAShowsEpisodesAcrossItsCopies()
    {
        LibraryShow Show(LibraryFolder folder, params (int Season, int Number)[] episodes)
        {
            var show = new LibraryShow { Name = "Severance", TmdbId = 95396 };
            foreach (var (season, number) in episodes)
            {
                show.Episodes.Add(new LibraryEpisode
                {
                    FolderId = folder.Id,
                    FilePath = $@"{folder.Path}\S{season}E{number}.mkv",
                    Season = season,
                    Episode = number,
                });
            }
            return show;
        }

        var nasFolder = Folder("NAS", MediaSourceKind.Smb);
        var laptopFolder = Folder("Laptop", MediaSourceKind.Local);
        var nas = Show(nasFolder, (1, 1), (1, 2));
        var laptop = Show(laptopFolder, (1, 2), (2, 1), (1, 3));

        var slots = PlaybackSources.EpisodeSlots(nas, [laptop], FolderOf);
        CollectionAssert.AreEqual(new[] { "1-1", "1-2", "1-3", "2-1" }, slots.Select(s => s.Id).ToArray());
        // Episodes only the other copy has are on the page too.
        CollectionAssert.AreEqual(new[] { @"C:\Laptop\S1E3.mkv" }, slots[2].Copies.Select(e => e.FilePath).ToArray());
        // A shared episode lists the page's copy first.
        CollectionAssert.AreEqual(
            new[] { @"\\NAS\share\S1E2.mkv", @"C:\Laptop\S1E2.mkv" },
            slots[1].Copies.Select(e => e.FilePath).ToArray());
        Assert.AreEqual(@"\\NAS\share\S1E2.mkv", slots[1].Primary.FilePath);
    }

    [TestMethod]
    public void OneShowRecordCanHoldSeveralCopiesOfAnEpisode()
    {
        // Windows groups episodes by show name across folders, so one record
        // can hold the same episode twice: local first, then by source name.
        var zeta = Folder("Zeta", MediaSourceKind.Smb);
        var local = Folder("Videos", MediaSourceKind.Local);
        var show = new LibraryShow { Name = "Severance", TmdbId = 95396 };
        show.Episodes.Add(new LibraryEpisode { FolderId = zeta.Id, FilePath = @"\\Zeta\share\S1E1.mkv", Season = 1, Episode = 1 });
        show.Episodes.Add(new LibraryEpisode { FolderId = local.Id, FilePath = @"C:\Videos\S1E1.mkv", Season = 1, Episode = 1 });

        var slot = PlaybackSources.EpisodeSlots(show, [show], FolderOf).Single();
        CollectionAssert.AreEqual(
            new[] { @"C:\Videos\S1E1.mkv", @"\\Zeta\share\S1E1.mkv" },
            slot.Copies.Select(e => e.FilePath).ToArray());
    }

    [TestMethod]
    public void DescribesACopyByKindAndFileName()
    {
        var nas = Folder("NAS", MediaSourceKind.Smb);
        Assert.AreEqual("SMB · Heat (1995) 2160p.mkv", PlaybackSources.Detail(nas, @"\\nas\films\Heat (1995) 2160p.mkv"));
        Assert.AreEqual("SFTP · Heat (1995).mkv", PlaybackSources.Detail(null, "sftp://nas/films/Heat%20(1995).mkv"));
        Assert.AreEqual("Heat (1995).mkv", PlaybackSources.FileName(@"C:\Users\me\Movies\Heat (1995).mkv"));
        Assert.AreEqual("Local Folder · Heat.mkv", PlaybackSources.Detail(null, @"C:\Movies\Heat.mkv"));
        Assert.AreEqual("SMB · Heat.mkv · Unavailable", PlaybackSources.Detail(nas, @"\\nas\films\Heat.mkv", unavailable: true));
    }
}
