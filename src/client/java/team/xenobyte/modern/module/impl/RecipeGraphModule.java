package team.xenobyte.modern.module.impl;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class RecipeGraphModule extends XenoModule {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_GRAPH_NODES = 8_000;
    private static final int MAX_ALTERNATIVES_PER_INGREDIENT = 12;
    private static final int MAX_RECIPE_ALTERNATIVES = 24;

    private static final Set<String> COMMON_RAW_NAMES = Set.of(
        "redstone", "glowstone_dust", "diamond", "emerald", "coal", "charcoal",
        "lapis_lazuli", "quartz", "amethyst_shard", "flint", "clay_ball",
        "slime_ball", "ender_pearl", "nether_star", "echo_shard"
    );

    private final ModuleSetting itemId = setting("ItemId", ModuleSetting.number("ItemId", 0.0D, 0.0D, 250000.0D, 1.0D)
        .describe("Numeric registry id shown by AdvancedTooltip."));
    private final ModuleSetting amount = setting("Amount", ModuleSetting.number("Amount", 1.0D, 1.0D, 1_000_000.0D, 1.0D)
        .describe("Requested output amount."));
    private final ModuleSetting leafMode = setting("LeafMode", ModuleSetting.choice("LeafMode", 0, "Common", "NoRecipe")
        .describe("Common stops at ingots, gems, dusts and ores. NoRecipe expands until a cycle, depth limit or missing recipe."));
    private final ModuleSetting maxDepth = setting("MaxDepth", ModuleSetting.number("MaxDepth", 16.0D, 1.0D, 48.0D, 1.0D)
        .describe("Maximum recursive recipe depth."));

    private Path lastExport;
    private String lastResult = "never";

    public RecipeGraphModule() {
        super("RecipeGraph", Category.MISC, ModuleMode.SINGLE);
    }

    @Override
    public void onPerform(Minecraft client) {
        if (client == null || client.level == null) {
            result("no world/recipes loaded", null);
            return;
        }

        Item target = BuiltInRegistries.ITEM.byId(itemId.intValue());
        if (target == null || target == Items.AIR) {
            result("invalid ItemId " + itemId.displayValue(), null);
            return;
        }

        try {
            ExportContext context = createContext(client);
            long requested = Math.max(1L, Math.round(amount.doubleValue()));
            ResourceLocation targetId = itemKey(target);

            StringBuilder output = new StringBuilder(64_000);
            appendHeader(output, client, context, target, requested);
            output.append(System.lineSeparator()).append("RECIPE TREE").append(System.lineSeparator());
            output.append("===========").append(System.lineSeparator());
            expand(context, output, target, requested, 0, new LinkedHashSet<>());
            appendTotals(output, context);
            appendAlternatives(output, context);
            appendDiagnostics(output, context);

            Path directory = resolveExportDirectory();
            Files.createDirectories(directory);
            String fileName = "xenobyte-recipe-graph-"
                + sanitize(targetId == null ? "unknown" : targetId.toString())
                + "-" + LocalDateTime.now().format(FILE_TIME) + ".txt";
            Path export = directory.resolve(fileName);
            Files.writeString(export, output.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            result("saved " + fileName, export);
            BootstrapLog.info("RecipeGraph exported: file=" + export
                + ", target=" + targetId
                + ", amount=" + requested
                + ", recipes=" + context.recipeCount
                + ", nodes=" + context.nodes
                + ", raw=" + context.rawTotals.size()
                + ", alternatives=" + context.alternativeGroups);
        } catch (IOException | RuntimeException error) {
            lastResult = "failed: " + error.getClass().getSimpleName();
            ModuleMessageLog.push("RecipeGraph", lastResult);
            BootstrapLog.error("RecipeGraph export failed", error);
        }
    }

    private ExportContext createContext(Minecraft client) {
        Map<Item, List<Recipe<?>>> recipesByOutput = new HashMap<>();
        List<String> indexingErrors = new ArrayList<>();
        int recipeCount = 0;

        for (Recipe<?> recipe : client.level.getRecipeManager().getRecipes()) {
            recipeCount++;
            try {
                ItemStack result = recipe.getResultItem(client.level.registryAccess());
                if (result == null || result.isEmpty() || result.getItem() == Items.AIR) {
                    continue;
                }
                recipesByOutput.computeIfAbsent(result.getItem(), ignored -> new ArrayList<>()).add(recipe);
            } catch (RuntimeException | LinkageError error) {
                if (indexingErrors.size() < 64) {
                    indexingErrors.add(safeRecipeId(recipe) + " -> " + error.getClass().getSimpleName()
                        + ": " + clean(error.getMessage()));
                }
            }
        }

        Comparator<Recipe<?>> byId = Comparator.comparing(this::safeRecipeId);
        recipesByOutput.values().forEach(recipes -> recipes.sort(byId));
        return new ExportContext(client, recipesByOutput, recipeCount, indexingErrors,
            maxDepth.intValue(), leafMode.choiceValue());
    }

    private void appendHeader(StringBuilder output, Minecraft client, ExportContext context, Item target, long requested) {
        ResourceLocation id = itemKey(target);
        output.append("XENOBYTE RECIPE GRAPH v1").append(System.lineSeparator());
        output.append("Generated: ").append(LocalDateTime.now()).append(System.lineSeparator());
        output.append("Dimension: ").append(client.level.dimension().location()).append(System.lineSeparator());
        output.append("Target: ").append(itemLabel(target)).append(System.lineSeparator());
        output.append("Numeric ItemId: ").append(BuiltInRegistries.ITEM.getId(target)).append(System.lineSeparator());
        output.append("Registry ID: ").append(id).append(System.lineSeparator());
        output.append("Requested amount: ").append(requested).append(System.lineSeparator());
        output.append("Leaf mode: ").append(context.leafMode).append(System.lineSeparator());
        output.append("Maximum depth: ").append(context.maxDepth).append(System.lineSeparator());
        output.append("Indexed recipes: ").append(context.recipeCount).append(System.lineSeparator());
        output.append(System.lineSeparator());
        output.append("Selection rules:").append(System.lineSeparator());
        output.append("- Recipe IDs are sorted for deterministic output.").append(System.lineSeparator());
        output.append("- A recipe that immediately returns to an ancestor is deprioritized.").append(System.lineSeparator());
        output.append("- Ingredient/tag alternatives choose the first non-cyclic registry ID.").append(System.lineSeparator());
        output.append("- Alternative recipes and ingredient choices are listed below the main tree.").append(System.lineSeparator());
        output.append("- Only recipes exposed by the synchronized Minecraft RecipeManager are available in v1.").append(System.lineSeparator());
    }

    private void expand(ExportContext context, StringBuilder output, Item item, long required, int depth, Set<Item> ancestors) {
        context.nodes++;
        String indent = "  ".repeat(Math.max(0, depth));
        output.append(indent).append(depth == 0 ? "" : "- ")
            .append(itemLabel(item)).append(" x").append(required).append(System.lineSeparator());

        if (context.nodes > MAX_GRAPH_NODES) {
            appendLeaf(context, output, item, required, indent, "NODE LIMIT");
            context.nodeLimitStops++;
            return;
        }
        if (depth >= context.maxDepth) {
            appendLeaf(context, output, item, required, indent, "MAX DEPTH");
            context.depthStops++;
            return;
        }
        if ("Common".equals(context.leafMode) && isCommonRaw(item)) {
            appendLeaf(context, output, item, required, indent, "COMMON RAW");
            context.commonStops++;
            return;
        }
        if (ancestors.contains(item)) {
            appendLeaf(context, output, item, required, indent, "CYCLE");
            context.cycleStops++;
            return;
        }

        List<Recipe<?>> recipes = context.recipesByOutput.getOrDefault(item, List.of());
        if (recipes.isEmpty()) {
            appendLeaf(context, output, item, required, indent, "NO RECIPE");
            context.noRecipeStops++;
            return;
        }

        RecipePlan plan = selectRecipe(context, item, recipes, ancestors);
        if (plan == null || plan.result.isEmpty() || plan.ingredients.isEmpty()) {
            appendLeaf(context, output, item, required, indent, "UNSUPPORTED/EMPTY RECIPE");
            context.unsupportedStops++;
            return;
        }

        int outputCount = Math.max(1, plan.result.getCount());
        long crafts = ceilDiv(required, outputCount);
        long produced = safeMultiply(crafts, outputCount);
        long leftovers = Math.max(0L, produced - required);
        output.append(indent).append("  CREATED IN: \"").append(stationName(plan.recipe)).append("\"")
            .append(System.lineSeparator());
        output.append(indent).append("  RECIPE: ").append(safeRecipeId(plan.recipe)).append(System.lineSeparator());
        output.append(indent).append("  TYPE: ").append(recipeTypeId(plan.recipe))
            .append(" | SERIALIZER: ").append(recipeSerializerId(plan.recipe)).append(System.lineSeparator());
        output.append(indent).append("  BATCH: ").append(crafts).append(" craft(s) x ")
            .append(outputCount).append(" output = ").append(produced);
        if (leftovers > 0L) {
            output.append(" (leftover ").append(leftovers).append(')');
        }
        output.append(System.lineSeparator());
        if (recipes.size() > 1) {
            output.append(indent).append("  ALTERNATIVE RECIPES: ").append(recipes.size() - 1)
                .append(" (see ALTERNATIVES section)").append(System.lineSeparator());
            rememberAlternatives(context, item, plan.recipe, recipes);
        }

        Set<Item> nextAncestors = new LinkedHashSet<>(ancestors);
        nextAncestors.add(item);
        for (IngredientPlan ingredient : plan.ingredients) {
            long ingredientAmount = safeMultiply(crafts, ingredient.count);
            if (ingredient.alternatives.size() > 1) {
                output.append(indent).append("  CHOICE: selected ").append(itemLabel(ingredient.item))
                    .append(" from ").append(formatItems(ingredient.alternatives, MAX_ALTERNATIVES_PER_INGREDIENT))
                    .append(System.lineSeparator());
                context.ingredientChoices++;
            }
            expand(context, output, ingredient.item, ingredientAmount, depth + 1, nextAncestors);
        }
    }

    private RecipePlan selectRecipe(ExportContext context, Item output, List<Recipe<?>> recipes, Set<Item> ancestors) {
        RecipePlan best = null;
        int bestUnsupportedPenalty = Integer.MAX_VALUE;
        int bestCyclePenalty = Integer.MAX_VALUE;
        Set<Item> blockedIngredients = new LinkedHashSet<>(ancestors);
        blockedIngredients.add(output);
        for (Recipe<?> recipe : recipes) {
            try {
                ItemStack result = recipe.getResultItem(context.client.level.registryAccess());
                List<IngredientPlan> ingredients = planIngredients(recipe, blockedIngredients);
                int cyclePenalty = 0;
                for (IngredientPlan ingredient : ingredients) {
                    if (ingredient.item == output || ancestors.contains(ingredient.item)) {
                        cyclePenalty++;
                    }
                }
                int unsupportedPenalty = ingredients.isEmpty() ? 1 : 0;
                RecipePlan candidate = new RecipePlan(recipe, result.copy(), ingredients);
                if (best == null || unsupportedPenalty < bestUnsupportedPenalty
                    || (unsupportedPenalty == bestUnsupportedPenalty && cyclePenalty < bestCyclePenalty)
                    || (unsupportedPenalty == bestUnsupportedPenalty && cyclePenalty == bestCyclePenalty
                        && safeRecipeId(recipe).compareTo(safeRecipeId(best.recipe)) < 0)) {
                    best = candidate;
                    bestUnsupportedPenalty = unsupportedPenalty;
                    bestCyclePenalty = cyclePenalty;
                }
            } catch (RuntimeException | LinkageError error) {
                if (context.expansionErrors.size() < 64) {
                    context.expansionErrors.add(safeRecipeId(recipe) + " -> " + error.getClass().getSimpleName()
                        + ": " + clean(error.getMessage()));
                }
            }
        }
        return best;
    }

    private List<IngredientPlan> planIngredients(Recipe<?> recipe, Set<Item> ancestors) {
        Map<Item, MutableIngredientPlan> grouped = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<ItemStack> alternatives = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                    alternatives.add(stack.copy());
                }
            }
            alternatives.sort(Comparator.comparing(stack -> String.valueOf(itemKey(stack.getItem()))));
            if (alternatives.isEmpty()) {
                continue;
            }

            ItemStack selected = alternatives.stream()
                .filter(stack -> !ancestors.contains(stack.getItem()))
                .findFirst()
                .orElse(alternatives.get(0));
            long count = Math.max(1, selected.getCount());
            MutableIngredientPlan existing = grouped.computeIfAbsent(selected.getItem(), ignored -> new MutableIngredientPlan(selected.getItem()));
            existing.count = safeAdd(existing.count, count);
            for (ItemStack alternative : alternatives) {
                existing.alternatives.add(alternative.getItem());
            }
        }

        List<IngredientPlan> plans = new ArrayList<>();
        for (MutableIngredientPlan plan : grouped.values()) {
            plans.add(new IngredientPlan(plan.item, plan.count, List.copyOf(plan.alternatives)));
        }
        plans.sort(Comparator.comparing(plan -> String.valueOf(itemKey(plan.item))));
        return List.copyOf(plans);
    }

    private void appendLeaf(ExportContext context, StringBuilder output, Item item, long amount, String indent, String reason) {
        output.append(indent).append("  LEAF: ").append(reason).append(System.lineSeparator());
        context.rawTotals.merge(item, amount, RecipeGraphModule::safeAdd);
    }

    private void rememberAlternatives(ExportContext context, Item item, Recipe<?> selected, List<Recipe<?>> recipes) {
        ResourceLocation itemId = itemKey(item);
        String key = String.valueOf(itemId);
        if (!context.reportedAlternativeItems.add(key)) {
            return;
        }
        context.alternativeGroups++;
        context.alternatives.append(System.lineSeparator())
            .append(itemLabel(item)).append(System.lineSeparator());
        int shown = 0;
        for (Recipe<?> recipe : recipes) {
            if (shown++ >= MAX_RECIPE_ALTERNATIVES) {
                context.alternatives.append("  ... ").append(recipes.size() - MAX_RECIPE_ALTERNATIVES)
                    .append(" more recipe(s)").append(System.lineSeparator());
                break;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(context.client.level.registryAccess());
            } catch (RuntimeException | LinkageError ignored) {
                result = ItemStack.EMPTY;
            }
            context.alternatives.append(recipe == selected ? "  * SELECTED " : "  - ")
                .append(safeRecipeId(recipe)).append(System.lineSeparator());
            context.alternatives.append("      CREATED IN: \"").append(stationName(recipe)).append("\"")
                .append(" | TYPE: ").append(recipeTypeId(recipe))
                .append(" | OUTPUT: ").append(result.isEmpty() ? "unknown" : result.getCount())
                .append(System.lineSeparator());
            context.alternatives.append("      INPUTS: ").append(recipeInputSummary(recipe)).append(System.lineSeparator());
        }
    }

    private void appendTotals(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("RAW / TERMINAL MATERIAL TOTALS").append(System.lineSeparator());
        output.append("===============================").append(System.lineSeparator());
        if (context.rawTotals.isEmpty()) {
            output.append("(none)").append(System.lineSeparator());
            return;
        }
        context.rawTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(item -> String.valueOf(itemKey(item)))))
            .forEach(entry -> output.append("- ").append(itemLabel(entry.getKey()))
                .append(" x").append(entry.getValue()).append(System.lineSeparator()));
    }

    private void appendAlternatives(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("ALTERNATIVE RECIPES").append(System.lineSeparator());
        output.append("===================").append(System.lineSeparator());
        if (context.alternatives.length() == 0) {
            output.append("(none)").append(System.lineSeparator());
        } else {
            output.append(context.alternatives);
        }
    }

    private void appendDiagnostics(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("DIAGNOSTICS").append(System.lineSeparator());
        output.append("===========").append(System.lineSeparator());
        output.append("Expanded nodes: ").append(context.nodes).append(System.lineSeparator());
        output.append("Common raw stops: ").append(context.commonStops).append(System.lineSeparator());
        output.append("No recipe stops: ").append(context.noRecipeStops).append(System.lineSeparator());
        output.append("Maximum depth stops: ").append(context.depthStops).append(System.lineSeparator());
        output.append("Cycle stops: ").append(context.cycleStops).append(System.lineSeparator());
        output.append("Unsupported/empty stops: ").append(context.unsupportedStops).append(System.lineSeparator());
        output.append("Node limit stops: ").append(context.nodeLimitStops).append(System.lineSeparator());
        output.append("Ingredient choices: ").append(context.ingredientChoices).append(System.lineSeparator());
        output.append("Alternative recipe groups: ").append(context.alternativeGroups).append(System.lineSeparator());
        appendErrors(output, "Recipe indexing errors", context.indexingErrors);
        appendErrors(output, "Recipe expansion errors", context.expansionErrors);
    }

    private void appendErrors(StringBuilder output, String title, List<String> errors) {
        output.append(title).append(": ").append(errors.size()).append(System.lineSeparator());
        for (String error : errors) {
            output.append("  - ").append(error).append(System.lineSeparator());
        }
    }

    private String recipeInputSummary(Recipe<?> recipe) {
        List<String> inputs = new ArrayList<>();
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                List<Item> alternatives = new ArrayList<>();
                int count = 1;
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                        alternatives.add(stack.getItem());
                        count = Math.max(count, stack.getCount());
                    }
                }
                if (!alternatives.isEmpty()) {
                    inputs.add(formatItems(alternatives, 5) + " x" + count);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            return "unavailable (" + error.getClass().getSimpleName() + ")";
        }
        return inputs.isEmpty() ? "not exposed" : String.join(" + ", inputs);
    }

    private String formatItems(List<Item> items, int limit) {
        List<String> labels = new ArrayList<>();
        Set<Item> unique = new LinkedHashSet<>(items);
        int index = 0;
        for (Item item : unique) {
            if (index++ >= limit) {
                labels.add("..." + (unique.size() - limit) + " more");
                break;
            }
            labels.add(String.valueOf(itemKey(item)));
        }
        return "[" + String.join(" | ", labels) + "]";
    }

    private boolean isCommonRaw(Item item) {
        ResourceLocation id = itemKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return COMMON_RAW_NAMES.contains(path)
            || path.endsWith("_ingot")
            || path.endsWith("_gem")
            || path.endsWith("_dust")
            || path.endsWith("_ore")
            || path.startsWith("raw_")
            || path.endsWith("_raw_material");
    }

    private String stationName(Recipe<?> recipe) {
        String type = recipeTypeId(recipe);
        String path = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.contains("crafting")) return "Crafting Table";
        if (normalized.contains("blasting")) return "Blast Furnace";
        if (normalized.contains("smelting")) return "Furnace";
        if (normalized.contains("smoking")) return "Smoker";
        if (normalized.contains("campfire")) return "Campfire";
        if (normalized.contains("stonecut")) return "Stonecutter";
        if (normalized.contains("smithing")) return "Smithing Table";
        if (normalized.contains("crusher") || normalized.contains("crushing")) return "Crusher";
        if (normalized.contains("pulveriz")) return "Pulverizer";
        if (normalized.contains("macerat")) return "Macerator";
        if (normalized.contains("enrich")) return "Enrichment Chamber";
        if (normalized.contains("infus")) return "Infuser";
        if (normalized.contains("compress")) return "Compressor";
        if (normalized.contains("sawmill") || normalized.contains("sawing")) return "Sawmill";
        if (normalized.contains("press")) return "Press";
        if (normalized.contains("assembl")) return "Assembler";
        return humanize(path);
    }

    private String recipeTypeId(Recipe<?> recipe) {
        try {
            ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            return id == null ? recipe.getType().toString() : id.toString();
        } catch (RuntimeException | LinkageError error) {
            return recipe.getType().toString();
        }
    }

    private String recipeSerializerId(Recipe<?> recipe) {
        try {
            ResourceLocation id = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            return id == null ? recipe.getSerializer().toString() : id.toString();
        } catch (RuntimeException | LinkageError error) {
            return recipe.getSerializer().toString();
        }
    }

    private String safeRecipeId(Recipe<?> recipe) {
        try {
            return String.valueOf(recipe.getId());
        } catch (RuntimeException | LinkageError error) {
            return recipe.getClass().getName();
        }
    }

    private String itemLabel(Item item) {
        ResourceLocation id = itemKey(item);
        String name;
        try {
            name = clean(new ItemStack(item).getHoverName().getString());
        } catch (RuntimeException | LinkageError error) {
            name = "unknown";
        }
        return name + " [" + id + "; numeric=" + BuiltInRegistries.ITEM.getId(item) + "]";
    }

    private ResourceLocation itemKey(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private Path resolveExportDirectory() {
        String packageDir = System.getProperty("xenobyte-modern.packageDir", "");
        if (!packageDir.isBlank()) {
            return Path.of(packageDir);
        }
        try {
            CodeSource source = RecipeGraphModule.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                Path path = Path.of(uri);
                if (Files.isRegularFile(path)) {
                    return path.getParent();
                }
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        } catch (Exception ignored) {
            // Fall through to temp.
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private void result(String message, Path path) {
        lastResult = message;
        lastExport = path;
        ModuleMessageLog.push("RecipeGraph", message);
        BootstrapLog.info("RecipeGraph: " + message + (path == null ? "" : ", path=" + path));
    }

    private String humanize(String value) {
        String[] words = value.replace('-', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.length() == 0 ? "Unknown Machine" : result.toString();
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0L) {
            return 0L;
        }
        return 1L + (value - 1L) / Math.max(1L, divisor);
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    @Override
    public String description() {
        return "Exports a recursive recipe tree, machines, alternatives and terminal material totals next to the injected DLL.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "RecipeGraph result=" + lastResult
            + " file=" + (lastExport == null ? "none" : lastExport.getFileName())
            + " itemId=" + itemId.displayValue()
            + " amount=" + amount.displayValue()
            + " leaf=" + leafMode.choiceValue()
            + " depth=" + maxDepth.displayValue();
    }

    private record RecipePlan(Recipe<?> recipe, ItemStack result, List<IngredientPlan> ingredients) {
    }

    private record IngredientPlan(Item item, long count, List<Item> alternatives) {
    }

    private static final class MutableIngredientPlan {
        private final Item item;
        private long count;
        private final Set<Item> alternatives = new LinkedHashSet<>();

        private MutableIngredientPlan(Item item) {
            this.item = item;
        }
    }

    private static final class ExportContext {
        private final Minecraft client;
        private final Map<Item, List<Recipe<?>>> recipesByOutput;
        private final int recipeCount;
        private final List<String> indexingErrors;
        private final List<String> expansionErrors = new ArrayList<>();
        private final int maxDepth;
        private final String leafMode;
        private final Map<Item, Long> rawTotals = new HashMap<>();
        private final Set<String> reportedAlternativeItems = new HashSet<>();
        private final StringBuilder alternatives = new StringBuilder();
        private int nodes;
        private int commonStops;
        private int noRecipeStops;
        private int depthStops;
        private int cycleStops;
        private int unsupportedStops;
        private int nodeLimitStops;
        private int ingredientChoices;
        private int alternativeGroups;

        private ExportContext(Minecraft client, Map<Item, List<Recipe<?>>> recipesByOutput, int recipeCount,
                              List<String> indexingErrors, int maxDepth, String leafMode) {
            this.client = client;
            this.recipesByOutput = recipesByOutput;
            this.recipeCount = recipeCount;
            this.indexingErrors = indexingErrors;
            this.maxDepth = maxDepth;
            this.leafMode = leafMode;
        }
    }
}
