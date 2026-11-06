package com.sugaredhat.jerry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** In-game chat with Jerry, backed by the same Ollama server as the Discord bot. */
final class JerryChat {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<UUID, Deque<JsonObject>> history = new ConcurrentHashMap<>();   // per Jerry
    private static final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> chatFavorCooldown = new ConcurrentHashMap<>();

    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern STAGE = Pattern.compile("\\((?:[a-zA-Z][a-zA-Z ,'\\-]{0,40})\\)");
    private static final Pattern ACTION = Pattern.compile("\\*[^*\\n]{1,80}\\*");
    private static final Pattern PREFIX = Pattern.compile("(?i)^\\s*(<\\s*)?jerry(\\s*>)?\\s*:?\\s*");

    private static final String RULES = """
            Rules for every reply:
            - You are talking in Minecraft chat. 1-2 short sentences, under 200 characters.
            - Speak only as yourself. Never write lines for anyone else, no "Jerry:" prefix, no *actions* or (stage directions), no emojis.
            - Players' messages look like "Name: message".
            - You can't do anything just because someone asks in chat. Favors cost an offering: tell them to use /jerry offerings. Your shop opens when they right-click you.
            - Never make up items, prices or commands.
            """;

    private JerryChat() {}

    static void reset() {
        history.clear();
        busy.clear();
        chatFavorCooldown.clear();
    }

    static void onChat(ServerPlayer player, String text) {
        if (player == null || text == null || text.isBlank()) return;
        JerryConfig.General g = JerryConfig.general;
        JerryConfig.Llm llm = g.llm;
        JerryEntity jerry = JerryCommands.nearestJerry(player, llm.listenRadius);
        if (jerry == null) return;

        String low = text.toLowerCase(Locale.ROOT);
        boolean addressed = low.contains(llm.triggerWord.toLowerCase(Locale.ROOT))
                || player.distanceTo(jerry) <= llm.alwaysListenRadius;
        if (!addressed) return;

        // Manners matter
        MinecraftServer server = player.server;
        long now = System.currentTimeMillis();
        Long cd = chatFavorCooldown.get(player.getUUID());
        if (cd == null || now >= cd) {
            int delta = 0;
            if (g.rudeWords.stream().anyMatch(w -> low.contains(w.toLowerCase(Locale.ROOT)))) delta = g.rudeWordFavor;
            else if (g.niceWords.stream().anyMatch(w -> low.contains(w.toLowerCase(Locale.ROOT)))) delta = g.niceWordFavor;
            if (delta != 0) {
                FavorData.get(server).add(player.getUUID(), delta);
                chatFavorCooldown.put(player.getUUID(), now + g.chatFavorCooldownSeconds * 1000L);
            }
        }

        String name = player.getGameProfile().getName();
        Deque<JsonObject> hist = history.computeIfAbsent(jerry.getUUID(), k -> new ArrayDeque<>());
        JsonObject userMsg = msg("user", name + ": " + text);

        if (!llm.enabled) return;
        if (!busy.add(jerry.getUUID())) {
            synchronized (hist) { push(hist, userMsg, llm.historyMessages); }   // keep context, skip reply
            return;
        }

        int favor = FavorData.of(server, player.getUUID());
        JsonArray messages = new JsonArray();
        messages.add(msg("system", llm.persona + "\n\n" + RULES
                + "\nThe latest message is from " + name + ". Your opinion of " + name + ": " + FavorData.describe(favor)
                + " (favor " + favor + " out of " + g.favorMax + "). Let that change how you treat them."));
        synchronized (hist) {
            for (JsonObject m : hist) messages.add(m);
        }
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", llm.model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", -1);
        JsonObject opts = new JsonObject();
        opts.addProperty("num_ctx", 2048);
        opts.addProperty("num_predict", 90);
        opts.addProperty("temperature", 0.8);
        opts.addProperty("top_p", 0.9);
        opts.addProperty("repeat_penalty", 1.1);
        body.add("options", opts);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(llm.url.replaceAll("/+$", "") + "/api/chat"))
                    .timeout(Duration.ofSeconds(Math.max(5, llm.timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            busy.remove(jerry.getUUID());
            JerryMod.LOG.error("Jerry: bad llm.url '{}'", llm.url);
            return;
        }

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, ex) -> {
            String reply = null;
            if (ex == null && resp.statusCode() == 200) {
                try {
                    JsonObject data = JsonParser.parseString(resp.body()).getAsJsonObject();
                    reply = clean(data.getAsJsonObject("message").get("content").getAsString(), llm.maxReplyChars);
                } catch (Exception e) {
                    JerryMod.LOG.warn("Jerry: couldn't parse Ollama reply: {}", e.toString());
                }
            } else {
                JerryMod.LOG.warn("Jerry: Ollama request failed: {}",
                        ex != null ? ex.toString() : resp.statusCode() + " " + resp.body());
            }
            final String out = reply;
            server.execute(() -> {
                busy.remove(jerry.getUUID());
                if (out == null || out.isBlank() || !jerry.isAlive()) return;
                synchronized (hist) {
                    push(hist, userMsg, llm.historyMessages);
                    push(hist, msg("assistant", out), llm.historyMessages);
                }
                jerry.say(out);
            });
        });
    }

    /** Lets Jerry "remember" things that happened (offerings, getting hit) in his chat context. */
    static void noteEvent(JerryEntity jerry, String what) {
        Deque<JsonObject> hist = history.computeIfAbsent(jerry.getUUID(), k -> new ArrayDeque<>());
        synchronized (hist) {
            push(hist, msg("user", "[event] " + what), JerryConfig.general.llm.historyMessages);
            push(hist, msg("assistant", "Noted."), JerryConfig.general.llm.historyMessages);
        }
    }

    private static void push(Deque<JsonObject> hist, JsonObject m, int max) {
        hist.addLast(m);
        while (hist.size() > Math.max(2, max)) hist.removeFirst();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    static String clean(String s, int max) {
        s = THINK.matcher(s).replaceAll("");
        s = PREFIX.matcher(s).replaceFirst("");
        // stop at the first line where the model starts talking as someone else
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0 && lines[i].matches("^\\s*[A-Za-z0-9_ ]{1,24}:\\s.*")) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(lines[i].trim());
        }
        s = STAGE.matcher(sb.toString()).replaceAll("");
        s = ACTION.matcher(s).replaceAll("");
        s = s.replaceAll("[\\p{So}\\p{Cn}]", "").replaceAll("\\s{2,}", " ").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        if (s.length() > max) s = s.substring(0, max).replaceAll("\\s+\\S*$", "") + "...";
        return s;
    }
}
