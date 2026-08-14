package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.bootstrap.RenderDiagnostics;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.RenderUtil;
import team.xenobyte.modern.render.WorldRenderContext;

public class EspModule extends XenoModule {
    private final ModuleSetting range = setting("Range", ModuleSetting.number("Range", 96.0D, 16.0D, 256.0D, 8.0D)
        .describe("Maximum entity distance for ESP boxes and tracers."));
    private final ModuleSetting tracers = setting("Tracers", ModuleSetting.bool("Tracers", true)
        .describe("Draws tracer lines from the camera center toward matching entities."));
    private final ModuleSetting players = setting("Players", ModuleSetting.bool("Players", true)
        .describe("Show player entities."));
    private final ModuleSetting hostile = setting("Hostile", ModuleSetting.bool("Hostile", true)
        .describe("Show hostile mobs."));
    private final ModuleSetting passive = setting("Passive", ModuleSetting.bool("Passive", true)
        .describe("Show passive animals."));
    private final ModuleSetting npcs = setting("NPCs", ModuleSetting.bool("NPCs", true)
        .describe("Show other living entities and modded NPC-like entities."));
    private final ModuleSetting items = setting("Items", ModuleSetting.bool("Items", true)
        .describe("Show dropped items."));
    private int lastBoxes;
    private int lastTracers;
    private long lastCounterLogNanos;

    public EspModule() {
        super("Esp", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }
        RenderDiagnostics.once(
            "esp-render-immediate-after-level",
            "Esp render path active: immediateNoDepth=true, stage=AFTER_BLOCK_ENTITIES, tracers=" + tracers.boolValue()
        );
        lastBoxes = 0;
        lastTracers = 0;
        Map<Integer, List<AABB>> boxesByColor = new LinkedHashMap<>();
        Map<Integer, List<Vec3>> tracersByColor = tracers.boolValue() ? new LinkedHashMap<>() : null;
        for (Entity entity : client.level.entitiesForRendering()) {
            double rangeValue = range.doubleValue();
            if (entity == client.player || entity.isRemoved() || entity.distanceToSqr(client.player) > rangeValue * rangeValue) {
                continue;
            }
            int color = colorFor(entity);
            if (color == 0) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(0.04D);
            lastBoxes++;
            boxesByColor.computeIfAbsent(color, ignored -> new ArrayList<>()).add(box);
            if (tracers.boolValue()) {
                lastTracers++;
                tracersByColor.computeIfAbsent(color, ignored -> new ArrayList<>()).add(box.getCenter());
            }
        }
        boxesByColor.forEach((color, boxes) -> RenderUtil.drawBoxesImmediate(context, boxes, color));
        if (tracersByColor != null) {
            tracersByColor.forEach((color, targets) -> RenderUtil.drawTracersImmediate(context, targets, color));
        }
        logCounters();
    }

    private void logCounters() {
        long now = System.nanoTime();
        if (now - lastCounterLogNanos < 5_000_000_000L) {
            return;
        }
        lastCounterLogNanos = now;
        BootstrapLog.info("Esp render counters: boxes=" + lastBoxes
            + ", tracers=" + lastTracers
            + ", range=" + range.intValue()
            + ", immediateNoDepth=true"
            + ", tracersEnabled=" + tracers.boolValue()
            + ", filters=" + filtersSummary());
    }

    private int colorFor(Entity entity) {
        if (entity instanceof Player) {
            return players.boolValue() ? 0xffff55ff : 0;
        }
        if (entity instanceof Monster) {
            return hostile.boolValue() ? 0xffff5555 : 0;
        }
        if (entity instanceof Animal) {
            return passive.boolValue() ? 0xff55ff77 : 0;
        }
        if (entity instanceof ItemEntity) {
            return items.boolValue() ? 0xffffff55 : 0;
        }
        if (entity instanceof LivingEntity || entity instanceof Mob) {
            return npcs.boolValue() ? 0xff55ddff : 0;
        }
        return 0;
    }

    @Override
    public String description() {
        return "Draws entity boxes and tracer lines through walls.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Esp boxes=" + lastBoxes
            + " tracers=" + lastTracers
            + " filters=" + filtersSummary();
    }

    private String filtersSummary() {
        return (players.boolValue() ? "P" : "")
            + (hostile.boolValue() ? "H" : "")
            + (passive.boolValue() ? "A" : "")
            + (npcs.boolValue() ? "N" : "")
            + (items.boolValue() ? "I" : "");
    }
}
