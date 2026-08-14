package team.xenobyte.modern.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import team.xenobyte.modern.module.impl.XRayTargetRegistry;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.RenderMode;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.TargetStyle;

public class XRayColorScreen extends Screen {
    private static final int WINDOW_WIDTH = 430;
    private static final int WINDOW_HEIGHT = 278;
    private static final int ROW_HEIGHT = 34;
    private static final int SLIDER_WIDTH = 112;

    private final Block block;
    private final Screen returnScreen;
    private int red;
    private int green;
    private int blue;
    private RenderMode mode;
    private int activeSlider = -1;
    private int windowX;
    private int windowY;

    public XRayColorScreen(Block block, Screen returnScreen) {
        super(Component.literal("XRay Color"));
        this.block = block;
        this.returnScreen = returnScreen == this ? null : returnScreen;
        TargetStyle style = XRayTargetRegistry.style(block);
        int color = style == null ? 0xff55d6ff : style.color();
        red = Math.max(1, (color >> 16) & 0xff);
        green = Math.max(1, (color >> 8) & 0xff);
        blue = Math.max(1, color & 0xff);
        mode = style == null ? RenderMode.OUTLINE : style.mode();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        closeToReturn();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float partialTick) {
        renderBackground(context);
        windowX = Math.max(10, (width - WINDOW_WIDTH) / 2);
        windowY = Math.max(10, (height - WINDOW_HEIGHT) / 2);
        int right = Math.min(width - 10, windowX + WINDOW_WIDTH);
        int bottom = Math.min(height - 10, windowY + WINDOW_HEIGHT);

        context.fill(windowX, windowY, right, bottom, 0xf20d1117);
        context.fill(windowX, windowY, right, windowY + 34, 0xff253244);
        context.drawString(font, "XRAY COLOR", windowX + 14, windowY + 12, 0xffffffff);

        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        String clippedId = font.width(id) > WINDOW_WIDTH - 170
            ? font.plainSubstrByWidth(id, WINDOW_WIDTH - 176) + "..."
            : id;
        context.drawString(font, clippedId, right - 14 - font.width(clippedId), windowY + 12, 0xffa8bbce);

        int controlX = windowX + 22;
        int controlY = windowY + 54;
        drawChannel(context, "RED", 0, red, controlX, controlY, mouseX, mouseY);
        drawChannel(context, "GREEN", 1, green, controlX, controlY + ROW_HEIGHT, mouseX, mouseY);
        drawChannel(context, "BLUE", 2, blue, controlX, controlY + ROW_HEIGHT * 2, mouseX, mouseY);
        drawMode(context, controlX, controlY + ROW_HEIGHT * 3 + 6, mouseX, mouseY);

        int previewX = windowX + 278;
        int previewY = windowY + 60;
        int color = currentColor();
        context.fill(previewX, previewY, previewX + 126, previewY + 112, 0xff171d24);
        if (mode == RenderMode.FILLED || mode == RenderMode.BOTH) {
            context.fill(previewX + 12, previewY + 12, previewX + 114, previewY + 100, 0xff000000 | (color & 0x00ffffff));
        }
        if (mode == RenderMode.OUTLINE || mode == RenderMode.BOTH) {
            drawOutline(context, previewX + 12, previewY + 12, 102, 88, color);
        }
        context.drawCenteredString(font, "R " + red + "  G " + green + "  B " + blue,
            previewX + 63, previewY + 122, 0xffcbd7e3);

        drawButton(context, "APPLY", windowX + 22, bottom - 42, 116, 26, 0xff2f7549, mouseX, mouseY);
        drawButton(context, "CANCEL", windowX + 148, bottom - 42, 116, 26, 0xff384553, mouseX, mouseY);
        drawButton(context, "REMOVE", windowX + 274, bottom - 42, 130, 26, 0xff704044, mouseX, mouseY);
        super.render(context, mouseX, mouseY, partialTick);
    }

    private void drawChannel(GuiGraphics context, String label, int channel, int value, int x, int y, int mouseX, int mouseY) {
        context.drawString(font, label, x, y + 10, 0xffc9d5e2);
        int controlX = x + 76;
        context.fill(controlX, y, controlX + 166, y + 26, 0xff202832);
        int trackX = controlX + 8;
        int trackY = y + 11;
        int channelColor = switch (channel) {
            case 0 -> 0xffff6262;
            case 1 -> 0xff62e889;
            default -> 0xff62a8ff;
        };
        double ratio = (value - 1) / 254.0D;
        int knobX = trackX + (int)Math.round(ratio * SLIDER_WIDTH);
        context.fill(trackX, trackY, trackX + SLIDER_WIDTH, trackY + 4, 0xff4a5663);
        context.fill(trackX, trackY, knobX, trackY + 4, channelColor);
        context.fill(knobX - 3, y + 6, knobX + 4, y + 21,
            activeSlider == channel ? 0xffffffff : 0xffdce6ef);
        context.drawString(font, Integer.toString(value), controlX + 156 - font.width(Integer.toString(value)),
            y + 9, 0xffb4ffca);
    }

    private void drawMode(GuiGraphics context, int x, int y, int mouseX, int mouseY) {
        context.drawString(font, "STYLE", x, y + 10, 0xffc9d5e2);
        int controlX = x + 76;
        context.fill(controlX, y, controlX + 166, y + 26, 0xff202832);
        drawSmallButton(context, "<", controlX, y, mouseX, mouseY);
        drawSmallButton(context, ">", controlX + 140, y, mouseX, mouseY);
        context.drawCenteredString(font, mode.displayName(), controlX + 83, y + 9, 0xffb4ffca);
    }

    private void drawSmallButton(GuiGraphics context, String label, int x, int y, int mouseX, int mouseY) {
        int color = inside(mouseX, mouseY, x, y, 26, 26) ? 0xff405063 : 0xff2d3946;
        context.fill(x, y, x + 26, y + 26, color);
        context.drawCenteredString(font, label, x + 13, y + 9, 0xffffffff);
    }

    private void drawButton(GuiGraphics context, String label, int x, int y, int w, int h, int color, int mouseX, int mouseY) {
        context.fill(x, y, x + w, y + h, inside(mouseX, mouseY, x, y, w, h) ? brighten(color) : color);
        context.drawCenteredString(font, label, x + w / 2, y + 9, 0xffffffff);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int controlX = windowX + 22;
        int controlY = windowY + 54;
        for (int channel = 0; channel < 3; channel++) {
            int rowY = controlY + channel * ROW_HEIGHT;
            if (button == 0 && inside(mouseX, mouseY, controlX + 80, rowY, SLIDER_WIDTH + 8, 26)) {
                activeSlider = channel;
                setChannelFromMouse(channel, mouseX, controlX + 84);
                return true;
            }
        }

        int modeY = controlY + ROW_HEIGHT * 3 + 6;
        if (inside(mouseX, mouseY, controlX + 76, modeY, 26, 26)) {
            mode = mode.next(-1);
            return true;
        }
        if (inside(mouseX, mouseY, controlX + 216, modeY, 26, 26)) {
            mode = mode.next(1);
            return true;
        }

        int bottom = Math.min(height - 10, windowY + WINDOW_HEIGHT);
        if (inside(mouseX, mouseY, windowX + 22, bottom - 42, 116, 26)) {
            XRayTargetRegistry.put(block, currentColor(), mode);
            closeToReturn();
            return true;
        }
        if (inside(mouseX, mouseY, windowX + 148, bottom - 42, 116, 26)) {
            closeToReturn();
            return true;
        }
        if (inside(mouseX, mouseY, windowX + 274, bottom - 42, 130, 26)) {
            XRayTargetRegistry.remove(block);
            closeToReturn();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && activeSlider >= 0) {
            int trackX = windowX + 22 + 84;
            setChannelFromMouse(activeSlider, mouseX, trackX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && activeSlider >= 0) {
            activeSlider = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void setChannelFromMouse(int channel, double mouseX, int trackX) {
        double ratio = Math.max(0.0D, Math.min(1.0D, (mouseX - trackX) / SLIDER_WIDTH));
        setChannel(channel, 1 + (int)Math.round(ratio * 254.0D));
    }

    private void setChannel(int channel, int value) {
        int clamped = Math.max(1, Math.min(255, value));
        if (channel == 0) {
            red = clamped;
        } else if (channel == 1) {
            green = clamped;
        } else {
            blue = clamped;
        }
    }

    private int currentColor() {
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private void closeToReturn() {
        if (minecraft != null) {
            minecraft.setScreen(returnScreen);
        }
    }

    private void drawOutline(GuiGraphics context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 2, color);
        context.fill(x, y + h - 2, x + w, y + h, color);
        context.fill(x, y, x + 2, y + h, color);
        context.fill(x + w - 2, y, x + w, y + h, color);
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
