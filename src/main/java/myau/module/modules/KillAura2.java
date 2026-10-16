package myau.module.modules;

import myau.Myau;
import myau.combat.AimGeometry;
import myau.combat.ClickPlan;
import myau.combat.HitFeedback;
import myau.combat.HitWindow;
import myau.combat.TargetPicker;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.management.TickActions;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.Ping;
import myau.util.PlayerUtil;
import myau.util.RotationEngine;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * KillAura rewritten from the ground up (2026-10-14), in layers that each do
 * one thing (after LiquidBounce nextgen's KillAura, GPL-3.0, CCBlueX):
 *
 * <ol>
 *   <li><b>Targets</b> ({@link TargetPicker}): valid entities within scan
 *       range (reach plus scan-extra), those the hit can reach first; SINGLE
 *       keeps its target while it stays valid, SWITCH moves on after
 *       switch-delay. With InvulnTiming's redirect, a target that cannot be
 *       hurt for a while gives way to one in reach, near the look, that
 *       can.</li>
 *   <li><b>Aim</b>: the old KillAura's SMOOTHSTEP aim with the owner's
 *       tuned values (min-turn 14, max-turn 60, turn-accel 30, aim-drift 0.4,
 *       flick overshoot, hand tremor, lazy, short stop), on the mouse grid.
 *       When the target is gone the look eases back to the camera, still on
 *       the mouse grid, instead of snapping.</li>
 *   <li><b>Clicks</b> ({@link ClickPlan}): planned log-normal intervals
 *       around a CPS drawn per combo, at most max-per-tick a tick.</li>
 *   <li><b>Timing</b> ({@link HitWindow}): when the target can be hurt again,
 *       from this client's own send clock (exact for its own hits) or from
 *       the hurt time it sees (InvulnTiming, for anyone else's). With
 *       InvulnTiming's hold-fire on, clicks into the window are not made
 *       (now and then one in its last two ticks is); the clicker's next click
 *       after it opens is the hit, so combo hits fall 10-13 ticks apart, not
 *       10 every time.</li>
 *   <li><b>Hit</b>: a click becomes an attack only when the look this tick's
 *       movement packet carries reaches the hitbox within attack-range, with
 *       no block in the way (unless through-walls) and, with raycast, the
 *       target is the first entity along it (another enemy there takes the
 *       hit, as a vanilla crosshair would give it); not while an item is in
 *       use, nor in a tick that placed, dug, used or clicked a window. A click
 *       that cannot land swings at the air while the target is within
 *       swing-range, as a hand that is still closing in does.</li>
 *   <li><b>Block</b>: in four windows of five the sword goes up one or two
 *       ticks after a hit (never in its tick), while the target is inside
 *       the window and can hit back (Legit), or whenever it is inside the
 *       window (Always); it comes down one or two ticks before the window
 *       opens, so the next hit lands unblocked and a release and a hit never
 *       share a tick (vanilla handles no attack in the tick it releases). It
 *       stays down while a catch (Clutch) or a placement has the hand.</li>
 * </ol>
 */
public class KillAura2 extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] SORTS = {"Distance", "Health", "Angle", "HurtTime"};
    private static final int NONE = Integer.MIN_VALUE;
    /** A redirect never asks for a bigger turn than this (degrees). */
    private static final float REDIRECT_MAX_TURN = 60.0F;
    /** Chance a click in the last two ticks of our own window is made anyway (a hand's early click). */
    private static final int EARLY_CLICK_PERCENT = 25;
    /** Chance the sword goes up at all in a window our hit opened. */
    private static final int BLOCK_CHANCE = 80;

    // Targets
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Single", "Switch"});
    public final ModeProperty sort = new ModeProperty("sort", 0, SORTS);
    public final IntProperty switchDelay = new IntProperty("switch-delay", 300, 50, 2000,
            () -> this.mode.getValue() == 1);
    public final FloatProperty attackRange = new FloatProperty("attack-range", 3.0F, 2.5F, 6.0F);
    /* Clicks that cannot land still swing within this range, as a hand that is
       closing in does (LB FailSwing, Raven's swing range); and the sword is
       only raised within it. */
    public final FloatProperty swingRange = new FloatProperty("swing-range", 3.5F, 2.5F, 6.0F);
    public final FloatProperty scanExtra = new FloatProperty("scan-extra", 1.5F, 0.0F, 3.0F);
    public final IntProperty fov = new IntProperty("fov", 180, 30, 180);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", false);
    public final BooleanProperty raycast = new BooleanProperty("raycast", true);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);

    // Clicks
    public final IntProperty minCps = new IntProperty("min-cps", 10, 1, 20);
    public final IntProperty maxCps = new IntProperty("max-cps", 14, 1, 20);
    public final IntProperty maxPerTick = new IntProperty("max-per-tick", 1, 1, 2);

    // Aim
    /* The old KillAura's SMOOTHSTEP aim with the owner's tuned values (2026-10-14). */
    public final IntProperty minTurn = new IntProperty("min-turn", 14, 1, 180);
    public final IntProperty maxTurn = new IntProperty("max-turn", 60, 1, 180);
    public final FloatProperty turnAccel = new FloatProperty("turn-accel", 30.0F, 0.0F, 180.0F);
    public final FloatProperty aimDrift = new FloatProperty("aim-drift", 0.4F, 0.0F, 1.0F);
    public final BooleanProperty flickOvershoot = new BooleanProperty("flick-overshoot", true);
    public final BooleanProperty lazy = new BooleanProperty("lazy", true);
    public final BooleanProperty shortStop = new BooleanProperty("short-stop", true);
    /*
     * None: movement by the camera (the server sees moves its look cannot
     * explain). FollowAim: movement turns with the aim, keys unchanged - what
     * the old KillAura's Legit did, and what the server can always simulate.
     * KeepDirection: keys remapped so the walk keeps the camera's direction;
     * sprint is dropped whenever the remapped keys are not forward (vanilla
     * needs forward input to sprint). 2026-10-14: remapping while sprinting
     * gave 3-6 setbacks a minute against 1.4 with the old aura.
     */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"None", "FollowAim", "KeepDirection"})
            .alias("silent", "FollowAim");

    // Block
    /* Legit: the old KillAura's LEGIT (the owner's pick), timed by the hit
       window: up after a hit while the target can hit back, down the tick
       before the window opens. Always: the same without the threat check. */
    /* Default None since 2026-10-15: without NoSlow a raised sword walks at 20 % and drops the sprint, so the
       next hit lands unsprinted (sprint hits 0-1 of 5 in the 19:48/19:52 games, 42-78 % with the old aura). */
    public final ModeProperty autoBlock = new ModeProperty("auto-block", 0, new String[]{"None", "Legit", "Always"});
    /* Legit's threat test (2026-10-16), as KillAura's LegitThreat, after
       LiquidBounce NextGen's KillAuraAutoBlock.OnlyWhenInDanger. Look: an
       enemy's look ray, within its reach and with line of sight, lands on
       our box grown by legit-tolerance. Swing: the old rule (just hit, or
       within 3.6 facing us within 60 degrees and swinging), true for nearly
       the whole fight. */
    public final ModeProperty legitThreat = new ModeProperty("legit-threat", 0, new String[]{"Look", "Swing"},
            () -> this.autoBlock.getValue() == 1);
    public final FloatProperty legitTolerance = new FloatProperty("legit-tolerance", 0.3F, 0.0F, 1.0F,
            () -> this.autoBlock.getValue() == 1 && this.legitThreat.getValue() == 0);
    /* 2026-10-16, after Rise 6.9.5 (its own AutoBlockA/B checks flag what our
       earlier blocks did: use state toggled within a tick or two, and
       sprinting with the sword up). block-min-ticks: once up, the sword stays
       up this long, and goes up again no sooner than 2 ticks after it came
       down. block-stop-sprint: sprint is ended a tick before the block (the
       C0B reaches the server before the C08), kept off while it is up, and the
       next hit waits for the sprint to be back. block-release Slot: the block
       ends by a slot change instead of a C07 -- away in one tick, back the
       next, with a movement between, because a 1.8 server only drops the use
       when it updates the player and finds another item in hand (switching
       away and back before that leaves the block up). block-log: every hit
       taken, with the server's verdict from the damage (config/Atlas/ka2block-*.txt). */
    public final IntProperty blockMinTicks = new IntProperty("block-min-ticks", 3, 2, 6,
            () -> this.autoBlock.getValue() != 0);
    public final BooleanProperty blockStopSprint = new BooleanProperty("block-stop-sprint", true,
            () -> this.autoBlock.getValue() != 0);
    public final ModeProperty blockRelease = new ModeProperty("block-release", 0, new String[]{"Slot", "Release"},
            () -> this.autoBlock.getValue() != 0);
    public final BooleanProperty blockLog = new BooleanProperty("block-log", true,
            () -> this.autoBlock.getValue() != 0);

    // Conditions
    public final BooleanProperty requirePress = new BooleanProperty("require-press", false);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", false);
    public final BooleanProperty inventoryCheck = new BooleanProperty("inventory-check", true);
    public final BooleanProperty ignoredBackoff = new BooleanProperty("ignored-backoff", true);

    private final Random random = new Random();
    private final TargetPicker picker = new TargetPicker();
    private final ClickPlan clicks = new ClickPlan(this.random);
    private final RotationEngine aimEngine = new RotationEngine();
    private final HitFeedback feedback = new HitFeedback();
    private final HitWindow window = new HitWindow();

    // Aim state, as in the old KillAura (2026-10-08): the wandering aim point
    // (-1..1 across the aim box per axis) and its goal; per-flick speed and its
    // slow wobble; the overshoot carried past the target; the tremor phase.
    private EntityLivingBase aimFor;
    private float driftX, driftY, driftZ;
    private float driftGoalX, driftGoalY, driftGoalZ;
    private int driftTicks;
    private float flickSpeed = 1.0F;
    private float speedWobble;
    private float lastAimDistance;
    private float overYaw, overPitch;
    private boolean overArmed;
    private float aimPhase;
    private int shortStopTicks;
    private int tick;

    private EntityLivingBase target;
    /** The look the last movement packet carried (the start of this tick's turn). */
    private float sentYaw;
    private float sentPitch;
    private boolean haveSent;
    /** This module turned the look last tick, so a release has to ease back. */
    private boolean aiming;
    private boolean attackPending;
    private double[] eyesAtPre;

    /** A press kept for the tick the target can be hurt, or one a release took: the last tick it may wait to. */
    private int carryUntil = NONE;
    /** Ticks in a row a click was held on the hurt time this client sees (not on its own clock). */
    private int heldOnObserved;
    /** The sword is up because this module raised it. */
    private boolean blocking;
    /** The tick this module let the sword down: no attack in it. */
    private int releasedAt = NONE;
    /* The block in the window our last hit opened (planBlock): whether, from
       which tick, and how many ticks before the window opens it comes down. */
    private boolean blockThisWindow = true;
    private int blockFrom = NONE;
    private int releaseLead = 1;
    /* 2026-10-16 (Rise-style block): when the sword went up and came down,
       a block waiting for the sprint to end, the slot the release switched
       away from (to switch back to next tick), and whether the block ended
       a sprint (the next hit waits for it to be back). */
    private int blockedAt = NONE;
    private int unblockedAt = NONE;
    private int blockPendingUntil = NONE;
    private int slotBackAt = NONE;
    private int slotBack = -1;
    private boolean sprintStopped;
    private boolean sprintAtSend;
    /* block-log: per tick, the block as the server has it and the sprint. */
    private final boolean[] blockTrace = new boolean[64];
    private final boolean[] sprintTrace = new boolean[64];
    private int lastOwnHurt;
    private float lastOwnHealth = Float.NaN;
    private int logPending;
    private float logBefore;
    private float logLowest;
    private float[][] logRanges;
    private String logLine = "";
    private int logReduced;
    private int logFull;
    private final java.util.Map<String, Integer> logVerdicts = new java.util.TreeMap<String, Integer>();
    private java.io.File logFile;

    public KillAura2() {
        super("KillAura2", false, false, "Attacks enemies around you: careful aim, human click timing, hits timed to the hurt window, auto-block");
    }

    // ------------------------------------------------------------- API used by other modules

    public EntityLivingBase getTarget() {
        return this.target;
    }

    public boolean isAttackAllowed() {
        return this.weaponOk() && !Myau.moduleManager.modules.get(Scaffold.class).isEnabled()
                && (!this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode()));
    }

    /** The sword is up because this module raised it. */
    public boolean isBlocking() {
        return this.isEnabled() && this.blocking;
    }

    /** Whether this module blocks by itself (other blocking modules stand aside). */
    public boolean blocksByItself() {
        return this.isEnabled() && this.autoBlock.getValue() != 0;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnabled() {
        KillAura old = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (old != null && old.isEnabled()) {
            old.setEnabled(false);
        }
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.stopBlock(false);
        this.finishSlotBack();
        this.resetState();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        if (this.blocking) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        this.blocking = false;
        this.slotBackAt = NONE;
        this.resetState();
    }

    private void resetState() {
        this.target = null;
        this.picker.reset();
        this.aimFor = null;
        this.shortStopTicks = 0;
        this.aiming = false;
        this.attackPending = false;
        this.haveSent = false;
        this.feedback.reset();
        this.window.reset();
        this.carryUntil = NONE;
        this.heldOnObserved = 0;
        this.releasedAt = NONE;
        this.blockThisWindow = true;
        this.blockFrom = NONE;
        this.releaseLead = 1;
        this.blockedAt = NONE;
        this.unblockedAt = NONE;
        this.blockPendingUntil = NONE;
        this.slotBackAt = NONE;
        this.slotBack = -1;
        this.sprintStopped = false;
        this.logPending = 0;
    }

    // ------------------------------------------------------------- PRE: target, block release, turn

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.haveSent) {
            this.sentYaw = mc.thePlayer.rotationYaw;
            this.sentPitch = mc.thePlayer.rotationPitch;
            this.haveSent = true;
        }
        this.clicks.configure(this.minCps.getValue(), this.maxCps.getValue(), this.maxPerTick.getValue());
        this.clicks.tick(System.currentTimeMillis());
        this.attackPending = false;
        this.tick++;
        int last = (this.tick - 1) & 63;
        this.blockTrace[last] = this.blocking;
        this.sprintTrace[last] = this.sprintAtSend;
        if (this.slotBackAt != NONE && this.tick >= this.slotBackAt) {
            this.finishSlotBack();
        }
        if (this.blockStopSprint.getValue() && (this.blocking
                || this.blockPendingUntil != NONE && this.tick <= this.blockPendingUntil)) {
            /* Up, or about to be: no sprint goes out (vanilla does not start
               one with an item in use; NoSlow's sword-sprint keeps one going). */
            mc.thePlayer.setSprinting(false);
        }
        this.watchOwnHurt();

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        this.eyesAtPre = new double[]{eyes.xCoord, eyes.yCoord, eyes.zCoord};
        EntityLivingBase previous = this.target;
        this.target = this.isAttackAllowed() && !mc.thePlayer.isSpectator() ? this.chooseTarget() : null;
        if (this.target != previous) {
            this.newTarget();
        }
        if (this.target != null) {
            if (this.feedback.observe(this.tick, this.target.hurtTime)) {
                // The hit that opened our window showed no hurt: it never opened one.
                this.window.ignored();
            }
            this.window.observe(this.tick, this.target.hurtTime, this.roundTripTicks());
        }
        this.updateBlock();

        if (this.target == null) {
            this.easeBack(event);
            return;
        }
        this.aimAt(event, this.target);
        this.attackPending = true;
    }

    private void newTarget() {
        this.feedback.reset();
        this.window.reset();
        this.carryUntil = NONE;
        this.heldOnObserved = 0;
    }

    private EntityLivingBase chooseTarget() {
        double reach = this.attackRange.getValue();
        double scan = reach + this.scanExtra.getValue();
        List<TargetPicker.Candidate> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!this.isValidTarget(living)) {
                continue;
            }
            double distance = AimGeometry.distance(this.eyesAtPre, box(living));
            if (distance > scan) {
                continue;
            }
            candidates.add(new TargetPicker.Candidate(living, distance,
                    living.getHealth() + living.getAbsorptionAmount(),
                    RotationUtil.angleToEntity(living), living.hurtTime, distance <= reach));
        }
        TargetPicker.Mode pickMode = this.mode.getValue() == 1 ? TargetPicker.Mode.SWITCH : TargetPicker.Mode.SINGLE;
        TargetPicker.Sort pickSort = TargetPicker.Sort.values()[this.sort.getValue()];
        long now = System.currentTimeMillis();
        EntityLivingBase chosen = (EntityLivingBase) this.picker.pick(candidates, pickMode, pickSort,
                this.switchDelay.getValue(), now);
        EntityLivingBase redirect = this.redirect(chosen, candidates);
        if (redirect != null) {
            this.picker.force(redirect, now);
            InvulnTiming timing = InvulnTiming.instance();
            if (timing != null) {
                timing.noteRedirect();
            }
            return redirect;
        }
        return chosen;
    }

    /**
     * InvulnTiming's redirect: the chosen target cannot be hurt for three ticks
     * or more, and another one in reach, within a modest turn of the look, can
     * be hurt now. Its own window ticks on all the same.
     */
    private EntityLivingBase redirect(EntityLivingBase chosen, List<TargetPicker.Candidate> candidates) {
        InvulnTiming timing = InvulnTiming.instance();
        if (chosen == null || timing == null || !timing.isEnabled() || !timing.redirect.getValue()
                || chosen != this.target || this.opensIn(chosen) < 3) {
            return null;
        }
        EntityLivingBase best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TargetPicker.Candidate c : candidates) {
            EntityLivingBase other = (EntityLivingBase) c.key;
            if (other == chosen || !c.inReach || !timing.canDamage(other) || c.distance >= bestDistance) {
                continue;
            }
            double[] b = box(other);
            float[] to = AimGeometry.rotationTo(this.eyesAtPre,
                    new double[]{(b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0});
            if (RotationEngine.angle(this.sentYaw, this.sentPitch, to[0], to[1]) > REDIRECT_MAX_TURN) {
                continue;
            }
            best = other;
            bestDistance = c.distance;
        }
        return best;
    }

    private void aimAt(UpdateEvent event, EntityLivingBase entity) {
        float fromYaw = this.sentYaw;
        float fromPitch = this.sentPitch;
        if (this.lazy.getValue() && AimGeometry.hits(this.eyesAtPre, AimGeometry.direction(fromYaw, fromPitch),
                box(entity), this.attackRange.getValue())) {
            /* The look already lands: no turn (LB lazyRotation). */
            this.apply(event, fromYaw, fromPitch);
            return;
        }
        float[] rot = this.stepTowards(entity, fromYaw, fromPitch);
        if (this.shortStop.getValue()) {
            if (this.shortStopTicks <= 0 && this.random.nextInt(100) < 3) {
                this.shortStopTicks = 1 + this.random.nextInt(2);
            }
            if (this.shortStopTicks > 0) {
                /* Only 0-10 % of the way this tick (LB towardsLinear 0..0.1). */
                this.shortStopTicks--;
                float f = this.random.nextFloat() * 0.1F;
                rot = new float[]{fromYaw + MathHelper.wrapAngleTo180_float(rot[0] - fromYaw) * f,
                        fromPitch + (rot[1] - fromPitch) * f};
            }
        }
        /* Hand tremor: two sines, scaled by how far this tick's correction
           moved, so a settled aim carries almost none. */
        float stepYaw = MathHelper.wrapAngleTo180_float(rot[0] - fromYaw);
        float stepPitch = rot[1] - fromPitch;
        float activity = Math.min(1.0F, (Math.abs(stepYaw) + Math.abs(stepPitch)) / 6.0F);
        this.aimPhase += 0.35F + (this.random.nextFloat() * 0.1F - 0.05F);
        float yaw = rot[0] + this.tremor(0.0F, 2.5F, activity);
        float pitch = rot[1] + this.tremor(1.9F, 1.5F, activity);
        float[] fixed = RotationUtil.gcd(new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)},
                new float[]{fromYaw, fromPitch});
        this.apply(event, fixed[0], MathHelper.clamp_float(fixed[1], -90.0F, 90.0F));
    }

    /**
     * The old KillAura's SMOOTHSTEP turn (stepTowards), unchanged in substance:
     * the nearest point of the aim box (sides inset 15 %, 5-75 % of the height)
     * pulled toward a wandering point by aim-drift; flicks over 20 degrees
     * aim 4-12 % past the target and come back; the speed runs from min-turn
     * near the target to max-turn 60 degrees away along a smoothstep, with a
     * pace per flick and a slow wobble; turn-accel caps how fast it grows.
     */
    private float[] stepTowards(EntityLivingBase entity, float currentYaw, float currentPitch) {
        if (this.aimFor != entity) {
            this.aimFor = entity;
            this.driftTicks = 0;
            this.overYaw = 0.0F;
            this.overPitch = 0.0F;
            this.overArmed = false;
            this.lastAimDistance = 0.0F;
        }
        AxisAlignedBB aim = RotationEngine.aimBox(entity.getEntityBoundingBox(), 0.15, 0.05, 0.75);
        Vec3 eyes = new Vec3(this.eyesAtPre[0], this.eyesAtPre[1], this.eyesAtPre[2]);
        Vec3 point = RotationEngine.nearestOnBox(aim, eyes, currentYaw, currentPitch);
        float weight = this.aimDrift.getValue();
        if (weight > 0.0F) {
            if (--this.driftTicks <= 0) {
                this.driftTicks = 6 + this.random.nextInt(9);
                this.driftGoalX = this.random.nextFloat() * 2.0F - 1.0F;
                this.driftGoalY = this.random.nextFloat() * 2.0F - 1.0F;
                this.driftGoalZ = this.random.nextFloat() * 2.0F - 1.0F;
            }
            this.driftX += (this.driftGoalX - this.driftX) * 0.2F;
            this.driftY += (this.driftGoalY - this.driftY) * 0.2F;
            this.driftZ += (this.driftGoalZ - this.driftZ) * 0.2F;
            double cx = (aim.minX + aim.maxX) / 2.0 + this.driftX * (aim.maxX - aim.minX) / 2.0;
            double cy = (aim.minY + aim.maxY) / 2.0 + this.driftY * (aim.maxY - aim.minY) / 2.0;
            double cz = (aim.minZ + aim.maxZ) / 2.0 + this.driftZ * (aim.maxZ - aim.minZ) / 2.0;
            point = new Vec3(point.xCoord + (cx - point.xCoord) * weight,
                    point.yCoord + (cy - point.yCoord) * weight,
                    point.zCoord + (cz - point.zCoord) * weight);
        }
        float[] full = RotationEngine.rotationsTo(eyes, point, currentYaw);

        float rawYaw = MathHelper.wrapAngleTo180_float(full[0] - currentYaw);
        float rawPitch = full[1] - currentPitch;
        float rawDistance = (float) Math.sqrt(rawYaw * rawYaw + rawPitch * rawPitch);
        if (rawDistance > 12.0F && this.lastAimDistance <= 12.0F) {
            this.flickSpeed = 0.85F + this.random.nextFloat() * 0.3F;
        }
        if (this.flickOvershoot.getValue() && rawDistance > 20.0F && this.lastAimDistance <= 20.0F
                && !this.overArmed && this.random.nextInt(100) < 60) {
            float amount = Math.min(4.0F, rawDistance * (0.04F + this.random.nextFloat() * 0.08F));
            this.overYaw = rawYaw / rawDistance * amount;
            this.overPitch = rawPitch / rawDistance * amount * 0.5F;
            this.overArmed = true;
        }
        this.lastAimDistance = rawDistance;
        if (this.overArmed) {
            float leftYaw = rawYaw + this.overYaw;
            float leftPitch = rawPitch + this.overPitch;
            if (leftYaw * leftYaw + leftPitch * leftPitch < 2.25F) {
                this.overArmed = false;
            }
        } else {
            this.overYaw *= 0.45F;
            this.overPitch *= 0.45F;
            if (Math.abs(this.overYaw) + Math.abs(this.overPitch) < 0.1F) {
                this.overYaw = 0.0F;
                this.overPitch = 0.0F;
            }
        }
        full = new float[]{full[0] + this.overYaw, MathHelper.clamp_float(full[1] + this.overPitch, -90.0F, 90.0F)};
        return this.paced(currentYaw, currentPitch, full[0], full[1]);
    }

    /** One tick toward (toYaw, toPitch) at the smoothstep pace. */
    private float[] paced(float currentYaw, float currentPitch, float toYaw, float toPitch) {
        float deltaYaw = MathHelper.wrapAngleTo180_float(toYaw - currentYaw);
        float deltaPitch = toPitch - currentPitch;
        float distance = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance < 0.05F) {
            return new float[]{toYaw, toPitch};
        }
        float min = Math.min(this.minTurn.getValue(), this.maxTurn.getValue());
        float max = Math.max(this.minTurn.getValue(), this.maxTurn.getValue());
        float t = Math.min(1.0F, distance / 60.0F);
        float shaped = t * t * (3.0F - 2.0F * t);
        float step = min + (max - min) * shaped;
        this.speedWobble = this.speedWobble * 0.8F + (float) this.random.nextGaussian() * 0.03F;
        step *= this.flickSpeed * (1.0F + Math.max(-0.12F, Math.min(0.12F, this.speedWobble)));
        return this.aimEngine.step(currentYaw, currentPitch, toYaw, toPitch, step, this.turnAccel.getValue(), 0, false);
    }

    private float tremor(float phaseOffset, float amplitude, float activity) {
        double p = this.aimPhase + phaseOffset;
        double wave = Math.sin(p) + Math.sin(p * 2.7 + 1.3) * 0.3;
        return (float) (wave * amplitude * activity);
    }

    /**
     * No target: ease the look back to the camera at the same pace, then let
     * it go. Every step is on the mouse grid (2026-10-15: they were not, so
     * the server saw a turn no mouse can make after every fight), and on
     * release the camera is put on the grid of the look last sent, so the
     * first vanilla packet after it is a whole number of mouse counts away.
     */
    private void easeBack(UpdateEvent event) {
        if (!this.aiming) {
            return;
        }
        float camYaw = mc.thePlayer.rotationYaw;
        float camPitch = mc.thePlayer.rotationPitch;
        if (RotationEngine.angle(this.sentYaw, this.sentPitch, camYaw, camPitch) < 1.0F) {
            this.aiming = false;
            this.aimFor = null;
            this.snapCameraToGrid();
            return;
        }
        float[] next = this.paced(this.sentYaw, this.sentPitch,
                this.sentYaw + AimGeometry.wrap(this.sentYaw, camYaw), camPitch);
        float[] fixed = RotationUtil.gcd(next, new float[]{this.sentYaw, this.sentPitch});
        this.apply(event, fixed[0], fixed[1]);
    }

    /** Moves the camera by less than half a mouse count, onto the grid of the look last sent. */
    private void snapCameraToGrid() {
        double unit = RotationUtil.gcd();
        float dYaw = mc.thePlayer.rotationYaw - this.sentYaw;
        float dPitch = mc.thePlayer.rotationPitch - this.sentPitch;
        if (unit <= 0.0 || Math.abs(dYaw) > 180.0F) {
            return;
        }
        float yaw = this.sentYaw + (float) (Math.round(dYaw / unit) * unit);
        float pitch = MathHelper.clamp_float(this.sentPitch + (float) (Math.round(dPitch / unit) * unit), -90.0F, 90.0F);
        mc.thePlayer.prevRotationYaw += yaw - mc.thePlayer.rotationYaw;
        mc.thePlayer.prevRotationPitch += pitch - mc.thePlayer.rotationPitch;
        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;
    }

    private void apply(UpdateEvent event, float yaw, float pitch) {
        event.setRotation(yaw, pitch, 1);
        if (this.moveFix.getValue() != 0) {
            event.setPervRotation(yaw, 1);
        }
        mc.thePlayer.rotationYawHead = yaw;
        mc.thePlayer.renderYawOffset = yaw;
        this.aiming = true;
    }

    // ------------------------------------------------------------- after every module's rotation: the hit

    /**
     * Called by MixinEntityPlayerSP after the PRE UpdateEvent, when no module
     * can change the look any more: the hit is judged along the look the
     * movement packet will carry.
     */
    public void afterRotations(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        float yaw = event.getNewYaw();
        float pitch = event.getNewPitch();
        boolean wasPending = this.attackPending;
        this.attackPending = false;
        this.sentYaw = yaw;
        this.sentPitch = pitch;
        this.haveSent = true;
        if (!wasPending || this.target == null) {
            return;
        }
        int presses = 0;
        while (this.clicks.take()) {
            presses++;
        }
        if (this.carryUntil != NONE) {
            if (this.carryUntil >= this.tick) {
                presses = Math.max(presses, 1);
            }
            this.carryUntil = NONE;
        }
        if (this.ignoredBackoff.getValue() && this.feedback.paused(this.tick)) {
            // The server is not taking hits on this target: aim, but no swings.
            return;
        }
        this.sprintAtSend = mc.thePlayer.isSprinting();
        if (presses > 0 && this.sprintStopped && this.releasedAt != NONE && this.tick == this.releasedAt + 1
                && mc.thePlayer.isSprinting()) {
            /* The sprint the block ended is back this tick, its C0B after this
               attack would be: the hit waits one tick so it lands sprinting. */
            this.carry(2);
            return;
        }
        if (presses > 0 && this.slotBackAt != NONE) {
            // The server holds the other slot until the switch back.
            this.carry(2);
            return;
        }
        if (presses > 0 && (this.releasedAt == this.tick || mc.thePlayer.isUsingItem())) {
            /* Vanilla 1.8 handles no attack in the tick an item is let go, nor
               while one is in use: the press waits for the next tick. */
            this.carry(2);
            return;
        }
        boolean attacked = false;
        for (int i = 0; i < presses; i++) {
            int opens = this.opensIn(this.target);
            if (opens > 0 && this.holdsFire(opens)) {
                /* Into the window: this click is not made. The next one the
                   clicker plans after the window opens is the hit -- 0-2 ticks
                   late, as a hand timing it is. Until 2026-10-15 19:10 the
                   click was kept for the exact tick: every hit of a combo 10
                   ticks after the last, 43 of 43 landing, none into a window,
                   and the damage halved after five minutes on Pika. */
                continue;
            }
            if (this.hitSelectHolds()) {
                // HitSelect ACTIVE keeps this click back; it is spent.
                continue;
            }
            EntityLivingBase before = this.target;
            EntityLivingBase hit = this.resolveHit(yaw, pitch);
            if (hit == null) {
                this.swingAtAir();
                continue;
            }
            if (hit != before) {
                // Raycast gave the hit to someone else: their window decides.
                opens = this.opensIn(hit);
                if (opens > 0 && this.holdsFire(opens)) {
                    continue;
                }
            }
            if (!this.canAttackNow()) {
                break;
            }
            this.attack(hit, opens == 0);
            attacked = true;
            if (opens == 0) {
                this.planBlock();
            }
        }
        this.maybeBlock(attacked);
    }

    /**
     * A hit opened a window: whether the sword goes up in it, when, and how
     * early it comes down -- drawn per window. A hand does not raise the sword
     * in the same tick as every hit, nor after every hit, nor lower it to the
     * tick (until 19:10 it did all three, every time).
     */
    private void planBlock() {
        this.blockThisWindow = this.random.nextInt(100) < BLOCK_CHANCE;
        this.blockFrom = this.tick + 1 + this.random.nextInt(2);
        this.releaseLead = this.random.nextInt(100) < 40 ? 2 : 1;
    }

    /** Keeps one press for up to {@code ticks} ticks. */
    private void carry(int ticks) {
        this.carryUntil = this.tick + Math.max(1, ticks);
    }

    /**
     * Whether a click into the window is not made: InvulnTiming's hold-fire.
     * On this client's own clock the window is exact, but a hand is not: in
     * its last two ticks a click still goes now and then (EARLY_CLICK_PERCENT),
     * landing in the window as a human's early click does. On the hurt time
     * it sees, InvulnTiming's max-hold-ticks caps a run of held clicks,
     * however wrong that estimate.
     */
    private boolean holdsFire(int opens) {
        InvulnTiming timing = InvulnTiming.instance();
        if (timing == null || !timing.isEnabled() || !timing.holdFire.getValue()) {
            return false;
        }
        if (this.window.ours(this.tick, this.roundTripTicks())) {
            this.heldOnObserved = 0;
            return opens > 2 || this.random.nextInt(100) >= EARLY_CLICK_PERCENT;
        }
        if (this.heldOnObserved >= timing.maxHold.getValue()) {
            this.heldOnObserved = 0;
            return false;
        }
        this.heldOnObserved++;
        return true;
    }

    /** Ticks until a hit sent then on {@code entity} counts (0 = now). */
    private int opensIn(EntityLivingBase entity) {
        InvulnTiming timing = InvulnTiming.instance();
        float observed = timing != null ? timing.predict(entity)
                : entity.hurtTime - Math.min(10.0F, Math.max(0, Ping.own()) / 50.0F) + 0.5F;
        if (entity != this.target) {
            return observed <= 0.0F ? 0 : (int) Math.ceil(observed);
        }
        return this.window.opensIn(this.tick, observed, this.roundTripTicks());
    }

    private int roundTripTicks() {
        int ping = Ping.own();
        return ping > 0 ? (int) Math.ceil(ping / 50.0) : 6;
    }

    private boolean hitSelectHolds() {
        HitSelect hitSelect = (HitSelect) Myau.moduleManager.modules.get(HitSelect.class);
        return hitSelect != null && hitSelect.dropsAuraHit(this.target);
    }

    /**
     * Who this look hits, or null when it does not land: the target, or with
     * raycast another valid enemy in front of it (it becomes the target, as
     * a vanilla crosshair would give it the hit). Anything else first along
     * the ray takes the hit in vanilla, so no attack goes past it.
     */
    private EntityLivingBase resolveHit(float yaw, float pitch) {
        double range = this.attackRange.getValue();
        double[] dir = AimGeometry.direction(yaw, pitch);
        if (this.raycast.getValue()) {
            Entity first = this.firstAlong(dir, range);
            if (first != null && first != this.target) {
                if (first instanceof EntityLivingBase && this.isValidTarget((EntityLivingBase) first)
                        && this.clearOfBlocks(dir,
                        AimGeometry.hitDistance(this.eyesAtPre, dir, box((EntityLivingBase) first), range))) {
                    this.retarget((EntityLivingBase) first);
                    return this.target;
                }
                return null;
            }
        }
        double at = AimGeometry.hitDistance(this.eyesAtPre, dir, box(this.target), range);
        return at >= 0.0 && this.clearOfBlocks(dir, at) ? this.target : null;
    }

    /** No block before {@code at} along the look (or through-walls is on). */
    private boolean clearOfBlocks(double[] dir, double at) {
        if (at < 0.0) {
            return false;
        }
        if (this.throughWalls.getValue() || at == 0.0) {
            return true;
        }
        Vec3 from = new Vec3(this.eyesAtPre[0], this.eyesAtPre[1], this.eyesAtPre[2]);
        Vec3 to = new Vec3(this.eyesAtPre[0] + dir[0] * at, this.eyesAtPre[1] + dir[1] * at,
                this.eyesAtPre[2] + dir[2] * at);
        return mc.theWorld.rayTraceBlocks(from, to, false, true, false) == null;
    }

    /** The nearest entity a vanilla crosshair could pick along the look within range. */
    private Entity firstAlong(double[] dir, double range) {
        AxisAlignedBB sweep = mc.thePlayer.getEntityBoundingBox()
                .addCoord(dir[0] * range, dir[1] * range, dir[2] * range).expand(1.0, 1.0, 1.0);
        Entity first = null;
        double nearest = range + 1.0E-3;
        for (Entity entity : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, sweep)) {
            if (!entity.canBeCollidedWith() || entity == mc.thePlayer.ridingEntity
                    || entity instanceof EntityArmorStand && entity.isInvisible()) {
                continue;
            }
            double border = entity.getCollisionBorderSize();
            AxisAlignedBB bb = entity.getEntityBoundingBox().expand(border, border, border);
            double at = AimGeometry.hitDistance(this.eyesAtPre, dir,
                    new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ}, range);
            if (at >= 0.0 && at < nearest) {
                nearest = at;
                first = entity;
            }
        }
        return first;
    }

    private void retarget(EntityLivingBase entity) {
        this.target = entity;
        this.picker.force(entity, System.currentTimeMillis());
        this.newTarget();
    }

    private void attack(EntityLivingBase entity, boolean counts) {
        mc.thePlayer.swingItem();
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        myau.event.EventManager.call(new myau.events.AttackEvent(entity));
        PacketUtil.sendPacket(new C02PacketUseEntity(entity, C02PacketUseEntity.Action.ATTACK));
        int hurtBefore = entity.hurtTime;
        if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
            PlayerUtil.attackEntity(entity);
        }
        this.window.sent(this.tick, counts);
        this.feedback.attacked(this.tick, hurtBefore, HitFeedback.waitTicksFor(Ping.own()));
    }

    /** A click that cannot land, with the target near: a swing at the air, as a vanilla miss. */
    private void swingAtAir() {
        if (myau.management.Arbiter.catching() || TickActions.any(TickActions.USE | TickActions.SWING)) {
            // A catch has the look, or this tick already swung for a placement.
            return;
        }
        if (AimGeometry.distance(this.eyesAtPre, box(this.target)) > this.swingReach()
                || mc.thePlayer.isUsingItem() || this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer
                || ((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
            return;
        }
        mc.thePlayer.swingItem();
    }

    private boolean canAttackNow() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        }
        // Vanilla 1.8 handles attack clicks only while no item is in use (Minecraft.runTick).
        if (mc.thePlayer.isUsingItem()) {
            return false;
        }
        if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
            return false;
        }
        if (TickActions.any(TickActions.USE | TickActions.DIG | TickActions.INVENTORY | TickActions.INTERACT)) {
            TickActions.refuse();
            return false;
        }
        return this.isAttackAllowed();
    }

    private boolean weaponOk() {
        return !this.weaponsOnly.getValue() || ItemUtil.isHoldingSword() || ItemUtil.hasRawUnbreakingEnchant();
    }

    private double swingReach() {
        return Math.max(this.attackRange.getValue(), this.swingRange.getValue());
    }

    // ------------------------------------------------------------- auto-block

    /**
     * PRE: lets the sword down when it should no longer be up -- the tick
     * before the window opens (so that tick's movement is unblocked and the
     * next one can hit, sprinting), or when the target is gone, out of
     * swing range or (Legit) no longer a threat.
     */
    private void updateBlock() {
        if (!this.blocking) {
            return;
        }
        if (!mc.thePlayer.isUsingItem()) {
            // Ended elsewhere: a slot change, a death, a window.
            this.blocking = false;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            return;
        }
        if (useHeldByHand()) {
            // The player holds it now; theirs to let go.
            this.blocking = false;
            return;
        }
        EntityLivingBase t = this.target;
        boolean hard = t == null || this.autoBlock.getValue() == 0 || !ItemUtil.isHoldingSword()
                || myau.management.Arbiter.catching();
        boolean young = this.blockedAt != NONE && this.tick - this.blockedAt < this.blockMinTicks.getValue();
        boolean keep = !hard && (young
                || this.inSwing(t) && this.threat(t) && this.opensIn(t) > this.releaseLead);
        if (!keep) {
            this.stopBlock(true);
        }
    }

    /**
     * After this tick's clicks: the sword goes up while the target is inside
     * the window for three ticks or more (a hit just landed, or someone
     * else's did), within swing range, and (Legit) able to hit back.
     */
    private void maybeBlock(boolean attacked) {
        if (this.autoBlock.getValue() == 0 || this.blocking || attacked || this.target == null
                || !ItemUtil.isHoldingSword() || mc.thePlayer.isUsingItem() || useHeldByHand()
                || this.releasedAt == this.tick || mc.currentScreen instanceof GuiContainer) {
            return;
        }
        if (myau.management.Arbiter.catching() || Myau.playerStateManager.placing || Myau.playerStateManager.digging) {
            /* A catch or a placement has the hand (the old KillAura's guard,
               which KillAura2 lacked). */
            return;
        }
        if (this.window.ours(this.tick, this.roundTripTicks())
                && (!this.blockThisWindow || this.blockFrom != NONE && this.tick < this.blockFrom)) {
            return;
        }
        if (!this.inSwing(this.target) || !this.threat(this.target)
                || this.opensIn(this.target) < this.releaseLead + this.blockMinTicks.getValue()) {
            return;
        }
        if (this.unblockedAt != NONE && this.tick - this.unblockedAt < 2 || this.slotBackAt != NONE) {
            // Down for under two ticks, or the release's switch is not back: too quick a toggle.
            return;
        }
        if (this.blockStopSprint.getValue() && mc.thePlayer.isSprinting()) {
            /* Sprint off now (its C0B goes with this tick's movement); the
               block follows next tick, after it. */
            mc.thePlayer.setSprinting(false);
            this.sprintStopped = true;
            this.blockPendingUntil = this.tick + 2;
            return;
        }
        if (TickActions.any(TickActions.USE | TickActions.INVENTORY | TickActions.DIG)) {
            // Someone else's placement or click this tick: not in the same hand.
            return;
        }
        this.startBlock();
        if (this.blocking) {
            this.blockedAt = this.tick;
            this.blockPendingUntil = NONE;
        }
    }

    /**
     * Vanilla's right click: with the look on the target, INTERACT_AT then
     * INTERACT, then the use (the old KillAura's interactAttack); off it,
     * just the use.
     */
    private void startBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return;
        }
        EntityLivingBase t = this.target;
        double border = t.getCollisionBorderSize();
        AxisAlignedBB real = t.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition mop = RotationUtil.rayTrace(real, this.sentYaw, this.sentPitch, this.attackRange.getValue());
        if (mop != null && mop.hitVec != null) {
            MovingObjectPosition onEntity = new MovingObjectPosition(t, mop.hitVec);
            if (!mc.playerController.isPlayerRightClickingOnEntity(mc.thePlayer, t, onEntity)) {
                mc.playerController.interactWithEntitySendPacket(mc.thePlayer, t);
            }
        }
        if (mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held)) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress2();
        }
        this.blocking = mc.thePlayer.isUsingItem();
        if (this.blocking) {
            /* Held as a hand holds it (2026-10-16): with the key up, vanilla's
               runTick lets go the very next tick (onStoppedUsingItem, a C07),
               so every block lasted one tick -- block-min-ticks and the slot
               release never ran (ka2block 22:29-22:33: 0 of 27, traces
               1010100000000). */
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        }
    }

    /** The physical right button (or use key): the player blocking by hand, not this module's hold. */
    private static boolean useHeldByHand() {
        int code = mc.gameSettings.keyBindUseItem.getKeyCode();
        return code < 0 ? org.lwjgl.input.Mouse.isButtonDown(code + 100) : org.lwjgl.input.Keyboard.isKeyDown(code);
    }

    /** @param slot allow block-release Slot (not on disable: nothing may be left pending) */
    private void stopBlock(boolean slot) {
        if (!this.blocking) {
            return;
        }
        this.blocking = false;
        this.blockedAt = NONE;
        this.unblockedAt = this.tick;
        /* The key back to what the hand holds first, so vanilla does not
           right-click again once the use has ended. */
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        if (mc.thePlayer != null && mc.thePlayer.isUsingItem()) {
            if (slot && this.blockRelease.getValue() == 0 && mc.getNetHandler() != null) {
                /* Away now, back next tick: the server's player update in
                   between finds another item in hand and drops the use. */
                int current = mc.thePlayer.inventory.currentItem;
                this.slotBack = current;
                this.slotBackAt = this.tick + 1;
                mc.getNetHandler().addToSendQueue(new net.minecraft.network.play.client.C09PacketHeldItemChange((current + 1) % 9));
                mc.thePlayer.stopUsingItem();
            } else {
                mc.playerController.onStoppedUsingItem(mc.thePlayer);
            }
            this.releasedAt = this.tick;
        }
    }

    /** The switch back after a slot release, to the slot the player has. */
    private void finishSlotBack() {
        if (this.slotBackAt == NONE) {
            return;
        }
        this.slotBackAt = NONE;
        if (mc.thePlayer != null && mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new net.minecraft.network.play.client.C09PacketHeldItemChange(
                    mc.thePlayer.inventory.currentItem));
        }
        this.slotBack = -1;
    }

    /**
     * block-log: each hit taken, the server's verdict from the damage
     * (BlockVerdict), with the block and the sprint for the 13 ticks before.
     */
    private void watchOwnHurt() {
        float health = mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount();
        int hurt = mc.thePlayer.hurtTime;
        if (this.autoBlock.getValue() != 0 && this.blockLog.getValue() && hurt > this.lastOwnHurt
                && this.logPending == 0 && !Float.isNaN(this.lastOwnHealth)) {
            EntityPlayer attacker = null;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer || player.isDead || TeamUtil.isSameTeam(player)) {
                    continue;
                }
                if (mc.thePlayer.getDistanceToEntity(player) <= 6.0F && (attacker == null
                        || mc.thePlayer.getDistanceToEntity(player) < mc.thePlayer.getDistanceToEntity(attacker))) {
                    attacker = player;
                }
            }
            if (attacker != null) {
                StringBuilder up = new StringBuilder();
                StringBuilder sprint = new StringBuilder();
                for (int lag = 1; lag <= 13; lag++) {
                    int at = (this.tick - lag) & 63;
                    up.append(this.blockTrace[at] ? '1' : '0');
                    sprint.append(this.blockTrace[at] && this.sprintTrace[at] ? 'S' : this.sprintTrace[at] ? 's' : '.');
                }
                this.logRanges = myau.combat.BlockVerdict.ranges(attacker, mc.thePlayer);
                ItemStack held = attacker.getHeldItem();
                this.logLine = String.format("up[1..13t ago] %s sprint %s  by %s with %s  rtt %d",
                        up, sprint, attacker.getName(), held == null ? "hand"
                                : String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem()))
                                .replace("minecraft:", ""), this.roundTripTicks());
                this.logBefore = this.lastOwnHealth;
                this.logLowest = health;
                this.logPending = 3;
            }
        }
        if (this.logPending > 0) {
            this.logLowest = Math.min(this.logLowest, health);
            if (--this.logPending == 0) {
                this.writeBlockLog();
            }
        }
        this.lastOwnHurt = hurt;
        this.lastOwnHealth = health;
    }

    private void writeBlockLog() {
        float lost = Math.max(0.0F, this.logBefore - this.logLowest);
        String verdict = myau.combat.BlockVerdict.classify(lost, this.logRanges, 0.15F);
        Integer seen = this.logVerdicts.get(verdict);
        this.logVerdicts.put(verdict, seen == null ? 1 : seen + 1);
        if ("REDUCED".equals(verdict)) {
            this.logReduced++;
        } else if ("FULL".equals(verdict)) {
            this.logFull++;
        }
        if (this.logFile == null) {
            this.logFile = new java.io.File("./config/Atlas/", "ka2block-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        myau.util.AsyncLog.append(this.logFile, String.format("%s  %-7s lost %.2f (full %.2f/crit %.2f, blocked %.2f/crit %.2f or half %.2f/crit %.2f)  %s"
                        + "  release %s min %d stop-sprint %s | server blocked %d of %d %s",
                new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()), verdict, lost,
                this.logRanges[0][0], this.logRanges[1][0], this.logRanges[2][0], this.logRanges[3][0],
                this.logRanges[4][0], this.logRanges[5][0], this.logLine,
                this.blockRelease.getModeString(), this.blockMinTicks.getValue(), this.blockStopSprint.getValue(),
                this.logReduced, this.logReduced + this.logFull, this.logVerdicts));
    }

    private boolean inSwing(EntityLivingBase entity) {
        return AimGeometry.distance(this.eyesAtPre, box(entity)) <= this.swingReach();
    }

    /**
     * Legit: can the target hit us now? We were just hit, or it is within
     * 3.6, facing us (60 degrees) and swinging -- the old KillAura's LEGIT
     * rule. Always: yes.
     */
    private boolean threat(EntityLivingBase entity) {
        if (this.autoBlock.getValue() == 2) {
            return true;
        }
        if (this.legitThreat.getValue() == 0) {
            return this.lookThreat();
        }
        if (mc.thePlayer.hurtTime > 0) {
            return true;
        }
        if (mc.thePlayer.getDistanceToEntity(entity) > 3.6F || !entity.isSwingInProgress) {
            return false;
        }
        double dx = mc.thePlayer.posX - entity.posX;
        double dz = mc.thePlayer.posZ - entity.posZ;
        float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(MathHelper.wrapAngleTo180_float(toUs - entity.rotationYawHead)) < 60.0F;
    }

    /**
     * legit-threat Look: some enemy player within reach is looking at us --
     * the ray from its eyes along its look, 3 blocks long, lands on our box
     * grown by legit-tolerance, with no block in the way. Any enemy counts,
     * not only the target, and not filtered by fov or through-walls: those
     * are about whether we can hit it, and an enemy behind us is exactly the
     * one to block against.
     */
    private boolean lookThreat() {
        double reach = 3.0;
        double tolerance = this.legitTolerance.getValue();
        AxisAlignedBB ours = mc.thePlayer.getEntityBoundingBox().expand(tolerance, tolerance, tolerance);
        for (EntityPlayer enemy : mc.theWorld.playerEntities) {
            if (!this.isThreatCandidate(enemy) || mc.thePlayer.getDistanceSqToEntity(enemy) > 36.0) {
                continue;
            }
            Vec3 eyes = enemy.getPositionEyes(1.0F);
            if (ours.isVecInside(eyes)) {
                return true;
            }
            Vec3 look = enemy.getLook(1.0F);
            Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
            MovingObjectPosition mop = ours.calculateIntercept(eyes, end);
            if (mop != null && mc.theWorld.rayTraceBlocks(eyes, mop.hitVec, false, true, false) == null) {
                return true;
            }
        }
        return false;
    }

    private boolean isThreatCandidate(EntityPlayer enemy) {
        if (!(enemy instanceof EntityOtherPlayerMP) || enemy.isDead || enemy.deathTime > 0
                || enemy.worldObj != mc.theWorld) {
            return false;
        }
        return TargetFilter.accepts(enemy)
                && !TeamUtil.isFriend(enemy)
                && (!this.teams.getValue() || !TeamUtil.isSameTeam(enemy))
                && (!this.botCheck.getValue() || !TeamUtil.isBot(enemy));
    }

    // ------------------------------------------------------------- targets

    private boolean isValidTarget(EntityLivingBase entity) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.isDead
                || entity.deathTime > 0 || entity.worldObj != mc.theWorld || entity.getHealth() <= 0.0F) {
            return false;
        }
        if (entity == mc.getRenderViewEntity()) {
            return false;
        }
        if (RotationUtil.angleToEntity(entity) > this.fov.getValue()) {
            return false;
        }
        if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entity) != null) {
            return false;
        }
        if (entity instanceof EntityOtherPlayerMP) {
            EntityPlayer player = (EntityPlayer) entity;
            return this.players.getValue()
                    && TargetFilter.accepts(player)
                    && !TeamUtil.isFriend(player)
                    && (!this.teams.getValue() || !TeamUtil.isSameTeam(player))
                    && (!this.botCheck.getValue() || !TeamUtil.isBot(player));
        }
        if (entity instanceof EntityDragon || entity instanceof EntityWither
                || entity instanceof EntityMob || entity instanceof EntitySlime) {
            return this.mobs.getValue();
        }
        if (entity instanceof EntityIronGolem) {
            return this.mobs.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entity));
        }
        return entity instanceof EntityAnimal && this.animals.getValue();
    }

    /** The hitbox the server judges by: the bounding box grown by the collision border (0.1). */
    private static double[] box(EntityLivingBase entity) {
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(border, border, border);
        return new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
    }

    // ------------------------------------------------------------- movement

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() && this.aiming && this.moveFix.getValue() == 2
                && RotationState.isActived() && RotationState.getPriority() == 1.0F && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            if (mc.thePlayer.movementInput.moveForward < 0.8F && mc.thePlayer.isSprinting()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
