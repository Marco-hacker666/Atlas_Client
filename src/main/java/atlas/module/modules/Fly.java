package atlas.module.modules;

import com.google.common.base.CaseFormat;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.StrafeEvent;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorC03PacketPlayer;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.util.KeyBindUtil;
import atlas.util.MoveUtil;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

public class Fly extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private double verticalMotion = 0.0;
    private int startY = 0;
    private int flyTicks = 0;
    private int boostTicks = -1;
    private boolean boostActive = false;
    private boolean timerActive = false;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"VANILLA", "VANILLA2", "AIR_WALK", "AIR_PLACE", "VULCAN", "MATRIX", "MATRIX_BOW", "MATRIX_TNT", "FAKE", "TEST", "CUSTOM"});
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 1.0F, 0.0F, 100.0F);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 1.0F, 0.0F, 100.0F);
    public final BooleanProperty groundSpoof = new BooleanProperty("ground-spoof", false, () -> mode.getValue() == 1);
    public final BooleanProperty timerBoost = new BooleanProperty("timer-boost", false, () -> mode.getValue() == 4);
    public final FloatProperty timerSpeed = new FloatProperty("timer-speed", 2.0F, 1.5F, 5.0F, () -> mode.getValue() == 4 && timerBoost.getValue());
    public final FloatProperty boost = new FloatProperty("boost", 1.2F, 1.0F, 1.5F, () -> mode.getValue() == 7);
    public final FloatProperty motionCap = new FloatProperty("motion-cap", -0.2F, -0.4F, 0.0F, () -> mode.getValue() == 9);
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true, () -> mode.getValue() == 7);
    public final BooleanProperty stopAtEnd = new BooleanProperty("stop-at-end", false);

    public Fly() {
        super("Fly", false, false, "Lets you fly using several bypass and vanilla modes.");
    }

    private void placeBlockBelow() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (this.flyTicks % 2 != 0) {
            return;
        }
        BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
        if (!mc.theWorld.isAirBlock(below)) {
            return;
        }
        BlockPos support = below.down();
        if (mc.theWorld.isAirBlock(support)) {
            return;
        }
        int slot = -1;
        for (int i = 8; i >= 0; i--) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            return;
        }
        int prevSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
        mc.playerController.updateController();
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, support, EnumFacing.UP,
                new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5))) {
            mc.thePlayer.swingItem();
        }
        mc.thePlayer.inventory.currentItem = prevSlot;
        mc.playerController.updateController();
    }

    private void pollVerticalKeys() {
        this.verticalMotion = 0.0;
        if (mc.currentScreen == null) {
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                this.verticalMotion = this.verticalMotion + this.vSpeed.getValue().doubleValue() * 0.42F;
            }
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                this.verticalMotion = this.verticalMotion - this.vSpeed.getValue().doubleValue() * 0.42F;
            }
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled()) {
            int m = this.mode.getValue();
            // Donor MatrixBowFly/MatrixTNTFly drive motion directly, keep hands off strafe while boosting.
            if ((m == 6 || m == 7) && this.boostActive) {
                return;
            }
            if (m == 10 || mc.thePlayer.posY % 1.0 != 0.0) {
                mc.thePlayer.motionY = this.verticalMotion;
            }
            MoveUtil.setSpeed(0.0);
            event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.hSpeed.getValue());
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.flyTicks++;
            switch (this.mode.getValue()) {
                case 0: // Donor Vanilla2Fly: classic key-driven fly.
                case 1: // Donor Vanilla2Fly with ground spoof toggle.
                case 10: // Donor CustomFly: raw user speeds, no extra logic.
                    this.pollVerticalKeys();
                    break;
                case 2: // Donor AirWalkFly: hover in place, no block solidity events in Atlas.
                    this.pollVerticalKeys();
                    if (this.verticalMotion == 0.0 && mc.thePlayer.motionY < 0.0) {
                        mc.thePlayer.motionY = 0.0;
                        this.verticalMotion = 0.0;
                    }
                    break;
                case 3: // AirPlace: sink slowly while placing blocks below, jump refreshes.
                    if (mc.thePlayer.onGround) {
                        if (!KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                            mc.thePlayer.jump();
                        }
                        this.verticalMotion = 0.0;
                    } else if (mc.thePlayer.motionY < 0.0) {
                        mc.thePlayer.motionY = -0.12;
                        this.verticalMotion = -0.12;
                        this.placeBlockBelow();
                    }
                    break;
                case 4: // Donor VulcanFly: hover, timer kicks in after a flag.
                    this.pollVerticalKeys();
                    if (this.verticalMotion == 0.0 && mc.thePlayer.motionY < 0.0) {
                        mc.thePlayer.motionY = 0.0;
                        this.verticalMotion = 0.0;
                    }
                    if (this.timerActive && this.timerBoost.getValue()) {
                        ((IAccessorMinecraft) mc).getTimer().timerSpeed = this.timerSpeed.getValue() - (float) (Math.random() / 1000.0);
                    } else if (this.timerActive && ((IAccessorMinecraft) mc).getTimer().timerSpeed != 1.0F) {
                        ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                    }
                    break;
                case 5: // Donor MatrixFly: pin below the takeoff height.
                    if (mc.thePlayer.posY < (double) this.startY) {
                        mc.thePlayer.motionY = 0.0;
                        this.verticalMotion = 0.0;
                        if (MoveUtil.isMoving()) {
                            MoveUtil.setSpeed(MoveUtil.getBaseMoveSpeed() * this.hSpeed.getValue());
                        }
                    } else {
                        this.pollVerticalKeys();
                    }
                    break;
                case 6: // Donor MatrixBowFly: hurt triggers a short glide.
                case 7: // Donor MatrixTNTFly: hurt triggers boosted glide with auto disable.
                    if (!this.boostActive && mc.thePlayer.hurtTime > 0) {
                        this.boostActive = true;
                        this.boostTicks = 0;
                        mc.thePlayer.motionY = 0.6;
                    }
                    if (this.boostActive) {
                        this.boostTicks++;
                        double factor = this.mode.getValue() == 7 ? this.boost.getValue().doubleValue() - Math.random() / 1000.0 : 1.0;
                        mc.thePlayer.motionX *= factor;
                        mc.thePlayer.motionZ *= factor;
                        mc.thePlayer.motionY = 0.01 + (double) this.boostTicks * 0.003;
                        this.verticalMotion = mc.thePlayer.motionY;
                        if (this.boostTicks >= 30) {
                            this.boostActive = false;
                            this.boostTicks = -1;
                            if (this.mode.getValue() == 7 && this.autoDisable.getValue()) {
                                this.setEnabled(false);
                            }
                        }
                    } else {
                        mc.thePlayer.motionY = 0.0;
                        this.verticalMotion = 0.0;
                    }
                    break;
                case 8: // Donor FakeFly: gentle damped glide, no scaffold hook in Atlas.
                    this.pollVerticalKeys();
                    if (!mc.thePlayer.onGround && mc.thePlayer.motionY < -0.15) {
                        mc.thePlayer.motionY = -0.15;
                        this.verticalMotion = -0.15;
                    }
                    break;
                case 9: // Donor TestFly: clamp sink speed.
                    if (!mc.thePlayer.onGround) {
                        mc.thePlayer.motionY = Math.max(mc.thePlayer.motionY, this.motionCap.getValue().doubleValue());
                        this.verticalMotion = mc.thePlayer.motionY;
                    } else {
                        this.verticalMotion = 0.0;
                    }
                    break;
                default:
                    this.pollVerticalKeys();
                    break;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            if (this.isEnabled()) {
                if (this.mode.getValue() == 4) {
                    this.timerActive = true;
                } else if (this.mode.getValue() == 5 || this.mode.getValue() == 6) {
                    this.setEnabled(false);
                }
            }
        } else if (this.isEnabled() && event.getType() == EventType.SEND && event.getPacket() instanceof C03PacketPlayer) {
            if (this.mode.getValue() == 1 && this.groundSpoof.getValue()) {
                ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
            }
        }
    }

    @Override
    public void onEnabled() {
        this.verticalMotion = 0.0;
        this.flyTicks = 0;
        this.boostTicks = -1;
        this.boostActive = false;
        this.timerActive = false;
        if (mc.thePlayer != null) {
            this.startY = (int) mc.thePlayer.posY;
            mc.thePlayer.motionY = 0.0;
        }
    }

    @Override
    public void onDisabled() {
        this.verticalMotion = 0.0;
        this.flyTicks = 0;
        this.boostTicks = -1;
        this.boostActive = false;
        this.timerActive = false;
        ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        if (mc.thePlayer != null) {
            mc.thePlayer.motionY = 0.0;
            MoveUtil.setSpeed(0.0);
            if (this.stopAtEnd.getValue()) {
                mc.thePlayer.motionX = 0.0;
                mc.thePlayer.motionZ = 0.0;
            }
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
        }
    }

    @Override
    public void verifyValue(String name) {
        if (this.isEnabled() && (this.mode.getValue() != 4 || !this.timerBoost.getValue())) {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
