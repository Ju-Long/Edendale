using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>Managed HTTP boundary for TMDB v3.</summary>
internal sealed class TmdbClient
{
    private const string BaseAddress = "https://api.themoviedb.org/3";
    private static readonly HttpClient Http = CreateHttpClient();
    private readonly string _bearerToken;
    private readonly string _apiKey;

    public TmdbClient()
    {
        (_bearerToken, _apiKey) = TmdbCredentials.Load();
    }

    public bool IsConfigured =>
        !string.IsNullOrWhiteSpace(_bearerToken) || !string.IsNullOrWhiteSpace(_apiKey);

    public Task<JsonElement> GetAsync(
        string path,
        IReadOnlyDictionary<string, string>? parameters = null,
        CancellationToken cancellationToken = default) =>
        SendAsync(HttpMethod.Get, path, parameters, null, cancellationToken);

    public async Task<JsonElement> SendAsync(
        HttpMethod method,
        string path,
        IReadOnlyDictionary<string, string>? parameters = null,
        object? jsonBody = null,
        CancellationToken cancellationToken = default)
    {
        if (!IsConfigured)
        {
            throw new WindowsCoreException(
                AppText.Get("Tmdb_NotConfigured"));
        }

        var query = new List<string>();
        if (parameters is not null)
        {
            query.AddRange(parameters.Select(pair =>
                $"{Uri.EscapeDataString(pair.Key)}={Uri.EscapeDataString(pair.Value)}"));
        }
        if (string.IsNullOrWhiteSpace(_bearerToken) && !string.IsNullOrWhiteSpace(_apiKey))
        {
            query.Add($"api_key={Uri.EscapeDataString(_apiKey)}");
        }

        var target = $"{BaseAddress}{path}";
        if (query.Count > 0) target += $"?{string.Join("&", query)}";
        using var request = new HttpRequestMessage(method, target);
        if (!string.IsNullOrWhiteSpace(_bearerToken))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _bearerToken);
        }
        if (jsonBody is not null)
        {
            request.Content = new StringContent(
                JsonSerializer.Serialize(jsonBody),
                Encoding.UTF8,
                "application/json");
        }

        using var response = await Http.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var detail = TryReadStatusMessage(body);
            throw new WindowsCoreException(
                AppText.Format("Tmdb_RequestFailed", (int)response.StatusCode) +
                (detail is null ? "." : $": {detail}"));
        }

        try
        {
            using var document = JsonDocument.Parse(body);
            return document.RootElement.Clone();
        }
        catch (JsonException error)
        {
            throw new WindowsCoreException(AppText.Format("Tmdb_InvalidJson", error.Message));
        }
    }

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        client.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/json"));
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Edendale-Windows/1.0");
        return client;
    }

    private static string? TryReadStatusMessage(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return document.RootElement.String("status_message");
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

internal static class TmdbCredentials
{
    private const string ResourceName = "Edendale.LocalSecrets";

    public static (string BearerToken, string ApiKey) Load()
    {
        var environmentToken = Environment.GetEnvironmentVariable("TMDB_READ_ACCESS_TOKEN");
        var environmentKey = Environment.GetEnvironmentVariable("TMDB_API_KEY");
        if (!string.IsNullOrWhiteSpace(environmentToken) || !string.IsNullOrWhiteSpace(environmentKey))
        {
            return (CleanBearer(environmentToken), environmentKey?.Trim() ?? "");
        }

        try
        {
            var assembly = typeof(TmdbCredentials).Assembly;
            using var stream = assembly.GetManifestResourceStream(ResourceName);
            if (stream is null) return ("", "");
            using var document = JsonDocument.Parse(stream);
            var root = document.RootElement;
            return (
                CleanBearer(root.String("TMDB_READ_ACCESS_TOKEN")),
                root.String("TMDB_API_KEY")?.Trim() ?? "");
        }
        catch (JsonException)
        {
            return ("", "");
        }
    }

    private static string CleanBearer(string? token)
    {
        var value = token?.Trim() ?? "";
        return value.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            ? value[7..].Trim()
            : value;
    }
}

internal static class JsonElementExtensions
{
    public static JsonElement? Property(this JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value)
            ? value
            : null;

    public static string? String(this JsonElement element, string name)
    {
        var value = element.Property(name);
        return value is { ValueKind: JsonValueKind.String } ? value.Value.GetString() : null;
    }

    public static string? NonBlankString(this JsonElement element, string name) =>
        element.String(name) is { Length: > 0 } value ? value : null;

    public static int? Int(this JsonElement element, string name)
    {
        var value = element.Property(name);
        return value is { ValueKind: JsonValueKind.Number } && value.Value.TryGetInt32(out var result)
            ? result
            : null;
    }

    public static double? Double(this JsonElement element, string name)
    {
        var value = element.Property(name);
        return value is { ValueKind: JsonValueKind.Number } && value.Value.TryGetDouble(out var result)
            ? result
            : null;
    }

    public static bool? Bool(this JsonElement element, string name)
    {
        var value = element.Property(name);
        return value?.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null,
        };
    }

    public static IEnumerable<JsonElement> Array(this JsonElement element, string name)
    {
        var value = element.Property(name);
        return value is { ValueKind: JsonValueKind.Array }
            ? value.Value.EnumerateArray()
            : [];
    }
}
