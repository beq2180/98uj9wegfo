package dev.chatty.localai;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/** Lets players talk to the companion through ordinary Minecraft chat. */
public final class AgentChatBridge {
    private AgentChatBridge() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (!AgentController.exists()) return;
            String text = message.getContent().getString().trim();
            if (text.isEmpty()) return;
            AgentController.onChatMessage(sender, text);
        });
    }
}
