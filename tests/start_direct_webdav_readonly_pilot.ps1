param([ValidatePattern('^[A-Z]$')][string]$Letter='Z')
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$exe=Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
$config=Join-Path $env:APPDATA 'NASMount\config.json'
if(!(Test-Path -LiteralPath $exe)){throw 'Build the LockBridge Release target before running this pilot'}
if(!(Test-Path -LiteralPath $config)){throw 'No saved FileHub settings found'}
$profiles=@((Get-Content -LiteralPath $config -Raw|ConvertFrom-Json).Profiles | Where-Object {$_.Protocol -eq 'WebDAV'})
if($profiles.Count -ne 1){throw 'This safe pilot script requires exactly one saved WebDAV profile'}
$profile=$profiles[0]
if([Environment]::GetLogicalDrives() -contains ($Letter+':\')){throw ('The requested drive letter is in use: '+$Letter)}

# Never type the NAS password into a ChatGPT message, command-line argument,
# plain text file or saved profile. Prompt in the local Windows PowerShell.
$old=$env:NASMOUNT_BRIDGE_PASSWORD
$private=$null
$bstr=[IntPtr]::Zero
$logDir=Join-Path $env:LOCALAPPDATA 'NASMount\direct-pilot-logs'
[IO.Directory]::CreateDirectory($logDir)|Out-Null
$stdout=Join-Path $logDir ($Letter+'-read.out')
$stderr=Join-Path $logDir ($Letter+'-read.err')
$proc=$null
try {
    if(-not $profile.IsAnonymous -and -not $profile.RememberPassword){
        $private=Read-Host -Prompt 'WebDAV password (not saved)' -AsSecureString
        $bstr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($private)
        $env:NASMOUNT_BRIDGE_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    $proc=Start-Process -FilePath $exe -PassThru -NoNewWindow -ArgumentList @(
        '--direct-webdav-profile-readonly-pilot',[string]$profile.Id,($Letter+':')) `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
}finally{
    $env:NASMOUNT_BRIDGE_PASSWORD=$old
    if($bstr -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)}
    $private=$null
}

$ready=$false
for($n=0;$n -lt 100;$n++){
    Start-Sleep -Milliseconds 200
    if($proc.HasExited){break}
    if(Test-Path $stdout){
        $body=Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue
        if($body -match 'DIRECT_WEBDAV_PILOT_READY'){$ready=$true;break}
    }
}
if(!$ready){
    if($proc -and !$proc.HasExited){Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue}
    throw ('Pilot failed; inspect log: '+$stderr)
}
Write-Output ('Direct WinFsp read-only pilot ready: '+$Letter+': (PID '+$proc.Id+')')
Write-Output ('This is a separate test drive. Existing FileHub mounts are unchanged.')
Write-Output ('To stop the READ-ONLY pilot: Stop-Process -Id '+$proc.Id)
Write-Output ('Diagnostic log: '+$stdout)
