param(
    [ValidateSet('test', 'build', 'run')][string]$Task = 'run',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn.cmd',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw '请通过 -JavaHome 指定 JDK 21，例如 -JavaHome D:/jdk-21。'
}
$env:JAVA_HOME = $JavaHome
Push-Location $taskRoot
try {
    if (-not $SkipBuild -or $Task -ne 'run') {
        $taskGoal = if ($Task -eq 'test') { 'verify' } else { 'package' }
        $taskArguments = @('-B', '-ntp', "-Dmaven.repo.local=$taskRoot/.cache/maven", $taskGoal)
        & $Maven @taskArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码 $LASTEXITCODE" }
    }
    if ($Task -eq 'run') {
        # Windows 下运行独立副本，避免开发服务锁住 target 中的 JAR，影响再次构建。
        $taskRunDirectory = Join-Path $taskRoot '.cache/run'
        New-Item -ItemType Directory -Force -Path $taskRunDirectory | Out-Null
        $taskRunJar = Join-Path $taskRunDirectory 'fitsync-server.jar'
        Copy-Item -LiteralPath 'server/target/fitsync-server-0.1.0-SNAPSHOT.jar' -Destination $taskRunJar -Force
        & (Join-Path $JavaHome 'bin/java.exe') '-jar' $taskRunJar '--spring.profiles.active=local'
        if ($LASTEXITCODE -ne 0) { throw "服务退出，退出码 $LASTEXITCODE" }
    }
} finally { Pop-Location }
