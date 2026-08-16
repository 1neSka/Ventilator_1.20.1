package team.xenobyte.modern.module.impl;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;

/** Reads recipes that mods expose only through JEI, without making JEI a build-time dependency. */
final class JeiRecipeBridge {
    private static final int MAX_ERRORS = 64;

    private JeiRecipeBridge() {
    }

    static ScanResult scan(Set<String> managedRecipeIds) {
        List<RecipeData> recipes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int categories = 0;
        int inspected = 0;
        int skippedVanilla = 0;
        int partial = 0;
        int nonItemSlots = 0;

        try {
            Object runtime = invokeStaticNoArgs("mezz.jei.common.Internal", "getJeiRuntime");
            if (runtime == null) {
                return new ScanResult(List.of(), 0, 0, 0, 0, 0, List.of("JEI runtime is not available"));
            }
            Object recipeManager = invoke(runtime, "getRecipeManager");
            Object focusFactory = invoke(invoke(runtime, "getJeiHelpers"), "getFocusFactory");
            Object emptyFocus = invoke(focusFactory, "getEmptyFocusGroup");
            Object categoriesLookup = invoke(recipeManager, "createRecipeCategoryLookup");
            categoriesLookup = invoke(categoriesLookup, "includeHidden");
            List<?> categoryList = collectStream(invoke(categoriesLookup, "get"));
            categories = categoryList.size();

            for (Object category : categoryList) {
                Object recipeType;
                String categoryUid;
                String station;
                try {
                    recipeType = invoke(category, "getRecipeType");
                    categoryUid = String.valueOf(invoke(recipeType, "getUid"));
                    station = componentText(invoke(category, "getTitle"), categoryUid);
                } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                    addError(errors, "category " + category.getClass().getName(), error);
                    continue;
                }

                List<?> categoryRecipes;
                try {
                    Object lookup = invoke(recipeManager, "createRecipeLookup", recipeType);
                    lookup = invoke(lookup, "includeHidden");
                    categoryRecipes = collectStream(invoke(lookup, "get"));
                } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                    addError(errors, "lookup " + categoryUid, error);
                    continue;
                }

                int recipeIndex = 0;
                for (Object recipe : categoryRecipes) {
                    recipeIndex++;
                    inspected++;
                    if (recipe instanceof Recipe<?> minecraftRecipe
                        && managedRecipeIds.contains(recipeId(minecraftRecipe))) {
                        skippedVanilla++;
                        continue;
                    }
                    try {
                        LayoutCapture capture = new LayoutCapture();
                        Object layout = capture.layoutProxy();
                        invoke(category, "setRecipe", layout, recipe, emptyFocus);
                        String recipeId = registryName(category, recipe);
                        if (recipeId.isBlank()) {
                            recipeId = "jei:" + sanitize(categoryUid) + "/" + recipeIndex;
                        }
                        List<RecipeData> captured = capture.finish(recipeId, station, categoryUid);
                        recipes.addAll(captured);
                        nonItemSlots += capture.nonItemSlots;
                        for (RecipeData data : captured) {
                            if (data.partial()) {
                                partial++;
                            }
                        }
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                        addError(errors, categoryUid + " recipe " + recipeIndex, error);
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            return new ScanResult(List.of(), 0, 0, 0, 0, 0, List.of("JEI is not installed"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            addError(errors, "JEI bridge initialization", error);
        }

        return new ScanResult(List.copyOf(recipes), categories, inspected, skippedVanilla,
            partial, nonItemSlots, List.copyOf(errors));
    }

    private static String recipeId(Recipe<?> recipe) {
        try {
            ResourceLocation id = recipe.getId();
            return id == null ? "" : id.toString();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static String registryName(Object category, Object recipe) {
        try {
            Object name = invoke(category, "getRegistryName", recipe);
            return name == null ? "" : String.valueOf(name);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return "";
        }
    }

    private static List<?> collectStream(Object value) {
        if (!(value instanceof Stream<?> stream)) {
            return List.of();
        }
        try (stream) {
            return stream.toList();
        }
    }

    private static String componentText(Object value, String fallback) {
        if (value instanceof Component component) {
            String text = component.getString();
            return text.isBlank() ? fallback : text;
        }
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static Object invokeStaticNoArgs(String className, String methodName)
        throws ReflectiveOperationException {
        Class<?> target = loadClass(className);
        Method method = target.getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Object invoke(Object target, String methodName, Object... arguments)
        throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method selected = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != arguments.length) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!compatible(parameters[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + methodName
                + "/" + arguments.length);
        }
        selected.setAccessible(true);
        try {
            return selected.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw error;
        }
    }

    private static boolean compatible(Class<?> parameter, Object argument) {
        if (argument == null) {
            return !parameter.isPrimitive();
        }
        if (parameter.isInstance(argument)) {
            return true;
        }
        if (!parameter.isPrimitive()) {
            return false;
        }
        return (parameter == boolean.class && argument instanceof Boolean)
            || (parameter == int.class && argument instanceof Integer)
            || (parameter == long.class && argument instanceof Long)
            || (parameter == double.class && argument instanceof Double)
            || (parameter == float.class && argument instanceof Float)
            || (parameter == short.class && argument instanceof Short)
            || (parameter == byte.class && argument instanceof Byte)
            || (parameter == char.class && argument instanceof Character);
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            Minecraft.class.getClassLoader(),
            JeiRecipeBridge.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Try the next loader.
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte)0;
        if (type == short.class) return (short)0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        return 0.0D;
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    private static void addError(List<String> errors, String context, Throwable error) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().replace('\r', ' ').replace('\n', ' ');
        errors.add(context + " -> " + cause.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message));
    }

    record RecipeData(String id, String station, String type, ItemStack output,
                      List<List<ItemStack>> inputs, boolean partial) {
    }

    record ScanResult(List<RecipeData> recipes, int categories, int inspectedRecipes,
                      int skippedVanillaRecipes, int partialRecipes, int nonItemSlots,
                      List<String> errors) {
    }

    private static final class LayoutCapture {
        private final List<SlotCapture> slots = new ArrayList<>();
        private final Class<?> layoutClass;
        private final Class<?> slotClass;
        private int nonItemSlots;

        private LayoutCapture() throws ClassNotFoundException {
            this.layoutClass = loadClass("mezz.jei.api.gui.builder.IRecipeLayoutBuilder");
            this.slotClass = loadClass("mezz.jei.api.gui.builder.IRecipeSlotBuilder");
        }

        private Object layoutProxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("toString")) return "XenobyteJeiLayoutCapture";
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == (args == null ? null : args[0]);

                String role = null;
                if (name.equals("addInputSlot")) {
                    role = "INPUT";
                } else if (name.equals("addOutputSlot")) {
                    role = "OUTPUT";
                } else if ((name.equals("addSlot") || name.equals("addSlotToWidget")
                    || name.equals("addInvisibleIngredients")) && args != null && args.length > 0) {
                    role = String.valueOf(args[0]).toUpperCase(Locale.ROOT);
                }
                if (role != null) {
                    SlotCapture slot = new SlotCapture(role);
                    slots.add(slot);
                    return slot.proxy(slotClass);
                }
                return defaultValue(method.getReturnType());
            };
            return Proxy.newProxyInstance(layoutClass.getClassLoader(), new Class<?>[] {layoutClass}, handler);
        }

        private List<RecipeData> finish(String id, String station, String type) {
            List<List<ItemStack>> inputs = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();
            boolean partial = false;
            for (SlotCapture slot : slots) {
                List<ItemStack> items = slot.uniqueItems();
                if (items.isEmpty()) {
                    if (slot.sawIngredient && (slot.role.equals("INPUT") || slot.role.equals("OUTPUT"))) {
                        nonItemSlots++;
                        partial = true;
                    }
                    continue;
                }
                if (slot.role.equals("OUTPUT")) {
                    outputs.addAll(items);
                } else if (slot.role.equals("INPUT")) {
                    inputs.add(items);
                }
            }
            if (outputs.isEmpty()) {
                return List.of();
            }
            List<RecipeData> result = new ArrayList<>();
            Map<Item, ItemStack> uniqueOutputs = new LinkedHashMap<>();
            for (ItemStack output : outputs) {
                uniqueOutputs.putIfAbsent(output.getItem(), output);
            }
            for (ItemStack output : uniqueOutputs.values()) {
                result.add(new RecipeData(id, station, type, output.copy(), List.copyOf(inputs), partial));
            }
            return result;
        }
    }

    private static final class SlotCapture implements InvocationHandler {
        private final String role;
        private final List<ItemStack> items = new ArrayList<>();
        private boolean sawIngredient;
        private Object proxy;

        private SlotCapture(String role) {
            this.role = role;
        }

        private Object proxy(Class<?> slotClass) {
            this.proxy = Proxy.newProxyInstance(slotClass.getClassLoader(), new Class<?>[] {slotClass}, this);
            return this.proxy;
        }

        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("toString")) return "XenobyteJeiSlotCapture[" + role + "]";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
            if (name.startsWith("add") && args != null) {
                sawIngredient = true;
                IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
                for (Object argument : args) {
                    capture(argument, seen, 0);
                }
            }
            if (method.getReturnType().isInstance(proxy)) {
                return proxy;
            }
            return defaultValue(method.getReturnType());
        }

        private void capture(Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
            if (value == null || depth > 5 || seen.put(value, Boolean.TRUE) != null) {
                return;
            }
            if (value instanceof ItemStack stack) {
                if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                    items.add(stack.copy());
                }
                return;
            }
            if (value instanceof Item item) {
                if (item != Items.AIR) {
                    items.add(new ItemStack(item));
                }
                return;
            }
            if (value instanceof ItemLike itemLike) {
                Item item = itemLike.asItem();
                if (item != Items.AIR) {
                    items.add(new ItemStack(item));
                }
                return;
            }
            if (value instanceof Ingredient ingredient) {
                for (ItemStack stack : ingredient.getItems()) {
                    capture(stack, seen, depth + 1);
                }
                return;
            }
            if (value instanceof Optional<?> optional) {
                optional.ifPresent(nested -> capture(nested, seen, depth + 1));
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object nested : iterable) {
                    capture(nested, seen, depth + 1);
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    capture(Array.get(value, i), seen, depth + 1);
                }
                return;
            }
            try {
                Object ingredient = JeiRecipeBridge.invoke(value, "getIngredient");
                if (ingredient != value) {
                    capture(ingredient, seen, depth + 1);
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                // Non-item JEI ingredients are recorded as partial slots by the caller.
            }
        }

        private List<ItemStack> uniqueItems() {
            Map<ResourceLocation, ItemStack> unique = new LinkedHashMap<>();
            for (ItemStack stack : items) {
                ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                unique.putIfAbsent(id, stack);
            }
            return List.copyOf(unique.values());
        }
    }
}
