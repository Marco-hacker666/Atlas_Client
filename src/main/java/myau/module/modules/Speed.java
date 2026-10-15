package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LivingUpdateEvent;
import myau.events.MoveInputEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;

public class Speed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"default", "legit", "polar", "vulcan", "grimac", "intave", "blocksmc", "hypixel", "hypixel-low", "matrix", "strafe", "vanilla"});

    public final FloatProperty multiplier = new FloatProperty("multiplier", 1.0F, 0.0F, 10.0F);
    public final FloatProperty friction = new FloatProperty("friction", 1.0F, 0.0F, 10.0F);
    public final PercentProperty strafe = new PercentProperty("strafe", 0);
    public final BooleanProperty onlyJumping = new BooleanProperty("only-jumping", true);
    public final ModeProperty blockPlacements = new ModeProperty("block-placements", 1, new String[]{"LEGIT", "BLATANT"});
    public final FloatProperty vulcanLowHop = new FloatProperty("vulcan-lowhop", 2.0F, 0.0F, 4.0F, () -> mode.getValue() == 3);
    public final FloatProperty grimAmount = new FloatProperty("grim-amount", 3.0F, 0.0F, 10.0F, () -> mode.getValue() == 4);
    public final BooleanProperty grimAutoJump = new BooleanProperty("grim-autojump", true, () -> mode.getValue() == 4);
    public final BooleanProperty intaveAir = new BooleanProperty("intave-air", true, () -> mode.getValue() == 5);
    public final FloatProperty blocksmcBoost = new FloatProperty("blocksmc-boost", 2.15F, 1.0F, 3.0F, () -> mode.getValue() == 6);
    public final FloatProperty hypixelBoost = new FloatProperty("hypixel-boost", 1.2F, 1.0F, 2.0F, () -> mode.getValue() == 7 || mode.getValue() == 8);
    public final FloatProperty matrixGlide = new FloatProperty("matrix-glide", 1.002F, 1.0F, 1.01F, () -> mode.getValue() == 9);
    public final FloatProperty strafeBoost = new FloatProperty("strafe-boost", 1.0F, 0.0F, 3.0F, () -> mode.getValue() == 10);
    public final FloatProperty speedTimer = new FloatProperty("speed-timer", 1.0F, 0.1F, 2.0F, () -> mode.getValue() >= 2);

    private boolean wasOnGround = false;
    private boolean boosting = false;
    private float cachedSilentYaw = Float.NaN;
    private int speedOffTicks = 0;
    private int vulcanJumps = 0;
    private boolean vulcanJumped = false;
    private double blocksmcSpeed = 0.0;
    private boolean blocksmcReset = true;

    private boolean canBoost() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        return !scaffold.isEnabled() && MoveUtil.isForwardPressed()
                && mc.thePlayer.getFoodStats().getFoodLevel() > 6
                && !mc.thePlayer.isSneaking()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private boolean canBoostLegit() {
        if (!canBoost()) return false;
        if (myau.util.Auras.target() != null) return false;
        if (this.onlyJumping.getValue()) {
            return mc.gameSettings.keyBindJump.isKeyDown();
        }
        return true;
    }

    private boolean isOnlyForward() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                && !mc.gameSettings.keyBindLeft.isKeyDown()
                && !mc.gameSettings.keyBindRight.isKeyDown()
                && !mc.gameSettings.keyBindBack.isKeyDown();
    }

    public boolean isBoosting() {
        return boosting && mode.getModeString().equals("legit")
                && blockPlacements.getValue() == 0;
    }

    public float getSilentYaw() {
        return cachedSilentYaw;
    }

    public Speed() {
        super("Speed", false, false, "Increases movement speed with multiple bypass modes.");
    }

    @Override
    public void onEnabled() {
        speedOffTicks = 0;
        vulcanJumps = 0;
        vulcanJumped = false;
        blocksmcSpeed = 0.0;
        blocksmcReset = true;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    private double predictedMotion(double motion, int ticks) {
        double predicted = motion;
        for (int i = 0; i < ticks; i++) {
            predicted = (predicted - 0.08) * 0.98;
        }
        return predicted;
    }

    private void applySpeedTimer() {
        if (mc.thePlayer == null) return;
        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (timer == null) return;
        if (this.mode.getValue() >= 2 && this.speedTimer.getValue() != 1.0F && canBoost()) {
            timer.timerSpeed = this.speedTimer.getValue();
        } else if (timer.timerSpeed != 1.0F) {
            timer.timerSpeed = 1.0F;
        }
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        if (mc.thePlayer.onGround) {
            speedOffTicks = 0;
        } else {
            speedOffTicks++;
        }
        if (this.mode.getValue() >= 2) {
            applySpeedTimer();
        }

        if (this.mode.getModeString().equals("legit")) {
            if (!canBoostLegit()) {
                boosting = false;
                cachedSilentYaw = Float.NaN;
                wasOnGround = mc.thePlayer.onGround;
                return;
            }

            boolean onGround = mc.thePlayer.onGround;

            if (!onGround && wasOnGround && isOnlyForward()) {
                boosting = true;
            }

            if (onGround) {
                boosting = false;
                cachedSilentYaw = Float.NaN;
            }

            if (boosting && isOnlyForward()) {
                float realYaw = event.getNewYaw();
                float realPitch = event.getNewPitch();
                float quantized = RotationUtil.quantizeAngle(realYaw + 45.0F);
                cachedSilentYaw = quantized;
                event.setRotation(quantized, realPitch, 1);
                event.setPervRotation(quantized, 1);
            } else if (boosting) {
                boosting = false;
                cachedSilentYaw = Float.NaN;
            }

            wasOnGround = onGround;
        }
    }

    @EventTarget(Priority.LOW)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        if (!this.mode.getModeString().equals("legit")) return;
        if (!canBoostLegit()) return;
        if (mc.thePlayer.onGround) return;

        if (boosting && RotationState.isActived() && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget(Priority.LOW)
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) return;

        if (this.mode.getModeString().equals("default") && canBoost()) {
            runDefault(event);
        } else if (this.mode.getValue() == 2 && canBoost()) {
            runPolar(event);
        } else if (this.mode.getValue() == 3 && canBoost()) {
            runVulcan(event);
        } else if (this.mode.getValue() == 4 && canBoost()) {
            runGrimAC(event);
        } else if (this.mode.getValue() == 5 && canBoost()) {
            runIntave(event);
        } else if (this.mode.getValue() == 6 && canBoost()) {
            runBlocksMC(event);
        } else if (this.mode.getValue() == 7 && canBoost()) {
            runHypixel(event, false);
        } else if (this.mode.getValue() == 8 && canBoost()) {
            runHypixel(event, true);
        } else if (this.mode.getValue() == 9 && canBoost()) {
            runMatrix(event);
        } else if (this.mode.getValue() == 10 && canBoost()) {
            runStrafe(event);
        } else if (this.mode.getValue() == 11 && canBoost()) {
            runVanilla(event);
        } else if (this.mode.getModeString().equals("legit") && canBoostLegit()) {
            if (!this.onlyJumping.getValue() && mc.thePlayer.onGround && MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null) return;

        if (this.mode.getModeString().equals("default") && canBoost()) {
            mc.thePlayer.movementInput.jump = false;
        } else if (this.mode.getValue() >= 2 && canBoost()) {
            if (this.mode.getValue() == 4 && this.grimAutoJump.getValue()) {
                mc.thePlayer.movementInput.jump = MoveUtil.isForwardPressed();
            } else {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    private void runDefault(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.motionY = 0.42F;
            MoveUtil.setSpeed(
                    MoveUtil.getJumpMotion() * (double) this.multiplier.getValue().floatValue(),
                    MoveUtil.getMoveYaw()
            );
        } else {
            if (this.friction.getValue() != 1.0F) {
                event.setFriction(event.getFriction() * this.friction.getValue());
            }
            if (this.strafe.getValue() > 0) {
                double speed = MoveUtil.getSpeed();
                MoveUtil.setSpeed(speed * (double) ((float) (100 - this.strafe.getValue()) / 100.0F), MoveUtil.getDirectionYaw());
                MoveUtil.addSpeed(
                        speed * (double) ((float) this.strafe.getValue().intValue() / 100.0F), MoveUtil.getMoveYaw()
                );
                MoveUtil.setSpeed(speed);
            }
        }
    }

    // Ported from Miau PolarSpeed.java: air-tick motion multipliers plus a small sink on tick 5.
    private void runPolar(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            if (MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
            }
        } else {
            mc.thePlayer.movementInput.jump = false;
            float mult = speedOffTicks == 1 ? 1.0020001F : 1.0030001F;
            mc.thePlayer.motionX *= mult;
            mc.thePlayer.motionZ *= mult;
            if (speedOffTicks == 5) {
                mc.thePlayer.motionY -= 0.008;
            }
        }
    }

    // Ported from Miau VulcanSpeed.java and Raven VulcanSpeed.java: hop then timed motion cut.
    private void runVulcan(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            speedOffTicks = 0;
            if (MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
                vulcanJumps++;
                vulcanJumped = true;
                if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                    MoveUtil.setSpeed(0.6, MoveUtil.getMoveYaw());
                } else {
                    MoveUtil.setSpeed(0.485, MoveUtil.getMoveYaw());
                }
            }
            mc.thePlayer.movementInput.jump = false;
        } else if (vulcanJumped) {
            if (speedOffTicks == 1 || speedOffTicks == 2) {
                MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
            } else if (speedOffTicks == 5) {
                mc.thePlayer.motionY = predictedMotion(mc.thePlayer.motionY, vulcanLowHop.getValue().intValue());
            } else if (speedOffTicks == 8 || speedOffTicks == 9) {
                MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
            }
        }
    }

    // Ported from Raven GrimACSpeed.java: entity-proximity push adapted to 1.8.9 motion.
    private void runGrimAC(StrafeEvent event) {
        if (!MoveUtil.isForwardPressed()) return;
        AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox().expand(1.0, 1.0, 1.0);
        int c = 0;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof Entity)) continue;
            Entity entity = (Entity) o;
            if (entity == mc.thePlayer || entity instanceof EntityArmorStand) continue;
            if (!(entity instanceof EntityLivingBase) && !(entity instanceof EntityBoat)
                    && !(entity instanceof EntityMinecart) && !(entity instanceof EntityFishHook)) continue;
            if (entity.getEntityId() == -8 || entity.getEntityId() == -1337) continue;
            if (playerBox.intersectsWith(entity.getEntityBoundingBox())) {
                c++;
            }
        }
        if (c > 0) {
            double offset = Math.min((double) c, (double) grimAmount.getValue()) * 0.04;
            MoveUtil.addSpeed(offset, MoveUtil.getMoveYaw());
        }
    }

    // Ported from Raven IntaveSpeed.java: ground hop plus tiny air multipliers while sprinting.
    private void runIntave(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            if (MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
                if (mc.thePlayer.isSprinting()) {
                    MoveUtil.setSpeed(0.29, MoveUtil.getMoveYaw());
                }
            }
            mc.thePlayer.movementInput.jump = false;
        } else if (this.intaveAir.getValue() && mc.thePlayer.motionY > 0.003 && mc.thePlayer.isSprinting()) {
            mc.thePlayer.motionX *= 1.0015;
            mc.thePlayer.motionZ *= 1.0015;
        }
    }

    // Ported from Raven BlocksMCSpeed.java: boost on hop then friction decay per air tick.
    private void runBlocksMC(StrafeEvent event) {
        double base = MoveUtil.getAllowedHorizontalDistance();
        boolean potionActive = mc.thePlayer.isPotionActive(Potion.moveSpeed);
        if (MoveUtil.isForwardPressed()) {
            if (speedOffTicks == 0) {
                mc.thePlayer.motionY = 0.42;
                blocksmcSpeed = base * (potionActive ? 1.4 : (double) blocksmcBoost.getValue());
            } else if (speedOffTicks == 1) {
                blocksmcSpeed -= 0.8 * (blocksmcSpeed - base);
            } else {
                blocksmcSpeed -= blocksmcSpeed / 159.9;
            }
            blocksmcReset = false;
        } else if (!blocksmcReset) {
            blocksmcSpeed = 0.0;
            blocksmcReset = true;
            blocksmcSpeed = base;
        }
        if (mc.thePlayer.isCollidedHorizontally) {
            blocksmcSpeed = base;
        }
        MoveUtil.setSpeed(Math.max(blocksmcSpeed, base), MoveUtil.getMoveYaw());
    }

    private void runHypixel(StrafeEvent event, boolean low) {
        double base = MoveUtil.getAllowedHorizontalDistance();
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed()) {
            mc.thePlayer.jump();
            MoveUtil.setSpeed(base * (double) hypixelBoost.getValue(), MoveUtil.getMoveYaw());
        } else if (!mc.thePlayer.onGround) {
            mc.thePlayer.movementInput.jump = false;
            if (low && speedOffTicks == 5) {
                mc.thePlayer.motionY -= 0.008;
            }
        }
    }

    private void runMatrix(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            if (MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
            }
        } else {
            mc.thePlayer.movementInput.jump = false;
            float glide = matrixGlide.getValue();
            mc.thePlayer.motionX *= glide;
            mc.thePlayer.motionZ *= glide;
        }
    }

    private void runStrafe(StrafeEvent event) {
        if (mc.thePlayer.onGround) {
            if (MoveUtil.isForwardPressed()) {
                mc.thePlayer.jump();
            }
        } else {
            mc.thePlayer.movementInput.jump = false;
            double speed = MoveUtil.getSpeed();
            MoveUtil.setSpeed(speed * (double) strafeBoost.getValue(), MoveUtil.getMoveYaw());
        }
    }

    private void runVanilla(StrafeEvent event) {
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed()) {
            mc.thePlayer.jump();
        } else {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    @Override
    public void onDisabled() {
        boosting = false;
        wasOnGround = false;
        cachedSilentYaw = Float.NaN;
        speedOffTicks = 0;
        vulcanJumps = 0;
        vulcanJumped = false;
        blocksmcSpeed = 0.0;
        blocksmcReset = true;
        /* Not gated on thePlayer (2026-10-11): the game's Timer outlives a
           world, so disabling Speed while disconnected used to leave
           timerSpeed where the module had put it, and the next server ran at
           that speed until Speed touched it again. Timer, Criticals and Fly
           already reset unconditionally. */
        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (timer != null && timer.timerSpeed != 1.0F) {
            timer.timerSpeed = 1.0F;
        }
    }

    @Override
    public void verifyValue(String mode) {
        if (mc.thePlayer != null) {
            net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
            if (timer != null && timer.timerSpeed != 1.0F) {
                timer.timerSpeed = 1.0F;
            }
        }
    }
}