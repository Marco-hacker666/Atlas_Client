package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

public class FreeLook extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty holdMode = new BooleanProperty("hold-mode", true);
    public final BooleanProperty thirdPerson = new BooleanProperty("third-person", true);

    private float cameraYaw;
    private float cameraPitch;

    private float prevCameraYaw;
    private float prevCameraPitch;

    private boolean active;
    private int restorePerspective;

    public FreeLook() {
        super("FreeLook", false, false,
                "Look around without turning your body, while the key is held");
    }

    @Override
    public void onDisabled() {
        this.stop();
    }

    private boolean holding() {
        int key = this.getKey();
        return key == Keyboard.KEY_NONE || Keyboard.isKeyDown(key);
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }

        if (!this.isEnabled()) {
            if (this.active) {
                this.stop();
            }
            return;
        }

        if (this.holdMode.getValue() && !this.holding()) {
            this.stop();
            this.setEnabled(false);
            return;
        }
        boolean wanted = mc.thePlayer != null && mc.currentScreen == null && this.holding();
        if (wanted && !this.active) {
            this.start();
        } else if (!wanted && this.active) {
            this.stop();
        }
    }

    private void start() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        this.cameraYaw = player.rotationYaw;
        this.cameraPitch = player.rotationPitch;
        this.prevCameraYaw = this.cameraYaw;
        this.prevCameraPitch = this.cameraPitch;
        this.restorePerspective = mc.gameSettings.thirdPersonView;
        if (this.thirdPerson.getValue() && mc.gameSettings.thirdPersonView == 0) {
            mc.gameSettings.thirdPersonView = 1;
        }
        this.active = true;
    }

    private void stop() {
        if (!this.active) {
            return;
        }
        this.active = false;

        if (this.thirdPerson.getValue()) {
            mc.gameSettings.thirdPersonView = this.restorePerspective;
        }
    }

    public boolean isFreeLooking() {
        return this.active && mc.thePlayer != null;
    }

    public void updateCamera(float yawDelta, float pitchDelta) {
        this.prevCameraYaw = this.cameraYaw;
        this.prevCameraPitch = this.cameraPitch;
        this.cameraYaw += yawDelta * 0.15F;
        this.cameraPitch = MathHelper.clamp_float(this.cameraPitch - pitchDelta * 0.15F, -90.0F, 90.0F);
    }

    public float getCameraYaw() {
        return this.cameraYaw;
    }

    public float getCameraPitch() {
        return this.cameraPitch;
    }

    public float getPrevCameraYaw() {
        return this.prevCameraYaw;
    }

    public float getPrevCameraPitch() {
        return this.prevCameraPitch;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.holdMode.getValue() ? "hold" : "toggle"};
    }
}
