param([int]$Port = 18800, [switch]$SkipExcel)
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
 $lastReported=[FileHubCopyFileExProgress]::Times[
   [FileHubCopyFileExProgress]::Times.Count-1]
 $tail=[long]$copyClock.ElapsedMilliseconds-$lastReported
 Write-Output ('ONE_LAYER_RW_COPY_99_PERCENT_TAIL_MS='+$tail)
 Write-Output 'ONE_LAYER_RW_QUICK_PROGRESS_VERIFIED'
}finally{
 if($book){try{$book.Close($false)}catch{}}
 if($excel){try{$excel.Quit()}catch{}}
 if($mount -and !$mount.HasExited){Stop-Process -Id $mount.Id -Force -ErrorAction SilentlyContinue}
 if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
 if($backend -and !$backend.HasExited){Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue}
 Write-Output ('ONE_LAYER_RW_LOGS='+$logs)
}
