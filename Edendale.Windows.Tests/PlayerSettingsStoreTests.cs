using System.IO;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The device-local player settings file: defaults when a key is missing,
/// recovery from an unreadable file, rejection of values of the wrong shape,
/// atomic writes, and change notification.
/// </summary>
[TestClass]
public sealed class PlayerSettingsStoreTests
{
    private string _directory = "";
    private string _path = "";

    [TestInitialize]
    public void Init()
    {
        _directory = Path.Combine(Path.GetTempPath(), $"eden-player-settings-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "player-settings.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    [TestMethod]
    public void MissingFileReadsDefaults()
    {
        var store = new PlayerSettingsStore(_path);

        Assert.IsFalse(store.Contains("player.loopEnabled"));
        Assert.IsTrue(store.GetBool("player.loopEnabled", fallback: true));
        Assert.IsNull(store.GetDouble("player.holdLeftRate"));
        Assert.IsNull(store.GetInt("player.skipForwardSeconds"));
        Assert.IsNull(store.GetString("subtitles.font"));
        Assert.IsNull(store.GetDoubleArray("audio.enhancementBands"));
    }

    [TestMethod]
    public void UnreadableFileStartsEmptyAndIsRepairedByTheNextWrite()
    {
        File.WriteAllText(_path, "{ this is not json");
        var store = new PlayerSettingsStore(_path);

        Assert.IsFalse(store.GetBool("player.aspectFill", fallback: false));

        store.SetBool("player.aspectFill", true);
        var reloaded = new PlayerSettingsStore(_path);
        Assert.IsTrue(reloaded.GetBool("player.aspectFill", fallback: false));
    }

    [TestMethod]
    public void NonObjectRootStartsEmpty()
    {
        File.WriteAllText(_path, "[1, 2, 3]");
        var store = new PlayerSettingsStore(_path);
        Assert.IsNull(store.GetInt("player.skipForwardSeconds"));
    }

    [TestMethod]
    public void ValuesOfTheWrongShapeReadAsMissing()
    {
        File.WriteAllText(_path, """
            {
              "player.loopEnabled": "yes",
              "player.holdLeftRate": "fast",
              "player.skipForwardSeconds": 12.5,
              "subtitles.font": 4,
              "audio.enhancementBands": [1, "two", 3]
            }
            """);
        var store = new PlayerSettingsStore(_path);

        Assert.IsFalse(store.GetBool("player.loopEnabled", fallback: false));
        Assert.IsNull(store.GetDouble("player.holdLeftRate"));
        Assert.IsNull(store.GetInt("player.skipForwardSeconds"));
        Assert.IsNull(store.GetString("subtitles.font"));
        Assert.IsNull(store.GetDoubleArray("audio.enhancementBands"));
    }

    [TestMethod]
    public void ValuesRoundTripThroughTheFile()
    {
        var store = new PlayerSettingsStore(_path);
        store.SetBool("player.loopEnabled", true);
        store.SetDouble("player.holdRightRate", 2.5);
        store.SetInt("player.skipBackwardSeconds", 30);
        store.SetString("subtitles.font", "serif");
        store.SetDoubleArray("audio.enhancementBands", [1, -2.5, 0]);
        store.SetObject("player.content.movie.603", new Sample { Rate = 1.25, Language = "en" });

        var reloaded = new PlayerSettingsStore(_path);
        Assert.IsTrue(reloaded.GetBool("player.loopEnabled", fallback: false));
        Assert.AreEqual(2.5, reloaded.GetDouble("player.holdRightRate"));
        Assert.AreEqual(30, reloaded.GetInt("player.skipBackwardSeconds"));
        Assert.AreEqual("serif", reloaded.GetString("subtitles.font"));
        CollectionAssert.AreEqual(new[] { 1, -2.5, 0 }, reloaded.GetDoubleArray("audio.enhancementBands"));
        var sample = reloaded.GetObject<Sample>("player.content.movie.603");
        Assert.IsNotNull(sample);
        Assert.AreEqual(1.25, sample.Rate);
        Assert.AreEqual("en", sample.Language);
    }

    [TestMethod]
    public void NonFiniteNumbersAreNotStored()
    {
        var store = new PlayerSettingsStore(_path);
        store.SetDouble("player.holdLeftRate", 0.75);
        store.SetDouble("player.holdLeftRate", double.NaN);
        Assert.IsNull(store.GetDouble("player.holdLeftRate"));
    }

    [TestMethod]
    public void WritesLeaveNoTemporaryFileBehind()
    {
        var store = new PlayerSettingsStore(_path);
        store.SetBool("player.loopEnabled", true);

        Assert.IsTrue(File.Exists(_path));
        Assert.IsFalse(File.Exists(_path + ".tmp"));
    }

    [TestMethod]
    public void ChangedNamesTheKeyAndSkipsNoOpWrites()
    {
        var store = new PlayerSettingsStore(_path);
        var changes = new List<string>();
        store.Changed += (_, key) => changes.Add(key);

        store.SetBool("player.loopEnabled", true);
        store.SetBool("player.loopEnabled", true);
        store.SetInt("player.skipForwardSeconds", 15);
        store.Remove("player.loopEnabled");
        store.Remove("player.loopEnabled");

        CollectionAssert.AreEqual(
            new[] { "player.loopEnabled", "player.skipForwardSeconds", "player.loopEnabled" },
            changes);
    }

    private sealed class Sample
    {
        public double Rate { get; set; }
        public string? Language { get; set; }
    }
}
