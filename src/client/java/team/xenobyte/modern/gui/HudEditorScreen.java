package team.xenobyte.modern.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.XenoModule.HudBounds;

public class HudEditorScreen extends Screen {
    private static final int OFFSCREEN_ALLOWANCE = 32;
    private static final int LIST_X = 12;
    private static final int LIST_Y = 38;
    private static final int LIST_WIDTH = 146;
    private static final int ROW_HEIGHT = 22;

    private final ModuleManager modules;
    private final Screen returnScreen;
    private XenoModule selected;
    private boolean dragging;
    private double dragMouseX;
    private double dragMouseY;
    private int dragOffsetX;
    private int dragOffsetY;

    public HudEditorScreen(ModuleManager modules, Screen returnScreen) {
        super(Component.literal("HUD Layout"));
        this.modules = modules;
        this.returnScreen = returnScreen == this ? null : returnScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        modules.saveConfig();
        if (minecraft != null) {
            minecraft.setScreen(returnScreen);
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float partialTick) {
        renderBackground(context);
        List<XenoModule> hudModules = hudModules();
        if (selected == null || !hudModules.contains(selected)) {
            selected = hudModules.stream().filter(XenoModule::enabled).findFirst().orElse(hudModules.isEmpty() ? null : hudModules.get(0));
        }

        context.fill(8, 8, 162, height - 8, 0xe612171e);
        context.fill(8, 8, 162, 32, 0xff253244);
        context.drawString(font, "HUD LAYOUT", 18, 17, 0xffffffff);

        int y = LIST_Y;
        for (XenoModule module : hudModules) {
            boolean active = module == selected;
            boolean hovered = inside(mouseX, mouseY, LIST_X, y, LIST_WIDTH, ROW_HEIGHT - 2);
            int color = active ? 0xff385d77 : hovered ? 0xff303a46 : 0xff202832;
            context.fill(LIST_X, y, LIST_X + LIST_WIDTH, y + ROW_HEIGHT - 2, color);
            context.drawString(font, module.name(), LIST_X + 8, y + 7, module.enabled() ? 0xffdfffe9 : 0xff9bacbd);
            String state = module.enabled() ? "ON" : "OFF";
            context.drawString(font, state, LIST_X + LIST_WIDTH - 8 - font.width(state), y + 7,
                module.enabled() ? 0xffb4ffca : 0xff778899);
            y += ROW_HEIGHT;
        }

        int resetY = height - 72;
        drawButton(context, "RESET", LIST_X, resetY, 70, 24, 0xff384553, mouseX, mouseY);
        drawButton(context, "BACK", LIST_X + 76, resetY, 70, 24, 0xff384553, mouseX, mouseY);
        drawButton(context, "RESET ALL", LIST_X, resetY + 30, 146, 24, 0xff55383c, mouseX, mouseY);

        context.fill(174, 8, width - 8, 32, 0xaa10151c);
        String title = selected == null ? "NO MOVABLE HUD" : selected.name() + "  " + selected.hudOffsetX() + ", " + selected.hudOffsetY();
        context.drawString(font, title, 184, 17, 0xffd7e1ec);

        for (XenoModule module : hudModules) {
            if (module.enabled()) {
                modules.renderHudModule(module, context, partialTick);
            }
        }

        drawHudFrames(context, hudModules, mouseX, mouseY);
        super.render(context, mouseX, mouseY, partialTick);
    }

    private void drawHudFrames(GuiGraphics context, List<XenoModule> hudModules, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, 600.0F);
        for (XenoModule module : hudModules) {
            HudBounds raw = module.hudBounds(client);
            if (raw == null) {
                continue;
            }
            HudBounds bounds = raw.translated(module.hudOffsetX(), module.hudOffsetY());
            boolean active = module == selected;
            boolean hovered = inside(mouseX, mouseY, bounds.x() - 3, bounds.y() - 12, bounds.width() + 6, bounds.height() + 15);
            int color = active ? 0xff55d6ff : hovered ? 0xff9fc4df : 0x996f8aae;
            drawOutline(context, bounds.x() - 3, bounds.y() - 3, bounds.width() + 6, bounds.height() + 6, color);
            String label = module.name();
            int labelWidth = font.width(label) + 8;
            context.fill(bounds.x() - 3, bounds.y() - 13, bounds.x() - 3 + labelWidth, bounds.y() - 3, color);
            context.drawString(font, label, bounds.x() + 1, bounds.y() - 12, 0xff0d1117);
            if (!module.enabled()) {
                context.fill(bounds.x(), bounds.y(), bounds.x() + Math.max(42, Math.min(bounds.width(), labelWidth + 20)), bounds.y() + 12, 0xaa202832);
                context.drawString(font, "preview", bounds.x() + 4, bounds.y() + 3, 0xff9bacbd);
            }
        }
        context.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        List<XenoModule> hudModules = hudModules();
        int y = LIST_Y;
        for (XenoModule module : hudModules) {
            if (inside(mouseX, mouseY, LIST_X, y, LIST_WIDTH, ROW_HEIGHT - 2)) {
                selected = module;
                return true;
            }
            y += ROW_HEIGHT;
        }

        int resetY = height - 72;
        if (inside(mouseX, mouseY, LIST_X, resetY, 70, 24)) {
            if (selected != null) {
                selected.setHudOffset(0, 0);
                modules.saveConfig();
            }
            return true;
        }
        if (inside(mouseX, mouseY, LIST_X + 76, resetY, 70, 24)) {
            onClose();
            return true;
        }
        if (inside(mouseX, mouseY, LIST_X, resetY + 30, 146, 24)) {
            hudModules.forEach(module -> module.setHudOffset(0, 0));
            modules.saveConfig();
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        for (int i = hudModules.size() - 1; i >= 0; i--) {
            XenoModule module = hudModules.get(i);
            HudBounds raw = module.hudBounds(client);
            if (raw == null) {
                continue;
            }
            HudBounds bounds = raw.translated(module.hudOffsetX(), module.hudOffsetY());
            if (inside(mouseX, mouseY, bounds.x() - 3, bounds.y() - 13, bounds.width() + 6, bounds.height() + 16)) {
                selected = module;
                startDrag(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || selected == null || button != 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        HudBounds raw = selected.hudBounds(Minecraft.getInstance());
        if (raw == null) {
            return true;
        }
        int desiredX = dragOffsetX + (int)Math.round(mouseX - dragMouseX);
        int desiredY = dragOffsetY + (int)Math.round(mouseY - dragMouseY);
        int minX = -OFFSCREEN_ALLOWANCE - raw.x();
        int maxX = width + OFFSCREEN_ALLOWANCE - raw.x() - raw.width();
        int minY = -OFFSCREEN_ALLOWANCE - raw.y();
        int maxY = height + OFFSCREEN_ALLOWANCE - raw.y() - raw.height();
        selected.setHudOffset(Math.max(minX, Math.min(maxX, desiredX)), Math.max(minY, Math.min(maxY, desiredY)));
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            modules.saveConfig();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void startDrag(double mouseX, double mouseY) {
        dragging = selected != null;
        dragMouseX = mouseX;
        dragMouseY = mouseY;
        dragOffsetX = selected == null ? 0 : selected.hudOffsetX();
        dragOffsetY = selected == null ? 0 : selected.hudOffsetY();
    }

    private List<XenoModule> hudModules() {
        Minecraft client = Minecraft.getInstance();
        return modules.modules().stream().filter(module -> module.hudBounds(client) != null).toList();
    }

    private void drawButton(GuiGraphics context, String label, int x, int y, int w, int h, int color, int mouseX, int mouseY) {
        context.fill(x, y, x + w, y + h, inside(mouseX, mouseY, x, y, w, h) ? brighten(color) : color);
        context.drawCenteredString(font, label, x + w / 2, y + 8, 0xffffffff);
    }

    private void drawOutline(GuiGraphics context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xff) + 18);
        int g = Math.min(255, ((color >> 8) & 0xff) + 18);
        int b = Math.min(255, (color & 0xff) + 18);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
