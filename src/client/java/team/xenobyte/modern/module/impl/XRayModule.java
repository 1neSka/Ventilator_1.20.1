package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.bootstrap.RenderDiagnostics;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.RenderMode;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.TargetStyle;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.RenderUtil;
import team.xenobyte.modern.render.WorldRenderContext;

public class XRayModule extends XenoModule {
    private static final int BROAD_TAG_SAFETY_LIMIT = 12000;
    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xenobyte-xray-scan");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<Block> DIAMOND = Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
    private static final Set<Block> EMERALD = Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
    private static final Set<Block> GOLD = Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
    private static final Set<Block> IRON = Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
    private static final Set<Block> LAPIS = Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
    private static final Set<Block> REDSTONE = Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
    private static final Set<Block> COAL = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
    private static final Set<Block> COPPER = Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
    private static final Set<Block> QUARTZ = Set.of(Blocks.NETHER_QUARTZ_ORE);
    private static final Set<Block> DEBRIS = Set.of(Blocks.ANCIENT_DEBRIS);

    private final Map<Block, Boolean> nameMatchCache = new ConcurrentHashMap<>();
    private final Map<Block, Boolean> taggedOreCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<ColumnOffset>> columnOffsetCache = new ConcurrentHashMap<>();
    private volatile List<Highlight> visibleBlocks = List.of();

    private final ModuleSetting radius = setting("Radius", ModuleSetting.number("Radius", 24.0D, 8.0D, 160.0D, 4.0D)
        .describe("Horizontal scan radius in loaded chunks. XRay scans full world height."));
    private final ModuleSetting scanDelay = setting("ScanDelay", ModuleSetting.number("ScanDelay", 20.0D, 5.0D, 80.0D, 5.0D)
        .describe("Client ticks between full-height XRay scans."));
    private final ModuleSetting profile = setting("Profile", ModuleSetting.choice("Profile", 0, "Vanilla+Custom", "CustomOnly", "VanillaOnly", "BroadTags")
        .describe("Which XRay target set to scan. BroadTags includes broad ore/name matching and uses an internal freeze guard."));
    private final ModuleSetting defaultRender = setting("Render", ModuleSetting.choice("Render", 0, "Outline", "Both", "Filled")
        .describe("Render style for built-in vanilla ore targets and BroadTags matches."));
    private final ModuleSetting genericColor = setting("GenericClr", ModuleSetting.choice("GenericClr", 9, "Cyan", "Red", "Green", "Gold", "Blue", "Orange", "White", "Gray", "Purple", "Pink")
        .describe("Color for BroadTags fallback matches."));

    private volatile boolean asyncScanRunning;
    private volatile int scanGeneration;
    private int tickCounter;
    private int lastLoggedCount = -1;
    private int lastScannedColumns;
    private boolean lastScanTruncated;
    private long lastScanLogNanos;

    public XRayModule() {
        super("XRay", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        tickCounter = 0;
        scan(client);
    }

    @Override
    public void onDisable(Minecraft client) {
        scanGeneration++;
        visibleBlocks = List.of();
        asyncScanRunning = false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client != null && client.noRender) {
            visibleBlocks = List.of();
            return;
        }
        if (++tickCounter < scanDelay.intValue()) {
            return;
        }
        tickCounter = 0;
        scan(client);
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        List<Highlight> snapshot = visibleBlocks;
        RenderDiagnostics.once(
            "xray-render-styled",
            "XRay render path active: styledImmediate=true, async=true, blocks=" + snapshot.size()
        );
        Map<StyleKey, List<AABB>> boxesByStyle = new LinkedHashMap<>();
        for (Highlight highlight : snapshot) {
            BlockPos pos = highlight.pos();
            StyleKey key = new StyleKey(highlight.color(), highlight.mode());
            boxesByStyle.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
        }

        boxesByStyle.forEach((style, boxes) -> {
            if (style.mode() == RenderMode.FILLED || style.mode() == RenderMode.BOTH) {
                RenderUtil.drawFilledBoxesImmediate(context, boxes, fillColor(style.color()));
            }
            if (style.mode() == RenderMode.OUTLINE || style.mode() == RenderMode.BOTH) {
                RenderUtil.drawBoxesImmediate(context, boxes, style.color());
            }
        });
    }

    private void scan(Minecraft client) {
        if (client.level == null || client.player == null) {
            visibleBlocks = List.of();
            return;
        }

        ClientLevel world = client.level;
        BlockPos center = client.player.blockPosition();
        int radiusValue = radius.intValue();
        String activeProfile = profile.choiceValue();
        RenderMode fallbackMode = RenderMode.from(defaultRender.choiceValue());
        int broadColor = XRayTargetRegistry.colorForChoice(genericColor.choiceValue());
        Map<Block, TargetStyle> customTargets = XRayTargetRegistry.snapshot();

        scanAsync(world, center, radiusValue, activeProfile, fallbackMode, broadColor, customTargets);
    }

    private void scanAsync(
        ClientLevel world,
        BlockPos center,
        int radiusValue,
        String activeProfile,
        RenderMode fallbackMode,
        int broadColor,
        Map<Block, TargetStyle> customTargets
    ) {
        if (asyncScanRunning) {
            return;
        }

        asyncScanRunning = true;
        int generation = ++scanGeneration;
        SCANNER.execute(() -> {
            try {
                ScanResult result = scanWorld(world, center, radiusValue, activeProfile, fallbackMode, broadColor, customTargets, true);
                if (generation == scanGeneration && enabled()) {
                    applyScanResult(result);
                }
            } catch (Throwable error) {
                BootstrapLog.error("XRay async scan failed", error);
            } finally {
                asyncScanRunning = false;
            }
        });
    }

    private ScanResult scanWorld(
        ClientLevel world,
        BlockPos center,
        int radiusValue,
        String activeProfile,
        RenderMode fallbackMode,
        int broadColor,
        Map<Block, TargetStyle> customTargets,
        boolean async
    ) {
        List<Highlight> found = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int scannedColumns = 0;
        boolean truncated = false;
        int minY = world.getMinBuildHeight();
        int maxY = world.getMaxBuildHeight();
        boolean broadProfile = "BroadTags".equals(activeProfile);

        for (ColumnOffset offset : columnOffsets(radiusValue)) {
            int x = center.getX() + offset.dx();
            int z = center.getZ() + offset.dz();
            if (!world.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            scannedColumns++;
            for (int y = minY; y < maxY; y++) {
                mutable.set(x, y, z);
                BlockState state = world.getBlockState(mutable);
                TargetStyle style = styleFor(state, activeProfile, fallbackMode, broadColor, customTargets);
                if (style == null) {
                    continue;
                }
                found.add(new Highlight(mutable.immutable(), style.color(), style.mode()));
                if (broadProfile && found.size() >= BROAD_TAG_SAFETY_LIMIT) {
                    truncated = true;
                    return new ScanResult(List.copyOf(found), scannedColumns, truncated, async, radiusValue, activeProfile, defaultRender.choiceValue(), customTargets.size());
                }
            }
        }
        return new ScanResult(List.copyOf(found), scannedColumns, truncated, async, radiusValue, activeProfile, defaultRender.choiceValue(), customTargets.size());
    }

    private void applyScanResult(ScanResult result) {
        visibleBlocks = result.highlights();
        lastScannedColumns = result.scannedColumns();
        lastScanTruncated = result.truncated();
        logScanResult(result);
    }

    private List<ColumnOffset> columnOffsets(int radiusValue) {
        return columnOffsetCache.computeIfAbsent(radiusValue, this::createColumnOffsets);
    }

    private List<ColumnOffset> createColumnOffsets(int radiusValue) {
        List<ColumnOffset> offsets = new ArrayList<>((radiusValue * 2 + 1) * (radiusValue * 2 + 1));
        for (int dx = -radiusValue; dx <= radiusValue; dx++) {
            for (int dz = -radiusValue; dz <= radiusValue; dz++) {
                offsets.add(new ColumnOffset(dx, dz, dx * dx + dz * dz));
            }
        }
        offsets.sort(Comparator.comparingInt(ColumnOffset::distanceSqr));
        return List.copyOf(offsets);
    }

    private TargetStyle styleFor(
        BlockState state,
        String activeProfile,
        RenderMode fallbackMode,
        int broadColor,
        Map<Block, TargetStyle> customTargets
    ) {
        Block block = state.getBlock();
        boolean includeCustom = !"VanillaOnly".equals(activeProfile);
        if (includeCustom) {
            TargetStyle custom = customTargets.get(block);
            if (custom != null) {
                return custom;
            }
        }
        if ("CustomOnly".equals(activeProfile)) {
            return null;
        }

        int vanillaColor = vanillaColor(block);
        if (vanillaColor != 0) {
            return new TargetStyle(vanillaColor, fallbackMode);
        }

        if ("BroadTags".equals(activeProfile) && (isTaggedOre(state) || isNameMatch(state))) {
            return new TargetStyle(broadColor, fallbackMode);
        }
        return null;
    }

    private int vanillaColor(Block block) {
        if (DIAMOND.contains(block)) {
            return 0xff55d6ff;
        }
        if (EMERALD.contains(block)) {
            return 0xff55ff77;
        }
        if (DEBRIS.contains(block)) {
            return 0xffff5555;
        }
        if (GOLD.contains(block)) {
            return 0xffffd65a;
        }
        if (IRON.contains(block)) {
            return 0xffffffff;
        }
        if (REDSTONE.contains(block)) {
            return 0xffff5555;
        }
        if (LAPIS.contains(block)) {
            return 0xff5588ff;
        }
        if (COAL.contains(block)) {
            return 0xff999999;
        }
        if (COPPER.contains(block)) {
            return 0xffff9955;
        }
        if (QUARTZ.contains(block)) {
            return 0xffbb77ff;
        }
        return 0;
    }

    private boolean isTaggedOre(BlockState state) {
        return taggedOreCache.computeIfAbsent(state.getBlock(), ignored -> state.is(Tags.Blocks.ORES)
            || state.is(BlockTags.COAL_ORES)
            || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.DIAMOND_ORES)
            || state.is(BlockTags.EMERALD_ORES)
            || state.is(BlockTags.GOLD_ORES)
            || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.LAPIS_ORES)
            || state.is(BlockTags.REDSTONE_ORES));
    }

    private boolean isNameMatch(BlockState state) {
        return nameMatchCache.computeIfAbsent(state.getBlock(), block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            String path = id.getPath();
            return path.contains("ore")
                || path.contains("debris")
                || path.contains("crystal")
                || path.contains("gem");
        });
    }

    private void logScanResult(ScanResult result) {
        int count = result.highlights().size();
        long now = System.nanoTime();
        if (count != lastLoggedCount || now - lastScanLogNanos > 10_000_000_000L || result.truncated()) {
            lastLoggedCount = count;
            lastScanLogNanos = now;
            BootstrapLog.info("XRay scan completed: blocks=" + count
                + ", radius=" + result.radius()
                + ", fullHeight=true"
                + ", scannedColumns=" + result.scannedColumns()
                + ", profile=" + result.profile()
                + ", customTargets=" + result.customTargets()
                + ", renderMode=" + result.renderMode()
                + ", broadSafetyLimit=" + BROAD_TAG_SAFETY_LIMIT
                + ", truncated=" + result.truncated()
                + ", async=" + result.async()
                + ", render=styledImmediate");
        }
    }

    @Override
    public String description() {
        return "Highlights vanilla ore groups and custom XRaySelect blocks across full loaded world height.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "XRay blocks=" + visibleBlocks.size()
            + " radius=" + radius.intValue()
            + " profile=" + profile.choiceValue()
            + " custom=" + XRayTargetRegistry.size()
            + " async=ON"
            + " running=" + asyncScanRunning
            + " truncated=" + lastScanTruncated;
    }

    private static int fillColor(int color) {
        return 0x44000000 | (color & 0x00ffffff);
    }

    private record Highlight(BlockPos pos, int color, RenderMode mode) {
    }

    private record StyleKey(int color, RenderMode mode) {
    }

    private record ColumnOffset(int dx, int dz, int distanceSqr) {
    }

    private record ScanResult(
        List<Highlight> highlights,
        int scannedColumns,
        boolean truncated,
        boolean async,
        int radius,
        String profile,
        String renderMode,
        int customTargets
    ) {
    }
}
