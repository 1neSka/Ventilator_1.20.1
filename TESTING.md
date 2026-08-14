# Forge 1.20.1 testing

Only test on clients and servers where you have permission. Packet, movement, automation, and native-injection behavior may be rejected by servers or prohibited by their rules.

## Build verification

From the repository root:

```bat
build_all.bat
native_loader\verify_hotfix.bat /nopause
```

Expected build outputs:

```text
build\libs\xenobyte-modern-0.1.0.jar
native_loader\build\xenobyte-modern-loader.x64.dll
native_loader\build\xenobyte-modern-0.1.0.jar
native_loader\build\BUILD_INFO.txt
```

Expected verification result:

```text
Result: HOTFIX_VERIFY_OK
```

Create an isolated numbered test package when needed:

```bat
native_loader\package_hotfix.bat
```

## Bootstrap smoke test

```bat
native_loader\smoke_bootstrap.bat
```

When run outside Minecraft, this expected message indicates correct bootstrap layering:

```text
Expected smoke-test failure: Could not find a classloader that can resolve Minecraft/Forge classes
```

A `NoClassDefFoundError` at this stage is a real failure.

## Native bootstrap test

1. Run `native_loader\before_live_test.bat /nopause`.
2. Start a Forge 1.20.1 client and wait for the main menu or a loaded world.
3. Identify the actual game JVM process rather than a launcher/helper JVM.
4. Use a compatible DLL loader to load the verified `native_loader\build\xenobyte-modern-loader.x64.dll` into that process.
5. Run `native_loader\after_live_test.bat /nopause`.

Expected native log markers:

```text
Using jar:
Jar file exists
Bootstrap invoked successfully
Native loader thread finished; unloading DLL module
```

Expected Java log markers:

```text
NativeBootstrap start
Forge runtime environment OK
Forge direct event listeners registered
Bootstrap completed:
First client tick callback observed
```

The HUD and world-render callbacks only appear after their corresponding game states are reached.

Open the GUI with Right Shift. Bind assignment is hover-based; Backspace or pressing the same key clears a bind. Hold Right Shift over a module or setting to display its description. Press Escape to close the GUI.

## Runtime configuration

Runtime settings are stored in the current user's temporary directory as:

```text
%TEMP%\xenobyte-modern-forge.properties
```

Show, back up, reset, or restore them with:

```bat
native_loader\runtime_config.bat
native_loader\runtime_config.bat backup
native_loader\runtime_config.bat reset
native_loader\runtime_config.bat restore-latest
```

## Reports

```bat
native_loader\collect_runtime_report.bat
native_loader\analyze_runtime_report.bat
```

The report is written to:

```text
native_loader\build\last_runtime_report.txt
```

Expected analyzer states:

- `WAITING_FOR_LIVE_INJECT`: no Java runtime data has been collected since logs were cleared;
- `CHECK_WARNINGS`: a known failure marker was found;
- `REPORT_HAS_RUNTIME_DATA`: Java runtime data exists and no known fatal marker was found.

Reports and packet logs can contain session-specific or personal data, including player names, chat, server data, and packet payloads. They are Git-ignored; review and redact them before sharing.

## Common failures

`jvm.dll is not loaded in this process`

The DLL was loaded into a non-JVM or launcher/helper process. Select the actual game JVM.

`No created Java VM found`

The target process contains `jvm.dll`, but the running VM could not be obtained through `JNI_GetCreatedJavaVMs`.

`Missing runtime classes`

The target is not the supported Forge 1.20.1 runtime, or the selected classloader cannot see the required client classes.

`Could not find a classloader that can resolve Minecraft/Forge classes`

The loader attached to Java, but no visible classloader could resolve the game runtime.

## Design boundary

The project uses direct Forge callbacks and client-side Minecraft access. Late loading cannot reliably apply Mixins to classes that are already loaded. Server-side validation may correct or reject client movement and packet behavior.
