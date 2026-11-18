package atlas.property.properties;

import com.google.gson.JsonObject;
import atlas.property.Property;

import java.util.function.BooleanSupplier;

public class ColorProperty extends Property<Integer> {
    public ColorProperty(String name, Integer color) {
        this(name, color, null);
    }

    public ColorProperty(String string, Integer color, BooleanSupplier check) {
        super(string, color, rgb -> rgb <= 16777215, check);
    }

    @Override
    public String getValuePrompt() {
        return "RGB";
    }

    @Override
    public String formatValue() {
        String hex = String.format("%06X", this.getValue()).substring(0,6);
        return String.format("&c%s&a%s&9%s", hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6));
    }

    @Override
    public boolean parseString(String string) {
        return this.setValue(parseHex(string));
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        return this.parseString(jsonObject.get(this.getName()).getAsString());
    }

    public static int parseHex(String string) {
        String digits = string.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.isEmpty() || digits.length() > 8) {
            throw new NumberFormatException("not a colour: " + string);
        }
        return Integer.parseUnsignedInt(digits, 16);
    }

    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), String.format("%06X", this.getValue()));
    }
}
