# C/B order visibility E2E wrapper
#
# Requires an already running Kind environment with the current images deployed.
# It only orchestrates the existing real E2E scripts and does not fake assertions:
#   1) hotel: C booking -> C my orders -> B/SaaS queries -> payment/refund/OTA
#   2) KTV: fresh C account OAuth -> reservation -> B fulfill/settle -> C payment
#
# Usage:
#   pwsh ./scripts/verify/verify-order-visibility-e2e.ps1 -Gateway http://192.168.3.35:30080
param(
  [string]$Gateway = 'http://192.168.3.35:30080',
  [string]$H5Base = '',
  [string]$AdminUser = 'tenant-admin',
  [string]$AdminPassword = '',
  [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($H5Base)) { $H5Base = $Gateway.TrimEnd('/') }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
  $AdminPassword = ('Admin' + '@' + '123')
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path '.outputs\verify\order-visibility-e2e'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$hotelLog = Join-Path $OutDir 'hotel.log'
$ktvLog = Join-Path $OutDir 'ktv.log'
$pwsh = (Get-Process -Id $PID).Path

Write-Host '== C/B hotel visibility E2E =='
$hotelArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
  (Join-Path $PSScriptRoot 'e2e-c-end-ota-channel.ps1'),
  '-Gateway', $Gateway, '-OutDir', (Join-Path $OutDir 'hotel'),
  '-AdminUser', $AdminUser, '-AdminPassword', $AdminPassword)
& $pwsh @hotelArgs 2>&1 | Tee-Object -FilePath $hotelLog
$hotelExit = $LASTEXITCODE
if ($hotelExit -ne 0) { throw "hotel visibility E2E failed ($hotelExit): $hotelLog" }

Write-Host '== C/B KTV visibility E2E =='
$ktvArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
  (Join-Path $PSScriptRoot 'verify-openchat-ktv-closure.ps1'),
  '-Gateway', $Gateway, '-H5Base', $H5Base,
  '-AdminUser', $AdminUser, '-AdminPassword', $AdminPassword)
& $pwsh @ktvArgs 2>&1 | Tee-Object -FilePath $ktvLog
$ktvExit = $LASTEXITCODE
if ($ktvExit -ne 0) { throw "KTV visibility E2E failed ($ktvExit): $ktvLog" }

Write-Host "order visibility E2E PASS: hotel=$hotelLog ktv=$ktvLog"
