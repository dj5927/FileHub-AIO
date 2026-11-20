param([int]$Port = 18960)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$rclone=if($env:FILEHUB_LAB_RCLONE){$env:FILEHUB_LAB_RCLONE}else{
    'E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
}
$engine=if($env:FILEHUB_TEST_PUBLISHED_BRIDGE){$env:FILEHUB_TEST_PUBLISHED_BRIDGE}else{
    Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
}
$app=if($env:FILEHUB_TEST_PUBLISHED_APP){$env:FILEHUB_TEST_PUBLISHED_APP}else{
    Join-Path $repo 'bin\Release\net10.0-windows\win-x64\FileHubAIO.exe'
}
$root=Join-Path $env:TEMP ('FileHubAutoFallback_'+[Guid]::NewGuid().ToString('N'))
$server=Join-Path $root 'server'
$staged=Join-Path $root 'app'
$tools=Join-Path $staged 'tools'
$logs=Join-Path $root 'logs'
foreach($directory in @($server,$staged,$tools,$logs)){
    [IO.Directory]::CreateDirectory($directory)|Out-Null
}
[IO.File]::WriteAllText((Join-Path $server 'read-only-test.txt'),'fallback-readable')
$original=Split-Path -Parent $app
Get-ChildItem -LiteralPath $original -File|Copy-Item -Destination $staged -Force
Get-ChildItem -LiteralPath $original -Directory|Where-Object{$_.Name -ne 'tools'}|
    Copy-Item -Destination $staged -Recurse -Force
Copy-Item -Path ((Split-Path -Parent $engine)+'\*') -Destination $tools -Recurse -Force
Copy-Item -LiteralPath $rclone -Destination (Join-Path $tools 'rclone.exe') -Force
$actualApp=Join-Path $staged 'FileHubAIO.exe'
$backend=$null;$gui=$null
Write-Output ('WEBDAV_COMPATIBILITY_LAB_ROOT='+$root)
try {
    # Deliberately use STOCK rclone serve webdav. It ignores If-Match on
    # duplicate PUT; unlike strict proxy this server is NOT safe for the
    # direct writer and MUST activate the legacy compatibility route.
    $backend=Start-Process -FilePath $rclone -PassThru -NoNewWindow -ArgumentList @(
        'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--log-level','ERROR')
    $ready=$false
    for($n=0;$n -lt 70;$n++){
        try{$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port);$tcp.Dispose();$ready=$true;break}
        catch{Start-Sleep -Milliseconds 160}
    }
    if(!$ready){throw 'Loopback compatibility server unavailable'}
    $gui=Start-Process -FilePath $actualApp -PassThru -WindowStyle Hidden -ArgumentList @(
        '--direct-webdav-gui-compatibility-flow-lab',$Port)
    $exited=$false
    for($n=0;$n -lt 440;$n++){
        $gui.Refresh()
        if($gui.HasExited){$exited=$true;break}
        Start-Sleep -Milliseconds 250
    }
    if(!$exited){throw 'GUI automatic compatibility mount exceeded 110 seconds'}
    if($gui.ExitCode -ne 0){throw ('GUI automatic compatibility mount failed code='+$gui.ExitCode)}
    Write-Output 'WEBDAV_GUI_AUTOMATIC_LEGACY_FALLBACK_AND_READ_PASS'
}finally{
    if($gui -and !$gui.HasExited){Stop-Process -Id $gui.Id -Force -ErrorAction SilentlyContinue}
    # Only clean processes launched from this unique throwaway QA directory.
    Get-CimInstance Win32_Process|Where-Object{
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($staged,
            [StringComparison]::OrdinalIgnoreCase)
    }|ForEach-Object{Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue}
    if($backend -and !$backend.HasExited){Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue}
    Write-Output ('WEBDAV_COMPATIBILITY_LOGS='+$logs)
}
