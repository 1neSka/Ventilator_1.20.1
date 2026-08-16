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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
    private static final int MAX_GRAPH_NODES = 100_000;
    private static final int MAX_ALTERNATIVES_PER_INGREDIENT = 12;
    private static final int MAX_RECIPE_ALTERNATIVES = 24;
    private static final int COMPLEXITY_LOOKAHEAD = 3;

    private static final Set<String> COMMON_RAW_NAMES = Set.of(
        "redstone", "glowstone_dust", "diamond", "emerald", "coal", "charcoal",
        "lapis_lazuli", "quartz", "amethyst_shard", "flint", "clay_ball",
        "slime_ball", "ender_pearl", "nether_star", "echo_shard"
    );
    private static final Set<String> BASE_ELEMENT_NAMES = Set.of(
        "iron", "gold", "copper", "tin", "lead", "silver", "nickel", "osmium",
        "uranium", "zinc", "aluminum", "aluminium", "platinum", "iridium", "cobalt",
        "tungsten", "titanium", "chromium", "chrome", "magnesium", "lithium",
        "boron", "thorium"
    );
    private static final Map<Integer, ManaMaterial> MANA_MATERIALS = Map.ofEntries(
        Map.entry(2146, new ManaMaterial("terrasteel_ingot", 1_000_000L)),
        Map.entry(4082, new ManaMaterial("alfsteel_ingot", 1_000_000L)),
        Map.entry(8058, new ManaMaterial("malachite_ingot", 750_000L)),
        Map.entry(8060, new ManaMaterial("saffron_ingot", 1_000_000L)),
        Map.entry(8062, new ManaMaterial("shadow_ingot", 1_500_000L)),
        Map.entry(8064, new ManaMaterial("crimson_ingot", 2_000_000L)),
        Map.entry(6613, new ManaMaterial("heroic_manasteel", 3_000_000L)),
        Map.entry(6614, new ManaMaterial("heroic_elementium", 3_000_000L)),
        Map.entry(6615, new ManaMaterial("heroic_alfsteel", 3_000_000L)),
        Map.entry(6617, new ManaMaterial("heroic_alloy", 4_000_000L))
    );

    private final ModuleSetting itemId = setting("ItemId", ModuleSetting.number("ItemId", 0.0D, 0.0D, 250000.0D, 1.0D)
        .describe("Numeric registry id shown by AdvancedTooltip."));
    private final ModuleSetting amount = setting("Amount", ModuleSetting.number("Amount", 1.0D, 1.0D, 1_000_000.0D, 1.0D)
        .describe("Requested output amount."));
    private final ModuleSetting leafMode = setting("LeafMode", ModuleSetting.choice("LeafMode", 0, "Common", "NoRecipe")
        .describe("Common uses Smart Raw for base materials. NoRecipe expands until a terminal recipe."));
    private final ModuleSetting maxDepth = setting("MaxDepth", ModuleSetting.number("MaxDepth", 16.0D, 1.0D, 48.0D, 1.0D)
        .describe("Maximum recursive recipe depth."));
    private final ModuleSetting materialDepth = setting("MaterialDepth", ModuleSetting.number("MaterialDepth", 0.0D, 0.0D, 16.0D, 1.0D)
        .describe("0 = automatic. Otherwise limits how many non-base material recipes expand along one branch."));
    private final ModuleSetting detail = setting("Detail", ModuleSetting.choice("Detail", 0, "Compact", "Full")
        .describe("Compact keeps one-line definitions. Full includes recipe type, serializer and complete choices."));
    private final ModuleSetting alternatives = setting("Alternatives", ModuleSetting.choice("Alternatives", 0, "Summary", "Full", "Off")
        .describe("Summary only reports counts. Full appends every available recipe."));
    private final ModuleSetting noiseFilter = setting("NoiseFilter", ModuleSetting.bool("NoiseFilter", true)
        .describe("Stops reversible packing loops and one-step transmutation chains from polluting the graph."));

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
            expandPlan(context, plan, target, requested, 0, 0, new LinkedHashSet<>(), null);
            calculateTotals(context, target, requested);

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
        Map<Item, List<RecipeEntry>> recipesByOutput = new HashMap<>();
        List<String> indexingErrors = new ArrayList<>();
        Set<String> indexedKeys = new HashSet<>();
        Set<String> managedRecipeIds = new HashSet<>();
        int recipeCount = 0;

        for (Recipe<?> recipe : client.level.getRecipeManager().getRecipes()) {
            recipeCount++;
            try {
                RecipeEntry entry = vanillaEntry(client, recipe);
                if (entry == null) {
                    continue;
                }
                addRecipe(recipesByOutput, indexedKeys, entry);
                if (!entry.inputGroups.isEmpty()) {
                    managedRecipeIds.add(entry.id);
                }
            } catch (RuntimeException | LinkageError error) {
                if (indexingErrors.size() < 64) {
                    indexingErrors.add(safeRecipeId(recipe) + " -> " + error.getClass().getSimpleName()
                        + ": " + clean(error.getMessage()));
                }
            }
        }

        JeiRecipeBridge.ScanResult jeiScan = JeiRecipeBridge.scan(managedRecipeIds);
        for (JeiRecipeBridge.RecipeData data : jeiScan.recipes()) {
            RecipeEntry entry = new RecipeEntry(data.id(), data.station(), "jei:" + data.type(),
                "jei:runtime", data.output().copy(), copyGroups(data.inputs()), true, data.partial());
            addRecipe(recipesByOutput, indexedKeys, entry);
        }

        Comparator<RecipeEntry> byId = Comparator.comparing(RecipeEntry::id);
        recipesByOutput.values().forEach(recipes -> recipes.sort(byId));
        return new ExportContext(client, recipesByOutput, recipeCount, indexingErrors,
            maxDepth.intValue(), leafMode.choiceValue(), detail.choiceValue(), alternatives.choiceValue(),
            noiseFilter.boolValue(), materialDepth.intValue(), jeiScan);
    }

    private RecipeEntry vanillaEntry(Minecraft client, Recipe<?> recipe) {
        ItemStack result = recipe.getResultItem(client.level.registryAccess());
        if (result == null || result.isEmpty() || result.getItem() == Items.AIR) {
            return null;
        }
        List<List<ItemStack>> groups = new ArrayList<>();
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
            if (!options.isEmpty()) {
                groups.add(List.copyOf(options));
            }
        }
        return new RecipeEntry(safeRecipeId(recipe), stationName(recipe), recipeTypeId(recipe),
            recipeSerializerId(recipe), result.copy(), List.copyOf(groups), false, false);
    }

    private void addRecipe(Map<Item, List<RecipeEntry>> recipesByOutput, Set<String> indexedKeys,
                           RecipeEntry entry) {
        ResourceLocation outputId = itemKey(entry.result.getItem());
        String key = outputId + "|" + entry.type + "|" + entry.id;
        if (!indexedKeys.add(key)) {
            return;
        }
        recipesByOutput.computeIfAbsent(entry.result.getItem(), ignored -> new ArrayList<>()).add(entry);
    }

    private List<List<ItemStack>> copyGroups(List<List<ItemStack>> groups) {
        List<List<ItemStack>> copy = new ArrayList<>();
        for (List<ItemStack> group : groups) {
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack stack : group) {
                if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                    options.add(stack.copy());
                }
            }
            if (!options.isEmpty()) {
                copy.add(List.copyOf(options));
            }
        }
        return List.copyOf(copy);
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
        output.append("Material depth: ").append(context.materialDepth == 0 ? "Auto" : context.materialDepth)
            .append(System.lineSeparator());
        output.append("Detail: ").append(context.detailMode).append(System.lineSeparator());
        output.append("Alternatives: ").append(context.alternativesMode).append(System.lineSeparator());
        output.append("Noise filter: ").append(context.noiseFilter ? "ON" : "OFF").append(System.lineSeparator());
        output.append("Indexed recipes: ").append(context.recipeCount).append(System.lineSeparator());
        output.append("JEI-only recipes: ").append(context.jeiScan.recipes().size()).append(System.lineSeparator());
        output.append(System.lineSeparator());
        output.append("Selection rules:").append(System.lineSeparator());
        output.append("- Recipes are scored before their ID is used as a deterministic tie breaker.").append(System.lineSeparator());
        output.append("- Deconstruction, recycling, decorative conversion and reversible cycles are deprioritized.")
            .append(System.lineSeparator());
        output.append("- Ingredient alternatives prefer common raw items, vanilla base items and simple variants.")
            .append(System.lineSeparator());
        output.append("- Common uses Smart Raw: elemental materials stop, while trusted mod processing chains expand.")
            .append(System.lineSeparator());
        output.append("- Quest, loot-fabrication and trading routes are fallback acquisition paths, not preferred crafting.")
            .append(System.lineSeparator());
        output.append("- Recipes exposed only through JEI are merged with synchronized RecipeManager recipes.")
            .append(System.lineSeparator());
        output.append("- Known mana materials are charged at every expanded stage and reported separately.")
            .append(System.lineSeparator());
    }

    private void expandPlan(ExportContext context, StringBuilder output, Item item, long required, int depth,
                            int expandedMaterials, Set<Item> ancestors, Item parent) {
        context.nodes++;
        int id = nodeId(context, item);
        boolean cycle = ancestors.contains(item);
        if (cycle && parent != null) {
            context.cycleEdges.add(new RecipeEdge(parent, item));
        }
        boolean repeated = context.renderedItems.contains(item);
        if (repeated) {
            appendPlanReference(output, id, item, required, depth, cycle);
            context.collapsedReferences++;
            if (cycle) {
                context.cycleStops++;
            }
            return;
        }
        context.renderedItems.add(item);

        if (context.nodes > MAX_GRAPH_NODES) {
            registerLeaf(context, item, "NODE LIMIT");
            appendPlanLeaf(output, id, item, required, depth, "NODE LIMIT");
            context.nodeLimitStops++;
            return;
        }
        if (depth >= context.maxDepth) {
            registerLeaf(context, item, "MAX DEPTH");
            appendPlanLeaf(output, id, item, required, depth, "MAX DEPTH");
            context.depthStops++;
            return;
        }
        boolean smartMaterial = isMaterialCandidate(item) && !isForcedBaseRaw(item);
        if (context.materialDepth > 0 && smartMaterial && expandedMaterials >= context.materialDepth) {
            registerLeaf(context, item, "MATERIAL DEPTH");
            appendPlanLeaf(output, id, item, required, depth, "MATERIAL DEPTH");
            context.materialDepthStops++;
            return;
        }
        if ("Common".equals(context.leafMode) && shouldStopAtCommonRaw(context, item)) {
            registerLeaf(context, item, "COMMON RAW");
            appendPlanLeaf(output, id, item, required, depth, "COMMON RAW");
            context.commonStops++;
            return;
        }
        if (cycle) {
            registerLeaf(context, item, "CYCLE");
            appendPlanLeaf(output, id, item, required, depth, "CYCLE");
            context.cycleStops++;
            return;
        }

        List<RecipeEntry> recipes = context.recipesByOutput.getOrDefault(item, List.of());
        if (recipes.isEmpty()) {
            registerLeaf(context, item, "NO RECIPE");
            appendPlanLeaf(output, id, item, required, depth, "NO RECIPE");
            context.noRecipeStops++;
            return;
        }

        RecipePlan recipePlan = selectedPlan(context, item, recipes);
        if (recipePlan == null || recipePlan.result.isEmpty() || recipePlan.ingredients.isEmpty()) {
            String reason = context.noiseFilteredOutputs.contains(item)
                ? "FILTERED CONVERSION" : "UNSUPPORTED/EMPTY RECIPE";
            registerLeaf(context, item, reason);
            appendPlanLeaf(output, id, item, required, depth, reason);
            if (context.noiseFilteredOutputs.contains(item)) {
                context.filteredStops++;
            } else {
                context.unsupportedStops++;
            }
            return;
        }

        registerRecipe(context, item, recipePlan, recipes.size() - 1);
        int outputCount = Math.max(1, recipePlan.result.getCount());
        long crafts = ceilDiv(required, outputCount);
        appendPlanRecipe(output, id, item, required, depth, recipePlan.recipe.station, crafts);

        Set<Item> nextAncestors = new LinkedHashSet<>(ancestors);
        nextAncestors.add(item);
        int nextMaterialDepth = expandedMaterials + (smartMaterial ? 1 : 0);
        for (IngredientPlan ingredient : recipePlan.ingredients) {
            long ingredientAmount = safeMultiply(crafts, ingredient.count);
            expandPlan(context, output, ingredient.item, ingredientAmount, depth + 1,
                nextMaterialDepth, nextAncestors, item);
        }
    }

    private void calculateTotals(ExportContext context, Item target, long requested) {
        Deque<CalculationState> queue = new ArrayDeque<>();
        Set<CalculationState> queued = new HashSet<>();
        Map<CalculationState, Long> demand = new HashMap<>();
        Map<CalculationState, Long> processedRequired = new HashMap<>();
        Map<CalculationState, Long> processedCrafts = new HashMap<>();

        addDemand(context, queue, queued, demand, new CalculationState(target, 0, 0), requested);
        while (!queue.isEmpty()) {
            CalculationState state = queue.removeFirst();
            queued.remove(state);
            long totalRequired = demand.getOrDefault(state, 0L);
            long previousRequired = processedRequired.getOrDefault(state, 0L);
            if (totalRequired <= previousRequired) {
                continue;
            }

            long requiredDelta = totalRequired - previousRequired;
            processedRequired.put(state, totalRequired);
            context.calculatedUnits = safeAdd(context.calculatedUnits, requiredDelta);
            recordMana(context, state.item, requiredDelta);

            if (state.depth >= context.maxDepth) {
                registerLeaf(context, state.item, "MAX DEPTH");
                recordRaw(context, state.item, requiredDelta);
                continue;
            }
            boolean smartMaterial = isMaterialCandidate(state.item) && !isForcedBaseRaw(state.item);
            if (context.materialDepth > 0 && smartMaterial
                && state.expandedMaterials >= context.materialDepth) {
                registerLeaf(context, state.item, "MATERIAL DEPTH");
                recordRaw(context, state.item, requiredDelta);
                continue;
            }
            if ("Common".equals(context.leafMode) && shouldStopAtCommonRaw(context, state.item)) {
                registerLeaf(context, state.item, "COMMON RAW");
                recordRaw(context, state.item, requiredDelta);
                continue;
            }

            List<RecipeEntry> recipes = context.recipesByOutput.getOrDefault(state.item, List.of());
            if (recipes.isEmpty()) {
                registerLeaf(context, state.item, "NO RECIPE");
                recordRaw(context, state.item, requiredDelta);
                continue;
            }
            RecipePlan recipePlan = selectedPlan(context, state.item, recipes);
            if (recipePlan == null || recipePlan.result.isEmpty() || recipePlan.ingredients.isEmpty()) {
                String reason = context.noiseFilteredOutputs.contains(state.item)
                    ? "FILTERED CONVERSION" : "UNSUPPORTED/EMPTY RECIPE";
                registerLeaf(context, state.item, reason);
                recordRaw(context, state.item, requiredDelta);
                continue;
            }
            registerRecipe(context, state.item, recipePlan, recipes.size() - 1);

            long totalCrafts = ceilDiv(totalRequired, Math.max(1, recipePlan.result.getCount()));
            long previousCraftCount = processedCrafts.getOrDefault(state, 0L);
            if (totalCrafts <= previousCraftCount) {
                continue;
            }
            long craftDelta = totalCrafts - previousCraftCount;
            processedCrafts.put(state, totalCrafts);
            int nextMaterialDepth = state.expandedMaterials + (smartMaterial ? 1 : 0);
            for (IngredientPlan ingredient : recipePlan.ingredients) {
                long ingredientAmount = safeMultiply(craftDelta, ingredient.count);
                if (context.cycleEdges.contains(new RecipeEdge(state.item, ingredient.item))) {
                    context.cycleTotals.merge(ingredient.item, ingredientAmount, RecipeGraphModule::safeAdd);
                    continue;
                }
                addDemand(context, queue, queued, demand,
                    new CalculationState(ingredient.item, state.depth + 1, nextMaterialDepth), ingredientAmount);
            }
        }
        context.calculationStates = processedRequired.size();
    }

    private void addDemand(ExportContext context, Deque<CalculationState> queue, Set<CalculationState> queued,
                           Map<CalculationState, Long> demand, CalculationState state, long amount) {
        if (amount <= 0L) {
            return;
        }
        Long previous = demand.put(state, safeAdd(demand.getOrDefault(state, 0L), amount));
        if (previous != null) {
            context.aggregatedDemands++;
        }
        if (queued.add(state)) {
            queue.addLast(state);
        }
    }

    private void recordMana(ExportContext context, Item item, long amount) {
        ManaMaterial material = MANA_MATERIALS.get(BuiltInRegistries.ITEM.getId(item));
        if (material == null || amount <= 0L) {
            return;
        }
        context.manaUnits.merge(item, amount, RecipeGraphModule::safeAdd);
        context.totalMana = safeAdd(context.totalMana, safeMultiply(amount, material.manaPerItem));
    }

    private RecipePlan selectedPlan(ExportContext context, Item item, List<RecipeEntry> recipes) {
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

    private RecipePlan selectRecipe(ExportContext context, Item output, List<RecipeEntry> recipes) {
        RecipePlan best = null;
        long bestScore = Long.MAX_VALUE;
        Set<Item> blockedIngredients = Set.of(output);
        int filtered = 0;
        for (RecipeEntry recipe : recipes) {
            try {
                List<IngredientPlan> ingredients = planIngredients(context, recipe, blockedIngredients, output);
                if (context.noiseFilter && isObviousNoise(context, output, recipe, ingredients)) {
                    filtered++;
                    context.filteredRecipes++;
                    continue;
                }
                RecipePlan candidate = new RecipePlan(recipe, recipe.result.copy(), ingredients);
                long score = recipeScore(context, output, candidate);
                if (best == null || score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            } catch (RuntimeException | LinkageError error) {
                if (context.expansionErrors.size() < 64) {
                    context.expansionErrors.add(recipe.id + " -> " + error.getClass().getSimpleName()
                        + ": " + clean(error.getMessage()));
                }
            }
        }
        if (best == null && filtered > 0) {
            context.noiseFilteredOutputs.add(output);
        }
        return best;
    }

    private boolean isObviousNoise(ExportContext context, Item output, RecipeEntry recipe,
                                   List<IngredientPlan> ingredients) {
        String route = routeText(recipe);
        if (recipe.jeiOnly && isQuestRoute(route)) {
            return true;
        }
        if (route.contains("deconstruction") || route.contains("deconstruct")
            || route.contains("recycling") || route.contains("recycle")) {
            return true;
        }
        if (ingredients.size() == 1) {
            if (route.contains("transmutation")) {
                return true;
            }
            if (isPackingConversionRoute(route)
                || ((route.contains("_to_") || route.contains("/to_"))
                    && reverseCyclePenalty(context, output, ingredients) > 0)) {
                return true;
            }
        }
        return false;
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
        Set<Item> visiting = new HashSet<>();
        visiting.add(output);
        long recursiveCost = 0L;
        for (IngredientPlan ingredient : plan.ingredients) {
            long branch = estimateItemComplexity(context, output, ingredient.item,
                COMPLEXITY_LOOKAHEAD, visiting);
            recursiveCost = safeAdd(recursiveCost, safeMultiply(ingredient.count, branch));
        }
        score = safeAdd(score, safeMultiply(Math.min(5_000_000L, recursiveCost), 10_000L));
        score = safeAdd(score, plan.ingredients.size() * 100L);
        score = safeAdd(score, Math.min(10_000L, ingredientUnits));
        score -= Math.min(99, Math.max(1, plan.result.getCount()));
        return score;
    }

    private int recipeRoutePenalty(RecipeEntry recipe) {
        String route = routeText(recipe);
        int penalty = 0;
        if (isQuestRoute(route)) penalty += 100_000;
        if (isAcquisitionRoute(route)) penalty += 25_000;
        if (route.contains("deconstruction") || route.contains("deconstruct")) penalty += 8_000;
        if (route.contains("recycling") || route.contains("recycle")) penalty += 7_000;
        if (isPackingConversionRoute(route)) penalty += 4_000;
        if (route.contains("transmutation")) penalty += 3_500;
        if (route.contains("nugget_from_blasting") || route.contains("nugget_from_smelting")) penalty += 4_500;
        if (route.contains("pebble_to") || route.contains("cobble_to_pebble")) penalty += 2_000;
        if (route.contains("stonecut") || route.contains("stonecutting")) penalty += 900;
        if (route.contains("stairs") || route.contains("slab") || route.contains("wall")) penalty += 500;
        return penalty;
    }

    private String routeText(RecipeEntry recipe) {
        return (recipe.id + " " + recipe.type + " " + recipe.station).toLowerCase(Locale.ROOT);
    }

    private boolean isQuestRoute(String route) {
        return route.contains("ftbquests") || route.contains("ftb quests")
            || route.contains("quest_reward") || route.contains("quest reward");
    }

    private boolean isAcquisitionRoute(String route) {
        return route.contains("hostilenetworks_loot_fabricator")
            || route.contains("hostilenetworks_sim_chamber")
            || route.contains("loot_fabricator")
            || route.contains("loot table") || route.contains("loot_table")
            || route.contains("mob drop") || route.contains("mob_drop")
            || route.contains("entity drop") || route.contains("entity_drop")
            || route.contains("trading") || route.contains("merchant");
    }

    private boolean isPackingConversionRoute(String route) {
        return route.contains("uncompress") || route.contains("unpack")
            || route.contains("from_storage") || route.contains("from_block")
            || route.contains("block_to_") || route.contains("from_nugget")
            || route.contains("nugget_to_") || route.contains("storage_block");
    }

    private long estimateItemComplexity(ExportContext context, Item root, Item item, int remaining,
                                        Set<Item> visiting) {
        if (isForcedBaseRaw(item)) {
            return 1L;
        }
        if (remaining <= 0) {
            return 100L;
        }
        if (visiting.contains(item)) {
            return 1_000_000L;
        }
        ComplexityKey key = new ComplexityKey(root, item, remaining);
        Long cached = context.complexityCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<RecipeEntry> recipes = context.recipesByOutput.getOrDefault(item, List.of());
        if (recipes.isEmpty()) {
            context.complexityCache.put(key, 20L);
            return 20L;
        }

        visiting.add(item);
        long best = Long.MAX_VALUE;
        try {
            for (RecipeEntry recipe : recipes) {
                List<IngredientPlan> ingredients = planIngredients(context, recipe, visiting, item);
                if (ingredients.isEmpty() || (context.noiseFilter && isObviousNoise(context, item, recipe, ingredients))) {
                    continue;
                }
                long cost = safeAdd(10L, safeMultiply(recipeRoutePenalty(recipe), 1_000L));
                for (IngredientPlan ingredient : ingredients) {
                    long child = estimateItemComplexity(context, root, ingredient.item, remaining - 1, visiting);
                    cost = safeAdd(cost, safeMultiply(ingredient.count, child));
                }
                cost = ceilDiv(cost, Math.max(1, recipe.result.getCount()));
                best = Math.min(best, cost);
            }
        } finally {
            visiting.remove(item);
        }
        long result = best == Long.MAX_VALUE ? 50L : Math.min(5_000_000L, best);
        context.complexityCache.put(key, result);
        return result;
    }

    private int reverseCyclePenalty(ExportContext context, Item output, List<IngredientPlan> ingredients) {
        int penalty = 0;
        for (IngredientPlan ingredient : ingredients) {
            if (isForcedBaseRaw(ingredient.item)) {
                continue;
            }
            for (RecipeEntry reverse : context.recipesByOutput.getOrDefault(ingredient.item, List.of())) {
                if (recipeContainsItem(context, reverse, output)) {
                    penalty++;
                    break;
                }
            }
        }
        return penalty;
    }

    private boolean recipeContainsItem(ExportContext context, RecipeEntry recipe, Item item) {
        for (List<ItemStack> group : recipe.inputGroups) {
            for (ItemStack stack : group) {
                if (stack.getItem() == item) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<IngredientPlan> planIngredients(ExportContext context, RecipeEntry recipe, Set<Item> blocked, Item output) {
        Map<Item, MutableIngredientPlan> grouped = new LinkedHashMap<>();
        for (List<ItemStack> group : recipe.inputGroups) {
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack stack : group) {
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
        if (isForcedBaseRaw(candidate)) {
            score -= 400;
        } else if (isCommonRaw(candidate)) {
            score -= 40;
        }
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
            output.append("  via: ").append(plan.recipe.station).append(" | ").append(plan.recipe.id)
                .append(" | output x").append(Math.max(1, plan.result.getCount()));
            if (plan.recipe.jeiOnly) {
                output.append(" | JEI");
            }
            if (plan.recipe.partial) {
                output.append(" | PARTIAL");
            }
            output.append(System.lineSeparator());
            if ("Full".equals(context.detailMode)) {
                output.append("  type: ").append(plan.recipe.type)
                    .append(" | serializer: ").append(plan.recipe.serializer).append(System.lineSeparator());
                if (plan.recipe.jeiOnly) {
                    output.append("  source: JEI-only");
                    if (plan.recipe.partial) {
                        output.append(" (non-item inputs omitted)");
                    }
                    output.append(System.lineSeparator());
                }
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

    private void appendFullAlternatives(ExportContext context, Item item, RecipeEntry selected,
                                        List<RecipeEntry> recipes) {
        context.fullAlternatives.append(System.lineSeparator()).append(itemLabel(item)).append(System.lineSeparator());
        int shown = 0;
        for (RecipeEntry recipe : recipes) {
            if (shown++ >= MAX_RECIPE_ALTERNATIVES) {
                context.fullAlternatives.append("  ... ").append(recipes.size() - MAX_RECIPE_ALTERNATIVES)
                    .append(" more recipe(s)").append(System.lineSeparator());
                break;
            }
            context.fullAlternatives.append(recipe == selected ? "  * SELECTED " : "  - ")
                .append(recipe.id).append(System.lineSeparator());
            context.fullAlternatives.append("      via: ").append(recipe.station)
                .append(" | type: ").append(recipe.type)
                .append(" | output: ").append(recipe.result.isEmpty() ? "unknown" : recipe.result.getCount())
                .append(System.lineSeparator());
            context.fullAlternatives.append("      inputs: ").append(recipeInputSummary(recipe)).append(System.lineSeparator());
        }
    }

    private void appendTotals(StringBuilder output, ExportContext context) {
        output.append(System.lineSeparator()).append("RAW / TERMINAL MATERIAL TOTALS").append(System.lineSeparator());
        output.append("===============================").append(System.lineSeparator());
        if (context.rawTotals.isEmpty()) {
            output.append("(none)").append(System.lineSeparator());
        } else {
            context.rawTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(item -> String.valueOf(itemKey(item)))))
                .forEach(entry -> output.append("- ").append(itemLabel(entry.getKey()))
                    .append(" x").append(entry.getValue()).append(System.lineSeparator()));
        }
        if (!context.cycleTotals.isEmpty()) {
            output.append(System.lineSeparator()).append("UNRESOLVED CYCLE REFERENCES").append(System.lineSeparator());
            output.append("===========================").append(System.lineSeparator());
            context.cycleTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(item -> String.valueOf(itemKey(item)))))
                .forEach(entry -> output.append("- ").append(itemLabel(entry.getKey()))
                    .append(" x").append(entry.getValue()).append(System.lineSeparator()));
        }

        output.append(System.lineSeparator()).append("MANA COST TOTALS").append(System.lineSeparator());
        output.append("================").append(System.lineSeparator());
        output.append("Known mana costs are counted at every expanded recipe stage.")
            .append(System.lineSeparator());
        if (context.manaUnits.isEmpty()) {
            output.append("(no configured mana materials used)").append(System.lineSeparator());
        } else {
            context.manaUnits.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(BuiltInRegistries.ITEM::getId)))
                .forEach(entry -> {
                    int numericId = BuiltInRegistries.ITEM.getId(entry.getKey());
                    ManaMaterial material = MANA_MATERIALS.get(numericId);
                    long subtotal = safeMultiply(entry.getValue(), material.manaPerItem);
                    output.append("- ").append(material.name)
                        .append(" [").append(itemKey(entry.getKey())).append("; numeric=").append(numericId).append(']')
                        .append(" x").append(entry.getValue())
                        .append(" @ ").append(formatLong(material.manaPerItem))
                        .append(" = ").append(formatLong(subtotal)).append(" mana")
                        .append(System.lineSeparator());
                });
        }
        output.append("TOTAL MANA: ").append(formatLong(context.totalMana)).append(System.lineSeparator());
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
        output.append("Aggregated calculation states: ").append(context.calculationStates)
            .append(System.lineSeparator());
        output.append("Merged repeated demands: ").append(context.aggregatedDemands).append(System.lineSeparator());
        output.append("Calculated item units: ").append(context.calculatedUnits).append(System.lineSeparator());
        output.append("Recipe choices changed by scoring: ").append(context.scoredRecipeChanges).append(System.lineSeparator());
        output.append("Noise-filtered recipes: ").append(context.filteredRecipes).append(System.lineSeparator());
        output.append("Noise-filtered terminal items: ").append(context.filteredStops).append(System.lineSeparator());
        output.append("Common raw stops: ").append(context.commonStops).append(System.lineSeparator());
        output.append("No recipe stops: ").append(context.noRecipeStops).append(System.lineSeparator());
        output.append("Maximum depth stops: ").append(context.depthStops).append(System.lineSeparator());
        output.append("Material depth stops: ").append(context.materialDepthStops).append(System.lineSeparator());
        output.append("Cycle stops: ").append(context.cycleStops).append(System.lineSeparator());
        output.append("Unsupported/empty stops: ").append(context.unsupportedStops).append(System.lineSeparator());
        output.append("Node limit stops: ").append(context.nodeLimitStops).append(System.lineSeparator());
        output.append("Ingredient choices: ").append(context.ingredientChoices).append(System.lineSeparator());
        output.append("Alternative recipe groups: ").append(context.alternativeGroups).append(System.lineSeparator());
        output.append("JEI categories scanned: ").append(context.jeiScan.categories()).append(System.lineSeparator());
        output.append("JEI recipes inspected: ").append(context.jeiScan.inspectedRecipes()).append(System.lineSeparator());
        output.append("JEI RecipeManager duplicates skipped: ").append(context.jeiScan.skippedVanillaRecipes())
            .append(System.lineSeparator());
        output.append("JEI-only recipes indexed: ").append(context.jeiScan.recipes().size()).append(System.lineSeparator());
        output.append("JEI partial recipes: ").append(context.jeiScan.partialRecipes()).append(System.lineSeparator());
        output.append("JEI non-item slots: ").append(context.jeiScan.nonItemSlots()).append(System.lineSeparator());
        output.append("JEI focused category details: ").append(context.jeiScan.focusedCategories().size())
            .append(System.lineSeparator());
        for (String category : context.jeiScan.focusedCategories()) {
            output.append("- ").append(category).append(System.lineSeparator());
        }
        appendErrors(output, "Recipe indexing errors", context.indexingErrors);
        appendErrors(output, "Recipe expansion errors", context.expansionErrors);
        appendErrors(output, "JEI bridge errors", context.jeiScan.errors());
    }

    private void appendErrors(StringBuilder output, String title, List<String> errors) {
        output.append(title).append(": ").append(errors.size()).append(System.lineSeparator());
        for (String error : errors) {
            output.append("  - ").append(error).append(System.lineSeparator());
        }
    }

    private String recipeInputSummary(RecipeEntry recipe) {
        List<String> inputs = new ArrayList<>();
        for (List<ItemStack> group : recipe.inputGroups) {
            List<Item> options = new ArrayList<>();
            int count = 1;
            for (ItemStack stack : group) {
                if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
                    options.add(stack.getItem());
                    count = Math.max(count, stack.getCount());
                }
            }
            if (!options.isEmpty()) {
                inputs.add(formatItems(options, 5) + " x" + count);
            }
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
            || path.startsWith("ingot_")
            || path.endsWith("_gem")
            || path.startsWith("gem_")
            || path.endsWith("_dust")
            || path.startsWith("dust_")
            || path.endsWith("_ore")
            || path.startsWith("raw_")
            || path.endsWith("_raw_material");
    }

    private boolean shouldStopAtCommonRaw(ExportContext context, Item item) {
        if (isForcedBaseRaw(item)) {
            return true;
        }
        if (!isMaterialCandidate(item)) {
            return false;
        }
        return !hasTrustedManufacturingRecipe(context, item);
    }

    private boolean isForcedBaseRaw(Item item) {
        ResourceLocation id = itemKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (COMMON_RAW_NAMES.contains(path) || path.endsWith("_ore") || path.startsWith("raw_")
            || path.endsWith("_raw_material")) {
            return true;
        }
        String stem = materialStem(path);
        return stem != null && BASE_ELEMENT_NAMES.contains(stem);
    }

    private boolean isMaterialCandidate(Item item) {
        ResourceLocation id = itemKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return isCommonRaw(item) || path.contains("alloy") || path.endsWith("_steel")
            || path.endsWith("_metal") || path.endsWith("_plate");
    }

    private String materialStem(String path) {
        String[] prefixes = {"ingot_", "dust_", "gem_"};
        for (String prefix : prefixes) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return path.substring(prefix.length());
            }
        }
        String[] suffixes = {"_ingot", "_dust", "_gem"};
        for (String suffix : suffixes) {
            if (path.endsWith(suffix) && path.length() > suffix.length()) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return null;
    }

    private boolean hasTrustedManufacturingRecipe(ExportContext context, Item item) {
        for (RecipeEntry recipe : context.recipesByOutput.getOrDefault(item, List.of())) {
            String route = routeText(recipe);
            if (isQuestRoute(route) || isAcquisitionRoute(route) || isPackingConversionRoute(route)
                || route.contains("deconstruct") || route.contains("recycl")
                || route.contains("transmutation")) {
                continue;
            }
            boolean hasDifferentInput = false;
            for (List<ItemStack> group : recipe.inputGroups) {
                for (ItemStack stack : group) {
                    if (stack != null && !stack.isEmpty() && stack.getItem() != item) {
                        hasDifferentInput = true;
                        break;
                    }
                }
                if (hasDifferentInput) {
                    break;
                }
            }
            if (hasDifferentInput) {
                return true;
            }
        }
        return false;
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

    private String formatLong(long value) {
        return String.format(Locale.ROOT, "%,d", value);
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
            + " materialDepth=" + (materialDepth.intValue() == 0 ? "Auto" : materialDepth.displayValue())
            + " detail=" + detail.choiceValue()
            + " alternatives=" + alternatives.choiceValue()
            + " noiseFilter=" + noiseFilter.boolValue();
    }

    private record RecipeEntry(String id, String station, String type, String serializer,
                               ItemStack result, List<List<ItemStack>> inputGroups,
                               boolean jeiOnly, boolean partial) {
    }

    private record RecipePlan(RecipeEntry recipe, ItemStack result, List<IngredientPlan> ingredients) {
    }

    private record IngredientPlan(Item item, long count, List<Item> alternatives) {
    }

    private record RecipeDefinition(Item item, RecipePlan plan, String leafReason, int alternativeCount) {
    }

    private record ComplexityKey(Item root, Item item, int remaining) {
    }

    private record RecipeEdge(Item from, Item to) {
    }

    private record CalculationState(Item item, int depth, int expandedMaterials) {
    }

    private record ManaMaterial(String name, long manaPerItem) {
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
        private final Map<Item, List<RecipeEntry>> recipesByOutput;
        private final int recipeCount;
        private final List<String> indexingErrors;
        private final List<String> expansionErrors = new ArrayList<>();
        private final int maxDepth;
        private final String leafMode;
        private final String detailMode;
        private final String alternativesMode;
        private final boolean noiseFilter;
        private final int materialDepth;
        private final JeiRecipeBridge.ScanResult jeiScan;
        private final Map<Item, Long> rawTotals = new HashMap<>();
        private final Map<Item, Long> cycleTotals = new HashMap<>();
        private final Map<Item, Long> manaUnits = new HashMap<>();
        private final Map<Item, Integer> nodeIds = new LinkedHashMap<>();
        private final Map<Item, RecipeDefinition> definitions = new LinkedHashMap<>();
        private final Map<Item, RecipePlan> selectedPlans = new HashMap<>();
        private final Map<ComplexityKey, Long> complexityCache = new HashMap<>();
        private final Set<Item> renderedItems = new HashSet<>();
        private final Set<Item> reportedAlternativeItems = new HashSet<>();
        private final Set<Item> noiseFilteredOutputs = new HashSet<>();
        private final Set<RecipeEdge> cycleEdges = new HashSet<>();
        private final StringBuilder fullAlternatives = new StringBuilder();
        private int nodes;
        private int collapsedReferences;
        private int scoredRecipeChanges;
        private int filteredRecipes;
        private int filteredStops;
        private int commonStops;
        private int noRecipeStops;
        private int depthStops;
        private int materialDepthStops;
        private int cycleStops;
        private int unsupportedStops;
        private int nodeLimitStops;
        private int ingredientChoices;
        private int alternativeGroups;
        private int calculationStates;
        private int aggregatedDemands;
        private long calculatedUnits;
        private long totalMana;

        private ExportContext(Minecraft client, Map<Item, List<RecipeEntry>> recipesByOutput, int recipeCount,
                              List<String> indexingErrors, int maxDepth, String leafMode,
                              String detailMode, String alternativesMode, boolean noiseFilter,
                              int materialDepth, JeiRecipeBridge.ScanResult jeiScan) {
            this.client = client;
            this.recipesByOutput = recipesByOutput;
            this.recipeCount = recipeCount;
            this.indexingErrors = indexingErrors;
            this.maxDepth = maxDepth;
            this.leafMode = leafMode;
            this.detailMode = detailMode;
            this.alternativesMode = alternativesMode;
            this.noiseFilter = noiseFilter;
            this.materialDepth = materialDepth;
            this.jeiScan = jeiScan;
        }
    }
}
