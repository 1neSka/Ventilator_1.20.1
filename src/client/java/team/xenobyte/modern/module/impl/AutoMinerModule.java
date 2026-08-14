package team.xenobyte.modern.module.impl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
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

public class AutoMinerModule extends XenoModule {
    private static final String CORRECTION_HANDLER_NAME = "xenobyte_autominer_corrections";
    private static final double CENTER_Y_OFFSET = 0.08D;
    private static final double ARRIVE_DISTANCE = 0.10D;
    private static final double PICKUP_SCAN_RADIUS = 12.0D;
    private static final double PICKUP_ASSOCIATION_RADIUS = 9.0D;
    private static final double PICKUP_REACH_DISTANCE = 0.65D;
    private static final int PICKUP_SCAN_TICKS = 140;
    private static final int RECENT_BREAK_POS_LIMIT = 192;
    private static final int DIRECT_PATH_MAX_NODES = 12_000;
    private static final int DIRECT_PATH_MARGIN = 8;
    private static final int DIRECT_REPATH_INTERVAL_TICKS = 16;
    private static final double DIRECT_PATH_ARRIVE_DISTANCE = 0.72D;
    private static final Direction[] DIRECT_PATH_DIRECTIONS = {
        Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final int SMART_RECOVERY_COOLDOWN_TICKS = 24;
    private static final int SMART_REPEAT_BLOCK_TICKS = 80;
    private static final int MOVEMENT_CEILING_RANGE = 5;
    private static final int SEARCH_BACKOFF_TICKS = 20;
    private static final int TARGET_SCAN_BUDGET_PER_TICK = 2_048;
    private static final int DIRECT_TARGET_SCAN_BUDGET_PER_TICK = 32_768;
    private static final int DIRECT_CANDIDATE_CACHE_LIMIT = 16_384;
    private static final int DIRECT_RETARGET_SCAN_BUDGET = 1_024;
    private static final int DIRECT_RETARGET_RADIUS_CAP = 12;
    private static final int DIRECT_RETARGET_INTERVAL = 2;
    private static final double DIRECT_RETARGET_SCORE_MARGIN = 0.25D;
    private static final int DIRECT_RECOVERY_ATTEMPTS = 5;
    private static final int DIRECT_BREAK_ABANDON_ATTEMPTS = 18;
    private static final int DIRECT_STALL_ABANDON_TICKS = 24;
    private static final int DIRECT_TARGET_BACKOFF_TICKS = 120;
    private static final boolean GROUND_MINE_ALWAYS_ON = true;
    private static final double FAST_TUNNEL_BASE_SPEED = 4.0D;
    private static final double TUNNEL_CELL_ACCEPT_DISTANCE = 1.36D;
    private static final double WATER_MOTION_CAP = 0.62D;
    private static final double LAVA_MOTION_CAP = 0.38D;
    private static final double LAVA_VERTICAL_CAP = 0.20D;
    private static final double TUNNEL_CLOSE_SPEED_DISTANCE = 1.75D;
    private static final double TUNNEL_CLOSE_SPEED_MIN_CAP = 0.55D;
    private static final double TUNNEL_CLOSE_SPEED_MAX_CAP = 1.65D;
    private static final double TUNNEL_PROGRESS_EPSILON = 0.06D;
    private static final int TUNNEL_STALL_TICKS = 14;
    private static final double TUNNEL_HARD_ROLLBACK_DELTA = 1.20D;
    private static final int TUNNEL_RECOVERY_PAUSE_TICKS = 5;
    private static final int TARGET_RECOVERY_LIMIT = 3;
    private static final int TARGET_BACKOFF_TICKS = 240;
    private static final double ADAPTIVE_MIN_SCALE = 0.20D;
    private static final double ADAPTIVE_RECOVERY_STEP = 0.08D;
    private static final int STABLE_WAYPOINTS_PER_RECOVERY = 8;
    private static final int EMERGENCY_SPEED_TICKS = 40;
    private static final int DIRECT_EMERGENCY_SPEED_TICKS = 8;
    private static final double EMERGENCY_SPEED_CAP = 1.0D;
    private static final double TUNNEL_ROUTE_PROGRESS_EPSILON = 0.18D;
    private static final int TUNNEL_ROUTE_STALL_TICKS = 12;
    private static final int ZONE_SCAN_BUDGET_PER_TICK = 16_384;
    private static final int ZONE_BURST_CAP = 96;
    private static final int ZONE_BREAK_ABANDON_ATTEMPTS = 60;

    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 16.0D, 4.0D, 96.0D, 4.0D)
        .describe("Search radius around the player."));
    private final ModuleSetting minY = setting("MinY", ModuleSetting.number("MinY", -59.0D, -64.0D, 320.0D, 1.0D)
        .describe("Lowest Y coordinate AutoMiner may target or tunnel through."));
    private final ModuleSetting speed = setting("Speed", ModuleSetting.number("Speed", 0.8D, 0.1D, 10.0D, 0.1D)
        .describe("Motion-fly speed used while moving between tunnel cells."));
    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 1.0D, 0.0D, 10.0D, 1.0D)
        .describe("Ticks between dig pulses."));
    private final ModuleSetting onlyVisible = setting("OnlyVisible", ModuleSetting.bool("OnlyVisible", false)
        .describe("Only applies in direct flight mode. Tunnel mode can target hidden ore."));
    private final ModuleSetting tunnelMode = setting("TunnelMode", ModuleSetting.bool("TunnelMode", true)
        .describe("Digs an axis-aligned tunnel to an adjacent cell near the target."));
    private final ModuleSetting breakPerTick = setting("BreakPerTick", ModuleSetting.number("BreakPerTick", 16.0D, 1.0D, 96.0D, 1.0D)
        .describe("Maximum vanilla dig packets sent per pulse."));
    private final ModuleSetting shadowNuker = setting("ShadowNuker", ModuleSetting.bool("ShadowNuker", true)
        .describe("When AutoMiner reaches a target, also mines matching target blocks around it like a scoped VanilaNuker."));
    private final ModuleSetting nukeRadius = setting("NukeRadius", ModuleSetting.number("NukeRadius", 6.0D, 1.0D, 6.0D, 1.0D)
        .describe("Scoped shadow nuker radius around the current AutoMiner target."));
    private final ModuleSetting nukeReach = setting("NukeReach", ModuleSetting.number("NukeReach", 8.0D, 3.0D, 16.0D, 0.5D)
        .describe("Eye-to-block reach used by direct ShadowNuker mining."));
    private final ModuleSetting nukeLimit = setting("NukeLimit", ModuleSetting.number("NukeLimit", 64.0D, 1.0D, 256.0D, 1.0D)
        .describe("Maximum target-block dig packets sent by ShadowNuker per target pulse."));
    private final ModuleSetting ensurePickup = setting("EnsurePickup", ModuleSetting.bool("EnsurePickup", true)
        .describe("Temporarily flies through drops near recently mined blocks before continuing."));
    private final ModuleSetting tunnelAhead = setting("TunnelAhead", ModuleSetting.number("TunnelAhead", 4.0D, 1.0D, 16.0D, 1.0D)
        .describe("How many future tunnel cells are pre-cleared per pulse."));
    private final ModuleSetting antiStuck = setting("AntiStuck", ModuleSetting.bool("AntiStuck", true)
        .describe("Adds randomized recovery motion when direct flight stops making progress."));
    private final ModuleSetting smartDirect = setting("SmartDirect", ModuleSetting.bool("SmartDirect", true)
        .describe("In direct mode, uses Ceiling-style vertical bypasses on the current X/Z when stuck."));
    private final ModuleSetting ceilingRange = setting("CeilRange", ModuleSetting.number("CeilRange", 5.0D, 2.0D, 5.0D, 1.0D)
        .describe("Vertical range used by SmartDirect recovery in no-tunnel mode."));
    private final ModuleSetting jitter = setting("Jitter", ModuleSetting.number("Jitter", 0.6D, 0.1D, 2.0D, 0.1D)
        .describe("Random recovery motion strength for direct flight mode."));
    private final ModuleSetting directRange = setting("DirectRange", ModuleSetting.number("DirectRange", 3.6D, 1.5D, 6.0D, 0.25D)
        .describe("Eye-to-target distance at which direct flight starts sending dig packets."));
    private final ModuleSetting retarget = setting("Retarget", ModuleSetting.bool("Retarget", true)
        .describe("Lets direct flight switch to a significantly closer target while moving."));
    private final ModuleSetting axisOrder = setting("AxisOrder", ModuleSetting.choice("AxisOrder", 0, "XZY", "XYZ", "YXZ", "ZYX")
        .describe("Order of axis legs used to reach the target-adjacent cell."));
    private final ModuleSetting targetMode = setting("Target", ModuleSetting.choice("Target", 0, "XRay+Ore", "XRayOnly", "OreLike", "SelectZone")
        .describe("Which blocks AutoMiner may select as mining targets."));

    private final Map<Block, Boolean> oreLikeCache = new ConcurrentHashMap<>();
    private final Set<BlockPos> recentBreakPositions = new LinkedHashSet<>();
    private final Set<BlockPos> directCandidateCache = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> targetBackoffUntil = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> targetRecoveryFailures = new ConcurrentHashMap<>();
    private final AtomicInteger pendingServerCorrections = new AtomicInteger();

    private BlockPos target;
    private BlockPos goalCell;
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private int cooldownTicks;
    private int searchBackoffTicks;
    private int stuckTicks;
    private int directStuckTicks;
    private int jitterTicks;
    private int directRetargetTicks;
    private int directBreakAttempts;
    private int lastBroken;
    private int lastNukeTargets;
    private int pickupScanTicks;
    private int lastPickupItems;
    private int smartRecoveryCooldown;
    private int smartRepeatBlockTicks;
    private int lastSmartY = Integer.MIN_VALUE;
    private int lastSmartBypasses;
    private int tunnelWaypointNoProgressTicks;
    private boolean lastInWater;
    private boolean lastInLava;
    private double previousMoveDistance = Double.MAX_VALUE;
    private double previousDirectDistance = Double.MAX_VALUE;
    private double bestTunnelWaypointDistance = Double.MAX_VALUE;
    private Vec3 jitterVector = Vec3.ZERO;
    private BlockPos lastTunnelWaypoint;
    private BlockPos directAttemptTarget;
    private ItemEntity pickupTarget;
    private boolean pickupActive;
    private boolean hadMovementSnapshot;
    private boolean previousNoGravity;
    private boolean previousNoPhysics;
    private long lastLogNanos;
    private long lastGroundMineLogNanos;
    private int groundMineSpoofPackets;
    private int moduleTicks;
    private int tunnelRecoveryPauseTicks;
    private int stableWaypointTicks;
    private int tunnelRecoveries;
    private int abandonedTargets;
    private long serverCorrectionPackets;
    private double adaptiveMovementScale = 1.0D;
    private Channel correctionChannel;
    private int emergencySpeedTicks;
    private int emergencySpeedEvents;
    private BlockPos tunnelRouteTarget;
    private double bestTunnelTargetDistance = Double.MAX_VALUE;
    private int tunnelRouteNoProgressTicks;
    private int tunnelRouteStalls;
    private long zonePlanVersion = -1L;
    private List<BlockPos> zoneTargets = new ArrayList<>();
    private final Set<BlockPos> zoneRejectedTargets = new LinkedHashSet<>();
    private ZoneScanCursor zoneScanCursor;
    private int zoneScanChecked;
    private int zoneFluidSkipped;
    private int lastZoneBurst;
    private int zoneEmptyRescanTicks;
    private BlockPos zoneAttemptTarget;
    private int zoneBreakAttempts;
    private ResourceLocation zonePlanDimension;
    private TargetSearchState targetSearch;
    private TargetSearchState directRetargetSearch;
    private List<BlockPos> directPath = List.of();
    private BlockPos directPathTarget;
    private int directPathIndex;
    private int directRepathTicks;
    private int directPathPlans;
    private int directPathFailures;
    private int shadowRoundRobinIndex;
    private ResourceLocation directCacheDimension;
    private String directCacheMode = "";
    private int directCacheHits;
    private int directCacheAdds;

    public AutoMinerModule() {
        super("AutoMiner", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        snapshotMovement(client);
        moduleTicks = 0;
        tunnelRecoveries = 0;
        abandonedTargets = 0;
        serverCorrectionPackets = 0L;
        adaptiveMovementScale = 1.0D;
        emergencySpeedTicks = 0;
        emergencySpeedEvents = 0;
        tunnelRouteStalls = 0;
        directPathPlans = 0;
        directPathFailures = 0;
        shadowRoundRobinIndex = 0;
        clearDirectCandidateCache();
        directCacheHits = 0;
        directCacheAdds = 0;
        resetTunnelRouteProgress();
        resetZonePlan();
        targetBackoffUntil.clear();
        targetRecoveryFailures.clear();
        pendingServerCorrections.set(0);
        installCorrectionMonitor(client);
        resetTargetState();
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null && client.player != null && hadMovementSnapshot) {
            client.player.setNoGravity(previousNoGravity);
            client.player.noPhysics = previousNoPhysics;
            client.player.setDeltaMovement(Vec3.ZERO);
        }
        hadMovementSnapshot = false;
        removeCorrectionMonitor();
        clearPickupState();
        resetZonePlan();
        resetTargetState();
        clearDirectCandidateCache();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
            return;
        }
        if (!hadMovementSnapshot) {
            snapshotMovement(client);
        }

        moduleTicks++;
        if (emergencySpeedTicks > 0) {
            emergencySpeedTicks--;
        }
        if (correctionChannel == null || !correctionChannel.isActive()
            || correctionChannel.pipeline().get(CORRECTION_HANDLER_NAME) == null) {
            installCorrectionMonitor(client);
        }
        keepFlightState(client);
        if (consumeServerCorrections(client)) {
            return;
        }
        if (tunnelRecoveryPauseTicks > 0) {
            tunnelRecoveryPauseTicks--;
            client.player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (smartRecoveryCooldown > 0) {
            smartRecoveryCooldown--;
        }
        if (smartRepeatBlockTicks > 0) {
            smartRepeatBlockTicks--;
        }

        if (ensurePickup.boolValue() && tickEnsurePickup(client)) {
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (!validateTarget(client)) {
            if (target != null) {
                discardInvalidTarget(client);
            }
            acquireTarget(client);
            if (target == null) {
                client.player.setDeltaMovement(Vec3.ZERO);
                return;
            }
        }

        if (isZoneMode()) {
            tickSelectedZones(client);
        } else if (tunnelMode.boolValue()) {
            tickTunnel(client);
        } else {
            tickDirectFlight(client);
        }
    }

    private void discardInvalidTarget(Minecraft client) {
        BlockPos invalid = target.immutable();
        BlockState state = client.level.getBlockState(invalid);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        targetRecoveryFailures.remove(invalid);
        directCandidateCache.remove(invalid);
        target = null;
        goalCell = null;
        path = List.of();
        pathIndex = 0;
        targetSearch = null;
        directRetargetSearch = null;
        invalidateDirectPath();
        previousMoveDistance = Double.MAX_VALUE;
        previousDirectDistance = Double.MAX_VALUE;
        stuckTicks = 0;
        directStuckTicks = 0;
        clearTunnelWaypointProgress();
        resetTunnelRouteProgress();

        BootstrapLog.info("AutoMiner invalid target discarded: target=" + invalid.toShortString()
            + ", block=" + (blockId == null ? "unknown" : blockId)
            + ", fluid=" + !state.getFluidState().isEmpty()
            + ", next=fresh-search");
    }

    private void tickTunnel(Minecraft client) {
        if (path.isEmpty() || goalCell == null) {
            rebuildPath(client);
        }
        if (path.isEmpty() && goalCell == null) {
            return;
        }

        BlockPos currentCell = client.player.blockPosition();
        if (goalCell != null && currentCell.distManhattan(expectedCurrentCell(client)) > 4) {
            rebuildPath(client);
        }

        if (pathIndex < path.size()) {
            BlockPos expectedCell = expectedCurrentCell(client);
            BlockPos nextCell = path.get(pathIndex);
            Direction direction = directionBetween(expectedCell, nextCell);
            if (direction == null) {
                rebuildPath(client);
                return;
            }

            int broken = clearTunnelStep(client, currentCell, expectedCell, nextCell, direction);
            if (broken > 0) {
                client.player.setDeltaMovement(Vec3.ZERO);
                cooldownTicks = delay.intValue();
                lastBroken = broken;
                return;
            }

            if (observeTunnelRouteStall(client)) {
                reduceAdaptiveSpeed(0.55D);
                recoverTunnel(client, nextCell, "route oscillation", true);
                return;
            }

            int moveIndex = fastTunnelMoveIndex(client, pathIndex);
            BlockPos moveCell = path.get(moveIndex);
            if (moveToCell(client, moveCell)) {
                pathIndex = moveIndex + 1;
                previousMoveDistance = Double.MAX_VALUE;
                stuckTicks = 0;
            }
            return;
        }

        breakTarget(client);
    }

    private void tickSelectedZones(Minecraft client) {
        if (!ensureZonePlan(client)) {
            client.player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (target == null || !isTargetBlock(client, target)) {
            target = closestZoneTarget(client);
            if (target == null) {
                resetZoneBreakAttempts();
                client.player.setDeltaMovement(Vec3.ZERO);
                lastZoneBurst = 0;
                return;
            }
        }
        if (!target.equals(zoneAttemptTarget)) {
            zoneAttemptTarget = target.immutable();
            zoneBreakAttempts = 0;
        }

        Vec3 targetCenter = Vec3.atCenterOf(target);
        double eyeDistance = client.player.getEyePosition(0.0F).distanceTo(targetCenter);
        if (eyeDistance > nukeReach.doubleValue()) {
            moveToward(client, targetCenter);
            return;
        }

        lookAt(client, targetCenter);
        List<BlockPos> burst = collectShadowNukeTargets(client, target);
        int maxPackets = Math.min(ZONE_BURST_CAP, Math.min(breakPerTick.intValue(), nukeLimit.intValue()));
        lastZoneBurst = sendDigPulseAutoFace(client, burst, "zoneNuker", maxPackets);
        lastBroken = lastZoneBurst;
        lastNukeTargets = burst.size();
        cooldownTicks = Math.max(0, delay.intValue());
        trackZoneBreakAttempts(client);
        if (lastZoneBurst > 0) {
            log("zone", "target=" + target.toShortString()
                + ", burst=" + lastZoneBurst
                + ", nearby=" + burst.size()
                + ", planned=" + zoneTargets.size()
                + ", zones=" + SelectedZoneRegistry.size());
        }
    }

    private int clearTunnelStep(Minecraft client, BlockPos actualCell, BlockPos expectedCell, BlockPos nextCell, Direction direction) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        addBodySpace(positions, actualCell);
        addBodySpace(positions, expectedCell);
        addBodySpace(positions, nextCell);
        int endExclusive = Math.min(path.size(), pathIndex + Math.max(1, tunnelAhead.intValue()));
        for (int i = pathIndex; i < endExclusive; i++) {
            addBodySpace(positions, path.get(i));
        }

        List<BlockPos> toBreak = positions.stream()
            .filter(pos -> isBreakablePassageBlock(client, pos))
            .sorted(Comparator.comparingInt(pos -> pos.distManhattan(actualCell)))
            .toList();
        return sendDigPulse(client, toBreak, direction, "tunnel");
    }

    private int fastTunnelMoveIndex(Minecraft client, int startIndex) {
        if (path.isEmpty() || startIndex >= path.size() - 1) {
            return startIndex;
        }
        if (client.player.isInWaterOrBubble() || client.player.isInLava()
            || adaptiveMovementScale < 0.99D || emergencySpeedTicks > 0) {
            return startIndex;
        }
        double configuredSpeed = Math.max(0.1D, speed.doubleValue());
        if (configuredSpeed <= FAST_TUNNEL_BASE_SPEED) {
            return startIndex;
        }
        int preparedCells = Math.max(1, tunnelAhead.intValue()) - 1;
        int speedCells = 1 + (int)Math.floor((configuredSpeed - FAST_TUNNEL_BASE_SPEED) * 0.85D);
        int maxSkip = Math.min(Math.max(0, preparedCells), Math.max(0, speedCells));
        Direction direction = directionBetween(path.get(startIndex), path.get(startIndex + 1));
        int end = Math.min(path.size() - 1, startIndex + maxSkip);
        int best = startIndex;
        for (int i = startIndex + 1; i <= end; i++) {
            Direction nextDirection = directionBetween(path.get(i - 1), path.get(i));
            if (direction == null || nextDirection != direction) {
                break;
            }
            best = i;
        }
        return best;
    }

    private boolean moveToCell(Minecraft client, BlockPos cell) {
        Vec3 destination = cellCenter(cell);
        Vec3 current = client.player.position();
        Vec3 delta = destination.subtract(current);
        double distance = delta.length();
        lookAt(client, destination);

        if (isTunnelCellAccepted(client, cell, distance)) {
            client.player.setDeltaMovement(Vec3.ZERO);
            noteStableWaypoint();
            BootstrapLog.info("AutoMiner tunnel waypoint accepted: cell=" + cell.toShortString()
                + ", dist=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                + ", playerCell=" + client.player.blockPosition().toShortString()
                + ", adaptive=" + formatScale()
                + ", target=" + targetText());
            clearTunnelWaypointProgress();
            return true;
        }

        Vec3 motion = stabilizeFluidMotion(client, tunnelMotion(client, delta, distance), distance);
        boolean waypointNoProgress = updateTunnelWaypointProgress(cell, distance);
        boolean hardRollback = bestTunnelWaypointDistance < Double.MAX_VALUE
            && distance > bestTunnelWaypointDistance + TUNNEL_HARD_ROLLBACK_DELTA
            && previousMoveDistance < Double.MAX_VALUE
            && distance > previousMoveDistance + 0.75D;
        if (hardRollback) {
            reduceAdaptiveSpeed(0.65D);
            recoverTunnel(client, cell, "distance rollback", true);
            return false;
        }
        if (distance >= previousMoveDistance - 0.015D || waypointNoProgress) {
            stuckTicks++;
            if (stuckTicks >= 5 && tryMovementVerticalBypass(client, destination, "tunnel ceiling")) {
                stuckTicks = 0;
                previousMoveDistance = Double.MAX_VALUE;
                jitterTicks = 0;
                jitterVector = Vec3.ZERO;
                return false;
            }
            if (tunnelWaypointNoProgressTicks >= TUNNEL_STALL_TICKS) {
                reduceAdaptiveSpeed(0.72D);
                recoverTunnel(client, cell, "waypoint stalled", true);
                return false;
            }
        } else {
            stuckTicks = 0;
            jitterTicks = 0;
            jitterVector = Vec3.ZERO;
        }
        previousMoveDistance = distance;
        client.player.setDeltaMovement(motion);
        client.player.setOnGround(false);
        client.player.fallDistance = 0.0F;
        log("move", "next=" + cell.toShortString()
            + ", dist=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
            + ", motion=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", motion.x, motion.y, motion.z));
        return false;
    }

    private boolean isTunnelCellAccepted(Minecraft client, BlockPos cell, double distance) {
        if (distance <= ARRIVE_DISTANCE) {
            return true;
        }
        return client.player.blockPosition().equals(cell) && distance <= tunnelCellAcceptDistance(client);
    }

    private double tunnelCellAcceptDistance(Minecraft client) {
        return TUNNEL_CELL_ACCEPT_DISTANCE;
    }

    private Vec3 tunnelMotion(Minecraft client, Vec3 delta, double distance) {
        double configuredSpeed = effectiveMovementSpeed(client);
        double amount = Math.min(configuredSpeed, distance);
        if (distance <= TUNNEL_CLOSE_SPEED_DISTANCE) {
            double closeCap = Math.max(TUNNEL_CLOSE_SPEED_MIN_CAP,
                Math.min(TUNNEL_CLOSE_SPEED_MAX_CAP, configuredSpeed * 0.85D));
            amount = Math.min(amount, closeCap);
        }
        return delta.normalize().scale(amount);
    }

    private boolean updateTunnelWaypointProgress(BlockPos cell, double distance) {
        if (!cell.equals(lastTunnelWaypoint)) {
            lastTunnelWaypoint = cell.immutable();
            bestTunnelWaypointDistance = distance;
            tunnelWaypointNoProgressTicks = 0;
            return false;
        }
        if (distance < bestTunnelWaypointDistance - TUNNEL_PROGRESS_EPSILON) {
            bestTunnelWaypointDistance = distance;
            tunnelWaypointNoProgressTicks = 0;
            return false;
        }
        tunnelWaypointNoProgressTicks++;
        return tunnelWaypointNoProgressTicks >= TUNNEL_STALL_TICKS;
    }

    private void clearTunnelWaypointProgress() {
        lastTunnelWaypoint = null;
        bestTunnelWaypointDistance = Double.MAX_VALUE;
        tunnelWaypointNoProgressTicks = 0;
    }

    private boolean observeTunnelRouteStall(Minecraft client) {
        if (target == null) {
            resetTunnelRouteProgress();
            return false;
        }

        if (!target.equals(tunnelRouteTarget)) {
            tunnelRouteTarget = target.immutable();
            bestTunnelTargetDistance = client.player.position().distanceTo(Vec3.atCenterOf(target));
            tunnelRouteNoProgressTicks = 0;
            return false;
        }

        double distance = client.player.position().distanceTo(Vec3.atCenterOf(target));
        if (distance < bestTunnelTargetDistance - TUNNEL_ROUTE_PROGRESS_EPSILON) {
            bestTunnelTargetDistance = distance;
            tunnelRouteNoProgressTicks = 0;
            return false;
        }

        tunnelRouteNoProgressTicks++;
        if (tunnelRouteNoProgressTicks < TUNNEL_ROUTE_STALL_TICKS) {
            return false;
        }

        tunnelRouteStalls++;
        BootstrapLog.info("AutoMiner route oscillation detected: target=" + target.toShortString()
            + ", playerCell=" + client.player.blockPosition().toShortString()
            + ", bestDistance=" + String.format(java.util.Locale.ROOT, "%.2f", bestTunnelTargetDistance)
            + ", currentDistance=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
            + ", movementTicks=" + tunnelRouteNoProgressTicks
            + ", configuredSpeed=" + speed.displayValue()
            + ", stalls=" + tunnelRouteStalls);
        return true;
    }

    private void resetTunnelRouteProgress() {
        tunnelRouteTarget = null;
        bestTunnelTargetDistance = Double.MAX_VALUE;
        tunnelRouteNoProgressTicks = 0;
    }

    private void noteStableWaypoint() {
        stableWaypointTicks++;
        if (stableWaypointTicks < STABLE_WAYPOINTS_PER_RECOVERY || adaptiveMovementScale >= 0.999D) {
            return;
        }
        stableWaypointTicks = 0;
        adaptiveMovementScale = Math.min(1.0D, adaptiveMovementScale + ADAPTIVE_RECOVERY_STEP);
        BootstrapLog.info("AutoMiner adaptive speed recovered: scale=" + formatScale());
    }

    private void reduceAdaptiveSpeed(double factor) {
        adaptiveMovementScale = Math.max(ADAPTIVE_MIN_SCALE, adaptiveMovementScale * factor);
        stableWaypointTicks = 0;
    }

    private double effectiveMovementSpeed(Minecraft client) {
        double effective = Math.max(0.1D, speed.doubleValue()) * adaptiveMovementScale;
        if (emergencySpeedTicks > 0) {
            effective = Math.min(effective, EMERGENCY_SPEED_CAP);
        }
        if (client != null && client.player != null) {
            if (client.player.isInLava()) {
                return Math.min(effective, LAVA_MOTION_CAP);
            }
            if (client.player.isInWaterOrBubble()) {
                return Math.min(effective, WATER_MOTION_CAP);
            }
        }
        return Math.max(0.08D, effective);
    }

    private String formatScale() {
        return String.format(java.util.Locale.ROOT, "%.2f", adaptiveMovementScale);
    }

    private void recoverTunnel(Minecraft client, BlockPos waypoint, String reason, boolean countTargetFailure) {
        client.player.setDeltaMovement(Vec3.ZERO);
        armEmergencySpeed(reason);
        tunnelRecoveries++;
        tunnelRecoveryPauseTicks = TUNNEL_RECOVERY_PAUSE_TICKS;

        BlockPos failedTarget = target == null ? null : target.immutable();
        int failures = 0;
        if (countTargetFailure && failedTarget != null) {
            failures = targetRecoveryFailures.merge(failedTarget, 1, Integer::sum);
        }

        path = List.of();
        pathIndex = 0;
        goalCell = null;
        previousMoveDistance = Double.MAX_VALUE;
        stuckTicks = 0;
        jitterTicks = 0;
        jitterVector = Vec3.ZERO;
        clearTunnelWaypointProgress();
        resetTunnelRouteProgress();

        boolean abandoned = failures >= TARGET_RECOVERY_LIMIT && failedTarget != null;
        if (abandoned) {
            targetBackoffUntil.put(failedTarget, moduleTicks + TARGET_BACKOFF_TICKS);
            targetRecoveryFailures.remove(failedTarget);
            target = null;
            abandonedTargets++;
        }

        BootstrapLog.info("AutoMiner controlled recovery: reason=" + reason
            + ", waypoint=" + (waypoint == null ? "none" : waypoint.toShortString())
            + ", playerCell=" + client.player.blockPosition().toShortString()
            + ", failedTarget=" + (failedTarget == null ? "none" : failedTarget.toShortString())
            + ", failures=" + failures
            + ", abandoned=" + abandoned
            + ", pause=" + tunnelRecoveryPauseTicks
            + ", adaptive=" + formatScale()
            + ", recoveries=" + tunnelRecoveries);
    }

    private boolean consumeServerCorrections(Minecraft client) {
        int corrections = pendingServerCorrections.getAndSet(0);
        if (corrections <= 0) {
            return false;
        }
        serverCorrectionPackets += corrections;
        armEmergencySpeed("server position correction");
        if (target == null || (!tunnelMode.boolValue() && !isZoneMode())) {
            if (target != null && !tunnelMode.boolValue()) {
                invalidateDirectPath();
            }
            BootstrapLog.info("AutoMiner observed server position correction: count=" + corrections
                + ", activeTarget=" + targetText()
                + ", tunnel=" + tunnelMode.boolValue());
            return false;
        }
        reduceAdaptiveSpeed(0.50D);
        recoverTunnel(client, lastTunnelWaypoint, "server position correction x" + corrections, true);
        return true;
    }

    private void tickDirectFlight(Minecraft client) {
        if (target == null) {
            return;
        }
        maybeRetargetDirect(client);
        if (target == null) {
            return;
        }
        if (onlyVisible.boolValue() && !canSeeBlock(client, target)) {
            resetTargetState();
            return;
        }
        Vec3 targetCenter = Vec3.atCenterOf(target);
        double eyeDistance = client.player.getEyePosition(0.0F).distanceTo(targetCenter);
        if (eyeDistance <= directMiningReach()) {
            int sent = breakTarget(client);
            if (target != null && sent > 0 && isTargetBlock(client, target)) {
                trackDirectBreakAttempt();
                if (directBreakAttempts >= DIRECT_BREAK_ABANDON_ATTEMPTS) {
                    abandonDirectTarget(client, "repeated dig pulses");
                } else {
                    client.player.setDeltaMovement(Vec3.ZERO);
                }
            }
            return;
        }

        boolean pathBlocked = directPathBlocked(client, targetCenter);
        if (pathBlocked && smartDirect.boolValue() && tickDirectPath(client)) {
            return;
        }
        if (shouldAttemptSmartBypass(client, targetCenter, eyeDistance, pathBlocked)
            && trySmartDirectBypass(client, targetCenter, pathBlocked)) {
            return;
        }

        resetDirectBreakAttempts();
        if (!pathBlocked) {
            invalidateDirectPath();
        }
        moveToward(client, targetCenter);
        if (directStuckTicks >= DIRECT_STALL_ABANDON_TICKS) {
            abandonDirectTarget(client, "movement made no progress");
        }
    }

    private void maybeRetargetDirect(Minecraft client) {
        if (!retarget.boolValue() || tunnelMode.boolValue()) {
            directRetargetSearch = null;
            return;
        }
        if (directRetargetSearch == null && directRetargetTicks > 0) {
            directRetargetTicks--;
            return;
        }
        int localRadius = Math.min(radius.intValue(), DIRECT_RETARGET_RADIUS_CAP);
        if (directRetargetSearch == null || !directRetargetSearch.matches(client, localRadius)) {
            directRetargetSearch = new TargetSearchState(client, localRadius);
        }
        TargetSearchResult result = advanceTargetSearch(client, directRetargetSearch, DIRECT_RETARGET_SCAN_BUDGET);
        if (!result.complete) {
            return;
        }
        BlockPos best = result.target;
        directRetargetSearch = null;
        directRetargetTicks = DIRECT_RETARGET_INTERVAL;
        if (best == null || best.equals(target)) {
            return;
        }
        double currentScore = targetScore(client, target);
        double bestScore = targetScore(client, best);
        if (bestScore + DIRECT_RETARGET_SCORE_MARGIN < currentScore
            || directBreakAttempts >= DIRECT_RECOVERY_ATTEMPTS * 2) {
            BootstrapLog.info("AutoMiner direct retarget: old=" + target.toShortString()
                + ", oldScore=" + String.format(java.util.Locale.ROOT, "%.2f", currentScore)
                + ", new=" + best.toShortString()
                + ", newScore=" + String.format(java.util.Locale.ROOT, "%.2f", bestScore));
            target = best;
            rebuildPath(client);
            resetDirectBreakAttempts();
        }
    }

    private void abandonDirectTarget(Minecraft client, String reason) {
        if (target == null) {
            return;
        }
        BlockPos failed = target.immutable();
        targetBackoffUntil.put(failed, moduleTicks + DIRECT_TARGET_BACKOFF_TICKS);
        abandonedTargets++;
        BootstrapLog.info("AutoMiner direct target abandoned: target=" + failed.toShortString()
            + ", reason=" + reason
            + ", stuck=" + directStuckTicks
            + ", tries=" + directBreakAttempts
            + ", backoff=" + DIRECT_TARGET_BACKOFF_TICKS
            + ", abandoned=" + abandonedTargets);
        resetTargetState();
        searchBackoffTicks = 1;
        client.player.setDeltaMovement(Vec3.ZERO);
    }

    private double directMiningReach() {
        return shadowNuker.boolValue()
            ? Math.max(directRange.doubleValue(), nukeReach.doubleValue())
            : directRange.doubleValue();
    }

    private boolean tickDirectPath(Minecraft client) {
        if (target == null) {
            invalidateDirectPath();
            return false;
        }
        if (directRepathTicks > 0) {
            directRepathTicks--;
        }
        boolean targetChanged = directPathTarget == null || !directPathTarget.equals(target);
        boolean waypointInvalid = directPathIndex < directPath.size()
            && !isDirectNavigationCell(client, directPath.get(directPathIndex));
        if (targetChanged || directPath.isEmpty() || directPathIndex >= directPath.size()
            || waypointInvalid || directRepathTicks <= 0) {
            DirectPathPlan plan = planDirectPath(client, target);
            directPathPlans++;
            directRepathTicks = DIRECT_REPATH_INTERVAL_TICKS;
            if (!plan.found || plan.path.isEmpty()) {
                directPathFailures++;
                invalidateDirectPath(false);
                log("direct path", "target=" + target.toShortString()
                    + ", found=false, explored=" + plan.explored
                    + ", failures=" + directPathFailures);
                return false;
            }
            directPath = plan.path;
            directPathTarget = target.immutable();
            directPathIndex = 0;
            BootstrapLog.info("AutoMiner direct path planned: target=" + target.toShortString()
                + ", nodes=" + directPath.size()
                + ", explored=" + plan.explored
                + ", reach=" + String.format(java.util.Locale.ROOT, "%.2f", directMiningReach())
                + ", plans=" + directPathPlans);
        }

        Vec3 playerPosition = client.player.position();
        while (directPathIndex < directPath.size()
            && playerPosition.distanceTo(cellCenter(directPath.get(directPathIndex))) <= DIRECT_PATH_ARRIVE_DISTANCE) {
            directPathIndex++;
        }
        if (directPathIndex >= directPath.size()) {
            client.player.setDeltaMovement(Vec3.ZERO);
            return true;
        }

        int moveIndex = directPathMoveIndex();
        BlockPos waypoint = directPath.get(moveIndex);
        moveToward(client, cellCenter(waypoint));
        if (client.player.position().distanceTo(cellCenter(waypoint)) <= DIRECT_PATH_ARRIVE_DISTANCE) {
            directPathIndex = moveIndex + 1;
        }
        log("direct path", "target=" + target.toShortString()
            + ", node=" + directPathIndex + "/" + directPath.size()
            + ", move=" + waypoint.toShortString()
            + ", speed=" + speed.displayValue());
        return true;
    }

    private int directPathMoveIndex() {
        int maxAdvance = Math.max(1, Math.min(8, 1 + (int)Math.floor(Math.max(0.0D, speed.doubleValue() - 1.0D) * 0.7D)));
        int end = Math.min(directPath.size() - 1, directPathIndex + maxAdvance - 1);
        if (directPathIndex >= end) {
            return directPathIndex;
        }
        Direction direction = directionBetween(directPath.get(directPathIndex), directPath.get(directPathIndex + 1));
        int best = directPathIndex;
        for (int i = directPathIndex + 1; i <= end; i++) {
            Direction next = directionBetween(directPath.get(i - 1), directPath.get(i));
            if (direction == null || next != direction) {
                break;
            }
            best = i;
        }
        return best;
    }

    private DirectPathPlan planDirectPath(Minecraft client, BlockPos targetPos) {
        BlockPos start = client.player.blockPosition().immutable();
        int minX = Math.min(start.getX(), targetPos.getX()) - DIRECT_PATH_MARGIN;
        int maxX = Math.max(start.getX(), targetPos.getX()) + DIRECT_PATH_MARGIN;
        int minYValue = Math.max(minY.intValue(), Math.min(start.getY(), targetPos.getY()) - DIRECT_PATH_MARGIN);
        int maxYValue = Math.min(client.level.getMaxBuildHeight() - 2,
            Math.max(start.getY(), targetPos.getY()) + DIRECT_PATH_MARGIN);
        int minZ = Math.min(start.getZ(), targetPos.getZ()) - DIRECT_PATH_MARGIN;
        int maxZ = Math.max(start.getZ(), targetPos.getZ()) + DIRECT_PATH_MARGIN;

        PriorityQueue<DirectPathNode> open = new PriorityQueue<>(Comparator.comparingDouble(DirectPathNode::score));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        costs.put(start, 0.0D);
        open.add(new DirectPathNode(start, directPathHeuristic(start, targetPos), 0.0D));

        int explored = 0;
        while (!open.isEmpty() && explored < DIRECT_PATH_MAX_NODES) {
            DirectPathNode node = open.poll();
            BlockPos current = node.pos;
            if (!closed.add(current)) {
                continue;
            }
            explored++;
            if (isDirectMiningCell(client, current, targetPos)) {
                return new DirectPathPlan(reconstructDirectPath(start, current, parents), explored, true);
            }

            for (Direction direction : DIRECT_PATH_DIRECTIONS) {
                BlockPos next = current.relative(direction).immutable();
                if (next.getX() < minX || next.getX() > maxX
                    || next.getY() < minYValue || next.getY() > maxYValue
                    || next.getZ() < minZ || next.getZ() > maxZ
                    || closed.contains(next)
                    || !client.level.hasChunkAt(next)
                    || !isDirectNavigationCell(client, next)) {
                    continue;
                }
                double stepCost = direction.getAxis() == Direction.Axis.Y ? 1.08D : 1.0D;
                if (!client.level.getFluidState(next).isEmpty()) {
                    stepCost += 0.35D;
                }
                double nextCost = node.cost + stepCost;
                if (nextCost + 0.0001D >= costs.getOrDefault(next, Double.MAX_VALUE)) {
                    continue;
                }
                costs.put(next, nextCost);
                parents.put(next, current);
                open.add(new DirectPathNode(next, nextCost + directPathHeuristic(next, targetPos), nextCost));
            }
        }
        return new DirectPathPlan(List.of(), explored, false);
    }

    private List<BlockPos> reconstructDirectPath(BlockPos start, BlockPos goal, Map<BlockPos, BlockPos> parents) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos cursor = goal;
        while (!cursor.equals(start)) {
            result.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) {
                return List.of();
            }
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    private double directPathHeuristic(BlockPos cell, BlockPos targetPos) {
        double remaining = Vec3.atCenterOf(cell).distanceTo(Vec3.atCenterOf(targetPos)) - directMiningReach();
        return Math.max(0.0D, remaining);
    }

    private boolean isDirectMiningCell(Minecraft client, BlockPos cell, BlockPos targetPos) {
        if (!cell.equals(client.player.blockPosition()) && !isDirectNavigationCell(client, cell)) {
            return false;
        }
        Vec3 feet = cellCenter(cell);
        Vec3 eye = feet.add(0.0D, client.player.getEyeHeight(), 0.0D);
        return eye.distanceTo(Vec3.atCenterOf(targetPos)) <= Math.max(1.5D, directMiningReach() - 0.20D);
    }

    private boolean isDirectNavigationCell(Minecraft client, BlockPos cell) {
        return insideBuildHeight(client, cell.getY())
            && isTwoPassable(client, cell)
            && !hasLavaNear(client, cell);
    }

    private void invalidateDirectPath() {
        invalidateDirectPath(true);
    }

    private void invalidateDirectPath(boolean resetTimer) {
        directPath = List.of();
        directPathTarget = null;
        directPathIndex = 0;
        if (resetTimer) {
            directRepathTicks = 0;
        }
    }

    private Vec3 directRecoveryPoint(Minecraft client, Vec3 targetCenter) {
        Vec3 eye = client.player.getEyePosition(0.0F);
        Vec3 away = eye.subtract(targetCenter);
        if (away.lengthSqr() < 0.0001D) {
            away = new Vec3(0.0D, 1.0D, 0.0D);
        } else {
            away = away.normalize();
        }
        return targetCenter.add(away.scale(1.15D));
    }

    private boolean shouldAttemptSmartBypass(Minecraft client, Vec3 targetCenter, double eyeDistance, boolean pathBlocked) {
        if (!antiStuck.boolValue() || !smartDirect.boolValue() || tunnelMode.boolValue() || smartRecoveryCooldown > 0 || target == null) {
            return false;
        }
        int playerY = client.player.blockPosition().getY();
        int targetY = target.getY();
        boolean verticalMismatch = Math.abs(targetY - playerY) >= 3;
        if (pathBlocked && verticalMismatch && directStuckTicks >= 3) {
            return true;
        }
        if (pathBlocked && directStuckTicks >= 6) {
            return true;
        }
        if (directStuckTicks >= 12) {
            return true;
        }
        return eyeDistance > directRange.doubleValue() + 1.0D
            && verticalMismatch
            && directPathBlockedFrom(client, client.player.getEyePosition(0.0F), targetCenter, target)
            && directStuckTicks >= 2;
    }

    private boolean trySmartDirectBypass(Minecraft client, Vec3 targetCenter, boolean pathBlocked) {
        int preferredDirection = target == null ? 0 : Integer.compare(target.getY(), client.player.blockPosition().getY());
        BlockPos vertical = findVerticalPocket(client, preferredDirection);
        if (vertical != null && improvesDirectRoute(client, vertical, targetCenter)) {
            smartMoveVertically(client, vertical, pathBlocked ? "vertical blocked path" : "vertical stuck");
            return true;
        }
        return false;
    }

    private BlockPos findVerticalPocket(Minecraft client, int preferredDirection) {
        int rangeValue = Math.min(MOVEMENT_CEILING_RANGE, Math.max(2, ceilingRange.intValue()));
        BlockPos base = client.player.blockPosition();
        int[] directions = preferredDirection < 0 ? new int[] {-1, 1} : new int[] {1, -1};
        if (preferredDirection == 0) {
            directions = new int[] {1, -1};
        }
        for (int direction : directions) {
            for (int distance = 1; distance <= rangeValue; distance++) {
                int y = base.getY() + direction * distance;
                if (!insideBuildHeight(client, y)) {
                    continue;
                }
                BlockPos candidate = new BlockPos(base.getX(), y, base.getZ());
                if (smartRepeatBlockTicks > 0 && y == lastSmartY) {
                    continue;
                }
                if (isTwoPassable(client, candidate) && !hasLavaNear(client, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean improvesDirectRoute(Minecraft client, BlockPos candidate, Vec3 targetCenter) {
        Vec3 current = client.player.position();
        Vec3 candidateCenter = verticalBypassPosition(client, candidate);
        double currentScore = current.distanceToSqr(targetCenter);
        double candidateScore = candidateCenter.distanceToSqr(targetCenter);
        if (candidateScore + 1.0D < currentScore) {
            return true;
        }
        return !directPathBlockedFrom(client, candidateCenter, targetCenter, target)
            && directPathBlockedFrom(client, client.player.getEyePosition(0.0F), targetCenter, target);
    }

    private Vec3 verticalBypassPosition(Minecraft client, BlockPos cell) {
        return new Vec3(client.player.getX(), cell.getY() + CENTER_Y_OFFSET, client.player.getZ());
    }

    private void smartMoveVertically(Minecraft client, BlockPos cell, String reason) {
        Vec3 position = verticalBypassPosition(client, cell);
        client.player.setPos(position.x, position.y, position.z);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.fallDistance = 0.0F;
        client.player.setOnGround(false);
        smartRecoveryCooldown = SMART_RECOVERY_COOLDOWN_TICKS;
        smartRepeatBlockTicks = SMART_REPEAT_BLOCK_TICKS;
        lastSmartY = cell.getY();
        directStuckTicks = 0;
        jitterTicks = 0;
        previousDirectDistance = Double.MAX_VALUE;
        lastSmartBypasses++;
        BootstrapLog.info("AutoMiner smart vertical bypass: reason=" + reason
            + ", y=" + cell.getY()
            + ", xzPreserved=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f", position.x, position.z)
            + ", target=" + targetText()
            + ", bypasses=" + lastSmartBypasses);
    }

    private boolean tryMovementVerticalBypass(Minecraft client, Vec3 destination, String reason) {
        if (!antiStuck.boolValue() || smartRecoveryCooldown > 0) {
            return false;
        }
        int preferredDirection = Integer.compare((int)Math.floor(destination.y), client.player.blockPosition().getY());
        BlockPos vertical = findVerticalPocket(client, preferredDirection);
        if (vertical == null) {
            return false;
        }
        smartMoveVertically(client, vertical, reason);
        BootstrapLog.info("AutoMiner movement ceiling bypass: reason=" + reason
            + ", range=" + MOVEMENT_CEILING_RANGE
            + ", destination=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", destination.x, destination.y, destination.z)
            + ", target=" + targetText());
        return true;
    }

    private void moveToward(Minecraft client, Vec3 destination) {
        Vec3 delta = destination.subtract(client.player.position());
        double distance = delta.length();
        if (distance <= ARRIVE_DISTANCE) {
            client.player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        lookAt(client, destination);
        Vec3 motion = stabilizeFluidMotion(client, delta.normalize().scale(Math.min(effectiveMovementSpeed(client), distance)), distance);
        motion = applyDirectAntiStuck(client, destination, motion, distance);
        client.player.setDeltaMovement(motion);
        client.player.setOnGround(false);
        client.player.fallDistance = 0.0F;
    }

    private Vec3 applyDirectAntiStuck(Minecraft client, Vec3 destination, Vec3 baseMotion, double distance) {
        if (!antiStuck.boolValue()) {
            previousDirectDistance = distance;
            directStuckTicks = 0;
            jitterTicks = 0;
            jitterVector = Vec3.ZERO;
            return baseMotion;
        }

        if (distance >= previousDirectDistance - 0.02D) {
            directStuckTicks++;
        } else {
            directStuckTicks = 0;
        }
        previousDirectDistance = distance;

        if (directStuckTicks >= 6 && tryMovementVerticalBypass(client, destination, "direct ceiling")) {
            return Vec3.ZERO;
        }

        if (directStuckTicks >= 8 && jitterTicks <= 0) {
            armEmergencySpeed("direct movement stalled");
            jitterVector = directedRecoveryVector(client, destination, directStuckTicks / 8);
            jitterTicks = 8;
            BootstrapLog.info("AutoMiner direct anti-stuck: ticks=" + directStuckTicks
                + ", jitter=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", jitterVector.x, jitterVector.y, jitterVector.z)
                + ", target=" + targetText());
        }

        if (jitterTicks > 0) {
            jitterTicks--;
            return blendRecoveryMotion(client, baseMotion, jitterVector);
        }

        return baseMotion;
    }

    private Vec3 stabilizeFluidMotion(Minecraft client, Vec3 motion, double distance) {
        if (client.player == null || motion.lengthSqr() < 0.0001D) {
            return motion;
        }
        if (client.player.isInLava()) {
            double cap = Math.max(0.10D, Math.min(LAVA_MOTION_CAP, distance));
            Vec3 capped = motion.length() > cap ? motion.normalize().scale(cap) : motion;
            double yCap = Math.max(0.06D, Math.min(LAVA_VERTICAL_CAP, distance));
            return new Vec3(capped.x, Math.max(-yCap, Math.min(yCap, capped.y)), capped.z);
        }
        if (!client.player.isInWaterOrBubble()) {
            return motion;
        }
        double cap = Math.max(0.12D, Math.min(WATER_MOTION_CAP, distance));
        if (motion.length() > cap) {
            return motion.normalize().scale(cap);
        }
        return motion;
    }

    private Vec3 directedRecoveryVector(Minecraft client, Vec3 destination, int attempt) {
        Vec3 toDestination = destination.subtract(client.player.position());
        Vec3 horizontal = new Vec3(toDestination.x, 0.0D, toDestination.z);
        if (horizontal.lengthSqr() < 0.0001D) {
            horizontal = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            horizontal = horizontal.normalize();
        }
        Vec3 back = horizontal.scale(-1.0D);
        Vec3 right = new Vec3(-horizontal.z, 0.0D, horizontal.x);
        Vec3 left = right.scale(-1.0D);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vec3 base = switch (attempt % 6) {
            case 1 -> back.scale(1.80D).add(0.0D, 0.85D, 0.0D);
            case 2 -> right.scale(1.55D).add(0.0D, 1.15D, 0.0D);
            case 3 -> left.scale(1.55D).add(0.0D, -0.50D, 0.0D);
            case 4 -> back.add(right).normalize().scale(2.00D).add(0.0D, 0.30D, 0.0D);
            case 5 -> horizontal.scale(0.75D).add(0.0D, 1.30D, 0.0D);
            default -> back.add(left).normalize().scale(2.05D).add(0.0D, -0.30D, 0.0D);
        };
        double strength = Math.max(1.0D, jitter.doubleValue());
        return base.scale(strength).add(
            random.nextDouble(-0.25D, 0.25D),
            random.nextDouble(-0.15D, 0.15D),
            random.nextDouble(-0.25D, 0.25D)
        );
    }

    private Vec3 blendRecoveryMotion(Minecraft client, Vec3 baseMotion, Vec3 recovery) {
        Vec3 mixed = baseMotion.scale(0.45D).add(recovery);
        double activeSpeed = effectiveMovementSpeed(client);
        double max = Math.max(activeSpeed * 2.35D, activeSpeed + 1.15D);
        if (emergencySpeedTicks > 0) {
            max = Math.min(max, EMERGENCY_SPEED_CAP);
        }
        if (mixed.length() > max) {
            mixed = mixed.normalize().scale(max);
        }
        return mixed;
    }

    private void armEmergencySpeed(String reason) {
        boolean newlyArmed = emergencySpeedTicks <= 0;
        emergencySpeedTicks = !tunnelMode.boolValue() && !isZoneMode()
            ? DIRECT_EMERGENCY_SPEED_TICKS
            : EMERGENCY_SPEED_TICKS;
        if (!newlyArmed) {
            return;
        }
        emergencySpeedEvents++;
        BootstrapLog.info("AutoMiner emergency speed armed: reason=" + reason
            + ", ticks=" + emergencySpeedTicks
            + ", cap=" + String.format(java.util.Locale.ROOT, "%.2f", EMERGENCY_SPEED_CAP)
            + ", configured=" + speed.displayValue()
            + ", events=" + emergencySpeedEvents);
    }

    private int breakTarget(Minecraft client) {
        if (target == null) {
            resetTargetState();
            return 0;
        }
        if (!isTargetBlock(client, target)) {
            resetTargetState();
            return 0;
        }

        lookAt(client, Vec3.atCenterOf(target));
        List<BlockPos> targets = shadowNuker.boolValue() ? collectShadowNukeTargets(client, target) : List.of(target);
        lastNukeTargets = targets.size();
        int sent;
        if (shadowNuker.boolValue()) {
            List<BlockPos> burstOrder = rotateShadowTargets(targets);
            sent = sendDigPulseAutoFace(client, burstOrder, "shadowNuker", shadowBurstLimit());
            if (!targets.isEmpty() && sent > 0) {
                shadowRoundRobinIndex = (shadowRoundRobinIndex + sent) % targets.size();
            }
        } else {
            shadowRoundRobinIndex = 0;
            sent = sendDigPulse(client, targets, faceFromPlayer(client, target), "target", nukeLimit.intValue());
        }
        lastBroken = sent;
        cooldownTicks = Math.max(0, delay.intValue());
        if (sent > 0) {
            BootstrapLog.info("AutoMiner target pulse: target=" + target.toShortString()
                + ", shadowNuker=" + shadowNuker.boolValue()
                + ", nukeTargets=" + targets.size()
                + ", sent=" + sent
                + ", goal=" + (goalCell == null ? "none" : goalCell.toShortString())
                + ", pathIndex=" + pathIndex + "/" + path.size());
        }
        return sent;
    }

    private List<BlockPos> collectShadowNukeTargets(Minecraft client, BlockPos center) {
        int radiusValue = nukeRadius.intValue();
        List<BlockPos> targets = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -radiusValue; dx <= radiusValue; dx++) {
            for (int dy = -radiusValue; dy <= radiusValue; dy++) {
                for (int dz = -radiusValue; dz <= radiusValue; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusValue * radiusValue) {
                        continue;
                    }
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (isTargetBlock(client, mutable) && canReachDig(client, mutable)) {
                        targets.add(mutable.immutable());
                    }
                }
            }
        }
        targets.sort(Comparator
            .comparingDouble((BlockPos pos) -> client.player.getEyePosition(0.0F).distanceToSqr(Vec3.atCenterOf(pos)))
            .thenComparingInt(pos -> pos.distManhattan(center)));
        return targets.isEmpty() && isTargetBlock(client, center) && canReachDig(client, center)
            ? List.of(center)
            : List.copyOf(targets);
    }

    private int shadowBurstLimit() {
        return nukeLimit.intValue();
    }

    private List<BlockPos> rotateShadowTargets(List<BlockPos> targets) {
        if (targets.size() <= 1) {
            shadowRoundRobinIndex = 0;
            return targets;
        }
        int start = Math.floorMod(shadowRoundRobinIndex, targets.size());
        if (start == 0) {
            return targets;
        }
        List<BlockPos> rotated = new ArrayList<>(targets.size());
        rotated.addAll(targets.subList(start, targets.size()));
        rotated.addAll(targets.subList(0, start));
        return rotated;
    }

    private int sendDigPulse(Minecraft client, List<BlockPos> positions, Direction face, String reason) {
        return sendDigPulse(client, positions, face, reason, breakPerTick.intValue());
    }

    private int sendDigPulse(Minecraft client, List<BlockPos> positions, Direction face, String reason, int maxPackets) {
        if (positions.isEmpty()) {
            return 0;
        }
        int limit = Math.min(Math.max(1, maxPackets), positions.size());
        rememberBreakPositions(positions, limit);
        GroundMineTrace groundTrace = applyGroundMine(client, positions.get(0));
        try {
            for (int i = 0; i < limit; i++) {
                BlockPos pos = positions.get(i);
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
            }
        } finally {
            restoreGroundMine(client, groundTrace);
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        log(reason, "sent=" + limit
            + ", pending=" + positions.size()
            + ", face=" + face.getName()
            + ", maxPackets=" + maxPackets
            + groundMineDetails(groundTrace)
            + ", target=" + targetText());
        return limit;
    }

    private int sendDigPulseAutoFace(Minecraft client, List<BlockPos> positions, String reason, int maxPackets) {
        if (positions.isEmpty()) {
            return 0;
        }
        int limit = Math.min(Math.max(1, maxPackets), positions.size());
        rememberBreakPositions(positions, limit);
        GroundMineTrace groundTrace = applyGroundMine(client, positions.get(0));
        try {
            for (int i = 0; i < limit; i++) {
                BlockPos pos = positions.get(i);
                Direction face = faceFromPlayer(client, pos);
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
                client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
            }
        } finally {
            restoreGroundMine(client, groundTrace);
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        log(reason, "sent=" + limit
            + ", pending=" + positions.size()
            + ", maxPackets=" + maxPackets
            + ", autoFace=true"
            + groundMineDetails(groundTrace)
            + ", target=" + targetText());
        return limit;
    }

    private GroundMineTrace applyGroundMine(Minecraft client, BlockPos samplePos) {
        boolean previousOnGround = client.player.onGround();
        BlockState state = client.level.getBlockState(samplePos);
        float baseRaw = state.getDestroyProgress(client.player, client.level, samplePos);
        if (!GROUND_MINE_ALWAYS_ON || client.getConnection() == null) {
            return new GroundMineTrace(previousOnGround, false, baseRaw, baseRaw, samplePos);
        }
        client.player.setOnGround(true);
        client.getConnection().send(new ServerboundMovePlayerPacket.Pos(
            client.player.getX(), client.player.getY(), client.player.getZ(), true));
        client.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true));
        groundMineSpoofPackets += 2;
        float baseSpoof = state.getDestroyProgress(client.player, client.level, samplePos);
        GroundMineTrace trace = new GroundMineTrace(previousOnGround, true, baseRaw, baseSpoof, samplePos);
        logGroundMine(trace);
        return trace;
    }

    private void restoreGroundMine(Minecraft client, GroundMineTrace trace) {
        if (trace != null && trace.spoofed) {
            client.player.setOnGround(trace.previousOnGround);
        }
    }

    private void logGroundMine(GroundMineTrace trace) {
        long now = System.nanoTime();
        if (now - lastGroundMineLogNanos < 1_000_000_000L) {
            return;
        }
        lastGroundMineLogNanos = now;
        BootstrapLog.info("AutoMiner GroundMine: pos=" + trace.samplePos.toShortString()
            + ", baseRaw=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseRaw)
            + ", baseSpoof=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseSpoof)
            + ", prevGround=" + trace.previousOnGround
            + ", spoofed=" + trace.spoofed
            + ", spoofPackets=" + groundMineSpoofPackets
            + ", target=" + targetText());
    }

    private String groundMineDetails(GroundMineTrace trace) {
        if (trace == null) {
            return "";
        }
        return ", groundMine=" + GROUND_MINE_ALWAYS_ON
            + ", groundSpoof=" + trace.spoofed
            + ", prevGround=" + trace.previousOnGround
            + ", baseRaw=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseRaw)
            + ", baseSpoof=" + String.format(java.util.Locale.ROOT, "%.4f", trace.baseSpoof)
            + ", spoofPackets=" + groundMineSpoofPackets;
    }

    private boolean tickEnsurePickup(Minecraft client) {
        if (pickupScanTicks > 0) {
            pickupScanTicks--;
        }
        if (pickupScanTicks <= 0 && pickupTarget == null) {
            lastPickupItems = 0;
            return false;
        }

        ItemEntity item = validatePickupTarget(client) ? pickupTarget : findPickupTarget(client);
        if (item == null) {
            pickupTarget = null;
            lastPickupItems = 0;
            if (pickupActive) {
                pickupActive = false;
                if (target != null && isTargetBlock(client, target)) {
                    rebuildPath(client);
                }
            }
            return false;
        }

        pickupTarget = item;
        pickupActive = true;
        lastPickupItems = countPickupCandidates(client);
        moveToPickup(client, item);
        return true;
    }

    private boolean validatePickupTarget(Minecraft client) {
        return pickupTarget != null
            && !pickupTarget.isRemoved()
            && !pickupTarget.getItem().isEmpty()
            && isRecentDrop(client, pickupTarget);
    }

    private ItemEntity findPickupTarget(Minecraft client) {
        ItemEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || item.getItem().isEmpty()) {
                continue;
            }
            if (!isRecentDrop(client, item)) {
                continue;
            }
            double score = item.distanceToSqr(client.player);
            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private int countPickupCandidates(Minecraft client) {
        int count = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ItemEntity item && !item.isRemoved() && !item.getItem().isEmpty() && isRecentDrop(client, item)) {
                count++;
            }
        }
        return count;
    }

    private boolean isRecentDrop(Minecraft client, ItemEntity item) {
        double playerScanSq = PICKUP_SCAN_RADIUS * PICKUP_SCAN_RADIUS;
        if (item.distanceToSqr(client.player) > playerScanSq) {
            return false;
        }
        if (recentBreakPositions.isEmpty()) {
            return pickupScanTicks > 0;
        }
        Vec3 itemPos = item.position();
        double associationSq = PICKUP_ASSOCIATION_RADIUS * PICKUP_ASSOCIATION_RADIUS;
        for (BlockPos pos : recentBreakPositions) {
            if (Vec3.atCenterOf(pos).distanceToSqr(itemPos) <= associationSq) {
                return true;
            }
        }
        return false;
    }

    private void moveToPickup(Minecraft client, ItemEntity item) {
        Vec3 destination = item.position();
        Vec3 current = client.player.position();
        Vec3 delta = destination.subtract(current);
        double distance = delta.length();
        lookAt(client, destination.add(0.0D, 0.25D, 0.0D));
        client.player.setOnGround(false);
        client.player.fallDistance = 0.0F;
        if (distance <= PICKUP_REACH_DISTANCE) {
            client.player.setPos(destination.x, destination.y, destination.z);
            client.player.setDeltaMovement(Vec3.ZERO);
            log("pickup", "touch item=" + item.getItem().getHoverName().getString()
                + ", drops=" + lastPickupItems
                + ", target=" + targetText());
            return;
        }
        Vec3 motion = stabilizeFluidMotion(client,
            delta.normalize().scale(Math.min(Math.max(0.25D, effectiveMovementSpeed(client)), distance)), distance);
        client.player.setDeltaMovement(motion);
        log("pickup", "move item=" + item.getItem().getHoverName().getString()
            + ", dist=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
            + ", drops=" + lastPickupItems
            + ", target=" + targetText());
    }

    private void rememberBreakPositions(List<BlockPos> positions, int limit) {
        if (!ensurePickup.boolValue() || positions.isEmpty() || limit <= 0) {
            return;
        }
        int actualLimit = Math.min(limit, positions.size());
        for (int i = 0; i < actualLimit; i++) {
            recentBreakPositions.add(positions.get(i).immutable());
        }
        while (recentBreakPositions.size() > RECENT_BREAK_POS_LIMIT) {
            recentBreakPositions.remove(recentBreakPositions.iterator().next());
        }
        pickupScanTicks = PICKUP_SCAN_TICKS;
    }

    private void clearPickupState() {
        recentBreakPositions.clear();
        pickupTarget = null;
        pickupActive = false;
        pickupScanTicks = 0;
        lastPickupItems = 0;
    }

    private void acquireTarget(Minecraft client) {
        if (searchBackoffTicks > 0) {
            searchBackoffTicks--;
            return;
        }

        if (isZoneMode()) {
            targetSearch = null;
            if (!ensureZonePlan(client)) {
                target = null;
                return;
            }
            target = closestZoneTarget(client);
            if (target == null) {
                zoneEmptyRescanTicks++;
                if (zoneEmptyRescanTicks >= 40) {
                    resetZonePlan();
                }
                searchBackoffTicks = 2;
                return;
            }
            zoneEmptyRescanTicks = 0;
        } else {
            int searchRadius = radius.intValue();
            int scanBudget = tunnelMode.boolValue() ? TARGET_SCAN_BUDGET_PER_TICK : DIRECT_TARGET_SCAN_BUDGET_PER_TICK;
            if (!tunnelMode.boolValue() && targetSearch == null) {
                target = takeCachedDirectTarget(client, searchRadius);
            }
            if (target == null) {
                if (targetSearch == null || !targetSearch.matches(client, searchRadius)) {
                    targetSearch = new TargetSearchState(client, searchRadius);
                    BootstrapLog.info("AutoMiner target scan started: origin=" + targetSearch.origin.toShortString()
                        + ", radius=" + searchRadius
                        + ", budget=" + scanBudget
                        + ", targetMode=" + targetMode.choiceValue()
                        + ", tunnel=" + tunnelMode.boolValue());
                }
                TargetSearchResult result = advanceTargetSearch(client, targetSearch, scanBudget);
                if (!result.complete) {
                    log("search", "shell=" + targetSearch.shell + "/" + targetSearch.radius
                        + ", checked=" + targetSearch.checked
                        + ", candidates=" + targetSearch.candidates);
                    return;
                }
                target = result.target;
                BootstrapLog.info("AutoMiner target scan completed: checked=" + targetSearch.checked
                    + ", shell=" + targetSearch.shell + "/" + targetSearch.radius
                    + ", candidates=" + targetSearch.candidates
                    + ", result=" + (target == null ? "none" : target.toShortString())
                    + ", cache=" + directCandidateCache.size());
                targetSearch = null;
                directCandidateCache.remove(target);
            }
        }
        if (target == null) {
            searchBackoffTicks = SEARCH_BACKOFF_TICKS;
            return;
        }
        if (isZoneMode()) {
            path = List.of();
            pathIndex = 0;
            goalCell = null;
            previousDirectDistance = Double.MAX_VALUE;
            directStuckTicks = 0;
        } else {
            rebuildPath(client);
        }
        BootstrapLog.info("AutoMiner target acquired: target=" + target.toShortString()
            + ", goal=" + (goalCell == null ? "none" : goalCell.toShortString())
            + ", path=" + path.size()
            + ", mode=" + (isZoneMode() ? "zone" : tunnelMode.boolValue() ? "tunnel" : "flight")
            + ", axisOrder=" + axisOrder.choiceValue()
            + ", targetMode=" + targetMode.choiceValue());
    }

    private boolean ensureZonePlan(Minecraft client) {
        ResourceLocation dimension = client.level.dimension().location();
        long registryVersion = SelectedZoneRegistry.version();
        if (zonePlanDimension == null || !zonePlanDimension.equals(dimension) || zonePlanVersion != registryVersion) {
            zonePlanDimension = dimension;
            zonePlanVersion = registryVersion;
            zoneTargets = new ArrayList<>();
            zoneRejectedTargets.clear();
            zoneScanChecked = 0;
            zoneFluidSkipped = 0;
            zoneScanCursor = new ZoneScanCursor(SelectedZoneRegistry.snapshot(client.level.dimension()));
            resetZoneBreakAttempts();
            target = null;
            BootstrapLog.info("AutoMiner zone scan started: dimension=" + dimension
                + ", zones=" + SelectedZoneRegistry.snapshot(client.level.dimension()).size()
                + ", selectedBlocks=" + SelectedZoneRegistry.totalBlocks());
        }
        if (zoneScanCursor == null) {
            return true;
        }

        int budget = ZONE_SCAN_BUDGET_PER_TICK;
        while (budget-- > 0 && zoneScanCursor.hasNext()) {
            BlockPos pos = zoneScanCursor.next();
            zoneScanChecked++;
            if (client.level.hasChunkAt(pos) && !zoneRejectedTargets.contains(pos) && isZoneMineable(client, pos)) {
                zoneTargets.add(pos);
            }
        }
        if (zoneScanCursor.hasNext()) {
            return false;
        }
        zoneScanCursor = null;
        BootstrapLog.info("AutoMiner zone scan completed: checked=" + zoneScanChecked
            + ", mineable=" + zoneTargets.size()
            + ", fluidsSkipped=" + zoneFluidSkipped
            + ", zones=" + SelectedZoneRegistry.size());
        return true;
    }

    private BlockPos closestZoneTarget(Minecraft client) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = zoneTargets.size() - 1; i >= 0; i--) {
            BlockPos pos = zoneTargets.get(i);
            if (isTargetBackedOff(pos)) {
                continue;
            }
            if (!isTargetBlock(client, pos)) {
                zoneTargets.remove(i);
                continue;
            }
            double score = targetScore(client, pos);
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    private boolean isZoneMineable(Minecraft client, BlockPos pos) {
        if (!isMineYAllowed(pos)) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (isFluidOnlyBlock(client, pos, state)) {
            zoneFluidSkipped++;
            return false;
        }
        return !state.isAir()
            && state.getBlock() != Blocks.BEDROCK
            && state.getDestroySpeed(client.level, pos) >= 0.0F;
    }

    private void trackZoneBreakAttempts(Minecraft client) {
        if (target == null || lastZoneBurst <= 0 || !isTargetBlock(client, target)) {
            resetZoneBreakAttempts();
            return;
        }
        if (!target.equals(zoneAttemptTarget)) {
            zoneAttemptTarget = target.immutable();
            zoneBreakAttempts = 0;
        }
        zoneBreakAttempts++;
        if (zoneBreakAttempts < ZONE_BREAK_ABANDON_ATTEMPTS) {
            return;
        }

        BlockPos failed = target.immutable();
        BlockState state = client.level.getBlockState(failed);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        zoneRejectedTargets.add(failed);
        zoneTargets.remove(failed);
        abandonedTargets++;
        BootstrapLog.info("AutoMiner zone target rejected: target=" + failed.toShortString()
            + ", block=" + (id == null ? "unknown" : id)
            + ", fluid=" + !state.getFluidState().isEmpty()
            + ", destroySpeed=" + state.getDestroySpeed(client.level, failed)
            + ", attempts=" + zoneBreakAttempts
            + ", rejected=" + zoneRejectedTargets.size());
        target = null;
        lastZoneBurst = 0;
        client.player.setDeltaMovement(Vec3.ZERO);
        resetZoneBreakAttempts();
    }

    private void resetZoneBreakAttempts() {
        zoneAttemptTarget = null;
        zoneBreakAttempts = 0;
    }

    private boolean isZoneMode() {
        return "SelectZone".equals(targetMode.choiceValue());
    }

    private void resetZonePlan() {
        zonePlanVersion = -1L;
        zonePlanDimension = null;
        zoneTargets = new ArrayList<>();
        zoneRejectedTargets.clear();
        zoneScanCursor = null;
        zoneScanChecked = 0;
        zoneFluidSkipped = 0;
        zoneEmptyRescanTicks = 0;
        lastZoneBurst = 0;
        resetZoneBreakAttempts();
    }

    private TargetSearchResult advanceTargetSearch(Minecraft client, TargetSearchState search, int budget) {
        int processed = 0;
        while (processed < budget) {
            if (search.shell > search.radius) {
                return TargetSearchResult.complete(search.best);
            }
            if (!search.cursor.hasNext()) {
                int nextShell = search.shell + 1;
                if (search.best != null && targetSearchCanStop(search, nextShell)) {
                    return TargetSearchResult.complete(search.best);
                }
                search.shell = nextShell;
                if (search.shell > search.radius) {
                    return TargetSearchResult.complete(search.best);
                }
                search.cursor = new ShellCursor(search.shell);
                continue;
            }

            BlockPos pos = search.cursor.next(search.origin);
            processed++;
            search.checked++;
            if (!insideBuildHeight(client, pos.getY()) || !client.level.hasChunkAt(pos) || isTargetBackedOff(pos)) {
                continue;
            }
            if (!isTargetBlock(client, pos)) {
                continue;
            }
            if (search.visibleOnly && !canSeeBlock(client, pos)) {
                continue;
            }
            search.candidates++;
            if (!search.tunnel) {
                cacheDirectCandidate(client, pos);
            }
            double score = search.score(pos);
            if (score < search.bestScore
                || (Math.abs(score - search.bestScore) < 0.0001D
                    && (search.best == null || pos.distManhattan(search.origin) < search.best.distManhattan(search.origin)))) {
                search.best = pos.immutable();
                search.bestScore = score;
            }
        }
        return TargetSearchResult.pending();
    }

    private void cacheDirectCandidate(Minecraft client, BlockPos pos) {
        ensureDirectCacheContext(client);
        BlockPos immutable = pos.immutable();
        if (!directCandidateCache.add(immutable)) {
            return;
        }
        directCacheAdds++;
        while (directCandidateCache.size() > DIRECT_CANDIDATE_CACHE_LIMIT) {
            var iterator = directCandidateCache.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private BlockPos takeCachedDirectTarget(Minecraft client, int searchRadius) {
        ensureDirectCacheContext(client);
        if (directCandidateCache.isEmpty()) {
            return null;
        }
        BlockPos origin = client.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        var iterator = directCandidateCache.iterator();
        while (iterator.hasNext()) {
            BlockPos candidate = iterator.next();
            int shellDistance = Math.max(Math.abs(candidate.getX() - origin.getX()),
                Math.max(Math.abs(candidate.getY() - origin.getY()), Math.abs(candidate.getZ() - origin.getZ())));
            if (shellDistance > searchRadius || !client.level.hasChunkAt(candidate)
                || isTargetBackedOff(candidate) || !isTargetBlock(client, candidate)
                || (onlyVisible.boolValue() && !canSeeBlock(client, candidate))) {
                iterator.remove();
                continue;
            }
            double score = targetScore(client, candidate);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) {
            directCandidateCache.remove(best);
            directCacheHits++;
            BootstrapLog.info("AutoMiner direct cache hit: target=" + best.toShortString()
                + ", remaining=" + directCandidateCache.size()
                + ", hits=" + directCacheHits
                + ", adds=" + directCacheAdds);
        }
        return best;
    }

    private void ensureDirectCacheContext(Minecraft client) {
        ResourceLocation dimension = client.level.dimension().location();
        String mode = targetMode.choiceValue();
        if (!dimension.equals(directCacheDimension) || !mode.equals(directCacheMode)) {
            directCandidateCache.clear();
            directCacheDimension = dimension;
            directCacheMode = mode;
        }
    }

    private void clearDirectCandidateCache() {
        directCandidateCache.clear();
        directCacheDimension = null;
        directCacheMode = "";
    }

    private boolean targetSearchCanStop(TargetSearchState search, int nextShell) {
        if (nextShell > search.radius) {
            return true;
        }
        // A target scored from eye position can be about two blocks closer vertically than its block shell.
        double minimumFutureDistance = Math.max(0.0D, nextShell - 2.25D);
        return minimumFutureDistance * minimumFutureDistance > search.bestScore;
    }

    private double targetScore(Minecraft client, BlockPos pos) {
        if (pos == null) {
            return Double.MAX_VALUE;
        }
        Vec3 player = client.player.position();
        Vec3 center = Vec3.atCenterOf(pos);
        double score = player.distanceToSqr(center);
        if (!tunnelMode.boolValue() || isZoneMode()) {
            double eyeScore = client.player.getEyePosition(0.0F).distanceToSqr(center);
            score = Math.min(score, eyeScore);
        }
        return score;
    }

    private void rebuildPath(Minecraft client) {
        invalidateDirectPath();
        shadowRoundRobinIndex = 0;
        path = List.of();
        pathIndex = 0;
        goalCell = null;
        previousMoveDistance = Double.MAX_VALUE;
        previousDirectDistance = Double.MAX_VALUE;
        directStuckTicks = 0;
        jitterTicks = 0;
        directRetargetTicks = 0;
        directBreakAttempts = 0;
        directAttemptTarget = null;
        jitterVector = Vec3.ZERO;
        stuckTicks = 0;
        clearTunnelWaypointProgress();

        if (target == null) {
            return;
        }

        BlockPos start = client.player.blockPosition();
        goalCell = chooseGoalCell(client, start, target);
        if (goalCell == null) {
            return;
        }
        path = buildAxisPath(start, goalCell);
        log("path", "start=" + start.toShortString()
            + ", goal=" + goalCell.toShortString()
            + ", target=" + target.toShortString()
            + ", nodes=" + path.size());
    }

    private BlockPos chooseGoalCell(Minecraft client, BlockPos start, BlockPos targetPos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(targetPos.relative(Direction.NORTH));
        candidates.add(targetPos.relative(Direction.SOUTH));
        candidates.add(targetPos.relative(Direction.EAST));
        candidates.add(targetPos.relative(Direction.WEST));
        candidates.add(targetPos.above());

        return candidates.stream()
            .filter(candidate -> !candidate.equals(targetPos))
            .filter(candidate -> !candidate.above().equals(targetPos))
            .filter(candidate -> isUsableGoalCell(client, candidate))
            .min(Comparator
                .comparingInt((BlockPos candidate) -> candidate.distManhattan(start))
                .thenComparingInt(candidate -> clearanceCost(client, candidate)))
            .orElse(targetPos.relative(Direction.NORTH));
    }

    private boolean isUsableGoalCell(Minecraft client, BlockPos cell) {
        if (!isMineYAllowed(cell) || !isMineYAllowed(cell.above())) {
            return false;
        }
        BlockState foot = client.level.getBlockState(cell);
        BlockState head = client.level.getBlockState(cell.above());
        return isAirOrBreakable(client, cell, foot) && isAirOrBreakable(client, cell.above(), head);
    }

    private int clearanceCost(Minecraft client, BlockPos cell) {
        int cost = 0;
        if (!client.level.getBlockState(cell).isAir()) {
            cost++;
        }
        if (!client.level.getBlockState(cell.above()).isAir()) {
            cost++;
        }
        return cost;
    }

    private List<BlockPos> buildAxisPath(BlockPos start, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos cursor = start;
        for (char axis : axisOrder.choiceValue().toCharArray()) {
            while (coordinate(cursor, axis) != coordinate(goal, axis)) {
                int step = Integer.compare(coordinate(goal, axis), coordinate(cursor, axis));
                cursor = switch (axis) {
                    case 'X' -> cursor.offset(step, 0, 0);
                    case 'Y' -> cursor.offset(0, step, 0);
                    case 'Z' -> cursor.offset(0, 0, step);
                    default -> cursor;
                };
                result.add(cursor);
            }
        }
        return List.copyOf(result);
    }

    private int coordinate(BlockPos pos, char axis) {
        return switch (axis) {
            case 'X' -> pos.getX();
            case 'Y' -> pos.getY();
            case 'Z' -> pos.getZ();
            default -> 0;
        };
    }

    private BlockPos expectedCurrentCell(Minecraft client) {
        if (pathIndex <= 0 || pathIndex > path.size()) {
            return client.player.blockPosition();
        }
        return path.get(pathIndex - 1);
    }

    private void addBodySpace(Set<BlockPos> positions, BlockPos footCell) {
        positions.add(footCell.immutable());
        positions.add(footCell.above().immutable());
    }

    private boolean validateTarget(Minecraft client) {
        return target != null && isTargetBlock(client, target);
    }

    private boolean isTargetBlock(Minecraft client, BlockPos pos) {
        if (client.level == null || pos == null) {
            return false;
        }
        if (!isMineYAllowed(pos)) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || isFluidOnlyBlock(client, pos, state)
            || state.getBlock() == Blocks.BEDROCK || state.getDestroySpeed(client.level, pos) < 0.0F) {
            return false;
        }
        return switch (targetMode.choiceValue()) {
            case "SelectZone" -> SelectedZoneRegistry.contains(client.level.dimension(), pos);
            case "XRayOnly" -> XRayTargetRegistry.contains(state.getBlock());
            case "OreLike" -> isOreLike(state);
            default -> XRayTargetRegistry.contains(state.getBlock()) || isOreLike(state);
        };
    }

    private boolean isTargetBackedOff(BlockPos pos) {
        Integer until = targetBackoffUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until <= moduleTicks) {
            targetBackoffUntil.remove(pos);
            return false;
        }
        return true;
    }

    private boolean canReachDig(Minecraft client, BlockPos pos) {
        double reachValue = Math.max(3.0D, nukeReach.doubleValue()) + 0.25D;
        double reachSq = reachValue * reachValue;
        return client.player.getEyePosition(0.0F).distanceToSqr(Vec3.atCenterOf(pos)) <= reachSq;
    }

    private boolean isOreLike(BlockState state) {
        return oreLikeCache.computeIfAbsent(state.getBlock(), block -> {
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
            return path.contains("ore")
                || path.contains("debris")
                || path.contains("crystal")
                || path.contains("gem");
        });
    }

    private boolean isBreakablePassageBlock(Minecraft client, BlockPos pos) {
        if (!isMineYAllowed(pos)) {
            return false;
        }
        if (target != null && pos.equals(target)) {
            return false;
        }
        BlockState state = client.level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || state.getCollisionShape(client.level, pos).isEmpty()) {
            return false;
        }
        return !state.isAir()
            && state.getBlock() != Blocks.BEDROCK
            && state.getDestroySpeed(client.level, pos) >= 0.0F;
    }

    private boolean isFluidOnlyBlock(Minecraft client, BlockPos pos, BlockState state) {
        return state != null
            && !state.getFluidState().isEmpty()
            && state.getCollisionShape(client.level, pos).isEmpty();
    }

    private boolean isAirOrBreakable(Minecraft client, BlockPos pos, BlockState state) {
        if (!isMineYAllowed(pos)) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return !state.getFluidState().is(FluidTags.LAVA);
        }
        return state.getCollisionShape(client.level, pos).isEmpty()
            || (state.getBlock() != Blocks.BEDROCK && state.getDestroySpeed(client.level, pos) >= 0.0F);
    }

    private boolean isMineYAllowed(BlockPos pos) {
        return pos != null && pos.getY() >= minY.intValue();
    }

    private boolean canSeeBlock(Minecraft client, BlockPos pos) {
        Vec3 start = client.player.getEyePosition(0.0F);
        Vec3 end = Vec3.atCenterOf(pos);
        ClipContext context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player);
        BlockHitResult result = client.level.clip(context);
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos);
    }

    private boolean directPathBlocked(Minecraft client, Vec3 targetCenter) {
        return directPathBlockedFrom(client, client.player.getEyePosition(0.0F), targetCenter, target);
    }

    private boolean directPathBlockedFrom(Minecraft client, Vec3 start, Vec3 end, BlockPos allowedEndBlock) {
        ClipContext context = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player);
        BlockHitResult result = client.level.clip(context);
        if (result.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return allowedEndBlock == null || !result.getBlockPos().equals(allowedEndBlock);
    }

    private boolean insideBuildHeight(Minecraft client, int y) {
        return y >= client.level.getMinBuildHeight() && y <= client.level.getMaxBuildHeight() - 2;
    }

    private boolean isTwoPassable(Minecraft client, BlockPos footCell) {
        return isPassableForPlayer(client, footCell) && isPassableForPlayer(client, footCell.above());
    }

    private boolean isPassableForPlayer(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.getCollisionShape(client.level, pos).isEmpty()
            && !state.getFluidState().is(FluidTags.LAVA);
    }

    private boolean hasLavaNear(Minecraft client, BlockPos footCell) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockState state = client.level.getBlockState(footCell.offset(0, dy, 0));
            if (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private void keepFlightState(Minecraft client) {
        client.player.noPhysics = true;
        client.player.setNoGravity(true);
        client.player.fallDistance = 0.0F;
        client.player.setOnGround(false);
        lastInLava = client.player.isInLava();
        lastInWater = client.player.isInWaterOrBubble();
    }

    private Vec3 cellCenter(BlockPos cell) {
        return new Vec3(cell.getX() + 0.5D, cell.getY() + CENTER_Y_OFFSET, cell.getZ() + 0.5D);
    }

    private void lookAt(Minecraft client, Vec3 point) {
        Vec3 eye = client.player.getEyePosition(0.0F);
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float)(MthWrap.degrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float)(-MthWrap.degrees(Math.atan2(delta.y, horizontal)));
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.yRotO = yaw;
        client.player.xRotO = pitch;
    }

    private Direction directionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == dx && direction.getStepY() == dy && direction.getStepZ() == dz) {
                return direction;
            }
        }
        return null;
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

    private void snapshotMovement(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        previousNoGravity = client.player.isNoGravity();
        previousNoPhysics = client.player.noPhysics;
        hadMovementSnapshot = true;
    }

    private void installCorrectionMonitor(Minecraft client) {
        if (client == null || client.getConnection() == null) {
            return;
        }
        try {
            Connection connection = client.getConnection().getConnection();
            Channel found = findChannel(connection);
            if (found == null) {
                return;
            }
            ChannelPipeline pipeline = found.pipeline();
            if (pipeline.get(CORRECTION_HANDLER_NAME) == null) {
                PositionCorrectionHandler handler = new PositionCorrectionHandler(this);
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", CORRECTION_HANDLER_NAME, handler);
                } else {
                    pipeline.addLast(CORRECTION_HANDLER_NAME, handler);
                }
                BootstrapLog.info("AutoMiner server-correction monitor installed");
            }
            correctionChannel = found;
        } catch (Throwable error) {
            BootstrapLog.error("AutoMiner server-correction monitor install failed", error);
        }
    }

    private Channel findChannel(Connection connection) {
        if (connection == null) {
            return null;
        }
        Class<?> type = connection.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!Channel.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(connection);
                    if (value instanceof Channel found) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // Try the next channel field.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void removeCorrectionMonitor() {
        Channel current = correctionChannel;
        correctionChannel = null;
        pendingServerCorrections.set(0);
        if (current == null) {
            return;
        }
        try {
            current.eventLoop().execute(() -> {
                try {
                    if (current.pipeline().get(CORRECTION_HANDLER_NAME) != null) {
                        current.pipeline().remove(CORRECTION_HANDLER_NAME);
                    }
                } catch (Throwable error) {
                    BootstrapLog.error("AutoMiner server-correction monitor remove failed", error);
                }
            });
        } catch (Throwable error) {
            BootstrapLog.error("AutoMiner server-correction monitor remove schedule failed", error);
        }
    }

    private void observeInboundPacket(Object message) {
        if (message instanceof ClientboundPlayerPositionPacket) {
            pendingServerCorrections.incrementAndGet();
        }
    }

    private void resetTargetState() {
        targetSearch = null;
        directRetargetSearch = null;
        invalidateDirectPath();
        shadowRoundRobinIndex = 0;
        target = null;
        goalCell = null;
        path = List.of();
        pathIndex = 0;
        cooldownTicks = 0;
        searchBackoffTicks = 0;
        stuckTicks = 0;
        directStuckTicks = 0;
        jitterTicks = 0;
        directRetargetTicks = 0;
        directBreakAttempts = 0;
        directAttemptTarget = null;
        lastBroken = 0;
        lastNukeTargets = 0;
        lastSmartBypasses = 0;
        smartRecoveryCooldown = 0;
        smartRepeatBlockTicks = 0;
        lastSmartY = Integer.MIN_VALUE;
        lastInWater = false;
        lastInLava = false;
        tunnelRecoveryPauseTicks = 0;
        stableWaypointTicks = 0;
        previousMoveDistance = Double.MAX_VALUE;
        previousDirectDistance = Double.MAX_VALUE;
        jitterVector = Vec3.ZERO;
        clearTunnelWaypointProgress();
        resetTunnelRouteProgress();
    }

    private String targetText() {
        return target == null ? "none" : target.toShortString();
    }

    private void trackDirectBreakAttempt() {
        if (target == null) {
            resetDirectBreakAttempts();
            return;
        }
        if (!target.equals(directAttemptTarget)) {
            directAttemptTarget = target;
            directBreakAttempts = 0;
        }
        directBreakAttempts++;
    }

    private void resetDirectBreakAttempts() {
        directAttemptTarget = target;
        directBreakAttempts = 0;
    }

    private void log(String phase, String details) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("AutoMiner " + phase + ": " + details);
    }

    @Override
    public String description() {
        return "Finds XRay/ore targets and either flies directly or digs an axis-aligned motion tunnel to them.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "AutoMiner target=" + targetText()
            + " goal=" + (goalCell == null ? "none" : goalCell.toShortString())
            + " path=" + pathIndex + "/" + path.size()
            + " speed=" + speed.displayValue()
            + " minY=" + minY.displayValue()
            + " broken=" + lastBroken
            + " vein=" + lastNukeTargets
            + " pickup=" + lastPickupItems
            + " ahead=" + tunnelAhead.displayValue()
            + " stuck=" + directStuckTicks
            + " wpStuck=" + tunnelWaypointNoProgressTicks
            + " tries=" + directBreakAttempts
            + " smart=" + lastSmartBypasses
            + " water=" + lastInWater
            + " lava=" + lastInLava
            + " adaptive=" + formatScale()
            + " emergency=" + emergencySpeedTicks
            + " emergencyEvents=" + emergencySpeedEvents
            + " routeStuck=" + tunnelRouteNoProgressTicks
            + " routeStalls=" + tunnelRouteStalls
            + " corrections=" + serverCorrectionPackets
            + " recoveries=" + tunnelRecoveries
            + " abandoned=" + abandonedTargets
            + " pause=" + tunnelRecoveryPauseTicks
            + " zonePlan=" + zoneTargets.size()
            + " zoneScan=" + (zoneScanCursor == null ? "ready" : zoneScanChecked)
            + " zoneFluids=" + zoneFluidSkipped
            + " zoneRejected=" + zoneRejectedTargets.size()
            + " zoneTries=" + zoneBreakAttempts
            + " zoneBurst=" + lastZoneBurst
            + " targetScan=" + (targetSearch == null
                ? "ready"
                : targetSearch.shell + "/" + targetSearch.radius + ":" + targetSearch.checked + ":" + targetSearch.candidates)
            + " scanBudget=" + (tunnelMode.boolValue() ? TARGET_SCAN_BUDGET_PER_TICK : DIRECT_TARGET_SCAN_BUDGET_PER_TICK)
            + " directCache=" + directCandidateCache.size() + ":" + directCacheHits + ":" + directCacheAdds
            + " retargetScan=" + (directRetargetSearch == null
                ? "ready"
                : directRetargetSearch.shell + "/" + directRetargetSearch.radius + ":" + directRetargetSearch.checked)
            + " directPath=" + directPathIndex + "/" + directPath.size()
            + " directPlans=" + directPathPlans
            + " directPlanFailures=" + directPathFailures
            + " nukeReach=" + nukeReach.displayValue()
            + " nukeRR=" + shadowRoundRobinIndex
            + " groundMine=ON"
            + " groundPackets=" + groundMineSpoofPackets
            + " tunnel=" + tunnelMode.displayValue();
    }

    private record DirectPathNode(BlockPos pos, double score, double cost) {
    }

    private record DirectPathPlan(List<BlockPos> path, int explored, boolean found) {
    }

    private final class TargetSearchState {
        private final BlockPos origin;
        private final Vec3 originPosition;
        private final Vec3 originEyePosition;
        private final ResourceLocation dimension;
        private final int radius;
        private final int minimumY;
        private final String mode;
        private final boolean tunnel;
        private final boolean visibleOnly;
        private int shell;
        private ShellCursor cursor;
        private int checked;
        private int candidates;
        private BlockPos best;
        private double bestScore = Double.MAX_VALUE;

        private TargetSearchState(Minecraft client, int radius) {
            this.origin = client.player.blockPosition().immutable();
            this.originPosition = client.player.position();
            this.originEyePosition = client.player.getEyePosition(0.0F);
            this.dimension = client.level.dimension().location();
            this.radius = radius;
            this.minimumY = minY.intValue();
            this.mode = targetMode.choiceValue();
            this.tunnel = tunnelMode.boolValue();
            this.visibleOnly = !tunnel && onlyVisible.boolValue();
            this.cursor = new ShellCursor(0);
        }

        private boolean matches(Minecraft client, int expectedRadius) {
            return client != null
                && client.level != null
                && dimension.equals(client.level.dimension().location())
                && radius == expectedRadius
                && minimumY == minY.intValue()
                && mode.equals(targetMode.choiceValue())
                && tunnel == tunnelMode.boolValue()
                && visibleOnly == (!tunnelMode.boolValue() && onlyVisible.boolValue());
        }

        private double score(BlockPos pos) {
            Vec3 center = Vec3.atCenterOf(pos);
            double score = originPosition.distanceToSqr(center);
            if (!tunnel) {
                score = Math.min(score, originEyePosition.distanceToSqr(center));
            }
            return score;
        }
    }

    private static final class TargetSearchResult {
        private final BlockPos target;
        private final boolean complete;

        private TargetSearchResult(BlockPos target, boolean complete) {
            this.target = target;
            this.complete = complete;
        }

        private static TargetSearchResult pending() {
            return new TargetSearchResult(null, false);
        }

        private static TargetSearchResult complete(BlockPos target) {
            return new TargetSearchResult(target, true);
        }
    }

    private static final class ShellCursor {
        private final int shell;
        private int face;
        private int a;
        private int b;
        private boolean centerConsumed;

        private ShellCursor(int shell) {
            this.shell = shell;
            if (shell > 0) {
                startFace(0);
            }
        }

        private boolean hasNext() {
            return shell == 0 ? !centerConsumed : face < 6;
        }

        private BlockPos next(BlockPos origin) {
            if (!hasNext()) {
                throw new IllegalStateException("target shell exhausted");
            }
            if (shell == 0) {
                centerConsumed = true;
                return origin;
            }

            int dx;
            int dy;
            int dz;
            switch (face) {
                case 0 -> {
                    dx = -shell;
                    dy = a;
                    dz = b;
                }
                case 1 -> {
                    dx = shell;
                    dy = a;
                    dz = b;
                }
                case 2 -> {
                    dx = a;
                    dy = -shell;
                    dz = b;
                }
                case 3 -> {
                    dx = a;
                    dy = shell;
                    dz = b;
                }
                case 4 -> {
                    dx = a;
                    dy = b;
                    dz = -shell;
                }
                case 5 -> {
                    dx = a;
                    dy = b;
                    dz = shell;
                }
                default -> throw new IllegalStateException("invalid target shell face");
            }
            BlockPos result = origin.offset(dx, dy, dz);
            advance();
            return result;
        }

        private void advance() {
            int aEnd = (face <= 1) ? shell : shell - 1;
            int bEnd = (face <= 3) ? shell : shell - 1;
            int bStart = (face <= 3) ? -shell : -shell + 1;
            if (b < bEnd) {
                b++;
                return;
            }
            b = bStart;
            if (a < aEnd) {
                a++;
                return;
            }
            startFace(face + 1);
        }

        private void startFace(int nextFace) {
            face = nextFace;
            if (face >= 6) {
                return;
            }
            a = face <= 1 ? -shell : -shell + 1;
            b = face <= 3 ? -shell : -shell + 1;
        }
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

    private static final class PositionCorrectionHandler extends ChannelDuplexHandler {
        private final AutoMinerModule owner;

        private PositionCorrectionHandler(AutoMinerModule owner) {
            this.owner = owner;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            owner.observeInboundPacket(message);
            super.channelRead(context, message);
        }
    }

    private static final class ZoneScanCursor {
        private final List<SelectedZoneRegistry.Zone> zones;
        private int zoneIndex;
        private int x;
        private int y;
        private int z;
        private boolean initialized;

        private ZoneScanCursor(List<SelectedZoneRegistry.Zone> zones) {
            this.zones = zones == null ? List.of() : List.copyOf(zones);
        }

        private boolean hasNext() {
            return zoneIndex < zones.size();
        }

        private BlockPos next() {
            if (!hasNext()) {
                throw new IllegalStateException("zone scan exhausted");
            }
            SelectedZoneRegistry.Zone zone = zones.get(zoneIndex);
            if (!initialized) {
                x = zone.min().getX();
                y = zone.min().getY();
                z = zone.min().getZ();
                initialized = true;
            }
            BlockPos result = new BlockPos(x, y, z);
            advance(zone);
            return result;
        }

        private void advance(SelectedZoneRegistry.Zone zone) {
            z++;
            if (z <= zone.max().getZ()) {
                return;
            }
            z = zone.min().getZ();
            y++;
            if (y <= zone.max().getY()) {
                return;
            }
            y = zone.min().getY();
            x++;
            if (x <= zone.max().getX()) {
                return;
            }
            zoneIndex++;
            initialized = false;
        }
    }

    private static final class MthWrap {
        private MthWrap() {
        }

        static double degrees(double radians) {
            return radians * 180.0D / Math.PI;
        }
    }
}
