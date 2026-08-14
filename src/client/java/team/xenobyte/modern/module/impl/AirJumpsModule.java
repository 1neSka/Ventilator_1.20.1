package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AirJumpsModule extends XenoModule {
    private final ModuleSetting onlyWhenJumpHeld = setting("JumpHeld", ModuleSetting.bool("JumpHeld", true)
        .describe("When ON, AirJumps only forces local ground state while the jump key is held."));
    private long lastLogNanos;

    public AirJumpsModule() {
        super("AirJumps", Category.MOVE, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.options == null || client.player.onGround()) {
            return;
        }
        if (onlyWhenJumpHeld.boolValue() && !client.options.keyJump.isDown()) {
            return;
        }
        client.player.setOnGround(true);
        log(client);
    }

    private void log(Minecraft client) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 3_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("AirJumps forced local ground: motionY="
            + String.format(java.util.Locale.ROOT, "%.3f", client.player.getDeltaMovement().y)
            + ", jumpHeld=" + client.options.keyJump.isDown());
    }

    @Override
    public String description() {
        return "Forces local onGround while airborne so jump input can be tested in the air.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (client == null || client.player == null) {
            return "AirJumps player=null";
        }
        return "AirJumps onGround=" + client.player.onGround();
    }
}
