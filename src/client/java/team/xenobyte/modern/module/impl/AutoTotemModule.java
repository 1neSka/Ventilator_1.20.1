package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AutoTotemModule extends XenoModule {
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 4.0D, 0.0D, 40.0D, 1.0D)
        .describe("Ticks between offhand refill attempts."));
    private final ModuleSetting allowGui = setting("InGui", ModuleSetting.bool("InGui", false)
        .describe("Allows inventory clicks while another screen is open."));

    private int cooldownTicks;
    private int moved;
    private long lastLogNanos;

    public AutoTotemModule() {
        super("AutoTotem", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null) {
            return;
        }
        if (!allowGui.boolValue() && client.screen != null) {
            return;
        }
        if (client.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            cooldownTicks = 0;
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = delay.intValue();

        int inventorySlot = findTotemSlot(client);
        int menuSlot = menuSlotFromInventoryIndex(inventorySlot);
        if (menuSlot < 0) {
            return;
        }

        client.gameMode.handleInventoryMouseClick(
            client.player.inventoryMenu.containerId,
            menuSlot,
            OFFHAND_SWAP_BUTTON,
            ClickType.SWAP,
            client.player
        );
        moved++;
        log("AutoTotem moved totem: invSlot=" + inventorySlot + ", menuSlot=" + menuSlot + ", moved=" + moved);
    }

    private int findTotemSlot(Minecraft client) {
        for (int i = 0; i < client.player.getInventory().items.size(); i++) {
            ItemStack stack = client.player.getInventory().items.get(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private int menuSlotFromInventoryIndex(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot <= 8) {
            return 36 + inventorySlot;
        }
        if (inventorySlot >= 9 && inventorySlot <= 35) {
            return inventorySlot;
        }
        return -1;
    }

    private void log(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(message);
    }

    @Override
    public String description() {
        return "Moves a totem from inventory into offhand when the offhand is empty or no longer contains a totem.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "AutoTotem moved=" + moved + " delay=" + delay.displayValue();
    }
}
