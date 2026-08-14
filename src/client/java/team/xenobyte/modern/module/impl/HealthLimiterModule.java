package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.gui.XenoScreen;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class HealthLimiterModule extends XenoModule {
    private static final ResourceLocation CURIOS_CHANNEL = Objects.requireNonNull(ResourceLocation.tryBuild("curios", "main"));
    private static final int OPEN_TIMEOUT_TICKS = 32;
    private static final int REEQUIP_TIMEOUT_TICKS = 80;
    private static final int REEQUIP_RETRY_TICKS = 5;
    private static final int MAX_RECOVERY_OPENS = 6;
    private static final Set<String> TARGET_ITEM_IDS = Set.of(
        "jitl:heart_container_large",
        "jitl:heart_container_medium",
        "botania:odin_ring"
    );

    private final ModuleSetting trigger = setting("Trigger", ModuleSetting.choice("Trigger", 0, "Health", "Timer", "Both")
        .describe("Health cycles when current half-hearts are above HPHalf. Timer cycles every Interval ticks."));
    private final ModuleSetting healthHalf = setting("HPHalf", ModuleSetting.number("HPHalf", 200.0D, 1.0D, 200.0D, 1.0D)
        .describe("Current health threshold in half-hearts. Minecraft health points are half-hearts."));
    private final ModuleSetting interval = setting("Interval", ModuleSetting.number("Interval", 100.0D, 5.0D, 6000.0D, 5.0D)
        .describe("Ticks between forced cycles in Timer/Both mode."));
    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 6.0D, 1.0D, 40.0D, 1.0D)
        .describe("Ticks to wait between unequip and re-equip."));
    private final ModuleSetting maxItems = setting("MaxItems", ModuleSetting.number("MaxItems", 6.0D, 1.0D, 12.0D, 1.0D)
        .describe("Maximum matching relic stacks cycled per trigger."));
    private final ModuleSetting silent = setting("Silent", ModuleSetting.bool("Silent", true)
        .describe("Hides the temporary Curios screen while keeping the server container open for packet clicks."));

    private Phase phase = Phase.IDLE;
    private final Map<String, Integer> removedById = new HashMap<>();
    private int phaseTicks;
    private int ticksSinceCycle;
    private int cycles;
    private int failedCycles;
    private int lastRemoved;
    private int lastEquipped;
    private boolean healthArmed = true;
    private boolean openedByModule;
    private boolean openingForReequip;
    private int recoveryOpens;
    private Screen hiddenScreen;
    private String activeReason = "";
    private long lastLogNanos;

    public HealthLimiterModule() {
        super("HealthLimit", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        resetState();
        ticksSinceCycle = interval.intValue();
        message("enabled " + settingsSummary(), false);
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null && client.player != null && openedByModule && isCuriosMenu(client.player.containerMenu)) {
            closeMenu(client);
        }
        message("disabled cycles=" + cycles + " failed=" + failedCycles, false);
        resetState();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null) {
            return;
        }

        if (phase != Phase.IDLE) {
            tickCycle(client);
            return;
        }

        updateHealthArmed(client);
        ticksSinceCycle++;
        String reason = triggerReason(client);
        if (reason.isEmpty()) {
            return;
        }

        if (client.player.containerMenu != client.player.inventoryMenu && !isCuriosMenu(client.player.containerMenu)) {
            log("HealthLimit trigger postponed: busy menu=" + client.player.containerMenu.getClass().getName()
                + ", reason=" + reason);
            return;
        }

        startCycle(client, reason);
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        if (client == null || client.player == null || phase != Phase.OPENING) {
            return;
        }
        if (isCuriosMenu(client.player.containerMenu)) {
            hideCuriosScreen(client);
            phase = openingForReequip ? Phase.REEQUIP : Phase.UNEQUIP;
            phaseTicks = 0;
        }
    }

    private void startCycle(Minecraft client, String reason) {
        activeReason = reason;
        removedById.clear();
        lastRemoved = 0;
        lastEquipped = 0;
        phaseTicks = 0;
        openedByModule = false;
        openingForReequip = false;
        recoveryOpens = 0;
        hiddenScreen = client.screen;

        if (isCuriosMenu(client.player.containerMenu)) {
            phase = Phase.UNEQUIP;
            message("cycle start: already in Curios reason=" + reason, false);
            return;
        }

        sendOpenCurios(client);
        openedByModule = true;
        phase = Phase.OPENING;
        message("cycle start: open Curios reason=" + reason
            + " hp=" + format(client.player.getHealth()) + "/" + format(client.player.getMaxHealth()), false);
    }

    private void tickCycle(Minecraft client) {
        phaseTicks++;
        if (phase == Phase.OPENING) {
            if (isCuriosMenu(client.player.containerMenu)) {
                hideCuriosScreen(client);
                phase = openingForReequip ? Phase.REEQUIP : Phase.UNEQUIP;
                phaseTicks = 0;
                return;
            }
            if (phaseTicks > OPEN_TIMEOUT_TICKS) {
                fail(client, "open-timeout");
            }
            return;
        }

        if (!isCuriosMenu(client.player.containerMenu)) {
            if (hasPendingReequip()) {
                recoverCuriosMenu(client, "menu-lost:" + client.player.containerMenu.getClass().getSimpleName());
                return;
            }
            fail(client, "curios-menu-lost");
            return;
        }

        if (phase == Phase.UNEQUIP) {
            unequipTargets(client);
            if (lastRemoved <= 0) {
                if (prepareDetachedReequip(client)) {
                    phase = Phase.REEQUIP;
                    phaseTicks = 0;
                    return;
                }
                fail(client, "no-targets");
                return;
            }
            phase = Phase.WAIT_REEQUIP;
            phaseTicks = 0;
            return;
        }

        if (phase == Phase.WAIT_REEQUIP) {
            if (phaseTicks >= delay.intValue()) {
                phase = Phase.REEQUIP;
                phaseTicks = 0;
            }
            return;
        }

        if (phase == Phase.REEQUIP) {
            reEquipTargets(client);
            if (isReequipComplete(client.player.containerMenu, client)) {
                finish(client, "done");
            } else if (phaseTicks > REEQUIP_TIMEOUT_TICKS) {
                recoverCuriosMenu(client, "reequip-timeout");
            }
        }
    }

    private void unequipTargets(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        List<Integer> targetSlots = equippedTargetSlots(menu, client);
        int limit = Math.max(1, maxItems.intValue());
        int emptySlots = emptyPlayerSlots(menu, client);
        if (emptySlots <= 0) {
            return;
        }

        int clicks = Math.min(Math.min(targetSlots.size(), limit), emptySlots);
        for (int i = 0; i < clicks; i++) {
            int slotIndex = targetSlots.get(i);
            ItemStack stack = menu.slots.get(slotIndex).getItem();
            String id = itemId(stack);
            removedById.merge(id, 1, Integer::sum);
            client.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.QUICK_MOVE, client.player);
        }
        lastRemoved = clicks;
        lastEquipped = 0;
        openingForReequip = true;
        log("HealthLimit unequip: slots=" + targetSlots
            + ", clicked=" + clicks
            + ", empty=" + emptySlots
            + ", ids=" + removedById);
    }

    private void reEquipTargets(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        Map<String, Integer> remaining = missingEquippedCounts(menu, client);
        lastEquipped = verifiedEquippedCount(menu, client);
        if (remaining.isEmpty()) {
            return;
        }
        if (phaseTicks > 1 && phaseTicks % REEQUIP_RETRY_TICKS != 0) {
            return;
        }

        int clicked = 0;

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isPlayerSlot(client, slot) || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String id = itemId(stack);
            int need = remaining.getOrDefault(id, 0);
            if (need <= 0 || !isTargetRelic(stack)) {
                continue;
            }

            client.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
            remaining.put(id, need - 1);
            clicked++;
            if (sumPositive(remaining) <= 0) {
                break;
            }
        }

        log("HealthLimit re-equip: clicked=" + clicked
            + ", removed=" + lastRemoved
            + ", verified=" + lastEquipped
            + ", remaining=" + remaining);
    }

    private boolean prepareDetachedReequip(Minecraft client) {
        Map<String, Integer> detached = playerTargetCounts(client.player.containerMenu, client);
        int total = Math.min(sumPositive(detached), Math.max(1, maxItems.intValue()));
        if (total <= 0) {
            return false;
        }

        removedById.clear();
        for (Map.Entry<String, Integer> entry : detached.entrySet()) {
            int current = sumPositive(removedById);
            if (current >= total) {
                break;
            }
            removedById.put(entry.getKey(), Math.min(entry.getValue(), total - current));
        }
        lastRemoved = sumPositive(removedById);
        lastEquipped = verifiedEquippedCount(client.player.containerMenu, client);
        openingForReequip = true;
        log("HealthLimit detached re-equip prepared: ids=" + removedById
            + ", removed=" + lastRemoved
            + ", verified=" + lastEquipped);
        return lastRemoved > 0;
    }

    private List<Integer> equippedTargetSlots(AbstractContainerMenu menu, Minecraft client) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (isPlayerSlot(client, slot) || !slot.hasItem()) {
                continue;
            }
            if (isTargetRelic(slot.getItem())) {
                slots.add(i);
            }
        }
        return slots;
    }

    private int emptyPlayerSlots(AbstractContainerMenu menu, Minecraft client) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (isPlayerSlot(client, slot) && !slot.hasItem()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Integer> playerTargetCounts(AbstractContainerMenu menu, Minecraft client) {
        Map<String, Integer> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (!isPlayerSlot(client, slot) || !slot.hasItem() || !isTargetRelic(slot.getItem())) {
                continue;
            }
            counts.merge(itemId(slot.getItem()), 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> equippedTargetCounts(AbstractContainerMenu menu, Minecraft client) {
        Map<String, Integer> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (isPlayerSlot(client, slot) || !slot.hasItem() || !isTargetRelic(slot.getItem())) {
                continue;
            }
            counts.merge(itemId(slot.getItem()), 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> missingEquippedCounts(AbstractContainerMenu menu, Minecraft client) {
        Map<String, Integer> equipped = equippedTargetCounts(menu, client);
        Map<String, Integer> missing = new HashMap<>();
        for (Map.Entry<String, Integer> entry : removedById.entrySet()) {
            int need = entry.getValue() - equipped.getOrDefault(entry.getKey(), 0);
            if (need > 0) {
                missing.put(entry.getKey(), need);
            }
        }
        return missing;
    }

    private int verifiedEquippedCount(AbstractContainerMenu menu, Minecraft client) {
        Map<String, Integer> equipped = equippedTargetCounts(menu, client);
        int verified = 0;
        for (Map.Entry<String, Integer> entry : removedById.entrySet()) {
            verified += Math.min(entry.getValue(), equipped.getOrDefault(entry.getKey(), 0));
        }
        return verified;
    }

    private boolean isReequipComplete(AbstractContainerMenu menu, Minecraft client) {
        if (lastRemoved <= 0 || removedById.isEmpty()) {
            return false;
        }
        lastEquipped = verifiedEquippedCount(menu, client);
        return lastEquipped >= lastRemoved;
    }

    private boolean hasPendingReequip() {
        return lastRemoved > 0 && !removedById.isEmpty();
    }

    private int sumPositive(Map<String, Integer> counts) {
        int sum = 0;
        for (int value : counts.values()) {
            if (value > 0) {
                sum += value;
            }
        }
        return sum;
    }

    private boolean isTargetRelic(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = itemId(stack);
        if (TARGET_ITEM_IDS.contains(id)) {
            return true;
        }

        String name = normalize(stack.getHoverName().getString());
        return (id.contains("odin") || name.contains("один") || name.contains("odin"))
            || ((id.contains("heart") || name.contains("серд") || name.contains("heart"))
                && (id.contains("container") || id.contains("canister") || name.contains("контейнер") || name.contains("container")));
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString().toLowerCase(Locale.ROOT);
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private boolean isPlayerSlot(Minecraft client, Slot slot) {
        return slot.container == client.player.getInventory();
    }

    private void sendOpenCurios(Minecraft client) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByte(0);
        buffer.writeByte(0);
        client.getConnection().send(new ServerboundCustomPayloadPacket(CURIOS_CHANNEL, buffer));
    }

    private void recoverCuriosMenu(Minecraft client, String reason) {
        if (!hasPendingReequip()) {
            fail(client, reason);
            return;
        }
        if (recoveryOpens >= MAX_RECOVERY_OPENS) {
            fail(client, "recovery-limit:" + reason);
            return;
        }

        recoveryOpens++;
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        if (hiddenScreen == null || !(hiddenScreen instanceof XenoScreen)) {
            hiddenScreen = client.screen instanceof XenoScreen ? client.screen : null;
        }
        openingForReequip = true;
        openedByModule = true;
        sendOpenCurios(client);
        phase = Phase.OPENING;
        phaseTicks = 0;
        log("HealthLimit recovery open: reason=" + reason
            + ", attempt=" + recoveryOpens
            + ", removed=" + lastRemoved
            + ", verified=" + lastEquipped
            + ", ids=" + removedById);
    }

    private void hideCuriosScreen(Minecraft client) {
        if (!silent.boolValue()) {
            return;
        }
        Screen current = client.screen;
        if (current == hiddenScreen) {
            return;
        }
        if (hiddenScreen instanceof XenoScreen) {
            setScreenDirect(client, hiddenScreen);
        } else {
            setScreenDirect(client, null);
        }
        log("HealthLimit hid Curios screen: current=" + screenName(current)
            + ", restore=" + screenName(hiddenScreen));
    }

    private boolean isCuriosMenu(AbstractContainerMenu menu) {
        return menu != null && normalize(menu.getClass().getName()).contains("curios");
    }

    private void closeMenu(Minecraft client) {
        client.player.closeContainer();
        if (silent.boolValue() && hiddenScreen instanceof XenoScreen) {
            setScreenDirect(client, hiddenScreen);
        } else {
            client.setScreen(null);
        }
        hiddenScreen = null;
    }

    private void finish(Minecraft client, String reason) {
        cycles++;
        if (usesHealthTrigger() && activeReason.contains("health")) {
            healthArmed = false;
        }
        ticksSinceCycle = 0;
        message(reason + " removed=" + lastRemoved
            + " equipped=" + lastEquipped
            + " hp=" + format(client.player.getHealth()) + "/" + format(client.player.getMaxHealth()), true);
        if (openedByModule) {
            closeMenu(client);
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        openedByModule = false;
        openingForReequip = false;
        recoveryOpens = 0;
        hiddenScreen = null;
        activeReason = "";
        removedById.clear();
    }

    private void fail(Minecraft client, String reason) {
        failedCycles++;
        ticksSinceCycle = 0;
        message("failed " + reason
            + " removed=" + lastRemoved
            + " equipped=" + lastEquipped
            + " menu=" + (client.player.containerMenu == null ? "none" : client.player.containerMenu.getClass().getSimpleName()), true);
        if (openedByModule && client.player.containerMenu != client.player.inventoryMenu) {
            closeMenu(client);
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        openedByModule = false;
        openingForReequip = false;
        recoveryOpens = 0;
        hiddenScreen = null;
        activeReason = "";
        removedById.clear();
    }

    private void updateHealthArmed(Minecraft client) {
        if (!usesHealthTrigger()) {
            healthArmed = true;
            return;
        }
        if (client.player.getHealth() <= healthHalf.floatValue()) {
            healthArmed = true;
        }
    }

    private String triggerReason(Minecraft client) {
        String activeTrigger = trigger.choiceValue();
        boolean healthTrigger = ("Health".equals(activeTrigger) || "Both".equals(activeTrigger))
            && ticksSinceCycle >= interval.intValue()
            && client.player.getHealth() > healthHalf.floatValue();
        if (healthTrigger) {
            return "health>" + healthHalf.displayValue();
        }

        boolean timerTrigger = ("Timer".equals(activeTrigger) || "Both".equals(activeTrigger))
            && ticksSinceCycle >= interval.intValue();
        if (timerTrigger) {
            return "timer=" + interval.displayValue();
        }
        return "";
    }

    private boolean usesHealthTrigger() {
        String activeTrigger = trigger.choiceValue();
        return "Health".equals(activeTrigger) || "Both".equals(activeTrigger);
    }

    private String format(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String screenName(Screen screen) {
        return screen == null ? "none" : screen.getClass().getSimpleName();
    }

    private void setScreenDirect(Minecraft client, Screen screen) {
        client.screen = screen;
        if (screen != null) {
            client.mouseHandler.releaseMouse();
        } else if (client.level != null) {
            client.mouseHandler.grabMouse();
        }
    }

    private void message(String text, boolean ui) {
        BootstrapLog.info("HealthLimit " + text);
        if (ui) {
            ModuleMessageLog.push("HealthLimit", text);
        }
    }

    private void log(String text) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 500_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(text);
    }

    private void resetState() {
        phase = Phase.IDLE;
        removedById.clear();
        phaseTicks = 0;
        ticksSinceCycle = 0;
        lastRemoved = 0;
        lastEquipped = 0;
        healthArmed = true;
        openedByModule = false;
        openingForReequip = false;
        recoveryOpens = 0;
        hiddenScreen = null;
        activeReason = "";
    }

    @Override
    public String description() {
        return "Cycles only whitelisted HP Curios relics to clamp current health while preserving the equipped buffs.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "HealthLimit phase=" + phase
            + " cycles=" + cycles
            + " failed=" + failedCycles
            + " trigger=" + trigger.choiceValue()
            + " hpHalf=" + healthHalf.displayValue()
            + " interval=" + interval.displayValue()
            + " last=" + lastRemoved + "/" + lastEquipped;
    }

    private enum Phase {
        IDLE,
        OPENING,
        UNEQUIP,
        WAIT_REEQUIP,
        REEQUIP
    }
}
