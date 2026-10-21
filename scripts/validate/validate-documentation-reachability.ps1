# Documentation reachability validator (G50).
#
# A valid link is not the same as a discoverable document. Starting from the
# root README and docs/README, follow local Markdown links recursively and fail
# if any current Markdown file under docs/ (excluding 99-archive) is unreachable.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$docsRoot = Join-Path $root 'docs'
$worklistPath = Join-Path $root '.outputs\quality\documentation-reachability-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$queue = [System.Collections.Generic.Queue[string]]::new()

function Resolve-DocLink([string]$sourcePath, [string]$target) {
  $clean = $target.Trim()
  if ($clean.StartsWith('<') -and $clean.EndsWith('>')) {
    $clean = $clean.Substring(1, $clean.Length - 2)
  }
  $clean = $clean.Split('#')[0].Trim()
  if ([string]::IsNullOrWhiteSpace($clean)) { return $null }
  if ($clean -match '^(https?:|mailto:|tel:|#)') { return $null }
  $decoded = [Uri]::UnescapeDataString($clean)
  $candidate = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $sourcePath) $decoded))
  if (Test-Path -LiteralPath $candidate -PathType Container) {
    foreach ($name in @('README.md', 'README.en.md')) {
      $readme = Join-Path $candidate $name
      if (Test-Path -LiteralPath $readme -PathType Leaf) { return $readme }
    }
    return $null
  }
  if ((Test-Path -LiteralPath $candidate -PathType Leaf) -and ($candidate -match '\.md$')) {
    return $candidate
  }
  return $null
}

foreach ($entry in @((Join-Path $root 'README.md'), (Join-Path $docsRoot 'README.md'))) {
  if (Test-Path -LiteralPath $entry -PathType Leaf) { $queue.Enqueue($entry) }
}

while ($queue.Count -gt 0) {
  $current = $queue.Dequeue()
  if (-not $seen.Add($current)) { continue }
  $text = [System.IO.File]::ReadAllText($current, [System.Text.UTF8Encoding]::new($false, $true))
  foreach ($match in [regex]::Matches($text, '!?\[[^\]]*\]\(([^)]+)\)')) {
    $resolved = Resolve-DocLink $current $match.Groups[1].Value
    if ($resolved -and -not $seen.Contains($resolved)) { $queue.Enqueue($resolved) }
  }
}

# Historical process records under docs/99-archive are intentionally exempt.
$allDocs = @(Get-ChildItem $docsRoot -Recurse -File -Filter '*.md' | Where-Object { $_.FullName -notmatch '\\99-archive\\' })
foreach ($doc in $allDocs) {
  $full = [IO.Path]::GetFullPath($doc.FullName)
  if (-not $seen.Contains($full)) {
    $violations.Add("document is not reachable from README.md or docs/README.md: $($doc.FullName.Substring($root.Length + 1))")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Documentation reachability validation passed ($($allDocs.Count) documents)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Documentation reachability: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Documentation reachability: $($violations.Count) violation(s) in total."
exit 1
