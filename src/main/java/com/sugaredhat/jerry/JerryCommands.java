package com.sugaredhat.jerry;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class JerryCommands {
    private static final Map<UUID, Long> summonCooldown = new ConcurrentHashMap<>();

    private JerryCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        d.register(Commands.literal("jerry")
                .executes(JerryCommands::help)
                .then(Commands.literal("help").executes(JerryCommands::help))
                .then(Commands.literal("summon")
                        .requires(s -> s.hasPermission(JerryConfig.general.summonPermissionLevel))
                        .executes(JerryCommands::summon))
                .then(Commands.literal("dismiss").executes(JerryCommands::dismiss))
                .then(Commands.literal("offerings").executes(JerryCommands::listOfferings))
                .then(Commands.literal("offer")
                        .then(Commands.argument("offering", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        JerryConfig.offerings.offerings.stream().filter(o -> o != null && o.id != null).map(o -> o.id), b))
                                .executes(c -> offer(c, null))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(c -> offer(c, EntityArgument.getEntity(c, "target"))))))
                .then(Commands.literal("favor")
                        .executes(c -> favor(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> favor(c, EntityArgument.getPlayer(c, "player")))))
                .then(Commands.literal("setfavor").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", IntegerArgumentType.integer(-1000, 1000))
                                        .executes(JerryCommands::setFavor))))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2))
                        .executes(JerryCommands::reload)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    static JerryEntity nearestJerry(Entity near, double range) {
        if (!(near.level() instanceof ServerLevel level)) return null;
        JerryEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (JerryEntity j : level.getEntities(JerryMod.JERRY.get(), e -> e.isAlive())) {
            double d = j.distanceToSqr(near);
            if (range >= 0 && d > range * range) continue;
            if (d < bestD) {
                bestD = d;
                best = j;
            }
        }
        return best;
    }

    private static void flash(ServerLevel level, Vec3 pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(pos);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 1, pos.z, 40, 0.5, 1, 0.5, 0.02);
    }

    private static int ok(CommandContext<CommandSourceStack> c, Component msg) {
        c.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    // ── commands ─────────────────────────────────────────────────────────────
    private static int help(CommandContext<CommandSourceStack> c) {
        MutableComponent m = Component.literal("— Jerry —\n").withStyle(ChatFormatting.GOLD);
        m.append(Component.literal(
                "/jerry summon — call Jerry (he never starts fights, but he finishes them)\n" +
                "Right-click Jerry — shop.  Sneak + right-click with an item — gift\n" +
                "Say \"jerry\" in chat near him to talk\n" +
                "/jerry offerings — what he'll do for a price\n" +
                "/jerry offer <id> [target] — pay for a favor\n" +
                "/jerry favor — how much he likes you\n" +
                "/jerry dismiss — send him away").withStyle(ChatFormatting.WHITE));
        return ok(c, m);
    }

    private static int summon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = p.serverLevel();
        JerryConfig.General g = JerryConfig.general;
        long now = System.currentTimeMillis();
        Long ready = summonCooldown.get(p.getUUID());
        if (ready != null && now < ready && !p.hasPermissions(2)) {
            return fail(c, "Jerry won't come again for " + ((ready - now) / 1000 + 1) + "s.");
        }
        Vec3 look = p.getLookAngle();
        Vec3 spot = new Vec3(p.getX() + look.x * 3, p.getY(), p.getZ() + look.z * 3);

        List<? extends JerryEntity> existing = level.getEntities(JerryMod.JERRY.get(), e -> e.isAlive());
        if (existing.size() >= Math.max(1, g.maxJerrysPerDimension)) {
            JerryEntity j = nearestJerry(p, -1);
            if (j == null) return fail(c, "Jerry is busy.");
            level.sendParticles(ParticleTypes.PORTAL, j.getX(), j.getY() + 1, j.getZ(), 60, 0.4, 1, 0.4, 0.5);
            j.teleportTo(spot.x, spot.y, spot.z);
            j.setTarget(null);
            flash(level, spot);
            j.sayLine("arrive", p.getGameProfile().getName(), null);
            summonCooldown.put(p.getUUID(), now + g.summonCooldownSeconds * 1000L);
            return ok(c, Component.literal("Jerry came over.").withStyle(ChatFormatting.GOLD));
        }

        JerryEntity jerry = JerryMod.JERRY.get().create(level);
        if (jerry == null) return fail(c, "Jerry failed to spawn.");
        jerry.moveTo(spot.x, spot.y, spot.z, p.getYRot() + 180F, 0F);
        jerry.setYHeadRot(p.getYRot() + 180F);
        jerry.applyConfig();
        jerry.setHealth(jerry.getMaxHealth());
        jerry.setSummoner(p.getUUID());
        level.addFreshEntity(jerry);
        flash(level, spot);
        level.playSound(null, jerry.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 1.6F);
        jerry.sayLine("summon", p.getGameProfile().getName(), null);
        summonCooldown.put(p.getUUID(), now + g.summonCooldownSeconds * 1000L);
        return ok(c, Component.literal("You summoned Jerry. Be nice.").withStyle(ChatFormatting.GOLD));
    }

    private static int dismiss(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        JerryEntity j = nearestJerry(p, 32);
        if (j == null) return fail(c, "No Jerry within 32 blocks.");
        boolean allowed = p.hasPermissions(JerryConfig.general.dismissPermissionLevel) || p.getUUID().equals(j.getSummoner());
        if (!allowed) return fail(c, "Only whoever summoned him (or an op) can dismiss Jerry.");
        j.sayLine("dismiss", p.getGameProfile().getName(), null);
        ServerLevel level = p.serverLevel();
        level.sendParticles(ParticleTypes.PORTAL, j.getX(), j.getY() + 1, j.getZ(), 80, 0.4, 1, 0.4, 0.6);
        level.playSound(null, j.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1F, 0.5F);
        j.discard();
        return ok(c, Component.literal("Jerry left.").withStyle(ChatFormatting.GRAY));
    }

    private static int listOfferings(CommandContext<CommandSourceStack> c) {
        int favor = 0;
        if (c.getSource().getEntity() instanceof Player p) favor = FavorData.of(c.getSource().getServer(), p.getUUID());
        MutableComponent m = Component.literal("Jerry's offerings (your favor: " + favor + ")\n").withStyle(ChatFormatting.GOLD);
        for (JerryConfig.Offering o : JerryConfig.offerings.offerings) {
            if (o == null || o.id == null) continue;
            boolean locked = favor < o.minFavor;
            String tgt = o.target == null || o.target.equalsIgnoreCase("none") ? "" : " <" + o.target + ">";
            m.append(Component.literal("\n" + o.id + tgt).withStyle(locked ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW));
            m.append(Component.literal(" — " + (o.name == null ? o.id : o.name)
                    + (locked ? "  [needs favor " + o.minFavor + "]" : "")).withStyle(locked ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE));
            if (o.description != null)
                m.append(Component.literal("\n   " + o.description).withStyle(ChatFormatting.GRAY));
            m.append(Component.literal("\n   Cost: " + Util.costText(o.cost) + "  ·  cooldown " + o.cooldownSeconds + "s")
                    .withStyle(ChatFormatting.GRAY));
        }
        return ok(c, m);
    }

    private static int offer(CommandContext<CommandSourceStack> c, Entity target) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(c, "offering");
        var off = Offerings.find(id);
        if (off.isEmpty()) return fail(c, "No offering called '" + id + "'. See /jerry offerings");
        Component result = Offerings.offer(p, off.get(), target);
        p.sendSystemMessage(result);
        return 1;
    }

    private static int favor(CommandContext<CommandSourceStack> c, ServerPlayer who) {
        int f = FavorData.of(c.getSource().getServer(), who.getUUID());
        String name = who.getGameProfile().getName();
        return ok(c, Component.literal("Jerry's opinion of " + name + ": " + f + " (" + FavorData.describe(f) + ")")
                .withStyle(f >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static int setFavor(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer who = EntityArgument.getPlayer(c, "player");
        int v = FavorData.get(c.getSource().getServer()).set(who.getUUID(), IntegerArgumentType.getInteger(c, "value"));
        return ok(c, Component.literal("Set " + who.getGameProfile().getName() + "'s favor to " + v));
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        List<String> problems = JerryConfig.load();
        for (ServerLevel level : c.getSource().getServer().getAllLevels()) {
            for (JerryEntity j : level.getEntities(JerryMod.JERRY.get(), e -> true)) {
                j.applyConfig();
                j.resetShop();
            }
        }
        if (!problems.isEmpty()) return fail(c, "Reloaded with problems: " + String.join("; ", problems));
        return ok(c, Component.literal("Jerry config reloaded: " + JerryConfig.shop.trades.size() + " trades, "
                + JerryConfig.offerings.offerings.size() + " offerings.").withStyle(ChatFormatting.GREEN));
    }
}
