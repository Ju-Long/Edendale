using System.Diagnostics;
using System.Numerics;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Input;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Automation.Provider;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using FontWeight = Windows.UI.Text.FontWeight;
using Point = Windows.Foundation.Point;
using ShapePath = Microsoft.UI.Xaml.Shapes.Path;
using UISettings = Windows.UI.ViewManagement.UISettings;
using VirtualKey = Windows.System.VirtualKey;

namespace Edendale.Windows.Controls;

/// <summary>A band moved on the <see cref="EqualizerGraph"/>, and the level it was moved to.</summary>
public sealed class EqualizerBandChangedEventArgs(int band, double level) : EventArgs
{
    /// <summary>The band, from 0 (60 Hz) to 9 (16 kHz).</summary>
    public int Band { get; } = band;

    /// <summary>A whole-decibel level within ±20 dB that differs from the band's current one.</summary>
    public double Level { get; } = level;
}

/// <summary>
/// Settings → Audio's ten equalizer bands drawn as one curve the user shapes
/// directly (EqualizerGraph.swift, apple-27.1). The centre line is 0 dB; a
/// profile sets where each band's point sits, and dragging a point up or
/// down moves that band in 1 dB steps. Each point is a ring that shows it can
/// be moved; it fills and grows while held, with its level above it. Only the
/// points take pointer input, so the wheel or a swipe elsewhere still scrolls
/// the page, and a click without a drag changes nothing.
///
/// The graph never changes its own levels: it raises <see cref="BandChanged"/>
/// and the host writes the model and calls <see cref="SetLevels"/> back,
/// Apple's levels-in, onChange-out. A profile change or a reset sweeps the
/// curve and points to their new levels in 300 ms; drags and keys move them
/// at once, and everything snaps when Windows animations are off.
///
/// Windows additions, since Apple's Mac graph takes no keyboard focus: the
/// points are one Tab stop that returns to the band used last; ←/→ move
/// between bands; ↑/↓ move a band 1 dB, Page Up/Page Down 3 dB, and Home/End
/// to the −20/+20 dB bounds; and the keyboard-focused band shows its level
/// like a held one. Screen readers see an "Equalizer" group of ten vertical
/// sliders named by frequency ("60 Hz"), each reading its level as Settings
/// writes it ("+8 dB").
/// </summary>
public sealed partial class EqualizerGraph : UserControl
{
    private const int BandCount = AudioEnhancementProfiles.BandCount;

    /// <summary>Page Up/Page Down step: the large change of the sliders this graph replaced.</summary>
    internal const double LargeStep = 3;

    /// <summary>Apple's <c>.snappy(duration: 0.3)</c> for profile changes and resets.</summary>
    private static readonly TimeSpan SweepDuration = TimeSpan.FromMilliseconds(300);

    /// <summary>Apple's <c>.snappy(duration: 0.18)</c> for a point's held and hover states.</summary>
    internal static readonly TimeSpan HighlightDuration = TimeSpan.FromMilliseconds(180);

    private readonly UISettings _uiSettings = new();
    private readonly Brush _gold = Token("EdendaleGoldBrush");
    private readonly Brush _textSecondary = Token("EdendaleTextSecondaryBrush");

    private readonly Canvas _canvas;
    private readonly Line[] _columns = new Line[BandCount];
    private readonly (double Level, Line Rule, Centered Label)[] _rules;
    private readonly TextBlock[] _frequencyLabels = new TextBlock[BandCount];
    private readonly Centered[] _frequencyPlacements = new Centered[BandCount];
    private readonly Centered _unitPlacement;
    private readonly ShapePath _curve;
    private readonly EqualizerBandThumb[] _thumbs = new EqualizerBandThumb[BandCount];
    private readonly Border _badge;
    private readonly TextBlock _badgeText;
    private readonly Centered _badgePlacement;

    /// <summary>The levels the host last set: what plays, and what UI Automation reads.</summary>
    private readonly double[] _levels = new double[BandCount];

    /// <summary>The levels drawn, which trail <see cref="_levels"/> during a sweep.</summary>
    private readonly double[] _shown = new double[BandCount];

    private readonly double[] _sweepFrom = new double[BandCount];
    private readonly Stopwatch _sweepClock = new();
    private bool _sweeping;

    /// <summary>
    /// The point being dragged, its level when it was grabbed, and where the
    /// pointer was then. The drag moves it from there, so grabbing a point
    /// off-centre doesn't make it jump.
    /// </summary>
    private readonly record struct Grab(int Band, double StartLevel, double StartY, uint PointerId);

    private Grab? _grab;
    private int? _hoveredBand;
    private int? _keyboardBand;
    private int _lastFocusedBand;

    /// <summary>A point was dragged, stepped from the keyboard, or set through UI Automation.</summary>
    public event EventHandler<EqualizerBandChangedEventArgs>? BandChanged;

    public EqualizerGraph()
    {
        IsTabStop = false;
        // The ten points are one Tab stop between the profiles and the preamp.
        TabFocusNavigation = KeyboardNavigationMode.Once;
        AutomationProperties.SetName(this, Loc.Get("Audio_Equalizer"));

        _canvas = new Canvas
        {
            Height = EqualizerPlot.GraphHeight,
            Margin = new Thickness(0, 4, 0, 4),
        };

        // One faint column per band, so each point reads as sitting on its
        // own frequency.
        for (var band = 0; band < BandCount; band++)
        {
            _columns[band] = Decoration(new Line
            {
                Stroke = Token("EdendaleHairlineBorderBrush"),
                StrokeThickness = 1,
            });
        }

        // The scale: a rule and a label at both limits, halfway to each, and
        // 0 dB, which is brighter and bold. The halfway rules are dashed.
        var scale = new EqualizerPlot(0, EqualizerPlot.GraphHeight, BandCount);
        _rules = [.. scale.GridLevels.Select(level =>
        {
            var rule = Decoration(new Line
            {
                Stroke = Token(level == 0 ? "EdendaleOutlineBrightBrush" : "EdendaleOutlineBrush"),
                StrokeThickness = 1,
            });
            if (!scale.IsSolidGridLine(level)) rule.StrokeDashArray = new DoubleCollection { 2, 4 };
            var label = Label(DecibelLabel.Axis(level), 10, level == 0 ? FontWeights.Bold : FontWeights.Medium, tabular: true);
            return (level, rule, new Centered(label));
        })];

        for (var band = 0; band < BandCount; band++)
        {
            _frequencyLabels[band] = Label(AudioEnhancementProfiles.BandFrequencyLabels[band], 11, FontWeights.Medium, tabular: false);
            _frequencyPlacements[band] = new Centered(_frequencyLabels[band]);
        }
        _unitPlacement = new Centered(Label(Loc.Get("Audio_HertzUnit"), 10, FontWeights.Medium, tabular: false));

        _curve = Decoration(new ShapePath
        {
            Stroke = _gold,
            StrokeThickness = 2.5,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
        });

        for (var band = 0; band < BandCount; band++)
        {
            var name = Loc.Format("Audio_BandHertz", AudioEnhancementProfiles.BandFrequencyLabels[band]);
            _thumbs[band] = new EqualizerBandThumb(this, band, name);
            _canvas.Children.Add(_thumbs[band]);
        }

        // The held point's level, above it — or below it near the top.
        _badgeText = new TextBlock
        {
            FontFamily = (FontFamily)Application.Current.Resources["TextFontFamily"],
            FontSize = 12,
            FontWeight = FontWeights.Bold,
            Foreground = Token("EdendaleOnGoldBrush"),
            TextWrapping = TextWrapping.NoWrap,
        };
        Typography.SetNumeralAlignment(_badgeText, FontNumeralAlignment.Tabular);
        AutomationProperties.SetAccessibilityView(_badgeText, AccessibilityView.Raw);
        _badge = new Border
        {
            Background = _gold,
            Padding = new Thickness(8, 4, 8, 4),
            Child = _badgeText,
            IsHitTestVisible = false,
            Visibility = Visibility.Collapsed,
        };
        _badge.SizeChanged += (_, _) => _badge.CornerRadius = new CornerRadius(_badge.ActualHeight / 2);
        _badgePlacement = new Centered(_badge);
        _canvas.Children.Add(_badge);

        _canvas.SizeChanged += (_, _) => LayoutAll();
        GettingFocus += Graph_GettingFocus;
        Unloaded += Graph_Unloaded;
        Content = _canvas;
    }

    /// <summary>Each band's level, as the host last set it.</summary>
    public IReadOnlyList<double> Levels => [.. _levels];

    /// <summary>
    /// Moves the points to <paramref name="levels"/> (one per band, in dB;
    /// clamped to ±20). <paramref name="animate"/> sweeps them there, for a
    /// profile change or a reset; otherwise they move at once, as for a
    /// drag. Levels equal to the current ones change nothing, so a refresh
    /// arriving during a sweep doesn't cut it short.
    /// </summary>
    public void SetLevels(IReadOnlyList<double> levels, bool animate)
    {
        var target = new double[BandCount];
        for (var band = 0; band < BandCount && band < levels.Count; band++)
        {
            target[band] = AudioEnhancementProfiles.Clamp(levels[band]);
        }
        if (target.AsSpan().SequenceEqual(_levels)) return;

        var previous = (double[])_levels.Clone();
        target.CopyTo(_levels, 0);

        if (animate && IsLoaded && _canvas.ActualWidth > 0 && _uiSettings.AnimationsEnabled)
        {
            StartSweep();
        }
        else
        {
            StopSweep();
            _levels.CopyTo(_shown, 0);
            LayoutLevels();
        }

        if (AutomationPeer.ListenerExists(AutomationEvents.PropertyChanged))
        {
            for (var band = 0; band < BandCount; band++)
            {
                if (previous[band] != _levels[band]
                    && FrameworkElementAutomationPeer.FromElement(_thumbs[band]) is EqualizerBandAutomationPeer peer)
                {
                    peer.RaiseLevelChanged(previous[band], _levels[band]);
                }
            }
        }
    }

    internal double LevelOf(int band) => _levels[band];

    /// <summary>Asks the host for a band's level: rounded to a whole decibel, clamped, and only when it changes.</summary>
    internal void RequestLevel(int band, double level)
    {
        if (band is < 0 or >= BandCount || !double.IsFinite(level)) return;
        var target = AudioEnhancementProfiles.Clamp(Math.Round(level, MidpointRounding.AwayFromZero));
        if (target != _levels[band]) BandChanged?.Invoke(this, new EqualizerBandChangedEventArgs(band, target));
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new EqualizerGraphAutomationPeer(this);

    // ------------------------------------------------------------------
    // Pointer: a point lights up as soon as it is pressed; dragging it moves
    // its band in whole decibels; releasing or losing the pointer lets go.
    // ------------------------------------------------------------------

    internal void ThumbPressed(EqualizerBandThumb thumb, PointerRoutedEventArgs e)
    {
        var point = e.GetCurrentPoint(_canvas);
        if (e.Pointer.PointerDeviceType == PointerDeviceType.Mouse && !point.Properties.IsLeftButtonPressed) return;
        if (_grab is not null || !thumb.CapturePointer(e.Pointer)) return;

        thumb.Focus(FocusState.Pointer);
        // The graph's coordinate space, never the moving point's.
        _grab = new Grab(thumb.Band, _levels[thumb.Band], point.Position.Y, e.Pointer.PointerId);
        _keyboardBand = null;
        UpdateHighlights();
        e.Handled = true;
    }

    internal void ThumbMoved(PointerRoutedEventArgs e)
    {
        if (_grab is not { } grab || grab.PointerId != e.Pointer.PointerId) return;
        var y = e.GetCurrentPoint(_canvas).Position.Y;
        var level = Plot.DragLevel(grab.StartLevel, y - grab.StartY);
        if (level != _levels[grab.Band]) BandChanged?.Invoke(this, new EqualizerBandChangedEventArgs(grab.Band, level));
        e.Handled = true;
    }

    internal void ThumbReleased(EqualizerBandThumb thumb, PointerRoutedEventArgs e)
    {
        if (_grab is not { } grab || grab.PointerId != e.Pointer.PointerId) return;
        LetGo();
        thumb.ReleasePointerCapture(e.Pointer);
        e.Handled = true;
    }

    /// <summary>The pointer was cancelled or captured elsewhere: the point lets go where it is.</summary>
    internal void ThumbLostPointer(PointerRoutedEventArgs e)
    {
        if (_grab is { } grab && grab.PointerId == e.Pointer.PointerId) LetGo();
    }

    private void LetGo()
    {
        _grab = null;
        UpdateHighlights();
    }

    internal void ThumbHovered(EqualizerBandThumb thumb, PointerRoutedEventArgs e, bool inside)
    {
        // Apple's onHover: a mouse or pen over the point, never a finger.
        if (e.Pointer.PointerDeviceType == PointerDeviceType.Touch) return;
        if (inside) _hoveredBand = thumb.Band;
        else if (_hoveredBand == thumb.Band) _hoveredBand = null;
        UpdateHighlights();
    }

    // ------------------------------------------------------------------
    // Keyboard and focus
    // ------------------------------------------------------------------

    /// <summary>Handles a key on a focused point; false leaves it to the page.</summary>
    internal bool ThumbKey(EqualizerBandThumb thumb, VirtualKey key)
    {
        var band = thumb.Band;
        var level = _levels[band];
        switch (key)
        {
            case VirtualKey.Up: RequestLevel(band, level + 1); break;
            case VirtualKey.Down: RequestLevel(band, level - 1); break;
            case VirtualKey.PageUp: RequestLevel(band, level + LargeStep); break;
            case VirtualKey.PageDown: RequestLevel(band, level - LargeStep); break;
            case VirtualKey.Home: RequestLevel(band, AudioEnhancementProfiles.MinimumAmplification); break;
            case VirtualKey.End: RequestLevel(band, AudioEnhancementProfiles.MaximumAmplification); break;
            case VirtualKey.Left:
                if (band > 0) _thumbs[band - 1].Focus(FocusState.Keyboard);
                return true;
            case VirtualKey.Right:
                if (band < BandCount - 1) _thumbs[band + 1].Focus(FocusState.Keyboard);
                return true;
            default:
                return false;
        }
        // A point clicked and then stepped from the keyboard shows its level too.
        _keyboardBand = band;
        UpdateHighlights();
        return true;
    }

    internal void ThumbGotFocus(EqualizerBandThumb thumb)
    {
        _lastFocusedBand = thumb.Band;
        if (thumb.FocusState == FocusState.Keyboard) _keyboardBand = thumb.Band;
        UpdateHighlights();
    }

    internal void ThumbLostFocus(EqualizerBandThumb thumb)
    {
        if (_keyboardBand == thumb.Band) _keyboardBand = null;
        UpdateHighlights();
    }

    /// <summary>Tabbing into the points lands on the band used last (60 Hz at first), from either direction.</summary>
    private void Graph_GettingFocus(UIElement sender, GettingFocusEventArgs args)
    {
        if (args.FocusState != FocusState.Keyboard) return;
        if (args.NewFocusedElement is not EqualizerBandThumb target || !ReferenceEquals(target.Graph, this)) return;
        if (args.OldFocusedElement is EqualizerBandThumb previous && ReferenceEquals(previous.Graph, this)) return;
        var remembered = _thumbs[_lastFocusedBand];
        if (!ReferenceEquals(target, remembered)) args.TrySetNewFocusedElement(remembered);
    }

    private void Graph_Unloaded(object sender, RoutedEventArgs e)
    {
        StopSweep();
        _levels.CopyTo(_shown, 0);
        _grab = null;
        _hoveredBand = null;
        _keyboardBand = null;
        UpdateHighlights();
        LayoutLevels();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private EqualizerPlot Plot => new(_canvas.ActualWidth, EqualizerPlot.GraphHeight, BandCount);

    private bool HasSize => _canvas.ActualWidth > 0;

    private void LayoutAll()
    {
        if (!HasSize) return;
        var plot = Plot;
        for (var band = 0; band < BandCount; band++)
        {
            var x = Crisp(plot.X(band));
            var column = _columns[band];
            column.X1 = x;
            column.X2 = x;
            column.Y1 = plot.Top;
            column.Y2 = plot.Bottom;
            _frequencyPlacements[band].MoveTo(plot.X(band), plot.LabelY);
            _thumbs[band].SetHitWidth(Math.Max(plot.ColumnWidth, EqualizerPlot.HitWidthMinimum));
        }
        foreach (var (level, rule, label) in _rules)
        {
            var y = Crisp(plot.Y(level));
            rule.X1 = plot.Left;
            rule.X2 = plot.Right;
            rule.Y1 = y;
            rule.Y2 = y;
            label.MoveTo(EqualizerPlot.AxisLabelX, plot.Y(level));
        }
        _unitPlacement.MoveTo(EqualizerPlot.AxisLabelX, plot.LabelY);
        LayoutLevels();
    }

    /// <summary>The curve, the points, and the badge, at the levels drawn.</summary>
    private void LayoutLevels()
    {
        if (!HasSize) return;
        var plot = Plot;
        if (plot.Curve(_shown) is { } curve) _curve.Data = Geometry(curve);
        for (var band = 0; band < BandCount; band++)
        {
            var box = plot.HitBox(band, _shown[band]);
            Canvas.SetLeft(_thumbs[band], box.X);
            Canvas.SetTop(_thumbs[band], box.Y);
        }
        PlaceBadge(plot);
    }

    private void PlaceBadge(EqualizerPlot plot)
    {
        if ((_grab?.Band ?? _keyboardBand) is not int band || !HasSize)
        {
            _badge.Visibility = Visibility.Collapsed;
            return;
        }
        _badgeText.Text = DecibelLabel.Text(_levels[band]);
        _badge.Visibility = Visibility.Visible;
        var center = plot.BadgeCenter(band, _shown[band]);
        _badgePlacement.MoveTo(center.X, center.Y);
    }

    /// <summary>The held, hovered, and keyboard-focused states of every point and label.</summary>
    private void UpdateHighlights()
    {
        var animate = IsLoaded && _uiSettings.AnimationsEnabled;
        var highlighted = _grab?.Band ?? _keyboardBand;
        for (var band = 0; band < BandCount; band++)
        {
            var held = _grab?.Band == band;
            _thumbs[band].ShowState(held, hovered: _grab is null && _hoveredBand == band, animate);
            _frequencyLabels[band].Foreground = highlighted == band ? _gold : _textSecondary;
        }
        PlaceBadge(Plot);
    }

    private void StartSweep()
    {
        _shown.CopyTo(_sweepFrom, 0);
        _sweepClock.Restart();
        if (_sweeping) return;
        _sweeping = true;
        CompositionTarget.Rendering += Sweep_Rendering;
    }

    private void StopSweep()
    {
        if (!_sweeping) return;
        _sweeping = false;
        CompositionTarget.Rendering -= Sweep_Rendering;
        _sweepClock.Reset();
    }

    /// <summary>
    /// One frame of a sweep, eased out cubically. Apple's spring settles past
    /// its target before it rests; easing out instead keeps every point
    /// within ±20 dB on the way.
    /// </summary>
    private void Sweep_Rendering(object? sender, object e)
    {
        var progress = Math.Min(_sweepClock.Elapsed / SweepDuration, 1);
        var eased = 1 - Math.Pow(1 - progress, 3);
        for (var band = 0; band < BandCount; band++)
        {
            _shown[band] = _sweepFrom[band] + (_levels[band] - _sweepFrom[band]) * eased;
        }
        LayoutLevels();
        if (progress >= 1) StopSweep();
    }

    private static PathGeometry Geometry(EqualizerCurve curve)
    {
        var figure = new PathFigure { StartPoint = At(curve.Start), IsClosed = false, IsFilled = false };
        figure.Segments.Add(new LineSegment { Point = At(curve.First) });
        foreach (var segment in curve.Segments)
        {
            figure.Segments.Add(new BezierSegment
            {
                Point1 = At(segment.Control1),
                Point2 = At(segment.Control2),
                Point3 = At(segment.End),
            });
        }
        figure.Segments.Add(new LineSegment { Point = At(curve.Finish) });
        var geometry = new PathGeometry();
        geometry.Figures.Add(figure);
        return geometry;
    }

    private static Point At(PlotPoint point) => new(point.X, point.Y);

    /// <summary>A 1 px rule centred on a pixel row, so it draws sharp rather than smeared across two.</summary>
    private static double Crisp(double value) => Math.Floor(value) + 0.5;

    private T Decoration<T>(T element) where T : FrameworkElement
    {
        element.IsHitTestVisible = false;
        AutomationProperties.SetAccessibilityView(element, AccessibilityView.Raw);
        _canvas.Children.Add(element);
        return element;
    }

    private TextBlock Label(string text, double size, FontWeight weight, bool tabular)
    {
        var label = Decoration(new TextBlock
        {
            Text = text,
            FontFamily = (FontFamily)Application.Current.Resources["TextFontFamily"],
            FontSize = size,
            FontWeight = weight,
            Foreground = _textSecondary,
            TextWrapping = TextWrapping.NoWrap,
        });
        if (tabular) Typography.SetNumeralAlignment(label, FontNumeralAlignment.Tabular);
        return label;
    }

    private static Brush Token(string key) => (Brush)Application.Current.Resources[key];

    /// <summary>Keeps an element centred on a point of the canvas, whatever size its text gives it.</summary>
    private sealed class Centered
    {
        private readonly FrameworkElement _element;
        private double _x;
        private double _y;

        public Centered(FrameworkElement element)
        {
            _element = element;
            element.SizeChanged += (_, _) => Place();
        }

        public void MoveTo(double x, double y)
        {
            _x = x;
            _y = y;
            Place();
        }

        private void Place()
        {
            Canvas.SetLeft(_element, _x - _element.ActualWidth / 2);
            Canvas.SetTop(_element, _y - _element.ActualHeight / 2);
        }
    }
}

/// <summary>
/// One band's point on the <see cref="EqualizerGraph"/>: a ring on a touch
/// target as tall as a fingertip and as wide as the band's column. Resting,
/// it is a 14 px ring; under a mouse or pen it grows to 17; held, it fills
/// with gold, grows to 22, and glows.
/// </summary>
public sealed partial class EqualizerBandThumb : UserControl
{
    private const double GlowDiameter = 42;
    private const double RestDiameter = 14;
    private const double HoverDiameter = 17;
    private const double HeldDiameter = 22;

    /// <summary>The square the focus rectangle hugs: the held ring plus 2 px all round.</summary>
    private const double FocusSize = HeldDiameter + 4;

    private static readonly Vector3 GlowRestScale = new(0.4f, 0.4f, 1);

    private readonly Canvas _surface;
    private readonly Ellipse _glow;
    private readonly Grid _pointHost;
    private readonly Ellipse _point;
    private readonly Brush _gold;
    private readonly Brush _surfaceHigh;
    private Storyboard? _resize;
    private bool _held;
    private bool _hovered;

    internal EqualizerBandThumb(EqualizerGraph graph, int band, string name)
    {
        Graph = graph;
        Band = band;
        IsTabStop = true;
        UseSystemFocusVisuals = true;
        Height = EqualizerPlot.HitHeight;
        // Not System: a touch drag that starts on a point moves the point
        // instead of scrolling the Settings page.
        ManipulationMode = ManipulationModes.TranslateY;
        ProtectedCursor = InputSystemCursor.Create(InputSystemCursorShape.SizeNorthSouth);
        AutomationProperties.SetName(this, name);
        AutomationProperties.SetPositionInSet(this, band + 1);
        AutomationProperties.SetSizeOfSet(this, AudioEnhancementProfiles.BandCount);

        _gold = (Brush)Application.Current.Resources["EdendaleGoldBrush"];
        _surfaceHigh = (Brush)Application.Current.Resources["EdendaleSurfaceHighBrush"];

        _glow = new Ellipse
        {
            Width = GlowDiameter,
            Height = GlowDiameter,
            Fill = (Brush)Application.Current.Resources["EdendaleGoldGlowBrush"],
            Opacity = 0,
            Scale = GlowRestScale,
            CenterPoint = new Vector3((float)(GlowDiameter / 2), (float)(GlowDiameter / 2), 0),
            IsHitTestVisible = false,
        };
        // Shapes draw their stroke inside their bounds, like SwiftUI's
        // strokeBorder; the host keeps the ring centred as it grows.
        _point = new Ellipse
        {
            Width = RestDiameter,
            Height = RestDiameter,
            Fill = _surfaceHigh,
            Stroke = _gold,
            StrokeThickness = 2,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _pointHost = new Grid { Width = HeldDiameter, Height = HeldDiameter, IsHitTestVisible = false };
        _pointHost.Children.Add(_point);

        // A transparent surface is the whole touch target; the canvas lets the
        // glow spread past a narrow column without being clipped.
        _surface = new Canvas
        {
            Height = EqualizerPlot.HitHeight,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
        };
        _surface.Children.Add(_glow);
        _surface.Children.Add(_pointHost);
        Content = _surface;
    }

    internal EqualizerGraph Graph { get; }

    /// <summary>The band, from 0 (60 Hz) to 9 (16 kHz).</summary>
    public int Band { get; }

    /// <summary>The band's level, as the host last set it.</summary>
    public double Level => Graph.LevelOf(Band);

    /// <summary>Sizes the touch target to the band's column and centres the point in it.</summary>
    internal void SetHitWidth(double width)
    {
        if (Width == width) return;
        Width = width;
        _surface.Width = width;
        var centerX = width / 2;
        var centerY = EqualizerPlot.HitHeight / 2;
        Canvas.SetLeft(_glow, centerX - GlowDiameter / 2);
        Canvas.SetTop(_glow, centerY - GlowDiameter / 2);
        Canvas.SetLeft(_pointHost, centerX - HeldDiameter / 2);
        Canvas.SetTop(_pointHost, centerY - HeldDiameter / 2);
        var insetX = (width - FocusSize) / 2;
        var insetY = (EqualizerPlot.HitHeight - FocusSize) / 2;
        FocusVisualMargin = new Thickness(insetX, insetY, insetX, insetY);
    }

    /// <summary>Shows the held and hover states, over 180 ms unless Windows animations are off.</summary>
    internal void ShowState(bool held, bool hovered, bool animate)
    {
        if (held == _held && hovered == _hovered) return;
        var heldChanged = held != _held;
        _held = held;
        _hovered = hovered;

        Resize(held ? HeldDiameter : hovered ? HoverDiameter : RestDiameter, animate);
        _point.Fill = held ? _gold : _surfaceHigh;
        if (!heldChanged) return;
        _glow.OpacityTransition = animate ? new ScalarTransition { Duration = EqualizerGraph.HighlightDuration } : null;
        _glow.ScaleTransition = animate ? new Vector3Transition { Duration = EqualizerGraph.HighlightDuration } : null;
        _glow.Opacity = held ? 1 : 0;
        _glow.Scale = held ? Vector3.One : GlowRestScale;
    }

    private void Resize(double diameter, bool animate)
    {
        var from = _point.ActualWidth > 0 ? _point.ActualWidth : _point.Width;
        _resize?.Stop();
        _resize = null;
        _point.Width = diameter;
        _point.Height = diameter;
        if (!animate || Math.Abs(from - diameter) < 0.01) return;

        // A dependent animation, since the ring's stroke must stay 2 px wide
        // at every size, which scaling it would not keep.
        var storyboard = new Storyboard { FillBehavior = FillBehavior.Stop };
        foreach (var property in new[] { nameof(Width), nameof(Height) })
        {
            var animation = new DoubleAnimation
            {
                From = from,
                To = diameter,
                Duration = new Duration(EqualizerGraph.HighlightDuration),
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
                EnableDependentAnimation = true,
            };
            Storyboard.SetTarget(animation, _point);
            Storyboard.SetTargetProperty(animation, property);
            storyboard.Children.Add(animation);
        }
        _resize = storyboard;
        storyboard.Begin();
    }

    protected override void OnPointerPressed(PointerRoutedEventArgs e)
    {
        base.OnPointerPressed(e);
        Graph.ThumbPressed(this, e);
    }

    protected override void OnPointerMoved(PointerRoutedEventArgs e)
    {
        base.OnPointerMoved(e);
        Graph.ThumbMoved(e);
    }

    protected override void OnPointerReleased(PointerRoutedEventArgs e)
    {
        base.OnPointerReleased(e);
        Graph.ThumbReleased(this, e);
    }

    protected override void OnPointerCanceled(PointerRoutedEventArgs e)
    {
        base.OnPointerCanceled(e);
        Graph.ThumbLostPointer(e);
    }

    protected override void OnPointerCaptureLost(PointerRoutedEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        Graph.ThumbLostPointer(e);
    }

    protected override void OnPointerEntered(PointerRoutedEventArgs e)
    {
        base.OnPointerEntered(e);
        Graph.ThumbHovered(this, e, inside: true);
    }

    protected override void OnPointerExited(PointerRoutedEventArgs e)
    {
        base.OnPointerExited(e);
        Graph.ThumbHovered(this, e, inside: false);
    }

    protected override void OnKeyDown(KeyRoutedEventArgs e)
    {
        if (Graph.ThumbKey(this, e.Key))
        {
            e.Handled = true;
            return;
        }
        base.OnKeyDown(e);
    }

    protected override void OnGotFocus(RoutedEventArgs e)
    {
        base.OnGotFocus(e);
        Graph.ThumbGotFocus(this);
    }

    protected override void OnLostFocus(RoutedEventArgs e)
    {
        base.OnLostFocus(e);
        Graph.ThumbLostFocus(this);
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new EqualizerBandAutomationPeer(this);
}

/// <summary>The graph as an "Equalizer" group whose children are its ten bands.</summary>
public sealed partial class EqualizerGraphAutomationPeer(EqualizerGraph owner) : FrameworkElementAutomationPeer(owner)
{
    protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Group;

    protected override string GetClassNameCore() => nameof(EqualizerGraph);
}

/// <summary>
/// One band as a vertical slider named by its frequency ("60 Hz", the name
/// Apple gives the point), whose RangeValue is the level in dB and whose
/// Value reads it the way Settings writes it ("+8 dB").
/// </summary>
public sealed partial class EqualizerBandAutomationPeer(EqualizerBandThumb owner)
    : FrameworkElementAutomationPeer(owner), IRangeValueProvider, IValueProvider
{
    private EqualizerBandThumb Thumb => (EqualizerBandThumb)Owner;

    protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Slider;

    protected override string GetClassNameCore() => "EqualizerBand";

    protected override AutomationOrientation GetOrientationCore() => AutomationOrientation.Vertical;

    protected override object? GetPatternCore(PatternInterface patternInterface) =>
        patternInterface is PatternInterface.RangeValue or PatternInterface.Value
            ? this
            : base.GetPatternCore(patternInterface);

    public bool IsReadOnly => false;

    public double Minimum => AudioEnhancementProfiles.MinimumAmplification;

    public double Maximum => AudioEnhancementProfiles.MaximumAmplification;

    public double SmallChange => 1;

    public double LargeChange => EqualizerGraph.LargeStep;

    public double Value => Thumb.Level;

    string IValueProvider.Value => DecibelLabel.Text(Thumb.Level);

    /// <summary>Rounds to a whole decibel and clamps; a non-finite value changes nothing.</summary>
    public void SetValue(double value) => Thumb.Graph.RequestLevel(Thumb.Band, value);

    /// <summary>Accepts "+5 dB", "−3 dB", "-3", or "5"; unreadable text changes nothing.</summary>
    public void SetValue(string value)
    {
        if (DecibelLabel.TryParse(value, out var level)) SetValue(level);
    }

    internal void RaiseLevelChanged(double oldLevel, double newLevel)
    {
        RaisePropertyChangedEvent(RangeValuePatternIdentifiers.ValueProperty, oldLevel, newLevel);
        RaisePropertyChangedEvent(
            ValuePatternIdentifiers.ValueProperty, DecibelLabel.Text(oldLevel), DecibelLabel.Text(newLevel));
    }
}
