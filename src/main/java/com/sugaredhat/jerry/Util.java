package com.sugaredhat.jerry;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

final class Util {
    static final Random RNG = new Random();

    private Util() {}

    static Optional<Item> item(String id) {
        if (id == null) return Optional.empty();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != Items.AIR);
    }

    /** Builds an ItemStack from a spec, including optional components. Empty if the item doesn't exist. */
    static ItemStack stack(MinecraftServer server, JerryConfig.ItemSpec spec) {
        if (spec == null || item(spec.item).isEmpty()) return ItemStack.EMPTY;
        if (spec.components == null) {
            return new ItemStack(item(spec.item).get(), Math.max(1, spec.count));
        }
        HolderLookup.Provider regs = server.registryAccess();
        JsonObject json = JerryConfig.toStackJson(spec);
        return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, regs), json)
                .resultOrPartial(err -> JerryMod.LOG.warn("Jerry: bad item {}: {}", spec.item, err))
                .orElse(ItemStack.EMPTY);
    }

    static int count(Player p, Item item) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (ItemStack s : inv.items) if (s.is(item)) n += s.getCount();
        for (ItemStack s : inv.offhand) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Returns a human list of what's missing, or empty list if the player can pay. */
    static List<String> missing(Player p, List<JerryConfig.ItemSpec> cost) {
        List<String> out = new ArrayList<>();
        for (JerryConfig.ItemSpec c : cost) {
            Optional<Item> it = item(c.item);
            if (it.isEmpty()) {
                out.add(c.item + " (not installed!)");
                continue;
            }
            int have = count(p, it.get());
            if (have < c.count) out.add((c.count - have) + "x " + it.get().getDescription().getString());
        }
        return out;
    }

    static void take(Player p, List<JerryConfig.ItemSpec> cost) {
        Inventory inv = p.getInventory();
        for (JerryConfig.ItemSpec c : cost) {
            Item item = item(c.item).orElse(null);
            if (item == null) continue;
            int left = c.count;
            List<ItemStack> all = new ArrayList<>(inv.items);
            all.addAll(inv.offhand);
            for (ItemStack s : all) {
                if (left <= 0) break;
                if (s.is(item)) {
                    int t = Math.min(left, s.getCount());
                    s.shrink(t);
                    left -= t;
                }
            }
        }
        inv.setChanged();
    }

    static void give(Player p, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack.copy())) p.drop(stack.copy(), false);
    }

    static String costText(List<JerryConfig.ItemSpec> cost) {
        List<String> parts = new ArrayList<>();
        for (JerryConfig.ItemSpec c : cost) {
            String name = item(c.item).map(i -> i.getDescription().getString()).orElse(c.item);
            parts.add(c.count + "x " + name);
        }
        return String.join(", ", parts);
    }

    static String line(String key, String player, String target) {
        List<String> opts = JerryConfig.general.lines.get(key);
        if (opts == null || opts.isEmpty()) return null;
        return fill(opts.get(RNG.nextInt(opts.size())), player, target);
    }

    static String fill(String text, String player, String target) {
        return text.replace("{player}", player == null ? "someone" : player)
                .replace("{target}", target == null ? "someone" : target);
    }
}
