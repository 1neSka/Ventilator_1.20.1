package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

import java.util.Comparator;
import java.util.List;

public class KillAuraModule extends XenoModule {

    private final ModuleSetting radius = setting("Radius",
            ModuleSetting.number("Radius", 6.0, 1.0, 12.0, 0.5)
                    .describe("Радиус поиска целей."));

    private final ModuleSetting delayMode = setting("DelayMode",
            ModuleSetting.choice("DelayMode", 0, "Fixed", "Smart")
                    .describe("Fixed uses Delay ticks. Smart waits for the real weapon/effect attack cooldown."));

    private final ModuleSetting delay = setting("Delay",
            ModuleSetting.number("Delay", 0.0, 0.0, 40.0, 1.0)
                    .describe("Задержка между ударами в тиках."));

    private final ModuleSetting smartCharge = setting("SmartCharge",
            ModuleSetting.number("SmartCharge", 0.98, 0.80, 1.0, 0.01)
                    .describe("Required vanilla attack charge in Smart mode. 0.98 keeps nearly full damage and reliable critical timing."));

    private final ModuleSetting players = setting("Players",
            ModuleSetting.bool("Players", true)
                    .describe("Атаковать игроков."));

    private final ModuleSetting monsters = setting("Monsters",
            ModuleSetting.bool("Monsters", true)
                    .describe("Атаковать монстров."));

    private final ModuleSetting animals = setting("Animals",
            ModuleSetting.bool("Animals", true)
                    .describe("Атаковать животных."));

    private final ModuleSetting villagers = setting("Villagers",
            ModuleSetting.bool("Villagers", false)
                    .describe("Атаковать жителей."));

    private final ModuleSetting npcs = setting("NPCs",
            ModuleSetting.bool("NPCs", false)
                    .describe("Атаковать модовых NPC (нестандартные сущности)."));

    private final ModuleSetting ignoreWalls = setting("IgnoreWalls",
            ModuleSetting.bool("IgnoreWalls", true)
                    .describe("Игнорировать стены (атаковать сквозь)."));

    private final ModuleSetting criticals = setting("Criticals",
            ModuleSetting.bool("Criticals", true)
                    .describe("Наносить критические удары (эмуляция прыжка)."));

    private final ModuleSetting handshake = setting("HandShake",
            ModuleSetting.bool("HandShake", true)
                    .describe("Взмах руки при ударе."));

    private final ModuleSetting pointed = setting("Pointed",
            ModuleSetting.bool("Pointed", false)
                    .describe("Атаковать только ту сущность, на которую смотришь."));

    private int tickCounter;
    private Entity target;
    private float currentCharge = 1.0F;
    private double currentAttackSpeed = 4.0D;
    private double estimatedCooldownTicks = 5.0D;
    private String smartState = "idle";

    public KillAuraModule() {
        super("KillAura", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        tickCounter = 0;
        target = null;
        smartState = "enabled";
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;

        updateAttackTiming(client);
        target = findTarget(client);
        if (target == null) {
            smartState = "no-target";
            return;
        }

        if ("Smart".equals(delayMode.choiceValue())) {
            if (currentCharge + 0.0001F < smartCharge.floatValue()) {
                smartState = "charging";
                return;
            }
            smartState = criticals.boolValue() ? "critical-ready" : "ready";
        } else {
            if (++tickCounter < delay.intValue()) {
                smartState = "fixed-wait";
                return;
            }
        }

        tickCounter = 0;
        attack(client, target);
        client.player.resetAttackStrengthTicker();
        updateAttackTiming(client);
        smartState = "attacked";
    }

    private void updateAttackTiming(Minecraft client) {
        currentCharge = client.player.getAttackStrengthScale(0.0F);
        currentAttackSpeed = Math.max(0.01D, client.player.getAttributeValue(Attributes.ATTACK_SPEED));
        estimatedCooldownTicks = 20.0D / currentAttackSpeed;
    }

    private Entity findTarget(Minecraft client) {
        double rad = radius.doubleValue();
        AABB area = client.player.getBoundingBox().inflate(rad);

        List<LivingEntity> entities = client.level.getEntitiesOfClass(
                LivingEntity.class,
                area,
                this::isValidTarget
        );

        entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(client.player)));

        if (pointed.boolValue()) {
            HitResult hit = client.player.pick(rad, 0.0F, false);
            if (hit instanceof EntityHitResult entityHit) {
                Entity pointedEntity = entityHit.getEntity();
                if (pointedEntity != null && entities.contains(pointedEntity)) {
                    return pointedEntity;
                }
            }
            return null;
        }

        return entities.isEmpty() ? null : entities.get(0);
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == null || entity == Minecraft.getInstance().player) return false;
        if (!entity.isAlive()) return false;
        if (!ignoreWalls.boolValue()) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || !localPlayer.hasLineOfSight(entity)) {
                return false;
            }
        }

        if (entity instanceof Player) return players.boolValue();
        if (entity instanceof Monster) return monsters.boolValue();
        if (entity instanceof Animal) return animals.boolValue();
        if (entity instanceof Villager) return villagers.boolValue();

        // Модовые NPC (все остальные живые сущности)
        if (!(entity instanceof Player) && !(entity instanceof Monster)
                && !(entity instanceof Animal) && !(entity instanceof Villager)) {
            return npcs.boolValue();
        }

        return false;
    }

    private void attack(Minecraft client, Entity target) {
        boolean naturalCritical = !client.player.onGround() && client.player.fallDistance > 0.0F;
        if (criticals.boolValue() && !naturalCritical) {
            double x = client.player.getX();
            double y = client.player.getY();
            double z = client.player.getZ();
            client.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0624, z, true));
            client.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false));
            client.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.000111, z, false));
            client.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false));
        }

        client.getConnection().send(ServerboundInteractPacket.createAttackPacket(target, false));

        if (handshake.boolValue()) {
            client.player.swing(client.player.getUsedItemHand());
        }
    }

    @Override
    public String description() {
        return "Автоматически атакует ближайших врагов в радиусе. Полезно для фарма мобов.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "KillAura mode=" + delayMode.choiceValue()
                + " state=" + smartState
                + " charge=" + String.format(java.util.Locale.ROOT, "%.2f", currentCharge)
                + " atkSpeed=" + String.format(java.util.Locale.ROOT, "%.2f", currentAttackSpeed)
                + " cooldown=" + String.format(java.util.Locale.ROOT, "%.1f", estimatedCooldownTicks) + "t"
                + " target=" + (target != null ? target.getName().getString() : "none");
    }
}
