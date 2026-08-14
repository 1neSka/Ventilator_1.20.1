package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.RenderUtil;
import team.xenobyte.modern.render.WorldRenderContext;

public class SelectZoneModule extends XenoModule {
    private final ModuleSetting range = setting("Range", ModuleSetting.number("Range", 32.0D, 4.0D, 128.0D, 4.0D)
        .describe("Maximum distance used to select a block under the crosshair."));
    private final ModuleSetting color = setting("Color", ModuleSetting.choice("Color", 0,
        "Cyan", "Green", "Gold", "Red", "Purple", "White")
        .describe("Color used to render every selected zone."));
    private final ModuleSetting render = setting("Render", ModuleSetting.choice("Render", 1, "Outline", "Both", "Filled")
        .describe("How selected zone bounds are rendered through blocks."));
    private final ModuleSetting clear = setting("ClearZones", ModuleSetting.action("ClearZones", this::clearZones)
        .describe("Clears all selected zones and the pending first point."));

    private boolean useWasDown;
    private int lastRenderedZones;

    public SelectZoneModule() {
        super("SelectZone", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        useWasDown = false;
        message(client, "RMB: first point, Shift+RMB: second point");
    }

    @Override
    public void onDisable(Minecraft client) {
        useWasDown = false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.options == null) {
            return;
        }
        if (client.screen != null) {
            useWasDown = false;
            return;
        }

        boolean useDown = client.options.keyUse.isDown();
        if (useDown && !useWasDown) {
            selectPoint(client, client.options.keyShift.isDown());
        }
        useWasDown = useDown;
    }

    private void selectPoint(Minecraft client, boolean secondPoint) {
        BlockHitResult hit = blockHit(client);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            message(client, "No block under crosshair");
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        if (!secondPoint) {
            SelectedZoneRegistry.setFirst(client.level.dimension(), pos);
            message(client, "First point: " + pos.toShortString());
            BootstrapLog.info("SelectZone first point: dimension=" + client.level.dimension().location()
                + ", pos=" + pos.toShortString());
            return;
        }

        SelectedZoneRegistry.AddResult result = SelectedZoneRegistry.addSecond(client.level.dimension(), pos);
        switch (result.status()) {
            case NO_FIRST -> message(client, "Set the first point with RMB");
            case WRONG_DIMENSION -> message(client, "First point belongs to another dimension");
            case ADDED -> {
                SelectedZoneRegistry.Zone zone = result.zone();
                message(client, "Zone " + SelectedZoneRegistry.size() + ": " + zone.volume() + " blocks");
                BootstrapLog.info("SelectZone added: dimension=" + client.level.dimension().location()
                    + ", min=" + zone.min().toShortString()
                    + ", max=" + zone.max().toShortString()
                    + ", volume=" + zone.volume()
                    + ", zones=" + SelectedZoneRegistry.size());
            }
        }
    }

    private BlockHitResult blockHit(Minecraft client) {
        Entity camera = client.cameraEntity;
        HitResult hit = camera == null ? client.hitResult : camera.pick(range.doubleValue(), 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit;
        }
        return client.hitResult instanceof BlockHitResult blockHit ? blockHit : null;
    }

    private void clearZones() {
        SelectedZoneRegistry.ClearResult result = SelectedZoneRegistry.clear();
        Minecraft client = Minecraft.getInstance();
        message(client, "Cleared " + result.zones() + " zones / " + result.blocks() + " blocks");
        BootstrapLog.info("SelectZone cleared: zones=" + result.zones()
            + ", blocks=" + result.blocks()
            + ", pendingFirst=" + result.hadFirst());
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return;
        }
        List<AABB> boxes = new ArrayList<>();
        for (SelectedZoneRegistry.Zone zone : SelectedZoneRegistry.snapshot(client.level.dimension())) {
            boxes.add(zone.box().inflate(0.003D));
        }
        SelectedZoneRegistry.PendingPoint first = SelectedZoneRegistry.firstPoint();
        if (first != null && first.dimension().equals(client.level.dimension())) {
            boxes.add(new AABB(first.pos()).inflate(0.01D));
        }
        lastRenderedZones = boxes.size();
        if (boxes.isEmpty()) {
            return;
        }

        int selectedColor = colorValue();
        String mode = render.choiceValue();
        if ("Filled".equals(mode) || "Both".equals(mode)) {
            RenderUtil.drawFilledBoxesImmediate(context, boxes, 0x22000000 | (selectedColor & 0x00ffffff));
        }
        if ("Outline".equals(mode) || "Both".equals(mode)) {
            RenderUtil.drawBoxesImmediate(context, boxes, selectedColor);
        }
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

    private void message(Minecraft client, String text) {
        ModuleMessageLog.push("SelectZone", text);
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.literal("SelectZone: " + text), true);
        }
    }

    @Override
    public String description() {
        return "Selects multiple cuboid regions: RMB sets point one and Shift+RMB completes a zone.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "SelectZone zones=" + SelectedZoneRegistry.size()
            + " blocks=" + SelectedZoneRegistry.totalBlocks()
            + " rendered=" + lastRenderedZones
            + " pending=" + (SelectedZoneRegistry.firstPoint() != null);
    }
}
