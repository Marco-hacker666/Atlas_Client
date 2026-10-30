package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0): speeds up eating and blocking by finishing
// the use action early.
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorEntityPlayer;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;

public class FastUse extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Instant", "NCP", "Matrix"});
    public final IntProperty packets = new IntProperty("packets", 20, 3, 35);

    public FastUse() {
        super("FastUse", false, false, "Speeds up eating and blocking by finishing use faster.");
    }

    private boolean using() {
        return mc.thePlayer.isEating() || mc.thePlayer.isUsingItem();
    }

    private void resetTimer() {
        try {
            atlas.util.TimerControl.release(this);
        } catch (Exception ignored) {
        }
    }

    private void finishUse() {
        ItemStack held = mc.thePlayer.getCurrentEquippedItem();
        if (held != null) {
            ((IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(held.getMaxItemUseDuration() - 1);
        }
        mc.playerController.onStoppedUsingItem(mc.thePlayer);
    }

    private void spamGround(int count) {
        boolean ground = mc.thePlayer.onGround;
        for (int i = 0; i < count; i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(ground));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.using()) {
            if (this.mode.getValue() == 2) {
                this.resetTimer();
            }
            return;
        }
        switch (this.mode.getValue()) {
            case 0:
                this.spamGround(this.packets.getValue());
                this.finishUse();
                break;
            case 1:
                if (mc.thePlayer.getItemInUseDuration() > 14) {
                    this.spamGround(this.packets.getValue());
                    this.finishUse();
                }
                break;
            default:
                atlas.util.TimerControl.set(this, 1.18F);
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer(mc.thePlayer.onGround));
                break;
        }
    }

    @Override
    public void onDisabled() {
        this.resetTimer();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
