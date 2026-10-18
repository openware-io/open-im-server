# Observability correlation validator (standards/40 "correlation id at entries", HTTP part).
#
# The gateway must generate or preserve X-Request-Id and propagate it downstream and to the
# response; the infrastructure must carry it from HTTP into MQ and restore it in consumers.
# This is behavioral: the gate runs the request-id tests instead of grepping text.
# Scheduled-task propagation is not covered yet and remains a manual item.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-observability-correlation.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\observability-correlation-worklist.txt'

Push-Location $root
try {
  $maven = if (Test-Path -LiteralPath (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
  $previousErrorAction = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $gateway = & $maven -B -q -pl gateways/gateway -am '-Dtest=RequestIdFilterTest' `
      '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $gatewayExit = $LASTEXITCODE

    $infra = & $maven -B -q -pl sdk/infrastructure -am `
      '-Dtest=TenantContextFilterTest,RemotingMqConsumerFactoryTest' `
      '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $infraExit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorAction
  }

  $violations = [System.Collections.Generic.List[string]]::new()
  if ($gatewayExit -ne 0) {
    $violations.Add('Gateway correlation-id test failed (RequestIdFilterTest)')
  }
  if ($infraExit -ne 0) {
    $violations.Add('HTTP/MQ correlation-id tests failed (TenantContextFilterTest, RemotingMqConsumerFactoryTest)')
  }

  if ($violations.Count -eq 0) {
    if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
    Write-Host 'Observability correlation validation passed (HTTP + MQ).'
    exit 0
  }

  [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
  [System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

  if ($ReportOnly) {
    Write-Warning "Observability correlation: $($violations.Count) failing group(s). Worklist: $worklistPath"
    exit 0
  }

  $violations | ForEach-Object { Write-Error $_ }
  Write-Error "Observability correlation: $($violations.Count) failing group(s). Worklist: $worklistPath"
  exit 1
} finally {
  Pop-Location
}
