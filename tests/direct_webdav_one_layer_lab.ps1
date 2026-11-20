param([int]$Port = 18766)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$rclone = 'E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
$bridge = Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.dll'
$lab = Join-Path $env:TEMP ('FileHubDirectWinFsp_' + [Guid]::NewGuid().ToString('N'))
$server = Join-Path $lab 'server'
$logs = Join-Path $lab 'logs'
[IO.Directory]::CreateDirectory($server) | Out-Null
[IO.Directory]::CreateDirectory($logs) | Out-Null
Copy-Item -LiteralPath "$env:windir\System32\where.exe" -Destination (Join-Path $server 'DirectDownloadTests.exe')
Copy-Item -LiteralPath "$env:windir\System32\findstr.exe" -Destination (Join-Path $server 'MultiDownloader.exe')
[IO.Directory]::CreateDirectory((Join-Path $server 'subdir')) | Out-Null
[IO.File]::WriteAllText((Join-Path $server 'subdir\note.txt'),'one WinFsp layer; direct WebDAV GET')
$used = [Environment]::GetLogicalDrives() | ForEach-Object { $_.Substring(0,1).ToUpperInvariant() }
$letter = @('V','W','Q','P','T','U','M','N') | Where-Object { $used -notcontains $_ } | Select-Object -First 1
if (!$letter) { throw 'No free isolated drive letter' }
$mount = $letter + ':'
$serverProcess = $null
$mountProcess = $null
$env:FILEHUB_DIRECT_DEBUG='1'
Write-Output ('DIRECT_LAB_ROOT=' + $lab)
Write-Output ('DIRECT_LAB_LETTER=' + $mount)
try {
    $serverProcess = Start-Process -FilePath $rclone -PassThru -NoNewWindow -ArgumentList @(
        'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s',
        '--log-level','INFO',
        '--log-file',(Join-Path $logs 'server.log'))
    $alive = $false
    for($i=0; $i -lt 50; $i++){
        try { $tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port);$tcp.Dispose();$alive=$true;break }
        catch { Start-Sleep -Milliseconds 200 }
    }
    if(!$alive) { throw 'Loopback WebDAV server did not start' }
    $mountProcess = Start-Process 'dotnet.exe' -PassThru -NoNewWindow -ArgumentList @(
        $bridge,'--direct-webdav-readonly-lab',"http://127.0.0.1:$Port/",$mount) `
        -RedirectStandardOutput (Join-Path $logs 'direct.out') `
        -RedirectStandardError (Join-Path $logs 'direct.err')
    $ready=$false
    for($i=0;$i -lt 80;$i++){
        try { $items=@(Get-ChildItem -LiteralPath ($mount+'\') -ErrorAction Stop);
            if($items.Count -ge 2){$ready=$true;break} }
        catch {}
        if($mountProcess.HasExited){break}
        Start-Sleep -Milliseconds 200
    }
    if(!$ready){Get-Content (Join-Path $logs 'direct.err') -ErrorAction SilentlyContinue | Select-Object -Last 20;throw 'Direct one-layer WinFsp directory listing failed'}
    Write-Output ('DIRECT_ONE_LAYER_ROOT_ITEMS='+(($items | ForEach-Object{$_.Name}) -join ','))
    foreach($name in @('DirectDownloadTests.exe','MultiDownloader.exe')){
        $source = Join-Path $server $name
        $remote = $mount+'\'+$name
        for($n=1;$n -le 3;$n++){
            $timer=[Diagnostics.Stopwatch]::StartNew()
            $info=[Diagnostics.FileVersionInfo]::GetVersionInfo($remote)
            $versionMs=$timer.ElapsedMilliseconds
            $timer.Restart()
            $hash=(Get-FileHash -LiteralPath $remote -Algorithm SHA256).Hash
            $readMs=$timer.ElapsedMilliseconds
            if($hash -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash){throw ('READ_HASH_MISMATCH='+$name)}
            Write-Output ('DIRECT_ONE_LAYER_EXE name='+$name+' round='+$n+' versionMs='+$versionMs+' shaReadMs='+$readMs)
        }
    }
    $note=Get-Content -LiteralPath ($mount+'\subdir\note.txt') -Raw
    if($note -notmatch 'direct WebDAV') {throw 'Subdirectory GET mismatch'}
    $null=@(Get-ChildItem -LiteralPath ($mount+'\subdir'))
    Write-Output 'DIRECT_ONE_LAYER_NESTED_READ_PASS'
    # A real direct provider must eventually refresh remote creations and
    # invalidate an executable cache when the NAS revision changes.
    $newPath = $mount+'\subdir\new-from-nas.txt'
    $null = Test-Path -LiteralPath $newPath
    [IO.File]::WriteAllText((Join-Path $server 'subdir\new-from-nas.txt'),'new-version')
    Start-Sleep -Seconds 10
    if(!(Test-Path -LiteralPath $newPath)) { throw 'Remote creation not refreshed after TTL' }
    Write-Output 'DIRECT_ONE_LAYER_REMOTE_CREATION_REFRESH_PASS'
    $modified = Join-Path $server 'MultiDownloader.exe'
    [IO.File]::AppendAllText($modified,'FileHub-direct-backend-new-revision')
    [IO.File]::SetLastWriteTimeUtc($modified,[DateTime]::UtcNow)
    Start-Sleep -Seconds 17
    $updated=(Get-FileHash -LiteralPath ($mount+'\MultiDownloader.exe') -Algorithm SHA256).Hash
    if($updated -ne (Get-FileHash -LiteralPath $modified -Algorithm SHA256).Hash) {
        throw 'Executable content cache did not refresh on remote revision'
    }
    Write-Output 'DIRECT_ONE_LAYER_CHANGED_EXECUTABLE_REFRESH_PASS'
    $readOnly = $false
    try { [IO.File]::WriteAllText(($mount+'\should-not-create.txt'),'must fail') }
    catch { $readOnly = $true }
    if(!$readOnly -or (Test-Path -LiteralPath (Join-Path $server 'should-not-create.txt'))){throw 'LAB_WRITE_GUARD_FAILED'}
    Write-Output 'DIRECT_ONE_LAYER_READONLY_GUARD_PASS'
    Write-Output 'DIRECT_WEBDAV_SINGLE_WINFSP_EXE_INTEGRATION_PASS'
} finally {
    if($mountProcess -and !$mountProcess.HasExited) { Stop-Process -Id $mountProcess.Id -Force -ErrorAction SilentlyContinue }
    if($serverProcess -and !$serverProcess.HasExited) { Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue }
    Write-Output ('DIRECT_LAB_LOGS='+$logs)
}
