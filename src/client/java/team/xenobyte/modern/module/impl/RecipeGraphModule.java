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
        .describe("Common stops at ingots, gems, dusts and ores. NoRecipe expands until a terminal recipe."));
    private final ModuleSetting maxDepth = setting("MaxDepth", ModuleSetting.number("MaxDepth", 16.0D, 1.0D, 48.0D, 1.0D)
        .describe("Maximum recursive recipe depth."));
    private final ModuleSetting detail = setting("Detail", ModuleSetting.choice("Detail", 0, "Compact", "Full")
        .describe("Compact keeps one-line definitions. Full includes recipe type, serializer and complete choices."));
    private final ModuleSetting alternatives = setting("Alternatives", ModuleSetting.choice("Alternatives", 0, "Summary", "Full", "Off")
        .describe("Summary only reports counts. Full appends every available recipe."));

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

            StringBuilder plan = new StringBuilder(32_000);
            expand(context, plan, target, requested, 0, new LinkedHashSet<>(), true);

            StringBuilder output = new StringBuilder(48_000);
            appendHeader(output, client, context, target, requested);
            output.append(System.lineSeparator()).append("COMPACT RECIPE PLAN").append(System.lineSeparator());
            output.append("===================").append(System.lineSeparator());
            output.append("Each [Nxxx] item is expanded once. '->' lines reference an earlier definition.")
                .append(System.lineSeparator());
            output.append(plan);
            appendDefinitions(output, context);
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
                + ", occurrences=" + context.nodes
                + ", unique=" + context.definitions.size()
                + ", references=" + context.collapsedReferences
                + ", raw=" + context.rawTotals.size());
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
            maxDepth.intValue(), leafMode.choiceValue(), detail.choiceValue(), alternatives.choiceValue());
    }

    private void appendHeader(StringBuilder output, Minecraft client, ExportContext context, Item target, long requested) {
        ResourceLocation id = itemKey(target);
        output.append("XENOBYTE RECIPE GRAPH v2").append(System.lineSeparator());
        output.append("Generated: ").append(LocalDateTime.now()).append(System.lineSeparator());
        output.append("Dimension: ").append(client.level.dimension().location()).append(System.lineSeparator());
        output.append("Target: ").append(itemLabel(target)).append(System.lineSeparator());
        output.append("Numeric ItemId: ").append(BuiltInRegistries.ITEM.getId(target)).append(System.lineSeparator());
        output.append("Registry ID: ").append(id).append(System.lineSeparator());
        output.append("Requested amount: ").append(requested).append(System.lineSeparator());
        output.append("Leaf mode: ").append(context.leafMode).append(System.lineSeparator());
        output.append("Maximum depth: ").append(context.maxDepth).append(System.lineSeparator());
        output.append("Detail: ").append(context.detailMode).append(System.lineSeparator());
        output.append("Alternatives: ").append(context.alternativesMode).append(System.lineSeparator());
        output.append("Indexed recipes: ").append(context.recipeCount).append(System.lineSeparator());
        output.append(System.lineSeparator());
        output.append("Selection rules:").append(System.lineSeparator());
        output.append("- Recipes are scored before their ID is used as a deterministic tie breaker.").append(System.lineSeparator());
        output.append("- Deconstruction, recycling, decorative conversion and reversible cycles are deprioritized.")
            .append(System.lineSeparator());
        output.append("- Ingredient alternatives prefer common raw items, vanilla base items and simple variants.")
            .append(System.lineSeparator());
        output.append("- Only recipes exposed by the synchronized Minecraft RecipeManager are available.")
            .append(System.lineSeparator());
    }

    private void expand(ExportContext context, StringBuilder output, Item item, long required, int depth,
                        Set<Item> ancestors, boolean emit) {
        context.nodes++;
        int id = nodeId(context, item);
        boolean cycle = ancestors.contains(item);
        boolean repeated = emit && context.renderedItems.contains(item);
        if (repeated) {
            appendPlanReference(output, id, item, required, depth, cycle);
            context.collapsedReferences++;
            emit = false;
        } else if (emit) {
            context.renderedItems.add(item);
        }

        if (context.nodes > MAX_GRAPH_NODES) {
            registerLeaf(context, item, "NODE LIMIT");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "NODE LIMIT");
            context.nodeLimitStops++;
            return;
        }
        if (depth >= context.maxDepth) {
            registerLeaf(context, item, "MAX DEPTH");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "MAX DEPTH");
            context.depthStops++;
            return;
        }
        if ("Common".equals(context.leafMode) && isCommonRaw(item)) {
            registerLeaf(context, item, "COMMON RAW");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "COMMON RAW");
            context.commonStops++;
            return;
        }
        if (cycle) {
            registerLeaf(context, item, "CYCLE");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "CYCLE");
            context.cycleStops++;
            return;
        }

        List<Recipe<?>> recipes = context.recipesByOutput.getOrDefault(item, List.of());
        if (recipes.isEmpty()) {
            registerLeaf(context, item, "NO RECIPE");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "NO RECIPE");
            context.noRecipeStops++;
            return;
        }

        RecipePlan recipePlan = selectedPlan(context, item, recipes);
        if (recipePlan == null || recipePlan.result.isEmpty() || recipePlan.ingredients.isEmpty()) {
            registerLeaf(context, item, "UNSUPPORTED/EMPTY RECIPE");
            recordRaw(context, item, required);
            if (emit) appendPlanLeaf(output, id, item, required, depth, "UNSUPPORTED/EMPTY RECIPE");
            context.unsupportedStops++;
            return;
        }

        registerRecipe(context, item, recipePlan, recipes.size() - 1);
        int outputCount = Math.max(1, recipePlan.result.getCount());
        long crafts = ceilDiv(required, outputCount);
        if (emit) {
            appendPlanRecipe(output, id, item, required, depth, stationName(recipePlan.recipe), crafts);
        }

        Set<Item> nextAncestors = new LinkedHashSet<>(ancestors);
        nextAncestors.add(item);
        for (IngredientPlan ingredient : recipePlan.ingredients) {
            long ingredientAmount = safeMultiply(crafts, ingredient.count);
            expand(context, output, ingredient.item, ingredientAmount, depth + 1, nextAncestors, emit);
        }
    }

    private RecipePlan selectedPlan(ExportContext context, Item item, List<Recipe<?>> recipes) {
        if (context.selectedPlans.containsKey(item)) {
            return context.selectedPlans.get(item);
        }
        RecipePlan selected = selectRecipe(context, item, recipes);
        context.selectedPlans.put(item, selected);
        if (selected != null && selected.recipe != recipes.get(0)) {
            context.scoredRecipeChanges++;
        }
        return selected;
    }

    private RecipePlan selectRecipe(ExportContext context, Item output, List<Recipe<?>> recipes) {
        RecipePlan best = null;
        long bestScore = Long.MAX_VALUE;
        Set<Item> blockedIngredients = Set.of(output);
        for (Recipe<?> recipe : recipes) {
            try {
                ItemStack result = recipe.getResultItem(context.client.level.registryAccess());
                List<IngredientPlan> ingredients = planIngredients(context, recipe, blockedIngredients, output);
                RecipePlan candidate = new RecipePlan(recipe, result.copy(), ingredients);
                long score = recipeScore(context, output, candidate);
                if (best == null || score < bestScore) {
                    best = candidate;
                    bestScore = score;
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

    private long recipeScore(ExportContext context, Item output, RecipePlan plan) {
        if (plan.result.isEmpty()) {
            return Long.MAX_VALUE / 2L;
        }
        long score = plan.ingredients.isEmpty() ? 1_000_000_000_000L : 0L;
        int directCycles = 0;
        long ingredientUnits = 0L;
        for (IngredientPlan ingredient : plan.ingredients) {
            if (ingredient.item == output) {
                directCycles++;
            }
            ingredientUnits = safeAdd(ingredientUnits, ingredient.count);
        }
        score = safeAdd(score, directCycles * 100_000_000_000L);
        score = safeAdd(score, recipeRoutePenalty(plan.recipe) * 1_000_000L);
        score = safeAdd(score, reverseCyclePenalty(context, output, plan.ingredients) * 100_000L);
        score = safeAdd(score, plan.ingredients.size() * 100L);
        score = safeAdd(score, Math.min(10_000L, ingredientUnits));
        score -= Math.min(99, Math.max(1, plan.result.getCount()));
        return score;
    }

    private int recipeRoutePenalty(Recipe<?> recipe) {
        String id = safeRecipeId(recipe).toLowerCase(Locale.ROOT);
        String type = recipeTypeId(recipe).toLowerCase(Locale.ROOT);
        int penalty = 0;
        if (id.contains("deconstruction") || id.contains("deconstruct")) penalty += 2_000;
        if (id.contains("recycling") || id.contains("recycle")) penalty += 1_800;
        if (id.contains("uncompress") || id.contains("unpack") || id.contains("from_storage")) penalty += 1_000;
        if (id.contains("from_block") || id.contains("block_to_")) penalty += 700;
        if (id.contains("nugget_from_blasting") || id.contains("nugget_from_smelting")) penalty += 1_200;
        if (id.contains("pebble_to") || id.contains("cobble_to_pebble")) penalty += 600;
        if (type.contains("stonecut") || id.contains("stonecutting")) penalty += 700;
        if (id.contains("stairs") || id.contains("slab") || id.contains("wall")) penalty += 350;
        return penalty;
    }

    private int reverseCyclePenalty(ExportContext context, Item output, List<IngredientPlan> ingredients) {
        int penalty = 0;
        for (IngredientPlan ingredient : ingredients) {
            if (isCommonRaw(ingredient.item)) {
                continue;
            }
            for (Recipe<?> reverse : context.recipesByOutput.getOrDefault(ingredient.item, List.of())) {
                if (recipeContainsItem(context, reverse, output)) {
                    penalty++;
                    break;
                }
            }
        }
        return penalty;
    }

    private boolean recipeContainsItem(ExportContext context, Recipe<?> recipe, Item item) {
        Set<Item> cached = context.recipeInputs.get(recipe);
        if (cached == null) {
            cached = new HashSet<>();
            try {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    for (ItemStack stack : ingredient.getItems()) {
                        if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                            cached.add(stack.getItem());
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // An unreadable custom recipe is not useful for cycle scoring.
            }
            context.recipeInputs.put(recipe, cached);
        }
        return cached.contains(item);
    }

    private List<IngredientPlan> planIngredients(ExportContext context, Recipe<?> recipe, Set<Item> blocked, Item output) {
        Map<Item, MutableIngredientPlan> grouped = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                    options.add(stack.copy());
                }
            }
            options.sort(Comparator
                .comparingInt((ItemStack stack) -> ingredientPreference(context, output, stack.getItem(), blocked))
                .thenComparing(stack -> String.valueOf(itemKey(stack.getItem()))));
            if (options.isEmpty()) {
                continue;
            }

            ItemStack selected = options.stream()
                .filter(stack -> !blocked.contains(stack.getItem()))
                .findFirst()
                .orElse(options.get(0));
            long count = Math.max(1, selected.getCount());
            MutableIngredientPlan existing = grouped.computeIfAbsent(selected.getItem(), MutableIngredientPlan::new);
            existing.count = safeAdd(existing.count, count);
            for (ItemStack option : options) {
                existing.alternatives.add(option.getItem());
            }
        }

        List<IngredientPlan> plans = new ArrayList<>();
        for (MutableIngredientPlan plan : grouped.values()) {
            plans.add(new IngredientPlan(plan.item, plan.count, List.copyOf(plan.alternatives)));
        }
        plans.sort(Comparator.comparing(plan -> String.valueOf(itemKey(plan.item))));
        return List.copyOf(plans);
    }

    private int ingredientPreference(ExportContext context, Item output, Item candidate, Set<Item> blocked) {
        ResourceLocation id = itemKey(candidate);
        if (id == null) {
            return 10_000;
        }
        int score = blocked.contains(candidate) ? 100_000 : 0;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        ResourceLocation outputId = itemKey(output);
        if (isCommonRaw(candidate)) score -= 400;
        if ("minecraft".equals(id.getNamespace())) score -= 140;
        if (outputId != null && outputId.getNamespace().equals(id.getNamespace())) score -= 60;
        if (!context.recipesByOutput.containsKey(candidate)) score -= 20;
        if (path.equals("glass") || path.equals("stone") || path.equals("cobblestone")) score -= 100;
        if (path.contains("stained") || path.contains("framed") || path.contains("tiled")) score += 80;
        if (path.endsWith("_stairs") || path.endsWith("_slab") || path.endsWith("_wall")) score += 120;
        if (path.endsWith("_block")) score += 20;
        return score;
    }

    private void registerRecipe(ExportContext context, Item item, RecipePlan plan, int alternativeCount) {
        RecipeDefinition existing = context.definitions.get(item);
        if (existing == null || existing.plan == null) {
            context.definitions.put(item, new RecipeDefinition(item, plan, null, Math.max(0, alternativeCount)));
        }
        if (alternativeCount > 0 && context.reportedAlternativeItems.add(item)) {
            context.alternativeGroups++;
            if ("Full".equals(context.alternativesMode)) {
                appendFullAlternatives(context, item, plan.recipe,
                    context.recipesByOutput.getOrDefault(item, List.of()));
            }
        }
    }

    private void registerLeaf(ExportContext context, Item item, String reason) {
        context.definitions.putIfAbsent(item, new RecipeDefinition(item, null, reason, 0));
    }

    private int nodeId(ExportContext context, Item item) {
        return context.nodeIds.computeIfAbsent(item, ignored -> context.nodeIds.size() + 1);
    }

    private void recordRaw(ExportContext context, Item item, long amount) {
        context.rawTotals.merge(item, amount, RecipeGraphModule::safeAdd);
    }

    private void appendPlanRecipe(StringBuilder output, int id, Item item, long required, int depth,
                                  String station, long crafts) {
        output.append("  ".repeat(Math.max(0, depth)))
            .append(nodeToken(id)).append(' ').append(shortItemLabel(item)).append(" x").append(required)
            .append(" <- ").append(station).append(" (").append(crafts).append(" craft")
            .append(crafts == 1L ? ")" : "s)").append(System.lineSeparator());
    }

    private void appendPlanLeaf(StringBuilder output, int id, Item item, long required, int depth, String reason) {
        output.append("  ".repeat(Math.max(0, depth)))
            .append(nodeToken(id)).append(' ').append(shortItemLabel(item)).append(" x").append(required)
            .append(" [TERMINAL: ").append(reason).append(']').append(System.lineSeparator());
    }

    private void appendPlanReference(StringBuilder output, int id, Item item, long required, int depth, boolean cycle) {
        output.append("  ".repeat(Math.max(0, depth))).append("-> ")
            .append(nodeToken(id)).append(' ').append(shortItemLabel(item)).append(" x").append(required);
        if (cycle) {
            output.append(" [CYCLE STOP]");
        }
        output.append(System.lineSeparator());
    }

    private void appendDefinitions(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("UNIQUE RECIPE DEFINITIONS").append(System.lineSeparator());
        output.append("=========================").append(System.lineSeparator());
        for (RecipeDefinition definition : context.definitions.values()) {
            Item item = definition.item;
            output.append(nodeToken(nodeId(context, item))).append(' ').append(itemLabel(item)).append(System.lineSeparator());
            if (definition.plan == null) {
                output.append("  terminal: ").append(definition.leafReason).append(System.lineSeparator());
                continue;
            }

            RecipePlan plan = definition.plan;
            output.append("  via: ").append(stationName(plan.recipe)).append(" | ").append(safeRecipeId(plan.recipe))
                .append(" | output x").append(Math.max(1, plan.result.getCount())).append(System.lineSeparator());
            if ("Full".equals(context.detailMode)) {
                output.append("  type: ").append(recipeTypeId(plan.recipe))
                    .append(" | serializer: ").append(recipeSerializerId(plan.recipe)).append(System.lineSeparator());
            }
            if (plan.ingredients.isEmpty()) {
                output.append("  inputs: not exposed").append(System.lineSeparator());
            } else {
                output.append("  inputs:").append(System.lineSeparator());
                for (IngredientPlan ingredient : plan.ingredients) {
                    output.append("    - ").append(nodeToken(nodeId(context, ingredient.item))).append(' ')
                        .append(shortItemLabel(ingredient.item)).append(" x").append(ingredient.count);
                    if (ingredient.alternatives.size() > 1) {
                        output.append(" | choice +").append(ingredient.alternatives.size() - 1).append(" option(s)");
                        context.ingredientChoices++;
                    }
                    output.append(System.lineSeparator());
                    if ("Full".equals(context.detailMode) && ingredient.alternatives.size() > 1) {
                        output.append("      selected from ")
                            .append(formatItems(ingredient.alternatives, MAX_ALTERNATIVES_PER_INGREDIENT))
                            .append(System.lineSeparator());
                    }
                }
            }
            if (definition.alternativeCount > 0 && !"Off".equals(context.alternativesMode)) {
                output.append("  alternatives: ").append(definition.alternativeCount).append(" other recipe(s)");
                if ("Full".equals(context.alternativesMode)) {
                    output.append("; see FULL ALTERNATIVE RECIPES");
                }
                output.append(System.lineSeparator());
            }
        }
    }

    private void appendFullAlternatives(ExportContext context, Item item, Recipe<?> selected, List<Recipe<?>> recipes) {
        context.fullAlternatives.append(System.lineSeparator()).append(itemLabel(item)).append(System.lineSeparator());
        int shown = 0;
        for (Recipe<?> recipe : recipes) {
            if (shown++ >= MAX_RECIPE_ALTERNATIVES) {
                context.fullAlternatives.append("  ... ").append(recipes.size() - MAX_RECIPE_ALTERNATIVES)
                    .append(" more recipe(s)").append(System.lineSeparator());
                break;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(context.client.level.registryAccess());
            } catch (RuntimeException | LinkageError ignored) {
                result = ItemStack.EMPTY;
            }
            context.fullAlternatives.append(recipe == selected ? "  * SELECTED " : "  - ")
                .append(safeRecipeId(recipe)).append(System.lineSeparator());
            context.fullAlternatives.append("      via: ").append(stationName(recipe))
                .append(" | type: ").append(recipeTypeId(recipe))
                .append(" | output: ").append(result.isEmpty() ? "unknown" : result.getCount())
                .append(System.lineSeparator());
            context.fullAlternatives.append("      inputs: ").append(recipeInputSummary(recipe)).append(System.lineSeparator());
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
        if (!"Full".equals(context.alternativesMode)) {
            return;
        }
        output.append(System.lineSeparator()).append("FULL ALTERNATIVE RECIPES").append(System.lineSeparator());
        output.append("========================").append(System.lineSeparator());
        if (context.fullAlternatives.length() == 0) {
            output.append("(none)").append(System.lineSeparator());
        } else {
            output.append(context.fullAlternatives);
        }
    }

    private void appendDiagnostics(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("DIAGNOSTICS").append(System.lineSeparator());
        output.append("===========").append(System.lineSeparator());
        output.append("Expanded occurrences: ").append(context.nodes).append(System.lineSeparator());
        output.append("Unique item definitions: ").append(context.definitions.size()).append(System.lineSeparator());
        output.append("Collapsed references: ").append(context.collapsedReferences).append(System.lineSeparator());
        output.append("Recipe choices changed by scoring: ").append(context.scoredRecipeChanges).append(System.lineSeparator());
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
                List<Item> options = new ArrayList<>();
                int count = 1;
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                        options.add(stack.getItem());
                        count = Math.max(count, stack.getCount());
                    }
                }
                if (!options.isEmpty()) {
                    inputs.add(formatItems(options, 5) + " x" + count);
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
        return itemName(item) + " [" + itemKey(item) + "; numeric=" + BuiltInRegistries.ITEM.getId(item) + "]";
    }

    private String shortItemLabel(Item item) {
        return itemName(item) + " [" + itemKey(item) + "]";
    }

    private String itemName(Item item) {
        try {
            return clean(new ItemStack(item).getHoverName().getString());
        } catch (RuntimeException | LinkageError error) {
            return "unknown";
        }
    }

    private String nodeToken(int id) {
        return String.format(Locale.ROOT, "[N%03d]", id);
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
        return "Exports a compact deduplicated recipe graph, machine definitions and terminal material totals next to the DLL.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "RecipeGraph result=" + lastResult
            + " file=" + (lastExport == null ? "none" : lastExport.getFileName())
            + " itemId=" + itemId.displayValue()
            + " amount=" + amount.displayValue()
            + " leaf=" + leafMode.choiceValue()
            + " depth=" + maxDepth.displayValue()
            + " detail=" + detail.choiceValue()
            + " alternatives=" + alternatives.choiceValue();
    }

    private record RecipePlan(Recipe<?> recipe, ItemStack result, List<IngredientPlan> ingredients) {
    }

    private record IngredientPlan(Item item, long count, List<Item> alternatives) {
    }

    private record RecipeDefinition(Item item, RecipePlan plan, String leafReason, int alternativeCount) {
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
        private final String detailMode;
        private final String alternativesMode;
        private final Map<Item, Long> rawTotals = new HashMap<>();
        private final Map<Item, Integer> nodeIds = new LinkedHashMap<>();
        private final Map<Item, RecipeDefinition> definitions = new LinkedHashMap<>();
        private final Map<Item, RecipePlan> selectedPlans = new HashMap<>();
        private final Map<Recipe<?>, Set<Item>> recipeInputs = new HashMap<>();
        private final Set<Item> renderedItems = new HashSet<>();
        private final Set<Item> reportedAlternativeItems = new HashSet<>();
        private final StringBuilder fullAlternatives = new StringBuilder();
        private int nodes;
        private int collapsedReferences;
        private int scoredRecipeChanges;
        private int commonStops;
        private int noRecipeStops;
        private int depthStops;
        private int cycleStops;
        private int unsupportedStops;
        private int nodeLimitStops;
        private int ingredientChoices;
        private int alternativeGroups;

        private ExportContext(Minecraft client, Map<Item, List<Recipe<?>>> recipesByOutput, int recipeCount,
                              List<String> indexingErrors, int maxDepth, String leafMode,
                              String detailMode, String alternativesMode) {
            this.client = client;
            this.recipesByOutput = recipesByOutput;
            this.recipeCount = recipeCount;
            this.indexingErrors = indexingErrors;
            this.maxDepth = maxDepth;
            this.leafMode = leafMode;
            this.detailMode = detailMode;
            this.alternativesMode = alternativesMode;
        }
    }
}
