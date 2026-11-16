using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The string catalog in Edendale.Windows/Strings: every translated locale
/// carries every en-US key with the same {n} placeholders, the English
/// variants only override en-US keys, and no key appears twice. Android's
/// StringResourcesTest is the counterpart. The files are read from the
/// repository, found by walking up from the test assembly to the solution.
/// </summary>
[TestClass]
public sealed partial class ResourceCatalogTests
{
    private static readonly string[] Translated =
        ["de", "es-ES", "es-MX", "fr", "it", "ja", "ko", "nl", "pt-BR", "pt-PT", "ru", "sv", "zh-Hans", "zh-Hant"];

    private static readonly string[] EnglishVariants = ["en-AU", "en-CA", "en-GB"];

    private static string StringsFolder()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            if (File.Exists(Path.Combine(directory.FullName, "Edendale.Windows.sln")))
            {
                return Path.Combine(directory.FullName, "Edendale.Windows", "Strings");
            }
        }
        Assert.Inconclusive("The repository's Strings folder isn't reachable from the test assembly.");
        return "";
    }

    private static List<(string Name, string Value)> Entries(string locale) =>
        [.. XDocument.Load(Path.Combine(StringsFolder(), locale, "Resources.resw"))
            .Root!.Elements("data")
            .Select(data => ((string)data.Attribute("name")!, (string?)data.Element("value") ?? ""))];

    [GeneratedRegex(@"(?<!\{)\{(\d+)(?::[^}]*)?\}")]
    private static partial Regex Placeholder();

    private static string Placeholders(string value) =>
        string.Join(",", Placeholder().Matches(value).Select(match => match.Groups[1].Value).Distinct().Order());

    [TestMethod]
    public void NoLocaleRepeatsAKey()
    {
        foreach (var locale in Translated.Concat(EnglishVariants).Prepend("en-US"))
        {
            var repeated = Entries(locale).GroupBy(entry => entry.Name).Where(group => group.Count() > 1).Select(group => group.Key).ToList();
            Assert.AreEqual(0, repeated.Count, $"{locale}: {string.Join(", ", repeated)}");
        }
    }

    [TestMethod]
    public void EveryTranslatedLocaleHasEveryKeyWithItsPlaceholders()
    {
        var english = Entries("en-US").ToDictionary(entry => entry.Name, entry => entry.Value);
        foreach (var locale in Translated)
        {
            var entries = Entries(locale).ToDictionary(entry => entry.Name, entry => entry.Value);
            var missing = english.Keys.Except(entries.Keys).ToList();
            var extra = entries.Keys.Except(english.Keys).ToList();
            Assert.AreEqual(0, missing.Count, $"{locale} lacks {string.Join(", ", missing.Take(10))}");
            Assert.AreEqual(0, extra.Count, $"{locale} has keys en-US lacks: {string.Join(", ", extra.Take(10))}");
            foreach (var (name, value) in entries)
            {
                Assert.AreEqual(Placeholders(english[name]), Placeholders(value), $"{locale} {name}");
                Assert.IsFalse(string.IsNullOrWhiteSpace(value), $"{locale} {name} is empty");
            }
        }
    }

    [TestMethod]
    public void EnglishVariantsOnlyOverrideEnUsKeys()
    {
        var english = Entries("en-US").ToDictionary(entry => entry.Name, entry => entry.Value);
        foreach (var locale in EnglishVariants)
        {
            foreach (var (name, value) in Entries(locale))
            {
                Assert.IsTrue(english.ContainsKey(name), $"{locale} {name} is not an en-US key");
                Assert.AreEqual(Placeholders(english[name]), Placeholders(value), $"{locale} {name}");
            }
        }
    }
}
