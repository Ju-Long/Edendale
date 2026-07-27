using System.Globalization;
using Microsoft.Windows.ApplicationModel.Resources;

namespace Edendale.Windows.Services;

/// <summary>
/// Localized strings for code-behind. XAML pulls its own copy through
/// <c>x:Uid</c>, which MRT Core resolves against the same
/// <c>Strings/&lt;language&gt;/Resources.resw</c> files; this is the entry point
/// for everything built at runtime — status lines, dialog copy, error text.
///
/// The app is unpackaged (<c>WindowsPackageType=None</c>), so this uses the
/// Windows App SDK loader rather than the WinRT
/// <c>Windows.ApplicationModel.Resources</c> one, which needs a package identity.
/// </summary>
internal static class Loc
{
    private static readonly ResourceLoader Loader = new();

    /// <summary>The string for <paramref name="key"/>, or the key itself when it is missing.</summary>
    public static string Get(string key)
    {
        try
        {
            var value = Loader.GetString(key);
            return string.IsNullOrEmpty(value) ? key : value;
        }
        catch (Exception)
        {
            // A missing resource must never take the window down — showing the
            // key is a visible but harmless failure.
            return key;
        }
    }

    /// <summary>
    /// <see cref="Get"/> with <see cref="string.Format(IFormatProvider, string, object[])"/>
    /// applied, in the current UI culture so numbers and dates match the copy.
    /// </summary>
    public static string Format(string key, params object?[] args) =>
        string.Format(CultureInfo.CurrentUICulture, Get(key), args);

    /// <summary>Pick singular or plural copy — English rules, matching the Android plurals.</summary>
    public static string Plural(string singularKey, string pluralKey, int count) =>
        Format(count == 1 ? singularKey : pluralKey, count);
}
