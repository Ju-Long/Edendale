using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml.Controls;
using Windows.Globalization.NumberFormatting;

namespace Edendale.Windows.Controls;

/// <summary>Settings → App Controls (DIFF.md §3.1).</summary>
public sealed partial class AppControlsSettings : UserControl
{
    private readonly ChipGroup<SkipInterval> _skipBack;
    private readonly ChipGroup<SkipInterval> _skipForward;
    private bool _updating;

    public AppControlsSettings()
    {
        InitializeComponent();

        _skipBack = new ChipGroup<SkipInterval>(SkipBackChips, Options(SkipDirection.Backward),
            value => AppServices.Controls.SkipBackwardInterval = value);
        _skipForward = new ChipGroup<SkipInterval>(SkipForwardChips, Options(SkipDirection.Forward),
            value => AppServices.Controls.SkipForwardInterval = value);

        // Hold speeds step in quarters and read in the reader's number format.
        foreach (var box in new[] { HoldLeftBox, HoldRightBox })
        {
            box.NumberFormatter = new DecimalFormatter
            {
                IntegerDigits = 1,
                FractionDigits = 2,
                NumberRounder = new IncrementNumberRounder
                {
                    Increment = PlayerControlPreferences.HoldRateStep,
                    RoundingAlgorithm = RoundingAlgorithm.RoundHalfUp,
                },
            };
        }

        Refresh();
    }

    private static IReadOnlyList<ChipOption<SkipInterval>> Options(SkipDirection direction) =>
        [.. PlayerControlPreferences.SkipIntervals.Select(interval =>
        {
            var seconds = (int)interval;
            var glyph = $"ms-appx:///Assets/Icons/arrow-rotate-{(direction == SkipDirection.Backward ? "left" : "right")}-{seconds}.svg";
            var name = Loc.Format(direction == SkipDirection.Backward ? "Settings_SkipBackSeconds" : "Settings_SkipForwardSeconds", seconds);
            return new ChipOption<SkipInterval>(
                interval,
                new SvgIcon { UriSource = new Uri(glyph), Width = 20, Height = 20 },
                name,
                name);
        })];

    private void Refresh()
    {
        _updating = true;
        _skipBack.Select(AppServices.Controls.SkipBackwardInterval);
        _skipForward.Select(AppServices.Controls.SkipForwardInterval);
        HoldLeftBox.Value = AppServices.Controls.HoldRate(HoldSide.Left);
        HoldRightBox.Value = AppServices.Controls.HoldRate(HoldSide.Right);
        _updating = false;
    }

    private void HoldLeftBox_ValueChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) =>
        SetHoldRate(sender, HoldSide.Left, args.NewValue);

    private void HoldRightBox_ValueChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) =>
        SetHoldRate(sender, HoldSide.Right, args.NewValue);

    /// <summary>Stored values snap to the quarter grid; the box shows what was stored.</summary>
    private void SetHoldRate(NumberBox box, HoldSide side, double value)
    {
        if (_updating) return;
        AppServices.Controls.SetHoldRate(double.IsNaN(value) ? AppServices.Controls.HoldRate(side) : value, side);
        var stored = AppServices.Controls.HoldRate(side);
        if (Math.Abs(box.Value - stored) > PlayerLogic.RateTolerance)
        {
            _updating = true;
            box.Value = stored;
            _updating = false;
        }
    }
}
