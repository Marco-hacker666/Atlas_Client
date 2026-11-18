package atlas.module.modules;

import atlas.management.Arbiter;
import atlas.mixin.IAccessorKeyBinding;
import atlas.mixin.IAccessorMinecraft;
import atlas.util.AsyncLog;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

final class AutoBlockFull {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final int HISTORY = 40;

    private static final int MIN_GAP = 2;

    private static final int CYCLE_TICKS = 30;

    private static final int IMMUNE_TICKS = 10;

    private static final float ENGAGED_EXTRA = 1.5F;

    private final BlockHit module;

    private boolean active;
    private final boolean[] upAt = new boolean[HISTORY];
    private int tick;
    private Object lastWorld;
    private int lastToggle = -100;
    private int dropTick = -1;
    private int lastAttackTick = -100;
    private int lastHurt;
    private int lastHurtAt = -1000;

    private boolean windowOpen;
    private int windowUntil = -1;
    private int guardRestUntil;
    private float lastHealth = -1.0F;
    private String upReason = "";
    private boolean wantedUp;
    private int blockedHits;
    private int openHits;
    private int ticksActive;
    private int ticksUp;
    private File logTarget;

    AutoBlockFull(BlockHit module) {
        this.module = module;
    }

    boolean managing() {
        return this.active;
    }

    boolean active() {
        return this.active;
    }

    void attacked() {
        this.lastAttackTick = this.tick;
    }

    void tick() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.theWorld != this.lastWorld) {
            this.lastWorld = mc.theWorld;
            this.lastHurtAt = -1000;
            this.windowUntil = -1;
            this.dropTick = -1;
        }
        this.tick++;
        boolean using = mc.thePlayer.isUsingItem() && ItemUtil.isHoldingSword();
        this.upAt[this.tick % HISTORY] = using;
        boolean hurtAgain = noteHits();

        float range = this.module.fullRange.getValue();
        EntityPlayer enemy = wanted() ? this.module.blockTarget(this.active ? range + ENGAGED_EXTRA : range) : null;
        if (enemy == null) {
            if (this.active) {
                release(wanted() ? "no enemy in range" : "off");
            }
            return;
        }
        if (!this.active) {
            this.active = true;
            log(String.format("ON %s %.2f | style %s", enemy.getName(), BlockHit.boxDistance(enemy),
                    this.module.fullStyle.getModeString()));
        }
        this.ticksActive++;
        if (using) {
            this.ticksUp++;
        }
        int useKey = mc.gameSettings.keyBindUseItem.getKeyCode();
        int presses = ((IAccessorKeyBinding) mc.gameSettings.keyBindAttack).getPressTime();

        if (this.dropTick >= 0) {
            if (presses > 0 && !mc.thePlayer.isUsingItem()) {
                this.dropTick = -1;

                this.lastToggle = this.tick - MIN_GAP;
            } else if (this.tick - this.dropTick >= 3) {
                log("DROP spent, no click came");
                this.dropTick = -1;
            } else {
                KeyBindUtil.setKeyBindState(useKey, false);
                return;
            }
        }

        boolean up = shouldBeUp(enemy, hurtAgain, BlockHit.boxDistance(enemy) <= range);
        if (up != this.wantedUp) {
            this.wantedUp = up;
            if (this.module.fullLog.getValue()) {
                log(up ? "UP " + this.upReason : "DOWN");
            }
        }
        if (using && hurtableUnderCrosshair() && this.tick - this.lastToggle >= MIN_GAP) {
            KeyBindUtil.setKeyBindState(useKey, false);
            this.dropTick = this.tick;
            this.lastToggle = this.tick;
            return;
        }
        if (up) {
            if (!mc.thePlayer.isUsingItem()) {
                if (this.tick - this.lastToggle < MIN_GAP) {
                    KeyBindUtil.setKeyBindState(useKey, false);
                    return;
                }

                ((IAccessorMinecraft) mc).setRightClickDelayTimer(0);
                this.lastToggle = this.tick;
            }
            KeyBindUtil.setKeyBindState(useKey, true);
        } else {
            if (mc.thePlayer.isUsingItem() && this.tick - this.lastToggle < MIN_GAP) {
                KeyBindUtil.setKeyBindState(useKey, true);
                return;
            }
            if (mc.thePlayer.isUsingItem()) {
                this.lastToggle = this.tick;
            }
            KeyBindUtil.setKeyBindState(useKey, false);
        }
    }

    private boolean shouldBeUp(EntityPlayer enemy, boolean hurtAgain, boolean inReach) {
        if (this.module.fullStyle.getValue() == 0) {
            this.upReason = "always";
            return true;
        }
        if (hurtAgain) {
            this.windowUntil = -1;
            this.windowOpen = false;
        }
        int hurt = mc.thePlayer.hurtTime;
        int lead = this.module.fullPingAware.getValue() ? Math.round(atlas.util.Ping.own() / 50.0F) : 0;
        int openAt = Math.max(0, Math.min(9, this.module.fullOpenHurt.getValue() + lead));
        boolean inCycle = this.tick - this.lastHurtAt <= CYCLE_TICKS;
        int hold = this.module.fullHold.getValue();

        if (inCycle && hurt <= openAt && inReach) {
            if (!this.windowOpen) {
                this.windowOpen = true;
                this.windowUntil = this.tick + hold;
                this.upReason = "window (hurt " + hurt + ", open at " + openAt + ")";
            }
            return true;
        }
        this.windowOpen = false;
        if (!inCycle && inReach && this.module.fullGuard.getValue() && this.tick >= this.guardRestUntil
                && this.tick >= this.windowUntil && facesMe(enemy)) {
            this.windowUntil = this.tick + hold;
            this.guardRestUntil = this.tick + hold * 2;
            this.upReason = String.format("guard %s %.1f", enemy.getName(), BlockHit.boxDistance(enemy));
        }
        if (this.tick < this.windowUntil) {
            return true;
        }
        if (this.module.fullAir.getValue() && !mc.thePlayer.onGround && !mc.thePlayer.isInWater()
                && hurt < IMMUNE_TICKS - 1) {
            this.upReason = "air";
            return true;
        }
        return false;
    }

    private boolean hurtableUnderCrosshair() {
        if (!KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode()) || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || !(mc.objectMouseOver.entityHit instanceof EntityPlayer)) {
            return false;
        }
        Entity target = mc.objectMouseOver.entityHit;
        if (((EntityPlayer) target).hurtTime > 2) {
            return false;
        }
        int since = this.tick - this.lastAttackTick;

        int back = Math.max(3, Math.round(atlas.util.Ping.own() / 50.0F) + 2);
        return since >= IMMUNE_TICKS - 1 || since >= back && ((EntityPlayer) target).hurtTime == 0;
    }

    private static boolean facesMe(EntityPlayer p) {
        double dx = mc.thePlayer.posX - p.posX;
        double dz = mc.thePlayer.posZ - p.posZ;
        float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(MathHelper.wrapAngleTo180_float(toUs - p.rotationYawHead)) <= 60.0F;
    }

    private boolean wanted() {
        return mc.currentScreen == null && !mc.thePlayer.isDead && ItemUtil.isHoldingSword()
                && !this.module.auraBlocksAlone() && !Arbiter.catching()
                && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
    }

    private boolean noteHits() {
        int hurt = mc.thePlayer.hurtTime;
        float health = mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount();
        boolean again = hurt > this.lastHurt;
        if (again) {
            this.lastHurtAt = this.tick;
            if (this.active && this.lastHealth >= 0.0F) {
                int back = Math.max(1, Math.min(HISTORY - 1, Math.round(atlas.util.Ping.own() / 50.0F)));
                boolean up = this.upAt[(this.tick - back + HISTORY) % HISTORY];
                if (up) {
                    this.blockedHits++;
                } else {
                    this.openHits++;
                }
                log(String.format("HIT %s (%dt back) took %.2f | blocked %d of %d | sword up %d%% of the time",
                        up ? "blocked" : "NOT blocked", back, Math.max(0.0F, this.lastHealth - health),
                        this.blockedHits, this.blockedHits + this.openHits,
                        this.ticksActive == 0 ? 0 : this.ticksUp * 100 / this.ticksActive));
            }
        }
        this.lastHurt = hurt;
        this.lastHealth = health;
        return again;
    }

    private void release(String why) {
        this.active = false;
        this.dropTick = -1;
        this.wantedUp = false;
        if (mc.gameSettings != null && mc.currentScreen == null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        log("OFF " + why);
    }

    void reset() {
        if (this.active) {
            release("mode off");
        }
        this.lastHurt = 0;
        this.lastHealth = -1.0F;
    }

    private void log(String line) {
        if (!this.module.fullLog.getValue()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "fullblock-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
