package atlas.module.modules;

import com.google.common.base.CaseFormat;
import lombok.Getter;
import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.*;
import atlas.management.RotationHelper;
import atlas.management.RotationState;
import atlas.mixin.IAccessorEntity;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.*;
import atlas.util.*;
import atlas.util.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.MathHelper;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private static final long ROTATION_UPDATE_INTERVAL_MS = 50;

    public final ModeProperty engine = new ModeProperty("engine", 0, new String[]{"LiquidBounce", "Atlas"});
    public final ModeProperty cpsMode;
    public final ModeProperty mode;
    public final ModeProperty sort;
    public ModeProperty autoBlock;

    public final IntProperty attackTick = new IntProperty("AttackTick", 0, 1, 5, () -> this.autoBlock.getValue() == 6);

    public final IntProperty attackOnDamage = new IntProperty("AttackOnDamage", 0, 0, 4, this::atlasEngine);
    public final BooleanProperty noAirSprintHit = new BooleanProperty("NoAirSprintHit", false, this::atlasEngine);
    private final DamageHits damageHits = new DamageHits();

    private static final int YURI_FIRST = 11;
    public final BooleanProperty yuriOnlyWhenHurt = new BooleanProperty("yuri-only-when-hurt", false,
            () -> this.autoBlock.getValue() >= YURI_FIRST);
    public final IntProperty yuriHurtTicks = new IntProperty("yuri-hurt-ticks", 4, 0, 10,
            () -> this.autoBlock.getValue() >= YURI_FIRST && this.yuriOnlyWhenHurt.getValue());

    public final BooleanProperty simulateClicks = new BooleanProperty("simulate-clicks", true);
    /** Up to how much shorter than AttackRange the first hit may wait for; a new edge after each crossing. */
    public final FloatProperty attackRangeDrift = new FloatProperty("AttackRangeDrift", 0.0F, 0.0F, 0.5F, this::atlasEngine);
    private final atlas.lb.combat.ReachDrift reachDrift = new atlas.lb.combat.ReachDrift(new java.util.Random());

    private final atlas.humanize.Humanizer atlasHumanizer = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_ATLAS_AURA);
    private final KillAuraYuri yuri = new KillAuraYuri();
    private boolean yuriBlockAfterMove;

    public final ModeProperty legitThreat = new ModeProperty("LegitThreat", 0, new String[]{"Look", "Swing"},
            () -> this.autoBlock.getValue() == 7);
    public final FloatProperty legitTolerance = new FloatProperty("LegitTolerance", 0.3F, 0.0F, 1.0F,
            () -> this.autoBlock.getValue() == 7 && this.legitThreat.getValue() == 0);
    public final BooleanProperty legitSameTick = new BooleanProperty("LegitSameTick", false,
            () -> this.autoBlock.getValue() == 7);
    public final BooleanProperty autoBlockRequirePress;
    public final FloatProperty autoBlockCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;

    public final FloatProperty aimLead = new FloatProperty("AimLead", 0.0F, 0.0F, 1.5F);

    public final FloatProperty scanExtra = new FloatProperty("ScanExtra", 2.5F, 0.0F, 4.0F);

    public final ModeProperty rotationTiming = new ModeProperty("RotationTiming", 0, new String[]{"Normal", "Snap"},
            this::isGenericAim);

    public final BooleanProperty lazyRotation = new BooleanProperty("LazyRotation", false, this::isGenericAim);

    public final BooleanProperty shortStop = new BooleanProperty("ShortStop", false, this::isGenericAim);

    public final BooleanProperty failAim = new BooleanProperty("FailAim", false, this::isGenericAim);

    public final ModeProperty raycast = new ModeProperty("Raycast", 1, new String[]{"None", "Enemy"});

    public final BooleanProperty exitClick = new BooleanProperty("ExitClick", true);

    public final FloatProperty aimLeadCap = new FloatProperty("AimLeadCap", 1.2F, 0.2F, 3.0F,
            () -> this.aimLead.getValue() > 0.0F);
    public final ModeProperty rotations;
    public final FloatProperty deadZoneSize;
    public final FloatProperty maxTurnSpeed;
    public final FloatProperty minTurnSpeed;
    public final FloatProperty acceleration;
    public final FloatProperty deceleration;
    public final BooleanProperty useOvershoot;
    public final FloatProperty overshootStrength;
    public final FloatProperty overshootRecovery;
    public final FloatProperty noiseStrength;
    public final BooleanProperty visualizeAim;
    public final BooleanProperty smoothBack;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty ravenSmoothing;
    public final IntProperty ravenPredictTicks;
    public final IntProperty ravenYawRandom;
    public final IntProperty angleStep;
    public final ModeProperty aimMode;
    public final IntProperty aimMinSpeed;
    public final IntProperty aimMaxSpeed;

    public final BooleanProperty multipoint;

    public final IntProperty turnAccel;

    public final FloatProperty aimDrift = new FloatProperty("AimDrift", 0.5F, 0.0F, 1.0F);
    public final BooleanProperty flickOvershoot = new BooleanProperty("FlickOvershoot", true);
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;
    public final BooleanProperty randomize;
    public final FloatProperty randomizeRange;
    public final FloatProperty yRandomizeStrength;
    public final FloatProperty liquidBounceHorizontalSpeed;
    public final FloatProperty liquidBounceVerticalSpeed;
    public final FloatProperty liquidBounceSmoothFactor;
    public final BooleanProperty liquidBouncePredict;
    public final FloatProperty liquidBouncePredictSize;
    public final BooleanProperty liquidBounceRandomize;
    public final FloatProperty liquidBounceRandomizeRange;
    public final FloatProperty liquidBounceHorizontalSearch;
    public final FloatProperty liquidBounceBodyPointMin;
    public final FloatProperty liquidBounceBodyPointMax;

    public final FloatProperty advGravity = new FloatProperty("Adv-Gravity", 9.0F, 1.0F, 20.0F, this::isAdvanced);
    public final FloatProperty advWind = new FloatProperty("Adv-Wind", 6.0F, 0.0F, 10.0F, this::isAdvanced);
    public final FloatProperty advDampedDistance = new FloatProperty("Adv-DampedDistance", 12.0F, 1.0F, 45.0F, this::isAdvanced);
    public final FloatProperty advMaxStep = new FloatProperty("Adv-MaxStep", 15.0F, 3.0F, 60.0F, this::isAdvanced);
    public final IntProperty advOvershootChance = new IntProperty("Adv-OvershootChance", 77, 0, 100, this::isAdvanced);
    public final FloatProperty advOvershootScale = new FloatProperty("Adv-OvershootScale", 0.0F, 0.0F, 0.6F, this::isAdvanced);
    public final FloatProperty advOvershootMax = new FloatProperty("Adv-OvershootMax", 17.0F, 2.0F, 45.0F, this::isAdvanced);
    public final FloatProperty advGaussian = new FloatProperty("Adv-Gaussian", 0.0F, 0.0F, 0.6F, this::isAdvanced);
    public final IntProperty advAccuracy = new IntProperty("Adv-Accuracy", 40, 40, 100, this::isAdvanced);
    public final FloatProperty advMinStep = new FloatProperty("Adv-MinStep", 0.0F, 0.0F, 8.0F, this::isAdvanced);
    public final FloatProperty advPrediction = new FloatProperty("Adv-Prediction", 1.0F, 0.0F, 3.5F, this::isAdvanced);
    public final FloatProperty advDeadzone = new FloatProperty("Adv-Deadzone", 1.0F, 0.25F, 4.5F, this::isAdvanced);
    public final FloatProperty advAnchor = new FloatProperty("Adv-Anchor", 0.0F, 0.0F, 0.7F, this::isAdvanced);
    public final IntProperty advHoldTicks = new IntProperty("Adv-HoldTicks", 2, 0, 8, this::isAdvanced);
    public final FloatProperty advCruiseFloor = new FloatProperty("Adv-CruiseFloor", 1.0F, 0.0F, 5.0F, this::isAdvanced);
    public final FloatProperty advPaceJitter = new FloatProperty("Adv-PaceJitter", 0.0F, 0.0F, 0.8F, this::isAdvanced);
    public final IntProperty advBurstChance = new IntProperty("Adv-BurstChance", 21, 0, 100, this::isAdvanced);
    public final FloatProperty advBurstStrength = new FloatProperty("Adv-BurstStrength", 0.0F, 0.0F, 1.0F, this::isAdvanced);
    public final BooleanProperty advFlickGuard = new BooleanProperty("Adv-FlickGuard", true, this::isAdvanced);
    public final FloatProperty advFlickMax = new FloatProperty("Adv-FlickMax", 29.0F, 4.0F, 60.0F,
            () -> this.isAdvanced() && this.advFlickGuard.getValue());

    public final BooleanProperty advSwing = new BooleanProperty("Adv-Swing", true, this::isAdvanced);
    public final IntProperty advAimReaction = new IntProperty("Adv-AimReaction", 180, 30, 450, this::isAdvanced);
    public final IntProperty advAimReactionJitter = new IntProperty("Adv-AimReactionJitter", 44, 0, 220, this::isAdvanced);
    public final IntProperty advTriggerReaction = new IntProperty("Adv-TriggerReaction", 95, 0, 300, this::isAdvanced);
    public final IntProperty advTriggerReactionJitter = new IntProperty("Adv-TriggerReactionJitter", 30, 0, 140, this::isAdvanced);

    public final ModeProperty kbDisplace = new ModeProperty("KBDisplace", 0, new String[]{"OFF", "SAFE", "FULL"},
            this::isAdvanced);

    private atlas.property.IntRange cps;

    private boolean kbDisplacing;
    private final atlas.util.AdvancedAim advancedAim = new atlas.util.AdvancedAim();
    private final atlas.util.AdvancedAim.Settings advancedSettings = new atlas.util.AdvancedAim.Settings();

    private boolean otherActionsThisTick;
    private final int[] clickPattern = {16, 22, 14, 46, 18, 8, 8, 63, 25, 25, 12, 39, 26, 18, 6, 62, 26, 18, 21, 40, 26, 8, 16, 46, 26, 20, 15, 50, 25, 10, 11, 43, 25, 11, 37, 39, 25, 12, 18, 54, 25, 25, 15, 41, 27, 9, 1, 66, 26, 17, 21, 48, 27, 8, 6, 62, 28, 19, 13, 47, 26, 7, 14, 53, 27, 16, 29, 38, 27, 8, 6, 60, 27, 22, 19, 45, 26, 10, 10, 62, 25, 20, 28, 22, 26, 19, 11, 57, 26, 16, 32, 36, 26, 9, 9, 66, 27, 19, 27, 38, 26, 9, 10, 61, 26, 25, 15, 34, 26, 20, 10, 52, 26, 22, 28, 29, 27, 8, 3, 63, 26, 21, 27, 38, 26, 10, 11, 38, 27, 15, 31, 39, 25, 13, 10, 45, 27, 14, 27, 40, 26, 10, 6, 51, 26, 18, 31, 27, 27, 11, 14, 47, 26, 23, 21, 35, 26, 12, 13, 41, 26, 15, 31, 36, 27, 16, 9, 44, 27, 14, 30, 39, 25, 14, 10, 46, 28, 10, 24, 45, 26, 7, 5, 46, 26, 20, 6, 50, 26, 8, 6, 51, 26, 17, 20, 40, 27, 25, 1, 32, 26, 20, 9, 46, 25, 15, 12, 30, 26, 11, 25, 46, 27, 13, 10, 36, 27, 20, 15, 41, 26, 8, 6, 41, 26, 12, 29, 44, 26, 13, 11, 44, 26, 12, 27, 36, 26, 23, 4, 39, 26, 24, 12, 47, 26, 9, 2, 65, 26, 16, 27, 34, 26, 25, 0, 53, 26, 16, 3, 47, 27, 16, 10, 41, 26, 18, 25, 38, 26, 11, 10, 50, 27, 20, 20, 29, 26, 11, 7, 66, 26, 20, 18, 31, 26, 21, 21, 28, 26, 21, 29, 25, 27, 15, 12, 43, 28, 11, 31, 32, 27, 23, 0, 49, 27, 20, 30, 30, 25, 32, 0, 50, 26, 12, 25, 34, 27, 11, 11, 44, 27, 23, 26, 25, 27, 16, 11, 46, 26, 13, 32, 35, 28, 9, 5, 48, 26, 21, 29, 37, 26, 10, 7, 48, 27, 20, 21, 41, 24, 7, 18, 46, 25, 22, 22, 33, 25, 10, 5, 59, 26, 21, 19, 29, 26, 11, 10, 46, 25, 22, 29, 31, 25, 11, 12, 50, 24, 20, 28, 40, 25, 10, 4, 56, 25, 16, 36, 30, 24, 10, 9, 63, 25, 22, 22, 32, 25, 9, 8, 58, 27, 10, 43, 30, 26, 8, 3, 60, 26, 24, 14, 42, 26, 12, 9, 49, 25, 11, 32, 38, 27, 8, 8, 50, 26, 20, 26, 32, 25, 10, 4, 66, 25, 18, 28, 24, 26, 10, 8, 54, 25, 16, 32, 34, 24, 9, 12, 54, 25, 18, 18, 41, 28, 9, 16, 50, 28, 15, 21, 46, 27, 9, 8, 49, 26, 21, 18, 36, 26, 15, 10, 54, 27, 22, 27, 32, 25, 9, 15, 48, 28, 19, 26, 35, 27, 9, 13, 48, 25, 21, 23, 33, 27, 8, 3, 65, 26, 19, 23, 39, 25, 9, 13, 44, 26, 25, 19, 35, 26, 14, 6, 63, 27, 15, 23, 32, 28, 8, 2, 65, 26, 19, 24, 34, 27, 12, 0, 49, 26, 21, 34, 34, 26, 8, 9, 60, 26, 23, 19, 34, 26, 10, 5, 59, 26, 12, 36, 39, 26, 11, 11, 44, 26, 25, 5, 47, 25, 9, 10, 49, 27, 19, 24, 31, 26, 10, 4, 60, 27, 25, 9, 41, 26, 20, 7, 54, 24, 11, 35, 35, 26, 9, 5, 67, 26, 17, 19, 43, 26, 24, 17, 39, 25, 16, 11, 45, 25, 9, 3, 60, 25, 25, 16, 37, 28, 9, 5, 55, 26, 15, 12, 49, 25, 17, 8, 39, 25, 15, 16, 48, 25, 12, 9, 37, 25, 17, 31, 38, 27, 8, 8, 62, 26, 23, 14, 38, 27, 16, 10, 45, 26, 13, 25, 42, 25, 9, 8, 57, 27, 12, 36, 38, 27, 13, 11, 30, 27, 21, 24, 47, 25, 10, 6, 54, 26, 13, 28, 42, 25, 10, 5, 47, 26, 21, 22, 44, 26, 10, 8, 50, 28, 17, 26, 33, 26, 10, 14, 55, 27, 14, 30, 29, 25, 13, 1, 70, 26, 14, 30, 26, 27, 12, 14, 67, 25, 21, 4, 33, 25, 11, 5, 48, 26, 21, 21, 39, 25, 11, 1, 55, 26, 11, 29, 32, 26, 12, 10, 50, 27, 16, 26, 36, 27, 23, 3, 57, 27, 11, 23, 37, 26, 9, 16, 37, 26, 16, 38, 37, 26, 9, 2, 60, 27, 22, 16, 38, 27, 9, 5, 53, 26, 14, 33, 30, 25, 13, 11, 46, 25, 23, 22, 43, 24, 10, 13, 51, 25, 21, 25, 35, 27, 8, 16, 48, 25, 21, 19, 42, 25, 12, 12, 49, 26, 21, 18, 42, 25, 12, 13, 51, 27, 16, 25, 37, 26, 11, 12, 47, 27, 21, 13, 39, 27, 5, 9, 61, 25, 24, 11, 39, 26, 10, 9, 52, 26, 15, 33, 28, 38, 0, 9, 55, 26, 14, 39, 24, 25, 10, 9, 52, 27, 13, 29, 36, 25, 12, 9, 49, 25, 22, 30, 26, 26, 10, 2, 66, 27, 17, 30, 31, 26, 14, 7, 64, 28, 16, 31, 28, 24, 13, 14, 54, 25, 12, 29, 35, 27, 10, 8, 49, 27, 18, 26, 38, 25, 8, 14, 46, 26, 23, 15, 36, 26, 11, 5, 61, 27, 23, 8, 42, 25, 9, 10, 57, 26, 11, 29, 37, 25, 11, 9, 56, 27, 11, 32, 35, 26, 12, 6, 62, 27, 20, 33, 27, 27, 10, 14, 50, 27, 17, 28, 40, 25, 9, 8, 46, 26, 23, 16, 44, 26, 11, 13, 47, 28, 19, 19, 36, 26, 8, 7, 55, 26, 15, 24, 39, 26, 12, 9, 56, 26, 15, 28, 36, 25, 10, 10, 51, 25, 17, 32, 36, 25, 9, 7, 58, 26, 11, 31, 32, 26, 7, 14, 57, 26, 13, 22, 25, 24, 9, 14, 42, 26, 12, 27, 31, 25, 9, 2, 62, 27, 23, 12, 33, 26, 8, 18, 46, 25, 24, 14, 33, 24, 10, 14, 50, 25, 20, 21, 38, 26, 9, 1, 61, 25, 11, 30, 35, 26, 10, 10, 53, 25, 18, 22, 35, 25, 8, 4, 44, 25, 25, 21, 37, 24, 13, 6, 35, 27, 11, 34, 32, 25, 9, 10, 51, 26, 17, 18, 31, 24, 11, 8, 53, 26, 16, 30, 35, 26, 8, 10, 60, 25, 11, 32, 29, 25, 22, 2, 53, 26, 16, 30, 33, 27, 9, 11, 57, 25, 13, 32, 30, 25, 14, 10, 67, 24, 21, 29, 35, 27, 8, 12, 70, 26, 14, 19, 42, 27, 22, 0, 57, 27, 12, 31, 33, 25, 9, 12, 62, 27, 23, 14, 43, 25, 11, 2, 71, 28, 12, 33, 31, 27, 8, 12, 71, 26, 15, 23, 42, 28, 9, 8, 63, 26, 22, 22, 37, 27, 7, 4, 78, 27, 20, 26, 34, 25, 9, 15, 64, 27, 21, 23, 32, 26, 12, 11, 77, 25, 11, 32, 29, 26, 9, 15, 63, 27, 19, 23, 38, 26, 10, 15, 57, 26, 14, 37, 14, 26, 18, 6, 67, 26, 13, 31, 33, 26, 19, 1, 60, 27, 25, 22, 24, 27, 22, 2, 55, 26, 13, 25, 34, 26, 24, 0, 68, 25, 20, 22, 31, 25, 11, 4, 80, 24, 22, 22, 29, 26, 16, 8, 81, 25, 11, 22, 38, 27, 10, 11, 50, 27, 18, 35, 32, 26, 10, 5, 76, 26, 23, 22, 30, 24, 21, 8, 67, 27, 24, 16, 42, 27, 8, 3};
    private final TimerUtil timer = new TimerUtil();
    private final Random random = new Random();
    public AttackData target = null;
    private Rotation serverRotation = new Rotation(0, 0);
    private boolean isSmoothBacking = false;
    private boolean wantsToDisable = false;

    private boolean returning;
    private final RotationEngine backEngine = new RotationEngine();
    private float lastSentYaw;
    private float lastSentPitch;
    private boolean sentKnown;

    private boolean closing;
    private Vec3 currentAimVec = null;
    private int patternIndex = 0;
    private int switchTick = 0;
    private boolean hitRegistered = false;

    private boolean deferredAttack;
    private boolean deferredSwap;
    private boolean deferredBlocked;
    private boolean deferredPending;

    private float scanRoll;

    private double humanComboCps;
    private long humanLastClick;

    private int shortStopTicks;
    private int failTicks;
    private float failYaw;
    private float failPitch;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private int hypixel3Asw = 0;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    int blockTick = 0;
    private int lastTickProcessed;
    private long lastRotationUpdateTime = 0;

    public KillAura() {
        super("Silent Aura", false, false, "Kill +999999 Aura");
        this.lastTickProcessed = 0;

        this.cpsMode = new ModeProperty("CPS Mode", 0, new String[]{"Normal", "Record", "Human"});

        this.mode = new ModeProperty("Mode", 1, new String[]{"Single", "Switch"});
        this.sort = new ModeProperty("Sort", 1, new String[]{"Distance", "Health", "HurtTime", "FOV"});
        this.autoBlock = new ModeProperty("auto-block", 3, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE", "Morden", "SameTick",
                "Yuri-Hypixel", "Yuri-Legit", "Yuri-NCP", "Yuri-Vanilla"})

                .hide("VANILLA", "SPOOF", "BLINK", "INTERACT", "SWAP", "FAKE", "Morden")
                .alias("LiquidBounce", "SameTick");
        this.autoBlockCPS = new FloatProperty("AutoBlockCPS", 8.0F, 1.0F, 10.0F,
                () -> this.autoBlock.getValue() != 7 && this.autoBlock.getValue() != 10
                        && this.autoBlock.getValue() < YURI_FIRST);
        this.autoBlockRequirePress = new BooleanProperty("AutoBlockRequirePress", false);
        this.autoBlockRange = new FloatProperty("AutoBlockRange", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("SwingRange", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("AttackRange", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("FOV", 360, 30, 360);
        this.minCPS = new IntProperty("MinCPS", 14, 1, 20);
        this.maxCPS = new IntProperty("MaxCPS", 14, 1, 20);
        this.cps = atlas.property.IntRange.of(this.minCPS, this.maxCPS);
        this.switchDelay = new IntProperty("SwitchDelay", 150, 0, 1000);
        this.rotations = new ModeProperty("Rotations", 2, new String[]{"NONE", "Legit", "Silent", "LockView", "LiquidBounce", "Hypixel", "Advanced"})
                .hide("LockView", "LiquidBounce", "Hypixel");
        this.deadZoneSize = new FloatProperty("DeadZone", 0.5F, 0.0F, 2.0F, () -> rotations.getValue() == 4);
        this.maxTurnSpeed = new FloatProperty("MaxSpeed", 25.0F, 5.0F, 180.0F, () -> rotations.getValue() == 4);
        this.minTurnSpeed = new FloatProperty("MinSpeed", 5.0F, 1.0F, 90.0F, () -> rotations.getValue() == 4);
        this.acceleration = new FloatProperty("Acceleration", 2.5F, 0.1F, 10.0F, () -> rotations.getValue() == 4);
        this.deceleration = new FloatProperty("Deceleration", 1.5F, 0.1F, 10.0F, () -> rotations.getValue() == 4);
        this.useOvershoot = new BooleanProperty("Overshoot", true, () -> rotations.getValue() == 4);
        this.overshootStrength = new FloatProperty("OverStr", 5.0F, 0.0F, 20.0F, () -> rotations.getValue() == 4 && useOvershoot.getValue());
        this.overshootRecovery = new FloatProperty("OverRecov", 0.2F, 0.01F, 1.0F, () -> rotations.getValue() == 4 && useOvershoot.getValue());
        this.noiseStrength = new FloatProperty("Noise", 0.2F, 0.0F, 2.0F, () -> rotations.getValue() == 4);
        this.randomize = new BooleanProperty("Randomize", true, () -> rotations.getValue() == 4);
        this.randomizeRange = new FloatProperty("RandomRange", 0.4F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && randomize.getValue());
        this.yRandomizeStrength = new FloatProperty("YRandomize", 0.3F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && randomize.getValue());
        this.visualizeAim = new BooleanProperty("VisualizeAim", true, () -> rotations.getValue() == 4);
        this.smoothBack = new BooleanProperty("SmoothBack", true,
                () -> rotations.getValue() != 0 && rotations.getValue() != 3);
        this.moveFix = new ModeProperty("MoveFix", 1, new String[]{"NONE", "Silent", "Strict"});
        this.smoothing = new PercentProperty("Smoothing", 0);
        this.ravenSmoothing = new IntProperty("HypixelSmoothing", 0, 0, 10, () -> this.rotations.getValue() == 5);
        this.ravenPredictTicks = new IntProperty("HypixelPredict", 0, 0, 5, () -> this.rotations.getValue() == 5);
        this.ravenYawRandom = new IntProperty("HypixelYawRandom", 0, 0, 5, () -> this.rotations.getValue() == 5);
        this.angleStep = new IntProperty("AngleStep", 90, 30, 180);
        this.aimMode = new ModeProperty("AimMode", 1, new String[]{"LEGACY", "SMOOTHSTEP"});
        this.aimMinSpeed = new IntProperty("MinTurnSpeed", 12, 1, 180, () -> this.aimMode.getValue() == 1);
        this.aimMaxSpeed = new IntProperty("MaxTurnSpeed", 60, 1, 180);
        this.multipoint = new BooleanProperty("Multipoint", true, () -> this.aimMode.getValue() == 1);
        this.turnAccel = new IntProperty("TurnAccel", 30, 1, 180);
        this.throughWalls = new BooleanProperty("ThroughWalls", false);
        this.requirePress = new BooleanProperty("RequirePress", false);
        this.allowMining = new BooleanProperty("AllowMining", true);
        this.weaponsOnly = new BooleanProperty("WeaponsOnly", true);
        this.allowTools = new BooleanProperty("AllowTools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("InventoryCheck", true);
        this.botCheck = new BooleanProperty("BotCheck", true);
        this.players = new BooleanProperty("Players", true);
        this.bosses = new BooleanProperty("Bosses", false);
        this.mobs = new BooleanProperty("Mobs", false);
        this.animals = new BooleanProperty("Animals", false);
        this.golems = new BooleanProperty("Golems", false);
        this.silverfish = new BooleanProperty("Silverfish", false);
        this.teams = new BooleanProperty("Teams", true);
        this.showTarget = new ModeProperty("ShowTarget", 0, new String[]{"NONE", "Default"});
        this.debugLog = new ModeProperty("Debug", 0, new String[]{"NONE", "Health"});

        this.liquidBounceHorizontalSpeed = new FloatProperty("LB-HSpeed", 180.0F, 1.0F, 180.0F, () -> rotations.getValue() == 4);
        this.liquidBounceVerticalSpeed = new FloatProperty("LB-VSpeed", 180.0F, 1.0F, 180.0F, () -> rotations.getValue() == 4);
        this.liquidBounceSmoothFactor = new FloatProperty("LB-Smooth", 0.5F, 0.1F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBouncePredict = new BooleanProperty("LB-Predict", true, () -> rotations.getValue() == 4);
        this.liquidBouncePredictSize = new FloatProperty("LB-PredictSize", 1.0F, 0.0F, 3.0F, () -> rotations.getValue() == 4 && liquidBouncePredict.getValue());
        this.liquidBounceRandomize = new BooleanProperty("LB-Randomize", true, () -> rotations.getValue() == 4);
        this.liquidBounceRandomizeRange = new FloatProperty("LB-RandomRange", 0.5F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && liquidBounceRandomize.getValue());
        this.liquidBounceHorizontalSearch = new FloatProperty("LB-HSearch", 0.5F, 0.0F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBounceBodyPointMin = new FloatProperty("LB-BodyMin", 0.1F, 0.0F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBounceBodyPointMax = new FloatProperty("LB-BodyMax", 0.9F, 0.0F, 1.0F, () -> rotations.getValue() == 4);

        java.util.function.BooleanSupplier plainAim = () -> rotations.getValue() >= 1 && rotations.getValue() <= 3;

        this.aimMode.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 0);
        this.aimMinSpeed.when(plainAim);
        this.multipoint.when(plainAim);
        this.aimDrift.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 1 && multipoint.getValue());
        this.flickOvershoot.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 1);
        java.util.function.BooleanSupplier turnBack = () -> plainAim.getAsBoolean() && aimMode.getValue() == 1
                || rotations.getValue() != 0 && rotations.getValue() != 3 && smoothBack.getValue();
        this.aimMaxSpeed.when(turnBack);
        this.turnAccel.when(turnBack);
        this.smoothing.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 0);
        this.angleStep.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 0);
        this.minCPS.when(() -> cpsMode.getValue() == 0 || cpsMode.getValue() == 2);
        this.maxCPS.when(() -> cpsMode.getValue() == 0 || cpsMode.getValue() == 2);
        this.moveFix.when(() -> rotations.getValue() != 0);
        // block settings only matter with an auto-block mode picked
        this.autoBlockRequirePress.alsoWhen(() -> autoBlock.getValue() != 0);
        this.autoBlockRange.alsoWhen(() -> autoBlock.getValue() != 0);

        for (java.lang.reflect.Field field : KillAura.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(this);
                if (value instanceof atlas.property.Property && value != this.engine) {
                    ((atlas.property.Property<?>) value).alsoWhen(this::atlasEngine);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        this.pauseWhen(() -> !this.atlasEngine());
    }

    public boolean atlasEngine() {
        return this.engine.getValue() == 1;
    }

    private boolean atlasOn() {
        return this.isEnabled() && this.atlasEngine();
    }

    public KillAuraLB liquidBounce() {
        return !this.atlasEngine() && this.lbEngine.child().isEnabled() ? (KillAuraLB) this.lbEngine.child() : null;
    }

    public float attackReach() {
        KillAuraLB lb = liquidBounce();
        return lb != null ? lb.reach() : this.atlasReach();
    }

    private boolean isGenericAim() {
        int mode = this.rotations.getValue();
        return mode >= 1 && mode <= 3;
    }

    private boolean snapHold(UpdateEvent event) {
        if (this.rotationTiming.getValue() != 1 || this.target == null) {
            return false;
        }
        if (this.aimedAt(event.getYaw(), event.getPitch())) {
            return false;
        }
        AxisAlignedBB box = this.target.getBox();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float[] to = RotationUtil.getRotationsTo(
                (box.minX + box.maxX) / 2.0 - eyes.xCoord,
                (box.minY + box.maxY) / 2.0 - eyes.yCoord,
                (box.minZ + box.maxZ) / 2.0 - eyes.zCoord,
                event.getYaw(), event.getPitch());
        float angle = Math.abs(MathHelper.wrapAngleTo180_float(to[0] - event.getYaw()))
                + Math.abs(to[1] - event.getPitch());
        int turnTicks = Math.max(1, (int) Math.ceil(angle / Math.max(1.0F, this.aimMaxSpeed.getValue())));
        int clickIn = this.attackDelayMS <= 0L ? 0 : (int) Math.ceil(this.attackDelayMS / 50.0);
        return clickIn > turnTicks;
    }

    private float[] processAim(UpdateEvent event, float[] rot) {
        float fromYaw = event.getYaw();
        float fromPitch = event.getPitch();
        if (this.lazyRotation.getValue() && this.aimedAt(fromYaw, fromPitch)) {
            return new float[]{fromYaw, fromPitch};
        }
        float yaw = rot[0];
        float pitch = rot[1];
        if (this.failAim.getValue()) {
            if (this.random.nextInt(100) < 3) {
                this.failTicks = 1 + this.random.nextInt(4);
                this.failYaw = (5.0F + this.random.nextFloat() * 5.0F) * (this.random.nextBoolean() ? 1.0F : -1.0F);
                this.failPitch = this.random.nextFloat() * 2.0F * (this.random.nextBoolean() ? 1.0F : -1.0F);
            }
            if (this.failTicks > 0) {
                this.failTicks--;
                yaw += this.failYaw;
                pitch += this.failPitch;
            }
        }
        if (this.shortStop.getValue()) {
            if (this.shortStopTicks <= 0 && this.random.nextInt(100) < 3) {
                this.shortStopTicks = 1 + this.random.nextInt(2);
            }
            if (this.shortStopTicks > 0) {
                this.shortStopTicks--;
                float f = this.random.nextFloat() * 0.1F;
                yaw = fromYaw + MathHelper.wrapAngleTo180_float(yaw - fromYaw) * f;
                pitch = fromPitch + (pitch - fromPitch) * f;
            }
        }
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    private long humanInterval() {
        final double sigma = 0.45;
        long now = System.currentTimeMillis();
        if (this.humanComboCps <= 0.0 || now - this.humanLastClick > 250L) {
            int min = this.minCPS.getValue();
            int max = Math.max(min, this.maxCPS.getValue());
            this.humanComboCps = min + this.random.nextDouble() * (max - min);
        }
        this.humanLastClick = now;
        double mu = Math.log(1000.0 / this.humanComboCps) - sigma * sigma / 2.0;
        long interval = Math.round(Math.exp(mu + sigma * this.random.nextGaussian()));
        return Math.max(10L, Math.min(1000L, interval));
    }

    private long getAttackDelay() {
        int blockMode = this.autoBlock.getValue();
        if (blockMode >= YURI_FIRST && KillAuraYuri.CLICK_MS[blockMode - YURI_FIRST] > 0L) {
            return KillAuraYuri.CLICK_MS[blockMode - YURI_FIRST];
        }
        if (this.isBlocking && blockMode != 7 && blockMode != 10 && blockMode < YURI_FIRST) {
            return (long) (1000.0F / this.autoBlockCPS.getValue());
        } else {
            if (this.cpsMode.getValue() == 2) {
                return this.humanInterval();
            } else if (this.cpsMode.getValue() == 1) {
                long delay = 0L;
                for (int i = 0; i < 4; i++) {
                    delay += clickPattern[(patternIndex + i) % clickPattern.length];
                }
                return delay;
            } else {
                return 1000L / this.cps.random();
            }
        }
    }

    public void afterRotations(UpdateEvent event) {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            lb.afterRotations(event);
            return;
        }
        if (!this.deferredPending) {
            return;
        }
        this.deferredPending = false;
        if (!this.atlasOn() || this.target == null) {
            return;
        }
        if (this.deferredAttack) {
            this.raycastRetarget(event.getNewYaw(), event.getNewPitch());
        }
        boolean attacked = this.deferredAttack && this.performAttack(event.getNewYaw(), event.getNewPitch());
        this.finishAttack(new float[]{event.getNewYaw(), event.getNewPitch()}, attacked, this.deferredSwap,
                this.deferredBlocked);
    }

    private boolean askedThisTick;

    private int lookMove() {
        int mode = this.rotations.getValue();
        if (mode == 3) {
            return RotationHelper.MOVE_LOOK;
        }
        if (this.moveFix.getValue() == 0) {
            return RotationHelper.MOVE_CAMERA;
        }
        return mode == 2 || mode == 4 || mode == 5 || mode == 6 ? RotationHelper.MOVE_FIX : RotationHelper.MOVE_LOOK;
    }

    private float[] ask(float yaw, float pitch, float cap) {
        this.askedThisTick = true;
        float[] look = RotationHelper.request(this, yaw, pitch, 1, this.lookMove(), false, cap);
        return look != null ? look : new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    private void finishAttack(float[] look, boolean attacked, boolean swap, boolean blocked) {
        if (swap) {
            if (attacked) {
                this.interactAttack(look[0], look[1]);
            } else {
                this.sendUseItem();
            }
        }
        if (blocked) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Atlas.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
    }

    private void raycastRetarget(float yaw, float pitch) {
        if (this.raycast.getValue() == 0 || this.target == null) {
            return;
        }
        double range = this.atlasReach();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(pitch, yaw);
        Vec3 end = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        AxisAlignedBB sweep = mc.thePlayer.getEntityBoundingBox()
                .addCoord(look.xCoord * range, look.yCoord * range, look.zCoord * range)
                .expand(1.0, 1.0, 1.0);
        EntityLivingBase first = null;
        double nearest = range + 1.0E-3;
        for (Entity entity : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, sweep)) {
            if (!(entity instanceof EntityLivingBase) || !entity.canBeCollidedWith()) {
                continue;
            }
            double border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            double distance;
            if (box.isVecInside(eyes)) {
                distance = 0.0;
            } else {
                MovingObjectPosition hit = box.calculateIntercept(eyes, end);
                if (hit == null) {
                    continue;
                }
                distance = eyes.distanceTo(hit.hitVec);
            }
            if (distance < nearest) {
                nearest = distance;
                first = (EntityLivingBase) entity;
            }
        }
        if (first != null && first != this.target.getEntity() && this.isValidTarget(first)) {
            this.target = new AttackData(first, this);
        }
    }

    private boolean exitingRange() {
        if (!this.exitClick.getValue() || this.target == null || this.attackDelayMS > 50L) {
            return false;
        }
        EntityLivingBase entity = this.target.getEntity();
        if (entity.hurtTime > 7) {
            return false;
        }
        double range = this.atlasReach();
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        if (distanceTo(box, eyes) > range) {
            return false;
        }
        AxisAlignedBB nextBox = box.offset(entity.posX - entity.lastTickPosX,
                entity.posY - entity.lastTickPosY, entity.posZ - entity.lastTickPosZ);
        Vec3 nextEyes = eyes.addVector(mc.thePlayer.posX - mc.thePlayer.lastTickPosX,
                mc.thePlayer.posY - mc.thePlayer.lastTickPosY, mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ);
        return distanceTo(nextBox, nextEyes) > range;
    }

    private boolean legitBlockThreat() {
        if (this.target == null) {
            return false;
        }
        if (this.legitThreat.getValue() == 0) {
            return this.lookThreat();
        }
        if (mc.thePlayer.hurtTime > 0) {
            return true;
        }
        EntityLivingBase entity = this.target.getEntity();
        if (mc.thePlayer.getDistanceToEntity(entity) > 3.6F || !entity.isSwingInProgress) {
            return false;
        }
        double dx = mc.thePlayer.posX - entity.posX;
        double dz = mc.thePlayer.posZ - entity.posZ;
        float toUs = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(MathHelper.wrapAngleTo180_float(toUs - entity.rotationYawHead)) < 60.0F;
    }

    private boolean lookThreat() {
        double reach = 3.0;
        double tolerance = this.legitTolerance.getValue();
        AxisAlignedBB ours = mc.thePlayer.getEntityBoundingBox().expand(tolerance, tolerance, tolerance);
        for (EntityPlayer enemy : mc.theWorld.playerEntities) {
            if (!this.isThreatCandidate(enemy) || mc.thePlayer.getDistanceSqToEntity(enemy) > 36.0) {
                continue;
            }
            Vec3 eyes = enemy.getPositionEyes(1.0F);
            Vec3 look = enemy.getLook(1.0F);
            Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
            if (ours.isVecInside(eyes)) {
                return true;
            }
            MovingObjectPosition mop = ours.calculateIntercept(eyes, end);
            if (mop == null) {
                continue;
            }
            if (mc.theWorld.rayTraceBlocks(eyes, mop.hitVec, false, true, false) == null) {
                return true;
            }
        }
        return false;
    }

    private boolean isThreatCandidate(EntityPlayer enemy) {
        if (!(enemy instanceof EntityOtherPlayerMP) || enemy.isDead || enemy.deathTime > 0
                || enemy.worldObj != mc.theWorld) {
            return false;
        }
        if (!TargetFilter.accepts(enemy) || TeamUtil.isFriend(enemy)) {
            return false;
        }
        return (!this.teams.getValue() || !TeamUtil.isSameTeam(enemy))
                && (!this.botCheck.getValue() || !TeamUtil.isBot(enemy));
    }

    private static double distanceTo(AxisAlignedBB box, Vec3 point) {
        double x = Math.max(box.minX, Math.min(point.xCoord, box.maxX));
        double y = Math.max(box.minY, Math.min(point.yCoord, box.maxY));
        double z = Math.max(box.minZ, Math.min(point.zCoord, box.maxZ));
        return point.distanceTo(new Vec3(x, y, z));
    }

    private boolean hitSelectHolds() {
        HitSelect hitSelect = (HitSelect) Atlas.moduleManager.modules.get(HitSelect.class);
        return hitSelect != null && this.target != null && hitSelect.dropsAuraHit(this.target.getEntity());
    }

    private void spendHeldClick() {
        if (this.autoBlock.getValue() != 0 && this.canAutoBlock()) {
            this.attackDelayMS += this.getAttackDelay();
        }
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                return this.refuse("still blocking (" + this.autoBlock.getModeString() + ")");
            } else if (this.attackDelayMS > 0L && !this.exitingRange()) {
                return this.refuse("click timer");
            } else if (this.otherActionsThisTick) {
                atlas.management.TickActions.refuse();
                return this.refuse("other action this tick");
            } else {
                if ((this.rotations.getValue() == 4 || this.rotations.getValue() == 6)
                        && !(this.kbDisplacing && this.isBoxInAttackRange(this.target.getBox()))
                        && !this.aimedAt(yaw, pitch)) {
                    return this.refuse("look not on target");
                }
                boolean forced = this.damageHits.due(System.currentTimeMillis());
                if (!forced && (this.noAirSprintHit.getValue() && !mc.thePlayer.onGround && mc.thePlayer.isSprinting()
                        || this.hitSelectHolds())) {
                    this.spendHeldClick();
                    return this.refuse(this.hitSelectHolds() ? "HitSelect holding" : "NoAirSprintHit");
                }
                if (forced) {
                    this.damageHits.spend();
                    HitSelect hitSelect = (HitSelect) Atlas.moduleManager.modules.get(HitSelect.class);
                    if (hitSelect != null) {
                        hitSelect.passForcedHit();
                    }
                }

                this.attackDelayMS += this.getAttackDelay();

                long humanNow = System.currentTimeMillis();
                atlas.humanize.Humanizer.Verdict human = this.atlasHumanizer.click(
                        atlas.humanize.Situations.of(this.target.getEntity()), humanNow);
                if (human != null && human.land && this.atlasHumanizer.dropClick()) {
                    human = new atlas.humanize.Humanizer.Verdict(false, atlas.humanize.Misses.Style.SKIP);
                }
                if (human != null && !human.land) {
                    this.atlasHumanizer.missed(human.miss, humanNow);
                    if (human.miss != atlas.humanize.Misses.Style.SKIP) {
                        mc.thePlayer.swingItem();
                    }
                    return this.refuse("Humanization miss");
                }
                if (this.simulateClicks.getValue()) {
                    if (this.cpsMode.getValue() == 1) {
                        patternIndex = (patternIndex + 4) % clickPattern.length;
                    }
                    MovingObjectPosition camera = mc.objectMouseOver;
                    MovingObjectPosition aimed = this.sentLookHit(yaw, pitch);
                    if (aimed != null) {
                        mc.objectMouseOver = aimed;
                    }
                    int sentBefore = this.attackPackets;
                    this.simulatingClick = true;
                    try {
                        ((atlas.mixin.IAccessorMinecraft) mc).callClickMouse();
                    } finally {
                        this.simulatingClick = false;
                        if (aimed != null && mc.objectMouseOver == aimed) {
                            mc.objectMouseOver = camera;
                        }
                    }
                    this.hitRegistered = this.attackPackets > sentBefore;
                    if (human != null) {
                        if (this.hitRegistered) {
                            this.atlasHumanizer.landed(humanNow);
                        } else {
                            this.atlasHumanizer.unlucky(humanNow);
                        }
                    }
                    return this.hitRegistered || this.refuse("simulate-clicks: crosshair not on target ("
                            + (mc.objectMouseOver == null ? "none" : mc.objectMouseOver.typeOfHit) + ")");
                }
                mc.thePlayer.swingItem();

                if (this.cpsMode.getValue() == 1) {
                    patternIndex = (patternIndex + 4) % clickPattern.length;
                }

                if (this.rotations.getValue() != 4 && this.rotations.getValue() != 6
                        && !this.aimedAt(yaw, pitch)) {
                    return this.refuse("look not on target");
                }
                if (Hitflick.holdsHit(this.target.getEntity())) {
                    if (human != null) {
                        this.atlasHumanizer.landed(humanNow);
                    }
                    this.hitRegistered = true;
                    return true;
                }
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();

                atlas.event.EventManager.call(new atlas.events.AttackEvent(this.target.getEntity()));
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                    PlayerUtil.attackEntity(this.target.getEntity());
                }
                if (human != null) {
                    this.atlasHumanizer.landed(humanNow);
                }
                this.hitRegistered = true;
                return true;
            }
        } else {
            return false;
        }
    }

    private boolean aimedAt(float yaw, float pitch) {
        EntityLivingBase entity = this.target.getEntity();
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB real = entity.getEntityBoundingBox().expand(border, border, border);
        if (real.isVecInside(mc.thePlayer.getPositionEyes(1.0F))) {
            return true;
        }
        return RotationUtil.rayTrace(real, yaw, pitch, this.atlasReach()) != null;
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target == null) {
            return;
        }
        EntityLivingBase entity = this.target.getEntity();
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB real = entity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition mop = RotationUtil.rayTrace(real, yaw, pitch, this.atlasReach());
        if (mop == null) {
            this.sendUseItem();
            return;
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        PacketUtil.sendPacket(new C02PacketUseEntity(entity,
                new Vec3(mop.hitVec.xCoord - entity.posX, mop.hitVec.yCoord - entity.posY, mop.hitVec.zCoord - entity.posZ)));
        PacketUtil.sendPacket(new C02PacketUseEntity(entity, Action.INTERACT));
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
        this.blockingState = true;
    }

    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return this.refuse("inventory open");
        } else if (!(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return this.refuse("mining a block");
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return this.refuse("eating or bow");
            } else {
                AutoHeal autoHeal = (AutoHeal) Atlas.moduleManager.modules.get(AutoHeal.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return this.refuse("AutoHeal switching");
                } else {
                    BedNuker bedNuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return this.refuse("BedNuker");
                    } else if (Atlas.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                        return this.refuse("Scaffold on");
                    } else if (atlas.management.Arbiter.catching()) {
                        atlas.management.Arbiter.yielded("Silent Aura");
                        return this.refuse("Clutch catching");
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking() || this.refuse("RequirePress, not pressed");
                    } else {
                        return !this.allowMining.getValue() || mc.objectMouseOver == null
                                || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking()
                                || this.refuse("AllowMining: pressing on a block");
                    }
                }
            }
        } else {
            return this.refuse("WeaponsOnly, no weapon");
        }
    }

    private MovingObjectPosition sentLookHit(float yaw, float pitch) {
        if (this.target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        net.minecraft.entity.Entity entity = this.target.getEntity();
        float border = entity.getCollisionBorderSize();
        double reach = Math.max(3.0, this.atlasReach());
        MovingObjectPosition onBox = RotationUtil.rayTrace(
                entity.getEntityBoundingBox().expand(border, border, border), yaw, pitch, reach);
        if (onBox == null || onBox.hitVec == null) {
            return null;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double toEntity = eyes.distanceTo(onBox.hitVec);
        if (!this.throughWalls.getValue()) {
            MovingObjectPosition wall = RotationUtil.rayTrace(yaw, pitch, toEntity, 1.0F);
            if (wall != null && wall.typeOfHit == MovingObjectType.BLOCK && wall.hitVec != null
                    && eyes.distanceTo(wall.hitVec) < toEntity) {
                return null;
            }
        }
        return new MovingObjectPosition(entity, onBox.hitVec);
    }

    private boolean refuse(String why) {
        this.refusal = why;
        return false;
    }

    private String refusal;
    private boolean simulatingClick;
    private int attackPackets;
    private int watchTicks;
    private int watchInRange;
    private int watchHits;
    private final java.util.Map<String, Integer> watchWhy = new java.util.TreeMap<String, Integer>();

    private void watchTick() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        String why = null;
        double range = this.atlasReach();
        if (this.target == null) {
            net.minecraft.entity.EntityLivingBase near = null;
            double best = range;
            for (Object o : mc.theWorld.loadedEntityList) {
                if (o instanceof net.minecraft.entity.EntityLivingBase && o != mc.thePlayer
                        && !((net.minecraft.entity.EntityLivingBase) o).isDead) {
                    double d = mc.thePlayer.getDistanceToEntity((net.minecraft.entity.Entity) o);
                    if (d <= best) {
                        best = d;
                        near = (net.minecraft.entity.EntityLivingBase) o;
                    }
                }
            }
            if (near != null) {
                why = "no target: nearest " + near.getClass().getSimpleName() + " (Players "
                        + this.players.getValue() + ", Mobs " + this.mobs.getValue() + ", Animals "
                        + this.animals.getValue() + ")";
            }
        } else if (RotationUtil.distanceToEntity(this.target.getEntity()) <= range) {
            why = this.refusal == null ? "no attack attempted" : this.refusal;
        }
        this.refusal = null;
        if (why != null) {
            this.watchInRange++;
            Integer n = this.watchWhy.get(why);
            this.watchWhy.put(why, n == null ? 1 : n + 1);
        }
        if (++this.watchTicks < 20) {
            return;
        }
        if (this.watchInRange >= 15 && this.watchHits == 0) {
            atlas.util.DiagNotes.note("Silent Aura", "no hit for 1 s with something in range | engine "
                    + this.engine.getModeString() + " | " + this.watchWhy, true);
        }
        this.watchTicks = 0;
        this.watchInRange = 0;
        this.watchHits = 0;
        this.watchWhy.clear();
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (entityLivingBase.isDead || entityLivingBase.worldObj != mc.theWorld) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (!TargetFilter.accepts((EntityPlayer) entityLivingBase)) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase)) && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase)
                || this.isInAttackRange(entityLivingBase) || this.isInScanRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private double scanRange() {
        return this.swingRange.getValue() + this.scanRoll;
    }

    private boolean isInScanRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase)
                <= this.swingRange.getValue() + Math.max(0.0F, this.scanExtra.getValue() + 0.5F);
    }

    private boolean isBoxInScanRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= this.scanRange();
    }

    /** AttackRange, shortened by the drift's current cut (see ReachDrift). */
    private float atlasReach() {
        return this.attackRange.getValue() - this.reachDrift.cut();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.atlasReach();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.atlasReach();
    }

    public double leadTicks() {
        float strength = this.aimLead.getValue();
        if (strength <= 0.0F) {
            return 0.0;
        }
        int ping = atlas.util.Ping.own();
        if (ping <= 0) {
            return 0.0;
        }
        return Math.min(10.0, ping / 50.0) * strength;
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private boolean invulnAllowsAttack() {
        InvulnTiming timing = InvulnTiming.instance();
        return timing == null || this.target == null
                || timing.allowAttack(this.target.getEntity());
    }

    private boolean invulnWantsSwitch() {
        InvulnTiming timing = InvulnTiming.instance();
        return timing != null && timing.isEnabled() && timing.redirect.getValue()
                && this.target != null && !timing.canDamage(this.target.getEntity());
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    public void noteExternalAttack() {
        this.attackDelayMS = Math.max(this.attackDelayMS, 51L);
    }

    public EntityLivingBase getTarget() {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            return lb.getTarget();
        }
        return this.target != null ? this.target.getEntity() : null;
    }

    public boolean blocksByItself() {
        KillAuraLB lb = liquidBounce();
        return lb != null ? lb.autoBlocking.getValue() : this.autoBlock.getValue() != 0;
    }

    public boolean isAttackAllowed() {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            return lb.isAttackAllowed();
        }
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7);
        } else {
            return false;
        }
    }

    public boolean isBlocking() {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            return lb.isBlockVisual();
        }
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    private boolean yuriAutoBlock(boolean eligible) {
        int mode = this.autoBlock.getValue() - YURI_FIRST;
        EntityLivingBase entity = this.target == null ? null : this.target.getEntity();
        double distance = entity == null ? Double.MAX_VALUE : mc.thePlayer.getDistanceToEntity(entity);
        boolean inRange = eligible && entity != null && distance <= this.autoBlockRange.getValue()
                && ItemUtil.isHoldingSword();
        KillAuraYuri.Plan plan = this.yuri.step(mode, inRange, distance, mc.thePlayer.hurtTime,
                this.yuriOnlyWhenHurt.getValue(), this.yuriHurtTicks.getValue(), this.attackDelayMS,
                mc.thePlayer.isUsingItem());
        this.yuriApply(mode, plan);
        if (mode == KillAuraYuri.LEGIT && inRange) {
            while (mc.gameSettings.keyBindAttack.isPressed()) {
            }
        }
        this.isBlocking = inRange && mode != KillAuraYuri.LEGIT;
        this.fakeBlockState = inRange;
        return plan.mayClick;
    }

    private void yuriApply(int mode, KillAuraYuri.Plan plan) {
        if (plan.release) {
            if (mode == KillAuraYuri.HYPIXEL) {
                this.stopBlock();
            } else {
                PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        BlockPos.ORIGIN, EnumFacing.DOWN));
            }
        }
        if (plan.useKey != null) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    mc.gameSettings.keyBindUseItem.getKeyCode(), plan.useKey);
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (plan.block && held != null) {
            if (mode == KillAuraYuri.HYPIXEL) {
                this.startBlock(held);
            } else {
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(held));
            }
        }
        this.yuriBlockAfterMove = plan.blockAfterMove;
    }

    private void yuriAfterMove() {
        int mode = this.autoBlock.getValue() - YURI_FIRST;
        if (mode < 0) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (this.yuriBlockAfterMove) {
            this.yuriBlockAfterMove = false;
            if (held != null && ItemUtil.isHoldingSword()) {
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(held));
                this.yuri.blockedAfterMove();
            }
        }
        if (mode == KillAuraYuri.HYPIXEL && this.yuri.blocking && held != null && !mc.thePlayer.isBlocking()) {
            mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
        }
    }

    private void yuriStop() {
        int mode = this.autoBlock.getValue() - YURI_FIRST;
        if (mode >= 0 && this.yuri.blocking && mc.thePlayer != null) {
            if (mode == KillAuraYuri.LEGIT) {
                net.minecraft.client.settings.KeyBinding.setKeyBindState(
                        mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            } else {
                PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        BlockPos.ORIGIN, EnumFacing.DOWN));
                if (mode == KillAuraYuri.HYPIXEL) {
                    mc.thePlayer.stopUsingItem();
                }
            }
        }
        this.yuri.reset();
        this.yuriBlockAfterMove = false;
    }

    private boolean atlasStoodDown = true;

    private void atlasStandDown() {
        if (this.atlasStoodDown) {
            return;
        }
        this.atlasStoodDown = true;
        this.blinkReset = false;
        Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        if (mc.thePlayer != null) {
            this.yuriStop();
            if (this.blockingState) {
                this.stopBlock();
            }
        }
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.target = null;
        this.wantsToDisable = false;
        this.returning = false;
        this.currentAimVec = null;
    }

    public boolean isAuraBlocking() {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            return lb.isAuraBlocking();
        }
        return this.blockingState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        KillAuraLB lb = liquidBounce();
        if (lb != null) {
            return (mc.thePlayer.isUsingItem() || lb.isAuraBlocking()) && ItemUtil.isHoldingSword();
        }
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    @EventTarget(value = Priority.LOW, whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            this.lastSentYaw = event.getYaw();
            this.lastSentPitch = event.getPitch();
            this.sentKnown = true;
            if (this.atlasOn()) {
                this.yuriAfterMove();
            }
        }
        if (!this.atlasEngine()) {
            this.atlasStandDown();
            return;
        }
        if (this.isEnabled()) {
            this.atlasStoodDown = false;
        }
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Atlas.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() || this.wantsToDisable) {
            if (event.getType() == EventType.PRE) {
                if (this.wantsToDisable) {
                    if (this.smoothBack.getValue() && !event.isRotated() && !RotationHelper.askedByOther(this, 1)
                            && this.stepBack(event)) {
                        return;
                    }
                    this.wantsToDisable = false;
                    this.returning = false;
                    this.closing = true;
                    this.setEnabled(false);
                    this.closing = false;
                    return;
                }
                boolean rotatedBefore = event.isRotated() || RotationHelper.askedByOther(this, 1);
                this.askedThisTick = false;
                this.deferredPending = false;
                if (this.attackDelayMS > 0L) {
                    this.attackDelayMS -= 50L;
                }
                if (this.damageHits.due(System.currentTimeMillis())) {
                    this.attackDelayMS = Math.min(this.attackDelayMS, 0L);
                }

                this.kbDisplacing = false;

                this.otherActionsThisTick = atlas.management.TickActions.any(
                        atlas.management.TickActions.USE | atlas.management.TickActions.DIG
                                | atlas.management.TickActions.INVENTORY);
                this.watchTick();
                boolean attack = this.target != null && this.canAttack()
                        && (this.invulnAllowsAttack() || this.refuse("InvulnTiming waiting"));
                boolean block = attack && this.canAutoBlock();
                if (this.autoBlock.getValue() >= YURI_FIRST && !this.yuriAutoBlock(block)) {
                    attack = this.refuse("auto-block holding the click");
                }
                if (!block) {
                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    this.blockTick = 0;
                    this.hypixel3Asw = 0;
                }
                if (attack) {
                    boolean swap = false;
                    boolean blocked = false;
                    if (block) {
                        switch (this.autoBlock.getValue()) {
                            case 0:
                                if (PlayerUtil.isUsingItem()) {
                                    this.isBlocking = true;
                                    if (!this.isPlayerBlocking() && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        swap = true;
                                    }
                                } else {
                                    this.isBlocking = false;
                                    if (this.isPlayerBlocking() && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        this.stopBlock();
                                    }
                                }
                                Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.fakeBlockState = false;
                                break;
                            case 1:
                                if (this.hasValidTarget()) {
                                    if (!this.isPlayerBlocking() && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        swap = true;
                                    }
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 2:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (Atlas.playerStateManager.digging || Atlas.playerStateManager.placing || mc.thePlayer.inventory.currentItem != item || this.isPlayerBlocking() && this.blockTick != 0 || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                        this.blockTick = 0;
                                    } else {
                                        int slot = this.findEmptySlot(item);
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                        swap = true;
                                        this.blockTick = 1;
                                    }
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 3:
                                if (this.hasValidTarget()) {
                                    if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                blocked = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    if (Atlas.moduleManager.modules.get(NoSlow.class).isEnabled()) {
                                                        int randomSlot = new Random().nextInt(9);
                                                        while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                            randomSlot = new Random().nextInt(9);
                                                        }
                                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                    }
                                                    this.stopBlock();
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 4:
                                if (this.hasValidTarget()) {
                                    if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blinkReset = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 5:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (mc.thePlayer.inventory.currentItem == item && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blinkReset = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    int slot = this.findEmptySlot(item);
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                    ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(slot);
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 6:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (mc.thePlayer.inventory.currentItem == item && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                int slot = this.findSwordSlot(item);
                                                if (slot != -1) {
                                                    if (!this.isPlayerBlocking()) {
                                                        swap = true;
                                                    }
                                                    this.blockTick = 1;
                                                }
                                                break;
                                            case 1:
                                                int swordsSlot = this.findSwordSlot(item);
                                                if (swordsSlot == -1) {
                                                    this.blockTick = 0;
                                                } else if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                } else if (this.attackDelayMS <= 50L) {
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                    ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(swordsSlot);
                                                    this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                    attack = false;
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                        Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                        this.isBlocking = true;
                                        this.fakeBlockState = true;
                                        break;
                                    }
                                }
                                Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                                break;
                            case 7:

                                if (this.hasValidTarget() && this.target != null
                                        && this.isBoxInSwingRange(this.target.getBox())) {
                                    boolean threat = this.legitBlockThreat();
                                    if (this.legitSameTick.getValue()) {
                                        if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                            if (this.isPlayerBlocking()) {
                                                if (!threat) {
                                                    this.stopBlock();
                                                } else if (this.attackDelayMS <= 0L) {
                                                    this.stopBlock();
                                                    swap = true;
                                                }
                                            } else if (threat) {
                                                swap = true;
                                            }
                                        }
                                    } else if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        if (this.isPlayerBlocking()) {
                                            if (!threat || this.attackDelayMS <= 50L) {
                                                this.stopBlock();
                                                if (this.attackDelayMS <= 0L) {
                                                    attack = false;
                                                }
                                            }
                                        } else if (threat && this.attackDelayMS <= 0L) {
                                            swap = true;
                                        }
                                    }
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = threat;
                                    this.fakeBlockState = false;
                                } else {
                                    if (this.isPlayerBlocking() && !Atlas.playerStateManager.digging
                                            && !Atlas.playerStateManager.placing) {
                                        this.stopBlock();
                                    }
                                    this.blockTick = 0;
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 10:

                                if (this.target != null && this.isBoxInSwingRange(this.target.getBox())) {
                                    if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        if (this.attackDelayMS <= 0L) {
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                            }
                                            swap = true;
                                        } else if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                    }
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    if (this.isPlayerBlocking() && !Atlas.playerStateManager.digging
                                            && !Atlas.playerStateManager.placing) {
                                        this.stopBlock();
                                    }
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 9:
                                // Hypixel3 (ported from Cryptix KillAura)
                                if (this.hasValidTarget()) {
                                    Atlas.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                                    if (!Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                        switch (this.hypixel3Asw) {
                                            case 0:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                }
                                                attack = false;
                                                this.hypixel3Asw = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                }
                                                attack = false;
                                                this.hypixel3Asw = 2;
                                                break;
                                            case 2:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                blocked = true;
                                                this.hypixel3Asw = 0;
                                                break;
                                            default:
                                                this.hypixel3Asw = 0;
                                        }
                                    } else {
                                        attack = false;
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                    this.hypixel3Asw = 0;
                                }
                                break;
                            case 8:
                                Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = this.hasValidTarget();
                                if (PlayerUtil.isUsingItem() && !this.isPlayerBlocking() && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing) {
                                    swap = true;
                                }
                        }
                    }
                    boolean attacked = false;

                    boolean deferred = false;
                    boolean inSwing = this.target != null && this.isBoxInSwingRange(this.target.getBox());
                    if (!inSwing) {
                        attack = false;
                    }
                    if ((this.rotations.getValue() == 4 || this.rotations.getValue() == 6) && this.smoothBack.getValue() && (this.target == null || !this.isBoxInScanRange(this.target.getBox()))) {
                        Rotation currentRot = this.serverRotation;
                        Rotation playerRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                        if (Math.abs(MathHelper.wrapAngleTo180_float(currentRot.yaw - playerRot.yaw)) > 1.0F || Math.abs(MathHelper.wrapAngleTo180_float(currentRot.pitch - playerRot.pitch)) > 1.0F) {
                            this.isSmoothBacking = true;
                            Rotation nextRot = getSmoothBackRotation(currentRot, playerRot);

                            float[] fixed = RotationUtil.gcd(new float[]{nextRot.yaw, nextRot.pitch}, new float[]{currentRot.yaw, currentRot.pitch});
                            nextRot = new Rotation(fixed[0], fixed[1]);

                            float[] look = this.ask(nextRot.yaw, nextRot.pitch, RotationHelper.NO_LIMIT);
                            this.serverRotation = new Rotation(look[0], look[1]);
                        } else {
                            this.isSmoothBacking = false;
                            this.serverRotation = playerRot;
                        }
                    }
                    if (this.target != null && this.isBoxInScanRange(this.target.getBox())) {
                        if (this.rotations.getValue() == 4) {
                            Rotation currentRot = this.serverRotation;
                            if (Float.isNaN(currentRot.yaw) || Float.isNaN(currentRot.pitch)) {
                                currentRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                            }

                            Rotation nextRot = updateLiquidBounceRotation(currentRot);

                            float[] fixed = RotationUtil.gcd(new float[]{nextRot.yaw, nextRot.pitch}, new float[]{currentRot.yaw, currentRot.pitch});
                            nextRot = new Rotation(fixed[0], fixed[1]);

                            float[] look = this.ask(nextRot.yaw, nextRot.pitch, RotationHelper.NO_LIMIT);
                            nextRot = new Rotation(look[0], look[1]);
                            this.serverRotation = nextRot;
                            updateRenderAimPosition(nextRot);
                            mc.thePlayer.rotationYawHead = nextRot.yaw;
                            mc.thePlayer.renderYawOffset = nextRot.yaw;
                            if (attack) {
                                attacked = this.performAttack(nextRot.yaw, nextRot.pitch);
                            }
                        } else if (this.rotations.getValue() == 6) {
                            float[] wanted = this.advancedRotation(event.getYaw(), event.getPitch());
                            float[] next = this.ask(wanted[0], wanted[1], RotationHelper.NO_LIMIT);
                            this.serverRotation = new Rotation(next[0], next[1]);
                            mc.thePlayer.rotationYawHead = next[0];
                            mc.thePlayer.renderYawOffset = next[0];
                            if (attack) {
                                attacked = this.advancedAttack(next[0], next[1]);
                            }
                        } else if (this.rotations.getValue() == 5) {
                            Rotation currentRot = this.serverRotation;
                            if (Float.isNaN(currentRot.yaw) || Float.isNaN(currentRot.pitch)) {
                                currentRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                            }
                            float[] raw = this.getRavenRotations(this.target.getEntity());
                            float[] fixedRot = this.ravenFixRotation(raw[0], raw[1], currentRot.yaw, currentRot.pitch);
                            float[] smoothed = this.getRavenRotationsSmoothed(fixedRot, currentRot.yaw, currentRot.pitch);
                            float finalYaw = smoothed[0];
                            float finalPitch = smoothed[1];
                            if (finalPitch > 90) finalPitch = 90;
                            if (finalPitch < -90) finalPitch = -90;
                            float[] look = this.ask(finalYaw, finalPitch, RotationHelper.NO_LIMIT);
                            finalYaw = look[0];
                            finalPitch = look[1];
                            this.serverRotation = new Rotation(finalYaw, finalPitch);
                            mc.thePlayer.rotationYawHead = finalYaw;
                            mc.thePlayer.renderYawOffset = finalYaw;
                            deferred = true;
                        } else if (this.rotations.getValue() >= 1 && this.snapHold(event)) {
                            deferred = true;
                        } else if (this.rotations.getValue() >= 1) {
                            float[] rotations = this.aimMode.getValue() == 1
                                    ? this.stepTowards(event.getYaw(), event.getPitch())
                                    : RotationUtil.getRotationsToBox(
                                            this.target.getBox(),
                                            event.getYaw(),
                                            event.getPitch(),
                                            (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F),
                                            (float) this.smoothing.getValue() / 100.0F
                                    );
                            rotations = this.processAim(event, rotations);

                            float stepYaw = MathHelper.wrapAngleTo180_float(rotations[0] - event.getYaw());
                            float stepPitch = rotations[1] - event.getPitch();
                            float activity = Math.min(1.0F,
                                    (Math.abs(stepYaw) + Math.abs(stepPitch)) / 6.0F);
                            this.aimPhase += 0.35F + RandomUtil.nextFloat(-0.05F, 0.05F);
                            float randomYaw = this.tremor(0.0F, 2.5F, activity);
                            float randomPitch = this.tremor(1.9F, 1.5F, activity);
                            float finalYaw = rotations[0] + randomYaw;
                            float finalPitch = rotations[1] + randomPitch;

                            float[] fixed = RotationUtil.gcd(new float[]{finalYaw, finalPitch}, new float[]{event.getYaw(), event.getPitch()});
                            finalYaw = fixed[0];
                            finalPitch = fixed[1];

                            if (finalPitch > 90) finalPitch = 90;
                            if (finalPitch < -90) finalPitch = -90;
                            float[] look = this.ask(finalYaw, finalPitch, RotationHelper.NO_LIMIT);
                            finalYaw = look[0];
                            finalPitch = look[1];
                            if (this.rotations.getValue() == 3) {
                                Atlas.rotationManager.setRotation(finalYaw, finalPitch, 1, true);
                            } else {
                                mc.thePlayer.rotationYawHead = finalYaw;
                                mc.thePlayer.renderYawOffset = finalYaw;
                            }
                            deferred = true;
                        } else {
                            deferred = true;
                        }
                    }
                    if (this.rotations.getValue() == 6
                            && (this.target == null || !this.isBoxInScanRange(this.target.getBox()))) {
                        this.advancedAim.lostTarget();
                    }
                    if (deferred) {
                        this.deferredAttack = attack;
                        this.deferredSwap = swap;
                        this.deferredBlocked = blocked;
                        this.deferredPending = true;
                    } else {
                        this.finishAttack(RotationHelper.packetLook(event), attacked, swap, blocked);
                    }
                }
                if (!rotatedBefore && this.askedThisTick) {
                    this.returning = this.rotations.getValue() != 3;
                } else if (this.returning) {
                    if (rotatedBefore || !this.smoothBack.getValue() || !this.stepBack(event)) {
                        this.returning = false;
                    }
                }
            }
        }
    }

    private float[] getRavenRotations(EntityLivingBase entity) {
        double posX = entity.posX;
        double posZ = entity.posZ;
        int ticks = this.ravenPredictTicks.getValue();
        if (ticks > 0) {
            double dX = entity.posX - entity.lastTickPosX;
            double dZ = entity.posZ - entity.lastTickPosZ;
            for (int i = 0; i < ticks; i++) {
                posX += dX;
                posZ += dZ;
            }
        }
        double deltaX = posX - mc.thePlayer.posX;
        double deltaZ = posZ - mc.thePlayer.posZ;
        double deltaY = entity.posY + entity.getEyeHeight() * 0.9 - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        float yaw = mc.thePlayer.rotationYaw + MathHelper.wrapAngleTo180_float(
                (float) (Math.atan2(deltaZ, deltaX) * 57.295780181884766) - 90.0f - mc.thePlayer.rotationYaw);
        float pitch = MathHelper.clamp_float(mc.thePlayer.rotationPitch + MathHelper.wrapAngleTo180_float(
                (float) (-(Math.atan2(deltaY, MathHelper.sqrt_double(deltaX * deltaX + deltaZ * deltaZ)) * 57.295780181884766)) - mc.thePlayer.rotationPitch) + 3.0f, -90.0f, 90.0f);
        return new float[]{yaw, pitch};
    }

    private int ravenRandInt(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + this.random.nextInt(max - min + 1);
    }

    private float[] ravenFixRotation(float targetYaw, float targetPitch, float yaw, float pitch) {
        float n5 = targetYaw - yaw;
        float abs = Math.abs(n5);
        float n7 = targetPitch - pitch;
        float n8 = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        double n9 = n8 * n8 * n8 * 1.2;
        float n10 = (float) (Math.round((double) n5 / n9) * n9);
        float n11 = (float) (Math.round((double) n7 / n9) * n9);
        targetYaw = yaw + n10;
        targetPitch = pitch + n11;
        if (abs >= 1.0f) {
            int factor = this.ravenYawRandom.getValue();
            if (factor != 0) {
                int n13 = factor * 100 + ravenRandInt(-30, 30);
                targetYaw += ravenRandInt(-n13, n13) / 100.0f;
            }
        } else if (abs <= 0.04f) {
            targetYaw += (abs > 0.0f) ? 0.01f : -0.01f;
        }
        return new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -90.0f, 90.0f)};
    }

    private float[] getRavenRotationsSmoothed(float[] rotations, float serverYaw, float serverPitch) {
        float unwrappedYaw = serverYaw + ((((rotations[0] - serverYaw + 180f) % 360f) + 360f) % 360f - 180f);
        float deltaYaw = unwrappedYaw - serverYaw;
        float deltaPitch = rotations[1] - serverPitch;

        float yawSmoothing = (float) this.ravenSmoothing.getValue();
        float pitchSmoothing = yawSmoothing;

        float strafe = mc.thePlayer.moveStrafing;
        if (strafe < 0 && deltaYaw < 0 || strafe > 0 && deltaYaw > 0) {
            yawSmoothing = Math.max(1f, yawSmoothing / 2f);
        }

        float motionY = (float) mc.thePlayer.motionY;
        if (motionY > 0 && deltaPitch > 0 || motionY < 0 && deltaPitch < 0) {
            pitchSmoothing = Math.max(1f, pitchSmoothing / 2f);
        }

        serverYaw += deltaYaw / Math.max(1f, yawSmoothing);
        serverPitch += deltaPitch / Math.max(1f, pitchSmoothing);

        return new float[]{serverYaw, serverPitch};
    }

    private Rotation updateLiquidBounceRotation(Rotation current) {
        if (this.target == null || System.currentTimeMillis() - this.lastRotationUpdateTime < ROTATION_UPDATE_INTERVAL_MS) {
            return current;
        }

        this.lastRotationUpdateTime = System.currentTimeMillis();
        EntityLivingBase targetEntity = this.target.getEntity();

        AxisAlignedBB bb = targetEntity.getEntityBoundingBox().expand(targetEntity.getCollisionBorderSize(), targetEntity.getCollisionBorderSize(), targetEntity.getCollisionBorderSize());
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);

        Vec3 targetPoint = searchCenterPoint(bb, eyes);

        if (targetPoint == null) {
            targetPoint = new Vec3(
                    (bb.minX + bb.maxX) / 2.0,
                    (bb.minY + bb.maxY) / 2.0,
                    (bb.minZ + bb.maxZ) / 2.0
            );
        }

        if (this.liquidBouncePredict.getValue()) {
            targetPoint = applyPrediction(targetPoint, targetEntity);
        }

        double diffX = targetPoint.xCoord - eyes.xCoord;
        double diffY = targetPoint.yCoord - eyes.yCoord;
        double diffZ = targetPoint.zCoord - eyes.zCoord;
        double dist = MathHelper.sqrt_double(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(diffY, dist) * 180.0D / Math.PI));

        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
        targetPitch = MathHelper.wrapAngleTo180_float(targetPitch);

        return limitAngleChange(current, new Rotation(targetYaw, targetPitch));
    }

    private Vec3 searchCenterPoint(AxisAlignedBB bb, Vec3 eyes) {
        double scanRange = Math.max(this.atlasReach(), this.swingRange.getValue());
        double attackRange = this.atlasReach();
        double throughWallsRange = this.throughWalls.getValue() ? attackRange : 0;

        double minBody = this.liquidBounceBodyPointMin.getValue();
        double maxBody = this.liquidBounceBodyPointMax.getValue();
        double hMin = 0.0;
        double hMax = this.liquidBounceHorizontalSearch.getValue();

        Vec3 bestPoint = null;
        double bestScore = Double.MAX_VALUE;

        for (double x = hMin; x <= hMax; x += 0.25) {
            for (double y = minBody; y <= maxBody; y += 0.25) {
                for (double z = hMin; z <= hMax; z += 0.25) {
                    Vec3 point = new Vec3(
                            bb.minX + (bb.maxX - bb.minX) * x,
                            bb.minY + (bb.maxY - bb.minY) * y,
                            bb.minZ + (bb.maxZ - bb.minZ) * z
                    );

                    double distance = eyes.distanceTo(point);

                    if (distance > scanRange) {
                        continue;
                    }

                    boolean visible = isVisible(eyes, point);
                    if (!visible && distance > throughWallsRange) {
                        continue;
                    }

                    double score = distance;
                    if (distance > attackRange) {
                        score += 10.0;
                    }
                    if (!visible) {
                        score += 5.0;
                    }

                    if (this.liquidBounceRandomize.getValue()) {
                        score += random.nextDouble() * this.liquidBounceRandomizeRange.getValue() * 5.0;
                    }

                    if (score < bestScore) {
                        bestScore = score;
                        bestPoint = point;
                    }
                }
            }
        }

        return bestPoint;
    }

    private boolean isVisible(Vec3 from, Vec3 to) {
        if (this.throughWalls.getValue()) {
            return true;
        }
        return mc.theWorld.rayTraceBlocks(from, to, false, true, false) == null;
    }

    private Vec3 applyPrediction(Vec3 point, EntityLivingBase entity) {
        double predictX = entity.posX + entity.motionX * this.liquidBouncePredictSize.getValue();
        double predictY = entity.posY + entity.motionY * this.liquidBouncePredictSize.getValue() * 0.5;
        double predictZ = entity.posZ + entity.motionZ * this.liquidBouncePredictSize.getValue();

        double offsetX = point.xCoord - entity.posX;
        double offsetY = point.yCoord - entity.posY;
        double offsetZ = point.zCoord - entity.posZ;

        return new Vec3(predictX + offsetX, predictY + offsetY, predictZ + offsetZ);
    }

    private Rotation limitAngleChange(Rotation current, Rotation target) {
        float maxHorizontalChange = this.liquidBounceHorizontalSpeed.getValue();
        float maxVerticalChange = this.liquidBounceVerticalSpeed.getValue();
        float smoothFactor = this.liquidBounceSmoothFactor.getValue();

        float yawDiff = MathHelper.wrapAngleTo180_float(target.yaw - current.yaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(target.pitch - current.pitch);

        yawDiff = MathHelper.clamp_float(yawDiff, -maxHorizontalChange, maxHorizontalChange);
        pitchDiff = MathHelper.clamp_float(pitchDiff, -maxVerticalChange, maxVerticalChange);

        yawDiff *= smoothFactor;
        pitchDiff *= smoothFactor;

        return new Rotation(
                current.yaw + yawDiff,
                MathHelper.clamp_float(current.pitch + pitchDiff, -90.0f, 90.0f)
        );
    }

    private void updateRenderAimPosition(Rotation rotation) {
        if (rotation == null || mc.thePlayer == null) {
            this.currentAimVec = null;
            return;
        }
        float yawRad = rotation.yaw * (float) Math.PI / 180.0F;
        float pitchRad = rotation.pitch * (float) Math.PI / 180.0F;
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);
        double renderDistance = Math.max(0.1, mc.thePlayer.getDistanceToEntity(this.target.getEntity()) * 0.5);
        renderDistance = Math.min(renderDistance, 4.0);
        this.currentAimVec = new Vec3(
                eyePos.xCoord + lookX * renderDistance,
                eyePos.yCoord + lookY * renderDistance,
                eyePos.zCoord + lookZ * renderDistance
        );
    }

    private boolean stepBack(UpdateEvent event) {
        float camYaw = mc.thePlayer.rotationYaw;
        float camPitch = mc.thePlayer.rotationPitch;
        float fromYaw = event.getYaw();
        float fromPitch = event.getPitch();
        if (Math.abs(MathHelper.wrapAngleTo180_float(camYaw - fromYaw)) <= 1.0F
                && Math.abs(camPitch - fromPitch) <= 1.0F) {
            return false;
        }

        float[] next = this.ask(camYaw, camPitch, Math.max(20.0F, this.aimMaxSpeed.getValue()));
        this.serverRotation = new Rotation(next[0], next[1]);
        mc.thePlayer.rotationYawHead = next[0];
        mc.thePlayer.renderYawOffset = next[0];
        return true;
    }

    private Rotation getSmoothBackRotation(Rotation current, Rotation target) {
        float yawDiff = MathHelper.wrapAngleTo180_float(target.yaw - current.yaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(target.pitch - current.pitch);
        float speed = Math.max(5.0f, Math.abs(yawDiff) * 0.3f);
        float yawStep = MathHelper.clamp_float(yawDiff, -speed, speed);
        float pitchStep = MathHelper.clamp_float(pitchDiff, -speed, speed);
        return new Rotation(current.yaw + yawStep, current.pitch + pitchStep);
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (this.atlasOn() || this.wantsToDisable && this.atlasEngine()) {
            switch (event.getType()) {
                case PRE:
                    this.reachDrift.update(this.attackRange.getValue(), this.attackRangeDrift.getValue(),
                            this.target == null ? null : this.target.getEntity(),
                            this.target == null ? 0.0 : RotationUtil.distanceToBox(this.target.getBox()));
                    if (this.target == null && !this.isSmoothBacking && !this.wantsToDisable) {
                        this.serverRotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                        this.currentAimVec = null;
                    }
                    ArrayList<EntityLivingBase> validTargets = new ArrayList<>();
                    for (Entity entity : mc.theWorld.loadedEntityList) {
                        if (entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInRange((EntityLivingBase) entity)) {
                            validTargets.add((EntityLivingBase) entity);
                        }
                    }

                    boolean switchDue = this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue());
                    boolean needsNewTarget = false;
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInScanRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            && validTargets.stream().anyMatch(this::isInSwingRange)
                            || !this.isBoxInAttackRange(this.target.getBox())
                            && validTargets.stream().anyMatch(this::isInAttackRange)
                            || switchDue
                            || this.invulnWantsSwitch()) {
                        needsNewTarget = true;
                        this.timer.reset();
                    }
                    if (validTargets.isEmpty()) {
                        this.target = null;
                        this.currentAimVec = null;
                    } else {
                        if (validTargets.stream().anyMatch(this::isInSwingRange)) {
                            validTargets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                        }
                        if (validTargets.stream().anyMatch(this::isInAttackRange)) {
                            validTargets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                        }
                        if (validTargets.stream().anyMatch(this::isPlayerTarget)) {
                            validTargets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                        }
                        validTargets.sort(
                                (entityLivingBase1, entityLivingBase2) -> {
                                    InvulnTiming timing = InvulnTiming.instance();
                                    if (timing != null && timing.isEnabled() && timing.redirect.getValue()) {
                                        int free1 = timing.canDamage(entityLivingBase1) ? 0 : 1;
                                        int free2 = timing.canDamage(entityLivingBase2) ? 0 : 1;
                                        if (free1 != free2) {
                                            return Integer.compare(free1, free2);
                                        }
                                    }
                                    int sortBase = 0;
                                    switch (this.sort.getValue()) {
                                        case 1:
                                            sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                            break;
                                        case 2:
                                            sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                            break;
                                        case 3:
                                            sortBase = Float.compare(
                                                    RotationUtil.angleToEntity(entityLivingBase1),
                                                    RotationUtil.angleToEntity(entityLivingBase2)
                                            );
                                    }
                                    return sortBase != 0
                                            ? sortBase
                                            : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                }
                        );
                        if (needsNewTarget) {
                            if (this.mode.getValue() == 1 && this.hitRegistered && switchDue) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= validTargets.size()) {
                                this.switchTick = 0;
                            }
                            EntityLivingBase newTarget = validTargets.get(this.switchTick);
                            boolean targetChanged = this.target == null || this.target.getEntity() != newTarget;
                            if (targetChanged) {
                                InvulnTiming timing = InvulnTiming.instance();

                                if (timing != null && timing.isEnabled() && this.target != null
                                        && !timing.canDamage(this.target.getEntity())
                                        && timing.canDamage(newTarget)) {
                                    timing.noteRedirect();
                                }
                                this.target = new AttackData(newTarget, this);
                                float extra = this.scanExtra.getValue();
                                this.scanRoll = extra <= 0.0F ? 0.0F
                                        : Math.max(0.0F, extra + RandomUtil.nextFloat(-0.5F, 0.5F));
                            }
                        }
                    }
                    if (this.target != null) {
                        this.target.update();
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onKnockback(PacketEvent event) {
        if (this.isEnabled()) {
            this.damageHits.onPacket(event, this.attackOnDamage.getValue());
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
                this.watchHits++;
                this.attackPackets++;
            }
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Atlas.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();

                    if (mc.thePlayer != null && packet.func_149376_c() != null
                            && packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Atlas.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onMove(MoveInputEvent event) {
        if (this.atlasOn() || this.wantsToDisable && this.atlasEngine()) {
            boolean isSilent = this.rotations.getValue() == 2;
            boolean isLiquidBounce = this.rotations.getValue() == 4;
            boolean isRavenBS = this.rotations.getValue() == 5;
            boolean isAdvanced = this.rotations.getValue() == 6;

            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onRender3D(Render3DEvent event) {
        try {
            if (mc.getRenderManager() == null || mc.getRenderViewEntity() == null) return;
            IAccessorRenderManager renderManagerAccessor = (IAccessorRenderManager) mc.getRenderManager();
            double viewerX = renderManagerAccessor.getRenderPosX();
            double viewerY = renderManagerAccessor.getRenderPosY();
            double viewerZ = renderManagerAccessor.getRenderPosZ();
            if (this.atlasOn() && target != null) {
                if (this.showTarget.getValue() != 0 && !TargetOrbit.active()
                        && TeamUtil.isEntityLoaded(this.target.getEntity())
                        && this.isAttackAllowed()) {
                    Color color = new Color(-1);
                    if (this.showTarget.getValue() == 1) {
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                    }
                    ThemeStyle themed = ThemeStyle.active(TargetColors.class);
                    if (themed != null) {
                        color = themed.color(color, this.target.getEntity(), -1);
                    }
                    RenderUtil.enableRenderState();
                    RenderUtil.drawEntityBox(this.target.getEntity(), color.getRed(), color.getGreen(), color.getBlue());
                    RenderUtil.disableRenderState();
                }
            }
            if ((this.atlasOn() || this.wantsToDisable && this.atlasEngine()) && this.visualizeAim.getValue() && this.currentAimVec != null) {
                double x = this.currentAimVec.xCoord;
                double y = this.currentAimVec.yCoord;
                double z = this.currentAimVec.zCoord;
                x -= viewerX;
                y -= viewerY;
                z -= viewerZ;
                GlStateManager.pushMatrix();
                GlStateManager.enableBlend();
                GlStateManager.disableDepth();
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GlStateManager.depthMask(false);
                Color inner = new Color(0, 255, 255, 204);
                Color outer = new Color(0, 255, 0, 128);
                float aimWidth = 2.0F;
                ThemeStyle themed = ThemeStyle.active(TargetColors.class);
                if (themed != null) {
                    inner = themed.line(inner, null, 0);
                    outer = themed.line(outer, null, 3);
                    aimWidth = themed.width();
                }
                GlStateManager.color(inner.getRed() / 255.0F, inner.getGreen() / 255.0F, inner.getBlue() / 255.0F,
                        inner.getAlpha() / 255.0F);
                double size = 0.08;
                AxisAlignedBB bb = new AxisAlignedBB(x - size, y - size, z - size, x + size, y + size, z + size);
                RenderGlobal.drawSelectionBoundingBox(bb);
                GlStateManager.color(outer.getRed() / 255.0F, outer.getGreen() / 255.0F, outer.getBlue() / 255.0F,
                        outer.getAlpha() / 255.0F);
                GL11.glLineWidth(aimWidth);
                RenderGlobal.drawOutlinedBoundingBox(bb, outer.getRed(), outer.getGreen(), outer.getBlue(), outer.getAlpha());
                GlStateManager.depthMask(true);
                GlStateManager.enableTexture2D();
                GlStateManager.enableDepth();
                GlStateManager.disableBlend();
                GlStateManager.popMatrix();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @EventTarget(whenDisabled = true)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.simulatingClick) {
            return;
        }
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.atlasOn() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.atlasOn() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.atlasOn() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        if (!this.wantsToDisable) {
            this.target = null;
            this.switchTick = 0;
            this.hitRegistered = false;
            this.attackDelayMS = 0L;
            this.damageHits.clear();
            this.blockTick = 0;

            if (mc.thePlayer != null) {
                this.serverRotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            }
            this.currentAimVec = null;
            this.isSmoothBacking = false;
            this.lastRotationUpdateTime = 0;
            this.patternIndex = 0;
            this.advancedAim.reset();
            this.returning = false;
            this.backEngine.reset();
        }
    }

    private boolean isAdvanced() {
        return this.rotations != null && this.rotations.getValue() == 6;
    }

    private atlas.util.AdvancedAim.Settings advancedSettings() {
        atlas.util.AdvancedAim.Settings s = this.advancedSettings;
        s.gravity = this.advGravity.getValue();
        s.wind = this.advWind.getValue();
        s.dampedDistance = this.advDampedDistance.getValue();
        s.maxStep = this.advMaxStep.getValue();
        s.overshootChance = this.advOvershootChance.getValue();
        s.overshootScale = this.advOvershootScale.getValue();
        s.overshootMax = this.advOvershootMax.getValue();
        s.gaussian = this.advGaussian.getValue();
        s.accuracy = this.advAccuracy.getValue();
        s.minStep = this.advMinStep.getValue();
        s.prediction = this.advPrediction.getValue();
        s.deadzone = this.advDeadzone.getValue();
        s.anchor = this.advAnchor.getValue();
        s.holdTicks = this.advHoldTicks.getValue();
        s.cruiseFloor = this.advCruiseFloor.getValue();
        s.paceJitter = this.advPaceJitter.getValue();
        s.burstChance = this.advBurstChance.getValue();
        s.burstStrength = this.advBurstStrength.getValue();
        s.flickGuard = this.advFlickGuard.getValue();
        s.flickMax = this.advFlickMax.getValue();
        s.aimReaction = this.advAimReaction.getValue();
        s.aimReactionJitter = this.advAimReactionJitter.getValue();
        s.triggerReaction = this.advTriggerReaction.getValue();
        s.triggerReactionJitter = this.advTriggerReactionJitter.getValue();
        return s;
    }

    private float[] advancedRotation(float yaw, float pitch) {
        atlas.util.AdvancedAim.Settings s = this.advancedSettings();
        EntityLivingBase entity = this.target.getEntity();
        AxisAlignedBB box = this.target.getBox();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        this.advancedAim.observeMotion(
                new Vec3(entity.posX - entity.lastTickPosX, entity.posY - entity.lastTickPosY, entity.posZ - entity.lastTickPosZ),
                new Vec3(mc.thePlayer.posX - mc.thePlayer.lastTickPosX, mc.thePlayer.posY - mc.thePlayer.lastTickPosY,
                        mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ));
        Vec3 wanted = this.advancedAim.predictedPoint(entity.posX, entity.posY, entity.posZ, entity.height,
                entity.getEntityBoundingBox(), eyes, s);
        Vec3 aim = this.advancedAim.aimPoint(wanted, entity.getEntityBoundingBox(),
                mc.thePlayer.getDistanceToEntity(entity), System.currentTimeMillis(), s);
        Vec3 point = this.advancedPick(box, aim, eyes, yaw);
        float[] want = RotationEngine.rotationsTo(eyes, point, yaw);
        this.kbDisplacing = false;
        if (this.kbDisplace.getValue() != 0 && this.attackDelayMS <= 50L
                && atlas.util.KnockbackPlanner.hasKnockbackSource(mc.thePlayer,
                        net.minecraft.enchantment.EnchantmentHelper.getKnockbackModifier(mc.thePlayer))
                && !atlas.util.KnockbackPlanner.fallingCrit(mc.thePlayer)) {
            atlas.util.KnockbackPlanner.Plan plan = atlas.util.KnockbackPlanner.plan(mc.theWorld, entity);
            if (plan != null) {
                float planYaw = yaw + MathHelper.wrapAngleTo180_float(plan.yaw - yaw);
                if (this.kbDisplace.getValue() == 2) {
                    want[0] = planYaw;
                    this.kbDisplacing = true;
                } else {
                    float safe = this.hittableYawToward(box, eyes, yaw, want[1], planYaw);
                    if (!Float.isNaN(safe)) {
                        want[0] = safe;
                    }
                }
            }
        }
        boolean onTarget = RotationUtil.rayTrace(box, yaw, pitch, this.atlasReach() + 0.15) != null;
        float[] step = this.advancedAim.step(yaw, pitch, want[0], want[1], entity.getEntityId(), onTarget, s);
        return RotationUtil.gcd(step, new float[]{yaw, pitch});
    }

    private Vec3 advancedPick(AxisAlignedBB box, Vec3 aim, Vec3 eyes, float nearYaw) {
        double range = this.atlasReach();
        Vec3 nearest = atlas.util.AdvancedAim.nearestInside(box, eyes);
        float[] toNearest = RotationEngine.rotationsTo(eyes, nearest, nearYaw);
        if (RotationUtil.rayTrace(box, toNearest[0], toNearest[1], range) != null) {
            return nearest;
        }
        float[] toAim = RotationEngine.rotationsTo(eyes, aim, nearYaw);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (Vec3 candidate : atlas.util.AdvancedAim.candidates(box, aim, eyes)) {
            float[] look = RotationEngine.rotationsTo(eyes, candidate, nearYaw);
            if (RotationUtil.rayTrace(box, look[0], look[1], range) == null) {
                continue;
            }
            double score = candidate.squareDistanceTo(eyes)
                    + (Math.abs(MathHelper.wrapAngleTo180_float(look[0] - toAim[0])) + Math.abs(look[1] - toAim[1])) * 1.0E-5;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? aim : best;
    }

    private float hittableYawToward(AxisAlignedBB box, Vec3 eyes, float nearYaw, float pitch, float planYaw) {
        float centre = RotationEngine.rotationsTo(eyes,
                new Vec3((box.minX + box.maxX) / 2.0, eyes.yCoord, (box.minZ + box.maxZ) / 2.0), nearYaw)[0];
        float low = 0.0F;
        float high = 0.0F;
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};
        for (double x : xs) {
            for (double z : zs) {
                float corner = MathHelper.wrapAngleTo180_float(
                        RotationEngine.rotationsTo(eyes, new Vec3(x, eyes.yCoord, z), nearYaw)[0] - centre);
                low = Math.min(low, corner);
                high = Math.max(high, corner);
            }
        }
        float offset = MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(planYaw - centre), low * 0.85F, high * 0.85F);
        float yaw = centre + offset;
        return RotationUtil.rayTrace(box, yaw, pitch, this.atlasReach()) != null ? yaw : Float.NaN;
    }

    private boolean advancedAttack(float yaw, float pitch) {
        atlas.util.AdvancedAim.Settings s = this.advancedSettings();
        boolean onTarget = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.atlasReach()) != null
                || this.kbDisplacing && this.isBoxInAttackRange(this.target.getBox());
        if (this.advancedAim.triggerReady(this.target.getEntity().getEntityId(), onTarget,
                System.currentTimeMillis(), s)) {
            return this.performAttack(yaw, pitch);
        }
        if (!onTarget && this.advSwing.getValue() && this.attackDelayMS <= 0L
                && !Atlas.playerStateManager.digging && !Atlas.playerStateManager.placing
                && !this.isPlayerBlocking()) {
            mc.thePlayer.swingItem();
            this.attackDelayMS += this.getAttackDelay();
        }
        return false;
    }

    @Override
    public void onDisabled() {
        this.blinkReset = false;
        Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.yuriStop();
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        if (!this.closing && this.atlasEngine() && this.rotations.getValue() != 0 && this.rotations.getValue() != 3 && this.smoothBack.getValue()
                && mc.thePlayer != null && this.sentKnown
                && (Math.abs(MathHelper.wrapAngleTo180_float(this.lastSentYaw - mc.thePlayer.rotationYaw)) > 1.0F
                || Math.abs(this.lastSentPitch - mc.thePlayer.rotationPitch) > 1.0F)) {
            this.wantsToDisable = true;
            this.setEnabled(true);
            return;
        }
        this.returning = false;
        this.wantsToDisable = false;
        this.currentAimVec = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    @Getter
    public static class AttackData {
        private final EntityLivingBase entity;
        private final KillAura killAura;
        private AxisAlignedBB box;
        private double x;
        private double y;
        private double z;

        private double velX;
        private double velY;
        private double velZ;
        private boolean sampled;

        public AttackData(EntityLivingBase entityLivingBase, KillAura killAura) {
            this.entity = entityLivingBase;
            this.killAura = killAura;
            update();
        }

        public void update() {
            if (this.sampled) {
                double dx = entity.posX - this.x;
                double dy = entity.posY - this.y;
                double dz = entity.posZ - this.z;

                if (Math.abs(dx) < 2.0 && Math.abs(dy) < 2.0 && Math.abs(dz) < 2.0) {
                    this.velX += (dx - this.velX) * 0.35;
                    this.velY += (dy - this.velY) * 0.35;
                    this.velZ += (dz - this.velZ) * 0.35;
                }
            }
            this.sampled = true;

            double collisionBorderSize = entity.getCollisionBorderSize();
            this.box = entity.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);

            double lead = this.killAura == null ? 0.0 : this.killAura.leadTicks();
            if (lead > 0.0) {
                double cap = this.killAura.aimLeadCap.getValue();
                double offsetX = clampLead(this.velX * lead, cap);
                double offsetY = clampLead(this.velY * lead, cap);
                double offsetZ = clampLead(this.velZ * lead, cap);
                this.box = this.box.offset(offsetX, offsetY, offsetZ);
            }

            this.x = entity.posX;
            this.y = entity.posY;
            this.z = entity.posZ;
        }

        private static double clampLead(double value, double cap) {
            return Math.max(-cap, Math.min(cap, value));
        }
    }

    private final RotationEngine aimEngine = new RotationEngine();

    private EntityLivingBase humanAimFor;
    private float driftX, driftY, driftZ;
    private float driftGoalX, driftGoalY, driftGoalZ;
    private int driftTicks;
    private float flickSpeed = 1.0F;
    private float speedWobble;
    private float lastAimDistance;
    private float overYaw, overPitch;
    private boolean overArmed;

    private float[] stepTowards(float currentYaw, float currentPitch) {
        if (this.humanAimFor != this.target.entity) {
            this.humanAimFor = this.target.entity;
            this.driftTicks = 0;
            this.overYaw = 0.0F;
            this.overPitch = 0.0F;
            this.overArmed = false;
            this.lastAimDistance = 0.0F;
        }
        float[] full;
        if (this.multipoint.getValue()) {
            AxisAlignedBB aim = RotationEngine.aimBox(this.target.getBox(), 0.15, 0.05, 0.75);
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            Vec3 point = RotationEngine.nearestOnBox(aim, eyes, currentYaw, currentPitch);
            float weight = this.aimDrift.getValue();
            if (weight > 0.0F) {
                if (--this.driftTicks <= 0) {
                    this.driftTicks = 6 + this.random.nextInt(9);
                    this.driftGoalX = this.random.nextFloat() * 2.0F - 1.0F;
                    this.driftGoalY = this.random.nextFloat() * 2.0F - 1.0F;
                    this.driftGoalZ = this.random.nextFloat() * 2.0F - 1.0F;
                }
                this.driftX += (this.driftGoalX - this.driftX) * 0.2F;
                this.driftY += (this.driftGoalY - this.driftY) * 0.2F;
                this.driftZ += (this.driftGoalZ - this.driftZ) * 0.2F;
                double cx = (aim.minX + aim.maxX) / 2.0 + this.driftX * (aim.maxX - aim.minX) / 2.0;
                double cy = (aim.minY + aim.maxY) / 2.0 + this.driftY * (aim.maxY - aim.minY) / 2.0;
                double cz = (aim.minZ + aim.maxZ) / 2.0 + this.driftZ * (aim.maxZ - aim.minZ) / 2.0;
                point = new Vec3(point.xCoord + (cx - point.xCoord) * weight,
                        point.yCoord + (cy - point.yCoord) * weight,
                        point.zCoord + (cz - point.zCoord) * weight);
            }
            full = RotationEngine.rotationsTo(eyes, point, currentYaw);
        } else {
            full = RotationUtil.getRotationsToBox(
                    this.target.getBox(), currentYaw, currentPitch, 180.0F, 0.0F);
        }

        float rawYaw = MathHelper.wrapAngleTo180_float(full[0] - currentYaw);
        float rawPitch = full[1] - currentPitch;
        float rawDistance = (float) Math.sqrt(rawYaw * rawYaw + rawPitch * rawPitch);
        if (rawDistance > 12.0F && this.lastAimDistance <= 12.0F) {
            this.flickSpeed = 0.85F + this.random.nextFloat() * 0.3F;
        }
        if (this.flickOvershoot.getValue() && rawDistance > 20.0F && this.lastAimDistance <= 20.0F
                && !this.overArmed && this.random.nextInt(100) < 60) {
            float amount = Math.min(4.0F, rawDistance * (0.04F + this.random.nextFloat() * 0.08F));
            this.overYaw = rawYaw / rawDistance * amount;
            this.overPitch = rawPitch / rawDistance * amount * 0.5F;
            this.overArmed = true;
        }
        this.lastAimDistance = rawDistance;
        if (this.overArmed) {
            float leftYaw = rawYaw + this.overYaw;
            float leftPitch = rawPitch + this.overPitch;
            if (leftYaw * leftYaw + leftPitch * leftPitch < 2.25F) {
                this.overArmed = false;
            }
        } else {
            this.overYaw *= 0.45F;
            this.overPitch *= 0.45F;
            if (Math.abs(this.overYaw) + Math.abs(this.overPitch) < 0.1F) {
                this.overYaw = 0.0F;
                this.overPitch = 0.0F;
            }
        }
        full = new float[]{full[0] + this.overYaw,
                MathHelper.clamp_float(full[1] + this.overPitch, -90.0F, 90.0F)};

        float deltaYaw = MathHelper.wrapAngleTo180_float(full[0] - currentYaw);
        float deltaPitch = full[1] - currentPitch;
        float distance = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance < 0.05F) {
            return new float[]{full[0], full[1]};
        }

        float min = Math.min(this.aimMinSpeed.getValue(), this.aimMaxSpeed.getValue());
        float max = Math.max(this.aimMinSpeed.getValue(), this.aimMaxSpeed.getValue());

        float t = Math.min(1.0F, distance / 60.0F);
        float shaped = t * t * (3.0F - 2.0F * t);
        float step = min + (max - min) * shaped;

        this.speedWobble = this.speedWobble * 0.8F + (float) this.random.nextGaussian() * 0.03F;
        step *= this.flickSpeed * (1.0F + Math.max(-0.12F, Math.min(0.12F, this.speedWobble)));

        return this.aimEngine.step(currentYaw, currentPitch, full[0], full[1], step,
                this.turnAccel.getValue(), 0, false);
    }

    private float aimPhase = 0.0F;

    private float tremor(float phaseOffset, float amplitude, float activity) {
        double p = this.aimPhase + phaseOffset;
        double wave = Math.sin(p) + Math.sin(p * 2.7 + 1.3) * 0.3;
        return (float) (wave * amplitude * activity);
    }

    public final atlas.module.ModuleBundle lbEngine = atlas.module.ModuleBundle.forMode(this, new KillAuraLB(),
                    "lb", "LiquidBounce", () -> !this.atlasEngine())
            .deselectWith(() -> this.engine.setValue(1))
            .selectWith(() -> this.engine.setValue(0))
            .seedWith(this::seedLiquidBounce);

    private void seedLiquidBounce(com.google.gson.JsonObject old) {
        atlas.module.ModuleBundle b = this.lbEngine;
        for (String key : new String[]{"MinCPS", "MaxCPS", "Players", "Mobs", "Animals", "Golems", "Silverfish",
                "Bosses", "Teams", "BotCheck", "RotationTiming", "LazyRotation", "ShortStop", "Raycast"}) {
            b.readInto(key, old.get(key));
        }

        com.google.gson.JsonElement walls = old.get("ThroughWalls");
        boolean throughWalls = walls != null && walls.isJsonPrimitive() && walls.getAsBoolean();
        b.readInto("ThroughWalls", new com.google.gson.JsonPrimitive(throughWalls));
        com.google.gson.JsonElement range = old.get("AttackRange");
        if (range != null && range.isJsonPrimitive()) {
            float increase = Math.max(0.0F, Math.min(5.0F, range.getAsFloat() - atlas.lb.combat.AuraRules.VANILLA_REACH));
            b.readInto("RangeIncrease", new com.google.gson.JsonPrimitive(increase));
            b.readInto("ThroughWallsRange", new com.google.gson.JsonPrimitive(throughWalls ? range.getAsFloat() : 0.0F));
        }
        com.google.gson.JsonElement extra = old.get("ScanExtra");
        if (extra != null && extra.isJsonPrimitive()) {
            b.readInto("ScanRangeIncrease-min", extra);
            b.readInto("ScanRangeIncrease-max", extra);
        }
        com.google.gson.JsonElement fov = old.get("FOV");
        if (fov != null && fov.isJsonPrimitive()) {
            b.readInto("TargetFOV", new com.google.gson.JsonPrimitive(Math.min(180.0F, fov.getAsFloat())));
        }
        com.google.gson.JsonElement moveFix = old.get("MoveFix");
        com.google.gson.JsonElement rotations = old.get("Rotations");
        if (rotations != null && rotations.isJsonPrimitive() && "LockView".equalsIgnoreCase(rotations.getAsString())) {
            b.readInto("MovementCorrection", new com.google.gson.JsonPrimitive("ChangeLook"));
        } else if (moveFix != null && moveFix.isJsonPrimitive()) {
            String fix = moveFix.getAsString();
            b.readInto("MovementCorrection", new com.google.gson.JsonPrimitive(
                    "NONE".equalsIgnoreCase(fix) ? "Off" : fix));
        }
        com.google.gson.JsonElement block = old.get("auto-block");
        if (block != null && block.isJsonPrimitive()) {
            String mode = block.getAsString();
            boolean on = !"NONE".equalsIgnoreCase(mode);
            b.readInto("AutoBlocking", new com.google.gson.JsonPrimitive(on));
            if ("FAKE".equalsIgnoreCase(mode)) {
                b.readInto("BlockMode", new com.google.gson.JsonPrimitive("Fake"));
            }
        }
    }

    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.humanAimFor = null;
    }
}
