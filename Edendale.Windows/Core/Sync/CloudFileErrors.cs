// The Windows file errors a sync folder kept by a cloud client can raise.
// OneDrive Files On-Demand, iCloud for Windows, and Dropbox's online-only
// files are Cloud Files API placeholders: they list under their real names
// and a read downloads them, so they already behave like the placeholders
// Apple's FolderSyncStorage downloads first (origin/apple-27.1
// SyncStorage.swift, K.7). What Windows adds is a download it refuses (the
// sync client isn't running, the app is blocked from downloading files, no
// network), which the engine names in a warning instead of only the log,
// and a target the client holds open for a moment while it uploads.

namespace Edendale.Windows.Core.Sync;

public static class CloudFileErrors
{
    /// <summary>
    /// Every ERROR_CLOUD_FILE_* code in winerror.h (Windows SDK 10.0.26100).
    /// Listed one by one: the 358–523 span also holds unrelated codes such as
    /// ERROR_CHILD_PROCESS_BLOCKED (367), ERROR_NOT_A_CLOUD_FILE (376), and
    /// ERROR_SMB1_NOT_AVAILABLE (384).
    /// </summary>
    private static readonly HashSet<int> CloudFileCodes =
    [
        358, // SYNC_ROOT_METADATA_CORRUPT
        362, // PROVIDER_NOT_RUNNING
        363, 364, 365, 366, // METADATA_CORRUPT, METADATA_TOO_LARGE, PROPERTY_BLOB_TOO_LARGE, PROPERTY_BLOB_CHECKSUM_MISMATCH
        374, 375, // TOO_MANY_PROPERTY_BLOBS, PROPERTY_VERSION_NOT_SUPPORTED
        377, 378, 379, 380, 381, 382, 383, // NOT_IN_SYNC … VALIDATION_FAILED
        386, 387, 388, 389, 390, 391, 392, 393, 394, // AUTHENTICATION_FAILED … PROPERTY_CORRUPT
        395, // ACCESS_DENIED: the app is blocked from automatic file downloads
        396, 397, 398, // INCOMPATIBLE_HARDLINKS, PROPERTY_LOCK_CONFLICT, REQUEST_CANCELED
        404, // PROVIDER_TERMINATED
        426, // REQUEST_TIMEOUT
        434, // DEHYDRATION_DISALLOWED
        475, // US_MESSAGE_TIMEOUT
        523, // HYDRATION_NOT_AVAILABLE
    ];

    private const int FacilityWin32 = 7;
    private const int ErrorSharingViolation = 32;
    private const int ErrorLockViolation = 33;

    /// <summary>
    /// Whether <paramref name="hresult"/> (an <see cref="Exception.HResult"/>)
    /// is a Cloud Files error: Windows couldn't download a placeholder. .NET
    /// raises these as an IOException with the Win32 code wrapped in a
    /// FACILITY_WIN32 HRESULT, 0x8007016A for ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING.
    /// </summary>
    public static bool IsCloudFileError(int hresult) => Win32Code(hresult) is int code && CloudFileCodes.Contains(code);

    /// <summary>
    /// Whether <paramref name="hresult"/> is a sharing or lock violation: the
    /// file is open in another process, such as a sync client uploading it.
    /// </summary>
    public static bool IsInUse(int hresult) => Win32Code(hresult) is ErrorSharingViolation or ErrorLockViolation;

    /// <summary>The Win32 error code inside a FACILITY_WIN32 failure HRESULT, or null.</summary>
    private static int? Win32Code(int hresult)
    {
        var value = unchecked((uint)hresult);
        if ((value & 0x80000000) == 0 || ((value >> 16) & 0x1FFF) != FacilityWin32) return null;
        return (int)(value & 0xFFFF);
    }
}
