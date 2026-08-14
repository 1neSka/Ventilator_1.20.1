param(
    [ValidateSet("show", "backup", "reset", "restore-latest")]
    [string] $Action = "show"
)

$ErrorActionPreference = "Stop"

$config = Join-Path $env:TEMP "xenobyte-modern-forge.properties"
$backupDir = Join-Path $PSScriptRoot "build\config_backups"

function Timestamp {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Ensure-BackupDir {
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
}

function Backup-Config {
    if (-not (Test-Path -LiteralPath $config)) {
        Write-Host "Runtime config is missing; nothing to backup."
        return $null
    }

    Ensure-BackupDir
    $backup = Join-Path $backupDir ("xenobyte-modern-forge-{0}.properties" -f (Timestamp))
    Copy-Item -LiteralPath $config -Destination $backup -Force
    Write-Host "Backed up runtime config:"
    Write-Host ("  native_loader\build\config_backups\{0}" -f (Split-Path -Leaf $backup))
    return $backup
}

function Show-Config {
    Write-Host "Runtime config:"
    Write-Host "  %TEMP%\xenobyte-modern-forge.properties"
    if (-not (Test-Path -LiteralPath $config)) {
        Write-Host "  missing"
        return
    }
    Write-Host ""
    Get-Content -LiteralPath $config
}

function Reset-Config {
    $backup = Backup-Config
    if (Test-Path -LiteralPath $config) {
        Remove-Item -LiteralPath $config -Force
        Write-Host "Deleted runtime config. Next injection will use module defaults."
    }
    if ($backup) {
        Write-Host "Restore with:"
        Write-Host "  native_loader\runtime_config.bat restore-latest"
    }
}

function Restore-Latest {
    if (-not (Test-Path -LiteralPath $backupDir)) {
        Write-Host "No backup directory found: native_loader\build\config_backups"
        exit 1
    }

    $latest = Get-ChildItem -LiteralPath $backupDir -Filter "xenobyte-modern-forge-*.properties" |
        Sort-Object LastWriteTime |
        Select-Object -Last 1

    if (-not $latest) {
        Write-Host "No config backups found in: native_loader\build\config_backups"
        exit 1
    }

    Copy-Item -LiteralPath $latest.FullName -Destination $config -Force
    Write-Host "Restored latest runtime config backup:"
    Write-Host ("  native_loader\build\config_backups\{0}" -f $latest.Name)
    Write-Host "To:"
    Write-Host "  %TEMP%\xenobyte-modern-forge.properties"
}

switch ($Action) {
    "show" { Show-Config }
    "backup" { Backup-Config | Out-Null }
    "reset" { Reset-Config }
    "restore-latest" { Restore-Latest }
}
