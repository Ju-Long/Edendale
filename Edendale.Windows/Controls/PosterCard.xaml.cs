using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Edendale.Windows.Controls;

public sealed partial class PosterCard : UserControl
{
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(
        nameof(Title), typeof(string), typeof(PosterCard),
        new PropertyMetadata("", (d, _) => ((PosterCard)d).Apply()));

    public static readonly DependencyProperty SubtitleProperty = DependencyProperty.Register(
        nameof(Subtitle), typeof(string), typeof(PosterCard),
        new PropertyMetadata(null, (d, _) => ((PosterCard)d).Apply()));

    public static readonly DependencyProperty ImageUrlProperty = DependencyProperty.Register(
        nameof(ImageUrl), typeof(string), typeof(PosterCard),
        new PropertyMetadata(null, (d, _) => ((PosterCard)d).ApplyImage()));

    public static readonly DependencyProperty CardWidthProperty = DependencyProperty.Register(
        nameof(CardWidth), typeof(double), typeof(PosterCard),
        new PropertyMetadata(180d, (d, _) => ((PosterCard)d).Apply()));

    public static readonly DependencyProperty ProgressProperty = DependencyProperty.Register(
        nameof(Progress), typeof(double), typeof(PosterCard),
        new PropertyMetadata(0d, (d, _) => ((PosterCard)d).Apply()));

    public static readonly DependencyProperty IsWatchedProperty = DependencyProperty.Register(
        nameof(IsWatched), typeof(bool), typeof(PosterCard),
        new PropertyMetadata(false, (d, _) => ((PosterCard)d).Apply()));

    public static readonly DependencyProperty PlaceholderAssetProperty = DependencyProperty.Register(
        nameof(PlaceholderAsset), typeof(string), typeof(PosterCard),
        new PropertyMetadata("ms-appx:///Assets/Icons/film.svg", (d, _) => ((PosterCard)d).Apply()));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string? Subtitle { get => (string?)GetValue(SubtitleProperty); set => SetValue(SubtitleProperty, value); }
    public string? ImageUrl { get => (string?)GetValue(ImageUrlProperty); set => SetValue(ImageUrlProperty, value); }
    public double CardWidth { get => (double)GetValue(CardWidthProperty); set => SetValue(CardWidthProperty, value); }
    /// <summary>Watch progress in [0, 1]; 0 hides the gold hairline.</summary>
    public double Progress { get => (double)GetValue(ProgressProperty); set => SetValue(ProgressProperty, value); }
    public bool IsWatched { get => (bool)GetValue(IsWatchedProperty); set => SetValue(IsWatchedProperty, value); }
    /// <summary>SVG asset shown while there is no artwork (film/tv).</summary>
    public string PlaceholderAsset { get => (string)GetValue(PlaceholderAssetProperty); set => SetValue(PlaceholderAssetProperty, value); }

    public PosterCard()
    {
        InitializeComponent();
        ScaleTransition = new Vector3Transition { Duration = TimeSpan.FromMilliseconds(180) };
        PointerEntered += (_, _) => SetHover(true);
        PointerExited += (_, _) => SetHover(false);
        Apply();
    }

    private void SetHover(bool hovering)
    {
        CenterPoint = new Vector3((float)(CardWidth / 2), (float)(CardWidth * 0.75), 0);
        Scale = hovering ? new Vector3(1.05f) : Vector3.One;
        PosterHost.BorderBrush = hovering
            ? (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleOutlineBrightBrush"]
            : (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleHairlineBorderBrush"];
    }

    private void Apply()
    {
        Root.Width = CardWidth;
        PosterHost.Width = CardWidth;
        PosterHost.Height = CardWidth * 1.5;
        var size = Math.Max(CardWidth * 0.28, 20);
        PlaceholderIcon.Width = size;
        PlaceholderIcon.Height = size;
        PlaceholderIcon.UriSource = new Uri(PlaceholderAsset);
        TitleText.Text = Title;
        SubtitleText.Text = Subtitle ?? "";
        SubtitleText.Visibility = string.IsNullOrEmpty(Subtitle) ? Visibility.Collapsed : Visibility.Visible;
        WatchedBadge.Visibility = IsWatched ? Visibility.Visible : Visibility.Collapsed;
        var clamped = Math.Clamp(Progress, 0, 1);
        ProgressHairline.Visibility = clamped > 0 ? Visibility.Visible : Visibility.Collapsed;
        ProgressHairline.Width = CardWidth * clamped;
    }

    private void ApplyImage()
    {
        PosterImage.Source = Uri.TryCreate(ImageUrl, UriKind.Absolute, out var uri)
            ? new BitmapImage(uri)
            : null;
    }
}
