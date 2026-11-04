package atlas.script.raven.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Raven script API: JSON values, over Gson (2026-11-02). add(...) returns this, so calls chain. */
public class Json {
    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private final JsonElement element;

    private Json(JsonElement element) {
        this.element = element == null ? JsonNull.INSTANCE : element;
    }

    /** Parsed, or a null value when it is not JSON. */
    public static Json parse(String jsonString) {
        try {
            return new Json(new JsonParser().parse(jsonString));
        } catch (Exception e) {
            return nullValue();
        }
    }

    public static Json object() {
        return new Json(new JsonObject());
    }

    public static Json array() {
        return new Json(new JsonArray());
    }

    public static Json string(String value) {
        return new Json(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public static Json number(Number value) {
        return new Json(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public static Json booleanValue(boolean value) {
        return new Json(new JsonPrimitive(value));
    }

    public static Json nullValue() {
        return new Json(JsonNull.INSTANCE);
    }

    public Type type() {
        if (this.element.isJsonObject()) {
            return Type.OBJECT;
        }
        if (this.element.isJsonArray()) {
            return Type.ARRAY;
        }
        if (this.element.isJsonNull()) {
            return Type.NULL;
        }
        JsonPrimitive primitive = this.element.getAsJsonPrimitive();
        return primitive.isBoolean() ? Type.BOOLEAN : primitive.isNumber() ? Type.NUMBER : Type.STRING;
    }

    public Json add(String key, Json value) {
        if (this.element.isJsonObject()) {
            this.element.getAsJsonObject().add(key, value == null ? JsonNull.INSTANCE : value.element);
        }
        return this;
    }

    public Json add(String key, String val) {
        return this.add(key, string(val));
    }

    public Json add(String key, Number val) {
        return this.add(key, number(val));
    }

    public Json add(String key, boolean val) {
        return this.add(key, booleanValue(val));
    }

    /** The member, or a null value. */
    public Json get(String key) {
        return this.element.isJsonObject() && this.element.getAsJsonObject().has(key)
                ? new Json(this.element.getAsJsonObject().get(key)) : nullValue();
    }

    public boolean has(String key) {
        return this.element.isJsonObject() && this.element.getAsJsonObject().has(key);
    }

    public Json add(Json value) {
        if (this.element.isJsonArray()) {
            this.element.getAsJsonArray().add(value == null ? JsonNull.INSTANCE : value.element);
        }
        return this;
    }

    public Json add(String val) {
        return this.add(string(val));
    }

    public Json add(Number val) {
        return this.add(number(val));
    }

    public Json add(boolean val) {
        return this.add(booleanValue(val));
    }

    public List<Json> asArray() {
        List<Json> out = new ArrayList<Json>();
        if (this.element.isJsonArray()) {
            for (JsonElement item : this.element.getAsJsonArray()) {
                out.add(new Json(item));
            }
        }
        return out;
    }

    public String asString() {
        if (this.element.isJsonNull()) {
            return null;
        }
        return this.element.isJsonPrimitive() ? this.element.getAsString() : this.element.toString();
    }

    public int asInt() {
        return this.element.isJsonPrimitive() ? this.element.getAsInt() : 0;
    }

    public double asDouble() {
        return this.element.isJsonPrimitive() ? this.element.getAsDouble() : 0.0;
    }

    public long asLong() {
        return this.element.isJsonPrimitive() ? this.element.getAsLong() : 0L;
    }

    public float asFloat() {
        return this.element.isJsonPrimitive() ? this.element.getAsFloat() : 0.0F;
    }

    public boolean asBoolean() {
        return this.element.isJsonPrimitive() && this.element.getAsBoolean();
    }

    public LinkedHashSet<String> keys() {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (this.element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : this.element.getAsJsonObject().entrySet()) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    public Json remove(String key) {
        if (this.element.isJsonObject()) {
            this.element.getAsJsonObject().remove(key);
        }
        return this;
    }

    /** Gson 2.2 (the game's) has no JsonArray.remove: the array is rebuilt without that item. */
    public Json remove(final int index) {
        if (this.element.isJsonArray()) {
            JsonArray array = this.element.getAsJsonArray();
            List<JsonElement> kept = new ArrayList<JsonElement>();
            for (int i = 0; i < array.size(); i++) {
                if (i != index) {
                    kept.add(array.get(i));
                }
            }
            while (array.size() > 0) {
                removeLast(array);
            }
            for (JsonElement item : kept) {
                array.add(item);
            }
        }
        return this;
    }

    private static void removeLast(JsonArray array) {
        try {
            java.lang.reflect.Field field = JsonArray.class.getDeclaredField("elements");
            field.setAccessible(true);
            List<?> elements = (List<?>) field.get(array);
            elements.remove(elements.size() - 1);
        } catch (Exception e) {
            throw new IllegalStateException("cannot remove from this JSON array", e);
        }
    }

    @Override
    public String toString() {
        return this.element.toString();
    }
}
