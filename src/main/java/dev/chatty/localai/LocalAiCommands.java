package dev.chatty.localai;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class LocalAiCommands {
    private LocalAiCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("localai")
                        .then(literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] " + LocalRuntimeManager.status()), false);
                                    ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] " + AgentController.status()), false);
                                    return 1;
                                }))
                        .then(literal("start")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    String result = LocalRuntimeManager.start();
                                    ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] " + result), false);
                                    return 1;
                                }))
                        .then(literal("stop")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    String result = LocalRuntimeManager.stop();
                                    ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] " + result), false);
                                    return 1;
                                }))
                        .then(literal("diagnose")
                                .executes(ctx -> {
                                    String result = LocalRuntimeManager.diagnostics();
                                    for (String line : result.split("\\n")) {
                                        ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] " + line), false);
                                    }
                                    return 1;
                                }))
                        .then(literal("reload")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    ConfigManager.load();
                                    ctx.getSource().sendFeedback(() -> Text.literal("[LocalAI] Reloaded " + ConfigManager.path()), false);
                                    return 1;
                                }))
                        .then(literal("ask")
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                            String prompt = StringArgumentType.getString(ctx, "prompt");
                                            String worldContext = MinecraftContext.describe(player);

                                            player.sendMessage(Text.literal("§8[§bLocalAI§8] §7Thinking..."), false);

                                            LocalAiClient.ask(prompt, worldContext)
                                                    .whenComplete((answer, error) -> ctx.getSource().getServer().execute(() -> {
                                                        if (error != null) {
                                                            String message = rootMessage(error);
                                                            player.sendMessage(Text.literal("§8[§bLocalAI§8] §cError: " + message), false);
                                                            LocalAiMod.LOGGER.warn("LocalAI request failed", error);
                                                        } else {
                                                            player.sendMessage(Text.literal("§8[§bLocalAI§8] §f" + answer), false);
                                                        }
                                                    }));
                                            return 1;
                                        })))
                        .then(literal("agent")
                                .then(literal("spawn")
                                        .executes(ctx -> {
                                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                            send(player, AgentController.spawn(player));
                                            return 1;
                                        }))
                                .then(literal("remove")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), AgentController.remove(true));
                                            return 1;
                                        }))
                                .then(literal("status")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), AgentController.status());
                                            return 1;
                                        }))
                                .then(literal("inventory")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), "Inventory: " + AgentController.inventory());
                                            return 1;
                                        }))
                                .then(literal("pause")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), AgentController.pause());
                                            return 1;
                                        }))
                                .then(literal("resume")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), AgentController.resume());
                                            return 1;
                                        }))
                                .then(literal("step")
                                        .executes(ctx -> {
                                            send(ctx.getSource().getPlayerOrThrow(), AgentController.step());
                                            return 1;
                                        }))
                                .then(literal("task")
                                        .then(argument("instruction", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                                    String instruction = StringArgumentType.getString(ctx, "instruction");
                                                    send(player, AgentController.setTask(player, instruction));
                                                    return 1;
                                                }))))
                )
        );
    }

    private static void send(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal("§8[§bLocalAI Agent§8] §f" + message), false);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
