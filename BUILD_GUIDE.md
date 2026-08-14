# Build guide

## Requirements

- Windows x64;
- a Java 17 or newer JDK (not only a JRE);
- Visual Studio 2019 or newer Build Tools with the Desktop development with C++ workload;
- PowerShell 5.1 or newer.

Set `JAVA_HOME` to the JDK when `javac.exe` is not discoverable through `PATH`.

## Full build

From the repository root:

```bat
build_all.bat
```

This command:

- builds the Forge mod jar;
- builds the native x64 loader DLL;
- copies `xenobyte-modern-0.1.0.jar` next to the DLL;
- writes portable relative paths and SHA-256 hashes to `native_loader\build\BUILD_INFO.txt`.

Outputs:

```text
build\libs\xenobyte-modern-0.1.0.jar
native_loader\build\xenobyte-modern-loader.x64.dll
native_loader\build\xenobyte-modern-0.1.0.jar
native_loader\build\BUILD_INFO.txt
```

## Numbered test package

To create an isolated numbered package:

```bat
native_loader\package_hotfix.bat
```

The script selects the next available number and creates:

```text
native_loader\build_hotfixN\xenobyte-modern-loader-hotfixN.x64.dll
native_loader\build_hotfixN\xenobyte-modern-0.1.0.jar
native_loader\build_hotfixN\BUILD_INFO.txt
```

Preview the target without building or writing files:

```bat
native_loader\package_hotfix.bat -DryRun
```

Verify the active package:

```bat
native_loader\verify_hotfix.bat /nopause
```

All build, hotfix, report, log, and local configuration outputs are ignored by Git.
