using System.Globalization;
using System.Text.Json.Serialization;

namespace Edendale.Windows.Core;

/// <summary>
/// A subtitle downloaded from the online search and kept on this device for
/// the title it was downloaded for, so the next time that title plays it is
/// attached again without a network request. Windows-only; device-local like
/// the library, and never part of the OneDrive replica.
/// </summary>
public sealed record SavedSubtitle
{
    /// <summary>The title it belongs to (<see cref="SavedSubtitleRules.TitleKey"/>).</summary>
    [JsonPropertyName("title")] public required string TitleKey { get; init; }

    /// <summary>The file's name inside the subtitle folder; never a path.</summary>
    [JsonPropertyName("file")] public required string FileName { get; init; }

    /// <summary>The search result it came from, so the browser can mark it as saved.</summary>
    [JsonPropertyName("id")] public string? CandidateId { get; init; }

    [JsonPropertyName("language")] public string? Language { get; init; }

    /// <summary>The provider's label for the language ("Brazilian Portuguese").</summary>
    [JsonPropertyName("languageLabel")] public string? LanguageLabel { get; init; }

    [JsonPropertyName("release")] public string? Release { get; init; }
    [JsonPropertyName("source")] public string? Source { get; init; }
    [JsonPropertyName("hearingImpaired")] public bool HearingImpaired { get; init; }
    [JsonPropertyName("downloadedAt")] public DateTimeOffset DownloadedAt { get; init; }

    /// <summary>When it was last turned on during playback; the 30-day clock runs from here.</summary>
    [JsonPropertyName("lastUsedAt")] public DateTimeOffset LastUsedAt { get; init; }

    /// <summary>It was on when the title was last left, so the next playback turns it on again.</summary>
    [JsonPropertyName("selected")] public bool Selected { get; init; }
}

/// <summary>What a prune or a removal leaves to delete from the subtitle folder.</summary>
public sealed record SavedSubtitleRemoval(IReadOnlyList<SavedSubtitle> Removed, IReadOnlyList<string> FilesToDelete);

/// <summary>The rules behind saved subtitles: title keys, the 30-day expiry, and labels.</summary>
public static class SavedSubtitleRules
{
    /// <summary>A saved subtitle unused for this long is removed ("a month").</summary>
    public static readonly TimeSpan RetentionPeriod = TimeSpan.FromDays(30);

    /// <summary>
    /// <c>movie:&lt;tmdbId&gt;</c>, or <c>episode:&lt;showTmdbId&gt;:&lt;season&gt;:&lt;episode&gt;</c>
    /// so every copy of an episode shares its subtitles and no two episodes
    /// do. An episode without its numbers falls back to its own TMDB id.
    /// Unidentified files get null: the online search can't find them either.
    /// </summary>
    public static string? TitleKey(string mediaType, int? tmdbId, int? showTmdbId, int? season, int? episode) => mediaType switch
    {
        "movie" when tmdbId is > 0 => string.Create(CultureInfo.InvariantCulture, $"movie:{tmdbId}"),
        "episode" when showTmdbId is > 0 && season is >= 0 && episode is > 0 =>
            string.Create(CultureInfo.InvariantCulture, $"episode:{showTmdbId}:{season}:{episode}"),
        "episode" when tmdbId is > 0 => string.Create(CultureInfo.InvariantCulture, $"episode:{tmdbId}"),
        _ => null,
    };

    /// <summary>Unused for the whole retention period, counting from the last use.</summary>
    public static bool IsExpired(SavedSubtitle subtitle, DateTimeOffset now, TimeSpan retention) =>
        now - subtitle.LastUsedAt >= retention;

    /// <summary>
    /// A bare file name that stays inside the subtitle folder. The index is a
    /// user-writable file, so anything with a separator, a parent reference,
    /// or a reserved character is refused before it can name a file to delete.
    /// </summary>
    public static bool IsSafeFileName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 200) return false;
        if (name is "." or "..") return false;
        foreach (var character in name)
        {
            if (character is '/' or '\\' or ':' or '*' or '?' or '"' or '<' or '>' or '|' || char.IsControl(character))
            {
                return false;
            }
        }
        return name == name.Trim() && !name.EndsWith('.');
    }

    /// <summary>
    /// "120 KB" below 1,000 KB (any non-empty file shows at least 1 KB), then
    /// "1.4 MB", with the number in the reader's format. The formats carry
    /// the localized unit, for example "{0} KB" or "{0} Ko".
    /// </summary>
    public static string FormatSize(long bytes, string kilobytesFormat, string megabytesFormat, CultureInfo culture)
    {
        const double Kilobyte = 1024;
        const double Megabyte = 1024 * 1024;
        if (bytes < 1000 * Kilobyte)
        {
            var kilobytes = bytes <= 0 ? 0 : Math.Max(1, Math.Round(bytes / Kilobyte, MidpointRounding.AwayFromZero));
            return string.Format(culture, kilobytesFormat, kilobytes.ToString("0", culture));
        }
        var megabytes = Math.Round(bytes / Megabyte, 1, MidpointRounding.AwayFromZero);
        return string.Format(culture, megabytesFormat, megabytes.ToString("0.#", culture));
    }

    /// <summary>
    /// The subtitle menu's row: the language, "Saved", and the release when
    /// another saved subtitle in the same language needs telling apart.
    /// </summary>
    public static string Label(SavedSubtitle subtitle, string savedTag, bool includeRelease)
    {
        var language = string.IsNullOrWhiteSpace(subtitle.LanguageLabel)
            ? (string.IsNullOrWhiteSpace(subtitle.Language) ? null : subtitle.Language)
            : subtitle.LanguageLabel;
        var parts = new List<string>();
        if (language is not null) parts.Add(language);
        if ((includeRelease || language is null) && !string.IsNullOrWhiteSpace(subtitle.Release)) parts.Add(subtitle.Release.Trim());
        parts.Add(savedTag);
        return string.Join(" · ", parts);
    }
}

/// <summary>
/// The saved-subtitle index: which file belongs to which title, when each was
/// last used, and which one was on. Pure; <c>SavedSubtitleStore</c> loads,
/// saves, and deletes the files it names.
/// </summary>
public sealed class SavedSubtitleIndex
{
    private readonly List<SavedSubtitle> _entries;
    private readonly List<string> _pendingDeletes;

    public SavedSubtitleIndex(IEnumerable<SavedSubtitle>? entries = null, IEnumerable<string>? pendingDeletes = null)
    {
        // Unsafe names and duplicates from a damaged or edited file are dropped.
        _entries = [];
        foreach (var entry in entries ?? [])
        {
            if (string.IsNullOrWhiteSpace(entry.TitleKey) || !SavedSubtitleRules.IsSafeFileName(entry.FileName)) continue;
            if (_entries.Any(existing => SameEntry(existing, entry.TitleKey, entry.FileName))) continue;
            _entries.Add(entry);
        }
        _pendingDeletes = [.. (pendingDeletes ?? []).Where(SavedSubtitleRules.IsSafeFileName).Distinct(StringComparer.OrdinalIgnoreCase)];
    }

    public IReadOnlyList<SavedSubtitle> Entries => _entries;

    /// <summary>Files whose delete failed (in use, say), retried at the next prune.</summary>
    public IReadOnlyList<string> PendingDeletes => _pendingDeletes;

    /// <summary>Every file an entry still refers to.</summary>
    public IReadOnlySet<string> ReferencedFiles =>
        _entries.Select(entry => entry.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);

    /// <summary>A title's saved subtitles, the most recently used first.</summary>
    public IReadOnlyList<SavedSubtitle> ForTitle(string titleKey) =>
        [.. _entries
            .Where(entry => entry.TitleKey == titleKey)
            .OrderByDescending(entry => entry.LastUsedAt)
            .ThenBy(entry => entry.FileName, StringComparer.OrdinalIgnoreCase)];

    public SavedSubtitle? Find(string titleKey, string fileName) =>
        _entries.FirstOrDefault(entry => SameEntry(entry, titleKey, fileName));

    /// <summary>Any entry for <paramref name="fileName"/>, whichever title it belongs to.</summary>
    public SavedSubtitle? FindFile(string fileName) =>
        _entries.FirstOrDefault(entry => string.Equals(entry.FileName, fileName, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// Adds a downloaded subtitle, or refreshes the entry already saved for
    /// that title and file. A download is a use, and it is the one now on.
    /// </summary>
    public void Record(SavedSubtitle subtitle)
    {
        if (string.IsNullOrWhiteSpace(subtitle.TitleKey) || !SavedSubtitleRules.IsSafeFileName(subtitle.FileName))
        {
            throw new ArgumentException("A saved subtitle needs a title and a bare file name.", nameof(subtitle));
        }

        var index = _entries.FindIndex(entry => SameEntry(entry, subtitle.TitleKey, subtitle.FileName));
        if (index >= 0)
        {
            subtitle = subtitle with { DownloadedAt = _entries[index].DownloadedAt };
            _entries[index] = subtitle;
        }
        else
        {
            _entries.Add(subtitle);
        }
        _pendingDeletes.RemoveAll(name => string.Equals(name, subtitle.FileName, StringComparison.OrdinalIgnoreCase));
        if (subtitle.Selected) Select(subtitle.TitleKey, subtitle.FileName);
    }

    /// <summary>
    /// The subtitle was on during playback: its 30 days start again, and it
    /// becomes the title's selected subtitle. False when it isn't saved.
    /// </summary>
    public bool MarkUsed(string titleKey, string fileName, DateTimeOffset now)
    {
        var index = _entries.FindIndex(entry => SameEntry(entry, titleKey, fileName));
        if (index < 0) return false;
        _entries[index] = _entries[index] with { LastUsedAt = now };
        Select(titleKey, fileName);
        return true;
    }

    /// <summary>The title was left with another track or none on. True when anything changed.</summary>
    public bool ClearSelection(string titleKey)
    {
        var changed = false;
        for (var index = 0; index < _entries.Count; index++)
        {
            if (_entries[index].TitleKey != titleKey || !_entries[index].Selected) continue;
            _entries[index] = _entries[index] with { Selected = false };
            changed = true;
        }
        return changed;
    }

    /// <summary>Forgets one title's subtitle; its file goes once nothing else refers to it.</summary>
    public SavedSubtitleRemoval Remove(string titleKey, string fileName) =>
        RemoveWhere(entry => SameEntry(entry, titleKey, fileName));

    /// <summary>Forgets everything; every referenced file is to be deleted.</summary>
    public SavedSubtitleRemoval RemoveAll() => RemoveWhere(_ => true);

    /// <summary>
    /// Removes the subtitles unused for <paramref name="retention"/>, except
    /// files in <paramref name="inUse"/> (attached to the playback on screen).
    /// </summary>
    public SavedSubtitleRemoval Prune(DateTimeOffset now, TimeSpan retention, IReadOnlySet<string>? inUse = null) =>
        RemoveWhere(entry => SavedSubtitleRules.IsExpired(entry, now, retention)
            && inUse?.Contains(entry.FileName) != true);

    /// <summary>A delete failed; try again at the next prune.</summary>
    public void DeferDelete(string fileName)
    {
        if (!SavedSubtitleRules.IsSafeFileName(fileName)) return;
        if (_pendingDeletes.Contains(fileName, StringComparer.OrdinalIgnoreCase)) return;
        if (ReferencedFiles.Contains(fileName)) return;
        _pendingDeletes.Add(fileName);
    }

    /// <summary>A deferred delete went through (or the file is already gone).</summary>
    public bool DeleteCompleted(string fileName) =>
        _pendingDeletes.RemoveAll(name => string.Equals(name, fileName, StringComparison.OrdinalIgnoreCase)) > 0;

    /// <summary>
    /// Files in the folder that no entry refers to: subtitles downloaded
    /// before this index existed, or for a playback that never recorded them.
    /// They have no title to come back for, so each goes once its last write
    /// is older than <paramref name="retention"/>.
    /// </summary>
    public IReadOnlyList<string> OrphanFiles(
        IEnumerable<(string Name, DateTimeOffset LastWrite)> files,
        DateTimeOffset now,
        TimeSpan retention,
        IReadOnlySet<string>? inUse = null)
    {
        var referenced = ReferencedFiles;
        return [.. files
            .Where(file => SavedSubtitleRules.IsSafeFileName(file.Name)
                && !referenced.Contains(file.Name)
                && inUse?.Contains(file.Name) != true
                && now - file.LastWrite >= retention)
            .Select(file => file.Name)];
    }

    private SavedSubtitleRemoval RemoveWhere(Func<SavedSubtitle, bool> predicate)
    {
        var removed = _entries.Where(predicate).ToList();
        if (removed.Count == 0) return new SavedSubtitleRemoval([], []);
        _entries.RemoveAll(entry => removed.Contains(entry));

        var stillReferenced = ReferencedFiles;
        var files = removed
            .Select(entry => entry.FileName)
            .Where(name => !stillReferenced.Contains(name))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        return new SavedSubtitleRemoval(removed, files);
    }

    /// <summary>Exactly one subtitle per title is on: this one.</summary>
    private void Select(string titleKey, string fileName)
    {
        for (var index = 0; index < _entries.Count; index++)
        {
            var entry = _entries[index];
            if (entry.TitleKey != titleKey) continue;
            var selected = string.Equals(entry.FileName, fileName, StringComparison.OrdinalIgnoreCase);
            if (entry.Selected != selected) _entries[index] = entry with { Selected = selected };
        }
    }

    private static bool SameEntry(SavedSubtitle entry, string titleKey, string fileName) =>
        entry.TitleKey == titleKey && string.Equals(entry.FileName, fileName, StringComparison.OrdinalIgnoreCase);
}
