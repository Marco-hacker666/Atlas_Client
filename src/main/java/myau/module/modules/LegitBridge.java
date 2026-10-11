package myau.module.modules;

// Combined legit-bridging helper: Eagle + BridgeAssist + LegitScaffold in one
// module so the three never fight over the sneak key.
//
// Sneak assist is BridgeAssist's logic verbatim (Normal / Silent / Telly,
// edge-distance scaled onto the predicted motion, sneak-ticks hold, telly
// auto-jumps, silent pitch hold), with Eagle's Arbiter guard folded into
// shouldSneak. It drives movementInput.sneak only.
// Auto-place is LegitScaffold's logic verbatim (below-feet air check plus the
// support-below check, right-click on the UP face, place-delay timer).
//
// LegitScaffold's own sneak (the physical key via KeyBindUtil) is
// intentionally NOT carried over: with the assist path sneaking already,
// holding the real sneak key as well would stack slowdowns and desync the
// sneak state. LegitScaffold's BOTH / SNEAK / PLACE mode is the two toggles
// sneak-assist + auto-place instead.
//
// Enabling this switches Eagle / BridgeAssist / LegitScaffold off, and those
// three switch this off in return: two sneakers would stack the 0.3x
// slowdown twice and fight over sneakDelay.
import java.util.Objects;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.management.Arbiter;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;

public class LegitBridge extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int sneakDelay = 0;
    private int holdTicks = 0;
    private int tellyJumpTicks = 0;
    private final TimerUtil placeTimer = new TimerUtil();

    public final ModeProperty assistMode = new ModeProperty("assist-mode", 0, new String[]{"Normal", "Silent", "Telly"});
    public final BooleanProperty sneakAssist = new BooleanProperty("sneak-assist", true);
    public final BooleanProperty autoPlace = new BooleanProperty("auto-place", true);
    public final FloatProperty edgeDistance = new FloatProperty("edge-distance", 0.8F, 0.2F, 2.0F, () -> sneakAssist.getValue());
    public final IntProperty minDelay = new IntProperty("min-delay", 2, 0, 10, () -> sneakAssist.getValue() && assistMode.getValue() != 1);
    public final IntProperty maxDelay = new IntProperty("max-delay", 3, 0, 10, () -> sneakAssist.getValue() && assistMode.getValue() != 1);
    public final IntProperty sneakTicks = new IntProperty("sneak-ticks", 3, 0, 10, () -> sneakAssist.getValue() && assistMode.getValue() != 1);
    public final IntProperty placeDelay = new IntProperty("place-delay", 150, 0, 500, () -> autoPlace.getValue());
    public final FloatProperty silentPitch = new FloatProperty("silent-pitch", 80.0F, 70.0F, 90.0F, () -> sneakAssist.getValue() && assistMode.getValue() == 1);
    public final BooleanProperty blocksOnly = new BooleanProperty("blocks-only", true);
    public final BooleanProperty directionCheck = new BooleanProperty("direction-check", true);
    public final BooleanProperty jumpCheck = new BooleanProperty("jump-check", true);
    public final BooleanProperty pitchCheck = new BooleanProperty("pitch-check", true);
    public final BooleanProperty sneakingOnly = new BooleanProperty("sneaking-only", false);
    public final BooleanProperty tellyFix = new BooleanProperty("telly-fix", false);
    public final IntProperty tellyJumpDelay = new IntProperty("telly-jump-delay", 6, 2, 20, () -> sneakAssist.getValue() && assistMode.getValue() == 2);

    public LegitBridge() {
        super("LegitBridge", false, false, "Bridging 3-in-1: edge sneak assist plus auto-place under your feet.");
    }

    private boolean canMoveSafely() {
        double[] offset = MoveUtil.predictMovement();
        float scale = this.edgeDistance.getValue();
        return PlayerUtil.canMove(mc.thePlayer.motionX + offset[0] * scale, mc.thePlayer.motionZ + offset[1] * scale);
    }

    private boolean isTellying() {
        return mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F;
    }

    private boolean shouldSneak() {
        if (Arbiter.catching() || Arbiter.viewHeld()) {
            /* The catch has the camera and the keys; see Arbiter. */
            return false;
        } else if (this.directionCheck.getValue() && mc.gameSettings.keyBindForward.isKeyDown()) {
            return false;
        } else if (this.jumpCheck.getValue() && mc.gameSettings.keyBindJump.isKeyDown()) {
            return false;
        } else if (this.pitchCheck.getValue() && mc.thePlayer.rotationPitch < 69.0F) {
            return false;
        } else if (this.sneakingOnly.getValue() && !Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
            return false;
        } else if (this.tellyFix.getValue() && this.isTellying()) {
            return false;
        } else {
            return (!this.blocksOnly.getValue() || ItemUtil.isHoldingBlock()) && mc.thePlayer.onGround;
        }
    }

    /** Below-feet air check, verbatim from LegitScaffold: the place trigger. */
    private boolean isOverEdge() {
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        return mc.theWorld.isAirBlock(below);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (this.sneakAssist.getValue() && this.assistMode.getValue() != 1) {
            if (this.sneakDelay > 0) {
                this.sneakDelay--;
            }
            if (this.holdTicks > 0) {
                this.holdTicks--;
            }
            if (this.sneakDelay == 0 && this.canMoveSafely()) {
                this.sneakDelay = RandomUtil.nextInt(this.minDelay.getValue(), this.maxDelay.getValue());
            }
            if (!this.canMoveSafely() && this.sneakTicks.getValue() > 0) {
                this.holdTicks = this.sneakTicks.getValue();
            }
            if (this.assistMode.getValue() == 2) {
                if (this.tellyJumpTicks > 0) {
                    this.tellyJumpTicks--;
                }
                if (this.tellyJumpTicks == 0 && mc.thePlayer.onGround && this.canMoveSafely()) {
                    mc.thePlayer.jump();
                    this.tellyJumpTicks = this.tellyJumpDelay.getValue();
                }
            }
        }
        if (this.autoPlace.getValue()) {
            this.tryPlace();
        }
    }

    /** Auto-place under the feet, verbatim from LegitScaffold. */
    private void tryPlace() {
        if (mc.currentScreen != null) {
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        if (!mc.thePlayer.onGround) {
            return;
        }
        if (!this.isOverEdge()) {
            return;
        }
        if (!this.placeTimer.hasTimeElapsed(this.placeDelay.getValue().longValue())) {
            return;
        }
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!mc.theWorld.isAirBlock(below)) {
            return;
        }
        BlockPos support = below.down();
        if (mc.theWorld.isAirBlock(support)) {
            return;
        }
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, support, EnumFacing.UP,
                new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5))) {
            mc.thePlayer.swingItem();
            this.placeTimer.reset();
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || !this.sneakAssist.getValue() || mc.currentScreen != null) {
            return;
        }
        if (this.sneakingOnly.getValue() && Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()) && this.shouldSneak()) {
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.movementInput.moveForward /= 0.3F;
            mc.thePlayer.movementInput.moveStrafe /= 0.3F;
        }
        if (!mc.thePlayer.movementInput.sneak && this.shouldSneak()) {
            boolean edge = this.assistMode.getValue() == 1 ? !this.canMoveSafely() : (this.sneakDelay > 0 || this.holdTicks > 0 || this.canMoveSafely());
            if (edge) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || !this.sneakAssist.getValue() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.assistMode.getValue() != 1 || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null || !ItemUtil.isHoldingBlock()) {
            return;
        }
        if (mc.thePlayer.rotationPitch < 70.0F || mc.thePlayer.movementInput.moveForward > 0.0F) {
            return;
        }
        float targetPitch = Math.min(this.silentPitch.getValue(), 90.0F);
        event.setRotation(mc.thePlayer.rotationYaw, targetPitch, 2);
    }

    @Override
    public void onEnabled() {
        this.sneakDelay = 0;
        this.holdTicks = 0;
        this.tellyJumpTicks = 0;
        this.placeTimer.reset();
        disableConflicting();
    }

    @Override
    public void onDisabled() {
        this.sneakDelay = 0;
        this.holdTicks = 0;
        this.tellyJumpTicks = 0;
    }

    /** One bridging helper at a time: the sources this was combined from. */
    private void disableConflicting() {
        disable(Eagle.class);
        disable(BridgeAssist.class);
        disable(LegitScaffold.class);
    }

    private static void disable(Class<?> type) {
        if (Myau.moduleManager == null) {
            return;
        }
        Module other = Myau.moduleManager.modules.get(type);
        if (other != null && other.isEnabled()) {
            other.setEnabled(false);
        }
    }

    /** Called by Eagle / BridgeAssist / LegitScaffold so enabling one switches this off. */
    public static void yieldTo(Class<?> type) {
        if (Myau.moduleManager == null) {
            return;
        }
        Module combined = Myau.moduleManager.modules.get(LegitBridge.class);
        if (combined != null && combined.isEnabled()) {
            combined.setEnabled(false);
        }
    }

    @Override
    public void verifyValue(String name) {
        if (Objects.equals(name, "min-delay") && this.minDelay.getValue() > this.maxDelay.getValue()) {
            this.maxDelay.setValue(this.minDelay.getValue());
        } else if (Objects.equals(name, "max-delay") && this.minDelay.getValue() > this.maxDelay.getValue()) {
            this.minDelay.setValue(this.maxDelay.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        String suffix = this.assistMode.getModeString();
        if (this.autoPlace.getValue()) {
            suffix += "+Place";
        }
        return new String[]{suffix};
    }
}
