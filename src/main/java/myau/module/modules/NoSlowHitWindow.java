package myau.module.modules;

import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.util.AsyncLog;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.TeamUtil;
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

/**
 * NoSlow's Hit-Window sword mode (2026-10-20, owner: "the current NoSlow
 * modes no longer work -- make a new one, learn from before, think it over").
 *
 * What the logs said about the others: NG-Blink holds movement while
 * blocking, and every release is a burst the server judges as blocked
 * movement (Grim 18:30:03, sixteen NoSlow flags in one second as ten held
 * moves landed; Pika corrections 1-5 ticks after hits, NoSlow held-send).
 * Grim-Alternate moves at full speed every other tick while the server
 * believes the player is blocking, which Grim still caught (18:35, 30 s of
 * NoSlow flags). Everything that moves fast while the server thinks the
 * sword is up is a bet on the check missing it.
 *
 * Hit-Window does not bet. The sword is up on the server only when a block
 * can matter -- around the moment the next hit can land -- and only then is
 * the player slowed, exactly as vanilla slows (x0.2, sprint dropped the
 * vanilla way). The rest of the time the server is told nothing is in use,
 * so full speed and sprint are simply legal. The client keeps its blocking
 * pose throughout. What the server sees is a player who raises the sword
 * shortly before each hit and lowers it after: a block-hitter's rhythm, with
 * movement that matches it on every tick.
 *
 * Windows: after being hit, damage is impossible for ten ticks (hurtTime 10
 * -> 0). The block goes up when hurtTime reaches open-hurt plus the round
 * trip in ticks (the hit reaches this client half a trip late and the block
 * the server half a trip late), and comes down after hold ticks or as soon
 * as the hit lands. One window per hit. Before the
 * first hit of an exchange (guard), the block goes up for hold ticks while
 * an enemy within guard-range faces this player, then rests at least as long.
 *
 * Lessons kept: nothing is held or delayed (no burst); inputs are only 1.0
 * or the vanilla 0.2 (Simulation); one change of state per tick, at least
 * two ticks between changes (block-toggle checks); sprint changes only
 * through vanilla's own path (BadPacketsX); no transaction is touched.
 * Only a block the player holds with their own right button is managed;
 * an aura or AutoBlock pressing the key is left alone (vanilla behaviour).
 * Each window is logged to config/Atlas/noslow-*.txt.
 */
final class NoSlowHitWindow {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String MODE = "Hit-Window";
    /** Ticks at least between two changes of the server's block state. */
    private static final int MIN_GAP = 2;
    /** An exchange is over this many ticks after the last hit taken. */
    private static final int CYCLE_TICKS = 30;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    private final NoSlow module;
    /** What the server believes: the sword is up. */
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

    NoSlowHitWindow(NoSlow module) {
        this.module = module;
    }

    boolean selected() {
        return MODE.equals(this.module.swordMode.getModeString());
    }

    /** Whether this mode is steering the block now: vanilla's slowdown is then replaced by slowed(). */
    boolean managing() {
        return this.managing;
    }

    /** This tick is slowed exactly as vanilla slows a raised sword. */
    boolean slowed() {
        return this.serverBlocking;
    }

    private static boolean physicalUseKey() {
        int code = mc.gameSettings.keyBindUseItem.getKeyCode();
        return code < 0 ? Mouse.isButtonDown(code + 100) : code > 0 && Keyboard.isKeyDown(code);
    }

    private boolean wantsToManage() {
        return this.module.isEnabled() && this.selected() && mc.thePlayer != null && ItemUtil.isHoldingSword()
                && mc.thePlayer.isUsingItem() && physicalUseKey() && !myau.util.Auras.blocksByItself();
    }

    // ---- each tick, before the game handles input and moves (TickEvent PRE) ----

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
                log("HIT " + (this.serverBlocking ? "blocked" : "NOT blocked") + " | blocked " + this.blockedHits
                        + " of " + (this.blockedHits + this.openHits));
            }
        }
        boolean wanted = this.wantsToManage();
        if (!wanted) {
            if (this.managing) {
                this.managing = false;
                if (mc.thePlayer.isUsingItem() && ItemUtil.isHoldingSword() && !this.serverBlocking) {
                    /* Still blocking, no longer ours (an aura took over, the module went off): the server
                       must hear the block the client is showing, or the vanilla slowdown that now applies
                       would be a slowdown the server did not expect. */
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
            if (reason != null && since >= MIN_GAP) {
                sendUse();
                this.serverBlocking = true;
                this.lastToggle = this.tick;
                this.openedAt = this.tick;
                this.openReason = reason;
                if (inCycle) {
                    this.windowSpent = true;
                }
                log("OPEN " + reason + " | ping " + myau.util.Ping.own() + "ms");
            }
        } else {
            String reason = null;
            if (hurtAgain && this.tick - this.openedAt > 0) {
                reason = "hit taken";
            } else if (this.tick - this.openedAt >= this.module.hwHold.getValue()) {
                reason = "held " + this.module.hwHold.getValue() + "t";
            }
            if (reason != null && since >= MIN_GAP) {
                sendRelease();
                this.serverBlocking = false;
                this.lastToggle = this.tick;
                log("CLOSE " + reason + " | open " + (this.tick - this.openedAt) + "t (" + this.openReason + ")");
            }
        }
    }

    /**
     * The round trip in ticks. The client hears of a hit half a trip after the
     * server dealt it, and the server hears of the block half a trip after it
     * is sent, so to be up when the server allows the next hit (ten ticks after
     * the last) it is sent a whole trip earlier, plus open-hurt as margin.
     */
    private int leadTicks() {
        if (!this.module.hwPingAware.getValue()) {
            return 0;
        }
        int ping = myau.util.Ping.own();
        return ping <= 0 ? 0 : Math.round(ping / 50.0F);
    }

    /** The nearest enemy within guard-range whose head turns toward this player (within 60 degrees). */
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

    // ---- the vanilla use and release packets ----

    /**
     * Vanilla sends the use (C08, face 255) when the right button goes down
     * and the release (C07) when it comes up. While managing, the server only
     * hears them inside a window; this mode sends its own, past the event bus.
     */
    void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled() || !this.selected() || !this.module.isEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof C09PacketHeldItemChange) {
            /* A slot change ends a use on the server. */
            this.serverBlocking = false;
            return;
        }
        if (mc.thePlayer == null || !ItemUtil.isHoldingSword() || myau.util.Auras.blocksByItself()) {
            return;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement
                && ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255) {
            if (physicalUseKey()) {
                /* The right button went down: the block waits for a window. */
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
                /* Released between windows: the server never had the sword up. */
                event.setCancelled(true);
            } else if (this.serverBlocking) {
                log("CLOSE released by the player | open " + (this.tick - this.openedAt) + "t");
            }
            this.serverBlocking = false;
            this.managing = false;
        }
    }

    private void sendUse() {
        ItemStack held = mc.thePlayer.getHeldItem();
        PacketUtil.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(held));
    }

    private static void sendRelease() {
        PacketUtil.sendPacketNoEvent(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN, EnumFacing.DOWN));
    }

    /** Off, or switched to another mode, while the client still blocks: the server gets the block back. */
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
