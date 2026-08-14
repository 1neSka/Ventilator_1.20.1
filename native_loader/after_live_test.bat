@echo off
setlocal

set "ROOT=%~dp0"
set "NO_PAUSE="
if /I "%~1"=="/nopause" set "NO_PAUSE=1"

echo Collecting Xenobyte runtime report...
call "%ROOT%collect_runtime_report.bat"
if errorlevel 1 goto done

echo.
echo Analyzing runtime report...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%analyze_runtime_report.ps1"

:done
echo.
if not defined NO_PAUSE pause
