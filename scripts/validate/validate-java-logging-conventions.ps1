[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  $files = git ls-files '*src/main/java/*.java'
  foreach ($relativePath in $files) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
      continue
    }
    $text = $utf8.GetString([System.IO.File]::ReadAllBytes($path))
    if ($text -match 'LoggerFactory\.getLogger|import\s+org\.slf4j\.Logger\s*;|\bLogger\s+\w+\s*=') {
      $violations.Add("Manual SLF4J logger: $relativePath")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Java logging convention validation passed.'
