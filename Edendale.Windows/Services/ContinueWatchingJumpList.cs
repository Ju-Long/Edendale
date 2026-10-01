// X.2: the taskbar and Start jump list offers Continue Watching, so a title
// resumes in one click. Each entry launches an edendale:// route (the same
// grammar as every other entry point), which ActivationService reads from the
// launch arguments. Jump lists need package identity, so unpackaged runs
// simply skip this.

using Windows.UI.StartScreen;

namespace Edendale.Windows.Services;

internal static class ContinueWatchingJumpList
{
    public sealed record Entry(string Title, string Subtitle, string Route);

    /// <summary>Entries beyond this many crowd the list without helping.</summary>
    public const int Limit = 5;

    private static string? _lastSaved;

    public static async Task UpdateAsync(IReadOnlyList<Entry> entries)
    {
        var signature = string.Join("\n", entries.Take(Limit).Select(entry => $"{entry.Route}|{entry.Title}|{entry.Subtitle}"));
        if (signature == _lastSaved) return;
        try
        {
            if (!JumpList.IsSupported()) return;
            var list = await JumpList.LoadCurrentAsync();
            list.SystemGroupKind = JumpListSystemGroupKind.None;
            list.Items.Clear();
            var group = Loc.Get("Section_ContinueWatching");
            foreach (var entry in entries.Take(Limit))
            {
                var item = JumpListItem.CreateWithArguments(entry.Route, entry.Title);
                item.Description = entry.Subtitle;
                item.GroupName = group;
                list.Items.Add(item);
            }
            await list.SaveAsync();
            _lastSaved = signature;
        }
        catch (Exception)
        {
            // No package identity, or the shell refused: the list is a convenience.
        }
    }
}
