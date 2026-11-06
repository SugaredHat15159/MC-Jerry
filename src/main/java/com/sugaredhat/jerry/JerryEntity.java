package com.sugaredhat.jerry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Jerry: absurdly tanky, hits like a truck, never starts a fight.
 * Right-click = shop (vanilla trade UI, offers built per player from shop.json).
 * Sneak + right-click with an item = gift.
 */
public class JerryEntity extends PathfinderMob implements Merchant {
    private @Nullable Player tradingPlayer;
    private @Nullable MerchantOffers offers;
    private final Map<MerchantOffer, Integer> offerIndex = new IdentityHashMap<>();
    private final Map<Integer, Integer> uses = new HashMap<>();
    private long lastRestock;
    private final Map<UUID, Long> hitLineCooldown = new HashMap<>();
    private @Nullable UUID summoner;

    public JerryEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCustomName(Component.literal("Jerry").withStyle(ChatFormatting.GOLD));
        setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 2000)
                .add(Attributes.ATTACK_DAMAGE, 40)
                .add(Attributes.ATTACK_KNOCKBACK, 2.0)
                .add(Attributes.ARMOR, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    /** Pushes general.json stats onto this Jerry (called on summon and /jerry reload). */
    public void applyConfig() {
        JerryConfig.General g = JerryConfig.general;
        setBase(Attributes.MAX_HEALTH, g.maxHealth);
        setBase(Attributes.ATTACK_DAMAGE, g.attackDamage);
        setBase(Attributes.ARMOR, g.armor);
        setBase(Attributes.MOVEMENT_SPEED, g.movementSpeed);
        if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());
        offers = null;
    }

    private void setBase(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double v) {
        AttributeInstance inst = getAttribute(attr);
        if (inst != null) inst.setBaseValue(v);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.5D, true));
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 10.0F));
        goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.5D));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        // Only ever fights back. No targeting goals that pick fights on their own.
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    public void setSummoner(@Nullable UUID id) {
        summoner = id;
    }

    public @Nullable UUID getSummoner() {
        return summoner;
    }

    // ── Talking ──────────────────────────────────────────────────────────────
    public void say(String msg) {
        if (msg == null || msg.isBlank() || !(level() instanceof ServerLevel sl)) return;
        double r = JerryConfig.general.llm.replyRadius;
        Component c = Component.literal("<Jerry> ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : sl.players()) {
            if (r < 0 || p.distanceToSqr(this) <= r * r) p.sendSystemMessage(c);
        }
    }

    public void sayLine(String key, @Nullable String player, @Nullable String target) {
        say(Util.line(key, player, target));
    }

    // ── Combat ───────────────────────────────────────────────────────────────
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount); // /kill still works
        if (!level().isClientSide && source.getEntity() instanceof ServerPlayer p && !p.isCreative()) {
            int favor = FavorData.get(p.server).add(p.getUUID(), JerryConfig.general.hitPenalty);
            long now = level().getGameTime();
            Long last = hitLineCooldown.get(p.getUUID());
            if (last == null || now - last > 100) {
                hitLineCooldown.put(p.getUUID(), now);
                sayLine("hit", p.getGameProfile().getName(), null);
                JerryChat.noteEvent(this, p.getGameProfile().getName() + " just attacked you. Their favor is now " + favor + ".");
            }
            if (tradingPlayer == p) p.closeContainer();
        }
        return super.hurt(source, (float) (amount * JerryConfig.general.damageTakenMultiplier));
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        if (tickCount % 20 == 0 && isAlive() && getHealth() < getMaxHealth()) {
            heal((float) JerryConfig.general.regenPerSecond);
        }
        if (tradingPlayer != null) {
            getNavigation().stop();
            getLookControl().setLookAt(tradingPlayer, 30F, 30F);
            if (!tradingPlayer.isAlive() || tradingPlayer.distanceToSqr(this) > 100) tradingPlayer.closeContainer();
        }
        LivingEntity t = getTarget();
        if (t != null && (!t.isAlive() || t.distanceToSqr(this) > 64 * 64)) setTarget(null);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (tradingPlayer != null) tradingPlayer.closeContainer();
        super.remove(reason);
    }

    // ── Interaction: shop + gifts ────────────────────────────────────────────
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !isAlive()) return InteractionResult.PASS;
        String name = sp.getGameProfile().getName();
        if (getTarget() == player) {
            say("You're swinging at me and you want service? No.");
            return InteractionResult.CONSUME;
        }
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && !held.isEmpty()) {
            giveGift(sp, held);
            return InteractionResult.CONSUME;
        }
        int favor = FavorData.of(sp.server, sp.getUUID());
        if (favor <= JerryConfig.shop.shopBanFavor) {
            sayLine("angry_shop", name, null);
            return InteractionResult.CONSUME;
        }
        if (tradingPlayer != null && tradingPlayer != player) {
            say("One at a time. I'm busy with " + tradingPlayer.getGameProfile().getName() + ".");
            return InteractionResult.CONSUME;
        }
        offers = JerryShop.build(this, sp, favor);
        if (offers.isEmpty()) {
            say("Shop's empty. Tell the admin to stock shop.json.");
            return InteractionResult.CONSUME;
        }
        setTradingPlayer(sp);
        openTradingScreen(sp, Component.literal("Jerry's Shop — " + FavorData.describe(favor)), 1);
        return InteractionResult.CONSUME;
    }

    private void giveGift(ServerPlayer p, ItemStack held) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        Integer per = JerryConfig.general.giftFavor.get(id);
        int each = per != null ? per : JerryConfig.general.defaultGiftFavor;
        int stack = held.getCount();
        // gifts of whole stacks count up to 8 items so nobody farms favor with a stack of cookies
        int counted = Math.min(stack, 8);
        int total = each * counted;
        int granted = FavorData.get(p.server).addGift(p.getUUID(), total, level().getDayTime() / 24000L);
        held.shrink(counted);
        String name = p.getGameProfile().getName();
        level().playSound(null, blockPosition(), total > 0 ? SoundEvents.VILLAGER_YES : SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1F, 0.8F);
        if (total > 0 && level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HEART, getX(), getY() + 2.2, getZ(), 4, 0.4, 0.2, 0.4, 0.0);
        }
        if (total < 0) sayLine("gift_bad", name, null);
        else if (granted <= 0) sayLine("gift_meh", name, null);
        else sayLine("gift_good", name, null);
    }

    // ── Merchant ─────────────────────────────────────────────────────────────
    void registerOffer(MerchantOffer offer, int tradeIndex) {
        offerIndex.put(offer, tradeIndex);
    }

    int usesFor(int tradeIndex) {
        long now = level().getGameTime();
        long restock = Math.max(1, JerryConfig.shop.restockMinutes) * 60L * 20L;
        if (now - lastRestock > restock) {
            uses.clear();
            lastRestock = now;
        }
        return uses.getOrDefault(tradeIndex, 0);
    }

    public void resetShop() {
        offers = null;
        uses.clear();
        if (tradingPlayer != null) tradingPlayer.closeContainer();
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        tradingPlayer = player;
    }

    @Override
    public @Nullable Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        if (offers == null) offers = new MerchantOffers();
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers newOffers) {
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        Integer idx = offerIndex.get(offer);
        if (idx != null) uses.merge(idx, 1, Integer::sum);
        if (tradingPlayer instanceof ServerPlayer sp) {
            FavorData.get(sp.server).add(sp.getUUID(), JerryConfig.general.tradeFavor);
        }
        level().playSound(null, blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1F, 0.8F);
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return level().isClientSide;
    }

    public boolean stillValid(Player player) {
        return tradingPlayer == player && isAlive() && player.distanceToSqr(this) <= 100;
    }

    // ── Save data ────────────────────────────────────────────────────────────
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (summoner != null) tag.putUUID("Summoner", summoner);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Summoner")) summoner = tag.getUUID("Summoner");
    }
}
