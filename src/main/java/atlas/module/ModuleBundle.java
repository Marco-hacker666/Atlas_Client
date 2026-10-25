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

/**
 * One module merged into another (2026-10-11). The merged module keeps its own
 * code and runs as before, but it is no longer a module of its own: it has no
 * entry in the menu or the module list, and it runs while its host is on and
 * its switch (or the host's mode) says so. Its settings appear under the host,
 * under their own heading, with keys prefixed so they stay unique.
 *
 * A field of this type in the host is picked up by the client's setting scan
 * (PropertyGroup). {@link #of(Class)} finds a merged module for code that
 * used to look it up in the module manager.
 */
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
    /** For a mode merge: picks the host's own mode again (FlagResponder switching it off). */
    private Runnable deselect;
    /** For a mode merge: picks this one's mode (an old config had the merged module on). */
    private Runnable select;
    /** Each member's key before the prefix, as a config saved before the merge has it. */
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

    /** Merged module with its own on/off switch under the host. */
    public static ModuleBundle withSwitch(Module host, Module child, String prefix, String title, boolean on) {
        return new ModuleBundle(host, child, prefix, title, new BooleanProperty(prefix, on), null);
    }

    /** Merged module that runs while the host's mode selects it (no switch of its own). */
    public static ModuleBundle forMode(Module host, Module child, String prefix, String title, BooleanSupplier selected) {
        return new ModuleBundle(host, child, prefix, title, null, selected);
    }

    /** The merged module of this class, or null; for code that used to ask the module manager. */
    @SuppressWarnings("unchecked")
    public static <T extends Module> T of(Class<T> type) {
        return (T) MERGED.get(type);
    }

    /** The merged module with this name, or null. */
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

    /** Fills the merged module's settings from the host's own section, once, when it has none of them yet. */
    private java.util.function.Consumer<com.google.gson.JsonObject> seed;

    public ModuleBundle seedWith(java.util.function.Consumer<com.google.gson.JsonObject> seed) {
        this.seed = seed;
        return this;
    }

    /** The member whose key before the prefix was {@code own}, or null. */
    public Property<?> member(String own) {
        for (Property<?> property : properties()) {
            if (own.equals(this.ownKeys.get(property))) {
                return property;
            }
        }
        return null;
    }

    /** Reads {@code value} into the member whose own key is {@code own}; false when there is none or it does not fit. */
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

    /**
     * A config saved before a merge (2026-10-24, Hitflick): the merged
     * module's own section, under its own name and unprefixed keys. When the
     * host's section has none of the prefixed keys yet, each setting is read
     * from there; if the old module was on, the host is switched on and
     * (a mode merge) its mode picked. Saving again writes the new keys only.
     *
     * @param hostObject the host's section, or null when the file has none
     */
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
                    /* A seed that cannot read the old section leaves the defaults. */
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
                    /* One unreadable value keeps its default, as Config does. */
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
                    /* As Config: a module that cannot start during the load stays off. */
                }
            }
        }
    }

    public Module child() {
        return this.child;
    }

    /** True while the merged module should run. */
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
        /* The merged module's own help texts, by its own keys, before rekeying. */
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
            property.fixGroup(this.title);
            this.members.add(property);
        }
        EventManager.register(this);
        EventManager.register(this.child);
    }

    /** Turns the merged module on and off with its host, switch and mode. */
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
            /* Switched off by itself (a one-shot) or by FlagResponder: show
               that on its switch, or go back to the host's own mode. */
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
