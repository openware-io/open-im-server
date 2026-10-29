# Local one-click deployment contract checks.
#
# Static checks (no Docker/Kubernetes required) for the Kind local lifecycle
# entry point and its recovery helpers. Keep this script fast so it can run in
# CI and before a release.

[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-Parse([string]$RelativePath) {
  $path = Join-Path $root $RelativePath
  if (!(Test-Path -LiteralPath $path)) { $failures.Add("missing: $RelativePath"); return }
  $tokens = $null
  $errors = $null
  [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
  foreach ($error in @($errors)) { $failures.Add("parse ${RelativePath}: $($error.Message)") }
}

Assert-Parse 'scripts/deploy/kind-local.ps1'
Assert-Parse 'scripts/deploy/kind-recovery.ps1'
Assert-Parse 'scripts/deploy/status.ps1'
Assert-Parse 'deploy-k8s.ps1'

$kindLocal = Get-Content -Raw -LiteralPath (Join-Path $root 'scripts/deploy/kind-local.ps1')
foreach ($mode in @('auto', 'init', 'recover', 'redeploy', 'status', 'reset')) {
  if ($kindLocal -notmatch [regex]::Escape("'$mode'")) {
    $failures.Add("kind-local.ps1 does not expose mode '$mode'")
  }
}
if ($kindLocal -match 'Restart-KindWorkloads') {
  $failures.Add('kind-local.ps1 must not restart all workloads; use staged recovery.')
}

$recovery = Get-Content -Raw -LiteralPath (Join-Path $root 'scripts/deploy/kind-recovery.ps1')
foreach ($function in @('Repair-KindSystemImages', 'Wait-KindSystemReady', 'Ensure-KindDeploymentImages',
    'Restart-KindUnhealthyDeployments', 'Write-KindDiagnostics', 'Invoke-KindRecovery', 'Test-KindLocalEndpoints')) {
  if ($recovery -notmatch "function\s+$function") { $failures.Add("missing recovery function: $function") }
}

$doc = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/60-deployment/LOCAL.md')
foreach ($marker in @('-Mode recover', '-Mode status', 'kind-recovery.ps1', 'emptyDir')) {
  if ($doc -notmatch [regex]::Escape($marker)) { $failures.Add("LOCAL.md missing marker: $marker") }
}

if ($failures.Count -gt 0) {
  $failures | ForEach-Object { Write-Error $_ }
  exit 1
}
Write-Host 'Local deployment contract validation passed.'
