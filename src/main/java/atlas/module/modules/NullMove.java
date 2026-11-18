package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.PreMoveInputEvent;
import atlas.module.Module;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

public class NullMove extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int[] OPPOSITE = {2, 3, 0, 1};

    private final int[] stamps = new int[4];

    public NullMove() {
        super("NullMove", false);
    }

    private static KeyBinding[] keys() {
        return new KeyBinding[]{mc.gameSettings.keyBindForward, mc.gameSettings.keyBindLeft,
                mc.gameSettings.keyBindBack, mc.gameSettings.keyBindRight};
    }

    @EventTarget
    public void onPreMoveInput(PreMoveInputEvent event) {
        if (mc.thePlayer == null || !mc.inGameHasFocus) {
            return;
        }
        KeyBinding[] keys = keys();
        int stamp = mc.thePlayer.ticksExisted + 1;
        for (int direction = 0; direction < 4; direction++) {
            int previous = this.stamps[direction];
            KeyBinding other = keys[OPPOSITE[direction]];
            int next = 0;
            if (keys[direction].isKeyDown()) {
                next = stamp;
                if (previous == 0 && other.isKeyDown()) {
                    KeyBinding.setKeyBindState(other.getKeyCode(), false);
                }
            } else if (previous != 0 && KeyBindUtil.isKeyDown(other.getKeyCode())) {
                KeyBinding.setKeyBindState(other.getKeyCode(), true);
            }
            this.stamps[direction] = next;
        }
    }

    @Override
    public void onDisabled() {
        if (mc.gameSettings != null) {
            for (KeyBinding key : keys()) {
                KeyBindUtil.updateKeyState(key.getKeyCode());
            }
        }
        java.util.Arrays.fill(this.stamps, 0);
    }
}
