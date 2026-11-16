using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace Edendale.Windows.Controls;

/// <summary>One choice in a <see cref="ChipGroup{T}"/>: what it stores, what it shows, and what a screen reader says.</summary>
public sealed record ChipOption<T>(T Value, object Content, string AccessibleName, string? ToolTip = null);

/// <summary>
/// A single-choice row of archive chips (the settings counterpart of Apple's
/// FilterChip rows): exactly one is checked, each is a tab stop, and screen
/// readers hear its name plus its position in the set. With
/// <c>radio</c> the chips are radio buttons, which a screen reader reads as
/// one choice among several, selected or not (the Downloaded section picker).
/// </summary>
public sealed class ChipGroup<T>
{
    private readonly List<(ToggleButton Chip, T Value)> _chips = [];
    private readonly Action<T> _select;
    private bool _updating;

    public ChipGroup(Panel host, IReadOnlyList<ChipOption<T>> options, Action<T> select, bool radio = false)
    {
        _select = select;
        host.Children.Clear();
        for (var index = 0; index < options.Count; index++)
        {
            var option = options[index];
            ToggleButton chip = radio ? new RadioButton() : new ToggleButton();
            chip.Style = (Style)Application.Current.Resources[radio ? "ArchiveRadioChipStyle" : "ArchiveChipStyle"];
            chip.Content = option.Content;
            chip.HorizontalAlignment = HorizontalAlignment.Stretch;
            chip.HorizontalContentAlignment = HorizontalAlignment.Center;
            AutomationProperties.SetName(chip, option.AccessibleName);
            AutomationProperties.SetPositionInSet(chip, index + 1);
            AutomationProperties.SetSizeOfSet(chip, options.Count);
            if (option.ToolTip is not null) ToolTipService.SetToolTip(chip, option.ToolTip);

            var value = option.Value;
            void Choose()
            {
                if (_updating) return;
                Select(value);
                _select(value);
            }
            // A radio chip is chosen when it becomes checked: by a click, Space,
            // or a screen reader's Select. A toggle chip on its click.
            if (radio) chip.Checked += (_, _) => Choose();
            else chip.Click += (_, _) => Choose();
            host.Children.Add(chip);
            _chips.Add((chip, value));
        }
    }

    /// <summary>Checks the chip for <paramref name="value"/> and unchecks the rest.</summary>
    public void Select(T value)
    {
        _updating = true;
        foreach (var (chip, chipValue) in _chips)
        {
            chip.IsChecked = EqualityComparer<T>.Default.Equals(chipValue, value);
        }
        _updating = false;
    }
}
