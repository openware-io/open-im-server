[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$violations = [System.Collections.Generic.List[string]]::new()
# platform-services 尚未完成 DDD 分层改造（约 26 个 controller 直连 mapper/po、13 个 application 直连 SDK infra），
# 暂不纳入本门禁，待独立改造后再补回覆盖（见 .agents/notes/maven-version-ownership.md 的遗留清单）。
$apiRoots = git -C $root ls-files 'im-services/*/*-api/src/main/java/*.java' 'common-services/*/*-api/src/main/java/*.java' 'group-services/*/*-api/src/main/java/*.java'
$serviceRoots = git -C $root ls-files 'im-services/*/*-service/src/main/java/*.java' 'common-services/*/*-service/src/main/java/*.java' 'group-services/*/*-service/src/main/java/*.java'

foreach ($relativePath in $apiRoots) {
  $path = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $path)) { continue }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  if ($text -match '@TableName|@Mapper|@Service|@RestController|BaseMapper|Repository|Flyway') {
    $violations.Add("Business implementation in API module: $relativePath")
  }
}

foreach ($relativePath in $serviceRoots) {
  $path = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $path)) { continue }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  if ($relativePath -match '/application/' -and $text -match 'import\s+io\.openware\.infrastructure\.mq\.') {
    $violations.Add("Application layer depends on infrastructure implementation: $relativePath")
  }
  if ($relativePath -match '/controller/' -and $text -match 'import\s+.*\.(repository|mapper|po)\.') {
    $violations.Add("Controller depends on persistence implementation: $relativePath")
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Service API boundary validation passed.'
