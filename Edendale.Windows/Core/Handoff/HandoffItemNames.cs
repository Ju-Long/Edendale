// The item names a handoff `result` reports (SYNC-PROTOCOL.md §9.6 step 4):
// `location`, `account:<kind>:<subject>`, and `login:<kind>:<host>`, read
// back so the sender's screen can say "OneDrive account" or "SFTP login for
// nas.local" rather than the raw ID (SyncHandoffView.swift `describe`). WinUI-
// free; the dialog turns the parts into words.

namespace Edendale.Windows.Core.Handoff;

public enum HandoffItemName
{
    /// <summary>The sync location.</summary>
    Location,
    /// <summary>A cloud account; <see cref="HandoffItemParts.SourceKind"/> is its kind.</summary>
    Account,
    /// <summary>A server login; the parts carry its kind and host.</summary>
    Login,
    /// <summary>Anything this build doesn't know; shown as sent.</summary>
    Other,
}

/// <summary>One result item, split: its kind, the source kind's raw value, and a login's host.</summary>
public readonly record struct HandoffItemParts(HandoffItemName Kind, string? SourceKind, string? Host);

public static class HandoffItemNames
{
    /// <summary>
    /// Splits an item on ':' into at most three parts, so a host keeps any
    /// further colons (an IPv6 address). An account's subject is not needed
    /// to name it, and an item missing its kind is <see cref="HandoffItemName.Other"/>.
    /// </summary>
    public static HandoffItemParts Parse(string? item)
    {
        if (string.IsNullOrEmpty(item)) return new(HandoffItemName.Other, null, null);
        var parts = item.Split(':', 3);
        return parts[0] switch
        {
            HandoffResult.LocationItem when parts.Length == 1 => new(HandoffItemName.Location, null, null),
            "account" when parts.Length >= 2 && parts[1].Length > 0 => new(HandoffItemName.Account, parts[1], null),
            "login" when parts.Length == 3 && parts[1].Length > 0 && parts[2].Length > 0 => new(HandoffItemName.Login, parts[1], parts[2]),
            _ => new(HandoffItemName.Other, null, null),
        };
    }
}
