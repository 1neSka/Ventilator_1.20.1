@echo off
setlocal

set "NO_PAUSE="
if /I "%~1"=="/nopause" (
    set "NO_PAUSE=1"
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify_hotfix.ps1" %2 %3 %4 %5 %6 %7 %8 %9
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify_hotfix.ps1" %*
)
set "RESULT=%ERRORLEVEL%"

echo.
if not defined NO_PAUSE pause
exit /b %RESULT%
