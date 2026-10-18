# Platform media boundary validator (standards/13 section 10, static checks).
#
# Covered:
#   1. no proxy-upload route (POST /api/v1/upload or equivalent controller mapping)
#   2. storage-provider SDK types stay inside common-media-service
#   3. MEDIA_INTERNAL_ENDPOINT does not appear in API/DTO responses
#
# Not covered here (need runtime/config review): public-media URL uniqueness and
# signed-URL/binary log redaction. Those stay manual until a config-level gate exists.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-media-boundaries.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\media-boundaries-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

function Remove-JavaComments([string]$text) {
  $withoutBlock = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
  return [regex]::Replace($withoutBlock, '(?m)//.*$', '')
}

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    $code = Remove-JavaComments $text

    # 1. Proxy upload: a controller mapping ending in /upload is the rejected shape.
    # Presigned session endpoints such as /upload-sessions are intentionally allowed.
    if ($relativePath -match '/controller/' -and
        $code -match '@PostMapping\s*\(\s*(value\s*=\s*)?"[^"]*/upload/?"') {
      $violations.Add("Proxy upload route must not exist: $relativePath")
    }

    # 2. Provider SDK leakage outside the media service.
    if ($relativePath -notmatch '^common-services/media/') {
      if ($code -match 'import\s+(io\.minio|com\.aliyun\.oss|software\.amazon\.awssdk|com\.qcloud)\.') {
        $violations.Add("Storage-provider SDK type outside common-media-service: $relativePath")
      }
    }

    # 3. Internal endpoint must not be exposed by API/DTO code.
    if ($relativePath -match '/(api|dto)/' -and $code -match 'MEDIA_INTERNAL_ENDPOINT|media\.internal-endpoint|internalEndpoint') {
      $violations.Add("Media internal endpoint leaked into API/DTO: $relativePath")
    }

    # 4. Media logs must not print signed URLs, credentials or binary payloads.
    if ($relativePath -match '^common-services/media/' -and
        $code -match '(?is)log\.(trace|debug|info|warn|error)\s*\([^;]*\b(presign\w*|signed\w*|accessKey|secretKey|bytes|body|payload)\b') {
      $violations.Add("Media log prints signed URL, credential or binary payload: $relativePath")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Media boundary validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Media boundaries: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Media boundaries: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
