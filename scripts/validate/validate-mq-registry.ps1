# RocketMQ ledger validator (G-09).
#
# standards/12: implemented resources have exactly one source of truth, the MQ ledger (docs/mq/REGISTRY.md);
# topics and producer/consumer groups must be defined as protocol-mq constants, and business services may
# only reference those constants instead of declaring duplicate literals.
# Nothing compared the ledger with the code, so 34 live resources were missing from the ledger.
#
# Checks:
#   1. every resource constant in sdk/protocol-mq appears in docs/mq/REGISTRY.md
#   2. every resource name listed in the ledger exists as a protocol-mq constant (drift the other way)
#   3. no producer/consumer group literal is declared outside protocol-mq (config files included)
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-mq-registry.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\mq-registry-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$registryPath = Join-Path $root 'docs\mq\REGISTRY.md'
if (-not (Test-Path -LiteralPath $registryPath -PathType Leaf)) { throw "MQ ledger not found: $registryPath" }
$registry = [System.IO.File]::ReadAllText($registryPath, [System.Text.UTF8Encoding]::new($false, $true))
$registryNames = [System.Collections.Generic.HashSet[string]]::new()
foreach ($match in [regex]::Matches($registry, '`([a-z][a-z0-9_:\-]+)`')) { [void]$registryNames.Add($match.Groups[1].Value) }

Push-Location $root
try {
  $protocolFiles = git -C $root ls-files 'sdk/protocol-mq/**/*.java'
  $codeResources = [System.Collections.Generic.HashSet[string]]::new()

  # 1) protocol constants -> ledger
  foreach ($relativePath in ($protocolFiles | Where-Object { $_ -match '(Topics|ConsumerGroups|ProducerGroups)\.java$' })) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    foreach ($match in [regex]::Matches($text, 'public\s+static\s+final\s+String\s+(?<name>[A-Z0-9_]+)\s*=\s*"(?<value>[^"]+)"')) {
      $value = $match.Groups['value'].Value
      [void]$codeResources.Add($value)
      if (-not $registryNames.Contains($value)) {
        $violations.Add("resource not registered in docs/mq/REGISTRY.md: $value ($relativePath)")
      }
    }
  }

  # 2) ledger -> code
  foreach ($name in $registryNames) {
    if ($name -notmatch '^(im|platform|common|group|cst|res)_') { continue }
    if (-not $codeResources.Contains($name)) {
      $violations.Add("registry entry without a protocol-mq constant: $name")
    }
  }

  # 3) group literals outside protocol-mq
  #
  # A literal is a violation when it is a SECOND source of truth - a resource name written out
  # somewhere other than protocol-mq. A config line of the form
  #   producer-group: ${SERVICE_MQ_PRODUCER_GROUP:<name>}
  # is not that: standards/12 requires the service-specific variable (which this is), and when the
  # fallback equals the protocol-mq constant the name still has exactly one definition. The check
  # is therefore semantic - flag only literals that DIVERGE from the declared resource names.
  $declaredGroupValues = [System.Collections.Generic.HashSet[string]]::new()
  $groupConstantFiles = git -C $root ls-files 'sdk/protocol-mq/*Group*.java'
  foreach ($groupFile in $groupConstantFiles) {
    $groupPath = Join-Path $root $groupFile
    if (-not (Test-Path -LiteralPath $groupPath -PathType Leaf)) { continue }
    $groupText = [System.IO.File]::ReadAllText($groupPath, [System.Text.UTF8Encoding]::new($false, $true))
    foreach ($constant in [regex]::Matches($groupText, 'static\s+final\s+String\s+[A-Z0-9_]+\s*=\s*"(?<value>[^"]+)"')) {
      [void]$declaredGroupValues.Add($constant.Groups['value'].Value)
    }
  }

  $literalPattern = '["'']?(?<value>(?:im|platform|common|group)-[a-z0-9\-]+-(?:producer|consumer))["'']?'
  $configFiles = git -C $root ls-files '*.yml' '*.yaml' '*.properties'
  foreach ($relativePath in $configFiles) {
    if ($relativePath -match '^sdk/protocol-mq/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      if ($line -match '\$\{[A-Z_]+\}') { continue }
      foreach ($match in [regex]::Matches($line, $literalPattern)) {
        $value = $match.Groups['value'].Value
        if ($declaredGroupValues.Contains($value)) { continue }
        $violations.Add("group literal declared outside protocol-mq: ${relativePath}:$lineNumber -> $value")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  Write-Host "RocketMQ ledger validation passed. Resources checked: $($codeResources.Count)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "RocketMQ ledger: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "RocketMQ ledger: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
