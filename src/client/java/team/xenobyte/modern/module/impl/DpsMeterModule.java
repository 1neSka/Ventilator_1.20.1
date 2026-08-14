package team.xenobyte.modern.module.impl;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.AABB;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class DpsMeterModule extends XenoModule {
    private final ModuleSetting window = setting("Window", ModuleSetting.number("Window", 5.0D, 1.0D, 15.0D, 1.0D)
        .describe("Rolling DPS window in seconds."));
    private final ModuleSetting range = setting("Range", ModuleSetting.number("Range", 48.0D, 8.0D, 128.0D, 8.0D)
        .describe("Entity scan radius around the player."));
    private final ModuleSetting showTarget = setting("Target", ModuleSetting.bool("Target", true)
        .describe("Shows the most recently damaged entity name."));

    private final Map<Integer, EntityState> lastHealth = new HashMap<>();
    private final ArrayDeque<DamageEvent> damageEvents = new ArrayDeque<>();
    private String lastTarget = "";
    private double currentDps;
    private double currentTotal;
    private int lastAttackEntityId = Integer.MIN_VALUE;
    private boolean attackWasDown;
    private long lastAttackNanos;

    public DpsMeterModule() {
        super("DPSmeter", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        trackAttackTarget(client);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            lastHealth.clear();
            damageEvents.clear();
            return;
        }

        trackAttackTarget(client);
        long now = System.nanoTime();
        Set<Integer> seen = new HashSet<>();
        double scan = range.doubleValue();
        AABB area = client.player.getBoundingBox().inflate(scan);
        for (LivingEntity entity : client.level.getEntitiesOfClass(LivingEntity.class, area, entity -> entity != client.player)) {
            int id = entity.getId();
            seen.add(id);
            float health = entity.isAlive() ? Math.max(0.0F, entity.getHealth() + entity.getAbsorptionAmount()) : 0.0F;
            EntityState previous = lastHealth.put(id, new EntityState(health, entity.getName().getString(), now));
            if (previous != null && health < previous.health() - 0.05F) {
                recordDamage(now, previous.health() - health, previous.name());
            }
        }
        Iterator<Map.Entry<Integer, EntityState>> stateIterator = lastHealth.entrySet().iterator();
        while (stateIterator.hasNext()) {
            Map.Entry<Integer, EntityState> entry = stateIterator.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            EntityState previous = entry.getValue();
            if (entry.getKey() == lastAttackEntityId && previous.health() > 0.05F && now - lastAttackNanos < 1_000_000_000L) {
                recordDamage(now, previous.health(), previous.name());
            }
            stateIterator.remove();
        }
        trim(now);
        recalculate(now);
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null || client.getWindow() == null) {
            return;
        }
        String line = "DPS " + String.format(Locale.ROOT, "%.1f", currentDps)
            + "  DMG " + String.format(Locale.ROOT, "%.1f", currentTotal);
        if (showTarget.boolValue() && !lastTarget.isBlank()) {
            line += " " + lastTarget;
        }
        int x = 8;
        int y = 44;
        context.fill(x - 3, y - 3, x + client.font.width(line) + 5, y + 11, 0x66000000);
        context.drawString(client.font, line, x, y, 0xffffd37a);
    }

    @Override
    public HudBounds hudBounds(Minecraft client) {
        if (client == null || client.font == null) {
            return null;
        }
        String line = "DPS " + String.format(Locale.ROOT, "%.1f", currentDps)
            + "  DMG " + String.format(Locale.ROOT, "%.1f", currentTotal);
        if (showTarget.boolValue() && !lastTarget.isBlank()) {
            line += " " + lastTarget;
        }
        return new HudBounds(5, 41, client.font.width(line) + 10, 17);
    }

    private void trim(long now) {
        long maxAge = window.intValue() * 1_000_000_000L;
        while (!damageEvents.isEmpty() && now - damageEvents.peekFirst().nanos() > maxAge) {
            damageEvents.removeFirst();
        }
    }

    private void recalculate(long now) {
        trim(now);
        currentTotal = 0.0D;
        Iterator<DamageEvent> iterator = damageEvents.iterator();
        long first = now;
        while (iterator.hasNext()) {
            DamageEvent event = iterator.next();
            currentTotal += event.damage();
            first = Math.min(first, event.nanos());
        }
        double activeSeconds = damageEvents.isEmpty()
            ? 1.0D
            : Math.max(1.0D, Math.min(window.doubleValue(), (now - first) / 1_000_000_000.0D));
        currentDps = currentTotal / activeSeconds;
    }

    private void recordDamage(long now, double damage, String target) {
        if (damage <= 0.05D) {
            return;
        }
        damageEvents.addLast(new DamageEvent(now, damage));
        lastTarget = target == null ? "" : target;
    }

    private void trackAttackTarget(Minecraft client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        boolean attackDown = client.options.keyAttack.isDown();
        if (attackDown && !attackWasDown && client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) {
            lastAttackEntityId = living.getId();
            lastAttackNanos = System.nanoTime();
            lastHealth.put(living.getId(), new EntityState(
                Math.max(0.0F, living.getHealth() + living.getAbsorptionAmount()),
                living.getName().getString(),
                lastAttackNanos
            ));
        }
        attackWasDown = attackDown;
    }

    @Override
    public String description() {
        return "Tracks nearby living-entity health drops and renders rolling damage per second.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "DPSmeter dps=" + String.format(Locale.ROOT, "%.2f", currentDps)
            + " total=" + String.format(Locale.ROOT, "%.2f", currentTotal)
            + " target=" + lastTarget;
    }

    private record DamageEvent(long nanos, double damage) {
    }

    private record EntityState(float health, String name, long nanos) {
    }
}
