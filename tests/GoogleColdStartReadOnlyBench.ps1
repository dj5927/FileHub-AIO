param(
    [ValidateSet('default','paced')][string]$Mode = 'default',
    [switch]$WithEnvType,
    [int]$TimeoutSeconds = 48
)
$ErrorActionPreference = 'Stop'
$rclone='E:\NASMount_Releases\NASMount_1_3_76_EXCEL_OPEN_CASE_FULL\tools\rclone.exe'
$authDir=Join-Path $env:APPDATA 'NASMount\google-auth'
$all=@(Get-ChildItem -LiteralPath $authDir -Filter '*.conf' -File)
if($all.Count -ne 1){throw ('Expected one Google profile, found '+$all.Count)}
$original=$all[0]
$text=[IO.File]::ReadAllText($original.FullName)
$name=[regex]::Match($text,'(?m)^\[(nm_[0-9a-fA-F]{32})\]\s*$').Groups[1].Value
if(!$name){throw 'Could not identify synthetic remote name'}
function TokenExpiry([string]$path){
    $data=[IO.File]::ReadAllText($path)
    $expiry=[regex]::Match($data,'"expiry"\s*:\s*"([^"]+)"').Groups[1].Value
    if(!$expiry){return $null}
    return [DateTimeOffset]::Parse($expiry).ToUniversalTime()
}
$lab=Join-Path $env:TEMP ('NASMount_Google_Cold_1378_'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($lab)|Out-Null
$acl=New-Object Security.AccessControl.DirectorySecurity
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
(Get-Item $lab).SetAccessControl($acl)
$config=Join-Path $lab 'test.conf'
Copy-Item -LiteralPath $original.FullName -Destination $config
$before=TokenExpiry $config
$start=[Diagnostics.Stopwatch]::StartNew()
$p=$null
try{
    $psi=[Diagnostics.ProcessStartInfo]::new($rclone)
    $psi.UseShellExecute=$false
    $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true
    $psi.RedirectStandardError=$true
    if($WithEnvType){
        $psi.EnvironmentVariables[('RCLONE_CONFIG_'+$name.ToUpperInvariant()+'_TYPE')]='drive'
    }
    $psi.Arguments='lsf '+($name+':')+' --config "'+$config+'" --max-depth 1 --log-level ERROR'
    if($Mode -eq 'paced'){
        $psi.Arguments+=' --drive-pacer-min-sleep 250ms --drive-pacer-burst 2'
    }
    $p=[Diagnostics.Process]::Start($psi)
    $out=$p.StandardOutput.ReadToEndAsync()
    $err=$p.StandardError.ReadToEndAsync()
    if(!$p.WaitForExit($TimeoutSeconds*1000)){
        $p.Kill($true)
        $p.WaitForExit()
        Write-Output ('GOOGLE_READONLY_BENCH mode='+$Mode+' timeout=true elapsedMs='+$start.ElapsedMilliseconds)
        exit 3
    }
    $out.GetAwaiter().GetResult()|Out-Null
    $errorText=$err.GetAwaiter().GetResult()
    $after=TokenExpiry $config
    Write-Output ('GOOGLE_READONLY_BENCH mode='+$Mode+' envType='+[bool]$WithEnvType+
        ' exit='+$p.ExitCode+' elapsedMs='+$start.ElapsedMilliseconds+
        ' initiallyExpired='+($before -lt [DateTimeOffset]::UtcNow)+
        ' tokenUpdated='+($after -ne $before)+
        ' rateLimited='+(($errorText -match 'rateLimitExceeded|userRateLimitExceeded|quotaExceeded'))+
        ' anyErrors='+(![string]::IsNullOrWhiteSpace($errorText)))
    exit $p.ExitCode
}
finally{
    if($p){$p.Dispose()}
    Remove-Item -LiteralPath $lab -Recurse -Force -ErrorAction SilentlyContinue
}
