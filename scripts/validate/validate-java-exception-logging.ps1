[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$matches = git -C $root grep -n -e 'printStackTrace(' -- '*.java' 2>$null

if ($LASTEXITCODE -eq 0) {
  $matches | ForEach-Object { Write-Error "Direct stack-trace printing: $_" }
  exit 1
}
if ($LASTEXITCODE -gt 1) {
  throw 'Unable to scan Java source for direct stack-trace printing.'
}

Write-Host 'Java exception logging validation passed.'
