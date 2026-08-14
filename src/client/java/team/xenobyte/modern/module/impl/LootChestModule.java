package team.xenobyte.modern.module.impl;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

public class LootChestModule extends XenoModule {
    private static final int OPEN_TIMEOUT_TICKS = 32;
    private static final int OPEN_RANGE_GRACE_TICKS = 14;
    private static final int LATE_OPEN_RECOVERY_TICKS = 60;
    private static final int EMPTY_MENU_GRACE_TICKS = 8;
    private static final int PROCESS_TIMEOUT_TICKS = 80;
    private static final Set<Integer> DUNGEON_CHEST_IDS = Set.of(4944, 4945, 4946);
    private static final Set<Integer> DUNGEON_DENY_IDS = Set.of(4947);
    private static final String DUNGEON_NAME_FRAGMENT = "сундук руин";
    private static final String DUNGEON_URN_FRAGMENT = "урна руин";
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST
    };

    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 0, "All", "Dungeon")
        .describe("All loots every matching storage container. Dungeon only loots DG1 chest block ids 4944/4945/4946."));
    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 5.0D, 1.0D, 16.0D, 1.0D)
        .describe("Block radius around the player scanned for lootable containers."));
    private final ModuleSetting openRange = setting("OpenRange", ModuleSetting.number("OpenRange", 5.2D, 2.0D, 8.0D, 0.1D)
        .describe("Maximum eye-to-container distance used before attempting to open a container."));
    private final ModuleSetting clickPerTick = setting("ClickTick", ModuleSetting.number("ClickTick", 54.0D, 1.0D, 108.0D, 1.0D)
        .describe("Maximum quick-move clicks sent per tick while looting an opened container."));
    private final ModuleSetting scanPerTick = setting("ScanTick", ModuleSetting.number("ScanTick", 4096.0D, 512.0D, 32768.0D, 512.0D)
        .describe("How many block positions are checked per tick while scanning."));
    private final ModuleSetting rescanCooldown = setting("Cooldown", ModuleSetting.number("Cooldown", 8.0D, 0.0D, 80.0D, 1.0D)
        .describe("Ticks to wait between completed scans when no queued container is available."));
    private final ModuleSetting filter = setting("Filter", ModuleSetting.choice("Filter", 0, "Menus+Names", "Menus", "Names")
        .describe("Container candidate filter. Menus uses menu providers; Names is a storage-name fallback."));

    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Map<BlockPos, BlockPos> candidateByKey = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final Set<BlockPos> failed = new HashSet<>();

    private BlockPos scanOrigin;
    private BlockPos currentContainer;
    private boolean scanning;
    private int scanRadius;
    private int scanX;
    private int scanY;
    private int scanZ;
    private int scanned;
    private int opened;
    private int lootedStacks;
    private int lootedItems;
    private int skipped;
    private int openWaitTicks;
    private int processTicks;
    private int emptyProcessTicks;
    private int cooldownTicks;
    private int lastOpenAttemptAgeTicks = Integer.MAX_VALUE;
    private int lateOpenRecoveries;
    private long lastLogNanos;
    private String phase = "idle";
    private BlockPos lastOpenAttemptContainer;
    private boolean hasSavedLook;
    private float savedYaw;
    private float savedPitch;
    private float savedHeadYaw;
    private float savedBodyYaw;

    public LootChestModule() {
        super("LootChest", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        resetState();
        if (client != null && client.player != null) {
            ModuleMessageLog.push("LootChest", "enabled r=" + radius.displayValue());
            BootstrapLog.info("LootChest enabled: radius=" + radius.displayValue()
                + ", mode=" + mode.choiceValue()
                + ", openRange=" + openRange.displayValue()
                + ", clickPerTick=" + clickPerTick.displayValue()
                + ", filter=" + filter.choiceValue());
        }
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null && client.player != null && currentContainer != null && client.player.containerMenu != client.player.inventoryMenu) {
            closeContainer(client);
        }
        restoreLook(client);
        BootstrapLog.info("LootChest disabled: opened=" + opened
            + ", stacks=" + lootedStacks
            + ", items=" + lootedItems
            + ", skipped=" + skipped
            + ", visited=" + visited.size()
            + ", failed=" + failed.size());
        resetState();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        tickLastOpenAttemptAge();

        if (client.player.containerMenu != client.player.inventoryMenu) {
            if (currentContainer == null && tryRecoverLateOpen(client)) {
                processOpenContainer(client);
                return;
            }
            if (currentContainer == null) {
                closeContainer(client);
                clearLastOpenAttempt();
                BootstrapLog.info("LootChest orphan container closed: title=" + openContainerTitle(client)
                    + ", recoveries=" + lateOpenRecoveries);
                return;
            }
            processOpenContainer(client);
            return;
        }

        if (currentContainer != null) {
            tickCurrentContainer(client);
            return;
        }

        if (scanning) {
            scanStep(client);
            return;
        }

        currentContainer = nextContainer(client);
        if (currentContainer != null) {
            openWaitTicks = 0;
            processTicks = 0;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        startScan(client);
    }

    private void tickCurrentContainer(Minecraft client) {
        if (!isCandidateContainer(client, currentContainer)) {
            skipped++;
            markCurrentVisited(client, "gone");
            return;
        }

        holdStill(client);
        double distance = client.player.getEyePosition(0.0F).distanceTo(Vec3.atCenterOf(currentContainer));
        if (distance > openRange.doubleValue()) {
            if (openWaitTicks > 0 && openWaitTicks <= OPEN_RANGE_GRACE_TICKS) {
                openWaitTicks++;
                phase = "wait-open";
                log("LootChest open grace: pos=" + currentContainer.toShortString()
                    + ", dist=" + String.format(Locale.ROOT, "%.2f", distance)
                    + ", wait=" + openWaitTicks
                    + ", timeout=" + OPEN_TIMEOUT_TICKS);
                if (openWaitTicks > OPEN_TIMEOUT_TICKS) {
                    skipped++;
                    markCurrentVisited(client, "open-timeout");
                }
                return;
            }
            skipped++;
            markCurrentVisited(client, "out-of-range");
            return;
        }

        snapshotLook(client);
        lookAt(client, Vec3.atCenterOf(currentContainer));
        if (openWaitTicks == 0 || openWaitTicks % 6 == 0) {
            openContainer(client, currentContainer);
        }
        openWaitTicks++;
        if (openWaitTicks > OPEN_TIMEOUT_TICKS) {
            skipped++;
            markCurrentVisited(client, "open-timeout");
        }
    }

    private void startScan(Minecraft client) {
        scanOrigin = client.player.blockPosition();
        scanRadius = radius.intValue();
        scanX = -scanRadius;
        scanY = -scanRadius;
        scanZ = -scanRadius;
        scanned = 0;
        candidateByKey.clear();
        queue.clear();
        scanning = true;
        phase = "scanning";
        log("LootChest scan started: origin=" + scanOrigin.toShortString()
            + ", radius=" + scanRadius
            + ", mode=" + mode.choiceValue()
            + ", filter=" + filter.choiceValue());
    }

    private void scanStep(Minecraft client) {
        int budget = Math.max(1, scanPerTick.intValue());
        for (int i = 0; i < budget && scanning; i++) {
            BlockPos pos = scanOrigin.offset(scanX, scanY, scanZ);
            if (insideBuildHeight(client, pos.getY()) && inOpenRange(client, pos) && isCandidateContainer(client, pos)) {
                addCandidate(client, pos);
            }
            scanned++;
            advanceScan(client);
        }
        log("LootChest scanning: checked=" + scanned
            + ", candidates=" + candidateByKey.size()
            + ", radius=" + scanRadius);
    }

    private void advanceScan(Minecraft client) {
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
        queue.addAll(candidateByKey.values().stream()
            .sorted(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(client.player.position())))
            .toList());
        cooldownTicks = rescanCooldown.intValue();
        phase = "queued";
        BootstrapLog.info("LootChest scan completed: checked=" + scanned
            + ", queued=" + queue.size()
            + ", visited=" + visited.size()
            + ", failed=" + failed.size());
        if (!queue.isEmpty()) {
            ModuleMessageLog.push("LootChest", "queued " + queue.size());
        }
    }

    private BlockPos nextContainer(Minecraft client) {
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            BlockPos key = containerKey(client, pos);
            if (!visited.contains(key) && !failed.contains(key) && isCandidateContainer(client, pos) && inOpenRange(client, pos)) {
                if (!key.equals(pos)) {
                    BootstrapLog.info("LootChest dedupe open: key=" + key.toShortString()
                        + ", openPos=" + pos.toShortString());
                }
                phase = "opening";
                return pos;
            }
        }
        return null;
    }

    private void addCandidate(Minecraft client, BlockPos pos) {
        BlockPos key = containerKey(client, pos);
        if (visited.contains(key) || failed.contains(key)) {
            return;
        }
        BlockPos candidate = pos.immutable();
        BlockPos previous = candidateByKey.get(key);
        if (previous == null || isCloserToPlayer(client, candidate, previous)) {
            candidateByKey.put(key, candidate);
        }
    }

    private boolean isCloserToPlayer(Minecraft client, BlockPos candidate, BlockPos previous) {
        Vec3 player = client.player.position();
        double candidateDistance = Vec3.atCenterOf(candidate).distanceToSqr(player);
        double previousDistance = Vec3.atCenterOf(previous).distanceToSqr(player);
        return candidateDistance < previousDistance;
    }

    private boolean inOpenRange(Minecraft client, BlockPos pos) {
        return client.player.getEyePosition(0.0F).distanceTo(Vec3.atCenterOf(pos)) <= openRange.doubleValue();
    }

    private boolean isCandidateContainer(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.AIR) {
            return false;
        }
        if ("Dungeon".equals(mode.choiceValue())) {
            return isDungeonCandidate(client, pos, state);
        }

        return isGenericContainerCandidate(client, pos, state, false);
    }

    private boolean isGenericContainerCandidate(Minecraft client, BlockPos pos, BlockState state, boolean forceMenusAndNames) {
        String activeFilter = forceMenusAndNames ? "Menus+Names" : filter.choiceValue();
        boolean menu = false;
        if (!"Names".equals(activeFilter)) {
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

        boolean name = !"Menus".equals(activeFilter) && storageNameMatch(state.getBlock());
        return switch (activeFilter) {
            case "Menus" -> menu;
            case "Names" -> name;
            default -> menu || name;
        };
    }

    private boolean isDungeonCandidate(Minecraft client, BlockPos pos, BlockState state) {
        String blockText = blockNameText(state);
        String providerText = menuProviderName(client, pos, state);
        if (isDungeonDenied(state, blockText + " " + providerText)) {
            return false;
        }
        return isDungeonChest(state)
            || dungeonNameMatches(blockText)
            || dungeonNameMatches(providerText)
            || dungeonChestKeyMatches(blockText);
    }

    private boolean isDungeonChest(BlockState state) {
        return DUNGEON_CHEST_IDS.contains(BuiltInRegistries.BLOCK.getId(state.getBlock()))
            || DUNGEON_CHEST_IDS.contains(BuiltInRegistries.ITEM.getId(state.getBlock().asItem()))
            || DUNGEON_CHEST_IDS.contains(Block.getId(state));
    }

    private boolean isDungeonDenied(BlockState state, String text) {
        return DUNGEON_DENY_IDS.contains(BuiltInRegistries.BLOCK.getId(state.getBlock()))
            || DUNGEON_DENY_IDS.contains(BuiltInRegistries.ITEM.getId(state.getBlock().asItem()))
            || DUNGEON_DENY_IDS.contains(Block.getId(state))
            || dungeonDenyNameMatches(text);
    }

    private String blockNameText(BlockState state) {
        try {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem());
            return id + " " + itemId + " " + state.getBlock().getName().getString()
                + " " + state.getBlock().asItem().getDescription().getString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private String menuProviderName(Minecraft client, BlockPos pos, BlockState state) {
        try {
            MenuProvider provider = state.getMenuProvider(client.level, pos);
            if (provider != null && provider.getDisplayName() != null) {
                return provider.getDisplayName().getString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the block entity provider check.
        }
        BlockEntity entity = client.level.getBlockEntity(pos);
        if (entity instanceof MenuProvider provider && provider.getDisplayName() != null) {
            return provider.getDisplayName().getString();
        }
        return "";
    }

    private boolean dungeonNameMatches(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return normalized.contains(DUNGEON_NAME_FRAGMENT);
    }

    private boolean dungeonDenyNameMatches(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return normalized.contains(DUNGEON_URN_FRAGMENT)
            || normalized.contains("ruins_urn");
    }

    private boolean dungeonChestKeyMatches(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        return normalized.contains("ruins")
            && normalized.contains("chest")
            && !normalized.contains("urn");
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
        lastOpenAttemptContainer = pos.immutable();
        lastOpenAttemptAgeTicks = 0;
        Direction face = faceFromPlayer(client, pos);
        Vec3 hit = Vec3.atCenterOf(pos).add(
            face.getStepX() * 0.5D,
            face.getStepY() * 0.5D,
            face.getStepZ() * 0.5D
        );
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        client.player.swing(InteractionHand.MAIN_HAND);
        if (openWaitTicks == 0) {
            opened++;
            BlockState state = client.level.getBlockState(pos);
            BootstrapLog.info("LootChest open attempt: pos=" + pos.toShortString()
                + ", block=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + ", blockId=" + BuiltInRegistries.BLOCK.getId(state.getBlock())
                + ", itemId=" + BuiltInRegistries.ITEM.getId(state.getBlock().asItem())
                + ", stateId=" + Block.getId(state)
                + ", mode=" + mode.choiceValue()
                + ", opened=" + opened);
            ModuleMessageLog.push("LootChest", "open " + pos.toShortString());
        }
    }

    private void processOpenContainer(Minecraft client) {
        holdStill(client);
        AbstractContainerMenu menu = client.player.containerMenu;
        processTicks++;
        if ("Dungeon".equals(mode.choiceValue()) && !isOpenDungeonContainer(client)) {
            skipped++;
            closeContainer(client);
            restoreLook(client);
            visited.add(containerKey(client, currentContainer));
            BootstrapLog.info("LootChest dungeon reject: pos=" + posText(currentContainer)
                + ", title=" + openContainerTitle(client)
                + ", ids=" + dungeonIdText(client, currentContainer)
                + ", skipped=" + skipped);
            ModuleMessageLog.push("LootChest", "skip non-dungeon");
            currentContainer = null;
            openWaitTicks = 0;
            processTicks = 0;
            emptyProcessTicks = 0;
            phase = "queued";
            return;
        }

        int moved = lootContainer(menu, client);
        if (moved > 0) {
            emptyProcessTicks = 0;
            BootstrapLog.info("LootChest looted pulse: stacks=" + moved
                + ", totalStacks=" + lootedStacks
                + ", totalItems=" + lootedItems
                + ", pos=" + posText(currentContainer));
            return;
        }

        if (processTicks <= EMPTY_MENU_GRACE_TICKS) {
            emptyProcessTicks++;
            phase = "wait-slots";
            log("LootChest waiting slots: pos=" + posText(currentContainer)
                + ", title=" + openContainerTitle(client)
                + ", processTicks=" + processTicks
                + ", emptyTicks=" + emptyProcessTicks
                + ", slots=" + menu.slots.size());
            return;
        }

        closeContainer(client);
        restoreLook(client);
        visited.add(containerKey(client, currentContainer));
        BootstrapLog.info("LootChest done: pos=" + posText(currentContainer)
            + ", opened=" + opened
            + ", stacks=" + lootedStacks
            + ", items=" + lootedItems
            + ", processTicks=" + processTicks);
        ModuleMessageLog.push("LootChest", "items " + lootedItems + " stacks " + lootedStacks);
        currentContainer = null;
        openWaitTicks = 0;
        processTicks = 0;
        emptyProcessTicks = 0;
        clearLastOpenAttempt();
        phase = "queued";
    }

    private int lootContainer(AbstractContainerMenu menu, Minecraft client) {
        int moved = 0;
        int clicks = 0;
        int maxClicks = Math.max(1, clickPerTick.intValue());
        for (int i = 0; i < menu.slots.size(); i++) {
            if (clicks >= maxClicks) {
                break;
            }
            Slot slot = menu.slots.get(i);
            if (isPlayerSlot(client, slot) || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            int stackCount = stack.getCount();
            client.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
            moved++;
            clicks++;
            lootedStacks++;
            lootedItems += stackCount;
        }

        if (moved <= 0 || processTicks > PROCESS_TIMEOUT_TICKS) {
            return 0;
        }
        return moved;
    }

    private boolean isOpenDungeonContainer(Minecraft client) {
        if (currentContainer == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(currentContainer);
        String debugText = blockNameText(state)
            + " " + menuProviderName(client, currentContainer, state)
            + " " + openContainerTitle(client)
            + " " + openContainerDebugText(client)
            + " " + containerSlotText(client.player.containerMenu, client);
        if (isDungeonDenied(state, debugText)) {
            return false;
        }
        return isDungeonChest(state)
            || dungeonNameMatches(debugText)
            || dungeonChestKeyMatches(debugText);
    }

    private String openContainerTitle(Minecraft client) {
        if (client != null && client.screen != null && client.screen.getTitle() != null) {
            return client.screen.getTitle().getString();
        }
        return "";
    }

    private String openContainerDebugText(Minecraft client) {
        if (client == null || client.screen == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(client.screen.getClass().getName());
        appendComponent(builder, "screenTitle", client.screen.getTitle());
        invokeTitleMethod(client.screen, builder, "getTitle");
        invokeTitleMethod(client.screen, builder, "getNarrationMessage");
        Class<?> type = client.screen.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!Component.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(client.screen);
                    if (value instanceof Component component) {
                        appendComponent(builder, field.getName(), component);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Title reflection is best-effort only.
                }
            }
            type = type.getSuperclass();
        }
        return builder.toString();
    }

    private void invokeTitleMethod(Object target, StringBuilder builder, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            if (value instanceof Component component) {
                appendComponent(builder, methodName, component);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Some screens do not expose every title/narration helper.
        }
    }

    private void appendComponent(StringBuilder builder, String label, Component component) {
        if (component == null) {
            return;
        }
        builder.append(" | ").append(label).append('=').append(component.getString());
        builder.append(" raw=").append(component);
    }

    private String containerSlotText(AbstractContainerMenu menu, Minecraft client) {
        if (menu == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (isPlayerSlot(client, slot) || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            builder.append(" | slot").append(i)
                .append('=').append(stack.getHoverName().getString())
                .append(" key=").append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .append(" id=").append(BuiltInRegistries.ITEM.getId(stack.getItem()));
            if (builder.length() > 4000) {
                break;
            }
        }
        return builder.toString();
    }

    private String dungeonIdText(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) {
            return "none";
        }
        BlockState state = client.level.getBlockState(pos);
        return "block=" + BuiltInRegistries.BLOCK.getId(state.getBlock())
            + ",item=" + BuiltInRegistries.ITEM.getId(state.getBlock().asItem())
            + ",state=" + Block.getId(state)
            + ",key=" + BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private boolean isPlayerSlot(Minecraft client, Slot slot) {
        return slot.container == client.player.getInventory();
    }

    private void tickLastOpenAttemptAge() {
        if (lastOpenAttemptContainer != null && lastOpenAttemptAgeTicks < Integer.MAX_VALUE) {
            lastOpenAttemptAgeTicks++;
        }
    }

    private boolean tryRecoverLateOpen(Minecraft client) {
        if (lastOpenAttemptContainer == null || lastOpenAttemptAgeTicks > LATE_OPEN_RECOVERY_TICKS) {
            return false;
        }
        currentContainer = lastOpenAttemptContainer.immutable();
        failed.remove(containerKey(client, currentContainer));
        openWaitTicks = Math.max(openWaitTicks, 1);
        processTicks = 0;
        emptyProcessTicks = 0;
        lateOpenRecoveries++;
        phase = "late-open";
        snapshotLook(client);
        BootstrapLog.info("LootChest late-open recovered: pos=" + currentContainer.toShortString()
            + ", ageTicks=" + lastOpenAttemptAgeTicks
            + ", title=" + openContainerTitle(client)
            + ", recoveries=" + lateOpenRecoveries);
        ModuleMessageLog.push("LootChest", "late open recovered");
        return true;
    }

    private void holdStill(Minecraft client) {
        if (client != null && client.player != null) {
            client.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private void markCurrentVisited(Minecraft client, String reason) {
        if (currentContainer == null) {
            return;
        }
        BlockPos key = containerKey(client, currentContainer);
        failed.add(key);
        restoreLook(client);
        BootstrapLog.info("LootChest skip: reason=" + reason
            + ", pos=" + currentContainer.toShortString()
            + ", failed=" + failed.size()
            + ", skipped=" + skipped);
        ModuleMessageLog.push("LootChest", "skip " + reason);
        currentContainer = null;
        openWaitTicks = 0;
        processTicks = 0;
        phase = "queued";
    }

    private void closeContainer(Minecraft client) {
        client.player.closeContainer();
        client.setScreen(null);
    }

    private Direction faceFromPlayer(Minecraft client, BlockPos pos) {
        Vec3 delta = Vec3.atCenterOf(pos).subtract(client.player.getEyePosition(0.0F));
        Direction face = Direction.getNearest(delta.x, delta.y, delta.z);
        return face == null ? Direction.UP : face.getOpposite();
    }

    private void lookAt(Minecraft client, Vec3 target) {
        Vec3 eye = client.player.getEyePosition(0.0F);
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float)(-Math.toDegrees(Math.atan2(delta.y, horizontal)));
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.yHeadRot = yaw;
        client.player.yBodyRot = yaw;
    }

    private void snapshotLook(Minecraft client) {
        if (hasSavedLook || client == null || client.player == null) {
            return;
        }
        savedYaw = client.player.getYRot();
        savedPitch = client.player.getXRot();
        savedHeadYaw = client.player.yHeadRot;
        savedBodyYaw = client.player.yBodyRot;
        hasSavedLook = true;
    }

    private void restoreLook(Minecraft client) {
        if (!hasSavedLook || client == null || client.player == null) {
            hasSavedLook = false;
            return;
        }
        client.player.setYRot(savedYaw);
        client.player.setXRot(savedPitch);
        client.player.yHeadRot = savedHeadYaw;
        client.player.yBodyRot = savedBodyYaw;
        hasSavedLook = false;
    }

    private boolean insideBuildHeight(Minecraft client, int y) {
        return y >= client.level.getMinBuildHeight() && y < client.level.getMaxBuildHeight();
    }

    private void resetState() {
        queue.clear();
        candidateByKey.clear();
        visited.clear();
        failed.clear();
        scanOrigin = null;
        currentContainer = null;
        scanning = false;
        scanRadius = 0;
        scanX = 0;
        scanY = 0;
        scanZ = 0;
        scanned = 0;
        opened = 0;
        lootedStacks = 0;
        lootedItems = 0;
        skipped = 0;
        openWaitTicks = 0;
        processTicks = 0;
        cooldownTicks = 0;
        emptyProcessTicks = 0;
        lastOpenAttemptContainer = null;
        lastOpenAttemptAgeTicks = Integer.MAX_VALUE;
        lateOpenRecoveries = 0;
        hasSavedLook = false;
        phase = "idle";
    }

    private void clearLastOpenAttempt() {
        lastOpenAttemptContainer = null;
        lastOpenAttemptAgeTicks = Integer.MAX_VALUE;
    }

    private void log(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 1_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(message);
    }

    private String posText(BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }

    @Override
    public String description() {
        return "Automatically opens nearby storage containers, quick-moves their contents, and closes them.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "LootChest " + phase
            + " mode=" + mode.choiceValue()
            + " queued=" + queue.size()
            + " opened=" + opened
            + " stacks=" + lootedStacks
            + " items=" + lootedItems
            + " skipped=" + skipped;
    }
}
