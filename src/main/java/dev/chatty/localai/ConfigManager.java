package dev.chatty.localai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("localai.json");
    private static volatile LocalAiConfig config;

    private ConfigManager() {}

    public static synchronized LocalAiConfig load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (!Files.exists(CONFIG_PATH)) {
                config = new LocalAiConfig();
                save(config);
                return config;
            }
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            LocalAiConfig loaded = GSON.fromJson(json, LocalAiConfig.class);
            config = loaded == null ? new LocalAiConfig() : loaded;
            return config;
        } catch (Exception e) {
            LocalAiMod.LOGGER.error("Failed to load LocalAI config; using defaults", e);
            config = new LocalAiConfig();
            return config;
        }
    }

    public static synchronized void save(LocalAiConfig value) throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.writeString(CONFIG_PATH, GSON.toJson(value), StandardCharsets.UTF_8);
        config = value;
    }

    public static LocalAiConfig get() {
        LocalAiConfig current = config;
        return current == null ? load() : current;
    }

    public static Path path() {
        return CONFIG_PATH;
    }
}
