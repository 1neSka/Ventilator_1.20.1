package team.xenobyte.modern.bootstrap;

import java.util.ArrayList;
import java.util.List;

public final class RuntimeEnvironment {
    private static final String[][] REQUIRED_CLASS_GROUPS = {
        { "Minecraft", "net.minecraft.client.Minecraft" },
        { "MinecraftForge", "net.minecraftforge.common.MinecraftForge" },
        { "ClientTickEvent", "net.minecraftforge.event.TickEvent$ClientTickEvent" },
        { "RenderGuiOverlayEvent", "net.minecraftforge.client.event.RenderGuiOverlayEvent" },
        { "RenderLevelStageEvent", "net.minecraftforge.client.event.RenderLevelStageEvent" }
    };

    private RuntimeEnvironment() {
    }

    public static void require(String source) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RuntimeEnvironment.class.getClassLoader();
        }

        BootstrapLog.info("Checking Forge runtime environment, source=" + source + ", loader=" + describe(loader));

        List<String> missing = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        for (String[] group : REQUIRED_CLASS_GROUPS) {
            String resolvedClass = findLoadable(loader, group);
            if (resolvedClass == null) {
                resolvedClass = findLoadable(RuntimeEnvironment.class.getClassLoader(), group);
            }

            if (resolvedClass == null) {
                missing.add(group[0] + " candidates=" + candidates(group));
            } else {
                resolved.add(group[0] + "=" + resolvedClass);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing runtime classes: " + missing);
        }

        BootstrapLog.info("Forge runtime environment OK; resolved=" + resolved + "; forge=" + forgeVersion(loader));
    }

    private static String findLoadable(ClassLoader loader, String[] group) {
        for (int i = 1; i < group.length; i++) {
            if (canLoad(loader, group[i])) {
                return group[i];
            }
        }
        return null;
    }

    private static List<String> candidates(String[] group) {
        List<String> candidates = new ArrayList<>();
        for (int i = 1; i < group.length; i++) {
            candidates.add(group[i]);
        }
        return candidates;
    }

    private static boolean canLoad(ClassLoader loader, String className) {
        if (loader == null) {
            return false;
        }

        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static String forgeVersion(ClassLoader loader) {
        try {
            Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", false, loader);
            Object instance = modList.getMethod("get").invoke(null);
            Object optional = modList.getMethod("getModContainerById", String.class).invoke(instance, "forge");
            return String.valueOf(optional);
        } catch (Exception | LinkageError ignored) {
            return "unknown";
        }
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
