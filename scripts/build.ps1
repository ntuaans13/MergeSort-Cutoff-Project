# Build + correctness gate.
#   ./scripts/build.ps1                          scaffold mode (OK while MergeSort is TODO)
#   ./scripts/build.ps1 -RequireImplementation   required before any measurement
#   ./scripts/build.ps1 -NoMaven                 no Maven/JMH: pure-JDK jar, fork backend only
param(
    [switch]$RequireImplementation,
    [switch]$NoMaven
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Invoke-Native([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe exited with code $LASTEXITCODE" }
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $NoMaven -and $mvn) {
    Write-Host "== Maven build (dependencies cached in .m2/ inside the project)"
    Invoke-Native "mvn" @("-q", "-B", "-Dmaven.repo.local=.m2/repository", "clean", "package")
    # Do not redirect stderr here: Windows PowerShell 5 turns native stderr lines into errors.
    $list = (& java -jar target/benchmarks.jar -l) | Out-String
    if ($list -notmatch "study\.jmh\.SortBenchmark\.sort") {
        throw "JMH did not register study.jmh.SortBenchmark.sort (missing /META-INF/BenchmarkList?). Re-run this script."
    }
    Write-Host "Built target/benchmarks.jar (JMH backend available)"
} else {
    if (-not $NoMaven) { Write-Host "Maven not found on PATH." }
    Write-Host "== Pure-JDK build (fork backend only)"
    if (Test-Path target) { Remove-Item -Recurse -Force target }
    New-Item -ItemType Directory -Force target/classes | Out-Null
    $src = Get-ChildItem -Recurse src/main/java -Filter *.java |
        Where-Object { $_.FullName -notmatch '[\\/]jmh[\\/]' } | ForEach-Object { $_.FullName }
    Invoke-Native "javac" (@("-encoding", "UTF-8", "--release", "17", "-d", "target/classes") + $src)
    Invoke-Native "jar" @("--create", "--file", "target/benchmarks.jar", "-C", "target/classes", ".")
    Write-Host "Built target/benchmarks.jar (no JMH)"
}

Write-Host "== Correctness check"
$checkArgs = @("-cp", "target/benchmarks.jar", "study.check.CheckSort")
if ($RequireImplementation) { $checkArgs += "--require" }
Invoke-Native "java" $checkArgs
