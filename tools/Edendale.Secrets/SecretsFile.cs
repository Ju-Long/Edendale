// Reading and writing the gitignored root secrets.json.
//
// The file is written through a sibling temporary file that is locked and
// restricted to the current Windows user *before* any secret reaches it, then
// renamed over the destination. That ordering is what keeps a value from ever
// existing in a world-readable file, even briefly.

using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace Edendale.Secrets;

internal static class SecretsFile
{
    private static readonly JsonSerializerOptions WriteOptions = new() { WriteIndented = true };

    /// <summary>
    /// Existing values, or an empty set when the file is missing or
    /// unreadable. A malformed file is treated as empty rather than fatal, so
    /// the tool can always repair one.
    /// </summary>
    public static Dictionary<string, string> Read(string path)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        try
        {
            if (!File.Exists(path)) return values;

            using var stream = File.OpenRead(path);
            using var document = JsonDocument.Parse(stream);
            if (document.RootElement.ValueKind != JsonValueKind.Object) return values;

            foreach (var property in document.RootElement.EnumerateObject())
            {
                if (property.Value.ValueKind == JsonValueKind.String)
                {
                    values[property.Name] = property.Value.GetString() ?? "";
                }
            }
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException)
        {
            values.Clear();
        }
        return values;
    }

    /// <summary>
    /// Writes <paramref name="values"/> atomically, owner-only. Serialized by
    /// System.Text.Json, so a value containing a quote or a backslash produces
    /// a correct file rather than a broken one.
    /// </summary>
    public static void Write(string path, IReadOnlyDictionary<string, string> values)
    {
        AssertSafeDestination(path);

        var json = JsonSerializer.Serialize(values, WriteOptions) + Environment.NewLine;
        var temporary = CreatePrivateTemporaryFile(path);

        try
        {
            // UTF-8 without a BOM: the app parses this with System.Text.Json,
            // and the embedded-resource path reads it as a stream.
            File.WriteAllText(temporary, json, new UTF8Encoding(false));

            File.Move(temporary, path, overwrite: true);
            AssertOwnerOnly(path);
        }
        catch
        {
            TryDelete(temporary);
            throw;
        }
    }

    /// <summary>
    /// A new, empty, owner-only file beside the destination. Created on the
    /// same volume so the rename that follows is a metadata operation rather
    /// than a copy.
    /// </summary>
    private static string CreatePrivateTemporaryFile(string destination)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(destination))
            ?? throw new InvalidOperationException($"Cannot determine the directory of {destination}.");
        var name = Path.GetFileName(destination);

        while (true)
        {
            var candidate = Path.Combine(directory, $"{name}.tmp.{Guid.NewGuid():N}");
            if (File.Exists(candidate)) continue;

            try
            {
                using (new FileStream(
                    candidate, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                }
            }
            catch (IOException)
            {
                continue;   // lost a race for the name
            }

            try
            {
                RestrictToCurrentUser(candidate);
                AssertOwnerOnly(candidate);
                return candidate;
            }
            catch
            {
                TryDelete(candidate);
                throw;
            }
        }
    }

    /// <summary>Replaces the file's ACL with a single full-control rule for this user.</summary>
    private static void RestrictToCurrentUser(string path)
    {
        using var identity = WindowsIdentity.GetCurrent();
        var user = identity.User
            ?? throw new InvalidOperationException("Unable to determine the current Windows user.");

        var security = new FileSecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        security.AddAccessRule(new FileSystemAccessRule(
            user, FileSystemRights.FullControl, AccessControlType.Allow));

        new FileInfo(path).SetAccessControl(security);
    }

    /// <summary>
    /// Verifies the ACL really is owner-only. Group policy can reapply
    /// inherited rules, so the write is only trustworthy if this passes.
    /// </summary>
    private static void AssertOwnerOnly(string path)
    {
        using var identity = WindowsIdentity.GetCurrent();
        var user = identity.User;
        var security = new FileInfo(path).GetAccessControl();
        var rules = security.GetAccessRules(true, true, typeof(SecurityIdentifier));

        var ownerOnly =
            user is not null &&
            Equals(security.GetOwner(typeof(SecurityIdentifier)), user) &&
            security.AreAccessRulesProtected &&
            rules.Count == 1 &&
            rules.Cast<FileSystemAccessRule>().Single() is { } rule &&
            Equals(rule.IdentityReference, user) &&
            rule.AccessControlType == AccessControlType.Allow &&
            rule.FileSystemRights.HasFlag(FileSystemRights.FullControl);

        if (!ownerOnly)
        {
            throw new InvalidOperationException(
                $"Unable to restrict file access to the current Windows user: {path}");
        }
    }

    /// <summary>
    /// Refuses to write through a symlink or over a directory, either of which
    /// would redirect the secret somewhere unintended.
    /// </summary>
    private static void AssertSafeDestination(string path)
    {
        FileAttributes attributes;
        try
        {
            attributes = File.GetAttributes(path);
        }
        catch (Exception error) when (error is FileNotFoundException or DirectoryNotFoundException)
        {
            return;
        }

        if (attributes.HasFlag(FileAttributes.ReparsePoint))
        {
            throw new InvalidOperationException(
                $"Refusing to replace a symbolic link or reparse point: {path}");
        }
        if (attributes.HasFlag(FileAttributes.Directory))
        {
            throw new InvalidOperationException(
                $"Refusing to replace a directory with a secret file: {path}");
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch (IOException)
        {
            // Nothing useful to do; the file is empty or partial either way.
        }
    }
}
