package atlas.script.api;

import atlas.Atlas;
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.TextProperty;
import atlas.script.ScriptEntry;
import atlas.script.ScriptManager;
import atlas.script.ScriptSettings;
import atlas.script.raven.Raven17Script;
import atlas.script.raven.model.Entity;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class AtlasScript extends Raven17Script {
    private static final Minecraft game = Minecraft.getMinecraft();
    private final ScriptEntry entry;
    private volatile String suffix;

    protected AtlasScript() {
        this.entry = ScriptSettings.constructing();
    }

    protected final BooleanProperty bool(String name, boolean value) {
        return ScriptSettings.add(this.entry, new BooleanProperty(ScriptSettings.key(name), value), name);
    }

    protected final FloatProperty number(String name, float value, float min, float max) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        return ScriptSettings.add(this.entry, new FloatProperty(ScriptSettings.key(name),
                Math.max(low, Math.min(high, value)), low, high), name);
    }

    protected final IntProperty integer(String name, int value, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return ScriptSettings.add(this.entry, new IntProperty(ScriptSettings.key(name),
                Math.max(low, Math.min(high, value)), low, high), name);
    }

    protected final ModeProperty mode(String name, String value, String... modes) {
        String[] options = modes == null || modes.length == 0 ? new String[]{value} : modes;
        int start = Math.max(0, Arrays.asList(options).indexOf(value));
        return ScriptSettings.add(this.entry, new ModeProperty(ScriptSettings.key(name), start, options), name);
    }

    protected final ColorProperty color(String name, int rgb) {
        return ScriptSettings.add(this.entry, new ColorProperty(ScriptSettings.key(name), rgb & 0xFFFFFF), name);
    }

    protected final KeyProperty key(String name, int key) {
        return ScriptSettings.add(this.entry, new KeyProperty(ScriptSettings.key(name), key), name);
    }

    protected final TextProperty text(String name, String value) {
        return ScriptSettings.add(this.entry, new TextProperty(ScriptSettings.key(name), value), name);
    }

    protected final void heading(String name) {
        ScriptSettings.group(this.entry, name);
    }

    public final Module module() {
        return this.entry == null ? null : this.entry.module();
    }

    public final String name() {
        return this.entry == null ? "script" : this.entry.name;
    }

    public final boolean enabled() {
        Module module = this.module();
        return module != null && module.isEnabled();
    }

    protected final void disableSelf() {
        Module module = this.module();
        if (module != null) {
            module.setEnabled(false);
        }
    }

    protected final void suffix(String text) {
        this.suffix = text;
    }

    public final String currentSuffix() {
        return this.suffix;
    }

    protected final void print(String message) {
        ChatUtil.sendFormatted("&7[&b" + this.name() + "&7]&r " + message);
    }

    protected final Module module(String name) {
        ScriptEntry script = ScriptManager.get().byName(name);
        if (script != null) {
            return script.module();
        }
        return Atlas.moduleManager.getModule(name);
    }

    protected final Property<?> setting(String moduleName, String settingName) {
        return ScriptSettings.find(this.module(moduleName), settingName);
    }

    protected final boolean look(float yaw, float pitch) {
        return this.look(yaw, pitch, ScriptManager.PRIORITY, true, RotationHelper.NO_LIMIT);
    }

    protected final boolean look(float yaw, float pitch, int priority, boolean moveFix, float maxTurn) {
        return RotationHelper.request(this, yaw, pitch, priority,
                moveFix ? RotationHelper.MOVE_FIX : RotationHelper.MOVE_CAMERA, false, maxTurn) != null;
    }

    protected final float[] lookAt(Entity entity) {
        return client.getRotationsToEntity(entity);
    }

    protected final float[] serverLook() {
        return new float[]{RotationHelper.serverYaw(), RotationHelper.serverPitch()};
    }

    protected final List<Entity> players(double range) {
        List<EntityPlayer> found = new ArrayList<EntityPlayer>();
        if (game.theWorld == null || game.thePlayer == null) {
            return new ArrayList<Entity>();
        }
        for (EntityPlayer player : new ArrayList<EntityPlayer>(game.theWorld.playerEntities)) {
            if (player == game.thePlayer || player.isDead || player.getHealth() <= 0.0F
                    || game.thePlayer.getDistanceToEntity(player) > range
                    || !atlas.util.CombatTargeting.isTrackablePlayer(player, true)) {
                continue;
            }
            found.add(player);
        }
        found.sort(Comparator.comparingDouble(player -> game.thePlayer.getDistanceToEntity(player)));
        List<Entity> out = new ArrayList<Entity>();
        for (EntityPlayer player : found) {
            out.add(Entity.convert(player));
        }
        return out;
    }

    protected final Entity nearestPlayer(double range) {
        List<Entity> found = this.players(range);
        return found.isEmpty() ? null : found.get(0);
    }
}
