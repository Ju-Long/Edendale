using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
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

    /// <summary>
    /// A panel given a name (the Downloaded section picker) is a named group
    /// to screen readers, so its chips are heard as one set; an unnamed one,
    /// like a row of Settings actions, stays out of the tree.
    /// </summary>
    protected override AutomationPeer? OnCreateAutomationPeer() =>
        string.IsNullOrEmpty(AutomationProperties.GetName(this)) ? base.OnCreateAutomationPeer() : new NamedGroupAutomationPeer(this);

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

    /// <summary>
    /// Places the children in order, wrapping at <paramref name="width"/>, each
    /// in the full height of its line so it keeps its own vertical alignment, as
    /// in a horizontal StackPanel; returns the size the lines take.
    /// </summary>
    private Size Flow(double width, bool arrange)
    {
        var children = Children;
        double y = 0, widest = 0;
        var lines = 0;
        var next = 0;
        while (next < children.Count)
        {
            // The line is children[start..next), collapsed ones skipped.
            var start = next;
            double lineWidth = 0, lineHeight = 0;
            var placed = 0;
            for (; next < children.Count; next++)
            {
                var child = children[next];
                if (child.Visibility == Visibility.Collapsed) continue;
                var size = child.DesiredSize;
                var extent = placed == 0 ? size.Width : lineWidth + Spacing + size.Width;
                if (placed > 0 && extent > width) break;
                lineWidth = extent;
                lineHeight = Math.Max(lineHeight, size.Height);
                placed++;
            }
            if (placed == 0) break;
            if (lines++ > 0) y += LineSpacing;
            if (arrange)
            {
                double x = 0;
                for (var index = start; index < next; index++)
                {
                    var child = children[index];
                    if (child.Visibility == Visibility.Collapsed) continue;
                    child.Arrange(new Rect(x, y, child.DesiredSize.Width, lineHeight));
                    x += child.DesiredSize.Width + Spacing;
                }
            }
            widest = Math.Max(widest, lineWidth);
            y += lineHeight;
        }
        return new Size(widest, y);
    }
}

/// <summary>A panel as a named group whose children are its controls.</summary>
public sealed partial class NamedGroupAutomationPeer(WrapPanel owner) : FrameworkElementAutomationPeer(owner)
{
    protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Group;

    protected override bool IsControlElementCore() => true;

    protected override string GetClassNameCore() => nameof(WrapPanel);
}
