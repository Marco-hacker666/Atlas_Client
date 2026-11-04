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

/**
 * The base of an Atlas script (2026-11-02): all of Raven bS 17's API (client, world, render ... and its models
 * and packets; bS 17 since 2026-11-03) plus this client's own.
 *
 *  - Settings are fields: {@code final FloatProperty range = number("range", 3.0F, 1.0F, 6.0F);} makes a real
 *    setting in the script's menu card, read with range.getValue(). heading("...") starts a group.
 *  - Hooks are methods it declares, any it likes:
 *      onLoad() onEnable() onDisable()
 *      onTick()                         every client tick in a world (TickEvent PRE; a look asked here counts)
 *      onUpdate(UpdateEvent) / onPostUpdate(UpdateEvent)  Atlas's movement tick, before and after the move
 *      onRender2D(float partialTicks) / onRender3D(float partialTicks)
 *      boolean onAttack(Entity target)  false cancels the hit
 *      onKey(int key)                   a key pressed in game
 *      onWorldLoad()
 *    and every Raven hook (onPreUpdate, onPreMotion(PlayerState), onPacketSent(CPacket), onChat(String) ...).
 *  - look(yaw, pitch) asks the shared rotation engine for this tick's server look (call it every tick it should
 *    hold; it eases and keeps the movement fix like any module's look).
 *  - module("KillAura") reaches any module; setting(module, name) any setting.
 *
 * Imports at the top of the file are allowed (they are moved above the class).
 */
public class AtlasScript extends Raven17Script {
    private static final Minecraft game = Minecraft.getMinecraft();
    private final ScriptEntry entry;
    private volatile String suffix;

    protected AtlasScript() {
        this.entry = ScriptSettings.constructing();
    }

    // ---- settings ----------------------------------------------------------

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

    /** One of the modes, {@code value} first chosen; getModeString() reads it. */
    protected final ModeProperty mode(String name, String value, String... modes) {
        String[] options = modes == null || modes.length == 0 ? new String[]{value} : modes;
        int start = Math.max(0, Arrays.asList(options).indexOf(value));
        return ScriptSettings.add(this.entry, new ModeProperty(ScriptSettings.key(name), start, options), name);
    }

    /** RGB (0xRRGGBB). */
    protected final ColorProperty color(String name, int rgb) {
        return ScriptSettings.add(this.entry, new ColorProperty(ScriptSettings.key(name), rgb & 0xFFFFFF), name);
    }

    /** A key of its own (Keyboard.KEY_...); isDown() reads it. */
    protected final KeyProperty key(String name, int key) {
        return ScriptSettings.add(this.entry, new KeyProperty(ScriptSettings.key(name), key), name);
    }

    protected final TextProperty text(String name, String value) {
        return ScriptSettings.add(this.entry, new TextProperty(ScriptSettings.key(name), value), name);
    }

    /** The settings made after this go under this heading. */
    protected final void heading(String name) {
        ScriptSettings.group(this.entry, name);
    }

    // ---- its own module ---------------------------------------------------------

    /** Its module (the menu's row, its key, its on/off). */
    public final Module module() {
        return this.entry == null ? null : this.entry.module();
    }

    /** Its name in the menu. */
    public final String name() {
        return this.entry == null ? "script" : this.entry.name;
    }

    public final boolean enabled() {
        Module module = this.module();
        return module != null && module.isEnabled();
    }

    /** Switches itself off (its onDisable runs). */
    protected final void disableSelf() {
        Module module = this.module();
        if (module != null) {
            module.setEnabled(false);
        }
    }

    /** What the HUD list shows after its name; null for nothing. */
    protected final void suffix(String text) {
        this.suffix = text;
    }

    public final String currentSuffix() {
        return this.suffix;
    }

    /** A chat line under its name; '&' colours. */
    protected final void print(String message) {
        ChatUtil.sendFormatted("&7[&b" + this.name() + "&7]&r " + message);
    }

    // ---- other modules ------------------------------------------------------------

    /** Any module by name (Atlas's names, their class names, scripts' names), or null. */
    protected final Module module(String name) {
        ScriptEntry script = ScriptManager.get().byName(name);
        if (script != null) {
            return script.module();
        }
        return Atlas.moduleManager.getModule(name);
    }

    /** A setting of any module by its name as shown or stored, or null. */
    protected final Property<?> setting(String moduleName, String settingName) {
        return ScriptSettings.find(this.module(moduleName), settingName);
    }

    // ---- the look ------------------------------------------------------------------

    /**
     * This tick's server look through the shared rotation engine, with the movement fix; true when it is this
     * script's (no higher priority asked). Call it every tick the look should hold: in onTick, onUpdate,
     * onPreUpdate or onPreMotion.
     */
    protected final boolean look(float yaw, float pitch) {
        return this.look(yaw, pitch, ScriptManager.PRIORITY, true, RotationHelper.NO_LIMIT);
    }

    /** look() with a priority (modules use 0..6), the movement fix or not, and a cap in degrees a tick. */
    protected final boolean look(float yaw, float pitch, int priority, boolean moveFix, float maxTurn) {
        return RotationHelper.request(this, yaw, pitch, priority,
                moveFix ? RotationHelper.MOVE_FIX : RotationHelper.MOVE_CAMERA, false, maxTurn) != null;
    }

    /** {yaw, pitch} at the middle of the entity. */
    protected final float[] lookAt(Entity entity) {
        return client.getRotationsToEntity(entity);
    }

    /** The server's look now: {yaw, pitch}. */
    protected final float[] serverLook() {
        return new float[]{RotationHelper.serverYaw(), RotationHelper.serverPitch()};
    }

    // ---- players ------------------------------------------------------------------

    /** Other players within range, nearest first: alive, not friends, not on this player's team. */
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

    /** The nearest of players(range), or null. */
    protected final Entity nearestPlayer(double range) {
        List<Entity> found = this.players(range);
        return found.isEmpty() ? null : found.get(0);
    }
}
