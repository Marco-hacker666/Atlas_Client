package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.AttackEvent;
import atlas.events.MoveInputEvent;
import atlas.events.TickEvent;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.CombatTargeting;
import atlas.util.KeepRangeMath;
import atlas.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;

public class KeepRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int STOP = 0;
    private static final int BACKWARDS = 1;

    public final FloatProperty range = new FloatProperty("range", 3.0F, 0.0F, 6.0F);
    public final BooleanProperty disableNearEdge = new BooleanProperty("disable-near-edge", true);
    public final IntProperty edgeRange = new IntProperty("edge-range", 5, 0, 6, this.disableNearEdge::getValue);
    public final ModeProperty mode = new ModeProperty("mode", STOP, new String[]{"STOP", "BACKWARDS"});
    public final IntProperty comboToStart = new IntProperty("combo-to-start", 2, 0, 6);

    private boolean nearEdge;
    private int comboTicks;
    private int ticksSinceAttack = 999;

    private int activeTicks;

    public KeepRange() {
        super("KeepRange", false, false, "S-tap: stops walking into a target you are comboing");
    }

    @Override
    public void onEnabled() {
        this.nearEdge = false;
        this.comboTicks = 0;
        this.ticksSinceAttack = 999;
        this.activeTicks = 0;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        this.ticksSinceAttack = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.ticksSinceAttack < 999) {
            this.ticksSinceAttack++;
        }
        if (this.activeTicks > 0) {
            this.activeTicks--;
        }
        if (mc.thePlayer.onGround) {
            this.nearEdge = this.disableNearEdge.getValue() && dropNearby(this.edgeRange.getValue());
        }
    }

    private static boolean dropNearby(int reach) {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                boolean open = true;
                for (int dy = -5; dy <= 0 && open; dy++) {
                    open = mc.theWorld.isAirBlock(feet.add(dx, dy, dz));
                }
                if (open) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        EntityPlayer target = CombatTargeting.findClosestTarget(10.0 * 10.0);
        if (target == null || this.nearEdge) {
            this.comboTicks = 0;
            return;
        }
        if (target.hurtTime > 0) {
            this.comboTicks++;
        }
        if (mc.thePlayer.hurtTime > 0) {
            this.comboTicks = 0;
        }
        if (!KeepRangeMath.comboReached(this.comboTicks, this.comboToStart.getValue())) {
            return;
        }

        double keep = this.range.getValue() - (this.ticksSinceAttack <= 7 ? 0.2 : 0.0);
        double distance = Math.sqrt(RotationUtil.distanceSqFromEyeToClosestOnAABB(target));
        if (distance >= keep - 0.05) {
            return;
        }
        MovementInput input = mc.thePlayer.movementInput;
        if (input.moveForward == 0.0F && input.moveStrafe == 0.0F) {
            return;
        }
        float yaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        int[] away = KeepRangeMath.bestAway(yaw, mc.thePlayer.posX - target.posX, mc.thePlayer.posZ - target.posZ);
        float[] out = KeepRangeMath.apply(this.mode.getValue() == BACKWARDS, away, input.moveForward, input.moveStrafe);
        if (out[0] != input.moveForward || out[1] != input.moveStrafe) {
            this.activeTicks = 10;
        }
        input.moveForward = out[0];
        input.moveStrafe = out[1];
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString() + (this.activeTicks > 0 ? " *" : "")};
    }
}
