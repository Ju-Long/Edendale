using System.Globalization;
using System.Text;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Deterministic tests for the local half of the Wyzie Subs integration: which
/// items can be searched at all, how results are ordered, how the language
/// codes map, and how a downloaded file's character set is resolved. They make
/// no network calls.
/// </summary>
[TestClass]
public sealed class SubtitleMatchingTests
{
    // ------------------------------------------------------------------
    // What can be searched
    //
    // Wyzie matches on an id rather than a file hash, so an unmatched file has
    // nothing to search by and the UI has to say so.
    // ------------------------------------------------------------------

    [TestMethod]
    public void CanSearch_AMovieWithATmdbId()
    {
        var request = new PlaybackRequest { FilePath = "C:/a.mkv", Title = "A", TmdbId = 603 };

        Assert.IsTrue(SubtitleService.CanSearch(request));
    }

    [TestMethod]
    public void CanSearch_IsFalseForAnUnmatchedFile()
    {
        // What "Open with Edendale" produces: playable, but unknown to TMDB.
        var request = new PlaybackRequest { FilePath = "C:/a.mkv", Title = "a" };

        Assert.IsFalse(SubtitleService.CanSearch(request));
    }

    [TestMethod]
    public void CanSearch_AnEpisodeNeedsItsSeriesId_NotItsOwn()
    {
        var withSeries = new PlaybackRequest
        {
            FilePath = "C:/a.mkv",
            Title = "Show",
            MediaType = "episode",
            TmdbId = 999,
            ShowTmdbId = 1396,
            SeasonNumber = 2,
            EpisodeNumber = 5,
        };
        var episodeIdOnly = new PlaybackRequest
        {
            FilePath = "C:/a.mkv",
            Title = "Show",
            MediaType = "episode",
            TmdbId = 999,
            SeasonNumber = 2,
            EpisodeNumber = 5,
        };

        Assert.IsTrue(SubtitleService.CanSearch(withSeries));
        Assert.IsFalse(SubtitleService.CanSearch(episodeIdOnly));
    }

    // ------------------------------------------------------------------
    // Ranking
    // ------------------------------------------------------------------

    private static SubtitleCandidate Candidate(
        string id,
        string language = "en",
        bool ai = false,
        int downloads = 0) =>
        new()
        {
            Id = id,
            Url = $"https://sub.wyzie.io/file/{id}",
            Language = language,
            IsAiTranslated = ai,
            DownloadCount = downloads,
        };

    [TestMethod]
    public void Ranking_FollowsThePreferredLanguageOrder()
    {
        var ordered = SubtitleRanking.Order(
            [
                Candidate("1", language: "en", downloads: 100_000),
                Candidate("2", language: "fr", downloads: 1),
            ],
            ["fr", "en"]);

        Assert.AreEqual("2", ordered[0].Id);
    }

    [TestMethod]
    public void Ranking_KeepsUnrequestedLanguagesLast_ButKeepsThem()
    {
        var ordered = SubtitleRanking.Order(
            [
                Candidate("1", language: "de", downloads: 100_000),
                Candidate("2", language: "en", downloads: 1),
            ],
            ["en"]);

        Assert.AreEqual("2", ordered[0].Id);
        Assert.AreEqual(2, ordered.Count);
    }

    [TestMethod]
    public void Ranking_PrefersHumanAuthoredThenPopular()
    {
        var ordered = SubtitleRanking.Order(
            [
                Candidate("a", downloads: 10),
                Candidate("b", downloads: 20),
                Candidate("c", ai: true, downloads: 900),
            ],
            ["en"]);

        CollectionAssert.AreEqual(
            new[] { "b", "a", "c" },
            ordered.Select(candidate => candidate.Id).ToArray());
    }

    [TestMethod]
    public void Ranking_MatchesARegionalUploadAgainstItsBaseLanguage()
    {
        Assert.IsTrue(SubtitleRanking.LanguageMatches("pt-BR", "pt"));
        Assert.IsTrue(SubtitleRanking.LanguageMatches("PT", "pt"));
        Assert.IsFalse(SubtitleRanking.LanguageMatches("pt-BR", "es"));
    }

    [TestMethod]
    public void LanguageLabel_PrefersTheProvidersOwnWording()
    {
        var labelled = Candidate("1", language: "pt") with { Display = "Brazilian Portuguese" };
        var bare = Candidate("2", language: "pt");

        Assert.AreEqual("Brazilian Portuguese", labelled.LanguageLabel);
        Assert.AreEqual("pt", bare.LanguageLabel);
    }

    // ------------------------------------------------------------------
    // Language codes — the service takes ISO 639-1 with no region
    // ------------------------------------------------------------------

    [TestMethod]
    public void Language_CollapsesEveryCultureToTwoLetters()
    {
        Assert.AreEqual("pt", SubtitleLanguages.ForCulture(new CultureInfo("pt-BR")));
        Assert.AreEqual("zh", SubtitleLanguages.ForCulture(new CultureInfo("zh-Hans")));
        Assert.AreEqual("de", SubtitleLanguages.ForCulture(new CultureInfo("de-AT")));
        Assert.AreEqual("en", SubtitleLanguages.ForCulture(new CultureInfo("en-GB")));
    }

    [TestMethod]
    public void Language_SearchesTheReadersLanguageThenEnglish()
    {
        CollectionAssert.AreEqual(
            new[] { "fr", "en" },
            SubtitleLanguages.Preferred(new CultureInfo("fr-FR")).ToArray());

        // English readers should not ask for English twice.
        CollectionAssert.AreEqual(
            new[] { "en" },
            SubtitleLanguages.Preferred(new CultureInfo("en-US")).ToArray());
    }

    [TestMethod]
    public void Language_OfferedCodesAllResolveToAName()
    {
        foreach (var code in SubtitleLanguages.Offered)
        {
            var name = SubtitleLanguages.DisplayName(code);
            Assert.IsFalse(string.IsNullOrWhiteSpace(name), $"{code} has no display name");
            Assert.AreNotEqual(code, name, $"{code} fell back to its raw code");
        }
    }

    // ------------------------------------------------------------------
    // Character sets
    //
    // Subtitles are routinely published in a legacy code page; decoding one as
    // UTF-8 turns every accented character into a replacement glyph.
    // ------------------------------------------------------------------

    [TestMethod]
    public void Decode_ReadsALegacyCyrillicCodePage()
    {
        byte[] windows1251 = [0xCF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2];

        Assert.AreEqual("Привет", SubtitleService.Decode(windows1251, "windows-1251"));

        // The declared encoding is doing real work here.
        Assert.AreNotEqual("Привет", SubtitleService.Decode(windows1251, "utf-8"));
    }

    [TestMethod]
    public void Decode_ReadsLatin1UnderEitherName()
    {
        byte[] latin1 = [0x63, 0x61, 0x66, 0xE9];   // café

        Assert.AreEqual("café", SubtitleService.Decode(latin1, "latin-1"));
        Assert.AreEqual("café", SubtitleService.Decode(latin1, "ISO-8859-1"));
    }

    [TestMethod]
    public void Decode_LetsAByteOrderMarkOverrideTheDeclaredEncoding()
    {
        var bytes = new UTF8Encoding(true).GetPreamble()
            .Concat(Encoding.UTF8.GetBytes("Привет"))
            .ToArray();

        // Providers mislabel often; the file itself is the better authority.
        Assert.AreEqual("Привет", SubtitleService.Decode(bytes, "windows-1251"));
    }

    [TestMethod]
    public void Decode_FallsBackToUtf8ForAnUnknownEncoding()
    {
        var bytes = Encoding.UTF8.GetBytes("Привет");

        Assert.AreEqual("Привет", SubtitleService.Decode(bytes, "not-a-charset"));
        Assert.AreEqual("Привет", SubtitleService.Decode(bytes, null));
        Assert.AreEqual("Привет", SubtitleService.Decode(bytes, "   "));
    }

    [TestMethod]
    public void ResolveEncoding_NormalizesTheSpellingsProvidersUse()
    {
        Assert.AreEqual(1251, SubtitleService.ResolveEncoding("cp1251").CodePage);
        Assert.AreEqual(1251, SubtitleService.ResolveEncoding("CP1251").CodePage);
        Assert.AreEqual(1252, SubtitleService.ResolveEncoding("windows_1252").CodePage);
        Assert.AreEqual(28591, SubtitleService.ResolveEncoding("latin1").CodePage);
        Assert.AreEqual(65001, SubtitleService.ResolveEncoding("utf8").CodePage);
    }
}
