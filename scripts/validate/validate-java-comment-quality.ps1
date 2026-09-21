[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$violations = [System.Collections.Generic.List[string]]::new()
$replacementCharacter = [char]0xFFFD
$unicodeFullStopEscape = 'u' + '3002'
$mojibakeCommentPattern = '(?:\*|//).*?(?:[\u00C2\u00C3]|\u00E2[\u0080-\u00BF])'

Get-ChildItem -Path $root -Recurse -Filter '*.java' -File |
  Where-Object { $_.FullName -notmatch '\\(\.git|target|node_modules)\\' } |
  ForEach-Object {
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadAllLines($_.FullName, $utf8)) {
      $lineNumber++
      if ($line -match $mojibakeCommentPattern -or
          $line.Contains($replacementCharacter) -or
          $line.Contains($unicodeFullStopEscape)) {
        $violations.Add("Unreadable Java comment: $($_.FullName.Substring($root.Length + 1)):$lineNumber")
      }
    }
  }

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Java comment quality validation passed.'
