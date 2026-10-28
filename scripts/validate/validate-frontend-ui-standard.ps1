# Frontend UI style standard validator (standards/32).
#
# Verifies that the hotel B/C pages keep using the KTV baseline components and
# that the C-side tenant config call never regresses to the admin endpoint.
#
# IMPORTANT: keep this file ASCII-only.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workspace = Split-Path -Parent $root
$violations = [System.Collections.Generic.List[string]]::new()

function Require-File([string]$path) {
  if (!(Test-Path -LiteralPath $path)) {
    $violations.Add("missing file: $path")
    return $false
  }
  return $true
}

function Require-Text([string]$path, [string]$needle, [string]$label) {
  if (!(Require-File $path)) { return }
  $raw = [System.IO.File]::ReadAllText($path)
  if (!$raw.Contains($needle)) {
    $violations.Add("$label missing '$needle' in $path")
  }
}

$admin = Join-Path $workspace 'open-saas-admin'
$mobile = Join-Path $workspace 'open-saas-mobile'

$globalCss = Join-Path $admin 'src/styles/global.css'
$hotelVue = Join-Path $admin 'src/views/tenant/hotel.vue'
$ktvVue = Join-Path $admin 'src/views/tenant/ktv-config.vue'
$styles = Join-Path $mobile 'c-end/styles.css'
$hotelJs = Join-Path $mobile 'c-end/hotel-real.js'
$saasJs = Join-Path $mobile 'c-end/saas.js'

Require-Text $globalCss '.metric-grid' 'B metric grid'
Require-Text $globalCss 'repeat(4' 'B metric grid columns'
Require-Text $globalCss '.admin-card' 'B admin card'
Require-Text $hotelVue 'class="admin-page' 'B hotel root'
Require-Text $hotelVue 'metric-grid' 'B hotel metrics'
Require-Text $hotelVue 'admin-card' 'B hotel cards'
Require-Text $ktvVue 'class="admin-page' 'B KTV root'
Require-Text $ktvVue 'admin-card' 'B KTV cards'
Require-Text $styles '--primary' 'C design token'
Require-Text $styles '.hotel-cover' 'C hotel cover'
Require-Text $styles '.tag-row' 'C tag row'
Require-Text $hotelJs 'hotel-cover' 'C hotel cover usage'
Require-Text $hotelJs 'tag-row' 'C hotel tag usage'
Require-Text $hotelJs 'hotel-detail-head' 'C hotel detail head'
Require-Text $hotelJs 'hotel-link' 'C hotel link'
Require-Text $saasJs '/api/v1/business/tenant/config' 'C tenant config path'

$cEnd = Join-Path $mobile 'c-end'
if (Test-Path -LiteralPath $cEnd) {
  Get-ChildItem -LiteralPath $cEnd -Filter '*.js' -File | ForEach-Object {
    if ($_.Name -like '*.test.js') { return }
    $raw = [System.IO.File]::ReadAllText($_.FullName)
    if ($raw.Contains('/api/v1/admin/tenant/config')) {
      $violations.Add("C side must not call admin tenant config: $($_.FullName)")
    }
  }
}

if ($violations.Count -gt 0) {
  Write-Output 'Frontend UI style standard validation failed:'
  $violations | ForEach-Object { Write-Output " - $_" }
  exit 1
}

Write-Output 'Frontend UI style standard validation passed.'
