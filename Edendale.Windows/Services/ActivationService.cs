// External activation — edendale:// links today, Open With files next.
// Registration uses the Windows App SDK's per-user rich-activation support
// for unpackaged apps (HKCU only, no elevation); dispatch always goes
// through MainWindow.OpenRoute so external entry points reuse the shell's
// navigation and player lifecycle.

using Microsoft.Windows.AppLifecycle;
using Windows.ApplicationModel.Activation;

namespace Edendale.Windows.Services;

public static class ActivationService
{
    /// <summary>Registers the URI scheme and Open With file types; safe to call every launch.</summary>
    public static void Register()
    {
        var icon = Path.Combine(AppContext.BaseDirectory, "Assets", "icon.ico");
        try
        {
            ActivationRegistrationManager.RegisterForProtocolActivation(
                AppRoute.Scheme, icon, "Edendale", null);
        }
        catch
        {
            // Best-effort: without registration the app still runs; links
            // just don't resolve to Edendale.
        }
        try
        {
            ActivationRegistrationManager.RegisterForFileTypeActivation(
                LibraryService.SupportedVideoExtensions, icon, "Edendale", ["open"], "");
        }
        catch
        {
            // Same: Open With simply won't offer Edendale.
        }
    }

    /// <summary>Handles the args this process was started with (call after the shell exists).</summary>
    public static void HandleCurrentActivation() =>
        Handle(AppInstance.GetCurrent().GetActivatedEventArgs());

    public static void Handle(AppActivationArguments args)
    {
        switch (args.Kind)
        {
            case ExtendedActivationKind.Protocol when args.Data is IProtocolActivatedEventArgs protocol:
                HandleUri(protocol.Uri);
                break;

            case ExtendedActivationKind.File when args.Data is IFileActivatedEventArgs files:
                OpenFiles([.. files.Files.Select(item => item.Path).Where(path => !string.IsNullOrEmpty(path))]);
                break;

            // `Edendale.exe C:\some\video.mkv` — a plain launch whose
            // command line names an existing file.
            case ExtendedActivationKind.Launch when args.Data is ILaunchActivatedEventArgs launch:
                var fileArgument = Tokenize(launch.Arguments)
                    .Where(token => !token.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                    .FirstOrDefault(File.Exists);
                if (fileArgument is not null) OpenFiles([fileArgument]);
                break;
        }
    }

    /// <summary>Open With entry point (Apple Phase 11): play directly, never import.</summary>
    public static void OpenFiles(IReadOnlyList<string> paths)
    {
        if (App.MainWindow is not MainWindow shell || paths.Count == 0) return;

        var playable = paths.FirstOrDefault(LibraryService.IsSupportedVideoFile);
        if (playable is null)
        {
            shell.ShowActivationMessage(
                Loc.Format("Activation_UnsupportedFile", Path.GetFileName(paths[0])));
            return;
        }

        // A file already in the library plays with its identity, keeping
        // watch-progress writes; anything else plays without importing.
        var library = AppServices.Library;
        if (library.Movies.FirstOrDefault(m =>
                string.Equals(m.FilePath, playable, StringComparison.OrdinalIgnoreCase)) is { } movie)
        {
            AppServices.Player.Play(movie);
            return;
        }
        var match = library.Shows
            .SelectMany(s => s.Episodes.Select(ep => (Show: s, Episode: ep)))
            .FirstOrDefault(pair => string.Equals(pair.Episode.FilePath, playable, StringComparison.OrdinalIgnoreCase));
        if (match.Episode is not null)
        {
            AppServices.Player.Play(match.Show, match.Episode);
            return;
        }

        AppServices.Player.PlayFile(playable);
    }

    private static IEnumerable<string> Tokenize(string commandLine)
    {
        var current = new System.Text.StringBuilder();
        var quoted = false;
        foreach (var character in commandLine)
        {
            if (character == '"')
            {
                quoted = !quoted;
                continue;
            }
            if (char.IsWhiteSpace(character) && !quoted)
            {
                if (current.Length > 0)
                {
                    yield return current.ToString();
                    current.Clear();
                }
                continue;
            }
            current.Append(character);
        }
        if (current.Length > 0) yield return current.ToString();
    }

    private static void HandleUri(Uri uri)
    {
        if (App.MainWindow is not MainWindow shell) return;
        if (AppRoute.Parse(uri) is { } route)
        {
            shell.OpenRoute(route);
        }
        else
        {
            shell.ShowActivationMessage(Loc.Get("Activation_BadLink"));
        }
    }
}
