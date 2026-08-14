@echo off
setlocal

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=show"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0runtime_config.ps1" -Action "%ACTION%"
echo.
pause
