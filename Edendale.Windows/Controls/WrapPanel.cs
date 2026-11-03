using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace Edendale.Windows.Controls;

/// <summary>
/// Lays its children out left to right and starts a new line when the next
/// one would not fit: the FlowLayout Apple's SettingsActions use on macOS, so
/// a row of actions wraps instead of clipping in a narrow window or a long
/// locale. A child wider than a whole line is measured at the line's width,
/// so a label that can wrap does.
/// </summary>
public sealed partial class WrapPanel : Panel
{
    public static readonly DependencyProperty SpacingProperty = DependencyProperty.Register(
        nameof(Spacing), typeof(double), typeof(WrapPanel),
        new PropertyMetadata(0d, (d, _) => ((WrapPanel)d).InvalidateMeasure()));

    public static readonly DependencyProperty LineSpacingProperty = DependencyProperty.Register(
        nameof(LineSpacing), typeof(double), typeof(WrapPanel),
        new PropertyMetadata(0d, (d, _) => ((WrapPanel)d).InvalidateMeasure()));

    /// <summary>The gap between neighbours on a line.</summary>
    public double Spacing { get => (double)GetValue(SpacingProperty); set => SetValue(SpacingProperty, value); }

    /// <summary>The gap between lines.</summary>
    public double LineSpacing { get => (double)GetValue(LineSpacingProperty); set => SetValue(LineSpacingProperty, value); }

    protected override Size MeasureOverride(Size availableSize)
    {
        foreach (var child in Children) child.Measure(new Size(availableSize.Width, double.PositiveInfinity));
        return Flow(availableSize.Width, arrange: false);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        Flow(finalSize.Width, arrange: true);
        return finalSize;
    }

    /// <summary>Places the children in order, wrapping at <paramref name="width"/>; returns the size the lines take.</summary>
    private Size Flow(double width, bool arrange)
    {
        double x = 0, y = 0, lineHeight = 0, widest = 0;
        var lineEmpty = true;
        foreach (var child in Children)
        {
            if (child.Visibility == Visibility.Collapsed) continue;
            var size = child.DesiredSize;
            if (!lineEmpty && x + Spacing + size.Width > width)
            {
                y += lineHeight + LineSpacing;
                x = 0;
                lineHeight = 0;
                lineEmpty = true;
            }
            if (!lineEmpty) x += Spacing;
            if (arrange) child.Arrange(new Rect(x, y, size.Width, size.Height));
            x += size.Width;
            widest = Math.Max(widest, x);
            lineHeight = Math.Max(lineHeight, size.Height);
            lineEmpty = false;
        }
        return new Size(widest, y + lineHeight);
    }
}
