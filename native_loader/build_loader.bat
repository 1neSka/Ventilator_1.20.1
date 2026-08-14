@echo off
setlocal

set "ROOT=%~dp0"
set "OUT=%ROOT%build"
set "SRC=%ROOT%xenobyte_loader.cpp"
if not defined DLL_BASENAME set "DLL_BASENAME=xenobyte-modern-loader.x64.dll"
set "DLL=%OUT%\%DLL_BASENAME%"

if not exist "%OUT%" mkdir "%OUT%"

if defined JAVA_HOME if exist "%JAVA_HOME%\include\jni.h" set "JNI_INCLUDE=%JAVA_HOME%\include"

if not exist "%JNI_INCLUDE%\jni.h" (
    echo jni.h was not found. Set JAVA_HOME to a Java 17 or newer JDK.
    exit /b 1
)

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VCVARS="
set "VCVARS2022=%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "VCVARS2019=%ProgramFiles(x86)%\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if exist "%VCVARS2022%" set "VCVARS=%VCVARS2022%"
if not defined VCVARS if exist "%VCVARS2019%" set "VCVARS=%VCVARS2019%"

if defined VCVARS goto have_vcvars
if not exist "%VSWHERE%" goto have_vcvars

for /f "delims=" %%I in ('^"%VSWHERE%^" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find "VC\Auxiliary\Build\vcvars64.bat"') do set "VCVARS=%%I"

:have_vcvars

if not defined VCVARS (
    echo vcvars64.bat was not found. Install Visual Studio Build Tools with C++ workload.
    exit /b 1
)

call "%VCVARS%" >nul

cl /nologo /std:c++17 /EHsc /LD "%SRC%" ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE%\win32" ^
    /Fe"%DLL%" /Fo"%OUT%\\" ^
    user32.lib

if errorlevel 1 exit /b 1

echo Built "native_loader\build\%DLL_BASENAME%"
