package dev.chatty.localai;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small, deterministic starter crafting layer for the autonomous prototype.
 * It intentionally supports a focused set of early-game utility recipes rather
 * than trying to emulate the full vanilla recipe manager in v0.2.
 */
public final class SimpleRecipeBook {
    private SimpleRecipeBook() {}

    private static final Map<String, String> LOG_TO_PLANKS = Map.ofEntries(
            Map.entry("minecraft:oak_log", "minecraft:oak_planks"),
            Map.entry("minecraft:spruce_log", "minecraft:spruce_planks"),
            Map.entry("minecraft:birch_log", "minecraft:birch_planks"),
            Map.entry("minecraft:jungle_log", "minecraft:jungle_planks"),
            Map.entry("minecraft:acacia_log", "minecraft:acacia_planks"),
            Map.entry("minecraft:dark_oak_log", "minecraft:dark_oak_planks"),
            Map.entry("minecraft:mangrove_log", "minecraft:mangrove_planks"),
            Map.entry("minecraft:cherry_log", "minecraft:cherry_planks"),
            Map.entry("minecraft:crimson_stem", "minecraft:crimson_planks"),
            Map.entry("minecraft:warped_stem", "minecraft:warped_planks")
    );

    public static String supportedRecipes() {
        return "matching logs->planks, sticks, crafting_table, wooden_pickaxe, stone_pickaxe, iron_pickaxe, furnace, torches";
    }

    public static CraftResult craft(AgentInventory inv, String requestedId, int requestedCount) {
        String out = normalize(requestedId);
        int count = Math.max(1, Math.min(64, requestedCount));

        for (Map.Entry<String, String> entry : LOG_TO_PLANKS.entrySet()) {
            if (entry.getValue().equals(out)) {
                int crafts = (int) Math.ceil(count / 4.0);
                if (!consumeExact(inv, entry.getKey(), crafts)) {
                    return CraftResult.fail("Need " + crafts + " " + entry.getKey());
                }
                return give(inv, out, crafts * 4);
            }
        }

        return switch (out) {
            case "minecraft:stick" -> craftFromAnyPlanks(inv, out, count, 2, 4);
            case "minecraft:crafting_table" -> craftFromAnyPlanks(inv, out, count, 4, 1);
            case "minecraft:wooden_pickaxe" -> craftTool(inv, out, count, "planks", 3, 2);
            case "minecraft:stone_pickaxe" -> craftTool(inv, out, count, "minecraft:cobblestone", 3, 2);
            case "minecraft:iron_pickaxe" -> craftTool(inv, out, count, "minecraft:iron_ingot", 3, 2);
            case "minecraft:furnace" -> craftExact(inv, out, count, Map.of("minecraft:cobblestone", 8), 1);
            case "minecraft:torch" -> craftTorch(inv, count);
            default -> CraftResult.fail("Unsupported starter recipe: " + out + ". Supported: " + supportedRecipes());
        };
    }

    private static CraftResult craftFromAnyPlanks(AgentInventory inv, String out, int requested, int planksPerCraft, int outputPerCraft) {
        int crafts = (int) Math.ceil(requested / (double) outputPerCraft);
        int need = crafts * planksPerCraft;
        if (!consumeAnyPlanks(inv, need)) return CraftResult.fail("Need " + need + " planks");
        return give(inv, out, crafts * outputPerCraft);
    }

    private static CraftResult craftTool(AgentInventory inv, String out, int requested, String material, int materialCount, int stickCount) {
        int crafts = requested;
        int materialNeeded = crafts * materialCount;
        int sticksNeeded = crafts * stickCount;

        int availableMaterial = material.equals("planks")
                ? inv.entries().stream().filter(e -> e.getKey().toString().endsWith("_planks")).mapToInt(Map.Entry::getValue).sum()
                : inv.count(material);
        if (availableMaterial < materialNeeded) return CraftResult.fail("Not enough material for " + out);
        if (inv.count("minecraft:stick") < sticksNeeded) return CraftResult.fail("Need " + sticksNeeded + " sticks for " + out);

        if (material.equals("planks")) consumeAnyPlanks(inv, materialNeeded);
        else consumeExact(inv, material, materialNeeded);
        consumeExact(inv, "minecraft:stick", sticksNeeded);
        return give(inv, out, crafts);
    }

    private static CraftResult craftExact(AgentInventory inv, String out, int requested, Map<String, Integer> ingredients, int outputPerCraft) {
        int crafts = (int) Math.ceil(requested / (double) outputPerCraft);
        for (Map.Entry<String, Integer> ingredient : ingredients.entrySet()) {
            if (inv.count(ingredient.getKey()) < ingredient.getValue() * crafts) {
                return CraftResult.fail("Need " + (ingredient.getValue() * crafts) + " " + ingredient.getKey());
            }
        }
        for (Map.Entry<String, Integer> ingredient : ingredients.entrySet()) {
            inv.remove(ingredient.getKey(), ingredient.getValue() * crafts);
        }
        return give(inv, out, crafts * outputPerCraft);
    }

    private static CraftResult craftTorch(AgentInventory inv, int requested) {
        int crafts = (int) Math.ceil(requested / 4.0);
        if (inv.count("minecraft:stick") < crafts) return CraftResult.fail("Need " + crafts + " sticks");
        String fuel = inv.count("minecraft:coal") >= crafts ? "minecraft:coal"
                : inv.count("minecraft:charcoal") >= crafts ? "minecraft:charcoal" : null;
        if (fuel == null) return CraftResult.fail("Need coal or charcoal");
        inv.remove("minecraft:stick", crafts);
        inv.remove(fuel, crafts);
        return give(inv, "minecraft:torch", crafts * 4);
    }

    private static boolean consumeExact(AgentInventory inv, String id, int amount) {
        return inv.remove(id, amount);
    }

    private static boolean consumeAnyPlanks(AgentInventory inv, int amount) {
        int total = inv.entries().stream()
                .filter(e -> e.getKey().toString().endsWith("_planks"))
                .mapToInt(Map.Entry::getValue)
                .sum();
        if (total < amount) return false;

        int left = amount;
        for (Map.Entry<Identifier, Integer> entry : new ArrayList<>(inv.entries())) {
            if (!entry.getKey().toString().endsWith("_planks")) continue;
            int take = Math.min(left, entry.getValue());
            inv.remove(entry.getKey(), take);
            left -= take;
            if (left <= 0) break;
        }
        return true;
    }


    private static void addItem(AgentInventory inv, String id, int count) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) return;
        Item item = Registries.ITEM.get(parsed);
        if (item != null) inv.add(item, count);
    }

    private static CraftResult give(AgentInventory inv, String id, int count) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !Registries.ITEM.containsId(parsed)) return CraftResult.fail("Unknown item: " + id);
        Item item = Registries.ITEM.get(parsed);
        inv.add(item, count);
        return CraftResult.ok("Crafted " + count + " " + id);
    }

    private static String normalize(String id) {
        if (id == null) return "";
        String value = id.trim().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    public record CraftResult(boolean success, String message) {
        public static CraftResult ok(String message) { return new CraftResult(true, message); }
        public static CraftResult fail(String message) { return new CraftResult(false, message); }
    }
}
