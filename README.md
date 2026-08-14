# Xenobyte 1.20.1
A project is a modification of xenobyte 1.7.10 to fit the 1.20.1 neo forge. Notice that it is has minimal anti-cheat bypass, as the intended functioning environment are modded minecraft projects, like McSkill, LemonCraft, CubixWorld etc.
## Status

- Minecraft 1.20.1;
- Forge 47.4.0;
- Java 17;
- native loader: Windows x64 and Visual C++;
- client side only.

## Build

Prerequisites and commands are documented in [`BUILD_GUIDE.md`](BUILD_GUIDE.md).

```bat
build_all.bat
```

The normal Forge jar is created at:

```text
build\libs\xenobyte-modern-0.1.0.jar
```

The optional native package is created under `native_loader\build`.

## Documentation

- [`TESTING.md`](TESTING.md) - smoke, native-bootstrap, and runtime checks;
- [`PORTING_NOTES.md`](PORTING_NOTES.md) - architecture and porting constraints;
- [`native_loader/README.md`](native_loader/README.md) - native loader behavior and diagnostics.


## License

[MIT License](LICENSE).
