param([int]$Port = 18872, [switch]$SynologyVirtualRoot)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$rclone = if($env:FILEHUB_LAB_RCLONE){$env:FILEHUB_LAB_RCLONE}else{
 'E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
}
$engine = if($env:FILEHUB_TEST_PUBLISHED_BRIDGE){$env:FILEHUB_TEST_PUBLISHED_BRIDGE}else{
 Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
}
$root = Join-Path $env:TEMP ('FileHubManagedOneWinFsp_' + [Guid]::NewGuid().ToString('N'))
$server = Join-Path $root 'server'
$logs = Join-Path $root 'logs'
[IO.Directory]::CreateDirectory($server)|Out-Null
[IO.Directory]::CreateDirectory($logs)|Out-Null
if($SynologyVirtualRoot){[IO.Directory]::CreateDirectory((Join-Path $server 'Shared'))|Out-Null}
$used = @([Environment]::GetLogicalDrives() | ForEach-Object {$_.Substring(0,1)})
$letter = @('V','W','Q','P','T','U','M') | Where-Object {$used -notcontains $_} | Select-Object -First 1
if(!$letter){throw 'No free Windows drive letter'}
$drive = $letter + ':\'
$id = [Guid]::NewGuid().ToString('D')
$webdav = $null; $proxy = $null; $managed = $null; $file = $null
$env:FILEHUB_STRICT_DAV_BACKEND = [string]$Port
$env:FILEHUB_STRICT_DAV_FRONT = [string]($Port+1)
$env:FILEHUB_STRICT_DAV_DENY_ROOT_WRITES = $(if($SynologyVirtualRoot){'1'}else{'0'})
Write-Output ('MANAGED_QA_ROOT='+$root)
Write-Output ('MANAGED_QA_PROFILE_ID='+$id)

function CommandToManager([string]$command){
 $pipeName='NASMountBridge_'+([Guid]::Parse($id)).ToString('N')
 $pipe=[IO.Pipes.NamedPipeClientStream]::new('.', $pipeName,
     [IO.Pipes.PipeDirection]::InOut)
 try {
   $pipe.Connect(4500)
   $writer=[IO.StreamWriter]::new($pipe)
   $writer.AutoFlush=$true
   $reader=[IO.StreamReader]::new($pipe)
   $writer.WriteLine($command)
   return $reader.ReadLine()
 } finally {$pipe.Dispose()}
}

try {
 $webdav=Start-Process $rclone -PassThru -NoNewWindow -ArgumentList @(
   'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s',
   '--log-level','ERROR')
 $proxy=Start-Process 'python.exe' -PassThru -NoNewWindow -ArgumentList @(
   '-u',(Join-Path $PSScriptRoot 'strict_webdav_conditional_proxy.py')) `
   -RedirectStandardOutput (Join-Path $logs 'proxy.out') `
   -RedirectStandardError (Join-Path $logs 'proxy.err')
 $ready=$false
 for($n=0;$n -lt 55;$n++){
   try {$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port+1);
     $tcp.Dispose();$ready=$true;break}
   catch{Start-Sleep -Milliseconds 150}
 }
 if(!$ready){throw 'Loopback WebDAV strict proxy unavailable'}
 $managed=Start-Process -FilePath $engine -PassThru -NoNewWindow -ArgumentList @(
    '--direct-webdav-managed-loopback-lab',($Port+1),($letter+':'),'write',$id) `
    -RedirectStandardOutput (Join-Path $logs 'managed.out') `
    -RedirectStandardError (Join-Path $logs 'managed.err')
 $ready=$false
 for($n=0;$n -lt 90;$n++){
   Start-Sleep -Milliseconds 200
   if($managed.HasExited){break}
   if(Test-Path (Join-Path $logs 'managed.out')){
     $s=Get-Content (Join-Path $logs 'managed.out') -Raw -ErrorAction SilentlyContinue
     if($s -match 'DIRECT_WEBDAV_MANAGED_READY'){$ready=$true;break}
   }
 }
 if(!$ready){Get-Content (Join-Path $logs 'managed.err') -Tail 12 -ErrorAction SilentlyContinue;
   throw 'Managed engine did not mount'}
 Write-Output 'DIRECT_MANAGED_ONE_WINFSP_GUI_SERVICE_START_PASS'
 $response=CommandToManager 'PING'
 if($response -notmatch '^READY [0-9]+ .* (SAFE|BUSY)$'){
   throw ('Unexpected status command '+$response)}
 Write-Output 'DIRECT_MANAGED_STATUS_PIPE_PASS'
 $relative=$(if($SynologyVirtualRoot){'Shared\live-open.txt'}else{'live-open.txt'})
 $target=Join-Path $drive $relative
 $file=[IO.FileStream]::new($target,[IO.FileMode]::CreateNew,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
 $payload=[Text.Encoding]::UTF8.GetBytes('managed-direct-webdav-content')
 $file.Write($payload,0,$payload.Length)
 $file.Flush($true)
 $response=CommandToManager 'QUIT'
 if($response -notmatch '^OPEN_OR_RECOVERY_PENDING '){
   throw ('Unsafe unmount accepted while file open: '+$response)}
 Write-Output 'DIRECT_MANAGED_OPEN_FILE_UNMOUNT_BLOCKED_PASS'
 $file.Dispose();$file=$null
 $remote=Join-Path $server $relative
 $saved=$false
 for($n=0;$n -lt 30;$n++){
   Start-Sleep -Milliseconds 180
   if(Test-Path $remote){
     if([IO.File]::ReadAllText($remote) -eq 'managed-direct-webdav-content'){
       $saved=$true;break
     }
   }
 }
 if(!$saved){throw 'Manager close did not persist content'}
 Write-Output 'DIRECT_MANAGED_FILE_CLOSE_SERVER_COMMIT_PASS'
 $reply=''
 for($n=0;$n -lt 30;$n++){
   $reply=CommandToManager 'QUIT'
   if($reply -eq 'OK'){break}
   Start-Sleep -Milliseconds 200
 }
 if($reply -ne 'OK'){throw ('Manager never completed safe unmount '+$reply)}
 if(-not $managed.WaitForExit(14000)){throw 'Managed process did not exit after QUIT'}
 Write-Output 'DIRECT_MANAGED_SAFE_UNMOUNT_AND_PROCESS_EXIT_PASS'
 Write-Output 'DIRECT_MANAGED_WEBDAV_GUI_LIFECYCLE_LAB_PASS'
 if($SynologyVirtualRoot){Write-Output 'DIRECT_SYNLOGY_VIRTUAL_ROOT_WRITABLE_SHARE_PASS'}
} finally {
 if($file){$file.Dispose()}
 if($managed -and !$managed.HasExited){Stop-Process -Id $managed.Id -Force -ErrorAction SilentlyContinue}
 if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
 if($webdav -and !$webdav.HasExited){Stop-Process -Id $webdav.Id -Force -ErrorAction SilentlyContinue}
 Remove-Item Env:\FILEHUB_STRICT_DAV_DENY_ROOT_WRITES -ErrorAction SilentlyContinue
 Write-Output ('MANAGED_QA_LOGS='+$logs)
}
