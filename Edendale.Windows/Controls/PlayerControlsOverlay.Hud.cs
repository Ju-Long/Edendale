using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Edendale.Windows.Controls;

/// <summary>
/// The floating feedback over the video: the HUD pill (F.5), the Up Next
/// card (3.4), and the skip prompt (3.5).
/// </summary>
public sealed partial class PlayerControlsOverlay
{
    private static readonly TimeSpan HudLinger = TimeSpan.FromSeconds(1.2);

    private DispatcherQueueTimer _hudTimer = null!;
    private bool _hudPersistent;
    private LibraryEpisode? _upNext;
    private readonly global::Windows.UI.ViewManagement.UISettings _uiSettings = new();

    private void InitializeHud()
    {
        _hudTimer = DispatcherQueue.CreateTimer();
        _hudTimer.Interval = HudLinger;
        _hudTimer.IsRepeating = false;
        _hudTimer.Tick += (_, _) =>
        {
            _hudTimer.Stop();
            if (!_hudPersistent) HidePill();
        };
    }

    // ------------------------------------------------------------------
    // HUD (F.5)
    // ------------------------------------------------------------------

    /// <summary>
    /// Shows <paramref name="text"/> in the pill. It fades out shortly after
    /// the last change, except a persistent one (the hold speed) which stays
    /// until <see cref="HideHud"/>. Screen readers hear the same text.
    /// </summary>
    private void ShowHud(string text, string? iconUri, bool persistent = false)
    {
        _hudPersistent = persistent;
        HudText.Text = text;
        HudIcon.UriSource = iconUri is null ? null : new Uri(iconUri);
        HudIcon.Visibility = iconUri is null ? Visibility.Collapsed : Visibility.Visible;

        // Skip the fade when Windows animations are off.
        HudPill.OpacityTransition = _uiSettings.AnimationsEnabled
            ? new ScalarTransition { Duration = TimeSpan.FromMilliseconds(180) }
            : null;
        HudPill.Opacity = 1;

        var peer = FrameworkElementAutomationPeer.FromElement(HudText)
            ?? FrameworkElementAutomationPeer.CreatePeerForElement(HudText);
        peer?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);

        _hudTimer.Stop();
        if (!persistent) _hudTimer.Start();
    }

    private void HideHud()
    {
        _hudPersistent = false;
        _hudTimer.Stop();
        HidePill();
    }

    private void HidePill()
    {
        HudPill.OpacityTransition = _uiSettings.AnimationsEnabled
            ? new ScalarTransition { Duration = TimeSpan.FromMilliseconds(250) }
            : null;
        HudPill.Opacity = 0;
    }

    /// <summary>Ctrl+↑/↓ brightness, shown as a percentage.</summary>
    public void ShowBrightness(double brightness) =>
        ShowHud(Loc.Format("Hud_Brightness", (int)Math.Round(brightness * 100)), null);

    // ------------------------------------------------------------------
    // Up Next (3.4)
    // ------------------------------------------------------------------

    /// <summary>Recomputed every tick, so seeking back hides the card again.</summary>
    private void UpdateUpNext(long timeMilliseconds, long durationMilliseconds)
    {
        var next = _context is null
            ? null
            : EpisodeProgression.UpcomingEpisode(
                TimeSpan.FromMilliseconds(timeMilliseconds),
                durationMilliseconds > 0 ? TimeSpan.FromMilliseconds(durationMilliseconds) : null,
                AppServices.PlayerPreferences.LoopEnabled,
                _context.Episode,
                _context.Show);

        if (next?.Id == _upNext?.Id) return;
        _upNext = next;
        if (next is null)
        {
            UpNextCard.Visibility = Visibility.Collapsed;
            return;
        }

        UpNextCode.Text = next.EpisodeCode;
        UpNextTitle.Text = next.DisplayTitle;
        var artwork = next.StillUrl ?? _context?.Show?.BackdropUrl;
        UpNextImage.Source = Uri.TryCreate(artwork, UriKind.Absolute, out var uri) ? new BitmapImage(uri) : null;
        AutomationProperties.SetName(UpNextCard, Loc.Format("UpNext_Name", next.EpisodeCode, next.DisplayTitle));
        UpNextCard.Visibility = Visibility.Visible;
    }

    private void HideUpNext()
    {
        _upNext = null;
        UpNextCard.Visibility = Visibility.Collapsed;
        UpNextImage.Source = null;
    }

    private void UpNextCard_Click(object sender, RoutedEventArgs e)
    {
        if (_upNext is not { } next || _context?.Show is not { } show) return;
        PlayNextRequested?.Invoke(this, PlayerSession.RequestFor(show, next));
    }

    // ------------------------------------------------------------------
    // Skip prompts (3.5)
    // ------------------------------------------------------------------

    private void UpdateSegmentPrompt(long timeMilliseconds, long durationMilliseconds)
    {
        if (_mediaPlayer is null) return;
        AppServices.SegmentPrompts.Update(
            timeMilliseconds,
            durationMilliseconds > 0 ? durationMilliseconds : null,
            _mediaPlayer.IsSeekable && !_isSliderManipulating);
        RefreshSegmentPrompt();
    }

    /// <summary>Shows the prompt for the active range; hidden while scrubbing.</summary>
    public void RefreshSegmentPrompt()
    {
        var segment = _mediaPlayer is null || _isSliderManipulating ? null : AppServices.SegmentPrompts.ActiveSegment;
        if (segment is null)
        {
            SkipSegmentButton.Visibility = Visibility.Collapsed;
            return;
        }

        var label = Loc.Get(segment.Kind switch
        {
            MediaSegmentKind.Intro => "Segment_SkipIntro",
            MediaSegmentKind.Recap => "Segment_SkipRecap",
            _ => "Segment_SkipCredits",
        });
        SkipSegmentButton.Content = label;
        AutomationProperties.SetName(SkipSegmentButton, label);
        AutomationProperties.SetHelpText(SkipSegmentButton, Loc.Get("Segment_SkipHelp"));
        SkipSegmentButton.Visibility = Visibility.Visible;
    }

    private void SkipSegmentButton_Click(object sender, RoutedEventArgs e) => ActivateSkipPrompt();

    /// <summary>The prompt, or the S key: a bounded range seeks to its end; terminal credits finish the item.</summary>
    public void ActivateSkipPrompt()
    {
        if (_mediaPlayer is not { } player || SkipSegmentButton.Visibility != Visibility.Visible) return;
        var length = player.Length;
        var action = AppServices.SegmentPrompts.ConsumeSkip(
            Math.Max(0, player.Time), length > 0 ? length : null, player.IsSeekable);
        RefreshSegmentPrompt();

        switch (action)
        {
            case SegmentSkipAction.Seek seek:
                player.Time = seek.TargetMilliseconds;
                break;
            case SegmentSkipAction.Finish:
                EndReachedBySkip?.Invoke(this, new RoutedEventArgs());
                break;
        }
    }
}
