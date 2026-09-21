package dev.chatty.localai;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side autonomous agent loop.
 *
 * v0.2 deliberately uses a vanilla husk as a temporary visible body. That keeps
 * the prototype server-side and avoids a custom renderer while the perception,
 * planning, navigation, mining, crafting and combat loop is being proven out.
 */
public final class AgentController {
    private static AgentPlayerEntity body;
    private static UUID ownerUuid;
    private static String ownerName = "unknown";
    private static String goal = defaultGoal();
    private static boolean explicitGoal;
    private static boolean chatThinking;
    private static String lastResult = "No action has run yet.";
    private static boolean autonomous;
    private static boolean thinking;
    private static long tickCounter;
    private static long nextPlanTick;
    private static long planToken;
    private static ActiveAction activeAction;

    private static final AgentInventory INVENTORY = new AgentInventory();

    private AgentController() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(AgentController::tick);
    }

    public static String spawn(ServerPlayerEntity owner) {
        remove(false);
        planToken++;
        ServerWorld world = owner.getServerWorld();
        AgentPlayerEntity spawned = ModEntities.AGENT_PLAYER.create(world);
        if (spawned == null) return "Could not create the companion body.";

        spawned.refreshPositionAndAngles(owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5, owner.getYaw(), 0.0f);
        spawned.setCustomName(Text.literal(agentName()));
        spawned.setCustomNameVisible(true);
        spawned.setPersistent();
        spawned.setCanPickUpLoot(false);
        spawned.setHealth(spawned.getMaxHealth());

        if (!world.spawnEntity(spawned)) return "Minecraft refused to spawn the companion body.";

        body = spawned;
        ownerUuid = owner.getUuid();
        ownerName = owner.getName().getString();
        goal = defaultGoal();
        explicitGoal = false;
        lastResult = "Spawned beside owner and ready to play survival.";
        autonomous = ConfigManager.get().agentAutonomousByDefault;
        thinking = false;
        chatThinking = false;
        activeAction = null;
        tickCounter = 0;
        nextPlanTick = 10;
        INVENTORY.clear();
        return "Spawned " + agentName() + " with a player model. Just talk normally in chat; commands are optional now.";
    }

    public static String remove(boolean announce) {
        if (body != null && !body.isRemoved()) {
            body.discard();
        }
        planToken++;
        body = null;
        ownerUuid = null;
        ownerName = "unknown";
        explicitGoal = false;
        chatThinking = false;
        thinking = false;
        activeAction = null;
        autonomous = false;
        INVENTORY.clear();
        return announce ? "Removed LocalAI Agent." : "";
    }

    public static String setTask(ServerPlayerEntity owner, String newGoal) {
        if (!exists()) return "Spawn the agent first with /localai agent spawn.";
        ownerUuid = owner.getUuid();
        ownerName = owner.getName().getString();
        goal = newGoal == null || newGoal.isBlank() ? defaultGoal() : newGoal.trim();
        explicitGoal = newGoal != null && !newGoal.isBlank();
        planToken++;
        thinking = false;
        autonomous = true;
        activeAction = null;
        stopNavigation();
        lastResult = "New owner instruction received: " + goal;
        nextPlanTick = tickCounter + 1;
        return "Task set: " + goal;
    }

    public static String pause() {
        if (!exists()) return "No agent is spawned.";
        planToken++;
        thinking = false;
        autonomous = false;
        activeAction = null;
        stopNavigation();
        return "Agent autonomy paused.";
    }

    public static String resume() {
        if (!exists()) return "No agent is spawned.";
        autonomous = true;
        nextPlanTick = tickCounter + 1;
        return "Agent autonomy resumed.";
    }

    public static String step() {
        if (!exists()) return "No agent is spawned.";
        if (thinking || activeAction != null) return "Agent is already busy.";
        MinecraftServer server = body.getServer();
        if (server == null) return "Server is unavailable.";
        nextPlanTick = tickCounter;
        requestPlan(server);
        return "Requested one planning step.";
    }

    public static String status() {
        if (!exists()) return "agent=not spawned";
        return "agent=spawned"
                + ", autonomous=" + autonomous
                + ", thinking=" + thinking
                + ", action=" + activeActionName()
                + ", explicitGoal=" + explicitGoal
                + ", goal=" + goal
                + ", pos=" + fmt(body.getX()) + "," + fmt(body.getY()) + "," + fmt(body.getZ())
                + ", inventory=" + INVENTORY.describe()
                + ", last=" + lastResult;
    }

    public static String inventory() {
        return INVENTORY.describe();
    }

    public static boolean exists() {
        return body != null && !body.isRemoved() && body.isAlive();
    }

    public static boolean isOwner(ServerPlayerEntity player) {
        return player != null && ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    public static void onChatMessage(ServerPlayerEntity sender, String text) {
        if (!exists() || chatThinking || text == null || text.isBlank()) return;
        LocalAiConfig cfg = ConfigManager.get();
        boolean owner = isOwner(sender);
        if (!owner && !cfg.agentAllowNonOwnerConversation) return;
        if (!cfg.agentRespondToAllChat) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.contains(agentName().toLowerCase(Locale.ROOT))) return;
        }

        chatThinking = true;
        MinecraftServer server = body.getServer();
        if (server == null) { chatThinking = false; return; }
        String snapshot = compactChatContext(sender, text, owner);
        LocalAiClient.askChat(snapshot).whenComplete((answer, error) -> server.execute(() -> {
            chatThinking = false;
            if (!exists()) return;
            if (error != null) {
                LocalAiMod.LOGGER.warn("Companion chat failed", error);
                return;
            }
            try {
                ChatDecision decision = ChatDecision.parse(answer);
                if (decision.reply() && !decision.message().isBlank()) broadcastChat(decision.message().trim());
                if (owner && decision.instruction() && !decision.goal().isBlank()) {
                    goal = decision.goal().trim();
                    explicitGoal = true;
                    planToken++;
                    thinking = false;
                    activeAction = null;
                    stopNavigation();
                    lastResult = "New instruction from normal chat: " + goal;
                    nextPlanTick = tickCounter + 1;
                }
            } catch (Exception e) {
                LocalAiMod.LOGGER.warn("Invalid companion chat JSON: {}", answer, e);
            }
        }));
    }

    public static void shutdown() {
        remove(false);
    }

    private static void tick(MinecraftServer server) {
        tickCounter++;
        if (!exists()) return;

        tickActiveAction();
        syncHeldItem();

        if (autonomous && !thinking && activeAction == null && tickCounter >= nextPlanTick) {
            requestPlan(server);
        }
    }

    private static void requestPlan(MinecraftServer server) {
        if (!exists() || thinking || activeAction != null) return;
        if (!(body.getWorld() instanceof ServerWorld world)) return;
        thinking = true;
        long token = planToken;

        String snapshot = AgentPerception.describe(
                world,
                body,
                INVENTORY,
                ownerName,
                goal,
                lastResult,
                activeActionName()
        );

        CompletableFuture<String> future = LocalAiClient.askAgent(snapshot);
        future.whenComplete((answer, error) -> server.execute(() -> {
            if (token != planToken) return;
            thinking = false;
            if (!exists()) return;
            if (error != null) {
                lastResult = "AI planning error: " + rootMessage(error);
                notifyOwner("§cPlanning error: " + rootMessage(error));
                nextPlanTick = tickCounter + 80;
                return;
            }
            try {
                AgentAction action = AgentAction.parse(answer);
                if (ConfigManager.get().agentVerboseStatus && !action.status().isBlank()) {
                    notifyOwner("§7" + action.status());
                }
                execute(action);
            } catch (Exception e) {
                lastResult = "Could not parse model action: " + e.getMessage();
                LocalAiMod.LOGGER.warn("Agent returned invalid action: {}", answer, e);
                notifyOwner("§cInvalid agent JSON; replanning shortly.");
                nextPlanTick = tickCounter + 50;
            }
        }));
    }

    private static void execute(AgentAction action) {
        if (!(body.getWorld() instanceof ServerWorld world)) return;
        String kind = action.action();
        switch (kind) {
            case "move_to", "move" -> startMove(action);
            case "mine", "mine_block" -> startMining(action);
            case "craft" -> {
                SimpleRecipeBook.CraftResult result = SimpleRecipeBook.craft(INVENTORY, action.item(), action.count());
                complete(result.message());
            }
            case "equip" -> {
                if (INVENTORY.equip(action.item())) {
                    complete("Equipped " + normalizeItem(action.item()));
                } else {
                    complete("Could not equip " + action.item() + ": item is not in inventory.");
                }
            }
            case "attack", "fight" -> attack(world, action.entityId());
            case "say", "chat" -> {
                String message = action.message().isBlank() ? action.status() : action.message();
                if (!message.isBlank()) {
                    broadcastChat(message);
                }
                complete("Said: " + message);
            }
            case "finish_goal", "done" -> {
                explicitGoal = false;
                goal = defaultGoal();
                complete("Owner task finished; returning to normal survival play.");
            }
            case "wait", "idle", "noop" -> {
                int ticks = Math.max(1, Math.min(200, action.ticks()));
                activeAction = ActiveAction.waiting(ticks, tickCounter);
                lastResult = "Waiting for " + ticks + " ticks.";
            }
            default -> complete("Unknown action '" + kind + "'. Use one of the listed actions.");
        }
    }

    private static void startMove(AgentAction action) {
        if (!(body.getWorld() instanceof ServerWorld world)) return;
        Vec3d target = new Vec3d(action.x() + 0.5, action.y(), action.z() + 0.5);
        double distance = body.getPos().distanceTo(target);
        int max = Math.max(4, ConfigManager.get().agentMaxMoveDistance);
        if (distance > max) {
            complete("Move target is " + fmt(distance) + " blocks away; max per move is " + max + ". Choose a closer waypoint.");
            return;
        }

        boolean started = body.getNavigation().startMovingTo(target.x, target.y, target.z, ConfigManager.get().agentMoveSpeed);
        if (!started) {
            complete("Pathfinder could not find a route to " + blockPosString(action.x(), action.y(), action.z()) + ".");
            return;
        }
        long timeout = Math.max(60, (long) (distance * 35));
        activeAction = ActiveAction.moving(target, tickCounter, tickCounter + timeout);
        lastResult = "Moving toward " + blockPosString(action.x(), action.y(), action.z()) + ".";
    }

    private static void startMining(AgentAction action) {
        if (!(body.getWorld() instanceof ServerWorld world)) return;
        BlockPos pos = BlockPos.ofFloored(action.x(), action.y(), action.z());
        double distance = body.getPos().distanceTo(Vec3d.ofCenter(pos));
        if (distance > 5.25) {
            complete("Block at " + pos.toShortString() + " is too far away (" + fmt(distance) + " blocks). Move closer first.");
            return;
        }

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            complete("There is no block to mine at " + pos.toShortString() + ".");
            return;
        }
        float hardness = state.getHardness(world, pos);
        if (hardness < 0) {
            complete("That block is unbreakable in this prototype: " + Registries.BLOCK.getId(state.getBlock()));
            return;
        }

        ItemStack best = bestMiningTool(state);
        if (!best.isEmpty()) {
            Identifier id = Registries.ITEM.getId(best.getItem());
            if (id != null) INVENTORY.equip(id.toString());
        }
        double speed = best.isEmpty() ? 1.0 : Math.max(1.0, best.getMiningSpeedMultiplier(state));
        int ticks = (int) Math.ceil(10 + (hardness * 28.0 / speed));
        ticks = Math.max(8, Math.min(180, ticks));
        activeAction = ActiveAction.mining(pos, ticks, tickCounter);
        lastResult = "Mining " + Registries.BLOCK.getId(state.getBlock()) + " at " + pos.toShortString() + ".";
    }

    private static void finishMining(BlockPos pos) {
        if (!(body.getWorld() instanceof ServerWorld world)) return;
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            complete("Mining target disappeared before it was finished.");
            return;
        }

        ItemStack tool = INVENTORY.equippedStack();
        List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, world.getBlockEntity(pos), body, tool);
        boolean broken = world.breakBlock(pos, false, body);
        if (!broken) {
            complete("Minecraft refused to break " + Registries.BLOCK.getId(state.getBlock()) + " at " + pos.toShortString() + ".");
            return;
        }
        for (ItemStack drop : drops) INVENTORY.add(drop);
        String dropText = drops.isEmpty() ? "no collectible drops" : drops.stream()
                .map(s -> String.valueOf(Registries.ITEM.getId(s.getItem())) + " x" + s.getCount())
                .reduce((a, b) -> a + ", " + b)
                .orElse("no collectible drops");
        complete("Mined " + Registries.BLOCK.getId(state.getBlock()) + "; collected " + dropText + ".");
    }

    private static ItemStack bestMiningTool(BlockState state) {
        return INVENTORY.entries().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .map(Registries.ITEM::get)
                .filter(item -> item != null)
                .map(ItemStack::new)
                .max(Comparator.comparingDouble(stack -> stack.getMiningSpeedMultiplier(state)))
                .orElse(ItemStack.EMPTY);
    }

    private static void attack(ServerWorld world, int entityId) {
        Entity target = world.getEntityById(entityId);
        if (!(target instanceof LivingEntity living) || target == body || !target.isAlive()) {
            complete("Attack target id=" + entityId + " is not a living nearby entity anymore.");
            return;
        }
        if (target instanceof PlayerEntity && !ConfigManager.get().agentCanAttackPlayers) {
            complete("Player combat is disabled by agentCanAttackPlayers=false.");
            return;
        }
        double distance = body.distanceTo(target);
        if (distance > 3.3) {
            complete("Target id=" + entityId + " is " + fmt(distance) + " blocks away. Move closer before attacking.");
            return;
        }

        body.lookAtEntity(target, 30.0f, 30.0f);
        body.swingHand(Hand.MAIN_HAND);

        // Keep the fighting prototype generic: no weapon/item-specific combat logic.
        // The visual held item is restored by syncHeldItem() on the next server tick.
        body.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        boolean hit = body.tryAttack(target);
        if (hit) {
            complete("Attacked entity id=" + entityId + " (" + target.getName().getString() + "). Remaining hp=" + fmt(living.getHealth()) + ".");
        } else {
            complete("Attack on entity id=" + entityId + " did not connect.");
        }
    }

    private static void tickActiveAction() {
        if (activeAction == null || !exists()) return;
        switch (activeAction.type) {
            case MOVE -> tickMove();
            case MINE -> tickMine();
            case WAIT -> tickWait();
        }
    }

    private static void tickMove() {
        if (activeAction == null || activeAction.target == null) return;
        double distance = body.getPos().distanceTo(activeAction.target);
        if (distance <= 1.25) {
            stopNavigation();
            complete("Reached movement target near " + activeAction.target + ".");
            return;
        }
        if (tickCounter > activeAction.deadlineTick) {
            stopNavigation();
            complete("Movement timed out with " + fmt(distance) + " blocks remaining. Replan with a closer waypoint.");
            return;
        }
        if (tickCounter - activeAction.startTick > 20 && body.getNavigation().isIdle()) {
            complete("Pathfinder became idle before reaching the target. Try another route or waypoint.");
        }
    }

    private static void tickMine() {
        if (activeAction == null || activeAction.blockPos == null) return;
        if (activeAction.remainingTicks % 6 == 0) body.swingHand(Hand.MAIN_HAND);
        activeAction.remainingTicks--;
        if (activeAction.remainingTicks <= 0) finishMining(activeAction.blockPos);
    }

    private static void tickWait() {
        activeAction.remainingTicks--;
        if (activeAction.remainingTicks <= 0) complete("Finished waiting.");
    }

    private static void complete(String result) {
        activeAction = null;
        lastResult = result;
        nextPlanTick = tickCounter + Math.max(4, ConfigManager.get().agentThinkIntervalTicks);
        LocalAiMod.LOGGER.info("[Agent] {}", result);
    }

    private static void stopNavigation() {
        if (exists()) body.getNavigation().stop();
    }

    private static void syncHeldItem() {
        if (!exists()) return;
        ItemStack stack = INVENTORY.equippedStack();
        body.equipStack(EquipmentSlot.MAINHAND, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
    }

    private static void notifyOwner(String message) {
        if (!exists() || ownerUuid == null || body.getServer() == null) return;
        ServerPlayerEntity player = body.getServer().getPlayerManager().getPlayer(ownerUuid);
        if (player != null) player.sendMessage(Text.literal("§8[§bLocalAI Agent§8] " + message), false);
    }

    private static String activeActionName() {
        if (thinking) return "thinking";
        return activeAction == null ? "idle" : activeAction.type.name().toLowerCase(Locale.ROOT);
    }

    private static String normalizeItem(String item) {
        if (item == null || item.isBlank()) return "unknown";
        return item.contains(":") ? item : "minecraft:" + item;
    }

    private static String blockPosString(double x, double y, double z) {
        return "(" + (int) Math.floor(x) + "," + (int) Math.floor(y) + "," + (int) Math.floor(z) + ")";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String compactChatContext(ServerPlayerEntity sender, String text, boolean owner) {
        String nearby = body == null ? "unknown" : String.format(Locale.ROOT, "companion_pos=(%.1f,%.1f,%.1f) hp=%.1f/%.1f current_goal=%s", body.getX(), body.getY(), body.getZ(), body.getHealth(), body.getMaxHealth(), goal);
        return "sender=" + sender.getName().getString() + "\nowner=" + owner + "\n" + nearby + "\nchat_message=" + text;
    }

    private static void broadcastChat(String message) {
        if (!exists() || body.getServer() == null || message == null || message.isBlank()) return;
        body.getServer().getPlayerManager().broadcast(Text.literal("§7<§b" + agentName() + "§7> §f" + message), false);
    }

    private static String agentName() {
        String name = ConfigManager.get().agentName;
        return name == null || name.isBlank() ? "Chatty" : name.trim();
    }

    private static String defaultGoal() {
        return "Play normal survival Minecraft alongside the owner: stay reasonably nearby, gather resources, improve tools, explore, and defend yourself. Wait naturally when there is nothing useful to do.";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private enum ActionType { MOVE, MINE, WAIT }

    private static final class ActiveAction {
        final ActionType type;
        final Vec3d target;
        final BlockPos blockPos;
        int remainingTicks;
        final long startTick;
        final long deadlineTick;

        private ActiveAction(ActionType type, Vec3d target, BlockPos blockPos, int remainingTicks, long startTick, long deadlineTick) {
            this.type = type;
            this.target = target;
            this.blockPos = blockPos;
            this.remainingTicks = remainingTicks;
            this.startTick = startTick;
            this.deadlineTick = deadlineTick;
        }

        static ActiveAction moving(Vec3d target, long start, long deadline) {
            return new ActiveAction(ActionType.MOVE, target, null, 0, start, deadline);
        }

        static ActiveAction mining(BlockPos pos, int ticks, long start) {
            return new ActiveAction(ActionType.MINE, null, pos, ticks, start, start + ticks + 20L);
        }

        static ActiveAction waiting(int ticks, long start) {
            return new ActiveAction(ActionType.WAIT, null, null, ticks, start, start + ticks + 20L);
        }
    }
}
