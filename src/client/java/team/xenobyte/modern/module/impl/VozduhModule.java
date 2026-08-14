package team.xenobyte.modern.module.impl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class VozduhModule extends XenoModule {
    private static final String[] TARGETS = {
        "KillAura",
        "AutoMiner",
        "Scaffold",
        "AreaMiner",
        "ClientSpeed",
        "LootChest"
    };

    private final ModuleManager modules;
    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 32.0D, 4.0D, 160.0D, 1.0D)
        .describe("Player detection radius in blocks."));
    private final ModuleSetting resumeGap = setting("ResumeGap", ModuleSetting.number("ResumeGap", 4.0D, 0.0D, 32.0D, 1.0D)
        .describe("Extra distance before suspended modules are restored, preventing rapid toggling on the border."));
    private final Map<String, ModuleSetting> toggles = new LinkedHashMap<>();
    private final Set<String> suspended = new java.util.LinkedHashSet<>();

    private boolean danger;
    private String nearestName = "none";
    private double nearestDistance = Double.POSITIVE_INFINITY;
    private long lastLogNanos;

    public VozduhModule(ModuleManager modules) {
        super("Vozduh", Category.MISC, ModuleMode.TOGGLE);
        this.modules = modules;
        for (String target : TARGETS) {
            toggles.put(target, setting(target, ModuleSetting.bool(target, true)
                .describe("Temporarily disables " + target + " while another player is nearby.")));
        }
    }

    @Override
    public void onDisable(Minecraft client) {
        restoreSuspended(client, "disabled");
        danger = false;
        nearestName = "none";
        nearestDistance = Double.POSITIVE_INFINITY;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        Nearest nearest = nearestPlayer(client);
        nearestName = nearest.name();
        nearestDistance = nearest.distance();
        double disableRadius = radius.doubleValue();
        double restoreRadius = disableRadius + Math.max(0.0D, resumeGap.doubleValue());

        if (nearest.distance() <= disableRadius) {
            danger = true;
            suspendSelected(client, nearest);
            return;
        }

        if (danger && nearest.distance() > restoreRadius) {
            danger = false;
            restoreSuspended(client, "clear " + nearest.describe());
        }
    }

    private void suspendSelected(Minecraft client, Nearest nearest) {
        int changed = 0;
        for (String name : TARGETS) {
            if (!enabledFor(name)) {
                continue;
            }
            XenoModule module = findModule(name);
            if (module == null || !module.enabled()) {
                continue;
            }
            module.setEnabled(client, false);
            suspended.add(name);
            changed++;
            BootstrapLog.info("Vozduh suspended module: " + name + ", nearest=" + nearest.describe());
        }
        if (changed > 0) {
            ModuleMessageLog.push("Vozduh", "suspended " + changed + " near " + nearest.describe());
        }
        log("Vozduh danger: nearest=" + nearest.describe()
            + ", suspended=" + suspended
            + ", radius=" + radius.displayValue());
    }

    private void restoreSuspended(Minecraft client, String reason) {
        if (suspended.isEmpty()) {
            return;
        }
        int restored = 0;
        for (String name : java.util.List.copyOf(suspended)) {
            XenoModule module = findModule(name);
            if (module != null && enabledFor(name) && !module.enabled()) {
                module.setEnabled(client, true);
                restored++;
                BootstrapLog.info("Vozduh restored module: " + name + ", reason=" + reason);
            }
            suspended.remove(name);
        }
        ModuleMessageLog.push("Vozduh", "restored " + restored + " (" + reason + ")");
    }

    private XenoModule findModule(String name) {
        for (XenoModule module : modules.modules()) {
            if (module.name().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    private boolean enabledFor(String name) {
        ModuleSetting setting = toggles.get(name);
        return setting != null && setting.boolValue();
    }

    private Nearest nearestPlayer(Minecraft client) {
        Player self = client.player;
        double best = Double.POSITIVE_INFINITY;
        String bestName = "none";
        for (Player player : client.level.players()) {
            if (player == self || !player.isAlive()) {
                continue;
            }
            double distance = Math.sqrt(player.distanceToSqr(self));
            if (distance < best) {
                best = distance;
                bestName = player.getName().getString();
            }
        }
        return new Nearest(bestName, best);
    }

    private void log(String text) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 1_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(text);
    }

    @Override
    public String description() {
        return "Temporarily disables selected modules while another player is inside the configured radius, then restores only modules it suspended.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Vozduh danger=" + danger
            + " nearest=" + nearestName + "@" + distanceText(nearestDistance)
            + " radius=" + radius.displayValue()
            + " suspended=" + suspended;
    }

    private String distanceText(double distance) {
        if (!Double.isFinite(distance)) {
            return "none";
        }
        return String.format(Locale.ROOT, "%.1f", distance);
    }

    private record Nearest(String name, double distance) {
        String describe() {
            if (!Double.isFinite(distance)) {
                return "none";
            }
            return name + "@" + String.format(Locale.ROOT, "%.1f", distance);
        }
    }
}
