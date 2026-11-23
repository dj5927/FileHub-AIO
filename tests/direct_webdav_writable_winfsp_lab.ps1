param([int]$Port = 18800, [switch]$SkipExcel, [switch]$ExcelInteropOnly)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$rclone=if($env:FILEHUB_LAB_RCLONE){$env:FILEHUB_LAB_RCLONE}else{'E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'}
$bridge=if($env:FILEHUB_TEST_PUBLISHED_BRIDGE){$env:FILEHUB_TEST_PUBLISHED_BRIDGE}else{Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'}
$root=Join-Path $env:TEMP ('FileHubDirectWritableWinFsp_'+[guid]::NewGuid().ToString('N'))
$server=Join-Path $root 'server'
$journals=Join-Path $root 'journals'
$logs=Join-Path $root 'logs'
foreach($p in @($server,$journals,$logs)){[IO.Directory]::CreateDirectory($p)|Out-Null}
$used=[Environment]::GetLogicalDrives()|ForEach-Object {$_.Substring(0,1).ToUpperInvariant()}
$letter=@('V','W','U','T','Q','P','M','N')|Where-Object {$used -notcontains $_}|Select-Object -First 1
if(!$letter){throw 'No free lab drive letter'}
$drive=$letter+':\'
$backend=$null;$proxy=$null;$mount=$null;$excel=$null;$book=$null
Write-Output ('ONE_LAYER_RW_ROOT='+$root)
Write-Output ('ONE_LAYER_RW_DRIVE='+$drive)
try {
 $backend=Start-Process $rclone -PassThru -NoNewWindow -ArgumentList @(
  'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s',
  '--log-level','INFO','--log-file',(Join-Path $logs 'server.log'))
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
 if(!$ready){throw 'Loopback strict WebDAV proxy not ready'}
 $env:FILEHUB_DIRECT_DEBUG='1'
 $env:FILEHUB_LAB_PROFILE_PIPELINE='1'
 $mount=Start-Process -FilePath $bridge -PassThru -NoNewWindow -ArgumentList @(
  '--direct-webdav-writable-lab',"http://127.0.0.1:$proxyPort/",($letter+':'),$journals) `
  -RedirectStandardOutput (Join-Path $logs 'mount.out') `
  -RedirectStandardError (Join-Path $logs 'mount.err')
 $ready=$false
 for($i=0;$i -lt 70;$i++){
   if(Test-Path $drive){$ready=$true;break}
   if($mount.HasExited){break}
   Start-Sleep -Milliseconds 250
 }
 if(!$ready){Get-Content (Join-Path $logs 'mount.err') -Tail 15 -ErrorAction SilentlyContinue;throw 'Writable single WinFsp did not mount'}
 Write-Output 'ONE_LAYER_RW_MOUNT_PASS'

 # Windows Explorer checks GetDiskFreeSpaceEx BEFORE copying any file.
 # A drive reporting 0 bytes available is unusable regardless of whether
 # the WebDAV server supports PUT. This exactly reproduces the reported
 # "0 bytes available / not enough space" dialog.
 Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FileHubDiskQuotaProbe {
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
 public static extern bool GetDiskFreeSpaceEx(string path,
    out ulong available, out ulong total, out ulong free);
}
'@
 [uint64]$available=0;[uint64]$total=0;[uint64]$free=0
 if(![FileHubDiskQuotaProbe]::GetDiskFreeSpaceEx($drive,
    [ref]$available,[ref]$total,[ref]$free)){
   throw ('WinFsp GetDiskFreeSpaceEx failed '+
      [Runtime.InteropServices.Marshal]::GetLastWin32Error())
 }
 Write-Output ('ONE_LAYER_RW_VOLUME total='+$total+' available='+$available)
 if($total -lt 1GB -or $available -lt 32MB -or $available -gt $total){
   throw 'WEB_DAV_VOLUME_ZERO_FREE_OR_INVALID_EXPLORER_COPY_BLOCKED'
 }
 Write-Output 'ONE_LAYER_RW_EXPLORER_COPY_HAS_SPACE_PASS'

 # Excel/RaiDrive interoperability: a Microsoft owner file created in the
 # mounted drive must become visible on the real NAS before its WinFsp write
 # returns. Other clients check it without FileHub's private lock folder.
 $workbookName='office-interop.xlsx'
 $ownerName='~$'+$workbookName
 $sourceWorkbook=Join-Path $server $workbookName
 $mountedWorkbook=Join-Path $drive $workbookName
 $remoteOwner=Join-Path $server $ownerName
 $mountedOwner=Join-Path $drive $ownerName
 $legacyDir=Join-Path $server ($workbookName+'.nasmount.lock')
 [IO.File]::WriteAllBytes($mountedWorkbook,[byte[]](1,2,3,4,5,6))
 if(!(Test-Path $sourceWorkbook)){throw 'Interop workbook not uploaded to NAS'}
 $ownerUrl='http://127.0.0.1:'+$proxyPort+'/'+[Uri]::EscapeDataString($ownerName)
 Add-Type -AssemblyName System.Net.Http
 $client=[System.Net.Http.HttpClient]::new()
 $request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Put,$ownerUrl)
 $request.Headers.TryAddWithoutValidation('If-None-Match','*')|Out-Null
 $request.Content=[System.Net.Http.ByteArrayContent]::new([byte[]](11,12,13,14))
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw ('Remote RaiDrive fixture PUT HTTP '+[int]$response.StatusCode)}
 $response.Dispose();$request.Dispose()
 $blocked=$false
 try {
   $opened=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
     [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
   $opened.Dispose()
 }catch [IO.IOException] { $blocked=$true }
 if(!$blocked){throw 'RaiDrive-style existing ~$ owner did not block FileHub write'}
 $readonly=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
   [IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
 $readonly.Dispose()
 Write-Output 'ONE_LAYER_RW_REMOTE_RAI_OWNER_BLOCKS_EDIT_BUT_READ_ALLOWED_PASS'
 $head=$client.SendAsync([System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::Head,$ownerUrl)).GetAwaiter().GetResult()
 $etag=$head.Headers.ETag.ToString();$head.Dispose()
 $request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Delete,$ownerUrl)
 $request.Headers.TryAddWithoutValidation('If-Match',$etag)|Out-Null
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw ('Fixture DELETE HTTP '+[int]$response.StatusCode)}
 $response.Dispose();$request.Dispose()
 $legacyUrl='http://127.0.0.1:'+$proxyPort+'/'+
   [Uri]::EscapeDataString($workbookName+'.nasmount.lock')
 $request=[System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::new('MKCOL'),$legacyUrl)
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw ('Legacy MKCOL HTTP '+[int]$response.StatusCode)}
 $response.Dispose();$request.Dispose()
 $blocked=$false
 try {
   $opened=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
     [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
   $opened.Dispose()
 }catch [IO.IOException] { $blocked=$true }
 if(!$blocked){throw 'Existing legacy FileHub sidecar did not block write'}
 $request=[System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::Delete,$legacyUrl)
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw ('Legacy DELETE HTTP '+[int]$response.StatusCode)}
 $response.Dispose();$request.Dispose()
 Write-Output 'ONE_LAYER_RW_LEGACY_LOCK_READ_COMPAT_PASS'
 $opened=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 try {
   $ownerStream=[IO.File]::Open($mountedOwner,[IO.FileMode]::CreateNew,
     [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
   try {
     $excelBytes=[byte[]](1,0,2,0,3,0,4,0,5,0,6,0)
     $ownerStream.Write($excelBytes,0,$excelBytes.Length)
     $ownerStream.Flush($true)
     if(!(Test-Path $remoteOwner) -or
        [Convert]::ToBase64String([IO.File]::ReadAllBytes($remoteOwner)) -ne
        [Convert]::ToBase64String($excelBytes)){
        throw 'Excel owner was not immediately published to NAS'
     }
     if(Test-Path $legacyDir){throw 'Excel owner-only mode created nasmount.lock'}
   } finally { $ownerStream.Dispose() }
 } finally { $opened.Dispose() }
 # A workbook saved through this SAME FileHub mount may reopen itself
 # internally during Excel SaveAs/atomic replacement. It is allowed only
 # because its exact remote owner ETag was published by THIS mount.
 # An unrelated RaiDrive/FileHub mount lacks that local ETag ownership and
 # takes the remote-owner conflict path tested above.
 $opened=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 $opened.Dispose()
 Write-Output 'ONE_LAYER_RW_OWNED_EXCEL_SAME_MOUNT_SAVE_REOPEN_PASS'
 [IO.File]::Delete($mountedOwner)
 if(Test-Path $remoteOwner){throw 'Manual ~$ cleanup did not remove NAS owner'}
 $opened=[IO.File]::Open($mountedWorkbook,[IO.FileMode]::Open,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 $opened.Dispose()
 if(Test-Path $legacyDir){throw 'Excel session left nasmount.lock directory'}
 Write-Output 'ONE_LAYER_RW_EXCEL_OWNER_ONLY_RAI_INTEROP_PASS'

 # Some Office versions replace the workbook's first two characters with
 # ~$ instead of prefixing the complete name. Verify Korean names too.
 $korName='11'+[char]0xC6D4+[char]0xB9E4+[char]0xCD9C+'.xlsx'
 $korLocal=Join-Path $drive $korName
 [IO.File]::WriteAllBytes($korLocal,[byte[]](7,8,9))
 $korOwner='~$'+$korName.Substring(2)
 $korOwnerUrl='http://127.0.0.1:'+$proxyPort+'/'+
   [Uri]::EscapeDataString($korOwner)
 $request=[System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::Put,$korOwnerUrl)
 $request.Headers.TryAddWithoutValidation('If-None-Match','*')|Out-Null
 $request.Content=[System.Net.Http.ByteArrayContent]::new([byte[]](13,14))
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw 'Korean alternative owner fixture PUT failed'}
 $response.Dispose();$request.Dispose()
 $blocked=$false
 try {
   $opened=[IO.File]::Open($korLocal,[IO.FileMode]::Open,
     [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
   $opened.Dispose()
 }catch [IO.IOException] {$blocked=$true}
 if(!$blocked){throw 'Korean alternate ~$ owner not honored'}
 $head=$client.SendAsync([System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::Head,$korOwnerUrl)).GetAwaiter().GetResult()
 $etag=$head.Headers.ETag.ToString();$head.Dispose()
 $request=[System.Net.Http.HttpRequestMessage]::new(
   [System.Net.Http.HttpMethod]::Delete,$korOwnerUrl)
 $request.Headers.TryAddWithoutValidation('If-Match',$etag)|Out-Null
 $response=$client.SendAsync($request).GetAwaiter().GetResult()
 if(!$response.IsSuccessStatusCode){throw 'Korean owner manual cleanup failed'}
 $response.Dispose();$request.Dispose()
 $opened=[IO.File]::Open($korLocal,[IO.FileMode]::Open,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 $opened.Dispose()
 Write-Output 'ONE_LAYER_RW_KOREAN_ALTERNATE_EXCEL_OWNER_PASS'
 if($ExcelInteropOnly){
   $app=New-Object -ComObject Excel.Application
   $app.Visible=$false
   $app.DisplayAlerts=$false
   $workbook=$null
   try {
     $document=Join-Path $drive 'ActualInterop.xlsx'
     $workbook=$app.Workbooks.Add()
     $workbook.Worksheets.Item(1).Cells.Item(2,2).Value2='INITIAL'
     $workbook.SaveAs($document,51)
     if(!(Test-Path (Join-Path $server '~$ActualInterop.xlsx')) -and
        !(Test-Path (Join-Path $server '~$tualInterop.xlsx'))){
       throw 'Real Excel ~$ owner file not immediately visible on NAS'
     }
     Write-Output 'ONE_LAYER_RW_REAL_EXCEL_OWNER_IMMEDIATELY_VISIBLE_PASS'
     Write-Output 'ONE_LAYER_RW_REAL_EXCEL_OWNER_MODE_SAVE_AS_PASS'
     foreach($round in 1..3){
       $workbook.Worksheets.Item(1).Cells.Item(2,2).Value2=('ROUND_'+$round)
       $workbook.Save()
       Write-Output ('ONE_LAYER_RW_REAL_EXCEL_OWNER_MODE_SAVE_'+$round+'_PASS')
     }
     $workbook.Close($false)
     [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($workbook)
     $workbook=$null
     $actual=Join-Path $server 'ActualInterop.xlsx'
     if(!(Test-Path $actual) -or (Get-Item $actual).Length -lt 4000){
       throw 'Excel Save did not publish full workbook'
     }
     if(Test-Path (Join-Path $server 'ActualInterop.xlsx.nasmount.lock')){
       throw 'Excel Save created legacy nasmount lock directory'
     }
     Write-Output 'ONE_LAYER_RW_REAL_EXCEL_OWNER_ONLY_THREE_SAVES_PASS'
   }finally{
     if($workbook){try{$workbook.Close($false)}catch{}}
     $app.Quit()
     [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($app)
     $client.Dispose()
   }
   return
 }

 # Cooperative Office locks are real NAS directories for cross-PC editing,
 # but must never enter the virtual filesystem or a WinFsp Notify event.
 # Exercise watcher creation/removal even without Office installed (CI).
 $null=@([IO.Directory]::GetFileSystemEntries($drive))
 $watchLog=Join-Path $logs 'mount.err'
 $watchBefore=@(Select-String -LiteralPath $watchLog -Pattern 'DIRECT_WATCH_NOTIFY result=' -ErrorAction SilentlyContinue).Count
 $lockName='lock-visibility-test.xlsx.nasmount.lock'
 $remoteLock=Join-Path $server $lockName
 $mountedLock=Join-Path $drive $lockName
 [IO.Directory]::CreateDirectory($remoteLock)|Out-Null
 [IO.File]::WriteAllText((Join-Path $remoteLock 'owner.json'),'{}')
 if(!(Test-Path $remoteLock)){throw 'Remote cooperative lock fixture was not created'}
 foreach($phase in 1..2){
   # The second check happens after the 15-second remote change poll.
   if($phase -eq 2){Start-Sleep -Seconds 18}
   $visible=@([IO.Directory]::GetFileSystemEntries($drive))
   if($visible|Where-Object { [IO.Path]::GetFileName($_) -eq $lockName }){
     throw ('Internal lock leaked into directory listing phase='+$phase)
   }
   if([IO.Directory]::Exists($mountedLock) -or (Test-Path (Join-Path $mountedLock 'owner.json'))){
     throw ('Internal lock accessible from mounted filesystem phase='+$phase)
   }
 }
 $watchWhilePresent=@(Select-String -LiteralPath $watchLog -Pattern 'DIRECT_WATCH_NOTIFY result=' -ErrorAction SilentlyContinue).Count
 if($watchWhilePresent -ne $watchBefore){
   throw 'Hidden internal lock triggered an Explorer change notification'
 }
 [IO.File]::Delete((Join-Path $remoteLock 'owner.json'))
 [IO.Directory]::Delete($remoteLock)
 Start-Sleep -Seconds 17
 $watchAfter=@(Select-String -LiteralPath $watchLog -Pattern 'DIRECT_WATCH_NOTIFY result=' -ErrorAction SilentlyContinue).Count
 if($watchAfter -ne $watchBefore){
   throw 'Removing hidden lock triggered an Explorer change notification'
 }
 Write-Output 'ONE_LAYER_RW_LOCK_FOLDER_NEVER_VISIBLE_OR_NOTIFIED_PASS'

 # Explorer F2 / Rename on a directory is not the same as an Office file
 # replacement. The directory has no guaranteed strong ETag and the
 # WinFsp callback must issue a directory MOVE, not file GET/PUT.
 $folderOld=Join-Path $drive 'rename-folder-before'
 $folderNew=Join-Path $drive 'rename-folder-after'
 [IO.Directory]::CreateDirectory($folderOld)|Out-Null
 $nested=Join-Path $folderOld 'child.txt'
 [IO.File]::WriteAllText($nested,'FOLDER_MOVE_MUST_KEEP_CHILDREN')
 Start-Sleep -Seconds 2
 [IO.Directory]::Move($folderOld,$folderNew)
 Start-Sleep -Seconds 2
 if((Test-Path (Join-Path $server 'rename-folder-before')) -or
   !(Test-Path (Join-Path $server 'rename-folder-after\child.txt')) -or
   ([IO.File]::ReadAllText((Join-Path $server 'rename-folder-after\child.txt')) -ne
     'FOLDER_MOVE_MUST_KEEP_CHILDREN')){
   throw 'Explorer folder rename failed or child file lost'
 }
 Write-Output 'ONE_LAYER_RW_DIRECTORY_RENAME_WITH_CHILD_PASS'
 $folderKorean=Join-Path $drive '회사자료-변경됨'
 [IO.Directory]::Move($folderNew,$folderKorean)
 Start-Sleep -Seconds 1
 $serverKorean=Join-Path $server '회사자료-변경됨\child.txt'
 if((Test-Path (Join-Path $server 'rename-folder-after')) -or
    !(Test-Path $serverKorean) -or
    [IO.File]::ReadAllText($serverKorean) -ne 'FOLDER_MOVE_MUST_KEEP_CHILDREN'){
   throw 'Korean folder rename failed or contents were altered'
 }
 Write-Output 'ONE_LAYER_RW_KOREAN_DIRECTORY_RENAME_WITH_CHILD_PASS'

 $path=Join-Path $drive 'test-created.txt'
 [IO.File]::WriteAllText($path,'first-save')
 Start-Sleep -Seconds 2
 if(!(Test-Path (Join-Path $server 'test-created.txt'))){throw 'Create did not upload to NAS'}
 if([IO.File]::ReadAllText((Join-Path $server 'test-created.txt')) -ne 'first-save'){throw 'Wrong first save'}
 Write-Output 'ONE_LAYER_RW_CREATE_AND_UPLOAD_PASS'
 # Exercise Explorer's native new-file CopyFile path, not just a tiny
 # WriteAllText (which cannot reveal a 99%-on-first-paint regression).
 $largeLocal=Join-Path $root 'source-progressive-24mb.zip'
 $largeMounted=Join-Path $drive 'progressive-24mb.zip'
 $largeServer=Join-Path $server 'progressive-24mb.zip'
 Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
public static class FileHubCopyFileExProgress {
  public delegate uint Callback(long total, long transferred,
    long stream, long streamTransferred, uint streamNumber,
    uint callbackReason, IntPtr source, IntPtr dest, IntPtr data);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  public static extern bool CopyFileEx(string source, string target,
    Callback callback, IntPtr data, ref int cancel, uint flags);
  public static readonly List<long> Bytes = new List<long>();
  public static readonly List<long> Times = new List<long>();
  private static readonly Stopwatch Clock = new Stopwatch();
  private static readonly Callback Reporter = (total, done, stream, streamDone,
    number, reason, source, dest, data) => {
    lock(Bytes) {
      if (Bytes.Count == 0 || done > Bytes[Bytes.Count-1]) {
        Bytes.Add(done);
        Times.Add(Clock.ElapsedMilliseconds);
      }
    }
    return 0;
  };
  public static bool Copy(string source, string target) {
    Bytes.Clear(); Times.Clear(); Clock.Restart();
    int cancel=0;
    bool ok=CopyFileEx(source,target,Reporter,IntPtr.Zero,ref cancel,0);
    Clock.Stop();
    return ok;
  }
}
'@
 $payload=New-Object byte[] (24*1024*1024)
 [Random]::new(20261123).NextBytes($payload)
 [IO.File]::WriteAllBytes($largeLocal,$payload)
 $copyClock=[Diagnostics.Stopwatch]::StartNew()
 if(![FileHubCopyFileExProgress]::Copy($largeLocal,$largeMounted)){
   throw ('CopyFileEx failed '+[Runtime.InteropServices.Marshal]::GetLastWin32Error())
 }
 $copyClock.Stop()
 if(!(Test-Path $largeServer)){throw 'Progressive Explorer copy destination missing'}
 $sourceHash=(Get-FileHash $largeLocal -Algorithm SHA256).Hash
 $remoteHash=(Get-FileHash $largeServer -Algorithm SHA256).Hash
 if($sourceHash -ne $remoteHash){
   throw 'Progressive Explorer copy SHA256 differs from source'
 }
 Write-Output ('ONE_LAYER_RW_PROGRESSIVE_EXPLORER_COPY_PASS bytes='+$payload.Length+
    ' elapsedMs='+$copyClock.ElapsedMilliseconds+' sha256='+$remoteHash)
 $sample=@()
 for($i=0;$i -lt [FileHubCopyFileExProgress]::Bytes.Count;$i++){
   if($i -eq 0 -or $i -eq [FileHubCopyFileExProgress]::Bytes.Count-1 -or $i%40 -eq 0){
     $sample+=('{0:N1}%@{1}ms' -f (100.0*[FileHubCopyFileExProgress]::Bytes[$i]/$payload.Length),[FileHubCopyFileExProgress]::Times[$i])
   }
 }
 Write-Output ('ONE_LAYER_RW_NATIVE_COPY_PROGRESS samples='+
   [FileHubCopyFileExProgress]::Bytes.Count+' '+($sample -join ','))
 if([int]$env:FILEHUB_STRICT_DAV_STAGE_BYTES_PER_SEC -gt 0){
   $points=[FileHubCopyFileExProgress]::Times
   if($points.Count -lt 5 -or
      ($points[$points.Count-1]-$points[0]) -lt 2000){
      throw 'Windows copy progress advanced before slow WebDAV upload'
   }
   Write-Output 'ONE_LAYER_RW_NATIVE_COPY_PROGRESS_BACKPRESSURE_PASS'
 }
 # Explorer also copies non-aligned file sizes. If a server cannot accept
 # the originally suggested allocation as a final Content-Length, safe
 # journal/PUT fallback must still preserve every byte.
 $oddLocal=Join-Path $root 'source-progressive-odd.zip'
 $oddDest=Join-Path $drive 'progressive-odd.zip'
 $oddServer=Join-Path $server 'progressive-odd.zip'
 $oddData=New-Object byte[] 23600123
 [Random]::new(20261124).NextBytes($oddData)
 [IO.File]::WriteAllBytes($oddLocal,$oddData)
 if(![FileHubCopyFileExProgress]::Copy($oddLocal,$oddDest)){
   throw ('Odd CopyFileEx failed '+
     [Runtime.InteropServices.Marshal]::GetLastWin32Error())
 }
 if((Get-FileHash $oddLocal -Algorithm SHA256).Hash -ne
    (Get-FileHash $oddServer -Algorithm SHA256).Hash){
    throw 'Odd-length progressive copy content mismatch'
 }
 Write-Output ('ONE_LAYER_RW_ODD_SIZE_COPY_PASS bytes='+$oddData.Length+
    ' progressSamples='+[FileHubCopyFileExProgress]::Bytes.Count)
 foreach($round in 2..3){
   [IO.File]::WriteAllText($path,"save-$round")
   Start-Sleep -Seconds 2
   $actual=[IO.File]::ReadAllText((Join-Path $server 'test-created.txt'))
   if($actual -ne "save-$round"){throw "Repeat save $round failed actual=$actual"}
   Write-Output ('ONE_LAYER_RW_REPEAT_SAVE_'+$round+'_PASS')
 }
 [IO.File]::Move($path,(Join-Path $drive 'test-renamed.txt'))
 Start-Sleep -Seconds 2
 if(!(Test-Path (Join-Path $server 'test-renamed.txt'))){throw 'MOVE did not reach NAS'}
 Write-Output 'ONE_LAYER_RW_MOVE_PASS'
 [IO.File]::Delete((Join-Path $drive 'test-renamed.txt'))
 Start-Sleep -Seconds 2
 if(Test-Path (Join-Path $server 'test-renamed.txt')){throw 'DELETE did not reach NAS'}
 Write-Output 'ONE_LAYER_RW_DELETE_PASS'

 # Real Excel may replace an existing target by moving its temporary file
 # with MOVEFILE_REPLACE_EXISTING, and will leave E2770D12.tmp behind if the
 # virtual filesystem sends MOVE Overwrite:F for every destination.
 $replaced=Join-Path $drive 'Existing-Save-Target.txt'
 [IO.File]::WriteAllText($replaced,'ORIGINAL_CONTENT')
 Start-Sleep -Seconds 1
 Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FileHubNativeReplace {
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
 public static extern bool MoveFileEx(string source, string target, uint flags);
}
'@
 foreach($round in 1..3){
   $tmp=Join-Path $drive ('E2770D1'+$round+'.tmp')
   [IO.File]::WriteAllText($tmp,('REPLACED_CONTENT_'+$round))
   if(![FileHubNativeReplace]::MoveFileEx($tmp,$replaced,1)){
     throw ('MoveFileEx replace-existing failed WindowsError='+
       [Runtime.InteropServices.Marshal]::GetLastWin32Error())
   }
   Start-Sleep -Seconds 1
   $serverTarget=Join-Path $server 'Existing-Save-Target.txt'
   $serverTmp=Join-Path $server ('E2770D1'+$round+'.tmp')
   if([IO.File]::ReadAllText($serverTarget) -ne ('REPLACED_CONTENT_'+$round)){
     throw ('Replace-existing failed at iteration '+$round)
   }
   if((Test-Path $serverTmp) -or (Test-Path $tmp)){
     throw ('Excel-style temporary file remained after replacement '+$round)
   }
   Write-Output ('ONE_LAYER_RW_EXCEL_TEMP_REPLACE_EXISTING_'+$round+'_PASS')
 }

 $image=Join-Path $drive 'photo-test.jpg'
 $originalImage=New-Object byte[] 524288
 [Random]::new(42).NextBytes($originalImage)
 [IO.File]::WriteAllBytes($image,$originalImage)
 Start-Sleep -Seconds 1
 $imgWatch=[Diagnostics.Stopwatch]::StartNew()
 $readImage=[IO.File]::ReadAllBytes($image)
 $hashEngine=[Security.Cryptography.SHA256]::Create()
 $actualHash=[BitConverter]::ToString($hashEngine.ComputeHash($readImage))
 $expectedHash=[BitConverter]::ToString($hashEngine.ComputeHash($originalImage))
 $hashEngine.Dispose()
 if($actualHash -ne $expectedHash){
   throw 'Image read after upload differs from source'
 }
 Write-Output ('ONE_LAYER_RW_IMAGE_READ_AFTER_UPLOAD_MS='+$imgWatch.ElapsedMilliseconds)
 Write-Output 'ONE_LAYER_RW_IMAGE_READ_AFTER_UPLOAD_PASS'

 $exe=Join-Path $drive 'DirectDownloadTests.exe'
 [IO.File]::Copy("$env:windir\System32\where.exe",$exe)
 Start-Sleep -Seconds 2
 $original=(Get-FileHash "$env:windir\System32\where.exe" -Algorithm SHA256).Hash
 $remote=(Get-FileHash $exe -Algorithm SHA256).Hash
 if($original -ne $remote){throw 'EXE uploaded/read content mismatch'}
 $sw=[Diagnostics.Stopwatch]::StartNew()
 $version=[Diagnostics.FileVersionInfo]::GetVersionInfo($exe)
 Write-Output ('ONE_LAYER_RW_EXE_VERSION_MS='+$sw.ElapsedMilliseconds)
 Write-Output 'ONE_LAYER_RW_EXE_SHA256_PASS'

 if(-not $SkipExcel){
 $excel=New-Object -ComObject Excel.Application
 $excel.Visible=$false
 $excel.DisplayAlerts=$false
 $book=$excel.Workbooks.Add()
 $book.Worksheets.Item(1).Cells.Item(2,2).Value2='FIRST'
 $document=Join-Path $drive 'LabActual.xlsx'
 $book.SaveAs($document,51)
 Write-Output 'ONE_LAYER_RW_EXCEL_SAVE_1_RETURNED'
 $probeOut=Join-Path $logs 'live-excel-lock.out'
 $probeErr=Join-Path $logs 'live-excel-lock.err'
 $probe=Start-Process -FilePath $bridge -PassThru -NoNewWindow -ArgumentList @(
   '--direct-webdav-assert-live-excel-lock',"http://127.0.0.1:$proxyPort/",'LabActual.xlsx') `
   -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr
 if(!$probe.WaitForExit(14000)){throw 'Live Excel second-client lock probe timed out'}
 $probeText=Get-Content $probeOut -Raw -ErrorAction SilentlyContinue
 Write-Output $probeText
 if($probeText -notmatch 'DIRECT_WEBDAV_LIVE_EXCEL_SECOND_PC_EDIT_BLOCKED_PASS'){
    Get-Content $probeErr -ErrorAction SilentlyContinue|Select-Object -Last 14
    throw 'Second FileHub editor was not blocked while actual Excel workbook was open'
 }
 foreach($n in 2..3){
   $book.Worksheets.Item(1).Cells.Item(2,2).Value2=('SAVE_'+$n)
   $book.Save()
   Write-Output ('ONE_LAYER_RW_EXCEL_SAVE_'+$n+'_RETURNED')
 }
 $book.Close($false)
 [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($book)
 $book=$null
 $excel.Quit()
 [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($excel)
 $excel=$null
 Start-Sleep -Seconds 2
 $serverDoc=Join-Path $server 'LabActual.xlsx'
 if(!(Test-Path $serverDoc)){throw 'Server Excel document missing after 3 saves'}
 $validate=New-Object -ComObject Excel.Application
 $validate.DisplayAlerts=$false
 try {
   $testbook=$validate.Workbooks.Open($serverDoc,0,$true)
   try{$v=[string]$testbook.Worksheets.Item(1).Cells.Item(2,2).Value2;if($v -ne 'SAVE_3'){throw ('Final workbook value='+$v)}}
   finally{$testbook.Close($false);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($testbook)}
 }finally{$validate.Quit();[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($validate)}
 Write-Output 'ONE_LAYER_RW_REAL_EXCEL_THREE_SAVES_SERVER_VERIFIED_PASS'
 # Reopen an EXISTING workbook in a Korean folder, the pattern that fails
 # in real NAS Excel sessions. Do not use any real NAS path or user document.
 $folder=Join-Path $drive '00.회사자료\2026\11월'
 [IO.Directory]::CreateDirectory($folder)|Out-Null
 $existingXlsx=Join-Path $folder '11월매출.xlsx'
 [IO.File]::Copy($serverDoc,$existingXlsx)
 $reopen=New-Object -ComObject Excel.Application
 $reopen.DisplayAlerts=$false
 $reopen.Visible=$false
 $reopenBook=$null
 try {
   $reopenBook=$reopen.Workbooks.Open($existingXlsx,0,$false)
   if($reopenBook.ReadOnly){throw 'Existing workbook opened readonly'}
   Write-Output 'ONE_LAYER_RW_EXISTING_KOREAN_XLSX_REOPEN_PASS'
   foreach($n in 1..5){
     $reopenBook.Worksheets.Item(1).Cells.Item(2,2).Value2=('KOREAN_REOPEN_SAVE_'+$n)
     $reopenBook.Save()
     Write-Output ('ONE_LAYER_RW_EXISTING_KOREAN_XLSX_SAVE_'+$n+'_RETURNED')
   }
   $reopenBook.Close($false)
   [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($reopenBook)
   $reopenBook=$null
 } finally {
   if($reopenBook){try{$reopenBook.Close($false)}catch{};[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($reopenBook)}
   $reopen.Quit()
   [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($reopen)
 }
 $serverExisting=Join-Path $server '00.회사자료\2026\11월\11월매출.xlsx'
 if(!(Test-Path $serverExisting)){throw 'Existing Korean XLSX missing on server'}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $checkZip=[IO.Compression.ZipFile]::OpenRead($serverExisting)
 try {
   $shared=$checkZip.GetEntry('xl/sharedStrings.xml')
   if(!$shared){throw 'Korean workbook missing shared strings'}
   $reader=[IO.StreamReader]::new($shared.Open())
   try{$value=$reader.ReadToEnd()}finally{$reader.Dispose()}
   if($value -notmatch 'KOREAN_REOPEN_SAVE_5'){
     throw 'Latest existing workbook content was not committed to WebDAV'
   }
 }finally{$checkZip.Dispose()}
 Write-Output 'ONE_LAYER_RW_EXISTING_KOREAN_EXCEL_FIVE_SAVES_VERIFIED_PASS'
 # Windows Excel queries GetFileInfo repeatedly on the SAME write-opened
 # handle. Its timestamp must not change with wall clock time while idle.
 Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class FileHubWinFspTimeProbe {
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetFileTime(SafeFileHandle file, out long created,
        out long accessed, out long modified);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern SafeFileHandle CreateFile(string path, uint desiredAccess,
        uint shareMode, System.IntPtr security, uint creation,
        uint flags, System.IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool SetFileTime(SafeFileHandle file,
        System.IntPtr created, System.IntPtr accessed, ref long modified);
}
'@
 $timeHandle=[IO.FileStream]::new($existingXlsx,[IO.FileMode]::Open,
   [IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
 try {
   [long]$created=0;[long]$accessed=0;[long]$first=0;[long]$second=0
   if(![FileHubWinFspTimeProbe]::GetFileTime($timeHandle.SafeFileHandle,
      [ref]$created,[ref]$accessed,[ref]$first)){throw 'GetFileTime first call failed'}
   Start-Sleep -Milliseconds 2300
   if(![FileHubWinFspTimeProbe]::GetFileTime($timeHandle.SafeFileHandle,
      [ref]$created,[ref]$accessed,[ref]$second)){throw 'GetFileTime second call failed'}
   if($first -ne $second){
     throw ('Excel open-writer LastWriteTime changed while idle: '+$first+' -> '+$second)
   }
 } finally {$timeHandle.Dispose()}
 Write-Output 'ONE_LAYER_RW_EXCEL_OPEN_HANDLE_TIMESTAMP_STABLE_PASS'
 # Excel also opens an existing XLSX with FILE_WRITE_ATTRIBUTES but no
 # FILE_WRITE_DATA. Such a handle must support SetBasicInfo, not route to
 # the read-only backend and fail with STATUS_ACCESS_DENIED.
 $attrHandle=[FileHubWinFspTimeProbe]::CreateFile($existingXlsx,
   0x180, 7, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
 if($attrHandle.IsInvalid){throw 'Excel FILE_WRITE_ATTRIBUTES only open failed'}
 try {
   [long]$targetTime=([DateTime]::UtcNow.AddMinutes(-2)).ToFileTimeUtc()
   if(![FileHubWinFspTimeProbe]::SetFileTime($attrHandle,[IntPtr]::Zero,
       [IntPtr]::Zero,[ref]$targetTime)){
     throw 'Excel FILE_WRITE_ATTRIBUTES only SetFileTime failed'
   }
   [long]$created=0;[long]$accessed=0;[long]$actualTime=0
   if(![FileHubWinFspTimeProbe]::GetFileTime($attrHandle,
       [ref]$created,[ref]$accessed,[ref]$actualTime)){
     throw 'Excel FILE_WRITE_ATTRIBUTES only GetFileTime failed'
   }
   if($actualTime -ne $targetTime){
     throw 'Excel attribute-only last write timestamp was not returned'
   }
 }finally{$attrHandle.Dispose()}
 Write-Output 'ONE_LAYER_RW_EXCEL_ATTRIBUTE_ONLY_HANDLE_PASS'
 }
 Write-Output 'ONE_LAYER_RW_WINFSP_INTEGRATION_PASS'
}finally{
 if($book){try{$book.Close($false)}catch{}}
 if($excel){try{$excel.Quit()}catch{}}
 if($mount -and !$mount.HasExited){Stop-Process -Id $mount.Id -Force -ErrorAction SilentlyContinue}
 if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
 if($backend -and !$backend.HasExited){Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue}
 Write-Output ('ONE_LAYER_RW_LOGS='+$logs)
}
