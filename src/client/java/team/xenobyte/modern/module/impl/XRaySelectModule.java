package team.xenobyte.modern.module.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.XRayColorScreen;
import team.xenobyte.modern.gui.XRayManagerScreen;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.impl.XRayTargetRegistry.RenderMode;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class XRaySelectModule extends XenoModule {
    private final ModuleSetting action = setting("Action", ModuleSetting.choice("Action", 0, "Toggle", "Add", "Remove", "Clean Custom")
        .describe("How the selected block should be applied to the custom XRay target list."));
    private final ModuleSetting color = setting("Color", ModuleSetting.choice("Color", 0, "Cyan", "Red", "Green", "Gold", "Blue", "Orange", "White", "Gray", "Purple", "Pink")
        .describe("Color used when adding or updating the selected block."));
    private final ModuleSetting render = setting("Render", ModuleSetting.choice("Render", 0, "Outline", "Both", "Filled")
        .describe("Render style used when adding or updating the selected block."));
    private final ModuleSetting colorPopup = setting("ColorPopup", ModuleSetting.bool("ColorPopup", true)
        .describe("Opens the RGB and render-style editor after adding a block."));
    private final ModuleSetting manage = setting("ManageXRay", ModuleSetting.action("ManageXRay", this::openManager)
        .describe("Opens the persistent custom XRay target list and RGB editor."));
    private long lastJeiFailureLogNanos;

    public XRaySelectModule() {
        super("XRaySelect", Category.WORLD, ModuleMode.SINGLE);
    }

    @Override
    public void onPerform(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        String activeAction = action.choiceValue();
        if ("Clean Custom".equals(activeAction)) {
            int removed = XRayTargetRegistry.clear();
            message(client, "XRaySelect: cleaned custom list (" + removed + ")");
            BootstrapLog.info("XRaySelect clean custom: removed=" + removed);
            return;
        }

        Block block = selectedBlock(client);
        if (block == null || block == Blocks.AIR) {
            message(client, "XRaySelect: no block selected");
            BootstrapLog.info("XRaySelect failed: no block selected");
            return;
        }

        boolean existed = XRayTargetRegistry.contains(block);
        if ("Remove".equals(activeAction) || ("Toggle".equals(activeAction) && existed)) {
            boolean removed = XRayTargetRegistry.remove(block);
            message(client, "XRaySelect: " + (removed ? "removed " : "not found ") + id(block));
            BootstrapLog.info("XRaySelect removed: block=" + id(block) + ", existed=" + existed);
            return;
        }

        int selectedColor = XRayTargetRegistry.colorForChoice(color.choiceValue());
        RenderMode selectedMode = RenderMode.from(render.choiceValue());
        XRayTargetRegistry.put(block, selectedColor, selectedMode);
        message(client, "XRaySelect: added " + id(block) + " " + color.choiceValue() + " " + selectedMode.name());
        BootstrapLog.info("XRaySelect added: block=" + id(block)
            + ", existed=" + existed
            + ", color=" + color.choiceValue()
            + ", render=" + selectedMode.name());
        if (colorPopup.boolValue()) {
            openColorEditor(client, block);
        }
    }

    @Override
    public boolean allowBindInGui() {
        return true;
    }

    private Block selectedBlock(Minecraft client) {
        Block fromScreen = blockFromScreen(client);
        if (fromScreen != null) {
            return fromScreen;
        }

        if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            Block block = client.level.getBlockState(pos).getBlock();
            if (block != Blocks.AIR) {
                return block;
            }
        }

        Block fromHand = blockFromStack(client.player.getMainHandItem());
        if (fromHand != null) {
            return fromHand;
        }
        return blockFromStack(client.player.getOffhandItem());
    }

    private Block blockFromScreen(Minecraft client) {
        Block fromJei = blockFromJeiHover();
        if (fromJei != null) {
            return fromJei;
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            ItemStack hovered = hoveredStack(screen);
            Block block = blockFromStack(hovered);
            if (block != null) {
                return block;
            }
        }

        ItemStack carried = client.player.containerMenu.getCarried();
        return blockFromStack(carried);
    }

    private Block blockFromJeiHover() {
        try {
            Object runtime = invokeStaticNoArgs("mezz.jei.common.Internal", "getJeiRuntime");
            if (runtime == null) {
                return null;
            }

            Block block = blockFromOverlay(invokeNoArgs(runtime, "getIngredientListOverlay"), "ingredient-list");
            if (block != null) {
                return block;
            }
            return blockFromOverlay(invokeNoArgs(runtime, "getBookmarkOverlay"), "bookmark");
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            logJeiFailure(error);
            return null;
        }
    }

    private Block blockFromOverlay(Object overlay, String source) throws ReflectiveOperationException {
        if (overlay == null) {
            return null;
        }

        Object optional = invokeNoArgs(overlay, "getIngredientUnderMouse");
        ItemStack stack = stackFromOptional(optional);
        Block block = blockFromStack(stack);
        if (block != null) {
            BootstrapLog.info("XRaySelect JEI hovered block: source=" + source
                + ", block=" + id(block)
                + ", stack=" + stack.getHoverName().getString());
        }
        return block;
    }

    private ItemStack stackFromOptional(Object optional) throws ReflectiveOperationException {
        if (!(optional instanceof Optional<?> typedOptional) || typedOptional.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stackFromIngredient(typedOptional.get());
    }

    private ItemStack stackFromIngredient(Object ingredient) throws ReflectiveOperationException {
        if (ingredient instanceof ItemStack stack) {
            return stack;
        }

        Object itemStackOptional = invokeNoArgs(ingredient, "getItemStack");
        if (itemStackOptional instanceof Optional<?> optional && optional.orElse(null) instanceof ItemStack stack) {
            return stack;
        }

        Object rawIngredient = invokeNoArgs(ingredient, "getIngredient");
        if (rawIngredient instanceof ItemStack stack) {
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private Object invokeStaticNoArgs(String className, String methodName) throws ReflectiveOperationException {
        Class<?> target = loadClass(className);
        Method method = target.getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = target.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            Minecraft.class.getClassLoader(),
            XRaySelectModule.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Try the next loader; injected jars and Forge mods can have different parents.
            }
        }
        return Class.forName(name);
    }

    private void logJeiFailure(Throwable error) {
        long now = System.nanoTime();
        if (now - lastJeiFailureLogNanos < 5_000_000_000L) {
            return;
        }
        lastJeiFailureLogNanos = now;
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause()
            : error;
        BootstrapLog.info("XRaySelect JEI hover unavailable: " + cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
    }

    private ItemStack hoveredStack(AbstractContainerScreen<?> screen) {
        try {
            Method method = AbstractContainerScreen.class.getDeclaredMethod("getSlotUnderMouse");
            method.setAccessible(true);
            Object slot = method.invoke(screen);
            if (slot instanceof Slot hovered) {
                return hovered.getItem();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JEI-like overlays are optional; fall back to carried/hand/crosshair selection.
        }
        return ItemStack.EMPTY;
    }

    private Block blockFromStack(ItemStack stack) {
        if (stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }

    private String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private void openManager() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> client.setScreen(new XRayManagerScreen(client.screen)));
    }

    private void openColorEditor(Minecraft client, Block block) {
        if (client == null || block == null) {
            return;
        }
        client.execute(() -> client.setScreen(new XRayColorScreen(block, client.screen)));
    }

    private void message(Minecraft client, String message) {
        client.player.displayClientMessage(Component.literal(message), true);
    }

    @Override
    public String description() {
        return "Adds or removes the looked-at, held, carried, or hovered block from the custom XRay target list.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "XRaySelect customTargets=" + XRayTargetRegistry.size();
    }
}
