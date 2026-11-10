package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.AttackEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;

public class SprintReset extends Module {

    /* LEGIT (the default since 2026-09-25) lets go of forward for one tick:
       one STOP, then one START the tick after, exactly a w-tap. PACKET and
       SILENT send STOP and START in the same tick, two sprint changes in one
       tick, which no keyboard produces and which bad-packet checks count. */
    public final ModeProperty mode = new ModeProperty("Mode", 1, new String[]{"PACKET", "LEGIT", "SILENT", "SOFT", "CRAB", "ZEST"});
    /** Every mode but CRAB uses the settings below; CRAB has its own (crab-...). */
    private final java.util.function.BooleanSupplier notCrab = () -> this.mode.getValue() != CRAB
            && this.mode.getValue() != ZEST;
    private final BooleanProperty onlyWhileSprinting = new BooleanProperty("Only While Sprinting", true, notCrab);
    private final BooleanProperty onlyWhileMoving = new BooleanProperty("Only While Moving", true, notCrab);
    /* Sends four made-up positions (a packet critical) with the reset. That
       is a movement no player makes, on every hit; off by default since
       2026-09-25, and not something to turn on on Pika. */
    private final BooleanProperty resetOnCrit = new BooleanProperty("Reset On Crit", false, notCrab);
    /* Ticks between resets. The server takes sprint away only on a hit that
       does damage, and a player can be damaged once every ten ticks; at 12-16
       clicks a second, resetting on every click let go of forward almost every
       tick. One reset per damage window is all the knockback needs. */
    private final IntProperty minGap = new IntProperty("min-gap", 8, 0, 20, notCrab);
    private int lastResetTick = -1000;

    /* The rest after Vape's WTap (studied 2026-09-25), rewritten here.
       - chance: not every hit. A reset on every single one is a rhythm.
       - release-delay / release-ticks: when forward is let go after the
         hit, and for how long, in ticks (Vape: 50 ms each, one tick).
       - select-hits: only on a hit that can do damage when it lands -- the
         target's hurt time within the measured round trip (HitTimer). The
         server takes sprint away only on a damaging hit, so a reset spent
         on one that cannot land is a slowdown for nothing.
       LEGIT only; PACKET and SILENT keep their old behaviour. */
    private final PercentProperty chance = new PercentProperty("chance", 90, notCrab);
    private final IntProperty releaseDelay = new IntProperty("release-delay", 0, 0, 5,
            () -> this.mode.getValue() == 1 || this.mode.getValue() == 3);
    private final IntProperty releaseTicks = new IntProperty("release-ticks", 1, 1, 5,
            () -> this.mode.getValue() == 1 || this.mode.getValue() == 3);
    /* 2026-10-12, from a player's suggestion:
       - SOFT: like LEGIT, but instead of letting go of forward the player
         sneaks for those ticks (since 2026-10-20; it used to ease forward to
         0.5, which Grim's Simulation flags): sprint drops and comes back, the
         player never stops.
       - randomize: each reset draws its delay (setting -1..+1) and its length
         (setting +0..+1) instead of using the same numbers every time.
       - wait-for-damage: after the hit, nothing happens until this player is
         hurt (their hurt time rises); the reset starts then. Given up after
         20 ticks without being hit. */
    private final BooleanProperty randomize = new BooleanProperty("randomize", true,
            () -> this.mode.getValue() == 1 || this.mode.getValue() == 3);
    private final BooleanProperty waitForDamage = new BooleanProperty("wait-for-damage", false, notCrab);
    private boolean waitingForDamage;
    private int waitTicks;
    private int lastHurtTime;
    private final BooleanProperty selectHits = new BooleanProperty("select-hits", true, notCrab);
    private int releaseWait = 0;
    private final BooleanProperty smartReset = new BooleanProperty("Smart Reset", false, notCrab);
    private final BooleanProperty fastReset = new BooleanProperty("Fast Reset", false, notCrab);
    /* ---- CRAB (2026-11-09, owner: "port Crab's Sprint Reset, as close as you can"): rebuilt from what Crab
       client's Sprint Reset does (its DLL, read with Ghidra); see CrabSprintPlan for the timing. A hit is seen the
       way Crab sees it: the player's last-attacker time moving, which vanilla sets on every hit it makes (the
       player's or an aura's), never on a swing at the air. The action is held by setting the key itself each tick,
       and given back as the key really is, so a key the player let go of meanwhile is not pressed again. */
    static final int CRAB = 4;
    private final java.util.function.BooleanSupplier crab = () -> this.mode.getValue() == CRAB;
    private final ModeProperty crabAction = new ModeProperty("crab-action", 0,
            new String[]{"WTap", "Sneak", "NoStop"}, crab);
    private final IntProperty crabDelay = new IntProperty("crab-delay-ms", 200, 0, 400, crab);
    private final IntProperty crabStop = new IntProperty("crab-stop-duration-ms", 100, 50, 300, crab);
    private final BooleanProperty crabRandomize = new BooleanProperty("crab-randomize", true, crab);
    private final BooleanProperty crabDamaged = new BooleanProperty("crab-damaged", false, crab);
    private final BooleanProperty crabWeapon = new BooleanProperty("crab-holding-weapon", false, crab);
    private final CrabSprintPlan crabPlan = new CrabSprintPlan();
    private final java.util.Random crabRandom = new java.util.Random();
    private int crabLastAttackerTime = Integer.MIN_VALUE;
    /** The player entity crabLastAttackerTime was read from: a new one (respawn, world change, reconnect) starts over. */
    private Object crabAttackerOf;
    /** The action CRAB is holding, so a change of crab-action mid-reset gives back the right key. */
    private int crabHeld = -1;
    /* ---- ZEST (2026-11-10, owner: zest tap): see ZestTapPlan. A hit counts when it lands (the target's hurt time
       rising after our attack on it), not on every click, or a burst would finish a sequence in a second. */
    static final int ZEST = 5;
    private final java.util.function.BooleanSupplier zest = () -> this.mode.getValue() == ZEST;
    /* Default HitSelect (owner's video: "let the other player hit you first and immediately hit him back"). */
    private final ModeProperty zestStart = new ModeProperty("zest-start", 2,
            new String[]{"Any", "FirstHit", "HitSelect", "Trade"}, zest);
    private final BooleanProperty zestStrafe = new BooleanProperty("zest-strafe", true, zest);
    private final BooleanProperty zestStrafeRandom = new BooleanProperty("zest-strafe-random", true,
            () -> this.mode.getValue() == ZEST && this.zestStrafe.getValue());
    private final java.util.function.BooleanSupplier zestTaps = () -> this.mode.getValue() == ZEST
            && this.zestStrafe.getValue() && this.zestStrafeRandom.getValue();
    private final IntProperty zestStrafeTaps = new IntProperty("zest-strafe-taps", ZestTapPlan.DEFAULT_TAPS, 1,
            ZestTapPlan.MAX_TAPS, zestTaps);
    private final IntProperty zestTapMin = new IntProperty("zest-strafe-tap-min-ticks", ZestTapPlan.TAP_MIN_TICKS, 1, 6,
            zestTaps);
    private final IntProperty zestTapMax = new IntProperty("zest-strafe-tap-max-ticks", ZestTapPlan.TAP_MAX_TICKS, 1, 6,
            zestTaps);
    private final IntProperty zestGapMin = new IntProperty("zest-strafe-gap-min-ticks", ZestTapPlan.GAP_MIN_TICKS, 0, 10,
            zestTaps);
    private final IntProperty zestGapMax = new IntProperty("zest-strafe-gap-max-ticks", ZestTapPlan.GAP_MAX_TICKS, 0, 10,
            zestTaps);
    private final IntProperty zestDelayMax = new IntProperty("zest-strafe-delay-max-ticks", ZestTapPlan.STRAFE_DELAY_MAX,
            0, 10, zestTaps);
    private final PercentProperty zestSwap = new PercentProperty("zest-strafe-swap-chance", ZestTapPlan.SWAP_PERCENT,
            zestTaps);
    private final BooleanProperty zestCheckOpponent = new BooleanProperty("zest-check-opponent", true, zest);
    private final ModeProperty zestReleaseAt = new ModeProperty("zest-release-at", 0, new String[]{"Hit", "Peak"}, zest);
    private final IntProperty zestReleaseMs = new IntProperty("zest-release-ms", 150, 50, 1000, zest);
    private final BooleanProperty zestClicks = new BooleanProperty("zest-clicks", false, zest);
    private final ZestClickPlan zestClickPlan = new ZestClickPlan(new java.util.Random());
    /** A click ZEST pressed this tick: its LeftClickMouseEvent is let through. */
    private boolean zestClicking;
    private boolean zestKeyPending;
    private boolean zestOwnsClicks;
    private long zestAttackHeldMs = Long.MIN_VALUE / 2;
    private final ZestTapPlan zestPlan = new ZestTapPlan(new java.util.Random());
    private ZestTapPlan.Keys zestKeys;
    private EntityLivingBase zestTarget;
    private long zestAttackMs;
    private int zestTargetHurt;
    private double zestLastDy;
    private int zestLastOwnHurt;
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean attacked = false;
    private boolean needsReset = false;
    private int ticksSinceAttack = 0;
    private Entity lastTarget = null;
    /* 2026-09-24. LEGIT and SILENT did not work.

       LEGIT set sprint off at the attack, but with the sprint key held (the
       Sprint module holds it) EntityPlayerSP.onLivingUpdate turns it straight
       back on in the same tick, and sprint packets only go out once a tick by
       comparing states -- so no STOP ever reached the server. Now it lets go
       of forward for one tick, which is what drops sprint for a real player.

       SILENT sent STOP from the attack event, which fires BEFORE the attack
       packet (vanilla's order, see MixinPlayerControllerMP). So the hit it
       meant to help landed without sprint knockback, and the START it sent a
       tick later was undone by the next attack's STOP. Now nothing is sent
       at the attack; STOP and START go together on the next tick, after the
       hit, so the next one lands sprinting. */
    private int forwardReleaseTicks = 0;

    public SprintReset() {
        super("SprintReset", false, false);
    }

    @Override
    public void onEnabled() {
        lastResetTick = -1000;
        attacked = false;
        needsReset = false;
        ticksSinceAttack = 0;
        lastTarget = null;
    }

    @Override
    public void onDisabled() {
        crabStop();
        zestStop();
        waitingForDamage = false;
        waitTicks = 0;
        releaseWait = 0;
        forwardReleaseTicks = 0;
        attacked = false;
        needsReset = false;
        ticksSinceAttack = 0;
        lastTarget = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || this.mode.getValue() == CRAB) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        Entity target = event.getTarget();
        if (target == null) return;
        if (!(target instanceof EntityLivingBase)) return;
        if (this.mode.getValue() == ZEST) {
            if (target != this.zestTarget) {
                this.zestTargetHurt = ((EntityLivingBase) target).hurtTime;
                this.zestLastDy = 0.0;
            }
            this.zestTarget = (EntityLivingBase) target;
            this.zestAttackMs = System.currentTimeMillis();
            return;
        }

        if (onlyWhileSprinting.getValue() && !mc.thePlayer.isSprinting()) return;
        if (onlyWhileMoving.getValue() && !isMoving()) return;
        /* One w-tap at a time: a new hit does not restart one in progress. */
        if (releaseWait > 0 || forwardReleaseTicks > 0 || waitingForDamage) return;
        if (selectHits.getValue() && ((EntityLivingBase) target).hurtTime > atlas.management.HitTimer.ticks() + 1) return;
        int now = mc.thePlayer.ticksExisted;
        if (now >= lastResetTick && now - lastResetTick < minGap.getValue()) return;
        if (Math.random() * 100.0 >= chance.getValue()) return;
        lastResetTick = now;

        if (smartReset.getValue()) {
            if (((EntityLivingBase) target).getHealth() <= 0) return;
            if (mc.thePlayer.getDistanceToEntity(target) > 6.0f) return;
        }
        if (fastReset.getValue() && lastTarget != null && lastTarget != target) {
            handlePacketReset();
        }

        lastTarget = target;
        attacked = true;

        if (waitForDamage.getValue()) {
            /* Held until this player is hit back (onUpdate). */
            waitingForDamage = true;
            waitTicks = 0;
            lastHurtTime = mc.thePlayer.hurtTime;
            return;
        }
        startReset();
    }

    private void startReset() {
        switch (mode.getValue()) {
            case 0:
                handlePacketReset();
                break;
            case 1:
                handleLegitReset();
                break;
            case 2:
                handleSilentReset();
                break;
            case 3:
                handleLegitReset();
                break;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || this.mode.getValue() == CRAB || this.mode.getValue() == ZEST) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        // Once per tick: this used to count PRE and POST, two a tick.
        if (event.getType() != atlas.event.types.EventType.PRE) return;
        if (waitingForDamage) {
            int hurt = mc.thePlayer.hurtTime;
            if (hurt > lastHurtTime) {
                waitingForDamage = false;
                attacked = true;
                ticksSinceAttack = 0;
                startReset();
            } else if (++waitTicks > 20) {
                waitingForDamage = false;
                attacked = false;
            }
            lastHurtTime = hurt;
            if (waitingForDamage) {
                return;
            }
        }
        if (attacked) {
            ticksSinceAttack++;
        }
        if ((mode.getValue() == 1 || mode.getValue() == 3) && needsReset && ticksSinceAttack >= 1) {
            needsReset = false;
            attacked = false;
            ticksSinceAttack = 0;
        }
        if (mode.getValue() == 2 && needsReset && ticksSinceAttack >= 1) {
            if (mc.thePlayer.isSprinting()) {
                mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                );
                mc.thePlayer.sendQueue.addToSendQueue(
                        new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                );
            }
            needsReset = false;
            attacked = false;
            ticksSinceAttack = 0;
        }
        if (ticksSinceAttack > 5) {
            attacked = false;
            needsReset = false;
            ticksSinceAttack = 0;
        }
    }

    private void handlePacketReset() {
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
        );
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
        );
        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
        attacked = false;
        ticksSinceAttack = 0;
    }

    private void handleLegitReset() {
        int delay = releaseDelay.getValue();
        int length = releaseTicks.getValue();
        if (randomize.getValue()) {
            delay = Math.max(0, Math.min(5, delay + (int) Math.floor(Math.random() * 3.0) - 1));
            length = Math.max(1, Math.min(5, length + (Math.random() < 0.4 ? 1 : 0)));
        }
        releaseWait = delay;
        forwardReleaseTicks = length;
        needsReset = true;
        ticksSinceAttack = 0;

        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
    }

    private void handleSilentReset() {
        needsReset = true;
        ticksSinceAttack = 0;

        if (resetOnCrit.getValue() && canCrit()) {
            performCritReset();
        }
    }

    private void performCritReset() {
        if (mc.thePlayer.onGround && !mc.thePlayer.isInWater() && !mc.thePlayer.isOnLadder()) {
            double x = mc.thePlayer.posX;
            double y = mc.thePlayer.posY;
            double z = mc.thePlayer.posZ;

            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.0625, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.1E-5, z, false)
            );
            mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false)
            );
        }
    }

    /** CRAB: once a tick, before vanilla reads the movement keys. */
    @EventTarget
    public void onPreMoveInput(atlas.events.PreMoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.gameSettings == null) {
            return;
        }
        if (this.mode.getValue() == ZEST) {
            crabStop();
            zestTick();
            return;
        }
        zestStop();
        if (this.mode.getValue() != CRAB) {
            crabStop();
            return;
        }
        if (this.crabAttackerOf != mc.thePlayer) {
            this.crabAttackerOf = mc.thePlayer;
            this.crabLastAttackerTime = Integer.MIN_VALUE;
        }
        int lastAttacker = mc.thePlayer.getLastAttackerTime();
        /* Changed (on the same player entity), and to this tick or the last. */
        boolean hit = this.crabLastAttackerTime != Integer.MIN_VALUE && lastAttacker != this.crabLastAttackerTime
                && lastAttacker >= mc.thePlayer.ticksExisted - 1;
        this.crabLastAttackerTime = lastAttacker;
        if (mc.thePlayer.isDead) {
            crabStop();
            return;
        }
        boolean weaponOk = !this.crabWeapon.getValue() || ItemUtil.isHoldingSword() || ItemUtil.isHoldingAxe();
        int step = this.crabPlan.tick(System.nanoTime() / 1.0E9, hit, mc.thePlayer.hurtTime,
                this.crabDelay.getValue() / 50.0F, this.crabStop.getValue() / 50.0F, this.crabRandomize.getValue(),
                this.crabDamaged.getValue(), weaponOk, this.crabRandom);
        if (step == CrabSprintPlan.APPLY) {
            if (this.crabHeld >= 0 && this.crabHeld != this.crabAction.getValue()) {
                crabAct(this.crabHeld, false);
            }
            this.crabHeld = this.crabAction.getValue();
            crabAct(this.crabHeld, true);
        } else if (step == CrabSprintPlan.RELEASE) {
            crabRelease();
        }
    }

    /** ZEST: what the keys do this tick, from the hits seen since the last. Applied in onMoveInput. */
    private void zestTick() {
        if (mc.thePlayer.isDead || mc.theWorld == null) {
            zestStop();
            return;
        }
        long now = System.currentTimeMillis();
        int ownHurt = mc.thePlayer.hurtTime;
        boolean hurt = ownHurt > this.zestLastOwnHurt;
        this.zestLastOwnHurt = ownHurt;
        EntityLivingBase target = this.zestTarget;
        if (target != null && (target.isDead || target.worldObj != mc.theWorld)) {
            target = this.zestTarget = null;
        }
        boolean landed = false;
        boolean peak = false;
        boolean ok = false;
        if (target != null) {
            int targetHurt = target.hurtTime;
            /* Ours if we attacked it within a round trip and a bit: the hurt shows when the server's answer is back. */
            landed = targetHurt > this.zestTargetHurt && now - this.zestAttackMs <= 600L + atlas.util.Ping.own();
            this.zestTargetHurt = targetHurt;
            double dy = target.posY - target.lastTickPosY;
            peak = this.zestLastDy > 0.0 && dy <= 0.0;
            this.zestLastDy = dy;
            ok = !this.zestCheckOpponent.getValue() || zestOpponentOk(target);
        }
        this.zestPlan.releaseAtHit(this.zestReleaseAt.getValue() == 0);
        this.zestPlan.targetOpen(target != null && atlas.management.HitTimer.canHurt(target));
        this.zestPlan.strafeShape(this.zestStrafeTaps.getValue(), this.zestTapMin.getValue(), this.zestTapMax.getValue(),
                this.zestGapMin.getValue(), this.zestGapMax.getValue(), this.zestDelayMax.getValue(),
                this.zestSwap.getValue());
        this.zestKeys = this.zestPlan.tick(now, landed, hurt, ok, peak, this.zestStart.getValue(),
                this.zestStrafe.getValue(), this.zestStrafeRandom.getValue(), this.zestReleaseMs.getValue());
    }

    /** Facing this player, not crouching, not blocking, not walking away (knockback aside). */
    private static boolean zestOpponentOk(EntityLivingBase target) {
        double dx = mc.thePlayer.posX - target.posX;
        double dz = mc.thePlayer.posZ - target.posZ;
        float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        if (Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(toUs - target.rotationYawHead)) > 60.0F) {
            return false;
        }
        if (target.isSneaking()) {
            return false;
        }
        if (target instanceof net.minecraft.entity.player.EntityPlayer
                && ((net.minecraft.entity.player.EntityPlayer) target).isBlocking()) {
            return false;
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (target.hurtTime == 0 && length > 0.01) {
            double toward = ((target.posX - target.lastTickPosX) * dx + (target.posZ - target.lastTickPosZ) * dz) / length;
            return toward >= -0.04;
        }
        return true;
    }

    private void zestStop() {
        this.zestPlan.reset();
        this.zestKeys = null;
        this.zestClickPlan.reset();
        this.zestOwnsClicks = false;
    }

    /**
     * zest-clicks, at the start of the tick (as AutoClicker): while a combo runs and the attack button is held (or was
     * within 300 ms), the clicks are ZEST's. None while the target cannot be hurt, a burst the moment it can (HitTimer:
     * an attack sent now lands after its hurt has run out). Not with an aura on: the aura makes its own attacks.
     */
    @EventTarget
    public void onZestTick(atlas.events.TickEvent event) {
        if (event.getType() != atlas.event.types.EventType.PRE) {
            return;
        }
        this.zestClicking = false;
        int key = mc.gameSettings == null ? 0 : mc.gameSettings.keyBindAttack.getKeyCode();
        if (this.zestKeyPending) {
            this.zestKeyPending = false;
            atlas.util.KeyBindUtil.updateKeyState(key);
        }
        if (!this.isEnabled() || this.mode.getValue() != ZEST || !this.zestClicks.getValue() || mc.thePlayer == null
                || mc.theWorld == null) {
            this.zestClickPlan.reset();
            this.zestOwnsClicks = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (mc.currentScreen == null && atlas.util.KeyBindUtil.isKeyDown(key)) {
            this.zestAttackHeldMs = now;
        }
        boolean wanting = mc.currentScreen == null && now - this.zestAttackHeldMs <= 300L
                && !atlas.util.Auras.anyEnabled();
        EntityLivingBase target = this.zestTarget;
        boolean combo = this.zestPlan.inCombo() && target != null;
        this.zestOwnsClicks = ZestClickPlan.owns(combo, wanting);
        boolean open = target != null && atlas.management.HitTimer.canHurt(target);
        if (this.zestClickPlan.tick(now, combo, wanting, open) > 0 && !mc.thePlayer.isUsingItem()) {
            this.zestClicking = true;
            this.zestKeyPending = true;
            atlas.util.KeyBindUtil.setKeyBindState(key, false);
            atlas.util.KeyBindUtil.pressKeyOnce(key);
        }
    }

    /** zest-clicks: while ZEST owns the clicks, only its own go out (AutoClicker's and the hand's are dropped). */
    @EventTarget(atlas.event.types.Priority.HIGHEST)
    public void onZestClick(atlas.events.LeftClickMouseEvent event) {
        if (this.zestClicking) {
            this.zestClicking = false;
            return;
        }
        if (this.isEnabled() && this.mode.getValue() == ZEST && this.zestClicks.getValue() && this.zestOwnsClicks
                && !event.isCancelled()) {
            event.setCancelled(true);
        }
    }

    /** Lets go of whatever CRAB holds. */
    private void crabStop() {
        if (this.crabPlan.stop() == CrabSprintPlan.RELEASE || this.crabHeld >= 0) {
            crabRelease();
        }
        this.crabLastAttackerTime = Integer.MIN_VALUE;
        this.crabAttackerOf = null;   // no old world kept alive by it
    }

    private void crabRelease() {
        if (this.crabHeld >= 0 && mc.thePlayer != null && mc.gameSettings != null) {
            crabAct(this.crabHeld, false);
        }
        this.crabHeld = -1;
    }

    /**
     * Crab's three actions. on: WTap lets go of forward, Sneak holds crouch, NoStop drops sprint and lets go of the
     * sprint key (the player keeps walking). off: the key goes back to what it really is; NoStop sprints again.
     */
    private static void crabAct(int action, boolean on) {
        net.minecraft.client.settings.KeyBinding key = action == 1 ? mc.gameSettings.keyBindSneak
                : action == 2 ? mc.gameSettings.keyBindSprint : mc.gameSettings.keyBindForward;
        if (action == 2) {
            mc.thePlayer.setSprinting(!on);
        }
        boolean physical = mc.currentScreen == null && atlas.util.KeyBindUtil.isKeyDown(key.getKeyCode());
        ((atlas.mixin.IAccessorKeyBinding) key).setPressed(on ? action == 1 : physical);
    }

    @EventTarget
    public void onMoveInput(atlas.events.MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.mode.getValue() == CRAB) {
            return;
        }
        if (this.mode.getValue() == ZEST) {
            ZestTapPlan.Keys keys = this.zestKeys;
            net.minecraft.util.MovementInput input = mc.thePlayer.movementInput;
            if (keys == null || mc.currentScreen != null) {
                return;
            }
            boolean walking = input.moveForward > 0.0F;
            if (keys.release && walking) {
                input.moveForward = 0.0F;
            }
            /* Only when walking in and not strafing by hand; vanilla: A is +1, D is -1, a crouch scales by 0.3. */
            if (keys.strafe != 0 && input.moveStrafe == 0.0F && walking && !keys.release) {
                input.moveStrafe = (keys.strafe < 0 ? 1.0F : -1.0F) * (input.sneak ? 0.3F : 1.0F);
            }
            return;
        }
        if (releaseWait > 0) {
            releaseWait--;
            return;
        }
        if (forwardReleaseTicks > 0) {
            if (mode.getValue() == 3) {
                /* SOFT: a sneak tap, never standing still. Until 2026-10-20 it eased forward to 0.5, an input
                   no keyboard makes: Grim's Simulation tries the key combinations and could not match that tick
                   (offsets .0294, .0161, .0088... each 0.546 of the last -- one bad tick decaying with ground
                   friction, test server 18:31). Sneaking is a real input (forward x 0.3, below the 0.8 sprinting
                   needs), so sprint drops the vanilla way and the move is one the server can predict. */
                net.minecraft.util.MovementInput input = mc.thePlayer.movementInput;
                if (input.moveForward > 0.0F && !input.sneak) {
                    input.sneak = true;
                    input.moveForward *= 0.3F;
                    input.moveStrafe *= 0.3F;
                }
            } else {
                mc.thePlayer.movementInput.moveForward = 0.0F;
            }
            forwardReleaseTicks--;
        }
    }

    private boolean canCrit() {
        return mc.thePlayer.onGround
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.blindness)
                && mc.thePlayer.ridingEntity == null;
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0 || mc.thePlayer.moveStrafing != 0;
    }

    /* 2026-10-31 (audit): a world unload leaves its entities alive (isDead stays false), and one kept here held the
       whole old world in memory -- for good while this module was off. Dropped when a world loads. */
    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.lastTarget = null;
        this.zestTarget = null;
    }
}
