# MQ ledger semantics validator (standards/12, P3).
#
# The existing mq-registry gate compares names only. This one checks the semantic columns:
# every Topic / Consumer Group row must declare a real order key and idempotency key; "-" is
# only allowed for Producer Group rows (they have no consumption semantics).
#
# The ledger still has pending rows. This gate is report-only until that finite worklist is cleared;
# do not invent values to silence it - fill them from the producer/consumer payload.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-mq-semantics.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$registryPath = Join-Path $root 'docs\30-reference\mq\REGISTRY.md'
$worklistPath = Join-Path $root '.outputs\quality\mq-semantics-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $registryPath -PathType Leaf)) {
  Write-Error 'MQ ledger is missing: docs/30-reference/mq/REGISTRY.md'
  exit 1
}

$rowPattern = '(?m)^\|\s*(Topic|Consumer Group)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|'
# The pending marker is built from code points so this script stays ASCII-only.
$pending = [string]([char]0x5F85) + [char]0x6838
# Message-chain resources must use conversationId even if some other key is non-empty.
$messageChain = @(
  'im_message_command_read_v1',
  'im_message_command_recall_v1',
  'im-message-service-read-command-consumer',
  'im-message-service-recall-command-consumer'
)
foreach ($match in [regex]::Matches([System.IO.File]::ReadAllText($registryPath, [System.Text.UTF8Encoding]::new($false, $true)), $rowPattern)) {
  $name = $match.Groups[2].Value.Trim().Trim('`')
  $orderKey = $match.Groups[5].Value.Trim().Trim('`')
  $idempotencyKey = $match.Groups[6].Value.Trim().Trim('`')

  if ($orderKey -eq '' -or $orderKey -eq '-' -or $orderKey -eq $pending) {
    $violations.Add("MQ ledger row has no real order key: $name")
  }
  if ($idempotencyKey -eq '' -or $idempotencyKey -eq '-' -or $idempotencyKey -eq $pending) {
    $violations.Add("MQ ledger row has no real idempotency key: $name")
  }
  if ($name -in $messageChain -and $orderKey -ne 'conversationId') {
    $violations.Add("Message-chain row must use conversationId as order key: $name (found '$orderKey')")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'MQ ledger semantic validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "MQ ledger semantics: $($violations.Count) pending row(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "MQ ledger semantics: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
