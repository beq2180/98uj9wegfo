package dev.chatty.localai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class LocalAiClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private LocalAiClient() {}

    public static CompletableFuture<String> ask(String prompt, String minecraftContext) {
        LocalAiConfig cfg = ConfigManager.get();
        return askWithSystem(cfg.systemPrompt, minecraftContext + "\nPlayer request: " + prompt, false);
    }

    public static CompletableFuture<String> askAgent(String agentSnapshot) {
        LocalAiConfig cfg = ConfigManager.get();
        return askWithSystem(cfg.agentSystemPrompt, agentSnapshot, true);
    }

    public static CompletableFuture<String> askChat(String chatPrompt) {
        LocalAiConfig cfg = ConfigManager.get();
        String system = cfg.chatSystemPrompt.formatted(cfg.agentName == null || cfg.agentName.isBlank() ? "Chatty" : cfg.agentName);
        return askWithSystem(system, chatPrompt, true);
    }

    private static CompletableFuture<String> askWithSystem(String systemPrompt, String userPrompt, boolean forceJson) {
        LocalAiConfig cfg = ConfigManager.get();
        String provider = normalizedProvider(cfg);

        if (!isServerReachable(1000)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Local AI server is not reachable at " + cfg.baseUrl + ". Run /localai start, then /localai diagnose if it still fails."
            ));
        }

        CompletableFuture<String> result = switch (provider) {
            case "openai", "openai-compatible", "openai_compatible" -> askOpenAiCompatible(cfg, systemPrompt, userPrompt, forceJson);
            default -> askOllama(cfg, systemPrompt, userPrompt, forceJson);
        };

        return result.exceptionallyCompose(error -> CompletableFuture.failedFuture(friendlyFailure(error, cfg)));
    }

    public static boolean isServerReachable(int timeoutMillis) {
        LocalAiConfig cfg = ConfigManager.get();
        String provider = normalizedProvider(cfg);
        String path = switch (provider) {
            case "openai", "openai-compatible", "openai_compatible" -> "/v1/models";
            default -> "/api/tags";
        };
        String url = stripTrailingSlash(cfg.baseUrl) + path;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(250, timeoutMillis)))
                    .GET()
                    .build();
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizedProvider(LocalAiConfig cfg) {
        return cfg.provider == null ? "ollama" : cfg.provider.toLowerCase(Locale.ROOT).trim();
    }

    private static CompletableFuture<String> askOllama(LocalAiConfig cfg, String systemPrompt, String userPrompt, boolean forceJson) {
        JsonObject root = new JsonObject();
        root.addProperty("model", cfg.model);
        root.addProperty("stream", false);
        if (forceJson) root.addProperty("format", "json");

        JsonObject options = new JsonObject();
        options.addProperty("temperature", forceJson ? 0.15 : 0.5);
        root.add("options", options);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        root.add("messages", messages);

        String url = stripTrailingSlash(cfg.baseUrl) + "/api/chat";
        return postJson(url, root.toString(), cfg.timeoutSeconds)
                .thenApply(body -> {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("error")) {
                        throw new IllegalStateException(json.get("error").getAsString());
                    }
                    if (!json.has("message") || !json.getAsJsonObject("message").has("content")) {
                        throw new IllegalStateException("Unexpected Ollama response: " + body);
                    }
                    return json.getAsJsonObject("message").get("content").getAsString().trim();
                });
    }

    private static CompletableFuture<String> askOpenAiCompatible(LocalAiConfig cfg, String systemPrompt, String userPrompt, boolean forceJson) {
        JsonObject root = new JsonObject();
        root.addProperty("model", cfg.model);
        root.addProperty("stream", false);
        root.addProperty("temperature", forceJson ? 0.15 : 0.5);
        if (forceJson) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            root.add("response_format", responseFormat);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        root.add("messages", messages);

        String url = stripTrailingSlash(cfg.baseUrl) + "/v1/chat/completions";
        return postJson(url, root.toString(), cfg.timeoutSeconds)
                .thenApply(body -> {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (!json.has("choices") || json.getAsJsonArray("choices").size() == 0) {
                        throw new IllegalStateException("Unexpected OpenAI-compatible response: " + body);
                    }
                    return json.getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString().trim();
                });
    }

    private static JsonObject message(String role, String content) {
        JsonObject result = new JsonObject();
        result.addProperty("role", role);
        result.addProperty("content", content == null ? "" : content);
        return result;
    }

    private static CompletableFuture<String> postJson(String url, String json, int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Local model HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                });
    }

    private static RuntimeException friendlyFailure(Throwable error, LocalAiConfig cfg) {
        Throwable root = error;
        while ((root instanceof CompletionException || root.getCause() != null) && root.getCause() != null) {
            root = root.getCause();
        }

        String simple = root.getClass().getSimpleName();
        String message = root.getMessage();
        if ("ClosedChannelException".equals(simple)) {
            return new IllegalStateException("Connection to the local AI server closed unexpectedly. The runtime probably exited or was still starting. Run /localai diagnose.");
        }
        if (root instanceof java.net.ConnectException) {
            return new IllegalStateException("Could not connect to " + cfg.baseUrl + ". Run /localai start, then /localai diagnose.");
        }
        if (root instanceof java.net.http.HttpTimeoutException) {
            return new IllegalStateException("Local AI request timed out after " + cfg.timeoutSeconds + " seconds.");
        }
        if (root instanceof IOException && (message == null || message.isBlank())) {
            return new IllegalStateException("Local AI connection failed (" + simple + "). Run /localai diagnose.");
        }
        if (root instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message == null || message.isBlank() ? simple : message, root);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:11434";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
