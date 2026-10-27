package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LeftClickMouseEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.util.*;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Objects;

public class AutoClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean clickPending = false;
    private long clickDelay = 0L;
    private boolean blockHitPending = false;
    private long blockHitDelay = 0L;
    public final IntProperty minCPS = new IntProperty("min-cps", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-cps", 12, 1, 20);
    public final BooleanProperty blockHit = new BooleanProperty("block-hit", false);
    public final FloatProperty blockHitTicks = new FloatProperty("block-hit-ticks", 1.5F, 1.0F, 20.0F, this.blockHit::getValue);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
    public final BooleanProperty breakBlocks = new BooleanProperty("break-blocks", true);
    public final FloatProperty range = new FloatProperty("range", 3.0F, 3.0F, 8.0F, this.breakBlocks::getValue);
    public final FloatProperty hitBoxVertical = new FloatProperty("hit-box-vertical", 0.1F, 0.0F, 1.0F, this.breakBlocks::getValue);
    public final FloatProperty hitBoxHorizontal = new FloatProperty("hit-box-horizontal", 0.2F, 0.0F, 1.0F, this.breakBlocks::getValue);
    public final ModeProperty clickMode = new ModeProperty("click-mode", 0, new String[]{"Normal", "LowCPS", "Record", "DragClick"});
    /* Human (2026-10-25, HumanClicks): a mean-reverting CPS walk, log-normal gaps, cadence repeats, hesitations. */
    public final ModeProperty randomization = new ModeProperty("randomization", 3, new String[]{"Normal", "Extra", "Extra+", "Human"});
    private final atlas.util.HumanClicks humanClicks = new atlas.util.HumanClicks();
    /* 2026-10-26 (review: apply-autoclicker was never read): Humanization's click-drop and click-double. The
       player aims, so no hit-rate verdict here. */
    private final atlas.humanize.Humanizer humanizer = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_AUTOCLICKER);
    public final IntProperty lowMaxCPS = new IntProperty("low-max-cps", 6, 1, 20, () -> this.clickMode.getValue() == 1);
    public final BooleanProperty jitter = new BooleanProperty("jitter", false);
    public final FloatProperty jitterStrength = new FloatProperty("jitter-strength", 0.3F, 0.0F, 2.0F, this.jitter::getValue);
    public final PercentProperty breakChance = new PercentProperty("break-chance", 100);
    public final BooleanProperty invFillPause = new BooleanProperty("inv-fill-pause", false);
    public final IntProperty invFillTicks = new IntProperty("inv-fill-ticks", 4, 0, 40, this.invFillPause::getValue);
    public final BooleanProperty clickSound = new BooleanProperty("click-sound", false);
    public final BooleanProperty doubleClick = new BooleanProperty("double-click", false);
    public final PercentProperty doubleChance = new PercentProperty("double-chance", 15, this.doubleClick::getValue);
    public final IntProperty doubleGapMs = new IntProperty("double-gap-ms", 45, 20, 100, this.doubleClick::getValue);
    public final BooleanProperty fatigue = new BooleanProperty("fatigue", false);
    public final FloatProperty fatigueFloor = new FloatProperty("fatigue-floor", 0.75F, 0.5F, 1.0F, this.fatigue::getValue);
    public final IntProperty fatigueSecs = new IntProperty("fatigue-secs", 120, 30, 600, this.fatigue::getValue);
    /* Runs the plain attack-key click cycle while a container GUI is open. It
       does not click inventory slots: the donor's inventory-fill behaviour
       (arm a start delay, then click the hovered slot) is not implemented, so
       the setting is named for what it does rather than for what it does not. */
    public final BooleanProperty guiAttack = new BooleanProperty("gui-attack", false);
    /* True for the tick in which this module pressed the attack key itself, so
       record mode can tell a synthesized click from a real one. */
    private boolean moduleClickThisTick = false;
    public final IntProperty startDelayMs = new IntProperty("start-delay-ms", 0, 0, 1000);
    private long enabledAtMs = 0L;
    private long doubleClickAtMs = 0L;
    private long fightStartMs = 0L;
    private long lastClickMs = 0L;
    private final long[] recordGaps = new long[20];
    private int recordCount = 0;
    private int recordPos = 0;
    private long lastRealClick = 0L;
    private long guiClosedAt = 0L;
    private long driftStartMs = 0L;
    private int clicksSinceBreak = 0;
    private long breakUntilMs = 0L;
    private int breakEveryClicks = 60;

    private double gaussian() {
        return (Math.random() + Math.random() + Math.random() - 1.5) * 2.0;
    }

    private long getNextClickDelay() {
        long base;
        if (this.clickMode.getValue() == 1) {
            // Ported from MiauMinus ghost AutoClicker.java (low CPS clamp).
            int max = Math.min(this.maxCPS.getValue(), this.lowMaxCPS.getValue());
            int min = Math.min(this.minCPS.getValue(), max);
            base = 1000L / RandomUtil.nextLong(min, max);
        } else if (this.clickMode.getValue() == 2 && this.recordCount > 4) {
            // Ported from raven RecordAutoClicker.java (replay sampled click rhythm).
            base = this.recordGaps[RandomUtil.nextInt(0, this.recordCount - 1)];
        } else if (this.clickMode.getValue() == 3) {
            int top = Math.max(this.minCPS.getValue(), this.maxCPS.getValue());
            base = 1000L / RandomUtil.nextLong(top, top + 8);
        } else if (this.randomization.getValue() == 3) {
            /* Human shapes the whole gap; fatigue still sags it on a long fight. */
            long gap = this.humanClicks.next(this.minCPS.getValue(), this.maxCPS.getValue(), System.currentTimeMillis());
            return (long) ((double) gap / this.fatigueMultiplier());
        } else {
            base = 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
        }
        return this.applyRandomization(base);
    }

    // Fatigue sags effective CPS the longer a fight runs, then recovers
    // after 5s without clicks. Emulation only, like the tiers above.
    private double fatigueMultiplier() {
        if (!this.fatigue.getValue()) {
            return 1.0;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastClickMs > 5000L) {
            this.fightStartMs = now;
            return 1.0;
        }
        if (this.fightStartMs == 0L) {
            this.fightStartMs = now;
            return 1.0;
        }
        double elapsedSecs = (double) (now - this.fightStartMs) / 1000.0;
        double sag = elapsedSecs / (double) this.fatigueSecs.getValue();
        if (sag > 1.0) {
            sag = 1.0;
        }
        return 1.0 - sag * (1.0 - (double) this.fatigueFloor.getValue());
    }

    // Humanization layer only. Normal returns the base untouched so old
    // behavior is identical. This imitates human timing, it proves nothing
    // against model-based detection.
    private long applyRandomization(long base) {
        if (this.randomization.getValue() == 0) {
            return (long) ((double) base / this.fatigueMultiplier());
        }
        long now = System.currentTimeMillis();
        if (this.driftStartMs == 0L) {
            this.driftStartMs = now;
            this.breakEveryClicks = 40 + (int) (Math.random() * 40);
        }
        if (this.randomization.getValue() >= 2) {
            if (now < this.breakUntilMs) {
                return Math.max(base, this.breakUntilMs - now);
            }
            this.clicksSinceBreak++;
            if (this.clicksSinceBreak >= this.breakEveryClicks) {
                this.clicksSinceBreak = 0;
                this.breakEveryClicks = 40 + (int) (Math.random() * 40);
                long pause = 400L + (long) (Math.random() * 800L);
                this.breakUntilMs = now + pause;
                return Math.max(base, pause);
            }
            double drift = 1.0 + 0.15 * Math.sin((double) (now - this.driftStartMs) / 30000.0);
            base = Math.max(25L, (long) ((double) base * drift));
        }
        double stdDev = this.randomization.getValue() >= 2 ? 0.15 : 0.12;
        long shaped = (long) ((double) base * (1.0 + this.gaussian() * stdDev));
        if (this.randomization.getValue() >= 1 && Math.random() < 0.08) {
            shaped += 100L + (long) (Math.random() * 150L);
        }
        return Math.max(25L, shaped);
    }

    private long getBlockHitDelay() {
        return (long) (50.0F * this.blockHitTicks.getValue());
    }

    private boolean isInvFillPaused() {
        // Ported from raven AutoClicker.java (inventory-fill pause).
        return this.invFillPause.getValue()
                && System.currentTimeMillis() - this.guiClosedAt < (long) this.invFillTicks.getValue() * 50L;
    }

    private void applyJitter() {
        // Ported from raven AutoClicker.java (jitter nudge after clicks).
        if (this.jitter.getValue() && mc.thePlayer != null) {
            float j = this.jitterStrength.getValue();
            mc.thePlayer.rotationYaw += RandomUtil.nextFloat(-j, j);
            mc.thePlayer.rotationPitch += RandomUtil.nextFloat(-j, j);
            if (mc.thePlayer.rotationPitch > 90.0F) {
                mc.thePlayer.rotationPitch = 90.0F;
            } else if (mc.thePlayer.rotationPitch < -90.0F) {
                mc.thePlayer.rotationPitch = -90.0F;
            }
        }
    }

    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    private boolean canClick() {
        if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (this.breakBlocks.getValue() && this.isBreakingBlock() && !this.hasValidTarget()) {
                GameType gameType12 = mc.playerController.getCurrentGameType();
                return gameType12 != GameType.SURVIVAL && gameType12 != GameType.CREATIVE;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /* 2026-10-20 (owner: "holding left click in front of an enemy, the
       clicker stops"). The crosshair sits on a block behind the enemy while
       the enemy is still just out of reach (ground behind them, or the feet
       when aiming low), and the old check only looked "range" far down the
       line, so it called it mining and stopped clicking. Now any enemy on the
       crosshair line in front of that block counts, however far. */
    /* 2026-10-25 (owner: "facing a block with someone behind it, holding the
       mouse never breaks the block"). The line was max(range, distance to the
       block), so with the block nearer than range a player BEHIND it was on
       the line too: "a target", clicking went on and every click restarted the
       break. On a block the line now ends at the block -- only an enemy in
       front of it counts, at any distance (the 10-20 case above still holds:
       there the block is behind the enemy). */
    private double rayLength() {
        if (this.isBreakingBlock() && mc.objectMouseOver.hitVec != null) {
            return mc.thePlayer.getPositionEyes(1.0F).distanceTo(mc.objectMouseOver.hitVec);
        }
        return this.range.getValue();
    }

    private boolean isValidTarget(EntityPlayer entityPlayer, double length) {
        if (entityPlayer != mc.thePlayer && entityPlayer != mc.thePlayer.ridingEntity) {
            if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityPlayer.deathTime > 0) {
                return false;
            } else {
                float borderSize = entityPlayer.getCollisionBorderSize();
                return RotationUtil.rayTrace(entityPlayer.getEntityBoundingBox().expand(
                        borderSize + this.hitBoxHorizontal.getValue(),
                        borderSize + this.hitBoxVertical.getValue(),
                        borderSize + this.hitBoxHorizontal.getValue()
                ), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, length) != null;
            }
        } else {
            return false;
        }
    }

    private boolean hasValidTarget() {
        double length = this.rayLength();
        return mc.theWorld
                .loadedEntityList
                .stream()
                .filter(e -> e instanceof EntityPlayer)
                .map(e -> (EntityPlayer) e)
                .anyMatch(p -> this.isValidTarget(p, length));
    }

    public AutoClicker() {
        super("AutoClicker", false, false, "Automatically clicks attack with random CPS and jitter.");
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            /* Cleared every tick: a click the module synthesizes lands in this
               same tick (vanilla turns the key press into clickMouse()), so
               the flag never outlives the tick it was set in. */
            this.moduleClickThisTick = false;
            if (this.clickDelay > 0L) {
                this.clickDelay -= 50L;
            }
            if (this.blockHitDelay > 0L) {
                this.blockHitDelay -= 50L;
            }
            // GUI attack: keep the normal attack-key click cycle running inside
            // container GUIs instead of stopping at the screen. Adapted from
            // raven inventory-fill, which arms a start delay then clicks the
            // hovered slot; the slot-clicking itself is not ported.
            boolean invMode = this.guiAttack.getValue() && mc.currentScreen instanceof GuiContainer;
            if (mc.currentScreen != null && !invMode) {
                this.clickPending = false;
                this.blockHitPending = false;
                this.guiClosedAt = System.currentTimeMillis();
            } else {
                if (this.clickPending) {
                    this.clickPending = false;
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
                }
                if (this.blockHitPending) {
                    this.blockHitPending = false;
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
                }
                // Start delay waits this long after toggle before the first click.
                // Adapted from raven inventory start-delay arming. Default 0 = off.
                if (this.isEnabled() && this.canClick() && mc.gameSettings.keyBindAttack.isKeyDown()
                        && System.currentTimeMillis() - this.enabledAtMs >= (long) this.startDelayMs.getValue()) {
                    if (!this.isInvFillPaused() && mc.thePlayer.isUsingItem() && BlockHit.fullManaging()) {
                        /* AutoBlock Full has the sword up: clicks wait, and do not pile up into a burst for
                           the tick it comes down (2026-10-21). */
                        this.clickDelay = Math.max(this.clickDelay, 0L);
                    }
                    if (!this.isInvFillPaused() && !mc.thePlayer.isUsingItem()) {
                        // Ported from raven ClickAssist.java (per-click chance gate).
                        if (this.breakChance.getValue() < 100 && RandomUtil.nextInt(1, 100) > this.breakChance.getValue()) {
                            this.clickDelay = this.clickDelay + this.getNextClickDelay();
                        } else {
                            while (this.clickDelay <= 0L) {
                                if (this.humanizer.dropClick()) {
                                    /* A press the finger did not make. */
                                    this.clickDelay = this.clickDelay + this.getNextClickDelay();
                                    continue;
                                }
                                this.clickPending = true;
                                this.moduleClickThisTick = true;
                                this.clickDelay = this.clickDelay + this.getNextClickDelay();
                                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
                                this.applyJitter();
                                this.lastClickMs = System.currentTimeMillis();
                                if (this.doubleClick.getValue()
                                        && RandomUtil.nextInt(1, 100) <= this.doubleChance.getValue()) {
                                    this.doubleClickAtMs = System.currentTimeMillis() + (long) this.doubleGapMs.getValue();
                                } else if (this.humanizer.doubleClick()) {
                                    this.doubleClickAtMs = System.currentTimeMillis() + RandomUtil.nextInt(18, 40);
                                }
                                if (this.clickSound.getValue()) {
                                    SoundUtil.playSound("random.click");
                                }
                            }
                        }
                        if (this.doubleClickAtMs > 0L && System.currentTimeMillis() >= this.doubleClickAtMs) {
                            this.doubleClickAtMs = 0L;
                            this.moduleClickThisTick = true;
                            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
                            this.applyJitter();
                        }
                    }
                    if (this.blockHit.getValue()
                            && this.blockHitDelay <= 0L
                            && mc.gameSettings.keyBindUseItem.isKeyDown()
                            && ItemUtil.isHoldingSword()) {
                        this.blockHitPending = true;
                        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                        if (!mc.thePlayer.isUsingItem()) {
                            this.blockHitDelay = this.blockHitDelay + this.getBlockHitDelay();
                            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                        }
                    }
                }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onCLick(LeftClickMouseEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            long now = System.currentTimeMillis();
            /* A click this module synthesized presses the attack key, which
               vanilla turns into clickMouse() in the same tick and lands back
               here. Recording that would feed record mode its own output, so
               only real clicks are recorded. */
            if (!this.moduleClickThisTick && this.lastRealClick > 0L && this.clickMode.getValue() == 2) {
                this.recordGaps[this.recordPos] = Math.max(1L, now - this.lastRealClick);
                this.recordPos = (this.recordPos + 1) % this.recordGaps.length;
                if (this.recordCount < this.recordGaps.length) {
                    this.recordCount++;
                }
            }
            this.lastRealClick = now;
            if (!this.clickPending) {
                this.clickDelay = this.clickDelay + this.getNextClickDelay();
            }
        }
    }

    @Override
    public void onEnabled() {
        this.humanClicks.reset();
        this.moduleClickThisTick = false;
        this.clickDelay = 0L;
        this.blockHitDelay = 0L;
        this.recordCount = 0;
        this.recordPos = 0;
        this.lastRealClick = 0L;
        this.guiClosedAt = 0L;
        this.driftStartMs = 0L;
        this.clicksSinceBreak = 0;
        this.breakUntilMs = 0L;
        this.doubleClickAtMs = 0L;
        this.fightStartMs = 0L;
        this.lastClickMs = 0L;
        this.enabledAtMs = System.currentTimeMillis();
    }

    public void onDisabled() {
        this.moduleClickThisTick = false;
        this.clickPending = false;
        this.blockHitPending = false;
        this.recordCount = 0;
        this.recordPos = 0;
        this.lastRealClick = 0L;
        this.guiClosedAt = 0L;
        this.driftStartMs = 0L;
        this.clicksSinceBreak = 0;
        this.breakUntilMs = 0L;
        this.doubleClickAtMs = 0L;
        this.fightStartMs = 0L;
        this.lastClickMs = 0L;
        this.enabledAtMs = 0L;
    }

    @Override
    public void verifyValue(String mode) {
        if (this.minCPS.getName().equals(mode)) {
            if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                this.maxCPS.setValue(this.minCPS.getValue());
            }
        } else {
            if (this.maxCPS.getName().equals(mode) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                this.minCPS.setValue(this.maxCPS.getValue());
            }
        }
    }

    @Override
    public String[] getSuffix() {
        String cps = Objects.equals(this.minCPS.getValue(), this.maxCPS.getValue())
                ? this.minCPS.getValue().toString()
                : String.format("%d-%d", this.minCPS.getValue(), this.maxCPS.getValue());
        if (this.clickMode.getValue() != 0) {
            return new String[]{cps, this.clickMode.getModeString()};
        }
        return new String[]{cps};
    }

    /* Merged modules as modes (2026-10-11, ModuleBundle): the module's own
       work pauses while another type is picked. */
    public final atlas.property.properties.ModeProperty mergedType = new atlas.property.properties.ModeProperty("type", 0, new String[]{"Normal", "Burst", "Smart", "Assist"});
    public final atlas.module.ModuleBundle burstMerged = atlas.module.ModuleBundle.forMode(this, new atlas.module.modules.BurstClicker(), "burst", "Burst", () -> this.mergedType.getValue() == 1).deselectWith(() -> this.mergedType.setValue(0));
    public final atlas.module.ModuleBundle smartMerged = atlas.module.ModuleBundle.forMode(this, new atlas.module.modules.SmartClicking(), "smart", "Smart", () -> this.mergedType.getValue() == 2).deselectWith(() -> this.mergedType.setValue(0));
    public final atlas.module.ModuleBundle assistMerged = atlas.module.ModuleBundle.forMode(this, new atlas.module.modules.ClickAssits(), "assist", "Assist", () -> this.mergedType.getValue() == 3).deselectWith(() -> this.mergedType.setValue(0));

    {
        this.pauseWhen(() -> this.mergedType.getValue() != 0);
    }
}
