param(
    [int] $Number = 0,
    [switch] $SkipBuild,
    [switch] $DryRun
)

$ErrorActionPreference = "Stop"

$nativeRoot = $PSScriptRoot
$root = Resolve-Path (Join-Path $nativeRoot "..")
$buildDir = Join-Path $nativeRoot "build"
$jar = Join-Path $root "build\libs\xenobyte-modern-0.1.0.jar"

function Get-NextHotfixNumber {
    $max = 0
    Get-ChildItem -LiteralPath $nativeRoot -Directory -Filter "build_hotfix*" | ForEach-Object {
        if ($_.Name -match "^build_hotfix(\d+)$") {
            $value = [int]$Matches[1]
            if ($value -gt $max) {
                $max = $value
            }
        }
    }
    return $max + 1
}

if ($Number -le 0) {
    $Number = Get-NextHotfixNumber
}

$dllName = "xenobyte-modern-loader-hotfix$Number.x64.dll"
$hotfixDir = Join-Path $nativeRoot "build_hotfix$Number"
$builtDll = Join-Path $buildDir $dllName
$hotfixDll = Join-Path $hotfixDir $dllName
$hotfixJar = Join-Path $hotfixDir "xenobyte-modern-0.1.0.jar"
$hotfixInfo = Join-Path $hotfixDir "BUILD_INFO.txt"
$activeInfo = Join-Path $buildDir "BUILD_INFO.txt"

Write-Host "Hotfix package target:"
Write-Host "  Number: $Number"
Write-Host "  DLL:    native_loader\build_hotfix$Number\$dllName"
Write-Host "  JAR:    native_loader\build_hotfix$Number\xenobyte-modern-0.1.0.jar"
Write-Host "  Info:   native_loader\build_hotfix$Number\BUILD_INFO.txt"

if ($DryRun) {
    Write-Host ""
    Write-Host "Dry run only; no files changed."
    exit 0
}

Set-Location -LiteralPath $root

if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "Running full Java/native build..."
    & (Join-Path $root "build_all.bat")
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host ""
Write-Host "Building named native DLL..."
$env:DLL_BASENAME = $dllName
& (Join-Path $nativeRoot "build_loader.bat")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath $builtDll)) {
    throw "Expected named DLL was not created: native_loader\build\$dllName"
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw "Expected Java jar is missing: build\libs\xenobyte-modern-0.1.0.jar"
}

New-Item -ItemType Directory -Force -Path $hotfixDir | Out-Null
Copy-Item -LiteralPath $builtDll -Destination $hotfixDll -Force
Copy-Item -LiteralPath $jar -Destination $hotfixJar -Force

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $nativeRoot "write_build_info.ps1") `
    -Jar $hotfixJar `
    -Dll $hotfixDll `
    -Out $hotfixInfo
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $nativeRoot "write_build_info.ps1") `
    -Jar $hotfixJar `
    -Dll $hotfixDll `
    -Out $activeInfo
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Packaged hotfix${Number}:"
Write-Host "  native_loader\build_hotfix$Number\$dllName"
Write-Host "  native_loader\build_hotfix$Number\xenobyte-modern-0.1.0.jar"
Write-Host "  native_loader\build_hotfix$Number\BUILD_INFO.txt"
