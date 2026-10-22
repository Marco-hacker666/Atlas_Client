package atlas.module.modules;

import com.google.common.base.CaseFormat;
import atlas.Atlas;
import atlas.event.EventManager;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.*;
import atlas.mixin.IAccessorEntity;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.ChatUtil;
import atlas.util.KeyBindUtil;
import atlas.util.MoveUtil;
import atlas.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovingObjectPosition;

public class Velocity extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private int chanceCounter = 0;
    private boolean pendingExplosion = false;
    private boolean allowNext = true;
    private boolean jumpFlag = false;

    private int rotatoTickCounter = 0;
    private double knockbackX = 0;
    private double knockbackZ = 0;
    private float[] targetRotation = null;
    private int reduceTick = -1;
    private boolean pressed = false;
    private boolean hasReceivedVelocity = false;
    private int ticksSinceVelocity = -1;

    private boolean ShouldJump = false;

    private int slapReduceTicks = 0;
    private int slapAnInt = 0;
    private boolean slot = false;
    private boolean attack = false;
    private boolean swing = false;
    private boolean block = false;
    private boolean inventory = false;
    private boolean dig = false;

    private boolean intaveReduced = false;
    private int grimPending = 0;
    private boolean matrixReduced = false;
    private int polarHurtTime = 8;
    private int polarHurtCount = 0;
    private boolean polarPending = false;
    private double delayMotionX = 0;
    private double delayMotionY = 0;
    private double delayMotionZ = 0;
    private int delayTickCounter = 0;
    private boolean delayActive = false;
    private int delayChanceCounter = 0;
    // Appended splits state (10-17). Existing 0-9 fields untouched.
    private boolean tickPending = false;
    private int tickCounter = 0;
    private int tickChanceCounter = 0;
    private boolean zipHolding = false;
    private int zipTicks = 0;
    private double zipMotionX = 0;
    private double zipMotionY = 0;
    private double zipMotionZ = 0;
    private boolean matrixFullReduced = false;
    private int xzChanceCounter = 0;
    private boolean oldGrimHolding = false;
    private boolean oldGrimAttacked = false;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Vanilla", "Jump", "Hypixel", "Slap_Attack", "Intave", "GrimAC", "Matrix", "PolarJump", "Legit", "Delay", "Tick", "Zip", "Karhu", "MatrixReverse", "MatrixFull", "Intave14", "XZSwitch", "OldGrim", "Vulcan", "BlocksMC", "GrimC03", "IntaveTimer"});

    public final PercentProperty chance = new PercentProperty("chance", 100, () -> mode.getValue() <= 1);
    public final PercentProperty horizontal = new PercentProperty("horizontal", 0, () -> mode.getValue() <= 1);
    public final PercentProperty vertical = new PercentProperty("vertical", 100, () -> mode.getValue() <= 1);
    public final PercentProperty explosionHorizontal = new PercentProperty("explosions-horizontal", 100, () -> mode.getValue() <= 1);
    public final PercentProperty explosionVertical = new PercentProperty("explosions-vertical", 100, () -> mode.getValue() <= 1);

    public final BooleanProperty reduce = new BooleanProperty("reduce", true, () -> mode.getValue() == 2);
    public final IntProperty attackTimes = new IntProperty("attack-times", 1, 1, 5, () -> mode.getValue() == 2 && reduce.getValue());
    private final BooleanProperty onlySprinting = new BooleanProperty("only-sprinting", true, () -> mode.getValue() == 2 && reduce.getValue());
    private final BooleanProperty reduceWhenCanAttack = new BooleanProperty("reduce-when-can-attack", true, () -> mode.getValue() == 2 && reduce.getValue());
    public final BooleanProperty hypixelJump = new BooleanProperty("jump", true, () -> mode.getValue() == 2);
    public final BooleanProperty rotate = new BooleanProperty("rotate", false, () -> mode.getValue() == 2);
    public final IntProperty rotateTick = new IntProperty("rotate-ticks", 3, 1, 12, () -> mode.getValue() == 2 && rotate.getValue());

    public final BooleanProperty slapReduce = new BooleanProperty("slap-reduce", true, () -> mode.getValue() == 3);
    public final BooleanProperty tickExactEnable = new BooleanProperty("tickExact", true, () -> mode.getValue() == 3);
    public final IntProperty tick500 = new IntProperty("500", 3, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick1000 = new IntProperty("1000", 4, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick2000 = new IntProperty("2000", 4, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick3000 = new IntProperty("3000", 5, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick4000 = new IntProperty("4000", 6, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick5000 = new IntProperty("5000", 6, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick6000 = new IntProperty("6000", 7, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick7000 = new IntProperty("7000", 7, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick8000 = new IntProperty("8000", 8, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick9000 = new IntProperty("9000", 8, 0, 20, () -> mode.getValue() == 3);
    public final IntProperty tick10000 = new IntProperty("10000", 9, 0, 20, () -> mode.getValue() == 3);

    public final BooleanProperty fakeCheck = new BooleanProperty("fake-check", true);
    public final BooleanProperty debugLog = new BooleanProperty("debug-log", false);

    // Ported from S+ IntaveVelocity.java: attack-time XZ scale plus optional reset jump.
    public final FloatProperty intaveXZ = new FloatProperty("intave-xz", 0.6F, 0.0F, 1.0F, () -> mode.getValue() == 4);
    public final FloatProperty intaveSprintXZ = new FloatProperty("intave-sprint-xz", 0.6F, 0.0F, 1.0F, () -> mode.getValue() == 4);
    public final PercentProperty intaveChance = new PercentProperty("intave-chance", 100, () -> mode.getValue() == 4);
    public final BooleanProperty intaveJump = new BooleanProperty("intave-jump", false, () -> mode.getValue() == 4);
    public final PercentProperty intaveJumpChance = new PercentProperty("intave-jump-chance", 80, () -> mode.getValue() == 4 && intaveJump.getValue());
    // Ported from S+ GrimACVelocity.java: sprint-restart plus burst attack reduces.
    public final IntProperty grimAttacks = new IntProperty("grim-attacks", 4, 1, 10, () -> mode.getValue() == 5);
    public final IntProperty grimTimes = new IntProperty("grim-times", 1, 1, 5, () -> mode.getValue() == 5);
    public final BooleanProperty grimOnlyMoving = new BooleanProperty("grim-only-moving", false, () -> mode.getValue() == 5);
    // Ported from S+ MatrixSimpleVelocity.java + MatrixVelocity.java: packet scale plus tiered attack reduce.
    public final FloatProperty matrixScale = new FloatProperty("matrix-scale", 0.36F, 0.0F, 1.0F, () -> mode.getValue() == 6);
    public final FloatProperty matrixGroundScale = new FloatProperty("matrix-ground-scale", 0.9F, 0.0F, 1.0F, () -> mode.getValue() == 6);
    // Ported from S+ PolarJumpVelocity.java: jump at a randomised hurt time.
    public final BooleanProperty polarForceChange = new BooleanProperty("polar-force-change", true, () -> mode.getValue() == 7);
    public final IntProperty polarHurtTarget = new IntProperty("polar-hurt-count", 5, 1, 10, () -> mode.getValue() == 7 && polarForceChange.getValue());
    // Ported from S+ LegitVelocity.java: chance-based reset jump with liquid guard.
    public final PercentProperty legitChance = new PercentProperty("legit-chance", 80, () -> mode.getValue() == 8);
    public final BooleanProperty legitJumpInInv = new BooleanProperty("legit-jump-in-inv", false, () -> mode.getValue() == 8);
    public final BooleanProperty legitIgnoreLiquid = new BooleanProperty("legit-ignore-liquid", true, () -> mode.getValue() == 8);
    // Ported from MiauMinus NewVelocity.java Delay mode: hold the packet, apply scaled motion late.
    public final IntProperty delayTicksProp = new IntProperty("delay-ticks", 3, 1, 20, () -> mode.getValue() == 9);
    public final PercentProperty delayChanceProp = new PercentProperty("delay-chance", 100, () -> mode.getValue() == 9);
    public final FloatProperty delayHorizontalProp = new FloatProperty("delay-horizontal", 0.0F, -1.0F, 1.0F, () -> mode.getValue() == 9);
    public final FloatProperty delayVerticalProp = new FloatProperty("delay-vertical", 0.0F, -1.0F, 1.0F, () -> mode.getValue() == 9);
    // Adapted from S+ TickVelocity.java: delayed post-hurt scale (scheduler replaced by tick counter).
    public final PercentProperty tickHorizontalProp = new PercentProperty("tick-horizontal", 0, () -> mode.getValue() == 10);
    public final PercentProperty tickVerticalProp = new PercentProperty("tick-vertical", 100, () -> mode.getValue() == 10);
    public final IntProperty tickDelayProp = new IntProperty("tick-delay", 2, 1, 10, () -> mode.getValue() == 10);
    public final PercentProperty tickChanceProp = new PercentProperty("tick-chance", 100, () -> mode.getValue() == 10);
    // Adapted from S+ ZipVelocity.java, simplified experimental: hold S12, release on attack/timeout. S32 buffering omitted.
    public final IntProperty zipDelayProp = new IntProperty("zip-delay", 20, 4, 200, () -> mode.getValue() == 11);
    public final BooleanProperty zipStopOnAttack = new BooleanProperty("zip-stop-on-attack", true, () -> mode.getValue() == 11);
    public final PercentProperty zipHorizontalProp = new PercentProperty("zip-horizontal", 0, () -> mode.getValue() == 11);
    public final PercentProperty zipVerticalProp = new PercentProperty("zip-vertical", 100, () -> mode.getValue() == 11);
    // Adapted from S+ KarhuVelocity.java + Miau KarhuVelocity.java, experimental: hurt-windowed damp only, bounding-box part omitted.
    public final IntProperty karhuStartProp = new IntProperty("karhu-start", 10, 1, 10, () -> mode.getValue() == 12);
    public final IntProperty karhuStopProp = new IntProperty("karhu-stop", 0, 0, 9, () -> mode.getValue() == 12);
    public final FloatProperty karhuFactorProp = new FloatProperty("karhu-factor", 0.6F, 0.0F, 1.0F, () -> mode.getValue() == 12);
    // Adapted from S+ MatrixReverseVelocity.java: negative XZ scale.
    public final FloatProperty matrixReverseXZProp = new FloatProperty("matrix-reverse-xz", -0.3F, -1.0F, 1.0F, () -> mode.getValue() == 13);
    public final PercentProperty matrixReverseChanceProp = new PercentProperty("matrix-reverse-chance", 100, () -> mode.getValue() == 13);
    // Adapted from S+ MatrixFullVelocity.java: pre scale plus post damp.
    public final FloatProperty matrixFullXZProp = new FloatProperty("matrix-full-xz", 0.4F, 0.0F, 1.0F, () -> mode.getValue() == 14);
    public final FloatProperty matrixFullGroundProp = new FloatProperty("matrix-full-ground", 0.8F, 0.0F, 1.0F, () -> mode.getValue() == 14);
    public final FloatProperty matrixFullYProp = new FloatProperty("matrix-full-y", 0.6F, 0.0F, 1.0F, () -> mode.getValue() == 14);
    // Adapted from S+ Intave14Velocity.java + Miau Intave14Velocity.java phased reduces, simplified: hurt-gated packet scale.
    public final IntProperty intave14HurtProp = new IntProperty("intave14-hurt", 9, 1, 10, () -> mode.getValue() == 15);
    public final FloatProperty intave14FactorProp = new FloatProperty("intave14-factor", 0.15F, 0.0F, 1.0F, () -> mode.getValue() == 15);
    public final PercentProperty intave14VerticalProp = new PercentProperty("intave14-vertical", 100, () -> mode.getValue() == 15);
    public final PercentProperty intave14ChanceProp = new PercentProperty("intave14-chance", 100, () -> mode.getValue() == 15);
    // Adapted from Miau NewVelocity.java XZSwitch mode: swap X/Z.
    public final PercentProperty xzChanceProp = new PercentProperty("xz-chance", 100, () -> mode.getValue() == 16);
    public final BooleanProperty xzOnlyMoving = new BooleanProperty("xz-only-moving", true, () -> mode.getValue() == 16);
    // Adapted from Miau OldGrimVelocity.java + NewVelocity handleOldGrim, experimental: cancel strong S12, burst on update.
    public final IntProperty oldGrimAttacksProp = new IntProperty("oldgrim-attacks", 6, 1, 12, () -> mode.getValue() == 17);
    public final FloatProperty oldGrimReduceProp = new FloatProperty("oldgrim-reduce", 0.5F, 0.0F, 1.0F, () -> mode.getValue() == 17);
    public final FloatProperty oldGrimRangeProp = new FloatProperty("oldgrim-range", 3.0F, 0.0F, 6.0F, () -> mode.getValue() == 17);

    private boolean vulcanTransaction = false;
    private boolean blocksHolding = false;
    private boolean grimC03Pending = false;
    private int grimC03Ticks = 0;
    public final BooleanProperty onlyWhileTargeting = new BooleanProperty("only-while-targeting", false);
    public final BooleanProperty pauseOnS = new BooleanProperty("pause-on-S", false);

    public Velocity() {
        super("Velocity", false, false, "Reduces or modifies incoming knockback velocity.");
    }

    private boolean isInLiquidOrWeb() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private boolean isVelocityPaused() {
        if (mc.thePlayer == null) return true;
        if (this.pauseOnS.getValue() && mc.gameSettings.keyBindBack.isKeyDown()) return true;
        if (this.onlyWhileTargeting.getValue()) {
            if (mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return true;
        }
        return false;
    }

    private void resetAppendedTimer() {
        try {
            net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
            if (timer != null) timer.timerSpeed = 1.0F;
        } catch (Exception ignored) {
        }
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            this.pendingExplosion = false;
            this.allowNext = true;
            return;
        }
        if (this.isVelocityPaused()) return;

        if (!this.allowNext || !this.fakeCheck.getValue()) {
            this.allowNext = true;
            if (this.pendingExplosion) {
                if (this.mode.getValue() <= 1) {
                    this.pendingExplosion = false;
                    if (this.explosionHorizontal.getValue() > 0) {
                        event.setX(event.getX() * (double) this.explosionHorizontal.getValue() / 100.0);
                        event.setZ(event.getZ() * (double) this.explosionHorizontal.getValue() / 100.0);
                    } else {
                        event.setX(mc.thePlayer.motionX);
                        event.setZ(mc.thePlayer.motionZ);
                    }
                    if (this.explosionVertical.getValue() > 0) {
                        event.setY(event.getY() * (double) this.explosionVertical.getValue() / 100.0);
                    } else {
                        event.setY(mc.thePlayer.motionY);
                    }
                }
            } else {
                if (this.mode.getValue() <= 1) {
                    this.chanceCounter = (this.chanceCounter % 100) + this.chance.getValue();
                    if (this.chanceCounter >= 100) {
                        if (this.mode.getValue() == 1) {
                            this.jumpFlag = event.getY() > 0.0;
                        }

                        if (this.horizontal.getValue() > 0) {
                            event.setX(event.getX() * (double) this.horizontal.getValue() / 100.0);
                            event.setZ(event.getZ() * (double) this.horizontal.getValue() / 100.0);
                        } else {
                            event.setX(mc.thePlayer.motionX);
                            event.setZ(mc.thePlayer.motionZ);
                        }
                        if (this.vertical.getValue() > 0) {
                            event.setY(event.getY() * (double) this.vertical.getValue() / 100.0);
                        } else {
                            event.setY(mc.thePlayer.motionY);
                        }
                    }
                } else if (this.mode.getValue() == 2) {
                    if (this.rotate.getValue() && event.getY() > 0.0) {
                        this.knockbackX = event.getX();
                        this.knockbackZ = event.getZ();
                        if (Math.abs(this.knockbackX) > 0.01 || Math.abs(this.knockbackZ) > 0.01) {
                            this.rotatoTickCounter = 1;
                        }
                    }
                    this.ticksSinceVelocity = 0;
                    this.ShouldJump = true;
                    this.hasReceivedVelocity = true;
                } else if (this.mode.getValue() == 4) {
                    this.intaveReduced = false;
                    if (this.intaveJump.getValue() && event.getY() > 0.0) {
                        this.jumpFlag = true;
                    }
                } else if (this.mode.getValue() == 5) {
                    this.grimPending = this.grimTimes.getValue();
                } else if (this.mode.getValue() == 6) {
                    event.setX(event.getX() * this.matrixScale.getValue());
                    event.setZ(event.getZ() * this.matrixScale.getValue());
                    if (mc.thePlayer != null && mc.thePlayer.onGround) {
                        event.setX(event.getX() * this.matrixGroundScale.getValue());
                        event.setZ(event.getZ() * this.matrixGroundScale.getValue());
                    }
                    this.matrixReduced = false;
                } else if (this.mode.getValue() == 7) {
                    this.polarPending = true;
                    this.polarHurtTime = 7 + (int) (Math.random() * 3);
                    this.polarHurtCount++;
                } else if (this.mode.getValue() == 8) {
                    if (this.legitIgnoreLiquid.getValue() && this.isInLiquidOrWeb()) return;
                    if (Math.random() * 100.0 > this.legitChance.getValue()) return;
                    if (mc.thePlayer != null && mc.thePlayer.onGround && (this.legitJumpInInv.getValue() || mc.currentScreen == null)) {
                        mc.thePlayer.jump();
                    }
                } else if (this.mode.getValue() == 10) {
                    // Adapted from S+ TickVelocity: arm delayed apply, applied in onTick.
                    this.tickCounter = 0;
                    this.tickPending = true;
                } else if (this.mode.getValue() == 11) {
                    // Adapted from S+ ZipVelocity, simplified experimental: hold KB here, release on attack/timeout. S32 buffering omitted.
                    this.zipMotionX = event.getX();
                    this.zipMotionY = event.getY();
                    this.zipMotionZ = event.getZ();
                    event.setX(mc.thePlayer.motionX);
                    event.setY(mc.thePlayer.motionY);
                    event.setZ(mc.thePlayer.motionZ);
                    this.zipHolding = true;
                    this.zipTicks = 0;
                } else if (this.mode.getValue() == 17) {
                    // Adapted from Miau OldGrimVelocity, experimental: cancel strong KB here, burst in onUpdate.
                    if (Math.hypot(event.getX(), event.getZ()) <= 0.125) return;
                    event.setX(mc.thePlayer.motionX);
                    event.setY(mc.thePlayer.motionY);
                    event.setZ(mc.thePlayer.motionZ);
                    this.oldGrimHolding = true;
                    this.oldGrimAttacked = false;
                } else if (this.mode.getValue() == 13) {
                    // Adapted from S+ MatrixReverseVelocity.
                    if (Math.random() * 100.0 > this.matrixReverseChanceProp.getValue()) return;
                    event.setX(event.getX() * this.matrixReverseXZProp.getValue());
                    event.setZ(event.getZ() * this.matrixReverseXZProp.getValue());
                } else if (this.mode.getValue() == 14) {
                    // Adapted from S+ MatrixFullVelocity pre scale.
                    event.setX(event.getX() * this.matrixFullXZProp.getValue());
                    event.setZ(event.getZ() * this.matrixFullXZProp.getValue());
                    if (mc.thePlayer != null && mc.thePlayer.onGround) {
                        event.setX(event.getX() * this.matrixFullGroundProp.getValue());
                        event.setZ(event.getZ() * this.matrixFullGroundProp.getValue());
                    }
                    event.setY(event.getY() * this.matrixFullYProp.getValue());
                    this.matrixFullReduced = false;
                } else if (this.mode.getValue() == 15) {
                    // Adapted from S+ Intave14Velocity hurt gate.
                    if (mc.thePlayer == null || mc.thePlayer.hurtTime < this.intave14HurtProp.getValue()) return;
                    if (Math.random() * 100.0 > this.intave14ChanceProp.getValue()) return;
                    event.setX(event.getX() * this.intave14FactorProp.getValue());
                    event.setZ(event.getZ() * this.intave14FactorProp.getValue());
                    if (this.intave14VerticalProp.getValue() != 100) {
                        event.setY(event.getY() * (double) this.intave14VerticalProp.getValue() / 100.0);
                    }
                } else if (this.mode.getValue() == 16) {
                    // Adapted from Miau NewVelocity XZSwitch.
                    if (this.xzOnlyMoving.getValue() && !MoveUtil.isMoving()) return;
                    this.xzChanceCounter = (this.xzChanceCounter % 100) + this.xzChanceProp.getValue();
                    if (this.xzChanceCounter < 100) return;
                    double swappedX = event.getX();
                    event.setX(event.getZ());
                    event.setZ(swappedX);
                }
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (!(event.getTarget() instanceof EntityLivingBase)) return;
        if (this.mode.getValue() == 4) {
            if (mc.thePlayer.hurtTime <= 0 || this.intaveReduced) return;
            if (Math.random() * 100.0 > this.intaveChance.getValue()) return;
            float factor = mc.thePlayer.isSprinting() ? this.intaveSprintXZ.getValue() : this.intaveXZ.getValue();
            mc.thePlayer.motionX *= factor;
            mc.thePlayer.motionZ *= factor;
            this.intaveReduced = true;
            if (this.debugLog.getValue()) {
                ChatUtil.sendFormatted(Atlas.clientName + "Intave reduce");
            }
        } else if (this.mode.getValue() == 6) {
            if (mc.thePlayer.hurtTime <= 0 || this.matrixReduced) return;
            if (!mc.thePlayer.isSprinting()) {
                this.matrixReduced = true;
                return;
            }
            double ax = Math.abs(mc.thePlayer.motionX);
            double az = Math.abs(mc.thePlayer.motionZ);
            if (ax < 0.625 && az < 0.625) {
                mc.thePlayer.motionX *= 0.4;
                mc.thePlayer.motionZ *= 0.4;
            } else if (ax < 1.25 && az < 1.25) {
                mc.thePlayer.motionX *= 0.67;
                mc.thePlayer.motionZ *= 0.67;
            }
            mc.thePlayer.setSprinting(false);
            this.matrixReduced = true;
        } else if (this.mode.getValue() == 11) {
            // Adapted from S+ ZipVelocity stop-on-attack, simplified: release held KB.
            if (this.zipHolding && this.zipStopOnAttack.getValue() && mc.thePlayer.hurtTime > 0) {
                mc.thePlayer.motionX = this.zipMotionX * (double) this.zipHorizontalProp.getValue() / 100.0;
                mc.thePlayer.motionZ = this.zipMotionZ * (double) this.zipHorizontalProp.getValue() / 100.0;
                mc.thePlayer.motionY = this.zipMotionY * (double) this.zipVerticalProp.getValue() / 100.0;
                this.zipHolding = false;
                this.zipTicks = 0;
            }
        } else if (this.mode.getValue() == 12) {
            // Adapted from S+ KarhuVelocity window, experimental, no bounding box.
            if (mc.thePlayer.hurtTime <= this.karhuStartProp.getValue() && mc.thePlayer.hurtTime > this.karhuStopProp.getValue()) {
                mc.thePlayer.motionX *= this.karhuFactorProp.getValue();
                mc.thePlayer.motionZ *= this.karhuFactorProp.getValue();
            }
        } else if (this.mode.getValue() == 14) {
            // Adapted from S+ MatrixFullVelocity post damp.
            if (mc.thePlayer.hurtTime <= 0 || this.matrixFullReduced) return;
            if (mc.thePlayer.onGround) {
                mc.thePlayer.motionX *= 0.75;
                mc.thePlayer.motionZ *= 0.75;
            } else {
                mc.thePlayer.motionX *= 0.85;
                mc.thePlayer.motionZ *= 0.85;
            }
            this.matrixFullReduced = true;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;

        if (this.mode.getValue() == 1) {
            this.handleJumpReset();
        }
        if (this.ticksSinceVelocity >= 0) {
            this.ticksSinceVelocity++;
        }
        if (this.ticksSinceVelocity >= 10) {
            this.ticksSinceVelocity = -1;
            this.ShouldJump = false;
        }

        if (mc.thePlayer == null) return;
        if (this.mode.getValue() == 7 && this.polarPending && this.polarForceChange.getValue()
                && this.polarHurtCount >= this.polarHurtTarget.getValue()) {
            this.polarHurtCount = 0;
            this.polarHurtTime = 7 + (int) (Math.random() * 3);
        }
        if (this.mode.getValue() == 9 && this.delayActive) {
            this.delayTickCounter++;
            if (this.delayTickCounter >= this.delayTicksProp.getValue()) {
                mc.thePlayer.motionX = this.delayMotionX * this.delayHorizontalProp.getValue();
                mc.thePlayer.motionZ = this.delayMotionZ * this.delayHorizontalProp.getValue();
                mc.thePlayer.motionY = this.delayMotionY * this.delayVerticalProp.getValue();
                this.delayActive = false;
                this.delayTickCounter = 0;
            }
        }
        if (this.mode.getValue() == 10 && this.tickPending) {
            // Adapted from S+ TickVelocity: tick-delayed scale instead of scheduler.
            this.tickCounter++;
            if (this.tickCounter >= this.tickDelayProp.getValue()) {
                this.tickChanceCounter = (this.tickChanceCounter % 100) + this.tickChanceProp.getValue();
                if (this.tickChanceCounter >= 100 && mc.thePlayer.hurtTime > 0) {
                    mc.thePlayer.motionX *= (double) this.tickHorizontalProp.getValue() / 100.0;
                    mc.thePlayer.motionZ *= (double) this.tickHorizontalProp.getValue() / 100.0;
                    mc.thePlayer.motionY *= (double) this.tickVerticalProp.getValue() / 100.0;
                }
                this.tickPending = false;
                this.tickCounter = 0;
            }
        }
        if (this.mode.getValue() == 11 && this.zipHolding) {
            // Adapted from S+ ZipVelocity timeout release, simplified.
            this.zipTicks++;
            if (this.zipTicks >= this.zipDelayProp.getValue()) {
                mc.thePlayer.motionX = this.zipMotionX * (double) this.zipHorizontalProp.getValue() / 100.0;
                mc.thePlayer.motionZ = this.zipMotionZ * (double) this.zipHorizontalProp.getValue() / 100.0;
                mc.thePlayer.motionY = this.zipMotionY * (double) this.zipVerticalProp.getValue() / 100.0;
                this.zipHolding = false;
                this.zipTicks = 0;
            }
        }
        if (this.mode.getValue() == 20 && this.grimC03Pending && !this.isVelocityPaused()) {
            this.grimC03Ticks++;
            if (mc.thePlayer.hurtTime > 0) {
                mc.getNetHandler().addToSendQueue(new C03PacketPlayer(true));
                this.grimC03Pending = false;
                this.grimC03Ticks = 0;
            } else if (this.grimC03Ticks > 40) {
                this.grimC03Pending = false;
                this.grimC03Ticks = 0;
            }
        }
    }

    private void handleJumpReset() {
        if (!this.ShouldJump) return;

        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        if (mc.thePlayer == null || mc.currentScreen instanceof GuiInventory || scaffold.isEnabled()) return;
        if (this.ticksSinceVelocity >= 0) {
            if (this.ticksSinceVelocity == 0) {
                this.pressed = mc.gameSettings.keyBindJump.isPressed();
            }
            if (this.ticksSinceVelocity <= 2 && mc.thePlayer.onGround) {
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
            }
        }
        if (this.ticksSinceVelocity >= 4 && this.ticksSinceVelocity <= 9) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), this.pressed);
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        // also checks Mode 2 with the hypixelJump option on
        boolean isMode1 = this.mode.getValue() == 1;
        boolean isMode2Jump = this.mode.getValue() == 2 && this.hypixelJump.getValue();
        boolean isMode4Jump = this.mode.getValue() == 4 && this.intaveJump.getValue()
                && Math.random() * 100.0 <= this.intaveJumpChance.getValue();

        if (this.isEnabled() && this.jumpFlag && (isMode1 || isMode2Jump || isMode4Jump)) {
            this.jumpFlag = false;
            if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump) && !this.isInLiquidOrWeb()) {
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;

        if (this.mode.getValue() == 2) {
            if (event.getType() == EventType.PRE) {
                if (this.reduce.getValue()) {
                    if (this.hasReceivedVelocity) {
                        if (this.reduceTick >= this.attackTimes.getValue()) {
                            this.reduceTick = 0;
                            this.hasReceivedVelocity = false;
                        }
                        KillAura killAura = (KillAura) Atlas.moduleManager.getModule(KillAura.class);
                        if (killAura.getTarget() != null && this.canReduceHit(killAura, event)) {
                            if (mc.thePlayer.isSprinting() || !this.onlySprinting.getValue()) {
                                if (!this.reduceWhenCanAttack.getValue()
                                        || (killAura.blockTick == 0 && killAura.autoBlock.getValue() == 4)
                                        || (killAura.autoBlock.getValue() == 3 && killAura.blockTick == 0)) {
                                    EventManager.call(new AttackEvent(killAura.getTarget()));
                                    mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
                                    mc.getNetHandler().addToSendQueue(new C02PacketUseEntity(killAura.getTarget(), C02PacketUseEntity.Action.ATTACK));
                                    killAura.noteExternalAttack();
                                    mc.thePlayer.motionX *= 0.6D;
                                    mc.thePlayer.motionZ *= 0.6D;
                                    mc.thePlayer.setSprinting(false);
                                }
                            }
                        }
                        this.reduceTick++;
                    }
                }

                int maxTick = this.rotateTick.getValue();
                if (this.rotatoTickCounter > 0 && this.rotatoTickCounter <= maxTick) {
                    if (this.rotatoTickCounter == 1) {
                        double deltaX = -this.knockbackX;
                        double deltaZ = -this.knockbackZ;
                        this.targetRotation = RotationUtil.getRotationsTo(deltaX, 0, deltaZ, event.getYaw(), event.getPitch());
                    }
                    if (this.targetRotation != null) {
                        event.setRotation(this.targetRotation[0], this.targetRotation[1], 2);
                        event.setPervRotation(this.targetRotation[0], 2);
                    }
                }
            } else if (event.getType() == EventType.POST) {
                int maxTick = this.rotateTick.getValue();
                if (this.rotatoTickCounter > 0 && this.rotatoTickCounter <= maxTick) {
                    this.rotatoTickCounter++;
                    if (this.rotatoTickCounter > maxTick) {
                        this.rotatoTickCounter = 0;
                        this.targetRotation = null;
                        this.knockbackX = 0;
                        this.knockbackZ = 0;
                    }
                }
            }
        }

        if (this.mode.getValue() == 3 && this.slapReduce.getValue() && event.getType() == EventType.PRE) {
            if (this.slapReduceTicks > 0) {
                this.slapReduceTicks--;
                if (atlas.util.Auras.target() != null) {
                    EntityLivingBase target = atlas.util.Auras.target();
                    if (!((IAccessorEntity) mc.thePlayer).getIsInWeb() && mc.thePlayer.isSprinting() && MoveUtil.isMoving() && target != mc.thePlayer && !this.badPackets()) {
                        EventManager.call(new AttackEvent(target));
                        mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
                        mc.getNetHandler().addToSendQueue(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
                        mc.thePlayer.motionX *= 0.6;
                        mc.thePlayer.motionZ *= 0.6;
                        mc.thePlayer.setSprinting(false);
                        this.slapAnInt++;
                        if (this.debugLog.getValue()) {
                            ChatUtil.sendFormatted(Atlas.clientName + "Attack reduce " + this.slapAnInt);
                        }
                    }
                }
            }
        }

        if (this.mode.getValue() == 5 && event.getType() == EventType.PRE) {
            if (this.grimPending > 0 && mc.thePlayer != null && mc.thePlayer.hurtTime > 0
                    && (!this.grimOnlyMoving.getValue() || MoveUtil.isMoving())) {
                Entity target = null;
                if (atlas.util.Auras.target() != null) {
                    target = atlas.util.Auras.target();
                } else if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                        && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
                    target = mc.objectMouseOver.entityHit;
                }
                if (target != null) {
                    for (int i = 0; i < this.grimAttacks.getValue(); i++) {
                        EventManager.call(new AttackEvent(target));
                        mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
                        mc.getNetHandler().addToSendQueue(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
                        mc.thePlayer.motionX *= 0.6;
                        mc.thePlayer.motionZ *= 0.6;
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(Atlas.clientName + "Grim reduce");
                    }
                }
                this.grimPending--;
            }
        }

        if (this.mode.getValue() == 7 && event.getType() == EventType.PRE) {
            if (this.polarPending && mc.thePlayer != null && mc.thePlayer.hurtTime == this.polarHurtTime && mc.thePlayer.onGround) {
                mc.thePlayer.jump();
                if (this.debugLog.getValue()) {
                    ChatUtil.sendFormatted(Atlas.clientName + "Polar jump");
                }
                this.polarHurtTime = 7 + (int) (Math.random() * 3);
                if (mc.thePlayer.hurtTime == 0) {
                    this.polarPending = false;
                }
            }
            if (mc.thePlayer != null && mc.thePlayer.hurtTime == 0) {
                this.polarPending = false;
            }
        }

        if (this.mode.getValue() == 17 && event.getType() == EventType.PRE) {
            // Adapted from Miau OldGrimVelocity, experimental: burst after cancelled strong S12.
            if (mc.thePlayer != null && mc.thePlayer.hurtTime == 0) {
                this.oldGrimHolding = false;
                this.oldGrimAttacked = false;
            }
            if (this.oldGrimHolding && !this.oldGrimAttacked && mc.thePlayer != null && mc.thePlayer.hurtTime > 0) {
                Entity target = null;
                EntityLivingBase auraTarget = atlas.util.Auras.target();
                if (auraTarget != null
                        && mc.thePlayer.getDistanceToEntity(auraTarget) <= this.oldGrimRangeProp.getValue()) {
                    target = auraTarget;
                } else if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                        && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                        && mc.thePlayer.getDistanceToEntity(mc.objectMouseOver.entityHit) <= this.oldGrimRangeProp.getValue()) {
                    target = mc.objectMouseOver.entityHit;
                }
                if (target != null) {
                    boolean wasSprinting = mc.thePlayer.isSprinting();
                    for (int i = 0; i < this.oldGrimAttacksProp.getValue(); i++) {
                        EventManager.call(new AttackEvent(target));
                        mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
                        mc.getNetHandler().addToSendQueue(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
                        mc.thePlayer.motionX *= 0.6;
                        mc.thePlayer.motionZ *= 0.6;
                    }
                    mc.thePlayer.motionX *= this.oldGrimReduceProp.getValue();
                    mc.thePlayer.motionZ *= this.oldGrimReduceProp.getValue();
                    if (!wasSprinting) {
                        mc.thePlayer.setSprinting(false);
                    }
                    this.oldGrimAttacked = true;
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(Atlas.clientName + "OldGrim reduce");
                    }
                }
            }
        }

        if (this.mode.getValue() == 21 && event.getType() == EventType.PRE && !this.isVelocityPaused()) {
            if (mc.thePlayer != null) {
                try {
                    net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
                    if (timer != null) {
                        int hurt = mc.thePlayer.hurtTime;
                        if (hurt >= 8) timer.timerSpeed = 0.3F;
                        else if (hurt > 2) timer.timerSpeed = 5.0F;
                        else timer.timerSpeed = 1.0F;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) return;
        if (this.isVelocityPaused()) return;

        if (event.getType() == EventType.SEND) {
            if (this.mode.getValue() == 19 && event.getPacket() instanceof C0BPacketEntityAction && this.blocksHolding) {
                this.blocksHolding = false;
                event.setCancelled(true);
                return;
            }
            if (this.mode.getValue() == 21 && event.getPacket() instanceof C03PacketPlayer
                    && !(event.getPacket() instanceof C03PacketPlayer.C04PacketPlayerPosition)
                    && !(event.getPacket() instanceof C03PacketPlayer.C05PacketPlayerLook)
                    && !(event.getPacket() instanceof C03PacketPlayer.C06PacketPlayerPosLook)
                    && mc.thePlayer != null && mc.thePlayer.hurtTime != 0) {
                event.setCancelled(true);
                return;
            }
        }

        if (this.mode.getValue() == 3 && event.getType() == EventType.SEND) {
            Packet<?> packet = event.getPacket();
            if (packet instanceof C09PacketHeldItemChange) {
                this.slot = true;
            } else if (packet instanceof C0APacketAnimation) {
                this.swing = true;
            } else if (packet instanceof C02PacketUseEntity) {
                C02PacketUseEntity useEntity = (C02PacketUseEntity) packet;
                if (useEntity.getAction() == C02PacketUseEntity.Action.ATTACK) {
                    this.attack = true;
                }
            } else if (packet instanceof C08PacketPlayerBlockPlacement) {
                this.block = true;
            } else if (packet instanceof C07PacketPlayerDigging) {
                this.block = true;
                this.dig = true;
            } else if (packet instanceof C0DPacketCloseWindow ||
                    packet instanceof C0EPacketClickWindow ||
                    (packet instanceof C16PacketClientStatus &&
                            ((C16PacketClientStatus) packet).getStatus() == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT)) {
                this.inventory = true;
            } else if (packet instanceof C03PacketPlayer) {
                this.resetBadPackets();
            }
        }

        if (event.getType() == EventType.RECEIVE) {
            if (this.mode.getValue() == 0 || this.mode.getValue() == 1) {
                if (event.getPacket() instanceof S27PacketExplosion) {
                    S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
                    if (packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F) {
                        this.pendingExplosion = true;
                        if (this.explosionHorizontal.getValue() == 0 || this.explosionVertical.getValue() == 0) {
                            event.setCancelled(true);
                        }
                        if (this.debugLog.getValue()) {
                            ChatUtil.sendFormatted(
                                    String.format(
                                            "%sExplosion (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                            Atlas.clientName,
                                            mc.thePlayer.ticksExisted,
                                            mc.thePlayer.motionX + (double) packet.func_149149_c(),
                                            mc.thePlayer.motionY + (double) packet.func_149144_d(),
                                            mc.thePlayer.motionZ + (double) packet.func_149147_e()
                                    )
                            );
                        }
                    }
                }
            }

            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    this.ticksSinceVelocity = 0;
                    this.ShouldJump = true;

                    if (this.mode.getValue() == 2) {
                        this.hasReceivedVelocity = true;
                        this.jumpFlag = packet.getMotionY() > 0;
                    }

                    if (this.mode.getValue() == 3 && this.slapReduce.getValue()) {
                        this.slapReduceTicks = this.calculateSlapTicks(packet.getMotionX(), packet.getMotionZ());
                        if (this.debugLog.getValue()) {
                            ChatUtil.sendFormatted(Atlas.clientName + "Attack reduceTicks: " + this.slapReduceTicks);
                        }
                    }

                    if (this.mode.getValue() == 4) {
                        this.intaveReduced = false;
                        if (this.intaveJump.getValue() && packet.getMotionY() > 0) {
                            this.jumpFlag = true;
                        }
                    }

                    if (this.mode.getValue() == 5) {
                        this.grimPending = this.grimTimes.getValue();
                    }

                    if (this.mode.getValue() == 6) {
                        this.matrixReduced = false;
                    }

                    if (this.mode.getValue() == 7) {
                        this.polarPending = true;
                        this.polarHurtTime = 7 + (int) (Math.random() * 3);
                        this.polarHurtCount++;
                    }

                    if (this.mode.getValue() == 9) {
                        this.delayChanceCounter = (this.delayChanceCounter % 100) + this.delayChanceProp.getValue();
                        if (this.delayChanceCounter >= 100 && mc.thePlayer.onGround) {
                            this.delayMotionX = (double) packet.getMotionX() / 8000.0;
                            this.delayMotionY = (double) packet.getMotionY() / 8000.0;
                            this.delayMotionZ = (double) packet.getMotionZ() / 8000.0;
                            this.delayTickCounter = 0;
                            this.delayActive = true;
                            event.setCancelled(true);
                        } else {
                            mc.thePlayer.motionX = ((double) packet.getMotionX() / 8000.0) * this.delayHorizontalProp.getValue();
                            mc.thePlayer.motionZ = ((double) packet.getMotionZ() / 8000.0) * this.delayHorizontalProp.getValue();
                            mc.thePlayer.motionY = ((double) packet.getMotionY() / 8000.0) * this.delayVerticalProp.getValue();
                            event.setCancelled(true);
                        }
                        if (this.debugLog.getValue()) {
                            ChatUtil.sendFormatted(Atlas.clientName + "Delay hold");
                        }
                    }
                    if (this.mode.getValue() == 19) {
                        this.blocksHolding = true;
                        event.setCancelled(true);
                        mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SNEAKING));
                        mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SNEAKING));
                    }
                    if (this.mode.getValue() == 20) {
                        if (MoveUtil.isMoving()) {
                            this.grimC03Pending = true;
                            this.grimC03Ticks = 0;
                            event.setCancelled(true);
                        }
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sVelocity (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Atlas.clientName,
                                        mc.thePlayer.ticksExisted,
                                        (double) packet.getMotionX() / 8000.0,
                                        (double) packet.getMotionY() / 8000.0,
                                        (double) packet.getMotionZ() / 8000.0
                                )
                        );
                    }
                }
            }

            if (this.mode.getValue() == 18 && event.getPacket() instanceof S32PacketConfirmTransaction) {
                event.setCancelled(true);
                mc.getNetHandler().addToSendQueue(new C0FPacketConfirmTransaction(this.vulcanTransaction ? 1 : -1, (short) (this.vulcanTransaction ? -1 : 1), this.vulcanTransaction));
                this.vulcanTransaction = !this.vulcanTransaction;
            }

            if (event.getPacket() instanceof S19PacketEntityStatus) {
                S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
                Entity entity = packet.getEntity(mc.theWorld);
                if (entity != null && entity.equals(mc.thePlayer) && packet.getOpCode() == 2) {
                    this.allowNext = false;
                }
            }
        }
    }

    /**
     * Whether a knockback-reducing hit on the aura's target could be a real
     * one (2026-10-04): the target's hitbox within the aura's attack range,
     * and the look last sent to the server on it. The aura keeps a target out
     * to its autoblock range (6 blocks by default), and the reduce hits used
     * to go out at whatever distance and whatever angle that target was --
     * an attack packet no reach or hitbox check passes.
     */
    private boolean canReduceHit(KillAura killAura, UpdateEvent event) {
        EntityLivingBase target = killAura.getTarget();
        if (target == null) {
            return false;
        }
        float border = target.getCollisionBorderSize();
        net.minecraft.util.AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        double range = killAura.attackRange.getValue();
        if (RotationUtil.distanceToBox(box) > range) {
            return false;
        }
        return box.isVecInside(mc.thePlayer.getPositionEyes(1.0F))
                || RotationUtil.rayTrace(box, event.getYaw(), event.getPitch(), range) != null;
    }

    private int calculateSlapTicks(int motionX, int motionZ) {
        double kb = Math.hypot(motionX, motionZ);
        if (!tickExactEnable.getValue()) {
            double ticks = 6.43153527E-4 * kb + 2.9419087136;
            int result = (int) Math.round(ticks);
            if (result < 1) result = 1;
            if (result > 10) result = 10;
            return result;
        }
        if (kb <= 500) return tick500.getValue();
        if (kb <= 1000) return tick1000.getValue();
        if (kb <= 2000) return tick2000.getValue();
        if (kb <= 3000) return tick3000.getValue();
        if (kb <= 4000) return tick4000.getValue();
        if (kb <= 5000) return tick5000.getValue();
        if (kb <= 6000) return tick6000.getValue();
        if (kb <= 7000) return tick7000.getValue();
        if (kb <= 8000) return tick8000.getValue();
        if (kb <= 9000) return tick9000.getValue();
        return tick10000.getValue();
    }

    private boolean badPackets() {
        return this.badPackets(false, false, false, false, false, false);
    }

    private boolean badPackets(boolean p1, boolean p2, boolean p3, boolean p4, boolean p5, boolean p6) {
        if (this.slot && !p1) return true;
        if (this.attack && !p2) return true;
        if (this.swing && !p3) return true;
        if (this.block && !p4) return true;
        if (this.inventory && !p5) return true;
        if (this.dig && !p6) return true;
        return false;
    }

    private void resetBadPackets() {
        this.slot = false;
        this.swing = false;
        this.attack = false;
        this.block = false;
        this.inventory = false;
        this.dig = false;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.onDisabled();
    }

    @Override
    public void onEnabled() {
        this.pendingExplosion = false;
        this.allowNext = true;
        this.rotatoTickCounter = 0;
        this.targetRotation = null;
        this.knockbackX = 0;
        this.knockbackZ = 0;
        this.reduceTick = -1;
        this.hasReceivedVelocity = false;
        this.ticksSinceVelocity = -1;
        this.jumpFlag = false;
        this.ShouldJump = false;
        this.slapReduceTicks = 0;
        this.slapAnInt = 0;
        this.intaveReduced = false;
        this.grimPending = 0;
        this.matrixReduced = false;
        this.polarPending = false;
        this.polarHurtTime = 8;
        this.polarHurtCount = 0;
        this.delayActive = false;
        this.delayTickCounter = 0;
        this.delayChanceCounter = 0;
        this.tickPending = false;
        this.tickCounter = 0;
        this.tickChanceCounter = 0;
        this.zipHolding = false;
        this.zipTicks = 0;
        this.zipMotionX = 0;
        this.zipMotionY = 0;
        this.zipMotionZ = 0;
        this.matrixFullReduced = false;
        this.xzChanceCounter = 0;
        this.oldGrimHolding = false;
        this.oldGrimAttacked = false;
        this.vulcanTransaction = false;
        this.blocksHolding = false;
        this.grimC03Pending = false;
        this.grimC03Ticks = 0;
        this.resetBadPackets();
    }

    @Override
    public void onDisabled() {
        this.pendingExplosion = false;
        this.allowNext = true;
        this.hasReceivedVelocity = false;
        this.rotatoTickCounter = 0;
        this.targetRotation = null;
        this.knockbackX = 0;
        this.knockbackZ = 0;
        this.reduceTick = -1;
        this.ticksSinceVelocity = -1;
        this.jumpFlag = false;
        this.ShouldJump = false;
        this.slapReduceTicks = 0;
        this.slapAnInt = 0;
        this.intaveReduced = false;
        this.grimPending = 0;
        this.matrixReduced = false;
        this.polarPending = false;
        this.polarHurtTime = 8;
        this.polarHurtCount = 0;
        this.delayActive = false;
        this.delayTickCounter = 0;
        this.delayChanceCounter = 0;
        this.tickPending = false;
        this.tickCounter = 0;
        this.tickChanceCounter = 0;
        this.zipHolding = false;
        this.zipTicks = 0;
        this.zipMotionX = 0;
        this.zipMotionY = 0;
        this.zipMotionZ = 0;
        this.matrixFullReduced = false;
        this.xzChanceCounter = 0;
        this.oldGrimHolding = false;
        this.oldGrimAttacked = false;
        this.vulcanTransaction = false;
        this.blocksHolding = false;
        this.grimC03Pending = false;
        this.grimC03Ticks = 0;
        this.resetAppendedTimer();
        this.resetBadPackets();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}