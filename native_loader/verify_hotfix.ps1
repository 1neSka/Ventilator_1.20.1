param(
    [string] $BuildInfo = (Join-Path $PSScriptRoot "build\BUILD_INFO.txt")
)

$ErrorActionPreference = "Stop"
$failed = $false

function Write-Check {
    param(
        [string] $Status,
        [string] $Name,
        [string] $Detail = ""
    )

    $suffix = if ($Detail) { " - $Detail" } else { "" }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $suffix)
}

function Read-BuildInfo {
    param([string] $Path)

    $map = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $idx = $line.IndexOf("=")
        if ($idx -le 0) {
            continue
        }
        $key = $line.Substring(0, $idx)
        $value = $line.Substring($idx + 1)
        $map[$key] = $value
    }
    return $map
}

function Get-Sha256 {
    param([string] $Path)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hash = $sha.ComputeHash($stream)
        return -join ($hash | ForEach-Object { $_.ToString("X2") })
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

function Resolve-BuildPath {
    param(
        [string] $Path,
        [string] $BaseDirectory
    )

    if (-not $Path) {
        return $null
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $Path))
}

function Test-Hash {
    param(
        [string] $Name,
        [string] $Path,
        [string] $Expected,
        [switch] $Critical
    )

    if (-not $Path) {
        Write-Check ($(if ($Critical) { "FAIL" } else { "WARN" })) $Name "path missing in BUILD_INFO"
        if ($Critical) { $script:failed = $true }
        return
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Check ($(if ($Critical) { "FAIL" } else { "WARN" })) $Name ("file missing: {0}" -f [System.IO.Path]::GetFileName($Path))
        if ($Critical) { $script:failed = $true }
        return
    }

    $actual = Get-Sha256 -Path $Path
    $displayPath = [System.IO.Path]::GetFileName($Path)
    if ($actual -eq $Expected) {
        Write-Check "OK" $Name $displayPath
    } else {
        Write-Check ($(if ($Critical) { "FAIL" } else { "WARN" })) $Name ("hash mismatch: expected={0}, actual={1}, file={2}" -f $Expected, $actual, $displayPath)
        if ($Critical) { $script:failed = $true }
    }
}

Write-Host "Xenobyte Hotfix Verification"
Write-Host ("BUILD_INFO: {0}" -f [System.IO.Path]::GetFileName($BuildInfo))
Write-Host ""

if (-not (Test-Path -LiteralPath $BuildInfo)) {
    Write-Check "FAIL" "BUILD_INFO" "missing"
    exit 2
}

$info = Read-BuildInfo -Path $BuildInfo
$buildInfoDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($BuildInfo))
$dll = Resolve-BuildPath -Path $info["Dll"] -BaseDirectory $buildInfoDirectory
$dllSha = $info["DllSHA256"]
$jar = Resolve-BuildPath -Path $info["Jar"] -BaseDirectory $buildInfoDirectory
$jarSha = $info["JarSHA256"]

if ($info["Built"]) {
    Write-Check "INFO" "Built" $info["Built"]
}

Test-Hash -Name "Hotfix DLL" -Path $dll -Expected $dllSha -Critical

$adjacentJar = $null
if ($dll) {
    $adjacentJar = Join-Path (Split-Path -LiteralPath $dll) "xenobyte-modern-0.1.0.jar"
}

Test-Hash -Name "Adjacent runtime JAR" -Path $adjacentJar -Expected $jarSha -Critical
Test-Hash -Name "Manifest JAR" -Path $jar -Expected $jarSha

Write-Host ""
if ($failed) {
    Write-Host "Result: HOTFIX_VERIFY_FAILED"
    exit 1
}

Write-Host "Result: HOTFIX_VERIFY_OK"
