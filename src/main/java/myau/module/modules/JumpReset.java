package myau.module.modules;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.KnockbackEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ActionLedger;
import myau.util.KeyBindUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;

/**
 * Jumps the moment knockback lands, the way a player times a jump reset
 * (rewritten 2026-10-11; the STANDARD and POLAR modes are gone).
 *
 * Why it was rewritten: POLAR also jumped when nobody had hit the player
 * (whenever the nearest enemy was predicted to back off), rolled its chance
 * every tick so 70% meant always, and pressed the jump key from the packet
 * handler -- and since a key press is not a change of motion, FlagDetector
 * never named it: the vertical setbacks right after hits read "unknown".
 *
 * Now: one decision per knockback (KnockbackEvent, upward, on the ground),
 * with the chance rolled once; the jump itself is made in the very next
 * movement input (MoveInputEvent, after the keys are read and before the
 * jump is processed) by setting movementInput.jump -- the jump key is never
 * touched. That tick is the only one a reset can happen on: a tick later
 * the knockback has lifted the player off the ground. A run of perfect
 * resets is broken on purpose (max-streak), as a person's would be.
 * Each jump is noted in the ActionLedger as "move", so a correction right
 * after it is put down to JumpReset.
 */
public class JumpReset extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance = new PercentProperty("chance", 60);
    /* 2026-10-11: of the last 10 knockbacks taken on the ground, at most this
       share is reset -- a server counting perfect resets sees a person's rate. */
    public final PercentProperty maxRate = new PercentProperty("max-rate", 50);
    public final IntProperty maxStreak = new IntProperty("max-streak", 3, 0, 20);
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", true);
    /* 2026-10-13 (after OpenB4's JumpReset.helps): a sprint jump adds 0.2
       along the way the body faces. Facing the hit that cancels part of
       the knockback; facing away it adds to it. Jump only when it takes at
       least min-reduction off the horizontal knockback. */
    public final BooleanProperty onlyIfHelps = new BooleanProperty("only-if-helps", true);
    public final PercentProperty minReduction = new PercentProperty("min-reduction", 3, this.onlyIfHelps::getValue);
    public final BooleanProperty movingForward = new BooleanProperty("moving-forward", true);
    public final BooleanProperty mouseDown = new BooleanProperty("mouse-down", false);
    public final BooleanProperty aimingOnPlayer = new BooleanProperty("aiming-on-player", true);
    public final FloatProperty range = new FloatProperty("range", 5.0F, 0.0F, 10.0F, this.aimingOnPlayer::getValue);
    public final IntProperty fov = new IntProperty("fov", 180, 0, 360, this.aimingOnPlayer::getValue);
    public final IntProperty minInterval = new IntProperty("min-interval", 0, 0, 20);
    public final ModeProperty pauseWhen = new ModeProperty("pause-when", 2, new String[]{"Flag", "NoCombat", "Both", "None"});
    public final IntProperty flagTicks = new IntProperty("flag-ticks", 10, 0, 40, () -> this.pauseWhen.getValue() == 0 || this.pauseWhen.getValue() == 2);
    public final IntProperty combatTicks = new IntProperty("combat-ticks", 100, 0, 400, () -> this.pauseWhen.getValue() == 1 || this.pauseWhen.getValue() == 2);

    /** A reset decided on and not yet made: the next movement input makes it. */
    private boolean pending;
    private int pendingAge;
    private int streak;
    /** The last 10 ground knockbacks: true where a reset was made. */
    private final java.util.ArrayDeque<Boolean> recent = new java.util.ArrayDeque<>();
    /** Within this run of resets, where it will be broken. */
    private int streakLimit;
    private int ticksSinceFlag = 9999;
    private int ticksSinceAttack = 9999;
    private int ticksSinceReset = 9999;

    public JumpReset() {
        super("JumpReset", false, false, "Jumps when hurt to reduce incoming knockback.");
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || event.isCancelled()) {
            return;
        }
        if (event.getY() <= 0.0D || !mc.thePlayer.onGround) {
            return;
        }
        if (!this.conditionsMet() || !this.allowed() || !this.helps(event.getX(), event.getZ())) {
            this.streak = 0;
            this.remember(false);
            return;
        }
        /* The chance once per hit, a long run of resets broken, and never
           more than max-rate of the recent knockbacks. */
        if (RandomUtil.nextInt(1, 100) > this.chance.getValue() || this.streakBroken() || this.rateFull()) {
            this.streak = 0;
            this.remember(false);
            return;
        }
        this.remember(true);
        this.pending = true;
        this.pendingAge = 0;
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || !this.pending || mc.thePlayer == null) {
            return;
        }
        this.pending = false;
        if (!mc.thePlayer.onGround || this.blocked()) {
            this.streak = 0;
            if (!this.recent.isEmpty()) {
                this.recent.removeLast();
                this.recent.addLast(false);
            }
            return;
        }
        mc.thePlayer.movementInput.jump = true;
        this.ticksSinceReset = 0;
        this.streak++;
        ActionLedger.note(this.getName(), "move");
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.ticksSinceFlag = 0;
            this.pending = false;
            this.streak = 0;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled()) {
            this.ticksSinceAttack = 0;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.ticksSinceFlag < 10000) {
            this.ticksSinceFlag++;
        }
        if (this.ticksSinceAttack < 10000) {
            this.ticksSinceAttack++;
        }
        if (this.ticksSinceReset < 10000) {
            this.ticksSinceReset++;
        }
        /* Never carried past the tick it was meant for. */
        if (this.pending && ++this.pendingAge > 1) {
            this.pending = false;
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.resetState();
    }

    /** The player's own conditions: sprinting, walking at someone, clicking. */
    private boolean conditionsMet() {
        if (this.requireSprint.getValue() && !mc.thePlayer.isSprinting()) {
            return false;
        }
        if (this.movingForward.getValue() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())) {
            return false;
        }
        if (this.mouseDown.getValue() && !KeyBindUtil.isKeyDown(-100)) {
            return false;
        }
        return !this.aimingOnPlayer.getValue() || this.nearestTarget(this.range.getValue()) != null;
    }

    /**
     * Whether the sprint jump's 0.2 push, along the yaw the jump will use
     * (the server-side look while a module holds one, as the jump mixin
     * does), cuts the horizontal knockback by at least min-reduction.
     * Knockback is in blocks per tick, as setVelocity takes it.
     */
    private boolean helps(double kbX, double kbZ) {
        if (!this.onlyIfHelps.getValue()) {
            return true;
        }
        double kb = Math.sqrt(kbX * kbX + kbZ * kbZ);
        if (kb < 1.0E-3) {
            return false;
        }
        float yaw = myau.management.RotationState.isActived()
                ? myau.management.RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        double rad = Math.toRadians(yaw);
        double x = kbX - Math.sin(rad) * 0.2;
        double z = kbZ + Math.cos(rad) * 0.2;
        double after = Math.sqrt(x * x + z * z);
        return (kb - after) / kb * 100.0 >= this.minReduction.getValue();
    }

    /** Places and states a jump would be wrong or pointless in. */
    private boolean blocked() {
        if (mc.thePlayer.isPotionActive(Potion.jump) || mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder() || mc.thePlayer.capabilities.isFlying) {
            return true;
        }
        if (mc.currentScreen instanceof GuiInventory) {
            return true;
        }
        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            return true;
        }
        BlockPos at = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        return mc.theWorld.getBlockState(at).getBlock().getMaterial() == Material.web;
    }

    private boolean allowed() {
        if (this.blocked()) {
            return false;
        }
        if (this.ticksSinceReset < this.minInterval.getValue()) {
            return false;
        }
        switch (this.pauseWhen.getValue()) {
            case 0:
                return this.ticksSinceFlag >= this.flagTicks.getValue();
            case 1:
                return this.ticksSinceAttack <= this.combatTicks.getValue();
            case 2:
                return this.ticksSinceFlag >= this.flagTicks.getValue()
                        && this.ticksSinceAttack <= this.combatTicks.getValue();
            default:
                return true;
        }
    }

    private void remember(boolean reset) {
        this.recent.addLast(reset);
        while (this.recent.size() > 10) {
            this.recent.removeFirst();
        }
    }

    /** True when one more reset would put the last 10 over max-rate. */
    private boolean rateFull() {
        int made = 0;
        for (Boolean reset : this.recent) {
            if (reset) {
                made++;
            }
        }
        int window = Math.min(10, this.recent.size() + 1);
        return (made + 1) * 100 > this.maxRate.getValue() * window && window >= 4;
    }

    /** True when this reset would make the run too long: it is skipped. */
    private boolean streakBroken() {
        int max = this.maxStreak.getValue();
        if (max <= 0) {
            return false;
        }
        if (this.streak == 0) {
            /* Each run ends somewhere between half the limit and the limit. */
            this.streakLimit = RandomUtil.nextInt(Math.max(1, (max + 1) / 2), max);
        }
        return this.streak >= this.streakLimit;
    }

    private EntityPlayer nearestTarget(double maxRange) {
        List<EntityPlayer> inRange = mc.theWorld.loadedEntityList.stream()
                .filter(entity -> entity instanceof EntityPlayer)
                .map(entity -> (EntityPlayer) entity)
                .filter(entity -> this.isValidTarget(entity, maxRange))
                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                .collect(Collectors.toList());
        if (inRange.isEmpty()) {
            return null;
        }
        EntityPlayer best = inRange.get(0);
        if (RotationUtil.angleToEntity(best) > this.fov.getValue() / 2.0F) {
            return null;
        }
        return best;
    }

    private boolean isValidTarget(EntityPlayer entity, double maxRange) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.deathTime > 0) {
            return false;
        }
        if (RotationUtil.distanceToEntity(entity) > maxRange) {
            return false;
        }
        return !TeamUtil.isFriend(entity) && !TeamUtil.isSameTeam(entity) && !TeamUtil.isBot(entity);
    }

    private void resetState() {
        this.pending = false;
        this.pendingAge = 0;
        this.streak = 0;
        this.recent.clear();
        this.ticksSinceFlag = 9999;
        this.ticksSinceAttack = 9999;
        this.ticksSinceReset = 9999;
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.chance.getValue() + "%"};
    }
}
