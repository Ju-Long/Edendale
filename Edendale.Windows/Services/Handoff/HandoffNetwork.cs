// The handoff's network side on Windows (SYNC-PROTOCOL.md §9.1): the host
// listens on a free port and advertises _edendale-handoff._tcp with DNS-SD
// (TXT v=2, name, platform) only while the pairing screen is open (H13); the
// guest browses for hosts, or connects to the address a QR code or the host's
// screen gives. Windows' own DNS-SD (Windows.Networking.ServiceDiscovery.Dnssd
// and the AssociationEndpointService device watcher) does the advertising and
// browsing; the protocol itself runs over a plain duplex stream
// (Core/Handoff/HandoffSession). Nothing here logs an address with a secret:
// the only secrets travel inside the QR code and the code on screen.

using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Core.Sync;
using Windows.Devices.Enumeration;
using Windows.Networking;
using Windows.Networking.ServiceDiscovery.Dnssd;
using Windows.Networking.Sockets;

namespace Edendale.Windows.Services.Handoff;

/// <summary>A host found on the network.</summary>
public sealed record HandoffPeer(string Id, string Name, string Address, int Port, string? Platform);

/// <summary>
/// The host's listener: one connection at a time; a second one while a
/// handoff runs is answered `busy` (§9.1). Disposing stops the
/// advertisement and closes the port.
/// </summary>
public sealed class HandoffHostListener : IDisposable
{
    public const string ServiceType = "_edendale-handoff._tcp";

    private readonly StreamSocketListener _listener = new();
    private readonly Func<Stream, Task> _serve;
    private DnssdServiceInstance? _service;
    private int _busy;
    private bool _disposed;

    /// <param name="serve">Runs one handoff over the connection; the stream closes when it returns.</param>
    public HandoffHostListener(Func<Stream, Task> serve)
    {
        _serve = serve;
        _listener.ConnectionReceived += OnConnection;
    }

    public int Port { get; private set; }

    /// <summary>Whether DNS-SD registration worked; without it the guest needs the QR code or the address.</summary>
    public bool IsAdvertised { get; private set; }

    public async Task StartAsync(string deviceName)
    {
        await _listener.BindServiceNameAsync("");
        Port = int.Parse(_listener.Information.LocalPort, System.Globalization.CultureInfo.InvariantCulture);
        try
        {
            var hostName = new HostName(Dns.GetHostName() + ".local");
            _service = new DnssdServiceInstance($"{InstanceLabel(deviceName)}.{ServiceType}.local", hostName, (ushort)Port);
            _service.TextAttributes["v"] = HandoffFrames.Version.ToString(System.Globalization.CultureInfo.InvariantCulture);
            _service.TextAttributes["name"] = deviceName;
            _service.TextAttributes["platform"] = SyncFormat.WindowsPlatform;
            var registration = await _service.RegisterStreamSocketListenerAsync(_listener);
            IsAdvertised = registration.Status == DnssdRegistrationStatus.Success;
        }
        catch (Exception error)
        {
            // Discovery is a convenience: the QR code and the address still work.
            IsAdvertised = false;
            SyncLog.Error("handoff", "advertise", error);
        }
        SyncLog.Info("handoff", $"listening, advertised={IsAdvertised}");
    }

    /// <summary>A DNS label: at most 63 bytes, no dots.</summary>
    internal static string InstanceLabel(string name)
    {
        var label = name.Replace('.', ' ').Trim();
        if (label.Length == 0) label = "Edendale";
        while (Encoding.UTF8.GetByteCount(label) > 63) label = label[..^1];
        return label;
    }

    private async void OnConnection(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
    {
        using var stream = new SocketStream(args.Socket);
        if (Interlocked.Exchange(ref _busy, 1) == 1)
        {
            try
            {
                await HandoffFrames.WriteAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Busy), CancellationToken.None);
            }
            catch (Exception)
            {
                // The other device closes either way.
            }
            return;
        }
        try
        {
            await _serve(stream);
        }
        catch (Exception error)
        {
            SyncLog.Error("handoff", "host connection", error);
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _listener.ConnectionReceived -= OnConnection;
        _listener.Dispose();
        _service = null;
        SyncLog.Info("handoff", "stopped listening");
    }
}

/// <summary>Browses for hosts while the guest's screen is open.</summary>
public sealed class HandoffBrowser : IDisposable
{
    private const string DnssdProtocol = "{4526e8c1-8aac-4153-9b16-55e86ada0e54}";
    private static readonly string[] Properties =
    [
        "System.Devices.Dnssd.HostName",
        "System.Devices.Dnssd.InstanceName",
        "System.Devices.IpAddress",
        "System.Devices.Dnssd.PortNumber",
        "System.Devices.Dnssd.TextAttributes",
    ];

    private readonly DeviceWatcher _watcher;
    private readonly object _gate = new();
    private readonly Dictionary<string, HandoffPeer> _peers = new(StringComparer.Ordinal);

    public HandoffBrowser()
    {
        var aqs = $"System.Devices.AepService.ProtocolId:={DnssdProtocol} AND System.Devices.Dnssd.Domain:=\"local\" AND System.Devices.Dnssd.ServiceName:=\"{HandoffHostListener.ServiceType}\"";
        _watcher = DeviceInformation.CreateWatcher(aqs, Properties, DeviceInformationKind.AssociationEndpointService);
        _watcher.Added += (_, info) => Update(info.Id, info.Properties);
        _watcher.Updated += (_, update) => Update(update.Id, update.Properties);
        _watcher.Removed += (_, update) =>
        {
            lock (_gate) _peers.Remove(update.Id);
            Changed?.Invoke(this, EventArgs.Empty);
        };
    }

    /// <summary>Raised on a background thread whenever the list changes.</summary>
    public event EventHandler? Changed;

    public IReadOnlyList<HandoffPeer> Peers
    {
        get { lock (_gate) return [.. _peers.Values.OrderBy(peer => peer.Name, StringComparer.CurrentCultureIgnoreCase)]; }
    }

    public void Start()
    {
        try
        {
            _watcher.Start();
        }
        catch (Exception error)
        {
            SyncLog.Error("handoff", "browse", error);
        }
    }

    private void Update(string id, IReadOnlyDictionary<string, object> properties)
    {
        string? address = properties.TryGetValue("System.Devices.IpAddress", out var ip) && ip is string[] addresses
            ? addresses.FirstOrDefault(item => IPAddress.TryParse(item, out var parsed) && parsed.AddressFamily == AddressFamily.InterNetwork) ?? addresses.FirstOrDefault()
            : null;
        var port = properties.TryGetValue("System.Devices.Dnssd.PortNumber", out var portValue) && portValue is ushort number ? number : 0;
        var text = properties.TryGetValue("System.Devices.Dnssd.TextAttributes", out var attributes) && attributes is string[] pairs
            ? pairs.Select(pair => pair.Split('=', 2)).Where(parts => parts.Length == 2).ToDictionary(parts => parts[0], parts => parts[1], StringComparer.OrdinalIgnoreCase)
            : [];
        var instance = properties.TryGetValue("System.Devices.Dnssd.InstanceName", out var instanceValue) ? instanceValue as string : null;
        lock (_gate)
        {
            _peers.TryGetValue(id, out var known);
            address ??= known?.Address;
            if (port == 0) port = known?.Port ?? 0;
            if (address is null || port == 0) return;
            // Version 1 hosts (Android TVs) advertise v=1: they speak only the phone-to-TV handoff.
            if (text.TryGetValue("v", out var version) && version != "2") return;
            var name = text.GetValueOrDefault("name") ?? instance ?? known?.Name ?? address;
            _peers[id] = new HandoffPeer(id, name, address, port, text.GetValueOrDefault("platform") ?? known?.Platform);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Dispose()
    {
        try
        {
            if (_watcher.Status is DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted) _watcher.Stop();
        }
        catch (Exception)
        {
            // Stopping a watcher that never started is fine.
        }
    }
}

public static class HandoffConnections
{
    /// <summary>A TCP connection to a host, within the handshake timeout.</summary>
    public static async Task<(Stream Stream, IDisposable Connection)> ConnectAsync(string host, int port, CancellationToken cancellation)
    {
        var client = new TcpClient();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(HandoffFrames.HandshakeTimeout);
        try
        {
            await client.ConnectAsync(host, port, timeout.Token);
            client.NoDelay = true;
            return (client.GetStream(), client);
        }
        catch (Exception error) when (error is SocketException or OperationCanceledException && !cancellation.IsCancellationRequested)
        {
            client.Dispose();
            throw new HandoffException(HandoffError.ConnectionFailed, error.GetType().Name);
        }
    }

    /// <summary>"host:port" or "[v6]:port", as the host's screen shows it.</summary>
    public static (string Host, int Port)? ParseAddress(string? text)
    {
        var value = text?.Trim();
        if (string.IsNullOrEmpty(value)) return null;
        var colon = value.LastIndexOf(':');
        if (colon <= 0 || !int.TryParse(value[(colon + 1)..], out var port) || port is <= 0 or > 65535) return null;
        var host = value[..colon].Trim('[', ']');
        return host.Length == 0 ? null : (host, port);
    }

    /// <summary>
    /// This PC's address on the local network for the QR code's `h`: the
    /// first private IPv4 address of an interface that is up, Ethernet and
    /// Wi-Fi first.
    /// </summary>
    public static string? LocalAddress()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up
                    && adapter.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
                .OrderBy(adapter => adapter.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 ? 0 : 1)
                .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
                .Select(unicast => unicast.Address)
                .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address) && IsPrivate(address))
                .Select(address => address.ToString())
                .FirstOrDefault();
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    private static bool IsPrivate(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) || (bytes[0] == 192 && bytes[1] == 168)
            || (bytes[0] == 100 && bytes[1] is >= 64 and <= 127);
    }
}

/// <summary>A WinRT socket as one duplex .NET stream, unbuffered.</summary>
internal sealed class SocketStream : Stream
{
    private readonly StreamSocket _socket;
    private readonly Stream _input;
    private readonly Stream _output;

    public SocketStream(StreamSocket socket)
    {
        _socket = socket;
        _input = socket.InputStream.AsStreamForRead(0);
        _output = socket.OutputStream.AsStreamForWrite(0);
    }

    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override int Read(byte[] buffer, int offset, int count) => _input.Read(buffer, offset, count);
    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => _input.ReadAsync(buffer, cancellationToken);
    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) => _input.ReadAsync(buffer, offset, count, cancellationToken);
    public override void Write(byte[] buffer, int offset, int count) => _output.Write(buffer, offset, count);
    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) => _output.WriteAsync(buffer, cancellationToken);
    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) => _output.WriteAsync(buffer, offset, count, cancellationToken);
    public override void Flush() => _output.Flush();
    public override Task FlushAsync(CancellationToken cancellationToken) => _output.FlushAsync(cancellationToken);
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _input.Dispose();
            _output.Dispose();
            _socket.Dispose();
        }
        base.Dispose(disposing);
    }
}
