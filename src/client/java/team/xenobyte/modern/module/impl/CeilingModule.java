package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.MovementUtil;

public class CeilingModule extends XenoModule {
    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 0, "Auto", "Instant", "Steps")
        .describe("Auto uses stepped movement only for simple vertical routes; Instant skips complex blocked routes."));
    private final ModuleSetting stepSize = setting("StepSize", ModuleSetting.number("StepSize", 8.0D, 2.0D, 8.0D, 1.0D)
        .describe("Maximum vertical distance of each stepped Ceiling movement pulse."));
    private final ModuleSetting delayTicks = setting("Delay", ModuleSetting.number("Delay", 1.0D, 0.0D, 10.0D, 1.0D)
        .describe("Client ticks to wait between stepped Ceiling movement pulses."));
    private PendingMove pendingMove;

    public CeilingModule() {
        super("Ceiling", Category.MOVE, ModuleMode.SINGLE);
    }

    @Override
    public void onPerform(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        if (pendingMove != null) {
            pendingMove = null;
            message(client, "Ceiling: stepped move cancelled");
            BootstrapLog.info("Ceiling pending move cancelled by user");
            return;
        }

        BlockPos base = client.player.blockPosition();
        boolean upward = client.player.getXRot() < 0.0F;
        int step = upward ? 1 : -1;
        int x = base.getX();
        int z = base.getZ();
        int y = base.getY() + step;
        int minY = client.level.getMinBuildHeight();
        int maxY = client.level.getMaxBuildHeight() - 2;

        if (upward) {
            while (inside(y, minY, maxY) && isTwoAir(client, x, y, z)) {
                y += step;
            }
            while (inside(y, minY, maxY) && !isTwoAir(client, x, y, z)) {
                y += step;
            }
        } else {
            while (inside(y, minY, maxY) && !isTwoAir(client, x, y, z)) {
                y += step;
            }
        }

        if (!inside(y, minY, maxY)) {
            BootstrapLog.info("Ceiling failed: no matching air pocket, upward=" + upward + ", base=" + base);
            message(client, "Ceiling: no matching air pocket");
            return;
        }
        if (hasLava(client, x, y, z)) {
            BootstrapLog.info("Ceiling failed: lava near target, upward=" + upward + ", targetY=" + y + ", base=" + base);
            message(client, "Ceiling: lava on the way");
            return;
        }

        boolean useSteps = shouldUseSteps(client, x, z, y, upward);
        if (useSteps && Math.abs(client.player.getY() - y) > 2.0D) {
            double maxStep = Math.max(2.0D, Math.min(8.0D, stepSize.doubleValue()));
            pendingMove = new PendingMove(x, z, y, upward, Math.max(0, delayTicks.intValue()), maxStep, client.player.getY());
            BootstrapLog.info("Ceiling stepped move started: upward=" + upward
                + ", base=" + base
                + ", targetY=" + y
                + ", mode=" + mode.choiceValue()
                + ", maxStep=" + format(maxStep)
                + ", routeBlocks=" + blockedPocketRuns(client, x, z, base.getY(), y)
                + ", delayTicks=" + delayTicks.displayValue());
            message(client, "Ceiling: stepped to Y " + y);
            return;
        }

        moveInstant(client, x, y, z, upward, "Ceiling moved");
        applyRescueJump(client, upward, "Ceiling instant");
        BootstrapLog.info("Ceiling moved: upward=" + upward
            + ", base=" + base
            + ", targetX=" + (x + 0.5D)
            + ", targetY=" + y
            + ", targetZ=" + (z + 0.5D)
            + ", hasFloor=" + hasFloor(client, x, y, z)
            + ", downwardMode=" + (upward ? "floor-after-blocks" : "nearest-air-pocket"));
        message(client, upward ? "Ceiling: moved to Y " + y : "Ceiling: moved to Y " + y);
    }

    @Override
    public boolean wantsTickWhenDisabled() {
        return pendingMove != null;
    }

    @Override
    public void onTick(Minecraft client) {
        if (pendingMove == null || client == null || client.player == null || client.level == null) {
            return;
        }
        if (pendingMove.cooldownTicks > 0) {
            pendingMove.cooldownTicks--;
            return;
        }

        double currentY = client.player.getY();
        double targetY = pendingMove.targetY;
        double delta = targetY - currentY;
        if (Math.abs(delta) <= 0.05D) {
            finishPending(client);
            return;
        }

        detectRollback(client, currentY);
        if (pendingMove == null) {
            return;
        }

        double amount = Math.min(Math.abs(delta), pendingMove.activeStep);
        double nextY = currentY + Math.copySign(amount, delta);
        client.player.setPos(pendingMove.x + 0.5D, nextY, pendingMove.z + 0.5D);
        client.player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        client.player.fallDistance = 0.0F;
        client.player.setOnGround(false);
        pendingMove.cooldownTicks = pendingMove.delayTicks;
        pendingMove.pulses++;
        pendingMove.lastCommandY = nextY;
        pendingMove.lastSeenY = nextY;
        BootstrapLog.info("Ceiling stepped pulse: targetY=" + targetY
            + ", currentY=" + String.format(java.util.Locale.ROOT, "%.2f", nextY)
            + ", step=" + format(pendingMove.activeStep)
            + ", upward=" + pendingMove.upward);

        if (Math.abs(targetY - nextY) <= 0.05D) {
            finishPending(client);
        } else if (pendingMove != null && pendingMove.pulses > pendingMove.maxPulses) {
            fallbackInstant(client, "max pulses");
        }
    }

    private void finishPending(Minecraft client) {
        PendingMove move = pendingMove;
        pendingMove = null;
        moveInstant(client, move.x, move.targetY, move.z, move.upward, "Ceiling stepped finish");
        applyRescueJump(client, move.upward, "Ceiling stepped");
        message(client, "Ceiling: stepped finished Y " + move.targetY);
    }

    private void detectRollback(Minecraft client, double currentY) {
        if (pendingMove == null || Double.isNaN(pendingMove.lastCommandY)) {
            return;
        }

        double previousDistance = Math.abs(pendingMove.targetY - pendingMove.lastSeenY);
        double currentDistance = Math.abs(pendingMove.targetY - currentY);
        boolean lostProgress = currentDistance > previousDistance + 0.25D;
        boolean correctedAway = Math.abs(currentY - pendingMove.lastCommandY) > Math.max(0.35D, pendingMove.activeStep * 0.35D);
        if (!lostProgress && !correctedAway) {
            pendingMove.rollbackTicks = 0;
            pendingMove.lastSeenY = currentY;
            return;
        }

        pendingMove.rollbackTicks++;
        BootstrapLog.info("Ceiling rollback detected: targetY=" + pendingMove.targetY
            + ", currentY=" + format(currentY)
            + ", lastCommandY=" + format(pendingMove.lastCommandY)
            + ", activeStep=" + format(pendingMove.activeStep)
            + ", rollbackTicks=" + pendingMove.rollbackTicks);

        if (pendingMove.rollbackTicks < 2) {
            pendingMove.lastSeenY = currentY;
            return;
        }

        if (pendingMove.activeStep > 2.0D) {
            pendingMove.activeStep = Math.max(2.0D, pendingMove.activeStep - 2.0D);
            pendingMove.rollbackTicks = 0;
            pendingMove.lastSeenY = currentY;
            BootstrapLog.info("Ceiling reduced step after rollback: activeStep=" + format(pendingMove.activeStep));
            return;
        }

        fallbackInstant(client, "rollback loop");
    }

    private void fallbackInstant(Minecraft client, String reason) {
        PendingMove move = pendingMove;
        pendingMove = null;
        BootstrapLog.info("Ceiling stepped fallback to instant: reason=" + reason
            + ", targetY=" + move.targetY
            + ", upward=" + move.upward);
        moveInstant(client, move.x, move.targetY, move.z, move.upward, "Ceiling stepped fallback");
        applyRescueJump(client, move.upward, "Ceiling fallback");
        message(client, "Ceiling: fallback instant Y " + move.targetY);
    }

    private void applyRescueJump(Minecraft client, boolean upward, String source) {
        if (!upward) {
            MovementUtil.applyRescueJump(client, source);
        }
    }

    private void moveInstant(Minecraft client, int x, int y, int z, boolean upward, String source) {
        client.player.setPos(x + 0.5D, y, z + 0.5D);
        client.player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        client.player.fallDistance = 0.0F;
        client.player.setOnGround(!upward);
        BootstrapLog.info(source + ": upward=" + upward
            + ", targetX=" + (x + 0.5D)
            + ", targetY=" + y
            + ", targetZ=" + (z + 0.5D));
    }

    private boolean inside(int y, int minY, int maxY) {
        return y >= minY && y <= maxY;
    }

    private boolean shouldUseSteps(Minecraft client, int x, int z, int targetY, boolean upward) {
        String activeMode = mode.choiceValue();
        if ("Instant".equals(activeMode)) {
            return false;
        }
        if ("Steps".equals(activeMode)) {
            return true;
        }

        int currentY = client.player.blockPosition().getY();
        int blockedRuns = blockedPocketRuns(client, x, z, currentY, targetY);
        int allowedRuns = upward ? 1 : 0;
        return blockedRuns <= allowedRuns;
    }

    private int blockedPocketRuns(Minecraft client, int x, int z, int fromY, int targetY) {
        int direction = Integer.compare(targetY, fromY);
        if (direction == 0) {
            return 0;
        }

        int blockedRuns = 0;
        boolean inBlockedRun = false;
        for (int y = fromY + direction; y != targetY; y += direction) {
            boolean blocked = !isTwoAir(client, x, y, z);
            if (blocked && !inBlockedRun) {
                blockedRuns++;
            }
            inBlockedRun = blocked;
            if (!blocked) {
                inBlockedRun = false;
            }
        }
        return blockedRuns;
    }

    private boolean isTwoAir(Minecraft client, int x, int y, int z) {
        return client.level.getBlockState(new BlockPos(x, y, z)).isAir()
            && client.level.getBlockState(new BlockPos(x, y + 1, z)).isAir();
    }

    private boolean hasFloor(Minecraft client, int x, int y, int z) {
        return !client.level.getBlockState(new BlockPos(x, y - 1, z)).getCollisionShape(client.level, new BlockPos(x, y - 1, z)).isEmpty();
    }

    private boolean hasLava(Minecraft client, int x, int y, int z) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockState state = client.level.getBlockState(new BlockPos(x, y + dy, z));
            if (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private void message(Minecraft client, String message) {
        client.player.displayClientMessage(Component.literal(message), true);
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    @Override
    public String description() {
        return "Moves the local player to the next vertical air pocket by pitch direction; optional stepped mode moves in smaller Y pulses.";
    }

    private static final class PendingMove {
        private final int x;
        private final int z;
        private final int targetY;
        private final boolean upward;
        private final int delayTicks;
        private final int maxPulses;
        private final double maxStep;
        private double activeStep;
        private double lastCommandY = Double.NaN;
        private double lastSeenY;
        private int cooldownTicks;
        private int pulses;
        private int rollbackTicks;

        private PendingMove(int x, int z, int targetY, boolean upward, int delayTicks, double maxStep, double currentY) {
            this.x = x;
            this.z = z;
            this.targetY = targetY;
            this.upward = upward;
            this.delayTicks = delayTicks;
            this.maxStep = maxStep;
            this.activeStep = maxStep;
            this.lastSeenY = currentY;
            this.maxPulses = Math.max(8, (int)Math.ceil(Math.abs(targetY - currentY) / 2.0D) + 8);
        }
    }
}
