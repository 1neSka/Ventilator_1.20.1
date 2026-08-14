# Native JNI loader

This directory contains a minimal Windows x64 JNI bootstrap for the Forge 1.20.1 client.

The loader does not implement stealth, anti-detection, process hiding, or bytecode patching. It only:

1. runs a worker thread from `DllMain`;
2. locates the JVM in the current process through `JNI_GetCreatedJavaVMs`;
3. attaches the worker thread with JNI;
4. loads `xenobyte-modern-0.1.0.jar` through a child `URLClassLoader`;
5. calls `team.xenobyte.modern.bootstrap.NativeBootstrap.initFromNative(...)`;
6. detaches and unloads the native DLL after bootstrap.

## Build

For a complete Java and native build, run this from the repository root:

```bat
build_all.bat
```

For a native-only rebuild, ensure `JAVA_HOME` points to a Java 17 or newer JDK, then run:

```bat
native_loader\build_loader.bat
```

See [`BUILD_GUIDE.md`](../BUILD_GUIDE.md) for prerequisites and output paths.

## Jar resolution

The loader resolves the Java jar in this order:

1. the `XENOBYTE_MODERN_JAR` environment variable;
2. `xenobyte-modern-0.1.0.jar` next to the DLL.

Recommended layout:

```text
native_loader\build\xenobyte-modern-loader.x64.dll
native_loader\build\xenobyte-modern-0.1.0.jar
```

## Diagnostics

Runtime logs are written to the current user's temporary directory:

```text
%TEMP%\xenobyte-modern-loader.log
%TEMP%\xenobyte-modern-java.log
```

Helpers:

```bat
native_loader\clear_logs.bat
native_loader\show_logs.bat
native_loader\collect_runtime_report.bat
native_loader\analyze_runtime_report.bat
```

`collect_runtime_report.bat` writes `native_loader\build\last_runtime_report.txt`.

Runtime reports and packet logs are ignored by Git. Treat them as potentially sensitive: a live game session can add player names, chat text, server data, filesystem paths, or packet payloads. Review and redact a report before sharing it.

## Smoke test

After a complete build:

```bat
native_loader\smoke_bootstrap.bat
```

The expected result outside Minecraft is a controlled message stating that no Minecraft/Forge classloader was found. A `NoClassDefFoundError` indicates a bootstrap layering problem.

## Limitation

The bootstrap supports late loading of code that does not require early Mixins. Classes already loaded by Minecraft cannot be reliably transformed without a retransformation-capable Java agent or JVMTI path.
