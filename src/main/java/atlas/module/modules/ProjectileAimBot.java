package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.Render3DEvent;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.sim.SimTargets;
import atlas.sim.TargetForecast;
import atlas.util.CombatTargeting;
import atlas.util.ProjectileSolver;
import atlas.util.RenderUtil;
import atlas.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bow aimbot (2026-10-31 port of LiquidBounce legacy's ProjectileAimbot; rebuilt 2026-11-15, owner: "a perfect bow
 * aimbot, look at the latest LiquidBounce, it must not go wrong").
 *
 * While a bow is drawn, or an egg, snowball or pearl is held, the look the server gets is turned onto the target so
 * the projectile lands in the middle of it. The camera is not moved.
 *
 * - The flight is the game's own (ProjectileSolver): the 0.16 side start and 0.1 drop, drag 0.99, gravity 0.05 an
 *   arrow and 0.03 a throwable, the box grown by 0.3, blocks first. The look is solved against where the target will
 *   be when the projectile gets there, and then flown tick by tick against its boxes and the blocks: a shot a wall
 *   would stop is not taken (allow-lob tries the high arc over it).
 * - Where the target will be comes from the movement simulation (atlas.sim SimTargets, the forecast .targets
 *   measures), else its present motion in a straight line. latency-compensation adds the round trip: the target's
 *   position reached us half a ping ago and the shot reaches the server half a ping from now.
 * - A shot leaves along the look the server already has: the release (or throw) packet goes before the tick's
 *   movement packet. So the look is aimed for the next tick, and auto-shoot lets go only when the look the server
 *   has now, flown with the draw it has now, hits: never on a guess.
 *
 * LiquidBounce (nextgen ModuleAutoBow, SituationalProjectileAngleCalculator) was read for the idea: solve flight time
 * and target position together, check the shot by flying it, release by charge and aim. No code was taken.
 */
public class ProjectileAimBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] BODY = new String[]{"Head", "Body", "Feet"};
    /** The server's eye height, standing and sneaking (EntityPlayer.getEyeHeight). */
    private static final double EYE = 1.62;
    private static final double EYE_SNEAKING = 1.54;

    public final BooleanProperty bow = new BooleanProperty("bow", true);
    public final BooleanProperty egg = new BooleanProperty("egg", true);
    public final BooleanProperty snowball = new BooleanProperty("snowball", true);
    public final BooleanProperty enderPearl = new BooleanProperty("ender-pearl", false);
    public final BooleanProperty otherItems = new BooleanProperty("other-items", false);
    public final FloatProperty range = new FloatProperty("range", 50.0F, 0.0F, 100.0F);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", false);
    public final FloatProperty throughWallsRange = new FloatProperty("through-walls-range", 10.0F, 0.0F, 100.0F,
            this.throughWalls::getValue);
    public final ModeProperty priority = new ModeProperty("priority", 2, new String[]{"Health", "Distance", "Direction"});
    /* 2026-11-15 (owner: "I can see him but it does not aim"): who counts, as KillAura decides it, each a switch.
       The team and bot checks were always on here and could not be turned off. */
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final ModeProperty gravityType = new ModeProperty("gravity-type", 1, new String[]{"None", "Projectile"});
    /** Lead the target where the movement simulation says it will be (else it is aimed where it stands). */
    public final BooleanProperty predict = new BooleanProperty("predict", true, () -> this.gravityType.getValue() == 1);
    public final BooleanProperty latencyCompensation = new BooleanProperty("latency-compensation", true,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue());
    /** Let go of the bow when the look the server has would hit, at shoot-charge or more. */
    public final BooleanProperty autoShoot = new BooleanProperty("auto-shoot", true,
            () -> this.gravityType.getValue() == 1);
    public final IntProperty shootCharge = new IntProperty("shoot-charge", 20, 3, 20,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());
    /**
     * Draw again straight after auto-shoot while right click is still held (2026-11-15). Off: the next draw waits
     * for a fresh press. The bow trace showed why: with the button still down the game drew again at once, and
     * letting go a moment later loosed that 1-3 tick draw, an arrow of 0.3 that dropped at your feet.
     */
    public final BooleanProperty redraw = new BooleanProperty("redraw", false,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());
    /** Up to this many ticks more draw, a new number each shot (LiquidBounce's ChargedRandom): not always on tick 20. */
    public final IntProperty chargeRandom = new IntProperty("charge-random", 2, 0, 5,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());
    /** The high arc when the low one would hit a block. */
    public final BooleanProperty allowLob = new BooleanProperty("allow-lob", true, () -> this.gravityType.getValue() == 1);
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 120.0F, 10.0F, 180.0F);
    /**
     * The movement yaw while the look is turned (2026-11-15, owner: "moving while aiming gets flagged, and I cannot
     * jump"): with NONE the walk and the sprint-jump boost went by the camera's yaw while the packet carried the aim's,
     * so a server that re-runs the move set it back. SILENT moves by the look's yaw with the keys turned so you still
     * go where you are looking; STRICT moves by the look's yaw as the keys are.
     */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty highestBodyPoint = new ModeProperty("highest-body-point", 0, BODY,
            () -> this.gravityType.getValue() == 0);
    public final ModeProperty lowestBodyPoint = new ModeProperty("lowest-body-point", 2, BODY,
            () -> this.gravityType.getValue() == 0);
    public final BooleanProperty mark = new BooleanProperty("mark", true);
    /** Draw the flight the aim is solved for, green when it hits. */
    public final BooleanProperty showPath = new BooleanProperty("show-path", true, () -> this.gravityType.getValue() == 1);
    /**
     * Log only (needs Logs): config/Atlas/bow-*.txt, a line a tick while a bow is drawn (targets, the solve, the
     * flight, the look the server has, the release), how each draw ended, and every arrow of ours the server spawns
     * (2026-11-15, owner: "I can see him but it does not aim; I let go while moving and nothing came out").
     */
    public final BooleanProperty bowTrace = new BooleanProperty("bow-trace", true);

    private EntityPlayer target;
    /** The last one aimed at, to keep the simulation on it from tick to tick. */
    private EntityPlayer lastAimed;
    /** This draw's extra ticks before auto-shoot (charge-random); -1 until the draw starts. */
    private int extraCharge = -1;
    /* What is aimed at now, for the suffix (owner, 2026-11-15: "does it simulate that far?"): its distance and the
       flight in ticks; -1 when nothing is aimed at. */
    private int shownDistance = -1;
    private int shownTicks = -1;
    /* bow-trace. */
    private java.io.File traceFile;
    private boolean wasDrawing;
    private int lastUseTicks;
    private boolean releasedByUs;
    private long releasedAt;
    private final java.util.Set<Integer> arrowsSeen = new java.util.HashSet<Integer>();
    /** After auto-shoot (redraw off): the use key is kept up until the button is let go. */
    private boolean holdAfterShot;
    /** What the last where() was built from: sim, line (the forecast's own straight line), motion, fixed. */
    private String whereKind = "-";
    /** Why the last aimExact found no shot, for the trace. */
    private String noShot = "";
    /** Why clearAim gave nothing, for the trace. */
    private String clearWhy = "";
    /* 2026-11-15 (owner: "can the prediction be better?"): every player's server positions, a tick apart. */
    private final java.util.Map<Integer, atlas.util.TargetLead> leads = new java.util.HashMap<Integer, atlas.util.TargetLead>();
    /** The round trip our own arrows showed (release to the server's arrow arriving), ms; -1 until one is seen. */
    private double arrowRttMs = -1.0;
    /** Who candidates() left out, and why, for the trace. */
    private final StringBuilder leftOut = new StringBuilder();
    private List<double[]> path = Collections.emptyList();
    private boolean pathHits;

    public ProjectileAimBot() {
        super("ProjectileAimBot", false, false, "Aims your bow, eggs, snowballs and pearls at the best target while you draw or hold them.");
    }

    @Override
    public void onEnabled() {
        this.clear();
    }

    @Override
    public void onDisabled() {
        this.clear();
    }

    @EventTarget(whenDisabled = true)
    public void onWorld(LoadWorldEvent event) {
        this.clear();
    }

    private void clear() {
        this.target = null;
        this.lastAimed = null;
        this.leads.clear();
        if (this.holdAfterShot) {
            this.holdAfterShot = false;
            if (mc.gameSettings != null) {
                atlas.util.KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            }
        }
        this.path = Collections.emptyList();
        this.pathHits = false;
    }

    /** What is in hand, as a shot: null when there is nothing to aim with now. */
    private static final class Shot {
        ProjectileSolver.Kind kind;
        /** Speed if let go (or thrown) now, and on the tick the aim is for. */
        double speedNow;
        double speedAim;
        boolean bow;
        int useTicks;
    }

    /**
     * Before the game reads its keys this tick: while holdAfterShot, right click reads as up, so no new draw starts
     * until the button is let go and pressed again.
     */
    @EventTarget
    public void onTick(atlas.events.TickEvent event) {
        if (!this.holdAfterShot || event.getType() != EventType.PRE || mc.gameSettings == null) {
            return;
        }
        int use = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (this.isEnabled() && atlas.util.KeyBindUtil.isKeyDown(use)) {
            atlas.util.KeyBindUtil.setKeyBindState(use, false);
        } else {
            this.holdAfterShot = false;
            atlas.util.KeyBindUtil.updateKeyState(use);
        }
    }

    /** Priority 2: above AimAssist (1), below Scaffold (3) and Clutch (7), which need theirs to place. */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.clear();
            return;
        }
        this.target = null;
        this.path = Collections.emptyList();
        this.pathHits = false;
        this.shownDistance = -1;
        this.shownTicks = -1;
        this.recordPositions();
        this.traceArrows();
        boolean drawing = mc.thePlayer.isUsingItem() && mc.thePlayer.getHeldItem() != null
                && mc.thePlayer.getHeldItem().getItem() instanceof ItemBow;
        if (this.wasDrawing && !drawing) {
            boolean keyHeld = mc.gameSettings.keyBindUseItem.isKeyDown();
            this.trace(String.format("END draw %d ticks: %s | look %.1f/%.1f | pos %.2f %.2f %.2f v %.3f %.3f",
                    this.lastUseTicks, keyHeld ? "STOPPED with the key still held (server or a module)"
                            : "let go by hand",
                    MathHelper.wrapAngleTo180_float(atlas.management.RotationHelper.serverYaw()),
                    atlas.management.RotationHelper.serverPitch(), mc.thePlayer.posX, mc.thePlayer.posY,
                    mc.thePlayer.posZ, mc.thePlayer.motionX, mc.thePlayer.motionZ));
            this.releasedAt = System.currentTimeMillis();
        }
        if (drawing && !this.wasDrawing) {
            this.releasedByUs = false;
        }
        this.wasDrawing = drawing;
        if (drawing) {
            this.lastUseTicks = mc.thePlayer.getItemInUseDuration();
        }
        Shot shot = this.shot();
        if (shot == null) {
            /* A bow in hand, not drawn yet: the best target is followed already, so its track is ready by the draw. */
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held != null && held.getItem() instanceof ItemBow && this.bow.getValue() && this.predict.getValue()
                    && this.gravityType.getValue() == 1) {
                List<EntityPlayer> found = this.candidates();
                if (!found.isEmpty()) {
                    SimTargets.focus(found.get(0));
                }
            }
            return;
        }
        float[] want;
        if (this.gravityType.getValue() == 1) {
            want = this.aimExact(shot);
            String release = "";
            if (want != null && shot.bow && this.autoShoot.getValue() && !fastBowOn()) {
                release = this.maybeRelease(shot);
            }
            if (shot.bow) {
                this.trace(String.format("use %d speed %.2f/%.2f | %s | look %.1f/%.1f%s",
                        shot.useTicks, shot.speedNow, shot.speedAim,
                        want == null ? "NO AIM " + this.noShot
                                : String.format("aim %s %dm %s %.1f/%.1f %dt %s", this.target.getName(),
                                this.shownDistance, this.whereKind, MathHelper.wrapAngleTo180_float(want[0]), want[1],
                                this.shownTicks, this.pathHits ? "hits" : "MISSES"),
                        MathHelper.wrapAngleTo180_float(atlas.management.RotationHelper.serverYaw()),
                        atlas.management.RotationHelper.serverPitch(), release));
            }
        } else {
            List<EntityPlayer> found = this.candidates();
            EntityPlayer chosen = found.isEmpty() ? null : found.get(0);
            want = chosen == null ? null : this.faceBody(chosen);
            this.target = want == null ? null : chosen;
        }
        if (want == null) {
            /* No aim now: the shared engine takes the look back to the camera. */
            return;
        }
        /* At most turn-speed a tick from the look the server last got; the engine adds the grid and its acceleration. */
        float max = this.turnSpeed.getValue() * (0.9F + (float) Math.random() * 0.2F);
        float[] look = atlas.management.RotationHelper.limitTurn(event.getYaw(), event.getPitch(), want[0], want[1], max);
        /* Priority 2: above AimAssist (1), below Scaffold (3) and Clutch (7). No clicks along it. */
        atlas.management.RotationHelper.request(this, look[0], look[1], 2, this.moveMode(), false,
                atlas.management.RotationHelper.NO_LIMIT);
    }

    /** move-fix as the engine's movement yaw. */
    private int moveMode() {
        switch (this.moveFix.getValue()) {
            case 0:
                return atlas.management.RotationHelper.MOVE_CAMERA;
            case 1:
                return atlas.management.RotationHelper.MOVE_FIX;
            default:
                return atlas.management.RotationHelper.MOVE_LOOK;
        }
    }

    private Shot shot() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || held.stackSize <= 0) {
            return null;
        }
        Item item = held.getItem();
        Shot shot = new Shot();
        if (item instanceof ItemBow) {
            if (!this.bow.getValue() || !mc.thePlayer.isUsingItem()) {
                return null;
            }
            shot.bow = true;
            shot.kind = ProjectileSolver.ARROW;
            shot.useTicks = mc.thePlayer.getItemInUseDuration();
            if (fastBowOn()) {
                /* FastBow lets go at full draw. */
                shot.speedNow = 3.0;
                shot.speedAim = 3.0;
            } else {
                shot.speedNow = ProjectileSolver.bowSpeed(shot.useTicks);
                /* The tick the aim is for: the next one, or the one auto-shoot will let go on. */
                int at = shot.useTicks + 1;
                if (this.autoShoot.getValue()) {
                    at = Math.max(at, this.shootCharge.getValue());
                }
                shot.speedAim = ProjectileSolver.bowSpeed(at);
            }
            return shot.speedAim > 0.0 ? shot : null;
        }
        boolean allowed = item instanceof ItemEgg ? this.egg.getValue()
                : item instanceof ItemSnowball ? this.snowball.getValue()
                : item instanceof ItemEnderPearl ? this.enderPearl.getValue()
                : this.otherItems.getValue();
        if (!allowed) {
            return null;
        }
        shot.kind = ProjectileSolver.THROWN;
        shot.speedNow = ProjectileSolver.THROWN_SPEED;
        shot.speedAim = ProjectileSolver.THROWN_SPEED;
        return shot;
    }

    private static boolean fastBowOn() {
        Module fastBow = atlas.Atlas.moduleManager == null ? null : atlas.Atlas.moduleManager.modules.get(FastBow.class);
        return fastBow != null && fastBow.isEnabled();
    }

    /** The first target by priority that a shot next tick can hit; its look, or null. */
    private float[] aimExact(Shot shot) {
        double[] eye = this.eyeNextTick();
        List<EntityPlayer> found = this.candidates();
        StringBuilder why = new StringBuilder(found.isEmpty() ? "no target (" + this.leftOut + ")" : "");
        /* The best one is followed by the movement simulation however far it is (SimTargets covers 16 blocks). */
        if (!found.isEmpty() && this.predict.getValue()) {
            SimTargets.focus(this.lastAimed != null && found.contains(this.lastAimed) ? this.lastAimed : found.get(0));
        }
        EntityPlayer bestEffort = null;
        ProjectileSolver.Target bestWhere = null;
        ProjectileSolver.Aim bestAim = null;
        String bestKind = "";
        for (EntityPlayer candidate : found) {
            ProjectileSolver.Target where = this.where(candidate, 1, true);
            if (where == null) {
                why.append(candidate.getName()).append(": no position; ");
                continue;
            }
            ProjectileSolver.Aim aim = this.clearAim(eye, shot.speedAim, shot.kind, where, candidate);
            if (aim == null && this.whereKind.startsWith("sim")) {
                /* The simulation's forecast gave no shot that flies (2026-11-15, owner: "past 16 blocks it does not
                   aim, close in it does": a player just taken on is followed from scratch): its server positions
                   (TargetLead), as LiquidBounce falls back to extrapolation. */
                String first = this.whereKind + ": " + this.clearWhy;
                ProjectileSolver.Target line = this.where(candidate, 1, false);
                aim = this.clearAim(eye, shot.speedAim, shot.kind, line, candidate);
                if (aim != null) {
                    where = line;
                } else {
                    this.clearWhy = first + " | " + this.whereKind + ": " + this.clearWhy;
                }
            }
            if (aim == null) {
                why.append(String.format("%s %dm %s: %s; ", candidate.getName(),
                        Math.round(mc.thePlayer.getDistanceToEntity(candidate)), this.whereKind, this.clearWhy));
                if (bestEffort == null) {
                    ProjectileSolver.Aim low = ProjectileSolver.solve(eye[0], eye[1], eye[2], shot.speedAim, shot.kind,
                            where, false);
                    if (low != null) {
                        bestEffort = candidate;
                        bestWhere = where;
                        bestAim = low;
                        bestKind = this.whereKind;
                    }
                }
                continue;
            }
            ProjectileSolver.Flight flight = ProjectileSolver.fly(eye[0], eye[1], eye[2], aim.yaw, aim.pitch,
                    shot.speedAim, shot.kind, where, this.blocks(), ProjectileSolver.MAX_TICKS);
            this.target = candidate;
            this.lastAimed = candidate;
            this.path = flight.path;
            this.pathHits = flight.hits();
            this.shownDistance = (int) Math.round(mc.thePlayer.getDistanceToEntity(candidate));
            this.shownTicks = (int) Math.round(aim.ticks);
            return new float[]{aim.yaw, aim.pitch};
        }
        this.noShot = why.toString();
        if (bestEffort != null) {
            /* Nothing flies clear: still aim at the best of them (as LiquidBounce, which does not check by
               default), shown red; auto-shoot still lets go only on a flown hit. */
            ProjectileSolver.Flight flight = ProjectileSolver.fly(eye[0], eye[1], eye[2], bestAim.yaw, bestAim.pitch,
                    shot.speedAim, shot.kind, bestWhere, this.blocks(), ProjectileSolver.MAX_TICKS);
            this.target = bestEffort;
            this.lastAimed = bestEffort;
            this.path = flight.path;
            this.pathHits = flight.hits();
            this.whereKind = bestKind + " UNCHECKED";
            this.shownDistance = (int) Math.round(mc.thePlayer.getDistanceToEntity(bestEffort));
            this.shownTicks = (int) Math.round(bestAim.ticks);
            return new float[]{bestAim.yaw, bestAim.pitch};
        }
        return null;
    }

    /** The low arc if nothing stops it, else the high one (allow-lob); through-walls takes the low arc regardless. */
    private ProjectileSolver.Aim clearAim(double[] eye, double speed, ProjectileSolver.Kind kind,
                                          ProjectileSolver.Target where, EntityPlayer candidate) {
        ProjectileSolver.Aim low = ProjectileSolver.solve(eye[0], eye[1], eye[2], speed, kind, where, false);
        String lowWhy = low == null ? "low arc unreachable" : this.flight(eye, low, speed, kind, where);
        if (low != null && lowWhy.isEmpty()) {
            return low;
        }
        String highWhy = "no lob";
        if (this.allowLob.getValue()) {
            ProjectileSolver.Aim high = ProjectileSolver.solve(eye[0], eye[1], eye[2], speed, kind, where, true);
            highWhy = high == null ? "high arc unreachable" : this.flight(eye, high, speed, kind, where);
            if (high != null && highWhy.isEmpty()) {
                return high;
            }
        }
        this.clearWhy = "low " + lowWhy + ", high " + highWhy;
        boolean wall = this.throughWalls.getValue()
                && RotationUtil.distanceToEntity(candidate) <= this.throughWallsRange.getValue();
        return wall ? low : null;
    }

    /** "" when this aim, flown, hits; else what happened to it. */
    private String flight(double[] eye, ProjectileSolver.Aim aim, double speed, ProjectileSolver.Kind kind,
                          ProjectileSolver.Target where) {
        ProjectileSolver.Flight f = ProjectileSolver.fly(eye[0], eye[1], eye[2], aim.yaw, aim.pitch, speed, kind,
                where, this.blocks(), ProjectileSolver.MAX_TICKS);
        if (f.hits()) {
            return "";
        }
        double[] end = f.path.get(f.path.size() - 1);
        return String.format("%s at tick %d (%.1f %.1f %.1f)", f.blocked ? "blocked" : "missed", f.path.size() - 1,
                end[0], end[1], end[2]);
    }

    /**
     * auto-shoot: let go now when the look the server already has, flown with the draw it has now from where the
     * server has us, hits the target (and nothing stops it first).
     */
    private String maybeRelease(Shot shot) {
        if (this.extraCharge < 0 || shot.useTicks <= 1) {
            this.extraCharge = this.chargeRandom.getValue() <= 0 ? 0
                    : (int) (Math.random() * (this.chargeRandom.getValue() + 1));
        }
        if (this.target == null || shot.useTicks < this.shootCharge.getValue() + this.extraCharge
                || shot.speedNow <= 0.0) {
            return " | wait (charge " + (this.shootCharge.getValue() + this.extraCharge) + ")";
        }
        ProjectileSolver.Target where = this.where(this.target, 0);
        if (where == null) {
            return " | no position now";
        }
        double[] eye = this.eyeNow();
        float yaw = atlas.management.RotationHelper.serverYaw();
        float pitch = atlas.management.RotationHelper.serverPitch();
        ProjectileSolver.Flight flight = ProjectileSolver.fly(eye[0], eye[1], eye[2], yaw, pitch, shot.speedNow,
                shot.kind, where, this.blocks(), ProjectileSolver.MAX_TICKS);
        if (!flight.hits()) {
            /* The aim may have been made on the straight line (the forecast gave no shot): that one too. */
            ProjectileSolver.Target line = this.where(this.target, 0, false);
            ProjectileSolver.Flight onLine = ProjectileSolver.fly(eye[0], eye[1], eye[2], yaw, pitch, shot.speedNow,
                    shot.kind, line, this.blocks(), ProjectileSolver.MAX_TICKS);
            if (onLine.hits()) {
                flight = onLine;
            }
        }
        if (flight.hits()) {
            /* The release packet goes now, before this tick's movement packet, as the game sends it from input. */
            mc.playerController.onStoppedUsingItem(mc.thePlayer);
            this.extraCharge = -1;
            this.releasedByUs = true;
            this.releasedAt = System.currentTimeMillis();
            this.trace(String.format("END draw %d ticks: auto-shoot let go | look %.1f/%.1f", shot.useTicks,
                    MathHelper.wrapAngleTo180_float(yaw), pitch));
            /* The next draw is a new one, whatever starts it. */
            this.wasDrawing = false;
            if (!this.redraw.getValue()) {
                this.holdAfterShot = true;
                atlas.util.KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            }
            return " | RELEASE (server look hits at tick " + flight.hitTick + ")";
        }
        return String.format(" | hold: server look %s at tick %d", flight.blocked ? "blocked" : "misses",
                flight.path.size() - 1);
    }

    /** One line to bow-*.txt (bow-trace, needs Logs). */
    private void trace(String line) {
        if (!this.bowTrace.getValue()) {
            return;
        }
        if (this.traceFile == null) {
            this.traceFile = new java.io.File("./config/Atlas/",
                    "bow-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        atlas.util.AsyncLog.append(this.traceFile,
                new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + " " + line);
    }

    /** Every arrow of ours the server spawned: proof the shot left, and which way it went. */
    private void traceArrows() {
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof net.minecraft.entity.projectile.EntityArrow)) {
                continue;
            }
            net.minecraft.entity.projectile.EntityArrow arrow = (net.minecraft.entity.projectile.EntityArrow) o;
            if (arrow.shootingEntity != mc.thePlayer || !this.arrowsSeen.add(arrow.getEntityId())) {
                continue;
            }
            long since = System.currentTimeMillis() - this.releasedAt;
            if (this.releasedAt > 0L && since > 0L && since < 1500L) {
                /* Seen at the start of a tick, half a tick late on average. */
                double sample = Math.max(0.0, since - 25.0);
                this.arrowRttMs = this.arrowRttMs < 0.0 ? sample : this.arrowRttMs * 0.7 + sample * 0.3;
            }
            double speed = Math.sqrt(arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY
                    + arrow.motionZ * arrow.motionZ);
            float yaw = (float) (Math.toDegrees(Math.atan2(arrow.motionZ, arrow.motionX)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(arrow.motionY,
                    Math.sqrt(arrow.motionX * arrow.motionX + arrow.motionZ * arrow.motionZ)));
            this.trace(String.format("ARROW of ours spawned %d ms after the release (round trip now %.0f ms): at %.2f"
                            + " %.2f %.2f, speed %.2f, going %.1f/%.1f", since, this.arrowRttMs, arrow.posX, arrow.posY,
                    arrow.posZ, speed, MathHelper.wrapAngleTo180_float(yaw), pitch));
            this.releasedAt = 0L;
        }
        if (this.arrowsSeen.size() > 512) {
            this.arrowsSeen.clear();
        }
    }

    /** Where the server has our eyes now (the last movement packet's position). */
    private double[] eyeNow() {
        return new double[]{mc.thePlayer.posX, mc.thePlayer.posY + eyeHeight(), mc.thePlayer.posZ};
    }

    /** Where they will be after this tick's move: the shot the aim is for leaves from there. */
    private double[] eyeNextTick() {
        double y = mc.thePlayer.onGround ? mc.thePlayer.posY : mc.thePlayer.posY + mc.thePlayer.motionY;
        return new double[]{mc.thePlayer.posX + mc.thePlayer.motionX, y + eyeHeight(),
                mc.thePlayer.posZ + mc.thePlayer.motionZ};
    }

    private static double eyeHeight() {
        return mc.thePlayer.isSneaking() ? EYE_SNEAKING : EYE;
    }

    /**
     * Where the target will be, from the moment a shot let go {@code delay} ticks from now leaves: the movement
     * simulation's forecast, else a straight line from its present motion. Null when it cannot be known.
     */
    private ProjectileSolver.Target where(EntityPlayer t, int delay) {
        return this.where(t, delay, true);
    }

    /** where(), the simulation's forecast only when {@code useForecast}. */
    private ProjectileSolver.Target where(EntityPlayer t, int delay, boolean useForecast) {
        double latency = this.latencyCompensation.getValue() ? latencyMs() / 50.0 : 0.0;
        if (!this.predict.getValue()) {
            this.whereKind = "fixed";
            AxisAlignedBB box = t.getEntityBoundingBox();
            double[] fixed = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
            return ticks -> fixed;
        }
        int age = useForecast ? SimTargets.age(t) : -1;
        TargetForecast forecast = age < 0 ? null
                : SimTargets.forecast(t, age + (int) Math.ceil(latency) + delay + ProjectileSolver.MAX_TICKS + 2);
        if (forecast != null && forecast.moves() > 0 && forecast.simulated) {
            this.whereKind = String.format("sim age %d lag %.1f", age, latency);
            final double offset = age + latency + delay;
            final TargetForecast f = forecast;
            final double half = 0.3;
            final double height = 1.8;
            return ticks -> {
                double at = Math.max(0.0, Math.min(f.moves(), offset + ticks));
                int i = (int) Math.floor(at);
                int j = Math.min(f.moves(), i + 1);
                double k = at - i;
                double x = f.x[i] + (f.x[j] - f.x[i]) * k;
                double y = f.y[i] + (f.y[j] - f.y[i]) * k;
                double z = f.z[i] + (f.z[j] - f.z[i]) * k;
                return new double[]{x - half, y, z - half, x + half, y + height, z + half};
            };
        }
        /* No simulated answer: its server positions (atlas.util.TargetLead), the near ticks by its latest speed,
           further out by the line fitted to the last second (a strafer's middle), falling as the game makes it. */
        final atlas.util.TargetLead history = this.leads.get(t.getEntityId());
        if (history != null && history.size() >= 2) {
            this.whereKind = String.format("lead lag %.1f", latency);
            final double ahead = latency + delay;
            final double floor = groundBelow(t);
            return ticks -> {
                double[] p = history.predict(ahead + ticks, floor);
                return new double[]{p[0] - 0.3, p[1], p[2] - 0.3, p[0] + 0.3, p[1] + 1.8, p[2] + 0.3};
            };
        }
        this.whereKind = "fixed (no history yet)";
        final double sx = t.serverPosX / 32.0;
        final double sy = t.serverPosY / 32.0;
        final double sz = t.serverPosZ / 32.0;
        return ticks -> new double[]{sx - 0.3, sy, sz - 0.3, sx + 0.3, sy + 1.8, sz + 0.3};
    }

    /**
     * The round trip to lead by, ms: what our own arrows showed (the truest: it is this very path, the server's wait
     * for its next tick included), else Ping.own() (the tab list, or on Hypixel, which shows 1 ms there, the
     * measured attack-to-hurt delay), else 0.
     */
    private double latencyMs() {
        if (this.arrowRttMs >= 0.0) {
            return Math.min(1000.0, this.arrowRttMs);
        }
        int own = atlas.util.Ping.own();
        return own > 0 ? Math.min(1000, own) : 0.0;
    }

    /** Every player's server position this tick, for TargetLead. */
    private void recordPositions() {
        java.util.Set<Integer> present = new java.util.HashSet<Integer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            present.add(player.getEntityId());
            atlas.util.TargetLead history = this.leads.get(player.getEntityId());
            if (history == null) {
                history = new atlas.util.TargetLead();
                this.leads.put(player.getEntityId(), history);
            }
            history.add(player.serverPosX / 32.0, player.serverPosY / 32.0, player.serverPosZ / 32.0,
                    player.onGround);
        }
        this.leads.keySet().retainAll(present);
    }

    /** The top of the ground under this player's server position (30 blocks down at most); NaN for none. */
    private static double groundBelow(EntityPlayer t) {
        double x = t.serverPosX / 32.0;
        double y = t.serverPosY / 32.0;
        double z = t.serverPosZ / 32.0;
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(new Vec3(x, y + 0.1, z), new Vec3(x, y - 30.0, z),
                false, true, false);
        return hit == null || hit.hitVec == null ? Double.NaN : hit.hitVec.yCoord;
    }

    /** The world's blocks as the projectile meets them: EntityArrow's rayTraceBlocks(from, to, false, true, false). */
    private ProjectileSolver.Blocks blocks() {
        return (x1, y1, z1, x2, y2, z2) -> {
            Vec3 from = new Vec3(x1, y1, z1);
            Vec3 to = new Vec3(x2, y2, z2);
            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.hitVec == null) {
                return 2.0;
            }
            double length = from.distanceTo(to);
            return length < 1.0E-9 ? 0.0 : from.distanceTo(hit.hitVec) / length;
        };
    }

    /**
     * In range, seen (or through-walls within its range, or reachable by the high arc), best first by priority. Who
     * counts is decided as KillAura decides it (TargetFilter, friends, teams and bot-check as switches).
     */
    private List<EntityPlayer> candidates() {
        List<EntityPlayer> out = new ArrayList<EntityPlayer>();
        final List<Double> scores = new ArrayList<Double>();
        this.leftOut.setLength(0);
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player == mc.getRenderViewEntity()) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(player);
            String no = player.isDead || player.deathTime > 0 ? "dead"
                    : distance > this.range.getValue() ? "out of range"
                    : !TargetFilter.accepts(player) ? "TargetFilter (health " + player.getHealth() + ")"
                    : atlas.util.TeamUtil.isFriend(player) ? "friend"
                    : this.teams.getValue() && atlas.util.TeamUtil.isSameTeam(player) ? "same team"
                    : this.botCheck.getValue() && atlas.util.TeamUtil.isBot(player) ? "bot"
                    : null;
            if (no != null) {
                this.leftOut.append(player.getName()).append(' ').append(Math.round(distance)).append("m ")
                        .append(no).append("; ");
                continue;
            }
            /* Not seen: through-walls within its range, or a high arc may still reach it (it is flown first). */
            boolean lobbable = this.gravityType.getValue() == 1 && this.allowLob.getValue();
            if (!mc.thePlayer.canEntityBeSeen(player)
                    && (!this.throughWalls.getValue() || distance > this.throughWallsRange.getValue()) && !lobbable) {
                this.leftOut.append(player.getName()).append(' ').append(Math.round(distance)).append("m not seen; ");
                continue;
            }
            double score;
            switch (this.priority.getValue()) {
                case 0:
                    score = player.getHealth();
                    break;
                case 1:
                    score = distance;
                    break;
                default:
                    score = RotationUtil.angleToEntity(player);
                    break;
            }
            int at = 0;
            while (at < scores.size() && scores.get(at) <= score) {
                at++;
            }
            scores.add(at, score);
            out.add(at, player);
        }
        return out;
    }

    /** Gravity-type None: straight at the middle of the chosen part of the body. */
    private float[] faceBody(EntityPlayer t) {
        AxisAlignedBB box = t.getEntityBoundingBox();
        double height = box.maxY - box.minY;
        double top = bodyY(this.highestBodyPoint.getValue());
        double bottom = bodyY(this.lowestBodyPoint.getValue());
        double y = box.minY + height * (Math.min(top, bottom) + Math.max(top, bottom)) / 2.0;
        double dx = (box.minX + box.maxX) / 2.0 - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = (box.minZ + box.maxZ) / 2.0 - mc.thePlayer.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return null;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Float.isNaN(yaw) || Float.isNaN(pitch) ? null : new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    /** Head, Body, Feet as a share of the box height from its bottom. */
    private static double bodyY(int point) {
        return point == 0 ? 0.9 : point == 1 ? 0.5 : 0.1;
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        EntityPlayer shown = this.target;
        if (!this.isEnabled() || shown == null || shown.isDead || mc.theWorld == null
                || !mc.theWorld.playerEntities.contains(shown)) {
            return;
        }
        if (this.mark.getValue()) {
            RenderUtil.enableRenderState();
            RenderUtil.drawEntityBoundingBox(shown, 37, 126, 255, 70, 2.0F, 0.05);
            RenderUtil.disableRenderState();
        }
        List<double[]> shownPath = this.path;
        if (this.showPath.getValue() && this.gravityType.getValue() == 1 && shownPath.size() > 1) {
            IAccessorRenderManager view = (IAccessorRenderManager) mc.getRenderManager();
            double rx = view.getRenderPosX();
            double ry = view.getRenderPosY();
            double rz = view.getRenderPosZ();
            RenderUtil.enableRenderState();
            RenderUtil.setColor(this.pathHits ? 0xC855FF55 : 0xC8FF5555);
            GL11.glLineWidth(1.5F);
            WorldRenderer lines = Tessellator.getInstance().getWorldRenderer();
            lines.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION);
            for (double[] p : shownPath) {
                lines.pos(p[0] - rx, p[1] - ry, p[2] - rz).endVertex();
            }
            Tessellator.getInstance().draw();
            RenderUtil.disableRenderState();
            GlStateManager.resetColor();
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.shownDistance >= 0) {
            return new String[]{this.gravityType.getModeString(), this.shownDistance + "m " + this.shownTicks + "t"};
        }
        return new String[]{this.gravityType.getModeString()};
    }
}
