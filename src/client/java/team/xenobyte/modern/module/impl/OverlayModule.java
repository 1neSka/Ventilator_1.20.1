package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import team.xenobyte.modern.gui.OverlayNotifier;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class OverlayModule extends XenoModule {
    public OverlayModule() {
        super("Overlay", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        OverlayNotifier.push("Overlay enabled");
    }

    @Override
    public void onDisable(Minecraft client) {
        OverlayNotifier.clear();
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        OverlayNotifier.render(context);
    }

    @Override
    public HudBounds hudBounds(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return null;
        }
        int width = 200;
        return new HudBounds((client.getWindow().getGuiScaledWidth() - width) / 2, 10, width, 56);
    }

    @Override
    public String description() {
        return "Shows short Xenobyte action messages at the top center of the screen.";
    }
}
