package team.xenobyte.modern.bootstrap;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NativeBootstrap {
    private static final String[] MINECRAFT_CLIENT_CLASSES = {
        "net.minecraft.client.Minecraft",
        "net.minecraft.client.MinecraftClient",
        "net.minecraft.class_310"
    };
    private static final String[] LOADER_MARKER_CLASSES = {
        "net.minecraftforge.common.MinecraftForge",
        "cpw.mods.modlauncher.Launcher",
        "net.fabricmc.loader.api.FabricLoader"
    };

    private static final List<ClassLoader> HELD_LOADERS = new ArrayList<>();
    private static boolean initialized;

    private NativeBootstrap() {
    }

    public static synchronized boolean initFromNative(String source, String jarPath) throws Exception {
        if (initialized) {
            BootstrapLog.info("NativeBootstrap already initialized, source=" + source);
            return true;
        }

        try {
            BootstrapLog.info("NativeBootstrap start, source=" + source + ", jar=" + fileName(jarPath));

            ClassLoader gameLoader = findGameClassLoader();
            if (gameLoader == null) {
                throw new IllegalStateException("Could not find a classloader that can resolve Minecraft/Forge classes");
            }
            BootstrapLog.info("Selected game classloader: " + describe(gameLoader));

            if (canLoad(gameLoader, "team.xenobyte.modern.bootstrap.GameBootstrap")) {
                BootstrapLog.info("GameBootstrap is already visible from game classloader");
                invokeGameBootstrap(gameLoader, source);
                initialized = true;
                return true;
            }

            ClassLoader currentLoader = NativeBootstrap.class.getClassLoader();
            if (currentLoader != null && canLoadAny(currentLoader, MINECRAFT_CLIENT_CLASSES)) {
                BootstrapLog.info("Current NativeBootstrap classloader can resolve Minecraft: " + describe(currentLoader));
                invokeGameBootstrap(currentLoader, source);
                initialized = true;
                return true;
            }

            URL jarUrl = resolveJarUrl(jarPath);
            BootstrapLog.info("Opening child URLClassLoader for " + fileName(jarUrl.getPath()));
            URLClassLoader childLoader = new URLClassLoader(new URL[] { jarUrl }, gameLoader);
            HELD_LOADERS.add(childLoader);

            invokeGameBootstrap(childLoader, source);
            initialized = true;
            return true;
        } catch (Exception | LinkageError error) {
            BootstrapLog.error("NativeBootstrap failed", error);
            throw error;
        }
    }

    public static boolean initFromNative(String jarPath) throws Exception {
        return initFromNative("jni-loader", jarPath);
    }

    private static void invokeGameBootstrap(ClassLoader loader, String source) throws Exception {
        Class<?> bootstrap = Class.forName("team.xenobyte.modern.bootstrap.GameBootstrap", true, loader);
        Method init = bootstrap.getMethod("initOnGameThread", String.class);
        init.invoke(null, source == null || source.isBlank() ? "jni-loader" : source);
    }

    private static URL resolveJarUrl(String jarPath) throws Exception {
        if (jarPath != null && !jarPath.isBlank()) {
            return new File(jarPath).getAbsoluteFile().toURI().toURL();
        }

        CodeSource source = NativeBootstrap.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalArgumentException("Jar path was not provided and code source is unavailable");
        }
        return source.getLocation();
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "unspecified";
        }
        return new File(path).getName();
    }

    private static ClassLoader findGameClassLoader() {
        Set<ClassLoader> candidates = new LinkedHashSet<>();
        candidates.add(Thread.currentThread().getContextClassLoader());
        candidates.add(NativeBootstrap.class.getClassLoader());
        candidates.add(ClassLoader.getSystemClassLoader());

        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            candidates.add(thread.getContextClassLoader());
        }

        BootstrapLog.info("Scanning classloader candidates: " + candidates.stream().map(NativeBootstrap::describe).toList());

        for (ClassLoader loader : candidates) {
            if (loader != null && canLoadAny(loader, MINECRAFT_CLIENT_CLASSES) && canLoadAny(loader, LOADER_MARKER_CLASSES)) {
                return loader;
            }
        }

        for (ClassLoader loader : candidates) {
            if (loader != null && canLoadAny(loader, MINECRAFT_CLIENT_CLASSES)) {
                return loader;
            }
        }

        return null;
    }

    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean canLoadAny(ClassLoader loader, String[] classNames) {
        for (String className : classNames) {
            if (canLoad(loader, className)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
