using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Automation.Provider;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using VirtualKey = Windows.System.VirtualKey;

namespace Edendale.Windows.Controls;

/// <summary>
/// A shelf heading with a trailing hairline rule (SectionHeader.swift). Given
/// a horizontal <see cref="Shelf"/> that overflows, the rule becomes the
/// shelf's scroll indicator and scrubber: drag the gold thumb or click the
/// rule (DIFF.md §3.15). A shelf that fits keeps the plain, decorative rule.
/// </summary>
public sealed partial class ShelfHeading : UserControl
{
    private readonly TextBlock _title;
    private readonly Rectangle _rule;
    private readonly ShelfScrubber _scrubber;
    private ScrollViewer? _shelf;

    public ShelfHeading()
    {
        IsTabStop = false;
        _title = new TextBlock
        {
            Style = (Style)Application.Current.Resources["TitleLGTextStyle"],
            VerticalAlignment = VerticalAlignment.Center,
            TextWrapping = TextWrapping.NoWrap,
        };
        AutomationProperties.SetHeadingLevel(_title, AutomationHeadingLevel.Level2);

        _rule = new Rectangle
        {
            Height = 1,
            Fill = (Brush)Application.Current.Resources["EdendaleOutlineBrush"],
            VerticalAlignment = VerticalAlignment.Center,
        };
        // Decorative: nothing to announce while the shelf fits.
        AutomationProperties.SetAccessibilityView(_rule, AccessibilityView.Raw);

        _scrubber = new ShelfScrubber { Visibility = Visibility.Collapsed };
        _scrubber.Scrubbed += (_, fraction) =>
        {
            if (_shelf is null) return;
            _shelf.ChangeView(Metrics.OffsetFor(fraction), null, null, disableAnimation: true);
        };

        var grid = new Grid { ColumnSpacing = 16 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(_title);
        Grid.SetColumn(_rule, 1);
        grid.Children.Add(_rule);
        Grid.SetColumn(_scrubber, 1);
        grid.Children.Add(_scrubber);
        Content = grid;
    }

    public string Title
    {
        get => _title.Text;
        set
        {
            _title.Text = value;
            AutomationProperties.SetName(_scrubber, Loc.Format("Shelf_ScrollPosition", value));
        }
    }

    /// <summary>The horizontal scroller this heading's rule mirrors and moves.</summary>
    public ScrollViewer? Shelf
    {
        get => _shelf;
        set
        {
            if (_shelf is { } old)
            {
                old.ViewChanged -= Shelf_ViewChanged;
                old.UnregisterPropertyChangedCallback(ScrollViewer.ExtentWidthProperty, _extentToken);
                old.UnregisterPropertyChangedCallback(ScrollViewer.ViewportWidthProperty, _viewportToken);
            }
            _shelf = value;
            if (value is not null)
            {
                value.ViewChanged += Shelf_ViewChanged;
                // The extent grows as cards are laid out and the viewport
                // follows the window, neither of which raises ViewChanged.
                _extentToken = value.RegisterPropertyChangedCallback(ScrollViewer.ExtentWidthProperty, (_, _) => Update());
                _viewportToken = value.RegisterPropertyChangedCallback(ScrollViewer.ViewportWidthProperty, (_, _) => Update());
            }
            Update();
        }
    }

    private ShelfScrollMetrics Metrics => _shelf is null
        ? default
        : new ShelfScrollMetrics(_shelf.HorizontalOffset, _shelf.ExtentWidth, _shelf.ViewportWidth);

    private long _extentToken;
    private long _viewportToken;

    private void Shelf_ViewChanged(object? sender, ScrollViewerViewChangedEventArgs e) => Update();

    private void Update()
    {
        var metrics = Metrics;
        var scrollable = _shelf is not null && metrics.IsScrollable;
        _scrubber.Visibility = scrollable ? Visibility.Visible : Visibility.Collapsed;
        _rule.Visibility = scrollable ? Visibility.Collapsed : Visibility.Visible;
        _scrubber.Metrics = metrics;
    }
}

/// <summary>
/// The hairline rule as a slider track with a gold thumb whose width mirrors
/// how much of the shelf is visible. Keyboard users step it with the arrow
/// keys, Page Up/Down, Home, and End; screen readers see a slider.
/// </summary>
public sealed partial class ShelfScrubber : UserControl
{
    private const double ThumbHeight = 5;

    private readonly Rectangle _track;
    private readonly Border _thumb;
    private readonly TranslateTransform _thumbOffset = new();
    private ShelfScrollMetrics _metrics;
    private bool _dragging;

    /// <summary>A new 0…1 position chosen by drag, click, or key.</summary>
    public event EventHandler<double>? Scrubbed;

    public ShelfScrubber()
    {
        IsTabStop = true;
        UseSystemFocusVisuals = true;
        Height = 16; // a generous hit area around the 5 px thumb
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);

        _track = new Rectangle
        {
            Height = 1,
            Fill = (Brush)Application.Current.Resources["EdendaleOutlineBrush"],
            VerticalAlignment = VerticalAlignment.Center,
        };
        _thumb = new Border
        {
            Height = ThumbHeight,
            CornerRadius = new CornerRadius(ThumbHeight / 2),
            Background = (Brush)Application.Current.Resources["EdendaleGoldBrush"],
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            RenderTransform = _thumbOffset,
        };
        var grid = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        grid.Children.Add(_track);
        grid.Children.Add(_thumb);
        Content = grid;
        SizeChanged += (_, _) => Layout();
    }

    public ShelfScrollMetrics Metrics
    {
        get => _metrics;
        set
        {
            var changed = Math.Abs(value.Progress - _metrics.Progress) > 0.0005;
            _metrics = value;
            Layout();
            if (changed && FrameworkElementAutomationPeer.FromElement(this) is ShelfScrubberAutomationPeer peer)
            {
                peer.RaiseValueChanged();
            }
        }
    }

    private void Layout()
    {
        var width = ActualWidth;
        if (width <= 0) return;
        var thumbWidth = _metrics.ThumbWidth(width);
        _thumb.Width = thumbWidth;
        _thumbOffset.X = (width - thumbWidth) * _metrics.Progress;
    }

    internal void ScrubTo(double fraction) => Scrubbed?.Invoke(this, Math.Clamp(fraction, 0, 1));

    private void ScrubToPointer(PointerRoutedEventArgs e)
    {
        var x = e.GetCurrentPoint(this).Position.X;
        ScrubTo(ShelfScrollMetrics.FractionAt(x, ActualWidth, _metrics.ThumbWidth(ActualWidth)));
    }

    protected override void OnPointerPressed(PointerRoutedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (e.Pointer.PointerDeviceType == PointerDeviceType.Mouse
            && !e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        _dragging = CapturePointer(e.Pointer);
        Focus(FocusState.Pointer);
        ScrubToPointer(e);
        e.Handled = true;
    }

    protected override void OnPointerMoved(PointerRoutedEventArgs e)
    {
        base.OnPointerMoved(e);
        if (!_dragging) return;
        ScrubToPointer(e);
        e.Handled = true;
    }

    protected override void OnPointerReleased(PointerRoutedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (!_dragging) return;
        _dragging = false;
        ReleasePointerCapture(e.Pointer);
        e.Handled = true;
    }

    protected override void OnPointerCaptureLost(PointerRoutedEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        _dragging = false;
    }

    protected override void OnKeyDown(KeyRoutedEventArgs e)
    {
        var step = _metrics.Step;
        double? target = e.Key switch
        {
            VirtualKey.Left or VirtualKey.Up => _metrics.Progress - step,
            VirtualKey.Right or VirtualKey.Down => _metrics.Progress + step,
            VirtualKey.PageUp => _metrics.Progress - step * 2,
            VirtualKey.PageDown => _metrics.Progress + step * 2,
            VirtualKey.Home => 0,
            VirtualKey.End => 1,
            _ => null,
        };
        if (target is double fraction)
        {
            ScrubTo(fraction);
            e.Handled = true;
            return;
        }
        base.OnKeyDown(e);
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new ShelfScrubberAutomationPeer(this);
}

/// <summary>Presents the scrubber as a slider whose value is the shelf's scroll position in percent.</summary>
public sealed partial class ShelfScrubberAutomationPeer(ShelfScrubber owner)
    : FrameworkElementAutomationPeer(owner), IRangeValueProvider
{
    private ShelfScrubber Scrubber => (ShelfScrubber)Owner;

    protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Slider;

    protected override string GetClassNameCore() => nameof(ShelfScrubber);

    protected override object? GetPatternCore(PatternInterface patternInterface) =>
        patternInterface == PatternInterface.RangeValue ? this : base.GetPatternCore(patternInterface);

    public bool IsReadOnly => false;
    public double LargeChange => Scrubber.Metrics.Step * 200;
    public double SmallChange => Scrubber.Metrics.Step * 100;
    public double Maximum => 100;
    public double Minimum => 0;
    public double Value => Math.Round(Scrubber.Metrics.Progress * 100);

    public void SetValue(double value) => Scrubber.ScrubTo(value / 100);

    internal void RaiseValueChanged() =>
        RaisePropertyChangedEvent(RangeValuePatternIdentifiers.ValueProperty, 0d, Value);
}
