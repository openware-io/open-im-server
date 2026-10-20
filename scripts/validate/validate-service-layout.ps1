# Service package-layout template validator (G43).
#
# Enforces the fixed DDD service layout documented in standards/10:
#   api/controller, application, domain, infra, plus the standard support
#   packages config/ and handler/.
#
# New controllers must live under api/controller. The legacy top-level
# controller package is allowed only for the services listed in
# service-layout-legacy-controllers.txt, until they are migrated.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$legacyPath = Join-Path $PSScriptRoot 'service-layout-legacy-controllers.txt'
$worklistPath = Join-Path $root '.outputs\quality\service-layout-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$legacyControllers = [System.Collections.Generic.HashSet[string]]::new()
if (Test-Path -LiteralPath $legacyPath) {
  foreach ($line in [System.IO.File]::ReadAllLines($legacyPath, [System.Text.UTF8Encoding]::new($false))) {
    $name = ($line -split '\|')[0].Trim()
    if ($name -and -not $name.StartsWith('#')) { [void]$legacyControllers.Add($name) }
  }
}

$allowedTopLevel = @(
  'api', 'application', 'domain', 'infra',
  'config', 'handler',
  'controller', 'media', 'conversation', 'messaging', 'scheduler', 'command', 'integration'
)
$domainOptional = @('common-media-service', 'common-payment-channel-service')

function Add-Violation([string]$message) {
  $violations.Add($message)
}

Push-Location $root
try {
  $serviceDirs = @()
  foreach ($domainRoot in @('im-services', 'platform-services', 'common-services', 'group-services')) {
    if (-not (Test-Path -LiteralPath $domainRoot)) { continue }
    $serviceDirs += Get-ChildItem $domainRoot -Recurse -Directory -Filter '*-service' |
      Where-Object { $_.FullName -notmatch '\\target\\' }
  }

  foreach ($service in $serviceDirs) {
    $serviceName = $service.Name
    $javaRoot = Join-Path $service.FullName 'src\main\java'
    if (-not (Test-Path -LiteralPath $javaRoot)) { continue }

    $layoutExempt = $serviceName -in @('common-media-service')
    $domainDir = Get-ChildItem $javaRoot -Recurse -Directory -Filter 'domain' | Select-Object -First 1
    $apiDir = Get-ChildItem $javaRoot -Recurse -Directory -Filter 'api' | Select-Object -First 1
    $controllerDir = Get-ChildItem $javaRoot -Recurse -Directory -Filter 'controller' | Select-Object -First 1
    $contextRoot = if ($domainDir) {
      $domainDir.Parent.FullName
    } elseif ($apiDir) {
      $apiDir.Parent.FullName
    } elseif ($controllerDir) {
      $controllerDir.Parent.FullName
    } else { $null }
    if (-not $contextRoot) {
      Add-Violation "cannot locate context package: $($service.FullName.Substring($root.Length + 1))"
      continue
    }
    $topNames = @(Get-ChildItem $contextRoot -Directory | Select-Object -ExpandProperty Name)

    if (-not $layoutExempt) {
      foreach ($required in @('api', 'application', 'infra')) {
        if ($required -notin $topNames) {
          Add-Violation "missing required layer '$required': $($service.FullName.Substring($root.Length + 1))"
        }
      }
      if ($serviceName -notin $domainOptional -and 'domain' -notin $topNames) {
        Add-Violation "missing required layer 'domain': $($service.FullName.Substring($root.Length + 1))"
      }
    }
    foreach ($name in $topNames) {
      if ($name -notin $allowedTopLevel) {
        Add-Violation "unexpected top-level package '$name': $($service.FullName.Substring($root.Length + 1))"
      }
    }

    $controllers = Get-ChildItem $javaRoot -Recurse -File -Filter '*.java' |
      Where-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName, [System.Text.UTF8Encoding]::new($false, $true))
        $text -match '@RestController\\b'
      }
    foreach ($controller in $controllers) {
      $relative = $controller.FullName.Substring($service.FullName.Length).Replace('\', '/')
      if ($controller.FullName -match '\\api\\controller\\') { continue }
      if ($controller.FullName -match '\\controller\\') {
        if ($legacyControllers.Contains($serviceName)) { continue }
        Add-Violation "new top-level controller requires api/controller: $serviceName$relative"
      } else {
        Add-Violation "controller outside api/controller: $serviceName$relative"
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Service layout validation passed ($($serviceDirs.Count) services)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Service layout: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Service layout: $($violations.Count) violation(s) in total."
exit 1
