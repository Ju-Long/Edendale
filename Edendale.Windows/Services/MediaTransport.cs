// 3.18: the Windows media flyout, media keys, and headset buttons through the
// System Media Transport Controls of the shell window. Play and pause, next
// and previous episode, and rewind and fast-forward by the App Controls
// lengths; the title, episode, and artwork appear in the flyout. Button
// presses arrive on a background thread and are handed to the UI thread.

using Edendale.Windows.Core;
using Windows.Media;
using Windows.Storage.Streams;

namespace Edendale.Windows.Services;

internal enum MediaTransportCommand
{
    Play,
    Pause,
    Next,
    Previous,
    Rewind,
    FastForward,
    Stop,
}

internal sealed class MediaTransport
{
    private readonly SystemMediaTransportControls _controls;
    private readonly Action<MediaTransportCommand> _dispatch;

    private MediaTransport(SystemMediaTransportControls controls, Action<MediaTransportCommand> dispatch)
    {
        _controls = controls;
        _dispatch = dispatch;
        _controls.ButtonPressed += Controls_ButtonPressed;
        _controls.IsPlayEnabled = true;
        _controls.IsPauseEnabled = true;
        _controls.IsStopEnabled = true;
        _controls.IsRewindEnabled = true;
        _controls.IsFastForwardEnabled = true;
        _controls.IsEnabled = false;
    }

    /// <summary>
    /// The window's controls, or null where Windows doesn't offer them.
    /// <paramref name="dispatch"/> runs on the UI thread.
    /// </summary>
    public static MediaTransport? TryCreate(nint windowHandle, Microsoft.UI.Dispatching.DispatcherQueue queue, Action<MediaTransportCommand> handler)
    {
        try
        {
            var controls = SystemMediaTransportControlsInterop.GetForWindow(windowHandle);
            return controls is null ? null : new MediaTransport(controls, command => queue.TryEnqueue(() => handler(command)));
        }
        catch (Exception error) when (error is System.Runtime.InteropServices.COMException or InvalidCastException or NotSupportedException)
        {
            return null;
        }
    }

    private void Controls_ButtonPressed(SystemMediaTransportControls sender, SystemMediaTransportControlsButtonPressedEventArgs args)
    {
        MediaTransportCommand? command = args.Button switch
        {
            SystemMediaTransportControlsButton.Play => MediaTransportCommand.Play,
            SystemMediaTransportControlsButton.Pause => MediaTransportCommand.Pause,
            SystemMediaTransportControlsButton.Next => MediaTransportCommand.Next,
            SystemMediaTransportControlsButton.Previous => MediaTransportCommand.Previous,
            SystemMediaTransportControlsButton.Rewind => MediaTransportCommand.Rewind,
            SystemMediaTransportControlsButton.FastForward => MediaTransportCommand.FastForward,
            SystemMediaTransportControlsButton.Stop => MediaTransportCommand.Stop,
            _ => null,
        };
        if (command is { } value) _dispatch(value);
    }

    /// <summary>Shows the item in the media flyout and enables the episode buttons it supports.</summary>
    public void SetItem(PlaybackRequest request, PlayerContext context)
    {
        try
        {
            _controls.IsEnabled = true;
            _controls.IsNextEnabled = context.NextRequest is not null;
            _controls.IsPreviousEnabled = context.PreviousRequest is not null;

            var updater = _controls.DisplayUpdater;
            updater.ClearAll();
            updater.Type = MediaPlaybackType.Video;
            updater.VideoProperties.Title = request.Title;
            updater.VideoProperties.Subtitle = request.Subtitle ?? "";
            var artwork = context.Episode?.StillUrl ?? context.Show?.BackdropUrl ?? MovieArtwork(request);
            if (Uri.TryCreate(artwork, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps)
            {
                updater.Thumbnail = RandomAccessStreamReference.CreateFromUri(uri);
            }
            updater.Update();
        }
        catch (Exception error) when (error is System.Runtime.InteropServices.COMException or ArgumentException)
        {
            // The flyout is a convenience; playback carries on without it.
        }
    }

    private static string? MovieArtwork(PlaybackRequest request) =>
        request.MediaType == "movie" && request.TmdbId is int id
            ? AppServices.Library.MovieByTmdbId(id) is { } movie ? movie.BackdropUrl ?? movie.PosterUrl : null
            : null;

    public void SetPlaying(bool playing)
    {
        try
        {
            _controls.PlaybackStatus = playing ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
        }
        catch (System.Runtime.InteropServices.COMException)
        {
        }
    }

    /// <summary>Keeps the flyout's timeline in step with playback.</summary>
    public void UpdateTimeline(long positionMilliseconds, long durationMilliseconds)
    {
        if (durationMilliseconds <= 0) return;
        try
        {
            _controls.UpdateTimelineProperties(new SystemMediaTransportControlsTimelineProperties
            {
                StartTime = TimeSpan.Zero,
                MinSeekTime = TimeSpan.Zero,
                Position = TimeSpan.FromMilliseconds(Math.Clamp(positionMilliseconds, 0, durationMilliseconds)),
                MaxSeekTime = TimeSpan.FromMilliseconds(durationMilliseconds),
                EndTime = TimeSpan.FromMilliseconds(durationMilliseconds),
            });
        }
        catch (System.Runtime.InteropServices.COMException)
        {
        }
    }

    /// <summary>The player closed: the flyout empties and the keys go back to Windows.</summary>
    public void Clear()
    {
        try
        {
            _controls.PlaybackStatus = MediaPlaybackStatus.Closed;
            _controls.DisplayUpdater.ClearAll();
            _controls.DisplayUpdater.Update();
            _controls.IsEnabled = false;
        }
        catch (System.Runtime.InteropServices.COMException)
        {
        }
    }
}
