package com.sugaredhat.jerry;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Optional;

/** Turns shop.json into vanilla merchant offers, priced for one player's favor. */
final class JerryShop {
    private JerryShop() {}

    static MerchantOffers build(JerryEntity jerry, ServerPlayer player, int favor) {
        MerchantOffers out = new MerchantOffers();
        List<JerryConfig.Trade> trades = JerryConfig.shop.trades;
        double shiftPct = JerryConfig.general.maxPriceShiftPercent / 100.0;
        double favorFrac = favor >= 0
                ? (double) favor / Math.max(1, JerryConfig.general.favorMax)
                : -(double) favor / Math.min(-1, JerryConfig.general.favorMin);
        for (int i = 0; i < trades.size(); i++) {
            JerryConfig.Trade t = trades.get(i);
            if (t == null || t.cost == null || t.result == null) continue;
            if (favor < t.minFavor) continue;
            Optional<Item> costA = Util.item(t.cost.item);
            ItemStack result = Util.stack(player.server, t.result);
            if (costA.isEmpty() || result.isEmpty()) continue;           // mod not installed → skip
            Optional<ItemCost> costB = Optional.empty();
            if (t.cost2 != null) {
                Optional<Item> b = Util.item(t.cost2.item);
                if (b.isEmpty()) continue;
                costB = Optional.of(new ItemCost(b.get(), clamp(t.cost2.count, b.get())));
            }
            int baseCount = clamp(t.cost.count, costA.get());
            MerchantOffer offer = new MerchantOffer(new ItemCost(costA.get(), baseCount), costB, result,
                    Math.max(1, t.maxUses), 0, 0F);
            int used = jerry.usesFor(i);
            for (int u = 0; u < used && u < t.maxUses; u++) offer.increaseUses();
            // + favor → discount, - favor → markup (MerchantOffer clamps final price to 1..stack size)
            int diff = (int) Math.round(baseCount * shiftPct * favorFrac);
            offer.setSpecialPriceDiff(-diff);
            jerry.registerOffer(offer, i);
            out.add(offer);
        }
        return out;
    }

    private static int clamp(int count, Item item) {
        return Math.max(1, Math.min(count, new ItemStack(item).getMaxStackSize()));
    }
}
