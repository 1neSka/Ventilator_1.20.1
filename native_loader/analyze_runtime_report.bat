@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0analyze_runtime_report.ps1" %*
echo.
pause
