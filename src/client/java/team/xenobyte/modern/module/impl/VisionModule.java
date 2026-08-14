package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderBlockScreenEffectEvent;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.ReflectionField;

public class VisionModule extends XenoModule {
    private static final String HANDLER_NAME = "xenobyte_vision_particles";
    private static final ReflectionField HURT_TIME = new ReflectionField(LivingEntity.class, "hurtTime", "f_20916_", "aL");
    private static final ReflectionField HURT_DURATION = new ReflectionField(LivingEntity.class, "hurtDuration", "f_20917_", "aM");

    private final ModuleSetting fullBright = setting("FullBright", ModuleSetting.bool("FullBright", true)
        .describe("Enables gamma/night-vision brightening."));
    private final ModuleSetting gamma = setting("Gamma", ModuleSetting.number("Gamma", 1.0D, 0.0D, 1.0D, 0.05D)
        .describe("Client gamma while FullBright is enabled."));
    private final ModuleSetting nightVision = setting("NightVision", ModuleSetting.bool("NightVision", true)
        .describe("Adds a local hidden night-vision effect while FullBright is enabled."));
    private final ModuleSetting noNausea = setting("NoNausea", ModuleSetting.bool("NoNausea", true)
        .describe("Removes local nausea distortion."));
    private final ModuleSetting noBlindness = setting("NoBlindness", ModuleSetting.bool("NoBlindness", true)
        .describe("Removes local blindness every render/tick pass."));
    private final ModuleSetting noFire = setting("NoFire", ModuleSetting.bool("NoFire", true)
        .describe("Cancels the first-person fire overlay."));
    private final ModuleSetting noPotion = setting("NoPotion", ModuleSetting.bool("NoPotion", true)
        .describe("Keeps potion effects but hides their screen-space particles."));
    private final ModuleSetting noShake = setting("NoShake", ModuleSetting.bool("NoShake", true)
        .describe("Clears local hurt camera shake."));

    private Double previousGamma;
    private boolean hadNightVision;
    private boolean appliedNightVision;
    private Channel channel;
    private int hiddenEffectRewrites;
    private int hiddenPacketDrops;
    private int hiddenEntityDataForces;
    private String lastDroppedParticle = "none";
    private long lastNoPotionLogNanos;
    private EntityDataAccessor<Integer> effectColorAccessor;
    private EntityDataAccessor<Boolean> effectAmbienceAccessor;
    private boolean effectColorAccessorFailed;
    private boolean effectAmbienceAccessorFailed;
    private Integer previousEffectColor;
    private Boolean previousEffectAmbience;
    private boolean forcedEffectEntityData;

    public VisionModule() {
        super("Vision", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        if (client != null && client.options != null) {
            previousGamma = client.options.gamma().get();
        }
        if (client != null && client.player != null) {
            hadNightVision = client.player.hasEffect(MobEffects.NIGHT_VISION);
        }
        hiddenEffectRewrites = 0;
        hiddenPacketDrops = 0;
        hiddenEntityDataForces = 0;
        lastDroppedParticle = "none";
        previousEffectColor = null;
        previousEffectAmbience = null;
        forcedEffectEntityData = false;
        installParticleFilter(client);
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null && client.options != null && previousGamma != null) {
            client.options.gamma().set(previousGamma);
        }
        if (client != null && client.player != null && nightVision.boolValue() && !hadNightVision) {
            client.player.removeEffect(MobEffects.NIGHT_VISION);
        }
        previousGamma = null;
        hadNightVision = false;
        appliedNightVision = false;
        restoreLocalPotionEntityData(client);
        removeParticleFilter();
    }

    @Override
    public void onTick(Minecraft client) {
        applyVisualOverrides(client);
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        applyVisualOverrides(client);
    }

    @Override
    public void onBeforeRender(Minecraft client) {
        applyVisualOverrides(client);
    }

    @Override
    public void onRenderBlockScreenEffect(RenderBlockScreenEffectEvent event) {
        if (noFire.boolValue() && event.getOverlayType() == RenderBlockScreenEffectEvent.OverlayType.FIRE) {
            event.setCanceled(true);
        }
    }

    private void applyVisualOverrides(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        if (noPotion.boolValue() && (channel == null || !channel.isActive() || channel.pipeline().get(HANDLER_NAME) == null)) {
            installParticleFilter(client);
        }
        if (client.options != null && fullBright.boolValue()) {
            client.options.gamma().set(gamma.doubleValue());
        } else if (client.options != null && previousGamma != null) {
            client.options.gamma().set(previousGamma);
        }

        if (fullBright.boolValue() && nightVision.boolValue() && !hadNightVision) {
            if (shouldRefreshNightVision(client)) {
                client.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, false, false, false));
            }
            appliedNightVision = true;
        } else if (appliedNightVision) {
            client.player.removeEffect(MobEffects.NIGHT_VISION);
            appliedNightVision = false;
        }

        if (noNausea.boolValue()) {
            client.player.removeEffect(MobEffects.CONFUSION);
        }
        if (noBlindness.boolValue()) {
            client.player.removeEffect(MobEffects.BLINDNESS);
        }
        if (noPotion.boolValue()) {
            hidePotionParticles(client);
            hideLocalPotionEntityData(client);
        } else {
            restoreLocalPotionEntityData(client);
        }
        if (noShake.boolValue()) {
            clearHurtShake(client.player);
        }
    }

    private boolean shouldRefreshNightVision(Minecraft client) {
        MobEffectInstance current = client.player.getEffect(MobEffects.NIGHT_VISION);
        return current == null || current.getDuration() < 220 || current.isVisible();
    }

    private void hidePotionParticles(Minecraft client) {
        Collection<MobEffectInstance> active = client.player.getActiveEffects();
        if (active.isEmpty()) {
            return;
        }

        List<MobEffectInstance> visible = new ArrayList<>();
        for (MobEffectInstance effect : active) {
            if (effect.isVisible()) {
                visible.add(effect);
            }
        }
        boolean rewroteAny = false;
        for (MobEffectInstance effect : visible) {
            MobEffect type = effect.getEffect();
            client.player.removeEffectNoUpdate(type);
            client.player.addEffect(new MobEffectInstance(
                type,
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                false,
                effect.showIcon()
            ));
            hiddenEffectRewrites++;
            rewroteAny = true;
        }
        if (rewroteAny) {
            clearCurrentParticles(client);
        }
        if (rewroteAny) {
            logNoPotion("rewrite visibleEffects=" + visible.size()
                + ", totalRewrites=" + hiddenEffectRewrites
                + ", packetDrops=" + hiddenPacketDrops
                + ", dataForces=" + hiddenEntityDataForces);
        }
    }

    private void hideLocalPotionEntityData(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        EntityDataAccessor<Integer> colorAccessor = effectColorAccessor();
        EntityDataAccessor<Boolean> ambienceAccessor = effectAmbienceAccessor();
        if (colorAccessor == null && ambienceAccessor == null) {
            return;
        }
        try {
            boolean changed = false;
            if (!forcedEffectEntityData) {
                if (colorAccessor != null) {
                    previousEffectColor = client.player.getEntityData().get(colorAccessor);
                }
                if (ambienceAccessor != null) {
                    previousEffectAmbience = client.player.getEntityData().get(ambienceAccessor);
                }
                forcedEffectEntityData = true;
            }

            if (colorAccessor != null) {
                Integer color = client.player.getEntityData().get(colorAccessor);
                if (color != null && color.intValue() != 0) {
                    client.player.getEntityData().set(colorAccessor, Integer.valueOf(0));
                    changed = true;
                }
            }
            if (ambienceAccessor != null) {
                Boolean ambient = client.player.getEntityData().get(ambienceAccessor);
                if (!Boolean.TRUE.equals(ambient)) {
                    client.player.getEntityData().set(ambienceAccessor, Boolean.TRUE);
                    changed = true;
                }
            }
            if (changed) {
                hiddenEntityDataForces++;
                logNoPotion("force local entity effect data hidden"
                    + ", dataForces=" + hiddenEntityDataForces
                    + ", rewrites=" + hiddenEffectRewrites
                    + ", packetDrops=" + hiddenPacketDrops);
            }
        } catch (Throwable error) {
            team.xenobyte.modern.bootstrap.BootstrapLog.error("Vision NoPotion local entity-data override failed", error);
        }
    }

    private void restoreLocalPotionEntityData(Minecraft client) {
        if (!forcedEffectEntityData) {
            return;
        }
        forcedEffectEntityData = false;
        if (client == null || client.player == null) {
            previousEffectColor = null;
            previousEffectAmbience = null;
            return;
        }
        try {
            EntityDataAccessor<Integer> colorAccessor = effectColorAccessor();
            if (colorAccessor != null && previousEffectColor != null) {
                client.player.getEntityData().set(colorAccessor, previousEffectColor);
            }
            EntityDataAccessor<Boolean> ambienceAccessor = effectAmbienceAccessor();
            if (ambienceAccessor != null && previousEffectAmbience != null) {
                client.player.getEntityData().set(ambienceAccessor, previousEffectAmbience);
            }
        } catch (Throwable error) {
            team.xenobyte.modern.bootstrap.BootstrapLog.error("Vision NoPotion local entity-data restore failed", error);
        } finally {
            previousEffectColor = null;
            previousEffectAmbience = null;
        }
    }

    @SuppressWarnings("unchecked")
    private EntityDataAccessor<Integer> effectColorAccessor() {
        if (effectColorAccessor != null || effectColorAccessorFailed) {
            return effectColorAccessor;
        }
        Object accessor = readStaticLivingEntityField("DATA_EFFECT_COLOR_ID", "f_20962_", "bJ");
        if (accessor instanceof EntityDataAccessor<?>) {
            effectColorAccessor = (EntityDataAccessor<Integer>) accessor;
        } else {
            effectColorAccessorFailed = true;
            team.xenobyte.modern.bootstrap.BootstrapLog.info("Vision NoPotion: effect color accessor not found");
        }
        return effectColorAccessor;
    }

    @SuppressWarnings("unchecked")
    private EntityDataAccessor<Boolean> effectAmbienceAccessor() {
        if (effectAmbienceAccessor != null || effectAmbienceAccessorFailed) {
            return effectAmbienceAccessor;
        }
        Object accessor = readStaticLivingEntityField("DATA_EFFECT_AMBIENCE_ID", "f_20963_", "bK");
        if (accessor instanceof EntityDataAccessor<?>) {
            effectAmbienceAccessor = (EntityDataAccessor<Boolean>) accessor;
        } else {
            effectAmbienceAccessorFailed = true;
            team.xenobyte.modern.bootstrap.BootstrapLog.info("Vision NoPotion: effect ambience accessor not found");
        }
        return effectAmbienceAccessor;
    }

    private Object readStaticLivingEntityField(String... names) {
        for (String name : names) {
            try {
                Field field = LivingEntity.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next known mapping name.
            }
        }
        return null;
    }

    private boolean shouldDropParticlePacket(Minecraft client, ClientboundLevelParticlesPacket packet) {
        if (!noPotion.boolValue() || client == null || client.player == null || packet == null) {
            return false;
        }
        ParticleOptions particle = packet.getParticle();
        if (!isPotionParticle(particle)) {
            return false;
        }
        hiddenPacketDrops++;
        lastDroppedParticle = String.valueOf(particle.getType());
        logNoPotion("drop particle=" + lastDroppedParticle
            + ", count=" + packet.getCount()
            + ", pos=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f", packet.getX(), packet.getY(), packet.getZ())
            + ", totalDrops=" + hiddenPacketDrops);
        return true;
    }

    private boolean isPotionParticle(ParticleOptions particle) {
        return particle != null && (particle.getType() == ParticleTypes.ENTITY_EFFECT
            || particle.getType() == ParticleTypes.AMBIENT_ENTITY_EFFECT
            || particle.getType() == ParticleTypes.EFFECT
            || particle.getType() == ParticleTypes.INSTANT_EFFECT);
    }

    private void clearCurrentParticles(Minecraft client) {
        if (client == null || client.particleEngine == null) {
            return;
        }
        for (String methodName : new String[] {"clearParticles", "m_107319_", "f"}) {
            try {
                Method method = client.particleEngine.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(client.particleEngine);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next known mapping name.
            }
        }
    }

    private void installParticleFilter(Minecraft client) {
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
            if (pipeline.get(HANDLER_NAME) == null) {
                VisionParticleHandler handler = new VisionParticleHandler(this);
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
                } else {
                    pipeline.addLast(HANDLER_NAME, handler);
                }
                logNoPotion("particle filter installed");
            }
            channel = found;
        } catch (Throwable error) {
            team.xenobyte.modern.bootstrap.BootstrapLog.error("Vision particle filter install failed", error);
        }
    }

    private Channel findChannel(Connection connection) {
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
                    // Try the next field.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void removeParticleFilter() {
        Channel current = channel;
        channel = null;
        if (current == null) {
            return;
        }
        try {
            current.eventLoop().execute(() -> {
                try {
                    if (current.pipeline().get(HANDLER_NAME) != null) {
                        current.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Throwable error) {
                    team.xenobyte.modern.bootstrap.BootstrapLog.error("Vision particle filter remove failed", error);
                }
            });
        } catch (Throwable error) {
            team.xenobyte.modern.bootstrap.BootstrapLog.error("Vision particle filter remove schedule failed", error);
        }
    }

    private void logNoPotion(String message) {
        long now = System.nanoTime();
        if (now - lastNoPotionLogNanos < 2_000_000_000L) {
            return;
        }
        lastNoPotionLogNanos = now;
        team.xenobyte.modern.bootstrap.BootstrapLog.info("Vision NoPotion: " + message);
    }

    private void clearHurtShake(LivingEntity entity) {
        HURT_TIME.setInt(entity, 0);
        HURT_DURATION.setInt(entity, 0);
    }

    @Override
    public String description() {
        return "Groups brightness and visual-noise filters: fire overlay, potion particles, blindness, nausea, and hurt shake.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Vision fb=" + fullBright.displayValue()
            + " gamma=" + gamma.displayValue()
            + " nv=" + nightVision.displayValue()
            + " fire=" + noFire.displayValue()
            + " potion=" + noPotion.displayValue()
            + " shake=" + noShake.displayValue()
            + " potionRewrites=" + hiddenEffectRewrites
            + " potionDrops=" + hiddenPacketDrops
            + " potionDataForces=" + hiddenEntityDataForces
            + " lastParticle=" + lastDroppedParticle;
    }

    private static final class VisionParticleHandler extends ChannelDuplexHandler {
        private final VisionModule owner;

        private VisionParticleHandler(VisionModule owner) {
            this.owner = owner;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ClientboundLevelParticlesPacket packet && owner.shouldDropParticlePacket(Minecraft.getInstance(), packet)) {
                return;
            }
            super.channelRead(ctx, msg);
        }
    }
}
