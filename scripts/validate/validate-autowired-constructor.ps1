# Unnecessary @Autowired constructor validator (G46).
#
# Spring injects a sole constructor automatically. @Autowired is only needed
# when a class has multiple constructors and one must be selected. This gate
# prevents the IDE warning from returning in new code.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\autowired-constructor-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  $files = git -C $root ls-files '*.java' | Where-Object { $_ -match '/src/main/java/' }
  foreach ($relative in $files) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $lines = [System.IO.File]::ReadAllLines($path, [System.Text.UTF8Encoding]::new($false, $true))
    $className = [System.IO.Path]::GetFileNameWithoutExtension($path)
    $constructorCount = ([regex]::Matches(($lines -join "`n"), ('(?m)^\s*(?:public|protected|private)?\s*' + [regex]::Escape($className) + '\s*\('))).Count
    if ($constructorCount -gt 1) { continue }

    for ($i = 0; $i -lt $lines.Count - 1; $i++) {
      if ($lines[$i].Trim() -ne '@Autowired') { continue }
      if ($lines[$i + 1] -match ('^\s*public\s+' + [regex]::Escape($className) + '\s*\(')) {
        $violations.Add("unnecessary @Autowired on sole constructor: ${relative}:$($i + 1)")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Autowired constructor validation passed.'
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Autowired constructor: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Autowired constructor: $($violations.Count) violation(s) in total."
exit 1
