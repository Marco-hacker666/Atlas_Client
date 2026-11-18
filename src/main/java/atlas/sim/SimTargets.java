package atlas.sim;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
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

public final class SimTargets {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final SimTargets INSTANCE = new SimTargets();

    public static final double RANGE = 16.0;

    public static final int MOST = 6;

    public static final int WANTED_FOR = 100;

    public static final int STALE = 6;

    private static final UUID SPRINT_BOOST = UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");

    private static final class Target {
        final TargetTrack track = new TargetTrack();
        boolean known;
        int x;
        int y;
        int z;

        int tick;
    }

    private final Map<Integer, Target> targets = new HashMap<Integer, Target>();

    private final Queue<double[]> hits = new ConcurrentLinkedQueue<double[]>();
    private final TargetScore score = new TargetScore();
    private volatile int tick;
    private volatile int wantedUntil = -1;
    private volatile boolean measuring;

    private volatile int focusId = -1;
    private volatile int focusUntil = -1;

    private SimTargets() {
    }

    public static SimTargets get() {
        return INSTANCE;
    }

    public static void want() {
        INSTANCE.wantedUntil = INSTANCE.tick + WANTED_FOR;
    }

    public static void focus(Entity player) {
        if (player == null) {
            return;
        }
        want();
        INSTANCE.focusId = player.getEntityId();
        INSTANCE.focusUntil = INSTANCE.tick + 5;
    }

    public static void measure(boolean on) {
        INSTANCE.measuring = on;
    }

    public static boolean measuring() {
        return INSTANCE.measuring;
    }

    public static String summary() {
        return INSTANCE.score.summary();
    }

    private static final int SAVE_EVERY = 1200;
    private java.io.File scoreFile;
    private String lastSaved = "";

    private void saveScore() {
        String line = this.score.summary();
        if (line == null || line.isEmpty() || line.equals(this.lastSaved) || line.contains("nothing measured")) {
            return;
        }
        this.lastSaved = line;
        if (this.scoreFile == null) {
            this.scoreFile = new java.io.File("./config/Atlas/",
                    "targets-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        String server = mc.getCurrentServerData() == null ? "singleplayer" : mc.getCurrentServerData().serverIP;
        atlas.util.AsyncLog.append(this.scoreFile, new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                + " " + server + " | " + line);
    }

    public static void saveNow() {
        INSTANCE.saveScore();
    }

    public static void resetScore() {
        INSTANCE.score.reset();
    }

    public static int followed() {
        return INSTANCE.targets.size();
    }

    public static TargetTrack track(Entity player) {
        Target target = player == null ? null : INSTANCE.targets.get(player.getEntityId());
        return target == null || !target.known ? null : target.track;
    }

    public static int age(Entity player) {
        Target target = player == null ? null : INSTANCE.targets.get(player.getEntityId());
        return target == null || !target.known ? -1 : INSTANCE.tick - target.tick;
    }

    public static TargetForecast forecast(Entity player, int moves) {
        want();
        Target target = fresh(player);
        return target == null ? null : target.track.forecast(SimPlayer.world(mc.theWorld, player), moves);
    }

    public static final class Dodges {
        public final List<TargetForecast> paths;

        public final int age;

        public final boolean atRest;

        Dodges(List<TargetForecast> paths, int age, boolean atRest) {
            this.paths = paths;
            this.age = age;
            this.atRest = atRest;
        }
    }

    public static Dodges dodges(Entity player, double after, int ticks) {
        want();
        if (player == null || mc.theWorld == null || ticks < 1) {
            return null;
        }
        Target target = INSTANCE.targets.get(player.getEntityId());
        if (target == null || !target.known) {
            return null;
        }
        int age = INSTANCE.tick - target.tick;
        if (age < 0) {
            return null;
        }

        PlayerSim.World world = SimWorlds.cached(SimPlayer.world(mc.theWorld, player));
        List<TargetForecast> paths;
        boolean atRest = age > STALE;
        if (atRest) {
            age = 0;
            paths = TargetDodges.pathsAtRest(target.track, world, ticks, (int) Math.round(after),
                    player.getRotationYawHead());
        } else {
            paths = TargetDodges.paths(target.track, world, age + ticks, age + (int) Math.round(after));
        }
        return paths.isEmpty() ? null : new Dodges(paths, age, atRest);
    }

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

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            clear();
            return;
        }
        this.tick++;
        if (this.measuring && this.tick % SAVE_EVERY == 0) {
            saveScore();
        }
        if (!running()) {
            clear();
            return;
        }
        List<EntityPlayer> near = new ArrayList<EntityPlayer>();
        EntityPlayer focused = null;
        boolean focusing = this.tick <= this.focusUntil;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || !covered(player)) {
                continue;
            }
            if (focusing && player.getEntityId() == this.focusId) {
                focused = player;
            } else if (distanceSq(player) <= RANGE * RANGE) {
                near.add(player);
            }
        }
        if (near.size() > MOST - (focused != null ? 1 : 0)) {
            near.sort((a, b) -> Double.compare(distanceSq(a), distanceSq(b)));
            near = new ArrayList<EntityPlayer>(near.subList(0, Math.max(0, MOST - (focused != null ? 1 : 0))));
        }
        if (focused != null) {
            near.add(0, focused);
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
                this.score.forget(player.getEntityId());
                this.targets.put(player.getEntityId(), new Target());
            }
        }
    }

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

        PlayerSim.World world = SimWorlds.cached(SimPlayer.world(mc.theWorld, player));
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

    private static boolean covered(EntityPlayer player) {
        return player.isEntityAlive() && !player.isRiding() && !player.isPlayerSleeping();
    }

    private static double distanceSq(EntityPlayer player) {
        double dx = (double) player.serverPosX / 32.0 - mc.thePlayer.posX;
        double dz = (double) player.serverPosZ / 32.0 - mc.thePlayer.posZ;
        return dx * dx + dz * dz;
    }

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
