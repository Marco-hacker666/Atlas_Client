package atlas.sim;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The game's side of {@link TargetTrack} (2026-11-13): the other players near this one, each followed through the
 * positions the server sends and forecast on request.
 *
 * Once a tick, between ticks (the server's packets are applied by then), each player whose position changed is a
 * new position for its track: the 1/32 cell (Entity.serverPosX and the like), the ground flag, the head's yaw (the
 * last one sent; the body's is eased toward it over three ticks), and the sprint, sneak and item-use flags. The
 * count of moves since the last position is this client's ticks since it.
 *
 * It runs only while something asks: a caller of {@link #forecast} or {@link #path} keeps it going for
 * {@link #WANTED_FOR} ticks, and so does the measuring switched on with .targets. Asked by nobody it holds nothing
 * and costs nothing. It follows the {@link #MOST} nearest players within {@link #RANGE}: a position costs up to a
 * few hundred simulated moves when the keys changed, a handful when they did not.
 *
 * An attack this client sends is told to the track of the player hit ({@link TargetTrack#expect}) as the knockback
 * vanilla would deal: the server tells nobody but the player hit.
 *
 * What a caller has to know: index 0 of a forecast is the player at the last position the server sent, which is
 * {@link #age} ticks old. After {@link #STALE} ticks without one there is no answer: a player that stopped sends
 * nothing, and the keys found before say nothing of it any more.
 *
 * Client thread, but for the packet listener, which only queues.
 */
public final class SimTargets {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final SimTargets INSTANCE = new SimTargets();

    /** Players farther than this (blocks, level) are not followed. */
    public static final double RANGE = 16.0;
    /** How many players are followed at most, the nearest first. */
    public static final int MOST = 6;
    /** A request keeps the tracking running this many ticks. */
    public static final int WANTED_FOR = 100;
    /** After this many ticks without a position a track has no answer. */
    public static final int STALE = 6;
    /** EntityLivingBase.sprintingSpeedBoostModifierUUID: the modifier a sprint puts on the walking speed. */
    private static final UUID SPRINT_BOOST = UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");

    private static final class Target {
        final TargetTrack track = new TargetTrack();
        boolean known;
        int x;
        int y;
        int z;
        /** The tick the last position came. */
        int tick;
    }

    private final Map<Integer, Target> targets = new HashMap<Integer, Target>();
    /** Hits this client dealt, {entity id, x, y, z of the knockback}: queued where the packet is seen. */
    private final Queue<double[]> hits = new ConcurrentLinkedQueue<double[]>();
    private final TargetScore score = new TargetScore();
    private volatile int tick;
    private volatile int wantedUntil = -1;
    private volatile boolean measuring;

    private SimTargets() {
    }

    /** The one instance, for the event bus. */
    public static SimTargets get() {
        return INSTANCE;
    }

    /** Keeps the tracking running for {@link #WANTED_FOR} more ticks. A forecast asked for does this itself. */
    public static void want() {
        INSTANCE.wantedUntil = INSTANCE.tick + WANTED_FOR;
    }

    /** Measuring (.targets on): the tracking runs, and every forecast is held against what comes. */
    public static void measure(boolean on) {
        INSTANCE.measuring = on;
    }

    public static boolean measuring() {
        return INSTANCE.measuring;
    }

    /** What the measuring found so far ({@link TargetScore#summary}). */
    public static String summary() {
        return INSTANCE.score.summary();
    }

    public static void resetScore() {
        INSTANCE.score.reset();
    }

    /** How many players are followed now. */
    public static int followed() {
        return INSTANCE.targets.size();
    }

    /** The track of this player, or null when it is not followed (yet). Read it; do not feed it. */
    public static TargetTrack track(Entity player) {
        Target target = player == null ? null : INSTANCE.targets.get(player.getEntityId());
        return target == null || !target.known ? null : target.track;
    }

    /** Ticks since the last position of this player came; -1 when it is not followed. */
    public static int age(Entity player) {
        Target target = player == null ? null : INSTANCE.targets.get(player.getEntityId());
        return target == null || !target.known ? -1 : INSTANCE.tick - target.tick;
    }

    /**
     * Where this player will be over its next {@code moves} moves, index 0 being the last position the server sent
     * ({@link #age} ticks ago). Null when it is not followed yet (the first call starts it), or has sent nothing
     * for {@link #STALE} ticks. {@link TargetForecast#simulated} says whether it is the simulation's answer or the
     * straight line's.
     */
    public static TargetForecast forecast(Entity player, int moves) {
        want();
        Target target = fresh(player);
        return target == null ? null : target.track.forecast(SimPlayer.world(mc.theWorld, player), moves);
    }

    /**
     * Where the simulation has this player {x, z} one tick from now, two, ... up to {@code ticks}; null when it has
     * no answer of its own (not followed, nothing sent lately, or its moves were not explained).
     */
    public static double[][] path(Entity player, int ticks) {
        want();
        Target target = fresh(player);
        if (target == null) {
            return null;
        }
        int age = INSTANCE.tick - target.tick;
        TargetForecast forecast = target.track.forecast(SimPlayer.world(mc.theWorld, player), age + ticks);
        if (forecast == null || !forecast.simulated) {
            return null;
        }
        double[][] out = new double[ticks][];
        for (int i = 1; i <= ticks; i++) {
            out[i - 1] = new double[]{forecast.x[age + i], forecast.z[age + i]};
        }
        return out;
    }

    private static Target fresh(Entity player) {
        if (player == null || mc.theWorld == null) {
            return null;
        }
        Target target = INSTANCE.targets.get(player.getEntityId());
        if (target == null || !target.known) {
            return null;
        }
        int age = INSTANCE.tick - target.tick;
        return age < 0 || age > STALE ? null : target;
    }

    private boolean running() {
        return this.measuring || this.tick <= this.wantedUntil;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            clear();
            return;
        }
        this.tick++;
        if (!running()) {
            clear();
            return;
        }
        List<EntityPlayer> near = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player != mc.thePlayer && covered(player) && distanceSq(player) <= RANGE * RANGE) {
                near.add(player);
            }
        }
        if (near.size() > MOST) {
            near.sort((a, b) -> Double.compare(distanceSq(a), distanceSq(b)));
            near = near.subList(0, MOST);
        }
        Set<Integer> followed = new HashSet<Integer>();
        for (EntityPlayer player : near) {
            followed.add(player.getEntityId());
        }
        for (Iterator<Map.Entry<Integer, Target>> it = this.targets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Target> entry = it.next();
            if (!followed.contains(entry.getKey())) {
                this.score.forget(entry.getKey());
                it.remove();
            }
        }
        for (EntityPlayer player : near) {
            Target target = this.targets.get(player.getEntityId());
            if (target == null) {
                target = new Target();
                this.targets.put(player.getEntityId(), target);
            }
        }
        /* The hits first: a knockback is looked for in the positions that come after the attack. */
        double[] hit;
        while ((hit = this.hits.poll()) != null) {
            Target target = this.targets.get((int) hit[0]);
            if (target != null) {
                target.track.expect(hit[1], hit[2], hit[3]);
            }
        }
        for (EntityPlayer player : near) {
            Target target = this.targets.get(player.getEntityId());
            if (target.known && player.serverPosX == target.x && player.serverPosY == target.y
                    && player.serverPosZ == target.z) {
                continue;
            }
            try {
                position(player, target);
            } catch (RuntimeException e) {
                /* A forecast is never worth the game: this player's track starts again. */
                this.score.forget(player.getEntityId());
                this.targets.put(player.getEntityId(), new Target());
            }
        }
    }

    /** A new position of this player: its track sees it, and the measuring, when on. */
    private void position(EntityPlayer player, Target target) {
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = player.serverPosX;
        seen.y = player.serverPosY;
        seen.z = player.serverPosZ;
        seen.onGround = player.onGround;
        seen.yaw = player.rotationYawHead;
        seen.sprinting = player.isSprinting();
        seen.sneaking = player.isSneaking();
        seen.usingItem = player.isUsingItem();
        int since = target.known ? this.tick - target.tick : 0;
        double perTickX = Double.NaN;
        double perTickZ = Double.NaN;
        if (target.known && since > 0) {
            perTickX = (double) (seen.x - target.x) * TargetFit.UNIT / (double) since;
            perTickZ = (double) (seen.z - target.z) * TargetFit.UNIT / (double) since;
        }
        target.track.walkSpeed = walkSpeed(player);
        target.track.depthStrider = (float) EnchantmentHelper.getDepthStriderModifier(player);
        PlayerSim.World world = SimPlayer.world(mc.theWorld, player);
        target.track.observe(seen, world, (double) since);
        target.known = true;
        target.x = seen.x;
        target.y = seen.y;
        target.z = seen.z;
        target.tick = this.tick;
        if (this.measuring) {
            this.score.position(player.getEntityId(), this.tick, seen.midX(), seen.midZ(),
                    target.track.forecast(world, TargetScore.AHEAD), perTickX, perTickZ);
        }
    }

    /** A player the simulation covers: alive, on its own feet. */
    private static boolean covered(EntityPlayer player) {
        return player.isEntityAlive() && !player.isRiding() && !player.isPlayerSleeping();
    }

    private static double distanceSq(EntityPlayer player) {
        double dx = (double) player.serverPosX / 32.0 - mc.thePlayer.posX;
        double dz = (double) player.serverPosZ / 32.0 - mc.thePlayer.posZ;
        return dx * dx + dz * dz;
    }

    /**
     * The player's walking speed without a sprint's share (the simulation adds that itself): the server sends the
     * attribute with its modifiers, a speed potion's among them.
     */
    private static double walkSpeed(EntityPlayer player) {
        IAttributeInstance speed = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        if (speed == null) {
            return (double) 0.1F;
        }
        double value = speed.getAttributeValue();
        if (speed.getModifier(SPRINT_BOOST) != null) {
            value /= 1.0 + (double) 0.3F;
        }
        return value > 0.0 && value < 1.0 ? value : (double) 0.1F;
    }

    /** An attack going out: the knockback vanilla deals for it, for the track of the player hit. */
    @EventTarget
    public void onSend(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled() || !running()
                || !(event.getPacket() instanceof C02PacketUseEntity) || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        Entity hit = packet.getEntityFromWorld(mc.theWorld);
        if (!(hit instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) hit;
        /* Still red from the last hit: this one deals no knockback. */
        if (player.hurtResistantTime > player.maxHurtResistantTime / 2) {
            return;
        }
        int extra = (mc.thePlayer.isSprinting() ? 1 : 0) + EnchantmentHelper.getKnockbackModifier(mc.thePlayer);
        double[] motion = TargetTrack.vanillaHit(mc.thePlayer.posX - (double) player.serverPosX / 32.0,
                mc.thePlayer.posZ - (double) player.serverPosZ / 32.0, SimPlayer.moveYaw(mc.thePlayer), extra);
        this.hits.add(new double[]{(double) player.getEntityId(), motion[0], motion[1], motion[2]});
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        clear();
    }

    private void clear() {
        if (!this.targets.isEmpty()) {
            this.targets.clear();
        }
        this.hits.clear();
        this.score.clearOpen();
    }
}
