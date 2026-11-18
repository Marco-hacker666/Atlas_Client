package atlas.module.modules;

import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.util.AsyncLog;
import atlas.util.ItemUtil;
import atlas.util.PacketUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

final class NoSlowHitWindow {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String MODE = "Hit-Window";

    private static final int MIN_GAP = 2;

    private static final int CYCLE_TICKS = 30;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    private final NoSlow module;

    private boolean serverBlocking;
    private boolean managing;
    private int tick;
    private int lastToggle = -100;
    private int openedAt = -100;
    private int lastHurt;
    private int lastHurtAt = -1000;
    private boolean windowSpent;
    private String openReason = "";
    private File logTarget;
    private int blockedHits;
    private int openHits;

    private int closedAt = -100;
    private String closeReason = "";
    private final java.util.Map<String, Integer> lastHitBy = new java.util.HashMap<String, Integer>();
    private int sumHits;
    private int sumBlocked;
    private int sumManaged;
    private int sumSlowed;
    private int sumWindows;
    private int sumStart;
    private int sumPostponed;

    NoSlowHitWindow(NoSlow module) {
        this.module = module;
    }

    boolean selected() {
        return MODE.equals(this.module.swordMode.getModeString());
    }

    boolean managing() {
        return this.managing;
    }

    boolean slowed() {
        return this.serverBlocking;
    }

    private static boolean physicalUseKey() {
        int code = mc.gameSettings.keyBindUseItem.getKeyCode();
        return code < 0 ? Mouse.isButtonDown(code + 100) : code > 0 && Keyboard.isKeyDown(code);
    }

    private boolean claims() {
        return physicalUseKey() || this.module.hwAura.getValue();
    }

    private boolean leftToAura() {
        return !this.module.hwAura.getValue() && atlas.util.Auras.blocksByItself();
    }

    private boolean wantsToManage() {
        return this.module.isEnabled() && this.selected() && mc.thePlayer != null && ItemUtil.isHoldingSword()
                && (mc.thePlayer.isUsingItem() && claims() && !leftToAura() || delegated())
                && !BlockHit.fullManaging() && !NoSlow.swordYields();
    }

    private static KillAuraLB aura() {
        KillAuraLB aura = atlas.module.ModuleBundle.of(KillAuraLB.class);
        return aura != null && aura.isEnabled() ? aura : null;
    }

    boolean delegated() {
        if (!this.module.hitWindowTakesAura()) {
            return false;
        }
        KillAuraLB aura = aura();
        return aura != null && aura.wantsServerBlock();
    }

    static boolean mayOpenForAura(boolean hitFirst, int ticksUntilClick) {
        return !hitFirst || ticksUntilClick >= MIN_GAP + 1;
    }

    static boolean mustCloseForAura(boolean hitFirst, int ticksUntilClick) {
        return hitFirst && ticksUntilClick <= 1;
    }

    private boolean hitFirst() {
        return this.module.hwPriority.getValue() == 0;
    }

    private int auraClickIn() {
        KillAuraLB aura = aura();
        return aura == null ? 20 : aura.ticksUntilClick();
    }

    void postponed() {
        this.sumPostponed++;
    }

    void tick() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tick++;
        int hurt = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurt > this.lastHurt;
        this.lastHurt = hurt;
        if (hurtAgain) {
            this.lastHurtAt = this.tick;
            this.windowSpent = false;
            if (this.managing) {
                if (this.serverBlocking) {
                    this.blockedHits++;
                } else {
                    this.openHits++;
                }
            }
            if (this.selected()) {
                logHit();
            }
        }
        if (this.selected()) {
            if (this.managing) {
                this.sumManaged++;
            }
            if (this.serverBlocking) {
                this.sumSlowed++;
            }
            if (this.tick - this.sumStart >= 1200) {
                summary();
            }
        }
        boolean wanted = this.wantsToManage();
        if (!wanted) {
            if (this.managing && this.serverBlocking && !mc.thePlayer.isUsingItem()) {
                sendRelease();
                this.serverBlocking = false;
                this.lastToggle = this.tick;
                this.closedAt = this.tick;
                this.closeReason = "aura stopped blocking";
                log("CLOSE aura stopped blocking | open " + (this.tick - this.openedAt) + "t");
            }
            if (this.managing) {
                this.managing = false;
                if (mc.thePlayer.isUsingItem() && ItemUtil.isHoldingSword() && !this.serverBlocking) {
                    sendUse();
                    log("SYNC block up (handed back)");
                }
            }
            if (!mc.thePlayer.isUsingItem()) {
                this.serverBlocking = false;
            }
            return;
        }
        this.managing = true;
        int since = this.tick - this.lastToggle;
        int openAt = Math.max(0, Math.min(9, this.module.hwOpenHurt.getValue() + this.leadTicks()));
        boolean inCycle = this.tick - this.lastHurtAt <= CYCLE_TICKS;
        if (!this.serverBlocking) {
            String reason = null;
            if (inCycle && !this.windowSpent && hurt <= openAt) {
                reason = "next hit (hurt " + hurt + ", open at " + openAt + ")";
            } else if (!inCycle && this.module.hwGuard.getValue() && since >= this.module.hwHold.getValue()) {
                EntityPlayer enemy = this.facingEnemy();
                if (enemy != null) {
                    reason = "guard " + enemy.getName() + String.format(" %.1f", mc.thePlayer.getDistanceToEntity(enemy));
                }
            }
            boolean delegatedNow = delegated();
            if (reason != null && delegatedNow && !mayOpenForAura(hitFirst(), auraClickIn())) {
                reason = null;
            }
            if (reason != null && since >= MIN_GAP
                    && !atlas.management.TickActions.any(atlas.management.TickActions.ATTACK | atlas.management.TickActions.DIG)
                    && !atlas.util.VanillaInput.attackQueued()) {
                sendUse();
                this.serverBlocking = true;
                this.lastToggle = this.tick;
                this.openedAt = this.tick;
                this.openReason = reason;
                this.sumWindows++;
                if (inCycle) {
                    this.windowSpent = true;
                }
                log("OPEN " + reason + " | ping " + atlas.util.Ping.own() + "ms");
            }
        } else {
            String reason = null;
            if (delegated() && mustCloseForAura(hitFirst(), auraClickIn())) {
                reason = "click due";
            } else if (hurtAgain && this.tick - this.openedAt > 0) {
                reason = "hit taken";
            } else if (this.tick - this.openedAt >= this.module.hwHold.getValue()) {
                reason = "held " + this.module.hwHold.getValue() + "t";
            }
            if (reason != null && since >= MIN_GAP) {
                sendRelease();
                this.serverBlocking = false;
                this.lastToggle = this.tick;
                this.closedAt = this.tick;
                this.closeReason = reason;
                log("CLOSE " + reason + " | open " + (this.tick - this.openedAt) + "t (" + this.openReason + ")");
            }
        }
    }

    private int leadTicks() {
        if (!this.module.hwPingAware.getValue()) {
            return 0;
        }
        int ping = atlas.util.Ping.own();
        return ping <= 0 ? 0 : Math.round(ping / 50.0F);
    }

    private static float facingOff(EntityPlayer p) {
        double dx = mc.thePlayer.posX - p.posX;
        double dz = mc.thePlayer.posZ - p.posZ;
        float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(MathHelper.wrapAngleTo180_float(toUs - p.rotationYawHead));
    }

    private static EntityPlayer likelyAttacker() {
        EntityPlayer facing = null;
        EntityPlayer nearest = null;
        double facingSq = 36.0;
        double nearestSq = 36.0;
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == mc.thePlayer || p.isDead || TeamUtil.isFriend(p) || TeamUtil.isSameTeam(p) || TeamUtil.isBot(p)) {
                continue;
            }
            double d = mc.thePlayer.getDistanceSqToEntity(p);
            if (d < nearestSq) {
                nearestSq = d;
                nearest = p;
            }
            if (d < facingSq && facingOff(p) <= 60.0F) {
                facingSq = d;
                facing = p;
            }
        }
        return facing != null ? facing : nearest;
    }

    private void logHit() {
        this.sumHits++;
        if (this.serverBlocking) {
            this.sumBlocked++;
        }
        EntityPlayer by = likelyAttacker();
        String who = "unknown";
        if (by != null) {
            Integer last = this.lastHitBy.put(by.getName(), this.tick);
            who = String.format("%s d=%.2f facing %.0f deg | since their last %s", by.getName(),
                    mc.thePlayer.getDistanceToEntity(by), facingOff(by), last == null ? "-" : (this.tick - last) + "t");
        }
        String window;
        if (this.serverBlocking) {
            window = "open " + (this.tick - this.openedAt) + "t (" + this.openReason + ")";
        } else if (this.closedAt > this.openedAt) {
            window = "closed " + (this.tick - this.closedAt) + "t ago (" + this.closeReason + ", was open "
                    + (this.closedAt - this.openedAt) + "t)";
        } else {
            window = "none yet";
        }
        String whose = !this.managing ? "not steering (" + (ItemUtil.isHoldingSword() && mc.thePlayer.isUsingItem()
                ? "block not ours" : "no block") + ")" : delegated() ? "aura (delegated)"
                : physicalUseKey() ? "right button" : "aura/AutoBlock";
        log("HIT " + (this.serverBlocking ? "blocked" : "NOT blocked") + " | by " + who + " | ping "
                + atlas.util.Ping.own() + "ms | window " + window + " | " + whose + " | blocked " + this.blockedHits
                + " of " + (this.blockedHits + this.openHits));
    }

    private void summary() {
        if (this.sumHits > 0 || this.sumManaged > 0) {
            log(String.format("SUMMARY %ds | hits %d, blocked %d (%d%%) | steered %dt, slowed %dt (%d%% of steered) | windows %d | aura clicks held %d",
                    (this.tick - this.sumStart) / 20, this.sumHits, this.sumBlocked,
                    this.sumHits == 0 ? 0 : this.sumBlocked * 100 / this.sumHits, this.sumManaged, this.sumSlowed,
                    this.sumManaged == 0 ? 0 : this.sumSlowed * 100 / this.sumManaged, this.sumWindows, this.sumPostponed));
        }
        this.sumStart = this.tick;
        this.sumHits = 0;
        this.sumBlocked = 0;
        this.sumManaged = 0;
        this.sumSlowed = 0;
        this.sumWindows = 0;
        this.sumPostponed = 0;
    }

    private EntityPlayer facingEnemy() {
        float range = this.module.hwGuardRange.getValue();
        EntityPlayer best = null;
        double bestSq = range * range;
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == mc.thePlayer || p.isDead || p.getHealth() <= 0.0F || TeamUtil.isFriend(p) || TeamUtil.isSameTeam(p)
                    || TeamUtil.isBot(p)) {
                continue;
            }
            double d = mc.thePlayer.getDistanceSqToEntity(p);
            if (d > bestSq) {
                continue;
            }
            double dx = mc.thePlayer.posX - p.posX;
            double dz = mc.thePlayer.posZ - p.posZ;
            float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            if (Math.abs(MathHelper.wrapAngleTo180_float(toUs - p.rotationYawHead)) > 60.0F) {
                continue;
            }
            bestSq = d;
            best = p;
        }
        return best;
    }

    void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled() || !this.selected() || !this.module.isEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof C09PacketHeldItemChange) {
            this.serverBlocking = false;
            return;
        }
        if (mc.thePlayer == null || !ItemUtil.isHoldingSword() || leftToAura()) {
            return;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement
                && ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255) {
            if (claims() && !BlockHit.fullManaging()) {
                event.setCancelled(true);
                this.serverBlocking = false;
                this.managing = true;
                log("PRESS block held back until a window");
            }
            return;
        }
        if (packet instanceof C07PacketPlayerDigging
                && ((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
            if (this.managing && !this.serverBlocking) {
                event.setCancelled(true);
            } else if (this.serverBlocking) {
                this.closedAt = this.tick;
                this.closeReason = physicalUseKey() ? "released by the player" : "released (aura/AutoBlock)";
                log("CLOSE " + this.closeReason + " | open " + (this.tick - this.openedAt) + "t");
            }
            this.serverBlocking = false;
            this.managing = false;
        }
    }

    private void sendUse() {
        ItemStack held = mc.thePlayer.getHeldItem();
        C08PacketPlayerBlockPlacement use = new C08PacketPlayerBlockPlacement(held);
        PacketUtil.sendPacketNoEvent(use);
        atlas.management.TickActions.noteDirect(use);
    }

    private static void sendRelease() {
        C07PacketPlayerDigging release = new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN, EnumFacing.DOWN);
        PacketUtil.sendPacketNoEvent(release);
        atlas.management.TickActions.noteDirect(release);
    }

    void reset() {
        if (this.managing && mc.thePlayer != null && mc.getNetHandler() != null && mc.thePlayer.isUsingItem()
                && ItemUtil.isHoldingSword() && !this.serverBlocking) {
            sendUse();
            log("SYNC block up (mode off)");
        }
        this.managing = false;
        this.serverBlocking = false;
        this.windowSpent = false;
    }

    void worldChanged() {
        summary();
        this.lastHitBy.clear();
        this.managing = false;
        this.serverBlocking = false;
        this.windowSpent = false;
        this.lastHurtAt = -1000;
        this.lastHurt = 0;
    }

    private void log(String line) {
        if (!this.module.hwLog.getValue()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "noslow-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
