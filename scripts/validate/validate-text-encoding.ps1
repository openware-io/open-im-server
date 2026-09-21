[CmdletBinding()]
param(
  [switch]$Strict
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$textExtensions = @('.bat', '.java', '.md', '.properties', '.ps1', '.py', '.sql', '.xml', '.yaml', '.yml')
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$gbk = [System.Text.Encoding]::GetEncoding(936)
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  $files = Get-ChildItem -Path $root -Recurse -File
  foreach ($file in $files) {
    $relativePath = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
    if ($relativePath -match '(^|/)(\.git|\.superpowers|target|node_modules)(/|$)' -or
        $relativePath -like 'release_manager/python-*-embed-amd64/*') {
      continue
    }
    if ($textExtensions -notcontains [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()) {
      continue
    }

    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
    if ($Strict -and $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
      $violations.Add("UTF-8 BOM: $relativePath")
      continue
    }

    try {
      $text = $utf8.GetString($bytes)
    } catch {
      $violations.Add("Invalid UTF-8: $relativePath")
      continue
    }

    if ($text.Contains([char]0xFFFD)) {
      $violations.Add("Replacement character: $relativePath")
    }
    if ($text -match ('u' + '3002')) {
      $violations.Add("Literal Unicode full-stop escape: $relativePath")
    }

    $lineNumber = 0
    foreach ($line in $text -split "`r?`n") {
      $lineNumber++
      try {
        $recovered = $utf8.GetString($gbk.GetBytes($line))
      } catch {
        continue
      }
      if ($line -match '[\uE000-\uF8FF]' -and
          $recovered -ne $line -and
          $recovered -match '[\u4E00-\u9FFF]{2,}') {
        $violations.Add("Possible UTF8-GBK mojibake: ${relativePath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Text encoding validation passed.'
