using Edendale.Windows.Core;
using Edendale.Windows.Services;
using LibVLCSharp.Platforms.Windows;
using LibVLCSharp.Shared;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.UI.Core;
using VirtualKey = Windows.System.VirtualKey;

namespace Edendale.Windows;

/// <summary>
/// The full-window LibVLC player: engine lifetime (and reopening at the same
/// position when an instance option changes), progress and completion,
/// auto-advance, per-title memory, the docked panes, full screen and Picture
/// in Picture, keyboard, and the system media controls.
/// </summary>
public sealed partial class MainWindow
{
    private enum PlayerPane
    {
        None,
        Playlist,
        Adjustments,
    }

    /// <summary>What a reopen at the same position restores (F.3).</summary>
    private sealed record ReopenState(
        long TimeMilliseconds,
        double BaseRate,
        bool WasPlaying,
        PlayerTrack? Audio,
        PlayerTrack? Subtitle,
        bool SubtitlesOff,
        PlayerTrack? Video,
        string? SelectedExternalSubtitle);

    private LibVLC? _libVlc;
    private string[] _swapChainOptions = [];
    private IReadOnlyList<string>? _engineArguments;
    private MediaPlayer? _mediaPlayer;
    private PlaybackRequest? _currentPlayback;
    private PlayerContext? _context;
    private DispatcherQueueTimer? _progressTimer;
    private readonly PlaybackTransitions _transitions = new();
    private bool _resumePending;
    private bool _titleMemoryPending;
    private ReopenState? _reopenState;
    private string? _reattachSelected;
    private bool _reattachPending;
    private bool _reopening;
    private bool _isCompactOverlay;

    /// <summary>Option B's own video output while frame generation runs (ENHANCEMENT.md G); null otherwise.</summary>
    private Services.FrameGeneration.FrameGenerationPresenter? _frameGeneration;

    /// <summary>Frame generation failed (or can't serve this source) during this presentation.</summary>
    private bool _frameGenerationRefused;
    private bool _isFullScreen;
    private PlayerPane _openPane;
    private VideoSourceInfo _sourceInfo = VideoSourceInfo.Unknown;
    private MediaTransport? _transport;

    /// <summary>
    /// The custom input of a remote item (DIFF.md §3.12): cancelled before
    /// the player stops, since LibVLC waits for a blocked read.
    /// </summary>
    private Services.Remote.ByteSourceMediaInput? _mediaInput;
    private readonly global::Windows.UI.ViewManagement.UISettings _uiSettings = new();

    private GamepadInput? _gamepad;
    private TaskbarButtons? _taskbarButtons;

    /// <summary>X.2: the taskbar thumbnail's buttons follow the player.</summary>
    private void UpdateTaskbarButtons(bool playing) =>
        _taskbarButtons?.Update(
            visible: PlayerOverlay.Visibility == Visibility.Visible,
            playing,
            hasPrevious: _context?.PreviousRequest is not null,
            hasNext: _context?.NextRequest is not null);

    private void InitializePlayer()
    {
        AppWindow.Changed += AppWindow_Changed;
        _gamepad = new GamepadInput(DispatcherQueue, Gamepad_Action);
        Activated += (_, args) => _gamepad.SetWindowActive(args.WindowActivationState != WindowActivationState.Deactivated);
        AppServices.PlayerSettings.Changed += (_, key) => DispatcherQueue.TryEnqueue(() => PlayerSetting_Changed(key));
        _ = GpuProbe.CapabilitiesAsync();

        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        _transport = MediaTransport.TryCreate(hwnd, DispatcherQueue, MediaTransport_Command);
        _taskbarButtons = TaskbarButtons.TryCreate(hwnd, command => MediaTransport_Command(command switch
        {
            TaskbarCommand.Previous => MediaTransportCommand.Previous,
            TaskbarCommand.Next => MediaTransportCommand.Next,
            _ => _mediaPlayer?.IsPlaying == true ? MediaTransportCommand.Pause : MediaTransportCommand.Play,
        }));
    }

    /// <summary>Live settings reach the running player at once; nothing restarts.</summary>
    private void PlayerSetting_Changed(string key)
    {
        if (_mediaPlayer is not { } player) return;
        if (AudioEnhancement.Owns(key))
        {
            PlayerEffects.ApplyEqualizer(player, AppServices.AudioEnhancement);
        }
        else if (key == VideoAdjustments.StorageKey)
        {
            PlayerEffects.ApplyAdjustments(player, AppServices.VideoAdjustments.EffectiveValues);
        }
        else if (key == PlayerPreferences.AspectFillKey)
        {
            ApplyAspectMode();
        }
    }

    // ------------------------------------------------------------------
    // Opening and the engine
    // ------------------------------------------------------------------

    private void OpenPlayer(PlaybackRequest request)
    {
        LeaveCurrentItem();
        DisposePlayer();

        _transitions.Present();
        _currentPlayback = request;
        _context = PlayerContext.Resolve(request, AppServices.Library.Shows);
        _resumePending = true;
        _titleMemoryPending = true;
        _reopenState = null;
        _reattachPending = false;
        _reattachSelected = null;
        _frameGenerationRefused = false;
        _sourceInfo = VideoSourceInfo.Unknown;

        // Subtitles downloaded for this title before come back without a
        // network request, and the one that was on last time is on again.
        foreach (var saved in AppServices.SavedSubtitles.ForTitle(request))
        {
            _context.AddSavedSubtitle(saved.FileUri);
            if (saved.Subtitle.Selected) _reattachSelected ??= saved.FileUri;
        }

        AppServices.VideoEnhancement.IsShowingOriginal = false;
        AppServices.VideoAdjustments.IsShowingOriginal = false;
        AppServices.SegmentPrompts.Begin(IntroDbMedia.For(request));

        PlayerOverlay.Visibility = Visibility.Visible;
        ApplyingLayer.Visibility = Visibility.Collapsed;
        _gamepad?.SetRunning(true);
        ControlsOverlay.SetMediaPlayer(null, request.Title.ToUpperInvariant(), request.Subtitle, request, _context);
        AdjustmentsPanel.Bind(null, _context);
        if (_openPane == PlayerPane.Playlist) PlaylistPanel.Load(request);
        if (_openPane == PlayerPane.None) ControlsOverlay.Focus(FocusState.Programmatic);
        _transport?.SetItem(request, _context);
        UpdateTaskbarButtons(playing: false);

        // The WinUI VideoView creates its Direct3D swap chain only once it is
        // visible, so the first request waits for Initialized.
        if (_swapChainOptions.Length > 0) _ = StartPlaybackAsync(request);
    }

    private void PlayerElement_Initialized(object sender, InitializedEventArgs e)
    {
        var changed = _swapChainOptions.Length > 0 && !_swapChainOptions.SequenceEqual(e.SwapChainOptions);
        _swapChainOptions = e.SwapChainOptions;
        if (changed)
        {
            // A new swap chain invalidates the engine bound to the old one.
            _engineArguments = null;
            if (_mediaPlayer is not null)
            {
                _ = ReopenAtSamePositionAsync();
                return;
            }
        }
        if (_currentPlayback is not null && _mediaPlayer is null) _ = StartPlaybackAsync(_currentPlayback);
    }

    /// <summary>
    /// Builds or reuses the LibVLC instance for <paramref name="arguments"/>.
    /// Subtitle appearance and video enhancement are instance arguments in
    /// LibVLC 3 (the video output reads them when it opens, from the media
    /// player's parent), so a change needs a new instance. The old one is
    /// released only after the new one exists, keeping the plugins loaded.
    /// </summary>
    private void EnsureEngine(IReadOnlyList<string> arguments)
    {
        if (_libVlc is not null && _engineArguments is not null && _engineArguments.SequenceEqual(arguments)) return;

        var created = new LibVLC([.. _swapChainOptions, .. arguments]);
        var previous = _libVlc;
        _libVlc = created;
        _engineArguments = arguments;
        previous?.Dispose();
    }

    private VideoEnhancementResult BuildEnhancement(GpuCapabilities capabilities, VideoSourceInfo source)
    {
        var settings = AppServices.VideoEnhancement;
        var scale = PlayerElement.XamlRoot?.RasterizationScale ?? 1;
        return VideoEnhancementOptions.Build(
            settings.EffectivePreset,
            settings.MotionSmoothing && !settings.IsShowingOriginal,
            onBattery: false,
            capabilities,
            source,
            (int)Math.Round(PlayerElement.ActualWidth * scale),
            (int)Math.Round(PlayerElement.ActualHeight * scale),
#if DEBUG
            frameRateIndicator: true);
#else
            frameRateIndicator: false);
#endif
    }

    private async Task StartPlaybackAsync(PlaybackRequest request)
    {
        var generation = _transitions.Generation;
        bool Current() => ReferenceEquals(request, _currentPlayback) && generation == _transitions.Generation && _mediaPlayer is null;

        try
        {
            var capabilities = await GpuProbe.CapabilitiesAsync();
            if (!Current()) return;

            // E.3: Motion Smoothing depends on the frame rate, so only then is
            // the file parsed (briefly) before it plays. Remote items aren't
            // parsed ahead: that would cost a second connection, so their
            // frame rate stays unknown and Motion Smoothing waits for it.
            var settings = AppServices.VideoEnhancement;
            var isRemoteInput = Services.Remote.ConnectorFactory.NeedsCustomInput(request.FilePath);
            var wantsFrameRate = (settings.MotionSmoothing && capabilities.MotionSmoothing) || WantsFrameGeneration(capabilities);
            if (!isRemoteInput && wantsFrameRate && !settings.IsShowingOriginal && _sourceInfo.FrameRate is null)
            {
                EnsureEngine(PlayerEffects.EngineArguments(BuildEnhancement(capabilities, _sourceInfo), _uiSettings.TextScaleFactor));
                using var probe = new Media(_libVlc!, new Uri(request.FilePath));
                await probe.Parse(MediaParseOptions.ParseLocal, timeout: 2000);
                if (!Current()) return;
                _sourceInfo = PlayerEffects.SourceInfo(probe);
            }

            var enhancement = BuildEnhancement(capabilities, _sourceInfo);
            EnsureEngine(PlayerEffects.EngineArguments(enhancement, _uiSettings.TextScaleFactor));
            AdjustmentsPanel.ShowEnhancement(enhancement, capabilities);

            var player = new MediaPlayer(_libVlc!);
            Attach(player);
            _mediaPlayer = player;
            if (!StartFrameGeneration(player, capabilities)) PlayerElement.MediaPlayer = player;
            ControlsOverlay.SetMediaPlayer(player, request.Title.ToUpperInvariant(), request.Subtitle, request, _context);
            AdjustmentsPanel.Bind(player, _context);
            PlayerEffects.ApplyEqualizer(player, AppServices.AudioEnhancement);
            _context?.ResetExternalTracks();
            _reattachPending = _context?.ExternalSubtitleUris.Count > 0;

            Media media;
            try
            {
                media = CreateMedia(request);
            }
            catch (ConnectorException failure)
            {
                // A source that needs sign-in, say: its own message, not "unsupported".
                FailPlayback(failure.Message);
                return;
            }
            using (media)
            {
                if (!player.Play(media))
                {
                    FailPlayback();
                    return;
                }
            }

            _progressTimer = DispatcherQueue.CreateTimer();
            _progressTimer.Interval = TimeSpan.FromSeconds(5);
            _progressTimer.Tick += (_, _) => ProgressTick();
            _progressTimer.Start();
        }
        catch (VLCException)
        {
            FailPlayback();
        }
    }

    /// <summary>Removes the handlers <see cref="Attach"/> gave the current player.</summary>
    private Action? _detachPlayer;

    /// <summary>
    /// LibVLCSharp raises MediaPlayer events with its internal event manager
    /// as the sender, not the player, so each handler is bound to the player
    /// it was attached to. That keeps a late event from a player stopped for a
    /// reopen apart from the new player's.
    /// </summary>
    private void Attach(MediaPlayer player)
    {
        EventHandler<EventArgs> playing = (_, _) => MediaPlayer_Playing(player);
        EventHandler<EventArgs> paused = (_, _) => MediaPlayer_Paused(player);
        EventHandler<MediaPlayerLengthChangedEventArgs> lengthChanged = (_, _) => MediaPlayer_LengthChanged(player);
        EventHandler<EventArgs> endReached = (_, _) => MediaPlayer_EndReached(player);
        EventHandler<EventArgs> encounteredError = (_, _) => MediaPlayer_EncounteredError(player);
        EventHandler<MediaPlayerESAddedEventArgs> esAdded = (_, e) => MediaPlayer_ESAdded(player, e);
        EventHandler<MediaPlayerVoutEventArgs> vout = (_, e) => MediaPlayer_Vout(player, e);
        player.Playing += playing;
        player.Paused += paused;
        player.Stopped += paused;
        player.LengthChanged += lengthChanged;
        player.EndReached += endReached;
        player.EncounteredError += encounteredError;
        player.ESAdded += esAdded;
        player.Vout += vout;
        _detachPlayer = () =>
        {
            player.Playing -= playing;
            player.Paused -= paused;
            player.Stopped -= paused;
            player.LengthChanged -= lengthChanged;
            player.EndReached -= endReached;
            player.EncounteredError -= encounteredError;
            player.ESAdded -= esAdded;
            player.Vout -= vout;
        };
    }

    private void Detach()
    {
        _detachPlayer?.Invoke();
        _detachPlayer = null;
    }

    /// <summary>
    /// The media for a request. HTTP and SFTP items play through a custom
    /// input, so their tokens and signed links never reach LibVLC's own
    /// network access; NFS goes to LibVLC's NFS module (X.5); local files and
    /// UNC paths are opened directly.
    /// </summary>
    private Media CreateMedia(PlaybackRequest request)
    {
        if (Services.Remote.ConnectorFactory.NeedsCustomInput(request.FilePath))
        {
            var source = Services.Remote.ConnectorFactory.ByteSourceFor(request.FilePath, AppServices.Connectors);
            _mediaInput = new Services.Remote.ByteSourceMediaInput(source);
            return new Media(_libVlc!, _mediaInput);
        }
        return new Media(_libVlc!, new Uri(request.FilePath));
    }

    private void FailPlayback(string? message = null)
    {
        // A remote read says why it failed ("This file is no longer in OneDrive").
        message ??= _mediaInput?.Source.FailureReason ?? Loc.Get("Activation_UnsupportedFile");
        ClosePlayer();
        ShowActivationMessage(message);
    }

    // ------------------------------------------------------------------
    // F.3: reopen at the same position
    // ------------------------------------------------------------------

    /// <summary>
    /// Rebuilds the engine with the current instance options, keeping the
    /// time, rate, pause state, tracks, and attached subtitles. The stopped
    /// player never writes progress (the ReferenceEquals guards below).
    /// </summary>
    private async Task ReopenAtSamePositionAsync()
    {
        if (_reopening || _mediaPlayer is not { } player || _currentPlayback is not { } request) return;
        _reopening = true;
        try
        {
            var (video, audio, subtitles) = PlayerEffects.Tracks(player, _context);
            var subtitle = subtitles.FirstOrDefault(track => track.Id == player.Spu);
            _reopenState = new ReopenState(
                TimeMilliseconds: Math.Max(0, player.Time),
                BaseRate: ControlsOverlay.BaseRate,
                WasPlaying: player.IsPlaying,
                Audio: audio.FirstOrDefault(track => track.Id == player.AudioTrack),
                Subtitle: subtitle,
                SubtitlesOff: player.Spu < 0,
                Video: video.Count > 1 ? video.FirstOrDefault(track => track.Id == player.VideoTrack) : null,
                SelectedExternalSubtitle: subtitle is { IsExternal: true } && _context is { } context
                    && context.ExternalSubtitleTracks.TryGetValue(subtitle.Id, out var uri) ? uri : null);
            _resumePending = false;
            _titleMemoryPending = false;

            ApplyingLayer.Visibility = Visibility.Visible;
            WriteProgress();
            DisposePlayer();
            await StartPlaybackAsync(request);
        }
        finally
        {
            _reopening = false;
        }
    }

    /// <summary>
    /// A panel option LibVLC reads at video-output time changed. When the
    /// resulting arguments are the ones the engine already runs with (High
    /// Quality without a denoiser, say), only the labels update.
    /// </summary>
    private void AdjustmentsPanel_EngineReopenRequested(object? sender, EventArgs e)
    {
        var capabilities = GpuProbe.Current;
        var enhancement = BuildEnhancement(capabilities, _sourceInfo);
        var arguments = PlayerEffects.EngineArguments(enhancement, _uiSettings.TextScaleFactor);
        var frameGenerationChanged = (_frameGeneration is not null)
            != (WantsFrameGeneration(capabilities) && FrameGenerationRules.IsEligible(_sourceInfo));
        if (_engineArguments is not null && _engineArguments.SequenceEqual(arguments) && !frameGenerationChanged)
        {
            AdjustmentsPanel.ShowEnhancement(enhancement, capabilities);
            AdjustmentsPanel.ShowFrameGeneration(FrameGenerationRules.BackendFor(capabilities), FrameGenerationStatus(capabilities));
            return;
        }
        _ = ReopenAtSamePositionAsync();
    }

    private void AdjustmentsPanel_RateChangeRequested(object? sender, double rate) => ControlsOverlay.SetBaseRate(rate);

    private void ControlsOverlay_BaseRateChanged(object? sender, double rate) => AdjustmentsPanel.ShowRate(rate);

    private void RestoreReopenState(MediaPlayer player, ReopenState state)
    {
        if (state.TimeMilliseconds > 0 && player.Length <= 0) return; // wait for LengthChanged
        _reopenState = null;

        if (state.TimeMilliseconds > 0) player.Time = Math.Min(state.TimeMilliseconds, player.Length);
        ControlsOverlay.SetBaseRate(state.BaseRate);
        ReattachExternalSubtitles(player, state.SelectedExternalSubtitle);

        var (video, audio, subtitles) = PlayerEffects.Tracks(player, _context);
        if (state.Audio is { } wantedAudio
            && (audio.FirstOrDefault(track => track.Id == wantedAudio.Id && track.Language == wantedAudio.Language)
                ?? TitlePlaybackMemory.BestAudioMatch(Remembered(wantedAudio), audio)) is { } audioMatch)
        {
            player.SetAudioTrack(audioMatch.Id);
        }
        if (state.SubtitlesOff)
        {
            player.SetSpu(-1);
        }
        else if (state.Subtitle is { IsExternal: false } wantedSubtitle
            && (subtitles.FirstOrDefault(track => track.Id == wantedSubtitle.Id && !track.IsExternal)
                ?? TitlePlaybackMemory.BestSubtitleMatch(Remembered(wantedSubtitle), subtitles)) is { } subtitleMatch)
        {
            player.SetSpu(subtitleMatch.Id);
        }
        if (state.Video is { } wantedVideo
            && video.FirstOrDefault(track => track.Width == wantedVideo.Width && track.Height == wantedVideo.Height) is { } videoMatch)
        {
            player.SetVideoTrack(videoMatch.Id);
        }
        if (!state.WasPlaying) player.SetPause(true);

        ApplyingLayer.Visibility = Visibility.Collapsed;

        static ContentPlayerPreferences Remembered(PlayerTrack track) => new()
        {
            AudioTrackLanguage = track.Language,
            AudioTrackName = track.Name,
            SubtitleEnabled = true,
            SubtitleTrackLanguage = track.Language,
            SubtitleTrackName = track.Name,
        };
    }

    /// <summary>
    /// Downloaded and side-loaded subtitles belong to one LibVLC input, so a
    /// new input (a reopen or a loop) attaches them again once the file's own
    /// tracks exist, keeping them out of the per-title memory.
    /// </summary>
    private void ReattachExternalSubtitles(MediaPlayer player, string? selected)
    {
        if (!_reattachPending || _context is not { } context) return;
        _reattachPending = false;
        foreach (var uri in context.ExternalSubtitleUris.ToList())
        {
            context.ExternalSubtitleAttaching(uri);
            player.AddSlave(MediaSlaveType.Subtitle, uri, select: uri == selected);
        }
        // A saved subtitle turned back on is in use again.
        if (selected is not null) AppServices.SavedSubtitles.MarkUsed(context.Request, selected);
    }

    /// <summary>
    /// Saved subtitles, as the title is left: the one on is used (its 30
    /// days start again) and comes back on next time. With another track or
    /// none on, none comes back on. Nothing is decided before the saved files
    /// are attached and the file has opened.
    /// </summary>
    private void SaveSubtitleUse()
    {
        if (_mediaPlayer is not { } player || _context is not { } context) return;
        if (_reattachPending || context.PendingExternalSubtitles.Count > 0) return;
        var (video, audio, _) = PlayerEffects.Tracks(player, context);
        if (video.Count == 0 && audio.Count == 0) return;

        if (player.Spu >= 0
            && context.ExternalSubtitleTracks.TryGetValue(player.Spu, out var uri)
            && AppServices.SavedSubtitles.Find(context.Request, uri) is not null)
        {
            AppServices.SavedSubtitles.MarkUsed(context.Request, uri);
        }
        else
        {
            AppServices.SavedSubtitles.ClearSelection(context.Request);
        }
    }

    // ------------------------------------------------------------------
    // Player events
    // ------------------------------------------------------------------

    private void MediaPlayer_Playing(MediaPlayer player)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            DisplayAwake.Hold(true);
            _transport?.SetPlaying(true);
            UpdateTaskbarButtons(playing: true);

            if (_reopenState is { } state)
            {
                RestoreReopenState(player, state);
            }
            else
            {
                ReattachExternalSubtitles(player, _reattachSelected);
                _reattachSelected = null;
                ResumeIfNeeded(player);
                RestoreTitleMemory(player);
            }

            ApplyAspectMode();
            PlayerEffects.ApplyAdjustments(player, AppServices.VideoAdjustments.EffectiveValues);
            // LibVLC 3 drops delays set before its input exists, as Bind's are.
            AdjustmentsPanel.ApplySyncOffsets();
            RefreshSourceInfo(player);
            AdjustmentsPanel.RefreshTracks();
            ControlsOverlay.RefreshChapterMarks();
        });
    }

    private void MediaPlayer_Paused(MediaPlayer player)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            DisplayAwake.Hold(false);
            _transport?.SetPlaying(false);
            UpdateTaskbarButtons(playing: false);
        });
    }

    private void MediaPlayer_LengthChanged(MediaPlayer player)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            if (_reopenState is { } state) RestoreReopenState(player, state);
            else ResumeIfNeeded(player);
        });
    }

    private void MediaPlayer_ESAdded(MediaPlayer player, MediaPlayerESAddedEventArgs e)
    {
        var type = e.Type;
        var id = e.Id;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            if (type == TrackType.Text) _context?.SubtitleTrackAdded(id);
            if (_reopenState is null) RestoreTitleMemory(player);
            AdjustmentsPanel.RefreshTracks();
            ControlsOverlay.RefreshChapterMarks();
        });
    }

    /// <summary>LibVLC's adjust filter can only be switched on once a video output exists.</summary>
    private void MediaPlayer_Vout(MediaPlayer player, MediaPlayerVoutEventArgs e)
    {
        if (e.Count <= 0) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            PlayerEffects.ApplyAdjustments(player, AppServices.VideoAdjustments.EffectiveValues);
            ApplyAspectMode();
            RefreshSourceInfo(player);
        });
    }

    private void MediaPlayer_EndReached(MediaPlayer player)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (ReferenceEquals(player, _mediaPlayer)) HandleNaturalEnd(creditsSkip: false);
        });
    }

    private void MediaPlayer_EncounteredError(MediaPlayer player)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (ReferenceEquals(player, _mediaPlayer)) FailPlayback();
        });
    }

    /// <summary>E.2/E.4 labels need the source's size and frame rate, known once it plays.</summary>
    private void RefreshSourceInfo(MediaPlayer player)
    {
        using var media = player.Media;
        var info = PlayerEffects.SourceInfo(media);
        if (info.Width <= 0) return;
        var frameRateChanged = info.FrameRate != _sourceInfo.FrameRate;
        _sourceInfo = info;
        AdjustmentsPanel.ShowEnhancement(BuildEnhancement(GpuProbe.Current, info), GpuProbe.Current);
        AdjustmentsPanel.ShowFrameGeneration(FrameGenerationRules.BackendFor(GpuProbe.Current), FrameGenerationStatus(GpuProbe.Current));
        // The frame rate is known only now when the file wasn't parsed ahead.
        if (frameRateChanged && _isFullScreen) MatchDisplayRate();
        // A remote file's rate arrives with playback; frame generation starts then.
        if (frameRateChanged && _frameGeneration is null && !_reopening
            && WantsFrameGeneration(GpuProbe.Current) && FrameGenerationRules.IsEligible(info))
        {
            _ = ReopenAtSamePositionAsync();
        }
    }

    // ------------------------------------------------------------------
    // Resume, per-title memory, progress, completion
    // ------------------------------------------------------------------

    /// <summary>Resume from the stored position when half-watched (Apple parity).</summary>
    private void ResumeIfNeeded(MediaPlayer player)
    {
        if (!_resumePending) return;
        if (_currentPlayback?.TmdbId is not int tmdbId)
        {
            _resumePending = false;
            return;
        }
        var progress = AppServices.WatchProgress.Get(tmdbId, _currentPlayback.MediaType);
        if (progress is null || progress.IsCompleted || progress.Position <= 0.005)
        {
            _resumePending = false;
            return;
        }

        if (player.Length > 0)
        {
            player.Time = (long)(player.Length * progress.Position);
            _resumePending = false;
        }
    }

    /// <summary>3.3: the title's remembered speed and tracks, once LibVLC reports the tracks.</summary>
    private void RestoreTitleMemory(MediaPlayer player)
    {
        if (!_titleMemoryPending) return;
        var preferences = AppServices.PlayerPreferences.ContentPreferences(_context?.ContentKey);
        if (preferences is null)
        {
            _titleMemoryPending = false;
            return;
        }

        var (video, audio, subtitles) = PlayerEffects.Tracks(player, _context);
        if (video.Count == 0 && audio.Count == 0) return; // tracks not reported yet
        _titleMemoryPending = false;

        if (preferences.Speed is double speed) ControlsOverlay.SetBaseRate(speed);
        if (TitlePlaybackMemory.BestAudioMatch(preferences, audio) is { } audioMatch && audioMatch.Id != player.AudioTrack)
        {
            player.SetAudioTrack(audioMatch.Id);
        }
        if (preferences.SubtitleEnabled == false)
        {
            player.SetSpu(-1);
        }
        else if (preferences.SubtitleEnabled == true
            && TitlePlaybackMemory.BestSubtitleMatch(preferences, subtitles) is { } subtitleMatch)
        {
            player.SetSpu(subtitleMatch.Id);
        }
        if (TitlePlaybackMemory.VideoMatch(preferences, video) is { } videoMatch && videoMatch.Id != player.VideoTrack)
        {
            player.SetVideoTrack(videoMatch.Id);
        }
    }

    /// <summary>Remembers the speed and tracks for the title on screen.</summary>
    private void SaveTitleMemory()
    {
        if (_mediaPlayer is not { } player || _titleMemoryPending || _context?.ContentKey is not { } key) return;
        var (video, audio, subtitles) = PlayerEffects.Tracks(player, _context);
        if (video.Count == 0 && audio.Count == 0) return;
        AppServices.PlayerPreferences.SaveContentPreferences(key, TitlePlaybackMemory.Snapshot(
            ControlsOverlay.BaseRate,
            audio.FirstOrDefault(track => track.Id == player.AudioTrack),
            subtitles.FirstOrDefault(track => track.Id == player.Spu),
            hasSubtitleTracks: subtitles.Any(track => !track.IsExternal),
            video.Count > 1 ? video.FirstOrDefault(track => track.Id == player.VideoTrack) : null));
    }

    private void ProgressTick()
    {
        WriteProgress();
        if (_mediaPlayer is { } player) _transport?.UpdateTimeline(player.Time, player.Length);
    }

    private void WriteProgress()
    {
        if (!_transitions.ShouldWriteProgress) return;
        if (_mediaPlayer is null || _currentPlayback?.TmdbId is not int tmdbId) return;
        var durationMilliseconds = _mediaPlayer.Length;
        if (durationMilliseconds <= 0) return;
        var positionMilliseconds = Math.Max(0, _mediaPlayer.Time);

        AppServices.WatchProgress.Update(
            tmdbId,
            _currentPlayback.MediaType,
            (double)positionMilliseconds / durationMilliseconds,
            positionMilliseconds / 1000.0,
            _currentPlayback.ShowTmdbId,
            _currentPlayback.SeasonNumber,
            _currentPlayback.EpisodeNumber);
    }

    private void CompleteCurrent()
    {
        if (_currentPlayback?.TmdbId is int tmdbId)
        {
            AppServices.WatchProgress.MarkCompleted(tmdbId, _currentPlayback.MediaType);
        }
        _transitions.MarkCurrentCompleted();
    }

    /// <summary>
    /// 3.4: at the natural end (or a terminal credits skip) the item is
    /// complete, then the next stored episode plays, Loop restarts the file,
    /// or the player closes. A newer manual request cancels the queued advance.
    /// </summary>
    private void HandleNaturalEnd(bool creditsSkip)
    {
        if (_mediaPlayer is not { } player || _currentPlayback is null) return;
        var loop = AppServices.PlayerPreferences.LoopEnabled;
        if (loop && !creditsSkip)
        {
            // Loop restarts instead of finishing.
            RestartFromBeginning(player);
            return;
        }

        CompleteCurrent();
        var ticket = _transitions.RequestAdvance();
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!_transitions.Claim(ticket)) return;
            if (loop && _mediaPlayer is { } current)
            {
                _transitions.Present();
                RestartFromBeginning(current);
            }
            else if (_context?.NextRequest is { } next)
            {
                OpenPlayer(next);
            }
            else
            {
                ClosePlayer();
            }
        });
    }

    private void RestartFromBeginning(MediaPlayer player)
    {
        _reattachSelected = player.Spu >= 0 && _context is { } context
            && context.ExternalSubtitleTracks.TryGetValue(player.Spu, out var selected) ? selected : null;
        _reattachPending = _context?.ExternalSubtitleUris.Count > 0;
        _context?.ResetExternalTracks();
        player.Stop();
        player.Play();
    }

    // ------------------------------------------------------------------
    // Overlay requests
    // ------------------------------------------------------------------

    private void ClosePlayer_Click(object sender, RoutedEventArgs e) => ClosePlayer();

    private void ControlsOverlay_PlaylistRequested(object sender, RoutedEventArgs e) => TogglePane(PlayerPane.Playlist);

    private void ControlsOverlay_AdjustmentsRequested(object sender, RoutedEventArgs e) => TogglePane(PlayerPane.Adjustments);

    private void ControlsOverlay_FullScreenRequested(object sender, RoutedEventArgs e)
    {
        // A double click in the floating window brings back the full window.
        if (_isCompactOverlay) SetCompactOverlay(false);
        else SetFullScreen(!_isFullScreen);
    }

    private void ControlsOverlay_PlayNextRequested(object sender, PlaybackRequest e) => OpenPlayer(e);

    /// <summary>A credits skip that reaches the end finishes the item like the natural end.</summary>
    private void ControlsOverlay_EndReachedBySkip(object sender, RoutedEventArgs e) => HandleNaturalEnd(creditsSkip: true);

    private void AdjustmentsPanel_AspectFillChanged(object? sender, bool fill) => ApplyAspectMode();

    private void PlayerPanel_CloseRequested(object sender, RoutedEventArgs e) => ClosePane();

    private void PlaylistPanel_PlayRequested(object sender, PlaybackRequest e) => OpenPlayer(e);

    // ------------------------------------------------------------------
    // Docked panes (3.15)
    // ------------------------------------------------------------------

    private void TogglePane(PlayerPane pane)
    {
        if (_openPane == pane)
        {
            ClosePane();
            return;
        }
        if (_isCompactOverlay || _currentPlayback is null) return;

        if (pane == PlayerPane.Playlist) PlaylistPanel.Load(_currentPlayback);
        PlaylistPanel.Visibility = pane == PlayerPane.Playlist ? Visibility.Visible : Visibility.Collapsed;
        AdjustmentsPanel.Visibility = pane == PlayerPane.Adjustments ? Visibility.Visible : Visibility.Collapsed;
        _openPane = pane;
        PlayerSplit.IsPaneOpen = true;
        if (pane == PlayerPane.Playlist) PlaylistPanel.FocusCurrent();
        else AdjustmentsPanel.FocusFirst();
    }

    private void ClosePane()
    {
        if (_openPane == PlayerPane.None) return;
        _openPane = PlayerPane.None;
        PlayerSplit.IsPaneOpen = false;
        PlaylistPanel.Visibility = Visibility.Collapsed;
        AdjustmentsPanel.Visibility = Visibility.Collapsed;
        ControlsOverlay.Focus(FocusState.Programmatic);
    }

    // ------------------------------------------------------------------
    // Fit / Fill
    // ------------------------------------------------------------------

    private void PlayerElement_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (AppServices.PlayerPreferences.AspectFill) ApplyAspectMode();
    }

    /// <summary>Fit letterboxes the frame; fill crops it to the window.</summary>
    private void ApplyAspectMode()
    {
        if (_mediaPlayer is null) return;
        if (_frameGeneration is { } presenter)
        {
            presenter.Fill = AppServices.PlayerPreferences.AspectFill;
            return;
        }

        if (!AppServices.PlayerPreferences.AspectFill)
        {
            _mediaPlayer.CropGeometry = null;
            _mediaPlayer.Scale = 0;
            return;
        }

        var width = Math.Max(1, (int)Math.Round(PlayerElement.ActualWidth));
        var height = Math.Max(1, (int)Math.Round(PlayerElement.ActualHeight));
        var divisor = GreatestCommonDivisor(width, height);
        _mediaPlayer.CropGeometry = $"{width / divisor}:{height / divisor}";
    }

    private static int GreatestCommonDivisor(int left, int right)
    {
        while (right != 0)
        {
            (left, right) = (right, left % right);
        }
        return left;
    }

    // ------------------------------------------------------------------
    // Window presenters: full screen (W.1) and Picture in Picture
    // ------------------------------------------------------------------

    private void ControlsOverlay_PictureInPictureRequested(object sender, RoutedEventArgs e)
        => SetCompactOverlay(!_isCompactOverlay);

    /// <summary>
    /// Windows' Picture in Picture: the shell window switches to the
    /// compact-overlay presenter, a small always-on-top window showing just
    /// the player. The docked pane closes, having no room.
    /// </summary>
    private void SetCompactOverlay(bool compact)
    {
        if (compact == _isCompactOverlay) return;
        if (compact && PlayerOverlay.Visibility != Visibility.Visible) return;

        try
        {
            if (compact)
            {
                ClosePane();
                var presenter = CompactOverlayPresenter.Create();
                presenter.InitialSize = CompactOverlaySize.Medium;
                AppWindow.SetPresenter(presenter);
            }
            else
            {
                AppWindow.SetPresenter(AppWindowPresenterKind.Default);
            }
        }
        catch (Exception)
        {
            // The compact-overlay presenter needs Windows 10 1903 or newer;
            // on anything older the player just stays full window.
            ShowActivationMessage(Loc.Get("Player_PipUnavailable"));
            return;
        }

        _isCompactOverlay = compact;
        _isFullScreen = false;
        ControlsOverlay.SetPictureInPictureActive(compact);
        ControlsOverlay.SetFullScreenActive(false);
        ControlsOverlay.Focus(FocusState.Programmatic);
    }

    /// <summary>F, F11, the toolbar button, or a mouse double click.</summary>
    private void SetFullScreen(bool fullScreen)
    {
        if (fullScreen == _isFullScreen) return;
        if (fullScreen && PlayerOverlay.Visibility != Visibility.Visible) return;
        if (fullScreen && _isCompactOverlay) SetCompactOverlay(false);

        try
        {
            AppWindow.SetPresenter(fullScreen ? AppWindowPresenterKind.FullScreen : AppWindowPresenterKind.Default);
        }
        catch (Exception)
        {
            return;
        }
        _isFullScreen = fullScreen;
        ControlsOverlay.SetFullScreenActive(fullScreen);
        MatchDisplayRate();
    }

    /// <summary>
    /// X.4: full screen moves the display to a whole multiple of the frame
    /// rate when the monitor offers one; leaving full screen restores it.
    /// </summary>
    private void MatchDisplayRate()
    {
        if (_isFullScreen && PlayerOverlay.Visibility == Visibility.Visible)
        {
            // Doubled frames want a multiple of the doubled rate.
            var rate = _frameGeneration is not null ? _sourceInfo.FrameRate * 2 : _sourceInfo.FrameRate;
            DisplayRefreshRate.Match(WinRT.Interop.WindowNative.GetWindowHandle(this), rate);
        }
        else
        {
            DisplayRefreshRate.Restore();
        }
    }

    /// <summary>Keeps the flags right when Windows changes the presenter itself.</summary>
    private void AppWindow_Changed(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (!args.DidPresenterChange) return;
        var kind = sender.Presenter.Kind;
        _isFullScreen = kind == AppWindowPresenterKind.FullScreen;
        _isCompactOverlay = kind == AppWindowPresenterKind.CompactOverlay;
        ControlsOverlay.SetFullScreenActive(_isFullScreen);
        ControlsOverlay.SetPictureInPictureActive(_isCompactOverlay);
        if (!_isFullScreen) DisplayRefreshRate.Restore();
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    private static bool IsDown(VirtualKey key) =>
        InputKeyboardSource.GetKeyStateForCurrentThread(key).HasFlag(CoreVirtualKeyStates.Down);

    private bool IsInPane(object? source)
    {
        for (var element = source as DependencyObject; element is not null; element = VisualTreeHelper.GetParent(element))
        {
            if (ReferenceEquals(element, PlayerPaneHost)) return true;
            if (ReferenceEquals(element, PlayerOverlay)) return false;
        }
        return false;
    }

    /// <summary>
    /// Esc (and a controller's B) peels back one layer at a time: a transient
    /// panel, the docked pane, full screen or the floating window, then the player.
    /// </summary>
    private void BackOutOneLayer()
    {
        if (ControlsOverlay.DismissTransient()) return;
        if (_openPane != PlayerPane.None) ClosePane();
        else if (_isFullScreen) SetFullScreen(false);
        else if (_isCompactOverlay) SetCompactOverlay(false);
        else ClosePlayer();
    }

    /// <summary>X.1: what the controller asked for (GamepadInterpreter).</summary>
    private void Gamepad_Action(PadAction action)
    {
        if (PlayerOverlay.Visibility != Visibility.Visible) return;
        switch (action)
        {
            case PadAction.PlayPause: ControlsOverlay.TogglePlayPause(); break;
            case PadAction.Back: BackOutOneLayer(); break;
            case PadAction.SkipBackward: ControlsOverlay.Skip(SkipDirection.Backward); break;
            case PadAction.SkipForward: ControlsOverlay.Skip(SkipDirection.Forward); break;
            case PadAction.SeekBackward: ControlsOverlay.SeekBy(-GamepadInterpreter.SeekSeconds); break;
            case PadAction.SeekForward: ControlsOverlay.SeekBy(GamepadInterpreter.SeekSeconds); break;
            case PadAction.VolumeUp: ControlsOverlay.ChangeVolume(1); break;
            case PadAction.VolumeDown: ControlsOverlay.ChangeVolume(-1); break;
            case PadAction.ToggleFullScreen: SetFullScreen(!_isFullScreen); break;
            case PadAction.Adjustments: TogglePane(PlayerPane.Adjustments); break;
            case PadAction.HoldLeftStart: ControlsOverlay.SetControllerHold(HoldSide.Left); break;
            case PadAction.HoldRightStart: ControlsOverlay.SetControllerHold(HoldSide.Right); break;
            case PadAction.HoldEnd: ControlsOverlay.SetControllerHold(null); break;
        }
    }

    private void PlayerOverlay_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Escape)
        {
            BackOutOneLayer();
            e.Handled = true;
            return;
        }

        // Keys typed into the docked pane belong to its controls, and Space
        // on a focused button or switch presses that control.
        if (IsInPane(e.OriginalSource)) return;
        if (e.Key == VirtualKey.Space && e.OriginalSource is Microsoft.UI.Xaml.Controls.Primitives.ButtonBase or ToggleSwitch) return;

        var control = IsDown(VirtualKey.Control);
        switch (e.Key)
        {
            case VirtualKey.Space:
                ControlsOverlay.TogglePlayPause();
                break;
            case VirtualKey.Left when !control:
                ControlsOverlay.Skip(SkipDirection.Backward);
                break;
            case VirtualKey.Right when !control:
                ControlsOverlay.Skip(SkipDirection.Forward);
                break;
            case VirtualKey.Up when control:
                ChangeBrightness(1);
                break;
            case VirtualKey.Down when control:
                ChangeBrightness(-1);
                break;
            case VirtualKey.Up:
                ControlsOverlay.ChangeVolume(1);
                break;
            case VirtualKey.Down:
                ControlsOverlay.ChangeVolume(-1);
                break;
            case VirtualKey.M when !control:
                ControlsOverlay.ToggleMute();
                break;
            case VirtualKey.F when !control:
            case VirtualKey.F11:
                SetFullScreen(!_isFullScreen);
                break;
            case VirtualKey.S when !control:
                ControlsOverlay.ActivateSkipPrompt();
                break;
            default:
                return;
        }
        e.Handled = true;
    }

    /// <summary>Ctrl+↑/↓, like ⌘↑/↓ on macOS: one brightness step, shown in the HUD.</summary>
    private void ChangeBrightness(int steps)
    {
        if (_mediaPlayer is null) return;
        var value = AppServices.VideoAdjustments.Step(VideoAdjustment.Brightness, steps);
        ControlsOverlay.ShowBrightness(value);
        AdjustmentsPanel.RefreshPicture();
    }

    // ------------------------------------------------------------------
    // System media controls (3.18)
    // ------------------------------------------------------------------

    private void MediaTransport_Command(MediaTransportCommand command)
    {
        if (_mediaPlayer is not { } player) return;
        switch (command)
        {
            case MediaTransportCommand.Play:
                if (!player.IsPlaying) ControlsOverlay.TogglePlayPause();
                break;
            case MediaTransportCommand.Pause:
                if (player.IsPlaying) ControlsOverlay.TogglePlayPause();
                break;
            case MediaTransportCommand.Rewind:
                ControlsOverlay.Skip(SkipDirection.Backward);
                break;
            case MediaTransportCommand.FastForward:
                ControlsOverlay.Skip(SkipDirection.Forward);
                break;
            case MediaTransportCommand.Next when _context?.NextRequest is { } next:
                OpenPlayer(next);
                break;
            case MediaTransportCommand.Previous when _context?.PreviousRequest is { } previous:
                OpenPlayer(previous);
                break;
            case MediaTransportCommand.Stop:
                ClosePlayer();
                break;
        }
    }

    // ------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------

    /// <summary>The outgoing item keeps its speed, tracks, and position.</summary>
    private void LeaveCurrentItem()
    {
        if (_mediaPlayer is null) return;
        SaveTitleMemory();
        SaveSubtitleUse();
        WriteProgress();
    }

    private void ClosePlayer()
    {
        LeaveCurrentItem();
        _transitions.End();
        DisposePlayer();
        _currentPlayback = null;
        _context = null;
        _resumePending = false;
        _titleMemoryPending = false;
        _reopenState = null;
        AppServices.SegmentPrompts.End();
        ClosePane();
        SetFullScreen(false);
        SetCompactOverlay(false);
        PlayerOverlay.Visibility = Visibility.Collapsed;
        _gamepad?.SetRunning(false);
        UpdateTaskbarButtons(playing: false);
        ApplyingLayer.Visibility = Visibility.Collapsed;
        ControlsOverlay.SetMediaPlayer(null, "", "", null, null);
        AdjustmentsPanel.Bind(null, null);
        DisplayAwake.Hold(false);
        _transport?.Clear();

        // The player has let go of its subtitle files, so expired ones can go.
        AppServices.SavedSubtitles.PruneInBackground();
    }

    /// <summary>Stops and releases the media player (and with it the video output and swap chain).</summary>
    private void DisposePlayer()
    {
        _progressTimer?.Stop();
        _progressTimer = null;
        if (_mediaPlayer is not { } player) return;

        _mediaPlayer = null;
        ControlsOverlay.SetMediaPlayer(null, "", "", _currentPlayback, _context);
        AdjustmentsPanel.Bind(null, _context);
        PlayerElement.MediaPlayer = null;
        Detach();
        // Fail a read LibVLC is blocked in first, or Stop waits for it.
        var input = _mediaInput;
        _mediaInput = null;
        input?.Source.Cancel();
        player.Stop();
        player.Dispose();
        input?.Dispose();
        StopFrameGeneration();
    }

    // ------------------------------------------------------------------
    // Frame generation and upscaling (ENHANCEMENT.md G)
    // ------------------------------------------------------------------

    /// <summary>Switched on, offered by this GPU, and not refused for this presentation or by Show Original.</summary>
    private bool WantsFrameGeneration(GpuCapabilities capabilities) =>
        AppServices.VideoEnhancement.FrameGeneration
        && !AppServices.VideoEnhancement.IsShowingOriginal
        && !_frameGenerationRefused
        && FrameGenerationRules.BackendFor(capabilities) != FrameGenerationBackend.None;

    /// <summary>
    /// Routes the player's frames to Edendale's generator when frame
    /// generation applies to this source; false leaves LibVLC drawing into
    /// the VideoView as usual.
    /// </summary>
    private bool StartFrameGeneration(MediaPlayer player, GpuCapabilities capabilities)
    {
        var backend = FrameGenerationRules.BackendFor(capabilities);
        if (!WantsFrameGeneration(capabilities) || !FrameGenerationRules.IsEligible(_sourceInfo))
        {
            AdjustmentsPanel.AudioCompensationMilliseconds = 0;
            AdjustmentsPanel.ShowFrameGeneration(backend, FrameGenerationStatus(capabilities));
            return false;
        }

        Services.FrameGeneration.FrameGenerationPresenter? presenter = null;
        try
        {
            FrameGenerationPanel.Visibility = Visibility.Visible;
            presenter = new Services.FrameGeneration.FrameGenerationPresenter(
                FrameGenerationPanel, backend, _sourceInfo, AppServices.PlayerPreferences.AspectFill);
            presenter.Failed += FrameGeneration_Failed;
            presenter.Start(player);
        }
        catch (Exception error) when (error is SharpDX.SharpDXException or System.Runtime.InteropServices.COMException
            or DllNotFoundException or InvalidOperationException)
        {
            presenter?.Dispose();
            FrameGenerationPanel.Visibility = Visibility.Collapsed;
            _frameGenerationRefused = true;
            AdjustmentsPanel.ShowFrameGeneration(backend, FrameGenerationStatus(capabilities));
            return false;
        }

        _frameGeneration = presenter;
        AdjustmentsPanel.AudioCompensationMilliseconds = presenter.DelayMilliseconds;
        AdjustmentsPanel.ShowFrameGeneration(backend, FrameGenerationStatus(capabilities));
        return true;
    }

    private void StopFrameGeneration()
    {
        if (_frameGeneration is not { } presenter) return;
        _frameGeneration = null;
        presenter.Failed -= FrameGeneration_Failed;
        presenter.Dispose();
        FrameGenerationPanel.Visibility = Visibility.Collapsed;
        AdjustmentsPanel.AudioCompensationMilliseconds = 0;
    }

    /// <summary>The GPU path stopped (or the source is HDR): reopen at the same position the normal way.</summary>
    private void FrameGeneration_Failed(string reason)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (_frameGeneration is null || _frameGenerationRefused) return;
            _frameGenerationRefused = true;
            _ = ReopenAtSamePositionAsync();
        });
    }

    /// <summary>The line under the toggle: the rates and engine, or why it isn't running.</summary>
    private string? FrameGenerationStatus(GpuCapabilities capabilities)
    {
        var backend = FrameGenerationRules.BackendFor(capabilities);
        if (backend == FrameGenerationBackend.None || !AppServices.VideoEnhancement.FrameGeneration) return null;
        if (_frameGenerationRefused) return Loc.Get("FrameGeneration_Unavailable");
        if (_sourceInfo.FrameRate is null) return Loc.Get("FrameGeneration_WaitingForRate");
        if (!FrameGenerationRules.IsEligible(_sourceInfo)) return Loc.Get("FrameGeneration_NotThisFile");
        return FrameGenerationRules.Label(_sourceInfo, backend);
    }

    private void FrameGenerationPanel_SizeChanged(object sender, SizeChangedEventArgs e) => _frameGeneration?.Resize();

    private void FrameGenerationPanel_CompositionScaleChanged(SwapChainPanel sender, object args) => _frameGeneration?.Resize();

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        LeaveCurrentItem();
        DisposePlayer();
        DisplayRefreshRate.Restore();
        DisplayAwake.Hold(false);
        _libVlc?.Dispose();
        _libVlc = null;
    }
}
