# Fully offline contract for the one-time publisher OAuth deployment script.
# Never uses an actual Google account, JSON credential or GitHub token.
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:TEMP ('FileHubPublisherOAuthLab-' + [guid]::NewGuid().ToString('N'))
$pathBefore = $env:PATH
try {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $env:MOCK_GH_LOG = Join-Path $dir 'mock-commands.txt'
    $env:PATH = $dir + ';' + $pathBefore
    [IO.File]::WriteAllLines((Join-Path $dir 'gh.cmd'), @(
        '@echo off',
        'echo %1>>"%MOCK_GH_LOG%"',
        'if "%1"=="auth" exit /b 0',
        'if "%1"=="secret" (',
        '  more >nul',
        '  exit /b 0',
        ')',
        'if "%1"=="workflow" exit /b 0',
        'exit /b 1'
    ))
    $valid = Join-Path $dir 'synthetic-desktop.json'
    [IO.File]::WriteAllText($valid, (@{
        installed = @{
            client_id = '123456789012-test-desktop.apps.googleusercontent.com'
            client_secret = 'GOCSPX-fake-for-offline-policy-only'
        }
    } | ConvertTo-Json -Compress))
    $script = Join-Path $PSScriptRoot 'Setup-GooglePublisherOAuth.ps1'
    & $script -CredentialFile $valid | Out-Null
    $calls = @(Get-Content -LiteralPath $env:MOCK_GH_LOG)
    if (($calls -join ',') -ne 'auth,secret,workflow') {
        throw 'Publisher setup did not upload the secret and dispatch CI via GitHub CLI.'
    }

    # Explicitly refuse Web OAuth JSON even if its keys look plausible;
    # no further GitHub upload must happen for an invalid config.
    $invalid = Join-Path $dir 'synthetic-web.json'
    [IO.File]::WriteAllText($invalid, (@{
        web = @{
            client_id = '123456789012-test-desktop.apps.googleusercontent.com'
            client_secret = 'GOCSPX-fake-for-offline-policy-only'
        }
    } | ConvertTo-Json -Compress))
    $rejected = $false
    try { & $script -CredentialFile $invalid | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected -or
        (@(Get-Content -LiteralPath $env:MOCK_GH_LOG) -join ',') -ne
            'auth,secret,workflow') {
        throw 'Publisher setup accepted an invalid OAuth client or modified GitHub.'
    }

    # GitHub requires workflow_dispatch workflows to exist on its default
    # branch. Test the safe fast-forward push fallback when dispatch fails.
    [IO.File]::WriteAllLines((Join-Path $dir 'gh.cmd'), @(
        '@echo off',
        'echo %1>>"%MOCK_GH_LOG%"',
        'if "%1"=="auth" exit /b 0',
        'if "%1"=="secret" (',
        '  more >nul',
        '  exit /b 0',
        ')',
        'if "%1"=="workflow" exit /b 1',
        'if "%1"=="api" (',
        '  if "%2"=="repos/dj5927/FileHub-AIO/git/ref/heads/pc/google-direct-stage-recovery-20261120" echo {"object":{"sha":"MOCK_HEAD"}} & exit /b 0',
        '  if "%2"=="repos/dj5927/FileHub-AIO/git/commits/MOCK_HEAD" echo {"tree":{"sha":"MOCK_TREE"}} & exit /b 0',
        '  if "%3"=="POST" echo {"sha":"MOCK_NEXT"} & exit /b 0',
        '  if "%3"=="PATCH" echo {"object":{"sha":"MOCK_NEXT"}} & exit /b 0',
        ')',
        'exit /b 1'
    ))
    Clear-Content -LiteralPath $env:MOCK_GH_LOG
    & $script -CredentialFile $valid | Out-Null
    if ((@(Get-Content -LiteralPath $env:MOCK_GH_LOG) -join ',') -ne
        'auth,secret,workflow,api,api,api,api') {
        throw 'Safe development-branch CI fallback did not use GitHub API as expected.'
    }
    Write-Output 'GOOGLE_PUBLISHER_OAUTH_ONCE_SECURE_BOOTSTRAP_POLICY_PASS'
    Write-Output 'GOOGLE_PUBLISHER_OAUTH_CI_BRANCH_PUSH_FALLBACK_PASS'
}
finally {
    $env:PATH = $pathBefore
    Remove-Item Env:MOCK_GH_LOG -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
