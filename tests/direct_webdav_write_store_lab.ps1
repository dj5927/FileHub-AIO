param([int]$Port = 18784)
$ErrorActionPreference = 'Stop'
$repo=Split-Path -Parent $PSScriptRoot
$backend='E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
$program=Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
$root=Join-Path $env:TEMP ('FileHubWriteStore_' + [Guid]::NewGuid().ToString('N'))
$server=Join-Path $root 'server'
$journals=Join-Path $root 'journals'
$logs=Join-Path $root 'logs'
foreach($dir in @($server,$journals,$logs)){[IO.Directory]::CreateDirectory($dir)|Out-Null}
$process=$null
$proxy=$null
$worker=$null
Write-Output ('WRITE_LAB_ROOT='+$root)
try {
 $process=Start-Process $backend -PassThru -NoNewWindow -ArgumentList @(
    'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s',
    '--log-level','INFO','--log-file',(Join-Path $logs 'webdav-server.log'))
 $ready=$false
 for($i=0;$i -lt 60;$i++){
    try {$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port);$tcp.Dispose();$ready=$true;break}
    catch{Start-Sleep -Milliseconds 200}
 }
 if(!$ready){throw 'Write lab server failed to start'}
 $proxyPort=$Port+1
 $env:FILEHUB_STRICT_DAV_BACKEND=[string]$Port
 $env:FILEHUB_STRICT_DAV_FRONT=[string]$proxyPort
 $proxy=Start-Process 'python.exe' -PassThru -NoNewWindow -ArgumentList @(
    '-u',(Join-Path $PSScriptRoot 'strict_webdav_conditional_proxy.py')) `
    -RedirectStandardOutput (Join-Path $logs 'proxy.out') `
    -RedirectStandardError (Join-Path $logs 'proxy.err')
 $ready=$false
 for($i=0;$i -lt 60;$i++){
    try{$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$proxyPort);$tcp.Dispose();$ready=$true;break}
    catch{Start-Sleep -Milliseconds 200}
 }
 if(!$ready){throw 'Strict conditional proxy did not start'}
 $worker=Start-Process -FilePath $program -PassThru -NoNewWindow -ArgumentList @(
    '--direct-webdav-write-store-lab',"http://127.0.0.1:$proxyPort/",$journals) `
    -RedirectStandardOutput (Join-Path $logs 'write.out') `
    -RedirectStandardError (Join-Path $logs 'write.err')
 if(!$worker.WaitForExit(55000)) {throw 'Direct write store test exceeded 55s'}
 $worker.Refresh()
 $workerOutput=Get-Content (Join-Path $logs 'write.out') -Raw -ErrorAction SilentlyContinue
 Write-Output $workerOutput
 if($workerOutput -notmatch 'DIRECT_WEBDAV_ONE_WINFSP_WRITE_STORE_LAB_PASS'){
    Get-Content (Join-Path $logs 'write.err') -ErrorAction SilentlyContinue|Select-Object -Last 18
    throw ('Direct WebDAV write store integration failed exit='+$worker.ExitCode)
 }
 $journalItems=@(Get-ChildItem -LiteralPath $journals -Filter '*.json')
 Write-Output ('RETAINED_RECOVERY_MANIFESTS='+$journalItems.Count)
 if($journalItems.Count -lt 1){throw 'Concurrent edit must leave at least one recovery journal'}
 Write-Output 'DIRECT_WEBDAV_WRITE_STORE_RECOVERY_LAB_PASS'
 $worker=Start-Process -FilePath $program -PassThru -NoNewWindow -ArgumentList @(
    '--direct-webdav-two-client-lease-lab',"http://127.0.0.1:$proxyPort/") `
    -RedirectStandardOutput (Join-Path $logs 'lease.out') `
    -RedirectStandardError (Join-Path $logs 'lease.err')
 if(!$worker.WaitForExit(30000)){throw 'Two-client lease test exceeded 30 seconds'}
 $leaseOutput=Get-Content (Join-Path $logs 'lease.out') -Raw -ErrorAction SilentlyContinue
 Write-Output $leaseOutput
 if([string]::IsNullOrWhiteSpace($leaseOutput) -or
    $leaseOutput -notmatch 'DIRECT_WEBDAV_COOPERATIVE_TWO_CLIENT_LAB_PASS'){
    Get-Content (Join-Path $logs 'lease.err') -ErrorAction SilentlyContinue|Select-Object -Last 20
    throw 'Two-client real WebDAV cooperative lock test failed'
 }
}finally{
 if($worker -and !$worker.HasExited){Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue}
 if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
 if($process -and !$process.HasExited){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue}
 Write-Output ('WRITE_LAB_LOGS='+$logs)
}
