[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$violations = [System.Collections.Generic.List[string]]::new()

Get-ChildItem -Path $root -Recurse -Filter '*.ps1' -File |
  Where-Object { $_.FullName -notmatch '\\(\.git|target|node_modules)\\' } |
  ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
      $violations.Add("UTF-8 BOM: $($_.FullName.Substring($root.Length + 1))")
    }
    try {
      [void]$utf8.GetString($bytes)
    } catch {
      $violations.Add("Invalid UTF-8: $($_.FullName.Substring($root.Length + 1))")
    }
  }

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'PowerShell UTF-8 safety validation passed.'
