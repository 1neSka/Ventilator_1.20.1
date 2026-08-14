package team.xenobyte.modern.module.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import team.xenobyte.modern.bootstrap.BootstrapLog;

public final class XRayTargetRegistry {
    private static final String CONFIG_DIRECTORY = "xenobyte-modern";
    private static final String CONFIG_FILE = "xray-targets.properties";
    private static final Map<Block, TargetStyle> TARGETS = new ConcurrentHashMap<>();
    private static boolean loaded;

    private XRayTargetRegistry() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        TARGETS.clear();

        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            BootstrapLog.info("XRay target config is new: " + path);
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            for (String rawId : properties.stringPropertyNames()) {
                ResourceLocation id = ResourceLocation.tryParse(rawId);
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                    BootstrapLog.info("XRay target ignored; block is unavailable: " + rawId);
                    continue;
                }
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block == null || block == Blocks.AIR) {
                    continue;
                }
                TargetStyle style = parseStyle(properties.getProperty(rawId));
                if (style != null) {
                    TARGETS.put(block, style);
                }
            }
            BootstrapLog.info("XRay targets loaded: count=" + TARGETS.size() + ", path=" + path);
        } catch (IOException | RuntimeException error) {
            BootstrapLog.error("XRay target config load failed: " + path, error);
        }
    }

    public static void put(Block block, int color, RenderMode mode) {
        ensureLoaded();
        if (block != null && block != Blocks.AIR && mode != null) {
            TARGETS.put(block, new TargetStyle(0xff000000 | (color & 0x00ffffff), mode));
            save();
        }
    }

    public static boolean remove(Block block) {
        ensureLoaded();
        boolean removed = block != null && TARGETS.remove(block) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public static boolean remove(ResourceLocation id) {
        ensureLoaded();
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return false;
        }
        return remove(BuiltInRegistries.BLOCK.get(id));
    }

    public static int clear() {
        ensureLoaded();
        int size = TARGETS.size();
        TARGETS.clear();
        save();
        return size;
    }

    public static boolean contains(Block block) {
        ensureLoaded();
        return block != null && TARGETS.containsKey(block);
    }

    public static int size() {
        ensureLoaded();
        return TARGETS.size();
    }

    public static TargetStyle style(Block block) {
        ensureLoaded();
        return block == null ? null : TARGETS.get(block);
    }

    public static Map<Block, TargetStyle> snapshot() {
        ensureLoaded();
        return Map.copyOf(TARGETS);
    }

    public static List<TargetEntry> entries() {
        ensureLoaded();
        List<TargetEntry> result = new ArrayList<>(TARGETS.size());
        TARGETS.forEach((block, style) -> result.add(new TargetEntry(BuiltInRegistries.BLOCK.getKey(block), block, style)));
        result.sort(Comparator.comparing(entry -> entry.id().toString()));
        return List.copyOf(result);
    }

    public static int colorForChoice(String choice) {
        return switch (choice) {
            case "Red" -> 0xffff5555;
            case "Green" -> 0xff55ff77;
            case "Gold" -> 0xffffd65a;
            case "Blue" -> 0xff5588ff;
            case "Orange" -> 0xffff9955;
            case "White" -> 0xffffffff;
            case "Gray" -> 0xff999999;
            case "Purple" -> 0xffbb77ff;
            case "Pink" -> 0xffff77cc;
            default -> 0xff55d6ff;
        };
    }

    private static synchronized void save() {
        if (!loaded) {
            return;
        }

        Path path = configPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Properties properties = new Properties();
        entries().forEach(entry -> properties.setProperty(
            entry.id().toString(),
            String.format("%08X,%s", entry.style().color(), entry.style().mode().name())
        ));

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Xenobyte Modern custom XRay targets");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            BootstrapLog.error("XRay target config save failed: " + path, error);
        }
    }

    private static TargetStyle parseStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(",", 2);
        try {
            long parsedColor = Long.parseUnsignedLong(parts[0], 16);
            RenderMode mode = parts.length > 1 ? RenderMode.valueOf(parts[1]) : RenderMode.OUTLINE;
            return new TargetStyle((int)parsedColor, mode);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static Path configPath() {
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client != null && client.gameDirectory != null
            ? client.gameDirectory.toPath()
            : Path.of(System.getProperty("user.home", "."));
        return gameDirectory.resolve("config").resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE);
    }

    public enum RenderMode {
        OUTLINE,
        BOTH,
        FILLED;

        public static RenderMode from(String value) {
            return switch (value) {
                case "Both" -> BOTH;
                case "Filled" -> FILLED;
                default -> OUTLINE;
            };
        }

        public String displayName() {
            return switch (this) {
                case BOTH -> "Both";
                case FILLED -> "Filled";
                default -> "Outline";
            };
        }

        public RenderMode next(int direction) {
            RenderMode[] values = values();
            int next = Math.floorMod(ordinal() + (direction < 0 ? -1 : 1), values.length);
            return values[next];
        }
    }

    public record TargetStyle(int color, RenderMode mode) {
    }

    public record TargetEntry(ResourceLocation id, Block block, TargetStyle style) {
    }
}
