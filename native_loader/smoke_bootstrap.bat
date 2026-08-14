@echo off
setlocal

set "ROOT=%~dp0.."
set "JAR=%ROOT%\native_loader\build\xenobyte-modern-0.1.0.jar"

if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~fI"

if not exist "%JAVA_EXE%" (
    echo Java was not found. Set JAVA_HOME to Java 17 or newer, or add java.exe to PATH.
    exit /b 1
)

if not exist "%JAR%" (
    echo Jar not found: native_loader\build\xenobyte-modern-0.1.0.jar
    echo Run build_all.bat first.
    exit /b 1
)

"%JAVA_EXE%" -cp "%JAR%" team.xenobyte.modern.bootstrap.BootstrapSmokeMain "%JAR%"
exit /b %ERRORLEVEL%
