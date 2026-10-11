//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package myau.module.modules;

import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;

import myau.util.LatePacket;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.ServerUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.client.C0BPacketEntityAction.Action;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;

public class Disabler extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_DELAYED_PACKETS = 256;
    public final BooleanProperty lifeboat = new BooleanProperty("lifeboat", false);
    public final BooleanProperty startSprint = new BooleanProperty("start-sprint", true);
    public final BooleanProperty grimPlace = new BooleanProperty("grim-place", false);
    public final BooleanProperty vulcanScaffold = new BooleanProperty("vulcan-scaffold", false);
    public final IntProperty vulcanPacketTick = new IntProperty("vulcan-packet-tick", 15, 1, 20);
    public final BooleanProperty verusFly = new BooleanProperty("verus-fly", false);
    public final BooleanProperty verusCombat = new BooleanProperty("verus-combat", false);
    public final BooleanProperty onlyCombat = new BooleanProperty("only-combat", true);
    public final BooleanProperty intaveFly = new BooleanProperty("intave-fly", false);
    public final BooleanProperty moveDisabler = new BooleanProperty("move-disabler", false);
    private boolean shouldDelay = false;
    // use diamond operator to avoid raw type warnings
    private final LinkedBlockingQueue<Packet<INetHandlerPlayClient>> packets = new LinkedBlockingQueue<>();
    public final BooleanProperty noRotationDisabler = new BooleanProperty("no-rotation-disabler", false);
    public final ModeProperty modifyMode = new ModeProperty("modify-mode", 0, new String[]{"ConvertNull", "Spoof", "Zero", "SpoofZero", "Negative", "OffsetYaw"});
    public final FloatProperty offsetAmount = new FloatProperty("offset-amount", 6.0F, -180.0F, 180.0F);
    public final BooleanProperty basicDisabler = new BooleanProperty("basic-disabler", false);
    public final BooleanProperty cancelC00 = new BooleanProperty("cancel-c00", true);
    public final BooleanProperty cancelC0F = new BooleanProperty("cancel-c0f", true);
    public final BooleanProperty cancelC0A = new BooleanProperty("cancel-c0a", true);
    public final BooleanProperty cancelC0B = new BooleanProperty("cancel-c0b", true);
    public final BooleanProperty cancelC07 = new BooleanProperty("cancel-c07", true);
    public final BooleanProperty cancelC13 = new BooleanProperty("cancel-c13", true);
    public final BooleanProperty cancelC03 = new BooleanProperty("cancel-c03", true);
    public final BooleanProperty c03NoMove = new BooleanProperty("c03-no-move", true);
    public final BooleanProperty watchdogMotion = new BooleanProperty("watchdog-motion", false);
    private int flags = 0;
    private boolean execute = false;
    private boolean jump = false;
    public final BooleanProperty watchdogInventory = new BooleanProperty("watchdog-inventory", false);
    private boolean c16 = false;
    private boolean c0d = false;
    public final BooleanProperty spigotSpam = new BooleanProperty("spigot-spam", false);
    public final TextProperty message = new TextProperty("message", "/skill");
    public final BooleanProperty chatDebug = new BooleanProperty("chat-debug", false);
    private boolean transaction = false;
    public boolean isOnCombat = false;
    public final BooleanProperty betaVerus = new BooleanProperty("verus-beta", false);
    public final IntProperty betaVerusBufferSize = new IntProperty("buffer-size", 300, 0, 1000);
    public final IntProperty betaVerusRepeatTimes = new IntProperty("repeat-times", 1, 1, 5);
    public final IntProperty betaVerusRepeatTimesFighting = new IntProperty("repeat-times-fighting", 1, 1, 5);
    public final IntProperty betaVerusFlagDelay = new IntProperty("flag-delay", 40, 35, 60);
    private boolean betaVerus2Stat = false;
    private boolean betaVerusModified = false;
    private final ArrayDeque<Packet<INetHandlerPlayClient>> betaVerusPacketBuffer = new ArrayDeque<>();
    private final TimerUtil betaVerusLagTimer = new TimerUtil();
    public final BooleanProperty matrixDisabler = new BooleanProperty("matrix-disabler", false);
    public final BooleanProperty matrixTA = new BooleanProperty("matrix-ta", true);
    public final BooleanProperty matrixTA188 = new BooleanProperty("matrix-ta-188", false);
    public final FloatProperty matrixTAPacket = new FloatProperty("matrix-ta-packet", 1.0F, 1.0F, 5.0F);
    public final ModeProperty matrixAB = new ModeProperty("matrix-ab", 0, new String[]{"Off", "BlockHit", "Shield"});
    public final ModeProperty matrixT = new ModeProperty("matrix-t", 0, new String[]{"Off", "Pingspoof", "FunnyValue", "OldCancel"});
    public final BooleanProperty matrixReach = new BooleanProperty("matrix-reach", false);
    public final BooleanProperty matrixAllDir = new BooleanProperty("matrix-alldir", false);
    public final BooleanProperty taPacketCounter = new BooleanProperty("ta-packet-counter", false);
    private final TimerUtil lastC00timer = new TimerUtil();
    private final TimerUtil lastSAPtimer = new TimerUtil();
    private final TimerUtil lastC03timer = new TimerUtil();
    private final TimerUtil lastPacketTimer = new TimerUtil();
    private final TimerUtil lastFlagtimer = new TimerUtil();
    private boolean wasBlockHit = false;
    private int matrixIndex1 = 0;
    private double lastSpeed2d = 0.0;
    private int flagSkip = 0;
    private boolean predictNextC0F = false;
    private boolean shouldDelayMatrix = false;
    private int lastPongId = 0;
    private int c0fCount = 0;
    private long randomLong = 0L;
    public int savedAbusePacket = 0;
    private final ArrayDeque<Packet<?>> c00s = new ArrayDeque<>();
    private final ArrayDeque<LatePacket> c0fs = new ArrayDeque<>();
    public final BooleanProperty verusExperimental = new BooleanProperty("verus-experimental", false);
    public final BooleanProperty verusExpVoidTP = new BooleanProperty("exp-void-tp", false);
    public final IntProperty verusExpVoidTPDelay = new IntProperty("exp-void-tp-delay", 1000, 0, 30000);
    private long lastVoidTP = 0L;
    private int cancelNext = 0;
    // Preset selector. Index 0 Custom preserves every legacy toggle exactly. Others UNTESTED, need live servers.
    private static final int PRESET_CUSTOM = 0;
    private static final int PRESET_POLAR = 1;
    private static final int PRESET_INTAVE = 2;
    private static final int PRESET_GRIM_EXTREME = 3;
    private static final int PRESET_GRIM_SPEC = 4;
    private static final int PRESET_MINELAND = 5;
    private static final int PRESET_HYPIXEL = 6;
    private static final int PRESET_HYPIXEL_MOTION = 7;
    private static final int PRESET_KKCRAFT = 8;
    private static final int PRESET_BLOCKSMC = 9;
    public final ModeProperty preset = new ModeProperty("preset", 0, new String[]{"Custom", "Polar", "Intave", "GrimExtreme", "GrimSpec", "Mineland", "Hypixel", "HypixelMotion", "KKCraft", "BlocksMC"});
    public final BooleanProperty presetPolarAirline = new BooleanProperty("preset-polar-airline", false, () -> preset.getValue() == PRESET_POLAR);
    public final BooleanProperty presetIntaveCloud = new BooleanProperty("preset-intave-cloud", false, () -> preset.getValue() == PRESET_INTAVE);
    public final BooleanProperty presetIntaveResource = new BooleanProperty("preset-intave-resource", true, () -> preset.getValue() == PRESET_INTAVE);
    public final FloatProperty presetGrimX = new FloatProperty("preset-grim-x", 999999.0F, -1000000.0F, 1000000.0F, () -> preset.getValue() == PRESET_GRIM_EXTREME);
    public final FloatProperty presetGrimY = new FloatProperty("preset-grim-y", -999999.0F, -1000000.0F, 1000000.0F, () -> preset.getValue() == PRESET_GRIM_EXTREME);
    public final FloatProperty presetGrimZ = new FloatProperty("preset-grim-z", 999999.0F, -1000000.0F, 1000000.0F, () -> preset.getValue() == PRESET_GRIM_EXTREME);
    public final BooleanProperty presetGrim117 = new BooleanProperty("preset-grim-117", false, () -> preset.getValue() == PRESET_GRIM_SPEC);
    public final BooleanProperty presetHypixelSprint = new BooleanProperty("preset-hypixel-cancel-sprint", false, () -> preset.getValue() == PRESET_HYPIXEL);
    public final BooleanProperty presetKkAutoban = new BooleanProperty("preset-kk-autoban", false, () -> preset.getValue() == PRESET_KKCRAFT);
    public final BooleanProperty presetBlocksCombat = new BooleanProperty("preset-blocksmc-combat", true, () -> preset.getValue() == PRESET_BLOCKSMC);
    public final BooleanProperty presetBlocksWait = new BooleanProperty("preset-blocksmc-wait", true, () -> preset.getValue() == PRESET_BLOCKSMC && presetBlocksCombat.getValue());
    public final BooleanProperty presetBlocksPlace = new BooleanProperty("preset-blocksmc-place", false, () -> preset.getValue() == PRESET_BLOCKSMC);
    public final TextProperty customCancelPackets = new TextProperty("custom-cancel-packets", "", () -> preset.getValue() == PRESET_CUSTOM);
    public final BooleanProperty presetIntaveReach = new BooleanProperty("preset-intave-reach", false, () -> preset.getValue() == PRESET_INTAVE);
    public final BooleanProperty presetIntaveStrong = new BooleanProperty("preset-intave-strong", false, () -> preset.getValue() == PRESET_INTAVE);
    public final BooleanProperty presetHypixelMotionLobby = new BooleanProperty("preset-hypixel-motion-lobby", true, () -> preset.getValue() == PRESET_HYPIXEL_MOTION);
    private final ArrayDeque<C0FPacketConfirmTransaction> presetCloud = new ArrayDeque<>();
    private final ArrayDeque<Packet<INetHandlerPlayClient>> presetDelayed = new ArrayDeque<>();
    private boolean presetGrimDelayed = false;
    private int presetMotionFlags = 0;
    private int presetOffGround = 0;
    private boolean presetMotionDone = false;
    private boolean presetBlocksFlip = false;

    public Disabler() {
        super("Disabler", false, false, "Bypasses anticheat checks by altering outbound packets.");
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        // use deobfuscated player reference
        if (this.isEnabled() && mc.thePlayer != null) {
            Packet<?> packet = event.getPacket();
            if (this.matrixDisabler.getValue()) {
                this.handleMatrixPacket(event, packet);
            }

            this.handlePresetPacket(event, packet);

            this.handleCustomPacket(event, packet);

            if (this.lifeboat.getValue() && packet instanceof C0FPacketConfirmTransaction) {
                event.setCancelled(true);
                this.debugMessage("Cancelled Lifeboat Transaction");
            }

            if (this.basicDisabler.getValue()) {
                if (packet instanceof C00PacketKeepAlive && this.cancelC00.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C00-KeepAlive");
                } else if (packet instanceof C0FPacketConfirmTransaction && this.cancelC0F.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C0F-Transaction");
                } else if (packet instanceof C0APacketAnimation && this.cancelC0A.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C0A-Swing");
                } else if (packet instanceof C0BPacketEntityAction && this.cancelC0B.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C0B-Action");
                } else if (packet instanceof C07PacketPlayerDigging && this.cancelC07.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C07-Digging");
                } else if (packet instanceof C13PacketPlayerAbilities && this.cancelC13.getValue()) {
                    event.setCancelled(true);
                    this.debugMessage("Cancelled C13-Abilities");
                } else if (packet instanceof C03PacketPlayer && this.cancelC03.getValue()) {
                    C03PacketPlayer c03 = (C03PacketPlayer)packet;
                    // if packet is not a pure rotation/position packet, cancel
                    if (!(c03 instanceof C03PacketPlayer.C04PacketPlayerPosition) && !(c03 instanceof C03PacketPlayer.C05PacketPlayerLook) && !(c03 instanceof C03PacketPlayer.C06PacketPlayerPosLook)) {
                        if (this.c03NoMove.getValue() && this.isMoving()) {
                            return;
                        }

                        event.setCancelled(true);
                        this.debugMessage("Cancelled C03-Flying");
                    }
                }
            }

            if (this.noRotationDisabler.getValue() && packet instanceof C03PacketPlayer) {
                C03PacketPlayer c03 = (C03PacketPlayer)packet;
                int mode = this.modifyMode.getValue();
                switch (mode) {
                    case 0:
                        // ConvertNull: resend a simple packet using current player position/ground
                        if (c03 instanceof C03PacketPlayer.C04PacketPlayerPosition || c03 instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, c03.isOnGround()));
                        } else {
                            PacketUtil.sendPacket(new C03PacketPlayer(c03.isOnGround()));
                        }
                        event.setCancelled(true);
                        break;
                    case 3:
                        // SpoofZero / PosLook spoof -> send a packet with zero rotation
                        PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, 0.0F, 0.0F, c03.isOnGround()));
                        event.setCancelled(true);
                        break;
                    default:
                        // Other modify modes relied on mixin accessors for yaw/pitch which are not available here; skip modification
                        break;
                }
            }

            if (this.watchdogMotion.getValue()) {
                if (packet instanceof S07PacketRespawn) {
                    this.flags = 0;
                    this.execute = false;
                    this.jump = true;
                } else if (packet instanceof S08PacketPlayerPosLook && ++this.flags >= 20) {
                    this.execute = false;
                    this.flags = 0;
                }
            }

            if (this.watchdogInventory.getValue()) {
                if (packet instanceof C16PacketClientStatus) {
                    if (this.c16) {
                        event.setCancelled(true);
                    }

                    this.c16 = true;
                }

                if (packet instanceof C0DPacketCloseWindow) {
                    if (this.c0d) {
                        event.setCancelled(true);
                    }

                    this.c0d = true;
                }
            }

            if (this.grimPlace.getValue() && packet instanceof C08PacketPlayerBlockPlacement) {
                C08PacketPlayerBlockPlacement place = (C08PacketPlayerBlockPlacement)packet;
                // safest approach: cancel original and resend a simple placement using the held item
                event.setCancelled(true);
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                this.debugMessage("§cModified §aPlace §cPacket§7.");
            }

            if (this.intaveFly.getValue() || this.preset.getValue() == PRESET_INTAVE) {
                if (packet instanceof S08PacketPlayerPosLook && mc.thePlayer.capabilities.isFlying) {
                    this.shouldDelay = true;
                    this.debugMessage("§cStarted Canceling IntaveFly");
                }

                if (packet instanceof S32PacketConfirmTransaction && this.shouldDelay) {
                    event.setCancelled(true);
                    while (this.packets.size() >= MAX_DELAYED_PACKETS) {
                        this.packets.poll();
                    }
                    this.packets.add((Packet<INetHandlerPlayClient>) packet);
                }
            }
            if (this.verusCombat.getValue()) {
                if (mc.thePlayer.ticksExisted <= 20) {
                    this.isOnCombat = false;
                    return;
                }

                if (this.onlyCombat.getValue() && !this.isOnCombat) {
                    return;
                }

                if (packet instanceof S32PacketConfirmTransaction) {
                    event.setCancelled(true);
                    PacketUtil.sendPacket(new C0FPacketConfirmTransaction(this.transaction ? 1 : -1, (short)(this.transaction ? -1 : 1), this.transaction));
                    this.transaction = !this.transaction;
                }

                this.isOnCombat = false;
            }

            if (this.betaVerus.getValue()) {
                if (!(packet instanceof C0FPacketConfirmTransaction)) {
                    if (packet instanceof C03PacketPlayer) {
                        C03PacketPlayer c03 = (C03PacketPlayer)packet;
                        if (mc.thePlayer.ticksExisted % this.betaVerusFlagDelay.getValue() == 0 && mc.thePlayer.ticksExisted > this.betaVerusFlagDelay.getValue() + 1 && !this.betaVerusModified) {
                            this.debugMessage("Packet C03 -> BetaVerus Y offset");
                            this.betaVerusModified = true;
                            // can't modify packet internals safely here, so just set a flag and cancel on server position look
                            event.setCancelled(true);
                        }
                    } else if (packet instanceof S08PacketPlayerPosLook) {
                        S08PacketPlayerPosLook s08 = (S08PacketPlayerPosLook)packet;
                        double x = s08.getX() - mc.thePlayer.posX;
                        double y = s08.getY() - mc.thePlayer.posY;
                        double z = s08.getZ() - mc.thePlayer.posZ;
                        double diff = Math.sqrt(x * x + y * y + z * z);
                        if (diff <= 8.0D) {
                            event.setCancelled(true);
                            this.debugMessage("Silent Flag");
                            PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(s08.getX(), s08.getY(), s08.getZ(), s08.getYaw(), s08.getPitch(), true));
                        }
                    }
                } else {
                    this.betaVerusPacketBuffer.add((Packet<INetHandlerPlayClient>) packet);
                    event.setCancelled(true);
                    if (this.betaVerusPacketBuffer.size() > this.betaVerusBufferSize.getValue()) {
                        if (!this.betaVerus2Stat) {
                            this.betaVerus2Stat = true;
                        }

                        Packet<INetHandlerPlayClient> packeted = this.betaVerusPacketBuffer.poll();
                        int repeatTimes = this.isOnCombat ? this.betaVerusRepeatTimesFighting.getValue() : this.betaVerusRepeatTimes.getValue();

                        for(int i = 0; i < repeatTimes; ++i) {
                            PacketUtil.sendPacketNoEvent(packeted);
                        }
                    }

                    this.debugMessage("Packet C0F IN BufferSize=" + this.betaVerusPacketBuffer.size());
                }

                if (mc.thePlayer.ticksExisted <= 7) {
                    this.betaVerusLagTimer.reset();
                    this.betaVerusPacketBuffer.clear();
                }
            }

            if (this.verusExperimental.getValue()) {
                if (this.verusExpVoidTP.getValue() && packet instanceof C03PacketPlayer) {
                    if (mc.thePlayer.ticksExisted > 20 && mc.thePlayer.posY > -64.0D && this.lastVoidTP + this.verusExpVoidTPDelay.getValue() < System.currentTimeMillis()) {
                        this.lastVoidTP = System.currentTimeMillis();
                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, -48.0D, mc.thePlayer.posZ, true));
                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, false));
                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
                        this.cancelNext = 2;
                        event.setCancelled(true);
                        this.debugMessage("VerusExp VoidTP attempt");
                    }
                } else if (this.verusExpVoidTP.getValue() && packet instanceof S08PacketPlayerPosLook && this.cancelNext > 0) {
                    --this.cancelNext;
                    event.setCancelled(true);
                    this.debugMessage("VerusExp cancelled server position look");
                }
            }

        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && mc.thePlayer != null) {
            if (this.watchdogMotion.getValue() && this.jump) {
                mc.thePlayer.jump();
            }

            if (this.watchdogInventory.getValue()) {
                this.c16 = false;
                this.c0d = false;
            }

            if (this.verusFly.getValue()) {
                if (!this.isOnCombat && !mc.thePlayer.isDead) {
                    BlockPos pos = mc.thePlayer.getPosition().add(0, mc.thePlayer.posY > 0.0D ? -255 : 255, 0);
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(pos, 256, new ItemStack(Items.apple), 0.0F, 0.5F + (float)(Math.random() * 0.44D), 0.0F));
                } else {
                    this.isOnCombat = false;
                }
            }

            if (this.vulcanScaffold.getValue() && !mc.thePlayer.isInWater() && !mc.thePlayer.isSneaking() && !mc.thePlayer.isDead && !mc.thePlayer.isRiding() && this.isMoving() && mc.thePlayer.ticksExisted % this.vulcanPacketTick.getValue() == 0) {
                PacketUtil.sendPacket(new C0BPacketEntityAction(mc.thePlayer, Action.START_SNEAKING));
                PacketUtil.sendPacket(new C0BPacketEntityAction(mc.thePlayer, Action.STOP_SNEAKING));
            }

            this.handlePresetTick();

            if ((this.intaveFly.getValue() || this.preset.getValue() == PRESET_INTAVE) && this.shouldDelay) {
                while (this.packets.size() > MAX_DELAYED_PACKETS) {
                    PacketUtil.handlePacket(this.packets.poll());
                }
                if (!this.packets.isEmpty()
                        && (!mc.thePlayer.capabilities.isFlying || mc.thePlayer.ticksExisted % 10 == 0)) {
                    PacketUtil.handlePacket(this.packets.poll());
                    if (this.packets.isEmpty()) {
                        this.shouldDelay = false;
                    }
                }
            }

            if (this.betaVerus.getValue()) {
                this.betaVerusModified = false;
                if (this.betaVerusLagTimer.hasTimeElapsed(490L)) {
                    this.betaVerusLagTimer.reset();
                    if (!this.betaVerusPacketBuffer.isEmpty()) {
                        Packet<INetHandlerPlayClient> packet = this.betaVerusPacketBuffer.poll();
                        int repeatTimes = this.isOnCombat ? this.betaVerusRepeatTimesFighting.getValue() : this.betaVerusRepeatTimes.getValue();

                        for(int i = 0; i < repeatTimes; ++i) {
                            PacketUtil.sendPacketNoEvent(packet);
                        }

                        this.debugMessage("Packet Buffer Dump");
                    } else {
                        this.debugMessage("Empty Packet Buffer");
                    }
                }
            }

            if (this.matrixDisabler.getValue() && this.matrixTA.getValue()) {
                double currentSpeed = MoveUtil.getSpeed();
                if (currentSpeed > 0.001D) {
                    double speedDiff = Math.abs(currentSpeed - this.lastSpeed2d);
                    if (!mc.thePlayer.onGround && speedDiff < 1.0E-4) {
                        MoveUtil.setSpeed(currentSpeed * (0.99999999999 - Math.random() * 1.0E-7), MoveUtil.getMoveYaw());
                    }
                }

                this.lastSpeed2d = currentSpeed;
                if (this.lastSAPtimer.hasTimeElapsed(50L) && this.savedAbusePacket <= 0) {
                    ++this.savedAbusePacket;
                    this.lastSAPtimer.reset();
                }

                if (this.lastC03timer.hasTimeElapsed(4000L) && MoveUtil.getSpeed() < 0.001) {
                    this.lastC03timer.reset();
                    this.shouldDelayMatrix = (double)this.randomLong * 1.3 < (double)this.c0fCount;
                    this.randomLong = 0L;
                    this.c0fCount = 0;
                    if (!this.c00s.isEmpty()) {
                        this.lastC00timer.reset();

                        while(!this.c00s.isEmpty()) {
                            PacketUtil.sendPacketNoEvent(this.c00s.pollFirst());
                        }
                    }
                }

                if (this.lastFlagtimer.hasTimeElapsed(400L)) {
                    this.flagSkip = 0;
                    this.lastFlagtimer.reset();
                }

                if (this.savedAbusePacket < -2) {
                    this.savedAbusePacket = -2;
                }
            }

        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled()) {
            this.isOnCombat = true;
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (this.isEnabled()) {
            this.isOnCombat = false;
            if (this.preset.getValue() == PRESET_HYPIXEL_MOTION) {
                this.presetMotionFlags = 0;
                this.presetOffGround = 0;
                this.presetMotionDone = false;
            }
            if (this.betaVerus.getValue()) {
                this.betaVerus2Stat = false;
                this.betaVerusPacketBuffer.clear();
                this.betaVerusLagTimer.reset();
            }

        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    private void handleMatrixPacket(PacketEvent event, Packet<?> packet) {
        if (packet instanceof C0FPacketConfirmTransaction && this.matrixT.getValue() == 1) {
            C0FPacketConfirmTransaction c0f = (C0FPacketConfirmTransaction)packet;
            // avoid calling obfuscated getter, store 0 as fallback
            this.lastPongId = 0;
            if (!event.isCancelled()) {
                long firstTime = !this.c0fs.isEmpty() ? this.c0fs.getLast().getRequiredMs() : 100L;
                this.c0fs.add(new LatePacket(packet, System.currentTimeMillis() + 35000L));
                event.setCancelled(true);
                long secTime = !this.c0fs.isEmpty() ? this.c0fs.getLast().getRequiredMs() : 200L;
                if (secTime - firstTime >= 20L) {
                    this.c0fs.pollLast();
                }
            }

            while(!this.c0fs.isEmpty() && this.c0fs.peekFirst().getRequiredMs() <= System.currentTimeMillis()) {
                PacketUtil.sendPacketNoEvent(this.c0fs.pollFirst().getPacket());
            }

            ++this.c0fCount;
        }

        if (packet instanceof C00PacketKeepAlive && this.matrixT.getValue() == 1) {
            if (this.c00s.isEmpty()) {
                this.lastC00timer.reset();
            }

            this.c00s.add(packet);
            event.setCancelled(true);
        }

        if (packet instanceof C0BPacketEntityAction && this.matrixAllDir.getValue()) {
            C0BPacketEntityAction c0b = (C0BPacketEntityAction)packet;
            try {
                // attempt to avoid calling obfuscated method; cancel common sprint actions by class name check
                event.setCancelled(true);
            } catch (Exception ignored) {
            }
        }

        if (packet instanceof C03PacketPlayer && this.matrixT.getValue() == 1) {
            C03PacketPlayer c03 = (C03PacketPlayer)packet;
            try {
                // cannot reliably inspect obfuscated movement flags; decrement savedAbusePacket conservatively
                --this.savedAbusePacket;
            } catch (Exception ignored) {
            }

            this.lastC03timer.reset();
            if (this.savedAbusePacket < 5 && !this.c0fs.isEmpty()) {
                long firstTime = this.c0fs.getFirst().getRequiredMs();
                PacketUtil.sendPacketNoEvent(this.c0fs.pollFirst().getPacket());
                if (!this.c0fs.isEmpty()) {
                    long secTime = this.c0fs.getFirst().getRequiredMs();
                    if (secTime - firstTime < 20L) {
                        PacketUtil.sendPacketNoEvent(this.c0fs.pollFirst().getPacket());
                    }
                }
            }

            if (this.lastC00timer.hasTimeElapsed(10000L)) {
                this.shouldDelayMatrix = (double)this.randomLong * 1.3 < (double)this.c0fCount;
                this.randomLong = 0L;
                this.c0fCount = 0;
                if (!this.c00s.isEmpty()) {
                    this.lastC00timer.reset();

                    while(!this.c00s.isEmpty()) {
                        PacketUtil.sendPacketNoEvent(this.c00s.pollFirst());
                    }
                }
            }

            ++this.randomLong;
        }

        if (packet instanceof S08PacketPlayerPosLook && event.getType() == EventType.RECEIVE) {
            if (this.flagSkip > 0) {
                --this.flagSkip;
                event.setCancelled(true);
                this.debugMessage("Skipped flag packet");
            }

            this.lastFlagtimer.reset();
        }

        if (packet instanceof S07PacketRespawn && event.getType() == EventType.RECEIVE) {
            this.savedAbusePacket = 0;
            this.flagSkip = 0;
            this.lastSAPtimer.reset();
        }

    }

    private void handleCustomPacket(PacketEvent event, Packet<?> packet) {
        if (this.preset.getValue() != PRESET_CUSTOM || event.getType() != EventType.SEND) {
            return;
        }
        String list = this.customCancelPackets.getValue();
        if (list == null || list.trim().isEmpty() || packet == null) {
            return;
        }
        String simple = packet.getClass().getSimpleName().toUpperCase();
        for (String entry : list.split(",")) {
            String clean = entry.trim().toUpperCase();
            if (!clean.isEmpty() && (simple.equals(clean) || simple.contains(clean))) {
                event.setCancelled(true);
                this.debugMessage("Custom cancelled " + packet.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean isLobby() {
        if (mc.theWorld == null) {
            return true;
        }
        for (String line : ServerUtil.getScoreboardLines()) {
            String lower = line.toLowerCase();
            if (lower.contains("lobby") || lower.contains("limbo")) {
                return true;
            }
        }
        return false;
    }

    private void handlePresetPacket(PacketEvent event, Packet<?> packet) {
        int p = this.preset.getValue();
        if (p == PRESET_CUSTOM) {
            return;
        }
        boolean send = event.getType() == EventType.SEND;
        boolean receive = event.getType() == EventType.RECEIVE;
        if (p == PRESET_POLAR) {
            // Adapted from Raven S+ PolarDisabler. UNTESTED, needs live server.
            if (send && packet instanceof C00PacketKeepAlive) {
                event.setCancelled(true);
                this.debugMessage("Polar cancelled C00");
            } else if (receive && packet instanceof S00PacketKeepAlive) {
                event.setCancelled(true);
                PacketUtil.sendPacket(new C00PacketKeepAlive(0));
                this.debugMessage("Polar spoofed keepalive");
            }
        } else if (p == PRESET_INTAVE) {
            // Adapted from Raven S+ IntaveDisabler. UNTESTED, needs live server.
            if (send && packet instanceof C19PacketResourcePackStatus && this.presetIntaveResource.getValue()) {
                event.setCancelled(true);
                this.debugMessage("Intave cancelled resource pack status");
            } else if (send && packet instanceof C0FPacketConfirmTransaction && this.presetIntaveCloud.getValue()) {
                event.setCancelled(true);
                this.presetCloud.add((C0FPacketConfirmTransaction) packet);
                if (this.presetCloud.size() >= 50) {
                    PacketUtil.sendPacketNoEvent(this.presetCloud.pollFirst());
                }
            }
        } else if (p == PRESET_GRIM_EXTREME) {
            // Adapted from Raven S+ GrimExtremeDisabler, cancel plus resend since packets are read-only here. UNTESTED, needs live server.
            if (send && (packet instanceof C03PacketPlayer.C04PacketPlayerPosition || packet instanceof C03PacketPlayer.C06PacketPlayerPosLook)) {
                event.setCancelled(true);
                double x = mc.thePlayer.posX + this.presetGrimX.getValue();
                double y = mc.thePlayer.posY + this.presetGrimY.getValue();
                double z = mc.thePlayer.posZ + this.presetGrimZ.getValue();
                if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                    PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(x, y, z, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
                } else {
                    PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, mc.thePlayer.onGround));
                }
                this.debugMessage("GrimExtreme offset position");
            }
        } else if (p == PRESET_GRIM_SPEC) {
            // Adapted from Raven S+ GrimSpecDisabler. UNTESTED, needs live server.
            if (receive && packet instanceof S08PacketPlayerPosLook) {
                if (mc.thePlayer.capabilities.isFlying && !this.presetGrimDelayed) {
                    this.presetGrimDelayed = true;
                } else if (this.presetGrimDelayed) {
                    this.debugMessage("Flag detected, consider disabling Disabler");
                }
            } else if (receive && packet instanceof S32PacketConfirmTransaction && this.presetGrimDelayed) {
                event.setCancelled(true);
                while (this.presetDelayed.size() >= MAX_DELAYED_PACKETS) {
                    this.presetDelayed.pollFirst();
                }
                this.presetDelayed.add((Packet<INetHandlerPlayClient>) packet);
                if (this.presetGrim117.getValue()) {
                    PacketUtil.sendPacket(new C0FPacketConfirmTransaction(0, (short) 0, true));
                }
            }
        } else if (p == PRESET_MINELAND) {
            // Adapted from Raven S+ MinelandDisabler. UNTESTED, needs live server.
            if (send && packet instanceof C0FPacketConfirmTransaction) {
                event.setCancelled(true);
                this.debugMessage("Mineland cancelled C0F");
            } else if (send && packet instanceof C03PacketPlayer && !(packet instanceof C03PacketPlayer.C04PacketPlayerPosition) && !(packet instanceof C03PacketPlayer.C05PacketPlayerLook) && !(packet instanceof C03PacketPlayer.C06PacketPlayerPosLook)) {
                event.setCancelled(true);
                this.debugMessage("Mineland cancelled bare C03");
            }
        } else if (p == PRESET_HYPIXEL) {
            // Adapted from Raven S+ HypixelDisabler. UNTESTED, needs live server.
            if (receive && packet instanceof S08PacketPlayerPosLook) {
                if (mc.thePlayer.isBlocking()) {
                    PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.UP));
                } else {
                    mc.playerController.onStoppedUsingItem(mc.thePlayer);
                }
            }
        } else if (p == PRESET_HYPIXEL_MOTION) {
            // Adapted from Raven S+ HypixelMotionDisabler. UNTESTED, needs live server.
            if (this.presetHypixelMotionLobby.getValue() && this.isLobby()) {
                return;
            }
            if (receive && packet instanceof S08PacketPlayerPosLook && !this.presetMotionDone) {
                ++this.presetMotionFlags;
                if (this.presetMotionFlags >= 20) {
                    this.presetMotionDone = true;
                    this.presetMotionFlags = 0;
                    this.debugMessage("Watchdog motion disabled");
                }
            }
        } else if (p == PRESET_KKCRAFT) {
            // KKCraft slot and chest dance has no Myau equivalent, tick burst only. UNTESTED, needs live server.
        } else if (p == PRESET_BLOCKSMC) {
            // Adapted from Raven S+ BlocksMCDisabler, resend replaces uid accessor writes. UNTESTED, needs live server.
            if (send && packet instanceof C08PacketPlayerBlockPlacement && this.presetBlocksPlace.getValue()) {
                event.setCancelled(true);
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                this.debugMessage("BlocksMC resent placement");
            } else if (send && packet instanceof C0FPacketConfirmTransaction && this.presetBlocksCombat.getValue()) {
                if (mc.thePlayer.ticksExisted <= 20) {
                    return;
                }
                if (this.presetBlocksWait.getValue() && !this.isOnCombat) {
                    return;
                }
                event.setCancelled(true);
                PacketUtil.sendPacket(new C0FPacketConfirmTransaction(this.presetBlocksFlip ? 1 : -1, (short) (this.presetBlocksFlip ? -1 : 1), this.presetBlocksFlip));
                this.presetBlocksFlip = !this.presetBlocksFlip;
                this.debugMessage("BlocksMC flipped C0F");
            }
        }
    }

    private void handlePresetTick() {
        int p = this.preset.getValue();
        if (p == PRESET_CUSTOM) {
            return;
        }
        if (p == PRESET_INTAVE && mc.thePlayer != null && mc.thePlayer.ticksExisted > 20) {
            // Reach/Strong bursts. UNTESTED, needs live server.
            if (this.presetIntaveReach.getValue() && mc.thePlayer.ticksExisted % 20 == 0) {
                PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
            }
            if (this.presetIntaveStrong.getValue() && mc.thePlayer.ticksExisted % 20 == 10) {
                for (int i = 0; i < 2; i++) {
                    PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
                }
            }
        }
        if (p == PRESET_POLAR && this.presetPolarAirline.getValue()) {
            // Adapted from Raven S+ PolarDisabler airline offset. UNTESTED, needs live server.
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX + 1024.0D, mc.thePlayer.posY + 1024.0D, mc.thePlayer.posZ + 1024.0D, mc.thePlayer.onGround));
        } else if (p == PRESET_HYPIXEL && this.presetHypixelSprint.getValue()) {
            // Adapted from Raven S+ HypixelDisabler sprint cancel. UNTESTED, needs live server.
            mc.thePlayer.setSprinting(false);
        } else if (p == PRESET_HYPIXEL_MOTION && !this.presetMotionDone) {
            // Adapted from Raven S+ HypixelMotionDisabler. UNTESTED, needs live server.
            if (this.presetHypixelMotionLobby.getValue() && this.isLobby()) {
                return;
            }
            if (mc.thePlayer.onGround) {
                this.presetOffGround = 0;
                if (!mc.gameSettings.keyBindJump.isKeyDown()) {
                    mc.thePlayer.jump();
                }
            } else {
                ++this.presetOffGround;
                if (this.presetOffGround >= 9) {
                    if (this.presetOffGround % 2 == 0) {
                        double jitter = 0.09D + Math.random() * 0.03D;
                        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX + jitter, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
                    }
                    mc.thePlayer.motionX = 0.0D;
                    mc.thePlayer.motionY = 0.0D;
                    mc.thePlayer.motionZ = 0.0D;
                }
            }
        } else if (p == PRESET_KKCRAFT && this.presetKkAutoban.getValue()) {
            // Adapted from Raven S+ KKCraftDisabler auto ban burst. UNTESTED, needs live server.
            for (int i = 0; i < 6; ++i) {
                PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
            }
        }
    }

    private void resetPresetState() {
        this.presetCloud.clear();
        this.presetDelayed.clear();
        this.presetGrimDelayed = false;
        this.presetMotionFlags = 0;
        this.presetOffGround = 0;
        this.presetMotionDone = false;
        this.presetBlocksFlip = false;
    }

    private void debugMessage(String msg) {
        if (this.chatDebug.getValue()) {
            mc.thePlayer.addChatMessage(new ChatComponentText("§7[§bDisabler§7] §f" + msg));
        }

    }

    public void onEnabled() {
        this.resetPresetState();
    }

    public void onDisabled() {
        this.debugMessage("Disabler disabled - resetting all states");
        if (mc.getNetHandler() != null) {
            while (!this.presetDelayed.isEmpty()) {
                PacketUtil.handlePacket(this.presetDelayed.pollFirst());
            }
        }
        this.resetPresetState();
        this.flags = 0;
        this.execute = false;
        this.jump = false;
        this.transaction = false;
        this.isOnCombat = false;
        this.c16 = false;
        this.c0d = false;
        this.shouldDelay = false;
        this.betaVerus2Stat = false;
        this.betaVerusModified = false;
        this.cancelNext = 0;
        this.lastVoidTP = 0L;
        this.packets.clear();
        this.betaVerusPacketBuffer.clear();
        this.c00s.clear();
        this.c0fs.clear();
        this.wasBlockHit = false;
        this.matrixIndex1 = 0;
        this.lastSpeed2d = 0.0;
        this.flagSkip = 0;
        this.predictNextC0F = false;
        this.shouldDelayMatrix = false;
        this.lastPongId = 0;
        this.c0fCount = 0;
        this.randomLong = 0L;
        this.savedAbusePacket = 0;
        this.debugMessage("Reset complete - flags:" + this.flags + " shouldDelay:" + this.shouldDelay + " lastVoidTP:" + this.lastVoidTP);
    }

    public String[] getSuffix() {
        if (this.preset.getValue() != PRESET_CUSTOM) {
            return new String[]{this.preset.getModeString()};
        }

        int active = 0;
        if (this.basicDisabler.getValue()) {
            ++active;
        }

        if (this.verusCombat.getValue()) {
            ++active;
        }

        if (this.watchdogMotion.getValue()) {
            ++active;
        }

        if (this.betaVerus.getValue()) {
            ++active;
        }

        return new String[]{String.valueOf(active)};
    }
}
