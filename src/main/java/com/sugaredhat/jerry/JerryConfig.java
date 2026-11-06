package com.sugaredhat.jerry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side JSON config in config/jerry/. Only the server reads it; shop offers,
 * offering results and chat all run on the server, so edits apply to every player.
 * Reload live with /jerry reload.
 */
public final class JerryConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static General general = new General();
    public static Shop shop = Shop.defaults();
    public static OfferingsFile offerings = OfferingsFile.defaults();

    private JerryConfig() {}

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("jerry");
    }

    /** Loads all three files, creating defaults on first run. Returns a list of problems (empty = fine). */
    public static List<String> load() {
        List<String> problems = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            writeReadme();
        } catch (IOException e) {
            problems.add("Can't create config dir: " + e.getMessage());
        }
        general = read("general.json", General.class, new General(), problems);
        shop = read("shop.json", Shop.class, Shop.defaults(), problems);
        offerings = read("offerings.json", OfferingsFile.class, OfferingsFile.defaults(), problems);
        if (general.llm == null) general.llm = new Llm();
        if (general.lines == null) general.lines = General.defaultLines();
        if (shop.trades == null) shop.trades = new ArrayList<>();
        if (offerings.offerings == null) offerings.offerings = new ArrayList<>();
        JerryMod.LOG.info("Jerry config loaded: {} trades, {} offerings", shop.trades.size(), offerings.offerings.size());
        for (String p : problems) JerryMod.LOG.error("Jerry config: {}", p);
        return problems;
    }

    private static <T> T read(String name, Class<T> type, T fallback, List<String> problems) {
        Path file = dir().resolve(name);
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, GSON.toJson(fallback), StandardCharsets.UTF_8);
                return fallback;
            }
            T value = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            return value != null ? value : fallback;
        } catch (Exception e) {
            problems.add(name + " is broken (" + e.getMessage() + ") — using defaults until fixed");
            return fallback;
        }
    }

    // ── Models ──────────────────────────────────────────────────────────────

    public static class ItemSpec {
        public String item;
        public int count = 1;
        /** Optional data components, same format as /give, e.g. {"minecraft:enchantments":{"levels":{"minecraft:sharpness":5}}} */
        public JsonObject components;

        public ItemSpec() {}
        public ItemSpec(String item, int count) { this.item = item; this.count = count; }
    }

    public static class Llm {
        public boolean enabled = true;
        public String url = "http://localhost:11434";
        public String model = "gemma3:4b";
        public String triggerWord = "jerry";
        public double listenRadius = 32;
        public double alwaysListenRadius = 5;
        public double replyRadius = 48;
        public int maxReplyChars = 240;
        public int timeoutSeconds = 60;
        public int historyMessages = 10;
        public String persona = "You are Jerry, a sarcastic, extremely powerful being who lives in a Minecraft world. "
                + "You run a shop and grant favors for offerings. You're smug and funny but not cruel for no reason. "
                + "You hold grudges against people who are rude to you and go easy on people who are nice.";
    }

    public static class General {
        public String _readme = "See README.txt in this folder. Reload with /jerry reload.";
        // Summoning
        public int summonPermissionLevel = 0;
        public int summonCooldownSeconds = 300;
        public int maxJerrysPerDimension = 1;
        public int dismissPermissionLevel = 2;
        // Body
        public double maxHealth = 2000;
        public double attackDamage = 40;
        public double armor = 20;
        public double movementSpeed = 0.3;
        public double damageTakenMultiplier = 0.25;
        public double regenPerSecond = 5;
        // Favor
        public int favorMin = -100;
        public int favorMax = 100;
        public int hitPenalty = -25;
        public int tradeFavor = 1;
        public int niceWordFavor = 2;
        public int rudeWordFavor = -4;
        public int chatFavorCooldownSeconds = 60;
        public List<String> niceWords = List.of("please", "thank", "thanks", "love you", "best", "goat", "king", "awesome", "appreciate", "sorry", "legend");
        public List<String> rudeWords = List.of("stupid", "idiot", "dumb", "hate you", "trash", "ugly", "loser", "shut up", "bald", "cringe");
        public int defaultGiftFavor = 0;
        public Map<String, Integer> giftFavor = defaultGifts();
        public int giftFavorCapPerDay = 30;
        /** Shop price shift at full favor, in percent. +100 favor = 30% cheaper, -100 = 30% pricier. */
        public double maxPriceShiftPercent = 30;
        // Offerings
        public double offeringRange = 16;
        public double targetRange = -1;
        public boolean protectOps = false;
        public int friendProtectionFavor = 75;
        public int targetGraceSeconds = 300;
        public double betrayalChanceAtMinFavor = 0.5;
        public Map<String, List<String>> lines = defaultLines();
        public Llm llm = new Llm();

        static Map<String, Integer> defaultGifts() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("minecraft:cookie", 1);
            m.put("minecraft:cake", 6);
            m.put("minecraft:pumpkin_pie", 3);
            m.put("minecraft:golden_apple", 8);
            m.put("minecraft:enchanted_golden_apple", 40);
            m.put("minecraft:diamond", 3);
            m.put("minecraft:emerald", 1);
            m.put("minecraft:poppy", 1);
            m.put("minecraft:rotten_flesh", -2);
            m.put("minecraft:poisonous_potato", -3);
            m.put("create:sweet_roll", 3);
            m.put("create:chocolate_glazed_berries", 3);
            m.put("create:blaze_cake", 10);
            return m;
        }

        static Map<String, List<String>> defaultLines() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("summon", List.of("Who called me. Make it quick.", "I'm here. Don't waste my time, {player}.", "You rang?"));
            m.put("arrive", List.of("Yeah yeah, I'm coming.", "Teleporting. You owe me."));
            m.put("dismiss", List.of("Finally. Peace.", "Call me when you have money."));
            m.put("accept", List.of("Done. Pleasure doing business, {player}.", "Consider it handled.", "Say less."));
            m.put("accept_target", List.of("{target} had it coming. Courtesy of {player}.", "Sorry {target}, business is business.", "{target}, someone paid good money for this."));
            m.put("refuse_favor", List.of("You? After how you've treated me? No.", "Earn my respect first, {player}.", "I don't work for people I don't like."));
            m.put("refuse_friend", List.of("{target} is my buddy. Not happening.", "Touch {target} and you'll deal with me."));
            m.put("refuse_grace", List.of("{target} just got hit. Give it a minute, vulture."));
            m.put("refuse_poor", List.of("You're short. I don't do layaway."));
            m.put("betrayal", List.of("Thanks for the donation, {player}. Now get lost.", "Funny thing, {player}: I don't like you either."));
            m.put("betrayal_backfire", List.of("You know what? You deserve it more, {player}.", "Plot twist, {player}."));
            m.put("hit", List.of("Bad idea.", "You just made the biggest mistake of your life.", "Oh you wanna go?"));
            m.put("gift_good", List.of("Aw. You shouldn't have. Well, you should have.", "Now THIS is how you treat a legend.", "Noted. I like you a little more."));
            m.put("gift_bad", List.of("Is this a joke? I'll remember this.", "Wow. Insulting."));
            m.put("gift_meh", List.of("...thanks, I guess.", "What am I supposed to do with this?"));
            m.put("angry_shop", List.of("Shop's closed for you. You know why."));
            return m;
        }
    }

    public static class Trade {
        public ItemSpec cost;
        public ItemSpec cost2;
        public ItemSpec result;
        public int maxUses = 12;
        public int minFavor = -100;

        public Trade() {}
        Trade(ItemSpec cost, ItemSpec cost2, ItemSpec result, int maxUses, int minFavor) {
            this.cost = cost; this.cost2 = cost2; this.result = result; this.maxUses = maxUses; this.minFavor = minFavor;
        }
    }

    public static class Shop {
        public String _readme = "Trades in Jerry's shop. Items from mods that aren't installed are skipped automatically.";
        public int restockMinutes = 20;
        /** Players at or below this favor can't open the shop at all. */
        public int shopBanFavor = -60;
        public List<Trade> trades;

        static Shop defaults() {
            Shop s = new Shop();
            s.trades = new ArrayList<>(List.of(
                    t("minecraft:emerald", 4, null, 0, "minecraft:golden_apple", 1, 8, -100),
                    t("minecraft:emerald", 12, null, 0, "minecraft:ender_pearl", 4, 12, -100),
                    t("minecraft:emerald", 24, "minecraft:book", 1, "minecraft:experience_bottle", 16, 8, -100),
                    t("minecraft:diamond", 6, null, 0, "minecraft:totem_of_undying", 1, 2, 25),
                    t("minecraft:diamond", 10, "minecraft:netherite_scrap", 2, "minecraft:netherite_ingot", 1, 2, 40),
                    t("minecraft:emerald", 32, null, 0, "minecraft:enchanted_golden_apple", 1, 1, 60),
                    // Create — skipped if Create isn't installed
                    t("minecraft:emerald", 3, null, 0, "create:andesite_alloy", 16, 16, -100),
                    t("minecraft:emerald", 6, null, 0, "create:brass_ingot", 8, 16, -100),
                    t("minecraft:emerald", 10, null, 0, "create:precision_mechanism", 2, 8, 0),
                    t("minecraft:diamond", 2, null, 0, "create:blaze_burner", 1, 4, 10),
                    t("minecraft:emerald", 16, null, 0, "create:mechanical_crafter", 4, 4, 0)
            ));
            return s;
        }

        private static Trade t(String c, int cn, String c2, int c2n, String r, int rn, int uses, int minFavor) {
            return new Trade(new ItemSpec(c, cn), c2 == null ? null : new ItemSpec(c2, c2n), new ItemSpec(r, rn), uses, minFavor);
        }
    }

    public static class Offering {
        public String id;
        public String name;
        public String description;
        public List<ItemSpec> cost = new ArrayList<>();
        public int minFavor = -100;
        public int favorGain = 0;
        public int cooldownSeconds = 60;
        /** none | players | mobs | any */
        public String target = "none";
        /** Harmful offerings respect friend protection, target grace and protectOps. */
        public boolean harmful = false;
        public List<JsonObject> actions = new ArrayList<>();
    }

    public static class OfferingsFile {
        public String _readme = "Paid favors: /jerry offer <id> [target]. Action types are listed in README.txt.";
        public List<Offering> offerings;

        static OfferingsFile defaults() {
            OfferingsFile f = new OfferingsFile();
            f.offerings = new ArrayList<>();

            f.offerings.add(off("eternal_day", "Eternal Day", "Jerry snaps his fingers and it's morning.",
                    List.of(new ItemSpec("minecraft:emerald", 6)), -100, 1, 300, "none", false,
                    act("time", "value", 1000)));

            f.offerings.add(off("storm", "Storm Call", "Five minutes of thunder. Charged creepers, anyone?",
                    List.of(new ItemSpec("minecraft:diamond", 2), new ItemSpec("minecraft:lightning_rod", 1)), -100, 1, 600, "none", false,
                    act("weather", "kind", "thunder", "seconds", 300)));

            f.offerings.add(off("blessing", "Jerry's Blessing", "Strength II, Resistance II and Regeneration for 3 minutes.",
                    List.of(new ItemSpec("minecraft:diamond", 6), new ItemSpec("minecraft:golden_apple", 1)), 0, 3, 600, "none", false,
                    effects("self", new Object[][]{{"minecraft:strength", 180, 1}, {"minecraft:resistance", 180, 1}, {"minecraft:regeneration", 180, 0}})));

            f.offerings.add(off("pocket_sand", "Pocket Sand", "Blindness and slowness on someone for 20 seconds.",
                    List.of(new ItemSpec("minecraft:emerald", 8), new ItemSpec("minecraft:sand", 16)), -50, 0, 60, "any", true,
                    effects("target", new Object[][]{{"minecraft:blindness", 20, 0}, {"minecraft:slowness", 20, 2}})));

            f.offerings.add(off("yeet", "Yeet", "Launches the target into the sky. Slow falling not included.",
                    List.of(new ItemSpec("minecraft:emerald", 16), new ItemSpec("minecraft:slime_ball", 4)), -20, 1, 120, "any", true,
                    act("launch", "power", 3.5)));

            f.offerings.add(off("plague", "Plague", "Poison, hunger and weakness for 45 seconds. Won't kill on its own.",
                    List.of(new ItemSpec("minecraft:diamond", 4), new ItemSpec("minecraft:spider_eye", 4)), 0, 1, 180, "players", true,
                    effects("target", new Object[][]{{"minecraft:poison", 45, 1}, {"minecraft:hunger", 45, 2}, {"minecraft:weakness", 45, 1}})));

            f.offerings.add(off("mob_party", "Mob Party", "Six angry zombies show up next to the target.",
                    List.of(new ItemSpec("minecraft:diamond", 6), new ItemSpec("minecraft:rotten_flesh", 32)), 0, 1, 300, "players", true,
                    act("spawn", "entity", "minecraft:zombie", "count", 6, "radius", 4, "aggro", true)));

            f.offerings.add(off("smite", "Smite", "Three lightning bolts and 16 damage (8 hearts). Armor doesn't help.",
                    List.of(new ItemSpec("minecraft:diamond", 12), new ItemSpec("minecraft:lightning_rod", 2)), 20, 2, 600, "any", true,
                    act("lightning", "count", 3, "damage", 16)));

            f.offerings.add(off("hit_job", "The Hit Job", "Jerry personally shows up and kills the target. A totem still saves them.",
                    List.of(new ItemSpec("minecraft:netherite_ingot", 2), new ItemSpec("minecraft:diamond", 16)), 40, 5, 1800, "any", true,
                    act("teleport_jerry"), act("lightning", "count", 1, "damage", 0), act("kill")));

            JsonObject jackpot = act("random", "options", new JsonArray());
            JsonArray opts = jackpot.getAsJsonArray("options");
            opts.add(weighted(50, act("give", "to", "self", "items", items(new ItemSpec("minecraft:diamond", 12)))));
            opts.add(weighted(25, act("give", "to", "self", "items", items(new ItemSpec("minecraft:netherite_ingot", 1)))));
            opts.add(weighted(15, act("explode", "power", 2.5, "on", "self")));
            opts.add(weighted(10, act("give", "to", "self", "items", items(new ItemSpec("minecraft:enchanted_golden_apple", 2)))));
            f.offerings.add(off("gamble", "Jerry's Gamble", "Pay 24 emeralds. Maybe get rich. Maybe explode.",
                    List.of(new ItemSpec("minecraft:emerald", 24)), -100, 0, 120, "none", false, jackpot));
            return f;
        }

        private static Offering off(String id, String name, String desc, List<ItemSpec> cost, int minFavor, int favorGain,
                                    int cooldown, String target, boolean harmful, JsonObject... actions) {
            Offering o = new Offering();
            o.id = id; o.name = name; o.description = desc; o.cost = new ArrayList<>(cost); o.minFavor = minFavor;
            o.favorGain = favorGain; o.cooldownSeconds = cooldown; o.target = target; o.harmful = harmful;
            o.actions = new ArrayList<>(List.of(actions));
            return o;
        }

        private static JsonObject act(String type, Object... kv) {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = (String) kv[i];
                Object v = kv[i + 1];
                if (v instanceof Number n) o.addProperty(k, n);
                else if (v instanceof Boolean b) o.addProperty(k, b);
                else if (v instanceof JsonArray a) o.add(k, a);
                else o.addProperty(k, String.valueOf(v));
            }
            return o;
        }

        private static JsonObject effects(String on, Object[][] list) {
            JsonObject o = act("effect", "on", on);
            JsonArray arr = new JsonArray();
            for (Object[] e : list) {
                JsonObject eo = new JsonObject();
                eo.addProperty("id", (String) e[0]);
                eo.addProperty("seconds", (Integer) e[1]);
                eo.addProperty("amplifier", (Integer) e[2]);
                arr.add(eo);
            }
            o.add("effects", arr);
            return o;
        }

        private static JsonArray items(ItemSpec... specs) {
            JsonArray arr = new JsonArray();
            for (ItemSpec s : specs) arr.add(GSON.toJsonTree(s));
            return arr;
        }

        private static JsonObject weighted(int weight, JsonObject... actions) {
            JsonObject o = new JsonObject();
            o.addProperty("weight", weight);
            JsonArray arr = new JsonArray();
            for (JsonObject a : actions) arr.add(a);
            o.add("actions", arr);
            return o;
        }
    }

    // ── README ──────────────────────────────────────────────────────────────
    private static void writeReadme() throws IOException {
        String txt = """
                JERRY CONFIG  (server side; edit, then run /jerry reload — applies to everyone instantly)

                general.json   — Jerry's stats, favor rules, gift values, his canned lines, and the AI (Ollama) settings.
                shop.json      — trades. Each: cost {item,count}, optional cost2, result {item,count,components}, maxUses, minFavor.
                                 Any mod item works (e.g. "create:brass_ingot"). Missing mods = trade silently skipped.
                offerings.json — paid favors. Players run: /jerry offer <id> [target]
                                 target: none | players | mobs | any     harmful: true = respects friend protection & grace time

                FAVOR runs favorMin..favorMax (default -100..100). Goes up with gifts (sneak + right-click Jerry),
                trades, offerings and nice words in chat. Goes down when you hit him or say rude words.
                High favor = cheaper shop + unlocks offerings. Negative favor = Jerry may take your offering and betray you.

                ACTION TYPES (offerings.json -> "actions": [ {...}, {...} ], run in order)
                  {"type":"lightning","count":3,"damage":16}            bolts on target (or offerer if no target), damage bypasses armor
                  {"type":"damage","amount":10}                          magic damage to target
                  {"type":"kill"}                                        kills target (totems still work)
                  {"type":"launch","power":3.5}                          yeets target upward
                  {"type":"effect","on":"target|self","effects":[{"id":"minecraft:speed","seconds":60,"amplifier":1}]}
                  {"type":"explode","power":2.5,"on":"target|self","breakBlocks":false}
                  {"type":"spawn","entity":"minecraft:zombie","count":6,"radius":4,"aggro":true}
                  {"type":"weather","kind":"clear|rain|thunder","seconds":300}
                  {"type":"time","value":1000}                           1000 = morning, 13000 = night
                  {"type":"give","to":"self|target","items":[{"item":"create:brass_ingot","count":32}]}
                  {"type":"teleport_jerry"}                              Jerry appears next to the target
                  {"type":"message","text":"{player} paid me. Fear me.","broadcast":true}
                  {"type":"command","command":"effect give {target} minecraft:glowing 30"}   runs as the server (op level 4)
                  {"type":"random","options":[{"weight":3,"actions":[...]},{"weight":1,"actions":[...]}]}
                Placeholders in text/commands: {player} {target} {x} {y} {z}  (x/y/z = target, or offerer if no target)
                """;
        Files.writeString(dir().resolve("README.txt"), txt, StandardCharsets.UTF_8);
    }

    /** Parses an item spec into an ItemStack JSON for ItemStack.CODEC. */
    public static JsonObject toStackJson(ItemSpec spec) {
        JsonObject o = new JsonObject();
        o.addProperty("id", spec.item);
        o.addProperty("count", Math.max(1, spec.count));
        if (spec.components != null) o.add("components", spec.components);
        return o;
    }

    public static ItemSpec specFromJson(JsonObject o) {
        return GSON.fromJson(o, ItemSpec.class);
    }

    public static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
