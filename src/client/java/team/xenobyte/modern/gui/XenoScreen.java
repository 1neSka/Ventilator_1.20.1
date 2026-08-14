package team.xenobyte.modern.gui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class XenoScreen extends Screen {
    private static final int PANEL_WIDTH = 124;
    private static final int SETTINGS_WIDTH = 138;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_HEIGHT = 17;
    private static final int SETTING_ROW_HEIGHT = 16;
    private static final int GAP = 8;

    private final ModuleManager modules;
    private final Screen returnScreen;
    private XenoModule activeModule;
    private XenoModule hoveredModule;
    private ModuleSetting hoveredSetting;
    private int settingsX;
    private int settingsY;
    private int settingsHeight;
    private int settingsScroll;
    private ModuleSetting editingSetting;
    private String editingText = "";
    private Category draggingCategory;
    private int panelDragOffsetX;
    private int panelDragOffsetY;
    private boolean showHidden;

    public XenoScreen(ModuleManager modules) {
        this(modules, null);
    }

    public XenoScreen(ModuleManager modules, Screen returnScreen) {
        super(Component.literal("Xenobyte Modern"));
        this.modules = modules;
        this.returnScreen = returnScreen == this ? null : returnScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null && returnScreen != null) {
            minecraft.setScreen(returnScreen);
            return;
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        XenoModule hovered = null;
        hoveredSetting = null;
        boolean settingsCapture = isInsideSettingsPanel(mouseX, mouseY);
        int defaultX = 12;
        for (Category category : Category.values()) {
            ModuleManager.PanelPosition stored = modules.panelPosition(category, defaultX, 18);
            int x = clamp(stored.x(), 4, Math.max(4, width - PANEL_WIDTH - 4));
            int y = clamp(stored.y(), 18, Math.max(18, height - HEADER_HEIGHT - 8));
            if (x != stored.x() || y != stored.y()) {
                modules.setPanelPosition(category, x, y);
            }
            XenoModule panelHover = renderPanel(context, category, x, y, mouseX, mouseY, settingsCapture);
            if (panelHover != null) {
                hovered = panelHover;
            }
            defaultX += PANEL_WIDTH + GAP;
        }

        if (hovered != null && editingSetting == null) {
            hoveredModule = hovered;
            setActiveModule(hovered);
        } else if (editingSetting == null) {
            hoveredModule = null;
        }
        renderSettingsPanel(context, mouseX, mouseY);
        renderToolbar(context, mouseX, mouseY);

        String footer = modules.isPanicked()
            ? "PANIC ACTIVE"
            : hoveredModule == null ? "Hover module + key binds, hold L SHIFT for info" : "Binding target: " + hoveredModule.name();
        context.drawString(font, footer, 12, height - 18, modules.isPanicked() ? 0xffff5555 : 0xffb8c7d9);
        renderTooltipIfRequested(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private XenoModule renderPanel(GuiGraphics context, Category category, int x, int y, int mouseX, int mouseY, boolean settingsCapture) {
        XenoModule hovered = null;
        java.util.List<XenoModule> displayed = displayedModules(category);
        int rows = Math.max(1, displayed.size());
        int panelHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + 6;
        context.fill(x, y, x + PANEL_WIDTH, y + panelHeight, 0xcc11161d);
        int headerColor = draggingCategory == category ? 0xff3a526c : 0xff253244;
        context.fill(x, y, x + PANEL_WIDTH, y + HEADER_HEIGHT, headerColor);
        context.drawString(font, category.name(), x + 6, y + 5, 0xffffffff);
        String count = Integer.toString(displayed.size());
        context.drawString(font, count, x + PANEL_WIDTH - 7 - font.width(count), y + 5, 0xff9fb4c9);

        int rowY = y + HEADER_HEIGHT + 3;
        for (XenoModule module : displayed) {
            boolean rowHovered = !settingsCapture
                && mouseX >= x + 4 && mouseX <= x + PANEL_WIDTH - 4
                && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 2;
            if (rowHovered) {
                hovered = module;
                settingsX = Math.min(width - SETTINGS_WIDTH - 8, x + PANEL_WIDTH + GAP);
                settingsY = Math.max(18, Math.min(height - settingsPanelHeight(module) - 24, rowY - 3));
                settingsHeight = settingsPanelHeight(module);
            }

            int bg = module.hidden() ? (rowHovered ? 0xff4a3b43 : 0xff302832)
                : module.enabled() ? 0xff2f7549 : rowHovered ? 0xff303a46 : 0xff202832;
            int fg = module.hidden() ? 0xffb99cab : module.enabled() ? 0xffe7fff0 : 0xffd7e1ec;
            context.fill(x + 4, rowY, x + PANEL_WIDTH - 4, rowY + ROW_HEIGHT - 2, bg);
            context.drawString(font, module.name(), x + 9, rowY + 4, fg);
            if (module.hidden()) {
                context.drawString(font, "H", x + PANEL_WIDTH - 17, rowY + 4, 0xffffa0b8);
            } else if (module.keyBind() != GLFW.GLFW_KEY_UNKNOWN) {
                String bind = module.keyBindName();
                context.drawString(font, bind, x + PANEL_WIDTH - 10 - font.width(bind), rowY + 4, 0xffb8c7d9);
            } else if (module.enabled()) {
                context.drawString(font, "ON", x + PANEL_WIDTH - 24, rowY + 4, 0xffb4ffca);
            }
            rowY += ROW_HEIGHT;
        }
        return hovered;
    }

    private void renderToolbar(GuiGraphics context, int mouseX, int mouseY) {
        int x = toolbarX();
        drawToolbarButton(context, "HUD", x, mouseX, mouseY, false);
        drawToolbarButton(context, showHidden ? "HIDDEN:ON" : "HIDDEN", x + 46, mouseX, mouseY, showHidden);
        drawToolbarButton(context, "RESET", x + 128, mouseX, mouseY, false);
    }

    private void drawToolbarButton(GuiGraphics context, String label, int x, int mouseX, int mouseY, boolean active) {
        int width = "HIDDEN:ON".equals(label) || "HIDDEN".equals(label) ? 76 : 40;
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= 2 && mouseY <= 15;
        context.fill(x, 2, x + width, 15, active ? 0xff2f7549 : hovered ? 0xff394958 : 0xcc202832);
        context.drawCenteredString(font, label, x + width / 2, 5, active ? 0xffe7fff0 : 0xffc6d3e0);
    }

    private void renderSettingsPanel(GuiGraphics context, int mouseX, int mouseY) {
        if (activeModule == null) {
            return;
        }

        if (settingsHeight <= 0) {
            settingsHeight = settingsPanelHeight(activeModule);
        }
        clampSettingsScroll();
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, 300.0F);
        context.fill(settingsX - 4, settingsY - 4, settingsX + SETTINGS_WIDTH + 4, settingsY + settingsHeight + 4, 0xaa000000);
        context.fill(settingsX - 1, settingsY - 1, settingsX + SETTINGS_WIDTH + 1, settingsY + settingsHeight + 1, 0xff6f8aae);
        context.fill(settingsX, settingsY, settingsX + SETTINGS_WIDTH, settingsY + settingsHeight, 0xdd10151c);
        context.fill(settingsX, settingsY, settingsX + SETTINGS_WIDTH, settingsY + HEADER_HEIGHT, 0xff314052);
        context.drawString(font, activeModule.name(), settingsX + 6, settingsY + 5, 0xffffffff);

        int rowsTop = settingsRowsTop();
        int rowsBottom = settingsRowsBottom();
        int y = rowsTop - settingsScroll;
        context.enableScissor(settingsX, rowsTop, settingsX + SETTINGS_WIDTH, rowsBottom);
        drawSettingRow(context, "Bind", activeModule.keyBindName(), y, mouseX, mouseY, null);
        y += SETTING_ROW_HEIGHT;
        for (ModuleSetting setting : activeModule.menuSettings()) {
            String value = setting == editingSetting ? editingValue() : setting.displayValue();
            drawSettingRow(context, setting.name(), value, y, mouseX, mouseY, setting);
            y += SETTING_ROW_HEIGHT;
        }
        if (activeModule.menuSettings().isEmpty()) {
            context.drawString(font, "No settings", settingsX + 7, y + 2, 0xff7f8a99);
        }
        context.disableScissor();
        drawSettingsScrollBar(context);
        context.pose().popPose();
    }

    private void drawSettingRow(GuiGraphics context, String name, String value, int y, int mouseX, int mouseY, ModuleSetting setting) {
        boolean visible = y + SETTING_ROW_HEIGHT - 2 >= settingsRowsTop() && y <= settingsRowsBottom();
        boolean hovered = visible
            && mouseX >= settingsX + 4 && mouseX <= settingsX + SETTINGS_WIDTH - 4
            && mouseY >= y && mouseY <= y + SETTING_ROW_HEIGHT - 2;
        if (hovered && setting != null) {
            hoveredSetting = setting;
        }
        boolean editing = setting != null && setting == editingSetting;
        context.fill(settingsX + 4, y, settingsX + SETTINGS_WIDTH - 4, y + SETTING_ROW_HEIGHT - 2, editing ? 0xff435066 : hovered ? 0xff303a46 : 0xff1c2430);
        context.drawString(font, name, settingsX + 8, y + 4, 0xffd7e1ec);
        context.drawString(font, value, settingsX + SETTINGS_WIDTH - 8 - font.width(value), y + 4, editing ? 0xffffffff : 0xffb4ffca);
    }

    private void renderTooltipIfRequested(GuiGraphics context, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }
        if (GLFW.glfwGetKey(client.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) != GLFW.GLFW_PRESS) {
            return;
        }

        renderMessageLog(context);

        String title = "";
        String body = "";
        if (hoveredSetting != null) {
            title = hoveredSetting.name();
            body = hoveredSetting.description();
        } else {
            XenoModule target = hoveredModule != null ? hoveredModule : activeModule;
            if (target != null) {
                title = target.name();
                body = target.description();
            }
        }
        if (body == null || body.isBlank()) {
            return;
        }

        java.util.List<String> lines = wrap(body, 42);
        int tooltipWidth = Math.max(font.width(title), lines.stream().mapToInt(font::width).max().orElse(0)) + 12;
        int tooltipHeight = 18 + lines.size() * 10;
        int x = Math.min(width - tooltipWidth - 8, mouseX + 12);
        int y = Math.min(height - tooltipHeight - 8, mouseY + 12);
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, 500.0F);
        context.fill(x - 1, y - 1, x + tooltipWidth + 1, y + tooltipHeight + 1, 0xff6f8aae);
        context.fill(x, y, x + tooltipWidth, y + tooltipHeight, 0xee10151c);
        context.drawString(font, title, x + 6, y + 5, 0xffffffff);
        int lineY = y + 17;
        for (String line : lines) {
            context.drawString(font, line, x + 6, lineY, 0xffd7e1ec);
            lineY += 10;
        }
        context.pose().popPose();
    }

    private void renderMessageLog(GuiGraphics context) {
        java.util.List<ModuleMessageLog.Entry> entries = ModuleMessageLog.snapshot();
        if (entries.isEmpty()) {
            return;
        }

        int maxWidth = Math.min(width - 24, 520);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (ModuleMessageLog.Entry entry : entries) {
            String line = "[" + entry.time() + "] " + entry.module() + ": " + entry.message();
            lines.add(trimToWidth(line, maxWidth - 12));
        }

        int boxWidth = Math.min(maxWidth, lines.stream().mapToInt(font::width).max().orElse(0) + 12);
        int boxHeight = lines.size() * 10 + 8;
        int x = 12;
        int y = Math.max(24, height - 28 - boxHeight);
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, 520.0F);
        context.fill(x - 1, y - 1, x + boxWidth + 1, y + boxHeight + 1, 0xff6f8aae);
        context.fill(x, y, x + boxWidth, y + boxHeight, 0xee10151c);
        int lineY = y + 5;
        for (String line : lines) {
            context.drawString(font, line, x + 6, lineY, 0xffd7e1ec);
            lineY += 10;
        }
        context.pose().popPose();
    }

    private String trimToWidth(String line, int maxWidth) {
        if (font.width(line) <= maxWidth) {
            return line;
        }
        String suffix = "...";
        int allowed = Math.max(0, maxWidth - font.width(suffix));
        String trimmed = font.plainSubstrByWidth(line, allowed);
        return trimmed + suffix;
    }

    private java.util.List<String> wrap(String text, int maxChars) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private int settingsPanelHeight(XenoModule module) {
        return Math.min(settingsContentHeight(module), maxSettingsPanelHeight());
    }

    private int settingsContentHeight(XenoModule module) {
        return HEADER_HEIGHT + 8 + SETTING_ROW_HEIGHT * (1 + Math.max(1, module.menuSettings().size()));
    }

    private int maxSettingsPanelHeight() {
        return Math.max(HEADER_HEIGHT + 8 + SETTING_ROW_HEIGHT * 5, height - 42);
    }

    private int settingsRowsTop() {
        return settingsY + HEADER_HEIGHT + 4;
    }

    private int settingsRowsBottom() {
        return settingsY + settingsHeight - 4;
    }

    private int settingsScrollableHeight() {
        return Math.max(0, settingsContentHeight(activeModule) - settingsHeight);
    }

    private void clampSettingsScroll() {
        if (activeModule == null) {
            settingsScroll = 0;
            return;
        }
        settingsScroll = Math.max(0, Math.min(settingsScroll, settingsScrollableHeight()));
    }

    private void drawSettingsScrollBar(GuiGraphics context) {
        int maxScroll = settingsScrollableHeight();
        if (maxScroll <= 0) {
            return;
        }
        int trackTop = settingsRowsTop();
        int trackBottom = settingsRowsBottom();
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int contentHeight = settingsContentHeight(activeModule) - HEADER_HEIGHT - 8;
        int thumbHeight = Math.max(14, trackHeight * trackHeight / Math.max(trackHeight, contentHeight));
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        int thumbY = trackTop + settingsScroll * thumbTravel / maxScroll;
        int x = settingsX + SETTINGS_WIDTH - 3;
        context.fill(x, trackTop, x + 2, trackBottom, 0x66000000);
        context.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xff8ea7c7);
    }

    private void setActiveModule(XenoModule module) {
        if (activeModule != module) {
            cancelEdit();
            activeModule = module;
            settingsScroll = 0;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ModuleSetting previousEdit = editingSetting;
        if (previousEdit != null) {
            commitEdit();
        }
        if (button == 0 || button == 1) {
            ModuleSetting setting = settingAt(mouseX, mouseY);
            if (setting != null) {
                if (button == 0 && setting.kind() == ModuleSetting.Kind.DOUBLE) {
                    beginEdit(setting);
                    return true;
                }
                setting.click(button);
                modules.saveConfig();
                return true;
            }
            if (isInsideSettingsPanel(mouseX, mouseY)) {
                return true;
            }
        }

        int toolbarX = toolbarX();
        if (button == 0 && inside(mouseX, mouseY, toolbarX, 2, 40, 13)) {
            if (minecraft != null) {
                minecraft.setScreen(new HudEditorScreen(modules, this));
            }
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY, toolbarX + 46, 2, 76, 13)) {
            showHidden = !showHidden;
            if (!showHidden && activeModule != null && activeModule.hidden()) {
                activeModule = null;
            }
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY, toolbarX + 128, 2, 40, 13)) {
            modules.resetPanelPositions();
            modules.saveConfig();
            return true;
        }

        int defaultX = 12;
        for (Category category : Category.values()) {
            ModuleManager.PanelPosition position = modules.panelPosition(category, defaultX, 18);
            int panelX = clamp(position.x(), 4, Math.max(4, width - PANEL_WIDTH - 4));
            int panelY = clamp(position.y(), 18, Math.max(18, height - HEADER_HEIGHT - 8));
            if (button == 0 && inside(mouseX, mouseY, panelX, panelY, PANEL_WIDTH, HEADER_HEIGHT)) {
                draggingCategory = category;
                panelDragOffsetX = (int)Math.round(mouseX) - panelX;
                panelDragOffsetY = (int)Math.round(mouseY) - panelY;
                return true;
            }

            int rowY = panelY + HEADER_HEIGHT + 3;
            for (XenoModule module : displayedModules(category)) {
                if (mouseX >= panelX + 4 && mouseX <= panelX + PANEL_WIDTH - 4 && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 2) {
                    setActiveModule(module);
                    if (button == 1) {
                        module.setHidden(!module.hidden());
                        modules.saveConfig();
                        if (module.hidden() && !showHidden) {
                            activeModule = null;
                            hoveredModule = null;
                        }
                    } else if (button == 0) {
                        modules.perform(module, Minecraft.getInstance());
                    }
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
            defaultX += PANEL_WIDTH + GAP;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingCategory != null && button == 0) {
            int x = clamp((int)Math.round(mouseX) - panelDragOffsetX, 4, Math.max(4, width - PANEL_WIDTH - 4));
            int y = clamp((int)Math.round(mouseY) - panelDragOffsetY, 18, Math.max(18, height - HEADER_HEIGHT - 8));
            modules.setPanelPosition(draggingCategory, x, y);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingCategory != null && button == 0) {
            draggingCategory = null;
            modules.saveConfig();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (editingSetting != null) {
            return true;
        }
        if (isInsideSettingsPanel(mouseX, mouseY) && settingsScrollableHeight() > 0) {
            settingsScroll -= (int)Math.round(delta * SETTING_ROW_HEIGHT);
            clampSettingsScroll();
            return true;
        }
        ModuleSetting setting = settingAt(mouseX, mouseY);
        if (setting != null) {
            setting.adjust(delta > 0.0D ? 1.0D : -1.0D);
            modules.saveConfig();
            return true;
        }
        if (isInsideSettingsPanel(mouseX, mouseY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean isInsideSettingsPanel(double mouseX, double mouseY) {
        return activeModule != null
            && mouseX >= settingsX - 4 && mouseX <= settingsX + SETTINGS_WIDTH + 4
            && mouseY >= settingsY - 4 && mouseY <= settingsY + settingsHeight + 4;
    }

    private ModuleSetting settingAt(double mouseX, double mouseY) {
        if (activeModule == null
            || mouseX < settingsX + 4 || mouseX > settingsX + SETTINGS_WIDTH - 4
            || mouseY < settingsRowsTop() || mouseY > settingsRowsBottom()) {
            return null;
        }

        int y = settingsRowsTop() + SETTING_ROW_HEIGHT - settingsScroll;
        for (ModuleSetting setting : activeModule.menuSettings()) {
            if (mouseY >= y && mouseY <= y + SETTING_ROW_HEIGHT - 2) {
                return setting;
            }
            y += SETTING_ROW_HEIGHT;
        }
        return null;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingSetting != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editingText.isEmpty()) {
                    editingText = editingText.substring(0, editingText.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                editingText = "";
                return true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT) {
            return true;
        }
        if (hoveredModule != null && keyCode != GLFW.GLFW_KEY_UNKNOWN) {
            modules.assignBind(hoveredModule, keyCode);
            modules.saveConfig();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editingSetting != null) {
            if (isNumericChar(codePoint)) {
                editingText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void beginEdit(ModuleSetting setting) {
        editingSetting = setting;
        editingText = setting.displayValue();
        ModuleMessageLog.push(activeModule == null ? "GUI" : activeModule.name(), "editing " + setting.name() + " = " + editingText);
    }

    private void commitEdit() {
        if (editingSetting == null) {
            return;
        }
        ModuleSetting setting = editingSetting;
        String raw = editingText == null ? "" : editingText.trim();
        if (!raw.isBlank() && isValidNumber(raw)) {
            setting.setFromString(raw);
            modules.saveConfig();
            ModuleMessageLog.push(activeModule == null ? "GUI" : activeModule.name(), setting.name() + " = " + setting.displayValue());
        } else if (!raw.isBlank()) {
            ModuleMessageLog.push(activeModule == null ? "GUI" : activeModule.name(), "invalid number: " + raw);
        }
        editingSetting = null;
        editingText = "";
    }

    private void cancelEdit() {
        editingSetting = null;
        editingText = "";
    }

    private String editingValue() {
        boolean caret = (System.currentTimeMillis() / 400L) % 2L == 0L;
        return ">" + editingText + (caret ? "_" : "");
    }

    private boolean isNumericChar(char codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
            || codePoint == '-'
            || codePoint == '.'
            || codePoint == 'e'
            || codePoint == 'E';
    }

    private boolean isValidNumber(String raw) {
        try {
            Double.parseDouble(raw);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private java.util.List<XenoModule> displayedModules(Category category) {
        return modules.modules(category).stream().filter(module -> showHidden || !module.hidden()).toList();
    }

    private int toolbarX() {
        return Math.max(4, width - 176);
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
