#Requires -Version 5.1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($null -eq ('EdendaleInit.NativeFile' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace EdendaleInit
{
    public static class NativeFile
    {
        private const uint MoveFileReplaceExisting = 0x1;
        private const uint MoveFileWriteThrough = 0x8;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool MoveFileEx(
            string existingFileName,
            string newFileName,
            uint flags
        );

        public static void Replace(string source, string destination)
        {
            if (!MoveFileEx(
                source,
                destination,
                MoveFileReplaceExisting | MoveFileWriteThrough
            ))
            {
                throw new Win32Exception(
                    Marshal.GetLastWin32Error(),
                    "Unable to install the generated secret file."
                );
            }
        }
    }
}
'@
}

function Get-PlainText {
    param(
        [Parameter(Mandatory = $true)]
        [Security.SecureString] $SecureValue
    )

    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
}

function Read-Secret {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Prompt,

        [Parameter(Mandatory = $true)]
        [string] $Pattern,

        [Parameter(Mandatory = $true)]
        [string] $Guidance
    )

    while ($true) {
        $secureValue = Read-Host -Prompt $Prompt -AsSecureString
        try {
            $value = Get-PlainText -SecureValue $secureValue
        }
        finally {
            $secureValue.Dispose()
        }

        if ([string]::IsNullOrEmpty($value)) {
            [Console]::Error.WriteLine('A value is required.')
            continue
        }

        if ($value -cnotmatch $Pattern) {
            [Console]::Error.WriteLine($Guidance)
            $value = $null
            continue
        }

        return $value
    }
}

function Set-OwnerOnlyAccess {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $userSid = $identity.User
        if ($null -eq $userSid) {
            throw 'Unable to determine the current Windows user.'
        }

        $security = New-Object Security.AccessControl.FileSecurity
        $security.SetAccessRuleProtection($true, $false)

        $rule = New-Object Security.AccessControl.FileSystemAccessRule(
            $userSid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow
        )
        [void] $security.AddAccessRule($rule)
        Set-Acl -LiteralPath $Path -AclObject $security
    }
    finally {
        $identity.Dispose()
    }
}

function Assert-SafeDestination {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    try {
        $attributes = [IO.File]::GetAttributes($Path)
    }
    catch [IO.FileNotFoundException] {
        return
    }
    catch [IO.DirectoryNotFoundException] {
        return
    }

    if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing to replace a symbolic link or reparse point: $Path"
    }
    if (($attributes -band [IO.FileAttributes]::Directory) -ne 0) {
        throw "Refusing to replace a directory with a secret file: $Path"
    }
}

function Assert-OwnerOnlyAccess {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $userSid = $identity.User
        $security = Get-Acl -LiteralPath $Path
        $owner = $security.GetOwner([Security.Principal.SecurityIdentifier])
        $rules = @($security.GetAccessRules(
            $true,
            $true,
            [Security.Principal.SecurityIdentifier]
        ))

        $isOwnerOnly = $null -ne $userSid -and
            $owner -eq $userSid -and
            $security.AreAccessRulesProtected -and
            $rules.Count -eq 1 -and
            $rules[0].IdentityReference -eq $userSid -and
            $rules[0].AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            ($rules[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
                [Security.AccessControl.FileSystemRights]::FullControl

        if (-not $isOwnerOnly) {
            throw "Unable to restrict file access to the current Windows user: $Path"
        }
    }
    finally {
        $identity.Dispose()
    }
}

function New-PrivateTempFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    $directory = [IO.Path]::GetDirectoryName($Destination)
    $name = [IO.Path]::GetFileName($Destination)

    do {
        $temporaryPath = Join-Path $directory "$name.tmp.$([Guid]::NewGuid().ToString('N'))"
    } while ([IO.File]::Exists($temporaryPath))

    $stream = [IO.File]::Open(
        $temporaryPath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    $stream.Dispose()

    try {
        Set-OwnerOnlyAccess -Path $temporaryPath
        Assert-OwnerOnlyAccess -Path $temporaryPath
    }
    catch {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        throw
    }

    return $temporaryPath
}

function Install-PrivateFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $TemporaryPath,

        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    Assert-SafeDestination -Path $Destination

    # The sibling temporary file carries its protected ACL through the same-volume rename.
    [EdendaleInit.NativeFile]::Replace($TemporaryPath, $Destination)
    Assert-OwnerOnlyAccess -Path $Destination
}

function Invoke-EdendaleInitialization {
    $repositoryRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
    $nativeSecrets = Join-Path $repositoryRoot 'secrets.json'

    Assert-SafeDestination -Path $nativeSecrets

    if ([IO.File]::Exists($nativeSecrets)) {
        $reply = Read-Host -Prompt 'The existing local secret file will be replaced. Continue? [y/N]'
        if ($reply -cne 'y' -and $reply -cne 'Y') {
            Write-Output 'No files changed.'
            return
        }
    }

    Write-Output 'Enter the two TMDB application credentials from https://www.themoviedb.org/settings/api.'
    Write-Output 'Input is hidden and the generated file is gitignored.'
    Write-Output ''

    $tmdbReadAccessToken = $null
    $tmdbApiKey = $null
    $nativeTemporary = $null
    $nativeContent = $null

    try {
        $tmdbReadAccessToken = Read-Secret `
            -Prompt 'TMDB API Read Access Token (long eyJ... token)' `
            -Pattern '\A[A-Za-z0-9._~-]+\z' `
            -Guidance 'Use the API Read Access Token exactly as shown by TMDB, without a Bearer prefix.'

        $tmdbApiKey = Read-Secret `
            -Prompt 'TMDB API Key (short v3 key)' `
            -Pattern '\A[A-Fa-f0-9]{32}\z' `
            -Guidance 'The TMDB v3 API key must contain exactly 32 hexadecimal characters.'

        $nativeTemporary = New-PrivateTempFile -Destination $nativeSecrets

        $nativeContent = @(
            '{'
            "  `"TMDB_READ_ACCESS_TOKEN`": `"$tmdbReadAccessToken`","
            "  `"TMDB_API_KEY`": `"$tmdbApiKey`""
            '}'
            ''
        ) -join "`n"

        $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($nativeTemporary, $nativeContent, $utf8WithoutBom)

        Install-PrivateFile -TemporaryPath $nativeTemporary -Destination $nativeSecrets
        $nativeTemporary = $null
    }
    finally {
        if ($null -ne $nativeTemporary -and [IO.File]::Exists($nativeTemporary)) {
            Remove-Item -LiteralPath $nativeTemporary -Force -ErrorAction SilentlyContinue
        }

        $tmdbReadAccessToken = $null
        $tmdbApiKey = $null
        $nativeContent = $null
    }

    Write-Output ''
    Write-Output "Created the build-time secret file: $nativeSecrets"
    Write-Output 'Rebuild the app to bundle and use the credentials; no runtime API-key entry is required.'
}

try {
    Invoke-EdendaleInitialization
}
catch {
    [Console]::Error.WriteLine("Error: $($_.Exception.Message)")
    exit 1
}
