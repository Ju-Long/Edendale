// What both frame generators share: the GPU work for one source frame and
// the shader sources embedded in the app.

using System.Reflection;
using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using SharpDX.Direct3D11;

namespace Edendale.Windows.Services.FrameGeneration;

/// <summary>
/// Generates and scales frames on the GPU. Every call comes from the
/// presenter's own thread, which owns the Direct3D 11 immediate context.
/// </summary>
internal interface IFrameGenerator : IDisposable
{
    FrameGenerationBackend Backend { get; }

    /// <summary>A previous frame of the same size exists, so a frame can be generated.</summary>
    bool HasPrevious { get; }

    /// <summary>
    /// Uploads a decoded NV12 frame (Y plane, then the interleaved Cb Cr
    /// plane, both <paramref name="pitch"/> bytes per row) and converts it to
    /// RGBA. It becomes the current frame; the old current one becomes the previous.
    /// </summary>
    void Submit(nint frame, int pitch, int width, int height);

    /// <summary>
    /// Writes into <paramref name="target"/> (R8G8B8A8, <paramref name="outputWidth"/> ×
    /// <paramref name="outputHeight"/>): the frame halfway between the previous and
    /// current frames when <paramref name="generated"/>, otherwise the current frame,
    /// scaled into <paramref name="rect"/> with black around it.
    /// </summary>
    void Render(bool generated, Texture2D target, int outputWidth, int outputHeight, OutputRect rect);

    /// <summary>The presenter is about to release <paramref name="target"/> (a resize).</summary>
    void Forget(Texture2D target);
}

internal static class FrameGenerationAssets
{
    /// <summary>The PTX or HLSL text embedded in the app (see the .csproj).</summary>
    public static string Read(string logicalName)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(logicalName)
            ?? throw new InvalidOperationException($"Missing embedded resource {logicalName}.");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}

/// <summary>
/// Compiles HLSL with d3dcompiler_47.dll, which ships with Windows 10 and
/// 11, so no shader compiler ships with Edendale.
/// </summary>
internal static class ShaderCompiler
{
    private const uint OptimizationLevel3 = 1 << 15;

    [DllImport("d3dcompiler_47.dll", CharSet = CharSet.Ansi, BestFitMapping = false)]
    private static extern int D3DCompile(
        byte[] source, nint sourceSize, string sourceName, nint defines, nint include,
        string entryPoint, string target, uint flags1, uint flags2, out nint code, out nint errors);

    /// <summary>cs_5_0 bytecode for <paramref name="entryPoint"/>, or an exception with the compiler's message.</summary>
    public static byte[] Compile(string source, string entryPoint)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(source);
        var result = D3DCompile(bytes, bytes.Length, "FrameGeneration.hlsl", 0, 0, entryPoint, "cs_5_0", OptimizationLevel3, 0, out var code, out var errors);
        try
        {
            if (result < 0 || code == 0)
            {
                var message = errors != 0 ? Text(errors) : $"0x{result:X8}";
                throw new InvalidOperationException($"Compiling {entryPoint} failed: {message}");
            }
            using var blob = new SharpDX.Direct3D.Blob(code);
            code = 0; // The Blob owns the reference now.
            var compiled = new byte[blob.BufferSize];
            Marshal.Copy(blob.BufferPointer, compiled, 0, compiled.Length);
            return compiled;
        }
        finally
        {
            if (code != 0) Marshal.Release(code);
            if (errors != 0) Marshal.Release(errors);
        }
    }

    private static string Text(nint blobPointer)
    {
        Marshal.AddRef(blobPointer);
        using var blob = new SharpDX.Direct3D.Blob(blobPointer);
        return Marshal.PtrToStringAnsi(blob.BufferPointer, (int)blob.BufferSize).TrimEnd('\0');
    }
}
