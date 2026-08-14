package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.MovementUtil;

public class NoFallModule extends XenoModule {
    private final ModuleSetting trigger = setting("Trigger", ModuleSetting.number("Trigger", 2.5D, 0.5D, 8.0D, 0.5D)
        .describe("Minimum local fall distance before NoFall tries the rescue jump."));
    private final ModuleSetting floorRange = setting("FloorRange", ModuleSetting.number("FloorRange", 2.0D, 0.4D, 4.0D, 0.2D)
        .describe("How close the floor must be before the rescue jump fires."));
    private long lastActionLogNanos;
    private double accumulatedFall;
    private double lastY;
    private boolean hasLastY;
    private int rescueCooldownTicks;

    public NoFallModule() {
        super("NoFall", Category.MOVE, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        resetTracker(client);
    }

    @Override
    public void onDisable(Minecraft client) {
        accumulatedFall = 0.0D;
        hasLastY = false;
        rescueCooldownTicks = 0;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client != null && client.player != null) {
            float before = client.player.fallDistance;
            boolean rescued = false;
            double currentY = client.player.getY();
            double motionY = client.player.getDeltaMovement().y;
            if (!hasLastY) {
                resetTracker(client);
            }
            if (rescueCooldownTicks > 0) {
                rescueCooldownTicks--;
            }
            if (client.player.onGround() || motionY >= -0.03D) {
                accumulatedFall = 0.0D;
            } else {
                accumulatedFall += Math.max(0.0D, lastY - currentY);
            }

            double trackedFall = Math.max(before, accumulatedFall);
            double effectiveRange = Math.max(floorRange.doubleValue(), Math.min(4.0D, Math.abs(motionY) + 1.4D));
            if (rescueCooldownTicks == 0
                && trackedFall >= trigger.floatValue()
                && motionY < -0.08D
                && MovementUtil.hasFloorWithin(client, effectiveRange)) {
                MovementUtil.applyRescueJump(client, "NoFall");
                rescueCooldownTicks = 8;
                accumulatedFall = 0.0D;
                rescued = true;
            }
            client.player.fallDistance = 0.0F;
            lastY = client.player.getY();
            hasLastY = true;
            if (before > 0.5F || trackedFall > 0.5D || rescued) {
                logAction(before, trackedFall, rescued, client.player.getDeltaMovement().y, effectiveRange);
            }
        }
    }

    private void resetTracker(Minecraft client) {
        if (client != null && client.player != null) {
            lastY = client.player.getY();
            hasLastY = true;
        }
        accumulatedFall = 0.0D;
        rescueCooldownTicks = 0;
    }

    private void logAction(float fallDistance, double trackedFall, boolean rescued, double motionY, double effectiveRange) {
        long now = System.nanoTime();
        if (now - lastActionLogNanos < 2_000_000_000L) {
            return;
        }
        lastActionLogNanos = now;
        BootstrapLog.info("NoFall tick action: fallDistance=" + String.format(java.util.Locale.ROOT, "%.2f", fallDistance)
            + ", trackedFall=" + String.format(java.util.Locale.ROOT, "%.2f", trackedFall)
            + ", rescueJump=" + rescued
            + ", motionY=" + String.format(java.util.Locale.ROOT, "%.3f", motionY)
            + ", trigger=" + trigger.displayValue()
            + ", floorRange=" + floorRange.displayValue()
            + ", effectiveRange=" + String.format(java.util.Locale.ROOT, "%.2f", effectiveRange)
            + ", cooldown=" + rescueCooldownTicks);
    }

    @Override
    public String description() {
        return "Resets local fall distance and can pulse a local jump shortly before landing.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (client == null || client.player == null) {
            return "NoFall player=null";
        }
        return "NoFall fall=" + String.format(java.util.Locale.ROOT, "%.2f", client.player.fallDistance)
            + " tracked=" + String.format(java.util.Locale.ROOT, "%.2f", accumulatedFall)
            + " onGround=" + client.player.onGround();
    }
}
