# Currency default-behavior validator (standards/16 "default resolution").
#
# This is a behavioral gate, not a text scan: it runs the tests that lock the contract
# "no row / blank / unknown -> USD" and "write path rejects unsupported values".
# The static literal/symbol gates cannot prove behavior; these tests can.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-currency-defaults.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\currency-defaults-worklist.txt'

Push-Location $root
try {
  $maven = if (Test-Path -LiteralPath (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
  # Maven writes progress/warnings to stderr; keep them from tripping $ErrorActionPreference.
  $previousErrorAction = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $sdk = & $maven -B -q -pl sdk/infrastructure -am '-Dtest=CurrencyTest,CurrencyResolverTest' `
      '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $sdkExit = $LASTEXITCODE

    $tenant = & $maven -B -q -pl platform-services/tenant/platform-tenant-service -am `
      '-Dtest=TenantCurrencyControllerTest,TenantCurrencyWriteThroughTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $tenantExit = $LASTEXITCODE

    $payment = & $maven -B -q -pl common-services/payment/common-payment-service -am `
      '-Dtest=PaymentIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $paymentExit = $LASTEXITCODE

    $order = & $maven -B -q -pl platform-services/order/platform-order-service -am `
      '-Dtest=OrderIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1
    $orderExit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorAction
  }

  $violations = [System.Collections.Generic.List[string]]::new()
  if ($sdkExit -ne 0) {
    $violations.Add('Currency default tests failed (sdk/infrastructure: CurrencyTest, CurrencyResolverTest)')
  }
  if ($tenantExit -ne 0) {
    $violations.Add('Tenant currency default/switch tests failed (TenantCurrencyControllerTest, TenantCurrencyWriteThroughTest)')
  }
  if ($paymentExit -ne 0) {
    $violations.Add('Payment currency snapshot test failed (PaymentIntegrationTest)')
  }
  if ($orderExit -ne 0) {
    $violations.Add('Order currency snapshot test failed (OrderIntegrationTest)')
  }

  if ($violations.Count -eq 0) {
    if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
    Write-Host 'Currency default validation passed.'
    exit 0
  }

  [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
  [System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

  if ($ReportOnly) {
    Write-Warning "Currency defaults: $($violations.Count) failing test group(s). Worklist: $worklistPath"
    exit 0
  }

  $violations | ForEach-Object { Write-Error $_ }
  Write-Error "Currency defaults: $($violations.Count) failing test group(s). Worklist: $worklistPath"
  exit 1
} finally {
  Pop-Location
}
