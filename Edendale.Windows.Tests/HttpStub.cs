using System.Net;
using System.Text;
using System.Text.Json;

namespace Edendale.Windows.Tests;

/// <summary>
/// An HttpMessageHandler that answers from a function and records every
/// request (Support/HTTPStub.swift). No network, and no real credentials.
/// </summary>
internal sealed class HttpStub : HttpMessageHandler
{
    public sealed record Recorded(string Method, Uri Uri, IReadOnlyDictionary<string, string> Headers, byte[] Body)
    {
        public string? Header(string name) => Headers.TryGetValue(name, out var value) ? value : null;

        public string? Query(string name) =>
            Edendale.Windows.Services.Remote.OAuthClient.ParseQuery(Uri.Query).TryGetValue(name, out var value) ? value : null;

        public Dictionary<string, string> Form => Edendale.Windows.Services.Remote.OAuthClient.ParseQuery(Encoding.UTF8.GetString(Body));

        public JsonElement Json => JsonDocument.Parse(Body).RootElement;
    }

    private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;
    private readonly List<Recorded> _requests = [];

    public HttpStub(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

    public HttpClient Client => new(this, disposeHandler: false);

    public IReadOnlyList<Recorded> Requests
    {
        get { lock (_requests) return [.. _requests]; }
    }

    public IReadOnlyList<string> Ranges => Requests.Select(request => request.Header("Range")).OfType<string>().ToList();

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var body = request.Content is null ? [] : await request.Content.ReadAsByteArrayAsync(cancellationToken);
        var headers = request.Headers.Concat(request.Content?.Headers.AsEnumerable() ?? [])
            .ToDictionary(header => header.Key, header => string.Join(", ", header.Value), StringComparer.OrdinalIgnoreCase);
        lock (_requests) _requests.Add(new Recorded(request.Method.Method, request.RequestUri!, headers, body));
        var response = await Task.Run(() => _respond(request), cancellationToken);
        response.RequestMessage = request;
        return response;
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    /// <summary>Serves <paramref name="data"/> with Range support, like a file server.</summary>
    public static HttpResponseMessage File(byte[] data, HttpRequestMessage request)
    {
        if (request.Headers.Range?.Ranges.FirstOrDefault() is not { } range)
        {
            return Bytes(HttpStatusCode.OK, data);
        }
        var start = range.From ?? 0;
        if (start >= data.Length)
        {
            var empty = new HttpResponseMessage(HttpStatusCode.RequestedRangeNotSatisfiable) { Content = new ByteArrayContent([]) };
            empty.Content.Headers.TryAddWithoutValidation("Content-Range", $"bytes */{data.Length}");
            return empty;
        }
        var end = Math.Min(range.To ?? data.Length - 1, data.Length - 1);
        var slice = data[(int)start..((int)end + 1)];
        var response = Bytes(HttpStatusCode.PartialContent, slice);
        response.Content.Headers.TryAddWithoutValidation("Content-Range", $"bytes {start}-{end}/{data.Length}");
        return response;
    }

    public static HttpResponseMessage Bytes(HttpStatusCode status, byte[] data) => new(status) { Content = new ByteArrayContent(data) };

    public static HttpResponseMessage Text(string text, int status, params (string Name, string Value)[] headers)
    {
        var response = new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(text) };
        foreach (var (name, value) in headers) response.Headers.TryAddWithoutValidation(name, value);
        return response;
    }

    public static HttpResponseMessage Json(object value, int status = 200) =>
        new((HttpStatusCode)status)
        {
            Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json"),
        };

    public static byte[] TestData(int count) =>
        Enumerable.Range(0, count).Select(index => unchecked((byte)((index * 31) ^ (index >> 7)))).ToArray();
}
