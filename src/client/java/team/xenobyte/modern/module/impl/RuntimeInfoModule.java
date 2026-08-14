package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import team.xenobyte.modern.XenobyteModernClient;
import team.xenobyte.modern.bootstrap.BootstrapHealth;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class RuntimeInfoModule extends XenoModule {
    public RuntimeInfoModule() {
        super("RuntimeInfo", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        int y = 8;
        y = draw(context, client, "Xeno runtime: active", 8, y, 0xffb4ffca);
        y = draw(context, client, BootstrapHealth.summary(), 8, y, 0xffd7e1ec);
        y = draw(context, client, compactStatus(), 8, y, 0xff8fb2d9);
        int lines = 0;
        for (XenoModule module : XenobyteModernClient.MODULES.modules()) {
            if (!module.enabled() || module == this) {
                continue;
            }
            String info = module.runtimeInfo(client);
            if (info.isBlank()) {
                continue;
            }
            y = draw(context, client, info, 8, y, 0xffb8c7d9);
            if (++lines >= 6) {
                break;
            }
        }
    }

    @Override
    public HudBounds hudBounds(Minecraft client) {
        return client == null ? null : new HudBounds(6, 6, 350, 96);
    }

    private int draw(GuiGraphics context, Minecraft client, String text, int x, int y, int color) {
        context.drawString(client.font, text, x, y, color);
        return y + 10;
    }

    private String compactStatus() {
        String status = XenobyteModernClient.status();
        int generationIndex = status.indexOf("runtimeGeneration=");
        int callbackIndex = status.indexOf(", callbacks=");
        if (generationIndex >= 0 && callbackIndex > generationIndex) {
            String generation = status.substring(generationIndex + "runtimeGeneration=".length(), callbackIndex);
            if (generation.length() > 16) {
                generation = generation.substring(0, 16);
            }
            return "gen=" + generation;
        }
        return "status available";
    }

    @Override
    public String description() {
        return "Displays current runtime generation and callback counters.";
    }
}
