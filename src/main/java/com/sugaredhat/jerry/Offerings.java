package com.sugaredhat.jerry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runs paid favors from offerings.json. */
final class Offerings {
    private static final Map<String, Long> cooldowns = new ConcurrentHashMap<>();   // player|offering → ready-at ms
    private static final Map<UUID, Long> targetGrace = new ConcurrentHashMap<>();   // target → protected-until ms

    private Offerings() {}

    static void reset() {
        cooldowns.clear();
        targetGrace.clear();
    }

    static Optional<JerryConfig.Offering> find(String id) {
        return JerryConfig.offerings.offerings.stream().filter(o -> o != null && id.equalsIgnoreCase(o.id)).findFirst();
    }

    /** Returns a message for the player (red = problem) — Jerry's own lines go to chat separately. */
    static Component offer(ServerPlayer player, JerryConfig.Offering off, Entity target) {
        MinecraftServer server = player.server;
        JerryConfig.General g = JerryConfig.general;
        String pName = player.getGameProfile().getName();
        String tName = target == null ? null : target.getName().getString();

        JerryEntity jerry = JerryCommands.nearestJerry(player, g.offeringRange);
        if (jerry == null) {
            return err(g.offeringRange < 0 ? "There's no Jerry in this dimension. /jerry summon"
                    : "Jerry needs to be within " + (int) g.offeringRange + " blocks. /jerry summon");
        }

        FavorData favorData = FavorData.get(server);
        int favor = favorData.get(player.getUUID());
        if (favor < off.minFavor) {
            jerry.sayLine("refuse_favor", pName, tName);
            return err("Needs favor " + off.minFavor + " (you have " + favor + ").");
        }

        long now = System.currentTimeMillis();
        String cdKey = player.getUUID() + "|" + off.id;
        long ready = cooldowns.getOrDefault(cdKey, 0L);
        if (now < ready) return err(off.name + " is on cooldown for " + ((ready - now) / 1000 + 1) + "s.");

        // ── Target validation
        String mode = off.target == null ? "none" : off.target.toLowerCase();
        if (!mode.equals("none")) {
            if (target == null) return err("This one needs a target: /jerry offer " + off.id + " <player or @e[...]>");
            if (target instanceof JerryEntity) {
                jerry.say("Nice try, " + pName + ".");
                return err("You can't target Jerry.");
            }
            boolean isPlayer = target instanceof Player;
            if (mode.equals("players") && !isPlayer) return err("This offering only works on players.");
            if (mode.equals("mobs") && isPlayer) return err("This offering only works on mobs.");
            if (!(target instanceof LivingEntity)) return err("Target has to be alive.");
            if (target.level() != jerry.level()) return err("Jerry can't reach other dimensions.");
            if (g.targetRange >= 0 && target.distanceTo(jerry) > g.targetRange)
                return err("Target is too far from Jerry (max " + (int) g.targetRange + " blocks).");
            if (off.harmful && target instanceof ServerPlayer tp) {
                if (g.protectOps && server.getPlayerList().isOp(tp.getGameProfile()))
                    return err("Ops are protected on this server.");
                int tFavor = favorData.get(tp.getUUID());
                if (tFavor >= g.friendProtectionFavor && favor < tFavor && tp != player) {
                    jerry.sayLine("refuse_friend", pName, tName);
                    return err(tName + " is one of Jerry's favorites. Get your favor above " + tFavor + ".");
                }
                Long until = targetGrace.get(tp.getUUID());
                if (until != null && now < until && tp != player) {
                    jerry.sayLine("refuse_grace", pName, tName);
                    return err(tName + " is protected for " + ((until - now) / 1000 + 1) + "s more.");
                }
            }
        } else {
            target = null;
        }

        // ── Payment
        List<String> missing = Util.missing(player, off.cost);
        if (!missing.isEmpty()) {
            jerry.sayLine("refuse_poor", pName, tName);
            return err("Missing: " + String.join(", ", missing));
        }
        Util.take(player, off.cost);
        cooldowns.put(cdKey, now + off.cooldownSeconds * 1000L);

        // ── Betrayal: disliked players may get scammed (or get the curse themselves)
        if (favor < 0 && g.favorMin < 0) {
            double chance = g.betrayalChanceAtMinFavor * ((double) favor / g.favorMin);
            if (Util.RNG.nextDouble() < chance) {
                if (off.harmful && target != null) {
                    jerry.sayLine("betrayal_backfire", pName, tName);
                    target = player;
                    tName = pName;
                } else {
                    jerry.sayLine("betrayal", pName, tName);
                    return Component.literal("Jerry took your offering and did nothing. Maybe be nicer to him.")
                            .withStyle(ChatFormatting.DARK_RED);
                }
            }
        }

        // ── Do it
        Ctx ctx = new Ctx(server, player, jerry, target);
        try {
            run(ctx, off.actions);
        } catch (Exception e) {
            JerryMod.LOG.error("Jerry offering '{}' failed", off.id, e);
        }
        favorData.add(player.getUUID(), off.favorGain);
        if (off.harmful && target instanceof ServerPlayer tp && tp != player) {
            targetGrace.put(tp.getUUID(), now + g.targetGraceSeconds * 1000L);
        }
        if (target != null && target != player) jerry.sayLine("accept_target", pName, tName);
        else jerry.sayLine("accept", pName, tName);
        JerryChat.noteEvent(jerry, pName + " paid you for '" + off.name + "'" + (tName != null ? " on " + tName : "") + " and you did it.");
        return Component.literal("Jerry accepted: " + off.name).withStyle(ChatFormatting.GOLD);
    }

    private static Component err(String s) {
        return Component.literal(s).withStyle(ChatFormatting.RED);
    }

    // ── Action engine ────────────────────────────────────────────────────────
    record Ctx(MinecraftServer server, ServerPlayer player, JerryEntity jerry, Entity target) {
        Entity focus() { return target != null ? target : player; }
        ServerLevel level() { return (ServerLevel) jerry.level(); }
        String fill(String s) {
            Entity f = focus();
            return Util.fill(s, player.getGameProfile().getName(), target == null ? null : target.getName().getString())
                    .replace("{x}", String.valueOf(f.getBlockX()))
                    .replace("{y}", String.valueOf(f.getBlockY()))
                    .replace("{z}", String.valueOf(f.getBlockZ()));
        }
    }

    static void run(Ctx ctx, List<JsonObject> actions) {
        if (actions == null) return;
        for (JsonObject a : actions) {
            if (a == null || !a.has("type")) continue;
            try {
                runOne(ctx, a);
            } catch (Exception e) {
                JerryMod.LOG.error("Jerry action {} failed", a, e);
            }
        }
    }

    private static void runOne(Ctx ctx, JsonObject a) {
        ServerLevel level = ctx.level();
        Entity focus = ctx.focus();
        JerryEntity jerry = ctx.jerry();
        switch (a.get("type").getAsString().toLowerCase()) {
            case "lightning" -> {
                int count = Math.max(1, num(a, "count", 1));
                for (int i = 0; i < count; i++) {
                    LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                    if (bolt == null) continue;
                    bolt.moveTo(focus.getX() + (i == 0 ? 0 : Util.RNG.nextGaussian()), focus.getY(), focus.getZ() + (i == 0 ? 0 : Util.RNG.nextGaussian()));
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
                float dmg = (float) dbl(a, "damage", 0);
                if (dmg > 0 && focus instanceof LivingEntity le) le.hurt(level.damageSources().indirectMagic(jerry, jerry), dmg);
            }
            case "damage" -> {
                if (focus instanceof LivingEntity le) le.hurt(level.damageSources().indirectMagic(jerry, jerry), (float) dbl(a, "amount", 10));
            }
            case "kill" -> {
                if (focus instanceof LivingEntity le) {
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, le.getX(), le.getY() + 1, le.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
                    le.hurt(level.damageSources().indirectMagic(jerry, jerry), 100000F);
                }
            }
            case "launch" -> {
                double power = dbl(a, "power", 3.0);
                Vec3 v = focus.getDeltaMovement();
                focus.setDeltaMovement(v.x, power, v.z);
                focus.hurtMarked = true;
                if (focus instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
                level.sendParticles(ParticleTypes.CLOUD, focus.getX(), focus.getY(), focus.getZ(), 30, 0.5, 0.1, 0.5, 0.1);
                level.playSound(null, focus.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2F, 0.7F);
            }
            case "effect" -> {
                Entity who = "self".equalsIgnoreCase(str(a, "on", "target")) ? ctx.player() : focus;
                if (!(who instanceof LivingEntity le) || !a.has("effects")) return;
                for (JsonElement el : a.getAsJsonArray("effects")) {
                    JsonObject e = el.getAsJsonObject();
                    ResourceLocation rl = ResourceLocation.tryParse(str(e, "id", ""));
                    if (rl == null) continue;
                    Optional<Holder.Reference<MobEffect>> eff = BuiltInRegistries.MOB_EFFECT.getHolder(rl);
                    eff.ifPresent(h -> le.addEffect(new MobEffectInstance(h, num(e, "seconds", 30) * 20, num(e, "amplifier", 0))));
                }
            }
            case "explode" -> {
                Entity who = "self".equalsIgnoreCase(str(a, "on", "target")) ? ctx.player() : focus;
                boolean breakBlocks = a.has("breakBlocks") && a.get("breakBlocks").getAsBoolean();
                level.explode(jerry, who.getX(), who.getY(), who.getZ(), (float) dbl(a, "power", 2.5),
                        breakBlocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
            }
            case "spawn" -> {
                ResourceLocation rl = ResourceLocation.tryParse(str(a, "entity", "minecraft:zombie"));
                if (rl == null) return;
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
                if (type.isEmpty()) return;
                int count = Math.min(30, Math.max(1, num(a, "count", 3)));
                double radius = dbl(a, "radius", 4);
                boolean aggro = !a.has("aggro") || a.get("aggro").getAsBoolean();
                for (int i = 0; i < count; i++) {
                    double ang = Util.RNG.nextDouble() * Math.PI * 2;
                    BlockPos pos = BlockPos.containing(focus.getX() + Math.cos(ang) * radius, focus.getY(), focus.getZ() + Math.sin(ang) * radius);
                    Entity e = type.get().spawn(level, pos, MobSpawnType.MOB_SUMMONED);
                    if (aggro && e instanceof Mob m && focus instanceof LivingEntity le) m.setTarget(le);
                }
            }
            case "weather" -> {
                int ticks = num(a, "seconds", 300) * 20;
                ServerLevel ow = ctx.server().overworld();
                switch (str(a, "kind", "thunder").toLowerCase()) {
                    case "clear" -> ow.setWeatherParameters(ticks, 0, false, false);
                    case "rain" -> ow.setWeatherParameters(0, ticks, true, false);
                    default -> ow.setWeatherParameters(0, ticks, true, true);
                }
            }
            case "time" -> {
                long value = num(a, "value", 1000);
                for (ServerLevel l : ctx.server().getAllLevels()) {
                    long next = (l.getDayTime() / 24000L) * 24000L + value;
                    if (next <= l.getDayTime()) next += 24000L;
                    l.setDayTime(next);
                }
            }
            case "give" -> {
                Entity who = "target".equalsIgnoreCase(str(a, "to", "self")) ? focus : ctx.player();
                if (!(who instanceof Player p) || !a.has("items")) return;
                for (JsonElement el : a.getAsJsonArray("items")) {
                    Util.give(p, Util.stack(ctx.server(), JerryConfig.specFromJson(el.getAsJsonObject())));
                }
            }
            case "teleport_jerry" -> {
                if (focus.level() != jerry.level()) return;
                level.sendParticles(ParticleTypes.PORTAL, jerry.getX(), jerry.getY() + 1, jerry.getZ(), 60, 0.4, 1, 0.4, 0.5);
                Vec3 look = focus.getLookAngle();
                jerry.teleportTo(focus.getX() + look.x * 2, focus.getY(), focus.getZ() + look.z * 2);
                jerry.getLookControl().setLookAt(focus, 180F, 180F);
                level.playSound(null, jerry.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5F, 0.6F);
            }
            case "message" -> {
                String text = ctx.fill(str(a, "text", ""));
                if (a.has("broadcast") && a.get("broadcast").getAsBoolean()) {
                    ctx.server().getPlayerList().broadcastSystemMessage(
                            Component.literal("<Jerry> ").withStyle(ChatFormatting.GOLD).append(Component.literal(text)), false);
                } else {
                    jerry.say(text);
                }
            }
            case "command" -> {
                String cmd = ctx.fill(str(a, "command", ""));
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (!cmd.isBlank()) {
                    ctx.server().getCommands().performPrefixedCommand(
                            ctx.server().createCommandSourceStack().withSuppressedOutput().withPermission(4), cmd);
                }
            }
            case "random" -> {
                if (!a.has("options")) return;
                JsonArray opts = a.getAsJsonArray("options");
                int total = 0;
                for (JsonElement el : opts) total += Math.max(0, num(el.getAsJsonObject(), "weight", 1));
                if (total <= 0) return;
                int roll = Util.RNG.nextInt(total);
                for (JsonElement el : opts) {
                    JsonObject o = el.getAsJsonObject();
                    roll -= Math.max(0, num(o, "weight", 1));
                    if (roll < 0) {
                        java.util.List<JsonObject> sub = new java.util.ArrayList<>();
                        if (o.has("actions")) for (JsonElement s : o.getAsJsonArray("actions")) sub.add(s.getAsJsonObject());
                        run(ctx, sub);
                        return;
                    }
                }
            }
            default -> JerryMod.LOG.warn("Jerry: unknown action type {}", a.get("type"));
        }
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static double dbl(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
