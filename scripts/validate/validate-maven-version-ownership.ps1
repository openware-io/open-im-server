param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

function Get-PomValue {
    param([string]$PomPath, [string]$XPath)
    if (-not (Test-Path -LiteralPath $PomPath)) { throw "POM not found: $PomPath" }
    [xml]$pom = Get-Content -LiteralPath $PomPath -Raw -Encoding UTF8
    $mgr = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $mgr.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $node = $pom.SelectSingleNode($XPath, $mgr)
    if ($null -eq $node) { return $null }
    return $node.InnerText.Trim()
}

function Get-PomModules {
    param([string]$PomPath)
    [xml]$pom = Get-Content -LiteralPath $PomPath -Raw -Encoding UTF8
    $mgr = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $mgr.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $mods = @()
    foreach ($n in $pom.SelectNodes('/m:project/m:modules/m:module', $mgr)) { $mods += $n.InnerText.Trim() }
    return $mods
}

function Assert-PomValue {
    param([string]$PomPath, [string]$XPath, [string]$Expected, [string]$Desc)
    $actual = Get-PomValue -PomPath $PomPath -XPath $XPath
    if ($actual -ne $Expected) {
        throw "$Desc violates Maven version ownership: $PomPath; expected '$Expected', actual '$($actual)'."
    }
}

# 1) Root POM must have an explicit version (build baseline)
$rootPomPath = Join-Path $Root 'pom.xml'
$rootVersion = Get-PomValue -PomPath $rootPomPath -XPath '/m:project/m:version'
if ([string]::IsNullOrWhiteSpace($rootVersion)) { throw "Root POM is missing an explicit version (build baseline): $rootPomPath." }

# 2) Group aggregators (level 2) discovered from root <modules>
foreach ($groupDir in (Get-PomModules -PomPath $rootPomPath)) {
    $groupPomPath = Join-Path $Root "$groupDir\pom.xml"
    Assert-PomValue -PomPath $groupPomPath -XPath '/m:project/m:parent/m:artifactId' -Expected 'im-server' -Desc 'Group parent artifact'
    Assert-PomValue -PomPath $groupPomPath -XPath '/m:project/m:parent/m:version' -Expected $rootVersion -Desc 'Group parent version'

    $groupArtifact = Get-PomValue -PomPath $groupPomPath -XPath '/m:project/m:artifactId'
    $children = Get-PomModules -PomPath $groupPomPath

    if ($groupArtifact -eq 'sdk') {
        # Platform: sdk is the single version source, its 4 leaves inherit
        $sdkVersion = Get-PomValue -PomPath $groupPomPath -XPath '/m:project/m:version'
        if ([string]::IsNullOrWhiteSpace($sdkVersion)) { throw "sdk (platform BOM) is missing an explicit version: $groupPomPath." }
        foreach ($leaf in $children) {
            $leafPomPath = Join-Path $Root "$groupDir\$leaf\pom.xml"
            Assert-PomValue -PomPath $leafPomPath -XPath '/m:project/m:parent/m:artifactId' -Expected 'sdk' -Desc 'SDK leaf parent artifact'
            Assert-PomValue -PomPath $leafPomPath -XPath '/m:project/m:parent/m:version' -Expected $sdkVersion -Desc 'SDK leaf parent version'
            if ($null -ne (Get-PomValue -PomPath $leafPomPath -XPath '/m:project/m:version')) { throw "Leaf module must not declare its own version: $leafPomPath." }
        }
    } elseif ($groupArtifact -eq 'im-gateways') {
        # Gateways: independent deployables must declare their own version
        foreach ($leaf in $children) {
            $leafPomPath = Join-Path $Root "$groupDir\$leaf\pom.xml"
            Assert-PomValue -PomPath $leafPomPath -XPath '/m:project/m:parent/m:artifactId' -Expected 'im-gateways' -Desc 'Gateway leaf parent artifact'
            $leafVersion = Get-PomValue -PomPath $leafPomPath -XPath '/m:project/m:version'
            if ([string]::IsNullOrWhiteSpace($leafVersion)) { throw "Gateway deployable module must declare an explicit version: $leafPomPath." }
            if ($leafVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$') { throw "Gateway deployable module version must be SemVer or SemVer-SNAPSHOT: $leafPomPath; actual '$leafVersion'." }
            if (-not $leafVersion.EndsWith('-SNAPSHOT')) { throw "Gateway deployable module version must end with -SNAPSHOT on a development branch: $leafPomPath; actual '$leafVersion'." }
        }
    } else {
        # Service groups: children are domains (level 3), each with api/service leaves (level 4)
        foreach ($domainDir in $children) {
            $domainPomPath = Join-Path $Root "$groupDir\$domainDir\pom.xml"
            $domainArtifact = Get-PomValue -PomPath $domainPomPath -XPath '/m:project/m:artifactId'
            $domainVersion = Get-PomValue -PomPath $domainPomPath -XPath '/m:project/m:version'
            if ([string]::IsNullOrWhiteSpace($domainVersion)) { throw "Domain parent POM is missing an explicit version: $domainPomPath." }
            foreach ($leafDir in (Get-PomModules -PomPath $domainPomPath)) {
                $leafPomPath = Join-Path $Root "$groupDir\$domainDir\$leafDir\pom.xml"
                Assert-PomValue -PomPath $leafPomPath -XPath '/m:project/m:parent/m:artifactId' -Expected $domainArtifact -Desc 'Leaf parent artifact'
                Assert-PomValue -PomPath $leafPomPath -XPath '/m:project/m:parent/m:version' -Expected $domainVersion -Desc 'Leaf parent version'
                $leafVersion = Get-PomValue -PomPath $leafPomPath -XPath '/m:project/m:version'
                if ($null -ne $leafVersion) {
                    if ($leafVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$') {
                        throw "Leaf module version must be SemVer or SemVer-SNAPSHOT: $leafPomPath; actual '$leafVersion'."
                    }
                    if (-not $leafVersion.EndsWith('-SNAPSHOT')) { throw "Deployable leaf module version must end with -SNAPSHOT on a development branch: $leafPomPath; actual '$leafVersion'." }
                    $leafBaseVersion = $leafVersion -replace '-SNAPSHOT$', ''
                    if ($leafBaseVersion -ne $domainVersion) {
                        throw "Leaf module base version must match its domain version: $leafPomPath; expected '$domainVersion' or '$domainVersion-SNAPSHOT', actual '$leafVersion'."
                    }
                }
            }
        }
    }
}

Write-Host 'Maven version ownership validation passed.'
