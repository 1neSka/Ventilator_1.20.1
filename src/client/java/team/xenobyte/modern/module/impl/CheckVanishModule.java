package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class CheckVanishModule extends XenoModule {
    private final ModuleSetting interval = setting("Interval", ModuleSetting.number("Interval", 5.0D, 2.0D, 30.0D, 1.0D)
        .describe("Seconds between server status pings."));
    private final ModuleSetting showHud = setting("ShowHud", ModuleSetting.bool("ShowHud", true)
        .describe("Shows only the vanish count estimate on the HUD."));
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private ServerData pendingPing;
    private long nextPingNanos;
    private int listed = -1;
    private int online = -1;
    private int max = -1;
    private int hidden = -1;
    private String status = "waiting";

    public CheckVanishModule() {
        super("CheckVanish", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onDisable(Minecraft client) {
        pinger.removeAll();
        pendingPing = null;
        status = "disabled";
    }

    @Override
    public void onTick(Minecraft client) {
        pinger.tick();
        updateListed(client);
        finishPendingPing();

        long now = System.nanoTime();
        if (now < nextPingNanos) {
            return;
        }
        nextPingNanos = now + interval.intValue() * 1_000_000_000L;
        startPing(client);
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        if (!showHud.boolValue()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int color = hidden > 0 ? 0xffffaa55 : 0xffb4ffca;
        String line = hidden < 0 ? "Vanish: " + status : "Vanish: " + hidden + " hidden";
        context.drawString(client.font, line, 8, client.getWindow().getGuiScaledHeight() - 30, color);
    }

    @Override
    public HudBounds hudBounds(Minecraft client) {
        if (client == null || client.font == null || client.getWindow() == null) {
            return null;
        }
        String line = hidden < 0 ? "Vanish: " + status : "Vanish: " + hidden + " hidden";
        return new HudBounds(8, client.getWindow().getGuiScaledHeight() - 30, Math.max(72, client.font.width(line)), 10);
    }

    private void updateListed(Minecraft client) {
        ClientPacketListener connection = client == null ? null : client.getConnection();
        if (connection == null) {
            listed = -1;
            return;
        }
        listed = connection.getListedOnlinePlayers().size();
    }

    private void startPing(Minecraft client) {
        if (client == null || client.getCurrentServer() == null) {
            status = "singleplayer";
            return;
        }
        if (pendingPing != null) {
            status = "pending";
            return;
        }

        ServerData current = client.getCurrentServer();
        ServerData probe = new ServerData("xeno-check", current.ip, false);
        pendingPing = probe;
        status = "pinging";
        try {
            pinger.pingServer(probe, () -> {
                if (pendingPing == probe) {
                    readProbe(probe);
                    pendingPing = null;
                }
            });
        } catch (Exception exception) {
            pendingPing = null;
            status = "ping failed";
            BootstrapLog.error("CheckVanish ping failed", exception);
        }
    }

    private void finishPendingPing() {
        ServerData probe = pendingPing;
        if (probe == null || probe.players == null) {
            return;
        }
        readProbe(probe);
        pendingPing = null;
    }

    private void readProbe(ServerData probe) {
        if (probe.players == null) {
            return;
        }
        online = probe.players.online();
        max = probe.players.max();
        hidden = Math.max(0, online - Math.max(0, listed));
        status = "ok";
        BootstrapLog.info("CheckVanish result: online=" + online
            + ", max=" + max
            + ", listed=" + listed
            + ", hidden=" + hidden);
    }

    @Override
    public String description() {
        return "Compares server status online count with visible tab-list count and shows only the estimated hidden count.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (hidden < 0) {
            return "CheckVanish " + status;
        }
        return "CheckVanish hidden=" + hidden + " online=" + online + " listed=" + listed;
    }
}
