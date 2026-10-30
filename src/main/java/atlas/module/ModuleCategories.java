package atlas.module;

import atlas.module.modules.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which category every module is in -- the one place that says so
 * (2026-09-28, sorted after LiquidBounce's categories).
 *
 * The classic menu used to hardcode five lists of its own, and Atlas read
 * those; a module missing from them was in no menu at all (seven were).
 * The classic menu now builds its frames from here, Atlas follows it as
 * before, and a unit test fails when a registered module has no entry.
 *
 * Where LiquidBounce has the module, it sits where LiquidBounce puts it
 * (Scaffold, FastPlace, Timer and ChestStealer in World; AntiBot and the
 * name hider in Misc; GhostHand and Disabler in Exploit). Since 2026-10-19
 * Combat follows OpenSlinky's combat list plus the fight modules in common
 * use (see the COMBAT entry).
 */
public final class ModuleCategories {

    private static final Map<Class<? extends Module>, Category> CATEGORY =
            new LinkedHashMap<Class<? extends Module>, Category>();

    static {
        /* 2026-10-19, owner: Combat had 44 modules, too many. It keeps what
           OpenSlinky's own combat list has, plus the fight modules in common
           use here; the rest went where OpenSlinky puts them (FakeLag and
           AutoRod with the utilities, KeepSprint with movement), the packet
           tricks to Exploit and the item helpers to Player. */
        put(Category.COMBAT,
                AimAssist.class, AutoClicker.class, AutoWeapon.class, BackTrack.class, Criticals.class,
                HitBox.class, HitSelect.class, KnockbackDelay.class, LagRange.class,
                Piercing.class, Reach.class, SprintReset.class, TimerRange.class, Velocity.class, BlockHit.class,
                KillAura.class, KillAura2.class, PlainAura.class, NoDelay.class, JumpReset.class, Wtap.class,
                MoreKB.class, KeepRange.class, AimBacktrack.class, Hitflick.class, InvulnTiming.class,
                Humanization.class);
        put(Category.PLAYER,
                MLG.class, Blink.class, NoFall.class, AntiVoid.class, Clutch.class, AutoTool.class, AutoSwap.class,
                InvManager.class, Refill.class, AntiAFK.class, AntiFireball.class, AutoAnduril.class,
                InventoryClicker.class, FastUse.class, ItemUseFix.class,
                FakeLag.class, AutoRod.class, AutoHeal.class, FastBow.class, ArmorBreaker.class, AutoRecraft.class,
                PearlSaver.class, ThrowAura.class, ProjectileAimBot.class);
        put(Category.MOVEMENT,
                Fly.class, Speed.class, LongJump.class, Jesus.class, NoSlow.class, NoItemRelease.class, SafeWalk.class, AntiBounce.class, Eagle.class, InvWalk.class, TargetStrafe.class, MoveFix.class,
                Stasis.class, Parkour.class, NoWeb.class, Tower.class,
                FastAccel.class, InstantStop.class, NullMove.class, Strafe.class, KeepSprint.class);
        put(Category.RENDER,
                BlockESP.class, ESP.class, Chams.class, NameTags.class, Tracers.class,
                TargetESP.class, TargetOrbit.class, TargetHUD.class, ItemESP.class, Xray.class,
                ViewClip.class, Trajectories.class, BlockOverlay.class,
                BreakProgress.class, Indicators.class, Radar.class, LatencyCrosshair.class,
                TeamHealthDisplay.class, RenderFixes.class,
                AntiDebuff.class, BedPlates.class, TNTTimer.class, DamageTags.class, ItemTags.class,
                EntityCulling.class, Freecam.class, FallIndicator.class, Pointers.class);
        put(Category.WORLD,
                Scaffold.class, AutoBlockIn.class, AutoBedDef.class, AutoHeadHitter.class, BedNuker.class,
                ChestAura.class, ChestStealer.class, FastPlace.class, SpeedMine.class, Timer.class,
                AntiObbyTrap.class, AutoPlace.class);
        put(Category.MISC,
                AutoMessage.class, AntiBot.class, TargetFilter.class, MCF.class, NickHider.class, Spammer.class, AutoAuth.class,
                AutoHypixel.class, BedwarUtils.class, BedTracker.class, LightningTracker.class,
                ESPDetector.class, AntiCheat.class, AntiObfuscate.class, MouseRawInput.class,
                ResourceSpoofer.class, NoRotate.class, ExploitFixer.class,
                AutoReconnect.class, StaffDetector.class,
                SkyWars.class, MurderMystery.class, PingFix.class);
        put(Category.EXPLOIT,
                Disabler.class, GhostHand.class, ClientSpoofer.class,
                PingSpoof.class, ServerLag.class, TickBase.class, Freeze.class, SmartBlinker.class, ResetVL.class);
        put(Category.CLIENT,
                ClickGUIModule.class, GuiModule.class, FlagDetector.class,
                FlagResponder.class, HitCheck.class, FightLog.class, Debug.class, Logs.class, PacketLogger.class,
                ServerFingerprint.class, AutoTune.class, Adaptive.class, ServerProfiles.class,
                LatencyGovernor.class, Panic.class, Rotations.class, Notifications.class, ScriptModule.class);
        put(Category.LEGIT,
                Accessories.class, Ambience.class, Animations.class, ArmorHUD.class, AutoRespawn.class, Capes.class,
                DynamicIsland.class, EffectsHUD.class, FPScounter.class, FreeLook.class, FullBright.class,
                HUD.class, Hotbar.class,
                ItemPhysics.class, LegitHUD.class, NoHurtCam.class, Sprint.class, Statistics.class, PlayTracker.class,
                WaterMark.class, KeyStrokes.class, PotionHUD.class, InventoryHUD.class,
                PlayerList.class, ClosestPlayerHUD.class, FKCounter.class, KillEffect.class);
        /* In the order they appear on screen, not sorted: the order is the point. */
        put(Category.THEME,
                Theme.class, PlayerColors.class, TracerColors.class, TargetColors.class, BacktrackColors.class,
                BedColors.class, ChestColors.class, ItemColors.class, BlockColors.class, ProjectileColors.class,
                InterfaceColors.class, ChamsColors.class, NameTagColors.class, WidgetColors.class,
                EffectColors.class);
    }

    /* Modules being tested (2026-10-16): registered and categorised, so the
       table stays complete, but left out of the menus (modulesIn) -- the
       owner tests them with .toggle / .bind before they are shown. */
    private static final java.util.Set<Class<? extends Module>> TESTING =
            new java.util.HashSet<Class<? extends Module>>(java.util.Arrays.<Class<? extends Module>>asList(
                    KillAura2.class,
                    /* The old "ClickGui" opener (2026-10-17): a second entry
                       beside ClickGUI that does the same thing, listed twice
                       in the Client column. Still bindable with .bind. */
                    GuiModule.class));

    private ModuleCategories() {
    }

    /** Whether a module is kept out of the menus while it is tested. */
    public static boolean testing(Class<?> type) {
        return TESTING.contains(type);
    }

    @SafeVarargs
    private static void put(Category category, Class<? extends Module>... types) {
        for (Class<? extends Module> type : types) {
            Category previous = CATEGORY.put(type, category);
            if (previous != null) {
                throw new IllegalStateException(type.getSimpleName() + " is in " + previous + " and " + category);
            }
        }
    }

    /** The module's category; null for a module this table does not know. */
    public static Category of(Class<?> type) {
        return CATEGORY.get(type);
    }

    /** Every module type in a category, in the order listed. */
    public static List<Class<? extends Module>> typesIn(Category category) {
        List<Class<? extends Module>> out = new ArrayList<Class<? extends Module>>();
        for (Map.Entry<Class<? extends Module>, Category> entry : CATEGORY.entrySet()) {
            if (entry.getValue() == category) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * The registered modules in a category: sorted by name, except Theme,
     * which keeps its listed order.
     */
    public static List<Module> modulesIn(Category category, Map<Class<?>, Module> registered) {
        List<Module> out = new ArrayList<Module>();
        for (Class<? extends Module> type : typesIn(category)) {
            Module module = registered.get(type);
            if (module != null && !TESTING.contains(type)) {
                out.add(module);
            }
        }
        if (category != Category.THEME) {
            out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        return out;
    }

    /** Every module type with a category. */
    public static Map<Class<? extends Module>, Category> all() {
        return Collections.unmodifiableMap(CATEGORY);
    }
}
