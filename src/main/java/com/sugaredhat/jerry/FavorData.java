package com.sugaredhat.jerry;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player favor with Jerry, saved with the world (data/jerry_favor.dat). */
public class FavorData extends SavedData {
    private final Map<UUID, Integer> favor = new HashMap<>();
    // gift favor earned per player per in-game day (not saved; resets on restart, fine)
    private final Map<UUID, long[]> giftToday = new HashMap<>();

    public static FavorData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FavorData::new, FavorData::load, null), "jerry_favor");
    }

    public static int of(MinecraftServer server, UUID id) {
        return get(server).get(id);
    }

    private static FavorData load(CompoundTag tag, HolderLookup.Provider provider) {
        FavorData d = new FavorData();
        CompoundTag f = tag.getCompound("favor");
        for (String key : f.getAllKeys()) {
            try {
                d.favor.put(UUID.fromString(key), f.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag f = new CompoundTag();
        favor.forEach((k, v) -> f.putInt(k.toString(), v));
        tag.put("favor", f);
        return tag;
    }

    public int get(UUID id) {
        return favor.getOrDefault(id, 0);
    }

    public int set(UUID id, int value) {
        JerryConfig.General g = JerryConfig.general;
        int v = Math.max(g.favorMin, Math.min(g.favorMax, value));
        favor.put(id, v);
        setDirty();
        return v;
    }

    public int add(UUID id, int delta) {
        return delta == 0 ? get(id) : set(id, get(id) + delta);
    }

    /** Applies gift favor respecting the daily cap on positive gift favor. Returns favor actually granted. */
    public int addGift(UUID id, int delta, long day) {
        if (delta <= 0) {
            add(id, delta);
            return delta;
        }
        long[] rec = giftToday.computeIfAbsent(id, k -> new long[]{day, 0});
        if (rec[0] != day) {
            rec[0] = day;
            rec[1] = 0;
        }
        int cap = JerryConfig.general.giftFavorCapPerDay;
        int allowed = (int) Math.max(0, Math.min(delta, cap - rec[1]));
        rec[1] += allowed;
        add(id, allowed);
        return allowed;
    }

    public static String describe(int f) {
        if (f >= 75) return "best friend";
        if (f >= 40) return "friend";
        if (f >= 10) return "likes you";
        if (f > -10) return "neutral";
        if (f > -40) return "annoyed";
        if (f > -75) return "dislikes you";
        return "despises you";
    }
}
