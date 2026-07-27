using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Controls;

public sealed partial class SectionHeader : UserControl
{
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(
        nameof(Title), typeof(string), typeof(SectionHeader),
        new PropertyMetadata("", (d, _) =>
        {
            var header = (SectionHeader)d;
            header.TitleText.Text = header.Title.ToUpperInvariant();
        }));

    /// <summary>
    /// Resource key for <see cref="Title"/>. <c>x:Uid</c> cannot reach a custom
    /// dependency property, so headers name their catalogue entry instead and
    /// the control resolves it — the copy stays in Resources.resw with
    /// everything else, in natural case, and <see cref="Title"/> uppercases it
    /// the way Apple's <c>.textCase(.uppercase)</c> does.
    /// </summary>
    public static readonly DependencyProperty TitleKeyProperty = DependencyProperty.Register(
        nameof(TitleKey), typeof(string), typeof(SectionHeader),
        new PropertyMetadata("", (d, e) =>
        {
            if (e.NewValue is string key && !string.IsNullOrEmpty(key))
            {
                ((SectionHeader)d).Title = Loc.Get(key);
            }
        }));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }

    public string TitleKey { get => (string)GetValue(TitleKeyProperty); set => SetValue(TitleKeyProperty, value); }

    public SectionHeader() => InitializeComponent();
}
