using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The Settings categories (SettingsView.swift's SettingsCategory, as
/// Android's SettingsPageTest checks them) and the TMDB Account section's
/// visibility rule (TMDBAccountSection.swift).
/// </summary>
[TestClass]
public sealed class SettingsCategoriesTests
{
    [TestMethod]
    public void ListedIsTheMacOrder()
    {
        CollectionAssert.AreEqual(
            new[]
            {
                SettingsCategory.General,
                SettingsCategory.PlayerControls,
                SettingsCategory.Audio,
                SettingsCategory.Subtitles,
                SettingsCategory.Library,
                SettingsCategory.Sync,
                SettingsCategory.Accounts,
                SettingsCategory.ParentalControls,
                SettingsCategory.About,
            },
            SettingsCategories.Listed.ToArray());
    }

    [TestMethod]
    public void DefaultIsGeneral()
    {
        Assert.AreEqual(SettingsCategory.General, SettingsCategories.Default);
        Assert.AreEqual(SettingsCategories.Listed[0], SettingsCategories.Default);
    }

    [TestMethod]
    public void ParentalControlsAndAboutArePages()
    {
        // Apple's handheld and TV list shows these two in place and leaves out
        // General; the desktop lists all three, as the Mac does.
        CollectionAssert.Contains(SettingsCategories.Listed.ToArray(), SettingsCategory.General);
        CollectionAssert.Contains(SettingsCategories.Listed.ToArray(), SettingsCategory.ParentalControls);
        CollectionAssert.Contains(SettingsCategories.Listed.ToArray(), SettingsCategory.About);
        CollectionAssert.AreEquivalent(Enum.GetValues<SettingsCategory>(), SettingsCategories.Listed.ToArray());
    }

    [TestMethod]
    public void EnglishTitlesMatchApple()
    {
        // No resolver in the test host, so AppText falls back to its English copy.
        CollectionAssert.AreEqual(
            new[] { "General", "Player Controls", "Audio", "Subtitles", "Library", "Sync", "Accounts", "Parental Controls", "About" },
            SettingsCategories.Listed.Select(category => category.Title()).ToArray());
        Assert.AreEqual("Settings_CategoryPlayerControls", SettingsCategory.PlayerControls.TitleKey());
    }

    [TestMethod]
    public void KeysTagsAndIconsAreDistinct()
    {
        var listed = SettingsCategories.Listed;
        Assert.AreEqual(listed.Count, listed.Select(category => category.TitleKey()).Distinct().Count());
        Assert.AreEqual(listed.Count, listed.Select(category => category.Tag()).Distinct().Count());
        Assert.AreEqual(listed.Count, listed.Select(category => category.IconName()).Distinct().Count());
        foreach (var category in listed)
        {
            Assert.IsFalse(string.IsNullOrWhiteSpace(category.IconName()), $"{category} has an icon");
            // Automation ids are built from the tags, so they stay plain.
            Assert.IsTrue(category.Tag().All(c => char.IsAsciiLetterLower(c) || c == '-'), category.Tag());
        }
        Assert.AreEqual("player-controls", SettingsCategory.PlayerControls.Tag());
        Assert.AreEqual("parental-controls", SettingsCategory.ParentalControls.Tag());
    }

    [TestMethod]
    public void IconsFollowApple()
    {
        // Apple's iconText, except Parental Controls, which keeps eye-slash.
        CollectionAssert.AreEqual(
            new[] { "gear-complex", "play", "file-music", "subtitles", "folder-tree", "arrow-rotate-right", "circle-user-fill", "eye-slash", "film-circle-info" },
            SettingsCategories.Listed.Select(category => category.IconName()).ToArray());
    }

    [TestMethod]
    public void TmdbSectionRule()
    {
        // canSignIn || phase != .signedOut: a build without credentials hides
        // the section unless a session or an approval is already there.
        Assert.IsFalse(SettingsCategories.ShowsTmdbAccount(canConnect: false, isConnected: false, hasPendingApproval: false));
        Assert.IsTrue(SettingsCategories.ShowsTmdbAccount(canConnect: true, isConnected: false, hasPendingApproval: false));
        Assert.IsTrue(SettingsCategories.ShowsTmdbAccount(canConnect: false, isConnected: true, hasPendingApproval: false));
        Assert.IsTrue(SettingsCategories.ShowsTmdbAccount(canConnect: false, isConnected: false, hasPendingApproval: true));
        Assert.IsTrue(SettingsCategories.ShowsTmdbAccount(canConnect: true, isConnected: true, hasPendingApproval: false));
    }
}
