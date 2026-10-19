# Builds the in-scope documentation disposition manifest.
# Scope: the 8 im-saas repositories. open-green-pass and .github are intentionally excluded.
# This script only reads files and writes the manifest; it never moves or deletes documents.

[CmdletBinding()]
param(
  [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
  [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repos = @(
  'open-im-server',
  'open-chat-app',
  'open-chat-admin',
  'open-chat-desktop',
  'open-chat-turn',
  'open-saas-admin',
  'open-saas-mobile',
  'open-website'
)

$excludePattern = '\\(node_modules|build|dist|target|\.git|\.dart_tool|\.outputs)\\|\\site\\'
$manifest = [System.Collections.Generic.List[object]]::new()

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $WorkspaceRoot 'open-im-server/docs/archive/disposition-manifest.csv'
}
$existingByPath = @{}
if (Test-Path -LiteralPath $OutputPath -PathType Leaf) {
  foreach ($row in (Import-Csv -LiteralPath $OutputPath)) {
    $existingByPath["$($row.repository)|$($row.path)"] = $row
  }
}

function Get-ServiceTarget([string]$relativePath) {
  $name = Split-Path -Leaf (Split-Path -Parent $relativePath)
  $map = @{
    'admin'            = 'platform-admin-service'
    'audit'            = 'common-audit-service'
    'customer'         = 'platform-customer-service'
    'identity'         = 'platform-identity-service'
    'marketing'        = 'platform-marketing-service'
    'payment'          = 'common-payment-service'
    'payment-channel'  = 'common-payment-channel-service'
    'resource'         = 'platform-resource-service'
    'sms'              = 'common-sms-service'
    'tenant'           = 'platform-tenant-service'
  }
  if (-not $map.ContainsKey($name)) { return 'docs/services/' }
  return "docs/services/$($map[$name])/README.md"
}

foreach ($repo in $repos) {
  $repoRoot = Join-Path $WorkspaceRoot $repo
  if (-not (Test-Path -LiteralPath $repoRoot -PathType Container)) { throw "Missing repository: $repoRoot" }

  $files = Get-ChildItem $repoRoot -Recurse -File -Filter '*.md' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $excludePattern } |
    Sort-Object FullName

  foreach ($file in $files) {
    $relative = $file.FullName.Substring($repoRoot.Length).TrimStart('\').Replace('\', '/')
    $action = 'UPDATE'
    $type = 'guide'
    $target = $relative
    $reason = 'Current content needs verification and update.'
    $lifecycle = 'current'

    if ($repo -eq 'open-im-server') {
      if ($relative -like 'docs/renovation/*') {
        $action = 'ARCHIVE'; $type = 'renovation'; $lifecycle = 'historical'
        $target = 'docs/archive/renovation/' + $relative.Substring('docs/renovation/'.Length)
        $reason = 'Transformation history; extract still-valid conclusions before archiving.'
      } elseif ($relative -like 'docs/superpowers/*') {
        $action = 'ARCHIVE'; $type = 'plan'; $lifecycle = 'historical'
        $target = 'docs/archive/superpowers/' + $relative.Substring('docs/superpowers/'.Length)
        $reason = 'Historical design/plan snapshot.'
      } elseif ($relative -eq 'docs/audit-2026-09-17-exceptions-and-i18n.md') {
        $action = 'ARCHIVE'; $type = 'audit'; $lifecycle = 'historical'
        $target = 'docs/archive/audits/2026-09-17/' + (Split-Path -Leaf $relative)
        $reason = 'One-off audit snapshot.'
      } elseif ($relative -eq 'docs/currency-scope-inventory-2026-09-18.md') {
        $action = 'ARCHIVE'; $type = 'inventory'; $lifecycle = 'historical'
        $target = 'docs/archive/inventories/2026-09-18/' + (Split-Path -Leaf $relative)
        $reason = 'One-off scope inventory.'
      } elseif ($relative -eq 'docs/DATABASE_AUDIT_REPORT.md') {
        $action = 'ARCHIVE'; $type = 'audit'; $lifecycle = 'historical'
        $target = 'docs/archive/audits/database/' + (Split-Path -Leaf $relative)
        $reason = 'Historical database audit; extract current conclusions first.'
      } elseif ($relative -like 'docs/im-services/*') {
        $action = 'MERGE'; $type = 'service'; $lifecycle = 'current'
        $target = Get-ServiceTarget $relative
        $reason = 'Duplicates the service tree; merge into the authoritative service doc.'
      } elseif ($relative -like 'docs/archive/*') {
        $action = 'KEEP'; $type = 'archive'; $reason = 'Archived source retained for history; not a current contract.'
      } elseif ($relative -like 'docs/architecture/*') {
        $action = 'UPDATE'; $type = 'architecture'; $reason = 'Topology/architecture source; keep current.'
      } elseif ($relative -like 'docs/standards/*') {
        $action = 'UPDATE'; $type = 'standard'; $reason = 'Current standard; verify and translate by priority.'
      } elseif ($relative -like 'docs/deployment/*') {
        $action = 'UPDATE'; $type = 'deployment'; $reason = 'Verify ports, environments and release steps.'
      } elseif ($relative -like 'docs/services/*') {
        $action = 'UPDATE'; $type = 'service'; $reason = 'Current service doc; complete the service tree.'
      } elseif ($relative -like 'docs/business/*') {
        $action = 'UPDATE'; $type = 'business'; $reason = 'Current business semantics; absorb valid renovation conclusions.'
      } elseif ($relative -like 'docs/security/*' -or $relative -like 'docs/compliance/*' -or $relative -like 'docs/contracts/*' -or $relative -like 'docs/features/*' -or $relative -like 'docs/mq/*') {
        $action = 'UPDATE'; $type = 'domain'; $reason = 'Current domain doc; link it into the domain tree.'
      } elseif ($relative -eq 'docs/adr/0007-version-named-exceptions.md') {
        $action = 'SUPERSEDED'; $type = 'adr'; $lifecycle = 'superseded'
        $reason = 'Withdrawn ADR; keep as a superseded decision record.'
      } elseif ($relative -eq 'README.md' -or $relative -eq 'README.en.md' -or $relative -eq 'docs/README.md' -or $relative -eq 'docs/README.en.md' -or $relative -like 'docs/DOCUMENTATION_*' -or $relative -eq 'docs/ARCHITECTURE.md' -or $relative -eq 'docs/ARCHITECTURE.en.md') {
        $action = 'KEEP'; $type = 'governance'; $reason = 'Current entry or governance document.'
      } elseif ($relative -eq 'docs/ENGINEERING_RULES.md' -or $relative -eq 'docs/GATE_REGISTRY.md' -or $relative -eq 'docs/PROTOCOL.md' -or $relative -eq 'docs/DATABASE_MIGRATION_STANDARD.md' -or $relative -eq 'docs/AI_COLLABORATION_PRINCIPLES.md') {
        $action = 'KEEP'; $type = 'governance'; $reason = 'Current engineering rule or contract.'
      } elseif ($relative -eq 'docs/API_DOCUMENTATION.md') {
        $action = 'UPDATE'; $type = 'governance'; $reason = 'Keep the gateway Swagger entry current.'
      } elseif ($relative -eq 'docs/RELEASE_RUNBOOK.md' -or $relative -eq 'docs/PUSH_SETUP.md' -or $relative -eq 'docs/IDE_SETUP.md') {
        $action = 'UPDATE'; $type = 'ops'; $reason = 'Current operations guide; verify commands and endpoints.'
      } else {
        $action = 'UPDATE'; $type = 'governance'; $reason = 'Current governance/docs file; verify scope and links.'
      }
    } else {
      if ($relative -eq 'README.md') {
        $action = 'UPDATE'; $type = 'readme'; $reason = 'Repository entry; add README.en.md and align commands.'
      } elseif ($relative -eq 'README.en.md') {
        $action = 'KEEP'; $type = 'readme'; $reason = 'English repository entry.'
      } elseif ($relative -like 'docs/release-notes*') {
        $action = 'UPDATE'; $type = 'release'; $reason = 'Release notes need current version/name alignment.'
      } elseif ($relative -like 'docs/*') {
        $action = 'UPDATE'; $type = 'guide'; $reason = 'Current repository doc; verify and translate by priority.'
      }
    }

    $repoPath = if ($repo -eq 'open-im-server') { $relative } else { "$repo/$relative" }
    $rowStatus = if ($action -eq 'KEEP' -or $action -eq 'SUPERSEDED') { 'current' } else { 'planned' }
    $existing = $existingByPath["$repo|$repoPath"]
    if ($null -ne $existing -and $existing.status -in @('archived', 'merged', 'current')) {
      $rowStatus = $existing.status
    }
    $manifest.Add([pscustomobject]@{
      repository = $repo
      path       = $repoPath
      type       = $type
      action     = $action
      target     = if ($repo -eq 'open-im-server') { $target } else { "$repo/$target" }
      lifecycle  = $lifecycle
      owner      = 'im-saas docs'
      status     = $rowStatus
      reason     = $reason
    })
  }
}

# Preserve historical source rows that no longer exist at their old path (moved/archived docs).
foreach ($key in $existingByPath.Keys) {
  $existing = $existingByPath[$key]
  $alreadyListed = $manifest | Where-Object { "$($_.repository)|$($_.path)" -eq $key }
  if (-not $alreadyListed) {
    $manifest.Add($existing)
  }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $WorkspaceRoot 'open-im-server/docs/archive/disposition-manifest.csv'
}
$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null }

$manifest | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding utf8
Write-Host "Wrote $($manifest.Count) rows to $OutputPath"
$manifest | Group-Object action | Sort-Object Name | ForEach-Object { Write-Host ("{0}: {1}" -f $_.Name, $_.Count) }
