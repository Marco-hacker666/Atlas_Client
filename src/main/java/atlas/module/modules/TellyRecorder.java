package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.lb.aiming.AimFeed;
import atlas.lb.aiming.AimLibrary;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.AsyncLog;
import atlas.util.ChatUtil;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records how real people bridge, for a human Speed Telly (2026-11-06, owner: "make it look like a real player";
 * no friend who can telly, so it also records the players around).
 *
 * One CSV per recording in config/Atlas/recordings, one row per player per tick:
 * - "S" rows are you: the camera's yaw and pitch (the mouse, not any module's silent look), position, ground,
 *   the movement keys, sprint, the use key and the placements sent that tick. Assist says which helpers were on,
 *   as bits: 1 Scaffold or Clutch (they turn the look and click: leave those ticks out), 2 BridgeAssist (it only
 *   crouches at the edge: the look, jumps and clicks are still your own, the sneak column is not), 4 a module
 *   that moves the camera itself (AimAssist, Silent Aura on Legit or LockView, AutoClicker's jitter, Tower,
 *   AutoHeadHitter, AutoBedDef, ProjectileAimBot, AutoRod, Freecam): the look is not only the mouse's.
 * - "O" rows are other players: the yaw and pitch exactly as the server sent them (look packets, not the
 *   smoothed model), the position as sent, ground, sneak, sprint, and the blocks that appeared under them that
 *   tick. With only-bridging on, a player is recorded from PRE_ROLL ticks before a block appears under them until
 *   ACTIVE_TICKS after the last one, so the jump before the first block is in too.
 *
 * Aim (2026-11-09, owner: "an ML rotation mode"): with record-combat, a second CSV (aim-<time>.csv) gets one row a
 * tick while an enemy is within combat-range: your camera look, the enemy you are looking closest to, how far off
 * your look is from the middle of its box (degrees), both positions, ground and hurt time, and the swings and
 * attacks you sent that tick. That is what a model needs to learn how your hand follows a target.
 *
 * clean-recording switches off, for the recording, every module that would make the data not your own (anything
 * that turns the look, clicks, holds or delays packets, changes your fight movement, or shows players through
 * walls) and switches them back on after. Assist bit 8 marks a tick where one of them was on anyway.
 *
 * Recording while playing (2026-11-10, owner: "can it record while I use it"): record-with-aura keeps Silent Aura on.
 * Its turns are silent, so the camera is still your hand; those ticks carry assist bit 16 so Recorded can leave them
 * out. A tick is not recorded while the aura moves the camera itself (LiquidBounce engine, MovementCorrection
 * ChangeLook): that would be the aura learning from itself. Three filters keep only real aiming, with or without the
 * aura: aim-fov (the look within that many degrees of the target), only-when-clicking (a swing or the attack key in
 * the last second) and aura-target (the enemy the aura is fighting, rather than the one nearest your look). Every row
 * also goes straight to Silent Aura's Recorded turn (AimFeed), so it learns while you play.
 *
 * Nothing leaves the computer. Other players are "e" plus the entity id and the world number, which means nothing
 * outside the recording: no names, no UUIDs, no server address.
 */
public class TellyRecorder extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final File DIR = new File("./config/Atlas/recordings/");
    static final String HEADER = "tick,src,id,yaw,pitch,x,y,z,ground,fwd,strafe,jump,sneak,sprint,use,place,ping,assist";
    /** Ticks kept before a player's first block: the run-up and the jump. */
    static final int PRE_ROLL = 40;
    /** Ticks a player stays recorded after a block appeared under them. */
    static final int ACTIVE_TICKS = 60;

    public final BooleanProperty recordSelf = new BooleanProperty("record-self", true);
    public final BooleanProperty recordOthers = new BooleanProperty("record-others", true);
    public final IntProperty range = new IntProperty("range", 24, 4, 64, this.recordOthers::getValue);
    public final BooleanProperty onlyBridging = new BooleanProperty("only-bridging", true, this.recordOthers::getValue);
    public final IntProperty maxFileMb = new IntProperty("max-file-mb", 100, 5, 1000);
    public final BooleanProperty recordCombat = new BooleanProperty("record-combat", true);
    public final IntProperty combatRange = new IntProperty("combat-range", 8, 3, 16, this.recordCombat::getValue);
    public final BooleanProperty cleanRecording = new BooleanProperty("clean-recording", true);
    public final BooleanProperty recordWithAura = new BooleanProperty("record-with-aura", false,
            this.recordCombat::getValue);
    public final BooleanProperty auraTarget = new BooleanProperty("aura-target", true,
            () -> this.recordCombat.getValue() && this.recordWithAura.getValue());
    public final FloatProperty aimFov = new FloatProperty("aim-fov", 30.0F, 5.0F, 180.0F, this.recordCombat::getValue);
    public final BooleanProperty onlyWhenClicking = new BooleanProperty("only-when-clicking", true,
            this.recordCombat::getValue);

    static final String AIM_HEADER = "tick,target,yaw,pitch,err_yaw,err_pitch,dist,tx,ty,tz,px,py,pz,"
            + "t_ground,p_ground,t_hurt,p_hurt,swings,attacks,attack_key,ping,assist";
    /**
     * Modules that would make a recording not the player's own: they turn the look, click or change hits, hold or
     * delay packets, change fight movement, or show players through walls. clean-recording switches them off.
     */
    static final String[] NOT_OWN = {"KillAura", "AimAssist", "AutoClicker", "FreeLook", "Freecam", "Scaffold",
            "Clutch", "Tower", "AutoRod", "ProjectileAimBot", "AutoHeadHitter", "AutoBedDef", "ThrowAura", "HitSelect",
            "Hitflick", "NoDelay", "Piercing", "Reach", "HitBox", "BackTrack", "LagRange", "FakeLag", "ServerLag",
            "KnockbackDelay", "Blink", "PingFix", "PingSpoof", "Timer", "TimerRange", "TickBase", "KeepRange",
            "Velocity", "MoreKB", "SprintReset", "Wtap", "JumpReset", "Speed", "KeepSprint", "TargetStrafe",
            "BlockHit", "ESP", "Pointers", "Indicators"};
    static final int ASSIST_OTHER = 8;
    static final int ASSIST_AURA = AimLibrary.ASSIST_AURA;
    /** How recent a swing or the attack key must be for only-when-clicking. */
    static final long CLICK_WINDOW_MS = 1000L;
    private long lastClickAt;
    private final List<Module> switchedOff = new ArrayList<Module>();
    private File aimFile;
    private final List<String> aimPending = new ArrayList<String>();
    private int aimRows;
    private final AtomicInteger swingsSent = new AtomicInteger();
    private final AtomicInteger attacksSent = new AtomicInteger();

    /* Filled on the network thread, read on the client thread. */
    private final Map<Integer, float[]> looks = new ConcurrentHashMap<Integer, float[]>();
    private final ConcurrentLinkedQueue<BlockPos> appeared = new ConcurrentLinkedQueue<BlockPos>();
    private final AtomicInteger placesSent = new AtomicInteger();

    /* Client thread only. */
    private final Map<Integer, ArrayDeque<String>> preRoll = new HashMap<Integer, ArrayDeque<String>>();
    private final Map<Integer, Integer> activeUntil = new HashMap<Integer, Integer>();
    private final List<String> pending = new ArrayList<String>();
    private File file;
    private int tick;
    private int world;
    private long bytes;
    private int selfRows;
    private int otherRows;

    public TellyRecorder() {
        /* 2026-11-09: "Recorder" now that it records fights too; old configs find it by its class name. */
        super("Recorder", false);
    }

    @Override
    public void onEnabled() {
        DIR.mkdirs();
        this.file = new File(DIR, "telly-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".csv");
        this.tick = 0;
        this.world = 0;
        this.bytes = 0L;
        this.selfRows = 0;
        this.otherRows = 0;
        this.clearWorld();
        this.placesSent.set(0);
        this.pending.add(String.format(Locale.ROOT, "# atlas telly-recorder v1, sensitivity=%.4f",
                mc.gameSettings == null ? 0.0F : mc.gameSettings.mouseSensitivity));
        this.pending.add(HEADER);
        this.aimRows = 0;
        this.aimFile = new File(DIR, this.file.getName().replace("telly-", "aim-"));
        this.aimPending.add(this.pending.get(0).replace("telly-recorder", "aim-recorder"));
        this.aimPending.add(AIM_HEADER);
        AimFeed.publish(AIM_HEADER);
        this.swingsSent.set(0);
        this.attacksSent.set(0);
        this.switchedOff.clear();
        if (this.cleanRecording.getValue()) {
            StringBuilder names = new StringBuilder();
            for (String name : NOT_OWN) {
                Module module = atlas.Atlas.moduleManager.getModule(name);
                if (module != null && module != this && module.isEnabled() && !keptForAura(module)) {
                    module.setEnabled(false);
                    this.switchedOff.add(module);
                    names.append(names.length() == 0 ? "" : ", ").append(module.getName());
                }
            }
            if (names.length() > 0) {
                say("Switched off for a clean recording: &f" + names + "&7 (back on when you stop).");
            }
        }
        say("Recording to &f" + this.file.getPath() + "&7. Turn it off to save.");
    }

    @Override
    public void onDisabled() {
        this.flush();
        if (this.file != null) {
            say(String.format("Saved &f%d&7 rows of you and &f%d&7 of others to &f%s", this.selfRows,
                    this.otherRows, this.file.getPath()));
        }
        if (this.aimFile != null && this.aimRows > 0) {
            say(String.format("Saved &f%d&7 aim rows to &f%s", this.aimRows, this.aimFile.getPath()));
        }
        for (Module module : this.switchedOff) {
            if (!module.isEnabled()) {
                module.setEnabled(true);
            }
        }
        if (!this.switchedOff.isEmpty()) {
            say("Switched back on: the modules turned off for the recording.");
        }
        this.switchedOff.clear();
        this.clearWorld();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        /* Entity ids start over in a new world: a new number keeps them apart. */
        this.world++;
        this.clearWorld();
    }

    private void clearWorld() {
        this.looks.clear();
        this.appeared.clear();
        this.preRoll.clear();
        this.activeUntil.clear();
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof net.minecraft.network.play.client.C0APacketAnimation && !event.isCancelled()) {
                this.swingsSent.incrementAndGet();
            } else if (event.getPacket() instanceof net.minecraft.network.play.client.C02PacketUseEntity
                    && !event.isCancelled()
                    && ((net.minecraft.network.play.client.C02PacketUseEntity) event.getPacket()).getAction()
                    == net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK) {
                this.attacksSent.incrementAndGet();
            }
            if (event.getPacket() instanceof C08PacketPlayerBlockPlacement && !event.isCancelled()) {
                BlockPos pos = ((C08PacketPlayerBlockPlacement) event.getPacket()).getPosition();
                if (pos != null && pos.getY() >= 0) {
                    this.placesSent.incrementAndGet();
                }
            }
            return;
        }
        if (!this.recordOthers.getValue()) {
            return;
        }
        if (event.getPacket() instanceof S14PacketEntity.S16PacketEntityLook
                || event.getPacket() instanceof S14PacketEntity.S17PacketEntityLookMove) {
            S14PacketEntity look = (S14PacketEntity) event.getPacket();
            if (mc.theWorld != null && look.getEntity(mc.theWorld) instanceof EntityPlayer) {
                this.looks.put(look.getEntity(mc.theWorld).getEntityId(),
                        new float[]{angle(look.func_149066_f()), angle(look.func_149063_g())});
            }
        } else if (event.getPacket() instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) event.getPacket();
            this.looks.put(teleport.getEntityId(), new float[]{angle(teleport.getYaw()), angle(teleport.getPitch())});
        } else if (event.getPacket() instanceof S23PacketBlockChange) {
            S23PacketBlockChange change = (S23PacketBlockChange) event.getPacket();
            if (change.getBlockPosition() != null && change.getBlockState() != null
                    && change.getBlockState().getBlock().getMaterial() != Material.air) {
                this.appeared.add(change.getBlockPosition());
            }
        }
    }

    /** A packet's angle byte in degrees, -180 to 180 as sent (1.40625 degrees a step). */
    static float angle(byte value) {
        return value * 360.0F / 256.0F;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST || mc.thePlayer == null || mc.theWorld == null || this.file == null) {
            return;
        }
        this.tick++;
        int places = this.placesSent.getAndSet(0);
        if (this.recordSelf.getValue()) {
            this.recordSelf(places);
        }
        if (this.recordOthers.getValue()) {
            this.recordOthers();
        } else {
            this.appeared.clear();
        }
        int swings = this.swingsSent.getAndSet(0);
        int attacks = this.attacksSent.getAndSet(0);
        if (this.recordCombat.getValue()) {
            this.recordAim(swings, attacks);
        }
        if (this.tick % 20 == 0) {
            this.flush();
        }
        if (this.tick % 20 == 0 && !this.aimPending.isEmpty() && this.aimFile != null) {
            AsyncLog.append(this.aimFile, new ArrayList<String>(this.aimPending));
            this.aimPending.clear();
        }
        if (this.bytes > this.maxFileMb.getValue() * 1024L * 1024L) {
            say("The file reached max-file-mb.");
            this.setEnabled(false);
        }
    }

    private void recordSelf(int places) {
        boolean moving = Math.abs(mc.thePlayer.posX - mc.thePlayer.prevPosX) > 1.0E-3
                || Math.abs(mc.thePlayer.posZ - mc.thePlayer.prevPosZ) > 1.0E-3
                || mc.thePlayer.rotationYaw != mc.thePlayer.prevRotationYaw || places > 0;
        if (!moving) {
            return;
        }
        int ping = 0;
        if (mc.getNetHandler() != null) {
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            ping = info == null ? 0 : info.getResponseTime();
        }
        this.add(row(this.tick, "S", "me", mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe,
                mc.gameSettings.keyBindJump.isKeyDown(), mc.gameSettings.keyBindSneak.isKeyDown(),
                mc.thePlayer.isSprinting(), mc.gameSettings.keyBindUseItem.isKeyDown(), places, ping,
                this.assisted()), true);
    }

    static final int ASSIST_PLACING = 1;
    static final int ASSIST_EDGE = 2;
    static final int ASSIST_LOOK = 4;

    private int assisted() {
        return (on(Scaffold.class) || on(Clutch.class) ? ASSIST_PLACING : 0) | (on(Eagle.class) ? ASSIST_EDGE : 0)
                | (lookMoved() ? ASSIST_LOOK : 0) | (notOwnOn() ? ASSIST_OTHER : 0);
    }

    private boolean notOwnOn() {
        for (String name : NOT_OWN) {
            Module module = atlas.Atlas.moduleManager.getModule(name);
            if (module != null && module != this && module.isEnabled() && !keptForAura(module)) {
                return true;
            }
        }
        return false;
    }

    /** Silent Aura (either engine), which record-with-aura leaves on: its silent turns do not move the camera. */
    private boolean keptForAura(Module module) {
        return this.recordWithAura.getValue() && (module instanceof KillAura || module instanceof KillAuraLB);
    }

    /** Silent Aura, when on. */
    private static KillAura aura() {
        Module module = atlas.Atlas.moduleManager.modules.get(KillAura.class);
        return module != null && module.isEnabled() ? (KillAura) module : null;
    }

    /**
     * The aura turns the camera itself, so there is nothing of yours to learn: the LiquidBounce engine with
     * MovementCorrection ChangeLook, or the Atlas engine with Legit or LockView rotations.
     */
    private static boolean auraMovesCamera(KillAura aura) {
        if (aura == null) {
            return false;
        }
        KillAuraLB lb = aura.liquidBounce();
        if (lb != null) {
            return lb.movementCorrection.getValue() == 3;
        }
        String rotations = aura.rotations.getModeString();
        return aura.atlasEngine() && ("Legit".equals(rotations) || "LockView".equals(rotations));
    }

    /** One aim row while an enemy is within combat-range: the one your look is closest to. */
    private void recordAim(int swings, int attacks) {
        long now = System.currentTimeMillis();
        if (swings > 0 || attacks > 0 || mc.gameSettings.keyBindAttack.isKeyDown()) {
            this.lastClickAt = now;
        }
        if (this.onlyWhenClicking.getValue() && now - this.lastClickAt > CLICK_WINDOW_MS) {
            return;
        }
        KillAura aura = aura();
        boolean withAura = aura != null && this.recordWithAura.getValue();
        if (withAura && auraMovesCamera(aura)) {
            /* The aura steers the camera itself: this tick is not your hand. */
            return;
        }
        EntityPlayer auraPick = null;
        if (withAura && this.auraTarget.getValue() && aura.getTarget() instanceof EntityPlayer) {
            auraPick = (EntityPlayer) aura.getTarget();
        }
        double range = this.combatRange.getValue();
        double ex = mc.thePlayer.posX;
        double ey = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double ez = mc.thePlayer.posZ;
        float yaw = mc.thePlayer.rotationYaw;
        float pitch = mc.thePlayer.rotationPitch;
        EntityPlayer best = null;
        float[] bestErr = null;
        float bestOff = Float.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (auraPick != null && player != auraPick) {
                /* aura-target: only the enemy the aura is fighting. */
                continue;
            }
            if (player == mc.thePlayer || player.isDead || player.getHealth() <= 0.0F
                    || player.getDistanceToEntity(mc.thePlayer) > range || atlas.util.TeamUtil.isFriend(player)
                    || atlas.util.TeamUtil.isSameTeam(player) || atlas.util.TeamUtil.isBot(player)) {
                continue;
            }
            net.minecraft.util.AxisAlignedBB box = player.getEntityBoundingBox();
            float[] err = aimError(yaw, pitch, ex, ey, ez, (box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0,
                    (box.minZ + box.maxZ) / 2.0);
            float off = err[0] * err[0] + err[1] * err[1];
            if (off < bestOff) {
                bestOff = off;
                best = player;
                bestErr = err;
            }
        }
        if (best == null) {
            return;
        }
        if (Math.abs(bestErr[0]) > this.aimFov.getValue() || Math.abs(bestErr[1]) > this.aimFov.getValue()) {
            /* Not aiming at them (aim-fov): a look elsewhere teaches nothing about following a target. */
            return;
        }
        int ping = 0;
        if (mc.getNetHandler() != null) {
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            ping = info == null ? 0 : info.getResponseTime();
        }
        String line = String.format(Locale.ROOT,
                "%d,e%dw%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                this.tick, best.getEntityId(), this.world, yaw, pitch, bestErr[0], bestErr[1],
                best.getDistanceToEntity(mc.thePlayer), best.posX, best.posY, best.posZ, mc.thePlayer.posX,
                mc.thePlayer.posY, mc.thePlayer.posZ, best.onGround ? 1 : 0, mc.thePlayer.onGround ? 1 : 0,
                best.hurtTime, mc.thePlayer.hurtTime, swings, attacks,
                mc.gameSettings.keyBindAttack.isKeyDown() ? 1 : 0, ping,
                assisted() | (withAura ? ASSIST_AURA : aura != null ? ASSIST_OTHER : 0));
        this.aimPending.add(line);
        AimFeed.publish(line);
        this.bytes += line.length() + 1;
        this.aimRows++;
    }

    /**
     * How far the look is from a point, in degrees: [yaw, pitch], each from the look to the point (positive: the point
     * is to the right of / below the look), yaw wrapped to -180..180.
     */
    static float[] aimError(float yaw, float pitch, double ex, double ey, double ez, double x, double y, double z) {
        double dx = x - ex;
        double dy = y - ey;
        double dz = z - ez;
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawErr = wrap(wantYaw - yaw);
        return new float[]{yawErr, wantPitch - pitch};
    }

    static float wrap(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    /** A module that turns the camera itself is on; Silent Aura only on the modes that turn it. */
    private static boolean lookMoved() {
        if (on(AimAssist.class) || on(AutoClicker.class) || on(Tower.class) || on(AutoHeadHitter.class)
                || on(AutoBedDef.class) || on(ProjectileAimBot.class) || on(AutoRod.class) || on(Freecam.class)) {
            return true;
        }
        Module aura = atlas.Atlas.moduleManager.getModule(KillAura.class);
        if (aura == null || !aura.isEnabled()) {
            return false;
        }
        String rotations = ((KillAura) aura).rotations.getModeString();
        return "Legit".equals(rotations) || "LockView".equals(rotations);
    }

    private static boolean on(Class<?> type) {
        Module module = atlas.Atlas.moduleManager.getModule(type);
        return module != null && module.isEnabled();
    }

    private void recordOthers() {
        double range = this.range.getValue();
        List<EntityPlayer> near = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player != mc.thePlayer && !player.isDead && player.getDistanceToEntity(mc.thePlayer) <= range) {
                near.add(player);
            }
        }
        Map<Integer, Integer> placed = new HashMap<Integer, Integer>();
        for (BlockPos pos; (pos = this.appeared.poll()) != null; ) {
            EntityPlayer by = placer(pos, near);
            if (by != null) {
                Integer count = placed.get(by.getEntityId());
                placed.put(by.getEntityId(), count == null ? 1 : count + 1);
            }
        }
        for (EntityPlayer player : near) {
            int id = player.getEntityId();
            Integer count = placed.get(id);
            if (count != null) {
                this.activeUntil.put(id, this.tick + ACTIVE_TICKS);
            }
            float[] look = this.looks.get(id);
            String line = row(this.tick, "O", "e" + id + "w" + this.world,
                    look == null ? player.rotationYaw : look[0], look == null ? player.rotationPitch : look[1],
                    player.serverPosX / 32.0, player.serverPosY / 32.0, player.serverPosZ / 32.0, player.onGround,
                    Float.NaN, Float.NaN, false, player.isSneaking(), player.isSprinting(), false,
                    count == null ? 0 : count, -1, 0);
            Integer until = this.activeUntil.get(id);
            boolean active = !this.onlyBridging.getValue() || until != null && until >= this.tick;
            if (active) {
                ArrayDeque<String> before = this.preRoll.remove(id);
                if (before != null) {
                    for (String earlier : before) {
                        this.add(earlier, false);
                    }
                }
                this.add(line, false);
            } else {
                ArrayDeque<String> before = this.preRoll.get(id);
                if (before == null) {
                    before = new ArrayDeque<String>();
                    this.preRoll.put(id, before);
                }
                before.addLast(line);
                while (before.size() > PRE_ROLL) {
                    before.removeFirst();
                }
            }
        }
        /* Players gone from range keep nothing. */
        for (Iterator<Integer> it = this.preRoll.keySet().iterator(); it.hasNext(); ) {
            int id = it.next();
            boolean present = false;
            for (EntityPlayer player : near) {
                present |= player.getEntityId() == id;
            }
            if (!present) {
                it.remove();
                this.activeUntil.remove(id);
            }
        }
    }

    /**
     * Who a new block is under: the nearest other player whose feet are at most 1.5 blocks above its top and
     * within 4.5 blocks of its middle (reach), or null when you are nearer (your own block).
     */
    static EntityPlayer placer(BlockPos pos, List<EntityPlayer> near) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        EntityPlayer best = null;
        double bestDistance = 4.5 * 4.5;
        for (EntityPlayer player : near) {
            double feet = player.serverPosY / 32.0;
            if (feet < pos.getY() + 0.5 || feet > pos.getY() + 2.5) {
                continue;
            }
            double dx = player.serverPosX / 32.0 - cx;
            double dy = feet + player.getEyeHeight() - cy;
            double dz = player.serverPosZ / 32.0 - cz;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        if (best != null && mc != null && mc.thePlayer != null) {
            double dx = mc.thePlayer.posX - cx;
            double dy = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - cy;
            double dz = mc.thePlayer.posZ - cz;
            if (dx * dx + dy * dy + dz * dz < bestDistance) {
                return null;
            }
        }
        return best;
    }

    static String row(int tick, String src, String id, float yaw, float pitch, double x, double y, double z,
                      boolean ground, float forward, float strafe, boolean jump, boolean sneak, boolean sprint,
                      boolean use, int place, int ping, int assist) {
        return String.format(Locale.ROOT, "%d,%s,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d",
                tick, src, id, yaw, pitch, x, y, z, ground ? 1 : 0, Float.isNaN(forward) ? "" : key(forward),
                Float.isNaN(strafe) ? "" : key(strafe), jump ? 1 : 0, sneak ? 1 : 0, sprint ? 1 : 0, use ? 1 : 0,
                place, ping, assist);
    }

    private static String key(float input) {
        return input > 0.0F ? "1" : input < 0.0F ? "-1" : "0";
    }

    private void add(String line, boolean self) {
        this.pending.add(line);
        this.bytes += line.length() + 1;
        if (self) {
            this.selfRows++;
        } else {
            this.otherRows++;
        }
    }

    private void flush() {
        if (this.file == null || this.pending.isEmpty()) {
            return;
        }
        AsyncLog.append(this.file, new ArrayList<String>(this.pending));
        this.pending.clear();
    }

    private static void say(String message) {
        ChatUtil.sendFormatted("&7[&bTellyRecorder&7] &7" + message);
    }
}
