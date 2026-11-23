package atlas.module;

import atlas.event.EventManager;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.property.Property;
import atlas.property.PropertyGroup;
import atlas.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class ModuleBundle implements PropertyGroup {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<Class<?>, Module> MERGED = new HashMap<Class<?>, Module>();

    private final Module host;
    private final Module child;
    private final String prefix;
    private final String title;
    private final BooleanProperty toggle;
    private final BooleanSupplier selected;
    private final List<Property<?>> members = new ArrayList<Property<?>>();
    private boolean built;
    private boolean wanted;

    private Runnable deselect;

    private Runnable select;

    private final Map<Property<?>, String> ownKeys = new HashMap<Property<?>, String>();
    private static final List<ModuleBundle> ALL = new ArrayList<ModuleBundle>();

    private ModuleBundle(Module host, Module child, String prefix, String title, BooleanProperty toggle,
                         BooleanSupplier selected) {
        this.host = host;
        this.child = child;
        this.prefix = prefix;
        this.title = title;
        this.toggle = toggle;
        this.selected = selected;
        MERGED.put(child.getClass(), child);
        ALL.add(this);
    }

    public static ModuleBundle withSwitch(Module host, Module child, String prefix, String title, boolean on) {
        return new ModuleBundle(host, child, prefix, title, new BooleanProperty(prefix, on), null);
    }

    public static ModuleBundle forMode(Module host, Module child, String prefix, String title, BooleanSupplier selected) {
        return new ModuleBundle(host, child, prefix, title, null, selected);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T of(Class<T> type) {
        return (T) MERGED.get(type);
    }

    public static Module byName(String name) {
        for (Module module : MERGED.values()) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    public ModuleBundle deselectWith(Runnable action) {
        this.deselect = action;
        return this;
    }

    public ModuleBundle selectWith(Runnable action) {
        this.select = action;
        return this;
    }

    private java.util.function.Consumer<com.google.gson.JsonObject> seed;

    public ModuleBundle seedWith(java.util.function.Consumer<com.google.gson.JsonObject> seed) {
        this.seed = seed;
        return this;
    }

    public Property<?> member(String own) {
        for (Property<?> property : properties()) {
            if (own.equals(this.ownKeys.get(property))) {
                return property;
            }
        }
        return null;
    }

    public boolean readInto(String own, com.google.gson.JsonElement value) {
        Property<?> property = member(own);
        if (property == null || value == null) {
            return false;
        }
        com.google.gson.JsonObject one = new com.google.gson.JsonObject();
        one.add(property.getName(), value);
        try {
            return property.read(one);
        } catch (Exception e) {
            return false;
        }
    }

    public static void readLegacy(Module host, com.google.gson.JsonObject root, com.google.gson.JsonObject hostObject) {
        for (ModuleBundle bundle : ALL) {
            if (bundle.host != host) {
                continue;
            }
            List<Property<?>> members = bundle.properties();
            boolean merged = false;
            for (Property<?> property : members) {
                merged |= hostObject != null && hostObject.has(property.getName());
            }
            if (merged) {
                continue;
            }
            if (bundle.seed != null && hostObject != null) {
                try {
                    bundle.seed.accept(hostObject);
                } catch (Exception ignored) {
                }
            }
            com.google.gson.JsonElement old = root.get(bundle.child.getName());
            if (old == null || !old.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject section = old.getAsJsonObject();
            for (Property<?> property : members) {
                String own = bundle.ownKeys.get(property);
                if (own == null || !section.has(own)) {
                    continue;
                }
                com.google.gson.JsonObject one = new com.google.gson.JsonObject();
                one.add(property.getName(), section.get(own));
                try {
                    property.read(one);
                } catch (Exception ignored) {
                }
            }
            com.google.gson.JsonElement toggled = section.get("toggled");
            if (toggled != null && toggled.isJsonPrimitive() && toggled.getAsBoolean()) {
                if (bundle.select != null) {
                    bundle.select.run();
                } else if (bundle.toggle != null) {
                    bundle.toggle.setValue(true);
                }
                try {
                    host.setEnabled(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public Module child() {
        return this.child;
    }

    public boolean active() {
        if (!this.host.isEnabled()) {
            return false;
        }
        if (this.toggle != null && !this.toggle.getValue()) {
            return false;
        }
        return this.selected == null || this.selected.getAsBoolean();
    }

    @Override
    public List<Property<?>> properties() {
        if (!this.built) {
            this.built = true;
            build();
        }
        return Collections.unmodifiableList(this.members);
    }

    private void build() {
        List<Property<?>> own = new ArrayList<Property<?>>();
        for (Field field : this.child.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(this.child);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (value instanceof Property<?>) {
                own.add((Property<?>) value);
            } else if (value instanceof PropertyGroup) {
                own.addAll(((PropertyGroup) value).properties());
            }
        }

        own = ModuleDocs.apply(this.child, own);
        String help = this.child.getDescription();
        if (this.toggle != null) {
            this.toggle.setLabel(this.title);
            if (help != null) {
                this.toggle.setHelp(help);
            }
            this.toggle.fixGroup(this.title);
            this.members.add(this.toggle);
        }
        BooleanSupplier shown = this.toggle != null ? this.toggle::getValue
                : (this.selected != null ? this.selected : () -> true);
        for (Property<?> property : own) {
            property.setOwner(this.child);
            this.ownKeys.put(property, property.getName());
            property.rekey(this.prefix + "-" + property.getName());
            property.alsoWhen(shown);
            property.fixGroup(ModuleDocs.bundleHeading(this.title, this.child, property.getGroup()));
            this.members.add(property);
        }
        EventManager.register(this);
        EventManager.register(this.child);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        sync();
    }

    public void sync() {
        boolean want = active();
        if (want && mc.thePlayer == null) {
            return;
        }
        if (this.wanted && want && !this.child.isEnabled() && (this.toggle != null || this.deselect != null)) {
            if (this.toggle != null) {
                this.toggle.setValue(false);
            } else {
                this.deselect.run();
            }
            this.wanted = false;
            return;
        }
        if (this.child.isEnabled() != want) {
            this.child.setEnabled(want);
        }
        this.wanted = want;
    }
}
