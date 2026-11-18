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
    public final ModeProperty mode = new ModeProperty("Mode", 1, new String[]{"PACKET", "LEGIT", "SILENT", "SOFT", "CRAB", "ZEST"});

    private final java.util.function.BooleanSupplier notCrab = () -> this.mode.getValue() != CRAB
            && this.mode.getValue() != ZEST;
    private final BooleanProperty onlyWhileSprinting = new BooleanProperty("Only While Sprinting", true, notCrab);
    private final BooleanProperty onlyWhileMoving = new BooleanProperty("Only While Moving", true, notCrab);

    private final BooleanProperty resetOnCrit = new BooleanProperty("Reset On Crit", false, notCrab);

    private final IntProperty minGap = new IntProperty("min-gap", 8, 0, 20, notCrab);
    private int lastResetTick = -1000;

    private final PercentProperty chance = new PercentProperty("chance", 90, notCrab);
    private final IntProperty releaseDelay = new IntProperty("release-delay", 0, 0, 5,
            () -> this.mode.getValue() == 1 || this.mode.getValue() == 3);
    private final IntProperty releaseTicks = new IntProperty("release-ticks", 1, 1, 5,
            () -> this.mode.getValue() == 1 || this.mode.getValue() == 3);

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

    private Object crabAttackerOf;

    private int crabHeld = -1;

    static final int ZEST = 5;
    private final java.util.function.BooleanSupplier zest = () -> this.mode.getValue() == ZEST;

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

    private void crabStop() {
        if (this.crabPlan.stop() == CrabSprintPlan.RELEASE || this.crabHeld >= 0) {
            crabRelease();
        }
        this.crabLastAttackerTime = Integer.MIN_VALUE;
        this.crabAttackerOf = null;
    }

    private void crabRelease() {
        if (this.crabHeld >= 0 && mc.thePlayer != null && mc.gameSettings != null) {
            crabAct(this.crabHeld, false);
        }
        this.crabHeld = -1;
    }

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

    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.lastTarget = null;
        this.zestTarget = null;
    }
}
