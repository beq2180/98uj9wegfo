package dev.chatty.localai.client;

import dev.chatty.localai.AgentPlayerEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

/** Renders the companion as an actual vanilla player-shaped model instead of a mob skin. */
public final class AgentPlayerRenderer extends BipedEntityRenderer<AgentPlayerEntity, PlayerEntityModel<AgentPlayerEntity>> {
    public AgentPlayerRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(LocalAiClientMod.AGENT_PLAYER_LAYER), false), 0.5f);
        addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));
    }

    @Override
    public Identifier getTexture(AgentPlayerEntity entity) {
        return DefaultSkinHelper.getTexture();
    }
}
