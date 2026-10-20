# Controller mapping integrity validator (G45).
#
# This gate catches real routing defects that the IDE Spring Boot "extract common
# parent path" hint does not:
#   - duplicate HTTP verb + full path across controllers
#   - a method path that repeats the class-level path (class "/x", method "/x/y")
#
# The Spring Tools EXTRACT_REQUEST_MAPPING_PARENT_PATH diagnostic is an optional
# style hint, not a compiler warning; it is configured as IGNORE in .vscode/settings.json.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\controller-mapping-integrity-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$seen = @{}

function Normalize-Path([string]$path) {
  if ([string]::IsNullOrWhiteSpace($path)) { return '/' }
  $value = '/' + $path.Trim('/')
  return $value
}

Push-Location $root
try {
  $files = git -C $root ls-files '*.java' | Where-Object { $_ -match '/src/main/java/.*Controller\.java$' }
  foreach ($relative in $files) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    if ($text -notmatch '@RestController\b') { continue }

    $header = ($text -split 'public class', 2)[0]
    $classMatch = [regex]::Match($header, '@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
    $classPath = if ($classMatch.Success -and -not [string]::IsNullOrWhiteSpace($classMatch.Groups[1].Value)) { Normalize-Path $classMatch.Groups[1].Value } else { '' }

    $methods = [regex]::Matches($text, '@(?<verb>Get|Post|Put|Delete|Patch)Mapping\s*\((?<args>[^)]*)\)')
    foreach ($method in $methods) {
      $verb = $method.Groups['verb'].Value.ToUpperInvariant()
      $args = $method.Groups['args'].Value
      $pathMatch = [regex]::Match($args, '"(?<path>[^"]*)"')
      $methodPath = if ($pathMatch.Success) { $pathMatch.Groups['path'].Value } else { '' }
      $consumesMatch = [regex]::Match($args, 'consumes\s*=\s*"([^"]*)"')
      $consumes = if ($consumesMatch.Success) { $consumesMatch.Groups[1].Value } else { '' }
      if ($classPath -and $methodPath.StartsWith($classPath.TrimEnd('/') + '/')) {
        $violations.Add("method path repeats class-level path: $relative -> $methodPath")
      }
      $fullPath = if ($classPath) { Normalize-Path ($classPath.TrimEnd('/') + '/' + $methodPath.TrimStart('/')) } else { Normalize-Path $methodPath }
      $service = ($relative -split '/') | Where-Object { $_ -like '*-service' } | Select-Object -First 1; $key = "$service|$verb $fullPath|consumes=$consumes"
      if ($seen.ContainsKey($key)) {
        $violations.Add("duplicate mapping ${key}: $relative and $($seen[$key])")
      } else {
        $seen[$key] = $relative
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Controller mapping integrity validation passed ($($seen.Count) mappings)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Controller mapping integrity: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Controller mapping integrity: $($violations.Count) violation(s) in total."
exit 1
