[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$violations = [System.Collections.Generic.List[string]]::new()

# 1) 源码级：遗留 JPA 依赖（业务/接入模块源码不得使用 javax.persistence）
$javaFiles = git -C $root ls-files '*src/main/java/*.java'
foreach ($relativePath in $javaFiles) {
  if ($relativePath -notmatch '/src/main/java/') { continue }
  $absolutePath = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
  $text = [System.IO.File]::ReadAllText($absolutePath, [System.Text.UTF8Encoding]::new($false, $true))
  if ($text -match 'import\s+javax\.persistence\.') {
    $violations.Add("Legacy JPA persistence dependency: $relativePath")
  }
}

# 2) POM 级：业务/接入模块禁止直接声明 MyBatis-Plus 底层组件与 flyway-core
$servicePoms = git -C $root ls-files 'im-services/*/*-service/pom.xml' 'platform-services/*/*-service/pom.xml' 'common-services/*/*-service/pom.xml' 'group-services/*/*-service/pom.xml'
$gatewayPoms = git -C $root ls-files 'gateways/*/pom.xml'
$forbiddenBottom = @('mybatis-plus-core', 'mybatis-plus-extension', 'mybatis-plus-annotation', 'mybatis-spring', 'flyway-core')

foreach ($relativePath in ($servicePoms + $gatewayPoms)) {
  $absolutePath = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
  $text = [System.IO.File]::ReadAllText($absolutePath, [System.Text.UTF8Encoding]::new($false, $true))
  foreach ($artifact in $forbiddenBottom) {
    if ($text -match ('<artifactId>' + [regex]::Escape($artifact) + '</artifactId>')) {
      $violations.Add("Module must not directly declare bottom persistence component '$artifact': $relativePath")
    }
  }
  if ($relativePath -match 'gateways/') {
    if ($text -match 'mybatis-plus|mysql-connector-j|spring-boot-starter-flyway|flyway') {
      $violations.Add("Access layer must not declare persistence dependency: $relativePath")
    }
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Persistence dependency boundary validation passed.'
