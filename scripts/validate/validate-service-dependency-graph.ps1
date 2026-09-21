[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$violations = [System.Collections.Generic.List[string]]::new()

Get-ChildItem -Path (Join-Path $root 'sdk'), (Join-Path $root 'gateways'), (Join-Path $root 'im-services'), (Join-Path $root 'platform-services'), (Join-Path $root 'common-services'), (Join-Path $root 'group-services') -Recurse -Filter pom.xml | ForEach-Object {
  [xml]$pom = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
  $manager = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
  $manager.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
  foreach ($dependency in $pom.SelectNodes('/m:project/m:dependencies/m:dependency', $manager)) {
    $groupId = $dependency.SelectSingleNode('m:groupId', $manager).InnerText
    $artifactId = $dependency.SelectSingleNode('m:artifactId', $manager).InnerText
    if ($groupId -eq 'com.gvchat' -and $artifactId -like '*-service') {
      $violations.Add("Cross-service implementation dependency is forbidden: $($_.FullName) -> $artifactId")
    }
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Service dependency graph validation passed.'
