@echo off
setlocal

set "ROOT=%~dp0"
set "NO_PAUSE="
if /I "%~1"=="/nopause" set "NO_PAUSE=1"

echo Verifying active hotfix package...
call "%ROOT%verify_hotfix.bat" /nopause
if errorlevel 1 goto done

echo.
echo Current runtime config:
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%runtime_config.ps1" -Action show

echo.
echo Clearing Xenobyte runtime logs...
call "%ROOT%clear_logs.bat"

:done
echo.
if not defined NO_PAUSE pause
