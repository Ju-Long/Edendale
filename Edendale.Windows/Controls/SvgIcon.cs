// WinUI 3's BitmapIcon only decodes raster formats, so the Font Awesome
// SVG assets rendered as empty squares. This control reads the SVG's path
// data directly and renders it as vector Paths tinted by Foreground — the
// monochrome behaviour BitmapIcon was being asked for. Handled markup, per
// the assets actually in Assets/Icons: one or more <path d="..."/> (with
// optional duotone opacity) inside a single viewBox, optionally wrapped in
// one translating <g transform="matrix(1,0,0,1,x,y)"> group (Serif exports).

using System.Collections.Concurrent;
using System.Globalization;
using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

public sealed partial class SvgIcon : UserControl
{
    private sealed record SvgPath(string Data, double Opacity);

    private sealed record SvgDocument(
        double Width,
        double Height,
        double OffsetX,
        double OffsetY,
        List<SvgPath> Paths);

    private static readonly ConcurrentDictionary<string, SvgDocument?> Cache = new();

    public static readonly DependencyProperty UriSourceProperty = DependencyProperty.Register(
        nameof(UriSource), typeof(Uri), typeof(SvgIcon),
        new PropertyMetadata(null, (d, _) => ((SvgIcon)d).Rebuild()));

    /// <summary>ms-appx:///Assets/Icons/*.svg — same shape BitmapIcon took.</summary>
    public Uri? UriSource { get => (Uri?)GetValue(UriSourceProperty); set => SetValue(UriSourceProperty, value); }

    private readonly Viewbox _viewbox = new() { Stretch = Stretch.Uniform };

    public SvgIcon()
    {
        IsTabStop = false;
        Content = _viewbox;
    }

    private void Rebuild()
    {
        _viewbox.Child = null;
        if (UriSource is not { } source || Parse(source.ToString()) is not { } svg) return;

        var canvas = new Canvas { Width = svg.Width, Height = svg.Height };
        foreach (var entry in svg.Paths)
        {
            var path = LoadPath(entry.Data);
            if (path is null) continue;
            path.Opacity = entry.Opacity;
            if (svg.OffsetX != 0 || svg.OffsetY != 0)
            {
                path.RenderTransform = new TranslateTransform { X = svg.OffsetX, Y = svg.OffsetY };
            }
            // Follow this control's (inherited) Foreground, like BitmapIcon did.
            path.SetBinding(Microsoft.UI.Xaml.Shapes.Path.FillProperty, new Binding
            {
                Source = this,
                Path = new PropertyPath(nameof(Foreground)),
            });
            canvas.Children.Add(path);
        }
        _viewbox.Child = canvas;
    }

    /// <summary>An IconElement for slots that require one (NavigationViewItem.Icon).</summary>
    public static IconElement CreateIcon(string assetName, double size = 16)
    {
        var icon = new PathIcon();
        if (Parse($"ms-appx:///Assets/Icons/{assetName}.svg") is not { } svg) return icon;
        try
        {
            var merged = string.Join(' ', svg.Paths.Select(path => path.Data.Substring(3)));
            var loaded = (PathIcon)XamlReader.Load(
                "<PathIcon xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' " +
                $"Data=\"F1 {merged}\"/>");
            // PathIcon renders the geometry at its native coordinates, so the
            // 640-unit Font Awesome grid must be moved and scaled to the slot.
            var scale = size / Math.Max(svg.Width, svg.Height);
            loaded.Data.Transform = new TransformGroup
            {
                Children =
                {
                    new TranslateTransform { X = svg.OffsetX, Y = svg.OffsetY },
                    new ScaleTransform { ScaleX = scale, ScaleY = scale },
                },
            };
            return loaded;
        }
        catch (Exception)
        {
            return icon;
        }
    }

    private static Microsoft.UI.Xaml.Shapes.Path? LoadPath(string data)
    {
        try
        {
            return (Microsoft.UI.Xaml.Shapes.Path)XamlReader.Load(
                "<Path xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' " +
                $"Data=\"{data}\"/>");
        }
        catch (Exception)
        {
            return null; // Malformed asset — render nothing rather than crash.
        }
    }

    private static SvgDocument? Parse(string uri) =>
        Cache.GetOrAdd(uri, static key =>
        {
            try
            {
                // Unpackaged app: ms-appx resolves to files beside the exe.
                var relative = new Uri(key).AbsolutePath.TrimStart('/')
                    .Replace('/', System.IO.Path.DirectorySeparatorChar);
                var markup = File.ReadAllText(System.IO.Path.Combine(AppContext.BaseDirectory, relative));

                var viewBox = Regex.Match(markup, "viewBox=\"([^\"]+)\"");
                if (!viewBox.Success) return null;
                var bounds = viewBox.Groups[1].Value
                    .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                if (bounds.Length != 4
                    || !TryNumber(bounds[0], out var minX)
                    || !TryNumber(bounds[1], out var minY)
                    || !TryNumber(bounds[2], out var width)
                    || !TryNumber(bounds[3], out var height)
                    || width <= 0 || height <= 0)
                {
                    return null;
                }

                // Single translating group (Serif exports wrap all paths in one).
                var offsetX = -minX;
                var offsetY = -minY;
                var transform = Regex.Match(
                    markup,
                    @"transform=""(?:matrix\(1,\s*0,\s*0,\s*1,\s*([-0-9.]+),\s*([-0-9.]+)\)|translate\(([-0-9.]+)[,\s]+([-0-9.]+)\))""");
                if (transform.Success)
                {
                    var x = transform.Groups[1].Success ? transform.Groups[1].Value : transform.Groups[3].Value;
                    var y = transform.Groups[2].Success ? transform.Groups[2].Value : transform.Groups[4].Value;
                    if (TryNumber(x, out var tx) && TryNumber(y, out var ty))
                    {
                        offsetX += tx;
                        offsetY += ty;
                    }
                }

                var paths = new List<SvgPath>();
                foreach (Match tag in Regex.Matches(markup, "<path\\b[^>]*>"))
                {
                    var data = Regex.Match(tag.Value, "\\sd=\"([^\"]+)\"");
                    if (!data.Success) continue;
                    var opacityMatch = Regex.Match(tag.Value, "opacity=\"([0-9.]+)\"");
                    var opacity = opacityMatch.Success && TryNumber(opacityMatch.Groups[1].Value, out var parsed)
                        ? Math.Clamp(parsed, 0, 1)
                        : 1;
                    // "F1" = nonzero fill, SVG's default (XAML defaults to even-odd).
                    paths.Add(new SvgPath("F1 " + data.Groups[1].Value, opacity));
                }
                return paths.Count == 0 ? null : new SvgDocument(width, height, offsetX, offsetY, paths);
            }
            catch (Exception)
            {
                return null;
            }
        });

    private static bool TryNumber(string value, out double result) =>
        double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out result);
}
