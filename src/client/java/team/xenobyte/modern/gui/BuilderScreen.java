package team.xenobyte.modern.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import team.xenobyte.modern.XenobyteModernClient;
import team.xenobyte.modern.module.impl.BuilderModule;

public class BuilderScreen extends Screen {
    private static final int WINDOW_WIDTH = 690;
    private static final int WINDOW_HEIGHT = 420;
    private static final int ROW_HEIGHT = 26;
    private static final int LEFT_WIDTH = 220;
    private static final int MIDDLE_WIDTH = 206;
    private static final String[] GEOMETRY_SETTINGS = {
        "Width", "Length", "Height", "Radius", "Thickness", "OffsetY"
    };
    private static final String[] BUILD_SETTINGS = {
        "PlaceTick", "Reach", "MoveSpeed"
    };

    private final BuilderModule builder;
    private final Screen returnScreen;
    private String editingSetting;
    private String editingText = "";
    private int windowX;
    private int windowY;
    private float interfaceScale = 1.0F;

    public BuilderScreen(BuilderModule builder, Screen returnScreen) {
        super(Component.literal("Builder Studio"));
        this.builder = builder;
        this.returnScreen = returnScreen == this ? null : returnScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        commitEdit();
        if (minecraft != null && returnScreen != null) {
            minecraft.setScreen(returnScreen);
            return;
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float partialTick) {
        renderBackground(context);
        float widthScale = Math.max(0.35F, (width - 16.0F) / WINDOW_WIDTH);
        float heightScale = Math.max(0.35F, (height - 16.0F) / WINDOW_HEIGHT);
        interfaceScale = Math.min(1.0F, Math.min(widthScale, heightScale));
        windowX = Math.max(4, Math.round((width - WINDOW_WIDTH * interfaceScale) * 0.5F));
        windowY = Math.max(4, Math.round((height - WINDOW_HEIGHT * interfaceScale) * 0.5F));
        int localMouseX = Math.round((mouseX - windowX) / interfaceScale);
        int localMouseY = Math.round((mouseY - windowY) / interfaceScale);

        context.pose().pushPose();
        context.pose().translate(windowX, windowY, 0.0F);
        context.pose().scale(interfaceScale, interfaceScale, 1.0F);
        int right = WINDOW_WIDTH;
        int bottom = WINDOW_HEIGHT;

        context.fill(0, 0, right, bottom, 0xf20d1117);
        context.fill(0, 0, right, 34, 0xff253244);
        context.drawString(font, "BUILDER STUDIO", 14, 12, 0xffffffff);
        String subtitle = "configure / inspect / execute";
        context.drawString(font, subtitle, right - 14 - font.width(subtitle), 12, 0xff9fb4c9);

        int leftX = 18;
        int middleX = 258;
        int previewX = 480;
        int contentY = 50;

        context.drawString(font, "GEOMETRY", leftX, contentY - 12, 0xff9fb4c9);
        drawSelector(context, "Shape", builder.shapeValue(), leftX, contentY, LEFT_WIDTH, localMouseX, localMouseY);
        drawSelector(context, "Anchor", builder.anchorValue(), leftX, contentY + ROW_HEIGHT, LEFT_WIDTH, localMouseX, localMouseY);
        int rowY = contentY + ROW_HEIGHT * 2 + 12;
        for (String setting : GEOMETRY_SETTINGS) {
            drawNumber(context, setting, leftX, rowY, LEFT_WIDTH, localMouseX, localMouseY);
            rowY += ROW_HEIGHT;
        }

        context.drawString(font, "BUILD", middleX, contentY - 12, 0xff9fb4c9);
        drawSelector(context, "Material", builder.materialValue(), middleX, contentY, MIDDLE_WIDTH, localMouseX, localMouseY);
        rowY = contentY + 36;
        for (String setting : BUILD_SETTINGS) {
            drawNumber(context, setting, middleX, rowY, MIDDLE_WIDTH, localMouseX, localMouseY);
            rowY += ROW_HEIGHT;
        }
        drawToggle(context, "Auto swap", builder.autoSwapEnabled(), middleX, contentY + 124, MIDDLE_WIDTH, localMouseX, localMouseY);
        drawToggle(context, "Auto move", builder.autoMoveEnabled(), middleX, contentY + 152, MIDDLE_WIDTH, localMouseX, localMouseY);
        drawToggle(context, "World preview", builder.previewEnabled(), middleX, contentY + 180, MIDDLE_WIDTH, localMouseX, localMouseY);
        drawToggle(context, "Hand swing", builder.swingEnabled(), middleX, contentY + 208, MIDDLE_WIDTH, localMouseX, localMouseY);

        drawButton(context, "LIVE", middleX, contentY + 242, 98, 28, 0xff25677a, localMouseX, localMouseY);
        drawButton(context, "LOCK", middleX + 108, contentY + 242, 98, 28, 0xff756529, localMouseX, localMouseY);
        drawButton(context, "BUILD", middleX, contentY + 278, 98, 30, 0xff2f7549, localMouseX, localMouseY);
        drawButton(context, "CANCEL", middleX + 108, contentY + 278, 98, 30, 0xff704044, localMouseX, localMouseY);
        drawButton(context, "BACK", middleX, contentY + 316, MIDDLE_WIDTH, 26, 0xff303a46, localMouseX, localMouseY);

        drawPreview(context, previewX, contentY, 192, 342);

        String hint = editingSetting == null
            ? "Changes update preview. LIVE follows the anchor; LOCK freezes it."
            : "Editing " + editingSetting + ": Enter applies, Escape cancels";
        context.drawString(font, hint, 18, bottom - 20, 0xff91a5ba);
        context.pose().popPose();
        super.render(context, mouseX, mouseY, partialTick);
    }

    private void drawPreview(GuiGraphics context, int x, int y, int w, int h) {
        BuilderModule.EditorPreview preview = builder.editorPreview(minecraft);
        context.fill(x, y, x + w, y + h, 0xff151d27);
        context.fill(x, y, x + w, y + 22, 0xff2a394b);
        context.drawString(font, "SCHEMATIC", x + 8, y + 7, 0xffffffff);

        ItemStack material = preview.material();
        if (!material.isEmpty()) {
            context.renderItem(material, x + 8, y + 29);
        } else {
            context.fill(x + 8, y + 29, x + 24, y + 45, 0xff394958);
        }
        String materialId = clip(preview.materialId(), w - 42);
        context.drawString(font, materialId, x + 31, y + 30, material.isEmpty() ? 0xffffa0a0 : 0xffd7e1ec);
        context.drawString(font, preview.dimensions(), x + 31, y + 42, 0xff91a5ba);

        int canvasX = x + 8;
        int canvasY = y + 58;
        int canvasW = w - 16;
        int canvasH = 208;
        context.fill(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 0xff0c1117);
        context.fill(canvasX + 1, canvasY + canvasH - 28, canvasX + canvasW - 1, canvasY + canvasH - 27, 0xff253244);
        drawIsometricVoxels(context, preview.voxels(), material, canvasX, canvasY, canvasW, canvasH);

        drawStatusLine(context, "Queue", builder.pendingCount() + " / " + builder.plannedCount(), x + 8, y + 278, w - 16);
        drawStatusLine(context, "Draft", builder.draftStateValue() + " / " + builder.draftCount(), x + 8, y + 296, w - 16);
        drawStatusLine(context, "State", builder.buildStateValue(), x + 8, y + 314, w - 16);
    }

    private void drawIsometricVoxels(GuiGraphics context, List<BuilderModule.PreviewVoxel> source,
                                     ItemStack material, int x, int y, int w, int h) {
        if (source.isEmpty()) {
            context.drawCenteredString(font, "no geometry", x + w / 2, y + h / 2, 0xff65778a);
            return;
        }
        List<BuilderModule.PreviewVoxel> voxels = new ArrayList<>(source);
        voxels.sort(Comparator
            .comparingInt((BuilderModule.PreviewVoxel voxel) -> voxel.x() + voxel.z())
            .thenComparingInt(BuilderModule.PreviewVoxel::y)
            .thenComparingInt(BuilderModule.PreviewVoxel::x));

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BuilderModule.PreviewVoxel voxel : voxels) {
            int projectedX = (voxel.x() - voxel.z()) * 8;
            int projectedY = (voxel.x() + voxel.z()) * 4 - voxel.y() * 8;
            minX = Math.min(minX, projectedX);
            maxX = Math.max(maxX, projectedX);
            minY = Math.min(minY, projectedY);
            maxY = Math.max(maxY, projectedY);
        }
        int originX = x + (w - (maxX - minX)) / 2 - minX - 3;
        int originY = y + (h - (maxY - minY)) / 2 - minY - 6;

        for (BuilderModule.PreviewVoxel voxel : voxels) {
            int drawX = originX + (voxel.x() - voxel.z()) * 8;
            int drawY = originY + (voxel.x() + voxel.z()) * 4 - voxel.y() * 8;
            if (material.isEmpty()) {
                context.fill(drawX, drawY, drawX + 7, drawY + 7, 0xff55b8d6);
                continue;
            }
            context.pose().pushPose();
            context.pose().translate(drawX, drawY, 40.0F);
            context.pose().scale(0.5F, 0.5F, 1.0F);
            context.renderItem(material, 0, 0);
            context.pose().popPose();
        }
    }

    private void drawStatusLine(GuiGraphics context, String label, String value, int x, int y, int w) {
        context.drawString(font, label, x, y, 0xff91a5ba);
        String clipped = clip(value, w - 46);
        context.drawString(font, clipped, x + w - font.width(clipped), y, 0xffd7e1ec);
    }

    private String clip(String text, int maxWidth) {
        String value = text == null ? "none" : text;
        if (font.width(value) <= maxWidth) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private void drawSelector(GuiGraphics context, String label, String value, int x, int y, int w, int mouseX, int mouseY) {
        context.drawString(font, label, x, y + 8, 0xffc9d5e2);
        int controlX = x + 78;
        context.fill(controlX, y, x + w, y + 22, 0xff202832);
        context.fill(controlX, y, controlX + 24, y + 22,
            hovered(mouseX, mouseY, controlX, y, 24, 22) ? 0xff3a4858 : 0xff2b3643);
        context.fill(x + w - 24, y, x + w, y + 22,
            hovered(mouseX, mouseY, x + w - 24, y, 24, 22) ? 0xff3a4858 : 0xff2b3643);
        context.drawCenteredString(font, "<", controlX + 12, y + 7, 0xffffffff);
        context.drawCenteredString(font, ">", x + w - 12, y + 7, 0xffffffff);
        String clipped = clip(value, w - 132);
        context.drawCenteredString(font, clipped, controlX + (w - 78) / 2, y + 7, 0xffb4ffca);
    }

    private void drawNumber(GuiGraphics context, String label, int x, int y, int w, int mouseX, int mouseY) {
        context.drawString(font, label, x, y + 8, 0xffc9d5e2);
        int controlX = x + 110;
        context.fill(controlX, y, x + w, y + 22, 0xff202832);
        context.fill(controlX, y, controlX + 24, y + 22,
            hovered(mouseX, mouseY, controlX, y, 24, 22) ? 0xff3a4858 : 0xff2b3643);
        context.fill(x + w - 24, y, x + w, y + 22,
            hovered(mouseX, mouseY, x + w - 24, y, 24, 22) ? 0xff3a4858 : 0xff2b3643);
        context.drawCenteredString(font, "-", controlX + 12, y + 7, 0xffffffff);
        context.drawCenteredString(font, "+", x + w - 12, y + 7, 0xffffffff);
        String text = settingDisplay(label);
        context.drawCenteredString(font, text, controlX + (w - 110) / 2, y + 7,
            editingSetting != null && editingSetting.equals(label) ? 0xffffd65a : 0xffb4ffca);
    }

    private String settingDisplay(String label) {
        if (label.equals(editingSetting)) {
            boolean caret = (System.currentTimeMillis() / 400L) % 2L == 0L;
            return ">" + editingText + (caret ? "_" : "");
        }
        return builder.settingDisplay(label);
    }

    private void drawToggle(GuiGraphics context, String label, boolean enabled, int x, int y, int w, int mouseX, int mouseY) {
        int color = enabled ? 0xff2f7549 : hovered(mouseX, mouseY, x, y, w, 22) ? 0xff303a46 : 0xff202832;
        context.fill(x, y, x + w, y + 22, color);
        context.drawString(font, label, x + 8, y + 7, 0xffd7e1ec);
        String state = enabled ? "ON" : "OFF";
        context.drawString(font, state, x + w - 8 - font.width(state), y + 7, enabled ? 0xffb4ffca : 0xff9aa9b8);
    }

    private void drawButton(GuiGraphics context, String text, int x, int y, int w, int h, int color, int mouseX, int mouseY) {
        int active = hovered(mouseX, mouseY, x, y, w, h) ? brighten(color) : color;
        context.fill(x, y, x + w, y + h, active);
        context.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, 0xffffffff);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        commitEdit();
        double localMouseX = (mouseX - windowX) / interfaceScale;
        double localMouseY = (mouseY - windowY) / interfaceScale;
        int leftX = 18;
        int middleX = 258;
        int contentY = 50;
        if (button == 0 || button == 1) {
            if (clickSelector(localMouseX, localMouseY, leftX, contentY, LEFT_WIDTH, builder::cycleShape)) {
                return saveAndConsume();
            }
            if (clickSelector(localMouseX, localMouseY, leftX, contentY + ROW_HEIGHT, LEFT_WIDTH, builder::cycleAnchor)) {
                return saveAndConsume();
            }
            int rowY = contentY + ROW_HEIGHT * 2 + 12;
            for (String setting : GEOMETRY_SETTINGS) {
                if (clickNumber(localMouseX, localMouseY, setting, leftX, rowY, LEFT_WIDTH)) {
                    return true;
                }
                rowY += ROW_HEIGHT;
            }

            if (clickSelector(localMouseX, localMouseY, middleX, contentY, MIDDLE_WIDTH, builder::cycleMaterial)) {
                return saveAndConsume();
            }
            rowY = contentY + 36;
            for (String setting : BUILD_SETTINGS) {
                if (clickNumber(localMouseX, localMouseY, setting, middleX, rowY, MIDDLE_WIDTH)) {
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 124, MIDDLE_WIDTH, 22)) {
                builder.toggleAutoSwap();
                return saveAndConsume();
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 152, MIDDLE_WIDTH, 22)) {
                builder.toggleAutoMove();
                return saveAndConsume();
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 180, MIDDLE_WIDTH, 22)) {
                builder.togglePreview();
                return saveAndConsume();
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 208, MIDDLE_WIDTH, 22)) {
                builder.toggleSwing();
                return saveAndConsume();
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 242, 98, 28)) {
                builder.startDraftPreview();
                closeToGame();
                return true;
            }
            if (inside(localMouseX, localMouseY, middleX + 108, contentY + 242, 98, 28)) {
                builder.lockDraftPreview();
                return true;
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 278, 98, 30)) {
                builder.startFromUi();
                closeToGame();
                return true;
            }
            if (inside(localMouseX, localMouseY, middleX + 108, contentY + 278, 98, 30)) {
                builder.cancelFromUi();
                return true;
            }
            if (inside(localMouseX, localMouseY, middleX, contentY + 316, MIDDLE_WIDTH, 26)) {
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickSelector(double mouseX, double mouseY, int x, int y, int w, java.util.function.IntConsumer cycle) {
        int controlX = x + 78;
        if (inside(mouseX, mouseY, controlX, y, 24, 22)) {
            cycle.accept(-1);
            return true;
        }
        if (inside(mouseX, mouseY, x + w - 24, y, 24, 22)) {
            cycle.accept(1);
            return true;
        }
        return false;
    }

    private boolean clickNumber(double mouseX, double mouseY, String setting, int x, int y, int w) {
        int controlX = x + 110;
        if (inside(mouseX, mouseY, controlX, y, 24, 22)) {
            builder.adjustSetting(setting, -1);
            return saveAndConsume();
        }
        if (inside(mouseX, mouseY, x + w - 24, y, 24, 22)) {
            builder.adjustSetting(setting, 1);
            return saveAndConsume();
        }
        if (inside(mouseX, mouseY, controlX + 24, y, w - 158, 22)) {
            editingSetting = setting;
            editingText = builder.settingDisplay(setting);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingSetting != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editingSetting = null;
                editingText = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !editingText.isEmpty()) {
                editingText = editingText.substring(0, editingText.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                editingText = "";
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editingSetting != null && ((codePoint >= '0' && codePoint <= '9') || codePoint == '-' || codePoint == '.')) {
            if (editingText.length() < 8 && (codePoint != '.' || !editingText.contains("."))) {
                editingText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void commitEdit() {
        if (editingSetting == null) {
            return;
        }
        if (!editingText.isBlank() && !"-".equals(editingText) && !".".equals(editingText)) {
            builder.setSetting(editingSetting, editingText);
            save();
        }
        editingSetting = null;
        editingText = "";
    }

    private boolean saveAndConsume() {
        save();
        return true;
    }

    private void closeToGame() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private void save() {
        XenobyteModernClient.MODULES.saveConfig();
    }

    private boolean hovered(int mouseX, int mouseY, int x, int y, int w, int h) {
        return inside(mouseX, mouseY, x, y, w, h);
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
