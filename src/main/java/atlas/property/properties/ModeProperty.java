package atlas.property.properties;

import com.google.gson.JsonObject;
import atlas.property.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class ModeProperty extends Property<Integer> {
    private String[] modes;

    private final Set<Integer> hidden = new HashSet<>();
    private final Map<String, String> aliases = new HashMap<>();

    public ModeProperty(String name, Integer value, String[] modes) {
        this(name, value, modes, null);
    }

    public ModeProperty(String name, Integer value, String[] modes, BooleanSupplier check) {
        super(name, value, check);
        this.modes = modes;
    }

    public ModeProperty hide(String... names) {
        for (String name : names) {
            for (int i = 0; i < this.modes.length; i++) {
                if (this.modes[i].equals(name)) {
                    this.hidden.add(i);
                }
            }
        }
        return this;
    }

    public ModeProperty retire(String replacement, String... names) {
        this.hide(names);
        for (String name : names) {
            this.alias(name, replacement);
        }
        return this;
    }

    public ModeProperty alias(String old, String now) {
        this.aliases.put(old.toLowerCase(), now);
        return this;
    }

    public int[] visibleIndices() {
        List<Integer> out = new ArrayList<>();
        Integer current = this.getValue();
        for (int i = 0; i < this.modes.length; i++) {
            if (!this.hidden.contains(i) || current != null && current == i) {
                out.add(i);
            }
        }
        int[] array = new int[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    public String[] visibleModes() {
        int[] visible = this.visibleIndices();
        String[] names = new String[visible.length];
        for (int i = 0; i < visible.length; i++) {
            names[i] = this.modes[visible[i]];
        }
        return names;
    }

    @Override
    public String getValuePrompt() {
        return String.join(", ", visibleModes());
    }

    public boolean setVisible(int index) {
        int[] visible = this.visibleIndices();
        return index >= 0 && index < visible.length && this.setValue(visible[index]);
    }

    public String modeName(int index) {
        return index >= 0 && index < this.modes.length ? this.modes[index] : "";
    }

    public String getModeString() {
        int index = this.getValue();
        return index >= 0 && index < this.modes.length ? this.modes[index] : "";
    }

    @Override
    public String formatValue() {
        String index = this.getModeString();
        return index.isEmpty() ? "&4?" : String.format("&9%s", index);
    }

    @Override
    public boolean parseString(String string) {
        String renamed = this.aliases.get(string.toLowerCase());
        if (renamed != null) {
            string = renamed;
        }
        for (int i = 0; i < this.modes.length; i++) {
            if (string.equals(this.modes[i])) {
                return this.setValue(i);
            }
        }

        String valueStr = string.replace("_", "");
        for (int i = 0; i < this.modes.length; i++) {
            if (valueStr.equalsIgnoreCase(this.modes[i].replace("_", ""))) {
                return this.setValue(i);
            }
        }
        return false;
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        com.google.gson.JsonElement value = jsonObject.get(this.getName());

        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            int index = value.getAsInt();
            return index >= 0 && index < this.modes.length && this.setValue(index);
        }
        return this.parseString(value.getAsString());
    }

    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getModeString());
    }

    public void nextMode() {
        int next = this.getValue();
        for (int n = 0; n < this.modes.length; n++) {
            next = next + 1 >= this.modes.length ? 0 : next + 1;
            if (!this.hidden.contains(next)) {
                break;
            }
        }
        this.setValue(next);
    }

    public void previousMode() {
        int prev = this.getValue();
        for (int n = 0; n < this.modes.length; n++) {
            prev = prev - 1 < 0 ? this.modes.length - 1 : prev - 1;
            if (!this.hidden.contains(prev)) {
                break;
            }
        }
        this.setValue(prev);
    }

    public void setModes(String[] modes) {
        this.modes = modes;
        if (this.getValue() >= modes.length) {
            this.setValue(0);
        }
    }
}
