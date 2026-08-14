@echo off
setlocal

set "ROOT=%~dp0"
set "REPORT=%ROOT%build\last_runtime_report.txt"
set "BUILD_INFO=%ROOT%build\BUILD_INFO.txt"
set "NATIVE_LOG=%TEMP%\xenobyte-modern-loader.log"
set "JAVA_LOG=%TEMP%\xenobyte-modern-java.log"

if not exist "%ROOT%build" mkdir "%ROOT%build"

(
    echo Xenobyte Modern Runtime Report
    echo Generated=%DATE% %TIME%
    echo.
    echo ==== BUILD_INFO ====
    if exist "%BUILD_INFO%" (
        type "%BUILD_INFO%"
    ) else (
        echo missing
    )
    echo.
    echo ==== Native loader log ====
    echo File=xenobyte-modern-loader.log
    if exist "%NATIVE_LOG%" (
        type "%NATIVE_LOG%"
    ) else (
        echo missing
    )
    echo.
    echo ==== Java bootstrap log ====
    echo File=xenobyte-modern-java.log
    if exist "%JAVA_LOG%" (
        type "%JAVA_LOG%"
    ) else (
        echo missing
    )
) > "%REPORT%"

echo Wrote "native_loader\build\last_runtime_report.txt"
