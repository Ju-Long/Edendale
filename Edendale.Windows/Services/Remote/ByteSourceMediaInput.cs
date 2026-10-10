using System.Buffers;
using System.Runtime.InteropServices;
using LibVLCSharp.Shared;

namespace Edendale.Windows.Services.Remote;

/// <summary>
/// LibVLC's custom input (Open/Read/Seek/Close) over a byte source, so a
/// remote file's tokens and signed links never reach LibVLC's own HTTP access
/// (DIFF.md §3.12). LibVLC calls these on its input thread; reads block until
/// the byte source has data. Cancel the source before stopping the player,
/// or Stop waits for a read that may be minutes from finishing.
/// </summary>
public sealed class ByteSourceMediaInput : MediaInput
{
    private readonly IByteSource _source;
    private long _position;

    public ByteSourceMediaInput(IByteSource source)
    {
        _source = source;
        CanSeek = true;
    }

    public IByteSource Source => _source;

    public override bool Open(out ulong size)
    {
        _position = 0;
        // Read once so the size is known (the first Content-Range or the
        // listing gives it); LibVLC treats 0 as "unknown".
        Span<byte> probe = stackalloc byte[1];
        if (_source.Length < 0 && _source.Read(0, probe) < 0)
        {
            size = 0;
            return false;
        }
        size = _source.Length > 0 ? (ulong)_source.Length : 0;
        return true;
    }

    public override int Read(IntPtr buf, uint len)
    {
        if (len == 0) return 0;
        // The project builds without unsafe code, so reads land in a pooled
        // buffer and are copied into LibVLC's.
        var length = (int)Math.Min(len, 1u << 22);
        var buffer = ArrayPool<byte>.Shared.Rent(length);
        try
        {
            var count = _source.Read(_position, buffer.AsSpan(0, length));
            if (count > 0)
            {
                Marshal.Copy(buffer, 0, buf, count);
                _position += count;
            }
            return count;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    public override bool Seek(ulong offset)
    {
        _position = (long)offset;
        return true;
    }

    public override void Close()
    {
        // The player cancels the source when it closes the file; LibVLC also
        // closes the input when it is done with it.
        _source.Cancel();
    }
}
