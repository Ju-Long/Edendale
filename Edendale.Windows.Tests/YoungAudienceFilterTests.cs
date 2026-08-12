using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The PG / PG-13 policy, the TMDB certification payload parsing, and the
/// filter's fail-closed behavior over a fake provider. No network. Ports the
/// Apple YoungAudienceFilter tests.
/// </summary>
[TestClass]
public sealed class YoungAudienceFilterTests
{
    private string _preferencePath = "";

    [TestInitialize]
    public void Init() =>
        _preferencePath = Path.Combine(Path.GetTempPath(), $"eden-audience-{Guid.NewGuid():N}.json");

    [TestCleanup]
    public void Cleanup()
    {
        try { File.Delete(_preferencePath); } catch { /* best effort */ }
    }

    // ------------------------------------------------------------------
    // Policy
    // ------------------------------------------------------------------

    [TestMethod]
    public void Policy_AcceptsOnlyPgFamilyAndTelevisionEquivalents()
    {
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("PG", "movie"));
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("PG-13", "movie"));
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("PG13", "movie"));
        Assert.IsFalse(YoungAudienceCertificationPolicy.Allows("R", "movie"));
        Assert.IsFalse(YoungAudienceCertificationPolicy.Allows("", "movie"));

        // TV-PG / TV-14 count only for television.
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("TV-PG", "tv"));
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("TV-14", "tv"));
        Assert.IsFalse(YoungAudienceCertificationPolicy.Allows("TV-14", "movie"));
        Assert.IsFalse(YoungAudienceCertificationPolicy.Allows("TV-MA", "tv"));
        Assert.IsTrue(YoungAudienceCertificationPolicy.Allows("PG", "tv"));
    }

    // ------------------------------------------------------------------
    // Certification payload parsing
    // ------------------------------------------------------------------

    [TestMethod]
    public void MovieCertification_UsesRegionAndTheatricalPrecedence()
    {
        var response = Parse("""
        {"results": [
          {"iso_3166_1": "SG", "release_dates": [
            {"certification": "PG", "release_date": "2026-02-01", "type": 6},
            {"certification": "PG13", "release_date": "2026-01-01", "type": 3}
          ]},
          {"iso_3166_1": "US", "release_dates": [
            {"certification": "R", "release_date": "2026-01-02", "type": 3}
          ]},
          {"iso_3166_1": "GB", "release_dates": [
            {"certification": "PG", "release_date": "2026-01-01", "type": 3},
            {"certification": "15", "release_date": "2026-02-01", "type": 3}
          ]}
        ]}
        """);

        Assert.AreEqual("PG13", ContentCertification.Movie(response, "sg"));
        Assert.AreEqual("R", ContentCertification.Movie(response, "US"));
        // Two distinct certifications at the same preferred tier — fail closed.
        Assert.IsNull(ContentCertification.Movie(response, "GB"));
        Assert.IsNull(ContentCertification.Movie(response, "CA"));
    }

    [TestMethod]
    public void TvCertification_UsesOnlyTheRequestedRegion()
    {
        var response = Parse("""
        {"results": [
          {"iso_3166_1": "SG", "rating": "PG13"},
          {"iso_3166_1": "US", "rating": "TV-14"}
        ]}
        """);

        Assert.AreEqual("PG13", ContentCertification.Tv(response, "SG"));
        Assert.AreEqual("TV-14", ContentCertification.Tv(response, "us"));
        Assert.IsNull(ContentCertification.Tv(response, "CA"));
    }

    // ------------------------------------------------------------------
    // Filter behavior
    // ------------------------------------------------------------------

    [TestMethod]
    public void DisabledFilter_ReturnsEveryItemWithoutLookups()
    {
        var provider = new FakeProvider();
        var filter = new YoungAudienceFilter(provider, _preferencePath);
        var items = new List<MediaItem> { Item(1), Item(2) };

        Assert.IsFalse(filter.IsEnabled);
        CollectionAssert.AreEqual(items, filter.Visible(items));
        Assert.IsFalse(filter.IsVerifying(items.Select(i => i.Ref)));
        Assert.AreEqual(0, provider.Calls, "a disabled filter never touches the network");
    }

    [TestMethod]
    public async Task EnabledFilter_FailsClosedAndPreservesAllowedOrder()
    {
        var provider = new FakeProvider();
        provider.Set("movie:1", ContentCertificationLookup.Found("PG"));
        provider.Set("movie:2", ContentCertificationLookup.Found("R"));
        provider.Set("movie:3", ContentCertificationLookup.Unrated);

        var filter = new YoungAudienceFilter(provider, _preferencePath) { IsEnabled = true };
        var items = new List<MediaItem> { Item(1), Item(2), Item(3) };

        Assert.IsTrue(filter.IsVerifying(items.Select(i => i.Ref)), "unknown refs read as verifying");
        await filter.VerifyAsync(items.Select(i => i.Ref));
        Assert.IsFalse(filter.IsVerifying(items.Select(i => i.Ref)));

        var visible = filter.Visible(items);
        Assert.AreEqual(1, visible.Count);
        Assert.AreEqual(1, visible[0].Id, "only the PG title survives, order preserved");
        Assert.IsTrue(filter.Allows(items[0].Ref));
        Assert.IsFalse(filter.Allows(items[1].Ref));
        Assert.IsFalse(filter.Allows(items[2].Ref));
    }

    [TestMethod]
    public async Task UnavailableCertification_RetriesButAlwaysFailsClosed()
    {
        var provider = new FakeProvider();
        provider.Set("movie:9", ContentCertificationLookup.Unavailable);

        var filter = new YoungAudienceFilter(provider, _preferencePath) { IsEnabled = true };
        var refs = new[] { Item(9).Ref };

        await filter.VerifyAsync(refs);
        Assert.IsFalse(filter.Allows(refs[0]), "unavailable fails closed");

        // A later network success is picked up on the next verify pass.
        provider.Set("movie:9", ContentCertificationLookup.Found("PG-13"));
        await filter.VerifyAsync(refs);
        Assert.IsTrue(filter.Allows(refs[0]));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonElement Parse(string json) => JsonDocument.Parse(json).RootElement.Clone();

    private static MediaItem Item(int id, string mediaType = "movie") => new()
    {
        Id = id,
        MediaType = mediaType,
        Title = $"Title {id}",
    };

    private sealed class FakeProvider : IContentCertificationProvider
    {
        private readonly Dictionary<string, ContentCertificationLookup> _lookups = new(StringComparer.Ordinal);

        public string ContextIdentifier { get; set; } = "US";
        public int Calls { get; private set; }

        public void Set(string key, ContentCertificationLookup lookup) => _lookups[key] = lookup;

        public Task<ContentCertificationLookup> CertificationAsync(MediaRef reference)
        {
            Calls++;
            var key = $"{reference.MediaType}:{reference.Id}";
            return Task.FromResult(_lookups.TryGetValue(key, out var lookup)
                ? lookup
                : ContentCertificationLookup.Unrated);
        }
    }
}
