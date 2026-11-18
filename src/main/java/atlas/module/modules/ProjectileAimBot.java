package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.Render2DEvent;
import atlas.events.Render3DEvent;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.ModeProperty;
import atlas.sim.SimTargets;
import atlas.sim.TargetForecast;
import atlas.util.CombatTargeting;
import atlas.util.DodgeAim;
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

public class ProjectileAimBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] BODY = new String[]{"Head", "Body", "Feet"};

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

    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final ModeProperty gravityType = new ModeProperty("gravity-type", 1, new String[]{"None", "Projectile"});

    public final BooleanProperty predict = new BooleanProperty("predict", true, () -> this.gravityType.getValue() == 1);
    public final BooleanProperty latencyCompensation = new BooleanProperty("latency-compensation", true,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue());

    public final BooleanProperty autoShoot = new BooleanProperty("auto-shoot", true,
            () -> this.gravityType.getValue() == 1);
    public final IntProperty shootCharge = new IntProperty("shoot-charge", 20, 3, 20,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());

    public final BooleanProperty redraw = new BooleanProperty("redraw", false,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());

    public final ModeProperty earlyLetGo = new ModeProperty("early-let-go", 0, new String[]{"Cancel", "Finish"},
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue() && this.redraw.getValue());

    private int swapBackSlot = -1;
    private int swapOtherSlot = -1;
    private int swapWait;

    public final IntProperty chargeRandom = new IntProperty("charge-random", 2, 0, 5,
            () -> this.gravityType.getValue() == 1 && this.autoShoot.getValue());

    public final BooleanProperty dodgeAim = new BooleanProperty("dodge-aim", false,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue());
    public final BooleanProperty dodgeWait = new BooleanProperty("dodge-wait", false,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue() && this.autoShoot.getValue());

    public final IntProperty dodgeReaction = new IntProperty("dodge-reaction", 5, 0, 12,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue()
                    && (this.dodgeAim.getValue() || this.dodgeWait.getValue() && this.autoShoot.getValue()));

    public final BooleanProperty learnHabits = new BooleanProperty("learn-habits", true,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue());

    public final BooleanProperty dodgeHint = new BooleanProperty("dodge-hint", true,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue());

    public final PercentProperty dodgeCover = new PercentProperty("dodge-cover", 100, 50, 100,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue() && this.autoShoot.getValue()
                    && this.dodgeWait.getValue());

    public final IntProperty dodgeWaitTicks = new IntProperty("dodge-wait-ticks", 8, 1, 40,
            () -> this.gravityType.getValue() == 1 && this.predict.getValue() && this.autoShoot.getValue()
                    && this.dodgeWait.getValue());

    public final BooleanProperty allowLob = new BooleanProperty("allow-lob", true, () -> this.gravityType.getValue() == 1);
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 120.0F, 10.0F, 180.0F);

    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty highestBodyPoint = new ModeProperty("highest-body-point", 0, BODY,
            () -> this.gravityType.getValue() == 0);
    public final ModeProperty lowestBodyPoint = new ModeProperty("lowest-body-point", 2, BODY,
            () -> this.gravityType.getValue() == 0);
    public final BooleanProperty mark = new BooleanProperty("mark", true);

    public final BooleanProperty showPath = new BooleanProperty("show-path", true, () -> this.gravityType.getValue() == 1);

    public final BooleanProperty bowTrace = new BooleanProperty("bow-trace", true);

    private EntityPlayer target;

    private EntityPlayer lastAimed;

    private int extraCharge = -1;

    private int shownDistance = -1;
    private int shownTicks = -1;

    private java.io.File traceFile;
    private boolean wasDrawing;
    private int lastUseTicks;
    private boolean releasedByUs;
    private long releasedAt;
    private final java.util.Set<Integer> arrowsSeen = new java.util.HashSet<Integer>();

    private boolean holdAfterShot;

    private boolean autoRedrawPending;
    private boolean redrawDraw;
    private boolean finishing;

    private boolean ourRelease;
    private static final int FINISH_GRACE = 6;

    private String whereKind = "-";

    private String noShot = "";

    private String clearWhy = "";

    private final java.util.Map<Integer, atlas.util.TargetLead> leads = new java.util.HashMap<Integer, atlas.util.TargetLead>();

    private double arrowRttMs = -1.0;

    private int dodgeKeptFor = -1;
    private double dodgeKeptSide;
    private double dodgeKeptUp;

    private int dodgeHeldAt = -1;

    private atlas.sim.DodgeHabits habits;
    private Observation watching;

    private double escape = -1.0;

    private static final class Observation {
        EntityPlayer target;
        ProjectileSolver.Target carry;
        List<ProjectileSolver.Target> ways;
        int rtt;
        final List<double[]> seen = new ArrayList<double[]>();
    }

    private String dodgeNote = "";
    private String dodgeShown = "";

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
        this.dodgeHeldAt = -1;
        this.extraCharge = -1;
        this.dodgeKeptFor = -1;
        this.leads.clear();
        if (this.holdAfterShot) {
            this.holdAfterShot = false;
            if (mc.gameSettings != null) {
                atlas.util.KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            }
        }
        this.stopFinishing();
        this.swapBack();
        this.autoRedrawPending = false;
        this.path = Collections.emptyList();
        this.pathHits = false;
    }

    private static final class Shot {
        ProjectileSolver.Kind kind;

        double speedNow;
        double speedAim;
        boolean bow;
        int useTicks;
    }

    @EventTarget
    public void onTick(atlas.events.TickEvent event) {
        if (event.getType() == EventType.PRE && this.redrawDraw) {
            this.finishRedraw();
        }
        if (event.getType() == EventType.PRE && this.swapBackSlot >= 0) {
            if (this.swapWait > 0) {
                this.swapWait--;
            } else {
                this.swapBack();
            }
        }
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

    private void finishRedraw() {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (!this.isEnabled() || held == null || !(held.getItem() instanceof ItemBow) || !mc.thePlayer.isUsingItem()) {
            this.stopFinishing();
        }
    }

    @EventTarget
    public void onCancelUse(atlas.events.CancelUseEvent event) {
        if (!this.redrawDraw || this.ourRelease || !this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBow) || !mc.thePlayer.isUsingItem()) {
            this.stopFinishing();
            return;
        }
        int ticks = mc.thePlayer.getItemInUseDuration();
        int full = this.shootCharge.getValue() + Math.max(0, this.extraCharge);
        if (ticks < full && this.earlyLetGo.getValue() == 0 && this.swapBackSlot < 0) {
            event.setCancelled(true);
            int bowSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.clearItemInUse();
            this.swapBackSlot = bowSlot;
            this.swapOtherSlot = (bowSlot + 1) % 9;
            this.swapWait = 1;
            mc.thePlayer.inventory.currentItem = this.swapOtherSlot;
            this.stopFinishing();
            this.trace("CANCEL redraw let go at " + ticks + " ticks: slot " + (bowSlot + 1) + " -> "
                    + (this.swapOtherSlot + 1) + " and back, no arrow");
            return;
        }
        if (ticks < full + FINISH_GRACE) {
            event.setCancelled(true);
            if (!this.finishing) {
                this.finishing = true;
                this.trace("FINISH redraw let go at " + ticks + " ticks: held to full draw (" + full + ")");
            }
        } else {
            this.trace("FINISH no auto-shoot by " + ticks + " ticks: let go at full draw");
            this.stopFinishing();
        }
    }

    private void swapBack() {
        if (this.swapBackSlot >= 0 && mc.thePlayer != null
                && mc.thePlayer.inventory.currentItem == this.swapOtherSlot) {
            mc.thePlayer.inventory.currentItem = this.swapBackSlot;
        }
        this.swapBackSlot = -1;
        this.swapOtherSlot = -1;
    }

    private void stopFinishing() {
        this.finishing = false;
        this.redrawDraw = false;
    }

    @EventTarget
    public void onRightClick(atlas.events.RightClickMouseEvent event) {
        if (!this.holdAfterShot || !this.isEnabled() || mc.thePlayer == null || mc.gameSettings == null) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBow)) {
            return;
        }
        if (atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
            event.setCancelled(true);

            while (mc.gameSettings.keyBindUseItem.isPressed()) {
            }
        }
    }

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
        this.dodgeNote = "";
        this.dodgeShown = "";
        this.escape = -1.0;
        this.recordPositions();
        this.watch();
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
            if (!keyHeld) {
                this.startWatching();
            }
        }
        if (drawing != this.wasDrawing) {
            this.dodgeHeldAt = -1;
            this.extraCharge = -1;
        }
        boolean buttonDown = atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode());
        if (!drawing && !buttonDown) {
            this.autoRedrawPending = false;
        }
        if (drawing && !this.wasDrawing) {
            this.releasedByUs = false;

            this.redrawDraw = this.autoRedrawPending && buttonDown;
            this.autoRedrawPending = false;
        }
        this.wasDrawing = drawing;
        if (drawing) {
            this.lastUseTicks = mc.thePlayer.getItemInUseDuration();
        }
        Shot shot = this.shot();
        if (shot == null) {
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
                        atlas.management.RotationHelper.serverPitch(), this.dodgeNote + release));
            }
        } else {
            List<EntityPlayer> found = this.candidates();
            EntityPlayer chosen = found.isEmpty() ? null : found.get(0);
            want = chosen == null ? null : this.faceBody(chosen);
            this.target = want == null ? null : chosen;
        }
        if (want == null) {
            return;
        }

        float max = this.turnSpeed.getValue() * (0.9F + (float) Math.random() * 0.2F);
        float[] look = atlas.management.RotationHelper.limitTurn(event.getYaw(), event.getPitch(), want[0], want[1], max);

        atlas.management.RotationHelper.request(this, look[0], look[1], 2, this.moveMode(), false,
                atlas.management.RotationHelper.NO_LIMIT);
    }

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
                shot.speedNow = 3.0;
                shot.speedAim = 3.0;
            } else {
                shot.speedNow = ProjectileSolver.bowSpeed(shot.useTicks);

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

    private float[] aimExact(Shot shot) {
        double[] eye = this.eyeNextTick();
        List<EntityPlayer> found = this.candidates();
        StringBuilder why = new StringBuilder(found.isEmpty() ? "no target (" + this.leftOut + ")" : "");

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
            if (this.dodgeAim.getValue() && this.predict.getValue()) {
                ProjectileSolver.Aim proofed = this.dodgeProofed(eye, shot, where, candidate, aim);
                if (proofed != null) {
                    aim = proofed;
                }
            }
            ProjectileSolver.Flight flight = ProjectileSolver.fly(eye[0], eye[1], eye[2], aim.yaw, aim.pitch,
                    shot.speedAim, shot.kind, where, this.blocks(), ProjectileSolver.MAX_TICKS);
            this.target = candidate;
            this.lastAimed = candidate;
            this.path = flight.path;
            this.pathHits = flight.hits();
            this.shownDistance = (int) Math.round(mc.thePlayer.getDistanceToEntity(candidate));
            this.shownTicks = (int) Math.round(aim.ticks);
            this.escape = shot.bow && this.dodgeHint.getValue() && this.predict.getValue()
                    ? this.escapeChance(eye, shot, candidate, aim) : -1.0;
            return new float[]{aim.yaw, aim.pitch};
        }
        this.noShot = why.toString();
        if (bestEffort != null) {
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

    private static ProjectileSolver.Target along(final TargetForecast f, final double offset) {
        return ticks -> {
            double at = Math.max(0.0, Math.min(f.moves(), offset + ticks));
            int i = (int) Math.floor(at);
            int j = Math.min(f.moves(), i + 1);
            double k = at - i;
            double x = f.x[i] + (f.x[j] - f.x[i]) * k;
            double y = f.y[i] + (f.y[j] - f.y[i]) * k;
            double z = f.z[i] + (f.z[j] - f.z[i]) * k;
            return new double[]{x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3};
        };
    }

    private List<ProjectileSolver.Target> dodgeTargets(EntityPlayer t, int delay, double flightTicks) {
        double latency = this.latencyCompensation.getValue() ? latencyMs() / 50.0 : 0.0;

        int ticks = (int) Math.min(80.0, Math.ceil(latency) + delay + DodgeAim.flightTicks(flightTicks) + 2);
        SimTargets.Dodges dodges = SimTargets.dodges(t, latency + delay + this.reactionFor(t), ticks);
        if (dodges == null) {
            return null;
        }
        List<ProjectileSolver.Target> out = new ArrayList<ProjectileSolver.Target>();
        double offset = dodges.age + latency + delay;
        for (TargetForecast path : dodges.paths) {
            out.add(along(path, offset));
        }
        return out;
    }

    private ProjectileSolver.Aim dodgeProofed(double[] eye, Shot shot, ProjectileSolver.Target where,
                                              EntityPlayer candidate, ProjectileSolver.Aim aim) {
        try {
            List<ProjectileSolver.Target> dodges = this.dodgeTargets(candidate, 1, aim.ticks);
            if (dodges == null) {
                this.dodgeNote = " | dodge: ways out not known";
                return null;
            }
            boolean same = this.dodgeKeptFor == candidate.getEntityId();
            double[] weights = this.learnHabits.getValue() ? this.wayWeights(candidate, dodges.size()) : null;
            DodgeAim.Result result = DodgeAim.best(eye[0], eye[1], eye[2], aim, shot.speedAim, shot.kind, where, dodges,
                    same ? this.dodgeKeptSide : 0.0, same ? this.dodgeKeptUp : 0.0, weights);
            if (result.aim == aim) {
                this.dodgeKeptFor = candidate.getEntityId();
                this.dodgeKeptSide = 0.0;
                this.dodgeKeptUp = 0.0;
                this.dodgeShown = result.covered + "/" + result.total;
                this.dodgeNote = String.format(" | dodge: middle covers %d/%d", result.covered, result.total);
                return null;
            }
            if (!this.flight(eye, result.aim, shot.speedAim, shot.kind, where).isEmpty()) {
                int plain = DodgeAim.covered(eye[0], eye[1], eye[2], aim.yaw, aim.pitch, shot.speedAim,
                        DodgeAim.safe(shot.kind, mc.thePlayer.getDistanceToEntity(candidate)), dodges,
                        DodgeAim.flightTicks(aim.ticks));
                this.dodgeShown = plain + "/" + dodges.size();
                this.dodgeNote = String.format(" | dodge: the better look is stopped by a block, middle covers %d/%d",
                        plain, dodges.size());
                return null;
            }
            this.dodgeShown = result.covered + "/" + result.total;
            this.dodgeKeptFor = candidate.getEntityId();
            this.dodgeKeptSide = result.side;
            this.dodgeKeptUp = result.up;
            this.dodgeNote = String.format(" | dodge: %.2f aside %.2f up covers %d/%d", result.side, result.up,
                    result.covered, result.total);
            return result.aim;
        } catch (RuntimeException e) {
            this.dodgeNote = " | dodge: failed (" + e + ")";
            return null;
        }
    }

    private String dodgeHold(Shot shot, double[] eye, float yaw, float pitch, ProjectileSolver.Flight flight) {
        if (!this.dodgeWait.getValue() || !this.predict.getValue()) {
            return null;
        }
        try {
            if (this.dodgeHeldAt > shot.useTicks) {
                this.dodgeHeldAt = -1;
            }
            int waited = this.dodgeHeldAt < 0 ? 0 : shot.useTicks - this.dodgeHeldAt;
            if (waited >= this.dodgeWaitTicks.getValue()) {
                this.dodgeNote = " | dodge: waited " + waited + ", lets go";
                return null;
            }
            List<ProjectileSolver.Target> dodges = this.dodgeTargets(this.target, 0, flight.hitTick);
            if (dodges == null) {
                return null;
            }
            double distance = mc.thePlayer.getDistanceToEntity(this.target);
            int covered = DodgeAim.covered(eye[0], eye[1], eye[2], yaw, pitch, shot.speedNow,
                    DodgeAim.safe(shot.kind, distance), dodges, DodgeAim.flightTicks(flight.hitTick));
            int need = (int) Math.ceil(dodges.size() * this.dodgeCover.getValue() / 100.0);
            this.dodgeShown = covered + "/" + dodges.size();
            if (covered >= need) {
                return null;
            }
            if (this.dodgeHeldAt < 0) {
                this.dodgeHeldAt = shot.useTicks;
            }
            return String.format(" | hold: it can still get away (%d/%d covered, need %d), %d more ticks at most",
                    covered, dodges.size(), need, this.dodgeWaitTicks.getValue() - waited);
        } catch (RuntimeException e) {
            return null;
        }
    }

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

    private String maybeRelease(Shot shot) {
        if (this.extraCharge < 0 || shot.useTicks <= 1) {
            this.extraCharge = this.chargeRandom.getValue() <= 0 ? 0
                    : (int) (Math.random() * (this.chargeRandom.getValue() + 1));

            this.dodgeHeldAt = -1;
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
            ProjectileSolver.Target line = this.where(this.target, 0, false);
            ProjectileSolver.Flight onLine = ProjectileSolver.fly(eye[0], eye[1], eye[2], yaw, pitch, shot.speedNow,
                    shot.kind, line, this.blocks(), ProjectileSolver.MAX_TICKS);
            if (onLine.hits()) {
                flight = onLine;
            }
        }
        if (flight.hits()) {
            String hold = this.dodgeHold(shot, eye, yaw, pitch, flight);
            if (hold != null) {
                return hold;
            }

            this.ourRelease = true;
            try {
                mc.playerController.onStoppedUsingItem(mc.thePlayer);
            } finally {
                this.ourRelease = false;
            }
            this.startWatching();
            this.extraCharge = -1;
            this.dodgeHeldAt = -1;
            this.releasedByUs = true;
            this.releasedAt = System.currentTimeMillis();
            this.trace(String.format("END draw %d ticks: auto-shoot let go | look %.1f/%.1f", shot.useTicks,
                    MathHelper.wrapAngleTo180_float(yaw), pitch));

            this.wasDrawing = false;

            this.stopFinishing();
            this.autoRedrawPending = this.redraw.getValue()
                    && atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode());
            if (!this.redraw.getValue()) {
                this.holdAfterShot = true;
                atlas.util.KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            }
            return " | RELEASE (server look hits at tick " + flight.hitTick + ")";
        }
        return String.format(" | hold: server look %s at tick %d", flight.blocked ? "blocked" : "misses",
                flight.path.size() - 1);
    }

    private double reactionFor(EntityPlayer t) {
        if (!this.learnHabits.getValue() || t == null) {
            return this.dodgeReaction.getValue();
        }
        double local = this.habits().reaction(t.getUniqueID().toString(), this.dodgeReaction.getValue());

        return Math.max(0.0, Math.min(12.0, atlas.management.HabitsCloud.shared().reaction(cloudKey(t), local)));
    }

    private double[] wayWeights(EntityPlayer t, int ways) {
        double[] all = this.weightsFor(t);
        double[] out = new double[ways];
        for (int i = 0; i < ways; i++) {
            out[i] = i < all.length - 1 ? all[i] : 1.0 / (all.length);
        }
        return out;
    }

    private double[] weightsFor(EntityPlayer t) {
        double[] shared = atlas.management.HabitsCloud.shared().weights(cloudKey(t));
        return shared != null ? shared : this.habits().weights(t.getUniqueID().toString());
    }

    private static String cloudKey(EntityPlayer t) {
        return atlas.sim.SharedHabits.key(t.getName(), atlas.sim.SharedHabits.cracked(t.getUniqueID()));
    }

    private atlas.sim.DodgeHabits habits() {
        if (this.habits == null) {
            this.habits = new atlas.sim.DodgeHabits();
            try {
                java.io.File file = habitsFile();
                if (file.isFile()) {
                    this.habits.load(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
        return this.habits;
    }

    private static java.io.File habitsFile() {
        return new java.io.File("./config/Atlas/dodge-habits.txt");
    }

    private double escapeChance(double[] eye, Shot shot, EntityPlayer t, ProjectileSolver.Aim aim) {
        try {
            List<ProjectileSolver.Target> dodges = this.dodgeTargets(t, 1, aim.ticks);
            if (dodges == null) {
                return -1.0;
            }
            double[] weights = this.weightsFor(t);
            boolean[] hit = DodgeAim.hitsEach(eye[0], eye[1], eye[2], aim.yaw, aim.pitch, shot.speedAim,
                    DodgeAim.safe(shot.kind, mc.thePlayer.getDistanceToEntity(t)), dodges,
                    DodgeAim.flightTicks(aim.ticks));
            double away = this.pathHits ? 0.0 : weights[weights.length - 1];
            for (int i = 0; i < hit.length && i < weights.length - 1; i++) {
                if (!hit[i]) {
                    away += weights[i];
                }
            }
            return Math.max(0.0, Math.min(1.0, away));
        } catch (RuntimeException e) {
            return -1.0;
        }
    }

    private void startWatching() {
        EntityPlayer t = this.lastAimed;
        if (!this.learnHabits.getValue() || !this.predict.getValue() || t == null || t.isDead) {
            return;
        }
        try {
            Observation o = new Observation();
            o.target = t;
            o.carry = this.where(t, 0);
            double latency = this.latencyCompensation.getValue() ? latencyMs() / 50.0 : 0.0;
            o.rtt = (int) Math.round(latency);

            int ticks = (int) Math.min(80.0, Math.ceil(latency) + atlas.sim.DodgeHabits.COMPARE + 16);
            SimTargets.Dodges dodges = SimTargets.dodges(t, latency, ticks);
            if (o.carry == null || dodges == null) {
                return;
            }
            o.ways = new ArrayList<ProjectileSolver.Target>();
            double offset = dodges.age + latency;
            for (TargetForecast path : dodges.paths) {
                o.ways.add(along(path, offset));
            }
            this.watching = o;
        } catch (RuntimeException ignored) {
            this.watching = null;
        }
    }

    private void watch() {
        Observation o = this.watching;
        if (o == null) {
            return;
        }
        if (o.target.isDead || !mc.theWorld.playerEntities.contains(o.target)) {
            this.watching = null;
            return;
        }
        o.seen.add(new double[]{o.target.serverPosX / 32.0, o.target.serverPosZ / 32.0});
        int length = atlas.sim.DodgeHabits.COMPARE + 12;
        if (o.seen.size() < o.rtt + length) {
            return;
        }
        this.watching = null;
        try {
            double[][] actual = new double[length][];
            double[][] carry = new double[length][];
            double[][][] ways = new double[o.ways.size()][length][];
            for (int tau = 0; tau < length; tau++) {
                int k = tau + o.rtt;
                actual[tau] = k < o.seen.size() ? o.seen.get(k) : null;
                carry[tau] = centre(o.carry.box(tau));
                for (int w = 0; w < ways.length; w++) {
                    ways[w][tau] = centre(o.ways.get(w).box(tau));
                }
            }
            int reaction = atlas.sim.DodgeHabits.reactionTick(actual, carry);
            int way = reaction < 0 ? -1 : atlas.sim.DodgeHabits.classify(actual, carry, ways, reaction);
            double sample = atlas.sim.DodgeHabits.reactionSample(reaction, way);
            atlas.sim.DodgeHabits.Habit habit = this.habits().record(o.target.getUniqueID().toString(),
                    o.target.getName(), sample, way);
            java.nio.file.Files.write(habitsFile().toPath(),
                    this.habits().save().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            atlas.management.HabitsCloud.tell(o.target.getName(),
                    atlas.sim.SharedHabits.cracked(o.target.getUniqueID()), sample, way);
            this.trace(String.format("HABIT %s: %s, %s | now: reaction %.1f (%d), usually %s, %d shots watched",
                    o.target.getName(), reaction < 0 ? "did not move" : reaction == 0 ? "already moving"
                            : "moved " + reaction + " ticks in" + (Double.isNaN(sample) ? " (not a reaction)" : ""),
                    way < 0 ? "carried on" : atlas.sim.TargetDodges.WAYS[way].name, habit.reaction, habit.reactions,
                    habit.usual(), habit.shots()));
        } catch (Exception ignored) {
        }
    }

    private static double[] centre(double[] box) {
        return box == null ? null : new double[]{(box[0] + box[3]) / 2.0, (box[2] + box[5]) / 2.0};
    }

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

    private double[] eyeNow() {
        return new double[]{mc.thePlayer.posX, mc.thePlayer.posY + eyeHeight(), mc.thePlayer.posZ};
    }

    private double[] eyeNextTick() {
        double y = mc.thePlayer.onGround ? mc.thePlayer.posY : mc.thePlayer.posY + mc.thePlayer.motionY;
        return new double[]{mc.thePlayer.posX + mc.thePlayer.motionX, y + eyeHeight(),
                mc.thePlayer.posZ + mc.thePlayer.motionZ};
    }

    private static double eyeHeight() {
        return mc.thePlayer.isSneaking() ? EYE_SNEAKING : EYE;
    }

    private ProjectileSolver.Target where(EntityPlayer t, int delay) {
        return this.where(t, delay, true);
    }

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

        atlas.util.TargetLead steps = this.leads.get(t.getEntityId());

        boolean stopAndGo = steps != null && steps.stillNow();
        if (forecast != null && forecast.moves() > 0 && forecast.simulated && !stopAndGo) {
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

        final atlas.util.TargetLead history = this.leads.get(t.getEntityId());
        if (history != null && history.size() >= 2) {
            this.whereKind = String.format("lead%s lag %.1f", history.stillNow() ? " still"
                    : history.typicalBurst() > 0 ? " steps " + history.typicalBurst() + "/" + history.currentRun() : "",
                    latency);
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

    private double latencyMs() {
        if (this.arrowRttMs >= 0.0) {
            return Math.min(1000.0, this.arrowRttMs);
        }
        int own = atlas.util.Ping.own();
        return own > 0 ? Math.min(1000, own) : 0.0;
    }

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

    private static double groundBelow(EntityPlayer t) {
        double x = t.serverPosX / 32.0;
        double y = t.serverPosY / 32.0;
        double z = t.serverPosZ / 32.0;
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(new Vec3(x, y + 0.1, z), new Vec3(x, y - 30.0, z),
                false, true, false);
        return hit == null || hit.hitVec == null ? Double.NaN : hit.hitVec.yCoord;
    }

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
            if (this.learnHabits.getValue()) {
                atlas.management.HabitsCloud.ask(player.getName(), atlas.sim.SharedHabits.cracked(player.getUniqueID()));
            }

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
        double away = this.escape;
        if (this.dodgeHint.getValue() && away >= 0.0) {
            this.hintBox = RenderUtil.projectToScreen(shown, new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor());
            this.hintAway = away;
            this.hintSeen = System.currentTimeMillis();
        } else {
            this.hintBox = null;
        }
    }

    private javax.vecmath.Vector4d hintBox;
    private javax.vecmath.Vector4d hintShownBox;
    private double hintAway = -1.0;
    private long hintSeen;
    private float hintAlpha;
    private double hintEased = -1.0;
    private long hintFrame;

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        long now = System.currentTimeMillis();
        float dt = this.hintFrame == 0L ? 0.016F : Math.min(0.1F, (now - this.hintFrame) / 1000.0F);
        this.hintFrame = now;
        boolean live = this.isEnabled() && this.dodgeHint.getValue() && this.hintBox != null && now - this.hintSeen < 150L;
        if (live) {
            this.hintShownBox = this.hintBox;
            this.hintEased = this.hintEased < 0.0 ? this.hintAway
                    : this.hintEased + (this.hintAway - this.hintEased) * (1.0 - Math.exp(-dt * 12.0));
        }
        this.hintAlpha += ((live ? 1.0F : 0.0F) - this.hintAlpha) * (float) (1.0 - Math.exp(-dt * (live ? 14.0 : 9.0)));
        if (this.hintAlpha < 0.03F || this.hintShownBox == null) {
            if (!live) {
                this.hintEased = -1.0;
                this.hintX = Float.NaN;
                this.hintMoved = 0L;
            }
            return;
        }
        this.drawHintCard(this.hintShownBox, this.hintEased, this.hintAlpha);
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(8, Math.min(255, Math.round(a * 255.0F))) << 24) | (rgb & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
        int g = (int) Math.round(((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
        int bl = (int) Math.round((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int hintAccent(double away) {
        if (away <= 0.33) {
            return mix(0x4ADE80, 0xFACC15, (away - 0.17) / 0.16);
        }
        return mix(0xFACC15, 0xF87171, (away - 0.52) / 0.16);
    }

    private float hintX = Float.NaN;
    private float hintY = Float.NaN;
    private long hintMoved;

    private void drawHintCard(javax.vecmath.Vector4d box, double away, float a) {
        double hit = 1.0 - away;
        String verdict = away < 0.25 ? "NO ESCAPE" : away < 0.6 ? "MAY ESCAPE" : "CAN ESCAPE";
        String caption = "HIT CHANCE";
        int accent = hintAccent(away);
        String pct = Math.round(hit * 100.0) + "%";
        atlas.util.font.impl.FontRenderer big = atlas.util.font.FontManager.productSans18;
        atlas.util.font.impl.FontRenderer small = atlas.util.font.FontManager.productSans12;
        boolean ttf = big != null && small != null;
        net.minecraft.client.gui.FontRenderer vanilla = mc.fontRendererObj;

        double verdictW = ttf ? big.getStringWidth(verdict) : vanilla.getStringWidth(verdict);
        double pctW = ttf ? big.getStringWidth("100%") : vanilla.getStringWidth("100%");
        double captionW = ttf ? small.getStringWidth(caption) : 0.0;
        double bigH = ttf ? big.getHeight() : vanilla.FONT_HEIGHT;
        double smallH = ttf ? small.getHeight() : 0.0;
        float padL = 11.0F;
        float padR = 6.0F;
        float w = (float) Math.ceil(padL + Math.max(verdictW, captionW) + 8.0 + pctW + padR);
        float h = (float) Math.ceil(4.0 + smallH + 2.0 + bigH + 3.5 + 1.5 + 4.0);
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
        float wantX = (float) box.z + 6.0F;
        if (wantX + w > sr.getScaledWidth() - 2) {
            wantX = (float) box.x - 6.0F - w;
        }
        float wantY = (float) (box.y + (box.w - box.y) * 0.18);
        wantY = Math.max(2.0F, Math.min(sr.getScaledHeight() - h - 2.0F, wantY));
        long now = System.currentTimeMillis();
        float dt = this.hintMoved == 0L ? 0.016F : Math.min(0.1F, (now - this.hintMoved) / 1000.0F);
        this.hintMoved = now;
        if (Float.isNaN(this.hintX) || a < 0.1F && Math.abs(wantX - this.hintX) + Math.abs(wantY - this.hintY) > 80.0F) {
            this.hintX = wantX;
            this.hintY = wantY;
        } else {
            float k = (float) (1.0 - Math.exp(-dt * 22.0));
            this.hintX += (wantX - this.hintX) * k;
            this.hintY += (wantY - this.hintY) * k;
        }

        float scale = sr.getScaleFactor();
        float x = Math.round((this.hintX + (1.0F - a) * 6.0F) * scale) / scale;
        float y = Math.round(this.hintY * scale) / scale;
        GlStateManager.pushMatrix();

        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        for (int i = 3; i >= 1; i--) {
            RenderUtil.drawRoundedRect(x - i, y - i + 1.0F, w + i * 2.0F, h + i * 2.0F, 5.0F + i, alpha(0x000000, a * 0.07F));
        }
        RenderUtil.drawRoundedRect(x - 0.5F, y - 0.5F, w + 1.0F, h + 1.0F, 5.5F, alpha(accent, a * 0.10F));
        RenderUtil.drawRoundedRect(x, y, w, h, 5.0F, alpha(0x0E0F14, a * 0.84F));
        RenderUtil.drawRoundedRectOutline(x, y, w, h, 5.0F, 1.0F, alpha(0xFFFFFF, a * 0.07F), true, true, true, true);

        RenderUtil.drawRoundedRect(x + 4.0F, y + 4.5F, 2.0F, h - 9.0F, 1.0F, alpha(accent, a));
        float tx = x + padL;
        float captionY = y + 4.0F;
        float lineY = (float) (captionY + smallH + 2.0);
        if (ttf) {
            small.drawString(caption, tx, captionY, alpha(0x9CA3AF, a), false);
            big.drawString(verdict, tx, lineY, alpha(0xF5F5F7, a), false);
            big.drawString(pct, x + w - padR - big.getStringWidth(pct), lineY, alpha(accent, a), false);
        } else {
            vanilla.drawStringWithShadow(verdict, tx, lineY, alpha(0xF5F5F7, a));
            vanilla.drawStringWithShadow(pct, x + w - padR - vanilla.getStringWidth(pct), lineY, alpha(accent, a));
        }

        float bx = tx;
        float bw = x + w - padR - bx;
        float by = y + h - 5.5F;
        RenderUtil.drawRoundedRect(bx, by, bw, 1.5F, 0.75F, alpha(0xFFFFFF, a * 0.10F));
        RenderUtil.drawRoundedRect(bx, by, (float) Math.max(1.5, bw * Math.max(0.0, Math.min(1.0, hit))), 1.5F, 0.75F,
                alpha(accent, a));
        float pixel = 1.0F / scale;

        GlStateManager.disableTexture2D();
        for (float mark : new float[]{0.40F, 0.75F}) {
            float mx = Math.round((bx + bw * mark) * scale) / scale;
            RenderUtil.drawRect(mx, by - 0.75F, mx + pixel, by + 2.25F, alpha(0xFFFFFF, a * 0.22F));
        }
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    @Override
    public String[] getSuffix() {
        if (this.shownDistance >= 0) {
            return new String[]{this.gravityType.getModeString(), this.shownDistance + "m " + this.shownTicks + "t"
                    + (this.dodgeShown.isEmpty() ? "" : " " + this.dodgeShown)};
        }
        return new String[]{this.gravityType.getModeString()};
    }
}
