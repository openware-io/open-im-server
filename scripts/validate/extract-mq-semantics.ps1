# MQ semantics extractor (batch 2 helper for the docs/mq/REGISTRY.md ledger).
#
# WHY: validate-mq-registry.ps1 reports resources that exist as protocol-mq constants but are
# missing from the ledger. Filling a ledger row needs four semantic columns (owning module,
# consumption purpose, order key, idempotency key). Those must come from the implementation, not
# from guessing, so this script collects the evidence in one place: for every class that
# references a protocol-mq group or topic it prints the class path, the group constants, the topic
# constants, and the class-level javadoc (which in this codebase states the purpose and usually
# the idempotency key).
#
# It only reads; it writes nothing except the report. Output default:
#   .outputs/quality/mq-semantics-extract.txt
#
# IMPORTANT: keep this file ASCII-only. Windows PowerShell 5.1 decodes a BOM-less .ps1 as ANSI, so
# a non-ASCII character here (an ellipsis, a Chinese word) is corrupted at parse time and the
# script fails to parse. Use [char]0x2026 for an ellipsis.
#
# Usage: powershell -NoProfile -File scripts/validate/extract-mq-semantics.ps1 [-OutputPath <file>]
[CmdletBinding()]
param(
  [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root '.outputs\quality\mq-semantics-extract.txt'
}
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$ellipsis = [char]0x2026

$groupPattern = '(ImMqConsumerGroups|CustomerMqConsumerGroups|ResourceMqConsumerGroups|MktMqConsumerGroups|PayMqConsumerGroups|ImMqProducerGroups|MktMqProducerGroups|OrderMqProducerGroups|PayMqProducerGroups|ResourceMqProducerGroups)\.([A-Z0-9_]+)'
$topicPattern = '(ImMqTopics|CustomerMqTopics|ResourceMqTopics|MktMqTopics|PayMqTopics|OrderMqTopics)\.([A-Z0-9_]+)'

$files = Get-ChildItem $root -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch '\\(target|\.git|\.outputs)\\' }

$rows = [System.Collections.Generic.List[string]]::new()
foreach ($file in $files) {
  $text = [System.IO.File]::ReadAllText($file.FullName, $utf8)
  $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')

  $groups = [regex]::Matches($text, $groupPattern) | ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique
  $topics = [regex]::Matches($text, $topicPattern) | ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique
  if (-not $groups -and -not $topics) { continue }

  $purpose = ''
  $match = [regex]::Match($text, '(?s)/\*\*(?<doc>.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+(?:final\s+|abstract\s+)?(?:class|interface|record)\s')
  if ($match.Success) {
    $doc = ($match.Groups['doc'].Value -split "`n" |
      ForEach-Object { ($_ -replace '^\s*\*?\s?', '').Trim() } |
      Where-Object { $_ }) -join ' '
    if ($doc.Length -gt 120) { $doc = $doc.Substring(0, 120) + $ellipsis }
    $purpose = $doc
  }

  $rows.Add("FILE $relative")
  if ($groups) { $rows.Add('  GROUPS ' + ($groups -join ',')) }
  if ($topics) { $rows.Add('  TOPICS ' + ($topics -join ',')) }
  if ($purpose) { $rows.Add("  DOC $purpose") }
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath))
[System.IO.File]::WriteAllLines($OutputPath, [string[]]$rows, [System.Text.UTF8Encoding]::new($false))
Write-Host "MQ semantics extract written: $OutputPath ($($rows.Count) lines)."
