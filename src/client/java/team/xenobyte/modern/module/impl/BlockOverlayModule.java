package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.bootstrap.RenderDiagnostics;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.RenderUtil;
import team.xenobyte.modern.render.WorldRenderContext;

public class BlockOverlayModule extends XenoModule {
    private final ModuleSetting range = setting("Range", ModuleSetting.number("Range", 96.0D, 4.0D, 256.0D, 4.0D)
        .describe("Maximum block ray distance for the overlay."));
    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 1, "Outline", "Both", "Filled")
        .describe("How the looked-at block should be rendered."));
    private final ModuleSetting color = setting("Color", ModuleSetting.choice("Color", 1, "Cyan", "Green", "Gold", "Red", "Purple", "White")
        .describe("Overlay color for the looked-at block."));
    private long lastLogNanos;
    private BlockPos lastPos;

    public BlockOverlayModule() {
        super("BlockOverlay", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }

        BlockHitResult hit = blockHit(client, context.partialTick());
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        RenderDiagnostics.once("block-overlay-render", "BlockOverlay render path active: stage=AFTER_BLOCK_ENTITIES");
        AABB box = shapeBox(client, pos, state).inflate(0.004D);
        int overlayColor = colorValue();
        String activeMode = mode.choiceValue();
        if ("Filled".equals(activeMode) || "Both".equals(activeMode)) {
            RenderUtil.drawFilledBoxesImmediate(context, java.util.List.of(box), fillColor(overlayColor));
        }
        if ("Outline".equals(activeMode) || "Both".equals(activeMode)) {
            RenderUtil.drawBoxesImmediate(context, java.util.List.of(box), overlayColor);
        }
        log(pos, state);
    }

    private BlockHitResult blockHit(Minecraft client, float partialTick) {
        Entity camera = client.cameraEntity;
        HitResult hit = camera == null ? client.hitResult : camera.pick(range.doubleValue(), partialTick, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit;
        }
        if (client.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit;
        }
        return null;
    }

    private AABB shapeBox(Minecraft client, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(client.level, pos);
        if (shape.isEmpty()) {
            shape = state.getCollisionShape(client.level, pos);
        }
        if (shape.isEmpty()) {
            return new AABB(pos);
        }
        return shape.bounds().move(pos);
    }

    private void log(BlockPos pos, BlockState state) {
        long now = System.nanoTime();
        if (pos.equals(lastPos) && now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastPos = pos.immutable();
        lastLogNanos = now;
        BootstrapLog.info("BlockOverlay target: pos=" + pos.toShortString()
            + ", block=" + state.getBlock().getName().getString()
            + ", mode=" + mode.choiceValue()
            + ", color=" + color.choiceValue());
    }

    private int colorValue() {
        return switch (color.choiceValue()) {
            case "Green" -> 0xff55ff77;
            case "Gold" -> 0xffffd65a;
            case "Red" -> 0xffff5555;
            case "Purple" -> 0xffbb77ff;
            case "White" -> 0xffffffff;
            default -> 0xff55d6ff;
        };
    }

    private int fillColor(int color) {
        return 0x33000000 | (color & 0x00ffffff);
    }

    @Override
    public String description() {
        return "Highlights the looked-at block with an outline/fill overlay, useful for testing interactable and shaped blocks.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return lastPos == null ? "BlockOverlay no target" : "BlockOverlay target=" + lastPos.toShortString();
    }
}
