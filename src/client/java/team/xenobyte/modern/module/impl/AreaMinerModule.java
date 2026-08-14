package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AreaMinerModule extends XenoModule {
    private final ModuleSetting size = setting("Size", ModuleSetting.choice("Size", 0, "3x3", "5x5")
        .describe("Mining plane size around the looked-at block."));
    private final ModuleSetting depth = setting("Depth", ModuleSetting.number("Depth", 1.0D, 1.0D, 3.0D, 1.0D)
        .describe("How many block layers to mine into the looked-at face."));
    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 2.0D, 0.0D, 20.0D, 1.0D)
        .describe("Client ticks between area mining pulses."));
    private final ModuleSetting maxPerTick = setting("MaxTick", ModuleSetting.number("MaxTick", 24.0D, 1.0D, 96.0D, 1.0D)
        .describe("Maximum extra dig packets sent per pulse."));
    private final ModuleSetting handSwing = setting("HandSwing", ModuleSetting.bool("HandSwing", true)
        .describe("Swings the main hand after sending extra mining packets."));

    private int cooldownTicks;
    private int lastTargets;
    private long lastLogNanos;

    public AreaMinerModule() {
        super("AreaMiner", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.options == null || client.getConnection() == null) {
            return;
        }
        if (client.screen != null || !client.options.keyAttack.isDown()) {
            lastTargets = 0;
            cooldownTicks = 0;
            return;
        }
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            lastTargets = 0;
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = delay.intValue();

        List<BlockPos> targets = collectTargets(client, hit);
        int limit = Math.min(maxPerTick.intValue(), targets.size());
        Direction face = hit.getDirection();
        for (int i = 0; i < limit; i++) {
            BlockPos pos = targets.get(i);
            client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
            client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
        }
        if (limit > 0 && handSwing.boolValue()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        lastTargets = limit;
        log(hit.getBlockPos(), targets.size(), limit, face);
    }

    private List<BlockPos> collectTargets(Minecraft client, BlockHitResult hit) {
        BlockPos origin = hit.getBlockPos();
        Direction face = hit.getDirection();
        Direction inward = face.getOpposite();
        int planeSize = "5x5".equals(size.choiceValue()) ? 5 : 3;
        int half = planeSize / 2;
        int depthValue = depth.intValue();

        List<BlockPos> targets = new ArrayList<>(planeSize * planeSize * depthValue);
        for (int layer = 0; layer < depthValue; layer++) {
            BlockPos center = origin.relative(inward, layer);
            if (face.getAxis() == Direction.Axis.Y) {
                collectVerticalPlane(client, origin, center, half, targets);
            } else {
                collectWallPlane(client, origin, center, face, half, planeSize, targets);
            }
        }
        targets.sort(Comparator.comparingInt(pos -> pos.distManhattan(origin)));
        return targets;
    }

    private void collectVerticalPlane(Minecraft client, BlockPos origin, BlockPos center, int half, List<BlockPos> targets) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                addTarget(client, origin, center.offset(dx, 0, dz), targets);
            }
        }
    }

    private void collectWallPlane(Minecraft client, BlockPos origin, BlockPos center, Direction face, int half, int planeSize, List<BlockPos> targets) {
        int minY = planeSize == 5 ? -1 : -half;
        int maxY = planeSize == 5 ? 3 : half;
        for (int side = -half; side <= half; side++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos pos = face.getAxis() == Direction.Axis.X
                    ? center.offset(0, y, side)
                    : center.offset(side, y, 0);
                addTarget(client, origin, pos, targets);
            }
        }
    }

    private void addTarget(Minecraft client, BlockPos origin, BlockPos pos, List<BlockPos> targets) {
        if (pos.equals(origin)) {
            return;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK || state.getDestroySpeed(client.level, pos) < 0.0F) {
            return;
        }
        targets.add(pos.immutable());
    }

    private void log(BlockPos origin, int found, int sent, Direction face) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("AreaMiner pulse: origin=" + origin.toShortString()
            + ", face=" + face.getName()
            + ", size=" + size.choiceValue()
            + ", depth=" + depth.displayValue()
            + ", found=" + found
            + ", sent=" + sent
            + ", delay=" + delay.displayValue());
    }

    @Override
    public String description() {
        return "Expands held block mining into a 3x3 or 5x5 plane with configurable depth.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "AreaMiner size=" + size.choiceValue()
            + " depth=" + depth.displayValue()
            + " lastTargets=" + lastTargets;
    }
}
