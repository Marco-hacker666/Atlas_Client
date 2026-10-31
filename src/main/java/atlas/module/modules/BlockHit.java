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
    /* 2026-10-09 (Vape 4.21's BlockHit modes): "Rhythm" is shown as Predict
       -- it is the port of Vape's Predict -- and the old swing-watching
       Predict, decorative at high ping by its own note, is hidden as "Swing".
       Manual is new. Indices are unchanged (3 = swing, 4 = rhythm). */
    /* 2026-10-19: "Slinky" added and made the default (owner: "fix my
       AutoBlock the way Slinky does it"); the module is shown as AutoBlock.
       Saved configs keep the mode they name. */
    private final ModeProperty mode = new ModeProperty("Mode", 6,
            new String[]{"Helper", "Auto", "Lag", "Swing", "Predict", "Manual", "Slinky", "Full", "Yuri", "Raven"})
            .hide("Swing")
            /* 2026-10-31 (owner's consolidation): Auto, Lag, Swing and Predict retired; a config naming one gets Full. */
            .retire("Full", "Auto", "Lag", "Swing", "Predict")
            .alias("Rhythm", "Full");
    /* ---- Slinky (Slinky's Auto Block, rebuilt here from what it does) ----
       While the attack key is held and an enemy is within block-range, the
       use key goes down; after max-hold-ticks it comes up for a tick, which
       is the tick the next hit lands (1.8 drops attack clicks while the
       sword is up), then it goes down again. With require-damage it only
       goes up on the tick your hurt time reaches max-hurt-time (just before
       the next hit can land) and comes down when you are hit again. Holding
       right click alone blocks as usual. Optionally, on lowering, every
       outgoing packet is held for lag-ticks (lag-chance of the time), and
       let go the moment you attack. */
    private final java.util.function.BooleanSupplier slinky = () -> this.modeIndex() == 6;
    /* ---- Yuri (2026-10-24, BlockHitYuri): after your hit, the use key down
       combo-delay ms later (forward held), up block-delay ms after that. */
    private final java.util.function.BooleanSupplier yuriMode = () -> this.modeIndex() == 8;
    private final FloatProperty yRange = new FloatProperty("yuri-range", 3.0F, 1.0F, 6.0F, yuriMode);
    private final PercentProperty yChance = new PercentProperty("yuri-chance", 100, yuriMode);
    private final IntProperty yBlockDelay = new IntProperty("yuri-block-delay", 30, 1, 300, yuriMode);
    private final IntProperty yComboDelay = new IntProperty("yuri-combo-delay", 30, 1, 300, yuriMode);
    private final BlockHitYuri yuri = new BlockHitYuri();
    private final FloatProperty sRange = new FloatProperty("block-range", 3.1F, 2.0F, 6.0F, slinky);
    private final IntProperty sHold = new IntProperty("max-hold-ticks", 5, 1, 10, slinky);
    private final BooleanProperty sRequireLeft = new BooleanProperty("require-left-click", true, slinky);
    private final BooleanProperty sRequireRight = new BooleanProperty("require-right-click", false, slinky);
    private final BooleanProperty sRequireDamage = new BooleanProperty("require-damage", false, slinky);
    private final IntProperty sHurt = new IntProperty("max-hurt-time", 6, 1, 10,
            () -> slinky.getAsBoolean() && this.sRequireDamage.getValue());
    private final BooleanProperty sFake = new BooleanProperty("fake-animation", false, slinky);
    private final BooleanProperty sFakeInRange = new BooleanProperty("fake-animation-in-range", false,
            () -> slinky.getAsBoolean() && this.sFake.getValue());
    private final PercentProperty sLagChance = new PercentProperty("lag-chance", 0, slinky);
    private final IntProperty sLagTicks = new IntProperty("lag-ticks", 2, 1, 10,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);
    private final BooleanProperty sLagFlush = new BooleanProperty("lag-flush-on-attack", true,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);
    private final BooleanProperty sLagReblock = new BooleanProperty("lag-reblock", false,
            () -> slinky.getAsBoolean() && this.sLagChance.getValue() > 0);
    /* ---- Raven (2026-10-31, owner: "make me Raven's auto block"): Raven bS's Autoblock with its settings and
       defaults, see AutoBlockRaven. Right click held: block-hits while attacking an enemy in range, a plain block
       otherwise. Defaults for the owner (Pika/Polar, ~220 ms ping), not Raven's: range 3.5 (Raven 4.0), max-hold
       100 ms (150: a hit every 3 ticks, not 4), max-hurt-time 300 ms (200: up a tick earlier for the ping), lag-chance
       0 (100: every outgoing packet held is a pattern Polar can see; raise it to try). */
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
    private final AutoBlockRaven raven = new AutoBlockRaven(this);
    /* ---- Full (2026-10-21): sword up whenever an enemy is in range, every hit swapped in within
       one tick (release, hit, block again), so the server has it up for every hit taken. See AutoBlockFull. */
    private final java.util.function.BooleanSupplier full = () -> this.modeIndex() == 7;
    final FloatProperty fullRange = new FloatProperty("full-range", 3.5F, 2.0F, 6.0F, full);
    /* Second version (2026-10-21): vanilla block-hit order only, and the sword up only when it protects
       (windows before the next hit, guard, in the air). full-blink is gone (Grim PacketOrderI / NoSlow). */
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
    /* Defaults from the owner's 10-21 Pika games (2.6.1): guard and air off -- a raised sword ends sprinting,
       so blocking in the air cost the sprint on landing, and guard kept it up for a third of every approach. */
    final BooleanProperty fullLog = new BooleanProperty("full-log", true, full);
    private final AutoBlockFull fullBlock = new AutoBlockFull(this);
    private static AutoBlockFull fullInstance;
    private boolean sBlocking;
    private boolean sManual;
    private boolean sAnimation;
    private boolean sLagging;
    private boolean sActive;
    private int sTick;
    private int sBlockStart = -1;
    private int sLagStart = -1;
    private int sLastHurt;
    /* ---- Manual (Vape's Manual) ------------------------------------------
       Block hits paced by this player's own clicks: after a hit with a sword,
       ManualChance of the time the use key goes down for one tick (Vape: 50
       ms), as a player tapping right click after left click. Blocks per
       second = CPS x chance. Does nothing while KillAura blocks by itself. */
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
    /* ---- Predict --------------------------------------------------------

       The other three modes all block because this player swung. This one
       blocks because somebody else did, which is the only one of the four
       that can put the shield up before the damage rather than after it.

       The signal is the start of an opponent's swing animation, taken the
       same way the anticheat package already takes it when it is looking for
       other people's autoblock: swingProgress has just left zero. That is one
       tick wide and it is the earliest warning this client gets.

       BE HONEST ABOUT WHAT IT IS WORTH AT HIGH PING. The swing animation
       reaches us by way of the server, so by the time it arrives the attacker
       has already been swinging for their latency plus ours. At two hundred
       milliseconds each way the hit is usually resolved server side before
       the animation is drawn here, and blocking afterwards protects nothing.
       Predict is worth having on a good connection and is close to decorative
       on a bad one; it is not a setting that turns two hundred and twenty
       millisecond ping into an advantage. */

    private final FloatProperty predictRange = new FloatProperty("PredictRange", 4.0f, 2.0f, 8.0f,
            () -> this.modeIndex() == 3);
    /** Ticks the block is held once a swing has been seen. */
    private final IntProperty predictHold = new IntProperty("PredictHold", 4, 1, 12,
            () -> this.modeIndex() == 3);
    /**
     * How far off from facing this player an attacker may be and still count.
     *
     * A swing is not evidence of an attack on anyone in particular -- people
     * mine, break beds and swing at air constantly, and blocking for every one
     * of them is both useless and a very legible pattern. The cone is measured
     * from the attacker's yaw toward this player.
     */
    private final IntProperty predictAngle = new IntProperty("PredictAngle", 70, 10, 180,
            () -> this.modeIndex() == 3);
    /**
     * Chance of reacting to any given swing.
     *
     * Reacting to every single one is the tell. A hand misses some.
     */
    private final PercentProperty predictChance = new PercentProperty("PredictChance", 80,
            () -> this.modeIndex() == 3);

    private int predictTicks = 0;

    /* ---- Rhythm ---------------------------------------------------------

       After Vape's Predict (studied 2026-09-25), written here from what it
       does. Where Predict above waits to see an opponent swing -- too late at
       this ping, as its own note says -- this one never looks at the
       opponent. It reads this player's own damage.

       A hit makes this player immune for ten ticks: hurtResistantTime goes to
       twenty and damage lands again only once it is back to ten. That is
       server time, known here the moment the hurt arrives, and an opponent
       trading hits hits again as soon as it allows. So the block goes up in
       the last few ticks before this player can be hurt again -- early by
       RhythmEarly, plus the measured round trip (HitTimer) so it is up on the
       server in time -- and stays RhythmHold ticks past it if nothing
       arrives. Once three or more hits have come at a steady interval
       (250-1500 ms apart), the block is timed to that interval instead:
       from just before the next one is due to RhythmHold ticks after.

       Only with a sword and an opponent within six blocks. A 1.8 block
       halves the damage; it also stops attacks while it is up, which is the
       price. */
    private final IntProperty rhythmEarly = new IntProperty("RhythmEarly", 50, 0, 500,
            () -> this.modeIndex() == 4);
    private final BooleanProperty rhythmPing = new BooleanProperty("RhythmPing", true,
            () -> this.modeIndex() == 4);
    private final IntProperty rhythmHold = new IntProperty("RhythmHold", 1, 0, 10,
            () -> this.modeIndex() == 4);
    /* The longest one block may stay up, in ticks; after that it comes down
       and stays down until the next hit. At 230 ms the first version held
       about eight ticks a round -- 0.4 s every half second with no attacks
       -- and felt like it (2026-09-25). */
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
    } //67:D

    /** Full has the use key: AutoClicker's clicks wait while the sword is up. */
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

    /**
     * Ends the delay only if this module set it (2026-10-19). It used to set
     * LagManager's delay to 0 on every tick in every mode but Lag, so with
     * AutoBlock on, LagRange's delay was wiped each tick and LagRange did
     * nothing.
     */
    private void clearOwnDelay() {
        if ("BlockHit".equals(Atlas.lagManager.holder()) && Atlas.lagManager.getDelay() != 0) {
            Atlas.lagManager.setDelay(0);
        }
    }

    /* 2026-10-26 (owner: "add my AutoBlock modes to Silent Aura"): Silent Aura's BlockMode can name one of this
       module's modes. The module then runs that mode for the aura -- switched on or not, with its own settings --
       and while it does, that mode wins over the module's own Mode. -1 when no aura is driving it. */
    private int drivenMode = -1;

    /** The mode in effect: the one Silent Aura drives, else this module's own. */
    private int modeIndex() {
        return this.drivenMode >= 0 ? this.drivenMode : this.mode.getValue();
    }

    /** Runs while switched on, or while Silent Aura drives a mode through it (EventManager asks this). */
    @Override
    public boolean isRunning() {
        if (this.drivenMode < 0 && KillAuraLB.rhythmRuns()) {
            /* 2026-10-27: Silent Aura's Rhythm has the use key and the sword block; this module, switched on by
               itself, would raise and lower the same sword against it. */
            return false;
        }
        return super.isRunning() || this.drivenMode >= 0 && mc.thePlayer != null;
    }

    /**
     * Silent Aura runs {@code modeIndex} (an index into Mode) through this
     * module; -1 stops. A change lets go of whatever the old mode held (the
     * use key, a lag, a predicted block) before the new one starts.
     */
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

    /** The mode index Silent Aura drives, or -1. */
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
        /* Switched off mid-block would otherwise leave the use key held down
           by this module with nothing left to release it. */
        stopPredict();
        if (this.rhythmBlocking) {
            rhythmBlock(false);
        }
        this.intervalCount = 0;
        this.lastDamageAt = 0L;
        slinkyReset(true);
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
                /* BlockHitChance is a percentage (0-100) but Math.random()
                   returns 0..1, so the original comparison could never be
                   true for any chance above 0 -- the roll never skipped, and
                   at 0 it skipped every time. Scale the roll to match. */
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
                /* The delay used to be re-applied on every tick while only
                   ever cleared at the end of a block window, so outside combat
                   it was never cleared at all: selecting this mode held every
                   outgoing packet back permanently. Confine it to the window
                   it is meant to cover. */
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
                /* Back to whatever the player is really holding. */
                KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
            }
            if (this.modeIndex() == 4) {
                rhythm();
            } else if (this.rhythmBlocking) {
                rhythmBlock(false);
            }
            if (this.modeIndex() == 6) {
                slinkyTick();
            } else if (this.sActive) {
                slinkyReset(true);
            }
            if (this.modeIndex() == 9) {
                this.raven.tick();
            } else if (this.raven.active()) {
                this.raven.reset(true);
            }
        }
    }

    // ---- Slinky ------------------------------------------------------------

    private static boolean physical(net.minecraft.client.settings.KeyBinding key) {
        return KeyBindUtil.isKeyDown(key.getKeyCode());
    }

    /** The enemy to block against: the one under the crosshair, else the nearest in view. */
    private net.minecraft.entity.player.EntityPlayer slinkyTarget() {
        return blockTarget(this.sRange.getValue());
    }

    /** Within this box distance an enemy counts whatever the view angle. */
    private static final double CLOSE_ANY_ANGLE = 1.5;

    /** The enemy within range: the one under the crosshair, else the nearest within 70 degrees of the view (any angle when close). */
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
            /* Hugging: the angle to the feet is 60-70 degrees down, outside the cone, and Full let go while the
               enemy was closest (10-30 19:22, "no enemy in range" at 0.34 and 0.64). Anyone this close can hit. */
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

    /** Eyes to the nearest point of the box (grown by 0.1), as Slinky measures. */
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

    private void slinkyTick() {
        this.sActive = true;
        if (mc.currentScreen != null || mc.thePlayer.isDead) {
            slinkyReset(true);
            return;
        }
        int hurt = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurt > this.sLastHurt;
        this.sLastHurt = hurt;
        if (!ItemUtil.isHoldingSword() || atlas.util.Auras.blocksByItself()) {
            /* No sword, or the aura is blocking by itself: hands off. */
            slinkyReset(false);
            return;
        }
        int tick = ++this.sTick;
        boolean right = physical(mc.gameSettings.keyBindUseItem);
        boolean left = physical(mc.gameSettings.keyBindAttack);
        if (!left && this.sRequireLeft.getValue()) {
            if (!right) {
                slinkyReset(true);
            } else {
                /* Right click alone: a plain block. */
                if (this.sLagging) {
                    slinkyReleaseLag();
                }
                if (!this.sBlocking) {
                    slinkyStart(tick);
                    this.sManual = true;
                }
            }
            slinkyAnimation();
            return;
        }
        if (this.sManual) {
            slinkyStop(true);
            this.sManual = false;
        }
        boolean wanted = slinkyTarget() != null && (!this.sRequireRight.getValue() || right);
        if (this.sLagging) {
            int lagTicks = this.sLagTicks.getValue();
            boolean expired = this.sLagStart >= 0 && tick - this.sLagStart >= lagTicks;
            if (expired || !wanted) {
                slinkyReleaseLag();
                if (expired && this.sLagReblock.getValue() && wanted) {
                    slinkyStart(tick);
                }
            }
        }
        if (!wanted) {
            slinkyStop(true);
        } else {
            if (!this.sBlocking && !this.sLagging
                    && (!this.sRequireDamage.getValue() || hurt == this.sHurt.getValue())) {
                slinkyStart(tick);
            }
            if (this.sBlocking) {
                int hold = this.sHold.getValue();
                boolean stop = this.sBlockStart >= 0 && tick - this.sBlockStart >= hold;
                if (this.sRequireDamage.getValue() && hurtAgain) {
                    stop = true;
                }
                if (stop) {
                    if (this.sLagChance.getValue() > 0 && Math.random() * 100.0 < this.sLagChance.getValue()) {
                        slinkyStartLag(tick);
                    }
                    slinkyStop(true);
                }
            }
        }
        slinkyAnimation();
    }

    private void slinkyStart(int tick) {
        if (!ItemUtil.isHoldingSword()) {
            return;
        }
        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBindUtil.setKeyBindState(key, true);
        net.minecraft.client.settings.KeyBinding.onTick(key);
        this.sBlocking = true;
        this.sBlockStart = tick;
    }

    private void slinkyStop(boolean force) {
        if (this.sBlocking || force) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            this.sBlocking = false;
            this.sBlockStart = -1;
        }
    }

    private void slinkyStartLag(int tick) {
        if (this.sLagging) {
            return;
        }
        if (Atlas.blinkManager.setBlinkState(true, atlas.enums.BlinkModules.AUTO_BLOCK_LAG)) {
            this.sLagging = true;
            this.sLagStart = tick;
        }
    }

    private void slinkyReleaseLag() {
        if (this.sLagging) {
            Atlas.blinkManager.setBlinkState(false, atlas.enums.BlinkModules.AUTO_BLOCK_LAG);
        }
        this.sLagging = false;
        this.sLagStart = -1;
    }

    /** The block animation on screen while blocking or lagging, without using the item. */
    private void slinkyAnimation() {
        if (!this.sFake.getValue() || !ItemUtil.isHoldingSword()) {
            slinkyAnimationOff();
            return;
        }
        boolean show = this.sBlocking || this.sLagging;
        if (show && this.sFakeInRange.getValue()) {
            show = slinkyTarget() != null;
        }
        if (show) {
            ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(72000);
            this.sAnimation = true;
        } else {
            slinkyAnimationOff();
        }
    }

    private void slinkyAnimationOff() {
        if (this.sAnimation && mc.thePlayer != null) {
            if (!mc.thePlayer.isUsingItem()) {
                ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(0);
            }
            this.sAnimation = false;
        }
    }

    /** Lowers the sword, lets any held packets go, and gives the use key back to the player. */
    private void slinkyReset(boolean releaseKey) {
        slinkyReleaseLag();
        if (mc.gameSettings == null) {
            this.sBlocking = this.sManual = false;
            return;
        }
        slinkyStop(releaseKey);
        this.sManual = false;
        slinkyAnimationOff();
        if (releaseKey && mc.currentScreen == null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        this.sLastHurt = 0;
        this.sActive = false;
    }

    /** lag-flush-on-attack: an attack lets the held packets go first, so it is not delayed. */
    @EventTarget(atlas.event.types.Priority.HIGHEST)
    public void onSlinkyPacket(atlas.events.PacketEvent event) {
        if (!this.sLagging || event.getType() != EventType.SEND || !this.sLagFlush.getValue()) {
            return;
        }
        if (event.getPacket() instanceof net.minecraft.network.play.client.C02PacketUseEntity
                && ((net.minecraft.network.play.client.C02PacketUseEntity) event.getPacket()).getAction()
                == net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK) {
            slinkyReleaseLag();
            if (this.sLagReblock.getValue() && ItemUtil.isHoldingSword()) {
                slinkyStart(this.sTick);
            }
        }
    }

    /** While held packets wait, a right click must not raise the sword on its own. */
    @EventTarget
    public void onSlinkyRightClick(atlas.events.RightClickMouseEvent event) {
        if (this.isRunning() && (this.sLagging || this.raven.lagging()) && mc.currentScreen == null
                && ItemUtil.isHoldingSword()) {
            event.setCancelled(true);
        }
    }

    /** Raven: an attack lets the held packets go first, and the sword goes straight back up. */
    @EventTarget(atlas.event.types.Priority.HIGHEST)
    public void onRavenPacket(atlas.events.PacketEvent event) {
        if (this.modeIndex() == 9 || this.raven.lagging()) {
            this.raven.onSend(event);
        }
    }

    /**
     * Blocks in response to somebody else starting a swing.
     *
     * A commitment, once made, runs to the end of its window without being
     * re-decided: a block that flickers on and off as the animation flag
     * moves is both useless against the hit and a pattern nothing else
     * produces.
     */
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
        /* One way, not the round trip: the block only has to reach the
           server before the next hit does. */
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
            /* Damageable now and nothing has come: hold a little, then drop. */
            if (this.holdUntil == 0L) {
                this.holdUntil = now + this.rhythmHold.getValue() * 50L;
            }
            want = now < this.holdUntil;
        } else {
            want = false;
        }
        if (want && canLandHit()) {
            /* A block stops attacks. With the attack held on someone who can
               be hurt right now, the hit is worth more than the block; the
               block is only for the ticks a swing would be wasted anyway
               (2026-09-25: "can't attack while it blocks" was the complaint). */
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

    /** Attack held, crosshair on a living target, and that target can be hurt by a hit sent now. */
    private boolean canLandHit() {
        /* The physical button, not the key binding: AutoClicker flips the
           binding off and on every click. */
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
                /* A gap or a double: the rhythm has broken. */
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

    /**
     * The nearest opponent who has just begun a swing aimed roughly this way.
     *
     * swingProgress leaving zero is true for exactly one tick, which is what
     * makes it usable as an edge rather than a state.
     */
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

    /** How far the attacker's yaw is from pointing at this player, in degrees. */
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
        if (atlas.util.Auras.blocksByItself()) {
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
        /* Hitting a boat, minecart or item frame with a sword used to throw a
           ClassCastException here. */
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
            if (this.modeIndex() == 8) {
                this.yuri.hit(true, mc.thePlayer.getDistanceToEntity(event.getTarget()), this.yRange.getValue(),
                        Math.random(), this.yChance.getValue(), System.currentTimeMillis());
            }
        }
    }

    /* Yuri: after this tick's input, as Yuri's motion event, so the tick's own clicks count as actions. */
    @EventTarget
    public void onYuriUpdate(atlas.events.UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.isRunning() || this.modeIndex() != 8 || !mc.inGameHasFocus) {
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

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final atlas.module.ModuleBundle autoBlockMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.AuraAutoBlock(), "auto-block", "Aura auto block", false);

    /* 2026-10-31 (audit): a world unload leaves its entities alive (isDead stays false), and one kept here held the
       whole old world in memory -- for good while this module was off. Dropped when a world loads. */
    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.target = null;
    }
}
