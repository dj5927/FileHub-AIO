param([int]$Port = 18238, [int]$WriteBackSeconds = 15,
    [int]$Rounds = 2, [ValidateSet('xlsx','xls')][string]$Format = 'xlsx',
    [switch]$ProbeOwner, [switch]$RealVisibleLock,
    [switch]$FastClose, [switch]$ProbeAutoRefresh,
    [switch]$EnableAutoRefreshWithExcel)
$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $PSScriptRoot
$root = "E:\NASMount_Releases\QA_owner_1337_" + [Guid]::NewGuid().ToString('N').Substring(0,8)
$server = Join-Path $root 'server'
$cache = Join-Path $root 'cache'
$logs = Join-Path $root 'logs'
foreach ($p in @($server,$cache,$logs)) {[IO.Directory]::CreateDirectory($p) | Out-Null}
if ($ProbeAutoRefresh) {
    [IO.Directory]::CreateDirectory((Join-Path $server 'Folder_1339')) | Out-Null
}
$rclone = 'E:\NASMount_Releases\NASMount_1_3_72_STARTUP_READY_FULL\tools\rclone.exe'
$bridge = Join-Path $source 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.dll'
$seed = 'D:\GPT\NAS_MOUNT\tests\webdav_two_client_lab\LabActualSeed.xlsx'
$markerPrefix='NASMount_'+[string][char]0xC0AC+[string][char]0xC6A9+[string][char]0xC911+'_'
$name = "LabActual.$Format"
Write-Output ("CASE_BASELINE_NAME="+$name)
if ($Format -eq 'xls') {
    $convert=New-Object -ComObject Excel.Application
    $convert.Visible=$false
    $convert.DisplayAlerts=$false
    try {
        $old=$convert.Workbooks.Open($seed,0,$true)
        try {$old.SaveAs((Join-Path $server $name),56)}
        finally {$old.Close($false);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($old)}
    }
    finally {$convert.Quit();[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($convert)}
} else {
    Copy-Item $seed (Join-Path $server $name)
}
$used = [Environment]::GetLogicalDrives() | ForEach-Object {$_.Substring(0,1).ToUpperInvariant()}
$free = @('V','W','U','T','Q','P','N','M','L') | Where-Object {$used -notcontains $_}
if ($free.Count -lt 3) {throw 'Not enough free isolated test drive letters'}
$lower = $free[0]
$upper = $free[1]
$other = $free[2]
$running = @()
$excel = $null
$book = $null
function Wait-Condition([scriptblock]$condition,[int]$seconds,[string]$label) {
    for($i=0;$i -lt $seconds*5;$i++) {
        try {if (& $condition) {return}}catch{}
        Start-Sleep -Milliseconds 200
    }
    throw "Wait timeout: $label"
}
function Verify-Workbook([string]$at,[string]$expected) {
    $file = Join-Path $server $name
    if (!(Test-Path $file)) {throw "$at server original disappeared"}
    $length = (Get-Item $file).Length
    if ($length -lt 512) {throw "$at CORRUPTED size=$length"}
    $probe=New-Object -ComObject Excel.Application
    $probe.DisplayAlerts=$false
    try {
        $current=$probe.Workbooks.Open($file,0,$true)
        try {
            $value=[string]$current.Worksheets.Item(1).Cells.Item(2,2).Value2
            if ($value -ne $expected) {throw "$at expected=$expected actual=$value"}
            Write-Output ("VERIFY_"+$at+" bytes="+$length+" sha256="+(Get-FileHash $file).Hash)
        } finally {$current.Close($false);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($current)}
    } finally {$probe.Quit();[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($probe)}
}
Write-Output ("LAB_ROOT="+$root)
Write-Output ("LAB_LETTERS lower="+$lower+" upper="+$upper)
try {
    $serve=Start-Process $rclone -ArgumentList @('serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--log-level','INFO','--log-file',(Join-Path $logs 'server.log')) -PassThru -NoNewWindow
    $running+= $serve
    Wait-Condition {
        try {
            $tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port)
            $tcp.Dispose()
            return $true
        } catch {return $false}
    } 15 'Loopback WebDAV'
    $proxyPort=$Port+1
    $rcPort=$Port+2
    $env:NASMOUNT_LAB_BACKEND=[string]$Port
    $env:NASMOUNT_LAB_FRONT=[string]$proxyPort
    $proxy=Start-Process 'python.exe' -ArgumentList @('-u',(Join-Path $PSScriptRoot 'webdav_root_proxy.py')) -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $logs 'proxy.out') -RedirectStandardError (Join-Path $logs 'proxy.err')
    $running+= $proxy
    Wait-Condition {
        try {$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$proxyPort);$tcp.Dispose();return $true}
        catch {return $false}
    } 10 'Loopback WebDAV proxy'
    $mountArgs=@('mount',':webdav:',"$($lower):",'--webdav-url',"http://127.0.0.1:$proxyPort/",'--vfs-cache-mode','full','--vfs-write-back',"$($WriteBackSeconds)s",'--dir-cache-time','1s','--transfers','1','--cache-dir',$cache,'--log-level','DEBUG','--log-file',(Join-Path $logs 'rclone-mount.log'),
        '--rc','--rc-addr',"127.0.0.1:$rcPort",'--rc-user','naslab','--rc-pass','naslab-local-38')
    $mount=Start-Process $rclone -ArgumentList $mountArgs -PassThru -NoNewWindow
    $running+= $mount
    Wait-Condition {Test-Path "$($lower):\"} 20 'Lower rclone WinFsp'
    if ($ProbeOwner -and !$RealVisibleLock) {
        $otherArgs=@('mount',':webdav:',"$($other):",'--webdav-url',
            "http://127.0.0.1:$proxyPort/",'--vfs-cache-mode','full',
            '--vfs-write-back',"$($WriteBackSeconds)s",'--dir-cache-time','1s',
            '--cache-dir',(Join-Path $root 'other-cache'),'--log-level','ERROR')
        $otherMount=Start-Process $rclone -ArgumentList $otherArgs -PassThru -NoNewWindow
        $running+= $otherMount
        Wait-Condition {Test-Path "$($other):\"} 20 'Other PC simulated drive mount'
        $null=Get-ChildItem "$($other):\" -Force
        Write-Output ('OTHER_MOUNT_BEFORE_OPEN='+((Get-ChildItem "$($other):\" -Force | ForEach-Object {$_.Name}) -join ','))
    }
    Write-Output ('LOWER_ROOT_LIST='+((Get-ChildItem "$($lower):\" -ErrorAction SilentlyContinue | ForEach-Object {$_.Name}) -join ','))
    Write-Output ('LOWER_SEED_PRESENT='+(Test-Path "$($lower):\$name"))
    $env:NASMOUNT_FSP_TRACE='1'
    $env:NASMOUNT_LAB_REAL_VISIBLE_OWNER=if($RealVisibleLock){'1'}else{'0'}
    $env:NASMOUNT_LAB_RC_PORT=[string]$rcPort
    $env:NASMOUNT_LAB_RC_USER='naslab'
    $env:NASMOUNT_LAB_RC_PASS='naslab-local-38'
    $env:NASMOUNT_LAB_REMOTE_POLL=if($ProbeAutoRefresh -or $EnableAutoRefreshWithExcel){'1'}else{'0'}
    $bridgeArgs=@($bridge,'--local-bridge-test',"$($lower):\","$($upper):","http://127.0.0.1:$proxyPort/")
    $hostProcess=Start-Process 'dotnet.exe' -ArgumentList $bridgeArgs -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $logs 'bridge.out') -RedirectStandardError (Join-Path $logs 'bridge.err')
    $running+= $hostProcess
    Start-Sleep -Milliseconds 800
    Write-Output ('UPPER_DRIVE_EXISTS='+(Test-Path "$($upper):\"))
    Write-Output ('UPPER_ROOT_LIST='+((Get-ChildItem "$($upper):\" -ErrorAction SilentlyContinue | ForEach-Object {$_.Name}) -join ','))
    Write-Output ('UPPER_EXPECTED_PATH='+"$($upper):\$name")
    Wait-Condition {(Get-ChildItem "$($upper):\" -ErrorAction SilentlyContinue |
        Where-Object {$_.Name -eq $name}).Count -gt 0} 25 'Upper WinFsp bridge enumeration'
    if ($ProbeAutoRefresh) {
        $watched="$($upper):\"
        $null=Get-ChildItem $watched
        Start-Sleep -Seconds 3
        $watcher=[IO.FileSystemWatcher]::new($watched)
        $watcher.Filter='RemoteCreated_1339.txt'
        $watcher.NotifyFilter=[IO.NotifyFilters]'FileName,LastWrite'
        $watcher.EnableRaisingEvents=$true
        $remote=Join-Path $server 'RemoteCreated_1339.txt'
        try {
            $url="http://127.0.0.1:$proxyPort/RemoteCreated_1339.txt"
            $uploaded=Invoke-WebRequest -Uri $url -Method Put -Body 'external-PC-created' -UseBasicParsing
            Write-Output ('REMOTE_CREATE_SECOND_WEBDAV_CLIENT_HTTP='+$uploaded.StatusCode)
            $created=$watcher.WaitForChanged([IO.WatcherChangeTypes]::Created,14500)
            Write-Output ('WINDOWS_WINFSP_CREATED_EVENT='+(!$created.TimedOut)+' file='+$created.Name)
            if($created.TimedOut -or $created.Name -ne 'RemoteCreated_1339.txt') {
                throw 'Windows did not receive native WinFsp Created notification'
            }
            Wait-Condition {Test-Path "$($upper):\RemoteCreated_1339.txt"} 6 'Explorer automatic creation visibility'
            $removed=Invoke-WebRequest -Uri $url -Method Delete -UseBasicParsing
            Write-Output ('REMOTE_DELETE_SECOND_WEBDAV_CLIENT_HTTP='+$removed.StatusCode)
            $deleted=$watcher.WaitForChanged([IO.WatcherChangeTypes]::Deleted,14500)
            Write-Output ('WINDOWS_WINFSP_DELETED_EVENT='+(!$deleted.TimedOut)+' file='+$deleted.Name)
            if($deleted.TimedOut -or $deleted.Name -ne 'RemoteCreated_1339.txt') {
                throw 'Windows did not receive native WinFsp Deleted notification'
            }
            Wait-Condition {-not (Test-Path "$($upper):\RemoteCreated_1339.txt")} 6 'Explorer automatic deletion visibility'
            # Unicode Excel status marker from a second independent WebDAV client.
            $markerName='NASMount_'+[string][char]0xC0AC+[string][char]0xC6A9+[string][char]0xC911+'_RemoteCreated_1339.xlsx'
            $watcher.Filter=$markerName
            $markerUrl="http://127.0.0.1:$proxyPort/"+[Uri]::EscapeDataString($markerName)
            $markerCreated=Invoke-WebRequest -Uri $markerUrl -Method Put -InFile $seed -ContentType 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' -UseBasicParsing
            $markerEvent=$watcher.WaitForChanged([IO.WatcherChangeTypes]::Created,14500)
            Write-Output ('EXCEL_STATUS_FILE_CREATED_EVENT='+(!$markerEvent.TimedOut)+' HTTP='+$markerCreated.StatusCode)
            if ($markerEvent.TimedOut -or $markerEvent.Name -ne $markerName) {
                throw 'Remote Excel in-use marker Created notification missing'
            }
            Wait-Condition {Test-Path -LiteralPath ("$($upper):\"+$markerName)} 6 'Excel marker visible'
            $markerDeleted=Invoke-WebRequest -Uri $markerUrl -Method Delete -UseBasicParsing
            $deletedMarkerEvent=$watcher.WaitForChanged([IO.WatcherChangeTypes]::Deleted,14500)
            Write-Output ('EXCEL_STATUS_FILE_DELETED_EVENT='+(!$deletedMarkerEvent.TimedOut)+' HTTP='+$markerDeleted.StatusCode)
            if ($deletedMarkerEvent.TimedOut -or $deletedMarkerEvent.Name -ne $markerName) {
                throw 'Remote Excel in-use marker Deleted notification missing'
            }
            Wait-Condition {-not (Test-Path -LiteralPath ("$($upper):\"+$markerName))} 6 'Excel marker hidden after release'
            # A real NAS user commonly browses subfolders, not only the root.
            $nestedDrive="$($upper):\Folder_1339"
            Wait-Condition {Test-Path $nestedDrive} 7 'Nested remote folder visible'
            $null=Get-ChildItem $nestedDrive
            $nestedWatcher=[IO.FileSystemWatcher]::new($nestedDrive)
            $nestedWatcher.Filter='NestedCreated_1339.txt'
            $nestedWatcher.NotifyFilter=[IO.NotifyFilters]'FileName,LastWrite'
            $nestedWatcher.EnableRaisingEvents=$true
            Start-Sleep -Seconds 3
            $nestedUrl="http://127.0.0.1:$proxyPort/Folder_1339/NestedCreated_1339.txt"
            $nestPut=Invoke-WebRequest -Uri $nestedUrl -Method Put -Body 'nested-client' -UseBasicParsing
            $nestedCreated=$nestedWatcher.WaitForChanged([IO.WatcherChangeTypes]::Created,14500)
            Write-Output ('NESTED_FOLDER_CREATED_EVENT='+(!$nestedCreated.TimedOut)+' HTTP='+$nestPut.StatusCode)
            if ($nestedCreated.TimedOut -or $nestedCreated.Name -ne 'NestedCreated_1339.txt') {
                Write-Output ('NESTED_REMOTE_FILE_VISIBLE_AFTER_NOTIFY='+(Test-Path "$nestedDrive\NestedCreated_1339.txt"))
                throw 'Nested folder did not receive remote Created notification'
            }
            Wait-Condition {Test-Path "$nestedDrive\NestedCreated_1339.txt"} 6 'Nested file visible'
            $nestUpdate=Invoke-WebRequest -Uri $nestedUrl -Method Put -Body 'nested-client-updated-content' -UseBasicParsing
            $nestedChanged=$nestedWatcher.WaitForChanged([IO.WatcherChangeTypes]::Changed,14500)
            Write-Output ('NESTED_FOLDER_CHANGED_EVENT='+(!$nestedChanged.TimedOut)+' HTTP='+$nestUpdate.StatusCode)
            if ($nestedChanged.TimedOut -or $nestedChanged.Name -ne 'NestedCreated_1339.txt') {
                throw 'Nested folder did not receive remote Modified notification'
            }
            $nestDelete=Invoke-WebRequest -Uri $nestedUrl -Method Delete -UseBasicParsing
            $nestedDeleted=$nestedWatcher.WaitForChanged([IO.WatcherChangeTypes]::Deleted,14500)
            Write-Output ('NESTED_FOLDER_DELETED_EVENT='+(!$nestedDeleted.TimedOut)+' HTTP='+$nestDelete.StatusCode)
            if ($nestedDeleted.TimedOut -or $nestedDeleted.Name -ne 'NestedCreated_1339.txt') {
                throw 'Nested folder did not receive remote Deleted notification'
            }
            $nestedWatcher.Dispose()
            Write-Output 'WINFSP_REMOTE_FILE_CREATED_DELETED_AUTO_REFRESH_PASS'
        } finally {$watcher.EnableRaisingEvents=$false;$watcher.Dispose()}
        return
    }
    $excel=New-Object -ComObject Excel.Application
    $excel.Visible=$false
    $excel.DisplayAlerts=$false
    $book=$excel.Workbooks.Open("$($upper):\$name",0,$false)
    if ($book.ReadOnly) {throw 'Excel did not open in write mode'}
    Write-Output 'EXCEL_OPEN_FOR_EDIT_PASS'
    if ($ProbeOwner) {
        $ownerName='~$'+$name.ToUpperInvariant()
        Write-Output ('OWNER_FILE_EXPECTED='+$ownerName)
        Start-Sleep -Seconds 2
        $localOwner = Join-Path $server $ownerName
        Write-Output ('OWNER_ON_SERVER='+ (Test-Path -LiteralPath $localOwner))
        Write-Output ('OWNER_SERVER_ALL='+ ((Get-ChildItem -LiteralPath $server -Force -File | ForEach-Object {$_.Name+'('+[string]$_.Length+')'}) -join ', '))
        Write-Output ('OWNER_ON_UPPER='+((Get-ChildItem "$($upper):\" -Force -ErrorAction SilentlyContinue | Where-Object {$_.Name -eq $ownerName} | ForEach-Object {$_.Name+' attributes='+[string]$_.Attributes}) -join ';'))
        Write-Output ('OWNER_ON_LOWER='+((Get-ChildItem "$($lower):\" -Force -ErrorAction SilentlyContinue | Where-Object {$_.Name -eq $ownerName} | ForEach-Object {$_.Name+' attributes='+[string]$_.Attributes}) -join ';'))
        if ($RealVisibleLock) {
            $visibleMarker=$markerPrefix+$(if($Format -eq 'xlsx'){$name.Substring(0,$name.Length-5)}else{$name})+'.xlsx'
            $serverMarker=Join-Path $server $visibleMarker
            Wait-Condition {Test-Path -LiteralPath $serverMarker} 12 'Server-side visible marker'
            $ownerXlsx=$excel.Workbooks.Open($serverMarker,0,$true)
            try {
                $visibleOwner=[string]$ownerXlsx.Worksheets.Item(1).Cells.Item(3,2).Value2
                $visibleId=[string]$ownerXlsx.Worksheets.Item(1).Cells.Item(5,2).Value2
                if (!$visibleOwner.Contains([Environment]::MachineName) -or
                    $visibleId -notmatch '^[0-9a-fA-F]{32}$') {
                    throw 'Excel status workbook missing owner/PC or lease ID'
                }
                Write-Output 'MARKER_OPENED_AS_REAL_EXCEL_WORKBOOK=True'
            } finally { $ownerXlsx.Close($false);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($ownerXlsx) }
            Write-Output ('REAL_VISIBLE_MARKER_ON_SERVER='+$visibleMarker)
            $upperMarker=@(Get-ChildItem "$($upper):\" -Force -ErrorAction SilentlyContinue |
                Where-Object {$_.Name -ieq $visibleMarker})
            if ($upperMarker.Count -lt 1) {
                throw 'WinFsp Explorer directory listing did not expose the visible marker'
            }
            Write-Output ('MARKER_VISIBLE_IN_WIN_FSP_EXPLORER_VIEW=True')
            $lsf=@(& $rclone lsf ':webdav:' --webdav-url "http://127.0.0.1:$proxyPort/" --log-level ERROR)
            # rclone.exe stdout on Windows PowerShell 5 can decode UTF-8
            # Korean filenames using the active OEM codepage. Validate the
            # ASCII suffix and independently GET the exact UTF-8 marker URL.
            Write-Output ('INDEPENDENT_RCLONE_LIST_ITEMS='+$lsf.Count)
            $markerUrl="http://127.0.0.1:$proxyPort/"+[Uri]::EscapeDataString($visibleMarker)
            $secondGet=Invoke-WebRequest -Uri $markerUrl -UseBasicParsing
            if($secondGet.StatusCode -ne 200) {
                throw 'Independent WebDAV GET failed for the exact owner marker'
            }
            Write-Output ('INDEPENDENT_REMOTE_CLIENT_FETCHES_MARKER=True')
        } else {
            $seenOther = $false
            for($scan=1;$scan -le 12;$scan++){
                $ownerVisible=@(Get-ChildItem "$($other):\" -Force -ErrorAction SilentlyContinue |
                    Where-Object {$_.Name -eq $ownerName})
                Write-Output ('OTHER_MOUNT_SCAN_'+$scan+' ownerCount='+$ownerVisible.Count)
                if($ownerVisible.Count -gt 0){$seenOther=$true;break}
                Start-Sleep -Milliseconds 500
            }
            Write-Output ('OWNER_VISIBLE_OTHER_MOUNT='+$seenOther)
        }
        $remoteOwnerList = & $rclone lsf ':webdav:' --webdav-url "http://127.0.0.1:$proxyPort/" --log-level ERROR
        Write-Output ('REMOTE_LSF='+($remoteOwnerList -join ', '))
    }
    for($round=1;$round -le $Rounds;$round++) {
        $value="LAB_SINGLE_WRITER_$round"
        $book.Worksheets.Item(1).Cells.Item(2,2).Value2=$value
        $book.Save()
        Write-Output ("EXCEL_SAVE_RETURNED round="+$round)
        Write-Output ("CASE_SERVER_NAMES_AFTER_SAVE="+((Get-ChildItem -LiteralPath $server -File | ForEach-Object {$_.Name}) -join ";"))
        Write-Output ("CASE_UPPER_NAMES_AFTER_SAVE="+((Get-ChildItem "$($upper):\" -File | ForEach-Object {$_.Name}) -join ";"))
        if (!$FastClose) {
            Start-Sleep -Seconds ($WriteBackSeconds+7)
            Verify-Workbook "SAVE_$round" $value
        }
    }
    $book.Close($false)
    $markerRemovedTimer=[Diagnostics.Stopwatch]::StartNew()
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($book)
    $book=$null
    $excel.Quit()
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($excel)
    $excel=$null
    Start-Sleep -Seconds 2
    if($RealVisibleLock) {
        $serverMarker=Join-Path $server ($markerPrefix+$(if($Format -eq 'xlsx'){$name.Substring(0,$name.Length-5)}else{$name})+'.xlsx')
        Wait-Condition {-not (Test-Path -LiteralPath $serverMarker)} 32 'Visible marker cleanup on workbook close'
        Write-Output ('VISIBLE_MARKER_AUTODELETED_AFTER_CLOSE=True elapsed_ms='+$markerRemovedTimer.ElapsedMilliseconds)
    }
    if ($Rounds -gt 0) {Verify-Workbook 'AFTER_CLOSE' "LAB_SINGLE_WRITER_$Rounds"}
    Write-Output ("CASE_SERVER_FINAL="+((Get-ChildItem -LiteralPath $server -File | ForEach-Object {$_.Name}) -join ";"))
Write-Output 'LIVE_WIN_FSP_EXCEL_SINGLE_WRITER_LAB_PASS'
} catch {
    Write-Output ("LIVE_WIN_FSP_EXCEL_SINGLE_WRITER_LAB_FAIL "+$_)
    throw
} finally {
    if ($book) {try {$book.Close($false)}catch{}}
    if ($excel) {try {$excel.Quit()}catch{}}
    foreach($proc in $running) {
        try {
            if (!$proc.HasExited) {
                Write-Output ("STOP_LAB_PROCESS="+$proc.Id)
                Stop-Process -Id $proc.Id -Force
                $proc.WaitForExit(5000) | Out-Null
            }
        } catch {Write-Output ("LAB_STOP_WARNING="+$_)}
    }
    # WindowsApps python.exe may be a launcher which starts a separate
    # pythoncore child; stopping the launcher alone leaves the loopback
    # proxy alive. Only stop children with this EXACT lab script path.
    $ourProxy = Join-Path $PSScriptRoot 'webdav_root_proxy.py'
    $children = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'python.exe' -and $_.CommandLine -like "*$ourProxy*"
    }
    foreach($child in $children) {
        try {
            Write-Output ("STOP_LAB_PROXY_CHILD="+$child.ProcessId)
            Stop-Process -Id $child.ProcessId -Force -ErrorAction Stop
        } catch { Write-Output ("LAB_PROXY_CLEANUP_NOTE="+$_) }
    }
    Write-Output ("LAB_LOGS="+$logs)
}
