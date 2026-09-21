param(
  [string]$MySql = $env:MYSQL_BIN
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($MySql)) { $MySql = 'mysql' }
if ([string]::IsNullOrWhiteSpace($env:DB_HOST) -or [string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
  throw 'DB_HOST, DB_USERNAME and DB_PASSWORD must be supplied by managed environment variables.'
}
$database = if ($env:DB_DATABASE) { $env:DB_DATABASE } else { $env:DB_NAME }
if ([string]::IsNullOrWhiteSpace($database)) { throw 'DB_DATABASE or DB_NAME must be set.' }
$query = "SELECT COUNT(*) AS release_count FROM adm_app_release; SELECT COUNT(*) AS unexpected_schema_count FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('adm_client_release','adm_client_release_artifact','adm_client_release_policy','adm_client_release_audit_log','adm_client_release_operation','adm_app_release_retired_v1');"
$previousMySqlPassword = $env:MYSQL_PWD
try {
  $env:MYSQL_PWD = $env:DB_PASSWORD
  $result = $query | & $MySql --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --database=$database --batch --skip-column-names 2>&1
}
finally {
  if ($null -eq $previousMySqlPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
  else { $env:MYSQL_PWD = $previousMySqlPassword }
}
$outputDir = Join-Path (if ($env:IM_OUTPUT_ROOT) { $env:IM_OUTPUT_ROOT } else { '.outputs' }) 'logs'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$result | Out-File -FilePath (Join-Path $outputDir 'client-release-cutover-preflight.log') -Encoding utf8
if ($LASTEXITCODE -ne 0 -or $result.Count -ne 2 -or [int]$result[0] -ne 0 -or [int]$result[1] -ne 0) { throw 'Client release cutover preflight failed.' }
