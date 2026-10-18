# Domain model purity validator (stands/10 "domain model rules").
#
# The standard already said the domain layer must not carry persistence or serialisation concerns, but
# nothing enforced it, so the rule was freely ignored. This gate makes it checkable.
#
# Scope: every tracked Java file whose path contains /domain/ inside a service module.
#
# Reported:
#   1. persistence or serialisation annotations on domain types
#      (@TableName @TableId @TableField @Entity @Data @Document @JsonIgnore and friends)
#   2. imports reaching into infrastructure or persistence packages
#   3. Lombok @Data/@Setter specifically, because an unconstrained whole-object setter is exactly what
#      the aggregate-root rule forbids
#
# Deliberately allowed in the domain layer: java.*, lombok @Getter/@Builder/@RequiredArgsConstructor,
# domain.repository, domain.event, domain.service, domain.policy and other domain packages.
#
# Usage: powershell -File scripts/validate/validate-domain-model-purity.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\domain-model-purity-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$annotationPattern = '@(TableName|TableId|TableField|TableLogic|Entity|Document|Data|Setter|JsonIgnore|JsonInclude|JsonProperty)\b'
$infraImportPattern = 'import\s+(io\.openware\.infrastructure|com\.baomidou|org\.apache\.ibatis|org\.springframework|jakarta\.persistence|redis\.clients|org\.apache\.rocketmq|javax\.persistence)\b'
$persistenceImportPattern = 'import\s+[^;]*\.persistence\.'

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/domain/') { continue }
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))

    $lineNumber = 0
    foreach ($line in ($text -split "`n")) {
      $lineNumber++
      $trimmed = $line.Trim()
      if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }
      if ($trimmed -match $annotationPattern) {
        $violations.Add("persistence/serialisation annotation in domain: ${relativePath}:$lineNumber")
      }
      if ($trimmed -match $infraImportPattern) {
        $violations.Add("domain imports infrastructure: ${relativePath}:$lineNumber")
      }
      elseif ($trimmed -match $persistenceImportPattern) {
        $violations.Add("domain imports persistence: ${relativePath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Domain model purity validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Domain model purity: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Domain model purity: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
