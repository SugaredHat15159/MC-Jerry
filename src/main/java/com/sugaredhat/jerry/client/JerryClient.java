package com.sugaredhat.jerry.client;

import com.sugaredhat.jerry.JerryMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = JerryMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class JerryClient {
    private JerryClient() {}

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(JerryMod.JERRY.get(), JerryRenderer::new);
    }
}
