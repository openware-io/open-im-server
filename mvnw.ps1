param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments)

$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$mutex = [System.Threading.Mutex]::new($false, "Global\open-im-server-maven-build")
$lockAcquired = $false

$wrapperJar = Join-Path $PSScriptRoot ".mvn\wrapper\maven-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    Invoke-WebRequest -UseBasicParsing "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -OutFile $wrapperJar
}
if (-not (Test-Path $wrapperJar)) {
    throw "Maven Wrapper download failed: $wrapperJar"
}
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
$jvmConfigPath = Join-Path $PSScriptRoot '.mvn\jvm.config'
$jvmConfigArguments = if (Test-Path $jvmConfigPath) {
    Get-Content -LiteralPath $jvmConfigPath -Encoding utf8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
} else {
    @()
}
$javaArguments = @(
    "-Dmaven.multiModuleProjectDirectory=$PSScriptRoot"
    '-Dfile.encoding=UTF-8'
    '-Dsun.stdout.encoding=UTF-8'
    '-Dsun.stderr.encoding=UTF-8'
) + $jvmConfigArguments + @(
    '-classpath'
    $wrapperJar
    'org.apache.maven.wrapper.MavenWrapperMain'
) + $MavenArguments
$exitCode = 1
try {
    $lockAcquired = $mutex.WaitOne()
    if (-not $lockAcquired) {
        throw "Unable to acquire Maven build lock."
    }
    & $javaExecutable @javaArguments
    $exitCode = $LASTEXITCODE
} finally {
    if ($lockAcquired) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}
exit $exitCode
