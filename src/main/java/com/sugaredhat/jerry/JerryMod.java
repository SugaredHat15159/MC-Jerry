package com.sugaredhat.jerry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(JerryMod.MODID)
public class JerryMod {
    public static final String MODID = "jerry";
    public static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<JerryEntity>> JERRY =
            ENTITIES.register("jerry", () -> EntityType.Builder.<JerryEntity>of(JerryEntity::new, MobCategory.MISC)
                    .sized(0.66F, 2.15F)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .build("jerry"));

    public JerryMod(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(JerryMod::onAttributes);

        NeoForge.EVENT_BUS.addListener(JerryMod::onServerStarting);
        NeoForge.EVENT_BUS.addListener(JerryMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(JerryMod::onCommands);
        NeoForge.EVENT_BUS.addListener(JerryMod::onChat);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(JERRY.get(), JerryEntity.createAttributes().build());
    }

    private static void onServerStarting(ServerStartingEvent event) {
        JerryConfig.load();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        JerryChat.reset();
        Offerings.reset();
    }

    private static void onCommands(RegisterCommandsEvent event) {
        JerryCommands.register(event.getDispatcher(), event.getBuildContext());
    }

    private static void onChat(ServerChatEvent event) {
        JerryChat.onChat(event.getPlayer(), event.getRawText());
    }
}
