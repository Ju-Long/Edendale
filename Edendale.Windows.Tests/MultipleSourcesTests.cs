using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Titles in several sources (apple-27.1 94a8b4f, MultipleSourcesTests and
/// LibraryEnrichmentTests.playingACopyRemembersItsSource): copies tie by TMDB
/// match or else by parsed title, one card per title, Play From names each
/// endpoint and the copy played last, Play starts the reachable copy played
/// last, Remove works per source, and the next episode stays on the playing
/// source. Windows keeps one show record per parsed name that can span
/// sources, so each rule is also checked with one record holding both.
/// </summary>
[TestClass]
public sealed class MultipleSourcesTests
{
    private readonly LibraryData _library = new();
    private static readonly DateTimeOffset Now = new(2026, 11, 12, 20, 0, 0, TimeSpan.Zero);

    private LibraryFolder Folder(string name, string path, MediaSourceKind kind, string? username = null)
    {
        var folder = new LibraryFolder { Name = name, Path = path, Kind = kind.RawValue(), Username = username };
        _library.Folders.Add(folder);
        return folder;
    }

    private LibraryFolder Lan => _lan ??= Folder("Media", @"\\longs-mac-studio.local\Media", MediaSourceKind.Smb);
    private LibraryFolder Tailscale => _tailscale ??= Folder("Media", @"\\100.64.0.7\Media", MediaSourceKind.Smb);
    private LibraryFolder? _lan;
    private LibraryFolder? _tailscale;

    private LibraryMovie Movie(string title, LibraryFolder folder, int? tmdbId = null, int? year = null, DateTimeOffset? added = null)
    {
        var movie = new LibraryMovie
        {
            Title = title,
            Year = year,
            TmdbId = tmdbId,
            FolderId = folder.Id,
            FilePath = folder.Path + @"\" + title + ".mkv",
            DateAdded = added ?? Now,
        };
        _library.Movies.Add(movie);
        return movie;
    }

    private static LibraryEpisode Episode(LibraryFolder folder, int season, int number, int? tmdbId = null) => new()
    {
        Season = season,
        Episode = number,
        TmdbId = tmdbId,
        FolderId = folder.Id,
        FilePath = $@"{folder.Path}\Severance\S{season:00}E{number:00}.mkv",
    };

    private LibraryShow Show(string name, int? tmdbId, params LibraryEpisode[] episodes)
    {
        var show = new LibraryShow { Name = name, TmdbId = tmdbId, Episodes = [.. episodes], DateAdded = Now };
        _library.Shows.Add(show);
        return show;
    }

    // ------------------------------------------------------------------
    // Copy identity (oneShareThroughTwoEndpointsIsOneTitle)
    // ------------------------------------------------------------------

    [TestMethod]
    public void OneShareThroughTwoEndpointsIsOneTitle()
    {
        var lan = Movie("Heat", Lan, year: 1995);
        var tail = Movie("Heat", Tailscale, year: 1995);
        Assert.AreEqual(1, PlaybackSources.Distinct([lan, tail], PlaybackSources.CopyKey).Count, "unmatched, one title");

        lan.TmdbId = 949;
        tail.TmdbId = 949;
        var titles = PlaybackSources.Distinct([lan, tail], PlaybackSources.CopyKey);
        Assert.AreSame(lan, titles.Single(), "the first of each key stands for it");

        var ronin = Movie("Ronin", Lan, year: 1998);
        Assert.AreEqual(2, PlaybackSources.Distinct([lan, tail, ronin], PlaybackSources.CopyKey).Count);

        var severanceA = new LibraryShow { Name = "Severance" };
        var severanceB = new LibraryShow { Name = "severance " };
        Assert.AreEqual(PlaybackSources.CopyKey(severanceA), PlaybackSources.CopyKey(severanceB));
    }

    [TestMethod]
    public void CopyKeysNormaliseTitlesAndKeepYears()
    {
        string Key(string title, int? year) => PlaybackSources.CopyKey(new LibraryMovie { Title = title, Year = year });
        Assert.AreEqual(Key("Ame\u0301lie", 2001), Key("Amélie", 2001), "a decomposed title from a macOS share");
        Assert.AreEqual(Key("  AMÉLIE ", 2001), Key("Amélie", 2001));
        Assert.AreNotEqual(Key("Heat", 1995), Key("Heat", 1986));
        Assert.AreNotEqual(Key("Heat", null), Key("Heat", 1995));
        Assert.AreNotEqual(PlaybackSources.CopyKey(new LibraryMovie { Title = "Heat", TmdbId = 949 }), Key("Heat", null), "matched and unmatched differ");
        Assert.AreEqual(
            PlaybackSources.CopyKey(new LibraryShow { Name = "Severance", TmdbId = 95396 }),
            PlaybackSources.CopyKey(new LibraryShow { Name = "Severance (2022)", TmdbId = 95396 }),
            "two names one TMDB match ties");
    }

    [TestMethod]
    public void ShowSubtitlesCountAnEpisodeInTwoSourcesOnce()
    {
        var record = Show("Severance", 95396, Episode(Lan, 1, 1), Episode(Lan, 1, 2), Episode(Tailscale, 1, 1), Episode(Tailscale, 1, 2));
        Assert.AreEqual("1 season · 2 episodes", PlaybackSources.ShowSubtitle([record]));
        Assert.AreEqual("1 season · 2 episodes", record.DisplaySubtitle);
        var other = Show("Severance (2022)", 95396, Episode(Lan, 2, 1));
        Assert.AreEqual("2 seasons · 3 episodes", PlaybackSources.ShowSubtitle(_library.ShowCopies(record)));
        CollectionAssert.AreEqual(new[] { record, other }, _library.ShowCopies(record).ToArray());
    }

    // ------------------------------------------------------------------
    // Play From (playFromNamesEachEndpoint)
    // ------------------------------------------------------------------

    [TestMethod]
    public void PlayFromNamesEachEndpoint()
    {
        Assert.AreEqual("Media (longs-mac-studio.local)", PlaybackSources.Title(Lan, Lan.Path + @"\Heat.mkv"));
        Assert.AreEqual("Media (100.64.0.7)", PlaybackSources.Title(Tailscale, Tailscale.Path + @"\Heat.mkv"));
        Assert.AreEqual("nas:2222", PlaybackSources.Endpoint(null, "sftp://nas:2222/Heat.mkv"));
        Assert.AreEqual("[fd7a:115c:a1e0::1]", PlaybackSources.Endpoint(null, "sftp://[fd7a:115c:a1e0::1]/x.mkv"));
        Assert.AreEqual("nas:8443", PlaybackSources.Endpoint(null, "davs://nas:8443/x.mkv"));
        var local = Folder("Movies", @"C:\Movies", MediaSourceKind.Local);
        Assert.AreEqual("Movies", PlaybackSources.Title(local, @"C:\Movies\Heat.mkv"));

        var drive = Folder("Videos you chose", "gdrive://k3y/~chosen/Google%20Drive", MediaSourceKind.GoogleDrive, "me@example.com");
        Assert.AreEqual("Videos you chose (me@example.com)", PlaybackSources.Title(drive, "gdrive://k3y/f1/Heat.mkv"));
        var unnamed = Folder("OneDrive", "onedrive://k3y/root", MediaSourceKind.OneDrive);
        Assert.IsNull(PlaybackSources.Endpoint(unnamed, "onedrive://k3y/i1/Heat.mkv"), "never the account-key hash");
        var bucket = Folder("Films", "s3://k3y/films/", MediaSourceKind.S3, "films");
        Assert.AreEqual("films", PlaybackSources.Endpoint(bucket, "s3://k3y/films/Heat.mkv"));
    }

    [TestMethod]
    public void TheDetailMarksTheCopyPlayedLastAndAnUnavailableOne()
    {
        var path = Lan.Path + @"\Heat.mkv";
        Assert.AreEqual("SMB · Heat.mkv · Last Played", PlaybackSources.Detail(Lan, path, isLastPlayed: true));
        Assert.AreEqual("SMB · Heat.mkv · Last Played · Unavailable", PlaybackSources.Detail(Lan, path, unavailable: true, isLastPlayed: true));
    }

    [TestMethod]
    public void TwoEndpointsOfOneShareKeepAFixedOrder()
    {
        var page = Movie("Heat", Folder("Movies", @"C:\Movies", MediaSourceKind.Local), 949);
        var tail = Movie("Heat", Tailscale, 949);
        var lan = Movie("Heat", Lan, 949);
        var once = PlaybackSources.Order(page, [tail, lan], movie => _library.FolderById(movie.FolderId));
        var again = PlaybackSources.Order(page, [lan, tail], movie => _library.FolderById(movie.FolderId));
        CollectionAssert.AreEqual(once.ToArray(), again.ToArray());
    }

    // ------------------------------------------------------------------
    // The copy played last (playPrefersTheCopyPlayedLast)
    // ------------------------------------------------------------------

    private sealed record Copy(string Name, DateTimeOffset? LastPlayedAt, bool Offline = false);

    [TestMethod]
    public void PlayPrefersTheCopyPlayedLast()
    {
        var lan = new Copy("lan", null);
        var tailscale = new Copy("tailscale", Now);
        var laptop = new Copy("laptop", Now.AddSeconds(-60));
        Copy[] copies = [lan, tailscale, laptop];

        Assert.AreEqual("tailscale", PlaybackSources.Preferred(copies, copy => copy.Offline, copy => copy.LastPlayedAt)!.Name);
        Assert.AreEqual("laptop", PlaybackSources.Preferred(copies, copy => copy == tailscale, copy => copy.LastPlayedAt)!.Name, "the played-last copy is offline");
        Assert.AreEqual("lan", PlaybackSources.Preferred([lan, tailscale with { LastPlayedAt = null }], copy => false, copy => copy.LastPlayedAt)!.Name, "nothing played");
        CollectionAssert.AreEqual(new[] { "tailscale", "lan", "laptop" },
            PlaybackSources.LastPlayedFirst(copies, copy => copy.LastPlayedAt).Select(copy => copy.Name).ToArray());
        Assert.IsNull(PlaybackSources.MostRecentlyPlayed([lan], copy => copy.LastPlayedAt));
    }

    [TestMethod]
    public void PlayingACopyRemembersItsSource()
    {
        var lanEpisode = Episode(Lan, 1, 1);
        var tailEpisode = Episode(Tailscale, 1, 1);
        var show = Show("Severance", 95396, lanEpisode, tailEpisode);
        var movie = Movie("Heat", Tailscale, 949);

        Assert.IsTrue(_library.NotePlayed(tailEpisode.FilePath, Now));
        Assert.AreEqual(Now, show.LastPlayedAtBySource![Tailscale.Id]);
        Assert.IsFalse(show.LastPlayedAtBySource.ContainsKey(Lan.Id));
        Assert.IsTrue(_library.NotePlayed(movie.FilePath.ToUpperInvariant(), Now), "a local path compares as Windows does");
        Assert.AreEqual(Now, movie.LastPlayedAt);
        Assert.IsFalse(_library.NotePlayed(@"D:\Downloads\loose.mkv", Now));

        var other = Show("Severance (2022)", 95396, Episode(Lan, 2, 1));
        other.LastPlayedAtBySource = new() { [Tailscale.Id] = Now.AddDays(1) };
        Assert.AreEqual(Now.AddDays(1), PlaybackSources.SourceLastPlayedAt(_library.ShowCopies(show), Tailscale.Id), "the latest across the title's records");
    }

    [TestMethod]
    public void ContinueWatchingResumesFromTheSourcePlayedLast()
    {
        var show = Show("Severance", 95396, Episode(Lan, 1, 1, tmdbId: 501), Episode(Tailscale, 1, 1, tmdbId: 501));
        show.LastPlayedAtBySource = new() { [Tailscale.Id] = Now };

        CollectionAssert.AreEqual(new[] { Tailscale.Id, Lan.Id },
            PlaybackSources.SourcePreference(_library.ShowCopies(show), _library.FolderById, _ => false).ToArray());
        Assert.AreEqual(Tailscale.Id, _library.PreferredEpisode(501, _ => false)!.Episode.FolderId);
        Assert.AreEqual(Lan.Id, _library.PreferredEpisode(501, id => id == Tailscale.Id)!.Episode.FolderId, "its source is offline");
        CollectionAssert.AreEqual(new[] { Lan.Id, Tailscale.Id },
            PlaybackSources.SourcePreference(_library.ShowCopies(show), _library.FolderById, id => id == Tailscale.Id).ToArray(),
            "an unreachable source comes last");
    }

    [TestMethod]
    public void PreferredMovieSkipsAnUnreachableCopyPlayedLast()
    {
        var lan = Movie("Heat", Lan, 949, added: Now.AddDays(-1));
        var tail = Movie("Heat", Tailscale, 949, added: Now);
        tail.LastPlayedAt = Now;
        Assert.AreSame(tail, _library.PreferredMovie(949, _ => false));
        Assert.AreSame(lan, _library.PreferredMovie(949, id => id == Tailscale.Id));
        Assert.IsNull(_library.PreferredMovie(1, _ => false));
    }

    // ------------------------------------------------------------------
    // Remove per source
    // ------------------------------------------------------------------

    [TestMethod]
    public void RemovingOneSourceKeepsTheOthers()
    {
        var show = Show("Severance", 95396, Episode(Lan, 1, 1), Episode(Lan, 1, 2), Episode(Tailscale, 1, 1), Episode(Tailscale, 1, 2));
        show.LastPlayedAtBySource = new() { [Lan.Id] = Now, [Tailscale.Id] = Now };
        var lanOnly = Show("Severance (2022)", 95396, Episode(Lan, 2, 1));

        CollectionAssert.AreEqual(new[] { Tailscale.Id, Lan.Id }, PlaybackSources.ShowSources(_library.ShowCopies(show), _library.FolderById).ToArray(),
            "same name: by path (\\100… before \\longs…), so the order is fixed");
        Assert.IsTrue(_library.RemoveShowSource([show.Id, lanOnly.Id], Lan.Id));
        Assert.IsTrue(show.Episodes.All(episode => episode.FolderId == Tailscale.Id));
        Assert.AreEqual(2, show.Episodes.Count);
        Assert.IsFalse(show.LastPlayedAtBySource!.ContainsKey(Lan.Id), "the source's memory goes with it");
        Assert.IsFalse(_library.Shows.Contains(lanOnly), "a record left empty goes");
    }

    [TestMethod]
    public void RemovedDriveEpisodesStayHidden()
    {
        var drive = Folder("Videos you chose", "gdrive://k3y/~chosen/Google%20Drive", MediaSourceKind.GoogleDrive);
        var show = Show("Severance", 95396,
            new LibraryEpisode { Season = 1, Episode = 1, FolderId = drive.Id, FilePath = "gdrive://k3y/f1/S01E01.mkv" },
            Episode(Lan, 1, 1));
        _library.RemoveShowSource([show.Id], drive.Id);
        CollectionAssert.AreEqual(new[] { "f1" }, drive.HiddenItemIds!.ToArray());
        Assert.IsTrue(_library.RemoveShows([show.Id]));
        Assert.AreEqual(0, _library.Shows.Count);
    }

    [TestMethod]
    public void TheMemoryRoundTripsThroughLibraryJson_AndOlderFilesReadAsNull()
    {
        var options = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
        var show = Show("Severance", 95396, Episode(Lan, 1, 1));
        show.LastPlayedAtBySource = new() { [Lan.Id] = Now };
        Movie("Heat", Lan, 949).LastPlayedAt = Now;
        var json = JsonSerializer.Serialize(_library, options);
        StringAssert.Contains(json, "lastPlayedAtBySource");
        var back = JsonSerializer.Deserialize<LibraryData>(json, options)!;
        Assert.AreEqual(Now, back.Shows.Single().LastPlayedAtBySource![Lan.Id]);
        Assert.AreEqual(Now, back.Movies.Single().LastPlayedAt);

        var older = JsonSerializer.Deserialize<LibraryData>("""{"movies":[{"title":"Heat"}],"shows":[{"name":"Severance"}]}""", options)!;
        Assert.IsNull(older.Movies.Single().LastPlayedAt);
        Assert.IsNull(older.Shows.Single().LastPlayedAtBySource);
    }

    // ------------------------------------------------------------------
    // The next episode (nextEpisodeStaysOnTheChosenSource and its sibling)
    // ------------------------------------------------------------------

    private ShowEpisode? Next(LibraryEpisode current, IReadOnlyList<LibraryShow> copies, Func<Guid, bool>? offline = null) =>
        EpisodeProgression.NextEpisode(current, copies, offline ?? (_ => false), _library.FolderById);

    [TestMethod]
    public void TheNextEpisodeStaysOnTheChosenSource_InOneRecord()
    {
        var lan2 = Episode(Lan, 1, 2);
        var tail1 = Episode(Tailscale, 1, 1);
        var tail2 = Episode(Tailscale, 1, 2);
        // LAN scanned first: the record lists its files first.
        var show = Show("Severance", 95396, Episode(Lan, 1, 1), lan2, Episode(Lan, 1, 3), tail1, tail2, Episode(Tailscale, 1, 3));
        Assert.AreSame(tail2, Next(tail1, [show])!.Episode);
    }

    [TestMethod]
    public void TheNextEpisodeStaysOnTheChosenSource_AcrossRecords()
    {
        var lanShow = Show("Severance", 95396, Episode(Lan, 1, 1), Episode(Lan, 1, 2), Episode(Lan, 1, 3));
        var tail1 = Episode(Tailscale, 1, 1);
        var tail2 = Episode(Tailscale, 1, 2);
        var tailShow = Show("Severance (2022)", 95396, tail1, tail2, Episode(Tailscale, 1, 3));
        var next = Next(tail1, [tailShow, lanShow])!;
        Assert.AreSame(tail2, next.Episode);
        Assert.AreSame(tailShow, next.Show);
    }

    [TestMethod]
    public void TheNextEpisodeComesFromAnotherSourceOnlyWhenTheChosenOneLacksIt()
    {
        var nas = Folder("nas", "sftp://nas/", MediaSourceKind.Sftp);
        var laptop = Folder("laptop", "sftp://laptop/", MediaSourceKind.Sftp);
        var offline = Folder("offline", "sftp://offline/", MediaSourceKind.Sftp);
        var nas2 = Episode(nas, 1, 2);
        var nas4 = Episode(nas, 1, 4);
        var laptop3 = Episode(laptop, 1, 3);
        var offline5 = Episode(offline, 1, 5);
        var show = Show("Severance", 95396, Episode(nas, 1, 1), nas2, nas4, laptop3, Episode(offline, 1, 3), offline5);
        bool IsOffline(Guid id) => id == offline.Id;

        Assert.AreSame(laptop3, Next(nas2, [show], IsOffline)!.Episode, "not the offline copy of E3");
        Assert.AreSame(nas4, Next(laptop3, [show], IsOffline)!.Episode, "back to the chosen source");
        Assert.AreSame(offline5, Next(nas4, [show], IsOffline)!.Episode, "the only holder, offline or not");
        show.Episodes.Remove(offline5);
        Assert.IsNull(Next(nas4, [show], IsOffline));
    }

    [TestMethod]
    public void ThePlayingSourceWinsWhileFlaggedUnavailable()
    {
        var lan1 = Episode(Lan, 1, 1);
        var lan2 = Episode(Lan, 1, 2);
        var show = Show("Severance", 95396, lan1, lan2, Episode(Tailscale, 1, 2));
        Assert.AreSame(lan2, Next(lan1, [show], id => id == Lan.Id)!.Episode);
    }

    [TestMethod]
    public void UpNextPreviewsWhatAutoAdvancePlays()
    {
        var nas = Folder("nas", "sftp://nas/", MediaSourceKind.Sftp);
        var laptop = Folder("laptop", "sftp://laptop/", MediaSourceKind.Sftp);
        var nas2 = Episode(nas, 1, 2);
        var laptop3 = Episode(laptop, 1, 3);
        var show = Show("Severance", 95396, nas2, laptop3);
        var duration = TimeSpan.FromMinutes(50);
        var upcoming = EpisodeProgression.UpcomingEpisode(duration - TimeSpan.FromSeconds(20), duration, false, nas2, [show], _ => false, _library.FolderById);
        Assert.AreSame(laptop3, upcoming!.Episode);
        Assert.IsNull(EpisodeProgression.UpcomingEpisode(TimeSpan.FromMinutes(10), duration, false, nas2, [show], _ => false, _library.FolderById));
        Assert.IsNull(EpisodeProgression.UpcomingEpisode(duration - TimeSpan.FromSeconds(20), duration, true, nas2, [show], _ => false, _library.FolderById), "Loop on");
    }

    [TestMethod]
    public void PreviousStaysOnThePlayingSource()
    {
        var lan1 = Episode(Lan, 1, 1);
        var tail1 = Episode(Tailscale, 1, 1);
        var tail2 = Episode(Tailscale, 1, 2);
        var show = Show("Severance", 95396, lan1, Episode(Lan, 1, 2), tail1, tail2, Episode(Tailscale, 0, 1));
        Assert.AreSame(tail1, EpisodeProgression.PreviousEpisode(tail2, [show], _ => false, _library.FolderById)!.Episode);
        Assert.IsNull(EpisodeProgression.PreviousEpisode(tail1, [show], _ => false, _library.FolderById), "never back into specials");
    }

    [TestMethod]
    public void NextUpComesFromThePreferredSource()
    {
        var lan2 = Episode(Lan, 1, 2);
        var tail2 = Episode(Tailscale, 1, 2);
        var show = Show("Severance", 95396, Episode(Lan, 1, 1), lan2, Episode(Tailscale, 1, 1), tail2);
        var watched = new[] { new Models.WatchProgress { TmdbId = 9001, MediaType = "episode", IsCompleted = true, ShowTmdbId = 95396, SeasonNumber = 1, EpisodeNumber = 1, LastWatchedEpochMillis = 1 } };

        var toTail = EpisodeProgression.NextUpEpisodes(watched, new HashSet<int>(), [show], _ => [Tailscale.Id, Lan.Id]).Single();
        Assert.AreSame(tail2, toTail.Episode);
        var toLan = EpisodeProgression.NextUpEpisodes(watched, new HashSet<int>(), [show], _ => [Lan.Id, Tailscale.Id]).Single();
        Assert.AreSame(lan2, toLan.Episode);

        // The preferred source lacks it: another source's file comes up.
        show.Episodes.Remove(tail2);
        Assert.AreSame(lan2, EpisodeProgression.NextUpEpisodes(watched, new HashSet<int>(), [show], _ => [Tailscale.Id, Lan.Id]).Single().Episode);
    }

    [TestMethod]
    public void TheEpisodesPanelListsEachEpisodeOnce()
    {
        var laptop = Folder("laptop", "sftp://laptop/", MediaSourceKind.Sftp);
        var tail1 = Episode(Tailscale, 1, 1);
        var tail2 = Episode(Tailscale, 1, 2);
        var laptop3 = Episode(laptop, 1, 3);
        var show = Show("Severance", 95396, Episode(Lan, 1, 1), Episode(Lan, 1, 2), tail1, tail2, laptop3);
        var rows = EpisodeProgression.PlaylistEpisodes(tail1, [show], _ => false, _library.FolderById);
        CollectionAssert.AreEqual(new[] { tail1, tail2, laptop3 }, rows.Select(row => row.Episode).ToArray(),
            "one row per episode, from the playing source, and a gap filled from another");
    }

    [TestMethod]
    public void ThePlayerGathersEveryCopyOfTheShow()
    {
        var current = Episode(Lan, 1, 1);
        var record = Show("Severance", 95396, current);
        var next = Episode(Lan, 1, 2);
        var other = Show("Severance (2022)", 95396, next);
        Show("Ronin", 1, Episode(Lan, 1, 1));

        var context = PlayerContext.Resolve(PlayerSession.RequestFor(record, current), _library.Shows, _ => false, _library.FolderById);
        CollectionAssert.AreEqual(new[] { record, other }, context.ShowCopies.ToArray());
        Assert.AreSame(next, context.Next!.Episode);
        Assert.AreEqual(next.FilePath, context.NextRequest!.FilePath, "the request names the record holding the episode");
    }
}
