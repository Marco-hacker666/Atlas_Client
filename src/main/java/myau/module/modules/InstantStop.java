package myau.module.modules;

import myau.event.EventTarget;
import myau.events.PreMoveInputEvent;
import myau.module.Module;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Stops dead when you let go of W (2026-10-19, Slinky's Instant Stop rebuilt).
 *
 * The tick the forward key comes up on the ground, it works out which single
 * tick of input (back, back-left or back-right) brings the leftover motion
 * closest to zero, and holds those keys for that one tick, as a player
 * tapping S would. Nothing is changed if no tap would help. The keys go back
 * to what is physically held the next tick.
 */
public class InstantStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Returned when no tap brings the motion nearer to zero. */
    static final int NONE = 999;

    private int phase;

    public InstantStop() {
        super("InstantStop", false);
    }

    /** One tick of input added to the motion, as moveFlying adds it. */
    static double[] movement(float yaw, float forward, float sideways, float speed) {
        float f = forward * 0.98F;
        float s = sideways * 0.98F;
        float squared = s * s + f * f;
        if (squared < 1.0E-4F) {
            return new double[]{0.0, 0.0};
        }
        float scale = speed / Math.max(1.0F, (float) Math.sqrt(squared));
        s *= scale;
        f *= scale;
        double angle = yaw * Math.PI / 180.0;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        return new double[]{s * cos - f * sin, f * cos + s * sin};
    }

    /** -1 back-right, 0 back, 1 back-left, or NONE: the tap that leaves the least motion. */
    static int stoppingDirection(double x, double z, float yaw, float speed) {
        double origin = x * x + z * z;
        double best = origin;
        int index = NONE;
        for (int i = 0; i < 3; i++) {
            double[] step = movement(yaw, -1.0F, i - 1, speed);
            double sx = x + step[0];
            double sz = z + step[1];
            double score = sx * sx + sz * sz;
            if (score < best) {
                best = score;
                index = i - 1;
            }
        }
        return index;
    }

    @EventTarget
    public void onPreMoveInput(PreMoveInputEvent event) {
        if (mc.thePlayer == null || !mc.inGameHasFocus) {
            restore();
            return;
        }
        KeyBinding forward = mc.gameSettings.keyBindForward;
        if (this.phase == 2) {
            restore();
        } else if (this.phase != 1) {
            if (forward.isKeyDown()) {
                this.phase = 1;
            }
        } else if (!forward.isKeyDown() && mc.thePlayer.onGround) {
            float speed = mc.thePlayer.getAIMoveSpeed();
            if (mc.thePlayer.isSprinting()) {
                speed = (float) (speed / 1.3);
            }
            int direction = stoppingDirection(mc.thePlayer.motionX, mc.thePlayer.motionZ, mc.thePlayer.rotationYaw, speed);
            this.phase = 0;
            if (direction != NONE) {
                set(mc.gameSettings.keyBindBack, true);
                set(mc.gameSettings.keyBindLeft, direction == 1);
                set(mc.gameSettings.keyBindRight, direction == -1);
                this.phase = 2;
            }
        }
    }

    private static void set(KeyBinding key, boolean pressed) {
        KeyBinding.setKeyBindState(key.getKeyCode(), pressed);
    }

    private void restore() {
        if (this.phase == 2 && mc.gameSettings != null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindBack.getKeyCode());
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindLeft.getKeyCode());
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindRight.getKeyCode());
        }
        this.phase = 0;
    }

    @Override
    public void onDisabled() {
        restore();
    }
}
