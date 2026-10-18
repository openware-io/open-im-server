[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$violations = [System.Collections.Generic.List[string]]::new()

$jsqlparserArtifact = 'mybatis-plus-jsqlparser-4.9'

function Get-ServiceModulePom([string]$relativePath) {
  $parts = $relativePath -split '/'
  if ($parts.Length -ge 3) { return ($parts[0..2] -join '/') + '/pom.xml' }
  return $null
}

function Get-DependencyArtifact([string]$block) {
  $match = [regex]::Match($block, '<artifactId>\s*([^<]+?)\s*</artifactId>')
  if ($match.Success) { return $match.Groups[1].Value.Trim() }
  return ''
}

function Get-DependencyGroup([string]$block) {
  $match = [regex]::Match($block, '<groupId>\s*([^<]+?)\s*</groupId>')
  if ($match.Success) { return $match.Groups[1].Value.Trim() }
  return ''
}

function Remove-JavaComments([string]$text) {
  $withoutBlock = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
  return [regex]::Replace($withoutBlock, '(?m)//.*$', '')
}

# 1) POM level: only the managed jsqlparser coordinate is allowed, without version, compile scope.
$servicePoms = git -C $root ls-files 'im-services/*/*-service/pom.xml' 'platform-services/*/*-service/pom.xml' 'common-services/*/*-service/pom.xml' 'group-services/*/*-service/pom.xml'
$gatewayPoms = git -C $root ls-files 'gateways/*/pom.xml'
$forbiddenBottom = @('mybatis-plus-core', 'mybatis-plus-extension', 'mybatis-plus-annotation', 'mybatis-spring', 'flyway-core')
# mybatis-plus-jsqlparser-4.9 is deliberately NOT a bottom component: the mybatis-plus aggregate marks it
# optional, so it does not arrive transitively, while PaginationInnerInterceptor and TenantLineHandler need it.
# It is allowed only as the single managed coordinate below. See standards/21 "jsqlparser usage boundary".

$modulePoms = @{}
$jsqlparserDeclared = @{}
foreach ($relativePath in $servicePoms) {
  $absolutePath = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
  $text = [System.IO.File]::ReadAllText($absolutePath, [System.Text.UTF8Encoding]::new($false, $true))
  $modulePoms[$relativePath] = $text

  foreach ($artifact in $forbiddenBottom) {
    if ($text -match ('<artifactId>' + [regex]::Escape($artifact) + '</artifactId>')) {
      $violations.Add("Module must not directly declare bottom persistence component '$artifact': $relativePath")
    }
  }

  foreach ($dependencyMatch in [regex]::Matches($text, '(?s)<dependency>(.*?)</dependency>')) {
    $block = $dependencyMatch.Groups[1].Value
    $artifactId = Get-DependencyArtifact $block
    if (-not $artifactId -or $artifactId -notmatch 'jsqlparser') { continue }
    $groupId = Get-DependencyGroup $block
    if ($groupId -ne 'com.baomidou' -or $artifactId -ne $jsqlparserArtifact) {
      $violations.Add("Only '$jsqlparserArtifact' may be declared; found '${groupId}:${artifactId}': $relativePath")
    }
    else {
      $jsqlparserDeclared[$relativePath] = $true
    }
    if ($block -match '<version>') {
      $violations.Add("jsqlparser version must be managed by the root POM, not written in the module: $relativePath")
    }
    if ($block -match '<scope>\s*runtime\s*</scope>') {
      $violations.Add("jsqlparser must stay compile-scoped (TenantLineHandler returns a JSqlParser Expression): $relativePath")
    }
  }

  if ($text -match '(?s)<exclusions>.*jsqlparser.*</exclusions>') {
    $violations.Add("Pagination/TenantLine modules must not exclude jsqlparser: $relativePath")
  }
}

foreach ($relativePath in $gatewayPoms) {
  $absolutePath = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
  $text = [System.IO.File]::ReadAllText($absolutePath, [System.Text.UTF8Encoding]::new($false, $true))
  if ($text -match 'mybatis-plus|mysql-connector-j|spring-boot-starter-flyway|flyway') {
    $violations.Add("Access layer must not declare persistence dependency: $relativePath")
  }
}

# 2) Source level: no legacy JPA; JSqlParser imports only inside MyBatis-Plus config extension points;
#    pagination/tenant-line usage requires the module to declare the parser coordinate.
$javaFiles = git -C $root ls-files '*src/main/java/*.java'
$usedModules = @{}
foreach ($relativePath in $javaFiles) {
  if ($relativePath -notmatch '/src/main/java/') { continue }
  $absolutePath = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
  $text = [System.IO.File]::ReadAllText($absolutePath, [System.Text.UTF8Encoding]::new($false, $true))
  $code = Remove-JavaComments $text

  if ($code -match 'import\s+javax\.persistence\.') {
    $violations.Add("Legacy JPA persistence dependency: $relativePath")
  }

  if ($code -match 'import\s+net\.sf\.jsqlparser\.') {
    if ($relativePath -notmatch '/config/' -and $relativePath -notmatch '/infra/.*/config/') {
      $violations.Add("JSqlParser may only be imported by MyBatis-Plus config extension points: $relativePath")
    }
  }

  if ($code -match 'PaginationInnerInterceptor|selectPage\s*\(|TenantLineHandler') {
    $modulePom = Get-ServiceModulePom $relativePath
    if (-not $modulePom -or -not $modulePoms.ContainsKey($modulePom)) { continue }
    $usedModules[$modulePom] = $true
    if ($modulePoms[$modulePom] -notmatch [regex]::Escape("<artifactId>$jsqlparserArtifact</artifactId>")) {
      $violations.Add("Module uses pagination/TenantLineHandler but does not declare ${jsqlparserArtifact}: $modulePom")
    }
  }
}

foreach ($modulePom in $jsqlparserDeclared.Keys) {
  if (-not $usedModules.ContainsKey($modulePom)) {
    $violations.Add("Module declares ${jsqlparserArtifact} but has no pagination/TenantLineHandler usage: $modulePom")
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Persistence dependency boundary validation passed.'
