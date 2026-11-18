package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

public final class PlaceRotations {
    public static final class State {
        private float lastYaw = Float.NaN;
        private float deltaX;
        private boolean rotated;
        private int pendingPlaces;
        private float lastPlacedDeltaX = -1.0F;

        public synchronized void place() {
            this.pendingPlaces++;
        }

        public synchronized void flying(boolean rotating, float yaw) {
            if (rotating) {
                if (!Float.isNaN(this.lastYaw)) {
                    this.deltaX = Math.abs(yaw - this.lastYaw);
                    this.rotated = true;
                }
                this.lastYaw = yaw;
            }
            if (this.pendingPlaces > 0) {
                if (this.rotated) {
                    this.lastPlacedDeltaX = this.deltaX;
                    this.rotated = false;
                }
                this.pendingPlaces = 0;
            }
        }

        public synchronized boolean wouldDuplicate(float yaw) {
            if (Float.isNaN(this.lastYaw) || this.lastPlacedDeltaX < 0.0F) {
                return false;
            }
            float step = Math.abs(yaw - this.lastYaw);
            return step > 2.0F && Math.abs(step - this.lastPlacedDeltaX) < 1.0E-3F;
        }

        public synchronized void reset() {
            this.lastYaw = Float.NaN;
            this.rotated = false;
            this.pendingPlaces = 0;
            this.lastPlacedDeltaX = -1.0F;
        }
    }

    private static final State STATE = new State();

    public static boolean wouldDuplicate(float yaw) {
        return STATE.wouldDuplicate(yaw);
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            if (((C08PacketPlayerBlockPlacement) event.getPacket()).getPlacedBlockDirection() != 255) {
                STATE.place();
            }
        } else if (event.getPacket() instanceof C03PacketPlayer) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
            STATE.flying(packet.getRotating(), packet.getYaw());
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        STATE.reset();
    }
}
