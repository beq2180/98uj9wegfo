package dev.chatty.localai;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.List;
import java.util.Locale;

public final class MinecraftContext {
    private MinecraftContext() {}

    public static String describe(ServerPlayerEntity player) {
        int radius = Math.max(2, Math.min(32, ConfigManager.get().contextRadius));

        String dimension = player.getWorld().getRegistryKey().getValue().toString();
        String held = player.getMainHandStack().isEmpty()
                ? "empty"
                : player.getMainHandStack().getName().getString() + " x" + player.getMainHandStack().getCount();
        String below = player.getWorld().getBlockState(player.getBlockPos().down()).getBlock().getName().getString();

        String lookingAt = "nothing specific";
        HitResult hit = player.raycast(8.0, 0.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = blockHit.getBlockPos();
            String name = player.getWorld().getBlockState(pos).getBlock().getName().getString();
            lookingAt = name + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }

        List<Entity> entities = player.getWorld().getOtherEntities(
                player,
                player.getBoundingBox().expand(radius),
                entity -> !entity.isSpectator()
        );

        String nearby = entities.stream()
                .sorted((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)))
                .limit(12)
                .map(entity -> entity.getName().getString() + " (" +
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(entity.squaredDistanceTo(player))) + " blocks)")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        return """
                Minecraft world context:
                player=%s
                position=(%.1f, %.1f, %.1f)
                dimension=%s
                health=%.1f/%.1f
                hunger=%d/20
                main_hand=%s
                block_below=%s
                looking_at=%s
                nearby_entities_within_%d_blocks=%s
                """.formatted(
                player.getName().getString(),
                player.getX(), player.getY(), player.getZ(),
                dimension,
                player.getHealth(), player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(),
                held,
                below,
                lookingAt,
                radius,
                nearby
        );
    }
}
