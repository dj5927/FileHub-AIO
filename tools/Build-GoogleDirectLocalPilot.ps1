# Publisher-only local Windows test ZIP builder; real OAuth client JSON must
# be generated in the publisher's Google Cloud project. Never uploads any
# Drive content. This is not a stable release or a claim of live I/O success.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CredentialFile,
    [string]$OutputDirectory,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$credential = (Resolve-Path -LiteralPath $CredentialFile -ErrorAction Stop).Path
$raw = [IO.File]::ReadAllBytes($credential)
if ($raw.Length -eq 0 -or $raw.Length -gt 262144) {
    throw 'Invalid publisher Google OAuth JSON file size.'
}
$json = [Text.Encoding]::UTF8.GetString($raw) | ConvertFrom-Json
if (-not $json.installed -or $json.web -or
    -not ([string]$json.installed.client_id).EndsWith(
        '.apps.googleusercontent.com', [StringComparison]::OrdinalIgnoreCase) -or
    [string]::IsNullOrWhiteSpace([string]$json.installed.client_secret)) {
    throw 'Only a valid publisher-owned Google Desktop OAuth JSON may be bundled.'
}

# Publisher work often exceeds 1 GB: native x64 app, a second WinFsp bridge,
# ancillary binaries and the compressed output. Use a local FIXED drive with
# ample free space instead of silently exhausting the system C: drive.
$preferredRoot = [IO.Path]::GetPathRoot($env:TEMP)
$workDrive = [IO.DriveInfo]::GetDrives() |
    Where-Object { $_.IsReady -and $_.DriveType -eq [IO.DriveType]::Fixed -and
        $_.AvailableFreeSpace -gt 3GB } |
    Sort-Object @{ Expression = { if ($_.Name -eq $preferredRoot) { 0 } else { 1 } } },Name |
    Select-Object -First 1
if (-not $workDrive) {
    throw 'A local fixed drive with at least 3 GB free space is required to build the Windows test ZIP.'
}
$cache = Join-Path $workDrive.RootDirectory.FullName 'FileHubAIO-PublisherBuildCache'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$stableZip = Join-Path $cache 'FileHub-AIO-PC-1.3.126-win-x64.zip'
$stableHashFile = Join-Path $cache 'SHA256SUMS'
$releaseBase = 'https://github.com/dj5927/FileHub-AIO/releases/download/pc-v1.3.126'
if (-not (Test-Path $stableHashFile)) {
    Write-Host '[FileHub] Downloading pinned stable backend checksum'
    Invoke-WebRequest -Uri ($releaseBase + '/SHA256SUMS') -OutFile $stableHashFile
}
$sum = Get-Content -LiteralPath $stableHashFile -Raw
if ($sum -notmatch '(?im)^([a-f0-9]{64})\s+FileHub-AIO-PC-1\.3\.126-win-x64\.zip\s*$') {
    throw 'Pinned stable backend checksum manifest is invalid.'
}
$expected = $Matches[1]
if (-not (Test-Path $stableZip) -or
    (Get-FileHash -LiteralPath $stableZip -Algorithm SHA256).Hash -ne $expected) {
    Write-Host '[FileHub] Downloading verified stable ancillary backends'
    $downloading = $stableZip + '.download'
    try {
        Invoke-WebRequest -Uri ($releaseBase + '/FileHub-AIO-PC-1.3.126-win-x64.zip') -OutFile $downloading
        if ((Get-FileHash -LiteralPath $downloading -Algorithm SHA256).Hash -ne $expected) {
            throw 'Stable backend archive checksum mismatch.'
        }
        Move-Item -LiteralPath $downloading -Destination $stableZip -Force
    }
    finally { Remove-Item -LiteralPath $downloading -Force -ErrorAction SilentlyContinue }
}

$temp = Join-Path $cache ('work-' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $temp 'app'
$bridge = Join-Path $temp 'bridge'
$packageVerified = $false
$partialArchive = $null
$archive = $null
try {
    New-Item -ItemType Directory -Force -Path $app,$bridge | Out-Null
    Write-Host '[FileHub] Publishing Windows main app and native WinFsp bridge'
    & dotnet publish (Join-Path $root 'NASMount.csproj') -c Release -r win-x64 --no-restore -nologo -clp:ErrorsOnly -o $app
    if ($LASTEXITCODE -ne 0) { throw 'FileHub main app publish failed.' }
    & dotnet publish (Join-Path $root 'LockBridge/LockBridge.csproj') -c Release -r win-x64 --no-restore -nologo -clp:ErrorsOnly -o $bridge
    if ($LASTEXITCODE -ne 0) { throw 'Native Google WinFsp bridge publish failed.' }

    $targetTools = Join-Path $app 'tools'
    New-Item -ItemType Directory -Force -Path $targetTools | Out-Null
    Copy-Item -Path (Join-Path $bridge '*') -Destination $targetTools -Recurse -Force
    [IO.File]::WriteAllBytes((Join-Path $targetTools 'google-oauth-desktop.json'), $raw)

    Write-Host '[FileHub] Extracting exactly five verified ancillary backends'
    # Expand-Archive -Force can fail on this release's duplicate entries.
    # Never unpack the entire 200MB ZIP. Extract ONLY five fixed entries,
    # not names from the archive itself (blocks path traversal).
    Add-Type -AssemblyName System.IO.Compression
    $stableStream = [IO.File]::OpenRead($stableZip)
    try {
        $archiveReader = [IO.Compression.ZipArchive]::new(
            $stableStream, [IO.Compression.ZipArchiveMode]::Read)
        try {
            foreach ($name in @('rclone.exe','rclone.sha256',
                'rclone-terabox.exe','rclone-terabox.sha256','winfsp-2.1.25156.msi')) {
                $entry = $archiveReader.GetEntry('tools/' + $name)
                if ($null -eq $entry -or $entry.Length -le 0) {
                    throw ('Stable backend missing: ' + $name)
                }
                $entryStream = $entry.Open()
                try {
                    $targetStream = [IO.File]::Create((Join-Path $targetTools $name))
                    try { $entryStream.CopyTo($targetStream) }
                    finally { $targetStream.Dispose() }
                }
                finally { $entryStream.Dispose() }
            }
        }
        finally { $archiveReader.Dispose() }
    }
    finally { $stableStream.Dispose() }
    foreach ($name in @('rclone','rclone-terabox')) {
        $exe = Join-Path $targetTools ($name + '.exe')
        $manifest = Join-Path $targetTools ($name + '.sha256')
        $hash = ((Get-Content -LiteralPath $manifest -Raw).Trim() -split '\s+')[0]
        if ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $hash) {
            throw ('Ancillary backend hash mismatch: ' + $name)
        }
    }
    $winfsp = Join-Path $targetTools 'winfsp-2.1.25156.msi'
    if ((Get-FileHash -LiteralPath $winfsp -Algorithm SHA256).Hash -ne
        '073A70E00F77423E34BED98B86E600DEF93393BA5822204FAC57A29324DB9F7A') {
        throw 'Official WinFsp driver hash mismatch.'
    }

    Write-Host '[FileHub] Running packaged binary safety checks'
    $mainExe = Join-Path $app 'FileHubAIO.exe'
    $checks = @(
        @('--google-publisher-oauth-policy-test',
          'GOOGLE_PUBLISHER_OAUTH_PACKAGED_POLICY_PASS'),
        @('--google-login-ui-policy-test',
          'GOOGLE_CHROME_ONLY_LOGIN_NO_USER_OAUTH_FIELDS_PASS'),
        @('--google-direct-api-policy-test',
          'GOOGLE_NATIVE_ONLY_NO_RCLONE_FALLBACK_POLICY_PASS'),
        @('--google-cold-start-policy-test',
          'RCLONE_THREE_ENGINE_DISTINCT_MANIFEST_POLICY_PASS')
    )
    foreach ($check in $checks) {
        $mode = [string]$check[0]
        $required = [string]$check[1]
        $stdoutFile = Join-Path $temp 'policy-stdout.txt'
        $stderrFile = Join-Path $temp 'policy-stderr.txt'
        # GUI-subsystem EXEs launched with PowerShell's call operator can
        # return BEFORE the test terminates, leaving LASTEXITCODE stale.
        # Start-Process -Wait and an expected marker are BOTH mandatory.
        $process = Start-Process -FilePath $mainExe -ArgumentList $mode -Wait `
            -PassThru -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile
        $outText = if (Test-Path $stdoutFile) { Get-Content $stdoutFile -Raw } else { '' }
        $errText = if (Test-Path $stderrFile) { Get-Content $stderrFile -Raw } else { '' }
        if ($process.ExitCode -ne 0 -or $outText -notmatch $required) {
            # Google publisher checks may print diagnostic categories only,
            # never client credentials, tokens or the JSON file content.
            throw ('Packaged test failed: ' + $mode +
                ' exit=' + $process.ExitCode + ' marker=' +
                ($outText -match $required) + ' categories=' +
                (($errText -split "`r?`n" | Where-Object { $_ -match '^GOOGLE_' }) -join ','))
        }
        Write-Host ('[FileHub] ' + $required)
    }
    $bridgeExe = Join-Path $targetTools 'NASMountLockBridge.exe'
    if (-not (Test-Path $bridgeExe)) { throw 'Packaged Google WinFsp bridge missing.' }
    # The isolated fake-API Windows mount is part of CI; run it locally only
    # on a machine where WinFsp is already installed.

    if ($DryRun) {
        Write-Output 'GOOGLE_PUBLISHER_LOCAL_PILOT_PACKAGE_DRY_RUN_PASS'
        return
    }
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $downloadFolder = Join-Path $env:USERPROFILE 'Downloads'
        $downloadRoot = [IO.Path]::GetPathRoot($downloadFolder)
        $downloadDrive = [IO.DriveInfo]::new($downloadRoot)
        $OutputDirectory = if ($downloadDrive.AvailableFreeSpace -gt 2GB) {
            $downloadFolder
        } else {
            Join-Path $cache 'PublisherTestOutput'
        }
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $archive = Join-Path $OutputDirectory (
        'FileHub-GoogleDirect-PublisherTest-' + (Get-Date -Format 'yyyyMMdd-HHmmss') +
        '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.zip')
    # PowerShell 5's Compress-Archive writes Windows backslashes into ZIP
    # entry names; the resulting paths fail standard slash-based archive
    # verification and can be mishandled on other platforms. Explicitly
    # write ZIP-spec forward-slash entry names while streaming bytes.
    $partialArchive = $archive + '.partial'
    $zipStream = [IO.File]::Create($partialArchive)
    try {
        $zipWriter = [IO.Compression.ZipArchive]::new(
            $zipStream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $rootPrefix = ([IO.Path]::GetFullPath($app)).TrimEnd(
                [char]92, [char]47) + [char]92
            foreach ($file in Get-ChildItem -LiteralPath $app -Recurse -File) {
                if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw 'Unexpected filesystem link in staged publisher test package.'
                }
                if (-not $file.FullName.StartsWith($rootPrefix,
                    [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'Unexpected file outside publisher test staging root.'
                }
                $relative = $file.FullName.Substring($rootPrefix.Length).Replace(
                    [char]92, [char]47)
                $entry = $zipWriter.CreateEntry($relative,
                    [IO.Compression.CompressionLevel]::Optimal)
                $source = [IO.File]::OpenRead($file.FullName)
                try {
                    $destination = $entry.Open()
                    try { $source.CopyTo($destination) }
                    finally { $destination.Dispose() }
                }
                finally { $source.Dispose() }
            }
        }
        finally { $zipWriter.Dispose() }
    }
    finally { $zipStream.Dispose() }

    # The package is never made visible under its final name until the
    # bridge, OAuth config and ancillary driver are confirmed IN the ZIP.
    $reader = [IO.File]::OpenRead($partialArchive)
    try {
        $zipReader = [IO.Compression.ZipArchive]::new(
            $reader, [IO.Compression.ZipArchiveMode]::Read)
        try {
            foreach ($required in @('FileHubAIO.exe',
                'tools/NASMountLockBridge.exe',
                'tools/google-oauth-desktop.json',
                'tools/rclone.exe','tools/rclone-terabox.exe',
                'tools/winfsp-2.1.25156.msi')) {
                $item = $zipReader.GetEntry($required)
                if (-not $item -or $item.Length -le 0) {
                    throw ('Final ZIP is missing required component: ' + $required)
                }
            }
        }
        finally { $zipReader.Dispose() }
    }
    finally { $reader.Dispose() }

    Move-Item -LiteralPath $partialArchive -Destination $archive -ErrorAction Stop
    $sha = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    $packageVerified = $true
    Write-Host '[FileHub] Local publisher test ZIP created (NOT a production release):' -ForegroundColor Green
    Write-Output $archive
    Write-Output ('SHA256=' + $sha)
}
finally {
    [Array]::Clear($raw, 0, $raw.Length)
    if ($partialArchive -and (Test-Path -LiteralPath $partialArchive)) {
        Remove-Item -LiteralPath $partialArchive -Force -ErrorAction SilentlyContinue
    }
    if (-not $packageVerified -and $archive -and
        (Test-Path -LiteralPath $archive)) {
        Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
