package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class VanilaNukerModule extends XenoModule {
    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 1.0D, 1.0D, 8.0D, 1.0D)
        .describe("Block radius around the selected center."));
    private final ModuleSetting shape = setting("Shape", ModuleSetting.choice("Shape", 0, "Cube", "Sphere", "Face")
        .describe("Target volume shape: cube, old sphere, or face-oriented area."));
    private final ModuleSetting reach = setting("Reach", ModuleSetting.number("Reach", 8.0D, 3.0D, 16.0D, 0.5D)
        .describe("Eye-to-block distance limit used when collecting dig targets."));
    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 2.0D, 0.0D, 20.0D, 1.0D)
        .describe("Client ticks between nuker pulses."));
    private final ModuleSetting maxPerTick = setting("MaxTick", ModuleSetting.number("MaxTick", 16.0D, 1.0D, 512.0D, 1.0D)
        .describe("Maximum block dig packets sent per pulse."));
    private final ModuleSetting onView = setting("OnView", ModuleSetting.bool("OnView", true)
        .describe("Uses the looked-at block as center; otherwise uses the player position."));
    private final ModuleSetting onlyXRay = setting("OnlyXRay", ModuleSetting.bool("OnlyXRay", false)
        .describe("Limits targets by the XRaySet selector."));
    private final ModuleSetting target = setting("Target", ModuleSetting.choice("Target", 0, "Current", "SelectZone")
        .describe("Current uses the normal filters; SelectZone only mines blocks inside selected zones."));
    private final ModuleSetting xraySet = setting("XRaySet", ModuleSetting.choice("XRaySet", 0, "Custom", "Custom+Ore", "OreLike")
        .describe("Target set used while OnlyXRay is enabled."));
    private final ModuleSetting handSwing = setting("HandSwing", ModuleSetting.bool("HandSwing", true)
        .describe("Swings the main hand after a pulse."));

    private int cooldownTicks;
    private int roundRobinIndex;
    private int lastTargets;
    private int lastFound;
    private int groundSpoofPackets;
    private long lastLogNanos;
    private GroundMineTrace lastGroundTrace;

    public VanilaNukerModule() {
        super("VanilaNuker", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = delay.intValue();

        TargetCenter center = targetCenter(client);
        if (center == null) {
            lastTargets = 0;
            return;
        }

        List<BlockPos> targets = collectTargets(client, center);
        int limit = Math.min(maxPerTick.intValue(), targets.size());
        if (roundRobinIndex >= targets.size()) {
            roundRobinIndex = 0;
        }
        GroundMineTrace groundTrace = limit > 0 ? applyGroundMine(client, targets.get(roundRobinIndex % targets.size())) : null;
        try {
            for (int i = 0; i < limit; i++) {
                BlockPos pos = targets.get((roundRobinIndex + i) % targets.size());
                Direction face = faceFromPlayer(client, pos);
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
            }
        } finally {
            restoreGroundMine(client, groundTrace);
        }
        if (!targets.isEmpty()) {
            roundRobinIndex = (roundRobinIndex + limit) % targets.size();
        }
        if (limit > 0 && handSwing.boolValue()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        lastTargets = limit;
        lastFound = targets.size();
        lastGroundTrace = groundTrace;
        log(center.pos(), targets.size(), limit, groundTrace);
    }

    private TargetCenter targetCenter(Minecraft client) {
        if (onView.boolValue()) {
            HitResult picked = client.player.pick(Math.max(6.0D, reach.doubleValue()), 0.0F, false);
            if (picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                return new TargetCenter(hit.getBlockPos(), hit.getDirection());
            }
            return null;
        }
        return new TargetCenter(client.player.blockPosition(), Direction.UP);
    }

    private List<BlockPos> collectTargets(Minecraft client, TargetCenter center) {
        int r = radius.intValue();
        List<BlockPos> targets = new ArrayList<>();
        if ("Face".equals(shape.choiceValue())) {
            collectFaceTargets(client, center, r, targets);
        } else {
            boolean sphere = "Sphere".equals(shape.choiceValue());
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (sphere && dx * dx + dy * dy + dz * dz > r * r) {
                            continue;
                        }
                        mutable.set(center.pos().getX() + dx, center.pos().getY() + dy, center.pos().getZ() + dz);
                        addTarget(client, center.pos(), mutable, targets);
                    }
                }
            }
        }
        targets.sort(Comparator
            .comparingDouble((BlockPos pos) -> client.player.getEyePosition(0.0F).distanceToSqr(Vec3.atCenterOf(pos)))
            .thenComparingInt(pos -> pos.distManhattan(center.pos())));
        return targets;
    }

    private void collectFaceTargets(Minecraft client, TargetCenter center, int r, List<BlockPos> targets) {
        Direction face = center.face();
        Direction inward = face.getOpposite();
        for (int depth = 0; depth <= r; depth++) {
            BlockPos layerCenter = center.pos().relative(inward, depth);
            if (face.getAxis() == Direction.Axis.Y) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        addTarget(client, center.pos(), layerCenter.offset(dx, 0, dz), targets);
                    }
                }
            } else {
                for (int side = -r; side <= r; side++) {
                    for (int y = -r; y <= r; y++) {
                        BlockPos pos = face.getAxis() == Direction.Axis.X
                            ? layerCenter.offset(0, y, side)
                            : layerCenter.offset(side, y, 0);
                        addTarget(client, center.pos(), pos, targets);
                    }
                }
            }
        }
    }

    private void addTarget(Minecraft client, BlockPos center, BlockPos pos, List<BlockPos> targets) {
        if (!insideReach(client, pos)) {
            return;
        }
        BlockState state = client.level.getBlockState(pos);
        if (isTarget(client, pos, state)) {
            targets.add(pos.immutable());
        }
    }

    private boolean isTarget(Minecraft client, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK || state.getDestroySpeed(client.level, pos) < 0.0F) {
            return false;
        }
        if ("SelectZone".equals(target.choiceValue())) {
            return SelectedZoneRegistry.contains(client.level.dimension(), pos);
        }
        if (!onlyXRay.boolValue()) {
            return true;
        }
        Block block = state.getBlock();
        return switch (xraySet.choiceValue()) {
            case "Custom+Ore" -> XRayTargetRegistry.contains(block) || isOreLike(state);
            case "OreLike" -> isOreLike(state);
            default -> XRayTargetRegistry.contains(block);
        };
    }

    private boolean isOreLike(BlockState state) {
        Block block = state.getBlock();
        if (state.is(Tags.Blocks.ORES)
            || state.is(BlockTags.COAL_ORES)
            || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.DIAMOND_ORES)
            || state.is(BlockTags.EMERALD_ORES)
            || state.is(BlockTags.GOLD_ORES)
            || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.LAPIS_ORES)
            || state.is(BlockTags.REDSTONE_ORES)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();
        return path.contains("ore") || path.contains("debris") || path.contains("crystal") || path.contains("gem");
    }

    private boolean insideReach(Minecraft client, BlockPos pos) {
        double reachValue = reach.doubleValue() + 0.25D;
        return client.player.getEyePosition(0.0F).distanceToSqr(Vec3.atCenterOf(pos)) <= reachValue * reachValue;
    }

    private Direction faceFromPlayer(Minecraft client, BlockPos pos) {
        BlockPos player = client.player.blockPosition();
        int dx = pos.getX() - player.getX();
        int dy = pos.getY() - player.getY();
        int dz = pos.getZ() - player.getZ();
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy >= 0 ? Direction.DOWN : Direction.UP;
        }
        if (ax >= az) {
            return dx >= 0 ? Direction.WEST : Direction.EAST;
        }
        return dz >= 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private GroundMineTrace applyGroundMine(Minecraft client, BlockPos samplePos) {
        boolean previousOnGround = client.player.onGround();
        BlockState state = client.level.getBlockState(samplePos);
        float baseRaw = state.getDestroyProgress(client.player, client.level, samplePos);
        client.player.setOnGround(true);
        client.getConnection().send(new ServerboundMovePlayerPacket.Pos(client.player.getX(), client.player.getY(), client.player.getZ(), true));
        client.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true));
        groundSpoofPackets += 2;
        float baseSpoof = state.getDestroyProgress(client.player, client.level, samplePos);
        return new GroundMineTrace(previousOnGround, true, baseRaw, baseSpoof, samplePos);
    }

    private void restoreGroundMine(Minecraft client, GroundMineTrace trace) {
        if (trace != null && trace.spoofed) {
            client.player.setOnGround(trace.previousOnGround);
        }
    }

    private String groundMineDetails(GroundMineTrace trace) {
        if (trace == null) {
            return ", groundSpoof=false, spoofPackets=" + groundSpoofPackets;
        }
        return ", groundSpoof=" + trace.spoofed
            + ", prevGround=" + trace.previousOnGround
            + ", baseRaw=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseRaw)
            + ", baseSpoof=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseSpoof)
            + ", sample=" + trace.samplePos.toShortString()
            + ", spoofPackets=" + groundSpoofPackets;
    }

    private void log(BlockPos center, int found, int sent, GroundMineTrace trace) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("VanilaNuker pulse: center=" + center.toShortString()
            + ", found=" + found
            + ", sent=" + sent
            + ", radius=" + radius.displayValue()
            + ", shape=" + shape.choiceValue()
            + ", reach=" + reach.displayValue()
            + ", onlyXRay=" + onlyXRay.boolValue()
            + ", target=" + target.choiceValue()
            + ", xraySet=" + xraySet.choiceValue()
            + ", onView=" + onView.boolValue()
            + ", delay=" + delay.displayValue()
            + groundMineDetails(trace)
            + ", rr=" + roundRobinIndex);
    }

    @Override
    public String description() {
        return "Sends vanilla block dig packets around the looked-at block or player, optionally limited to XRay-style targets.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "VanilaNuker lastTargets=" + lastTargets
            + "/" + lastFound
            + " radius=" + radius.displayValue()
            + " shape=" + shape.choiceValue()
            + " reach=" + reach.displayValue()
            + " onlyXRay=" + onlyXRay.displayValue()
            + " target=" + target.choiceValue()
            + " xraySet=" + xraySet.choiceValue()
            + " groundPackets=" + groundSpoofPackets
            + (lastGroundTrace == null ? "" : " base="
                + String.format(java.util.Locale.ROOT, "%.4f", lastGroundTrace.baseRaw)
                + "->" + String.format(java.util.Locale.ROOT, "%.4f", lastGroundTrace.baseSpoof));
    }

    private record TargetCenter(BlockPos pos, Direction face) {
    }

    private static final class GroundMineTrace {
        private final boolean previousOnGround;
        private final boolean spoofed;
        private final float baseRaw;
        private final float baseSpoof;
        private final BlockPos samplePos;

        private GroundMineTrace(boolean previousOnGround, boolean spoofed, float baseRaw, float baseSpoof, BlockPos samplePos) {
            this.previousOnGround = previousOnGround;
            this.spoofed = spoofed;
            this.baseRaw = baseRaw;
            this.baseSpoof = baseSpoof;
            this.samplePos = samplePos;
        }
    }
}
