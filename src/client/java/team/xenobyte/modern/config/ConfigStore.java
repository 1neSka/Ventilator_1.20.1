package team.xenobyte.modern.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import team.xenobyte.modern.bootstrap.BootstrapLog;
import net.minecraft.client.Minecraft;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.setting.ModuleSetting;

public final class ConfigStore {
    private static final Path CONFIG = Path.of(System.getProperty("java.io.tmpdir"), "xenobyte-modern-forge.properties");

    private ConfigStore() {
    }

    public static void load(ModuleManager modules) {
        if (!Files.isRegularFile(CONFIG)) {
            BootstrapLog.info("Config not found: " + CONFIG.getFileName());
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG)) {
            properties.load(input);
        } catch (IOException exception) {
            BootstrapLog.error("Config load failed: " + CONFIG.getFileName(), exception);
            return;
        }

        Minecraft client = Minecraft.getInstance();
        for (Category category : Category.values()) {
            Integer x = parseInt(properties.getProperty("gui.panel." + key(category.name()) + ".x"));
            Integer y = parseInt(properties.getProperty("gui.panel." + key(category.name()) + ".y"));
            if (x != null && y != null) {
                modules.setPanelPosition(category, x, y);
            }
        }
        for (XenoModule module : modules.modules()) {
            String prefix = "module." + key(module.name()) + ".";
            String bind = properties.getProperty(prefix + "bind");
            if (bind != null && !bind.isBlank()) {
                try {
                    module.setKeyBind(Integer.parseInt(bind));
                } catch (NumberFormatException ignored) {
                    module.setKeyBind(org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN);
                }
            }
            module.setHidden(Boolean.parseBoolean(properties.getProperty(prefix + "hidden", "false")));
            Integer hudX = parseInt(properties.getProperty(prefix + "hud_x"));
            Integer hudY = parseInt(properties.getProperty(prefix + "hud_y"));
            module.setHudOffset(hudX == null ? 0 : hudX, hudY == null ? 0 : hudY);

            for (ModuleSetting setting : module.settings()) {
                if (setting.kind() == ModuleSetting.Kind.ACTION) {
                    continue;
                }
                setting.setFromString(properties.getProperty(prefix + "setting." + key(setting.name())));
            }

            String enabled = properties.getProperty(prefix + "enabled");
            if (module.mode() == ModuleMode.TOGGLE && enabled != null && Boolean.parseBoolean(enabled)) {
                module.setEnabled(client, true);
            }
        }
        BootstrapLog.info("Config loaded: " + CONFIG.getFileName());
    }

    public static void save(ModuleManager modules) {
        Properties properties = new Properties();
        int defaultX = 12;
        for (Category category : Category.values()) {
            ModuleManager.PanelPosition position = modules.panelPosition(category, defaultX, 18);
            String prefix = "gui.panel." + key(category.name()) + ".";
            properties.setProperty(prefix + "x", Integer.toString(position.x()));
            properties.setProperty(prefix + "y", Integer.toString(position.y()));
            defaultX += 132;
        }
        for (XenoModule module : modules.modules()) {
            String prefix = "module." + key(module.name()) + ".";
            properties.setProperty(prefix + "bind", Integer.toString(module.keyBind()));
            properties.setProperty(prefix + "enabled", Boolean.toString(module.mode() == ModuleMode.TOGGLE && module.enabled()));
            properties.setProperty(prefix + "hidden", Boolean.toString(module.hidden()));
            properties.setProperty(prefix + "hud_x", Integer.toString(module.hudOffsetX()));
            properties.setProperty(prefix + "hud_y", Integer.toString(module.hudOffsetY()));
            for (ModuleSetting setting : module.settings()) {
                if (setting.kind() == ModuleSetting.Kind.ACTION) {
                    continue;
                }
                properties.setProperty(prefix + "setting." + key(setting.name()), setting.rawValueString());
            }
        }

        try (OutputStream output = Files.newOutputStream(CONFIG)) {
            properties.store(output, "Xenobyte Modern Forge runtime config");
            BootstrapLog.info("Config saved: " + CONFIG.getFileName());
        } catch (IOException exception) {
            BootstrapLog.error("Config save failed: " + CONFIG.getFileName(), exception);
        }
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
