# Batch-2 helper: append the missing MQ resources to docs/mq/REGISTRY.md.
#
# The ledger rows live in mq-registry-ledger-entries.txt (UTF-8, contains Chinese). They are kept
# out of this file on purpose: this script MUST stay ASCII-only, because Windows PowerShell 5.1
# decodes a BOM-less .ps1 as ANSI and would corrupt any Chinese here at parse time - which makes the
# whole script fail to parse. That is the same trap standards/30 documents.
#
# Evidence behind the entries: .outputs/quality/mq-registry-worklist.txt (which names are missing)
# and .outputs/quality/mq-semantics-extract.txt (per-class group/topic constants + javadoc), the
# latter produced by the committed extractor scripts/validate/extract-mq-semantics.ps1.
#
# The script is idempotent: a resource already present in the ledger is skipped, so it can be re-run
# after the ledger gains rows. It writes UTF-8 without BOM.
#
# Usage: powershell -NoProfile -File scripts/validate/update-mq-registry-ledger.ps1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$registryPath = Join-Path $root 'docs\mq\REGISTRY.md'
$entriesPath = Join-Path $PSScriptRoot 'mq-registry-ledger-entries.txt'
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)

if (-not (Test-Path -LiteralPath $registryPath -PathType Leaf)) { throw "MQ ledger not found: $registryPath" }
if (-not (Test-Path -LiteralPath $entriesPath -PathType Leaf)) { throw "ledger entries not found: $entriesPath" }

$lines = [System.IO.File]::ReadAllLines($registryPath, $utf8)
$registryText = $lines -join "`n"

$pending = [System.Collections.Generic.List[string]]::new()
foreach ($raw in [System.IO.File]::ReadAllLines($entriesPath, $utf8)) {
  $entry = $raw.Trim()
  if (-not $entry -or $entry.StartsWith('#')) { continue }
  $field = $entry -split '\|' | ForEach-Object { $_.Trim() }
  if ($field.Count -ne 6) { throw "malformed entry (expected 6 fields: name | type | owner | purpose | orderKey | idempotencyKey): $entry" }
  $name = $field[0]
  if ($registryText.Contains('`' + $name + '`')) { continue }
  $pending.Add(('| {0} | `{1}` | `{2}` | {3} | {4} | {5} |' -f $field[1], $name, $field[2], $field[3], $field[4], $field[5]))
}

if ($pending.Count -eq 0) {
  Write-Host 'MQ ledger already lists every resource in mq-registry-ledger-entries.txt; nothing to do.'
  exit 0
}

# Insert directly after the final table row so the table stays contiguous.
$output = [System.Collections.Generic.List[string]]::new()
$lastTableRow = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
  $output.Add($lines[$i])
  if ($lines[$i].StartsWith('|')) { $lastTableRow = $output.Count - 1 }
}
if ($lastTableRow -lt 0) { throw "no markdown table found in $registryPath" }

for ($i = 0; $i -lt $pending.Count; $i++) {
  $output.Insert($lastTableRow + 1 + $i, $pending[$i])
}

[System.IO.File]::WriteAllLines($registryPath, [string[]]$output, [System.Text.UTF8Encoding]::new($false))
Write-Host "MQ ledger: appended $($pending.Count) row(s) to $registryPath"
