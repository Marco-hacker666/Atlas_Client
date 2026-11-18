package atlas.module.modules;

import java.util.ArrayList;
import java.util.List;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.Render2DEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.IntProperty;
import atlas.util.client.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

// Holds the knockback you were just sent (adapted from CrewX's Freeze).

public class Freeze extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty maxTimeout = new IntProperty("max-timeout", 300, 50, 600);
    public final BooleanProperty renderTimer = new BooleanProperty("render-timer", false);
    public final ColorProperty color = new ColorProperty("color", 0xD10101);

    private final List<Packet<INetHandlerPlayClient>> held = new ArrayList<Packet<INetHandlerPlayClient>>();
    private boolean holding = false;
    private int heldTicks = 0;
    private boolean sawPositionLook = false;

    public Freeze() {
        super("Freeze", false, false, "Holds incoming knockback so a hit does not move you.");

        atlas.management.PacketHub.registerIncoming("Freeze", () -> {
            synchronized (this.held) {
                return this.held.size();
            }
        }, this::flush);
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.flush();
        this.reset();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.flush();
        this.reset();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Packet<?> packet = event.getPacket();

        if (!this.holding && packet instanceof S08PacketPlayerPosLook) {
            this.sawPositionLook = true;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            if (velocity.getEntityID() == mc.thePlayer.getEntityId()) {
                if (this.sawPositionLook) {
                    this.sawPositionLook = false;
                    return;
                }
                this.holding = true;
            }
        } else if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            if (status.getOpCode() == 2 && mc.thePlayer.equals(status.getEntity(mc.theWorld))) {
                this.holding = true;
            }
        }

        if (this.holding
                && (packet instanceof S12PacketEntityVelocity
                || packet instanceof S32PacketConfirmTransaction
                || packet instanceof S08PacketPlayerPosLook)) {
            if (atlas.management.PacketHub.take(event, "Freeze")) {
                this.hold(packet);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void hold(Packet<?> packet) {
        synchronized (this.held) {
            this.held.add((Packet<INetHandlerPlayClient>) packet);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        this.sawPositionLook = false;
        if (!this.holding) {
            return;
        }
        if (++this.heldTicks >= this.maxTimeout.getValue()) {
            this.flush();
            ChatUtil.display("&cFreeze timed out.");
        }
    }

    private void flush() {
        synchronized (this.held) {
            for (Packet<INetHandlerPlayClient> packet : this.held) {
                try {
                    packet.processPacket(mc.getNetHandler());
                } catch (Exception ignored) {
                }
            }
            this.held.clear();
        }
        this.holding = false;
        this.heldTicks = 0;
    }

    private void reset() {
        this.holding = false;
        this.heldTicks = 0;
        this.sawPositionLook = false;
        synchronized (this.held) {
            this.held.clear();
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.renderTimer.getValue() || !this.holding || mc.currentScreen != null) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        String text = String.valueOf(this.heldTicks);
        int width = mc.fontRendererObj.getStringWidth(text);
        mc.fontRendererObj.drawStringWithShadow(
                text,
                (float) (resolution.getScaledWidth() / 2 - width),
                (float) (resolution.getScaledHeight() / 2 + 8),
                0xFF000000 | this.color.getValue());
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.holding && this.heldTicks > 0 ? String.valueOf(this.heldTicks) : "Ready"};
    }
}
