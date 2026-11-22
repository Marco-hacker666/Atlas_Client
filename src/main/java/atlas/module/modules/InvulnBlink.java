package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.management.LagGuard;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.Ping;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;

public class InvulnBlink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int UNKNOWN_PING = 150;
    private static final int VOID_DEPTH = 10;

    public final IntProperty maxTicks = new IntProperty("max-ticks", 8, 1, 9);
    public final IntProperty minTicks = new IntProperty("min-ticks", 2, 1, 9);
    public final IntProperty margin = new IntProperty("margin", 1, 0, 4);
    public final FloatProperty range = new FloatProperty("range", 6.0F, 3.0F, 10.0F);
    public final BooleanProperty releaseOnHit = new BooleanProperty("release-on-hit", true);
    public final IntProperty cooldown = new IntProperty("cooldown", 10, 0, 40);

    private final InvulnBlinkPlan plan = new InvulnBlinkPlan();
    private int lastHurtTime;
    private volatile boolean attacked;
    private boolean freshHit;

    public InvulnBlink() {
        super("InvulnBlink", false);
    }

    @Override
    public void onEnabled() {
        this.plan.reset();
        this.lastHurtTime = mc.thePlayer != null ? mc.thePlayer.hurtTime : 0;
        this.attacked = false;
        this.freshHit = false;
    }

    @Override
    public void onDisabled() {
        this.letGo("off");
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.plan.reset();
        this.lastHurtTime = 0;
        this.attacked = false;
        this.freshHit = false;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.pre();
        } else if (event.getType() == EventType.POST) {
            if (this.plan.holding() && this.releaseOnHit.getValue() && this.attacked) {
                this.letGo("hit");
            }
            this.attacked = false;
        }
    }

    private void pre() {
        if (!this.plan.holding() && Atlas.blinkManager.getBlinkingModule() == BlinkModules.INVULN_BLINK) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.INVULN_BLINK);
        }
        int hurt = mc.thePlayer.hurtTime;
        this.freshHit = hurt > this.lastHurtTime && hurt >= mc.thePlayer.maxHurtTime - 1;
        this.lastHurtTime = hurt;
        InvulnBlinkPlan.Input in = new InvulnBlinkPlan.Input();
        in.freshHit = this.freshHit;
        in.enemyNear = this.enemyNear();
        in.safe = this.safe();
        in.stillHeld = Atlas.blinkManager.getBlinkingModule() == BlinkModules.INVULN_BLINK;
        in.mayHold = Atlas.blinkManager.getBlinkingModule() == BlinkModules.NONE && !Arbiter.catching()
                && LagGuard.mayHold(BlinkModules.INVULN_BLINK.moduleName(), false, null);
        in.attacked = this.attacked;
        int ping = Ping.own();
        in.pingMs = ping < 0 ? UNKNOWN_PING : ping;
        InvulnBlinkPlan.Action action = this.plan.tick(in, this.settings());
        if (action == InvulnBlinkPlan.Action.START) {
            if (!Atlas.blinkManager.setBlinkState(true, BlinkModules.INVULN_BLINK)) {
                this.plan.stop(this.settings(), "refused");
            }
        } else if (action == InvulnBlinkPlan.Action.RELEASE) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.INVULN_BLINK);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            this.attacked = true;
        }
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            mc.addScheduledTask(this::corrected);
        }
    }

    private void corrected() {
        this.plan.stop(this.settings(), "correction");
        if (Atlas.blinkManager != null && Atlas.blinkManager.getBlinkingModule() == BlinkModules.INVULN_BLINK) {
            Atlas.blinkManager.abortForSetback();
        }
    }

    private void letGo(String why) {
        this.plan.stop(this.settings(), why);
        if (Atlas.blinkManager != null && Atlas.blinkManager.getBlinkingModule() == BlinkModules.INVULN_BLINK) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.INVULN_BLINK);
        }
    }

    private InvulnBlinkPlan.Settings settings() {
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        s.maxTicks = this.maxTicks.getValue();
        s.minTicks = this.minTicks.getValue();
        s.margin = this.margin.getValue();
        s.cooldown = this.cooldown.getValue();
        s.releaseOnHit = this.releaseOnHit.getValue();
        return s;
    }

    private boolean enemyNear() {
        double reach = this.range.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.getHealth() <= 0.0F) {
                continue;
            }
            if (TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player) || TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) <= reach) {
                return true;
            }
        }
        return false;
    }

    private boolean safe() {
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava() || mc.thePlayer.isOnLadder() || mc.thePlayer.ridingEntity != null) {
            return false;
        }
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        AxisAlignedBB below = new AxisAlignedBB(box.minX, box.minY - VOID_DEPTH, box.minZ, box.maxX, box.minY,
                box.maxZ);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, below).isEmpty();
    }

    @Override
    public String[] getSuffix() {
        if (this.plan.holding()) {
            return new String[]{this.plan.held() + "/" + this.plan.budget()};
        }
        return new String[0];
    }
}
