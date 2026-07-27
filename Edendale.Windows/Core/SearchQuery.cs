namespace Edendale.Windows.Core;

internal sealed record SearchQuery(string Scope, string Term)
{
    private static readonly IReadOnlyDictionary<string, string> Scopes =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["actor"] = "people",
            ["actors"] = "people",
            ["actress"] = "people",
            ["actresses"] = "people",
            ["person"] = "people",
            ["people"] = "people",
            ["cast"] = "people",
            ["movie"] = "movies",
            ["movies"] = "movies",
            ["film"] = "movies",
            ["films"] = "movies",
            ["show"] = "shows",
            ["shows"] = "shows",
            ["tv"] = "shows",
            ["series"] = "shows",
        };

    public static SearchQuery Parse(string raw)
    {
        var colon = raw.IndexOf(':');
        if (colon < 0) return new SearchQuery("all", raw.Trim());
        var keyword = raw[..colon].Trim();
        return Scopes.TryGetValue(keyword, out var scope)
            ? new SearchQuery(scope, raw[(colon + 1)..].Trim())
            : new SearchQuery("all", raw.Trim());
    }
}
