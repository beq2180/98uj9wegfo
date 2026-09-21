package dev.chatty.localai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record AgentAction(
        String action,
        String status,
        double x,
        double y,
        double z,
        int entityId,
        String item,
        int count,
        int ticks,
        String message
) {
    public static AgentAction parse(String raw) {
        String json = extractJson(raw);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String action = str(obj, "action", "wait").trim().toLowerCase();
        return new AgentAction(
                action,
                str(obj, "status", ""),
                number(obj, "x", 0),
                number(obj, "y", 0),
                number(obj, "z", 0),
                integer(obj, "entity_id", -1),
                str(obj, "item", ""),
                integer(obj, "count", 1),
                integer(obj, "ticks", 20),
                str(obj, "message", "")
        );
    }

    private static String extractJson(String raw) {
        if (raw == null) throw new IllegalArgumentException("Model returned no response");
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Model did not return JSON: " + text);
        return text.substring(start, end + 1);
    }

    private static String str(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject obj, String key, double fallback) {
        try { return obj.has(key) ? obj.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static int integer(JsonObject obj, String key, int fallback) {
        try { return obj.has(key) ? obj.get(key).getAsInt() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
}
