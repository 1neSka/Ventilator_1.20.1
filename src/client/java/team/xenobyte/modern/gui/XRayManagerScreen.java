package team.xenobyte.modern.gui;

import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import team.xenobyte.modern.module.impl.XRayTargetRegistry;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.TargetEntry;

public class XRayManagerScreen extends Screen {
    private static final int WINDOW_WIDTH = 610;
    private static final int WINDOW_HEIGHT = 370;
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 11;

    private final Screen returnScreen;
    private String filter = "";
    private int scroll;
    private int windowX;
    private int windowY;
    private long clearArmedUntil;

    public XRayManagerScreen(Screen returnScreen) {
        super(Component.literal("XRay Targets"));
        this.returnScreen = returnScreen == this ? null : returnScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(returnScreen);
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float partialTick) {
        renderBackground(context);
        windowX = Math.max(10, (width - WINDOW_WIDTH) / 2);
        windowY = Math.max(10, (height - WINDOW_HEIGHT) / 2);
        int right = Math.min(width - 10, windowX + WINDOW_WIDTH);
        int bottom = Math.min(height - 10, windowY + WINDOW_HEIGHT);

        context.fill(windowX, windowY, right, bottom, 0xf20d1117);
        context.fill(windowX, windowY, right, windowY + 36, 0xff253244);
        context.drawString(font, "XRAY TARGETS", windowX + 14, windowY + 13, 0xffffffff);
        String count = XRayTargetRegistry.size() + " selected";
        context.drawString(font, count, right - 14 - font.width(count), windowY + 13, 0xffa8bbce);

        int listX = windowX + 18;
        int listY = windowY + 52;
        int listWidth = 402;
        context.fill(listX, listY, listX + listWidth, listY + 26, 0xff202832);
        String search = filter.isEmpty() ? "Type to filter selected blocks..." : filter;
        context.drawString(font, search + (((System.currentTimeMillis() / 500L) & 1L) == 0L ? "_" : ""),
            listX + 9, listY + 9, filter.isEmpty() ? 0xff718396 : 0xffd7e1ec);

        List<TargetEntry> entries = filteredEntries();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - VISIBLE_ROWS)));
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            int y = listY + 34 + row * ROW_HEIGHT;
            if (index >= entries.size()) {
                context.fill(listX, y, listX + listWidth, y + ROW_HEIGHT - 2, 0xff161c23);
                continue;
            }

            TargetEntry entry = entries.get(index);
            boolean hovered = inside(mouseX, mouseY, listX, y, listWidth, ROW_HEIGHT - 2);
            context.fill(listX, y, listX + listWidth, y + ROW_HEIGHT - 2, hovered ? 0xff2a3643 : 0xff1c242d);
            int color = entry.style().color();
            context.fill(listX + 7, y + 5, listX + 21, y + 17, color);
            drawOutline(context, listX + 7, y + 5, 14, 12, 0xffd5dde6);
            String id = entry.id().toString();
            if (font.width(id) > 250) {
                id = font.plainSubstrByWidth(id, 244) + "...";
            }
            context.drawString(font, id, listX + 29, y + 7, 0xffd7e1ec);
            String mode = entry.style().mode().displayName();
            context.drawString(font, mode, listX + 330 - font.width(mode), y + 7, 0xffa8bbce);
            int removeX = listX + listWidth - 34;
            context.fill(removeX, y, listX + listWidth, y + ROW_HEIGHT - 2,
                inside(mouseX, mouseY, removeX, y, 34, ROW_HEIGHT - 2) ? 0xff7b4448 : 0xff55383c);
            context.drawCenteredString(font, "X", removeX + 17, y + 7, 0xffffffff);
        }

        int sideX = windowX + 440;
        context.drawString(font, "MANAGE", sideX, listY + 2, 0xffa8bbce);
        drawInfo(context, "Click a row", "RGB + style editor", sideX, listY + 27);
        drawInfo(context, "Click X", "Remove immediately", sideX, listY + 79);
        drawInfo(context, "JEI hover", "Use XRaySelect bind", sideX, listY + 131);

        boolean clearArmed = System.currentTimeMillis() < clearArmedUntil;
        drawButton(context, clearArmed ? "CONFIRM CLEAR" : "CLEAR ALL", sideX, bottom - 78, 150, 26,
            clearArmed ? 0xff87484d : 0xff55383c, mouseX, mouseY);
        drawButton(context, "BACK", sideX, bottom - 42, 150, 26, 0xff384553, mouseX, mouseY);

        String footer = entries.isEmpty() ? "No matching targets" : (scroll + 1) + "-" + Math.min(entries.size(), scroll + VISIBLE_ROWS)
            + " / " + entries.size();
        context.drawString(font, footer, listX, bottom - 21, 0xff8193a6);
        super.render(context, mouseX, mouseY, partialTick);
    }

    private void drawInfo(GuiGraphics context, String title, String detail, int x, int y) {
        context.fill(x, y, x + 150, y + 43, 0xff1c242d);
        context.drawString(font, title, x + 8, y + 8, 0xffd7e1ec);
        context.drawString(font, detail, x + 8, y + 25, 0xff8fa2b5);
    }

    private void drawButton(GuiGraphics context, String label, int x, int y, int w, int h, int color, int mouseX, int mouseY) {
        context.fill(x, y, x + w, y + h, inside(mouseX, mouseY, x, y, w, h) ? brighten(color) : color);
        context.drawCenteredString(font, label, x + w / 2, y + 9, 0xffffffff);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = windowX + 18;
        int listY = windowY + 52;
        int listWidth = 402;
        List<TargetEntry> entries = filteredEntries();
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= entries.size()) {
                break;
            }
            int y = listY + 34 + row * ROW_HEIGHT;
            if (!inside(mouseX, mouseY, listX, y, listWidth, ROW_HEIGHT - 2)) {
                continue;
            }
            TargetEntry entry = entries.get(index);
            if (inside(mouseX, mouseY, listX + listWidth - 34, y, 34, ROW_HEIGHT - 2)) {
                XRayTargetRegistry.remove(entry.block());
                return true;
            }
            if (minecraft != null) {
                minecraft.setScreen(new XRayColorScreen(entry.block(), this));
            }
            return true;
        }

        int sideX = windowX + 440;
        int bottom = Math.min(height - 10, windowY + WINDOW_HEIGHT);
        if (inside(mouseX, mouseY, sideX, bottom - 78, 150, 26)) {
            long now = System.currentTimeMillis();
            if (now < clearArmedUntil) {
                XRayTargetRegistry.clear();
                clearArmedUntil = 0L;
                scroll = 0;
            } else {
                clearArmedUntil = now + 3000L;
            }
            return true;
        }
        if (inside(mouseX, mouseY, sideX, bottom - 42, 150, 26)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, scroll + (delta > 0.0D ? -1 : 1));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            scroll = 0;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!Character.isISOControl(codePoint) && filter.length() < 80) {
            filter += Character.toString(codePoint).toLowerCase(Locale.ROOT);
            scroll = 0;
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private List<TargetEntry> filteredEntries() {
        if (filter.isBlank()) {
            return XRayTargetRegistry.entries();
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        return XRayTargetRegistry.entries().stream()
            .filter(entry -> entry.id().toString().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
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
