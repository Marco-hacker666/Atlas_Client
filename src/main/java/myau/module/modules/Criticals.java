//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package myau.module.modules;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.PacketUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Criticals extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Packet", "NCPPacket", "OldBlocksMC", "OldBlocksMC2", "NoGround", "Hop", "TPHop", "Jump", "LowJump", "CustomMotion", "Visual", "Hypixel", "Lag", "Matrix", "Timer"});
    public final IntProperty delay = new IntProperty("delay", 0, 0, 500);
    public final IntProperty hurtTime = new IntProperty("hurt-time", 10, 0, 10);
    public final FloatProperty customMotionY = new FloatProperty("custom-y", 0.2F, 0.01F, 0.42F);
    public final FloatProperty fallDistance = new FloatProperty("fall-distance", 0.1F, 0.0F, 0.42F, () -> mode.getValue() == 5 || mode.getValue() == 11);
    public final BooleanProperty groundSpoof = new BooleanProperty("ground-spoof", true, () -> mode.getValue() == 4);
    public final BooleanProperty hypixelAutoJump = new BooleanProperty("hypixel-autojump", false, () -> mode.getValue() == 11);
    public final FloatProperty hypixelBoost = new FloatProperty("hypixel-boost", 0.1F, 0.0F, 0.5F, () -> mode.getValue() == 11);
    public final IntProperty lagMaxMs = new IntProperty("lag-max-ms", 100, 50, 500, () -> mode.getValue() == 12);
    public final PercentProperty lagChance = new PercentProperty("lag-chance", 90, () -> mode.getValue() == 12);
    public final BooleanProperty lagStopHurt = new BooleanProperty("lag-stop-hurt", true, () -> mode.getValue() == 12);
    public final FloatProperty matrixOffset = new FloatProperty("matrix-offset", 0.0001F, 0.00001F, 0.01F, () -> mode.getValue() == 13);
    public final IntProperty matrixEvery = new IntProperty("matrix-every", 4, 1, 8, () -> mode.getValue() == 13);
    public final FloatProperty timerSpeed = new FloatProperty("timer-speed", 0.5F, 0.1F, 1.0F, () -> mode.getValue() == 14);
    public final IntProperty timerMaxMs = new IntProperty("timer-max-ms", 2000, 100, 3000, () -> mode.getValue() == 14);
    public final PercentProperty timerChance = new PercentProperty("timer-chance", 90, () -> mode.getValue() == 14);
    public final BooleanProperty timerStopHurt = new BooleanProperty("timer-stop-hurt", true, () -> mode.getValue() == 14);
    private final TimerUtil timer = new TimerUtil();
    private int offGroundTicks = 0;
    private long lagStart = -1L;
    private final Queue<Packet<?>> lagQueue = new ConcurrentLinkedQueue<Packet<?>>();
    private boolean flushing = false;
    private int matrixAttacks = 0;
    private long timerStart = -1L;

    public Criticals() {
        super("Criticals", false, false, "Forces critical hits using packets or motion tricks.");
    }

    public void onEnabled() {
        // If NoGround mode (index 4) was selected, attempt a jump on enable to ensure proper state
        if ((Integer) this.mode.getValue() == 4 && mc.thePlayer != null) {
            mc.thePlayer.jump();
        }
        this.offGroundTicks = 0;
        this.lagStart = -1L;
        this.lagQueue.clear();
        this.flushing = false;
        this.matrixAttacks = 0;
        this.timerStart = -1L;
        resetGameTimer();
    }

    @Override
    public void onDisabled() {
        this.flushing = true;
        try {
            Packet<?> packet;
            while ((packet = this.lagQueue.poll()) != null) {
                PacketUtil.sendPacketNoEvent(packet);
            }
        } finally {
            this.flushing = false;
        }
        this.lagStart = -1L;
        this.offGroundTicks = 0;
        this.matrixAttacks = 0;
        this.timerStart = -1L;
        resetGameTimer();
    }

    @Override
    public void verifyValue(String name) {
        if (this.isEnabled() && (Integer) this.mode.getValue() == 4 && mc.thePlayer != null) {
            mc.thePlayer.jump();
        }
    }

    private static void resetGameTimer() {
        try {
            net.minecraft.util.Timer gameTimer = ((IAccessorMinecraft) mc).getTimer();
            if (gameTimer != null) {
                gameTimer.timerSpeed = 1.0F;
            }
        } catch (Exception ignored) {
        }
    }

    /* Packet, NCPPacket and Visual used to call attackTargetEntityWithCurrentItem
       here. The attack path already runs it once, so the client applied the
       hit twice -- sprint slowdown 0.6 x 0.6 -- and, standing on the ground as
       far as the client knows, never drew crit particles anyway. onCriticalHit
       is the particle effect alone. */
    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled()) {
            if (mc.thePlayer != null && mc.theWorld != null) {
                if (event.getTarget() instanceof EntityLivingBase) {
                    EntityLivingBase target = (EntityLivingBase) event.getTarget();
                    if (mc.thePlayer.onGround) {
                        // don't crit while using an item (e.g. eating/bow)
                        if (!mc.thePlayer.isUsingItem()) {
                            if (!mc.thePlayer.isInWater() && !mc.thePlayer.isInLava()) {
                                if (mc.thePlayer.ridingEntity == null) {
                                    if (target.hurtTime <= (Integer) this.hurtTime.getValue()) {
                                        Fly fly = (Fly) Myau.moduleManager.modules.get(Fly.class);
                                        if (fly == null || !fly.isEnabled()) {
                                            if (this.timer.hasTimeElapsed((long) (Integer) this.delay.getValue())) {
                                                double x = mc.thePlayer.posX;
                                                double y = mc.thePlayer.posY;
                                                double z = mc.thePlayer.posZ;
                                                switch ((Integer) this.mode.getValue()) {
                                                    case 0:
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + (double) 0.0625F, z, true));
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                                                        mc.thePlayer.onCriticalHit(target);
                                                        break;
                                                    case 1:
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.11, z, false));
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.1100013579, z, false));
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.3579E-6, z, false));
                                                        mc.thePlayer.onCriticalHit(target);
                                                        break;
                                                    case 2:
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.001091981, z, true));
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                                                        break;
                                                    case 3:
                                                        if (mc.thePlayer.ticksExisted % 4 == 0) {
                                                            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.0011, z, true));
                                                            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                                                        }
                                                        break;
                                                    case 4:
                                                    default:
                                                        break;
                                                    case 5:
                                                        mc.thePlayer.motionY = 0.1;
                                                        mc.thePlayer.fallDistance = (Float) this.fallDistance.getValue();
                                                        mc.thePlayer.onGround = false;
                                                        break;
                                                    case 6:
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.02, z, false));
                                                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.01, z, false));
                                                        mc.thePlayer.setPosition(x, y + 0.01, z);
                                                        break;
                                                    case 7:
                                                        mc.thePlayer.motionY = 0.42;
                                                        break;
                                                    case 8:
                                                        mc.thePlayer.motionY = 0.3425;
                                                        break;
                                                    case 9:
                                                        mc.thePlayer.motionY = (double) (Float) this.customMotionY.getValue();
                                                        break;
                                                    case 10:
                                                        mc.thePlayer.onCriticalHit(target);
                                                        break;
                                                    case 11:
                                                        // Hypixel motion feel ported from donor HypixelCriticals.java, adapted to the Myau attack guard.
                                                        if ((Boolean) this.hypixelAutoJump.getValue() && !mc.thePlayer.isUsingItem()) {
                                                            mc.thePlayer.jump();
                                                        }
                                                        mc.thePlayer.fallDistance = (Float) this.fallDistance.getValue();
                                                        break;
                                                    case 12:
                                                        // Lag window ported from donor LagCriticals.java, arming and packet hold live in onUpdate and onPacket.
                                                        break;
                                                    case 13: {
                                                        // Matrix burst ported from donor MatrixV1Criticals.java with the MatrixSmartCriticals.java every-N-hit gate.
                                                        this.matrixAttacks++;
                                                        if (this.matrixAttacks >= (Integer) this.matrixEvery.getValue()) {
                                                            this.matrixAttacks = 0;
                                                            double offset = (Float) this.matrixOffset.getValue();
                                                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(x, y - offset, z, false));
                                                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(x, y - offset, z, false));
                                                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(x, y - offset, z, false));
                                                            PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, true));
                                                        }
                                                        break;
                                                    }
                                                    case 14:
                                                        // Timer slow-mo ported from donor TimerCriticals.java, window armed in onUpdate.
                                                        break;
                                                }

                                                this.timer.reset();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled()) {
            if (event.getType() == EventType.SEND) {
                int packetMode = (Integer) this.mode.getValue();
                if (packetMode == 4 && (Boolean) this.groundSpoof.getValue() && event.getPacket() instanceof C03PacketPlayer) {
                    ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(false);
                } else if (packetMode == 12 && this.lagStart != -1L && !this.flushing && event.getPacket() instanceof C03PacketPlayer) {
                    event.setCancelled(true);
                    this.lagQueue.add(event.getPacket());
                }

            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        int updateMode = (Integer) this.mode.getValue();
        if (updateMode == 11) {
            if (mc.thePlayer.onGround) {
                this.offGroundTicks = 0;
            } else {
                this.offGroundTicks++;
                if (this.offGroundTicks == 5 && mc.thePlayer.motionY < 0.0) {
                    mc.thePlayer.motionY += (Float) this.hypixelBoost.getValue() * 0.02;
                }
            }
        } else if (updateMode == 12) {
            long now = System.currentTimeMillis();
            if (this.lagStart != -1L) {
                boolean hurt = (Boolean) this.lagStopHurt.getValue() && mc.thePlayer.hurtTime > 0;
                if (mc.thePlayer.onGround || hurt || now - this.lagStart > (Integer) this.lagMaxMs.getValue()) {
                    flushLag();
                    this.lagStart = -1L;
                }
            } else if (mc.thePlayer.motionY < 0.0 && !mc.thePlayer.onGround) {
                if (Math.random() * 100.0 < (Integer) this.lagChance.getValue()) {
                    this.lagStart = now;
                }
            } else if (mc.thePlayer.onGround && !this.lagQueue.isEmpty()) {
                flushLag();
            }
        } else if (updateMode == 14) {
            net.minecraft.util.Timer gameTimer = null;
            try {
                gameTimer = ((IAccessorMinecraft) mc).getTimer();
            } catch (Exception ignored) {
            }
            long now = System.currentTimeMillis();
            if (this.timerStart != -1L) {
                boolean hurt = (Boolean) this.timerStopHurt.getValue() && mc.thePlayer.hurtTime > 0;
                if (mc.thePlayer.onGround || hurt || now - this.timerStart > (Integer) this.timerMaxMs.getValue()) {
                    if (gameTimer != null) {
                        gameTimer.timerSpeed = 1.0F;
                    }
                    this.timerStart = -1L;
                }
            } else if (mc.thePlayer.motionY < 0.0 && !mc.thePlayer.onGround) {
                if (Math.random() * 100.0 < (Integer) this.timerChance.getValue()) {
                    if (gameTimer != null) {
                        gameTimer.timerSpeed = (Float) this.timerSpeed.getValue();
                    }
                    this.timerStart = now;
                }
            }
        }
    }

    private void flushLag() {
        this.flushing = true;
        try {
            Packet<?> packet;
            while ((packet = this.lagQueue.poll()) != null) {
                PacketUtil.sendPacketNoEvent(packet);
            }
        } finally {
            this.flushing = false;
        }
    }

    public String[] getSuffix() {
        String[] modes = new String[]{"Packet", "NCPPacket", "OldBlocksMC", "OldBlocksMC2", "NoGround", "Hop", "TPHop", "Jump", "LowJump", "CustomMotion", "Visual", "Hypixel", "Lag", "Matrix", "Timer"};
        int suffixMode = (Integer) this.mode.getValue();
        if (suffixMode < 0 || suffixMode >= modes.length) {
            return new String[]{"?"};
        }
        return new String[]{modes[suffixMode]};
    }
}
