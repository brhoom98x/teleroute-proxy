# Builds the TeleRoute SOCKS5 proxy using the portable toolchain in <user profile>\dev-tools.
#
#   .\build.ps1            -> run the unit tests and build the fat jar
#   .\build.ps1 test       -> unit tests only
#   .\build.ps1 run        -> run locally against .\teleroute.conf
#   .\build.ps1 clean      -> clean

param([string]$Task = "fatJar")

$tools = Join-Path $env:USERPROFILE "dev-tools"
$env:JAVA_HOME        = "$tools\jdk17"
$env:GRADLE_USER_HOME = "$tools\gradle-home"

# Same reason as the Android project: C:\dev is Google Drive-synced and Drive intermittently locks
# files inside the build directory mid-task.
$outDir = Join-Path $tools "build\TeleRouteServer"

switch ($Task) {
    "test"  { $gradleTask = @("test") }
    "clean" { $gradleTask = @("clean") }
    "run"   { $gradleTask = @("run", "--args=teleroute.conf") }
    default { $gradleTask = @("test", "fatJar") }
}

Push-Location $PSScriptRoot
try {
    & "$tools\gradle\bin\gradle.bat" @gradleTask "-PbuildOutDir=$outDir" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

    $jar = Get-ChildItem (Join-Path $outDir "libs\*-all.jar") -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jar) {
        $size = [math]::Round($jar.Length / 1MB, 2)
        Write-Host ""
        Write-Host "JAR: $($jar.FullName) ($size MB)"
    }
} finally {
    Pop-Location
}
