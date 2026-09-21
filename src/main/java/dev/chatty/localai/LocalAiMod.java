package dev.chatty.localai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalAiMod implements ModInitializer {
    public static final String MOD_ID = "localai_minecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ConfigManager.load();
        ModEntities.register();
        LocalAiCommands.register();
        AgentChatBridge.register();
        AgentController.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("LocalAI Minecraft v0.3 companion loaded. Config: {}", ConfigManager.path());
            if (ConfigManager.get().autoStart) LOGGER.info(LocalRuntimeManager.start());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AgentController.shutdown();
            if (LocalRuntimeManager.isRunning()) LOGGER.info(LocalRuntimeManager.stop());
        });
    }
}
