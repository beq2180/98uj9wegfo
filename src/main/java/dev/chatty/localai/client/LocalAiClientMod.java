package dev.chatty.localai.client;

import dev.chatty.localai.LocalAiMod;
import dev.chatty.localai.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

public final class LocalAiClientMod implements ClientModInitializer {
    public static final EntityModelLayer AGENT_PLAYER_LAYER =
            new EntityModelLayer(Identifier.of(LocalAiMod.MOD_ID, "agent_player"), "main");

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(AGENT_PLAYER_LAYER,
                () -> TexturedModelData.of(PlayerEntityModel.getTexturedModelData(Dilation.NONE, false), 64, 64));
        EntityRendererRegistry.register(ModEntities.AGENT_PLAYER, AgentPlayerRenderer::new);
    }
}
