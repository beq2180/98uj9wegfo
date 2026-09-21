package dev.chatty.localai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record ChatDecision(boolean reply, String message, boolean instruction, String goal) {
    public static ChatDecision parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("No chat decision returned");
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Expected JSON object");
        JsonObject obj = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        return new ChatDecision(
                bool(obj, "reply", true),
                str(obj, "message", ""),
                bool(obj, "instruction", false),
                str(obj, "goal", "")
        );
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        try { return obj.has(key) ? obj.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static String str(JsonObject obj, String key, String fallback) {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
}
