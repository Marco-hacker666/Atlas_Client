package atlas.module.modules;

import atlas.util.Ping;
import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.Render3DEvent;
import atlas.events.RightClickMouseEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class Blink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty direction = new ModeProperty("direction", 0,
            new String[]{"OUTGOING", "BOTH"});
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"DEFAULT", "PULSE"});

    public final BooleanProperty autoSend = new BooleanProperty("auto-send", false);

    public final IntProperty threshold = new IntProperty("send-threshold", 50, 0, 600,
            this.autoSend::getValue);

    public final FloatProperty maxDrift = new FloatProperty("max-drift", 3.0F, 0.5F, 20.0F);

    public final IntProperty latencyBudget = new IntProperty("latency-budget", 900, 200, 4000);
    public final BooleanProperty pingAware = new BooleanProperty("ping-aware", true);

    public final BooleanProperty releaseOnCorrection = new BooleanProperty("release-on-correction", true);

    public final BooleanProperty autoOff = new BooleanProperty("auto-off", true,
            () -> !this.autoSend.getValue());

    public final IntProperty timeout = new IntProperty("timeout", 1000, 250, 10000,
            () -> !this.autoSend.getValue() && this.autoOff.getValue());
    private long anchoredAt;

    public final IntProperty releaseRate = new IntProperty("release-per-tick", 2, 1, 20);

    public final BooleanProperty holdTransactions = new BooleanProperty("hold-transactions", true);

    public final IntProperty releaseEvery = new IntProperty("release-every", 0, 0, 20);

    public final BooleanProperty releaseOnPlace = new BooleanProperty("release-on-place", true);

    public final BooleanProperty releaseOnAttack = new BooleanProperty("release-on-attack", false);
    public final BooleanProperty releaseOnDig = new BooleanProperty("release-on-dig", false);

    public final BooleanProperty notify = new BooleanProperty("announce-release", true,
            this.autoSend::getValue);
    public final BooleanProperty marker = new BooleanProperty("server-position", true);
    public final BooleanProperty breadcrumbs = new BooleanProperty("breadcrumbs", true);

    private boolean stopRequested;
    private String releaseReason = "";

    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private boolean anchored;
    private boolean releasing;
    private int heldTicks;
    private double drift;
    private final List<double[]> trail = new ArrayList<double[]>();

    public Blink() {
        super("Blink", false, false,
                "Holds outgoing movement and releases it at a rate the server accepts");

        atlas.management.BlinkManager.holdTransactions = this.holdTransactions::getValue;
    }

    private int ping() {
        return Ping.own();
    }

    private int budget() {
        if (!this.pingAware.getValue()) {
            return Integer.MAX_VALUE;
        }
        int ping = ping();
        if (ping <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, this.latencyBudget.getValue() - ping);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!Atlas.blinkManager.getBlinkingModule().equals(BlinkModules.BLINK)) {
            this.setEnabled(false);
            return;
        }

        if (mc.currentScreen != null) {
            this.stopRequested = true;
        }
        if (this.stopRequested) {
            this.stopRequested = false;
            this.releasing = true;
        }

        if (this.releasing) {
            drain();
            return;
        }
        if (!this.anchored) {
            anchor();
        }
        this.heldTicks++;
        if (this.releaseEvery.getValue() > 0 && this.heldTicks % this.releaseEvery.getValue() == 0) {
            Packet<?> oldest = Atlas.blinkManager.blinkedPackets.poll();
            if (oldest != null) {
                Atlas.blinkManager.release(oldest);
                if (oldest instanceof C03PacketPlayer && ((C03PacketPlayer) oldest).isMoving()) {
                    C03PacketPlayer moved = (C03PacketPlayer) oldest;
                    this.anchorX = moved.getPositionX();
                    this.anchorY = moved.getPositionY();
                    this.anchorZ = moved.getPositionZ();
                }
            }
        }

        if (this.breadcrumbs.getValue() && this.heldTicks % 3 == 0
                && (mc.thePlayer.motionX != 0.0 || mc.thePlayer.motionZ != 0.0)) {
            int sign = (this.trail.size() % 2 == 0) ? 1 : -1;
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            this.trail.add(new double[]{
                    mc.thePlayer.posX + 0.2 * sign * Math.cos(yaw),
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ + 0.2 * sign * Math.sin(yaw)});
            while (this.trail.size() > 200) {
                this.trail.remove(0);
            }
        }

        double dx = mc.thePlayer.posX - this.anchorX;
        double dy = mc.thePlayer.posY - this.anchorY;
        double dz = mc.thePlayer.posZ - this.anchorZ;
        this.drift = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (!this.autoSend.getValue()) {
            if (this.autoOff.getValue()) {
                long held = System.currentTimeMillis() - this.anchoredAt;
                boolean timedOut = held >= this.timeout.getValue();
                boolean drifted = this.drift >= this.maxDrift.getValue();
                if (timedOut || drifted) {
                    atlas.util.ChatUtil.sendFormatted(String.format(
                            "&7[&bBlink&7] auto-off on &f%s &8(&f%dms&7, &f%.1f&7 blocks, &f%d&7 packets&8)",
                            timedOut ? "timeout" : "drift", held, this.drift,
                            Atlas.blinkManager.blinkedPackets.size()));
                    this.setEnabled(false);
                }
            }
            return;
        }
        boolean driftReached = this.maxDrift.getValue() > 0.0F
                && this.drift >= this.maxDrift.getValue();
        boolean countReached = this.threshold.getValue() > 0
                && Atlas.blinkManager.blinkedPackets.size() >= this.threshold.getValue();
        boolean budgetSpent = this.pingAware.getValue() && this.heldTicks * 50 >= budget();

        if (driftReached || countReached || budgetSpent) {
            this.releaseReason = driftReached ? "drift"
                    : (countReached ? "packet limit" : "latency budget");
            if (this.notify.getValue()) {
                atlas.util.ChatUtil.sendFormatted(String.format(
                        "&7[&bBlink&7] released on &f%s &8(&f%.1f&7 blocks, &f%d&7 packets&8)",
                        this.releaseReason, this.drift,
                        Atlas.blinkManager.blinkedPackets.size()));
            }
            this.releasing = true;
        }
    }

    private void drain() {
        int allowance = this.releaseRate.getValue();
        while (allowance > 0) {
            Packet<?> packet = Atlas.blinkManager.blinkedPackets.poll();
            if (packet == null) {
                finishRelease();
                return;
            }
            Atlas.blinkManager.release(packet);
            if (packet instanceof C03PacketPlayer) {
                allowance--;
            }
        }
    }

    private void anchor() {
        this.anchorX = mc.thePlayer.posX;
        this.anchorY = mc.thePlayer.posY;
        this.anchorZ = mc.thePlayer.posZ;
        this.anchored = true;
        this.anchoredAt = System.currentTimeMillis();
        this.heldTicks = 0;
        this.drift = 0.0;
        this.trail.clear();
    }

    private void finishRelease() {
        this.releasing = false;
        this.anchored = false;
        this.trail.clear();
        if (this.mode.getValue() == 1) {
            anchor();
            return;
        }
        this.setEnabled(false);
    }

    @EventTarget
    public void onLeftClick(atlas.events.LeftClickMouseEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.objectMouseOver == null
                || Atlas.blinkManager.getBlinkingModule() != BlinkModules.BLINK) {
            return;
        }
        net.minecraft.util.MovingObjectPosition.MovingObjectType hit = mc.objectMouseOver.typeOfHit;
        boolean attack = hit == net.minecraft.util.MovingObjectPosition.MovingObjectType.ENTITY
                && this.releaseOnAttack.getValue();
        boolean dig = hit == net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK
                && this.releaseOnDig.getValue();
        if (!attack && !dig) {
            return;
        }
        if (this.notify.getValue()) {
            atlas.util.ChatUtil.sendFormatted(String.format(
                    "&7[&bBlink&7] released on &f%s &8(&f%.1f&7 blocks, &f%d&7 packets&8)",
                    attack ? "attacking" : "digging", this.drift, Atlas.blinkManager.blinkedPackets.size()));
        }
        this.setEnabled(false);
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled() || !this.releaseOnPlace.getValue() || mc.thePlayer == null
                || Atlas.blinkManager.getBlinkingModule() != BlinkModules.BLINK
                || !atlas.util.ItemUtil.isHoldingBlock() || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (this.notify.getValue()) {
            atlas.util.ChatUtil.sendFormatted(String.format(
                    "&7[&bBlink&7] released on &fplacing a block &8(&f%.1f&7 blocks, &f%d&7 packets&8)",
                    this.drift, Atlas.blinkManager.blinkedPackets.size()));
        }
        this.setEnabled(false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !this.releaseOnCorrection.getValue()) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            Atlas.blinkManager.discardHeld();
            this.setEnabled(false);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !this.anchored) {
            return;
        }
        double renderX = mc.getRenderManager().viewerPosX;
        double renderY = mc.getRenderManager().viewerPosY;
        double renderZ = mc.getRenderManager().viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GL11.glLineWidth(1.6F);

        if (this.breadcrumbs.getValue() && this.trail.size() > 1) {
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int i = 0; i < this.trail.size(); i++) {
                double[] point = this.trail.get(i);

                float along = i / (float) Math.max(1, this.trail.size() - 1);
                if (i == 0) {
                    GlStateManager.color(1.0F, 0.9F, 0.25F, 0.9F);
                } else {
                    GlStateManager.color(0.4F + 0.6F * along, 0.8F - 0.5F * along, 1.0F - 0.6F * along, 0.55F);
                }
                GL11.glVertex3d(point[0] - renderX, point[1] - renderY, point[2] - renderZ);
            }
            GL11.glEnd();
        }

        if (this.marker.getValue()) {
            float heat = (float) Math.min(1.0, this.drift / Math.max(0.1F, this.maxDrift.getValue()));
            GlStateManager.color(0.35F + 0.65F * heat, 1.0F - 0.75F * heat, 0.45F - 0.35F * heat, 0.75F);
            double half = mc.thePlayer.width / 2.0;
            double minX = this.anchorX - half - renderX;
            double minY = this.anchorY - renderY;
            double minZ = this.anchorZ - half - renderZ;
            double maxX = this.anchorX + half - renderX;
            double maxY = this.anchorY + mc.thePlayer.height - renderY;
            double maxZ = this.anchorZ + half - renderZ;
            outline(minX, minY, minZ, maxX, maxY, maxZ);
        }

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void outline(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glBegin(GL11.GL_LINES);
        double[][] edges = {
                {x1, y1, z1, x2, y1, z1}, {x2, y1, z1, x2, y1, z2},
                {x2, y1, z2, x1, y1, z2}, {x1, y1, z2, x1, y1, z1},
                {x1, y2, z1, x2, y2, z1}, {x2, y2, z1, x2, y2, z2},
                {x2, y2, z2, x1, y2, z2}, {x1, y2, z2, x1, y2, z1},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1},
                {x2, y1, z2, x2, y2, z2}, {x1, y1, z2, x1, y2, z2}
        };
        for (double[] edge : edges) {
            GL11.glVertex3d(edge[0], edge[1], edge[2]);
            GL11.glVertex3d(edge[3], edge[4], edge[5]);
        }
        GL11.glEnd();
    }

    @EventTarget(whenDisabled = true)
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        this.anchored = false;
        this.releasing = false;
        this.heldTicks = 0;
        this.drift = 0.0;
        this.stopRequested = false;
        this.releaseReason = "";
        this.trail.clear();
        Atlas.blinkManager.setBlinkState(false, Atlas.blinkManager.getBlinkingModule());
        Atlas.blinkManager.setBlinkState(true, BlinkModules.BLINK);
    }

    @Override
    public void onDisabled() {
        Atlas.blinkManager.setBlinkState(false, BlinkModules.BLINK);
        this.anchored = false;
        this.releasing = false;
        this.trail.clear();
    }

    public boolean isReleasing() {
        return this.releasing;
    }

    @Override
    public String[] getSuffix() {
        if (this.releasing) {
            return new String[]{(this.releaseReason.isEmpty() ? "catching up " : this.releaseReason + " ")
                    + Atlas.blinkManager.blinkedPackets.size()};
        }
        return new String[]{String.format("%.1f/%.1f  %d", this.drift, this.maxDrift.getValue(),
                Atlas.blinkManager.blinkedPackets.size())};
    }
}
