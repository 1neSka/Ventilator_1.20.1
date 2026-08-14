package team.xenobyte.modern.module.impl;

import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.ReflectionField;

public class ClickerModule extends XenoModule {
    private static final ReflectionField RIGHT_CLICK_DELAY = new ReflectionField(Minecraft.class, "rightClickDelay", "f_91011_", "aQ");

    private final ModuleSetting button = setting("Button", ModuleSetting.choice("Button", 0, "Attack", "Use", "Both")
        .describe("Which mouse action the clicker repeats."));
    private final ModuleSetting hand = setting("Hand", ModuleSetting.choice("Hand", 0, "Auto", "Main", "Off", "Both")
        .describe("Which hand receives repeated use clicks. Off is useful for modded offhand items."));
    private final ModuleSetting cps = setting("CPS", ModuleSetting.number("CPS", 8.0D, 0.25D, 200.0D, 0.25D)
        .describe("Approximate clicks per second. Values above 20 send multiple clicks per client tick."));
    private final ModuleSetting holdOnly = setting("HoldOnly", ModuleSetting.bool("HoldOnly", false)
        .describe("Only clicks while the matching vanilla mouse button is held."));
    private final ModuleSetting entities = setting("Entities", ModuleSetting.bool("Entities", true)
        .describe("Allows repeated attack clicks against entity targets."));
    private final ModuleSetting blocks = setting("Blocks", ModuleSetting.bool("Blocks", false)
        .describe("Allows repeated block-target clicks. When off, Use falls back to item-in-hand clicks."));
    private final ModuleSetting background = setting("Background", ModuleSetting.bool("Background", true)
        .describe("Keeps clicking while the Minecraft window is not focused. HoldOnly is treated as held while unfocused."));
    private final ModuleSetting inGui = setting("InGui", ModuleSetting.bool("InGui", true)
        .describe("Allows clicking while any Minecraft screen is open, including the pause screen."));

    private double clickBudget;
    private long lastLogNanos;
    private InteractionHand lastUseHand = InteractionHand.MAIN_HAND;

    public ClickerModule() {
        super("Clicker", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onDisable(Minecraft client) {
        clickBudget = 0.0D;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.options == null) {
            clickBudget = 0.0D;
            return;
        }

        boolean focused = isWindowFocused(client);
        boolean backgroundActive = background.boolValue() && !focused;
        boolean screenActive = client.screen != null;
        boolean clickThroughScreen = screenActive && inGui.boolValue();
        if (screenActive && !backgroundActive && !clickThroughScreen) {
            clickBudget = 0.0D;
            return;
        }

        String activeButton = button.choiceValue();
        boolean attack = "Attack".equals(activeButton) || "Both".equals(activeButton);
        boolean use = "Use".equals(activeButton) || "Both".equals(activeButton);
        boolean syntheticHeld = backgroundActive || clickThroughScreen;
        boolean attackReady = attack && (!holdOnly.boolValue() || syntheticHeld || client.options.keyAttack.isDown());
        boolean useReady = use && (!holdOnly.boolValue() || syntheticHeld || client.options.keyUse.isDown());

        if (!attackReady && !useReady) {
            clickBudget = 0.0D;
            return;
        }

        clickBudget = Math.min(40.0D, clickBudget + cps.doubleValue() / 20.0D);
        int maxThisTick = Math.max(1, (int)Math.ceil(cps.doubleValue() / 20.0D) + 1);
        int clicks = 0;
        while (clickBudget >= 1.0D && clicks < maxThisTick) {
            boolean didWork = false;
            if (attackReady) {
                didWork |= doAttack(client, syntheticHeld);
            }
            if (useReady) {
                didWork |= doUse(client, syntheticHeld);
            }
            if (!didWork) {
                clickBudget = Math.min(clickBudget, 1.0D);
                break;
            }
            clickBudget -= 1.0D;
            clicks++;
        }

        if (clicks > 0) {
            log(client, clicks);
        }
    }

    private boolean isWindowFocused(Minecraft client) {
        if (client.getWindow() == null) {
            return true;
        }
        return GLFW.glfwGetWindowAttrib(client.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    private boolean doAttack(Minecraft client, boolean forceRelease) {
        return pulseKey(client.options.keyAttack, forceRelease, () -> {
            if (client.hitResult instanceof EntityHitResult hit) {
                if (!entities.boolValue()) {
                    return false;
                }
                client.gameMode.attack(client.player, hit.getEntity());
                client.player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            if (client.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK && blocks.boolValue()) {
                client.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
                client.player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            client.player.swing(InteractionHand.MAIN_HAND);
            return true;
        });
    }

    private boolean doUse(Minecraft client, boolean forceRelease) {
        RIGHT_CLICK_DELAY.setInt(client, 0);
        boolean used = pulseKey(client.options.keyUse, forceRelease, () -> {
            for (InteractionHand useHand : useHands(client)) {
                if (tryUseHand(client, useHand)) {
                    lastUseHand = useHand;
                    return true;
                }
            }
            return false;
        });
        if (forceRelease) {
            releaseUsePulse(client);
        }
        return used;
    }

    private InteractionHand[] useHands(Minecraft client) {
        return switch (hand.choiceValue()) {
            case "Main" -> new InteractionHand[] {InteractionHand.MAIN_HAND};
            case "Off" -> new InteractionHand[] {InteractionHand.OFF_HAND};
            case "Both" -> new InteractionHand[] {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};
            default -> {
                ItemStack main = client.player.getMainHandItem();
                ItemStack off = client.player.getOffhandItem();
                if (main.isEmpty() && !off.isEmpty()) {
                    yield new InteractionHand[] {InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND};
                }
                yield new InteractionHand[] {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};
            }
        };
    }

    private boolean tryUseHand(Minecraft client, InteractionHand useHand) {
        if (client.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK && blocks.boolValue()) {
            InteractionResult result = client.gameMode.useItemOn(client.player, useHand, blockHit);
            if (result.consumesAction()) {
                if (result.shouldSwing()) {
                    client.player.swing(useHand);
                }
                return true;
            }
        }

        InteractionResult result = client.gameMode.useItem(client.player, useHand);
        if (result.consumesAction()) {
            if (result.shouldSwing()) {
                client.player.swing(useHand);
            }
            return true;
        }

        ItemStack stack = client.player.getItemInHand(useHand);
        if (!stack.isEmpty()) {
            client.player.swing(useHand);
            return true;
        }
        return false;
    }

    private boolean pulseKey(KeyMapping key, boolean forceRelease, BooleanSupplier action) {
        boolean wasDown = key.isDown();
        key.setDown(true);
        try {
            return action.getAsBoolean();
        } finally {
            if (forceRelease || !wasDown) {
                key.setDown(false);
            }
        }
    }

    private void releaseUsePulse(Minecraft client) {
        client.options.keyUse.setDown(false);
        RIGHT_CLICK_DELAY.setInt(client, 0);
        if (client.player != null && client.gameMode != null && client.player.isUsingItem()) {
            client.gameMode.releaseUsingItem(client.player);
        }
    }

    private void log(Minecraft client, int clicks) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("Clicker tick: button=" + button.choiceValue()
            + ", hand=" + hand.choiceValue()
            + ", lastUseHand=" + lastUseHand
            + ", cps=" + cps.displayValue()
            + ", clicksThisTick=" + clicks
            + ", budget=" + String.format(java.util.Locale.ROOT, "%.2f", clickBudget)
            + ", holdOnly=" + holdOnly.boolValue()
            + ", background=" + background.boolValue()
            + ", inGui=" + inGui.boolValue()
            + ", screen=" + (client.screen == null ? "none" : client.screen.getClass().getSimpleName())
            + ", entities=" + entities.boolValue()
            + ", blocks=" + blocks.boolValue());
    }

    @Override
    public String description() {
        return "Repeats Minecraft-local attack/use clicks, including offhand item use and multi-click pulses above 20 CPS.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Clicker button=" + button.choiceValue()
            + " hand=" + hand.choiceValue()
            + " cps=" + cps.displayValue()
            + " hold=" + holdOnly.displayValue()
            + " bg=" + background.displayValue()
            + " gui=" + inGui.displayValue();
    }
}
