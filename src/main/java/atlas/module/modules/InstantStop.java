package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.PreMoveInputEvent;
import atlas.module.Module;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

public class InstantStop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    static final int NONE = 999;

    private int phase;

    public InstantStop() {
        super("InstantStop", false);
    }

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
