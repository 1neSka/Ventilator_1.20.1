package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class SoundsModule extends XenoModule {
    private static final int HIT_COOLDOWN_TICKS = 4;
    private static final int AMBIENT_INTERVAL_TICKS = 20 * 30;

    private boolean attackWasDown;
    private int hitCooldown;
    private int ambientTicks;

    public SoundsModule() {
        super("Sounds", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        attackWasDown = false;
        hitCooldown = 0;
        ambientTicks = AMBIENT_INTERVAL_TICKS;
    }

    @Override
    public void onDisable(Minecraft client) {
        attackWasDown = false;
        hitCooldown = 0;
        ambientTicks = 0;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.options == null) {
            return;
        }

        if (hitCooldown > 0) {
            hitCooldown--;
        }
        if (--ambientTicks <= 0) {
            client.level.playLocalSound(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                SoundEvents.AMBIENT_CAVE.get(),
                SoundSource.AMBIENT,
                0.08F,
                1.0F,
                false
            );
            ambientTicks = AMBIENT_INTERVAL_TICKS;
            BootstrapLog.info("Sounds ambient event played");
        }

        boolean attackDown = client.options.keyAttack.isDown();
        if (attackDown && !attackWasDown && hitCooldown <= 0 && hasImpactTarget(client)) {
            client.player.playSound(SoundEvents.ANVIL_LAND, 1.0F, 1.0F);
            hitCooldown = HIT_COOLDOWN_TICKS;
            BootstrapLog.info("Sounds hit event played; cooldown=" + HIT_COOLDOWN_TICKS);
        }
        attackWasDown = attackDown;
    }

    private boolean hasImpactTarget(Minecraft client) {
        return client.hitResult != null && client.hitResult.getType() != HitResult.Type.MISS;
    }

    @Override
    public String description() {
        return "Plays built-in Minecraft sound events for impacts and periodic ambience.";
    }
}
