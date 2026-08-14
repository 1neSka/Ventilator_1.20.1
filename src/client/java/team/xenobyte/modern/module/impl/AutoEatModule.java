package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AutoEatModule extends XenoModule {
    private final ModuleSetting hunger = setting("Hunger", ModuleSetting.number("Hunger", 14.0D, 1.0D, 19.0D, 1.0D)
        .describe("Starts eating when food level is at or below this value."));
    private final ModuleSetting combatWait = setting("SafeSec", ModuleSetting.number("SafeSec", 5.0D, 0.0D, 20.0D, 1.0D)
        .describe("Seconds to wait after attacking or taking damage before eating."));
    private final ModuleSetting allowSpecial = setting("Special", ModuleSetting.bool("Special", false)
        .describe("Allows golden apples and enchanted golden apples."));
    private final ModuleSetting noSlow = setting("NoSlow", ModuleSetting.bool("NoSlow", true)
        .describe("Keeps sprint/input alive while AutoEat is holding use."));

    private int previousSelected = -1;
    private int swappedInventorySlot = -1;
    private int swappedHotbarSlot = -1;
    private boolean autoEating;
    private float lastHealth = -1.0F;
    private long lastCombatNanos;
    private long lastLogNanos;

    public AutoEatModule() {
        super("AutoEat", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onDisable(Minecraft client) {
        restoreSelectedSlot(client);
        autoEating = false;
        previousSelected = -1;
        swappedInventorySlot = -1;
        swappedHotbarSlot = -1;
        lastHealth = -1.0F;
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        trackCombat(client);
        if (autoEating && noSlow.boolValue()) {
            applyNoSlow(client);
        }
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null) {
            return;
        }
        trackCombat(client);

        if (autoEating) {
            if (noSlow.boolValue()) {
                applyNoSlow(client);
            }
            if (!client.player.isUsingItem() || client.player.getFoodData().getFoodLevel() > hunger.intValue()) {
                restoreSelectedSlot(client);
                autoEating = false;
            }
            return;
        }

        if (client.screen != null || client.player.isUsingItem() || client.player.getFoodData().getFoodLevel() > hunger.intValue()) {
            return;
        }
        if (isCombatBlocked()) {
            return;
        }

        FoodSlot food = findFood(client);
        if (food == null) {
            return;
        }
        startEating(client, food);
    }

    private void startEating(Minecraft client, FoodSlot food) {
        int selected = client.player.getInventory().selected;
        previousSelected = selected;
        swappedInventorySlot = -1;
        swappedHotbarSlot = -1;
        int targetHotbar = food.hotbarSlot() >= 0 ? food.hotbarSlot() : selected;

        if (food.hotbarSlot() < 0) {
            int menuSlot = menuSlotFromInventoryIndex(food.inventorySlot());
            if (menuSlot < 0) {
                return;
            }
            client.gameMode.handleInventoryMouseClick(
                client.player.inventoryMenu.containerId,
                menuSlot,
                targetHotbar,
                ClickType.SWAP,
                client.player
            );
            swappedInventorySlot = food.inventorySlot();
            swappedHotbarSlot = targetHotbar;
        }

        selectHotbar(client, targetHotbar);
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        client.options.keyUse.setDown(true);
        client.player.swing(InteractionHand.MAIN_HAND);
        autoEating = true;
        log("AutoEat started: food=" + food.name()
            + ", invSlot=" + food.inventorySlot()
            + ", hotbar=" + targetHotbar
            + ", hunger=" + client.player.getFoodData().getFoodLevel()
            + ", combatBlocked=" + isCombatBlocked());
    }

    private void restoreSelectedSlot(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null || previousSelected < 0) {
            return;
        }
        if (client.gameMode != null && swappedInventorySlot >= 0 && swappedHotbarSlot >= 0) {
            int menuSlot = menuSlotFromInventoryIndex(swappedInventorySlot);
            if (menuSlot >= 0) {
                client.gameMode.handleInventoryMouseClick(
                    client.player.inventoryMenu.containerId,
                    menuSlot,
                    swappedHotbarSlot,
                    ClickType.SWAP,
                    client.player
                );
            }
            swappedInventorySlot = -1;
            swappedHotbarSlot = -1;
        }
        selectHotbar(client, previousSelected);
        client.options.keyUse.setDown(false);
        previousSelected = -1;
    }

    private void selectHotbar(Minecraft client, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            return;
        }
        client.player.getInventory().selected = hotbarSlot;
        client.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
    }

    private FoodSlot findFood(Minecraft client) {
        FoodSlot best = null;
        for (int i = 0; i < client.player.getInventory().items.size(); i++) {
            ItemStack stack = client.player.getInventory().items.get(i);
            if (!isAllowedFood(stack)) {
                continue;
            }
            FoodProperties food = stack.getFoodProperties(client.player);
            int nutrition = food == null ? 1 : food.getNutrition();
            FoodSlot candidate = new FoodSlot(i, i >= 0 && i <= 8 ? i : -1, nutrition, stack.getHoverName().getString());
            if (best == null || candidate.nutrition() > best.nutrition() || (candidate.nutrition() == best.nutrition() && candidate.hotbarSlot() >= 0 && best.hotbarSlot() < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isAllowedFood(ItemStack stack) {
        if (stack.isEmpty() || !stack.isEdible()) {
            return false;
        }
        if (!allowSpecial.boolValue() && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
            return false;
        }
        return !stack.is(Items.ROTTEN_FLESH);
    }

    private void trackCombat(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        float health = client.player.getHealth() + client.player.getAbsorptionAmount();
        if (lastHealth >= 0.0F && health < lastHealth - 0.05F) {
            lastCombatNanos = System.nanoTime();
        }
        lastHealth = health;

        if (client.options != null && client.options.keyAttack.isDown() && client.hitResult instanceof EntityHitResult) {
            lastCombatNanos = System.nanoTime();
        }
    }

    private boolean isCombatBlocked() {
        long wait = combatWait.intValue() * 1_000_000_000L;
        return wait > 0L && System.nanoTime() - lastCombatNanos < wait;
    }

    private void applyNoSlow(Minecraft client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        if (client.options.keyUp.isDown() && !client.options.keyDown.isDown() && !client.player.isShiftKeyDown()) {
            client.player.setSprinting(true);
        }
        client.player.input.up = client.options.keyUp.isDown();
        client.player.input.down = client.options.keyDown.isDown();
        client.player.input.left = client.options.keyLeft.isDown();
        client.player.input.right = client.options.keyRight.isDown();
        client.player.input.forwardImpulse = (client.options.keyUp.isDown() ? 1.0F : 0.0F) - (client.options.keyDown.isDown() ? 1.0F : 0.0F);
        client.player.input.leftImpulse = (client.options.keyLeft.isDown() ? 1.0F : 0.0F) - (client.options.keyRight.isDown() ? 1.0F : 0.0F);
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
        return "Automatically eats inventory food below the configured hunger threshold after a combat safety delay.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "AutoEat hunger=" + hunger.displayValue()
            + " safe=" + combatWait.displayValue()
            + " eating=" + autoEating
            + " blocked=" + isCombatBlocked();
    }

    private record FoodSlot(int inventorySlot, int hotbarSlot, int nutrition, String name) {
    }
}
