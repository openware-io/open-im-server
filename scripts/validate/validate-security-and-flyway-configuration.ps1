[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$services = @(
  @{ Name = 'user'; Path = 'im-services\user\im-user-service'; History = 'user_schema_history' },
  @{ Name = 'message'; Path = 'im-services\message\im-message-service'; History = 'msg_schema_history' },
  @{ Name = 'conversation'; Path = 'im-services\conversation\im-conversation-service'; History = 'conversation_schema_history' },
  @{ Name = 'admin'; Path = 'im-services\admin\im-admin-service'; History = 'admin_schema_history' },
  @{ Name = 'support'; Path = 'common-services\media\common-media-service'; History = 'support_schema_history' }
)
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($service in $services) {
  $yamlPath = Join-Path $root ($service.Path + '\src\main\resources\application.yml')
  $pomPath = Join-Path $root ($service.Path + '\pom.xml')
  $yaml = [System.IO.File]::ReadAllText($yamlPath, [System.Text.UTF8Encoding]::new($false, $true))
  $pom = [System.IO.File]::ReadAllText($pomPath, [System.Text.UTF8Encoding]::new($false, $true))
  if ($yaml -notmatch ('table:\s*' + [regex]::Escape($service.History))) {
    $violations.Add("$($service.Name) Flyway history table is missing or incorrect")
  }
  if ($yaml -notmatch 'enabled:\s*true') {
    $violations.Add("$($service.Name) must enable Flyway")
  }
  if ($pom -notmatch '<artifactId>spring-boot-starter-flyway</artifactId>' -or $pom -notmatch '<artifactId>flyway-mysql</artifactId>') {
    $violations.Add("$($service.Name) must declare Flyway starter and flyway-mysql")
  }
  if ($yaml -notmatch 'secret:\s*\$\{JWT_SECRET:\}') {
    $violations.Add("$($service.Name) JWT secret must not have a default")
  }
  if ($yaml -notmatch 'secret:\s*\$\{INTERNAL_SERVICE_AUTH_SECRET\}') {
    $violations.Add("$($service.Name) internal HMAC secret must not have a default")
  }
}

Get-ChildItem -Path (Join-Path $root 'services') -Recurse -Filter '*.sql' | Where-Object {
  $_.FullName -match '[\\/]src[\\/]main[\\/]resources[\\/]db[\\/]migration[\\/]'
} | ForEach-Object {
  $relative = $_.FullName.Substring($root.Length + 1).Replace('/', '\')
  $sql = [System.IO.File]::ReadAllText($_.FullName, [System.Text.UTF8Encoding]::new($false, $true))
  if ($sql -match '(?i)CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS') {
    $violations.Add("Forward Flyway migration must not use CREATE TABLE IF NOT EXISTS: $relative")
  }
  if ($sql -match '(?i)\bFOREIGN\s+KEY\b|\bREFERENCES\s+`') {
    $violations.Add("Physical foreign keys are forbidden; enforce associations in Java: $relative")
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Security and Flyway configuration validation passed.'
