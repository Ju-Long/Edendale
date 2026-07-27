using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The <c>edendale://</c> grammar is the app's only external-input parser —
/// protocol activation, file activation, App Shortcuts and Siri all arrive
/// through it — so both what it accepts and what it refuses are pinned here.
/// The grammar is shared with Apple's AppRouter.swift; a change on either side
/// should break one of these.
/// </summary>
[TestClass]
public sealed class AppRouteTests
{
    private static AppRoute? Parse(string uri) => AppRoute.Parse(new Uri(uri));

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    [TestMethod]
    public void Search_ReadsTheQuery()
    {
        var route = Parse("edendale://search?q=alien");
        Assert.AreEqual(new AppRoute.Search("alien"), route);
    }

    [TestMethod]
    public void Search_DecodesEscapesAndPlusAsSpace()
    {
        Assert.AreEqual(new AppRoute.Search("blade runner"), Parse("edendale://search?q=blade%20runner"));
        Assert.AreEqual(new AppRoute.Search("blade runner"), Parse("edendale://search?q=blade+runner"));
        // A title with a colon must survive intact — the scope grammar treats
        // an unknown "word:" as literal text, and so must the route.
        Assert.AreEqual(new AppRoute.Search("Alien: Romulus"), Parse("edendale://search?q=Alien%3A%20Romulus"));
    }

    [TestMethod]
    public void Search_FindsTheParameterAmongOthers()
    {
        Assert.AreEqual(new AppRoute.Search("alien"), Parse("edendale://search?ref=widget&q=alien"));
    }

    [TestMethod]
    public void Search_WithoutATermIsRejected()
    {
        // Opening an empty search is not an action; refusing it keeps the shell
        // on whatever the user was already looking at.
        Assert.IsNull(Parse("edendale://search"));
        Assert.IsNull(Parse("edendale://search?q="));
        Assert.IsNull(Parse("edendale://search?q=%20%20"));
        Assert.IsNull(Parse("edendale://search?query=alien"));
    }

    // ------------------------------------------------------------------
    // media / library
    // ------------------------------------------------------------------

    [TestMethod]
    public void Media_AcceptsBothTmdbTypes()
    {
        Assert.AreEqual(new AppRoute.Media(603, "movie"), Parse("edendale://media/movie/603"));
        Assert.AreEqual(new AppRoute.Media(95396, "tv"), Parse("edendale://media/tv/95396"));
    }

    [TestMethod]
    public void Media_RejectsAnythingThatIsNotAKnownTypeAndId()
    {
        Assert.IsNull(Parse("edendale://media/person/500"));
        Assert.IsNull(Parse("edendale://media/movie/not-a-number"));
        Assert.IsNull(Parse("edendale://media/movie"));
        Assert.IsNull(Parse("edendale://media/movie/603/extra"));
    }

    [TestMethod]
    public void Library_RoutesLocalRecordsByGuid()
    {
        var id = Guid.NewGuid();
        Assert.AreEqual(new AppRoute.LocalMovie(id), Parse($"edendale://library/movie/{id}"));
        Assert.AreEqual(new AppRoute.LocalShow(id), Parse($"edendale://library/show/{id}"));
    }

    [TestMethod]
    public void Library_RejectsBadTypesAndIds()
    {
        var id = Guid.NewGuid();
        Assert.IsNull(Parse($"edendale://library/episode/{id}"));
        Assert.IsNull(Parse("edendale://library/movie/603"));
        Assert.IsNull(Parse("edendale://library/movie/not-a-guid"));
    }

    // ------------------------------------------------------------------
    // play
    // ------------------------------------------------------------------

    [TestMethod]
    public void Play_AcceptsTmdbIdsAndLocalGuids()
    {
        var id = Guid.NewGuid();
        Assert.AreEqual(new AppRoute.PlayMovie(603), Parse("edendale://play/movie/603"));
        Assert.AreEqual(new AppRoute.PlayEpisode(62085), Parse("edendale://play/episode/62085"));
        Assert.AreEqual(new AppRoute.PlayLocalMovie(id), Parse($"edendale://play/local-movie/{id}"));
        Assert.AreEqual(new AppRoute.PlayLocalEpisode(id), Parse($"edendale://play/local-episode/{id}"));
    }

    [TestMethod]
    public void Play_DoesNotMixIdentifierKinds()
    {
        var id = Guid.NewGuid();
        // A local kind needs a GUID and a TMDB kind needs a number; crossing
        // them would send the player after a record that cannot exist.
        Assert.IsNull(Parse($"edendale://play/movie/{id}"));
        Assert.IsNull(Parse("edendale://play/local-movie/603"));
        Assert.IsNull(Parse("edendale://play/show/603"));
        Assert.IsNull(Parse("edendale://play/movie"));
    }

    // ------------------------------------------------------------------
    // rejections
    // ------------------------------------------------------------------

    [TestMethod]
    public void ForeignSchemesAreRefused()
    {
        Assert.IsNull(Parse("https://edendale/media/movie/603"));
        Assert.IsNull(Parse("file:///C:/movies/alien.mkv"));
    }

    [TestMethod]
    public void TheSchemeIsCaseInsensitiveButTheGrammarIsNot()
    {
        // Windows hands back whatever case the caller registered, so the scheme
        // compare has to be loose.
        Assert.AreEqual(new AppRoute.Media(603, "movie"), Parse("EDENDALE://media/movie/603"));
        Assert.AreEqual(new AppRoute.Media(603, "movie"), Parse("edendale://MEDIA/movie/603"));
        // Path segments stay exact — "Movie" is not a media type.
        Assert.IsNull(Parse("edendale://media/Movie/603"));
    }

    [TestMethod]
    public void UnknownActionsAreRefused()
    {
        Assert.IsNull(Parse("edendale://settings"));
        Assert.IsNull(Parse("edendale://delete/movie/603"));
        Assert.IsNull(Parse("edendale://"));
    }
}
