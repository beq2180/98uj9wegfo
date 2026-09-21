package dev.chatty.localai;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AgentPerception {
    private AgentPerception() {}

    public static String describe(ServerWorld world, MobEntity body, AgentInventory inventory,
                                  String ownerName, String goal, String lastResult, String activeAction) {
        LocalAiConfig cfg = ConfigManager.get();
        BlockPos center = body.getBlockPos();
        String below = blockId(world.getBlockState(center.down()));
        String feet = blockId(world.getBlockState(center));
        String head = blockId(world.getBlockState(center.up()));

        String nearbyEntities = describeEntities(world, body, cfg.agentEntityScanRadius);
        String nearbyBlocks = describeBlocks(world, center, cfg.agentBlockScanRadius, cfg.agentBlockScanVertical);
        String equipped = inventory.getEquipped() == null ? "empty" : inventory.getEquipped().toString();

        return """
                MINECRAFT AGENT SNAPSHOT
                owner=%s
                agent_entity_id=%d
                position=(%.2f, %.2f, %.2f)
                block_position=(%d,%d,%d)
                dimension=%s
                health=%.1f/%.1f
                day_time=%d
                raining=%s
                block_below=%s
                block_at_feet=%s
                block_at_head=%s
                inventory=%s
                equipped=%s
                current_goal=%s
                current_action=%s
                previous_action_result=%s

                nearby_entities:
                %s

                nearby_blocks (aggregated; nearest coordinates are listed first):
                %s

                AVAILABLE ACTIONS — return exactly one JSON object:
                {"action":"move_to","x":X,"y":Y,"z":Z,"status":"short player-facing status"}
                {"action":"mine","x":X,"y":Y,"z":Z,"status":"short player-facing status"}
                {"action":"craft","item":"minecraft:item_id","count":1,"status":"short player-facing status"}
                {"action":"equip","item":"minecraft:item_id","status":"short player-facing status"}
                {"action":"attack","entity_id":123,"status":"short player-facing status"}
                {"action":"say","message":"text","status":"short player-facing status"}
                {"action":"wait","ticks":20,"status":"short status"}
                {"action":"finish_goal","status":"instruction complete"}

                Crafting currently supports: %s.
                Movement is local pathfinding; if a target is too far away, move in shorter stages.
                Mining requires the target to be close enough. If it is too far away, move closer first.
                Combat is close-range. Move close to a listed entity before attacking. Use finish_goal when the owner's requested task is actually complete.
                """.formatted(
                ownerName,
                body.getId(),
                body.getX(), body.getY(), body.getZ(),
                center.getX(), center.getY(), center.getZ(),
                world.getRegistryKey().getValue(),
                body.getHealth(), body.getMaxHealth(),
                world.getTimeOfDay(),
                world.isRaining(),
                below, feet, head,
                inventory.describe(), equipped,
                safe(goal), safe(activeAction), safe(lastResult),
                nearbyEntities,
                nearbyBlocks,
                SimpleRecipeBook.supportedRecipes()
        );
    }

    private static String describeEntities(ServerWorld world, MobEntity body, int radius) {
        List<Entity> entities = world.getOtherEntities(body, body.getBoundingBox().expand(radius), e -> !e.isSpectator());
        entities.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(body)));
        if (entities.isEmpty()) return "  none";

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Entity entity : entities) {
            if (shown++ >= 24) break;
            double distance = Math.sqrt(entity.squaredDistanceTo(body));
            String type = String.valueOf(Registries.ENTITY_TYPE.getId(entity.getType()));
            out.append("  id=").append(entity.getId())
                    .append(" type=").append(type)
                    .append(" name=").append(entity.getName().getString())
                    .append(String.format(Locale.ROOT, " pos=(%.1f,%.1f,%.1f) distance=%.1f", entity.getX(), entity.getY(), entity.getZ(), distance));
            if (entity instanceof LivingEntity living) {
                out.append(String.format(Locale.ROOT, " hp=%.1f/%.1f", living.getHealth(), living.getMaxHealth()));
            }
            out.append(" hostile=").append(entity instanceof HostileEntity).append('\n');
        }
        return out.toString().trim();
    }

    private static String describeBlocks(ServerWorld world, BlockPos center, int horizontalRadius, int verticalRadius) {
        Map<String, BlockSummary> summaries = new LinkedHashMap<>();
        int r = Math.max(2, Math.min(12, horizontalRadius));
        int ry = Math.max(2, Math.min(8, verticalRadius));

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    String id = blockId(state);
                    double d2 = center.getSquaredDistance(pos);
                    summaries.computeIfAbsent(id, ignored -> new BlockSummary()).add(pos, d2);
                }
            }
        }

        List<Map.Entry<String, BlockSummary>> sorted = new ArrayList<>(summaries.entrySet());
        sorted.sort(Comparator.comparingDouble(e -> e.getValue().nearestDistanceSquared));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, BlockSummary> entry : sorted) {
            if (shown++ >= 40) break;
            BlockSummary summary = entry.getValue();
            out.append("  ").append(entry.getKey())
                    .append(" count=").append(summary.count)
                    .append(" nearest=").append(summary)
                    .append('\n');
        }
        return out.length() == 0 ? "  none" : out.toString().trim();
    }

    private static String blockId(BlockState state) {
        return String.valueOf(Registries.BLOCK.getId(state.getBlock()));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.replace('\n', ' ');
    }

    private static final class BlockSummary {
        int count;
        double nearestDistanceSquared = Double.MAX_VALUE;
        final List<PosSample> samples = new ArrayList<>();

        void add(BlockPos pos, double d2) {
            count++;
            if (d2 < nearestDistanceSquared) nearestDistanceSquared = d2;
            samples.add(new PosSample(pos.toImmutable(), d2));
            samples.sort(Comparator.comparingDouble(PosSample::distanceSquared));
            if (samples.size() > 4) samples.remove(samples.size() - 1);
        }

        @Override
        public String toString() {
            return samples.stream()
                    .map(sample -> "(" + sample.pos().getX() + "," + sample.pos().getY() + "," + sample.pos().getZ() + ")")
                    .toList()
                    .toString();
        }
    }

    private record PosSample(BlockPos pos, double distanceSquared) {}
}
