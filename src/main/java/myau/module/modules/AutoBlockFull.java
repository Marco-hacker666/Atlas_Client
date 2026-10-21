package myau.module.modules;

import myau.management.Arbiter;
import myau.mixin.IAccessorKeyBinding;
import myau.mixin.IAccessorMinecraft;
import myau.util.AsyncLog;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * AutoBlock "Full" (2026-10-21, owner: "a full auto block like other clients,
 * every hit halved"). While the attack key is held and an enemy is within
 * full-range, the sword stays up -- on the client too, so the movement is the
 * blocking speed the server expects. On a tick the clicker attacks, the release
 * (C07), the swing and the hit (C0A, C02) and the block again (C08) all go out
 * in that one tick, in that order, before the movement packet:
 *   runTick HEAD (TickEvent PRE, after AutoClicker pressed the key): the C07
 *   and the right-click delay set to 0; vanilla's input then attacks, because
 *   the item is no longer in use, and right-clicks again at once, because the
 *   use key is held and the delay is 0.
 * The server checks blocking when it applies an opponent's hit, between whole
 * batches of this player's packets, so it finds the sword up for every hit.
 * Nothing is held or delayed. The cost, stated to the owner: blocking speed
 * whenever an enemy is in range, and a release and a block in one tick, which
 * no hand makes -- whether Grim or Polar flag it is what full-log is for.
 */
final class AutoBlockFull {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    /** Ticks of block state kept, to look back a round trip when a hit arrives. */
    private static final int HISTORY = 40;

    private final BlockHit module;
    /** The use key is held by this mode. */
    private boolean active;
    private final boolean[] upAt = new boolean[HISTORY];
    private int tick;
    private int lastHurt;
    private float lastHealth = -1.0F;
    private int swaps;
    private int blockedHits;
    private int openHits;
    private File logTarget;

    AutoBlockFull(BlockHit module) {
        this.module = module;
    }

    /** The sword is up by this mode: AutoClicker keeps clicking, the hit is swapped in. */
    boolean holding() {
        return this.active && mc.thePlayer != null && mc.thePlayer.isUsingItem();
    }

    boolean active() {
        return this.active || this.blinkActive || this.serverBlocked;
    }

    /** TickEvent PRE, after the clickers. */
    void tick() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.theWorld != this.lastWorld) {
            /* Another world: what was held belonged to the old connection. */
            this.lastWorld = mc.theWorld;
            worldChanged();
        }
        this.tick++;
        boolean upNow = this.module.fullBlink.getValue() ? this.serverBlocked
                : mc.thePlayer.isUsingItem() && ItemUtil.isHoldingSword();
        this.upAt[this.tick % HISTORY] = upNow;
        noteHits();

        EntityPlayer target = wanted() ? this.module.blockTarget(this.module.fullRange.getValue()) : null;
        if (this.module.fullBlink.getValue()) {
            if (this.active && !this.blinkActive) {
                release("switched to blink");
            }
            blinkTick(target);
            return;
        }
        if (this.blinkActive) {
            endBlink("blink off");
        }
        if (target == null) {
            if (this.active) {
                release(wanted() ? "no enemy in range" : "off");
            }
            return;
        }
        int useKey = mc.gameSettings.keyBindUseItem.getKeyCode();
        IAccessorMinecraft accessor = (IAccessorMinecraft) mc;
        if (!this.active) {
            this.active = true;
            log(String.format("UP %s %.2f", target.getName(), BlockHit.boxDistance(target)));
        }
        KeyBindUtil.setKeyBindState(useKey, true);
        if (!mc.thePlayer.isUsingItem()) {
            /* Not up yet (or a slot change dropped it): up this tick, not after vanilla's 4-tick delay. */
            accessor.setRightClickDelayTimer(0);
            return;
        }
        int presses = ((IAccessorKeyBinding) mc.gameSettings.keyBindAttack).getPressTime();
        if (presses > 0 && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            /* The hit swaps in: down now, vanilla swings and hits, then raises it again, this tick. */
            PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                    BlockPos.ORIGIN, EnumFacing.DOWN));
            mc.thePlayer.stopUsingItem();
            accessor.setRightClickDelayTimer(0);
            this.swaps++;
            if (this.module.fullLog.getValue() && this.swaps % 20 == 1) {
                log(String.format("SWAP #%d %s %.2f (one line per 20)", this.swaps,
                        mc.objectMouseOver.entityHit == null ? "?" : mc.objectMouseOver.entityHit.getName(),
                        mc.objectMouseOver.entityHit == null ? -1.0 : BlockHit.boxDistance(mc.objectMouseOver.entityHit)));
            }
        }
    }

    // ---- full-blink (2026-10-21, owner: "with blink, so it does not slow me down so much") ----
    /*
     * The client never raises the sword (no use key, no slowdown, clicks as
     * usual); only the server is told. Each cycle, on the wire:
     *   C07 (down) | the movement held since the last cycle, in order | this
     *   tick's swing and hit | C08 (up) | -- then this tick's C03 and every
     *   packet after it are held again, transaction replies included, in order.
     * So every position reaches the server after a release, judged unblocked
     * (full speed is legal), and between batches the server has the sword up,
     * which is when it applies an opponent's hit. A cycle runs on a tick with
     * an attack, or after full-blink-ticks, or when hurt (the knockback reply
     * must not wait). The price is blink's: the server hears from this player
     * in bursts of up to full-blink-ticks, replies held with them (latency, not
     * Timer); keep it low -- held packets have been behind mitigation before.
     */
    private static final String HUB = "AutoBlock";
    private final java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.network.Packet<?>> held =
            new java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.network.Packet<?>>();
    /** The server has the sword up (we sent C08, no C07 since). */
    private volatile boolean serverBlocked;
    /** Raise it on the server when this tick's movement packet comes. */
    private volatile boolean reblockPending;
    private volatile boolean flushRequested;
    private volatile boolean sending;
    private boolean blinkActive;
    private Object lastWorld;
    private int heldSince;
    private int cycles;
    private int maxBurst;

    {
        /* Above FakeLag (MOVEMENT): while this holds, FakeLag lets its queue go and waits. */
        myau.management.PacketHub.register(HUB, myau.management.PacketHub.COMBAT, () -> this.held.size(),
                () -> endBlink("packet hub"));
    }

    private void blinkTick(EntityPlayer target) {
        if (target == null) {
            if (this.blinkActive || this.serverBlocked) {
                endBlink(wanted() ? "no enemy in range" : "off");
            }
            return;
        }
        if (!this.blinkActive) {
            this.blinkActive = true;
            this.heldSince = this.tick;
            log(String.format("BLINK UP %s %.2f", target.getName(), BlockHit.boxDistance(target)));
        }
        if (mc.thePlayer.isUsingItem() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
            /* Nothing of the client's own may be up: that is the slowdown this is here to avoid. */
            mc.playerController.onStoppedUsingItem(mc.thePlayer);
        }
        int presses = ((IAccessorKeyBinding) mc.gameSettings.keyBindAttack).getPressTime();
        boolean attack = presses > 0 && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
        boolean hurt = mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0;
        boolean due = !this.serverBlocked || attack || hurt
                || this.tick - this.heldSince >= this.module.fullBlinkTicks.getValue();
        if (due) {
            cycle(attack ? "attack" : hurt ? "hurt" : this.serverBlocked ? "ticks" : "first");
        }
    }

    /** Down, the held movement, and up again when this tick's movement goes (reblockPending). */
    private void cycle(String why) {
        int burst = this.held.size();
        lower();
        flushHeld(false);
        this.reblockPending = true;
        this.heldSince = this.tick;
        this.cycles++;
        this.maxBurst = Math.max(this.maxBurst, burst);
        if (this.module.fullLog.getValue() && (this.cycles % 20 == 1 || burst > this.module.fullBlinkTicks.getValue() + 3)) {
            log(String.format("CYCLE #%d %s | sent %d | biggest %d (one line per 20)", this.cycles, why, burst,
                    this.maxBurst));
        }
    }

    private void lower() {
        if (this.serverBlocked) {
            send(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN,
                    EnumFacing.DOWN));
            this.serverBlocked = false;
        }
    }

    private void raise() {
        net.minecraft.item.ItemStack stack = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (stack != null && ItemUtil.isHoldingSword()) {
            send(new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(stack));
            this.serverBlocked = true;
        }
    }

    /** Everything held, in order; positions dropped when a correction made them stale. */
    private void flushHeld(boolean dropPositions) {
        net.minecraft.network.Packet<?> packet;
        while ((packet = this.held.poll()) != null) {
            if (dropPositions && packet instanceof net.minecraft.network.play.client.C03PacketPlayer) {
                continue;
            }
            send(packet);
        }
    }

    private void send(net.minecraft.network.Packet<?> packet) {
        this.sending = true;
        try {
            myau.util.ActionLedger.note("AutoBlock", "release");
            PacketUtil.sendPacketNoEvent(packet);
        } catch (Exception ignored) {
            this.held.clear();
        } finally {
            this.sending = false;
        }
    }

    /** The server's sword down, everything sent; the client was never blocking. */
    void endBlink(String why) {
        if (mc.getNetHandler() == null) {
            this.held.clear();
            this.serverBlocked = false;
        } else {
            lower();
            flushHeld(false);
        }
        this.reblockPending = false;
        if (this.blinkActive) {
            this.blinkActive = false;
            log("BLINK DOWN " + why + " | cycles " + this.cycles + " biggest " + this.maxBurst);
        }
    }

    /** PacketEvent, first of all listeners. */
    void onPacket(myau.events.PacketEvent event) {
        if (!this.module.fullBlink.getValue() || mc.thePlayer == null) {
            return;
        }
        net.minecraft.network.Packet<?> packet = event.getPacket();
        if (event.getType() == myau.event.types.EventType.RECEIVE) {
            if (packet instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook
                    || packet instanceof net.minecraft.network.play.server.S07PacketRespawn) {
                this.flushRequested = true;
            }
            return;
        }
        if (event.getType() != myau.event.types.EventType.SEND || this.sending || event.isCancelled()) {
            return;
        }
        if (this.flushRequested) {
            /* A correction: the held positions are stale. Down, the rest of what was held, and the answer goes. */
            this.flushRequested = false;
            lower();
            flushHeld(true);
            this.reblockPending = false;
            return;
        }
        if (packet instanceof net.minecraft.network.play.client.C00PacketKeepAlive
                || packet instanceof net.minecraft.network.play.client.C01PacketChatMessage) {
            return;
        }
        boolean position = packet instanceof net.minecraft.network.play.client.C03PacketPlayer;
        if (position && this.reblockPending && this.blinkActive) {
            /* This tick's swing and hit have gone; up again before its movement, which is then held. */
            raise();
            this.reblockPending = false;
        }
        if (!this.serverBlocked) {
            return;
        }
        if (Arbiter.catching()) {
            endBlink("catch");
            return;
        }
        if (myau.management.PacketHub.take(event, HUB)) {
            this.held.offer(packet);
        } else if (position) {
            /* Someone else has the lane: this movement must not reach the server while it has the sword up. */
            lower();
            flushHeld(false);
        }
    }

    void worldChanged() {
        this.held.clear();
        this.serverBlocked = false;
        this.reblockPending = false;
        this.flushRequested = false;
        this.blinkActive = false;
    }

    private boolean wanted() {
        return mc.currentScreen == null && !mc.thePlayer.isDead && ItemUtil.isHoldingSword()
                && !myau.util.Auras.blocksByItself() && !Arbiter.catching()
                && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
    }

    /** A hit taken: was the sword up on the server when it was dealt (a round trip back)? */
    private void noteHits() {
        int hurt = mc.thePlayer.hurtTime;
        float health = mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount();
        if (hurt > this.lastHurt && this.lastHealth >= 0.0F && (this.active || this.blockedHits + this.openHits > 0)) {
            int back = Math.max(1, Math.min(HISTORY - 1, Math.round(myau.util.Ping.own() / 50.0F)));
            boolean up = this.upAt[(this.tick - back + HISTORY) % HISTORY];
            if (up) {
                this.blockedHits++;
            } else {
                this.openHits++;
            }
            log(String.format("HIT %s (%dt back) took %.2f | blocked %d of %d | swaps %d",
                    up ? "blocked" : "NOT blocked", back, Math.max(0.0F, this.lastHealth - health),
                    this.blockedHits, this.blockedHits + this.openHits, this.swaps));
        }
        this.lastHurt = hurt;
        this.lastHealth = health;
    }

    private void release(String why) {
        this.active = false;
        if (mc.gameSettings != null && mc.currentScreen == null) {
            /* Back to the hand: vanilla lets go (C07) unless right click is really held. */
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        log("DOWN " + why);
    }

    void reset() {
        if (this.active) {
            release("mode off");
        }
        if (this.blinkActive || this.serverBlocked || !this.held.isEmpty()) {
            endBlink("mode off");
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
