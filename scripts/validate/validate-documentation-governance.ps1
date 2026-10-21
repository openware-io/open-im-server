# Documentation governance checks.
# 1. Every archived document has a historical metadata header.
# 2. Top-level docs have an English counterpart with a language switch.
# 3. Current operational docs do not use legacy product names.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 may read BOM-less scripts as ANSI.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$violations = [System.Collections.Generic.List[string]]::new()

function Add-Violation([string]$message) {
  $violations.Add($message)
}

function Get-Utf8Text([string]$path) {
  return [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
}

# 1) Archive metadata and archive-local English index pairs.
$archiveRoot = Join-Path $root 'docs/99-archive'
if (Test-Path -LiteralPath $archiveRoot) {
  $archiveDocs = Get-ChildItem $archiveRoot -Recurse -File -Filter '*.md' |
    Where-Object { $_.Name -notin @('README.md', 'README.en.md', 'ARCHIVE-LOG.md', 'ARCHIVE-LOG.en.md', 'INDEX.md', 'INDEX.en.md') }
  foreach ($doc in $archiveDocs) {
    $text = Get-Utf8Text $doc.FullName
    if (-not $text.TrimStart([char]0xFEFF).StartsWith('> Status: historical')) {
      Add-Violation "archive metadata missing: $($doc.FullName.Substring($root.Length + 1))"
    }
  }
}

# 2) Top-level bilingual pairs and language switches.
$pairedDocs = @(
  'README.md',
  'docs/README.md',
  'docs/10-product/README.md',
  'docs/20-architecture/OVERVIEW.md',
  'docs/20-architecture/TOPOLOGY.zh.md',
  'docs/50-services/README.md',
  'docs/40-domain/README.md',
  'docs/80-development/standards/README.md',
  'docs/80-development/governance/DOCUMENTATION_ORGANIZATION.md'
)
foreach ($relative in $pairedDocs) {
  $path = Join-Path $root $relative
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    Add-Violation "missing bilingual source: $relative"
    continue
  }
  $english = if ($relative -like '*.zh.md') { $relative -replace '\.zh\.md$', '.en.md' } else { $relative -replace '\.md$', '.en.md' }
  $englishPath = Join-Path $root $english
  if (-not (Test-Path -LiteralPath $englishPath -PathType Leaf)) {
    Add-Violation "missing English pair: $relative -> $english"
    continue
  }
  $sourceText = Get-Utf8Text $path
  $englishText = Get-Utf8Text $englishPath
  $sourceName = Split-Path -Leaf $relative
  $englishName = Split-Path -Leaf $english
  if ($sourceText -notmatch [regex]::Escape("($englishName)")) {
    Add-Violation "missing English language switch: $relative"
  }
  if ($englishText -notmatch [regex]::Escape("($sourceName)")) {
    Add-Violation "missing Chinese language switch: $english"
  }
}

# 2b) Every service README has an English summary pair.
$serviceRoot = Join-Path $root 'docs/50-services'
if (Test-Path -LiteralPath $serviceRoot) {
  foreach ($serviceDir in (Get-ChildItem $serviceRoot -Recurse -Directory)) {
    $zh = Join-Path $serviceDir.FullName 'README.md'
    $en = Join-Path $serviceDir.FullName 'README.en.md'
    if ((Test-Path -LiteralPath $zh) -and -not (Test-Path -LiteralPath $en)) {
      Add-Violation "missing service English summary: docs/50-services/$($serviceDir.Name)/README.en.md"
    }
  }
}

# 2c) Every business/standards document has an English summary pair.
foreach ($domainRoot in @('docs/40-domain', 'docs/80-development/standards')) {
  $fullRoot = Join-Path $root $domainRoot
  if (-not (Test-Path -LiteralPath $fullRoot)) { continue }
  foreach ($doc in (Get-ChildItem $fullRoot -File -Filter '*.md')) {
    if ($doc.Name -match '^README' -or $doc.Name -match '\.en\.md$') { continue }
    $en = Join-Path $doc.DirectoryName ($doc.BaseName + '.en.md')
    if (-not (Test-Path -LiteralPath $en)) {
      Add-Violation "missing English summary: $domainRoot/$($doc.BaseName).en.md"
    }
  }
}

# 3) Legacy names in current operational docs (archive and governance records excluded).
$legacyRoots = @('README.md', 'docs/20-architecture', 'docs/60-deployment', 'docs/50-services', 'docs/40-domain', 'docs/80-development/standards')
$allowPatterns = @(
  'docs/README.md', 'docs/README.en.md',
  'docs/99-archive/audits/DOCUMENTATION_AUDIT.md', 'docs/99-archive/audits/DOCUMENTATION_AUDIT.en.md',
  'docs/80-development/governance/DOCUMENTATION_ORGANIZATION.md', 'docs/80-development/governance/DOCUMENTATION_ORGANIZATION.en.md'
)
$legacyTerms = @('WV Chat', 'im-order-service')
foreach ($relativeRoot in $legacyRoots) {
  $target = Join-Path $root $relativeRoot
  if (-not (Test-Path -LiteralPath $target)) { continue }
  $files = if ((Get-Item -LiteralPath $target).PSIsContainer) {
    Get-ChildItem $target -Recurse -File -Filter '*.md'
  } else {
    Get-Item -LiteralPath $target
  }
  foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
    if ($allowPatterns -contains $relative) { continue }
    $text = Get-Utf8Text $file.FullName
    foreach ($term in $legacyTerms) {
      if ($text -match [regex]::Escape($term)) {
        Add-Violation "legacy name '$term' in current doc: $relative"
      }
    }
  }
}

if ($violations.Count -eq 0) {
  Write-Host 'Documentation governance validation passed.'
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Documentation governance: $($violations.Count) pending violation(s)."
  exit 0
}

$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Documentation governance: $($violations.Count) violation(s) in total."
exit 1
