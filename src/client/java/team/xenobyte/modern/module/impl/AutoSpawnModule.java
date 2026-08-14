package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AutoSpawnModule extends XenoModule {
    private final ModuleSetting health = setting("Health", ModuleSetting.number("Health", 6.0D, 1.0D, 20.0D, 1.0D)
        .describe("Uses /spawn when current health is at or below this value."));
    private final ModuleSetting cooldown = setting("Cooldown", ModuleSetting.number("Cooldown", 20.0D, 3.0D, 120.0D, 1.0D)
        .describe("Minimum seconds between automatic /spawn commands."));
    private final ModuleSetting rearmHp = setting("RearmHP", ModuleSetting.number("RearmHP", 12.0D, 1.0D, 20.0D, 1.0D)
        .describe("Health required before AutoSpawn can fire again after a low-HP trigger."));
    private boolean armed = true;
    private long lastCommandNanos;

    public AutoSpawnModule() {
        super("AutoSpawn", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        float hp = client.player.getHealth();
        if (!armed && hp >= rearmHp.floatValue()) {
            armed = true;
            BootstrapLog.info("AutoSpawn rearmed: hp=" + String.format(java.util.Locale.ROOT, "%.1f", hp));
        }
        long now = System.nanoTime();
        if (!armed || hp > health.floatValue() || now - lastCommandNanos < cooldown.intValue() * 1_000_000_000L) {
            return;
        }
        lastCommandNanos = now;
        armed = false;
        client.player.connection.sendCommand("spawn");
        BootstrapLog.info("AutoSpawn command sent: hp="
            + String.format(java.util.Locale.ROOT, "%.1f", hp)
            + ", threshold=" + health.displayValue()
            + ", cooldown=" + cooldown.displayValue());
    }

    @Override
    public String description() {
        return "Sends /spawn through the normal client command API when health drops below a configured threshold.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (client == null || client.player == null) {
            return "AutoSpawn player=null";
        }
        return "AutoSpawn hp=" + Math.round(client.player.getHealth()) + " armed=" + armed;
    }
}
