package team.xenobyte.modern.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderBlockScreenEffectEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.config.ConfigStore;
import team.xenobyte.modern.gui.OverlayNotifier;
import team.xenobyte.modern.render.WorldRenderContext;

public class ModuleManager {
    private final List<XenoModule> modules = new ArrayList<>();
    private final Map<Category, PanelPosition> panelPositions = new EnumMap<>(Category.class);
    private boolean panicked;

    public boolean register(XenoModule module) {
        if (modules.stream().anyMatch(existing -> existing.name().equalsIgnoreCase(module.name()))) {
            return false;
        }
        modules.add(module);
        return true;
    }

    public List<XenoModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    public List<XenoModule> modules(Category category) {
        return modules.stream().filter(module -> module.category() == category).collect(Collectors.toList());
    }

    public PanelPosition panelPosition(Category category, int defaultX, int defaultY) {
        return panelPositions.getOrDefault(category, new PanelPosition(defaultX, defaultY));
    }

    public void setPanelPosition(Category category, int x, int y) {
        if (category != null) {
            panelPositions.put(category, new PanelPosition(x, y));
        }
    }

    public void resetPanelPositions() {
        panelPositions.clear();
    }

    public boolean isPanicked() {
        return panicked;
    }

    public String summary() {
        String moduleSummary = modules.stream()
            .map(module -> module.name() + ":" + module.category() + ":" + module.mode())
            .collect(Collectors.joining(", "));
        return "panicked=" + panicked + ", count=" + modules.size() + ", modules=[" + moduleSummary + "]";
    }

    public void loadConfig() {
        ConfigStore.load(this);
    }

    public void saveConfig() {
        ConfigStore.save(this);
    }

    public void assignBind(XenoModule target, int keyCode) {
        if (target == null) {
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE || target.keyBind() == keyCode) {
            target.setKeyBind(GLFW.GLFW_KEY_UNKNOWN);
            BootstrapLog.info("Bind cleared: module=" + target.name());
            return;
        }
        for (XenoModule module : modules) {
            if (module != target && module.keyBind() == keyCode) {
                module.setKeyBind(GLFW.GLFW_KEY_UNKNOWN);
                BootstrapLog.info("Bind conflict cleared: module=" + module.name() + ", key=" + keyCode);
            }
        }
        target.setKeyBind(keyCode);
        BootstrapLog.info("Bind assigned: module=" + target.name() + ", key=" + keyCode + ", name=" + target.keyBindName());
    }

    public void perform(XenoModule module, Minecraft client) {
        if (!panicked || module.name().equals("Panic")) {
            boolean before = module.enabled();
            module.perform(client);
            BootstrapLog.info("Module performed: module=" + module.name()
                + ", mode=" + module.mode()
                + ", before=" + before
                + ", after=" + module.enabled()
                + ", " + module.settingsSummary());
            notifyPerformed(module, before);
            if (!module.name().equals("Panic")) {
                saveConfig();
            }
        }
    }

    public void tick(Minecraft client) {
        if (panicked) {
            return;
        }
        for (XenoModule module : modules) {
            boolean before = module.enabled();
            if (module.pollBind(client)) {
                BootstrapLog.info("Module performed by bind: module=" + module.name()
                    + ", mode=" + module.mode()
                    + ", before=" + before
                    + ", after=" + module.enabled()
                    + ", " + module.settingsSummary());
                notifyPerformed(module, before);
                if (!module.name().equals("Panic")) {
                    saveConfig();
                }
            }
        }
        modules.stream()
            .filter(module -> module.enabled() || module.wantsTickWhenDisabled())
            .forEach(module -> module.onTick(client));
    }

    public void tickStart(Minecraft client) {
        if (panicked) {
            return;
        }
        modules.stream()
            .filter(module -> module.enabled() || module.wantsTickWhenDisabled())
            .forEach(module -> module.onClientTickStart(client));
    }

    public void beforeRender(Minecraft client) {
        if (panicked) {
            return;
        }
        modules.stream().filter(XenoModule::enabled).forEach(module -> module.onBeforeRender(client));
    }

    public void hud(GuiGraphics context, float partialTick) {
        if (panicked) {
            return;
        }
        modules.stream().filter(XenoModule::enabled).forEach(module -> renderHudModule(module, context, partialTick));
    }

    public void renderHudModule(XenoModule module, GuiGraphics context, float partialTick) {
        if (module == null || context == null) {
            return;
        }
        context.pose().pushPose();
        context.pose().translate(module.hudOffsetX(), module.hudOffsetY(), 0.0F);
        module.onHudRender(context, partialTick);
        context.pose().popPose();
    }

    public void world(WorldRenderContext context) {
        if (panicked) {
            return;
        }
        modules.stream()
            .filter(module -> module.enabled() || module.wantsWorldRenderWhenDisabled())
            .forEach(module -> module.onWorldRender(context));
    }

    public void itemTooltip(ItemTooltipEvent event) {
        if (panicked) {
            return;
        }
        modules.stream().filter(XenoModule::enabled).forEach(module -> module.onItemTooltip(event));
    }

    public void movementInput(MovementInputUpdateEvent event) {
        if (panicked) {
            return;
        }
        modules.stream().filter(XenoModule::enabled).forEach(module -> module.onMovementInput(event));
    }

    public void renderBlockScreenEffect(RenderBlockScreenEffectEvent event) {
        if (panicked) {
            return;
        }
        modules.stream().filter(XenoModule::enabled).forEach(module -> module.onRenderBlockScreenEffect(event));
    }

    private void notifyPerformed(XenoModule module, boolean before) {
        if (module.mode() == ModuleMode.TOGGLE) {
            OverlayNotifier.push(module.name() + (module.enabled() ? " enabled" : " disabled"));
        } else {
            OverlayNotifier.push(module.name() + " used");
        }
    }

    public void panic(Minecraft client) {
        if (panicked) {
            return;
        }
        panicked = true;
        for (XenoModule module : modules) {
            if (module.enabled()) {
                module.setEnabled(client, false);
                BootstrapLog.info("Panic disabled module: " + module.name());
            }
        }
        if (client.screen != null) {
            client.setScreen(null);
        }
    }

    public record PanelPosition(int x, int y) {
    }
}
