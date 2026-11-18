package atlas.module.modules;

import com.google.common.base.CaseFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.AttackEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.*;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import atlas.util.TimerUtil;

public class BlockHit extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final ModeProperty mode = new ModeProperty("Mode", 6,
            new String[]{"Helper", "Auto", "Lag", "Swing", "Predict", "Manual", "Slinky", "Full", "Yuri", "Raven", "Crab"})
            .hide("Swing")

            .retire("Full", "Auto", "Lag", "Swing", "Predict")
            .alias("Rhythm", "Full")

            .retire("Slinky", "Crab");

    private final java.util.function.BooleanSupplier slinky = () -> this.modeIndex() == 6;

    private final java.util.function.BooleanSupplier yuriMode = () -> this.modeIndex() == 8;
    private final FloatProperty yRange = new FloatProperty("yuri-range", 3.0F, 1.0F, 6.0F, yuriMode);
    private final PercentProperty yChance = new PercentProperty("yuri-chance", 100, yuriMode);
    private final IntProperty yBlockDelay = new IntProperty("yuri-block-delay", 30, 1, 300, yuriMode);
    private final IntProperty yComboDelay = new IntProperty("yuri-combo-delay", 30, 1, 300, yuriMode);
    private final BlockHitYuri yuri = new BlockHitYuri();
    final FloatProperty sRange = new FloatProperty("block-range", 3.1F, 2.0F, 6.0F, slinky);
    final IntProperty sHold = new IntProperty("max-hold-ticks", 5, 1, 10, slinky);
    final BooleanProperty sRequireLeft = new BooleanProperty("require-left-click", true, slinky);
    final BooleanProperty sRequireRight = new BooleanProperty("require-right-click", false, slinky);
    final BooleanProperty sRequireDamage = new BooleanProperty("require-damage", false, slinky);

    final IntProperty sRandomHold = new IntProperty("random-hold-ticks", 0, 0, 5, slinky);
    final IntProperty sCooldown = new IntProperty("block-cooldown-ms", 0, 0, 500, slinky);

    final IntProperty sHurt = new IntProperty("max-hurt-time", 6, 1, 10, slinky);
    final BooleanProperty sFake = new BooleanProperty("fake-animation", false, slinky);
    final BooleanProperty sFakeInRange = new BooleanProperty("fake-animation-in-range", false,
            () -> slinky.getAsBoolean() && this.sFake.getValue());
    final PercentProperty sLagChance = new PercentProperty("lag-chance", 0, slinky);
    final IntProperty sLagTicks = new IntProperty("lag-ticks", 2, 1, 10,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);
    final BooleanProperty sLagFlush = new BooleanProperty("lag-flush-on-attack", true,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);
    final BooleanProperty sLagReblock = new BooleanProperty("lag-reblock", false,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);

    private final java.util.function.BooleanSupplier ravenMode = () -> this.modeIndex() == 9;
    final FloatProperty ravenRange = new FloatProperty("raven-range", 3.5F, 2.0F, 6.0F, ravenMode);
    final IntProperty ravenMaxHold = new IntProperty("raven-max-hold-ms", 100, 50, 500, ravenMode);
    final BooleanProperty ravenDamaged = new BooleanProperty("raven-damaged", false, ravenMode);
    final IntProperty ravenMaxHurt = new IntProperty("raven-max-hurt-time-ms", 300, 50, 500,
            () -> ravenMode.getAsBoolean() && this.ravenDamaged.getValue());
    final BooleanProperty ravenIgnoreTeammates = new BooleanProperty("raven-ignore-teammates", true, ravenMode);
    final PercentProperty ravenLagChance = new PercentProperty("raven-lag-chance", 0, ravenMode);
    final IntProperty ravenLagMax = new IntProperty("raven-lag-max-ms", 200, 50, 500,
            () -> ravenMode.getAsBoolean() && this.ravenLagChance.getValue() > 0);
    final BooleanProperty ravenNoDelayAttacks = new BooleanProperty("raven-prevent-delaying-attacks", true,
            () -> ravenMode.getAsBoolean() && this.ravenLagChance.getValue() > 0);
    final BooleanProperty ravenBlockAgain = new BooleanProperty("raven-block-again-immediately", true,
            () -> ravenMode.getAsBoolean() && this.ravenLagChance.getValue() > 0);
    final BooleanProperty ravenAnimation = new BooleanProperty("raven-block-animation", true, ravenMode);

    final BooleanProperty ravenAuto = new BooleanProperty("raven-auto", false, ravenMode);

    final BooleanProperty ravenAutoHuman = new BooleanProperty("raven-auto-human", true,
            () -> ravenMode.getAsBoolean() && this.ravenAuto.getValue());
    final IntProperty ravenRandomHold = new IntProperty("raven-random-hold-ms", 0, 0, 300, ravenMode);
    final IntProperty ravenCooldown = new IntProperty("raven-cooldown-ms", 0, 0, 500, ravenMode);
    final BooleanProperty ravenReleaseOnHit = new BooleanProperty("raven-release-on-hit", false, ravenMode);
    final BooleanProperty ravenNoChasing = new BooleanProperty("raven-no-chasing", false, ravenMode);
    final ModeProperty ravenOutOfRange = new ModeProperty("raven-out-of-range", 0,
            new String[]{"Keep", "Once", "Always", "Fake"}, ravenMode);
    final BooleanProperty ravenForceAttack = new BooleanProperty("raven-force-attack", false, ravenMode);
    final BooleanProperty ravenMobs = new BooleanProperty("raven-mobs", false, ravenMode);
    private final AutoBlockRaven raven = new AutoBlockRaven(this);

    private final AutoBlockSlinky slinkyEngine = new AutoBlockSlinky(this);

    private final java.util.function.BooleanSupplier full = () -> this.modeIndex() == 7;
    final FloatProperty fullRange = new FloatProperty("full-range", 3.5F, 2.0F, 6.0F, full);

    final ModeProperty fullStyle = new ModeProperty("full-style", 1, new String[]{"Always", "Smart"}, full);
    final IntProperty fullOpenHurt = new IntProperty("full-open-hurt", 1, 0, 9,
            () -> full.getAsBoolean() && this.fullStyle.getValue() == 1);
    final BooleanProperty fullPingAware = new BooleanProperty("full-ping-aware", true,
            () -> full.getAsBoolean() && this.fullStyle.getValue() == 1);
    final IntProperty fullHold = new IntProperty("full-hold", 3, 1, 10,
            () -> full.getAsBoolean() && this.fullStyle.getValue() == 1);
    final BooleanProperty fullGuard = new BooleanProperty("full-guard", false,
            () -> full.getAsBoolean() && this.fullStyle.getValue() == 1);
    final BooleanProperty fullAir = new BooleanProperty("full-air", false,
            () -> full.getAsBoolean() && this.fullStyle.getValue() == 1);

    final BooleanProperty fullLog = new BooleanProperty("full-log", true, full);
    private final AutoBlockFull fullBlock = new AutoBlockFull(this);
    private static AutoBlockFull fullInstance;

    private final PercentProperty manualChance = new PercentProperty("ManualChance", 80,
            () -> this.modeIndex() == 5);
    private int manualTicks;
    private final IntProperty stopTime = new IntProperty("StopTicks", 2, 1, 5, () -> this.modeIndex() == 0);
    private final ModeProperty autoBlockTime = new ModeProperty("AutoBlockTime", 0, new String[]{"Delay", "HurtTime", "Sag"}, () -> this.modeIndex() == 1);
    private final ModeProperty autoMode = new ModeProperty("AutoMode", 0, new String[]{"Spam", "Hold"}, () -> this.modeIndex() == 1 && this.autoBlockTime.getValue() == 0);
    private final IntProperty holdTick = new IntProperty("HoldTick", 2, 2, 5, () -> this.modeIndex() == 1 && this.autoMode.getValue() == 1 && this.autoBlockTime.getValue() == 0);
    private final IntProperty blockDelay = new IntProperty("BlockDelay", 100, 0, 1000, () -> this.modeIndex() == 1 && this.autoBlockTime.getValue() == 0);
    private final IntProperty minHurtTime = new IntProperty("MinHurtTime", 10, 1, 10, () -> this.modeIndex() == 1 && this.autoBlockTime.getValue() == 1);
    private final IntProperty maxHurtTime = new IntProperty("MaxHurtTime", 10, 1, 10, () -> this.modeIndex() == 1 && this.autoBlockTime.getValue() == 1);
    private final IntProperty delayPacketTick = new IntProperty("DelayPacketTick", 2, 1, 10, () -> this.modeIndex() == 2);
    private final IntProperty blockTick = new IntProperty("BlockTick", 3, 1, 5, () -> this.modeIndex() == 2);
    private final PercentProperty chance = new PercentProperty("BlockHitChance", 50, () -> this.modeIndex() == 1);
    private final BooleanProperty smart = new BooleanProperty("Smart", true, () -> this.modeIndex() == 1);
    private final BooleanProperty autoBlockRange = new BooleanProperty("AutoBlockRange", true, () -> this.modeIndex() == 1);
    private final FloatProperty range = new FloatProperty("Range", 3.0f, 1f, 4f, () -> autoBlockRange.getValue() && this.modeIndex() == 1);

    private final FloatProperty predictRange = new FloatProperty("PredictRange", 4.0f, 2.0f, 8.0f,
            () -> this.modeIndex() == 3);

    private final IntProperty predictHold = new IntProperty("PredictHold", 4, 1, 12,
            () -> this.modeIndex() == 3);

    private final IntProperty predictAngle = new IntProperty("PredictAngle", 70, 10, 180,
            () -> this.modeIndex() == 3);

    private final PercentProperty predictChance = new PercentProperty("PredictChance", 80,
            () -> this.modeIndex() == 3);

    private int predictTicks = 0;

    private final IntProperty rhythmEarly = new IntProperty("RhythmEarly", 50, 0, 500,
            () -> this.modeIndex() == 4);
    private final BooleanProperty rhythmPing = new BooleanProperty("RhythmPing", true,
            () -> this.modeIndex() == 4);
    private final IntProperty rhythmHold = new IntProperty("RhythmHold", 1, 0, 10,
            () -> this.modeIndex() == 4);

    private final IntProperty rhythmMax = new IntProperty("RhythmMax", 5, 1, 20,
            () -> this.modeIndex() == 4);
    private int rhythmHeld;
    private boolean rhythmSpent;
    private static final int DAMAGEABLE = 10;
    private final long[] damageIntervals = new long[8];
    private int intervalCount;
    private int nextInterval;
    private long lastDamageAt;
    private int lastOwnHurt;
    private boolean rhythmBlocking;
    private long holdUntil;

    private final TimerUtil timer = new TimerUtil();
    private int holdTicks, stopTick;

    private boolean startBlocking;
    private boolean attacking;
    private int attackTicks;
    private int sagTicks = 0;
    private int blockTicks = 0;
    private EntityLivingBase target;

    public BlockHit() {
        super("AutoBlock", false, false);
        fullInstance = this.fullBlock;
    }

    static boolean fullManaging() {
        AutoBlockFull full = fullInstance;
        return full != null && full.managing();
    }

    @EventTarget(atlas.event.types.Priority.LOWEST)
    public void onFullTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.isRunning() && this.modeIndex() == 7) {
            this.fullBlock.tick();
        } else if (this.fullBlock.active()) {
            this.fullBlock.reset();
        }
    }

    private void clearOwnDelay() {
        if ("BlockHit".equals(Atlas.lagManager.holder()) && Atlas.lagManager.getDelay() != 0) {
            Atlas.lagManager.setDelay(0);
        }
    }

    private int drivenMode = -1;

    private int modeIndex() {
        return this.drivenMode >= 0 ? this.drivenMode : this.mode.getValue();
    }

    @Override
    public boolean isRunning() {
        if (this.drivenMode < 0 && KillAuraLB.rhythmRuns()) {
            return false;
        }
        return super.isRunning() || this.drivenMode >= 0 && mc.thePlayer != null;
    }

    public void driveFromAura(int modeIndex) {
        if (modeIndex == this.drivenMode) {
            return;
        }
        int before = modeIndex();
        boolean wasRunning = isRunning();
        this.drivenMode = modeIndex;
        if (wasRunning && (before != modeIndex() || !isRunning())) {
            resetModes();
        }
    }

    boolean auraBlocksAlone() {
        return BlockHitYuri.standsAside(this.drivenMode >= 0, atlas.util.Auras.blocksByItself());
    }

    public int drivenMode() {
        return this.drivenMode;
    }

    @Override
    public void onDisabled() {
        resetModes();
    }

    private void resetModes() {
        clearOwnDelay();
        if (this.yuri.blocking) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
        this.yuri.reset();
        if (this.manualTicks > 0) {
            this.manualTicks = 0;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }

        stopPredict();
        if (this.rhythmBlocking) {
            rhythmBlock(false);
        }
        this.intervalCount = 0;
        this.lastDamageAt = 0L;
        this.slinkyEngine.reset(true);
        this.raven.reset(true);
        this.fullBlock.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isRunning() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() == EventType.PRE) {
            if (this.modeIndex() == 0) {
                if (mc.gameSettings.keyBindAttack.isKeyDown()) {
                    if (mc.thePlayer.isBlocking()) {
                        startBlocking = true;
                        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                    }
                }
                if (startBlocking) stopTick++;
                if (stopTick == 2) {
                    KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
                }
                if (stopTick > stopTime.getValue()) {
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
                    startBlocking = false;
                    stopTick = 0;
                }
            }
            if (this.modeIndex() == 1) {
                if (target == null) return;
                if (attacking) {
                    attackTicks++;
                }
                if (attackTicks > 5) {
                    reset();
                    target = null;
                    return;
                }

                if (Math.random() * 100.0 > chance.getValue()) {
                    reset();
                    return;
                }
                if (autoBlockRange.getValue() && mc.thePlayer.getDistanceToEntity(target) >= range.getValue()) {
                    reset();
                    return;
                }
                if (smart.getValue() && target.hurtTime >= 8 && target.hurtTime <= 10) {
                    reset();
                    return;
                }
                if (attacking) {
                    if (autoBlockTime.getValue() == 0) {
                        if (timer.hasTimeElapsed(blockDelay.getValue().longValue())) {
                            if (this.autoMode.getValue() == 0) {
                                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                                timer.reset();
                                reset();
                            }
                            if (this.autoMode.getValue() == 1) {
                                startBlocking = true;
                            }
                            if (startBlocking) {
                                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                                holdTicks++;
                            }
                            if (holdTicks > holdTick.getValue()) {
                                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                                startBlocking = false;
                                holdTicks = 0;
                                timer.reset();
                            }
                        }
                    }
                    if (autoBlockTime.getValue() == 1) {
                        if (mc.thePlayer.hurtTime >= minHurtTime.getValue() && mc.thePlayer.hurtTime <= maxHurtTime.getValue()) {
                            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                            startBlocking = true;
                        } else if (startBlocking) {
                            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                            startBlocking = false;
                        }
                    }
                    if (autoBlockTime.getValue() == 2) {
                        if (sagTicks < 10) {
                            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                            sagTicks++;
                        }
                        if (sagTicks >= 10) {
                            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
                            sagTicks = 0;
                        }
                    }
                }
            }
            if (this.modeIndex() == 2) {
                if (mc.thePlayer.hurtTime == 10) {
                    blockTicks = 1;
                }

                if (blockTicks >= 1) {
                    Atlas.lagManager.setDelay(delayPacketTick.getValue());
                    KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                    blockTicks++;
                } else {
                    clearOwnDelay();
                }
                if (blockTicks > blockTick.getValue()) {
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
                    clearOwnDelay();
                    blockTicks = 0;
                }
            } else clearOwnDelay();

            if (this.modeIndex() == 3) {
                predict();
            }
            if (this.manualTicks > 0 && --this.manualTicks == 0) {
                KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            }
            if (this.modeIndex() == 4) {
                rhythm();
            } else if (this.rhythmBlocking) {
                rhythmBlock(false);
            }
            if (this.modeIndex() == 6 || this.modeIndex() == 10) {
                this.slinkyEngine.tick();
            } else if (this.slinkyEngine.active()) {
                this.slinkyEngine.reset(true);
            }
            if (this.modeIndex() == 9) {
                this.raven.tick();
            } else if (this.raven.active()) {
                this.raven.reset(true);
            }
        }
    }

    private static final double CLOSE_ANY_ANGLE = 1.5;

    net.minecraft.entity.player.EntityPlayer blockTarget(float range) {
        if (mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof net.minecraft.entity.player.EntityPlayer) {
            net.minecraft.entity.player.EntityPlayer hit =
                    (net.minecraft.entity.player.EntityPlayer) mc.objectMouseOver.entityHit;
            if (hostile(hit) && boxDistance(hit) <= range) {
                return hit;
            }
        }
        net.minecraft.entity.player.EntityPlayer best = null;
        double bestDistance = 15.0;
        for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
            if (!hostile(player)) {
                continue;
            }
            double distance = boxDistance(player);
            if (distance > bestDistance) {
                continue;
            }

            if (distance <= CLOSE_ANY_ANGLE) {
                bestDistance = distance;
                best = player;
                continue;
            }
            double dx = player.posX - mc.thePlayer.posX;
            double dy = player.posY - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
            double dz = player.posZ - mc.thePlayer.posZ;
            float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            float wantPitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
            float yaw = net.minecraft.util.MathHelper.wrapAngleTo180_float(wantYaw - mc.thePlayer.rotationYaw);
            float pitch = wantPitch - mc.thePlayer.rotationPitch;
            if (yaw * yaw + pitch * pitch > 4900.0F) {
                continue;
            }
            bestDistance = distance;
            best = player;
        }
        return best != null && bestDistance <= range ? best : null;
    }

    private static boolean hostile(net.minecraft.entity.player.EntityPlayer player) {
        return player != mc.thePlayer && !player.isDead && player.getHealth() > 0.0F && !player.isPlayerSleeping()
                && TargetFilter.accepts(player) && !atlas.util.TeamUtil.isFriend(player)
                && !atlas.util.TeamUtil.isSameTeam(player) && !atlas.util.TeamUtil.isBot(player);
    }

    static double boxDistance(net.minecraft.entity.Entity entity) {
        net.minecraft.util.AxisAlignedBB box = entity.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        double ex = mc.thePlayer.posX;
        double ey = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double ez = mc.thePlayer.posZ;
        double x = Math.max(box.minX, Math.min(box.maxX, ex)) - ex;
        double y = Math.max(box.minY, Math.min(box.maxY, ey)) - ey;
        double z = Math.max(box.minZ, Math.min(box.maxZ, ez)) - ez;
        return Math.sqrt(x * x + y * y + z * z);
    }

    @EventTarget
    public void onSlinkyRightClick(atlas.events.RightClickMouseEvent event) {
        if (this.isRunning() && (this.slinkyEngine.lagging() || this.raven.lagging() || this.modeIndex() == 9 && this.raven.cancelsUse())
                && mc.currentScreen == null && ItemUtil.isHoldingSword()) {
            event.setCancelled(true);
        }
    }

    @EventTarget(atlas.event.types.Priority.HIGHEST)
    public void onRavenPacket(atlas.events.PacketEvent event) {
        if (this.modeIndex() == 9 || this.raven.lagging()) {
            this.raven.onSend(event);
        }
        if (this.modeIndex() == 6 || this.modeIndex() == 10 || this.slinkyEngine.lagging()) {
            this.slinkyEngine.onSend(event);
        }
    }

    private void predict() {
        if (!ItemUtil.isHoldingSword()) {
            stopPredict();
            return;
        }
        if (this.predictTicks > 0) {
            this.predictTicks--;
            if (this.predictTicks <= 0) {
                KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            } else {
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
            }
            return;
        }
        if (incomingSwing() == null) {
            return;
        }
        if (Math.random() * 100.0 > this.predictChance.getValue()) {
            return;
        }
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        this.predictTicks = this.predictHold.getValue();
    }

    private void rhythm() {
        long now = System.currentTimeMillis();
        int hurt = mc.thePlayer.hurtTime;
        if (hurt > this.lastOwnHurt) {
            recordDamage(now);
            this.holdUntil = 0L;
            this.rhythmSpent = false;
        }
        this.lastOwnHurt = hurt;
        if (!ItemUtil.isHoldingSword() || !opponentNear()) {
            rhythmBlock(false);
            return;
        }

        long early = this.rhythmEarly.getValue()
                + (this.rhythmPing.getValue() && atlas.management.HitTimer.get() != null
                ? atlas.management.HitTimer.get().averageDelay() / 2L : 0L) + 50L;
        int earlyTicks = (int) (early / 50L);
        int immune = mc.thePlayer.hurtResistantTime;
        boolean inWindow = immune <= DAMAGEABLE + earlyTicks;
        boolean want;
        if (this.intervalCount >= 3 && this.lastDamageAt > 0L) {
            long due = this.lastDamageAt + averageInterval();
            want = inWindow && now >= due - early && now <= due + this.rhythmHold.getValue() * 50L;
        } else if (immune > DAMAGEABLE) {
            this.holdUntil = 0L;
            want = inWindow;
        } else if (this.rhythmBlocking) {
            if (this.holdUntil == 0L) {
                this.holdUntil = now + this.rhythmHold.getValue() * 50L;
            }
            want = now < this.holdUntil;
        } else {
            want = false;
        }
        if (want && canLandHit()) {
            want = false;
        }
        if (this.rhythmSpent) {
            want = false;
        } else if (want && this.rhythmBlocking && ++this.rhythmHeld >= this.rhythmMax.getValue()) {
            want = false;
            this.rhythmSpent = true;
        }
        rhythmBlock(want);
    }

    private boolean canLandHit() {
        int code = mc.gameSettings.keyBindAttack.getKeyCode();
        boolean held = code < 0 ? org.lwjgl.input.Mouse.isButtonDown(code + 100)
                : org.lwjgl.input.Keyboard.isKeyDown(code);
        if (!held || mc.objectMouseOver == null
                || !(mc.objectMouseOver.entityHit instanceof EntityLivingBase)) {
            return false;
        }
        return atlas.management.HitTimer.canHurt((EntityLivingBase) mc.objectMouseOver.entityHit);
    }

    private void recordDamage(long now) {
        if (this.lastDamageAt > 0L) {
            long interval = now - this.lastDamageAt;
            if (interval >= 250L && interval <= 1500L) {
                this.damageIntervals[this.nextInterval] = interval;
                this.nextInterval = (this.nextInterval + 1) % this.damageIntervals.length;
                this.intervalCount = Math.min(this.damageIntervals.length, this.intervalCount + 1);
            } else {
                this.intervalCount = 0;
                this.nextInterval = 0;
            }
        }
        this.lastDamageAt = now;
    }

    private long averageInterval() {
        long sum = 0L;
        for (int i = 0; i < this.intervalCount; i++) {
            sum += this.damageIntervals[i];
        }
        return this.intervalCount == 0 ? 0L : sum / this.intervalCount;
    }

    private boolean opponentNear() {
        for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.deathTime > 0) {
                continue;
            }
            if (atlas.util.TeamUtil.isFriend(player) || atlas.util.TeamUtil.isSameTeam(player)
                    || atlas.util.TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) <= 6.0F) {
                return true;
            }
        }
        return false;
    }

    private void rhythmBlock(boolean block) {
        if (block == this.rhythmBlocking) {
            return;
        }
        this.rhythmBlocking = block;
        this.rhythmHeld = 0;
        if (block) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        } else {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
    }

    private void stopPredict() {
        if (this.predictTicks > 0) {
            this.predictTicks = 0;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
    }

    private net.minecraft.entity.player.EntityPlayer incomingSwing() {
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof net.minecraft.entity.player.EntityPlayer)) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) object;
            if (player == mc.thePlayer || player.isDead || player.deathTime > 0) {
                continue;
            }
            if (!TargetFilter.accepts(player)) {
                continue;
            }
            if (atlas.util.TeamUtil.isFriend(player) || atlas.util.TeamUtil.isSameTeam(player)
                    || atlas.util.TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) > this.predictRange.getValue()) {
                continue;
            }
            if (!(player.swingProgress > 0.0F && player.prevSwingProgress == 0.0F)) {
                continue;
            }
            if (angleToMe(player) > this.predictAngle.getValue() / 2.0) {
                continue;
            }
            return player;
        }
        return null;
    }

    private double angleToMe(net.minecraft.entity.player.EntityPlayer player) {
        double dx = mc.thePlayer.posX - player.posX;
        double dz = mc.thePlayer.posZ - player.posZ;
        double wanted = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        return Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_double(
                wanted - player.rotationYaw));
    }

    private void manualBlock() {
        if (this.manualTicks > 0 || mc.thePlayer.isUsingItem() || mc.currentScreen != null) {
            return;
        }
        if (auraBlocksAlone()) {
            return;
        }
        if (Math.random() * 100.0 >= this.manualChance.getValue()) {
            return;
        }
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        this.manualTicks = 1;
    }

    private void reset() {
        attacking = false;
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        holdTicks = sagTicks = 0;
        timer.reset();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isRunning() && ItemUtil.isHoldingSword() && event.getTarget() instanceof EntityLivingBase) {
            attacking = true;
            attackTicks = 0;
            target = (EntityLivingBase) event.getTarget();
            if (this.modeIndex() == 7) {
                this.fullBlock.attacked();
            }
            if (this.modeIndex() == 5) {
                manualBlock();
            }
            if (this.modeIndex() == 6 || this.modeIndex() == 10) {
                this.slinkyEngine.onAttack(event);
            }
            if (this.modeIndex() == 8 && !auraBlocksAlone()) {
                this.yuri.hit(true, mc.thePlayer.getDistanceToEntity(event.getTarget()), this.yRange.getValue(),
                        Math.random(), this.yChance.getValue(), System.currentTimeMillis());
            }
        }
    }

    @EventTarget
    public void onYuriUpdate(atlas.events.UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.isRunning() || this.modeIndex() != 8 || !mc.inGameHasFocus || auraBlocksAlone()) {
            if (this.yuri.blocking) {
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            }
            this.yuri.reset();
            return;
        }
        boolean actions = atlas.management.TickActions.any(atlas.management.TickActions.SLOT
                | atlas.management.TickActions.ATTACK | atlas.management.TickActions.SWING
                | atlas.management.TickActions.USE | atlas.management.TickActions.DIG
                | atlas.management.TickActions.INVENTORY);
        BlockHitYuri.Act act = this.yuri.tick(System.currentTimeMillis(), actions,
                mc.gameSettings.keyBindForward.isKeyDown(), this.yComboDelay.getValue(), this.yBlockDelay.getValue());
        if (act == BlockHitYuri.Act.PRESS) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        } else if (act == BlockHitYuri.Act.RELEASE) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    public final atlas.module.ModuleBundle autoBlockMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.AuraAutoBlock(), "auto-block", "Aura auto block", false);

    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.target = null;
    }
}
