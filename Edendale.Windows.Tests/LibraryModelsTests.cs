using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Display strings the Downloaded page and the playlist panel bind to
/// directly. They are the visible half of "enrichment has not happened yet",
/// so the fallbacks matter as much as the happy path.
/// </summary>
[TestClass]
public sealed class LibraryModelsTests
{
    [TestMethod]
    public void MovieSubtitle_UsesWhicheverHalvesAreKnown()
    {
        Assert.AreEqual("1999 · 136 min", new LibraryMovie { Year = 1999, RuntimeMinutes = 136 }.DisplaySubtitle);
        Assert.AreEqual("1999", new LibraryMovie { Year = 1999 }.DisplaySubtitle);
        Assert.AreEqual("136 min", new LibraryMovie { RuntimeMinutes = 136 }.DisplaySubtitle);
    }

    [TestMethod]
    public void MovieSubtitle_SaysSoWhileTmdbHasNotAnswered()
    {
        Assert.AreEqual("Awaiting metadata", new LibraryMovie().DisplaySubtitle);
        // A zero runtime is missing data, not a zero-minute film.
        Assert.AreEqual("Awaiting metadata", new LibraryMovie { RuntimeMinutes = 0 }.DisplaySubtitle);
    }

    [TestMethod]
    public void EpisodeCode_IsAlwaysZeroPadded()
    {
        Assert.AreEqual("S01E02", new LibraryEpisode { Season = 1, Episode = 2 }.EpisodeCode);
        Assert.AreEqual("S10E24", new LibraryEpisode { Season = 10, Episode = 24 }.EpisodeCode);
        // Specials sort as season 0 and still render as a code.
        Assert.AreEqual("S00E01", new LibraryEpisode { Season = 0, Episode = 1 }.EpisodeCode);
    }

    [TestMethod]
    public void EpisodeTitle_FallsBackToItsCode()
    {
        var unnamed = new LibraryEpisode { Season = 1, Episode = 2 };
        Assert.AreEqual("S01E02", unnamed.DisplayTitle);
        Assert.AreEqual("S01E02", new LibraryEpisode { Season = 1, Episode = 2, Title = "   " }.DisplayTitle);
        Assert.AreEqual("Half Loop", new LibraryEpisode { Season = 1, Episode = 2, Title = "Half Loop" }.DisplayTitle);
    }

    [TestMethod]
    public void Seasons_AreDistinctAndOrdered()
    {
        var show = new LibraryShow
        {
            Episodes =
            [
                new LibraryEpisode { Season = 2, Episode = 1 },
                new LibraryEpisode { Season = 1, Episode = 3 },
                new LibraryEpisode { Season = 2, Episode = 2 },
                new LibraryEpisode { Season = 1, Episode = 1 },
            ],
        };

        CollectionAssert.AreEqual(new[] { 1, 2 }, show.AvailableSeasons.ToArray());
    }

    [TestMethod]
    public void EpisodesFor_ReturnsOneSeasonInOrder()
    {
        var show = new LibraryShow
        {
            Episodes =
            [
                new LibraryEpisode { Season = 1, Episode = 3 },
                new LibraryEpisode { Season = 2, Episode = 1 },
                new LibraryEpisode { Season = 1, Episode = 1 },
            ],
        };

        CollectionAssert.AreEqual(
            new[] { 1, 3 },
            show.EpisodesFor(1).Select(episode => episode.Episode).ToArray());
        Assert.AreEqual(0, show.EpisodesFor(9).Count);
    }

    [TestMethod]
    public void ShowSubtitle_CountsSeasonsAndEpisodes()
    {
        var one = new LibraryShow { Episodes = [new LibraryEpisode { Season = 1, Episode = 1 }] };
        Assert.AreEqual("1 season · 1 episode", one.DisplaySubtitle);

        var many = new LibraryShow
        {
            Episodes =
            [
                new LibraryEpisode { Season = 1, Episode = 1 },
                new LibraryEpisode { Season = 1, Episode = 2 },
                new LibraryEpisode { Season = 2, Episode = 1 },
            ],
        };
        Assert.AreEqual("2 seasons · 3 episodes", many.DisplaySubtitle);
    }
}

/// <summary>
/// UNC parsing decides which stored login a scan uses. Credentials are keyed
/// per host, so a source added as <c>\\nas\media</c> and one added as
/// <c>\\nas\backups</c> must resolve to the same host and different shares.
/// </summary>
[TestClass]
public sealed class SmbPathTests
{
    [TestMethod]
    public void HostAndShareComeOffAFullUncPath()
    {
        const string path = @"\\nas\media\films\alien.mkv";
        Assert.AreEqual("nas", SmbCredentialsStore.HostFromUncPath(path));
        Assert.AreEqual(@"\\nas\media", SmbCredentialsStore.ShareFromUncPath(path));
    }

    [TestMethod]
    public void AShareRootIsItsOwnShare()
    {
        Assert.AreEqual("192.168.1.10", SmbCredentialsStore.HostFromUncPath(@"\\192.168.1.10\media"));
        Assert.AreEqual(@"\\192.168.1.10\media", SmbCredentialsStore.ShareFromUncPath(@"\\192.168.1.10\media"));
    }

    [TestMethod]
    public void TwoSharesOnOneHostAgreeOnTheCredentialKey()
    {
        Assert.AreEqual(
            SmbCredentialsStore.HostFromUncPath(@"\\nas\media"),
            SmbCredentialsStore.HostFromUncPath(@"\\nas\backups"));
        Assert.AreNotEqual(
            SmbCredentialsStore.ShareFromUncPath(@"\\nas\media"),
            SmbCredentialsStore.ShareFromUncPath(@"\\nas\backups"));
    }

    [TestMethod]
    public void AHostWithNoShareHasNoShareRoot()
    {
        Assert.AreEqual("nas", SmbCredentialsStore.HostFromUncPath(@"\\nas"));
        Assert.IsNull(SmbCredentialsStore.ShareFromUncPath(@"\\nas"));
    }

    [TestMethod]
    public void LocalPathsAreNotNetworkSources()
    {
        Assert.IsNull(SmbCredentialsStore.HostFromUncPath(@"C:\Users\me\Videos"));
        Assert.IsNull(SmbCredentialsStore.ShareFromUncPath(@"C:\Users\me\Videos"));
        Assert.IsNull(SmbCredentialsStore.HostFromUncPath(@"\\"));
        Assert.IsNull(SmbCredentialsStore.HostFromUncPath(""));
    }
}
