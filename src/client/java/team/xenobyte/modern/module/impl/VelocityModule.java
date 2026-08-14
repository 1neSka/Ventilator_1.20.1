package team.xenobyte.modern.module.impl;

import java.lang.reflect.Field;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class VelocityModule extends XenoModule {
    private static final String HANDLER_NAME = "xenobyte_velocity";
    private static final double PACKET_VELOCITY_SCALE = 8000.0D;

    private final ModuleSetting horizontal = setting("Horizontal", ModuleSetting.number("Horizontal", 100.0D, 0.0D, 100.0D, 1.0D)
        .describe("Percent of horizontal knockback removed. 100 means no horizontal knockback."));
    private final ModuleSetting vertical = setting("Vertical", ModuleSetting.number("Vertical", 100.0D, 0.0D, 100.0D, 1.0D)
        .describe("Percent of vertical knockback removed. 100 means no vertical knockback."));

    private Channel channel;
    private float lastHealth = -1.0F;
    private int hurtTicks;
    private long scaledPackets;

    public VelocityModule() {
        super("Velocity", Category.MOVE, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        install(client);
        lastHealth = currentHealth(client);
        hurtTicks = 0;
        scaledPackets = 0L;
    }

    @Override
    public void onDisable(Minecraft client) {
        removeHandler();
        lastHealth = -1.0F;
        hurtTicks = 0;
        BootstrapLog.info("Velocity disabled: scaledPackets=" + scaledPackets);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (channel == null || !channel.isActive() || channel.pipeline().get(HANDLER_NAME) == null) {
            install(client);
        }
        trackDamageWindow(client);
        applyTickFallback(client.player);
    }

    private void trackDamageWindow(Minecraft client) {
        float health = currentHealth(client);
        if (lastHealth >= 0.0F && health < lastHealth - 0.05F) {
            hurtTicks = 8;
        }
        lastHealth = health;
        if (hurtTicks > 0) {
            hurtTicks--;
        }
    }

    private float currentHealth(Minecraft client) {
        if (client == null || client.player == null) {
            return -1.0F;
        }
        return client.player.getHealth() + client.player.getAbsorptionAmount();
    }

    private void applyTickFallback(Player player) {
        if (hurtTicks <= 0) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        Vec3 scaled = scaleMotion(motion);
        if (motion.distanceToSqr(scaled) > 0.000001D) {
            player.setDeltaMovement(scaled);
        }
        player.fallDistance = 0.0F;
    }

    private Object scalePacket(Minecraft client, Object msg) {
        if (client == null || client.player == null) {
            return msg;
        }
        if (msg instanceof ClientboundSetEntityMotionPacket packet && packet.getId() == client.player.getId()) {
            Vec3 motion = new Vec3(packet.getXa() / PACKET_VELOCITY_SCALE, packet.getYa() / PACKET_VELOCITY_SCALE, packet.getZa() / PACKET_VELOCITY_SCALE);
            Vec3 scaled = scaleMotion(motion);
            if (motion.distanceToSqr(scaled) > 0.000001D) {
                scaledPackets++;
                hurtTicks = Math.max(hurtTicks, 8);
                return new ClientboundSetEntityMotionPacket(packet.getId(), scaled);
            }
            return msg;
        }
        if (msg instanceof ClientboundExplodePacket packet) {
            Vec3 motion = new Vec3(packet.getKnockbackX(), packet.getKnockbackY(), packet.getKnockbackZ());
            Vec3 scaled = scaleMotion(motion);
            if (motion.distanceToSqr(scaled) > 0.000001D) {
                scaledPackets++;
                hurtTicks = Math.max(hurtTicks, 8);
                return new ClientboundExplodePacket(packet.getX(), packet.getY(), packet.getZ(), packet.getPower(), packet.getToBlow(), scaled);
            }
        }
        return msg;
    }

    private Vec3 scaleMotion(Vec3 motion) {
        double hFactor = 1.0D - horizontal.doubleValue() / 100.0D;
        double vFactor = 1.0D - vertical.doubleValue() / 100.0D;
        return new Vec3(motion.x * hFactor, motion.y * vFactor, motion.z * hFactor);
    }

    private void install(Minecraft client) {
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
                VelocityHandler handler = new VelocityHandler(this);
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
                } else {
                    pipeline.addLast(HANDLER_NAME, handler);
                }
                BootstrapLog.info("Velocity packet hook installed");
            }
            channel = found;
        } catch (Throwable error) {
            BootstrapLog.error("Velocity packet hook install failed", error);
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

    private void removeHandler() {
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
                    BootstrapLog.error("Velocity packet hook remove failed", error);
                }
            });
        } catch (Throwable error) {
            BootstrapLog.error("Velocity packet hook remove schedule failed", error);
        }
    }

    @Override
    public String description() {
        return "Scales incoming player knockback and explosion velocity packets, with a short tick fallback after damage.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Velocity H=" + horizontal.displayValue()
            + "% V=" + vertical.displayValue()
            + "% packets=" + scaledPackets;
    }

    private static final class VelocityHandler extends ChannelDuplexHandler {
        private final VelocityModule owner;

        private VelocityHandler(VelocityModule owner) {
            this.owner = owner;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, owner.scalePacket(Minecraft.getInstance(), msg));
        }
    }
}
