# One-command domain E2E entrypoint for the local Kind release.
# It prepares deterministic E2E credentials, then runs the B-side/admin E2E and
# (when present) the C-side / OTA / channel-payment E2E scripts.
[CmdletBinding()]
param(
    [string]$Gateway = 'http://127.0.0.1:30080',
    [string]$Namespace = 'open-im-local',
    [string]$OutDir = '',
    [switch]$SkipCredentialPrepare,
    [switch]$SkipAdminE2e,
    # Interim diagnostics only. The final freeze gate MUST run without this switch so that
    # missing OTA/channel features are hard failures (fail-closed), never silent gaps.
    [switch]$AllowFeatureAbsent
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $root '.outputs\releases\architect-ii\e2e'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (-not $SkipCredentialPrepare) {
    & (Join-Path $root 'scripts\dev\prepare-e2e-credentials.ps1') -Namespace $Namespace -Gateway $Gateway
    if ($LASTEXITCODE -ne 0) { throw 'E2E credential preparation failed.' }
}

if (-not $SkipAdminE2e) {
    $adminE2e = Join-Path $root '.outputs\releases\architect\e2e-domain-refactor.ps1'
    if (-not (Test-Path -LiteralPath $adminE2e)) {
        throw "Admin E2E script not found: $adminE2e"
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $adminE2e -Gateway $Gateway -OutDir $OutDir
    if ($LASTEXITCODE -ne 0) { throw 'Admin E2E failed.' }
}

$cE2e = Join-Path $root 'scripts\verify\e2e-c-end-ota-channel.ps1'
if (-not (Test-Path -LiteralPath $cE2e)) {
    # Fail-closed: the final domain gate must never silently skip C-side / OTA / channel E2E.
    throw "C-side / OTA / channel E2E script not found: $cE2e"
}
$cArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $cE2e, '-Gateway', $Gateway, '-OutDir', $OutDir)
if ($AllowFeatureAbsent) { $cArgs += '-AllowFeatureAbsent' }
& powershell @cArgs
if ($LASTEXITCODE -ne 0) { throw 'C-side / OTA / channel E2E failed.' }

Write-Host "Domain E2E completed. Results: $OutDir"
