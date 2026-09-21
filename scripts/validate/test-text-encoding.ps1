[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("text-encoding-{0}" -f [guid]::NewGuid().ToString('N'))
$validatorDirectory = Join-Path $root 'scripts\validate'
$validator = Join-Path $validatorDirectory 'validate-text-encoding.ps1'

try {
  [void](New-Item -ItemType Directory -Path $validatorDirectory -Force)
  Copy-Item (Join-Path $PSScriptRoot 'validate-text-encoding.ps1') $validator
  [System.IO.File]::WriteAllText(
      (Join-Path $root 'AsciiSample.java'),
      'class AsciiSample { String value = "kwargs"; }' + [Environment]::NewLine,
      [System.Text.UTF8Encoding]::new($false))

  & powershell -NoProfile -ExecutionPolicy Bypass -File $validator -Strict
  if ($LASTEXITCODE -ne 0) {
    throw 'The encoding validator incorrectly rejected valid ASCII source.'
  }

  $original = [string]::Concat([char]0x4E2D, [char]0x6587)
  $mojibake = [System.Text.Encoding]::GetEncoding(936).GetString([System.Text.Encoding]::UTF8.GetBytes($original))
  [System.IO.File]::WriteAllText(
      (Join-Path $root 'MojibakeSample.java'),
      'class MojibakeSample { String text = "' + $mojibake + '"; }' + [Environment]::NewLine,
      [System.Text.UTF8Encoding]::new($false))

  & powershell -NoProfile -ExecutionPolicy Bypass -File $validator -Strict
  if ($LASTEXITCODE -eq 0) {
    throw 'The encoding validator did not detect reversible UTF-8/GBK mojibake.'
  }

} finally {
  if (Test-Path $root) {
    Remove-Item -Path $root -Recurse -Force
  }
}

Write-Host 'Text encoding mojibake regression test passed.'
