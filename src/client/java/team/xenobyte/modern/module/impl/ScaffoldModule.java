package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class ScaffoldModule extends XenoModule {
    private static final Direction[] SUPPORT_ORDER = new Direction[] {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
    };

    private final ModuleSetting inArea = setting("InArea", ModuleSetting.bool("InArea", true)
        .describe("Builds a flat square platform on the single block layer under the player."));
    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 2.0D, 0.0D, 5.0D, 1.0D)
        .describe("Area half-size. Radius 2 means a 5x5 target layer."));
    private final ModuleSetting ahead = setting("Ahead", ModuleSetting.number("Ahead", 3.0D, 1.0D, 8.0D, 1.0D)
        .describe("Forward bridge length used when InArea is off."));
    private final ModuleSetting perTick = setting("PerTick", ModuleSetting.number("PerTick", 4.0D, 1.0D, 16.0D, 1.0D)
        .describe("Maximum place attempts per client tick."));
    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 0.0D, 0.0D, 10.0D, 1.0D)
        .describe("Ticks to wait between placement waves."));
    private final ModuleSetting autoSwap = setting("AutoSwap", ModuleSetting.bool("AutoSwap", true)
        .describe("Automatically selects the first block stack from the hotbar."));
    private final ModuleSetting swing = setting("Swing", ModuleSetting.bool("Swing", true)
        .describe("Swings the hand after a placement attempt."));

    private int cooldown;
    private int lastLayerY = Integer.MIN_VALUE;
    private int lastAttempts;
    private int lastPlaced;
    private long lastLogNanos;

    public ScaffoldModule() {
        super("Scaffold", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onDisable(Minecraft client) {
        cooldown = 0;
        lastAttempts = 0;
        lastPlaced = 0;
        lastLayerY = Integer.MIN_VALUE;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null || client.screen != null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        int blockSlot = selectedBlockSlot(client);
        if (blockSlot < 0) {
            log("Scaffold skipped: no block in hotbar");
            return;
        }
        selectHotbar(client, blockSlot);

        int layerY = Mth.floor(client.player.getY()) - 1;
        BlockPos base = new BlockPos(Mth.floor(client.player.getX()), layerY, Mth.floor(client.player.getZ()));
        List<BlockPos> targets = collectTargets(client, base);
        lastLayerY = layerY;
        lastAttempts = targets.size();
        lastPlaced = 0;

        int limit = Math.max(1, perTick.intValue());
        for (BlockPos target : targets) {
            if (lastPlaced >= limit) {
                break;
            }
            if (placeAt(client, target)) {
                lastPlaced++;
            }
        }

        if (lastPlaced > 0) {
            cooldown = Math.max(0, delay.intValue());
        }
        log("Scaffold tick: layerY=" + layerY
            + ", base=" + base.toShortString()
            + ", mode=" + (inArea.boolValue() ? "area" : "bridge")
            + ", targets=" + targets.size()
            + ", placed=" + lastPlaced
            + ", slot=" + blockSlot);
    }

    private List<BlockPos> collectTargets(Minecraft client, BlockPos base) {
        List<BlockPos> targets = new ArrayList<>();
        if (inArea.boolValue()) {
            int r = Math.max(0, radius.intValue());
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    addIfValid(client, targets, base.offset(dx, 0, dz));
                }
            }
        } else {
            Direction forward = horizontalFacing(client);
            Direction right = forward.getClockWise();
            int width = Math.max(0, Math.min(2, radius.intValue()));
            int length = Math.max(1, ahead.intValue());
            for (int step = 0; step <= length; step++) {
                for (int side = -width; side <= width; side++) {
                    BlockPos target = base.relative(forward, step).relative(right, side);
                    addIfValid(client, targets, target);
                }
            }
        }
        targets.sort(Comparator
            .comparingInt((BlockPos pos) -> pos.distManhattan(base))
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        return targets;
    }

    private void addIfValid(Minecraft client, List<BlockPos> targets, BlockPos target) {
        if (target.getY() != Mth.floor(client.player.getY()) - 1) {
            return;
        }
        if (!canPlaceInto(client, target)) {
            return;
        }
        if (supportHit(client, target) == null) {
            return;
        }
        targets.add(target);
    }

    private boolean placeAt(Minecraft client, BlockPos target) {
        if (!canPlaceInto(client, target)) {
            return false;
        }
        BlockHitResult hit = supportHit(client, target);
        if (hit == null) {
            return false;
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (swing.boolValue()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private BlockHitResult supportHit(Minecraft client, BlockPos target) {
        for (Direction direction : SUPPORT_ORDER) {
            BlockPos support = target.relative(direction);
            if (!isSupport(client, support)) {
                continue;
            }
            Direction face = direction.getOpposite();
            Vec3 hit = Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D
            );
            return new BlockHitResult(hit, face, support, false);
        }
        return null;
    }

    private boolean canPlaceInto(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(client.level, pos).isEmpty();
    }

    private boolean isSupport(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(client.level, pos).isEmpty();
    }

    private int selectedBlockSlot(Minecraft client) {
        int selected = client.player.getInventory().selected;
        if (isBlockSlot(client, selected)) {
            return selected;
        }
        if (!autoSwap.boolValue()) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isBlockSlot(client, slot)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isBlockSlot(Minecraft client, int slot) {
        if (slot < 0 || slot > 8 || slot >= client.player.getInventory().items.size()) {
            return false;
        }
        ItemStack stack = client.player.getInventory().items.get(slot);
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    private void selectHotbar(Minecraft client, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8 || hotbarSlot == client.player.getInventory().selected) {
            return;
        }
        client.player.getInventory().selected = hotbarSlot;
        client.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
    }

    private Direction horizontalFacing(Minecraft client) {
        float yaw = client.player.getYRot();
        int quadrant = Mth.floor((yaw * 4.0F / 360.0F) + 0.5D) & 3;
        return switch (quadrant) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private void log(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 3_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(message);
    }

    @Override
    public String description() {
        return "Places hotbar blocks on one flat layer directly under the player to form bridges or platforms.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        String layer = lastLayerY == Integer.MIN_VALUE ? "none" : Integer.toString(lastLayerY);
        return "Scaffold mode=" + (inArea.boolValue() ? "area" : "bridge")
            + " layer=" + layer
            + " radius=" + radius.displayValue()
            + " placed=" + lastPlaced + "/" + lastAttempts
            + " delay=" + delay.displayValue()
            + " perTick=" + perTick.displayValue()
            + " swap=" + (autoSwap.boolValue() ? "on" : "off")
            + " y=" + (client != null && client.player != null ? String.format(Locale.ROOT, "%.2f", client.player.getY()) : "n/a");
    }
}
