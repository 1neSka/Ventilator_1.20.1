package team.xenobyte.modern.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderBlockScreenEffectEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.WorldRenderContext;

public abstract class XenoModule {
    private final String name;
    private final Category category;
    private final ModuleMode mode;
    private final List<ModuleSetting> settings = new ArrayList<>();
    private boolean enabled;
    private int keyBind = GLFW.GLFW_KEY_UNKNOWN;
    private boolean bindWasDown;
    private boolean hidden;
    private int hudOffsetX;
    private int hudOffsetY;

    protected XenoModule(String name, Category category, ModuleMode mode) {
        this.name = name;
        this.category = category;
        this.mode = mode;
    }

    public final String name() {
        return name;
    }

    public final Category category() {
        return category;
    }

    public final ModuleMode mode() {
        return mode;
    }

    public final boolean enabled() {
        return enabled;
    }

    public final List<ModuleSetting> settings() {
        return Collections.unmodifiableList(settings);
    }

    public List<ModuleSetting> menuSettings() {
        return settings();
    }

    public final String settingsSummary() {
        if (settings.isEmpty()) {
            return "settings=[]";
        }
        StringBuilder builder = new StringBuilder("settings=[");
        for (int i = 0; i < settings.size(); i++) {
            ModuleSetting setting = settings.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(setting.name()).append("=").append(setting.displayValue());
        }
        return builder.append("]").toString();
    }

    public final boolean hasSettings() {
        return !settings.isEmpty();
    }

    public final ModuleSetting setting(String name, ModuleSetting setting) {
        settings.add(setting);
        return setting;
    }

    public final int keyBind() {
        return keyBind;
    }

    public final void setKeyBind(int keyBind) {
        this.keyBind = keyBind;
        this.bindWasDown = false;
    }

    public final String keyBindName() {
        if (keyBind == GLFW.GLFW_KEY_UNKNOWN) {
            return "NONE";
        }
        String name = GLFW.glfwGetKeyName(keyBind, 0);
        if (name != null && !name.isBlank()) {
            return name.toUpperCase();
        }
        return switch (keyBind) {
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "L SHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "R SHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "L CTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "R CTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "L ALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "R ALT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            default -> "KEY " + keyBind;
        };
    }

    public final boolean hidden() {
        return hidden;
    }

    public final void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public final int hudOffsetX() {
        return hudOffsetX;
    }

    public final int hudOffsetY() {
        return hudOffsetY;
    }

    public final void setHudOffset(int x, int y) {
        hudOffsetX = x;
        hudOffsetY = y;
    }

    public HudBounds hudBounds(Minecraft client) {
        return null;
    }

    public final boolean pollBind(Minecraft client) {
        if (client == null || client.getWindow() == null || keyBind == GLFW.GLFW_KEY_UNKNOWN || (client.screen != null && !allowBindInGui())) {
            bindWasDown = false;
            return false;
        }

        boolean down = GLFW.glfwGetKey(client.getWindow().getWindow(), keyBind) == GLFW.GLFW_PRESS;
        boolean performed = false;
        if (down && !bindWasDown) {
            perform(client);
            performed = true;
        }
        bindWasDown = down;
        return performed;
    }

    public final void perform(Minecraft client) {
        if (mode == ModuleMode.SINGLE) {
            onPerform(client);
            return;
        }
        setEnabled(client, !enabled);
    }

    public final void setEnabled(Minecraft client, boolean enabled) {
        if (mode == ModuleMode.SINGLE || this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable(client);
        } else {
            onDisable(client);
        }
    }

    public void onEnable(Minecraft client) {
    }

    public void onDisable(Minecraft client) {
    }

    public void onPerform(Minecraft client) {
    }

    public void onTick(Minecraft client) {
    }

    public void onClientTickStart(Minecraft client) {
    }

    public void onBeforeRender(Minecraft client) {
    }

    public void onHudRender(GuiGraphics context, float partialTick) {
    }

    public void onWorldRender(WorldRenderContext context) {
    }

    public void onItemTooltip(ItemTooltipEvent event) {
    }

    public void onMovementInput(MovementInputUpdateEvent event) {
    }

    public void onRenderBlockScreenEffect(RenderBlockScreenEffectEvent event) {
    }

    public boolean wantsTickWhenDisabled() {
        return false;
    }

    public boolean wantsWorldRenderWhenDisabled() {
        return false;
    }

    public boolean allowBindInGui() {
        return false;
    }

    public String runtimeInfo(Minecraft client) {
        return "";
    }

    public String description() {
        return "";
    }

    public record HudBounds(int x, int y, int width, int height) {
        public HudBounds translated(int offsetX, int offsetY) {
            return new HudBounds(x + offsetX, y + offsetY, width, height);
        }
    }
}
