[CmdletBinding()]
param()

# Unified exception-outlet gate (audit report sections 1 H-1/H-3/H-5 and 5 batch 3.5).
#
# Why this exists: of the 20 deployable services, only 15 had a @RestControllerAdvice,
# 6 of them mapped ApiException only (any non-business exception fell through to the
# Spring default body {"timestamp","status","error","path"}), and 8 swallowed the
# framework exceptions Spring uses to express 4xx (NoResourceFoundException,
# HttpRequestMethodNotSupportedException, ...) into 500 without logging, with English messages.
# The pre-existing java-logging-conventions / java-exception-logging gates cover none of this.
#
# Rules, evaluated per service that has a REST surface (source lives under
# <service>/src/main/java and declares at least one @RestController):
#   R1 a unified exception outlet exists (at least one @RestControllerAdvice);
#   R2 an Exception (or Throwable) fallback branch exists;
#   R3 the fallback branch calls log.error (silent 500s cannot be diagnosed, H-3);
#   R4 the error body carries a "code" field (clients branch on code, M-1);
#   R5 the fallback message is Simplified Chinese (SaaS-facing copy rule, section 4.2);
#   R6 the advice explicitly maps the 404/405 framework exceptions
#      (NoResourceFoundException / HttpRequestMethodNotSupportedException); otherwise the
#      Exception fallback turns them into 500 (H-1: PATCH / and GET /zzz both returned 500).
#
# Exemption list (progressive rollout). A service that cannot satisfy the rules yet must be listed
# here with a reason, and must be removed from the table once fixed so the gate takes over.
# The table is now EMPTY: the IM-side workstream (im-admin/conversation/message/user), the
# media/common workstream (common-media) and the last three services without an advice
# (common-audit/common-mail/common-sms) all landed a compliant unified outlet, and group-idaas
# gained an Exception fallback plus explicit 404/405/415 mappings. Keep the mechanism for future
# staged rollouts.
#   group-idaas note: the service sets server.servlet.context-path: /idaas, so a URL outside that
#   context path (e.g. GET /zzz) is answered by the servlet container before Spring MVC runs and
#   cannot be turned into JSON by an @RestControllerAdvice. In-context unknowns (/idaas/zzz) are
#   covered by the NoResourceFoundException branch, which is what R6 checks.
#   Validators deliberately ignore sub-repositories other than this one (see the audit report
#   sections 1 H-1/H-3/H-5 for the full per-service evidence).
# Note: gateways/gateway (WebFlux GlobalFilter) and gateways/im-access-ws (WebSocket only)
# expose no @RestController, so the rule is not applicable and they never appear here.
#
# This script is intentionally ASCII-only: Windows PowerShell 5.1 reads UTF-8 files without
# a BOM using the ANSI code page, so non-ASCII source here would corrupt under `powershell -File`.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$violations = [System.Collections.Generic.List[string]]::new()
$exempted = [System.Collections.Generic.List[string]]::new()

$exempt = @{
}

# Simplified Chinese ideographs, expressed with ASCII-only .NET regex escapes.
$cjkPattern = '[\u4e00-\u9fff]'

function Get-AdviceFallbackRegion {
  param([string]$Text)

  $match = [regex]::Match($Text, '@ExceptionHandler\s*\(\s*(?:Exception|Throwable)\.class\s*\)')
  if (-not $match.Success) {
    return $null
  }
  $rest = $Text.Substring($match.Index + $match.Length)
  $next = [regex]::Match($rest, '@ExceptionHandler')
  if ($next.Success) {
    $rest = $rest.Substring(0, $next.Index)
  }
  if ($rest.Length -gt 4000) {
    $rest = $rest.Substring(0, 4000)
  }
  return $rest
}

Push-Location $root
try {
  $serviceDirectories = @()
  foreach ($pom in (git ls-files '*pom.xml')) {
    if ($pom -match '(^|/)target/') {
      continue
    }
    $directory = Split-Path -Parent $pom
    if (-not $directory) {
      continue
    }
    if ((Split-Path -Leaf $directory) -match '-service$') {
      $serviceDirectories += $directory
    }
  }
  $serviceDirectories = $serviceDirectories | Sort-Object -Unique
  $mainJava = git ls-files '*/src/main/java/*.java'
} finally {
  Pop-Location
}

foreach ($serviceDirectory in $serviceDirectories) {
  $serviceName = Split-Path -Leaf $serviceDirectory
  $prefix = $serviceDirectory.Replace('\', '/') + '/src/main/java/'
  $serviceFiles = $mainJava | Where-Object { $_.StartsWith($prefix) }

  if (-not $serviceFiles) {
    continue
  }

  $sources = @{}
  $hasRestController = $false
  foreach ($relativePath in $serviceFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
      continue
    }
    $text = $utf8.GetString([System.IO.File]::ReadAllBytes($path))
    $sources[$relativePath] = $text
    if ($text -match '@RestController\b') {
      $hasRestController = $true
    }
  }

  if (-not $hasRestController) {
    # No REST surface (pure consumer/scheduler service): rule not applicable.
    continue
  }

  if ($exempt.ContainsKey($serviceName)) {
    $exempted.Add("$serviceName (exempt: $($exempt[$serviceName]))")
    continue
  }

  $advicePaths = @($sources.Keys | Where-Object { $sources[$_] -match '@RestControllerAdvice' })
  if ($advicePaths.Count -eq 0) {
    $violations.Add("R1 ${serviceName}: no unified exception outlet (@RestControllerAdvice)")
    continue
  }

  $adviceText = ($advicePaths | ForEach-Object { $sources[$_] }) -join "`n"
  $fallback = Get-AdviceFallbackRegion -Text $adviceText

  if ($null -eq $fallback) {
    $violations.Add("R2 ${serviceName}: advice has no Exception/Throwable fallback branch")
  } else {
    if ($fallback -notmatch 'log\.error') {
      $violations.Add("R3 ${serviceName}: fallback does not call log.error (silent 500)")
    }
    if ($fallback -notmatch $cjkPattern) {
      $violations.Add("R5 ${serviceName}: fallback message is not Simplified Chinese")
    }
  }

  if ($adviceText -notmatch '"code"') {
    $violations.Add("R4 ${serviceName}: unified error body has no code field")
  }

  if ($adviceText -notmatch 'NoResourceFoundException' -or
      $adviceText -notmatch 'HttpRequestMethodNotSupportedException') {
    $violations.Add("R6 ${serviceName}: advice does not map the 404/405 framework exceptions (swallowed into 500)")
  }
}

if ($exempted.Count -gt 0) {
  Write-Host 'Exempted services (see header for reasons):'
  $exempted | ForEach-Object { Write-Host "  - $_" }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Exception handling validation passed.'
