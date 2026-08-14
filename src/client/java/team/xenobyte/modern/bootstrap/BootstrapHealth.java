package team.xenobyte.modern.bootstrap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import team.xenobyte.modern.render.WorldRenderContext;

public final class BootstrapHealth {
    private static long tickCallbacks;
    private static long hudCallbacks;
    private static long worldCallbacks;
    private static String lastTickSource = "none";
    private static boolean tickLogged;
    private static boolean hudLogged;
    private static boolean worldLogged;

    private BootstrapHealth() {
    }

    public static synchronized void markTick(Minecraft client, String source) {
        tickCallbacks++;
        lastTickSource = source;
        if (!tickLogged) {
            tickLogged = true;
            BootstrapLog.info("First client tick callback observed: source=" + source
                + ", level=" + (client.level != null)
                + ", player=" + (client.player != null)
                + ", screen=" + (client.screen == null ? "null" : client.screen.getClass().getName()));
        }
    }

    public static synchronized void markHud(GuiGraphics context, float partialTick) {
        hudCallbacks++;
        if (!hudLogged) {
            hudLogged = true;
            BootstrapLog.info("First HUD callback observed: partialTick=" + partialTick
                + ", context=" + context.getClass().getName());
        }
    }

    public static synchronized void markWorld(WorldRenderContext context) {
        worldCallbacks++;
        if (!worldLogged) {
            worldLogged = true;
            BootstrapLog.info("First world render callback observed: partialTick=" + context.partialTick());
        }
    }

    public static synchronized String summary() {
        return "callbacks={tick=" + tickCallbacks
            + ", tickSource=" + lastTickSource
            + ", hud=" + hudCallbacks
            + ", world=" + worldCallbacks
            + "}";
    }
}
