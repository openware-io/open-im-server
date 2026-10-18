# Media public URL validator (standards/13 "single public URL shape", static part).
#
# Rule: public media is reachable only through the gateway route Path=/api/v1/media-public/**
# with StripPrefix=3, and no other proxy/rewrite exposes the legacy /media-public shape.
# This does not fetch objects; it guards the routing contract.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-media-url.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gatewayConfig = Join-Path $root 'gateways\gateway\src\main\resources\application.yml'
$worklistPath = Join-Path $root '.outputs\quality\media-url-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $gatewayConfig)) {
  Write-Error 'Gateway application.yml is missing.'
  exit 1
}

$text = [System.IO.File]::ReadAllText($gatewayConfig, [System.Text.UTF8Encoding]::new($false, $true))
$routePath = 'Path=/api/v1/media-public/**'
$pathCount = ([regex]::Matches($text, [regex]::Escape($routePath))).Count
if ($pathCount -ne 1) {
  $violations.Add("Expected exactly one '$routePath' route, found $pathCount")
}
if ($text -match 'Path=/media-public/\*\*') {
  $violations.Add('Legacy /media-public/** route must not exist')
}

$idMatch = [regex]::Match($text, '(?ms)^\s*-\s*id:\s*public-media\s*$(.*?)(?=^\s*-\s*id:\s*|\z)')
if (-not $idMatch.Success) {
  $violations.Add('Gateway route id public-media is missing')
}
else {
  $block = $idMatch.Groups[1].Value
  if ($block -notmatch 'StripPrefix=3') {
    $violations.Add('public-media route must use StripPrefix=3 (api/v1/media-public -> bucket/key)')
  }
  if ($block -match 'RewritePath') {
    $violations.Add('public-media route must not use RewritePath')
  }
}

foreach ($relativePath in (git -C $root ls-files '*.yml' '*.yaml')) {
  if ($relativePath -match '^gateways/gateway/src/main/resources/application\.yml$') { continue }
  if ($relativePath -notmatch '^(deploy|k8s|gateways)/') { continue }
  $path = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
  $content = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  $withoutComments = ($content -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
  if ($withoutComments -match 'Path=/media-public|RewritePath[^\n]*media-public|proxy_pass[^\n]*media-public') {
    $violations.Add("Media public URL is proxied/rewritten outside the gateway contract: $relativePath")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Media public URL validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Media URL: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | ForEach-Object { Write-Error $_ }
Write-Error "Media URL: $($violations.Count) violation(s). Worklist: $worklistPath"
exit 1
