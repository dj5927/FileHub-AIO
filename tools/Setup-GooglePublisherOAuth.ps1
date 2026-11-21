# FileHub AIO publisher-only, one-time Google OAuth onboarding.
# End users never run this script or enter a Client ID/JSON in FileHub.
# The publisher Desktop JSON is bundled into a local publisher test ZIP
# (desktop OAuth is a public client identity), and optionally sent through
# STDIN to a GitHub Actions encrypted repository secret. Never print it.
[CmdletBinding()]
param(
    [string]$CredentialFile,
    [switch]$OpenCloudSetup,
    [switch]$SkipWorkflowRun,
    [switch]$SkipLocalBundle,
    [switch]$LocalOnly
)

$ErrorActionPreference = 'Stop'
$repo = 'dj5927/FileHub-AIO'
$branch = 'pc/google-direct-stage-recovery-20261120'
$secretName = 'FILEHUB_GOOGLE_DESKTOP_OAUTH_JSON_BASE64'

function Invoke-PilotBranchCiRecheck {
    # workflow_dispatch only works reliably for workflows that exist on the
    # GitHub default branch. This Google pilot intentionally exists ONLY on
    # its development branch. A safe fast-forward empty-tree commit pushes
    # that branch, triggering the declared push CI without changing source.
    $ref = (& gh api "repos/$repo/git/ref/heads/$branch" | Out-String) |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $ref.object.sha) {
        throw 'Could not read pilot branch head from GitHub.'
    }
    $oldHead = [string]$ref.object.sha
    $head = (& gh api "repos/$repo/git/commits/$oldHead" | Out-String) |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $head.tree.sha) {
        throw 'Could not read pilot branch tree from GitHub.'
    }
    $commitInput = @{
        message = 'CI: recheck publisher Google OAuth readiness'
        tree = [string]$head.tree.sha
        parents = @($oldHead)
    } | ConvertTo-Json -Compress
    $newCommit = ($commitInput |
        & gh api -X POST "repos/$repo/git/commits" --input - | Out-String) |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $newCommit.sha) {
        throw 'Could not create development-only CI trigger commit.'
    }
    $lease = @{
        sha = [string]$newCommit.sha
        force = $false
    } | ConvertTo-Json -Compress
    $moved = ($lease |
        & gh api -X PATCH "repos/$repo/git/refs/heads/$branch" --input - |
        Out-String) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $moved.object.sha -ne $newCommit.sha) {
        throw 'Pilot branch advanced concurrently or the push was rejected. Retry without forcing.'
    }
}

if (-not $OpenCloudSetup -and [string]::IsNullOrWhiteSpace($CredentialFile)) {
    Add-Type -AssemblyName System.Windows.Forms
    $picker = New-Object System.Windows.Forms.OpenFileDialog
    $picker.Title = 'FileHub AIO publisher Google Desktop OAuth JSON'
    $picker.Filter = 'Google OAuth Desktop JSON (*.json)|*.json'
    $picker.InitialDirectory = [Environment]::GetFolderPath('UserProfile') + '\Downloads'
    if ($picker.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $CredentialFile = $picker.FileName
    }
    else {
        $OpenCloudSetup = $true
    }
}

if ($OpenCloudSetup) {
    Write-Host '[FileHub Google] Publisher OAuth initial setup' -ForegroundColor Cyan
    Write-Host '1. Select or create a Google Cloud project.'
    Write-Host '2. Enable Google Drive API for that project.'
    Write-Host '3. For a publicly distributed FileHub app, select External audience.'
    Write-Host '   During Testing, add the developer test account as a test user.'
    Write-Host '   Public distribution of the full Drive scope requires Google verification.'
    Write-Host '4. Create OAuth client: Desktop app (not Web application).'
    Write-Host '5. Download the publisher Desktop OAuth JSON; keep it off public repositories.'
    Write-Host '   Then run this script with -CredentialFile <path> -LocalOnly.'
    Start-Process 'https://console.cloud.google.com/apis/library/drive.googleapis.com'
    Start-Process 'https://console.cloud.google.com/auth/clients'
    return
}

$resolved = (Resolve-Path -LiteralPath $CredentialFile -ErrorAction Stop).Path
$file = Get-Item -LiteralPath $resolved -ErrorAction Stop
if ($file.Length -le 0 -or $file.Length -gt 262144) {
    throw 'Google Desktop OAuth client JSON size is invalid.'
}

$raw = [System.IO.File]::ReadAllBytes($resolved)
try {
    $json = [System.Text.Encoding]::UTF8.GetString($raw) | ConvertFrom-Json
    $installed = $json.installed
    if (-not $installed -or $json.web -or $json.type -eq 'service_account') {
        throw 'A Google OAuth Desktop app JSON is required, not a Web or Service Account credential.'
    }
    $id = [string]$installed.client_id
    $secret = [string]$installed.client_secret
    if ($id.Length -lt 25 -or $id.Length -gt 512 -or
        -not $id.EndsWith('.apps.googleusercontent.com', [StringComparison]::OrdinalIgnoreCase) -or
        $id -match '\s' -or $secret.Length -lt 6 -or $secret.Length -gt 2048 -or
        $secret -match '[\x00-\x1f]') {
        throw 'Google Desktop OAuth Client ID/Secret format is invalid.'
    }

    # Build a Windows test ZIP directly on the publisher's PC, independent
    # of GitHub CLI; a signed-in Google OAuth app remains mandatory.
    if (-not $SkipLocalBundle) {
        & (Join-Path $PSScriptRoot 'Build-GoogleDirectLocalPilot.ps1') `
            -CredentialFile $resolved
        if ($LASTEXITCODE -ne 0) { throw 'Publisher local test ZIP build failed.' }
    }
    if ($LocalOnly) { return }

    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) {
        Write-Warning 'Local test ZIP is ready, but GitHub CLI is not installed. For CI deployment install https://cli.github.com/ and run publisher setup again.'
        return
    }
    & gh auth status --hostname github.com 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Sign in to GitHub once in your browser; this is only for the publisher.'
        & gh auth login --hostname github.com --web --scopes repo,workflow
        if ($LASTEXITCODE -ne 0) { throw 'GitHub publisher sign-in failed.' }
    }

    # Do not pass the encoded JSON on the command line (visible in process
    # listings), write it to a plaintext staging file, or print it to logs.
    $base64 = [Convert]::ToBase64String($raw)
    $base64 | & gh secret set $secretName --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'GitHub Actions secret update failed.' }
    Write-Host '[FileHub Google] Publisher OAuth configuration uploaded to encrypted GitHub Actions secret.' -ForegroundColor Green

    if (-not $SkipWorkflowRun) {
        & gh workflow run 'direct-google-pilot.yml' --repo $repo --ref $branch
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Google pilot workflow is not on the default branch. Triggering a safe development-branch push instead.'
            Invoke-PilotBranchCiRecheck
            Write-Host '[FileHub Google] Windows pilot CI was triggered by a safe, fast-forward-only development-branch commit.' -ForegroundColor Green
        }
        else {
            Write-Host '[FileHub Google] Windows direct-engine pilot validation requested.' -ForegroundColor Green
        }
    }
    Write-Host 'Review the Windows CI and real-account smoke test before claiming release readiness.'
}
finally {
    # This is publisher configuration (a public desktop client), but still
    # avoid keeping unnecessary copies of the original JSON in memory.
    if ($null -ne $raw) { [Array]::Clear($raw, 0, $raw.Length) }
    $base64 = $null
    $secret = $null
}
