@echo off
setlocal

set "NATIVE_LOG=%TEMP%\xenobyte-modern-loader.log"
set "JAVA_LOG=%TEMP%\xenobyte-modern-java.log"

echo Native loader log:
echo   "%%TEMP%%\xenobyte-modern-loader.log"
echo.
if exist "%NATIVE_LOG%" (
    type "%NATIVE_LOG%"
) else (
    echo   not found
)

echo.
echo Java bootstrap log:
echo   "%%TEMP%%\xenobyte-modern-java.log"
echo.
if exist "%JAVA_LOG%" (
    type "%JAVA_LOG%"
) else (
    echo   not found
)
