package com.sugaredhat.jerry.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sugaredhat.jerry.JerryEntity;
import com.sugaredhat.jerry.JerryMod;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class JerryRenderer extends HumanoidMobRenderer<JerryEntity, HumanoidModel<JerryEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(JerryMod.MODID, "textures/entity/jerry.png");

    public JerryRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.6F);
    }

    @Override
    public ResourceLocation getTextureLocation(JerryEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(JerryEntity entity, PoseStack pose, float partialTick) {
        pose.scale(1.1F, 1.1F, 1.1F);   // a little bigger than everyone else
    }
}
