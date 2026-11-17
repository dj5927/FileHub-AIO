# Isolated local-only Excel -> WinFsp passthrough test.
$ErrorActionPreference = 'Stop'
$work = Join-Path $env:TEMP ('FileHubExcelWinFsp_' + [guid]::NewGuid().ToString('N'))
$source = 'Q:'
$target = 'J:'
$port = 24197
$python = 'C:\Users\UserK\AppData\Local\Python\bin\python.exe'
$bridgeExe = Join-Path $PSScriptRoot '..\LockBridge\bin\Release\net10.0-windows\win-x64\NASMountLockBridge.exe'
$stubFile = Join-Path $PSScriptRoot 'qa_owner_stub.py'
$stub = $null
$bridge = $null
$excel = $null
$book = $null
try {
    if ([Environment]::GetLogicalDrives() -contains ($source + '\') -or
        [Environment]::GetLogicalDrives() -contains ($target + '\')) {
        throw 'QA drive letters occupied'
    }
    New-Item -ItemType Directory -Path $work | Out-Null
    & subst.exe $source $work
    if ($LASTEXITCODE -ne 0) { throw 'SUBST failed' }
    $stub = Start-Process -FilePath $python -PassThru -WindowStyle Hidden -ArgumentList @('-u', ('"' + $stubFile + '"'), $port)
    Start-Sleep -Milliseconds 650
    if ($stub.HasExited) { throw 'Owner stub stopped' }
    $bridgeArgs = @('--local-bridge-test', ($source + '\'), $target, ('http://127.0.0.1:' + $port + '/'))
    $bridge = Start-Process -FilePath $bridgeExe -PassThru -WindowStyle Hidden -ArgumentList $bridgeArgs -RedirectStandardOutput (Join-Path $work 'bridge.out') -RedirectStandardError (Join-Path $work 'bridge.err')
    $ready = $false
    for ($n = 0; $n -lt 60; $n++) {
        Start-Sleep -Milliseconds 250
        if (Test-Path ($target + '\')) { $ready = $true; break }
        if ($bridge.HasExited) { break }
    }
    if (-not $ready) {
        Get-Content (Join-Path $work 'bridge.err') -ErrorAction SilentlyContinue
        throw 'WinFsp test drive did not mount'
    }
    $excel = New-Object -ComObject Excel.Application
    $excel.Visible = $false
    $excel.DisplayAlerts = $false
    $book = $excel.Workbooks.Add()
    $book.Worksheets.Item(1).Cells.Item(1, 1).Value2 = 'LOCAL-ONLY-QA'
    $book.SaveAs(($source + '\11월매출.xlsx'), 51)
    $book.Close($false)
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($book)
    $book = $null
    if (-not (Test-Path ($target + '\11월매출.xlsx'))) { throw 'Cannot browse WinFsp workbook' }
    $book = $excel.Workbooks.Open(($target + '\11월매출.xlsx'))
    $book.Worksheets.Item(1).Cells.Item(2, 1).Value2 = 'SAVE-1'
    $book.Save()
    $book.Worksheets.Item(1).Cells.Item(3, 1).Value2 = 'SAVE-2'
    $book.Save()
    $book.Close($false)
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($book)
    $book = $null
    $files = @(Get-ChildItem $work -File | Where-Object {$_.Name -like '*11월매출*'})
    if ($files.Count -ne 1 -or $files[0].Name -cne '11월매출.xlsx') {
        throw ('CASE_REGRESSION_AFTER_REAL_EXCEL_SAVE: ' + (($files | ForEach-Object {$_.Name}) -join ';'))
    }
    $check = $excel.Workbooks.Open(($source + '\11월매출.xlsx'), 0, $true)
    try {
        if ($check.Worksheets.Item(1).Cells.Item(3, 1).Value2 -ne 'SAVE-2') { throw 'Second save lost data' }
    } finally {
        $check.Close($false)
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($check)
    }
    Write-Output 'REAL_EXCEL_LOCAL_WINFSP_TWO_SAVES_CASE_PASS'
} finally {
    if ($book -ne $null) {
        try { $book.Close($false) } catch {}
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($book)
    }
    if ($excel -ne $null) {
        try { $excel.Quit() } catch {}
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel)
    }
    foreach ($process in @($bridge, $stub)) {
        if ($process -ne $null -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Milliseconds 600
    & subst.exe $source /D | Out-Null
    Write-Output ('QA_FIXTURE=' + $work)
}
