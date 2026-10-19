# Run every scripts/verify/*.ps1 and keep per-script evidence (standards/43).
#
# The scripts still require their documented environment (cluster, credentials, port-forwards).
# This runner only guarantees one invocation point and a durable log/summary for release evidence.
#
# Usage:
#   powershell -File scripts/verify/run-all.ps1
#   powershell -File scripts/verify/run-all.ps1 -Only verify-audit-storage.ps1,e2e-ktv-smoke.ps1
#   powershell -File scripts/verify/run-all.ps1 -EvidenceDir .outputs/verify/release-20261019
[CmdletBinding()]
param(
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ".outputs/verify/$(Get-Date -Format 'yyyyMMdd-HHmmss')"),
  [string[]]$Only = @(),
  [switch]$FailFast
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dir = if ([System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir } else { Join-Path $root $EvidenceDir }
[void](New-Item -ItemType Directory -Force -Path $dir)

$scripts = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File |
  Where-Object { $_.Name -ne 'run-all.ps1' } |
  Sort-Object Name
if ($Only.Count -gt 0) {
  $scripts = $scripts | Where-Object { $Only -contains $_.Name }
}
if (-not $scripts -or $scripts.Count -eq 0) {
  Write-Error 'No verify scripts selected.'
  exit 1
}

$results = [System.Collections.Generic.List[string]]::new()
$failed = 0
foreach ($script in $scripts) {
  $log = Join-Path $dir ($script.BaseName + '.log')
  Write-Host "Running $($script.Name) ..."
  & $script.FullName *> $log
  $code = $LASTEXITCODE
  if ($null -eq $code) { $code = 0 }
  if ($code -ne 0) { $failed++ }
  $results.Add(("{0}`t{1}" -f $code, $script.Name))
  if ($code -ne 0 -and $FailFast) { break }
}

$summary = Join-Path $dir 'summary.txt'
$header = "verify run at $(Get-Date -Format o), git $(git -C $root rev-parse --short HEAD)"
[System.IO.File]::WriteAllLines($summary, ([string[]](@($header) + $results)), [System.Text.UTF8Encoding]::new($false))

Write-Host "Evidence: $dir"
Write-Host "Failed: $failed / $($results.Count)"
if ($failed -gt 0) { exit 1 }
