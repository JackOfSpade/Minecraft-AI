package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public record ChatToolCall(String id, String name, String arguments) {
    public JsonObject parsedArguments() {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(arguments).getAsJsonObject();
        } catch (IllegalStateException | JsonParseException exception) {
            return new JsonObject();
        }
    }
}
