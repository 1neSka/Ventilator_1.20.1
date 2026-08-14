@echo off
setlocal

set "ROOT=%~dp0"
set "JAR=%ROOT%build\libs\xenobyte-modern-0.1.0.jar"
set "NATIVE_BUILD=%ROOT%native_loader\build"
set "NATIVE_JAR=%NATIVE_BUILD%\xenobyte-modern-0.1.0.jar"
set "BUILD_INFO=%NATIVE_BUILD%\BUILD_INFO.txt"

if exist "%JAVA_HOME%\bin\java.exe" if exist "%JAVA_HOME%\include\jni.h" goto java_ready

set "JAVA_HOME="
for /f "delims=" %%I in ('where javac.exe 2^>nul') do if not defined JAVA_HOME call :java_home_from_exe "%%~fI"

if not exist "%JAVA_HOME%\bin\java.exe" goto java_missing
if not exist "%JAVA_HOME%\include\jni.h" goto java_missing

:java_ready
set "PATH=%JAVA_HOME%\bin;%PATH%"
goto build

:java_missing
echo A Java 17 or newer JDK was not found.
echo Set JAVA_HOME to a JDK installation or add javac.exe to PATH.
exit /b 1

:java_home_from_exe
set "JAVA_BIN=%~dp1"
for %%I in ("%JAVA_BIN%..") do set "JAVA_CANDIDATE=%%~fI"
if exist "%JAVA_CANDIDATE%\bin\java.exe" if exist "%JAVA_CANDIDATE%\include\jni.h" set "JAVA_HOME=%JAVA_CANDIDATE%"
exit /b 0

:build
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME does not point to a working Java installation.
    exit /b 1
)

cd /d "%ROOT%" || exit /b 1
call "%ROOT%gradlew.bat" build
if errorlevel 1 exit /b 1

call "%ROOT%native_loader\build_loader.bat"
if errorlevel 1 exit /b 1

if not exist "%NATIVE_BUILD%" mkdir "%NATIVE_BUILD%"
copy /Y "%JAR%" "%NATIVE_JAR%" >nul
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%native_loader\write_build_info.ps1" ^
    -Jar "%JAR%" ^
    -Dll "%NATIVE_BUILD%\xenobyte-modern-loader.x64.dll" ^
    -Out "%BUILD_INFO%"
if errorlevel 1 exit /b 1

echo.
echo Built Java jar:
echo   build\libs\xenobyte-modern-0.1.0.jar
echo.
echo Built native loader:
echo   native_loader\build\xenobyte-modern-loader.x64.dll
echo.
echo Copied jar next to DLL:
echo   native_loader\build\xenobyte-modern-0.1.0.jar
