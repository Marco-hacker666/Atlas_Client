package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.MoveInputEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.lb.aiming.AngleSmooth;
import atlas.lb.aiming.AuraRotations;
import atlas.lb.aiming.PointTracker;
import atlas.lb.aiming.Rotation;
import atlas.lb.clicking.Clicker;
import atlas.lb.combat.AuraRules;
import atlas.lb.combat.TargetTracker;
import atlas.mixin.IAccessorEntity;
import atlas.mixin.IAccessorMinecraft;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import atlas.property.FloatRange;
import atlas.property.IntRange;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.TextProperty;
import atlas.util.ItemUtil;
import atlas.util.MoveUtil;
import atlas.util.PacketUtil;
import atlas.util.PlayerUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.enchantment.EnchantmentHelper;
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
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * LiquidBounce NextGen's KillAura, ported to 1.8.9 (2026-10-25, owner: "port
 * LiquidBounce's KillAura with all its features and replace Silent Aura";
 * GPL-3.0, net.ccbluex.liquidbounce.features.module.modules.combat.killaura).
 *
 * Stage L2 of four: the core -- click scheduler (Clicker), ranges, target
 * tracking, requirements, raycast, criticals, KeepSprint, inventory handling,
 * the rotation engine (angle smoothers, ShortStop, Fail, movement
 * correction, reset), rotation timing Normal / Snap / OnTick, lazy rotation,
 * aiming through walls and the aim point (exempts, Gaussian, Lazy, Delay).
 * AutoBlock, FailSwing, FightBot, NotifyWhenFail and the indicators come in
 * L3; L4 makes this Silent Aura. Not registered until then.
 *
 * 1.8 differences, on purpose: no attack cooldown (so no item-cooldown gate
 * and no exiting-range override of it), crits do not need sprint off (so the
 * aura never stops sprinting for one), vanilla reach 3.0, other players'
 * positions extrapolated linearly (no movement simulator), no shields, maces
 * or elytra. "Targets" uses Atlas's own entity filters (teams, friends, bots).
 */
public class KillAuraLB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long CLICK_MEMORY_MS = 250L;

    // ---- clicker
    public final ModeProperty technique = new ModeProperty("Technique", 0, new String[]{"Human", "Constant"});
    public final IntProperty minCps = new IntProperty("MinCPS", 11, 1, 20);
    public final IntProperty maxCps = new IntProperty("MaxCPS", 14, 1, 20);
    public final IntRange cps = IntRange.of(this.minCps, this.maxCps);
    public final IntProperty maxPerTick = new IntProperty("MaxPerTick", 2, 1, 5);
    public final BooleanProperty missCooldown = new BooleanProperty("MissCooldown", true);
    // ---- range
    /* Defaults 0 here, not LiquidBounce's 1.0 / 3.0 (4.0 reach, hits through walls): those flag at once on a 1.8
       server. A Silent Aura profile from before the port brings its own reach and walls rule (seedLiquidBounce). */
    public final FloatProperty rangeIncrease = new FloatProperty("RangeIncrease", 0.0F, 0.0F, 5.0F);
    public final FloatProperty throughWallsRange = new FloatProperty("ThroughWallsRange", 0.0F, 0.0F, 8.0F);
    public final FloatRange scanRangeIncrease = new FloatRange("ScanRangeIncrease", 2.0F, 3.0F, 0.0F, 7.0F);
    // ---- target
    public final FloatProperty targetFov = new FloatProperty("TargetFOV", 180.0F, 0.0F, 180.0F);
    public final IntProperty hurtTime = new IntProperty("HurtTime", 10, 0, 10);
    public final ModeProperty priority = new ModeProperty("Priority", 0,
            new String[]{"Health", "Distance", "Direction", "HurtTime", "Age"});
    public final BooleanProperty players = new BooleanProperty("Players", true);
    public final BooleanProperty mobs = new BooleanProperty("Mobs", true);
    public final BooleanProperty animals = new BooleanProperty("Animals", false);
    public final BooleanProperty golems = new BooleanProperty("Golems", false);
    public final BooleanProperty silverfish = new BooleanProperty("Silverfish", false);
    public final BooleanProperty bosses = new BooleanProperty("Bosses", false);
    public final BooleanProperty teams = new BooleanProperty("Teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("BotCheck", true);
    // ---- rotations
    public final ModeProperty angleSmooth = new ModeProperty("AngleSmooth", 0,
            new String[]{"Linear", "Sigmoid", "Interpolation", "Acceleration", "AimAssist", "Tracking"});
    /* AngleSmooth AimAssist (2026-10-25): Atlas's AimAssist "Smooth" turn and aim point, read every tick from
       the AimAssist module's own settings (horizontal-speed, vertical-speed, smoothing, lead, multipoint,
       strafe-increase), so it is tuned in one place; AimAssist itself may stay off. */
    private static final int SMOOTH_AIMASSIST = 4;
    /* AngleSmooth Tracking (2026-10-26): Atlas's PI turn with the target's speed fed forward (AngleSmooth.Tracking). */
    private static final int SMOOTH_TRACKING = 5;
    public final FloatProperty trackingYawP = new FloatProperty("Tracking-YawP", 0.35F, 0.05F, 1.0F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatProperty trackingPitchP = new FloatProperty("Tracking-PitchP", 0.25F, 0.05F, 1.0F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatProperty trackingIntegral = new FloatProperty("Tracking-Integral", 0.04F, 0.0F, 0.3F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatProperty trackingLeak = new FloatProperty("Tracking-IntegralLeak", 0.8F, 0.0F, 0.98F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatProperty trackingFeedForward = new FloatProperty("Tracking-FeedForward", 0.9F, 0.0F, 1.5F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final PercentProperty trackingGainVariance = new PercentProperty("Tracking-GainVariance", 20, 0, 50,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatProperty trackingOnTarget = new FloatProperty("Tracking-OnTargetZone", 1.5F, 0.0F, 10.0F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatRange trackingMaxYaw = new FloatRange("Tracking-MaxYawTurn", 30.0F, 45.0F, 1.0F, 180.0F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatRange trackingMaxPitch = new FloatRange("Tracking-MaxPitchTurn", 15.0F, 25.0F, 1.0F, 90.0F,
            () -> this.angleSmooth.getValue() == SMOOTH_TRACKING);
    public final FloatRange linearHorizontal = new FloatRange("HorizontalTurnSpeed", 180.0F, 180.0F, 0.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 0);
    public final FloatRange linearVertical = new FloatRange("VerticalTurnSpeed", 180.0F, 180.0F, 0.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 0);
    public final FloatRange sigmoidHorizontal = new FloatRange("Sigmoid-HorizontalTurnSpeed", 180.0F, 180.0F, 0.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 1);
    public final FloatRange sigmoidVertical = new FloatRange("Sigmoid-VerticalTurnSpeed", 180.0F, 180.0F, 0.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 1);
    public final FloatProperty sigmoidSteepness = new FloatProperty("Sigmoid-Steepness", 10.0F, 0.0F, 20.0F,
            () -> this.angleSmooth.getValue() == 1);
    public final FloatProperty sigmoidMidpoint = new FloatProperty("Sigmoid-Midpoint", 0.3F, 0.0F, 1.0F,
            () -> this.angleSmooth.getValue() == 1);
    public final IntRange interpHorizontal = new IntRange("HorizontalSpeed", 80, 85, 1, 100,
            () -> this.angleSmooth.getValue() == 2);
    public final IntRange interpVertical = new IntRange("VerticalSpeed", 20, 25, 1, 100,
            () -> this.angleSmooth.getValue() == 2);
    public final IntRange interpDirectionChange = new IntRange("DirectionChangeFactor", 95, 100, 0, 100,
            () -> this.angleSmooth.getValue() == 2);
    public final FloatProperty interpMidpoint = new FloatProperty("Interpolation-Midpoint", 0.35F, 0.0F, 1.0F,
            () -> this.angleSmooth.getValue() == 2);
    public final FloatRange yawAcceleration = new FloatRange("YawAcceleration", 20.0F, 25.0F, 1.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 3);
    public final FloatRange pitchAcceleration = new FloatRange("PitchAcceleration", 20.0F, 25.0F, 1.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 3);
    public final BooleanProperty dynamicAccel = new BooleanProperty("DynamicAccel", false,
            () -> this.angleSmooth.getValue() == 3);
    public final FloatProperty coefDistance = new FloatProperty("CoefDistance", -1.393F, -2.0F, 2.0F,
            () -> this.angleSmooth.getValue() == 3 && this.dynamicAccel.getValue());
    public final FloatRange yawCrosshairAccel = new FloatRange("YawCrosshairAccel", 17.0F, 20.0F, 1.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 3 && this.dynamicAccel.getValue());
    public final FloatRange pitchCrosshairAccel = new FloatRange("PitchCrosshairAccel", 17.0F, 20.0F, 1.0F, 180.0F,
            () -> this.angleSmooth.getValue() == 3 && this.dynamicAccel.getValue());
    public final BooleanProperty accelerationError = new BooleanProperty("AccelerationError", true,
            () -> this.angleSmooth.getValue() == 3);
    public final FloatProperty yawAccelError = new FloatProperty("YawAccelError", 0.1F, 0.01F, 1.0F,
            () -> this.angleSmooth.getValue() == 3 && this.accelerationError.getValue());
    public final FloatProperty pitchAccelError = new FloatProperty("PitchAccelError", 0.1F, 0.01F, 1.0F,
            () -> this.angleSmooth.getValue() == 3 && this.accelerationError.getValue());
    public final BooleanProperty constantError = new BooleanProperty("ConstantError", true,
            () -> this.angleSmooth.getValue() == 3);
    public final FloatProperty yawConstantError = new FloatProperty("YawConstantError", 0.1F, 0.01F, 1.0F,
            () -> this.angleSmooth.getValue() == 3 && this.constantError.getValue());
    public final FloatProperty pitchConstantError = new FloatProperty("PitchConstantError", 0.1F, 0.01F, 1.0F,
            () -> this.angleSmooth.getValue() == 3 && this.constantError.getValue());
    public final BooleanProperty sigmoidDeceleration = new BooleanProperty("SigmoidDeceleration", false,
            () -> this.angleSmooth.getValue() == 3);
    public final FloatProperty decelSteepness = new FloatProperty("Deceleration-Steepness", 10.0F, 0.0F, 20.0F,
            () -> this.angleSmooth.getValue() == 3 && this.sigmoidDeceleration.getValue());
    public final FloatProperty decelMidpoint = new FloatProperty("Deceleration-Midpoint", 0.3F, 0.0F, 1.0F,
            () -> this.angleSmooth.getValue() == 3 && this.sigmoidDeceleration.getValue());
    public final BooleanProperty shortStop = new BooleanProperty("ShortStop", false);
    public final PercentProperty shortStopRate = new PercentProperty("ShortStop-Rate", 3, 1, 25, this.shortStop::getValue);
    public final IntRange shortStopDuration = new IntRange("ShortStop-Duration", 1, 2, 1, 5, this.shortStop::getValue);
    public final BooleanProperty fail = new BooleanProperty("Fail", false);
    public final IntProperty failRate = new IntProperty("Fail-Rate", 3, 1, 100, this.fail::getValue);
    public final FloatProperty failFactor = new FloatProperty("Fail-Factor", 0.04F, 0.01F, 0.99F, this.fail::getValue);
    public final FloatRange failHorizontal = new FloatRange("Fail-StrengthHorizontal", 5.0F, 10.0F, 1.0F, 90.0F, this.fail::getValue);
    public final FloatRange failVertical = new FloatRange("Fail-StrengthVertical", 0.0F, 2.0F, 0.0F, 90.0F, this.fail::getValue);
    public final IntRange failTransition = new IntRange("Fail-TransitionInDuration", 1, 4, 0, 20, this.fail::getValue);
    public final ModeProperty movementCorrection = new ModeProperty("MovementCorrection", 2,
            new String[]{"Off", "Strict", "Silent", "ChangeLook"});
    public final FloatProperty resetThreshold = new FloatProperty("ResetThreshold", 2.0F, 1.0F, 180.0F);
    public final IntProperty ticksUntilReset = new IntProperty("TicksUntilReset", 5, 1, 30);
    public final ModeProperty rotationTiming = new ModeProperty("RotationTiming", 0, new String[]{"Normal", "Snap", "OnTick"});
    public final BooleanProperty aimThroughWalls = new BooleanProperty("ThroughWalls", false);
    public final BooleanProperty lazyRotation = new BooleanProperty("LazyRotation", false);
    // ---- aim point
    public final BooleanProperty exemptHead = new BooleanProperty("ExemptHead", false);
    public final BooleanProperty exemptBody = new BooleanProperty("ExemptBody", false);
    public final BooleanProperty exemptFeet = new BooleanProperty("ExemptFeet", false);
    public final BooleanProperty exemptBest = new BooleanProperty("ExemptBestHitVector", false);
    public final FloatProperty exemptBestVertical = new FloatProperty("ExemptBest-Vertical", 0.2F, 0.0F, 1.0F, this.exemptBest::getValue);
    public final FloatProperty exemptBestHorizontal = new FloatProperty("ExemptBest-Horizontal", 0.1F, 0.0F, 1.0F, this.exemptBest::getValue);
    public final BooleanProperty pointGaussian = new BooleanProperty("Gaussian", false);
    public final FloatRange gaussYaw = new FloatRange("Gaussian-YawOffset", 0.0F, 0.0F, 0.0F, 1.0F, this.pointGaussian::getValue);
    public final FloatRange gaussPitch = new FloatRange("Gaussian-PitchOffset", 0.0F, 0.0F, 0.0F, 1.0F, this.pointGaussian::getValue);
    public final PercentProperty gaussChance = new PercentProperty("Gaussian-Chance", 100, this.pointGaussian::getValue);
    public final FloatRange gaussSpeed = new FloatRange("Gaussian-Speed", 0.1F, 0.2F, 0.01F, 1.0F, this.pointGaussian::getValue);
    public final FloatProperty gaussTolerance = new FloatProperty("Gaussian-Tolerance", 0.05F, 0.01F, 0.1F, this.pointGaussian::getValue);
    public final BooleanProperty pointLazy = new BooleanProperty("Lazy", false);
    public final FloatRange lazyThreshold = new FloatRange("Lazy-Threshold", 0.1F, 0.2F, 0.01F, 0.4F, this.pointLazy::getValue);
    public final BooleanProperty pointDelay = new BooleanProperty("Delay", false);
    public final IntRange delayTicks = new IntRange("Delay-Ticks", 2, 4, 0, 5, this.pointDelay::getValue);
    // ---- requires
    public final BooleanProperty requireClick = new BooleanProperty("Require-Click", false);
    public final BooleanProperty requireWeapon = new BooleanProperty("Require-Weapon", false);
    public final BooleanProperty requireEmptyHand = new BooleanProperty("Require-EmptyHand", false);
    public final BooleanProperty requireVanillaName = new BooleanProperty("Require-VanillaName", false);
    public final BooleanProperty requireNotBreaking = new BooleanProperty("Require-NotBreaking", false);
    // ---- bypass techniques and inventory
    public final ModeProperty raycast = new ModeProperty("Raycast", 2, new String[]{"None", "Enemy", "All"});
    /* AttackPath (2026-10-26): Packet = the swing and the attack packet sent here (LiquidBounce); Vanilla = the
       crosshair rebuilt along the rotation the server gets this tick and vanilla's own left click, so what is
       hit, the miss delay, the sprint reset and the packets are all vanilla's (vanillaClick). */
    public final ModeProperty attackPath = new ModeProperty("AttackPath", 0, new String[]{"Packet", "Vanilla"});
    public final ModeProperty criticals = new ModeProperty("Criticals", 0, new String[]{"Smart", "Ignore", "Always"});
    public final BooleanProperty keepSprint = new BooleanProperty("KeepSprint", true);
    public final BooleanProperty ignoreOpenInventory = new BooleanProperty("IgnoreOpenInventory", true);
    public final BooleanProperty simulateInventoryClosing = new BooleanProperty("SimulateInventoryClosing", true);
    // ---- L3: AutoBlocking (KillAuraAutoBlock)
    public final BooleanProperty autoBlocking = new BooleanProperty("AutoBlocking", false);
    public final ModeProperty blockMode = new ModeProperty("BlockMode", 1, new String[]{"Basic", "Interact", "Fake"},
            this.autoBlocking::getValue);
    public final BooleanProperty simulateVanillaUse = new BooleanProperty("SimulateVanillaUse", true, this.autoBlocking::getValue);
    /* SwapHand is LiquidBounce's offhand unblock; 1.8 has no offhand. */
    public final ModeProperty unblockMode = new ModeProperty("UnblockMode", 0, new String[]{"StopUsingItem", "ChangeSlot", "None"},
            this.autoBlocking::getValue);
    public final IntRange reblock = new IntRange("Reblock", 0, 0, 0, 3, this.autoBlocking::getValue);
    public final IntRange pauseOnUnblock = new IntRange("PauseOnUnblock", 0, 0, 0, 3, this.autoBlocking::getValue);
    public final PercentProperty blockChance = new PercentProperty("AutoBlock-Chance", 100, this.autoBlocking::getValue);
    public final IntProperty blockBlink = new IntProperty("Blink", 0, 0, 10, this.autoBlocking::getValue);
    public final BooleanProperty prioritizeBlocking = new BooleanProperty("PrioritizeBlocking", true, this.autoBlocking::getValue);
    public final BooleanProperty onScanRange = new BooleanProperty("OnScanRange", true, this.autoBlocking::getValue);
    public final BooleanProperty onlyWhenInDanger = new BooleanProperty("OnlyWhenInDanger", false, this.autoBlocking::getValue);
    public final FloatProperty dangerTolerance = new FloatProperty("Danger-Tolerance", 0.3F, 0.0F, 1.0F,
            () -> this.autoBlocking.getValue() && this.onlyWhenInDanger.getValue());
    public final FloatRange dangerForceActive = new FloatRange("Danger-ForceActiveRange", 0.0F, 1.0F, 0.0F, 6.0F,
            () -> this.autoBlocking.getValue() && this.onlyWhenInDanger.getValue());
    // ---- L3: FailSwing (KillAuraFailSwing) and NotifyWhenFail
    public final BooleanProperty failSwing = new BooleanProperty("FailSwing", false);
    public final FloatRange failSwingRange = new FloatRange("FailSwing-AdditionalRange", 2.5F, 3.0F, 0.0F, 10.0F, this.failSwing::getValue);
    public final ModeProperty notifyWhenFail = new ModeProperty("NotifyWhenFail", 1, new String[]{"None", "Box", "Sound"},
            this.failSwing::getValue);
    public final IntProperty notifyFade = new IntProperty("Notify-Fade", 4, 1, 10,
            () -> this.failSwing.getValue() && this.notifyWhenFail.getValue() == 1);
    public final ColorProperty notifyColor = new ColorProperty("Notify-Color", 0xFFB348,
            () -> this.failSwing.getValue() && this.notifyWhenFail.getValue() == 1);
    public final BooleanProperty notifyRainbow = new BooleanProperty("Notify-Rainbow", false,
            () -> this.failSwing.getValue() && this.notifyWhenFail.getValue() == 1);
    public final FloatProperty notifyVolume = new FloatProperty("Notify-Volume", 50.0F, 0.0F, 100.0F,
            () -> this.failSwing.getValue() && this.notifyWhenFail.getValue() == 2);
    public final FloatProperty notifyPitch = new FloatProperty("Notify-Pitch", 0.8F, 0.0F, 2.0F,
            () -> this.failSwing.getValue() && this.notifyWhenFail.getValue() == 2);
    // ---- L3: FightBot (KillAuraFightBot)
    public final BooleanProperty fightBot = new BooleanProperty("FightBot", false);
    public final FloatProperty opponentRange = new FloatProperty("OpponentRange", 3.0F, 0.1F, 10.0F, this.fightBot::getValue);
    public final FloatProperty dangerousYaw = new FloatProperty("DangerousYaw", 55.0F, 0.0F, 90.0F, this.fightBot::getValue);
    public final BooleanProperty runawayOnCooldown = new BooleanProperty("RunawayOnCooldown", true, this.fightBot::getValue);
    public final FloatProperty fightBotRange = new FloatProperty("FightBot-Range", 50.0F, 10.0F, 100.0F, this.fightBot::getValue);
    public final BooleanProperty visibleOnly = new BooleanProperty("VisibleOnly", true, this.fightBot::getValue);
    public final BooleanProperty notWhenVoid = new BooleanProperty("NotWhenVoid", true, this.fightBot::getValue);
    public final BooleanProperty leader = new BooleanProperty("Leader", false, this.fightBot::getValue);
    public final TextProperty leaderName = new TextProperty("Leader-Username", "",
            () -> this.fightBot.getValue() && this.leader.getValue());
    public final FloatProperty leaderRadius = new FloatProperty("Leader-Radius", 5.0F, 2.0F, 10.0F,
            () -> this.fightBot.getValue() && this.leader.getValue());
    public final BooleanProperty autoJump = new BooleanProperty("Auto-Jump", true, this.fightBot::getValue);
    public final BooleanProperty autoSwim = new BooleanProperty("Auto-Swim", true, this.fightBot::getValue);
    public final BooleanProperty autoSprint = new BooleanProperty("Auto-Sprint", true, this.fightBot::getValue);
    // ---- L3: RangeIndicator (KillAuraRangeIndicator)
    public final BooleanProperty rangeIndicator = new BooleanProperty("RangeIndicator", false);
    public final ModeProperty indicatorColorMode = new ModeProperty("Indicator-ColorMode", 0,
            new String[]{"Static", "Rainbow", "Distance"}, this.rangeIndicator::getValue);
    public final ColorProperty idleColor = new ColorProperty("Indicator-IdleColor", 0xFF3232, this.rangeIndicator::getValue);
    public final IntProperty idleAlpha = new IntProperty("Indicator-IdleAlpha", 80, 0, 255, this.rangeIndicator::getValue);
    public final ColorProperty activeColor = new ColorProperty("Indicator-ActiveColor", 0x32FF32, this.rangeIndicator::getValue);
    public final IntProperty activeAlpha = new IntProperty("Indicator-ActiveAlpha", 80, 0, 255, this.rangeIndicator::getValue);
    public final BooleanProperty indicatorOutline = new BooleanProperty("Indicator-Outline", true, this.rangeIndicator::getValue);
    public final IntProperty outlineAlpha = new IntProperty("Indicator-OutlineAlpha", 120, 0, 255,
            () -> this.rangeIndicator.getValue() && this.indicatorOutline.getValue());
    public final BooleanProperty pulseAnimation = new BooleanProperty("Indicator-Pulse", false, this.rangeIndicator::getValue);
    public final FloatProperty pulseSpeed = new FloatProperty("Indicator-PulseSpeed", 2.0F, 0.5F, 5.0F,
            () -> this.rangeIndicator.getValue() && this.pulseAnimation.getValue());
    public final FloatProperty pulseIntensity = new FloatProperty("Indicator-PulseIntensity", 0.15F, 0.05F, 0.5F,
            () -> this.rangeIndicator.getValue() && this.pulseAnimation.getValue());
    public final BooleanProperty fadeAnimation = new BooleanProperty("Indicator-Fade", true, this.rangeIndicator::getValue);
    public final FloatProperty fadeSpeed = new FloatProperty("Indicator-FadeSpeed", 0.1F, 0.01F, 0.5F,
            () -> this.rangeIndicator.getValue() && this.fadeAnimation.getValue());
    public final IntProperty wallRangeAlpha = new IntProperty("Indicator-WallRangeAlpha", 0, 0, 255, this.rangeIndicator::getValue);
    public final IntProperty scanRangeAlpha = new IntProperty("Indicator-ScanRangeAlpha", 0, 0, 255, this.rangeIndicator::getValue);
    public final IntProperty opponentRangeAlpha = new IntProperty("Indicator-OpponentRangeAlpha", 0, 0, 255, this.rangeIndicator::getValue);
    public final BooleanProperty hideWhenDead = new BooleanProperty("Indicator-HideWhenDead", true, this.rangeIndicator::getValue);
    public final BooleanProperty hideWhenSpectator = new BooleanProperty("Indicator-HideWhenSpectator", true, this.rangeIndicator::getValue);
    public final BooleanProperty hideInVehicle = new BooleanProperty("Indicator-HideInVehicle", false, this.rangeIndicator::getValue);
    public final BooleanProperty respectInventory = new BooleanProperty("Indicator-RespectInventorySetting", true, this.rangeIndicator::getValue);
    public final BooleanProperty indicatorCovered = new BooleanProperty("Indicator-CanBeCovered", false, this.rangeIndicator::getValue);
    // ---- L3: TargetRenderer (Legacy, Circle, GlowingCircle, Ghost)
    public final BooleanProperty targetRender = new BooleanProperty("TargetRender", true);
    public final ModeProperty targetRenderMode = new ModeProperty("TargetRender-Mode", 0,
            new String[]{"Legacy", "Circle", "GlowingCircle", "Ghost"}, this.targetRender::getValue);
    public final ColorProperty targetColor = new ColorProperty("TargetRender-Color", 0x007CFF, this.targetRender::getValue);
    public final IntProperty targetAlpha = new IntProperty("TargetRender-Alpha", 100, 0, 255, this.targetRender::getValue);
    public final FloatProperty targetSize = new FloatProperty("TargetRender-Size", 0.5F, 0.1F, 2.0F,
            () -> this.targetRender.getValue() && (this.targetRenderMode.getValue() == 0 || this.targetRenderMode.getValue() == 3));
    public final FloatProperty targetHeight = new FloatProperty("TargetRender-Height", 0.1F, 0.02F, 2.0F,
            () -> this.targetRender.getValue() && this.targetRenderMode.getValue() == 0);
    public final FloatProperty targetYOffset = new FloatProperty("TargetRender-ExtraYOffset", 0.1F, 0.0F, 1.0F,
            () -> this.targetRender.getValue() && this.targetRenderMode.getValue() == 0);
    public final FloatProperty targetRadius = new FloatProperty("TargetRender-Radius", 0.85F, 0.1F, 2.0F,
            () -> this.targetRender.getValue() && (this.targetRenderMode.getValue() == 1 || this.targetRenderMode.getValue() == 2));
    public final FloatProperty targetInnerRadius = new FloatProperty("TargetRender-InnerRadius", 0.0F, 0.0F, 2.0F,
            () -> this.targetRender.getValue() && this.targetRenderMode.getValue() == 1);
    public final FloatProperty glowHeight = new FloatProperty("TargetRender-GlowHeight", 0.3F, -1.0F, 1.0F,
            () -> this.targetRender.getValue() && this.targetRenderMode.getValue() == 2);
    public final IntProperty ghostLength = new IntProperty("TargetRender-Length", 25, 15, 40,
            () -> this.targetRender.getValue() && this.targetRenderMode.getValue() == 3);

    private final Clicker clicker = new Clicker();
    /* Humanization (2026-10-25): reaction, aim error and whether each click lands. */
    private final atlas.humanize.Humanizer humanizer = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_LIQUIDBOUNCE);
    /** This tick's click verdict (null: humanization off or not a click tick). */
    private atlas.humanize.Humanizer.Verdict verdict;
    /** The reaction to the current target is over (true with humanization off). */
    private boolean reacted = true;
    private int angularTarget = -1;
    private float lastYawToTarget = Float.NaN;
    private final AuraRotations rotations = new AuraRotations();
    private final PointTracker pointTracker = new PointTracker();
    private final TargetTracker<EntityLivingBase> targetTracker = new TargetTracker<EntityLivingBase>();
    private final AngleSmooth.Linear linear = new AngleSmooth.Linear();
    private final AngleSmooth.Sigmoid sigmoid = new AngleSmooth.Sigmoid();
    private final AngleSmooth.Interpolation interpolation = new AngleSmooth.Interpolation();
    private final AngleSmooth.Acceleration acceleration = new AngleSmooth.Acceleration();
    private final AngleSmooth.AimAssist aimAssistSmooth = new AngleSmooth.AimAssist();
    private final AngleSmooth.Tracking tracking = new AngleSmooth.Tracking();
    /** The target Tracking last drew its gains for. */
    private EntityLivingBase trackedTarget;
    private float scanAddition = -1.0F;
    private long lastAttackKeyAt;
    /** Ticks the aura waits before acting again (AutoBlock's pause on unblock). */
    int waitTicks;
    // AutoBlock state
    private boolean enforcedBlocking;
    private boolean blockVisual;
    private boolean hasBlockedSinceAttack;
    private boolean inDanger;
    private int reblockTicks = -1;
    private int pauseOnUnblockTicks = -1;
    private int flushTicks;
    private boolean blinking;
    // FailSwing / NotifyWhenFail
    private float failSwingAddition = -1.0F;
    private final List<double[]> failedHits = new ArrayList<double[]>();
    private int failedHitsIncrement;
    // RangeIndicator
    private float colorFactor;

    public KillAuraLB() {
        super("LiquidAura", false, false, "LiquidBounce's KillAura");
    }

    /** The current target, or null. */
    public EntityLivingBase getTarget() {
        return this.targetTracker.target();
    }

    /** May attack now: not paused for another module, and the requirements are met. */
    public boolean isAttackAllowed() {
        return mc.thePlayer != null && !shouldPauseCombat() && requirementsMet();
    }

    /**
     * LiquidBounce's CombatManager.shouldPauseCombat, with Atlas's modules:
     * Scaffold building, BedNuker breaking, AutoHeal swapping to heal.
     */
    private static boolean shouldPauseCombat() {
        Module scaffold = atlas.Atlas.moduleManager.modules.get(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            return true;
        }
        BedNuker bedNuker = (BedNuker) atlas.Atlas.moduleManager.modules.get(BedNuker.class);
        if (bedNuker != null && bedNuker.isEnabled() && bedNuker.isReady()) {
            return true;
        }
        AutoHeal autoHeal = (AutoHeal) atlas.Atlas.moduleManager.modules.get(AutoHeal.class);
        return autoHeal != null && autoHeal.isEnabled() && autoHeal.isSwitching();
    }

    /** This aura's humanization, for the stats line. */
    public atlas.humanize.Humanizer humanizer() {
        return this.humanizer;
    }

    /** The interaction range (vanilla reach + RangeIncrease). */
    public float reach() {
        return interactionRange();
    }

    /** A sword block of ours is up on the server (LiquidBounce: enforcedBlockingHand / isBlockingServerside). */
    public boolean isAuraBlocking() {
        return this.enforcedBlocking && ItemUtil.isHoldingSword();
    }

    /** Whether to draw the sword blocking (LiquidBounce's blockVisual: real or the fake animation). */
    public boolean isBlockVisual() {
        return this.blockVisual && this.autoBlocking.getValue() && ItemUtil.isHoldingSword();
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            stopBlocking(false);
        }
        setBlink(false);
        this.hasBlockedSinceAttack = false;
        this.inDanger = false;
        this.targetTracker.reset();
        this.humanizer.reset();
        this.reacted = true;
        this.verdict = null;
        /* The rotation is not reset here: onUpdate turns it back to the camera first, from now on. */
        this.rotations.expire();
        this.pointTracker.reset();
        this.failedHits.clear();
        this.failedHitsIncrement = 0;
    }

    @EventTarget
    public void onWorld(atlas.events.LoadWorldEvent event) {
        this.rotations.reset();
        this.enforcedBlocking = false;
        this.blockVisual = false;
        setBlink(false);
    }

    /** A slot change ends a sword block (LiquidBounce's changeSlot handler). */
    @EventTarget
    public void onPacket(atlas.events.PacketEvent event) {
        if (this.isEnabled() && event.getType() == EventType.SEND
                && event.getPacket() instanceof net.minecraft.network.play.client.C09PacketHeldItemChange
                && this.enforcedBlocking) {
            this.blockVisual = false;
            this.enforcedBlocking = false;
        }
    }

    // ---- settings into the engines -------------------------------------

    private void applySettings() {
        this.linear.horizontalMin = this.linearHorizontal.low();
        this.linear.horizontalMax = this.linearHorizontal.high();
        this.linear.verticalMin = this.linearVertical.low();
        this.linear.verticalMax = this.linearVertical.high();
        this.sigmoid.horizontalMin = this.sigmoidHorizontal.low();
        this.sigmoid.horizontalMax = this.sigmoidHorizontal.high();
        this.sigmoid.verticalMin = this.sigmoidVertical.low();
        this.sigmoid.verticalMax = this.sigmoidVertical.high();
        this.sigmoid.steepness = this.sigmoidSteepness.getValue();
        this.sigmoid.midpoint = this.sigmoidMidpoint.getValue();
        this.interpolation.horizontalMin = this.interpHorizontal.low();
        this.interpolation.horizontalMax = this.interpHorizontal.high();
        this.interpolation.verticalMin = this.interpVertical.low();
        this.interpolation.verticalMax = this.interpVertical.high();
        this.interpolation.directionChangeMin = this.interpDirectionChange.low();
        this.interpolation.directionChangeMax = this.interpDirectionChange.high();
        this.interpolation.midpoint = this.interpMidpoint.getValue();
        this.acceleration.yawAccelMin = this.yawAcceleration.low();
        this.acceleration.yawAccelMax = this.yawAcceleration.high();
        this.acceleration.pitchAccelMin = this.pitchAcceleration.low();
        this.acceleration.pitchAccelMax = this.pitchAcceleration.high();
        this.acceleration.dynamicAccel = this.dynamicAccel.getValue();
        this.acceleration.coefDistance = this.coefDistance.getValue();
        this.acceleration.yawCrosshairMin = this.yawCrosshairAccel.low();
        this.acceleration.yawCrosshairMax = this.yawCrosshairAccel.high();
        this.acceleration.pitchCrosshairMin = this.pitchCrosshairAccel.low();
        this.acceleration.pitchCrosshairMax = this.pitchCrosshairAccel.high();
        this.acceleration.accelerationError = this.accelerationError.getValue();
        this.acceleration.yawAccelError = this.yawAccelError.getValue();
        this.acceleration.pitchAccelError = this.pitchAccelError.getValue();
        this.acceleration.constantError = this.constantError.getValue();
        this.acceleration.yawConstantError = this.yawConstantError.getValue();
        this.acceleration.pitchConstantError = this.pitchConstantError.getValue();
        this.acceleration.sigmoidDeceleration = this.sigmoidDeceleration.getValue();
        this.acceleration.steepness = this.decelSteepness.getValue();
        this.acceleration.midpoint = this.decelMidpoint.getValue();
        switch (this.angleSmooth.getValue()) {
            case 1:
                this.rotations.smooth = this.sigmoid;
                break;
            case 2:
                this.rotations.smooth = this.interpolation;
                break;
            case 3:
                this.rotations.smooth = this.acceleration;
                break;
            case SMOOTH_AIMASSIST: {
                AimAssist assist = aimAssist();
                if (assist != null) {
                    this.aimAssistSmooth.horizontalSpeed = assist.hSpeed.getValue();
                    this.aimAssistSmooth.verticalSpeed = assist.vSpeed.getValue();
                    this.aimAssistSmooth.smoothing = assist.smoothing.getValue() / 100.0F;
                    this.aimAssistSmooth.strafeIncrease = assist.strafeIncrease.getValue();
                }
                float strafe = mc.thePlayer == null ? 0.0F : mc.thePlayer.movementInput.moveStrafe;
                this.aimAssistSmooth.strafeSide = strafe > 0.0F ? 1 : (strafe < 0.0F ? -1 : 0);
                this.rotations.smooth = this.aimAssistSmooth;
                break;
            }
            case SMOOTH_TRACKING:
                this.tracking.yawP = this.trackingYawP.getValue();
                this.tracking.pitchP = this.trackingPitchP.getValue();
                this.tracking.integral = this.trackingIntegral.getValue();
                this.tracking.leak = this.trackingLeak.getValue();
                this.tracking.feedForward = this.trackingFeedForward.getValue();
                this.tracking.gainVariance = this.trackingGainVariance.getValue() / 100.0F;
                this.tracking.onTargetZone = this.trackingOnTarget.getValue();
                this.tracking.maxYawMin = this.trackingMaxYaw.low();
                this.tracking.maxYawMax = this.trackingMaxYaw.high();
                this.tracking.maxPitchMin = this.trackingMaxPitch.low();
                this.tracking.maxPitchMax = this.trackingMaxPitch.high();
                this.rotations.smooth = this.tracking;
                break;
            default:
                this.rotations.smooth = this.linear;
        }
        this.rotations.movementCorrection = this.movementCorrection.getValue();
        this.rotations.resetThreshold = this.resetThreshold.getValue();
        this.rotations.ticksUntilReset = this.ticksUntilReset.getValue();
        this.rotations.shortStopOn = this.shortStop.getValue();
        this.rotations.shortStopRate = this.shortStopRate.getValue();
        this.rotations.shortStopMin = this.shortStopDuration.low();
        this.rotations.shortStopMax = this.shortStopDuration.high();
        this.rotations.failOn = this.fail.getValue();
        this.rotations.failRate = this.failRate.getValue();
        this.rotations.failFactor = this.failFactor.getValue();
        this.rotations.failHorizontalMin = this.failHorizontal.low();
        this.rotations.failHorizontalMax = this.failHorizontal.high();
        this.rotations.failVerticalMin = this.failVertical.low();
        this.rotations.failVerticalMax = this.failVertical.high();
        this.rotations.failTransitionMin = this.failTransition.low();
        this.rotations.failTransitionMax = this.failTransition.high();

        this.pointTracker.exemptParts = (this.exemptHead.getValue() ? PointTracker.PART_HEAD : 0)
                | (this.exemptBody.getValue() ? PointTracker.PART_BODY : 0)
                | (this.exemptFeet.getValue() ? PointTracker.PART_FEET : 0);
        this.pointTracker.exemptBest = this.exemptBest.getValue();
        this.pointTracker.exemptBestVertical = this.exemptBestVertical.getValue();
        this.pointTracker.exemptBestHorizontal = this.exemptBestHorizontal.getValue();
        this.pointTracker.gaussianOn = this.pointGaussian.getValue();
        this.pointTracker.gaussYawMin = this.gaussYaw.low();
        this.pointTracker.gaussYawMax = this.gaussYaw.high();
        this.pointTracker.gaussPitchMin = this.gaussPitch.low();
        this.pointTracker.gaussPitchMax = this.gaussPitch.high();
        this.pointTracker.gaussChance = this.gaussChance.getValue();
        this.pointTracker.gaussSpeedMin = this.gaussSpeed.low();
        this.pointTracker.gaussSpeedMax = this.gaussSpeed.high();
        this.pointTracker.gaussTolerance = this.gaussTolerance.getValue();
        this.pointTracker.lazyOn = this.pointLazy.getValue();
        this.pointTracker.lazyMin = this.lazyThreshold.low();
        this.pointTracker.lazyMax = this.lazyThreshold.high();
        this.pointTracker.delayOn = this.pointDelay.getValue();
        this.pointTracker.delayMin = this.delayTicks.low();
        this.pointTracker.delayMax = this.delayTicks.high();

        this.targetTracker.fov = this.targetFov.getValue();
        this.targetTracker.maxHurtTime = this.hurtTime.getValue();
        this.targetTracker.priorities = new int[]{this.priority.getValue() + 1};
        if (this.scanAddition < 0.0F) {
            this.scanAddition = this.scanRangeIncrease.random();
        }
    }

    private static AimAssist aimAssist() {
        Module module = atlas.Atlas.moduleManager == null ? null : atlas.Atlas.moduleManager.modules.get(AimAssist.class);
        return module instanceof AimAssist ? (AimAssist) module : null;
    }

    /**
     * AimAssist's aim point: the hitbox moved ahead by the target's last step
     * times lead; with multipoint the point of its inner box (15 % in from each
     * side, 5-85 % of the height) nearest to the current look, otherwise the
     * middle at eye level kept within 5-75 % of the height.
     */
    private PointTracker.Point aimAssistPoint(EntityLivingBase entity, Vec3 eyes, Rotation from) {
        AimAssist assist = aimAssist();
        float lead = assist == null ? 1.0F : assist.lead.getValue();
        boolean multipoint = assist == null || assist.multipoint.getValue();
        AxisAlignedBB box = hitBox(entity);
        if (lead > 0.0F) {
            box = box.offset((entity.posX - entity.lastTickPosX) * lead, 0.0, (entity.posZ - entity.lastTickPosZ) * lead);
        }
        Vec3 point;
        if (multipoint) {
            point = atlas.util.RotationEngine.nearestOnBox(atlas.util.RotationEngine.aimBox(box, 0.15, 0.05, 0.85),
                    eyes, from.yaw, from.pitch);
        } else {
            double height = box.maxY - box.minY;
            double y = Math.max(box.minY + 0.05 * height, Math.min(box.minY + 0.75 * height, eyes.yCoord));
            point = new Vec3((box.minX + box.maxX) / 2.0, y, (box.minZ + box.maxZ) / 2.0);
        }
        return new PointTracker.Point(new double[]{point.xCoord, point.yCoord, point.zCoord}, box(box));
    }

    private float interactionRange() {
        return AuraRules.interactionRange(this.rangeIncrease.getValue());
    }

    private float wallsRange() {
        return Math.min(this.throughWallsRange.getValue(), interactionRange());
    }

    private float scanRange() {
        return AuraRules.scanRange(interactionRange(), wallsRange(), this.scanAddition);
    }

    // ---- the tick ---------------------------------------------------------

    /** Start of the client tick: the click plan and Fail's roll (LiquidBounce: GameTickEvent, first priority). */
    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        applySettings();
        atlas.humanize.HumanProfile human = this.humanizer.profile();
        int clickTechnique = human != null && human.humanClicks ? Clicker.TECHNIQUE_ATLAS_HUMAN : this.technique.getValue();
        this.clicker.tick(clickTechnique, this.cps.low(), this.cps.high(), this.maxPerTick.getValue(),
                System.currentTimeMillis());
        this.rotations.gameTick();
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            this.lastAttackKeyAt = System.currentTimeMillis();
        }
        /* AutoBlock's game tick: danger, and Blink while only the fake block shows. */
        this.flushTicks++;
        this.inDanger = this.autoBlocking.getValue() && this.onlyWhenInDanger.getValue() && isInDanger();
        int blink = this.blockBlink.getValue();
        if (blink > 0 && isBlockVisual() && !this.enforcedBlocking && this.flushTicks < blink) {
            setBlink(true);
        } else {
            if (this.blinking) {
                this.flushTicks = 0;
            }
            setBlink(false);
        }
    }

    private void setBlink(boolean on) {
        if (on != this.blinking) {
            this.blinking = on;
            atlas.Atlas.blinkManager.setBlinkState(on, atlas.enums.BlinkModules.AUTO_BLOCK);
        }
    }

    /**
     * Before the movement packet: choose the target (RotationUpdateEvent), turn
     * (RotationManager.update), then attack (the KillAura tick handler).
     */
    @EventTarget(value = Priority.LOW, whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.isEnabled()) {
            /* Switched off (or another engine picked) mid-turn: turn back as on a lost target, then let go. */
            if (this.rotations.current() != null) {
                Rotation back = this.rotations.update(new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch),
                        RotationUtil.gcd());
                if (back != null) {
                    event.setRotation(back.yaw, back.pitch, 1);
                    event.setPervRotation(back.yaw, 1);
                }
            }
            return;
        }
        if (this.waitTicks > 0) {
            this.waitTicks--;
        }
        Rotation player = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        boolean inventory = mc.currentScreen instanceof GuiContainer;
        boolean dead = mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.isSpectator();
        boolean paused = shouldPauseCombat();
        this.verdict = null;
        this.rotations.speedScale = 1.0F;
        EntityLivingBase previousTarget = this.targetTracker.target();
        if (previousTarget != null && this.clicker.isClickTick() && this.humanizer.profile() != null
                && RotationUtil.distanceToBox(previousTarget, mc.thePlayer.getPositionEyes(1.0F)) <= interactionRange()) {
            /* Decided before the turn, so an aimed miss turns off the box this very tick. */
            this.verdict = this.humanizer.click(situation(previousTarget), System.currentTimeMillis());
            if (this.verdict != null && this.verdict.land && this.humanizer.dropClick()) {
                this.verdict = new atlas.humanize.Humanizer.Verdict(false, atlas.humanize.Misses.Style.SKIP);
            }
        }
        if (inventory && !this.ignoreOpenInventory.getValue() || dead || !requirementsMet()) {
            this.targetTracker.reset();
        } else if (!paused) {
            updateTarget();
        }
        if (this.targetTracker.target() != this.trackedTarget) {
            this.trackedTarget = this.targetTracker.target();
            this.tracking.reseed();
        }

        Rotation rotation = inventory && !this.ignoreOpenInventory.getValue() ? this.rotations.current()
                : this.rotations.update(player, RotationUtil.gcd());
        if (rotation != null) {
            if (this.rotations.movementCorrection == AuraRotations.CORRECTION_CHANGE_LOOK) {
                mc.thePlayer.rotationYaw = rotation.yaw;
                mc.thePlayer.rotationPitch = rotation.pitch;
            }
            event.setRotation(rotation.yaw, rotation.pitch, 1);
            if (this.rotations.movementCorrection != AuraRotations.CORRECTION_OFF) {
                event.setPervRotation(rotation.yaw, 1);
            }
            mc.thePlayer.rotationYawHead = rotation.yaw;
            mc.thePlayer.renderYawOffset = rotation.yaw;
        }
        this.attackPending = !dead;
        this.attackPaused = paused;
    }

    private boolean attackPending;
    private boolean attackPaused;

    /**
     * The attack, once every module has set this tick's look (MixinEntityPlayerSP
     * through Silent Aura's afterRotations, as the Atlas engine's K1): the hit is
     * judged along the rotation the movement packet will carry, even when a
     * module with a higher priority (Clutch, Scaffold) took it this tick.
     */
    public void afterRotations(UpdateEvent event) {
        if (!this.attackPending) {
            return;
        }
        this.attackPending = false;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Rotation sent = event.isRotated() ? new Rotation(event.getNewYaw(), event.getNewPitch())
                : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        if (this.attackPaused) {
            stopBlocking(false);
            return;
        }
        EntityLivingBase target = this.targetTracker.target();
        if (target == null) {
            boolean unblocked = stopBlocking(false);
            if (this.failSwing.getValue() && requirementsMet()) {
                if (unblocked && this.pauseOnUnblockTicks > 0) {
                    this.waitTicks = this.pauseOnUnblockTicks;
                } else {
                    dealWithFakeSwing(null);
                }
            }
            return;
        }
        if (!requirementsMet()) {
            return;
        }
        Rotation aim = null;
        if (this.rotationTiming.getValue() == 2) {
            Object[] found = findRotation(target, interactionRange(), wallsRange());
            aim = found == null ? null : (Rotation) found[0];
        }
        if (aim == null) {
            aim = sent;
        }
        Entity crosshair = target;
        if (this.raycast.getValue() != 0) {
            Entity hit = entityInCrosshair(interactionRange(), aim, this.raycast.getValue() == 1);
            if (hit != null) {
                crosshair = hit;
            }
        }
        if (crosshair instanceof EntityLivingBase && crosshair != target && shouldBeAttacked((EntityLivingBase) crosshair)) {
            this.targetTracker.setTarget((EntityLivingBase) crosshair);
        }
        if (this.waitTicks <= 0 && this.reacted) {
            attackTarget(crosshair, aim);
        }
    }

    /** Silent: walk keys turned to the server yaw (Strict only moves along it, through setPervRotation). */
    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.fightBot.getValue()) {
            fightBotInput();
        }
        Rotation rotation = this.rotations.current();
        if (rotation != null && this.rotations.movementCorrection == AuraRotations.CORRECTION_SILENT
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(rotation.yaw);
        }
    }

    /** FightBot: walk keys toward the goal, jump, swim and sprint (NavigationBaseValueGroup's input handler). */
    private void fightBotInput() {
        double px = mc.thePlayer.posX;
        double pz = mc.thePlayer.posZ;
        double[] goal = null;
        boolean leaderFar = false;
        if (this.leader.getValue() && !this.leaderName.getValue().isEmpty()) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player != mc.thePlayer && this.leaderName.getValue().equals(player.getName())) {
                    goal = atlas.lb.combat.FightBotPlanner.leaderPosition(player.posX, player.posZ,
                            this.leaderRadius.getValue(), px, pz);
                    leaderFar = Math.hypot(goal[0] - px, goal[1] - pz) > this.leaderRadius.getValue();
                    break;
                }
            }
        }
        EntityLivingBase target = this.targetTracker.target();
        boolean jumpForTarget = false;
        if (goal == null && target != null) {
            double distance = Math.hypot(target.posX - px, target.posZ - pz);
            double range = Math.min(interactionRange(), distance);
            float required = atlas.lb.combat.FightBotPlanner.yawTo(px, pz, target.posX, target.posZ);
            /* LiquidBounce: Rotation.lookingAt(playerPosition, entity.eyePosition) -- from the target toward us. */
            float requiredFromTarget = atlas.lb.combat.FightBotPlanner.yawTo(target.posX, target.posZ, px, pz);
            boolean outOfDistance = distance > this.opponentRange.getValue();
            boolean outOfDanger = atlas.lb.combat.FightBotPlanner.outOfDanger(target.rotationYaw, requiredFromTarget,
                    this.dangerousYaw.getValue());
            jumpForTarget = outOfDistance && !outOfDanger;
            if (this.runawayOnCooldown.getValue() && !this.clicker.willClickAt(1)) {
                goal = atlas.lb.combat.FightBotPlanner.runawayPosition(px, pz, requiredFromTarget, range);
            } else {
                final double y = mc.thePlayer.posY;
                goal = atlas.lb.combat.FightBotPlanner.attackPosition(target.posX, target.posZ, target.rotationYaw,
                        range, this.dangerousYaw.getValue(), px, pz, (x, z) -> collidesAt(x, y, z));
            }
        }
        if (goal == null) {
            return;
        }
        int[] keys = atlas.lb.combat.FightBotPlanner.directionalInput(goal[0] - px, goal[1] - pz,
                mc.thePlayer.rotationYaw, 20.0F);
        float scale = mc.thePlayer.movementInput.sneak ? 0.3F : 1.0F;
        mc.thePlayer.movementInput.moveForward = keys[0] * scale;
        mc.thePlayer.movementInput.moveStrafe = keys[1] * scale;
        if (this.autoSwim.getValue() && mc.thePlayer.isInWater()
                || this.autoJump.getValue() && mc.thePlayer.isCollidedHorizontally
                || jumpForTarget || leaderFar) {
            mc.thePlayer.movementInput.jump = true;
        }
        if (this.autoSprint.getValue() && (keys[0] != 0 || keys[1] != 0)) {
            mc.thePlayer.setSprinting(true);
        }
    }

    /** The player's box at (x, y, z) inside a block (LiquidBounce doesCollideAt). */
    private static boolean collidesAt(double x, double y, double z) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox()
                .offset(x - mc.thePlayer.posX, y - mc.thePlayer.posY, z - mc.thePlayer.posZ);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    // ---- targets ------------------------------------------------------------

    private void updateTarget() {
        float interaction = interactionRange();
        List<TargetTracker.Candidate<EntityLivingBase>> all = new ArrayList<TargetTracker.Candidate<EntityLivingBase>>();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc.thePlayer.getLook(1.0F);
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase) || !shouldBeAttacked((EntityLivingBase) entity)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            double distance = RotationUtil.distanceToBox(living, eyes);
            Vec3 to = new Vec3(living.posX - eyes.xCoord, living.posY + living.height / 2.0 - eyes.yCoord,
                    living.posZ - eyes.zCoord).normalize();
            all.add(new TargetTracker.Candidate<EntityLivingBase>(living, living instanceof EntityPlayer,
                    living instanceof EntityMob || living instanceof EntitySlime, living.getHealth(),
                    distance * distance, look.dotProduct(to), living.hurtTime, living.ticksExisted));
        }
        List<TargetTracker.Candidate<EntityLivingBase>> sorted = this.targetTracker.targets(all);
        double maximum = sorted.isEmpty() || this.targetTracker.closestSquaredEnemyDistance() > interaction * interaction
                ? scanRange() : interaction;
        List<TargetTracker.Candidate<EntityLivingBase>> inRange = new ArrayList<TargetTracker.Candidate<EntityLivingBase>>();
        List<TargetTracker.Candidate<EntityLivingBase>> beyond = new ArrayList<TargetTracker.Candidate<EntityLivingBase>>();
        for (TargetTracker.Candidate<EntityLivingBase> candidate : sorted) {
            if (candidate.distanceSq > maximum * maximum) {
                continue;
            }
            (candidate.distanceSq <= interaction * interaction ? inRange : beyond).add(candidate);
        }
        inRange.addAll(beyond);
        for (TargetTracker.Candidate<EntityLivingBase> candidate : inRange) {
            if (processTarget(candidate.entity, (float) maximum, wallsRange())) {
                this.targetTracker.setTarget(candidate.entity);
                return;
            }
        }
        this.humanizer.noTarget(System.currentTimeMillis());
        this.reacted = true;
        if (this.fightBot.getValue()) {
            /* FightBot: the nearest enemy it may walk to, aimed with the movement rotation. */
            this.targetTracker.reset();
            for (TargetTracker.Candidate<EntityLivingBase> candidate : sorted) {
                EntityLivingBase entity = candidate.entity;
                if (candidate.distanceSq > this.fightBotRange.getValue() * this.fightBotRange.getValue()) {
                    continue;
                }
                if (this.visibleOnly.getValue() && !mc.thePlayer.canEntityBeSeen(entity)) {
                    continue;
                }
                if (this.notWhenVoid.getValue() && overVoid(entity)) {
                    continue;
                }
                this.targetTracker.setTarget(entity);
                break;
            }
            this.rotations.request(fightBotRotation(), 0.0, false);
        } else {
            this.targetTracker.reset();
        }
    }

    /** No block under the entity down to the bottom of the world (LiquidBounce doesNotCollideBelow). */
    private static boolean overVoid(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        return mc.theWorld.getCollidingBoundingBoxes(entity, box.addCoord(0.0, -box.minY - 1.0, 0.0)).isEmpty();
    }

    /** FightBot's look: the direction the walk keys move in, pitch toward the target's middle. */
    private Rotation fightBotRotation() {
        float yaw = atlas.lb.combat.FightBotPlanner.movementYaw(mc.thePlayer.rotationYaw,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe);
        EntityLivingBase target = this.targetTracker.target();
        if (target == null) {
            return new Rotation(yaw, 0.0F);
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = target.getEntityBoundingBox();
        Rotation look = Rotation.fromVector((box.minX + box.maxX) / 2 - eyes.xCoord, (box.minY + box.maxY) / 2 - eyes.yCoord,
                (box.minZ + box.maxZ) / 2 - eyes.zCoord);
        return new Rotation(yaw, look.pitch);
    }

    /** Aims at the entity unless Snap / OnTick say to wait; false when no rotation reaches it. */
    private boolean processTarget(EntityLivingBase entity, float range, float wallsRange) {
        Object[] found = findRotation(entity, range, wallsRange);
        if (found == null) {
            return false;
        }
        Rotation rotation = (Rotation) found[0];
        Rotation from = this.rotations.current() != null ? this.rotations.current()
                : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        if (!this.humanizer.reacted(entity.getEntityId(), System.currentTimeMillis())) {
            /* Seen, not yet reacted to: no aim and no click until the reaction time is up. */
            this.reacted = false;
            return true;
        }
        this.reacted = true;
        int ticks = this.rotations.calculateTicks(from, rotation);
        if (this.rotationTiming.getValue() == 1 && !this.clicker.willClickAt(Math.max(1, ticks))) {
            /* Snap: not clicking when the aim would arrive, so no aim yet. */
            return true;
        }
        if (this.rotationTiming.getValue() == 2 && ticks <= 1) {
            /* OnTick: aims on the click itself. */
            return true;
        }
        double distance = RotationUtil.distanceToBox(entity, mc.thePlayer.getPositionEyes(1.0F));
        boolean onCrosshair = lookingAt(entity, from, Math.max(3.0, distance), Math.max(3.0, distance)) != null;
        this.rotations.request(humanAim(entity, from, rotation, distance), distance, onCrosshair);
        return true;
    }

    /**
     * Humanization's hand on the aim: tremor and overshoot added to the
     * rotation, the turn speed varied, a lapse holding the look where it is,
     * and an aimed miss turning just past the box for this click.
     */
    private Rotation humanAim(EntityLivingBase entity, Rotation from, Rotation rotation, double distance) {
        if (this.humanizer.profile() == null) {
            return rotation;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = hitBox(entity);
        Rotation middle = Rotation.fromVector((box.minX + box.maxX) / 2 - eyes.xCoord, (box.minY + box.maxY) / 2 - eyes.yCoord,
                (box.minZ + box.maxZ) / 2 - eyes.zCoord);
        float angular = 0.0F;
        if (this.angularTarget == entity.getEntityId() && !Float.isNaN(this.lastYawToTarget)) {
            angular = Math.abs(Rotation.angleDifference(middle.yaw, this.lastYawToTarget));
        }
        this.angularTarget = entity.getEntityId();
        this.lastYawToTarget = middle.yaw;
        float[] turn = from.deltaTo(rotation);
        atlas.humanize.AimError.Adjust adjust = this.humanizer.aim(turn[0], turn[1], angular);
        if (adjust == null) {
            return rotation;
        }
        this.rotations.speedScale = adjust.speed;
        if (this.verdict != null && this.verdict.miss == atlas.humanize.Misses.Style.AIM_OFF) {
            float[] off = this.humanizer.aimOff(distance, (box.maxX - box.minX) / 2, (box.maxY - box.minY) / 2);
            return new Rotation(middle.yaw + off[0], middle.pitch + off[1]);
        }
        if (adjust.lapsing) {
            return from;
        }
        return new Rotation(rotation.yaw + adjust.yaw, rotation.pitch + adjust.pitch);
    }

    /** The situation of a click on this target, for the accuracy. */
    private atlas.humanize.Accuracy.Situation situation(EntityLivingBase target) {
        atlas.humanize.Accuracy.Situation s = new atlas.humanize.Accuracy.Situation();
        s.distance = RotationUtil.distanceToBox(target, mc.thePlayer.getPositionEyes(1.0F));
        double lx = target.posX - mc.thePlayer.posX;
        double lz = target.posZ - mc.thePlayer.posZ;
        double length = Math.max(1.0E-6, Math.hypot(lx, lz));
        double vx = target.posX - target.lastTickPosX;
        double vz = target.posZ - target.lastTickPosZ;
        s.targetLateralSpeed = Math.abs(vx * -lz + vz * lx) / length;
        s.targetAirborne = !target.onGround;
        s.selfAirborne = !mc.thePlayer.onGround;
        s.ticksSinceHurt = mc.thePlayer.hurtTime > 0 ? Math.max(0, mc.thePlayer.maxHurtTime - mc.thePlayer.hurtTime) : -1;
        s.targetHealth = target.getHealth();
        return s;
    }

    /**
     * A click humanization says misses: AIM_OFF / SWING_ONLY swing without an
     * attack (AIM_OFF with the look already turned past the box this tick, and
     * vanilla's miss delay when miss-cooldown is on); SKIP spends the press.
     */
    private void humanMiss(Entity target, Rotation rotation, atlas.humanize.Misses.Style style) {
        long now = System.currentTimeMillis();
        if (style == atlas.humanize.Misses.Style.SKIP) {
            this.clicker.click(this::missOk, () -> false);
            this.humanizer.missed(style, now);
            return;
        }
        final boolean stillOn = lookingAt(target, rotation, interactionRange(), wallsRange()) != null;
        prepareForAttack(rotation, () -> {
            mc.thePlayer.swingItem();
            atlas.humanize.HumanProfile p = this.humanizer.profile();
            if (!stillOn && p != null && p.missCooldown && mc.playerController.isNotCreative()) {
                ((IAccessorMinecraft) mc).setLeftClickCounter(10);
            }
            this.humanizer.missed(stillOn ? atlas.humanize.Misses.Style.SWING_ONLY : style, now);
            return true;
        });
    }

    /** Atlas's own filters for who may be attacked (LiquidBounce: shouldBeAttacked + the global targets). */
    private boolean shouldBeAttacked(EntityLivingBase entity) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.isDead || entity.deathTime > 0
                || entity.worldObj != mc.theWorld || entity == mc.getRenderViewEntity()) {
            return false;
        }
        if (entity instanceof EntityOtherPlayerMP) {
            EntityPlayer player = (EntityPlayer) entity;
            return this.players.getValue() && TargetFilter.accepts(player) && !TeamUtil.isFriend(player)
                    && (!this.teams.getValue() || !TeamUtil.isSameTeam(player))
                    && (!this.botCheck.getValue() || !TeamUtil.isBot(player));
        }
        if (entity instanceof EntityPlayer) {
            return false;
        }
        if (entity instanceof EntityDragon || entity instanceof EntityWither) {
            return this.bosses.getValue();
        }
        if (entity instanceof EntitySilverfish) {
            return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entity));
        }
        if (entity instanceof EntityMob || entity instanceof EntitySlime) {
            return this.mobs.getValue();
        }
        if (entity instanceof EntityIronGolem) {
            return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entity));
        }
        if (entity instanceof EntityAnimal || entity instanceof EntityBat || entity instanceof EntitySquid
                || entity instanceof EntityVillager) {
            return this.animals.getValue();
        }
        return false;
    }

    // ---- rotation finding ------------------------------------------------

    private static AxisAlignedBB hitBox(Entity entity) {
        double border = entity.getCollisionBorderSize();
        return entity.getEntityBoundingBox().expand(border, border, border);
    }

    private static double[] box(AxisAlignedBB b) {
        return new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
    }

    /** {Rotation, Vec3 hit} for the best spot to attack, or null (LiquidBounce findRotation). */
    private Object[] findRotation(EntityLivingBase entity, float range, float wallsRange) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        if (this.lazyRotation.getValue()) {
            Rotation current = this.rotations.current() != null ? this.rotations.current()
                    : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            Vec3 hit = lookingAt(entity, current, range, wallsRange);
            if (hit != null) {
                return new Object[]{current, hit};
            }
        }
        PointTracker.Point point;
        if (this.angleSmooth.getValue() == SMOOTH_AIMASSIST) {
            Rotation from = this.rotations.current() != null ? this.rotations.current()
                    : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            point = aimAssistPoint(entity, eyes, from);
        } else {
            point = this.pointTracker.findPoint(new double[]{eyes.xCoord, eyes.yCoord, eyes.zCoord}, box(hitBox(entity)));
        }
        Object[] found = raytraceBox(eyes, point, range, wallsRange);
        if (found == null && this.aimThroughWalls.getValue()) {
            found = raytraceBox(eyes, point, range, range);
        }
        return found;
    }

    /**
     * A visible spot in the point's box within range (within walls range when
     * a block is in the way), the one whose rotation differs least from the
     * rotation to the tracked point (LiquidBounce raytraceBox with
     * LeastDifferencePreference).
     */
    private Object[] raytraceBox(Vec3 eyes, PointTracker.Point point, float range, float wallsRange) {
        Rotation preferred = Rotation.fromVector(point.pos[0] - eyes.xCoord, point.pos[1] - eyes.yCoord,
                point.pos[2] - eyes.zCoord);
        Vec3 best = null;
        float bestDiff = Float.MAX_VALUE;
        Vec3 tracked = new Vec3(point.pos[0], point.pos[1], point.pos[2]);
        if (reachable(eyes, tracked, range, wallsRange)) {
            best = tracked;
            bestDiff = 0.0F;
        } else {
            double[] b = point.box;
            for (double x = 0.0; x <= 1.0001; x += 0.2) {
                for (double y = 0.0; y <= 1.0001; y += 0.2) {
                    for (double z = 0.0; z <= 1.0001; z += 0.2) {
                        Vec3 spot = new Vec3(b[0] + (b[3] - b[0]) * x, b[1] + (b[4] - b[1]) * y, b[2] + (b[5] - b[2]) * z);
                        if (!reachable(eyes, spot, range, wallsRange)) {
                            continue;
                        }
                        Rotation r = Rotation.fromVector(spot.xCoord - eyes.xCoord, spot.yCoord - eyes.yCoord,
                                spot.zCoord - eyes.zCoord);
                        float diff = r.deltaLengthTo(preferred);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            best = spot;
                        }
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        Rotation rotation = Rotation.fromVector(best.xCoord - eyes.xCoord, best.yCoord - eyes.yCoord, best.zCoord - eyes.zCoord);
        return new Object[]{rotation, best};
    }

    private static boolean reachable(Vec3 eyes, Vec3 spot, float range, float wallsRange) {
        double distance = eyes.distanceTo(spot);
        if (distance > range) {
            return false;
        }
        return distance <= wallsRange || mc.theWorld.rayTraceBlocks(eyes, spot, false, true, false) == null;
    }

    /** Where a ray along {@code rotation} meets the entity's box within range, or null (isLookingAtEntity). */
    private static Vec3 lookingAt(Entity entity, Rotation rotation, double range, double wallsRange) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = hitBox(entity);
        if (box.isVecInside(eyes)) {
            return eyes;
        }
        Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(rotation.pitch, rotation.yaw);
        Vec3 end = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        MovingObjectPosition hit = box.calculateIntercept(eyes, end);
        if (hit == null) {
            return null;
        }
        double distance = eyes.distanceTo(hit.hitVec);
        if (distance > range) {
            return null;
        }
        if (distance > wallsRange && mc.theWorld.rayTraceBlocks(eyes, hit.hitVec, false, true, false) != null) {
            return null;
        }
        return hit.hitVec;
    }

    /** The nearest entity along the look within range (findEntityInCrosshair); Enemy: only those the aura attacks. */
    private Entity entityInCrosshair(double range, Rotation rotation, boolean enemiesOnly) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(rotation.pitch, rotation.yaw);
        Vec3 end = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        AxisAlignedBB sweep = mc.thePlayer.getEntityBoundingBox()
                .addCoord(look.xCoord * range, look.yCoord * range, look.zCoord * range).expand(1.0, 1.0, 1.0);
        Entity nearest = null;
        double best = range + 1.0E-3;
        for (Entity entity : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, sweep)) {
            if (!entity.canBeCollidedWith()) {
                continue;
            }
            if (enemiesOnly && !(entity instanceof EntityLivingBase && shouldBeAttacked((EntityLivingBase) entity))) {
                continue;
            }
            if (entity instanceof EntityPlayer && TeamUtil.isFriend((EntityPlayer) entity)) {
                /* All takes whatever the look meets first, except a friend (Atlas's friend list). */
                continue;
            }
            AxisAlignedBB box = hitBox(entity);
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
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    // ---- attacking -------------------------------------------------------

    private boolean requirementsMet() {
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean weapon = held != null && (held.getItem() instanceof ItemSword
                || EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0);
        int required = (this.requireClick.getValue() ? AuraRules.REQUIRE_CLICK : 0)
                | (this.requireWeapon.getValue() ? AuraRules.REQUIRE_WEAPON : 0)
                | (this.requireEmptyHand.getValue() ? AuraRules.REQUIRE_EMPTY_HAND : 0)
                | (this.requireVanillaName.getValue() ? AuraRules.REQUIRE_VANILLA_NAME : 0)
                | (this.requireNotBreaking.getValue() ? AuraRules.REQUIRE_NOT_BREAKING : 0);
        boolean clicked = mc.gameSettings.keyBindAttack.isKeyDown()
                || System.currentTimeMillis() - this.lastAttackKeyAt <= CLICK_MEMORY_MS;
        return AuraRules.requirementsMet(required, clicked, weapon, held == null,
                held == null || !held.hasDisplayName(),
                ((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock());
    }

    private void attackTarget(Entity target, Rotation rotation) {
        makeSeemBlock();
        Vec3 hit = lookingAt(target, rotation, interactionRange(), wallsRange());
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        boolean inRange = hit != null && eyes.distanceTo(hit) <= interactionRange();
        atlas.humanize.Humanizer.Verdict verdict = this.verdict;
        this.verdict = null;
        if (verdict != null && this.clicker.isClickTick() && canAttackNow(target)
                && RotationUtil.distanceToBox(target, eyes) <= interactionRange()) {
            if (!verdict.land) {
                humanMiss(target, rotation, verdict.miss);
                return;
            }
            if (!inRange) {
                /* Meant to land, the look not on the box: a person clicks anyway and misses. */
                long now = System.currentTimeMillis();
                prepareForAttack(rotation, () -> {
                    if (vanillaPath()) {
                        if (vanillaClick(rotation) == target) {
                            this.humanizer.landed(now);
                        } else {
                            this.humanizer.unlucky(now);
                        }
                        return true;
                    }
                    mc.thePlayer.swingItem();
                    this.humanizer.unlucky(now);
                    return true;
                });
                return;
            }
        }
        if (!inRange) {
            if (this.autoBlocking.getValue() && this.onScanRange.getValue()
                    && RotationUtil.distanceToBox(target, eyes) <= scanRange()) {
                if (this.clicker.ticksSinceLastClick() >= reblockTicks()) {
                    startBlocking();
                }
                return;
            }
            boolean unblocked = stopBlocking(false);
            if (unblocked && pauseTicks() > 0) {
                this.waitTicks = this.pauseOnUnblockTicks;
            } else if (this.failSwing.getValue()) {
                dealWithFakeSwing(target);
            }
            return;
        }
        if (this.clicker.isClickTick() && canAttackNow(target) && !isPrioritizingBlocking()) {
            prepareForAttack(rotation, () -> {
                if (!canAttackNow(target)) {
                    return false;
                }
                if (vanillaPath()) {
                    /* Vanilla decides what the click lands on; only a hit on an entity counts as one. */
                    Entity clicked = vanillaClick(rotation);
                    if (this.humanizer.profile() != null) {
                        if (clicked != null) {
                            this.humanizer.landed(System.currentTimeMillis());
                        } else {
                            this.humanizer.unlucky(System.currentTimeMillis());
                        }
                    }
                    if (clicked == null) {
                        return true;
                    }
                } else {
                    attackEntity(target);
                    if (this.humanizer.profile() != null) {
                        this.humanizer.landed(System.currentTimeMillis());
                    }
                }
                this.scanAddition = this.scanRangeIncrease.random();
                this.failSwingAddition = this.failSwingRange.random();
                this.failedHitsIncrement = 0;
                this.hasBlockedSinceAttack = false;
                return true;
            });
        } else if (this.clicker.ticksSinceLastClick() >= reblockTicks()) {
            startBlocking();
        }
    }

    // ---- AutoBlocking (KillAuraAutoBlock) --------------------------------

    private int reblockTicks() {
        if (this.reblockTicks < 0) {
            this.reblockTicks = this.reblock.random();
        }
        return this.reblockTicks;
    }

    private int pauseTicks() {
        if (this.pauseOnUnblockTicks < 0) {
            this.pauseOnUnblockTicks = this.pauseOnUnblock.random();
        }
        return this.pauseOnUnblockTicks;
    }

    private void makeSeemBlock() {
        if (this.autoBlocking.getValue()) {
            this.blockVisual = true;
        }
    }

    private boolean blockingServerside() {
        return this.enforcedBlocking || mc.thePlayer.isBlocking();
    }

    /** Fewer clicks for a block first: no block since the last hit (and in danger, with OnlyWhenInDanger). */
    private boolean isPrioritizingBlocking() {
        if (mc.thePlayer.isUsingItem()) {
            this.hasBlockedSinceAttack = true;
        }
        if (!this.autoBlocking.getValue() || !this.prioritizeBlocking.getValue() || this.blockMode.getValue() == 2
                || !ItemUtil.isHoldingSword()) {
            return false;
        }
        return !this.hasBlockedSinceAttack && (!this.onlyWhenInDanger.getValue() || this.inDanger);
    }

    /** Raises the sword (Basic: use; Interact: the entity in the crosshair first; Fake: only the animation). */
    private boolean startBlocking() {
        if (!this.autoBlocking.getValue()) {
            return false;
        }
        if (this.onlyWhenInDanger.getValue() && !this.inDanger) {
            stopBlocking(false);
            return false;
        }
        if (mc.thePlayer.isUsingItem()) {
            this.hasBlockedSinceAttack = true;
            return false;
        }
        if (Math.random() * 100.0 >= this.blockChance.getValue()) {
            return false;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemSword)) {
            return false;
        }
        if (this.blockMode.getValue() == 2) {
            this.blockVisual = true;
            return false;
        }
        if (this.blockMode.getValue() == 1) {
            Rotation rotation = this.rotations.current() != null ? this.rotations.current()
                    : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            Entity facing = this.raycast.getValue() == 0 ? null
                    : entityInCrosshair(interactionRange(), rotation, this.raycast.getValue() == 1);
            if (facing != null) {
                /* Vanilla's right click on an entity: the interact, then the item's own use. */
                mc.playerController.interactWithEntitySendPacket(mc.thePlayer, facing);
            }
        }
        useItem(held);
        this.reblockTicks = this.reblock.random();
        this.enforcedBlocking = true;
        this.hasBlockedSinceAttack = true;
        this.blockVisual = true;
        return true;
    }

    private void useItem(ItemStack held) {
        if (this.simulateVanillaUse.getValue()) {
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
        } else {
            PacketUtil.sendPacket(new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(held));
            mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
        }
    }

    /** Lowers the sword; {@code pauses}: only to hit, the animation stays. True when it let go. */
    private boolean stopBlocking(boolean pauses) {
        if (!pauses) {
            this.blockVisual = false;
            if (mc.gameSettings.keyBindUseItem.isKeyDown()) {
                return false;
            }
        }
        if (!this.enforcedBlocking && !mc.thePlayer.isBlocking()) {
            return false;
        }
        this.pauseOnUnblockTicks = this.pauseOnUnblock.random();
        switch (this.unblockMode.getValue()) {
            case 1: {
                int slot = mc.thePlayer.inventory.currentItem;
                PacketUtil.sendPacket(new net.minecraft.network.play.client.C09PacketHeldItemChange((slot + 1) % 9));
                PacketUtil.sendPacket(new net.minecraft.network.play.client.C09PacketHeldItemChange(slot));
                mc.thePlayer.stopUsingItem();
                this.enforcedBlocking = false;
                return true;
            }
            case 2:
                if (pauses) {
                    return false;
                }
                mc.playerController.onStoppedUsingItem(mc.thePlayer);
                this.enforcedBlocking = false;
                return true;
            default:
                mc.playerController.onStoppedUsingItem(mc.thePlayer);
                this.enforcedBlocking = false;
                return true;
        }
    }

    /** OnlyWhenInDanger: an enemy in range whose look, within reach, meets our (tolerated) box. */
    private boolean isInDanger() {
        float interaction = interactionRange();
        Vec3 ours = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB tolerated = mc.thePlayer.getEntityBoundingBox().expand(this.dangerTolerance.getValue(),
                this.dangerTolerance.getValue(), this.dangerTolerance.getValue());
        for (EntityPlayer enemy : mc.theWorld.playerEntities) {
            if (!shouldBeAttacked(enemy) || RotationUtil.distanceToBox(enemy, ours) > interaction) {
                continue;
            }
            Vec3 eyes = enemy.getPositionEyes(1.0F);
            Vec3 look = enemy.getLook(1.0F);
            Vec3 end = eyes.addVector(look.xCoord * interaction, look.yCoord * interaction, look.zCoord * interaction);
            MovingObjectPosition hit = tolerated.calculateIntercept(eyes, end);
            if (hit == null) {
                continue;
            }
            double distance = eyes.distanceTo(hit.hitVec);
            if (distance >= this.dangerForceActive.low() && distance <= this.dangerForceActive.high()
                    || distance <= wallsRange()
                    || distance <= interaction && mc.theWorld.rayTraceBlocks(eyes, hit.hitVec, false, true, false) == null) {
                return true;
            }
        }
        return false;
    }

    // ---- FailSwing and NotifyWhenFail ------------------------------------

    /** A swing at the air (or a dig) at an enemy just out of reach, for the look of it. */
    private void dealWithFakeSwing(Entity target) {
        if (!this.failSwing.getValue() || !canAttackNow(null)) {
            return;
        }
        if (this.failSwingAddition < 0.0F) {
            this.failSwingAddition = this.failSwingRange.random();
        }
        double range = interactionRange() + this.failSwingAddition;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Entity entity = target;
        if (entity == null) {
            double best = Double.MAX_VALUE;
            for (Entity e : mc.theWorld.loadedEntityList) {
                if (e instanceof EntityLivingBase && shouldBeAttacked((EntityLivingBase) e)) {
                    double d = RotationUtil.distanceToBox(e, eyes);
                    if (d <= range && d < best) {
                        best = d;
                        entity = e;
                    }
                }
            }
        }
        if (entity == null || entity.isDead || RotationUtil.distanceToBox(entity, eyes) > range) {
            return;
        }
        MovingObjectPosition over = mc.objectMouseOver;
        boolean onBlock = over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && !mc.theWorld.isAirBlock(over.getBlockPos());
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                || !onBlock && over != null && over.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) {
            return;
        }
        makeSeemBlock();
        final Entity swungAt = entity;
        prepareForAttack(null, () -> {
            /* A click on a block starts digging it; only one into the air has the miss delay. */
            if (onBlock) {
                mc.playerController.clickBlock(over.getBlockPos(), over.sideHit);
            } else if (mc.playerController.isNotCreative()) {
                ((IAccessorMinecraft) mc).setLeftClickCounter(10);
            }
            mc.thePlayer.swingItem();
            notifyForFailedHit(swungAt);
            return true;
        });
    }

    private void notifyForFailedHit(Entity entity) {
        this.failedHitsIncrement++;
        if (this.notifyWhenFail.getValue() == 1) {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            AxisAlignedBB box = entity.getEntityBoundingBox();
            double center = eyes.distanceTo(new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2));
            Rotation rotation = this.rotations.current() != null ? this.rotations.current()
                    : new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(rotation.pitch, rotation.yaw);
            this.failedHits.add(new double[]{eyes.xCoord + look.xCoord * center, eyes.yCoord + look.yCoord * center,
                    eyes.zCoord + look.zCoord * center, System.currentTimeMillis()});
        } else if (this.notifyWhenFail.getValue() == 2) {
            mc.thePlayer.playSound("random.click", this.notifyVolume.getValue() / 100.0F, this.notifyPitch.getValue());
        }
    }

    // ---- rendering -------------------------------------------------------

    @EventTarget
    public void onRender3D(atlas.events.Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        atlas.mixin.IAccessorRenderManager view = (atlas.mixin.IAccessorRenderManager) mc.getRenderManager();
        double vx = view.getRenderPosX();
        double vy = view.getRenderPosY();
        double vz = view.getRenderPosZ();
        float partial = event.getPartialTicks();
        long now = System.currentTimeMillis();
        renderFailedHits(vx, vy, vz, now);
        renderRangeIndicator(vx, vy, vz, partial, now);
        renderTarget(vx, vy, vz, partial, now);
    }

    private void renderFailedHits(double vx, double vy, double vz, long now) {
        if (this.failedHits.isEmpty()) {
            return;
        }
        if (!this.failSwing.getValue() || this.notifyWhenFail.getValue() != 1) {
            this.failedHits.clear();
            return;
        }
        long fadeMs = this.notifyFade.getValue() * 1000L;
        this.failedHits.removeIf(hit -> now - (long) hit[3] >= fadeMs);
        int base = this.notifyRainbow.getValue() ? atlas.lb.render.AuraRender.rainbow(now) : 0xFF000000 | this.notifyColor.getValue();
        atlas.lb.render.AuraRender.begin(true);
        for (double[] hit : this.failedHits) {
            int fade = (int) (255 * (1.0 - (now - (long) hit[3]) / (double) fadeMs));
            int color = atlas.lb.render.AuraRender.withAlpha(base, fade);
            double x = hit[0] - vx;
            double y = hit[1] - vy;
            double z = hit[2] - vz;
            atlas.lb.render.AuraRender.box(x, y, z, x + 0.05, y + 0.05, z + 0.05, color, color);
        }
        atlas.lb.render.AuraRender.end();
    }

    private void renderRangeIndicator(double vx, double vy, double vz, float partial, long now) {
        if (!this.rangeIndicator.getValue()) {
            return;
        }
        if (this.hideWhenDead.getValue() && mc.thePlayer.getHealth() <= 0.0F
                || this.hideWhenSpectator.getValue() && mc.thePlayer.isSpectator()
                || this.hideInVehicle.getValue() && mc.thePlayer.ridingEntity != null
                || this.respectInventory.getValue() && !this.ignoreOpenInventory.getValue()
                && mc.currentScreen instanceof GuiContainer) {
            return;
        }
        EntityLivingBase target = this.targetTracker.target();
        float goal = target != null ? 1.0F : 0.0F;
        this.colorFactor = this.fadeAnimation.getValue()
                ? this.colorFactor + (goal - this.colorFactor) * this.fadeSpeed.getValue() : goal;
        float maxRange = interactionRange();
        float pulse = this.pulseAnimation.getValue()
                ? (float) Math.sin(now / 1000.0F * this.pulseSpeed.getValue() * Math.PI * 2) * this.pulseIntensity.getValue() * maxRange
                : 0.0F;
        int idle = atlas.lb.render.AuraRender.withAlpha(this.idleColor.getValue(), this.idleAlpha.getValue());
        int active = atlas.lb.render.AuraRender.withAlpha(this.activeColor.getValue(), this.activeAlpha.getValue());
        int fill;
        switch (this.indicatorColorMode.getValue()) {
            case 1:
                fill = atlas.lb.render.AuraRender.withAlpha(atlas.lb.render.AuraRender.rainbow(now), this.idleAlpha.getValue());
                break;
            case 2: {
                float distance = target == null ? maxRange : (float) RotationUtil.distanceToBox(target, mc.thePlayer.getPositionEyes(1.0F));
                fill = atlas.lb.render.AuraRender.lerp(active, idle, distance / Math.max(0.01F, maxRange));
                break;
            }
            default:
                fill = atlas.lb.render.AuraRender.lerp(idle, active, this.colorFactor);
        }
        int outline = this.indicatorOutline.getValue() ? (this.outlineAlpha.getValue() << 24) | 0xFFFFFF : 0;
        double x = mc.thePlayer.lastTickPosX + (mc.thePlayer.posX - mc.thePlayer.lastTickPosX) * partial - vx;
        double y = mc.thePlayer.lastTickPosY + (mc.thePlayer.posY - mc.thePlayer.lastTickPosY) * partial - vy + 0.001;
        double z = mc.thePlayer.lastTickPosZ + (mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ) * partial - vz;
        atlas.lb.render.AuraRender.begin(this.indicatorCovered.getValue());
        atlas.lb.render.AuraRender.disc(x, y, z, 0.0, Math.max(0.0F, maxRange + pulse), fill, outline, 90);
        if (this.wallRangeAlpha.getValue() > 0 && wallsRange() < maxRange) {
            atlas.lb.render.AuraRender.ring(x, y, z, wallsRange() + pulse * 0.5F, (this.wallRangeAlpha.getValue() << 24) | 0xFFA500, 1.5F, 90);
        }
        if (this.scanRangeAlpha.getValue() > 0) {
            atlas.lb.render.AuraRender.ring(x, y, z, scanRange(), (this.scanRangeAlpha.getValue() << 24) | 0x6464FF, 1.5F, 90);
        }
        if (this.opponentRangeAlpha.getValue() > 0 && target != null) {
            atlas.lb.render.AuraRender.ring(x, y, z, 3.0, (this.opponentRangeAlpha.getValue() << 24) | 0xFF0000, 1.5F, 90);
        }
        atlas.lb.render.AuraRender.end();
    }

    private void renderTarget(double vx, double vy, double vz, float partial, long now) {
        EntityLivingBase target = this.targetTracker.target();
        if (!this.targetRender.getValue() || target == null) {
            return;
        }
        double x = target.lastTickPosX + (target.posX - target.lastTickPosX) * partial - vx;
        double y = target.lastTickPosY + (target.posY - target.lastTickPosY) * partial - vy;
        double z = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partial - vz;
        int color = atlas.lb.render.AuraRender.withAlpha(this.targetColor.getValue(), this.targetAlpha.getValue());
        int outline = atlas.lb.render.AuraRender.withAlpha(this.targetColor.getValue(), 255);
        atlas.lb.render.AuraRender.begin(true);
        switch (this.targetRenderMode.getValue()) {
            case 1:
                atlas.lb.render.AuraRender.disc(x, y + 0.01, z, this.targetInnerRadius.getValue(), this.targetRadius.getValue(),
                        color, outline, 60);
                break;
            case 2:
                atlas.lb.render.AuraRender.ring(x, y + 0.01, z, this.targetRadius.getValue(), outline, 2.0F, 60);
                atlas.lb.render.AuraRender.glow(x, y + 0.01, z, this.targetRadius.getValue(), this.glowHeight.getValue(),
                        color, atlas.lb.render.AuraRender.withAlpha(color, 0), 60);
                break;
            case 3:
                atlas.lb.render.AuraRender.ghost(x, y, z, target.width, target.height, this.targetSize.getValue(), outline,
                        this.ghostLength.getValue(), now);
                break;
            default: {
                double half = this.targetSize.getValue() / 2.0;
                double top = y + target.height + this.targetYOffset.getValue();
                atlas.lb.render.AuraRender.box(x - half, top, z - half, x + half, top + this.targetHeight.getValue(), z + half,
                        color, outline);
            }
        }
        atlas.lb.render.AuraRender.end();
    }

    private boolean missOk() {
        return !(this.missCooldown.getValue() && ((IAccessorMinecraft) mc).getLeftClickCounter() > 0);
    }

    /** LiquidBounce canAttackNow: the criticals selection and the open-inventory rule. */
    private boolean canAttackNow(Entity target) {
        if (target != null && !criticalAllows()) {
            return false;
        }
        boolean inventory = mc.currentScreen instanceof GuiContainer;
        return !(inventory && !this.ignoreOpenInventory.getValue() && !this.simulateInventoryClosing.getValue());
    }

    private boolean criticalAllows() {
        boolean blocked = mc.thePlayer.isOnLadder() || mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isPotionActive(Potion.blindness) || mc.thePlayer.ridingEntity != null
                || mc.thePlayer.capabilities.isFlying
                || mc.theWorld.isMaterialInBB(mc.thePlayer.getEntityBoundingBox(), Material.web);
        return AuraRules.criticalAllows(this.criticals.getValue(), mc.thePlayer.onGround,
                mc.gameSettings.keyBindJump.isKeyDown(), mc.thePlayer.motionY, mc.thePlayer.fallDistance, blocked,
                landingTick());
    }

    /** The tick a jump (on ground) or the current rise would land, by vanilla's vertical motion; -1 if not within 30. */
    private int landingTick() {
        double motion = mc.thePlayer.onGround ? AuraRules.JUMP_MOTION : mc.thePlayer.motionY;
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        double offset = 0.0;
        for (int tick = 0; tick < 30; tick++) {
            AxisAlignedBB moved = box.offset(0.0, offset + motion, 0.0);
            if (motion < 0.0 && !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, moved).isEmpty()) {
                return tick;
            }
            offset += motion;
            motion = (motion - 0.08) * 0.98;
        }
        return -1;
    }

    /** LiquidBounce prepareForAttack: close the inventory, OnTick's look packets, the clicks. */
    private void prepareForAttack(Rotation rotation, atlas.lb.clicking.ClickPlan.Attempt attempt) {
        if (!this.clicker.canExecuteClickNow(missOk())) {
            return;
        }
        /* 2026-10-26, Grim PacketOrderI "type=attack releasing=true": vanilla's own tick never releases a used
           item (C07, e.g. AutoBlock Yuri letting the use key up) or uses one (C08) and then attacks -- runTick
           clicks before it uses, and does not click at all while an item is in use. The aura attacks later in
           the tick, from the movement update, so after either it waits for the next tick. */
        if (atlas.management.TickActions.any(atlas.management.TickActions.DIG | atlas.management.TickActions.USE)) {
            return;
        }
        if (blockingServerside()) {
            /* 1. Lower the sword to hit (no MultiActions in this port: blocking without AutoBlock means no hit). */
            if (!this.autoBlocking.getValue()) {
                return;
            }
            if (this.unblockMode.getValue() != 2 && stopBlocking(true) && pauseTicks() > 0) {
                this.waitTicks = this.pauseOnUnblockTicks;
                return;
            }
        } else if (mc.thePlayer.isUsingItem()) {
            /* As vanilla 1.8: no attack while another item is in use. */
            return;
        }
        if (atlas.management.TickActions.any(atlas.management.TickActions.DIG)) {
            /* Just unblocked above (PauseOnUnblock 0): the hit is next tick's, as above. */
            return;
        }
        boolean closeInventory = this.simulateInventoryClosing.getValue() && mc.currentScreen instanceof GuiInventory;
        if (closeInventory) {
            PacketUtil.sendPacket(new C0DPacketCloseWindow(mc.thePlayer.inventoryContainer.windowId));
        }
        boolean onTick = this.rotationTiming.getValue() == 2 && rotation != null;
        if (onTick) {
            PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(mc.thePlayer.posX,
                    mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ, rotation.yaw, rotation.pitch,
                    mc.thePlayer.onGround));
        }
        this.clicker.click(this::missOk, attempt);
        if (onTick) {
            PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(mc.thePlayer.posX,
                    mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
        }
        /* 2. Raise it again at once (Reblock 0). */
        if (reblockTicks() == 0) {
            startBlocking();
        }
        if (closeInventory) {
            PacketUtil.sendPacket(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
        }
    }

    private boolean vanillaPath() {
        return this.attackPath.getValue() == 1;
    }

    /**
     * AttackPath Vanilla: vanilla's left click along {@code rotation}, the look
     * the movement packet carries this tick. The crosshair is rebuilt with
     * vanilla's own getMouseOver (its 3.0 entity reach, its block reach, and
     * whatever Reach/HitBox add) from that look, clickMouse does the rest --
     * swing, then attack the entity, start digging the block, or count a miss
     * -- and the camera's crosshair is put back. A friend on the ray is not
     * clicked. Returns the entity the click hit, or null.
     */
    private Entity vanillaClick(Rotation rotation) {
        net.minecraft.client.entity.EntityPlayerSP p = mc.thePlayer;
        MovingObjectPosition saved = mc.objectMouseOver;
        Entity savedPointed = mc.pointedEntity;
        float yaw = p.rotationYaw;
        float pitch = p.rotationPitch;
        float head = p.rotationYawHead;
        float prevYaw = p.prevRotationYaw;
        float prevPitch = p.prevRotationPitch;
        float prevHead = p.prevRotationYawHead;
        p.rotationYaw = p.prevRotationYaw = rotation.yaw;
        p.rotationYawHead = p.prevRotationYawHead = rotation.yaw;
        p.rotationPitch = p.prevRotationPitch = rotation.pitch;
        try {
            mc.entityRenderer.getMouseOver(1.0F);
        } finally {
            p.rotationYaw = yaw;
            p.rotationPitch = pitch;
            p.rotationYawHead = head;
            p.prevRotationYaw = prevYaw;
            p.prevRotationPitch = prevPitch;
            p.prevRotationYawHead = prevHead;
        }
        MovingObjectPosition over = mc.objectMouseOver;
        Entity hit = over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY ? over.entityHit : null;
        try {
            if (hit instanceof EntityPlayer && atlas.util.TeamUtil.isFriend((EntityPlayer) hit)) {
                return null;
            }
            ((IAccessorMinecraft) mc).callClickMouse();
            return hit;
        } finally {
            mc.objectMouseOver = saved;
            mc.pointedEntity = savedPointed;
        }
    }

    /** A swing and the attack, as vanilla does them; KeepSprint keeps the sprint and speed the hit would cost. */
    private void attackEntity(Entity target) {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        mc.thePlayer.swingItem();
        atlas.event.EventManager.call(new atlas.events.AttackEvent(target));
        PacketUtil.sendPacket(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
        if (mc.playerController.getCurrentGameType() != WorldSettings.GameType.SPECTATOR) {
            double motionX = mc.thePlayer.motionX;
            double motionZ = mc.thePlayer.motionZ;
            boolean sprinting = mc.thePlayer.isSprinting();
            PlayerUtil.attackEntity(target);
            if (this.keepSprint.getValue()) {
                mc.thePlayer.motionX = motionX;
                mc.thePlayer.motionZ = motionZ;
                mc.thePlayer.setSprinting(sprinting);
            }
        }
    }
}
