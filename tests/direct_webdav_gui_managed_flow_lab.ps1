param([int]$Port = 18886)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$rclone = 'E:\NASMount_Releases\NASMount_1_3_35_ICON_TITLEBAR\tools\rclone.exe'
$engine = if($env:FILEHUB_TEST_PUBLISHED_BRIDGE){$env:FILEHUB_TEST_PUBLISHED_BRIDGE}else{
    Join-Path $repo 'LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
}
$buildApp = if($env:FILEHUB_TEST_PUBLISHED_APP){$env:FILEHUB_TEST_PUBLISHED_APP}else{
    Join-Path $repo 'bin\Release\net10.0-windows\win-x64\FileHubAIO.exe'
}
$bin = Split-Path -Parent $buildApp
$root = Join-Path $env:TEMP ('FileHubGuiDirectTest_'+[Guid]::NewGuid().ToString('N'))
$stagedApp = Join-Path $root 'app'
$tools = Join-Path $stagedApp 'tools'
[IO.Directory]::CreateDirectory($stagedApp)|Out-Null
[IO.Directory]::CreateDirectory($tools)|Out-Null
# Stage the complete GUI and bridge dependency trees to a unique directory
# for every QA run. Never overwrite a DLL still mapped by an older test.
Get-ChildItem -LiteralPath $bin -File | Copy-Item -Destination $stagedApp -Force
Get-ChildItem -LiteralPath $bin -Directory | Where-Object {$_.Name -ne 'tools'} |
    Copy-Item -Destination $stagedApp -Recurse -Force
Copy-Item -Path ((Split-Path -Parent $engine)+'\*') -Destination $tools -Recurse -Force
$app = Join-Path $stagedApp 'FileHubAIO.exe'
$server = Join-Path $root 'server'
$logs = Join-Path $root 'logs'
[IO.Directory]::CreateDirectory($server)|Out-Null
[IO.Directory]::CreateDirectory($logs)|Out-Null
$webdav=$null;$proxy=$null;$appProcess=$null
Write-Output ('DIRECT_GUI_LAB_ROOT='+$root)
try {
    $webdav = Start-Process -FilePath $rclone -PassThru -NoNewWindow -ArgumentList @(
      'serve','webdav',$server,'--addr',"127.0.0.1:$Port",'--dir-cache-time','1s',
      '--log-level','ERROR')
    $env:FILEHUB_STRICT_DAV_BACKEND=[string]$Port
    $env:FILEHUB_STRICT_DAV_FRONT=[string]($Port+1)
    $proxy = Start-Process -FilePath 'python.exe' -PassThru -NoNewWindow -ArgumentList @(
        '-u',(Join-Path $PSScriptRoot 'strict_webdav_conditional_proxy.py')) `
        -RedirectStandardOutput (Join-Path $logs 'proxy.out') `
        -RedirectStandardError (Join-Path $logs 'proxy.err')
    $ready=$false
    for($i=0;$i -lt 70;$i++){
      try{$s=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port+1);$s.Dispose();$ready=$true;break}
      catch{Start-Sleep -Milliseconds 160}
    }
    if(!$ready){throw 'Strict WebDAV test server did not start'}
    # Use no redirected streams: Excel COM inherits open handles and can
    # delay StreamReader EOF even AFTER the test GUI process has exited.
    $appProcess=Start-Process -FilePath $app -PassThru -WindowStyle Hidden -ArgumentList @(
        '--direct-webdav-gui-managed-flow-lab',($Port+1))
    $exited=$false
    for($n=0;$n -lt 375;$n++){
        $appProcess.Refresh()
        if($appProcess.HasExited){$exited=$true;break}
        Start-Sleep -Milliseconds 200
    }
    if(!$exited){throw 'Direct FileHub GUI Connect/Disconnect lifecycle exceeded 75 seconds'}
    $appProcess.Refresh()
    if($appProcess.ExitCode -ne 0){throw ('GUI managed native route exit code='+$appProcess.ExitCode)}
    Write-Output 'DIRECT_GUI_NATIVE_WEBDAV_CONNECT_SAVE_DISCONNECT_PROCESS_PASS'
    $remote=Join-Path $server 'filehub-gui-rounds.txt'
    if(!(Test-Path -LiteralPath $remote) -or
       [IO.File]::ReadAllText($remote) -ne 'FileHub round 3'){
        throw 'FileHub GUI save did not persist the third revision to server'
    }
    Write-Output 'DIRECT_GUI_THIRD_SAVE_SERVER_VERIFIED_PASS'
    $serverWorkbook=Join-Path $server 'FileHubGuiActual.xlsx'
    if(!(Test-Path -LiteralPath $serverWorkbook)){
        throw 'Real Excel workbook is missing from server after GUI disconnect'
    }
    # Excel itself wrote the three revisions inside FileHubAIO.exe. Validate
    # the resulting actual .xlsx ZIP on the independent server without
    # starting a second Excel COM instance, which can hang a headless QA job.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($serverWorkbook)
    try {
        $strings=$zip.GetEntry('xl/sharedStrings.xml')
        $sheet=$zip.GetEntry('xl/worksheets/sheet1.xml')
        if(!$strings -or !$sheet){throw 'Excel workbook XML parts are missing'}
        $reader=[IO.StreamReader]::new($strings.Open())
        try {$stringsXml=$reader.ReadToEnd()} finally {$reader.Dispose()}
        $reader=[IO.StreamReader]::new($sheet.Open())
        try {$sheetXml=$reader.ReadToEnd()} finally {$reader.Dispose()}
        if($stringsXml -notmatch 'SAVE_3' -or $sheetXml -notmatch 'r="B2"'){
            throw 'Final XLSX does not contain the third saved B2 revision'
        }
    } finally {$zip.Dispose()}
    Write-Output 'DIRECT_GUI_REAL_EXCEL_THIRD_SAVE_SERVER_CONTENT_VERIFIED_PASS'
}finally{
    if($appProcess -and !$appProcess.HasExited){Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue}
    if($proxy -and !$proxy.HasExited){Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue}
    if($webdav -and !$webdav.HasExited){Stop-Process -Id $webdav.Id -Force -ErrorAction SilentlyContinue}
    Write-Output ('DIRECT_GUI_LAB_LOGS='+$logs)
}
