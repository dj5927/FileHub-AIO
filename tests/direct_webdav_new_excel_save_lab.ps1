param([string]$Repo=(Split-Path -Parent $PSScriptRoot),[string]$ProxyRepo=$Repo,[int]$Port=19141,[int]$Latency=0,[switch]$NoPutEtag,[switch]$NoTempEtag,[switch]$NoDocumentEtag,[switch]$RejectUnversionedMove,[switch]$DenyFinalPut,[switch]$LargeWorkbook,[switch]$ExistingTarget,[switch]$SecondMount)
$ErrorActionPreference='Stop'
$root=Join-Path $env:TEMP ('filehub-office-new-save-'+[guid]::NewGuid().ToString('N').Substring(0,8))
$server=Join-Path $root 'remote';$journals=Join-Path $root 'journals'
foreach($p in @($root,$server,$journals)){[IO.Directory]::CreateDirectory($p)|Out-Null}
Write-Output ('LAB_ROOT='+$root)
$rclone=$env:FILEHUB_LAB_RCLONE
if(!$rclone -or !(Test-Path $rclone)){throw 'Set FILEHUB_LAB_RCLONE to a local rclone executable'}
$bridge=Join-Path $Repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
$used=[Environment]::GetLogicalDrives()|ForEach-Object {$_.Substring(0,1).ToUpperInvariant()}
$letter=@('W','V','U','T','Q','P','M','N')|Where-Object {$used -notcontains $_}|Select-Object -First 1
if(!$letter){throw 'No free test letter'}
$drive=$letter+':\';$front=$Port+1;$b=$null;$p=$null;$m=$null;$m2=$null;$excel=$null;$book=$null;$ownedExcel=$false
try {
 $b=Start-Process $rclone -PassThru -NoNewWindow -ArgumentList @('serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s','--log-level','INFO','--log-file',(Join-Path $root 'backend.log'))
 $env:FILEHUB_STRICT_DAV_BACKEND="$Port";$env:FILEHUB_STRICT_DAV_FRONT="$front";$env:FILEHUB_STRICT_DAV_RESPONSE_LATENCY_MS="$Latency";$env:FILEHUB_STRICT_DAV_PER_PATH_LOCKS='1';$env:FILEHUB_STRICT_DAV_STRIP_OWNER_ETAGS='1';$env:FILEHUB_STRICT_DAV_STRIP_PUT_ETAG=if($NoPutEtag){'1'}else{'0'}
 $env:FILEHUB_STRICT_DAV_REJECT_NAMED_NEWFILE_PUT=if($DenyFinalPut){'Denied.xlsx'}else{''}
 $env:FILEHUB_STRICT_DAV_STRIP_OFFICE_TEMP_ETAGS=if($NoTempEtag){'1'}else{'0'}
 $env:FILEHUB_STRICT_DAV_STRIP_OFFICE_DOCUMENT_ETAGS=if($NoDocumentEtag){'1'}else{'0'}
 $env:FILEHUB_STRICT_DAV_REJECT_UNVERSIONED_MOVE_IF_MATCH=if($RejectUnversionedMove){'1'}else{'0'}
 $p=Start-Process python.exe -PassThru -NoNewWindow -ArgumentList @('-u',(Join-Path $ProxyRepo 'tests\strict_webdav_conditional_proxy.py')) -RedirectStandardOutput (Join-Path $root 'proxy.log') -RedirectStandardError (Join-Path $root 'proxy.err')
 $ready=$false;for($i=0;$i -lt 60;$i++){try{$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$front);$tcp.Dispose();$ready=$true;break}catch{Start-Sleep -Milliseconds 200}}
 if(!$ready){throw 'proxy did not start'}
 $env:FILEHUB_DIRECT_DEBUG='1';$env:FILEHUB_LAB_PROFILE_PIPELINE='1'
 $m=Start-Process $bridge -PassThru -NoNewWindow -ArgumentList @('--direct-webdav-writable-lab',"http://127.0.0.1:$front/",($letter+':'),$journals) -RedirectStandardOutput (Join-Path $root 'mount.out') -RedirectStandardError (Join-Path $root 'mount.err')
 $ready=$false;for($i=0;$i -lt 100;$i++){if(Test-Path $drive){$ready=$true;break};if($m.HasExited){break};Start-Sleep -Milliseconds 250}
 if(!$ready){throw 'mount did not start'}
 Write-Output ('MOUNT_READY='+$drive)
 Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class LabExcelWindow { [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid); }'
 $before=@(Get-Process EXCEL -ErrorAction SilentlyContinue|Select-Object -ExpandProperty Id)
 $excel=New-Object -ComObject Excel.Application
 [uint32]$excelPid=0;[void][LabExcelWindow]::GetWindowThreadProcessId([IntPtr]$excel.Hwnd,[ref]$excelPid)
 if(!$excelPid -or $excelPid -in $before){throw 'Excel reused a pre-existing user process; not touching it'}
 $ownedExcel=$true;$excel.Visible=$false;$excel.DisplayAlerts=$false
 $name=if($DenyFinalPut){'Denied.xlsx'}else{'NewDocument.xlsx'}
 $target=Join-Path $drive $name;$remote=Join-Path $server $name
 $book=$excel.Workbooks.Add();$book.Worksheets.Item(1).Cells.Item(1,1).Value2='FILEHUB_NEW_SAVE_TEST'
 if($LargeWorkbook){
   $rows=16000;$cols=10;$values=New-Object 'object[,]' $rows,$cols
   for($r=0;$r -lt $rows;$r++){for($c=0;$c -lt $cols;$c++){$values[$r,$c]=[guid]::NewGuid().ToString('N')}}
   $book.Worksheets.Item(1).Range('A10:J16009').Value2=$values
 }
 Write-Output 'SAVE_AS_START';$book.SaveAs($target,51)
 Write-Output ('SAVE_AS_RETURN saved='+$book.Saved+' remote='+[IO.File]::Exists($remote))
 if(!$book.Saved -or ![IO.File]::Exists($remote)){throw 'New SaveAs returned before confirmed remote file'}
 if($LargeWorkbook -and (Get-Item $remote).Length -lt 2MB){throw 'Large workbook did not exceed progressive threshold'}
 if($SecondMount){
   $used2=[Environment]::GetLogicalDrives()|ForEach-Object {$_.Substring(0,1).ToUpperInvariant()}
   $letter2=@('V','U','T','Q','P','M','N')|Where-Object {$used2 -notcontains $_}|Select-Object -First 1
   if(!$letter2){throw 'No second free lab drive letter'}
   $journal2=Join-Path $root 'journals-second';[IO.Directory]::CreateDirectory($journal2)|Out-Null
   $m2=Start-Process $bridge -PassThru -NoNewWindow -ArgumentList @('--direct-webdav-writable-lab',"http://127.0.0.1:$front/",($letter2+':'),$journal2) -RedirectStandardOutput (Join-Path $root 'second.out') -RedirectStandardError (Join-Path $root 'second.err')
   $drive2=$letter2+':\';$ready=$false
   for($i=0;$i -lt 80;$i++){if(Test-Path $drive2){$ready=$true;break};if($m2.HasExited){break};Start-Sleep -Milliseconds 200}
   if(!$ready){throw 'Second independent mount did not start'}
   $other=Join-Path $drive2 $name;$reader=[IO.File]::OpenRead($other);$reader.Dispose()
   $blocked=$false;$writer=$null
   try{$writer=[IO.File]::Open($other,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)}catch [IO.IOException]{$blocked=$true}catch [UnauthorizedAccessException]{$blocked=$true}finally{if($writer){$writer.Dispose()}}
   if(!$blocked){throw 'Foreign mount incorrectly acquired writable Excel handle'}
   Stop-Process -Id $m2.Id -Force;$m2=$null;Write-Output 'NO_ETAG_SECOND_MOUNT_EXCEL_OWNER_CONFLICT_PASS'
 }
 for($i=1;$i -le 5;$i++){$book.Worksheets.Item(1).Cells.Item(2,1).Value2=('SAVE_ROUND_'+$i);$book.Save();if(!$book.Saved){throw 'Save returned unsaved'};$owners=@(Get-ChildItem $server -File -Force|Where-Object Name -like '~$*');Write-Output ('REPEAT_SAVE='+$i+' owners='+$owners.Count);if($owners.Count -ne 1){throw 'owner missing'}}
 # SaveAs directly from the still-open NAS workbook. The former
 # local-source-only test did not exercise the user's failure path.
 $copyName='CopyOfOpenWorkbook.xlsx'
 $copyTarget=Join-Path $drive $copyName
 $copyRemote=Join-Path $server $copyName
 if($ExistingTarget){[IO.File]::WriteAllBytes($copyTarget,[byte[]]@());if(!(Test-Path $copyRemote)){throw 'Placeholder creation failed'}}
 $book.Worksheets.Item(1).Cells.Item(3,1).Value2='NAS_TO_NAS_SAVE_AS'
 $book.SaveAs($copyTarget,51)
 if(!$book.Saved -or !(Test-Path $copyRemote)){throw 'NAS to NAS SaveAs failed'}
 $owners=@(Get-ChildItem $server -File -Force|Where-Object Name -like '~$*')
 if($owners.Count -ne 1 -or $owners[0].Name -notlike '*CopyOfOpenWorkbook*'){throw 'SaveAs target owner missing or source owner leaked'}
 $book.Worksheets.Item(1).Cells.Item(4,1).Value2='SAVE_AFTER_SAVE_AS'
 $book.Save()
 $book.Close($false);$book=$null
 $book=$excel.Workbooks.Open($copyTarget,0,$false)
 if($book.ReadOnly){throw 'Saved copy reopened read-only'}
 $book.Worksheets.Item(1).Cells.Item(5,1).Value2='REOPEN_AND_SAVE'
 $book.Save();$book.Close($false);$book=$null
 $book=$excel.Workbooks.Open($copyRemote,0,$true)
 if($book.Worksheets.Item(1).Cells.Item(3,1).Value2 -ne 'NAS_TO_NAS_SAVE_AS' -or
    $book.Worksheets.Item(1).Cells.Item(4,1).Value2 -ne 'SAVE_AFTER_SAVE_AS' -or
    $book.Worksheets.Item(1).Cells.Item(5,1).Value2 -ne 'REOPEN_AND_SAVE'){throw 'NAS SaveAs or reopened content mismatch'}
 $book.Close($false);$book=$null;Write-Output 'NAS_TO_NAS_SAVE_AS_REOPEN_AND_SAVE_PASS'
 $book=$excel.Workbooks.Open($remote,0,$true);if($book.Worksheets.Item(1).Cells.Item(2,1).Value2 -ne 'SAVE_ROUND_5' -or $book.Worksheets.Item(1).Cells.Item(3,1).Value2){throw 'Original workbook was changed by SaveAs'}
 $book.Close($false);$book=$null;Write-Output 'NEW_SAVE_AND_REPEAT_PASS'
 $local=Join-Path $root 'LocalSource.xlsx'
 $book=$excel.Workbooks.Add();$book.Worksheets.Item(1).Cells.Item(1,1).Value2='LOCAL_TO_NAS_SAVE_AS'
 $book.SaveAs($local,51)
 $name2=([char]0xC0C8)+([char]0xBB38)+([char]0xC11C)+'.xlsx'
 $book.SaveAs((Join-Path $drive $name2),51)
 if(!$book.Saved -or !(Test-Path (Join-Path $server $name2))){throw 'Local to NAS SaveAs failed'}
 $book.Close($false);$book=$null
 $book=$excel.Workbooks.Open((Join-Path $server $name2),0,$true)
 if($book.Worksheets.Item(1).Cells.Item(1,1).Value2 -ne 'LOCAL_TO_NAS_SAVE_AS'){throw 'SaveAs content mismatch'}
 $book.Close($false);$book=$null;Write-Output 'LOCAL_TO_NAS_KOREAN_SAVE_AS_PASS'
}finally{
 if($ownedExcel){if($book){try{$book.Close($false)}catch{}};try{$excel.Quit()}catch{}}
 foreach($c in @($book,$excel)){if($c){try{[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($c)}catch{}}}
 foreach($proc in @($m2,$m,$p,$b)){if($proc -and !$proc.HasExited){Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue}}
 Write-Output ('LAB_LOGS='+$root)
}
