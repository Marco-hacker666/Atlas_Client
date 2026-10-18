package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.ActionLedger;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/**
 * Does not tell the server that the item was let go (2026-10-19, rebuilt on
 * Slinky's method).
 *
 * The rule is a two-state machine on the packets that actually go out:
 *   - a use-item packet (C08 with an item) arms it when the item is one the
 *     mode covers (swords, consumables, or both; never a bow or a rod, whose
 *     release is the shot or the cast);
 *   - the next dig packet (C07) disarms it, and if that packet is the
 *     release, it is not sent.
 * So the server keeps the sword up or the food in use while the client has
 * stopped; the next use, slot change or dig on the server's side ends it.
 *
 * Why the old version kept failing: it only dropped a release it could
 * prove was the game's own (a window around onStoppedUsingItem plus a stack
 * check), and with "safe-release" on (the default) it sent the release
 * anyway the first tick you moved or sprinted -- which in a fight is
 * always. This version needs no window: it watches the packets themselves.
 *
 * The check runs at the last step of NetworkManager.sendPacket
 * (MixinNetworkManager), after every listener and PlayerStateManager, before
 * Blink or Lag could hold the packet, so Atlas's own bookkeeping still sees
 * the release.
 */
public class NoItemRelease extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String NAME = "NoItemRelease";

    /** Slinky's three: Sword (its default), Consumable, All. */
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SWORD", "CONSUMABLE", "ALL"});
    /**
     * Releases another module sends on purpose (Silent Aura lowering the
     * sword before a hit, NoSlow's modes) still go out. Off: every covered
     * release is dropped, as Slinky does (it has no aura of its own).
     */
    public final BooleanProperty letModuleReleases = new BooleanProperty("let-module-releases", true);
    /**
     * The old safety net, now off by default: send the withheld release
     * before the movement packet of the first tick you move or sprint.
     * Without it Grim may flag NoSlow, since the server thinks the item is
     * still in use while you walk at full speed.
     */
    public final BooleanProperty safeRelease = new BooleanProperty("safe-release", false);

    /** The last use packet was for a covered item: the next release is dropped. */
    private static final Gate GATE = new Gate();
    /** A release was dropped and nothing since has ended the use on the server. */
    private static volatile boolean withheld;

    public NoItemRelease() {
        super(NAME, false);
    }

    /**
     * Last step of MixinNetworkManager.sendPacket; true means the packet is
     * not sent.
     */
    public static boolean filter(Packet<?> packet) {
        if (!(packet instanceof C08PacketPlayerBlockPlacement) && !(packet instanceof C07PacketPlayerDigging)
                && !(packet instanceof net.minecraft.network.play.client.C09PacketHeldItemChange)) {
            return false;
        }
        NoItemRelease self = Myau.moduleManager == null ? null
                : (NoItemRelease) Myau.moduleManager.modules.get(NoItemRelease.class);
        if (self == null || !self.isEnabled()) {
            GATE.use(false);
            withheld = false;
            return false;
        }
        if (packet instanceof net.minecraft.network.play.client.C09PacketHeldItemChange) {
            /* A slot change ends the use on the server. */
            withheld = false;
            return false;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            ItemStack stack = ((C08PacketPlayerBlockPlacement) packet).getStack();
            GATE.use(stack != null && stack.getItem() != null && self.covers(stack.getItem()));
            withheld = false;
            return false;
        }
        C07PacketPlayerDigging dig = (C07PacketPlayerDigging) packet;
        boolean release = dig.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM;
        boolean drop = GATE.dig(release);
        if (drop && self.letModuleReleases.getValue() && ActionLedger.callerModuleExcept(NAME) != null) {
            /* A module sent this one on purpose. */
            drop = false;
        }
        if (drop) {
            withheld = true;
            ActionLedger.note(NAME, ActionLedger.holdKind(packet, true));
        } else {
            withheld = false;
        }
        return drop;
    }

    private boolean covers(Item item) {
        return covers(this.mode.getValue(), item instanceof ItemSword,
                item instanceof ItemBow || item instanceof ItemFishingRod);
    }

    /** Mode 0 swords, 1 everything else, 2 both; never a bow or a rod. */
    public static boolean covers(int mode, boolean sword, boolean bowOrRod) {
        if (bowOrRod) {
            return false;
        }
        switch (mode) {
            case 0:
                return sword;
            case 1:
                return !sword;
            default:
                return true;
        }
    }

    /**
     * The two-state rule on its own: a use arms it for a covered item, the
     * next dig disarms it and is dropped when it is the release.
     */
    public static final class Gate {
        private boolean armed;

        public void use(boolean covered) {
            this.armed = covered;
        }

        /** True: drop this dig packet. */
        public boolean dig(boolean release) {
            boolean drop = this.armed && release;
            this.armed = false;
            return drop;
        }

        public boolean armed() {
            return this.armed;
        }
    }

    /** safe-release: before this tick's movement packet, once the player moves or sprints. */
    @EventTarget(value = Priority.HIGHEST, whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || !withheld || mc.thePlayer == null) {
            return;
        }
        boolean moving = MoveUtil.isForwardPressed() || mc.thePlayer.isSprinting()
                || mc.gameSettings.keyBindSprint.isKeyDown();
        if (!this.isEnabled() || this.safeRelease.getValue() && moving) {
            withheld = false;
            PacketUtil.sendPacket(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        }
    }

    @Override
    public void onEnabled() {
        GATE.use(false);
        withheld = false;
    }

    @Override
    public void onDisabled() {
        GATE.use(false);
    }
}
