package dev.chatty.localai;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
    private ModEntities() {}

    public static final EntityType<AgentPlayerEntity> AGENT_PLAYER = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(LocalAiMod.MOD_ID, "agent_player"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, AgentPlayerEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(2)
                    .build()
    );

    public static void register() {
        FabricDefaultAttributeRegistry.register(AGENT_PLAYER, AgentPlayerEntity.createAgentAttributes());
    }
}
