package dev.chatty.localai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentInventory {
    private final Map<Identifier, Integer> counts = new LinkedHashMap<>();
    private Identifier equipped;

    public synchronized void clear() {
        counts.clear();
        equipped = null;
    }

    public synchronized void add(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return;
        counts.merge(id, stack.getCount(), Integer::sum);
    }

    public synchronized void add(Item item, int count) {
        if (item == null || count <= 0) return;
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return;
        counts.merge(id, count, Integer::sum);
    }

    public synchronized int count(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        return id == null ? 0 : counts.getOrDefault(id, 0);
    }

    public synchronized int count(Identifier id) {
        return counts.getOrDefault(id, 0);
    }

    public synchronized boolean remove(String itemId, int amount) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return false;
        return remove(id, amount);
    }

    public synchronized boolean remove(Identifier id, int amount) {
        if (amount <= 0) return true;
        int have = counts.getOrDefault(id, 0);
        if (have < amount) return false;
        int left = have - amount;
        if (left <= 0) {
            counts.remove(id);
            if (id.equals(equipped)) equipped = null;
        } else {
            counts.put(id, left);
        }
        return true;
    }

    public synchronized List<Map.Entry<Identifier, Integer>> entries() {
        return new ArrayList<>(counts.entrySet());
    }

    public synchronized String describe() {
        if (counts.isEmpty()) return "empty";
        return counts.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> e.getKey() + " x" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("empty");
    }

    public synchronized Identifier getEquipped() {
        return equipped;
    }

    public synchronized boolean equip(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || counts.getOrDefault(id, 0) <= 0) return false;
        equipped = id;
        return true;
    }

    public synchronized ItemStack equippedStack() {
        if (equipped == null || counts.getOrDefault(equipped, 0) <= 0) {
            return ItemStack.EMPTY;
        }
        Item item = Registries.ITEM.get(equipped);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
