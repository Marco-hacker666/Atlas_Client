package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LeftClickMouseEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.AsyncLog;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class FightBrain extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    private static final double ATTRIBUTE_RANGE = 6.0;

    public final BooleanProperty clickGate = new BooleanProperty("click-gate", true);
    public final IntProperty maxHold = new IntProperty("max-hold", 6, 1, 12, this.clickGate::getValue);
    public final BooleanProperty forceKbDelay = new BooleanProperty("force-kb-delay", true);
    public final IntProperty comboedHits = new IntProperty("comboed-hits", 2, 2, 4);
    public final IntProperty memory = new IntProperty("memory", 25, 10, 60);
    public final FloatProperty range = new FloatProperty("range", 8.0F, 4.0F, 16.0F);
    public final ModeProperty log = new ModeProperty("log", 1, new String[]{"OFF", "FILE"});

    private static volatile FightPhase.Phase shared = FightPhase.Phase.IDLE;

    private final FightPhase fight = new FightPhase();
    private final Map<Integer, Integer> lastHurt = new HashMap<Integer, Integer>();
    private int tick;
    private int ourLastHurt;
    private int held;
    private FightPhase.Phase loggedPhase = FightPhase.Phase.IDLE;
    private int dropped;

    private boolean lastAttackCrit;
    private int opened;
    private File logFile;

    public FightBrain() {
        super("FightBrain", false);
    }

    public static FightPhase.Phase phase() {
        return shared;
    }

    public static boolean forcesKnockbackDelay() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(FightBrain.class);
        return module instanceof FightBrain && module.isEnabled() && ((FightBrain) module).forceKbDelay.getValue()
                && shared == FightPhase.Phase.COMBOED;
    }

    @Override
    public void onEnabled() {
        this.fight.reset();
        this.lastHurt.clear();
        this.held = 0;
        shared = FightPhase.Phase.IDLE;
    }

    @Override
    public void onDisabled() {
        shared = FightPhase.Phase.IDLE;
        this.summary("off");
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.fight.reset();
        this.lastHurt.clear();
        this.held = 0;
        shared = FightPhase.Phase.IDLE;
    }

    @Override
    public String[] getSuffix() {
        FightPhase.Phase p = this.fight.phase();
        return p == FightPhase.Phase.IDLE ? new String[0] : new String[]{p.name()};
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled() || mc.theWorld == null) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }
            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target instanceof EntityPlayer && opponent((EntityPlayer) target)) {
                this.fight.attackSent(target.getEntityId(), this.tick);
                this.lastAttackCrit = critical();
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            return;
        }
        this.tick++;
        boolean near = false;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || !opponent(player)) {
                continue;
            }
            Integer before = this.lastHurt.put(player.getEntityId(), player.hurtTime);
            if (before != null && player.hurtTime > before) {
                this.fight.opponentHurt(player.getEntityId(), this.tick);
            }
            if (!player.isDead && mc.thePlayer.getDistanceToEntity(player) <= this.range.getValue()) {
                near = true;
            }
        }
        int hurt = mc.thePlayer.hurtTime;
        if (hurt > this.ourLastHurt && attackerNear()) {
            this.fight.weWereHit(this.tick);
        }
        this.ourLastHurt = hurt;
        FightPhase.Phase phase = this.fight.tick(this.tick, near, this.memory.getValue(), this.comboedHits.getValue());
        shared = phase;
        if (phase != this.loggedPhase) {
            note(this.loggedPhase + " -> " + phase + " | us " + this.fight.ourStreak() + " them "
                    + this.fight.theirStreak());
            if (phase == FightPhase.Phase.IDLE) {
                this.summary("fight over");
            }
            this.loggedPhase = phase;
        }
    }

    private boolean attackerNear() {
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player != mc.thePlayer && opponent(player) && !player.isDead
                    && mc.thePlayer.getDistanceToEntity(player) <= ATTRIBUTE_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static boolean opponent(EntityPlayer player) {
        return player != mc.thePlayer && !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player)
                && !TeamUtil.isBot(player);
    }

    @EventTarget(Priority.HIGH)
    public void onClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || !this.clickGate.getValue() || event.isCancelled() || mc.thePlayer == null
                || mc.theWorld == null || this.standsDown()) {
            return;
        }
        FightPhase.Phase phase = this.fight.phase();
        if (phase != FightPhase.Phase.COMBO && phase != FightPhase.Phase.TRADE) {
            this.held = 0;
            return;
        }
        Entity hit = mc.objectMouseOver != null ? mc.objectMouseOver.entityHit : null;
        if (!(hit instanceof EntityPlayer) || !opponent((EntityPlayer) hit)) {
            return;
        }
        EntityLivingBase target = (EntityLivingBase) hit;
        if (!this.closingIn(target)) {
            this.held = 0;
            return;
        }
        if (critical() && !this.lastAttackCrit && target.hurtTime > 0) {
            this.held = 0;
            return;
        }
        if (this.until(target) <= 1.0F || this.held >= this.maxHold.getValue()) {
            if (this.held > 0) {
                this.opened++;
            }
            this.held = 0;
            return;
        }
        event.setCancelled(true);
        this.held++;
        this.dropped++;
    }

    private static boolean critical() {
        return mc.thePlayer.fallDistance > 0.0F && !mc.thePlayer.onGround && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater() && !mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.blindness)
                && mc.thePlayer.ridingEntity == null;
    }

    private boolean standsDown() {
        Module hitSelect = Atlas.moduleManager.modules.get(HitSelect.class);
        if (hitSelect instanceof HitSelect && hitSelect.isEnabled() && ((HitSelect) hitSelect).mode.getValue() == 3) {
            return true;
        }
        return atlas.util.Auras.anyEnabled();
    }

    private float until(EntityLivingBase target) {
        InvulnTiming invuln = InvulnTiming.instance();
        if (invuln != null) {
            return invuln.predict(target);
        }
        return target.hurtTime - atlas.management.HitTimer.ticks();
    }

    private boolean closingIn(EntityLivingBase target) {
        double mx = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
        double mz = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
        double length = Math.sqrt(mx * mx + mz * mz);
        if (length < 1.0E-3) {
            return false;
        }
        double tx = target.posX - mc.thePlayer.posX;
        double tz = target.posZ - mc.thePlayer.posZ;
        double tl = Math.sqrt(tx * tx + tz * tz);
        if (tl < 1.0E-3) {
            return false;
        }
        return (mx * tx + mz * tz) / (length * tl) > 0.0;
    }

    private void summary(String why) {
        if (this.dropped > 0 || this.opened > 0) {
            note("  " + why + ": " + this.dropped + " clicks dropped (immune), " + this.opened + " openings taken");
        }
        this.dropped = 0;
        this.opened = 0;
    }

    private void note(String line) {
        if (this.log.getValue() != 1) {
            return;
        }
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "fightbrain-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
