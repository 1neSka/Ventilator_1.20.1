package team.xenobyte.modern.module.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class SearchItemModule extends XenoModule {
    private static final int OPEN_WAIT_TICKS = 16;
    private static final int PROCESS_TIMEOUT_TICKS = 100;
    private static final int MAX_CLICKS_PER_TICK = 4;
    private static final int CEILING_RANGE = 5;
    private static final int VERTICAL_BYPASS_COOLDOWN_TICKS = 18;
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST
    };

    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 0, "Count", "Collect")
        .describe("Counts matching items or shift-clicks matching stacks from opened containers."));
    private final ModuleSetting source = setting("Source", ModuleSetting.choice("Source", 0, "Held", "Hovered", "Numeric")
        .describe("Target item source: held item, hovered/JEI item, or numeric registry id."));
    private final ModuleSetting itemId = setting("ItemId", ModuleSetting.number("ItemId", 0.0D, 0.0D, 250000.0D, 1.0D)
        .describe("Numeric item registry id used when Source is Numeric."));
    private final ModuleSetting meta = setting("Meta", ModuleSetting.number("Meta", -1.0D, -1.0D, 32767.0D, 1.0D)
        .describe("Damage/meta filter. -1 matches any damage."));
    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 24.0D, 4.0D, 96.0D, 4.0D)
        .describe("Cube radius used to scan loaded blocks around the player."));
    private final ModuleSetting amount = setting("Amount", ModuleSetting.number("Amount", 0.0D, 0.0D, 2304.0D, 1.0D)
        .describe("Collect target. 0 means collect every matching stack found."));
    private final ModuleSetting move = setting("Move", ModuleSetting.bool("Move", true)
        .describe("Motion-flies directly to candidate containers before opening them."));
    private final ModuleSetting speed = setting("Speed", ModuleSetting.number("Speed", 0.8D, 0.1D, 4.0D, 0.1D)
        .describe("Movement speed used while Move is enabled."));
    private final ModuleSetting filter = setting("Filter", ModuleSetting.choice("Filter", 0, "Menus+Names", "Menus", "Names")
        .describe("Container candidate filter. Menus uses menu providers; Names is a storage-name fallback."));
    private final ModuleSetting scanPerTick = setting("ScanTick", ModuleSetting.number("ScanTick", 4096.0D, 512.0D, 32768.0D, 512.0D)
        .describe("How many block positions are checked per tick while scanning."));

    private final List<BlockPos> candidates = new ArrayList<>();
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Map<BlockPos, BlockPos> candidateByKey = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();

    private TargetSpec target;
    private BlockPos scanOrigin;
    private BlockPos currentContainer;
    private boolean scanning;
    private boolean countedCurrentMenu;
    private int scanRadius;
    private int scanX;
    private int scanY;
    private int scanZ;
    private int scanned;
    private int openWaitTicks;
    private int processTicks;
    private int opened;
    private int skipped;
    private int totalFound;
    private int collected;
    private int moveTicks;
    private int stuckTicks;
    private int jitterTicks;
    private int verticalBypassCooldown;
    private int recoveryAttempts;
    private int lastBypassY = Integer.MIN_VALUE;
    private int verticalBypasses;
    private double previousMoveDistance = Double.MAX_VALUE;
    private Vec3 jitterVector = Vec3.ZERO;
    private BlockPos lastMoveTarget;
    private boolean hadMovementSnapshot;
    private boolean previousNoGravity;
    private boolean previousNoPhysics;
    private long lastLogNanos;
    private long lastJeiFailureLogNanos;
    private String phase = "idle";

    public SearchItemModule() {
        super("SearchItem", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        resetState();
        snapshotMovement(client);
        if (client != null && client.player != null) {
            target = resolveTarget(client);
            if (target != null) {
                message(client, "target " + target.describe());
                BootstrapLog.info("SearchItem enabled: target=" + target.describe()
                    + ", mode=" + mode.choiceValue()
                    + ", radius=" + radius.displayValue()
                    + ", amount=" + amount.displayValue()
                    + ", source=" + source.choiceValue()
                    + ", filter=" + filter.choiceValue());
            }
            if (client.screen != null) {
                client.setScreen(null);
            }
        }
    }

    @Override
    public void onDisable(Minecraft client) {
        restoreMovement(client);
        if (client != null && client.player != null && currentContainer != null && client.player.containerMenu != client.player.inventoryMenu) {
            closeContainer(client);
        }
        resetState();
    }

    @Override
    public boolean allowBindInGui() {
        return true;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null) {
            return;
        }

        if (target == null) {
            target = resolveTarget(client);
            if (target == null) {
                message(client, "no target item (" + source.choiceValue() + ")");
                BootstrapLog.info("SearchItem stopped: no target item, source=" + source.choiceValue());
                setEnabled(client, false);
                return;
            }
            message(client, "target " + target.describe());
        }

        if (client.player.containerMenu != client.player.inventoryMenu && client.screen instanceof AbstractContainerScreen<?>) {
            processOpenContainer(client);
            return;
        }

        if (scanning) {
            scanStep(client);
            return;
        }

        if (scanOrigin == null) {
            startScan(client);
            return;
        }

        if (currentContainer == null) {
            currentContainer = nextContainer(client);
            countedCurrentMenu = false;
            processTicks = 0;
            resetMoveState();
            if (currentContainer == null) {
                finish(client, "done");
                return;
            }
        }

        if (!isCandidateContainer(client, currentContainer)) {
            skipped++;
            currentContainer = null;
            return;
        }

        double distance = client.player.getEyePosition(0.0F).distanceTo(Vec3.atCenterOf(currentContainer));
        if (distance > openRange()) {
            if (!move.boolValue()) {
                skipped++;
                BootstrapLog.info("SearchItem skip out-of-range: pos=" + currentContainer.toShortString()
                    + ", dist=" + fmt(distance)
                    + ", openRange=" + fmt(openRange()));
                currentContainer = null;
                return;
            }
            moveToward(client, currentContainer, distance);
            phase = "moving";
            log("SearchItem moving: target=" + currentContainer.toShortString()
                + ", dist=" + fmt(distance)
                + ", queue=" + queue.size());
            return;
        }

        stopMotion(client);
        if (openWaitTicks <= 0) {
            openContainer(client, currentContainer);
            openWaitTicks = OPEN_WAIT_TICKS;
            phase = "opening";
            return;
        }
        openWaitTicks--;
        if (openWaitTicks == 0) {
            skipped++;
            BootstrapLog.info("SearchItem open timeout: pos=" + currentContainer.toShortString());
            ModuleMessageLog.push("SearchItem", "open timeout " + currentContainer.toShortString());
            currentContainer = null;
        }
    }

    private void startScan(Minecraft client) {
        scanOrigin = client.player.blockPosition();
        scanRadius = radius.intValue();
        scanX = -scanRadius;
        scanY = -scanRadius;
        scanZ = -scanRadius;
        scanned = 0;
        candidates.clear();
        queue.clear();
        candidateByKey.clear();
        visited.clear();
        scanning = true;
        phase = "scanning";
        BootstrapLog.info("SearchItem scan started: origin=" + scanOrigin.toShortString()
            + ", radius=" + scanRadius
            + ", target=" + target.describe()
            + ", filter=" + filter.choiceValue());
        ModuleMessageLog.push("SearchItem", "scan started r=" + scanRadius + " target=" + target.shortName());
    }

    private void scanStep(Minecraft client) {
        int budget = Math.max(1, scanPerTick.intValue());
        for (int i = 0; i < budget && scanning; i++) {
            BlockPos pos = scanOrigin.offset(scanX, scanY, scanZ);
            if (insideBuildHeight(client, pos.getY()) && isCandidateContainer(client, pos)) {
                addCandidate(client, pos);
            }
            scanned++;
            advanceScan();
        }
        log("SearchItem scanning: checked=" + scanned
            + ", candidates=" + candidateByKey.size()
            + ", radius=" + scanRadius);
    }

    private void advanceScan() {
        scanZ++;
        if (scanZ <= scanRadius) {
            return;
        }
        scanZ = -scanRadius;
        scanY++;
        if (scanY <= scanRadius) {
            return;
        }
        scanY = -scanRadius;
        scanX++;
        if (scanX <= scanRadius) {
            return;
        }
        scanning = false;
        candidates.clear();
        candidates.addAll(candidateByKey.values());
        candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(Vec3.atCenterOf(scanOrigin))));
        queue.addAll(candidates);
        phase = "queued";
        BootstrapLog.info("SearchItem scan completed: checked=" + scanned
            + ", candidates=" + candidates.size()
            + ", target=" + target.describe());
        ModuleMessageLog.push("SearchItem", "scan done containers=" + candidates.size());
    }

    private BlockPos nextContainer(Minecraft client) {
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            BlockPos key = containerKey(client, pos);
            if (visited.add(key) && isCandidateContainer(client, pos)) {
                if (!key.equals(pos)) {
                    BootstrapLog.info("SearchItem dedupe open: key=" + key.toShortString()
                        + ", openPos=" + pos.toShortString());
                }
                openWaitTicks = 0;
                return pos;
            }
        }
        return null;
    }

    private void addCandidate(Minecraft client, BlockPos pos) {
        BlockPos key = containerKey(client, pos);
        BlockPos candidate = pos.immutable();
        BlockPos previous = candidateByKey.get(key);
        if (previous == null || isCloserToScanOrigin(candidate, previous)) {
            candidateByKey.put(key, candidate);
        }
    }

    private boolean isCloserToScanOrigin(BlockPos candidate, BlockPos previous) {
        if (scanOrigin == null) {
            return false;
        }
        double candidateDistance = Vec3.atCenterOf(candidate).distanceToSqr(Vec3.atCenterOf(scanOrigin));
        double previousDistance = Vec3.atCenterOf(previous).distanceToSqr(Vec3.atCenterOf(scanOrigin));
        return candidateDistance < previousDistance;
    }

    private boolean isCandidateContainer(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.AIR) {
            return false;
        }

        boolean menu = false;
        if (!"Names".equals(filter.choiceValue())) {
            try {
                menu = state.getMenuProvider(client.level, pos) != null;
            } catch (RuntimeException ignored) {
                menu = false;
            }
            if (!menu) {
                BlockEntity entity = client.level.getBlockEntity(pos);
                menu = entity instanceof MenuProvider || entity instanceof Container;
            }
        }

        boolean name = !"Menus".equals(filter.choiceValue()) && storageNameMatch(state.getBlock());
        return switch (filter.choiceValue()) {
            case "Menus" -> menu;
            case "Names" -> name;
            default -> menu || name;
        };
    }

    private boolean storageNameMatch(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("chest")
            || path.contains("barrel")
            || path.contains("shulker")
            || path.contains("drawer")
            || path.contains("crate")
            || path.contains("cabinet")
            || path.contains("shelf")
            || path.contains("storage")
            || path.contains("furnace")
            || path.contains("hopper")
            || path.contains("cache");
    }

    private BlockPos containerKey(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        Block block = state.getBlock();
        if (!isChestLikeBlock(block)) {
            return pos.immutable();
        }

        Set<BlockPos> connected = new HashSet<>();
        Deque<BlockPos> pending = new ArrayDeque<>();
        connected.add(pos.immutable());
        pending.add(pos.immutable());
        while (!pending.isEmpty() && connected.size() < 8) {
            BlockPos current = pending.removeFirst();
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                BlockPos neighbor = current.relative(direction);
                if (connected.contains(neighbor)) {
                    continue;
                }
                BlockState neighborState = client.level.getBlockState(neighbor);
                if (neighborState.getBlock() == block && isCandidateContainer(client, neighbor)) {
                    BlockPos immutable = neighbor.immutable();
                    connected.add(immutable);
                    pending.add(immutable);
                }
            }
        }

        BlockPos best = pos.immutable();
        for (BlockPos candidate : connected) {
            best = minPosition(best, candidate);
        }
        return best;
    }

    private boolean isChestLikeBlock(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("chest") && !path.contains("ender_chest");
    }

    private BlockPos minPosition(BlockPos left, BlockPos right) {
        if (right.getX() != left.getX()) {
            return right.getX() < left.getX() ? right : left;
        }
        if (right.getY() != left.getY()) {
            return right.getY() < left.getY() ? right : left;
        }
        return right.getZ() < left.getZ() ? right : left;
    }

    private void openContainer(Minecraft client, BlockPos pos) {
        Direction face = faceFromPlayer(client, pos);
        Vec3 hit = Vec3.atCenterOf(pos).add(
            face.getStepX() * 0.5D,
            face.getStepY() * 0.5D,
            face.getStepZ() * 0.5D
        );
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        client.player.swing(InteractionHand.MAIN_HAND);
        opened++;
        BootstrapLog.info("SearchItem open attempt: pos=" + pos.toShortString()
            + ", block=" + BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock())
            + ", opened=" + opened
            + ", target=" + target.describe());
        ModuleMessageLog.push("SearchItem", "open " + pos.toShortString());
    }

    private void processOpenContainer(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        processTicks++;
        if (!countedCurrentMenu) {
            int found = countMatching(menu, client);
            totalFound += found;
            countedCurrentMenu = true;
            BootstrapLog.info("SearchItem container counted: pos=" + posText(currentContainer)
                + ", found=" + found
                + ", totalFound=" + totalFound
                + ", mode=" + mode.choiceValue());
            ModuleMessageLog.push("SearchItem", "count +" + found + " total=" + totalFound);
            if ("Count".equals(mode.choiceValue())) {
                closeCurrent(client);
                return;
            }
        }

        if (!"Collect".equals(mode.choiceValue())) {
            closeCurrent(client);
            return;
        }
        if (amount.intValue() > 0 && collected >= amount.intValue()) {
            closeCurrent(client);
            finish(client, "amount reached");
            return;
        }

        int moved = collectMatching(menu, client);
        if (moved <= 0 || processTicks > PROCESS_TIMEOUT_TICKS) {
            closeCurrent(client);
            return;
        }
        BootstrapLog.info("SearchItem collected pulse: movedEstimate=" + moved
            + ", collected=" + collected
            + ", amount=" + amount.displayValue()
            + ", pos=" + posText(currentContainer));
        ModuleMessageLog.push("SearchItem", "collect +" + moved + " total=" + collected);
    }

    private int countMatching(AbstractContainerMenu menu, Minecraft client) {
        int count = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (isPlayerSlot(client, slot) || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (target.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int collectMatching(AbstractContainerMenu menu, Minecraft client) {
        List<Integer> matches = matchingSlotIndexes(menu, client);
        if (amount.intValue() > 0) {
            matches.sort(Comparator.comparingInt(index -> menu.slots.get(index).getItem().getCount()));
        }

        int moved = 0;
        int clicks = 0;
        for (int slotIndex : matches) {
            if (clicks >= MAX_CLICKS_PER_TICK) {
                break;
            }
            if (amount.intValue() > 0 && collected >= amount.intValue()) {
                break;
            }
            Slot slot = menu.slots.get(slotIndex);
            if (!slot.hasItem() || !target.matches(slot.getItem())) {
                continue;
            }
            int stackCount = slot.getItem().getCount();
            client.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.QUICK_MOVE, client.player);
            moved += stackCount;
            collected += stackCount;
            clicks++;
        }
        return moved;
    }

    private List<Integer> matchingSlotIndexes(AbstractContainerMenu menu, Minecraft client) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isPlayerSlot(client, slot) && slot.hasItem() && target.matches(slot.getItem())) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private boolean isPlayerSlot(Minecraft client, Slot slot) {
        return slot.container == client.player.getInventory();
    }

    private void closeCurrent(Minecraft client) {
        closeContainer(client);
        currentContainer = null;
        openWaitTicks = 0;
        processTicks = 0;
        countedCurrentMenu = false;
    }

    private void closeContainer(Minecraft client) {
        client.player.closeContainer();
        client.setScreen(null);
    }

    private void finish(Minecraft client, String reason) {
        String message = reason
            + " found=" + totalFound
            + " collected=" + collected
            + " opened=" + opened
            + " skipped=" + skipped;
        message(client, message);
        BootstrapLog.info(message + ", target=" + (target == null ? "none" : target.describe())
            + ", candidates=" + candidates.size()
            + ", visited=" + visited.size());
        setEnabled(client, false);
    }

    private TargetSpec resolveTarget(Minecraft client) {
        String activeSource = source.choiceValue();
        if ("Numeric".equals(activeSource)) {
            Item item = BuiltInRegistries.ITEM.byId(itemId.intValue());
            if (item == null || item == Items.AIR) {
                return null;
            }
            return TargetSpec.from(item, meta.intValue());
        }

        ItemStack stack = ItemStack.EMPTY;
        if ("Hovered".equals(activeSource)) {
            stack = selectedScreenStack(client);
        }
        if (stack.isEmpty()) {
            stack = client.player.getMainHandItem();
        }
        if (stack.isEmpty()) {
            stack = client.player.getOffhandItem();
        }
        if (stack.isEmpty()) {
            return null;
        }
        return TargetSpec.from(stack.getItem(), meta.intValue());
    }

    private ItemStack selectedScreenStack(Minecraft client) {
        ItemStack fromJei = stackFromJeiHover();
        if (!fromJei.isEmpty()) {
            return fromJei;
        }
        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            ItemStack hovered = hoveredStack(screen);
            if (!hovered.isEmpty()) {
                return hovered;
            }
        }
        ItemStack carried = client.player.containerMenu.getCarried();
        return carried == null ? ItemStack.EMPTY : carried;
    }

    private ItemStack stackFromJeiHover() {
        try {
            Object runtime = invokeStaticNoArgs("mezz.jei.common.Internal", "getJeiRuntime");
            if (runtime == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = stackFromOverlay(invokeNoArgs(runtime, "getIngredientListOverlay"));
            if (!stack.isEmpty()) {
                return stack;
            }
            return stackFromOverlay(invokeNoArgs(runtime, "getBookmarkOverlay"));
        } catch (ClassNotFoundException ignored) {
            return ItemStack.EMPTY;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            logJeiFailure(error);
            return ItemStack.EMPTY;
        }
    }

    private ItemStack stackFromOverlay(Object overlay) throws ReflectiveOperationException {
        if (overlay == null) {
            return ItemStack.EMPTY;
        }
        Object optional = invokeNoArgs(overlay, "getIngredientUnderMouse");
        return stackFromOptional(optional);
    }

    private ItemStack stackFromOptional(Object optional) throws ReflectiveOperationException {
        if (!(optional instanceof Optional<?> typedOptional) || typedOptional.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stackFromIngredient(typedOptional.get());
    }

    private ItemStack stackFromIngredient(Object ingredient) throws ReflectiveOperationException {
        if (ingredient instanceof ItemStack stack) {
            return stack;
        }
        Object itemStackOptional = invokeNoArgs(ingredient, "getItemStack");
        if (itemStackOptional instanceof Optional<?> optional && optional.orElse(null) instanceof ItemStack stack) {
            return stack;
        }
        Object rawIngredient = invokeNoArgs(ingredient, "getIngredient");
        if (rawIngredient instanceof ItemStack stack) {
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private Object invokeStaticNoArgs(String className, String methodName) throws ReflectiveOperationException {
        Class<?> target = loadClass(className);
        Method method = target.getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = target.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            Minecraft.class.getClassLoader(),
            SearchItemModule.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Try the next loader; injected jars and Forge mods can have different parents.
            }
        }
        return Class.forName(name);
    }

    private ItemStack hoveredStack(AbstractContainerScreen<?> screen) {
        try {
            Method method = AbstractContainerScreen.class.getDeclaredMethod("getSlotUnderMouse");
            method.setAccessible(true);
            Object slot = method.invoke(screen);
            if (slot instanceof Slot hovered) {
                return hovered.getItem();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional helper; held item remains the fallback.
        }
        return ItemStack.EMPTY;
    }

    private void moveToward(Minecraft client, BlockPos pos, double distance) {
        snapshotMovement(client);
        if (!pos.equals(lastMoveTarget)) {
            resetMoveState();
            lastMoveTarget = pos.immutable();
        }
        moveTicks++;

        Vec3 targetCenter = Vec3.atCenterOf(pos);
        Vec3 eye = client.player.getEyePosition(0.0F);
        Vec3 delta = targetCenter.subtract(eye);
        lookAt(client, targetCenter);
        if (distance < 0.001D) {
            stopMotion(client);
            return;
        }

        boolean notProgressing = distance >= previousMoveDistance - 0.035D;
        if (notProgressing) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            jitterTicks = 0;
            jitterVector = Vec3.ZERO;
        }
        previousMoveDistance = distance;

        if (stuckTicks >= 6) {
            if (jitterTicks <= 0) {
                jitterTicks = 8;
                recoveryAttempts++;
                jitterVector = hardJitterVector(client, pos, recoveryAttempts);
                BootstrapLog.info("SearchItem hard jitter: pos=" + pos.toShortString()
                    + ", stuckTicks=" + stuckTicks
                    + ", moveTicks=" + moveTicks
                    + ", attempt=" + recoveryAttempts
                    + ", jitter=" + fmt(jitterVector.x) + "/" + fmt(jitterVector.y) + "/" + fmt(jitterVector.z)
                    + ", dist=" + fmt(distance));
                ModuleMessageLog.push("SearchItem", "jitter " + pos.toShortString() + " stuck=" + stuckTicks);
            }
            jitterTicks--;
        }

        if (verticalBypassCooldown > 0) {
            verticalBypassCooldown--;
        }
        if (stuckTicks >= 8 && tryVerticalBypass(client, pos)) {
            return;
        }

        if (moveTicks > 150 && stuckTicks > 30) {
            skipped++;
            BootstrapLog.info("SearchItem stuck skip: pos=" + pos.toShortString()
                + ", moveTicks=" + moveTicks
                + ", stuckTicks=" + stuckTicks
                + ", recoveryAttempts=" + recoveryAttempts
                + ", verticalBypasses=" + verticalBypasses
                + ", dist=" + fmt(distance));
            ModuleMessageLog.push("SearchItem", "skip stuck " + pos.toShortString());
            currentContainer = null;
            resetMoveState();
            return;
        }

        client.player.noPhysics = true;
        client.player.setNoGravity(true);
        client.player.setOnGround(false);
        client.player.fallDistance = 0.0F;
        Vec3 motion = delta.normalize().scale(Math.min(speed.doubleValue(), Math.max(0.05D, distance - openRange() + 0.25D)));
        if (stuckTicks >= 6) {
            motion = motion.add(jitterVector);
        }
        client.player.setDeltaMovement(motion);
    }

    private boolean tryVerticalBypass(Minecraft client, BlockPos targetPos) {
        if (verticalBypassCooldown > 0) {
            return false;
        }
        int preferred = Integer.compare(targetPos.getY(), client.player.blockPosition().getY());
        BlockPos pocket = findVerticalPocket(client, preferred);
        if (pocket == null) {
            return false;
        }
        Vec3 current = client.player.position();
        Vec3 next = new Vec3(current.x, pocket.getY() + 0.08D, current.z);
        client.player.setPos(next.x, next.y, next.z);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.noPhysics = true;
        client.player.setNoGravity(true);
        client.player.fallDistance = 0.0F;
        client.player.setOnGround(false);
        verticalBypassCooldown = VERTICAL_BYPASS_COOLDOWN_TICKS;
        lastBypassY = pocket.getY();
        verticalBypasses++;
        stuckTicks = 0;
        jitterTicks = 0;
        previousMoveDistance = Double.MAX_VALUE;
        BootstrapLog.info("SearchItem ceiling bypass: target=" + targetPos.toShortString()
            + ", pocket=" + pocket.toShortString()
            + ", range=" + CEILING_RANGE
            + ", bypasses=" + verticalBypasses);
        ModuleMessageLog.push("SearchItem", "ceiling Y " + pocket.getY() + " -> " + targetPos.toShortString());
        return true;
    }

    private BlockPos findVerticalPocket(Minecraft client, int preferredDirection) {
        BlockPos base = client.player.blockPosition();
        int[] directions = preferredDirection < 0 ? new int[] {-1, 1} : new int[] {1, -1};
        if (preferredDirection == 0) {
            directions = new int[] {1, -1};
        }
        for (int direction : directions) {
            for (int distance = 1; distance <= CEILING_RANGE; distance++) {
                int y = base.getY() + direction * distance;
                if (!insideBuildHeight(client, y) || y == lastBypassY) {
                    continue;
                }
                BlockPos candidate = new BlockPos(base.getX(), y, base.getZ());
                if (isTwoPassable(client, candidate) && !hasLavaNear(client, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isTwoPassable(Minecraft client, BlockPos footCell) {
        return isPassable(client, footCell) && isPassable(client, footCell.above());
    }

    private boolean isPassable(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.getCollisionShape(client.level, pos).isEmpty()
            && state.getFluidState().isEmpty();
    }

    private boolean hasLavaNear(Minecraft client, BlockPos footCell) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockState state = client.level.getBlockState(footCell.offset(0, dy, 0));
            if (!state.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Vec3 hardJitterVector(Minecraft client, BlockPos targetPos, int attempt) {
        Vec3 toTarget = Vec3.atCenterOf(targetPos).subtract(client.player.position());
        Vec3 horizontal = new Vec3(toTarget.x, 0.0D, toTarget.z);
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
            case 1 -> back.scale(1.85D).add(0.0D, 0.90D, 0.0D);
            case 2 -> right.scale(1.65D).add(0.0D, 1.15D, 0.0D);
            case 3 -> left.scale(1.65D).add(0.0D, -0.55D, 0.0D);
            case 4 -> back.add(right).normalize().scale(2.05D).add(0.0D, 0.35D, 0.0D);
            case 5 -> horizontal.scale(0.65D).add(0.0D, 1.35D, 0.0D);
            default -> back.add(left).normalize().scale(2.10D).add(0.0D, -0.35D, 0.0D);
        };
        return base.add(random.nextDouble(-0.25D, 0.25D), random.nextDouble(-0.15D, 0.15D), random.nextDouble(-0.25D, 0.25D));
    }

    private void stopMotion(Minecraft client) {
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.fallDistance = 0.0F;
    }

    private void lookAt(Minecraft client, Vec3 point) {
        Vec3 eye = client.player.getEyePosition(0.0F);
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float)(-Math.toDegrees(Math.atan2(delta.y, horizontal)));
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.yRotO = yaw;
        client.player.xRotO = pitch;
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

    private boolean insideBuildHeight(Minecraft client, int y) {
        return y >= client.level.getMinBuildHeight() && y < client.level.getMaxBuildHeight();
    }

    private double openRange() {
        return 4.75D;
    }

    private void snapshotMovement(Minecraft client) {
        if (client == null || client.player == null || hadMovementSnapshot) {
            return;
        }
        previousNoGravity = client.player.isNoGravity();
        previousNoPhysics = client.player.noPhysics;
        hadMovementSnapshot = true;
    }

    private void restoreMovement(Minecraft client) {
        if (client == null || client.player == null || !hadMovementSnapshot) {
            return;
        }
        client.player.setNoGravity(previousNoGravity);
        client.player.noPhysics = previousNoPhysics;
        client.player.setDeltaMovement(Vec3.ZERO);
        hadMovementSnapshot = false;
    }

    private void resetState() {
        candidates.clear();
        queue.clear();
        candidateByKey.clear();
        visited.clear();
        target = null;
        scanOrigin = null;
        currentContainer = null;
        scanning = false;
        countedCurrentMenu = false;
        scanRadius = 0;
        scanX = 0;
        scanY = 0;
        scanZ = 0;
        scanned = 0;
        openWaitTicks = 0;
        processTicks = 0;
        opened = 0;
        skipped = 0;
        totalFound = 0;
        collected = 0;
        resetMoveState();
        phase = "idle";
    }

    private void resetMoveState() {
        moveTicks = 0;
        stuckTicks = 0;
        jitterTicks = 0;
        verticalBypassCooldown = 0;
        recoveryAttempts = 0;
        lastBypassY = Integer.MIN_VALUE;
        verticalBypasses = 0;
        previousMoveDistance = Double.MAX_VALUE;
        jitterVector = Vec3.ZERO;
        lastMoveTarget = null;
    }

    private void logJeiFailure(Throwable error) {
        long now = System.nanoTime();
        if (now - lastJeiFailureLogNanos < 5_000_000_000L) {
            return;
        }
        lastJeiFailureLogNanos = now;
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause()
            : error;
        BootstrapLog.info("SearchItem JEI hover unavailable: " + cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
    }

    private void log(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(message);
    }

    private void message(Minecraft client, String message) {
        ModuleMessageLog.push("SearchItem", message);
        client.player.displayClientMessage(Component.literal("SearchItem: " + message), true);
    }

    private String posText(BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public String description() {
        return "Searches nearby container blocks for a target item, then counts or collects matching stacks after opening each container.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "SearchItem " + phase
            + " target=" + (target == null ? "none" : target.shortName())
            + " found=" + totalFound
            + " collected=" + collected
            + " opened=" + opened
            + " queue=" + queue.size()
            + " scan=" + scanned + "/" + candidates.size();
    }

    private record TargetSpec(Item item, int meta, int rawId, String id) {
        static TargetSpec from(Item item, int meta) {
            return new TargetSpec(item, meta, BuiltInRegistries.ITEM.getId(item), BuiltInRegistries.ITEM.getKey(item).toString());
        }

        boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty() || !stack.is(item)) {
                return false;
            }
            return meta < 0 || stack.getDamageValue() == meta;
        }

        String shortName() {
            return rawId + ":" + meta;
        }

        String describe() {
            return id + " " + rawId + ":" + meta;
        }
    }
}
