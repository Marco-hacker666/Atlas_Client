package atlas.module.modules;

import com.google.common.base.CaseFormat;
import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorC03PacketPlayer;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.util.*;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public class NoFall extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil packetDelayTimer = new TimerUtil();
    private final TimerUtil scoreboardResetTimer = new TimerUtil();
    private boolean slowFalling = false;
    private boolean lastOnGround = false;
    private int lastMlgSlot = -1;
    private boolean mlgPlaced = false;
    private int timerTicks = 0;
    private int timerSettick = 0;
    private double timerRepdist = 0.0;
    private int voidCacheTick = -1;
    private boolean voidCacheResult = true;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"PACKET", "BLINK", "NO_GROUND", "SPOOF", "LEGIT", "VULCAN", "TIMER", "OnGround1", "OnGround2", "MatrixSpoof", "HypixelNoGround"});
    public final FloatProperty distance = new FloatProperty("distance", 3.0F, 0.0F, 20.0F);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 10000);
    public final FloatProperty vulcanFall = new FloatProperty("vulcan-fall", 7.0F, 3.0F, 12.0F, () -> mode.getValue() == 5);
    public final IntProperty spoofTicks = new IntProperty("spoof-ticks", 5, 1, 20, () -> mode.getValue() == 6);

    private boolean canTrigger() {
        return this.scoreboardResetTimer.hasTimeElapsed(3000) && this.packetDelayTimer.hasTimeElapsed(this.delay.getValue().longValue());
    }

    public NoFall() {
        super("NoFall", false, false, "Prevents fall damage using packet tricks.");
    }

    @EventTarget(value = Priority.HIGH, whenDisabled = true)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            /* A correction: the server has put this player somewhere. BLINK's
               held fall describes where they were, and onDisabled() released
               it -- the whole fall, sent after the teleport, judged from the
               new position (2026-10-04). Dropped instead, replies still sent
               in order, as Blink does. On the client thread, which runs it
               before vanilla's own handling of this S08 (scheduled after). */
            mc.addScheduledTask(() -> {
                if (Atlas.blinkManager.getBlinkingModule() == BlinkModules.NO_FALL) {
                    Atlas.blinkManager.discardHeld();
                }
                this.onDisabled();
            });
        } else if (this.isEnabled() && event.getType() == EventType.SEND && !event.isCancelled()) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
                switch (this.mode.getValue()) {
                    case 0:
                        if (this.slowFalling) {
                            this.slowFalling = false;
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                        } else if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                this.slowFalling = true;
                                ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.5F;
                            }
                        }
                        break;
                    case 1:
                        boolean allowed = !mc.thePlayer.isOnLadder() && !mc.thePlayer.capabilities.allowFlying && mc.thePlayer.hurtTime == 0;
                        if (Atlas.blinkManager.getBlinkingModule() != BlinkModules.NO_FALL) {
                            if (this.lastOnGround
                                    && !packet.isOnGround()
                                    && allowed
                                    && PlayerUtil.canFly(this.distance.getValue().intValue())
                                    && mc.thePlayer.motionY < 0.0) {
                                Atlas.blinkManager.setBlinkState(false, Atlas.blinkManager.getBlinkingModule());
                                Atlas.blinkManager.setBlinkState(true, BlinkModules.NO_FALL);
                            }
                        } else if (!allowed) {
                            Atlas.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed player check!&r", Atlas.clientName, this.getName()));
                        } else if (PlayerUtil.checkInWater(mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0))) {
                            Atlas.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed void check!&r", Atlas.clientName, this.getName()));
                        } else if (packet.isOnGround()) {
                            for (Packet<?> blinkedPacket : Atlas.blinkManager.blinkedPackets) {
                                if (blinkedPacket instanceof C03PacketPlayer) {
                                    ((IAccessorC03PacketPlayer) blinkedPacket).setOnGround(true);
                                }
                            }
                            Atlas.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            this.packetDelayTimer.reset();
                        }
                        this.lastOnGround = packet.isOnGround() && allowed && this.canTrigger();
                        break;
                    case 2:
                        ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                        break;
                    case 3:
                        if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                                mc.thePlayer.fallDistance = 0.0F;
                            }
                        }
                        break;
                    case 4:
                        // Donor LegitNoFall (MiauMinus): water-bucket clutch handled on tick.
                        break;
                    case 5:
                        // Donor VulcanNoFall (MiauMinus): spoof ground once past the fall threshold.
                        if (!packet.isOnGround() && mc.thePlayer.fallDistance > this.vulcanFall.getValue() && this.canTrigger()) {
                            this.packetDelayTimer.reset();
                            ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                            mc.thePlayer.fallDistance = 0.0F;
                            mc.thePlayer.motionY = 0.0;
                        }
                        break;
                    case 6: {
                        // Donor TIMER block inside MiauMinus NoFall: re-timed ground spoofs while falling.
                        float fallDist = mc.thePlayer.fallDistance;
                        if (fallDist >= 1.51F && this.timerRepdist == 0.0 && !this.isOverVoid()) {
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.45F;
                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                            this.timerSettick = this.timerTicks + 1;
                            this.timerRepdist = fallDist;
                        }
                        if (fallDist - this.timerRepdist >= 1.51F && !this.isOverVoid()) {
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.6F;
                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                            this.timerSettick = this.timerTicks + this.spoofTicks.getValue();
                            this.timerRepdist = fallDist;
                        }
                        if (mc.thePlayer.onGround) {
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                            this.timerRepdist = 0.0;
                        }
                        break;
                    }
                    case 7:
                        ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                        mc.thePlayer.fallDistance = 0.0F;
                        break;
                    case 8:
                        if (!packet.isOnGround() && mc.thePlayer.fallDistance > 3.0F && this.canTrigger()) {
                            this.packetDelayTimer.reset();
                            ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                            mc.thePlayer.fallDistance = 0.0F;
                        }
                        break;
                    case 9:
                        if (!packet.isOnGround() && mc.thePlayer.fallDistance > 3.0F && this.timerTicks % 2 == 0 && this.canTrigger()) {
                            this.packetDelayTimer.reset();
                            ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                            mc.thePlayer.fallDistance = 0.0F;
                        }
                        break;
                    case 10:
                        if (mc.thePlayer.fallDistance > 3.0F && this.canTrigger() && !this.isOverVoid()) {
                            this.packetDelayTimer.reset();
                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                            mc.thePlayer.fallDistance = 0.0F;
                        } else {
                            ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                        }
                        break;
            }
        }
    }
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.timerTicks++;
            if (ServerUtil.hasPlayerCountInfo()) {
                this.scoreboardResetTimer.reset();
            }
            if (this.mode.getValue() == 0 && this.slowFalling) {
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                mc.thePlayer.fallDistance = 0.0F;
            }
            if (this.mode.getValue() == 4) {
                this.handleLegitMlg();
            }
            if (this.mode.getValue() == 6) {
                if (this.timerTicks == this.timerSettick) {
                    ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                }
                if (this.timerTicks >= this.timerSettick) {
                    this.timerSettick = 0;
                }
            }
        }
    }

    private boolean isOverVoid() {
        if (this.voidCacheTick == this.timerTicks) {
            return this.voidCacheResult;
        }
        boolean result = true;
        if (mc.thePlayer != null && mc.theWorld != null) {
            int x = MathHelper.floor_double(mc.thePlayer.posX);
            int z = MathHelper.floor_double(mc.thePlayer.posZ);
            for (int y = MathHelper.floor_double(mc.thePlayer.posY); y > -1; y--) {
                if (!mc.theWorld.isAirBlock(new BlockPos(x, y, z))) {
                    result = false;
                    break;
                }
            }
        }
        this.voidCacheTick = this.timerTicks;
        this.voidCacheResult = result;
        return result;
    }

    private void handleLegitMlg() {
        if (mc.thePlayer != null && mc.theWorld != null && mc.playerController != null) {
            if (!mc.thePlayer.onGround && !mc.thePlayer.capabilities.isFlying && !mc.thePlayer.isInWater() && !mc.thePlayer.isOnLadder()) {
                if (!(mc.thePlayer.fallDistance < this.distance.getValue()) && !(mc.thePlayer.motionY >= -0.1)) {
                    int waterSlot = this.findWaterBucketSlot();
                    if (waterSlot != -1) {
                        BlockPos target = this.findMlgTarget();
                        if (target != null) {
                            if (this.lastMlgSlot == -1) {
                                this.lastMlgSlot = mc.thePlayer.inventory.currentItem;
                            }
                            mc.thePlayer.inventory.currentItem = waterSlot;
                            mc.playerController.updateController();
                            mc.thePlayer.rotationPitch = 90.0F;
                            if (!this.mlgPlaced && mc.thePlayer.getDistance(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= mc.playerController.getBlockReachDistance() + 1.5F) {
                                Vec3 hitVec = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
                                ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
                                if (stack != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, target, EnumFacing.UP, hitVec)) {
                                    mc.thePlayer.swingItem();
                                    this.mlgPlaced = true;
                                }
                            }
                        }
                    }
                }
            } else {
                this.resetLegitMlg();
            }
        }
    }

    private int findWaterBucketSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.water_bucket) {
                return i;
            }
        }
        return -1;
    }

    private BlockPos findMlgTarget() {
        BlockPos playerPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        for (int y = 1; y <= 6; y++) {
            BlockPos pos = playerPos.down(y);
            if (!mc.theWorld.isAirBlock(pos) && mc.theWorld.isAirBlock(pos.up())) {
                return pos;
            }
        }
        MovingObjectPosition ray = mc.theWorld.rayTraceBlocks(
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ),
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY - mc.playerController.getBlockReachDistance() - 2.0, mc.thePlayer.posZ), false, true, false);
        return ray != null && ray.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? ray.getBlockPos() : null;
    }

    private void resetLegitMlg() {
        if (this.lastMlgSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = this.lastMlgSlot;
            mc.playerController.updateController();
        }
        this.lastMlgSlot = -1;
        this.mlgPlaced = false;
    }

    /**
     * A blink held through a world change belongs to the world that is gone
     * (2026-10-11); releasing it here is the same cleanup a disable does, and
     * it is what keeps a held fall from being replayed into the next server.
     */
    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.onDisabled();
    }

    @Override
    public void onDisabled() {
        this.lastOnGround = false;
        this.resetLegitMlg();
        Atlas.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
        if (this.slowFalling) {
            this.slowFalling = false;
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        }
        this.timerTicks = 0;
        this.timerSettick = 0;
        this.timerRepdist = 0.0;
        this.voidCacheTick = -1;
        ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
