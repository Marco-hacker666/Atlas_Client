package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ActionLedger;
import atlas.util.MoveUtil;
import atlas.util.PacketUtil;
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

public class NoItemRelease extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String NAME = "NoItemRelease";

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SWORD", "CONSUMABLE", "ALL"});

    public final BooleanProperty letModuleReleases = new BooleanProperty("let-module-releases", true);

    public final BooleanProperty safeRelease = new BooleanProperty("safe-release", false);

    private static final Gate GATE = new Gate();

    private static volatile boolean withheld;

    public NoItemRelease() {
        super(NAME, false);
    }

    public static boolean filter(Packet<?> packet) {
        if (!(packet instanceof C08PacketPlayerBlockPlacement) && !(packet instanceof C07PacketPlayerDigging)
                && !(packet instanceof net.minecraft.network.play.client.C09PacketHeldItemChange)) {
            return false;
        }
        NoItemRelease self = Atlas.moduleManager == null ? null
                : (NoItemRelease) Atlas.moduleManager.modules.get(NoItemRelease.class);
        if (self == null || !self.isEnabled()) {
            GATE.use(false);
            withheld = false;
            return false;
        }
        if (packet instanceof net.minecraft.network.play.client.C09PacketHeldItemChange) {
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

    public static final class Gate {
        private boolean armed;

        public void use(boolean covered) {
            this.armed = covered;
        }

        public boolean dig(boolean release) {
            boolean drop = this.armed && release;
            this.armed = false;
            return drop;
        }

        public boolean armed() {
            return this.armed;
        }
    }

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
