[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$matches = git -C $root grep -n -e '@Deprecated' -- '*.java' 2>$null

if ($LASTEXITCODE -eq 0) {
  $matches | ForEach-Object { Write-Error "Deprecated API declaration: $_" }
  exit 1
}
if ($LASTEXITCODE -gt 1) {
  throw 'Unable to scan Java source for deprecated API declarations.'
}

Write-Host 'Java deprecated API validation passed.'
