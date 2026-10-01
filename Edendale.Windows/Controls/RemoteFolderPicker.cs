using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>The folder a remote source imports, and how its row reads.</summary>
internal sealed record RemoteFolderChoice(string Url, string Name, string DisplayPath);

/// <summary>
/// Browses a connected source and picks the folder to import
/// (NetworkFolderPickerView.swift): folders open on click, Up goes back, and
/// the trail reads like "OneDrive › Films". Videos in the open folder are
/// counted so the reader knows they picked the right one.
/// </summary>
internal sealed class RemoteFolderPicker
{
    private readonly IMediaConnector _connector;
    private readonly List<(string Url, string Name)> _trail = [];
    private readonly ContentDialog _dialog;
    private readonly TextBlock _path;
    private readonly TextBlock _summary;
    private readonly StackPanel _list = new() { Spacing = 2 };
    private readonly ProgressRing _progress = new() { IsActive = false, Width = 18, Height = 18, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly Button _up;
    private CancellationTokenSource? _loading;
    private RemoteFolderChoice? _choice;

    private RemoteFolderPicker(XamlRoot root, IMediaConnector connector)
    {
        _connector = connector;
        _trail.Add((connector.Root, RootName(connector)));
        var resources = Application.Current.Resources;

        _path = new TextBlock { Style = (Style)resources["TitleLGTextStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetHeadingLevel(_path, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _summary = new TextBlock { Style = (Style)resources["BodySMTextStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetLiveSetting(_summary, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        _up = new Button { Style = (Style)resources["ArchiveGhostButtonStyle"], Content = Loc.Get("FolderPicker_Up") };
        _up.Click += (_, _) =>
        {
            if (_trail.Count <= 1) return;
            _trail.RemoveAt(_trail.Count - 1);
            _ = LoadAsync();
        };

        var header = new Grid { ColumnSpacing = 12 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(_path);
        Grid.SetColumn(_up, 1);
        header.Children.Add(_up);

        var content = new StackPanel { Spacing = 12, MinWidth = 460, MaxWidth = 560 };
        content.Children.Add(header);
        content.Children.Add(_progress);
        content.Children.Add(new ScrollViewer { Content = _list, MaxHeight = 360, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        content.Children.Add(_summary);

        _dialog = new ContentDialog
        {
            Title = Loc.Get("FolderPicker_Title"),
            Content = content,
            PrimaryButtonText = Loc.Get("FolderPicker_Add"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = root,
        };
        _dialog.PrimaryButtonClick += (_, _) =>
        {
            _choice = new RemoteFolderChoice(_trail[^1].Url, ChoiceName, DisplayPath);
        };
        _dialog.Closing += (_, _) => _loading?.Cancel();
        _dialog.Opened += (_, _) => _ = LoadAsync();
    }

    public static async Task<RemoteFolderChoice?> ShowAsync(XamlRoot root, IMediaConnector connector)
    {
        var picker = new RemoteFolderPicker(root, connector);
        await picker._dialog.ShowAsync();
        return picker._choice;
    }

    /// <summary>"OneDrive › Films › Action": the provider's root, then each folder opened.</summary>
    private string DisplayPath => string.Join(" › ", _trail.Select(step => step.Name));

    /// <summary>The root as the trail starts: the provider, the bucket, or the server and its path.</summary>
    private static string RootName(IMediaConnector connector) => connector.Kind switch
    {
        MediaSourceKind.OneDrive or MediaSourceKind.Dropbox => connector.Kind.DisplayName(),
        MediaSourceKind.S3 => connector.AccountLabel ?? connector.Kind.DisplayName(),
        _ => string.Join(" › ", new[] { SourceUrl.CredentialHost(connector.Root) ?? connector.Kind.DisplayName() }
            .Concat(SourceUrl.PathSegments(connector.Root))),
    };

    /// <summary>The source's own name: the folder picked, or the root's last part.</summary>
    private string ChoiceName => _trail.Count > 1
        ? _trail[^1].Name
        : SourceUrl.PathSegments(_connector.Root).LastOrDefault() ?? _trail[0].Name;

    private async Task LoadAsync()
    {
        _loading?.Cancel();
        var loading = _loading = new CancellationTokenSource();
        var (url, _) = _trail[^1];
        _path.Text = DisplayPath;
        _up.IsEnabled = _trail.Count > 1;
        _list.Children.Clear();
        _progress.IsActive = true;
        _summary.Text = "";
        _dialog.IsPrimaryButtonEnabled = false;
        try
        {
            var entries = await _connector.ListAsync(url, loading.Token);
            if (loading.IsCancellationRequested) return;
            foreach (var folder in entries.Where(entry => entry.IsDirectory))
            {
                _list.Children.Add(FolderRow(folder));
            }
            var videos = entries.Count(entry => entry.IsVideo);
            _summary.Text = Loc.Plural("FolderPicker_VideosOne", "FolderPicker_VideosOther", videos);
            _dialog.IsPrimaryButtonEnabled = _connector.CanIndex(url);
        }
        catch (OperationCanceledException)
        {
            // A newer listing replaced this one.
        }
        catch (Exception failure)
        {
            if (!loading.IsCancellationRequested) _summary.Text = failure.Message;
        }
        finally
        {
            if (ReferenceEquals(loading, _loading)) _progress.IsActive = false;
        }
    }

    private Button FolderRow(ConnectorEntry folder)
    {
        var resources = Application.Current.Resources;
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Padding = new Thickness(8, 6, 8, 6) };
        row.Children.Add(new SvgIcon
        {
            UriSource = new Uri("ms-appx:///Assets/Icons/folder-closed.svg"),
            Width = 16,
            Height = 16,
            Foreground = (Brush)resources["EdendaleTextSecondaryBrush"],
            VerticalAlignment = VerticalAlignment.Center,
        });
        row.Children.Add(new TextBlock { Text = folder.Name, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis });
        var button = new Button
        {
            Content = row,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
        };
        AutomationProperties.SetName(button, folder.Name);
        button.Click += (_, _) =>
        {
            _trail.Add((folder.Url, folder.Name));
            _ = LoadAsync();
        };
        return button;
    }
}
