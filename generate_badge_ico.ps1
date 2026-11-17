$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "generate_badge_ico.py"
& python $script
if ($LASTEXITCODE -ne 0) { throw "Windows icon generation failed. Install Pillow for Python." }