using System.Globalization;
using System.IO;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Per-title speed and track memory (DIFF.md §3.3) and the Player
/// Adjustments track labels (§3.9).
/// </summary>
[TestClass]
public sealed class PlayerTracksTests
{
    private CultureInfo _culture = CultureInfo.CurrentCulture;
    private CultureInfo _uiCulture = CultureInfo.CurrentUICulture;

    [TestInitialize]
    public void Init()
    {
        _culture = CultureInfo.CurrentCulture;
        _uiCulture = CultureInfo.CurrentUICulture;
        CultureInfo.CurrentCulture = new CultureInfo("en-US");
        CultureInfo.CurrentUICulture = new CultureInfo("en-US");
    }

    [TestCleanup]
    public void Cleanup()
    {
        CultureInfo.CurrentCulture = _culture;
        CultureInfo.CurrentUICulture = _uiCulture;
    }

    // ------------------------------------------------------------------
    // Key derivation
    // ------------------------------------------------------------------

    [TestMethod]
    public void MoviesKeyByTheirOwnTmdbId()
    {
        Assert.AreEqual("player.content.movie.603", TitlePlaybackMemory.ContentKey("movie", 603, null));
    }

    [TestMethod]
    public void EpisodesShareTheShowsKey()
    {
        Assert.AreEqual("player.content.show.1399", TitlePlaybackMemory.ContentKey("episode", 63056, 1399));
        Assert.AreEqual("player.content.show.1399", TitlePlaybackMemory.ContentKey("episode", 63057, 1399));
    }

    [TestMethod]
    public void UnidentifiedFilesStoreNothing()
    {
        Assert.IsNull(TitlePlaybackMemory.ContentKey("movie", null, null));
        Assert.IsNull(TitlePlaybackMemory.ContentKey("episode", 63056, null));
        Assert.IsNull(TitlePlaybackMemory.ContentKey("movie", 0, null));
        Assert.IsNull(TitlePlaybackMemory.ContentKey("trailer", 5, 5));
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    private static PlayerTrack Track(int id, string? language = null, string? name = null,
        bool external = false, int width = 0, int height = 0, int channels = 0) => new()
        {
            Id = id,
            Language = language,
            Name = name,
            IsExternal = external,
            Width = width,
            Height = height,
            Channels = channels,
        };

    [TestMethod]
    public void SnapshotRemembersSpeedAudioSubtitleAndVideo()
    {
        var snapshot = TitlePlaybackMemory.Snapshot(
            1.25,
            Track(1, "jpn", "Japanese 5.1"),
            Track(3, "eng", "Signs & Songs"),
            hasSubtitleTracks: true,
            Track(0, width: 1920, height: 1080));

        Assert.AreEqual(1.25, snapshot.Speed);
        Assert.AreEqual("jpn", snapshot.AudioTrackLanguage);
        Assert.AreEqual("Japanese 5.1", snapshot.AudioTrackName);
        Assert.AreEqual(true, snapshot.SubtitleEnabled);
        Assert.AreEqual("eng", snapshot.SubtitleTrackLanguage);
        Assert.AreEqual(1920, snapshot.VideoTrackWidth);
        Assert.AreEqual(1080, snapshot.VideoTrackHeight);
    }

    [TestMethod]
    public void SnapshotRemembersSubtitlesOffOnlyWhenTheFileHasSubtitles()
    {
        Assert.AreEqual(false, TitlePlaybackMemory.Snapshot(1, null, null, hasSubtitleTracks: true, null).SubtitleEnabled);
        Assert.IsNull(TitlePlaybackMemory.Snapshot(1, null, null, hasSubtitleTracks: false, null).SubtitleEnabled);
    }

    [TestMethod]
    public void SnapshotNeverRemembersDownloadedSubtitles()
    {
        var snapshot = TitlePlaybackMemory.Snapshot(1, null, Track(9, "eng", external: true), hasSubtitleTracks: true, null);
        Assert.IsNull(snapshot.SubtitleEnabled);
        Assert.IsNull(snapshot.SubtitleTrackLanguage);
    }

    [TestMethod]
    public void SnapshotSnapsTheSpeedToTheGrid()
    {
        Assert.AreEqual(1.5, TitlePlaybackMemory.Snapshot(1.4999f, null, null, false, null).Speed!.Value, 1e-9);
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    [TestMethod]
    public void AudioMatchesByLanguageBeforeName()
    {
        var preferences = new ContentPlayerPreferences { AudioTrackLanguage = "jpn", AudioTrackName = "Commentary" };
        var tracks = new[] { Track(1, "eng", "Commentary"), Track(2, "jpn", "Main") };
        Assert.AreEqual(2, TitlePlaybackMemory.BestAudioMatch(preferences, tracks)?.Id);
    }

    [TestMethod]
    public void AudioFallsBackToTheNameWhenNoLanguageMatches()
    {
        var preferences = new ContentPlayerPreferences { AudioTrackLanguage = "fre", AudioTrackName = "Commentary" };
        var tracks = new[] { Track(1, "eng", "Main"), Track(2, "eng", "Commentary") };
        Assert.AreEqual(2, TitlePlaybackMemory.BestAudioMatch(preferences, tracks)?.Id);
    }

    [TestMethod]
    public void AudioWithNothingInCommonStaysUnchanged()
    {
        var preferences = new ContentPlayerPreferences { AudioTrackLanguage = "fre", AudioTrackName = "Director" };
        Assert.IsNull(TitlePlaybackMemory.BestAudioMatch(preferences, [Track(1, "eng", "Main")]));
    }

    [TestMethod]
    public void SubtitlesMatchEmbeddedTracksOnly()
    {
        var preferences = new ContentPlayerPreferences { SubtitleEnabled = true, SubtitleTrackLanguage = "eng" };
        var tracks = new[] { Track(7, "eng", "Downloaded", external: true), Track(4, "eng", "Full") };
        Assert.AreEqual(4, TitlePlaybackMemory.BestSubtitleMatch(preferences, tracks)?.Id);
        Assert.IsNull(TitlePlaybackMemory.BestSubtitleMatch(preferences, [Track(7, "eng", external: true)]));
    }

    [TestMethod]
    public void VideoMatchesBySizeOnlyWhenTheFileHasSeveral()
    {
        var preferences = new ContentPlayerPreferences { VideoTrackWidth = 1280, VideoTrackHeight = 720 };
        var several = new[] { Track(0, width: 1920, height: 1080), Track(1, width: 1280, height: 720) };
        Assert.AreEqual(1, TitlePlaybackMemory.VideoMatch(preferences, several)?.Id);
        Assert.IsNull(TitlePlaybackMemory.VideoMatch(preferences, [Track(1, width: 1280, height: 720)]));
    }

    [TestMethod]
    public void PreferencesRoundTripThroughTheSettingsStoreWithApplesFieldNames()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"eden-title-memory-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            var path = Path.Combine(directory, "player-settings.json");
            var preferences = new PlayerPreferences(new PlayerSettingsStore(path));
            preferences.SaveContentPreferences("player.content.show.1399", new ContentPlayerPreferences
            {
                Speed = 1.5,
                AudioTrackLanguage = "jpn",
                SubtitleEnabled = false,
            });

            var json = File.ReadAllText(path);
            StringAssert.Contains(json, "\"audioTrackLanguage\"");
            StringAssert.Contains(json, "\"subtitleEnabled\"");
            Assert.IsFalse(json.Contains("videoTrackWidth"), "unset fields are left out");

            var reloaded = new PlayerPreferences(new PlayerSettingsStore(path)).ContentPreferences("player.content.show.1399");
            Assert.AreEqual(1.5, reloaded?.Speed);
            Assert.AreEqual("jpn", reloaded?.AudioTrackLanguage);
            Assert.AreEqual(false, reloaded?.SubtitleEnabled);
            Assert.IsNull(new PlayerPreferences(new PlayerSettingsStore(path)).ContentPreferences(null));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    [TestMethod]
    public void ChannelNamesFollowTheLayout()
    {
        Assert.AreEqual("Mono", TrackLabels.ChannelLabel(1));
        Assert.AreEqual("Stereo", TrackLabels.ChannelLabel(2));
        Assert.AreEqual("5.1", TrackLabels.ChannelLabel(6));
        Assert.AreEqual("7.1", TrackLabels.ChannelLabel(8));
        Assert.AreEqual("4ch", TrackLabels.ChannelLabel(4));
        Assert.AreEqual("", TrackLabels.ChannelLabel(0));
    }

    [TestMethod]
    public void AudioLabelAppendsLanguageAndChannels()
    {
        Assert.AreEqual("Main (Japanese) — 5.1", TrackLabels.AudioLabel(Track(1, "jpn", "Main", channels: 6), 0));
        Assert.AreEqual("Main (French) — Stereo", TrackLabels.AudioLabel(Track(1, "fre", "Main", channels: 2), 0));
    }

    [TestMethod]
    public void LanguageIsOmittedWhenTheNameAlreadyContainsIt()
    {
        Assert.AreEqual("English Commentary — Stereo", TrackLabels.AudioLabel(Track(1, "en", "English Commentary", channels: 2), 0));
        Assert.AreEqual("Track 1 - [eng]", TrackLabels.BaseLabel(Track(1, "eng", "Track 1 - [eng]"), 0));
    }

    [TestMethod]
    public void NamelessTracksAreNumbered()
    {
        Assert.AreEqual("Track 2 (English) — 7.1", TrackLabels.AudioLabel(Track(5, "eng", null, channels: 8), 1));
        Assert.AreEqual("Track 1", TrackLabels.AudioLabel(Track(5, "und", null), 0));
    }

    [TestMethod]
    public void VideoLabelShowsTheSize()
    {
        Assert.AreEqual("Main — 1920×1080", TrackLabels.VideoLabel(Track(0, null, "Main", width: 1920, height: 1080), 0));
        Assert.AreEqual("Angle 2 (English) — 1280×720", TrackLabels.VideoLabel(Track(1, "en", "Angle 2", width: 1280, height: 720), 1));
        Assert.AreEqual("Main", TrackLabels.VideoLabel(Track(0, null, "Main"), 0));
    }
}
