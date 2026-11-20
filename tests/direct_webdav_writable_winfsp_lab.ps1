param([int]$Port = 18800)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$rclone='E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
$bridge=Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
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

 $path=Join-Path $drive 'test-created.txt'
 [IO.File]::WriteAllText($path,'first-save')
 Start-Sleep -Seconds 2
 if(!(Test-Path (Join-Path $server 'test-created.txt'))){throw 'Create did not upload to NAS'}
 if([IO.File]::ReadAllText((Join-Path $server 'test-created.txt')) -ne 'first-save'){throw 'Wrong first save'}
 Write-Output 'ONE_LAYER_RW_CREATE_AND_UPLOAD_PASS'
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
 Write-Output 'ONE_LAYER_RW_WINFSP_INTEGRATION_PASS'
}finally{
 if($book){try{$book.Close($false)}catch{}}
 if($excel){try{$excel.Quit()}catch{}}
 if($mount -and !$mount.HasExited){Stop-Process -Id $mount.Id -Force -ErrorAction SilentlyContinue}
 if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
 if($backend -and !$backend.HasExited){Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue}
 Write-Output ('ONE_LAYER_RW_LOGS='+$logs)
}
